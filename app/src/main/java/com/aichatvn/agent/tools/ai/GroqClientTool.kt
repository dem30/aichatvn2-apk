package com.aichatvn.agent.tools.ai

import com.aichatvn.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroqClientTool @Inject constructor() {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val baseUrl = "https://api.groq.com/openai/v1"
    private val apiKey = BuildConfig.GROQ_API_KEY
    
    private val textModel = "llama-3.3-70b-versatile"
    private val visionModel = "meta-llama/llama-4-scout-17b-16e-instruct"
    
    private var lastCallTime = 0L
    private val minCallInterval = 6000L // 6 seconds between calls
    
    suspend fun chat(
        message: String,
        context: String = "",
        history: List<Map<String, String>> = emptyList(),
        imageUrl: String? = null
    ): String = withContext(Dispatchers.IO) {
        
        // Rate limiting
        val now = System.currentTimeMillis()
        val timeSinceLastCall = now - lastCallTime
        if (timeSinceLastCall < minCallInterval) {
            delay(minCallInterval - timeSinceLastCall)
        }
        lastCallTime = System.currentTimeMillis()
        
        val messages = JSONArray()
        
        // System prompt
        val systemPrompt = """
            Bạn là Groq, trợ lý hữu ích. Trả lời chính xác, ngắn gọn bằng tiếng Việt.
            Ưu tiên thông tin cụ thể từ ngữ cảnh cung cấp.
            Nếu thông tin mơ hồ, hỏi thêm chi tiết. Không bịa dữ liệu.
            Thời gian hiện tại: ${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy").format(System.currentTimeMillis())}
        """.trimIndent()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        
        // Add context if provided
        if (context.isNotEmpty()) {
            messages.put(JSONObject().put("role", "assistant").put("content", "Context: $context"))
        }
        
        // Add chat history
        history.forEach { msg ->
            messages.put(JSONObject().put("role", msg["role"]).put("content", msg["content"]))
        }
        
        // Build user message content
        val contentArray = JSONArray()
        contentArray.put(JSONObject().put("type", "text").put("text", message))
        
        // Add image if provided
        if (imageUrl != null) {
            contentArray.put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", imageUrl)
                )
            )
        }
        
        messages.put(JSONObject().put("role", "user").put("content", contentArray))
        
        val requestBody = JSONObject().apply {
            put("model", if (imageUrl != null) visionModel else textModel)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 1200)
        }
        
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                return@withContext "Lỗi API: ${response.code} - ${response.message}"
            }
            
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                choices.getJSONObject(0).getJSONObject("message").getString("content")
            } else {
                "Không có phản hồi từ API"
            }
            
        } catch (e: Exception) {
            "Lỗi kết nối Groq API: ${e.message}"
        }
    }
    
    suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val dataUrl = "data:image/jpeg;base64,$base64Image"
        chat(message = "Phân tích hình ảnh này", context = prompt, imageUrl = dataUrl)
    }
}