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
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.utils.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val database: AppDatabase,
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

    // ─── Import result ──────────────────────────────────────────────────────
    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    // ─── Init ────────────────────────────────────────────────────────────────
    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            _tuyaClientId.value = prefs[TUYA_CLIENT_ID] ?: ""
            _tuyaClientSecret.value = prefs[TUYA_CLIENT_SECRET] ?: ""
        }
    }

    fun clearImportResult() { 
        _importResult.value = null 
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
                when (result) {
                    is PluginResult.Success -> 
                        "✅ ${(result.data as? Map<*, *>)?.get("message") ?: "Gửi thành công tới $to"}"
                    is PluginResult.Failure -> 
                        "❌ ${result.error}"
                    else -> "❌ Gửi email thất bại"
                }
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Test email error: ${e.message}", e)
                "❌ Gửi email thất bại: ${e.message}"
            }
        }
    }

    // ─── Export Settings ─────────────────────────────────────────────────────
    // ✅ v2: export CẢ DataStore settings VÀ toàn bộ dữ liệu cấu hình trong Room DB
    // (schedules, cameras, customer_settings, customers, tuya_devices, qa_data).
    // KHÔNG export chat_messages/alerts vì đó là lịch sử phát sinh liên tục,
    // không phải cấu hình cần khôi phục khi setup máy mới.

    suspend fun exportSettings(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                val gson = Gson()

                val settingsJson = JSONObject().apply {
                    put("groq_api_key", prefs[GROQ_API_KEY] ?: "")
                    put("resend_api_key", prefs[RESEND_API_KEY] ?: "")
                    put("resend_sender", prefs[RESEND_SENDER] ?: "")
                    put("tuya_client_id", prefs[TUYA_CLIENT_ID] ?: "")
                    put("tuya_client_secret", prefs[TUYA_CLIENT_SECRET] ?: "")
                    put("dark_mode", prefs[DARK_MODE] ?: false)
                }

                val schedules = database.scheduleDao().getAllSchedules()
                val cameras = database.cameraDao().getAllCameras()
                val customers = database.customerDao().getAllCustomers()
                val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
                val qaList = database.qaDao().getAllQAs(username = "default_user")
                // CustomerSettingEntity không có DAO list-all riêng -> lấy theo từng customer
                val customerSettings = customers.mapNotNull { c ->
                    database.cameraDao().getCustomerSetting(c.id)
                }

                val dataJson = JSONObject().apply {
                    put("schedules", JSONArray(gson.toJson(schedules)))
                    put("cameras", JSONArray(gson.toJson(cameras)))
                    put("customer_settings", JSONArray(gson.toJson(customerSettings)))
                    put("customers", JSONArray(gson.toJson(customers)))
                    put("tuya_devices", JSONArray(gson.toJson(tuyaDevices)))
                    put("qa_data", JSONArray(gson.toJson(qaList)))
                }

                val json = JSONObject().apply {
                    put("export_version", 2)
                    put("exported_at", System.currentTimeMillis())
                    put("settings", settingsJson)
                    put("data", dataJson)
                }

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, "aichatvn_settings_${System.currentTimeMillis()}.json")
                file.writeText(json.toString(2))

                val total = schedules.size + cameras.size + customers.size + tuyaDevices.size + qaList.size
                _exportResult.value = "✅ Đã lưu ($total bản ghi dữ liệu): ${file.absolutePath}"
                file.absolutePath
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Export error: ${e.message}", e)
                _exportResult.value = "❌ Lỗi: ${e.message}"
                ""
            }
        }
    }

    // ─── Import Settings ─────────────────────────────────────────────────────
    // ✅ v2: import lại CẢ DataStore settings VÀ dữ liệu DB nếu file export có
    // chứa "data" (file export cũ chỉ có settings vẫn import được bình thường,
    // chỉ là không có gì để khôi phục ở phần data).
    // Ghi đè theo PRIMARY KEY (REPLACE) -> dữ liệu trùng id sẽ được cập nhật,
    // dữ liệu khác id được giữ nguyên (merge), không xoá sạch DB hiện tại trước khi import.

    suspend fun importSettings(context: Context, jsonString: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val gson = Gson()

                // Hỗ trợ cả format cũ (field settings ở top-level) và format mới (lồng trong "settings")
                val settingsJson = json.optJSONObject("settings") ?: json

                val groqKey = settingsJson.optString("groq_api_key", "")
                val resendKey = settingsJson.optString("resend_api_key", "")
                val resendSender = settingsJson.optString("resend_sender", "")
                val tuyaClientId = settingsJson.optString("tuya_client_id", "")
                val tuyaClientSecret = settingsJson.optString("tuya_client_secret", "")
                val darkMode = settingsJson.optBoolean("dark_mode", false)

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

                var restoredCount = 0
                val dataJson = json.optJSONObject("data")
                if (dataJson != null) {
                    dataJson.optJSONArray("customers")?.let { arr ->
                        val type = object : TypeToken<List<CustomerEntity>>() {}.type
                        val list: List<CustomerEntity> = gson.fromJson(arr.toString(), type)
                        list.forEach { database.customerDao().insertCustomer(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("cameras")?.let { arr ->
                        val type = object : TypeToken<List<CameraConfigEntity>>() {}.type
                        val list: List<CameraConfigEntity> = gson.fromJson(arr.toString(), type)
                        list.forEach { database.cameraDao().insertCamera(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("customer_settings")?.let { arr ->
                        val type = object : TypeToken<List<CustomerSettingEntity>>() {}.type
                        val list: List<CustomerSettingEntity> = gson.fromJson(arr.toString(), type)
                        list.forEach { database.cameraDao().insertCustomerSetting(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("schedules")?.let { arr ->
                        val type = object : TypeToken<List<ScheduleEntity>>() {}.type
                        val list: List<ScheduleEntity> = gson.fromJson(arr.toString(), type)
                        list.forEach { database.scheduleDao().insertSchedule(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("tuya_devices")?.let { arr ->
                        val type = object : TypeToken<List<TuyaDeviceEntity>>() {}.type
                        val list: List<TuyaDeviceEntity> = gson.fromJson(arr.toString(), type)
                        database.tuyaDeviceDao().insertAllDevices(list)
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("qa_data")?.let { arr ->
                        val type = object : TypeToken<List<QAEntity>>() {}.type
                        val list: List<QAEntity> = gson.fromJson(arr.toString(), type)
                        list.forEach { database.qaDao().insertQA(it) }
                        restoredCount += list.size
                    }
                }

                // ✅ Kick lại lịch ngay sau khi import schedules, để các lịch khôi phục
                // chạy đúng theo lastRunAt/interval mới mà không phải chờ chu kỳ check 5 phút.
                if (dataJson?.has("schedules") == true) {
                    com.aichatvn.agent.scheduler.TaskScheduler.runNow(context)
                }

                val message = if (restoredCount > 0)
                    "✅ Import thành công! Đã khôi phục $restoredCount bản ghi dữ liệu."
                else
                    "✅ Import thành công! (file chỉ chứa settings, không có dữ liệu DB)"

                _importResult.value = message
                message
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Import error: ${e.message}", e)
                _importResult.value = "❌ Lỗi: ${e.message}"
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