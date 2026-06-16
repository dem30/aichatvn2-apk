package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val emailSkill: EmailSkill,
    private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY    = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY  = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER   = stringPreferencesKey("resend_sender")
        val DARK_MODE       = booleanPreferencesKey("dark_mode")
    }

    val groqApiKey: StateFlow<String> = context.dataStore.data
        .map { it[GROQ_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val resendApiKey: StateFlow<String> = context.dataStore.data
        .map { it[RESEND_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val resendSender: StateFlow<String> = context.dataStore.data
        .map { it[RESEND_SENDER] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val darkMode: StateFlow<Boolean> = context.dataStore.data
        .map { it[DARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─── Save ─────────────────────────────────────────────────────────────────

    fun saveGroqApiKey(key: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[GROQ_API_KEY] = key.trim() }
        }
    }

    fun saveResendSettings(apiKey: String, sender: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[RESEND_API_KEY] = apiKey.trim()
                it[RESEND_SENDER]  = sender.trim()
            }
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[DARK_MODE] = enabled }
        }
    }

    // ─── Test Groq ────────────────────────────────────────────────────────────

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

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val success = response.isSuccessful
                val message = if (success) "Kết nối thành công!"
                              else "Lỗi API: ${response.code} - ${response.message}"
                if (success) logger.i("SettingsViewModel", "Groq test OK: $responseBody")
                else logger.e("SettingsViewModel", "Groq test failed: ${response.code} - $responseBody")
                response.close()

                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(success, message) }

            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi kết nối Groq: ${e.message}", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "Lỗi kết nối: ${e.message}")
                }
            }
        }
    }

    // ─── Test Email ───────────────────────────────────────────────────────────
    // SettingsScreen đã lưu settings trước khi gọi hàm này,
    // nên EmailSkill sẽ tự đọc đúng key từ DataStore.

    suspend fun testSendEmail(to: String): String {
        if (to.isBlank()) return "❌ Vui lòng nhập email nhận test"
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = emailSkill.sendEmail(
                    to      = to,
                    subject = "✅ Test email từ AIChatVN2",
                    body    = """
                        <html><body>
                        <h2>Test thành công! 🎉</h2>
                        <p>Email gửi qua <b>Resend API</b> hoạt động bình thường.</p>
                        <p><small>Thời gian: ${java.util.Date()}</small></p>
                        </body></html>
                    """.trimIndent()
                )
                if (result.success) "✅ ${result.data ?: "Gửi thành công tới $to"}"
                else "❌ ${result.error ?: "Gửi email thất bại"}"
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Test email error: ${e.message}", e)
                "❌ Gửi email thất bại: ${e.message}"
            }
        }
    }
}