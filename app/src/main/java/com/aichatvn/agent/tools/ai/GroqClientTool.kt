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

// ─────────────────────────── DATA CLASSES ────────────────────────────

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

/**
 * Một entry trong log prompt gần nhất.
 *
 * caller  : "chat" | "routeIntent" | "analyzeImage"
 * model   : tên model Groq thực tế đã dùng
 * prompt  : nội dung prompt (message user gửi lên, đã trim; system context được gộp vào nếu có)
 * sentAt  : epoch millis khi gửi request
 */
data class PromptLogEntry(
    val caller: String,
    val model: String,
    val prompt: String,
    val sentAt: Long = System.currentTimeMillis()
)

// ─────────────────────────── TOOL ────────────────────────────────────

/**
 * GroqClientTool v4
 *
 * Thay đổi so với v3:
 *  - Inject AppConfigProvider để đọc model / max_tokens động từ DB thay vì hardcode.
 *  - Thêm _promptLog: StateFlow<List<PromptLogEntry>> — lưu 5 prompt gần nhất
 *    (chat + routeIntent + analyzeImage), dùng cho UI Settings hiển thị debug.
 *  - Các model/token constants cũ giữ lại làm FALLBACK nếu DB chưa có giá trị.
 */
@Singleton
class GroqClientTool @Inject constructor(
    private val apiKeyProvider: GroqApiKeyProvider,
    private val logger: Logger,
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider
) {
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        // Fallback — chỉ dùng khi DB chưa seed xong
        private const val DEFAULT_MODEL_TEXT    = "openai/gpt-oss-120b"
        private const val DEFAULT_MODEL_VISION  = "meta-llama/llama-4-scout-17b-16e-instruct"
        private const val DEFAULT_MODEL_ROUTER  = "openai/gpt-oss-20b"
        private const val DEFAULT_MAX_TOKENS_CHAT   = 500
        private const val DEFAULT_MAX_TOKENS_VISION = 200
        private const val DEFAULT_MAX_TOKENS_ROUTER = 1000

        private const val SAFE_FALLBACK_INTENT =
            """{"plugin":"chat","action":"none","params":{}}"""

        private const val PREFS_NAME = "groq_rate_limit_prefs"
        private val PERSISTED_MODELS = listOf(
            DEFAULT_MODEL_TEXT, DEFAULT_MODEL_VISION, DEFAULT_MODEL_ROUTER
        )

        private const val PROMPT_LOG_SIZE = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Rate-limit ────────────────────────────────────────────────────
    private val _rateLimitByModel =
        MutableStateFlow<Map<String, GroqRateLimitInfo>>(loadPersistedRateLimits())
    val rateLimitByModel: StateFlow<Map<String, GroqRateLimitInfo>> = _rateLimitByModel.asStateFlow()

    private val _chatRateLimitInfo = MutableStateFlow(
        _rateLimitByModel.value[DEFAULT_MODEL_TEXT] ?: _rateLimitByModel.value[DEFAULT_MODEL_VISION]
    )
    val rateLimitInfo: StateFlow<GroqRateLimitInfo?> = _chatRateLimitInfo.asStateFlow()

    private val _routerRateLimitInfo = MutableStateFlow(_rateLimitByModel.value[DEFAULT_MODEL_ROUTER])
    val routerRateLimitInfo: StateFlow<GroqRateLimitInfo?> = _routerRateLimitInfo.asStateFlow()

    // ── Prompt log (5 entries gần nhất) ───────────────────────────────
    private val _promptLog = MutableStateFlow<List<PromptLogEntry>>(emptyList())
    /**
     * StateFlow chứa tối đa 5 prompt gần nhất gửi đến Groq.
     * UI Settings bind trực tiếp để hiển thị debug box.
     */
    val promptLog: StateFlow<List<PromptLogEntry>> = _promptLog.asStateFlow()

    private fun logPrompt(caller: String, model: String, prompt: String) {
        val entry = PromptLogEntry(caller = caller, model = model, prompt = prompt.take(2000))
        val current = _promptLog.value.toMutableList()
        current.add(0, entry)
        _promptLog.value = current.take(PROMPT_LOG_SIZE)
    }

    // ── SharedPrefs listener (multi-instance sync) ─────────────────────
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key !in PERSISTED_MODELS) return@OnSharedPreferenceChangeListener
            val raw = prefs.getString(key, null) ?: return@OnSharedPreferenceChangeListener
            val info = parseRateLimitJson(raw) ?: return@OnSharedPreferenceChangeListener

            _rateLimitByModel.value = _rateLimitByModel.value + (key to info)
            when (key) {
                DEFAULT_MODEL_TEXT, DEFAULT_MODEL_VISION -> _chatRateLimitInfo.value = info
                DEFAULT_MODEL_ROUTER -> _routerRateLimitInfo.value = info
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── Config helpers ────────────────────────────────────────────────

    private suspend fun modelText()   = configProvider.getString(AppConfigDefaults.GROQ_MODEL_TEXT, DEFAULT_MODEL_TEXT)
    private suspend fun modelVision() = configProvider.getString(AppConfigDefaults.GROQ_MODEL_VISION, DEFAULT_MODEL_VISION)
    private suspend fun modelRouter() = configProvider.getString(AppConfigDefaults.GROQ_MODEL_ROUTER, DEFAULT_MODEL_ROUTER)
    private suspend fun maxTokensChat()   = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_CHAT, DEFAULT_MAX_TOKENS_CHAT)
    private suspend fun maxTokensVision() = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_VISION, DEFAULT_MAX_TOKENS_VISION)
    private suspend fun maxTokensRouter() = configProvider.getInt(AppConfigDefaults.GROQ_MAX_TOKENS_ROUTER, DEFAULT_MAX_TOKENS_ROUTER)

    // ── Rate-limit persistence ────────────────────────────────────────

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

        _rateLimitByModel.value = _rateLimitByModel.value + (model to info)
        // Cập nhật đúng StateFlow theo loại model
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

    // ─────────────────────────── CHAT ────────────────────────────────

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
            if (extraContext.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", extraContext)
                })
            }
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

            // Log prompt
            logPrompt("chat", model, if (extraContext.isNotBlank()) "[system] $extraContext\n[user] $message" else message)

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            captureRateLimit(response, model)
            parseResponse(response, "chat")

        } catch (e: Exception) {
            logger.e("GroqClientTool", "chat error: ${e.message}", e)
            "Xin lỗi, không thể kết nối AI lúc này."
        }
    }

    // ─────────────────────────── ROUTE INTENT ────────────────────────

    suspend fun routeIntent(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey() ?: return@withContext SAFE_FALLBACK_INTENT

        try {
            val model     = modelRouter()
            val maxTokens = maxTokensRouter()

            logPrompt("routeIntent", model, prompt)

            val messages = JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            }
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
                put("temperature", 0)
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            captureRateLimit(response, model)
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                logger.e("GroqClientTool", "routeIntent HTTP ${response.code}: $bodyStr")
                return@withContext SAFE_FALLBACK_INTENT
            }
            if (bodyStr.isBlank()) return@withContext SAFE_FALLBACK_INTENT

            JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .takeIf { it.isNotBlank() } ?: SAFE_FALLBACK_INTENT

        } catch (e: Exception) {
            logger.e("GroqClientTool", "routeIntent error: ${e.message}", e)
            SAFE_FALLBACK_INTENT
        }
    }

    // ─────────────────────────── ANALYZE IMAGE ───────────────────────

    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: return@withContext "⚠️ Chưa cấu hình Groq API key."

        try {
            val model     = modelVision()
            val maxTokens = maxTokensVision()

            logPrompt("analyzeImage", model, prompt)

            val base64  = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", dataUrl) })
                        })
                    })
                })
            }
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxTokens)
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            captureRateLimit(response, model)
            parseResponse(response, "analyzeImage")

        } catch (e: Exception) {
            logger.e("GroqClientTool", "analyzeImage error: ${e.message}", e)
            "Không thể phân tích ảnh lúc này."
        }
    }

    // ─────────────────────────── PARSE ───────────────────────────────

    private fun parseResponse(response: okhttp3.Response, caller: String): String {
        val bodyStr = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            logger.e("GroqClientTool", "$caller HTTP ${response.code}: $bodyStr")
            return when (response.code) {
                401  -> "❌ Groq API key không hợp lệ (401). Kiểm tra lại key trong Settings."
                429  -> "⚠️ Groq rate limit — thử lại sau ít phút."
                else -> "Lỗi API: ${response.code}"
            }
        }
        return try {
            val raw = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            // Strip <think>...</think> — Qwen3, DeepSeek R1 và các model thinking mode
            raw.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE)), "")
                .trim()
        } catch (e: Exception) {
            logger.e("GroqClientTool", "$caller parse error: ${e.message}, body=$bodyStr")
            "Không thể đọc phản hồi từ AI."
        }
    }
}