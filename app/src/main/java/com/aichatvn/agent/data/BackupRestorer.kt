package com.aichatvn.agent.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.skills.ScheduleSkill
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.utils.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ✅ MỚI: Logic phục hồi bản sao lưu (settings + toàn bộ bảng DB) được tách ra từ
 * SettingsViewModel.importSettings() để dùng chung cho cả 2 nơi:
 * 1) Màn Settings — người dùng chọn file JSON để import thủ công.
 * 2) MainApplication — tự động nạp dữ liệu demo (demo_seed.json trong assets) ở lần
 *    mở app đầu tiên.
 *
 * Hành vi và kết quả trả về giữ nguyên 100% so với importSettings() gốc.
 */
@Singleton
class BackupRestorer @Inject constructor(
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val tuyaManager: TuyaManager,
    private val scheduleSkill: ScheduleSkill,
    private val configProvider: AppConfigProvider,
    private val trainingSkill: TrainingSkill,
    private val logger: Logger
) {
    companion object {
        val GROQ_API_KEY   = stringPreferencesKey("groq_api_key")
        val RESEND_API_KEY = stringPreferencesKey("resend_api_key")
        val RESEND_SENDER  = stringPreferencesKey("resend_sender")
        val DARK_MODE      = booleanPreferencesKey("dark_mode")
        val TUYA_CLIENT_ID = stringPreferencesKey("tuya_client_id")
        val TUYA_CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
        val TUYA_UID = stringPreferencesKey("tuya_uid")

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
            "call_contacts"
        )
    }

    // ✅ MỚI: một số key trong app_config được TỰ SINH RIÊNG cho từng máy (khoá nhúng
    // widget website) — không được copy nguyên từ bản demo/backup của máy khác sang, nếu
    // không các máy khách sẽ dùng chung 1 khoá, gây xung đột hoặc lộ khả năng điều khiển
    // chéo giữa các máy.
    // Lưu ý: global.gateway_token KHÔNG nằm trong danh sách này — đây là mã xác minh bắt
    // buộc để app hoạt động (không phải mã sinh riêng theo máy), nên luôn được seed cùng
    // giá trị mặc định.
    private val MACHINE_SPECIFIC_CONFIG_KEYS = setOf(
        AppConfigDefaults.WEBSITE_WIDGET_KEY
    )

    /**
     * @param preserveMachineTokens true (mặc định) = phục hồi đầy đủ mọi giá trị trong
     *   app_config đúng như trong file backup — dùng cho Import thủ công ở màn Settings khi
     *   người dùng khôi phục lại chính máy của họ (đổi điện thoại...).
     *   false = bỏ qua các key máy-sinh riêng (widget_key), giữ nguyên giá trị hiện có trên
     *   máy (hoặc để trống nếu máy chưa từng sinh) — dùng khi nạp dữ liệu demo dùng chung
     *   cho mọi máy khách cài mới. gateway_token vẫn luôn được seed vì là mã xác minh bắt
     *   buộc, không phải mã riêng theo máy.
     */
    suspend fun restore(context: Context, jsonString: String, preserveMachineTokens: Boolean = true): String {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(jsonString)
                val gson = Gson()

                val exportVersion = json.optInt("export_version", 1)
                if (exportVersion > 4) {
                    return@withContext "❌ Lỗi: Bản sao lưu (v$exportVersion) mới hơn phiên bản ứng dụng hiện tại. Vui lòng cập nhật ứng dụng."
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
                                val filteredList = if (preserveMachineTokens) {
                                    list
                                } else {
                                    list.filter { it.key !in MACHINE_SPECIFIC_CONFIG_KEYS }
                                }
                                filteredList.forEach { configProvider.upsert(it) }
                                configProvider.cleanupDeadKeys()
                                restoredCount += filteredList.size
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
                    logger.e("BackupRestorer", "Khởi tạo lại sơ đồ camera/tuya/lịch trình sau khi import thất bại", e)
                }

                if (dataJson?.has("schedules") == true || dataJson?.has("ScheduleEntity") == true) {
                    logger.i("BackupRestorer", "🔄 Đã đồng bộ danh sách lịch trình vừa nạp từ bản sao lưu thành công.")
                }

                if (restoredCount > 0)
                    "✅ Import thành công! Đã phục hồi $restoredCount bản ghi cấu trúc Whitelist an toàn."
                else
                    "✅ Import thành công! (chỉ có settings, không có dữ liệu DB)"
            } catch (e: Exception) {
                logger.e("BackupRestorer", "Import error: ${e.message}", e)
                "❌ Lỗi: ${e.message}"
            }
        }
    }
}
