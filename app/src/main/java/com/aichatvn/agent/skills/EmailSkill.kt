package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.BuildConfig
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.skills.base.BaseAgentSkill
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
    private val logger: Logger
) : BaseAgentSkill {

    override val skillName = "EmailSkill"

    companion object {
        private const val RESEND_API_URL = "https://api.resend.com/emails"
    }

    // ─── Đọc credentials từ DataStore, fallback BuildConfig ───────────────────

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

    override suspend fun initialize() {
        val key = loadApiKey()
        if (key.isBlank()) {
            logger.w("EmailSkill", "Chưa cấu hình Resend API key")
        }
    }

    override suspend fun shutdown() {}

    // ─── Gửi email qua Resend API ─────────────────────────────────────────────

    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): AgentResponse = withContext(Dispatchers.IO) {
        if (to.isBlank()) {
            return@withContext AgentResponse(success = false, error = "Địa chỉ email nhận không được để trống")
        }

        val apiKey = loadApiKey()
        if (apiKey.isBlank()) {
            return@withContext AgentResponse(
                success = false,
                error = "Chưa cấu hình Resend API key. Vào Settings để nhập."
            )
        }

        val sender = loadSenderEmail()
        if (sender.isBlank()) {
            return@withContext AgentResponse(
                success = false,
                error = "Chưa cấu hình email gửi (Resend sender). Vào Settings để nhập."
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
            val responseBody = connection.inputStream.bufferedReader().readText()

            if (code in 200..299) {
                logger.i("EmailSkill", "✅ Email gửi thành công tới $to (HTTP $code)")
                AgentResponse(success = true, data = "Email đã gửi tới $to")
            } else {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText() ?: responseBody
                }.getOrDefault(responseBody)
                logger.e("EmailSkill", "❌ Resend API lỗi HTTP $code: $errorBody")
                AgentResponse(success = false, error = "Gửi email thất bại (HTTP $code): $errorBody")
            }

        } catch (e: Exception) {
            logger.e("EmailSkill", "❌ Exception khi gửi email: ${e.message}", e)
            AgentResponse(success = false, error = "Gửi email thất bại: ${e.message}")
        }
    }

    // ─── Build JSON body cho Resend API ───────────────────────────────────────

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

            // Đính kèm ảnh nếu có (Resend hỗ trợ attachments dạng base64)
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