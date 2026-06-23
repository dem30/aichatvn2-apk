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
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.ai.PromptLogEntry
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
    private val groqClient: GroqClientTool,
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY   = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER  = stringPreferencesKey("resend_sender")
        val DARK_MODE      = booleanPreferencesKey("dark_mode")
        val TUYA_CLIENT_ID = stringPreferencesKey("tuya_client_id")
        val TUYA_CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── DataStore keys ──────────────────────────────────────────────────────
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

    private val _tuyaClientId = MutableStateFlow("")
    val tuyaClientId: StateFlow<String> = _tuyaClientId.asStateFlow()

    private val _tuyaClientSecret = MutableStateFlow("")
    val tuyaClientSecret: StateFlow<String> = _tuyaClientSecret.asStateFlow()

    val isGroqKeyConfigured: StateFlow<Boolean> = groqApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isResendConfigured: StateFlow<Boolean> = combine(resendApiKey, resendSender) { key, sender ->
        key.isNotBlank() && sender.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isTuyaConfigured: StateFlow<Boolean> = combine(tuyaClientId, tuyaClientSecret) { id, secret ->
        id.isNotBlank() && secret.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ─── AppConfig ───────────────────────────────────────────────────────────
    /** Toàn bộ config realtime — UI bind trực tiếp */
    val allConfigs: StateFlow<List<AppConfigEntity>> = configProvider.allConfigs

    /** Prompt log 5 entries gần nhất từ GroqClientTool */
    val promptLog: StateFlow<List<PromptLogEntry>> = groqClient.promptLog

    // ─── UI state cho config editing ─────────────────────────────────────────
    private val _configSaveResult = MutableStateFlow<String?>(null)
    val configSaveResult: StateFlow<String?> = _configSaveResult.asStateFlow()

    fun saveConfig(key: String, value: String) {
        viewModelScope.launch {
            configProvider.set(key, value)
            _configSaveResult.value = "✅ Đã lưu"
            kotlinx.coroutines.delay(1500)
            _configSaveResult.value = null
        }
    }

    fun resetConfig(key: String) {
        viewModelScope.launch {
            val default = AppConfigDefaults.all().firstOrNull { it.key == key } ?: return@launch
            configProvider.upsert(default)
            _configSaveResult.value = "🔄 Đã reset về mặc định"
            kotlinx.coroutines.delay(1500)
            _configSaveResult.value = null
        }
    }

    fun clearConfigSaveResult() { _configSaveResult.value = null }

    // ─── Export/Import ────────────────────────────────────────────────────────
    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            _tuyaClientId.value = prefs[TUYA_CLIENT_ID] ?: ""
            _tuyaClientSecret.value = prefs[TUYA_CLIENT_SECRET] ?: ""
        }
    }

    fun clearImportResult() { _importResult.value = null }
    fun clearExportResult() { _exportResult.value = null }

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
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                onResult(false, "❌ API key trống — vui lòng nhập key trước khi test")
            }
            return
        }
        viewModelScope.launch {
            try {
                saveGroqApiKey(trimmedKey)
                kotlinx.coroutines.delay(200)
                val body = JSONObject().apply {
                    put("model", "openai/gpt-oss-20b")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", "Hi") })
                    })
                    put("max_tokens", 5)
                }.toString()
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("https://api.groq.com/openai/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $trimmedKey")
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (response.isSuccessful) onResult(true, "✅ Kết nối Groq thành công!")
                    else onResult(false, "❌ HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onResult(false, "❌ Lỗi: ${e.message}")
                }
            }
        }
    }

    // ─── Test Email ───────────────────────────────────────────────────────────
    suspend fun testSendEmail(to: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val result = emailSkill.sendEmail(
                    to = to,
                    subject = "🧪 Test từ AIChatVN2",
                    body = "<p>Email test thành công từ AIChatVN2.</p>",
                    imageBytes = null
                )
                when (result) {
                    is PluginResult.Success -> "✅ Email đã gửi tới $to"
                    is PluginResult.Failure -> "❌ Lỗi: ${result.error}"
                    else -> "❌ Không xác định"
                }
            } catch (e: Exception) {
                "❌ Exception: ${e.message}"
            }
        }
    }

    // ─── Test Tuya ────────────────────────────────────────────────────────────
    // THÀNH:
suspend fun testTuyaConnection(clientId: String, clientSecret: String): String {
    return withContext(Dispatchers.IO) {
        try {
            // Save credentials trước để TuyaManager đọc từ DataStore
            context.dataStore.edit { prefs ->
                prefs[TUYA_CLIENT_ID] = clientId.trim()
                prefs[TUYA_CLIENT_SECRET] = clientSecret.trim()
            }
            val devices = tuyaManager.scanDevices()
            "✅ Kết nối Tuya OK — ${devices.size} thiết bị"
        } catch (e: Exception) {
            "❌ Lỗi Tuya: ${e.message}"
        }
    }
}

    // ─── Export ───────────────────────────────────────────────────────────────
    suspend fun exportSettings(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                val gson = Gson()

                val settingsJson = JSONObject().apply {
                    put("groq_api_key",      prefs[GROQ_API_KEY] ?: "")
                    put("resend_api_key",    prefs[RESEND_API_KEY] ?: "")
                    put("resend_sender",     prefs[RESEND_SENDER] ?: "")
                    put("tuya_client_id",    prefs[TUYA_CLIENT_ID] ?: "")
                    put("tuya_client_secret",prefs[TUYA_CLIENT_SECRET] ?: "")
                    put("dark_mode",         prefs[DARK_MODE] ?: false)
                }

                val schedules       = database.scheduleDao().getAllSchedules()
                val cameras         = database.cameraDao().getAllCameras()
                val customers       = database.customerDao().getAllCustomers()
                val tuyaDevices     = database.tuyaDeviceDao().getAllDevices()
                val qaList          = database.qaDao().getAllQAs(username = "default_user")
                val customerSettings = customers.mapNotNull { c ->
                    database.cameraDao().getCustomerSetting(c.id)
                }
                val appConfigs = configProvider.getAll()

                val dataJson = JSONObject().apply {
                    put("schedules",         JSONArray(gson.toJson(schedules)))
                    put("cameras",           JSONArray(gson.toJson(cameras)))
                    put("customer_settings", JSONArray(gson.toJson(customerSettings)))
                    put("customers",         JSONArray(gson.toJson(customers)))
                    put("tuya_devices",      JSONArray(gson.toJson(tuyaDevices)))
                    put("qa_data",           JSONArray(gson.toJson(qaList)))
                    put("app_config",        JSONArray(gson.toJson(appConfigs)))
                }

                val json = JSONObject().apply {
                    put("export_version", 3)
                    put("exported_at", System.currentTimeMillis())
                    put("settings", settingsJson)
                    put("data", dataJson)
                }

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "aichatvn_settings_${System.currentTimeMillis()}.json")
                file.writeText(json.toString(2))

                val total = schedules.size + cameras.size + customers.size + tuyaDevices.size + qaList.size + appConfigs.size
                _exportResult.value = "✅ Đã lưu ($total bản ghi): ${file.absolutePath}"
                file.absolutePath
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Export error: ${e.message}", e)
                _exportResult.value = "❌ Lỗi: ${e.message}"
                ""
            }
        }
    }

    // ─── Import ───────────────────────────────────────────────────────────────
    suspend fun importSettings(context: Context, jsonString: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val gson = Gson()
                val settingsJson = json.optJSONObject("settings") ?: json

                val groqKey         = settingsJson.optString("groq_api_key", "")
                val resendKey       = settingsJson.optString("resend_api_key", "")
                val resendSenderVal = settingsJson.optString("resend_sender", "")
                val tuyaClientIdVal = settingsJson.optString("tuya_client_id", "")
                val tuyaSecretVal   = settingsJson.optString("tuya_client_secret", "")
                val darkModeVal     = settingsJson.optBoolean("dark_mode", false)

                context.dataStore.edit { prefs ->
                    if (groqKey.isNotEmpty())         prefs[GROQ_API_KEY]       = groqKey
                    if (resendKey.isNotEmpty())       prefs[RESEND_API_KEY]     = resendKey
                    if (resendSenderVal.isNotEmpty()) prefs[RESEND_SENDER]      = resendSenderVal
                    if (tuyaClientIdVal.isNotEmpty()) prefs[TUYA_CLIENT_ID]     = tuyaClientIdVal
                    if (tuyaSecretVal.isNotEmpty())   prefs[TUYA_CLIENT_SECRET] = tuyaSecretVal
                    prefs[DARK_MODE] = darkModeVal
                }
                _tuyaClientId.value = tuyaClientIdVal
                _tuyaClientSecret.value = tuyaSecretVal

                var restoredCount = 0
                val dataJson = json.optJSONObject("data")
                if (dataJson != null) {
                    dataJson.optJSONArray("customers")?.let { arr ->
                        val list: List<CustomerEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<CustomerEntity>>() {}.type)
                        list.forEach { database.customerDao().insertCustomer(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("cameras")?.let { arr ->
                        val list: List<CameraConfigEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<CameraConfigEntity>>() {}.type)
                        list.forEach { database.cameraDao().insertCamera(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("customer_settings")?.let { arr ->
                        val list: List<CustomerSettingEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<CustomerSettingEntity>>() {}.type)
                        list.forEach { database.cameraDao().insertCustomerSetting(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("schedules")?.let { arr ->
                        val list: List<ScheduleEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<ScheduleEntity>>() {}.type)
                        list.forEach { database.scheduleDao().insertSchedule(it) }
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("tuya_devices")?.let { arr ->
                        val list: List<TuyaDeviceEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<TuyaDeviceEntity>>() {}.type)
                        database.tuyaDeviceDao().insertAllDevices(list)
                        restoredCount += list.size
                    }
                    dataJson.optJSONArray("qa_data")?.let { arr ->
                        val list: List<QAEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<QAEntity>>() {}.type)
                        list.forEach { database.qaDao().insertQA(it) }
                        restoredCount += list.size
                    }
                    // ✅ Import app_config (chỉ ghi nếu có trong file export)
                    dataJson.optJSONArray("app_config")?.let { arr ->
                        val list: List<AppConfigEntity> = gson.fromJson(arr.toString(), object : TypeToken<List<AppConfigEntity>>() {}.type)
                        list.forEach { configProvider.upsert(it) }
                        restoredCount += list.size
                    }
                }

                if (dataJson?.has("schedules") == true) {
                    com.aichatvn.agent.scheduler.TaskScheduler.runNow(context)
                }

                val message = if (restoredCount > 0)
                    "✅ Import thành công! Đã khôi phục $restoredCount bản ghi."
                else
                    "✅ Import thành công! (chỉ có settings, không có dữ liệu DB)"
                _importResult.value = message
                message
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Import error: ${e.message}", e)
                _importResult.value = "❌ Lỗi: ${e.message}"
                "❌ Lỗi: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
