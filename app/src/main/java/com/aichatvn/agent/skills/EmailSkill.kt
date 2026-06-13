package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.BuildConfig
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.utils.Logger
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.*
import javax.activation.DataHandler
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

@Singleton
class EmailSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : BaseAgentSkill {

    override val skillName = "EmailSkill"

    private val httpTransport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private var gmailService: Gmail? = null
    private var credentialExpiresAt: Long = 0L
    private val credentialsMutex = Mutex()

    // ─── Đọc credentials: DataStore trước, fallback BuildConfig (GitHub Secrets) ───

    private data class GmailCredentials(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
        val senderEmail: String
    ) {
        val isValid get() = clientId.isNotBlank() && clientSecret.isNotBlank()
                         && refreshToken.isNotBlank() && senderEmail.isNotBlank()
    }

    private suspend fun loadCredentials(): GmailCredentials {
        return try {
            val prefs = context.dataStore.data.first()
            val clientId     = prefs[stringPreferencesKey("gmail_client_id")]     ?: ""
            val clientSecret = prefs[stringPreferencesKey("gmail_client_secret")] ?: ""
            val refreshToken = prefs[stringPreferencesKey("gmail_refresh_token")] ?: ""
            val sender       = prefs[stringPreferencesKey("gmail_sender")]        ?: ""

            // Nếu DataStore có đủ dữ liệu → dùng DataStore
            if (clientId.isNotBlank() && refreshToken.isNotBlank()) {
                return GmailCredentials(clientId, clientSecret, refreshToken, sender)
            }

            // Fallback: BuildConfig (inject từ GitHub Secrets qua build.gradle)
            logger.i("EmailSkill", "DataStore trống → dùng BuildConfig (GitHub Secrets)")
            GmailCredentials(
                clientId     = BuildConfig.GMAIL_CLIENT_ID,
                clientSecret = BuildConfig.GMAIL_CLIENT_SECRET,
                refreshToken = BuildConfig.GMAIL_REFRESH_TOKEN,
                senderEmail  = BuildConfig.GMAIL_SENDER
            )
        } catch (e: Exception) {
            logger.e("EmailSkill", "Lỗi đọc credentials: ${e.message}")
            GmailCredentials(
                clientId     = BuildConfig.GMAIL_CLIENT_ID,
                clientSecret = BuildConfig.GMAIL_CLIENT_SECRET,
                refreshToken = BuildConfig.GMAIL_REFRESH_TOKEN,
                senderEmail  = BuildConfig.GMAIL_SENDER
            )
        }
    }

    // ─── Khởi tạo / refresh Gmail service ─────────────────────────────────────

    private suspend fun getGmailService(forceRefresh: Boolean = false): Pair<Gmail?, String> {
        return credentialsMutex.withLock {
            val now = System.currentTimeMillis()
            // Chỉ refresh khi hết hạn (token sống ~1 giờ) hoặc force
            if (!forceRefresh && gmailService != null && now < credentialExpiresAt) {
                return@withLock Pair(gmailService, "")
            }

            val creds = loadCredentials()
            if (!creds.isValid) {
                return@withLock Pair(null,
                    "Chưa cấu hình Gmail. Vào Settings để nhập credentials hoặc kiểm tra GitHub Secrets.")
            }

            try {
                val credential = GoogleCredential.Builder()
                    .setTransport(httpTransport)
                    .setJsonFactory(jsonFactory)
                    .setClientSecrets(creds.clientId, creds.clientSecret)
                    .build()
                    .setRefreshToken(creds.refreshToken)

                val refreshed = credential.refreshToken()
                if (!refreshed) {
                    return@withLock Pair(null, "Không refresh được access token. Kiểm tra refresh_token.")
                }

                gmailService = Gmail.Builder(httpTransport, jsonFactory, credential)
                    .setApplicationName("AIChatVN2")
                    .build()

                // Access token sống 1 giờ, đặt expire sớm hơn 5 phút
                credentialExpiresAt = now + 55 * 60 * 1000L

                logger.i("EmailSkill", "Gmail credentials refreshed OK (sender=${creds.senderEmail})")
                Pair(gmailService, "")

            } catch (e: Exception) {
                logger.e("EmailSkill", "Lỗi refresh credentials: ${e.message}", e)
                Pair(null, "Lỗi xác thực Gmail: ${e.message}")
            }
        }
    }

    override suspend fun initialize() {
        val (service, error) = getGmailService()
        if (service == null) {
            logger.w("EmailSkill", "initialize: $error")
        }
    }

    override suspend fun shutdown() {}

    // ─── Gửi email ────────────────────────────────────────────────────────────

    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): AgentResponse = withContext(Dispatchers.IO) {
        if (to.isBlank()) {
            return@withContext AgentResponse(success = false, error = "Địa chỉ email nhận không được để trống")
        }

        val creds = loadCredentials()
        if (!creds.isValid) {
            return@withContext AgentResponse(
                success = false,
                error = "Chưa cấu hình Gmail. Vào Settings hoặc kiểm tra GitHub Secrets."
            )
        }

        // Lần 1
        val (gmail, initError) = getGmailService()
        if (gmail == null) {
            return@withContext AgentResponse(success = false, error = initError)
        }

        try {
            sendWithService(gmail, creds.senderEmail, to, subject, body, imageBytes)
            AgentResponse(success = true, data = "Email đã gửi tới $to")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            logger.w("EmailSkill", "Gửi lần 1 thất bại ($msg), thử refresh token...")

            // Nếu 401 hoặc token hết hạn → force refresh rồi thử lần 2
            if (msg.contains("401") || msg.contains("invalid_grant") || msg.contains("Token")) {
                credentialExpiresAt = 0L // buộc refresh
                val (gmail2, refreshError) = getGmailService(forceRefresh = true)
                if (gmail2 == null) {
                    return@withContext AgentResponse(success = false, error = refreshError)
                }
                try {
                    sendWithService(gmail2, creds.senderEmail, to, subject, body, imageBytes)
                    AgentResponse(success = true, data = "Email đã gửi (sau khi refresh token)")
                } catch (e2: Exception) {
                    logger.e("EmailSkill", "Gửi lần 2 thất bại: ${e2.message}", e2)
                    AgentResponse(success = false, error = "Gửi email thất bại: ${e2.message}")
                }
            } else {
                logger.e("EmailSkill", "Lỗi gửi email: $msg", e)
                AgentResponse(success = false, error = "Gửi email thất bại: $msg")
            }
        }
    }

    private fun sendWithService(
        gmail: Gmail,
        from: String,
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray?
    ) {
        val mimeMessage = buildMimeMessage(from, to, subject, body, imageBytes)
        val raw = encodeMimeMessage(mimeMessage)
        gmail.users().messages().send("me", Message().setRaw(raw)).execute()
    }

    // ─── Build MIME ────────────────────────────────────────────────────────────

    private fun buildMimeMessage(
        from: String,
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray?
    ): MimeMessage {
        val session = Session.getDefaultInstance(Properties(), null)
        val mime = MimeMessage(session)
        mime.setFrom(InternetAddress(from))
        mime.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
        mime.subject = subject

        val multipart = MimeMultipart("mixed")

        // HTML body
        multipart.addBodyPart(MimeBodyPart().apply {
            setContent(body, "text/html; charset=utf-8")
        })

        // Ảnh đính kèm (dùng ByteArrayDataSource — đúng cách, không dùng anonymous DataSource)
        imageBytes?.let {
            multipart.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(it, "image/jpeg"))
                fileName = "evidence_${System.currentTimeMillis()}.jpg"
                addHeader("Content-Disposition", "attachment")
            })
        }

        mime.setContent(multipart)
        return mime
    }

    private fun encodeMimeMessage(mime: MimeMessage): String {
        val out = ByteArrayOutputStream()
        mime.writeTo(out)
        return Base64.getUrlEncoder().encodeToString(out.toByteArray())
    }
}
