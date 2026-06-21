package com.aichatvn.agent.tools.ai

import android.content.Context
import android.util.Base64
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

/**
 * Snapshot rate-limit Groq cho MỘT model cụ thể, đọc từ response header của lượt gọi
 * GẦN NHẤT tới model đó (header chuẩn OpenAI-compatible: x-ratelimit-*, retry-after).
 *
 * - remaining / limit: thông tin quota còn lại, null nếu Groq không trả header đó.
 * - isRateLimited + cooldownUntilMillis: CHỈ có giá trị khi lượt gọi gần nhất bị HTTP 429
 *   thật sự (lấy từ header retry-after). KHÔNG dùng x-ratelimit-reset-requests cho việc
 *   này vì header đó xuất hiện trên MỌI response kể cả thành công (200) - nó chỉ cho biết
 *   "cửa sổ quota tiếp theo bắt đầu lúc nào", không có nghĩa là đang bị chặn. Trộn 2 khái
 *   niệm này từng làm UI hiện cooldown ngay cả khi chat hoàn toàn bình thường.
 * - cooldownUntilMillis là mốc thời gian TUYỆT ĐỐI (epoch millis), không phải số giây còn
 *   lại tính từ lúc capture -> UI tính lại (cooldownUntilMillis - now) mỗi lần hiển thị,
 *   nên luôn đúng kể cả khi app vừa được mở lại sau khi bị tắt/đưa vào background.
 */
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
 * GroqClientTool
 *
 * ĐIỂM QUAN TRỌNG — root cause của 401:
 * KHÔNG inject API key qua constructor hay đọc từ StateFlow.value lúc khởi tạo.
 * DataStore chưa load xong khi Hilt inject -> key vẫn là "" -> "Bearer " -> 401.
 * GIẢI PHÁP: inject GroqApiKeyProvider và gọi getKey() suspend ngay trước mỗi request.
 *
 * v2: Thêm routeIntent() - thay thế hoàn toàn vai trò của LocalRouterEngine (SmolLM2 cục bộ,
 * đã bỏ vì crash SIGILL trên CPU yếu/cũ như Cortex-A53). Dùng model nhỏ + rẻ (MODEL_ROUTER)
 * riêng cho việc phân loại intent, tách biệt với model chat chính (MODEL_TEXT) để không tốn
 * chi phí/độ trễ của model lớn cho một việc chỉ cần trả 1 JSON ngắn.
 *
 * v3: Sửa rate-limit label:
 *  1) MODEL_TEXT/MODEL_VISION (chat với người dùng) và MODEL_ROUTER (phân loại intent nội bộ)
 *     có quota RIÊNG trên Groq -> lưu rate-limit theo từng model (rateLimitByModel), UI chỉ
 *     đọc của model chat chính (rateLimitInfo) thay vì 1 giá trị chung bị 2 model ghi đè nhau.
 *  2) Persist snapshot xuống SharedPreferences -> mở lại app vẫn còn label, không cần chat
 *     mới có.
 *  3) Cooldown chỉ tính khi bị 429 thật (xem GroqRateLimitInfo), không lẫn với thời gian
 *     reset quota thông thường.
 */
@Singleton
class GroqClientTool @Inject constructor(
    private val apiKeyProvider: GroqApiKeyProvider,
    private val logger: Logger,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        // Groq đã deprecate llama-3.3-70b-versatile, llama-3.1-8b-instant và
        // meta-llama/llama-4-scout-17b-16e-instruct (thông báo 17/6/2026). Dùng model thay thế
        // theo khuyến nghị chính thức: https://console.groq.com/docs/deprecations
        private const val MODEL_TEXT = "openai/gpt-oss-120b"          // chat chính
        private const val MODEL_VISION = "qwen/qwen3.6-27b"            // phân tích ảnh
        private const val MODEL_ROUTER = "openai/gpt-oss-20b"          // phân loại intent, nhanh+rẻ

        private const val MAX_TOKENS_CHAT = 1000
        private const val MAX_TOKENS_VISION = 500
        private const val MAX_TOKENS_ROUTER = 200

        private const val SAFE_FALLBACK_INTENT =
            """{"plugin":"chat","action":"none","params":{}}"""

        private const val PREFS_NAME = "groq_rate_limit_prefs"
        private val PERSISTED_MODELS = listOf(MODEL_TEXT, MODEL_VISION, MODEL_ROUTER)
    }

    // Singleton client - không tạo mới mỗi request
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ✅ Rate-limit theo TỪNG model (key = tên model) - đọc lại từ disk lúc khởi tạo nên
    // có giá trị ngay cả khi vừa mở app, chưa chat lần nào trong phiên này.
    private val _rateLimitByModel =
        MutableStateFlow<Map<String, GroqRateLimitInfo>>(loadPersistedRateLimits())
    val rateLimitByModel: StateFlow<Map<String, GroqRateLimitInfo>> = _rateLimitByModel.asStateFlow()

    // ✅ Rate-limit dành cho UI chính (ChatScreen) - CHỈ lấy từ model dùng để trả lời
    // người dùng (text hoặc vision khi gửi ảnh), KHÔNG lẫn quota của MODEL_ROUTER.
    //
    // ⚠️ LÝ DO LABEL "KHÔNG REALTIME" BẠN GẶP: routeIntent() (MODEL_ROUTER) được gọi ở
    // MỌI tin nhắn (AgentKernel.tryDeviceCommand() luôn chạy trước để xác định có phải lệnh
    // hay không), còn chat() (MODEL_TEXT/MODEL_VISION) CHỈ được gọi khi tin nhắn KHÔNG phải
    // lệnh thiết bị. Nếu bạn test chủ yếu bằng các câu lệnh điều khiển, rateLimitInfo (chat)
    // sẽ đứng yên suốt phiên vì chat() chưa từng được gọi lại - không phải do lỗi đồng bộ,
    // mà do đúng model đó chưa thực sự được gọi. Để label luôn "sống" trong mọi trường hợp,
    // expose thêm routerRateLimitInfo (cập nhật ở MỌI tin nhắn) để UI hiển thị song song.
    private val _chatRateLimitInfo = MutableStateFlow(
        _rateLimitByModel.value[MODEL_TEXT] ?: _rateLimitByModel.value[MODEL_VISION]
    )
    val rateLimitInfo: StateFlow<GroqRateLimitInfo?> = _chatRateLimitInfo.asStateFlow()

    // ✅ MỚI: Rate-limit của MODEL_ROUTER (phân loại intent) - cập nhật ở MỌI tin nhắn,
    // kể cả khi tin nhắn đó cuối cùng là lệnh thiết bị. Dùng để UI có 1 chỉ số luôn "động".
    private val _routerRateLimitInfo = MutableStateFlow(_rateLimitByModel.value[MODEL_ROUTER])
    val routerRateLimitInfo: StateFlow<GroqRateLimitInfo?> = _routerRateLimitInfo.asStateFlow()

    // ✅ FIX đồng bộ đa-instance (phòng hờ): trước đây _chatRateLimitInfo CHỈ được set bên
    // trong captureRateLimit(), tức là chỉ instance nào TỰ gọi chat()/routeIntent() mới thấy
    // số mới ngay; nếu có bất kỳ chỗ nào khác trong DI graph cầm một instance GroqClientTool
    // khác thì instance đó đứng yên cho tới khi tự nó gọi API hoặc app khởi động lại. Đăng ký
    // listener trực tiếp trên SharedPreferences giúp MỌI instance trong cùng process đồng bộ
    // ngay khi có bất kỳ instance nào ghi file.
    // Lưu ý: phải giữ listener bằng 1 property (không tạo lambda ẩn danh truyền thẳng vào
    // registerOnSharedPreferenceChangeListener) vì Android chỉ giữ WeakReference tới listener -
    // nếu không có biến nào giữ tham chiếu mạnh, nó có thể bị GC và ngừng nhận sự kiện.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key !in PERSISTED_MODELS) return@OnSharedPreferenceChangeListener
            val raw = prefs.getString(key, null) ?: return@OnSharedPreferenceChangeListener
            val info = parseRateLimitJson(raw) ?: return@OnSharedPreferenceChangeListener

            _rateLimitByModel.value = _rateLimitByModel.value + (key to info)
            when (key) {
                MODEL_TEXT, MODEL_VISION -> _chatRateLimitInfo.value = info
                MODEL_ROUTER -> _routerRateLimitInfo.value = info
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

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
    } catch (e: Exception) {
        null
    }

    /**
     * Đọc header rate-limit chuẩn OpenAI-compatible từ response (gọi được cả khi response
     * lỗi 429 - header rate-limit vẫn có trong response lỗi) và cập nhật state cho đúng
     * model vừa được gọi.
     */
    private fun captureRateLimit(response: okhttp3.Response, model: String) {
        val remainingReq = response.header("x-ratelimit-remaining-requests")?.toIntOrNull()
        val limitReq = response.header("x-ratelimit-limit-requests")?.toIntOrNull()
        val remainingTok = response.header("x-ratelimit-remaining-tokens")?.toIntOrNull()
        val limitTok = response.header("x-ratelimit-limit-tokens")?.toIntOrNull()
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
            // Chỉ set cooldown khi THẬT SỰ bị 429 và có retry-after -> mốc tuyệt đối được
            // phép gọi lại. Các lượt gọi thành công bình thường sẽ có cooldownUntilMillis = null,
            // nên UI sẽ không còn hiện nhãn "đang chờ" một cách sai lệch nữa.
            cooldownUntilMillis = if (isLimited && retryAfterSeconds != null) {
                now + (retryAfterSeconds * 1000).toLong()
            } else null,
            capturedAtMillis = now
        )

        _rateLimitByModel.value = _rateLimitByModel.value + (model to info)
        when (model) {
            MODEL_TEXT, MODEL_VISION -> _chatRateLimitInfo.value = info
            MODEL_ROUTER -> _routerRateLimitInfo.value = info
        }
        persistRateLimit(info)
    }

    /** Parse chuỗi duration kiểu "12.5s", "6m59s", "1h2m3s", "250ms", hoặc số giây thuần -> tổng số giây. */
    private fun parseDurationToSeconds(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        raw.toDoubleOrNull()?.let { return it } // trường hợp header chỉ là số giây thuần (vd retry-after)

        var total = 0.0
        var matched = false
        Regex("([\\d.]+)(ms|s|m|h)").findAll(raw).forEach { m ->
            matched = true
            val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
            total += when (m.groupValues[2]) {
                "ms" -> value / 1000.0
                "s" -> value
                "m" -> value * 60
                "h" -> value * 3600
                else -> 0.0
            }
        }
        return if (matched) total else null
    }

    /**
     * Chat text với lịch sử hội thoại.
     */
    suspend fun chat(
        message: String,
        extraContext: String = "",
        history: List<Map<String, String>> = emptyList(),
        imageUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: return@withContext "⚠️ Chưa cấu hình Groq API key. Vào Settings để nhập key."

        try {
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
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", message)
                    })
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

            val model = if (imageUrl != null) MODEL_VISION else MODEL_TEXT
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", MAX_TOKENS_CHAT)
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

    /**
     * MỚI: Phân loại intent (device command hay chat thường) bằng model NHỎ + RẺ.
     *
     * Thay thế vai trò của LocalRouterEngine.predictIntent() trước đây (SmolLM2 cục bộ).
     * - Luôn trả JSON hợp lệ, temperature = 0 để ổn định.
     * - Không dùng lịch sử/system message riêng - prompt đầu vào (từ AgentKernel) đã tự chứa
     *   đầy đủ catalog plugin + context + instruction rồi, gửi thẳng làm 1 user message.
     * - Lỗi mạng / thiếu key / parse lỗi -> trả SAFE_FALLBACK_INTENT ("chat") để caller luôn
     *   có JSON hợp lệ, không cần try-catch lại ở AgentKernel.
     */
    suspend fun routeIntent(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey() ?: return@withContext SAFE_FALLBACK_INTENT

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL_ROUTER)
                put("messages", messages)
                put("max_tokens", MAX_TOKENS_ROUTER)
                put("temperature", 0)
                // Đã bỏ "response_format": json_object - không phải model nào trên Groq
                // cũng hỗ trợ structured JSON mode này, gây HTTP 400. Prompt đã tự yêu cầu
                // "Output ONLY raw JSON" + parseIntentResponse() đã tự strip markdown nên
                // không cần ép response_format ở tầng API.
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            captureRateLimit(response, MODEL_ROUTER)
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                // Log đầy đủ body lỗi thật từ Groq (trước đây chỉ log status code,
                // không thấy được lý do thật -> không debug được).
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

    /**
     * Phân tích ảnh (vision) với prompt.
     */
    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider.getKey()
            ?: return@withContext "⚠️ Chưa cấu hình Groq API key."

        try {
            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", dataUrl) })
                        })
                    })
                })
            }

            val body = JSONObject().apply {
                put("model", MODEL_VISION)
                put("messages", messages)
                put("max_tokens", MAX_TOKENS_VISION)
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            captureRateLimit(response, MODEL_VISION)
            parseResponse(response, "analyzeImage")

        } catch (e: Exception) {
            logger.e("GroqClientTool", "analyzeImage error: ${e.message}", e)
            "Không thể phân tích ảnh lúc này."
        }
    }

    private fun parseResponse(response: okhttp3.Response, caller: String): String {
        val bodyStr = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            logger.e("GroqClientTool", "$caller HTTP ${response.code}: $bodyStr")
            return when (response.code) {
                401 -> "❌ Groq API key không hợp lệ (401). Kiểm tra lại key trong Settings."
                429 -> "⚠️ Groq rate limit — thử lại sau ít phút."
                else -> "Lỗi API: ${response.code}"
            }
        }

        return try {
            JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            logger.e("GroqClientTool", "$caller parse error: ${e.message}, body=$bodyStr")
            "Không thể đọc phản hồi từ AI."
        }
    }
}