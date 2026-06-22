package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.BuildConfig
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmailSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    logger: Logger
) : BaseSkill("email", "Gửi email", logger), Plugin {

    companion object {
        private const val RESEND_API_URL = "https://api.resend.com/emails"
    }

    // ─── Đọc credentials từ DataStore ───────────────────────────────────

    private suspend fun loadApiKey(): String {
        return try {
            val prefs = context.dataStore.data.first()
            val key = prefs[stringPreferencesKey("resend_api_key")] ?: ""
            if (key.isNotBlank()) key
            else {
                logger.i("EmailSkill", "DataStore trống → dùng BuildConfig")
                BuildConfig.RESEND_API_KEY
            }
        } catch (e: Exception) {
            logger.e("EmailSkill", "Lỗi đọc API key: ${e.message}")
            BuildConfig.RESEND_API_KEY
        }
    }

    private suspend fun loadSenderEmail(): String {
        return try {
            val prefs = context.dataStore.data.first()
            val sender = prefs[stringPreferencesKey("resend_sender")] ?: ""
            if (sender.isNotBlank()) sender
            else BuildConfig.RESEND_SENDER
        } catch (e: Exception) {
            BuildConfig.RESEND_SENDER
        }
    }

    // ==================== PLUGIN IMPLEMENTATION ====================

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "send",
                description = "Gửi email",
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email nhận", true),
                    PluginParameter("subject", "string", "Tiêu đề email", false),
                    PluginParameter("body", "string", "Nội dung email", false)
                )
            ),
            PluginAction(
                name = "test",
                description = "Gửi email test",
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email nhận", true)
                )
            )
        )
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            "test" -> handleTest(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleSend(params: Map<String, Any>): PluginResult {
        val to = params["to"] as? String
            ?: return PluginResult.Failure("Bạn muốn gửi email tới địa chỉ nào?")
        
        val subject = params["subject"] as? String ?: "Không có tiêu đề"
        val body = params["body"] as? String ?: ""
        
        val result = sendEmail(to, subject, body, null)
        
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Gửi email thất bại")
        }
    }

    private suspend fun handleTest(params: Map<String, Any>): PluginResult {
        val to = params["to"] as? String
            ?: return PluginResult.Failure("Bạn muốn gửi email test tới địa chỉ nào?")
        
        val result = sendEmail(
            to = to,
            subject = "Test từ AIChatVN2",
            body = "Email test gửi lúc ${System.currentTimeMillis()}",
            imageBytes = null
        )
        
        return when (result) {
            is PluginResult.Success -> result
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Gửi email test thất bại")
        }
    }

    // ==================== CORE SKILL METHODS ====================

    override suspend fun initialize() {
        val key = loadApiKey()
        if (key.isBlank()) {
            logger.w("EmailSkill", "Chưa cấu hình Resend API key")
        }
    }

    override suspend fun shutdown() {}

    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): PluginResult = withContext(Dispatchers.IO) {
        if (to.isBlank()) {
            return@withContext PluginResult.Failure("Địa chỉ email nhận không được để trống")
        }

        val apiKey = loadApiKey()
        if (apiKey.isBlank()) {
            return@withContext PluginResult.Failure(
                "Chưa cấu hình Resend API key. Vào Settings để nhập."
            )
        }

        val sender = loadSenderEmail()
        if (sender.isBlank()) {
            return@withContext PluginResult.Failure(
                "Chưa cấu hình email gửi (Resend sender). Vào Settings để nhập."
            )
        }

        try {
            val json = buildRequestJson(
                from    = sender,
                to      = to,
                subject = subject,
                html    = body,
                imageBytes = imageBytes
            )

            val connection = (URL(RESEND_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout    = 15_000
            }

            OutputStreamWriter(connection.outputStream).use { it.write(json) }

            val code = connection.responseCode

            // ✅ FIX: KHÔNG đọc connection.inputStream trước khi biết code thành công.
            // HttpURLConnection ném FileNotFoundException khi gọi getInputStream() trên
            // response lỗi (4xx/5xx) — body lỗi CHỈ đọc được qua errorStream. Code cũ đọc
            // inputStream trước rồi mới if/else nên mọi lỗi HTTP đều bị crash ra ngoài
            // catch(Exception) phía dưới, mất hết message lỗi thật từ Resend (vd "domain
            // not verified") và chỉ còn lại "FileNotFoundException: <url>".
            if (code in 200..299) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                logger.i("EmailSkill", "✅ Email gửi thành công tới $to (HTTP $code)")
                PluginResult.Success(
                    mapOf(
                        "message" to "Email đã gửi tới $to",
                        "to" to to,
                        "subject" to subject
                    )
                )
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                    ?: "Không có nội dung lỗi từ server"
                logger.e("EmailSkill", "❌ Resend API lỗi HTTP $code: $errorBody")
                PluginResult.Failure("Gửi email thất bại (HTTP $code): $errorBody")
            }

        } catch (e: Exception) {
            logger.e("EmailSkill", "❌ Exception khi gửi email: ${e.message}", e)
            PluginResult.Failure("Gửi email thất bại: ${e.message}")
        }
    }

    private fun buildRequestJson(
        from: String,
        to: String,
        subject: String,
        html: String,
        imageBytes: ByteArray?
    ): String {
        val obj = JSONObject().apply {
            put("from", from)
            put("to", JSONArray().put(to))
            put("subject", subject)
            put("html", html)

            if (imageBytes != null) {
                val attachments = JSONArray().put(
                    JSONObject().apply {
                        put("filename", "evidence_${System.currentTimeMillis()}.jpg")
                        put("content", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                        put("content_type", "image/jpeg")
                    }
                )
                put("attachments", attachments)
            }
        }
        return obj.toString()
    }
}