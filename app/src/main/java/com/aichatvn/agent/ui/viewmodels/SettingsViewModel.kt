package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.database.Cursor
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
import com.aichatvn.agent.data.BackupRestorer
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.model.FacebookPageEntity
import com.aichatvn.agent.data.model.EventLogEntity      // ✅ MỚI (Tuần 5)
import com.aichatvn.agent.data.model.WorldStateEntity    // ✅ MỚI (Tuần 5)
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.ai.PromptLogEntry
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

data class PromptLogTotals(
    val callCount: Int = 0,
    val sumPromptTokens: Int = 0,
    val sumCompletionTokens: Int = 0,
    val sumTotalTokens: Int = 0,
    val missingUsageCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val emailSkill: EmailSkill,
    private val tuyaManager: TuyaManager,
    private val groqClient: GroqClientTool,
    private val configProvider: AppConfigProvider,
    private val backupRestorer: BackupRestorer,
    private val logger: Logger
) : ViewModel() {

    companion object {
        val GROQ_API_KEY   = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER  = stringPreferencesKey("resend_sender")
        val DARK_MODE      = booleanPreferencesKey("dark_mode")
        val TUYA_CLIENT_ID = stringPreferencesKey("tuya_client_id")
        val TUYA_CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
        val TUYA_UID = stringPreferencesKey("tuya_uid")

        // ✅ MỚI (Tuần 5): Thêm event_logs và world_state vào danh sách sao lưu để hỗ trợ giám sát trên PC
        private val BACKUP_TABLES = listOf(
            "customers",
            "customer_settings",
            "cameras",
            "tuya_devices",
            "facebook_pages",
            "qa_data",
            "schedules",
            "app_config",
            "event_logs",  
            "world_state",
            // ✅ MỚI: danh bạ cuộc gọi P2P — cơ chế generic ở trên (PRAGMA table_info +
            // ContentValues) tự lo export/import ĐẦY ĐỦ mọi cột của bảng, không cần viết
            // thêm nhánh xử lý riêng như "app_config" (chỉ app_config cần nhánh riêng vì đi
            // qua configProvider.upsert() để bắn kèm sự kiện đổi cấu hình).
            "call_contacts"
        )
    }

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

    private val _tuyaClientId = MutableStateFlow("")
    val tuyaClientId: StateFlow<String> = _tuyaClientId.asStateFlow()

    private val _tuyaClientSecret = MutableStateFlow("")
    val tuyaClientSecret: StateFlow<String> = _tuyaClientSecret.asStateFlow()

    private val _tuyaUid = MutableStateFlow("")
    val tuyaUid: StateFlow<String> = _tuyaUid.asStateFlow()

    val isGroqKeyConfigured: StateFlow<Boolean> = groqApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isResendConfigured: StateFlow<Boolean> = combine(resendApiKey, resendSender) { key, sender ->
        key.isNotBlank() && sender.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isTuyaConfigured: StateFlow<Boolean> = combine(tuyaClientId, tuyaClientSecret, tuyaUid) { id, secret, uid ->
        id.isNotBlank() && secret.isNotBlank() && uid.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val allConfigs: StateFlow<List<AppConfigEntity>> = configProvider.allConfigs
    val promptLog: StateFlow<List<PromptLogEntry>> = groqClient.promptLog

    // ✅ MỚI: Tổng token của các cuộc gọi ĐANG có trong log (log chỉ giữ tối đa
    // PROMPT_LOG_SIZE=10 cuộc gọi gần nhất trong RAM — đây là tổng của phiên hiện tại,
    // KHÔNG phải tổng cộng dồn từ lúc cài app. Muốn tổng vĩnh viễn cần lưu DB riêng.
    val promptLogTotals: StateFlow<PromptLogTotals> = promptLog
        .map { entries ->
            PromptLogTotals(
                callCount = entries.size,
                sumPromptTokens = entries.sumOf { it.promptTokens ?: 0 },
                sumCompletionTokens = entries.sumOf { it.completionTokens ?: 0 },
                sumTotalTokens = entries.sumOf { it.totalTokens ?: 0 },
                missingUsageCount = entries.count { it.totalTokens == null }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PromptLogTotals())

    val facebookPages: StateFlow<List<FacebookPageEntity>> = database.facebookPageDao().getAllPagesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ✅ MỚI (Tuần 5 - Console CRUD): Luồng quan sát trực tiếp Trạng thái thực tế và Nhật ký sự kiện thời gian thực
    val worldStates: StateFlow<List<WorldStateEntity>> = database.worldStateDao().getAllStatesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val eventLogs: StateFlow<List<EventLogEntity>> = database.eventLogDao().getLatestLogsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            _tuyaClientId.value = prefs[TUYA_CLIENT_ID] ?: ""
            _tuyaClientSecret.value = prefs[TUYA_CLIENT_SECRET] ?: ""
            _tuyaUid.value = prefs[TUYA_UID] ?: ""
        }
    }

    fun clearImportResult() { _importResult.value = null }
    fun clearExportResult() { _exportResult.value = null }

    // ✅ MỚI (Tuần 5 - Console CRUD): Thao tác xóa sửa thế giới thực để dọn tàn dư thiết bị mồ côi
    fun deleteWorldState(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.worldStateDao().deleteStateById(id)
                logger.i("SettingsViewModel", "🗑️ Đã xóa thủ công world state: $id")
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi xóa world state: ${e.message}")
            }
        }
    }

    fun deleteEventLog(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.eventLogDao().deleteLogById(id)
                logger.i("SettingsViewModel", "🗑️ Đã xóa thủ công event log: $id")
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi xóa event log: ${e.message}")
            }
        }
    }

    fun clearAllEventLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.eventLogDao().clearAllLogs()
                logger.i("SettingsViewModel", "🧹 Đã xóa sạch toàn bộ Event Logs để làm mới trí nhớ AI")
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Lỗi dọn sạch event logs: ${e.message}")
            }
        }
    }

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

    fun saveTuyaConfig(clientId: String, clientSecret: String, uid: String) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[TUYA_CLIENT_ID] = clientId.trim()
                prefs[TUYA_CLIENT_SECRET] = clientSecret.trim()
                prefs[TUYA_UID] = uid.trim()
            }
            _tuyaClientId.value = clientId.trim()
            _tuyaClientSecret.value = clientSecret.trim()
            _tuyaUid.value = uid.trim()
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[DARK_MODE] = enabled }
        }
    }

    fun testGroqConnection(apiKey: String, onResult: (Boolean, String) -> Unit) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            viewModelScope.launch(Dispatchers.Main) {
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

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(
                        Request.Builder()
                            .url("https://api.groq.com/openai/v1/chat/completions")
                            .addHeader("Authorization", "Bearer $trimmedKey")
                            .addHeader("Content-Type", "application/json")
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                }

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) onResult(true, "✅ Kết nối Groq thành công!")
                    else onResult(false, "❌ HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "❌ Lỗi: ${e.message}")
                }
            }
        }
    }

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

    suspend fun testTuyaConnection(clientId: String, clientSecret: String, uid: String): String {
        return withContext(Dispatchers.IO) {
            try {
                context.dataStore.edit { prefs ->
                    prefs[TUYA_CLIENT_ID] = clientId.trim()
                    prefs[TUYA_CLIENT_SECRET] = clientSecret.trim()
                    prefs[TUYA_UID] = uid.trim()
                }
                val devices = tuyaManager.scanDevices()
                val onlineCount = devices.values.count { it.online }
                "✅ Kết nối Tuya OK — ${devices.size} thiết bị ($onlineCount đang online)"
            } catch (e: Exception) {
                "❌ Lỗi Tuya: ${e.message}"
            }
        }
    }

    suspend fun exportSettings(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()

                val settingsJson = JSONObject().apply {
                    put("groq_api_key",      prefs[GROQ_API_KEY] ?: "")
                    put("resend_api_key",    prefs[RESEND_API_KEY] ?: "")
                    put("resend_sender",     prefs[RESEND_SENDER] ?: "")
                    put("tuya_client_id",    prefs[TUYA_CLIENT_ID] ?: "")
                    put("tuya_client_secret",prefs[TUYA_CLIENT_SECRET] ?: "")
                    put("tuya_uid",          prefs[TUYA_UID] ?: "")
                    put("dark_mode",         prefs[DARK_MODE] ?: false)
                }

                val dataJson = JSONObject()
                val sdb = database.openHelper.writableDatabase
                
                var totalRecords = 0

                for (tableName in BACKUP_TABLES) {
                    val rowsArray = JSONArray()
                    
                    val query = if (tableName == "qa_data") {
                        "SELECT * FROM `$tableName` WHERE `category` != 'auto_init'"
                    } else {
                        "SELECT * FROM `$tableName`"
                    }

                    val rowCursor = sdb.query(query, emptyArray<Any?>())
                    val columnNames = rowCursor.columnNames

                    if (rowCursor.moveToFirst()) {
                        do {
                            val rowObj = JSONObject()
                            for (i in columnNames.indices) {
                                val colName = columnNames[i]
                                when (rowCursor.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> rowObj.put(colName, JSONObject.NULL)
                                    Cursor.FIELD_TYPE_INTEGER -> rowObj.put(colName, rowCursor.getLong(i))
                                    Cursor.FIELD_TYPE_FLOAT -> rowObj.put(colName, rowCursor.getDouble(i))
                                    Cursor.FIELD_TYPE_STRING -> rowObj.put(colName, rowCursor.getString(i))
                                    Cursor.FIELD_TYPE_BLOB -> {
                                        val bytes = rowCursor.getBlob(i)
                                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                        val blobObj = JSONObject().apply {
                                            put("_type", "blob")
                                            put("data", base64)
                                        }
                                        rowObj.put(colName, blobObj)
                                    }
                                }
                            }
                            rowsArray.put(rowObj)
                            totalRecords++
                        } while (rowCursor.moveToNext())
                    }
                    rowCursor.close()
                    dataJson.put(tableName, rowsArray)
                }

                val json = JSONObject().apply {
                    put("export_version", 4)
                    put("exported_at", System.currentTimeMillis())
                    put("settings", settingsJson)
                    put("data", dataJson)
                }

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "aichatvn_settings_${System.currentTimeMillis()}.json")
                file.writeText(json.toString(2))

                _exportResult.value = "✅ Đã lưu ($totalRecords bản ghi cốt lõi): ${file.absolutePath}"
                file.absolutePath
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Export error: ${e.message}", e)
                _exportResult.value = "❌ Lỗi: ${e.message}"
                ""
            }
        }
    }

    // ✅ MỚI: Xuất nhật ký cuộc gọi Groq (kèm tổng token) ra file text trong Downloads
    // để kiểm tra ngoài app — vì log trong RAM chỉ giữ 10 cuộc gọi gần nhất.
    suspend fun exportPromptLog(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val entries = groqClient.promptLog.value
                val totals = promptLogTotals.value

                val text = buildString {
                    appendLine("AIChatVN2 — Nhật ký cuộc gọi Groq")
                    appendLine("Xuất lúc: ${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}")
                    appendLine("Số cuộc gọi trong log (tối đa 10 gần nhất): ${totals.callCount}")
                    appendLine("Tổng prompt tokens: ${totals.sumPromptTokens}")
                    appendLine("Tổng completion tokens: ${totals.sumCompletionTokens}")
                    appendLine("Tổng tokens: ${totals.sumTotalTokens}")
                    if (totals.missingUsageCount > 0) {
                        appendLine("⚠️ ${totals.missingUsageCount} cuộc gọi không có dữ liệu usage (lỗi/timeout) — không tính vào tổng trên.")
                    }
                    appendLine("=".repeat(50))

                    entries.forEachIndexed { idx, e ->
                        appendLine()
                        appendLine("#${idx + 1} [${e.caller}] model=${e.model}  lúc=${java.text.SimpleDateFormat("HH:mm:ss dd/MM", java.util.Locale.getDefault()).format(java.util.Date(e.sentAt))}")
                        appendLine("tokens: ${e.promptTokens ?: "?"} → ${e.completionTokens ?: "?"} = ${e.totalTokens?.toString() ?: "không có usage"}")
                        appendLine("--- Nội dung gửi đi ---")
                        appendLine(e.prompt)
                        if (e.response != null) {
                            appendLine("--- Groq trả về ---")
                            appendLine(e.response)
                        }
                        appendLine("-".repeat(50))
                    }
                }

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "aichatvn_prompt_log_${System.currentTimeMillis()}.txt")
                file.writeText(text)

                _exportResult.value = "✅ Đã xuất log (${totals.callCount} cuộc gọi, ${totals.sumTotalTokens} tokens): ${file.absolutePath}"
                file.absolutePath
            } catch (e: Exception) {
                logger.e("SettingsViewModel", "Export prompt log error: ${e.message}", e)
                _exportResult.value = "❌ Lỗi xuất log: ${e.message}"
                ""
            }
        }
    }

    // ✅ SỬA: logic restore đầy đủ đã chuyển sang BackupRestorer (dùng chung với
    // seed dữ liệu demo lúc khởi động app trong MainApplication). Hàm này chỉ còn
    // gọi lại BackupRestorer rồi đồng bộ state Tuya riêng của màn Settings.
    suspend fun importSettings(context: Context, jsonString: String): String {
        val message = backupRestorer.restore(context, jsonString)

        // Đồng bộ lại state Tuya hiển thị trên màn Settings sau khi restore
        val prefs = context.dataStore.data.first()
        _tuyaClientId.value = prefs[TUYA_CLIENT_ID] ?: ""
        _tuyaClientSecret.value = prefs[TUYA_CLIENT_SECRET] ?: ""
        _tuyaUid.value = prefs[TUYA_UID] ?: ""

        _importResult.value = message
        return message
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}