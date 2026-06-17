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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val emailSkill: EmailSkill,
    private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY   = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER  = stringPreferencesKey("resend_sender")
        val DARK_MODE      = booleanPreferencesKey("dark_mode")
    }

    // FIX: Tái sử dụng một OkHttpClient thay vì tạo mới mỗi lần test
    // OkHttpClient giữ thread pool và connection pool — tạo mới liên tục là resource leak
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

    // ─── Expose trạng thái key để UI cảnh báo user khi chưa cấu hình ─────────

    /**
     * Dùng trong UI: hiển thị banner cảnh báo nếu Groq key chưa được set.
     * Ví dụ: if (!isGroqKeyConfigured) { Text("⚠️ Chưa nhập Groq API key") }
     */
    val isGroqKeyConfigured: StateFlow<Boolean> = groqApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isResendConfigured: StateFlow<Boolean> = combine(resendApiKey, resendSender) { key, sender ->
        key.isNotBlank() && sender.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─── Save ─────────────────────────────────────────────────────────────────

    fun saveGroqApiKey(key: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[GROQ_API_KEY] = key.trim() }
            logger.i("SettingsViewModel", "Groq API key saved (length=${key.trim().length})")
        }
    }

    fun saveResendSettings(apiKey: String, sender: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[RESEND_API_KEY] = apiKey.trim()
                it[RESEND_SENDER]  = sender.trim()
            }
            logger.i("SettingsViewModel", "Resend settings saved")
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[DARK_MODE] = enabled }
        }
    }

    // ─── Test Groq ────────────────────────────────────────────────────────────

    fun testGroqConnection(apiKey: String, onResult: (Boolean, String) -> Unit) {
        val trimmedKey = apiKey.trim()

        // FIX: Validate key trước khi gọi network — tránh gửi "Bearer " → 401
        if (trimmedKey.isEmpty()) {
            viewModelScope.launch(Dispatchers.Main) {
                onResult(false, "❌ API key trống — vui lòng nhập key trước khi test")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Test connection. Reply with 'OK'")
                        })
                    })
                    put("max_tokens", 10)
                }.toString()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $trimmedKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                // FIX: dùng httpClient singleton thay vì tạo mới
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                val success = response.isSuccessful

                val message = when {
                    success -> "✅ Kết nối thành công!"
                    response.code == 401 -> "❌ 401 Unauthorized — API key sai hoặc hết hạn. Key bắt đầu bằng 'gsk_'?"
                    response.code == 429 -> "⚠️ 429 Rate limit — key hợp lệ nhưng đang bị throttle"
                    else -> "❌ Lỗi API: ${response.code} - ${response.message}"
                }

                if (success) logger.i("SettingsViewModel", "Groq test OK")
                else logger.e("SettingsViewModel", "Groq test failed: ${response.code} - $responseBody")

                response.close()

                withContext(Dispatchers.Main) { onResult(success, message) }

            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi kết nối Groq: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "❌ Lỗi kết nối: ${e.message}")
                }
            }
        }
    }

    // ─── Test Email ───────────────────────────────────────────────────────────

    suspend fun testSendEmail(to: String): String {
        if (to.isBlank()) return "❌ Vui lòng nhập email nhận test"
        return withContext(Dispatchers.IO) {
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

    override fun onCleared() {
        super.onCleared()
        // Giải phóng connection pool khi ViewModel bị destroy
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}