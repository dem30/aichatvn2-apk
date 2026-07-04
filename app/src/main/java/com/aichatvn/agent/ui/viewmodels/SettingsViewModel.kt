package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.database.Cursor // Để xử lý các kiểu dữ liệu và con trỏ SQLite
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

        // DANH SÁCH TRẮNG: Các bảng dữ liệu nghiệp vụ quan trọng cần sao lưu
        private val BACKUP_TABLES = listOf(
            "customers",
            "customer_settings",
            "cameras",
            "tuya_devices",
            "facebook_pages",
            "qa_data",
            "schedules",
            "app_config"
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

    val isGroqKeyConfigured: StateFlow<Boolean> = groqApiKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isResendConfigured: StateFlow<Boolean> = combine(resendApiKey, resendSender) { key, sender ->
        key.isNotBlank() && sender.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isTuyaConfigured: StateFlow<Boolean> = combine(tuyaClientId, tuyaClientSecret) { id, secret ->
        id.isNotBlank() && secret.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val allConfigs: StateFlow<List<AppConfigEntity>> = configProvider.allConfigs
    val promptLog: StateFlow<List<PromptLogEntry>> = groqClient.promptLog

    // Theo dõi và cập nhật luồng các Fanpage đã lưu trong DB
    val facebookPages: StateFlow<List<FacebookPageEntity>> = database.facebookPageDao().getAllPagesFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        }
    }

    fun clearImportResult() { _importResult.value = null }
    fun clearExportResult() { _exportResult.value = null }

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

    suspend fun testTuyaConnection(clientId: String, clientSecret: String): String {
        return withContext(Dispatchers.IO) {
            try {
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
                    put("dark_mode",         prefs[DARK_MODE] ?: false)
                }

                val dataJson = JSONObject()
                val sdb = database.openHelper.writableDatabase
                
                var totalRecords = 0

                // Quét qua danh sách các bảng cần sao lưu thuộc Whitelist
                for (tableName in BACKUP_TABLES) {
                    val rowsArray = JSONArray()
                    
                    // Lọc bỏ dữ liệu khởi tạo mặc định "auto_init" ở tầng truy vấn
                    val query = if (tableName == "qa_data") {
                        "SELECT * FROM `$tableName` WHERE `category` != 'auto_init'"
                    } else {
                        "SELECT * FROM `$tableName`"
                    }

                    val rowCursor = sdb.query(query, null)
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
                                        // Mã hóa Base64 và đánh dấu cấu trúc nhận dạng kiểu BLOB khi nạp lại
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
                    put("export_version", 4) // Định dạng cấu trúc danh sách trắng v4
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

                // Kiểm tra tính tương thích của export_version trước khi ghi đè
                val exportVersion = json.optInt("export_version", 1)
                if (exportVersion > 4) {
                    val errMsg = "❌ Lỗi: Bản sao lưu (v$exportVersion) mới hơn phiên bản ứng dụng hiện tại. Vui lòng cập nhật ứng dụng."
                    _importResult.value = errMsg
                    return@withContext errMsg
                }

                val settingsJson = json.optJSONObject("settings") ?: json

                // 1. Phục hồi cấu hình Preferences
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
                    val sdb = database.openHelper.writableDatabase
                    
                    // Khởi động Transaction để bảo đảm an toàn dữ liệu, tự động Rollback nếu lỗi giữa chừng
                    sdb.beginTransaction()
                    try {
                        // Tắt ràng buộc khóa ngoại tạm thời để tránh lỗi xung đột thứ tự xóa bảng
                        sdb.execSQL("PRAGMA foreign_keys=OFF;")

                        for (tableName in BACKUP_TABLES) {
                            val rowsArray = dataJson.optJSONArray(tableName) ?: continue

                            // NGHIỆP VỤ ĐẶC THÙ (APP_CONFIG): Sử dụng upsert của Provider để xóa cache và gửi tín hiệu notify
                            if (tableName == "app_config") {
                                val list: List<AppConfigEntity> = gson.fromJson(
                                    rowsArray.toString(), 
                                    object : TypeToken<List<AppConfigEntity>>() {}.type
                                )
                                list.forEach { configProvider.upsert(it) }
                                restoredCount += list.size
                                continue
                            }

                            // Đọc cấu trúc cột thực tế trên thiết bị của bảng hiện tại
                            val pragmaCursor = sdb.query("PRAGMA table_info(`$tableName`)", null)
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

                            // ✅ ĐÃ SỬA: Nghiệp vụ đặc thù Q&A: Chỉ xóa những dòng KHÔNG thuộc loại "auto_init" để không làm hỏng dữ liệu khởi tạo mặc định của hệ thống
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
                                // Lọc chống thừa cột (chỉ insert cột thực tế đang tồn tại ở Schema vật lý hiện hành)
                                if (colName in existingColumns) {
                                    columnsToInsert.add(colName)
                                }
                            }

                            if (columnsToInsert.isEmpty()) continue

                            val columnsStr = columnsToInsert.joinToString(",") { "`$it`" }
                            val placeholdersStr = columnsToInsert.joinToString(",") { "?" }
                            val sql = "INSERT OR REPLACE INTO `$tableName` ($columnsStr) VALUES ($placeholdersStr)"

                            for (rowIndex in 0 until rowsArray.length()) {
                                val rowObj = rowsArray.getJSONObject(rowIndex)
                                val bindArgs = arrayOfNulls<Any>(columnsToInsert.size)
                                for (colIndex in columnsToInsert.indices) {
                                    val colName = columnsToInsert[colIndex]
                                    val value = rowObj.opt(colName)

                                    bindArgs[colIndex] = when {
                                        value == JSONObject.NULL -> null
                                        // GIẢI MÃ BLOB: Đọc nhãn định dạng để giải mã Base64 sang mảng byte chuẩn
                                        value is JSONObject && value.optString("_type") == "blob" -> {
                                            val base64Data = value.getString("data")
                                            android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                                        }
                                        else -> value
                                    }
                                }
                                sdb.execSQL(sql, bindArgs)
                                restoredCount++
                            }

                            // NGHIỆP VỤ ĐẶC THÙ (CAMERAS): Tự sinh cấu hình CustomerSetting mặc định nếu chưa tồn tại
                            if (tableName == "cameras") {
                                val cursor = sdb.query("SELECT DISTINCT `customerId` FROM `cameras` WHERE `customerId` != ''", null)
                                if (cursor.moveToFirst()) {
                                    do {
                                        val customerId = cursor.getString(0)
                                        val checkCursor = sdb.query("SELECT 1 FROM `customer_settings` WHERE `customerId` = ?", arrayOf(customerId))
                                        val exists = checkCursor.count > 0
                                        checkCursor.close()
                                        if (!exists) {
                                            val now = System.currentTimeMillis()
                                            sdb.execSQL(
                                                "INSERT OR REPLACE INTO `customer_settings` (`customerId`, `smartMode`, `isActive`, `updatedAt`, `timestamp`) VALUES (?, 0, 1, ?, ?)",
                                                arrayOf(customerId, now, now)
                                            )
                                            restoredCount++
                                        }
                                    } while (cursor.moveToNext())
                                }
                                cursor.close()
                            }
                        }
                        
                        sdb.execSQL("PRAGMA foreign_keys=ON;")
                        sdb.setTransactionSuccessful()
                    } finally {
                        sdb.endTransaction()
                    }
                }

                // Đồng bộ hóa RAM Cache của TrainingSkill được cập nhật tức thời ngay sau khi Import dữ liệu thành công
                trainingSkill.refreshQAList("default_user")

                // TỰ ĐỘNG ĐỒNG BỘ BẢN SAO SỐ LÊN DASHBOARD: Cập nhật sơ đồ thiết bị ngay lập tức sau khi import
                try {
                    cameraSkill.initialize()
                    tuyaManager.loadDevicesFromDB()
                } catch (e: Exception) {
                    logger.e("SettingsViewModel", "Khởi tạo lại sơ đồ camera/tuya sau khi import thất bại", e)
                }

                // Kích hoạt lại lịch trình tự động hóa
                if (dataJson?.has("schedules") == true || dataJson?.has("ScheduleEntity") == true) {
                    com.aichatvn.agent.scheduler.TaskScheduler.runNow(context)
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