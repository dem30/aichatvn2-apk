package com.aichatvn.agent.ui.viewmodels

import com.aichatvn.agent.skills.ScheduleSkill
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
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.EmailSkill
import com.aichatvn.agent.skills.TrainingSkill
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
    private val cameraSkill: CameraSkill,
    private val scheduleSkill: ScheduleSkill,
    private val groqClient: GroqClientTool,
    private val configProvider: AppConfigProvider,
    private val trainingSkill: TrainingSkill,
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
            "world_state"  
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

    suspend fun importSettings(context: Context, jsonString: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val gson = Gson()

                val exportVersion = json.optInt("export_version", 1)
                if (exportVersion > 4) {
                    val errMsg = "❌ Lỗi: Bản sao lưu (v$exportVersion) mới hơn phiên bản ứng dụng hiện tại. Vui lòng cập nhật ứng dụng."
                    _importResult.value = errMsg
                    return@withContext errMsg
                }

                val settingsJson = json.optJSONObject("settings") ?: json

                val groqKey         = settingsJson.optString("groq_api_key", "")
                val resendKey       = settingsJson.optString("resend_api_key", "")
                val resendSenderVal = settingsJson.optString("resend_sender", "")
                val tuyaClientIdVal = settingsJson.optString("tuya_client_id", "")
                val tuyaSecretVal   = settingsJson.optString("tuya_client_secret", "")
                val tuyaUidVal      = settingsJson.optString("tuya_uid", "")
                val darkModeVal     = settingsJson.optBoolean("dark_mode", false)

                context.dataStore.edit { prefs ->
                    if (groqKey.isNotEmpty())         prefs[GROQ_API_KEY]       = groqKey
                    if (resendKey.isNotEmpty())       prefs[RESEND_API_KEY]     = resendKey
                    if (resendSenderVal.isNotEmpty()) prefs[RESEND_SENDER]      = resendSenderVal
                    if (tuyaClientIdVal.isNotEmpty()) prefs[TUYA_CLIENT_ID]     = tuyaClientIdVal
                    if (tuyaSecretVal.isNotEmpty())   prefs[TUYA_CLIENT_SECRET] = tuyaSecretVal
                    if (tuyaUidVal.isNotEmpty())       prefs[TUYA_UID]          = tuyaUidVal
                    prefs[DARK_MODE] = darkModeVal
                }
                _tuyaClientId.value = tuyaClientIdVal
                _tuyaClientSecret.value = tuyaSecretVal
                _tuyaUid.value = tuyaUidVal

                var restoredCount = 0
                val dataJson = json.optJSONObject("data")
                
                if (dataJson != null) {
                    val sdb = database.openHelper.writableDatabase
                    
                    sdb.execSQL("PRAGMA foreign_keys=OFF;")
                    sdb.beginTransaction()
                    try {
                        for (tableName in BACKUP_TABLES) {
                            val rowsArray = dataJson.optJSONArray(tableName) ?: continue

                            if (tableName == "app_config") {
                                val list: List<AppConfigEntity> = gson.fromJson(
                                    rowsArray.toString(), 
                                    object : TypeToken<List<AppConfigEntity>>() {}.type
                                )
                                list.forEach { configProvider.upsert(it) }
                                restoredCount += list.size
                                continue
                            }

                            val pragmaCursor = sdb.query("PRAGMA table_info(`$tableName`)", emptyArray<Any?>())
                            val existingColumns = mutableSetOf<String>()
                            if (pragmaCursor.moveToFirst()) {
                                val nameColIdx = pragmaCursor.getColumnIndex("name")
                                if (nameColIdx >= 0) {
                                    do {
                                        existingColumns.add(pragmaCursor.getString(nameColIdx))
                                    } while (pragmaCursor.moveToNext())
                                }
                            }
                            pragmaCursor.close()

                            if (existingColumns.isEmpty()) continue

                            if (tableName == "qa_data") {
                                sdb.execSQL("DELETE FROM `$tableName` WHERE `category` != 'auto_init'")
                            } else {
                                sdb.execSQL("DELETE FROM `$tableName`")
                            }

                            if (rowsArray.length() == 0) continue

                            val firstRow = rowsArray.getJSONObject(0)
                            val columnsToInsert = mutableListOf<String>()
                            val colKeys = firstRow.keys()
                            while (colKeys.hasNext()) {
                                val colName = colKeys.next()
                                if (colName in existingColumns) {
                                    columnsToInsert.add(colName)
                                }
                            }

                            if (columnsToInsert.isEmpty()) continue

                            for (rowIndex in 0 until rowsArray.length()) {
                                val rowObj = rowsArray.getJSONObject(rowIndex)
                                val values = android.content.ContentValues()
                                
                                for (colName in columnsToInsert) {
                                    val value = rowObj.opt(colName)
                                    if (value == JSONObject.NULL || value == null) {
                                        values.putNull(colName)
                                    } else {
                                        when (value) {
                                            is Boolean -> values.put(colName, value)
                                            is Int -> values.put(colName, value)
                                            is Long -> values.put(colName, value)
                                            is Double -> values.put(colName, value)
                                            is String -> values.put(colName, value)
                                            is JSONObject -> {
                                                if (value.optString("_type") == "blob") {
                                                    val base64Data = value.getString("data")
                                                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                                                    values.put(colName, bytes)
                                                } else {
                                                    values.put(colName, value.toString())
                                                }
                                            }
                                            else -> values.put(colName, value.toString())
                                        }
                                    }
                                }
                                sdb.insert(tableName, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
                                restoredCount++
                            }

                            if (tableName == "cameras") {
                                val cursor = sdb.query("SELECT DISTINCT `customerId` FROM `cameras` WHERE `customerId` != ''", emptyArray<Any?>())
                                if (cursor.moveToFirst()) {
                                    do {
                                        val customerId = cursor.getString(0)
                                        val checkCursor = sdb.query("SELECT 1 FROM `customer_settings` WHERE `customerId` = ?", arrayOf<Any?>(customerId))
                                        val exists = checkCursor.count > 0
                                        checkCursor.close()
                                        if (!exists) {
                                            val now = System.currentTimeMillis()
                                            val customerValues = android.content.ContentValues().apply {
                                                put("customerId", customerId)
                                                put("smartMode", 0)
                                                put("isActive", 1)
                                                put("updatedAt", now)
                                                put("timestamp", now)
                                            }
                                            sdb.insert("customer_settings", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, customerValues)
                                            restoredCount++
                                        }
                                    } while (cursor.moveToNext())
                                }
                                cursor.close()
                            }
                        }
                        
                        sdb.setTransactionSuccessful()
                    } finally {
                        sdb.endTransaction()
                        sdb.execSQL("PRAGMA foreign_keys=ON;")
                    }
                }

                database.invalidationTracker.refreshVersionsAsync()

                trainingSkill.refreshQAList("default_user")

                try {
                    cameraSkill.initialize()
                    tuyaManager.loadDevicesFromDB()
                    scheduleSkill.loadSchedules() 
                } catch (e: Exception) {
                    logger.e("SettingsViewModel", "Khởi tạo lại sơ đồ camera/tuya/lịch trình sau khi import thất bại", e)
                }

                if (dataJson?.has("schedules") == true || dataJson?.has("ScheduleEntity") == true) {
                    logger.i("SettingsViewModel", "🔄 Đã đồng bộ danh sách lịch trình vừa nạp từ bản sao lưu thành công.")
                }

                val message = if (restoredCount > 0)
                    "✅ Import thành công! Đã phục hồi $restoredCount bản ghi cấu trúc Whitelist an toàn."
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