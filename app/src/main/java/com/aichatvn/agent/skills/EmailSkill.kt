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

    override val routable: Boolean = true
    override val visibleOnDashboard: Boolean = false
    override val autoGenerateQA: Boolean = true

    companion object {
        private const val RESEND_API_URL = "https://api.resend.com/emails"
    }

    private suspend fun loadApiKey(): String = withContext(Dispatchers.IO) {
        try {
            val prefs = context.dataStore.data.first()
            val key = prefs[stringPreferencesKey("resend_api_key")] ?: ""
            if (key.isNotBlank()) key else BuildConfig.RESEND_API_KEY
        } catch (e: Exception) {
            BuildConfig.RESEND_API_KEY
        }
    }

    private suspend fun loadSenderEmail(): String = withContext(Dispatchers.IO) {
        try {
            val prefs = context.dataStore.data.first()
            val sender = prefs[stringPreferencesKey("resend_sender")] ?: ""
            if (sender.isNotBlank()) sender else BuildConfig.RESEND_SENDER
        } catch (e: Exception) {
            BuildConfig.RESEND_SENDER
        }
    }

    // ==================== PLUGIN IMPLEMENTATION ====================

    override fun getActions(): List<PluginAction> {
        return listOf(
            PluginAction(
                name = "send",
                description = "Soạn thảo và gửi email tới địa chỉ đích",
                examples = listOf(
                    "gửi email cho tôi",
                    "gửi email cho",
                    "soạn mail gửi tới",
                    "gửi mail",
                    "viết thư báo cáo tình hình"
                ),
                aliases = listOf("gửi mail", "soạn thư", "gửi báo cáo"),
                tags = listOf("mail", "send", "report", "notification"),
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email nhận", true, "email"),
                    PluginParameter("subject", "string", "Tiêu đề email", true, "string"),
                    PluginParameter("body", "string", "Nội dung email", true, "string")
                )
            ),
            PluginAction(
                name = "test",
                description = "Gửi một bức email thử nghiệm kết nối hệ thống",
                examples = listOf(
                    "gửi mail test tới tôi",
                    "kiểm tra kết nối email",
                    "test gui mail"
                ),
                aliases = listOf("gửi test", "test mail"),
                tags = listOf("test", "diagnostic"),
                parameters = listOf(
                    PluginParameter("to", "string", "Địa chỉ email nhận", true, "email")
                )
            )
        )
    }

    override fun getQATriggers(): Map<String, List<String>> = mapOf(
        "send" to listOf("gửi email", "soạn email", "gửi mail cho", "viết email cho"),
        "test" to listOf("gửi email test", "test email", "kiểm tra gửi mail")
    )
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "send" -> handleSend(params)
            "test" -> handleTest(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleSend(params: Map<String, Any>): PluginResult {
        val to = params["to"] as? String ?: return PluginResult.Failure("Bạn muốn gửi email tới địa chỉ nào?")
        val subject = (params["subject"] as? String).takeIf { !it.isNullOrBlank() } ?: "Không có tiêu đề"
        val body = (params["body"] as? String).takeIf { !it.isNullOrBlank() } ?: "(Không có nội dung)"
        return sendEmail(to, subject, body, null)
    }

    private suspend fun handleTest(params: Map<String, Any>): PluginResult {
        val to = params["to"] as? String ?: return PluginResult.Failure("Bạn muốn gửi email test tới địa chỉ nào?")
        return sendEmail(
            to = to,
            subject = "Test từ AIChatVN2",
            body = "Email test gửi lúc ${System.currentTimeMillis()}",
            imageBytes = null
        )
    }

    // ==================== CORE SKILL METHODS ====================

    override suspend fun initialize() {
        val key = loadApiKey()
        if (key.isBlank()) logger.w("EmailSkill", "Chưa cấu hình Resend API key")
    }

    override suspend fun shutdown() {}

    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): PluginResult = withContext(Dispatchers.IO) {
        if (to.isBlank()) return@withContext PluginResult.Failure("Địa chỉ email nhận không được để trống")
        val apiKey = loadApiKey()
        if (apiKey.isBlank()) return@withContext PluginResult.Failure("Chưa cấu hình Resend API key. Vào Settings để nhập.")
        val sender = loadSenderEmail()
        if (sender.isBlank()) return@withContext PluginResult.Failure("Chưa cấu hình email gửi (Resend sender). Vào Settings để nhập.")

        try {
            val json = buildRequestJson(sender, to, subject, body, imageBytes)
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

            if (code in 200..299) {
                PluginResult.Success(mapOf("message" to "Email đã gửi tới $to", "to" to to))
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Không có nội dung lỗi từ server"
                PluginResult.Failure("Gửi email thất bại (HTTP $code): $errorBody")
            }
        } catch (e: Exception) {
            PluginResult.Failure("Gửi email thất bại: ${e.message}")
        }
    }

    private fun buildRequestJson(from: String, to: String, subject: String, html: String, imageBytes: ByteArray?): String {
        val obj = JSONObject().apply {
            put("from", from)
            put("to", JSONArray().put(to))
            put("subject", subject)
            if (html.isNotBlank()) put("html", html) else put("text", "(Không có nội dung)")
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