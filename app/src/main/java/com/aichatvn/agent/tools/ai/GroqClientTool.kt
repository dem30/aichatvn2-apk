package com.aichatvn.agent.tools.ai

import android.content.Context
import android.util.Base64
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.data.GroqApiKeyProvider

data class GroqRateLimitInfo(
    val model: String,
    val limitRequests: Int? = null,
    val remainingRequests: Int? = null,
    val limitTokens: Int? = null,
    val remainingTokens: Int? = null,
    val isRateLimited: Boolean = false,
    val cooldownUntilMillis: Long? = null,
    val capturedAtMillis: Long = System.currentTimeMillis()
)

data class PromptLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val caller: String,
    val model: String,
    val prompt: String,
    val response: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val sentAt: Long = System.currentTimeMillis()
)

private data class GroqUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

private data class GroqParsedResponse(
    val text: String,
    val usage: GroqUsage?,
    // ✅ MỚI: cờ có cấu trúc đánh dấu đây là nhánh lỗi (HTTP lỗi, parse lỗi, response rỗng...)
    // thay vì để caller phải đoán qua startsWith()/contains() trên câu chữ tiếng Việt — cách cũ
    // đã xác nhận lọt ít nhất 2 case (401 sai key, parse response rỗng/hỏng).
    val isError: Boolean = false
)

class GroqRoutingException(message: String) : Exception(message)

@Singleton
class GroqClientTool @Inject constructor(
    private val apiKeyProvider: GroqApiKeyProvider,
    private val logger: Logger,
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider
) {
    companion object {
        // ✅ MỚI: sentinel cố định duy nhất cho MỌI đường lỗi mà analyzeImage() có thể trả ra.
        // Ký tự \u0000 đầu để không bao giờ trùng với văn bản AI thật (kể cả tiếng Anh/tiếng Việt
        // đổi sau này). CameraSkill.isApiError chỉ cần startsWith(AI_ERROR_PREFIX), không cần
        // đoán từng chuỗi lỗi cụ thể nữa.
        const val AI_ERROR_PREFIX = "\u0000AI_ERR:"

        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        private val DEFAULT_MODEL_TEXT    get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MODEL_TEXT)
        private val DEFAULT_MODEL_VISION  get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MODEL_VISION)
        private val DEFAULT_MODEL_ROUTER  get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MODEL_ROUTER)
        private val DEFAULT_MAX_TOKENS_CHAT   get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MAX_TOKENS_CHAT).toInt()
        private val DEFAULT_MAX_TOKENS_VISION get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MAX_TOKENS_VISION).toInt()
        private val DEFAULT_MAX_TOKENS_ROUTER get() = AppConfigDefaults.defaultOf(AppConfigDefaults.GROQ_MAX_TOKENS_ROUTER).toInt()

        private const val SAFE_FALLBACK_INTENT =
            """{"plugin":"chat","action":"none","params":{}}"""

        private const val PREFS_NAME = "groq_rate_limit_prefs"
        private val PERSISTED_MODELS by lazy {
            listOf(DEFAULT_MODEL_TEXT, DEFAULT_MODEL_VISION, DEFAULT_MODEL_ROUTER)
        }

        private const val PROMPT_LOG_SIZE = 10
        private const val PROMPT_LOG_MAX_CHARS = 20_000

        
      
      
      
      
      // ✅ Cấu trúc prompt Vision phẳng thế hệ mới, tiết kiệm tối đa token và cực kỳ ổn định
        private const val STRUCTURED_VISION_SUFFIX = """

🚨 BẮT BUỘC TRẢ VỀ JSON THÔ THEO ĐỊNH DẠNG SAU, KHÔNG GIẢI THÍCH THÊM, KHÔNG BỌC TRONG MARKDOWN ```json:
{
  "state": "normal hoặc suspicious",
  "confidence": 0.0 đến 1.0,
  "objects": ["mảng chuỗi phẳng chứa các đối tượng phát hiện được, bao gồm cả nhóm phổ quát (người, đồ vật, động vật, thực vật) và tên gọi cụ thể của chúng. Ví dụ: 'động vật', 'con mèo', 'thực vật', 'quả mâm xôi'"],
  "description": "mô tả tóm tắt ngắn gọn bằng tiếng Việt về toàn cảnh bức hình",
  "question_classification": {
    "has_person": true hoặc false,
    "has_vehicle": true hoặc false,
    "has_animal": true hoặc false
  }
}"""




      

        private fun isReasoningModel(model: String): Boolean =
            model.startsWith("qwen/qwen3", ignoreCase = true)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _rateLimitByModel =
        MutableStateFlow<Map<String, GroqRateLimitInfo>>(loadPersistedRateLimits())
    val rateLimitByModel: StateFlow<Map<String, GroqRateLimitInfo>> = _rateLimitByModel.asStateFlow()

    private val _chatRateLimitInfo = MutableStateFlow(
        _rateLimitByModel.value[DEFAULT_MODEL_TEXT] ?: _rateLimitByModel.value[DEFAULT_MODEL_VISION]
    )
    val rateLimitInfo: StateFlow<GroqRateLimitInfo?> = _chatRateLimitInfo.asStateFlow()

    private val _routerRateLimitInfo = MutableStateFlow(_rateLimitByModel.value[DEFAULT_MODEL_ROUTER])
    val routerRateLimitInfo: StateFlow<GroqRateLimitInfo?> = _routerRateLimitInfo.asStateFlow()

    private val _promptLog = MutableStateFlow<List<PromptLogEntry>>(emptyList())
    val promptLog: StateFlow<List<PromptLogEntry>> = _promptLog.asStateFlow()

    private fun logPrompt(caller: String, model: String, prompt: String): String {
        val entry = PromptLogEntry(
            caller = caller,
            model = model,
            prompt = prompt.take(PROMPT_LOG_MAX_CHARS)
        )
        val current = _promptLog.value.toMutableList()
        current.add(0, entry)
        _promptLog.value = current.take(PROMPT_LOG_SIZE)
        return entry.id
    }

    private fun updatePromptLogResult(id: String, responseText: String?, usage: GroqUsage?) {
        val current = _promptLog.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx == -1) return
        current[idx] = current[idx].copy(
            response = responseText?.take(PROMPT_LOG_MAX_CHARS),
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            totalTokens = usage?.totalTokens
        )
        _promptLog.value = current
    }

    private fun parseUsage(bodyStr: String): GroqUsage? = try {
        val usageObj = JSONObject(bodyStr).optJSONObject("usage")
        if (usageObj == null) null
        else GroqUsage(
            promptTokens = if (usageObj.has("prompt_tokens")) usageObj.optInt("prompt_tokens") else null,
            completionTokens = if (usageObj.has("completion_tokens")) usageObj.optInt("completion_tokens") else null,
            totalTokens = if (usageObj.has("total_tokens")) usageObj.optInt("total_tokens") else null
        )
    } catch (e: Exception) {
        null
    }

    private fun sanitizeMessagesForLog(messages: JSONArray): String {
        val sanitized = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role")
            val content = msg.opt("content")
            val newMsg = JSONObject().put("role", role)
            when (content) {
                is JSONArray -> {
                    val newContent = JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "image_url") {
                            val url = part.optJSONObject("image_url")?.optString("url") ?: ""
                            val sizeKb = url.length / 1024
                            newContent.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", "[base64 image omitted, ~${sizeKb}KB]")
                            })
                        } else {
                            newContent.put(part)
                        }
                    }
                    newMsg.put("content", newContent)
                }
                else -> newMsg.put("content", content ?: "")
            }
            sanitized.put(newMsg)
        }
        return try {
            sanitized.toString(2)
        } catch (e: Exception) {
            sanitized.toString()
        }
    }

    private fun requestBodyForLog(model: String, messages: JSONArray, extra: Map<String, Any?> = emptyMap()): String {
        return buildString {
            append("model: $model\n")
            extra.forEach { (k, v) -> if (v != null) append("$k: $v\n") }
            append("messages:\n")
            append(sanitizeMessagesForLog(messages))
        }
    }

    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key !in PERSISTED_MODELS) return@OnSharedPreferenceChangeListener
            val raw = prefs.getString(key, null) ?: return@OnSharedPreferenceChangeListener
            val info = parseRateLimitJson(raw) ?: return@OnSharedPreferenceChangeListener

            _rateLimitByModel.update { it + (key to info) }
            when (key) {
                DEFAULT_MODEL_TEXT, DEFAULT_MODEL_VISION -> _chatRateLimitInfo.value = info
                DEFAULT_MODEL_ROUTER -> _routerRateLimitInfo.value = info
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private suspend fun modelText()   = configProvider.getString(AppConfigDefaults.GROQ_MODEL_TEXT, DEFAULT_MODEL_TEXT)
    private suspend fun modelVision() = configProvider.getString(AppConfigDefaults.GROQ_MODEL_VISION, DEFAULT_MODEL_VISION)
    private suspend fun modelRouter() = configProvider.getString(AppConfigDefaults.GROQ_MODEL_ROUTER, DEFAULT_MODEL_ROUTER)
    private suspend fun maxTokensChat()   = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_CHAT, DEFAULT_MAX_TOKENS_CHAT)
    private suspend fun maxTokensVision() = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_VISION, DEFAULT_MAX_TOKENS_VISION)
    private suspend fun maxTokensRouter() = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_ROUTER, DEFAULT_MAX_TOKENS_ROUTER)

    private fun tokenBudgetInstruction(maxTokens: Int): String =
        "\n\n⚠️ GIỚI HẠN ĐỘ DÀI: Trả lời trong phạm vi tối đa khoảng $maxTokens token. " +
        "Hãy tự liệu để hoàn chỉnh câu trả lời (không bỏ dở giữa chừng, không bỏ dở JSON nếu có) " +
        "trong giới hạn này — ưu tiên ngắn gọn, súc tích hơn là dài dòng rồi bị cắt cụt."

    private fun loadPersistedRateLimits(): Map<String, GroqRateLimitInfo> {
        val map = mutableMapOf<String, GroqRateLimitInfo>()
        for (model in PERSISTED_MODELS) {
            val raw = prefs.getString(model, null) ?: continue
            parseRateLimitJson(raw)?.let { map[model] = it }
        }
        return map
    }

    private fun persistRateLimit(info: GroqRateLimitInfo) {
        try {
            prefs.edit().putString(info.model, rateLimitToJson(info)).apply()
        } catch (e: Exception) {
            logger.e("GroqClientTool", "persistRateLimit error: ${e.message}", e)
        }
    }

    private fun rateLimitToJson(info: GroqRateLimitInfo): String = JSONObject().apply {
        put("model", info.model)
        put("limitRequests", info.limitRequests ?: JSONObject.NULL)
        put("remainingRequests", info.remainingRequests ?: JSONObject.NULL)
        put("limitTokens", info.limitTokens ?: JSONObject.NULL)
        put("remainingTokens", info.remainingTokens ?: JSONObject.NULL)
        put("isRateLimited", info.isRateLimited)
        put("cooldownUntilMillis", info.cooldownUntilMillis ?: JSONObject.NULL)
        put("capturedAtMillis", info.capturedAtMillis)
    }.toString()

    private fun parseRateLimitJson(raw: String): GroqRateLimitInfo? = try {
        val o = JSONObject(raw)
        GroqRateLimitInfo(
            model = o.getString("model"),
            limitRequests = if (o.isNull("limitRequests")) null else o.getInt("limitRequests"),
            remainingRequests = if (o.isNull("remainingRequests")) null else o.getInt("remainingRequests"),
            limitTokens = if (o.isNull("limitTokens")) null else o.getInt("limitTokens"),
            remainingTokens = if (o.isNull("remainingTokens")) null else o.getInt("remainingTokens"),
            isRateLimited = o.optBoolean("isRateLimited", false),
            cooldownUntilMillis = if (o.isNull("cooldownUntilMillis")) null else o.getLong("cooldownUntilMillis"),
            capturedAtMillis = o.optLong("capturedAtMillis", System.currentTimeMillis())
        )
    } catch (e: Exception) { null }

    private fun captureRateLimit(response: okhttp3.Response, model: String) {
        val remainingReq = response.header("x-ratelimit-remaining-requests")?.toIntOrNull()
        val limitReq     = response.header("x-ratelimit-limit-requests")?.toIntOrNull()
        val remainingTok = response.header("x-ratelimit-remaining-tokens")?.toIntOrNull()
        val limitTok     = response.header("x-ratelimit-limit-tokens")?.toIntOrNull()
        val retryAfterSeconds = parseDurationToSeconds(response.header("retry-after"))
        val isLimited = response.code == 429

        if (remainingReq == null && remainingTok == null && !isLimited) return

        val now = System.currentTimeMillis()
        val info = GroqRateLimitInfo(
            model = model,
            limitRequests = limitReq,
            remainingRequests = remainingReq,
            limitTokens = limitTok,
            remainingTokens = remainingTok,
            isRateLimited = isLimited,
            cooldownUntilMillis = if (isLimited && retryAfterSeconds != null) {
                now + (retryAfterSeconds * 1000).toLong()
            } else null,
            capturedAtMillis = now
        )

        _rateLimitByModel.update { it + (model to info) }
        
        val chatModel   = _rateLimitByModel.value.keys.firstOrNull { it == DEFAULT_MODEL_TEXT || it == DEFAULT_MODEL_VISION }
        val routerModel = _rateLimitByModel.value.keys.firstOrNull { it == DEFAULT_MODEL_ROUTER }
        if (model == chatModel || (chatModel == null && (model == DEFAULT_MODEL_TEXT || model == DEFAULT_MODEL_VISION))) {
            _chatRateLimitInfo.value = info
        }
        if (model == routerModel || model == DEFAULT_MODEL_ROUTER) {
            _routerRateLimitInfo.value = info
        }
        persistRateLimit(info)
    }

    private fun parseDurationToSeconds(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { return it }
        var total = 0.0
        var matched = false
        Regex("([\\d.]+)(ms|s|m|h)").findAll(raw).forEach { m ->
            matched = true
            val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
            total += when (m.groupValues[2]) {
                "ms" -> value / 1000.0
                "s"  -> value
                "m"  -> value * 60
                "h"  -> value * 3600
                else -> 0.0
            }
        }
        return if (matched) total else null
    }

    // ─── CHAT ────────────────────────────────────────────────────────

    suspend fun chat(
        message: String,
        extraContext: String = "",
        history: List<Map<String, String>> = emptyList(),
        imageUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: return@withContext "⚠️ Chưa cấu hình Groq API key. Vào Settings để nhập key."

        try {
            val model     = if (imageUrl != null) modelVision() else modelText()
            val maxTokens = maxTokensChat()

            val messages = JSONArray()
            val budgetNote = tokenBudgetInstruction(maxTokens)
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", if (extraContext.isNotBlank()) extraContext + budgetNote else budgetNote.trim())
            })
            for (h in history.takeLast(10)) {
                messages.put(JSONObject().apply {
                    put("role", h["role"] ?: "user")
                    put("content", h["content"] ?: "")
                })
            }

            val userContent: Any = if (imageUrl != null) {
                JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", message) })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", imageUrl) })
                    })
                }
            } else {
                message
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

            val logId = logPrompt(
                "chat",
                model,
                requestBodyForLog(model, messages, mapOf("max_tokens" to maxTokens))
            )

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
                if (isReasoningModel(model)) {
                    put("reasoning_effort", "none")
                    put("reasoning_format", "hidden")
                }
            }.toString()

            val parsed = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                captureRateLimit(response, model)
                parseResponse(response, "chat")
            }

            updatePromptLogResult(logId, parsed.text, parsed.usage)
            parsed.text

        } catch (e: Exception) {
            logger.e("GroqClientTool", "chat error: ${e.message}", e)
            "Xin lỗi, không thể kết nối AI lúc này."
        }
    }

    // ─── ROUTE INTENT ────────────────────────────────────────────────

    suspend fun routeIntent(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: throw GroqRoutingException("Chưa cấu hình Groq API key")

        val model     = modelRouter()
        val maxTokens = maxTokensRouter()

        val promptWithBudget = prompt + tokenBudgetInstruction(maxTokens)

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", promptWithBudget) })
        }

        val logId = logPrompt(
            "routeIntent",
            model,
            requestBodyForLog(model, messages, mapOf("max_tokens" to maxTokens, "temperature" to 0))
        )

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", 0)
            if (isReasoningModel(model)) {
                put("reasoning_effort", "none")
                put("reasoning_format", "hidden")
            }
        }.toString()

        val resultPair = try {
            client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                captureRateLimit(response, model)
                
                // ✅ SỬA: routeIntent chính thức sử dụng hàm parseResponse chung của hệ thống.
                // Loại bỏ thẻ suy luận <think> nếu model router tự sinh ra, chống trả về chuỗi rỗng trước khi router parse JSON.
                // ✅ MỚI: dùng parsed.isError thay vì đoán prefix "❌"/"⚠️"/"Lỗi API:" — cách cũ bị
                // lọt đúng case parse-response-rỗng/hỏng (dòng trả "Không thể đọc phản hồi từ AI...")
                // vì chuỗi đó không khớp bất kỳ điều kiện startsWith nào, y hệt bug đã tìm thấy ở CameraSkill.
                val parsed = parseResponse(response, "routeIntent")
                if (parsed.isError) {
                    throw GroqRoutingException("Router error: ${parsed.text}")
                }
                Pair(parsed.text, parsed.usage)
            }
        } catch (e: GroqRoutingException) {
            throw e
        } catch (e: Exception) {
            logger.e("GroqClientTool", "routeIntent network or parse error: ${e.message}", e)
            updatePromptLogResult(logId, "❌ Parse error: ${e.message}", null)
            throw GroqRoutingException("Lỗi mạng/phân giải khi gọi router: ${e.message}")
        }

        val resultText = resultPair.first
        val usage = resultPair.second

        if (resultText.isBlank()) {
            updatePromptLogResult(logId, "⚠️ Empty content -> fallback", usage)
            return@withContext SAFE_FALLBACK_INTENT
        }

        updatePromptLogResult(logId, resultText, usage)
        resultText
    }

    // ─── ANALYZE IMAGE ───────────────────────────────────────────────

    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: return@withContext "$AI_ERROR_PREFIX⚠️ Chưa cấu hình Groq API key."

        try {
            val model     = modelVision()
            val maxTokens = maxTokensVision()

            val base64  = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            val structuredPrompt = prompt + STRUCTURED_VISION_SUFFIX + tokenBudgetInstruction(maxTokens)

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", structuredPrompt) })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", dataUrl) })
                        })
                    })
                })
            }

            val logId = logPrompt(
                "analyzeImage",
                model,
                requestBodyForLog(model, messages, mapOf("max_tokens" to maxTokens))
            )

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
                if (isReasoningModel(model)) {
                    put("reasoning_effort", "none")
                    put("reasoning_format", "hidden")
                }
            }.toString()

            val parsed = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                captureRateLimit(response, model)
                parseResponse(response, "analyzeImage")
            }

            updatePromptLogResult(logId, parsed.text, parsed.usage)
            // ✅ MỚI: chỉ analyzeImage() gắn sentinel — chat()/routeIntent() vẫn nhận parsed.text
            // sạch (không prefix) vì chat() hiển thị trực tiếp cho người dùng, routeIntent() đã
            // chuyển sang dùng parsed.isError thay vì đoán chuỗi.
            if (parsed.isError) "$AI_ERROR_PREFIX${parsed.text}" else parsed.text

        } catch (e: Exception) {
            logger.e("GroqClientTool", "analyzeImage error: ${e.message}", e)
            "${AI_ERROR_PREFIX}Không thể phân tích ảnh lúc này."
        }
    }

    // ─── PARSE ───────────────────────────────────────────────────────

    private fun parseResponse(response: okhttp3.Response, caller: String): GroqParsedResponse {
        val bodyStr = response.body?.string() ?: ""
        val usage = parseUsage(bodyStr)
        if (!response.isSuccessful) {
            logger.e("GroqClientTool", "$caller HTTP ${response.code}: $bodyStr")
            val errText = when (response.code) {
                401  -> "❌ Groq API key không hợp lệ (401). Kiểm tra lại key trong Settings."
                429  -> "⚠️ Groq rate limit — thử lại sau ít phút."
                else -> "Lỗi API: ${response.code}"
            }
            return GroqParsedResponse(errText, usage, isError = true)
        }
        var parseFailed = false
        val text = try {
            val raw = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val hasOpenThink = raw.contains("<think>", ignoreCase = true)
            val hasCloseThink = raw.contains("</think>", ignoreCase = true)
            if (hasOpenThink && !hasCloseThink) {
                logger.e("GroqClientTool", "$caller bị cắt cụt giữa khối <think> (max_tokens không đủ), body=$bodyStr")
                throw IllegalStateException("Response bị cắt cụt giữa khối suy luận (max_tokens không đủ)")
            }

            val stripped = raw.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "")
                .trim()

            // ✅ SỬA: Siết chặt chuỗi rỗng sau khi bóc tách khối suy luận để đảm bảo
            // các lớp gọi API luôn nhận được dữ liệu thô hợp lệ hoặc lỗi rõ ràng thay vì bong bóng chat trống.
            if (stripped.isBlank()) {
                logger.e("GroqClientTool", "$caller: nội dung rỗng sau khi bóc <think> (model đã dùng hết token để suy luận), body=$bodyStr")
                throw IllegalStateException("Nội dung trả về rỗng sau khi bóc khối suy luận")
            }

            stripped
        } catch (e: Exception) {
            logger.e("GroqClientTool", "$caller parse error: ${e.message}, body=$bodyStr")
            parseFailed = true
            "Không thể đọc phản hồi từ AI. Vui lòng thử lại."
        }
        return GroqParsedResponse(text, usage, isError = parseFailed)
    }
}