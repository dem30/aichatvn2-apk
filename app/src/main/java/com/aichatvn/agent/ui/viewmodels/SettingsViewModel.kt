package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.skills.TuyaManager
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
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val emailSkill: EmailSkill,
    private val tuyaManager: TuyaManager,
    private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY   = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER  = stringPreferencesKey("resend_sender")
        val DARK_MODE      = booleanPreferencesKey("dark_mode")
        
        val TUYA_CLIENT_ID = stringPreferencesKey("tuya_client_id")
        val TUYA_CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
        val TUYA_DATA_CENTER = stringPreferencesKey("tuya_data_center")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Groq ────────────────────────────────────────────────────────────────
    val groqApiKey: StateFlow<String> = context.dataStore.data
        .map { it[GROQ_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ─── Resend ──────────────────────────────────────────────────────────────
    val resendApiKey: StateFlow<String> = context.dataStore.data
        .map { it[RESEND_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val resendSender: StateFlow<String> = context.dataStore.data
        .map { it[RESEND_SENDER] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ─── Dark mode ──────────────────────────────────────────────────────────
    val darkMode: StateFlow<Boolean> = context.dataStore.data
        .map { it[DARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─── Tuya ──────────────────────────────────────────────────────────────
    private val _tuyaClientId = MutableStateFlow("")
    val tuyaClientId: StateFlow<String> = _tuyaClientId.asStateFlow()

    private val _tuyaClientSecret = MutableStateFlow("")
    val tuyaClientSecret: StateFlow<String> = _tuyaClientSecret.asStateFlow()

    // ─── Expose trạng thái key ──────────────────────────────────────────────
    val isGroqKeyConfigured: StateFlow<Boolean> = groqApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isResendConfigured: StateFlow<Boolean> = combine(resendApiKey, resendSender) { key, sender ->
        key.isNotBlank() && sender.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isTuyaConfigured: StateFlow<Boolean> = combine(tuyaClientId, tuyaClientSecret) { id, secret ->
        id.isNotBlank() && secret.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─── Export result ──────────────────────────────────────────────────────
    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    // ─── Init ────────────────────────────────────────────────────────────────
    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            _tuyaClientId.value = prefs[TUYA_CLIENT_ID] ?: ""
            _tuyaClientSecret.value = prefs[TUYA_CLIENT_SECRET] ?: ""
        }
    }

    // ─── Save ─────────────────────────────────────────────────────────────────

    fun saveGroqApiKey(key: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[GROQ_API_KEY] = key.trim() }
            logger.i("SettingsViewModel", "Groq API key saved")
        }
    }

    fun saveResendSettings(apiKey: String, sender: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[RESEND_API_KEY] = apiKey.trim()
                it[RESEND_SENDER] = sender.trim()
            }
            logger.i("SettingsViewModel", "Resend settings saved")
        }
    }

    fun saveTuyaConfig(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[TUYA_CLIENT_ID] = clientId.trim()
                prefs[TUYA_CLIENT_SECRET] = clientSecret.trim()
            }
            _tuyaClientId.value = clientId.trim()
            _tuyaClientSecret.value = clientSecret.trim()
            logger.i("SettingsViewModel", "Tuya config saved")
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

                val response = httpClient.newCall(request).execute()
                val success = response.isSuccessful

                val message = when {
                    success -> "✅ Kết nối thành công!"
                    response.code == 401 -> "❌ 401 Unauthorized — API key sai hoặc hết hạn"
                    response.code == 429 -> "⚠️ 429 Rate limit — key hợp lệ nhưng đang bị throttle"
                    else -> "❌ Lỗi API: ${response.code}"
                }

                response.close()
                withContext(Dispatchers.Main) { onResult(success, message) }

            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Groq test error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "❌ Lỗi kết nối: ${e.message}")
                }
            }
        }
    }

    // ─── Test Tuya ───────────────────────────────────────────────────────────

    suspend fun testTuyaConnection(clientId: String, clientSecret: String): String {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return "❌ Vui lòng nhập Client ID và Client Secret"
        }
        
        return withContext(Dispatchers.IO) {
            try {
                context.dataStore.edit { 
                    it[TUYA_CLIENT_ID] = clientId.trim()
                    it[TUYA_CLIENT_SECRET] = clientSecret.trim()
                }
                
                val token = tuyaManager.getAccessToken()
                if (token.isNotBlank()) {
                    "✅ Kết nối Tuya thành công!"
                } else {
                    "❌ Không lấy được token"
                }
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Tuya test error: ${e.message}", e)
                "❌ Lỗi: ${e.message}"
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

    // ─── Export Settings ─────────────────────────────────────────────────────

    suspend fun exportSettings(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                
                val json = JSONObject().apply {
                    put("groq_api_key", prefs[GROQ_API_KEY] ?: "")
                    put("resend_api_key", prefs[RESEND_API_KEY] ?: "")
                    put("resend_sender", prefs[RESEND_SENDER] ?: "")
                    put("tuya_client_id", prefs[TUYA_CLIENT_ID] ?: "")
                    put("tuya_client_secret", prefs[TUYA_CLIENT_SECRET] ?: "")
                    put("dark_mode", prefs[DARK_MODE] ?: false)
                    put("exported_at", System.currentTimeMillis())
                }
                
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, "aichatvn_settings_${System.currentTimeMillis()}.json")
                file.writeText(json.toString(2))
                
                _exportResult.value = "✅ Đã lưu: ${file.absolutePath}"
                file.absolutePath
            } catch (e: Exception) {
                _exportResult.value = "❌ Lỗi: ${e.message}"
                ""
            }
        }
    }

    // ─── Import Settings ─────────────────────────────────────────────────────

    suspend fun importSettings(context: Context, jsonString: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                
                val groqKey = json.optString("groq_api_key", "")
                val resendKey = json.optString("resend_api_key", "")
                val resendSender = json.optString("resend_sender", "")
                val tuyaClientId = json.optString("tuya_client_id", "")
                val tuyaClientSecret = json.optString("tuya_client_secret", "")
                val darkMode = json.optBoolean("dark_mode", false)
                
                context.dataStore.edit { prefs ->
                    if (groqKey.isNotEmpty()) prefs[GROQ_API_KEY] = groqKey
                    if (resendKey.isNotEmpty()) prefs[RESEND_API_KEY] = resendKey
                    if (resendSender.isNotEmpty()) prefs[RESEND_SENDER] = resendSender
                    if (tuyaClientId.isNotEmpty()) prefs[TUYA_CLIENT_ID] = tuyaClientId
                    if (tuyaClientSecret.isNotEmpty()) prefs[TUYA_CLIENT_SECRET] = tuyaClientSecret
                    prefs[DARK_MODE] = darkMode
                }
                
                _tuyaClientId.value = tuyaClientId
                _tuyaClientSecret.value = tuyaClientSecret
                
                _exportResult.value = "✅ Import thành công!"
                "✅ Import thành công!"
            } catch (e: Exception) {
                _exportResult.value = "❌ Lỗi: ${e.message}"
                "❌ Lỗi: ${e.message}"
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}