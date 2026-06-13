package com.aichatvn.agent.skills

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.skills.base.BaseAgentSkill
import com.aichatvn.agent.utils.Logger
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.*
import javax.activation.DataHandler
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

val Context.emailDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class EmailSkill @Inject constructor(
    @ApplicationContext private val context: Context
    , private val logger: Logger
) : BaseAgentSkill {
    
    override val skillName = "EmailSkill"
    
    private val httpTransport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    
    private var gmailService: Gmail? = null
    private val credentialsMutex = Mutex()
    
    private suspend fun getGmailService(): Gmail? {
        return credentialsMutex.withLock {
            if (gmailService == null || needsRefresh()) {
                refreshCredentials()
            }
            gmailService
        }
    }
    
    private suspend fun refreshCredentials() {
        try {
            val prefs = context.emailDataStore.data.first()
            val clientId = prefs[stringPreferencesKey("gmail_client_id")] ?: ""
            val clientSecret = prefs[stringPreferencesKey("gmail_client_secret")] ?: ""
            val refreshToken = prefs[stringPreferencesKey("gmail_refresh_token")] ?: ""
            val senderEmail = prefs[stringPreferencesKey("gmail_sender")] ?: ""
            
            if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank() || senderEmail.isBlank()) {
                return
            }
            
            val credential = GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(clientId, clientSecret)
                .build()
                .setRefreshToken(refreshToken)
            
            credential.refreshToken()
            
            gmailService = Gmail.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("AIChatVN2")
                .build()
                
        } catch (e: Exception) {
            logger.e("EmailSkill", "Error: ${e.message}", e)
        }
    }
    
    private suspend fun needsRefresh(): Boolean {
        return true
    }
    
    override suspend fun initialize() {
        refreshCredentials()
    }
    
    override suspend fun shutdown() {
    }
    
    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray? = null
    ): AgentResponse {
        return withContext(Dispatchers.IO) {
            try {
                refreshCredentials()
                
                val gmail = gmailService
                if (gmail == null) {
                    return@withContext AgentResponse(
                        success = false,
                        error = "Gmail service not available. Vui lòng cấu hình Gmail trong Settings."
                    )
                }
                
                val senderEmail = context.emailDataStore.data.first()[stringPreferencesKey("gmail_sender")] ?: ""
                if (senderEmail.isBlank()) {
                    return@withContext AgentResponse(
                        success = false,
                        error = "Chưa cấu hình email gửi. Vui lòng vào Settings để cập nhật."
                    )
                }
                
                val mimeMessage = createMimeMessage(senderEmail, to, subject, body, imageBytes)
                val message = Message()
                message.raw = encodeMimeMessage(mimeMessage)
                
                gmail.users().messages().send("me", message).execute()
                AgentResponse(success = true, data = "Email sent")
                
            } catch (e: Exception) {
                logger.e("EmailSkill", "Error: ${e.message}", e)
                
                if (e.message?.contains("401") == true) {
                    try {
                        refreshCredentials()
                        val gmail = gmailService
                        if (gmail != null) {
                            val senderEmail = context.emailDataStore.data.first()[stringPreferencesKey("gmail_sender")] ?: ""
                            val mimeMessage = createMimeMessage(senderEmail, to, subject, body, imageBytes)
                            val message = Message()
                            message.raw = encodeMimeMessage(mimeMessage)
                            gmail.users().messages().send("me", message).execute()
                            return@withContext AgentResponse(success = true, data = "Email sent after retry")
                        }
                    } catch (retryError: Exception) {
                        retryError.printStackTrace()
                    }
                }
                
                AgentResponse(success = false, error = e.message ?: "Failed to send email")
            }
        }
    }
    
    private fun createMimeMessage(
        from: String,
        to: String,
        subject: String,
        body: String,
        imageBytes: ByteArray?
    ): MimeMessage {
        val props = Properties()
        val session = Session.getDefaultInstance(props, null)
        val mimeMessage = MimeMessage(session)
        
        mimeMessage.setFrom(InternetAddress(from))
        mimeMessage.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
        mimeMessage.subject = subject
        
        val multipart = MimeMultipart("mixed")
        
        val htmlPart = MimeBodyPart().apply {
            setContent(body, "text/html; charset=utf-8")
        }
        multipart.addBodyPart(htmlPart)
        
        imageBytes?.let {
            val imagePart = MimeBodyPart().apply {
                val dataSource = ByteArrayDataSource(it, "image/jpeg")
                dataHandler = DataHandler(dataSource)
                fileName = "evidence.jpg"
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