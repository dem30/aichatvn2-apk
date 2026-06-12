package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.BuildConfig
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.*
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmailSkill @Inject constructor(
    @ApplicationContext private val context: Context
) : BaseAgentSkill {
    
    override val skillName = "EmailSkill"
    
    private val httpTransport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    
    private val credentials: GoogleCredential? by lazy {
        try {
            GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(BuildConfig.GMAIL_CLIENT_ID, BuildConfig.GMAIL_CLIENT_SECRET)
                .build()
                .setRefreshToken(BuildConfig.GMAIL_REFRESH_TOKEN)
                .apply {
                    refreshToken()
                }
        } catch (e: Exception) {
            null
        }
    }
    
    private val gmailService: Gmail? by lazy {
        credentials?.let {
            Gmail.Builder(httpTransport, jsonFactory, it)
                .setApplicationName("AIChatVN2")
                .build()
        }
    }
    
    override suspend fun initialize() {
        // Pre-warm credentials
        credentials?.refreshToken()
    }
    
    override suspend fun shutdown() {
        // Nothing to do
    }
    
    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): AgentResponse {
        return withContext(Dispatchers.IO) {
            try {
                val gmail = gmailService ?: return@withContext AgentResponse(
                    success = false,
                    error = "Gmail service not available"
                )
                
                val mimeMessage = createMimeMessage(to, subject, body, imageBytes)
                val message = Message()
                message.raw = encodeMimeMessage(mimeMessage)
                
                gmail.users().messages().send("me", message).execute()
                AgentResponse(success = true, data = "Email sent")
                
            } catch (e: Exception) {
                e.printStackTrace()
                AgentResponse(success = false, error = e.message ?: "Failed to send email")
            }
        }
    }
    
    private fun createMimeMessage(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray?
    ): MimeMessage {
        val props = Properties()
        val session = Session.getDefaultInstance(props, null)
        val mimeMessage = MimeMessage(session)
        
        mimeMessage.setFrom(InternetAddress(BuildConfig.GMAIL_SENDER))
        mimeMessage.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
        mimeMessage.subject = subject
        
        val multipart = MimeMultipart("mixed")
        
        // HTML body part
        val htmlPart = MimeBodyPart().apply {
            setContent(body, "text/html; charset=utf-8")
        }
        multipart.addBodyPart(htmlPart)
        
        // Image attachment
        imageBytes?.let {
            val imagePart = MimeBodyPart().apply {
                val dataSource = object : DataSource {
                    override fun getInputStream(): java.io.InputStream = it.inputStream()
                    override fun getOutputStream(): ByteArrayOutputStream = ByteArrayOutputStream()
                    override fun getContentType(): String = "image/jpeg"
                    override fun getName(): String = "evidence.jpg"
                }
                dataHandler = DataHandler(dataSource)
                setFileName("evidence.jpg")
                addHeader("Content-ID", "<evidence_img>")
                addHeader("Content-Disposition", "inline")
            }
            multipart.addBodyPart(imagePart)
        }
        
        mimeMessage.setContent(multipart)
        return mimeMessage
    }
    
    private fun encodeMimeMessage(mimeMessage: MimeMessage): String {
        val outputStream = ByteArrayOutputStream()
        mimeMessage.writeTo(outputStream)
        return Base64.getUrlEncoder().encodeToString(outputStream.toByteArray())
    }
}