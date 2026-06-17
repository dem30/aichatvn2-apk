package com.aichatvn.agent.tools.ai

import android.util.Base64
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
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
 * Template cho GroqClientTool đúng cách.
 *
 * ĐIỂM QUAN TRỌNG — root cause của 401:
 * KHÔNG inject API key qua constructor hay đọc từ StateFlow.value lúc khởi tạo.
 * DataStore chưa load xong khi Hilt inject → key vẫn là "" → "Bearer " → 401.
 *
 * GIẢI PHÁP: inject GroqApiKeyProvider và gọi getKey() suspend ngay trước mỗi request.
 *
 * Nếu GroqClientTool hiện tại của bạn có dạng:
 *   class GroqClientTool @Inject constructor(
 *       private val apiKey: String,  // ← SAI, key có thể là ""
 *       ...
 *   )
 * hoặc:
 *   private val apiKey = groqApiKey.value  // ← SAI, .value có thể là "" lúc init
 *
 * Thì cần đổi sang pattern dưới đây.
 */
@Singleton
class GroqClientTool @Inject constructor(
    private val apiKeyProvider: GroqApiKeyProvider,  // ← inject provider, không inject key trực tiếp
    private val logger: Logger
) {
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL_TEXT = "llama-3.3-70b-versatile"
        private const val MODEL_VISION = "meta-llama/llama-4-scout-17b-16e-instruct"
        private const val MAX_TOKENS_CHAT = 1000
        private const val MAX_TOKENS_VISION = 500
    }

    // Singleton client — không tạo mới mỗi request
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Chat text với lịch sử hội thoại.
     */
    suspend fun chat(
        message: String,
        extraContext: String = "",
        history: List<Map<String, String>> = emptyList(),
        imageUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        // FIX: đọc key lazy ngay trước request, không phải lúc khởi tạo
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

            parseResponse(response, "chat")

        } catch (e: Exception) {
            logger.e("GroqClientTool", "chat error: ${e.message}", e)
            "Xin lỗi, không thể kết nối AI lúc này."
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

            parseResponse(response, "analyzeImage")

        } catch (e: Exception) {
            logger.e("GroqClientTool", "analyzeImage error: ${e.message}", e)
            "Không thể phân tích ảnh lúc này."
        }
    }

    private fun parseResponse(response: okhttp3.Response, caller: String): String {
        val bodyStr = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            // Log chi tiết để debug
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