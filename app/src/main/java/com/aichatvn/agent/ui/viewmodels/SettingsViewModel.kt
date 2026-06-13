package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aichatvn.agent.utils.Logger
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import javax.inject.Inject
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
    , private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        val GMAIL_CLIENT_ID = stringPreferencesKey("gmail_client_id")
        val GMAIL_CLIENT_SECRET = stringPreferencesKey("gmail_client_secret")
        val GMAIL_REFRESH_TOKEN = stringPreferencesKey("gmail_refresh_token")
        val GMAIL_SENDER = stringPreferencesKey("gmail_sender")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }

    val groqApiKey: StateFlow<String> = context.dataStore.data
        .map { it[GROQ_API_KEY] ?: "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" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val darkMode: StateFlow<Boolean> = context.dataStore.data
        .map { it[DARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val gmailClientId: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_CLIENT_ID] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailClientSecret: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_CLIENT_SECRET] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailRefreshToken: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_REFRESH_TOKEN] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailSender: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_SENDER] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun saveGroqApiKey(key: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[GROQ_API_KEY] = key }
        }
    }

    fun saveGmailSettings(clientId: String, clientSecret: String, refreshToken: String, sender: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[GMAIL_CLIENT_ID] = clientId
                it[GMAIL_CLIENT_SECRET] = clientSecret
                it[GMAIL_REFRESH_TOKEN] = refreshToken
                it[GMAIL_SENDER] = sender
            }
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[DARK_MODE] = enabled }
        }
    }

    fun testGroqConnection(apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val requestBodyJson = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Test connection. Reply with 'OK'")
                        })
                    })
                    put("max_tokens", 10)
                }.toString()
                
                val mediaType = "application/json".toMediaType()
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody(mediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                if (response.isSuccessful) {
                    logger.i("SettingsViewModel", "Groq test OK: $responseBody")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult(true, "Kết nối thành công!")
                    }
                } else {
                    logger.e("SettingsViewModel", "Groq test failed: ${response.code} - ${response.message} - $responseBody")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult(false, "Lỗi API: ${response.code} - ${response.message}")
                    }
                }
                response.close()
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi kết nối: ${e.message}", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Lỗi kết nối: ${e.message}")
                }
            }
        }
    }
    
    suspend fun testSendEmail(
        to: String,
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        senderEmail: String
    ): String {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank() || senderEmail.isBlank()) {
                    return@withContext "❌ Vui lòng cấu hình đầy đủ Gmail settings trước"
                }
                
                val httpTransport = NetHttpTransport()
                val jsonFactory = GsonFactory.getDefaultInstance()
                
                val credential = GoogleCredential.Builder()
                    .setTransport(httpTransport)
                    .setJsonFactory(jsonFactory)
                    .setClientSecrets(clientId, clientSecret)
                    .build()
                    .setRefreshToken(refreshToken)
                
                credential.refreshToken()
                
                val gmailService = Gmail.Builder(httpTransport, jsonFactory, credential)
                    .setApplicationName("AIChatVN2")
                    .build()
                
                val props = Properties()
                val session = Session.getDefaultInstance(props, null)
                val mimeMessage = MimeMessage(session)
                
                mimeMessage.setFrom(InternetAddress(senderEmail))
                mimeMessage.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(to))
                mimeMessage.subject = "📧 Test email từ AIChatVN2"
                mimeMessage.setText("Đây là email test từ ứng dụng AIChatVN2.\nThời gian: ${System.currentTimeMillis()}")
                
                val outputStream = ByteArrayOutputStream()
                mimeMessage.writeTo(outputStream)
                val rawMessage = Base64.getUrlEncoder().encodeToString(outputStream.toByteArray())
                
                val message = Message().setRaw(rawMessage)
                gmailService.users().messages().send("me", message).execute()
                
                "✅ Email test đã gửi thành công tới $to"
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Test email error: ${e.message}", e)
                "❌ Gửi email thất bại: ${e.message}"
            }
        }
    }
}