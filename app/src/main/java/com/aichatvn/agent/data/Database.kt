package com.aichatvn.agent.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aichatvn.agent.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

// ==================== CONVERTERS ====================

class Converters {
    @TypeConverter
    fun fromStringList(value: String?): List<String>? {
        if (value == null) return null
        val type = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(value, type)
    }
    
    @TypeConverter
    fun toStringList(list: List<String>?): String? {
        return list?.let { Gson().toJson(it) }
    }
}

// ==================== CUSTOMER DAO ====================

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomersFlow(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    suspend fun getAllCustomers(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :customerId")
    suspend fun getCustomerById(customerId: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity)

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomer(customerId: String)
}

// ==================== CHAT MESSAGE DAO ====================

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE username = :username ORDER BY timestamp ASC")
    fun getMessagesFlow(username: String): Flow<List<ChatMessageEntity>>
    
    @Query("SELECT * FROM chat_messages WHERE username = :username ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessages(username: String, limit: Int = 500): List<ChatMessageEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)
    
    @Query("DELETE FROM chat_messages WHERE username = :username")
    suspend fun clearMessages(username: String)
    
    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getAllMessagesRaw(limit: Int): List<ChatMessageEntity>

    @Query("""
        SELECT m1.* FROM chat_messages m1
        INNER JOIN (
            SELECT username, MAX(timestamp) as max_ts
            FROM chat_messages
            GROUP BY username
        ) m2 ON m1.username = m2.username AND m1.timestamp = m2.max_ts
        ORDER BY m1.timestamp DESC
    """)
    fun getLatestChatThreadsFlow(): Flow<List<ChatMessageEntity>>

    @Query("""
        SELECT username, COUNT(*) as unreadCount
        FROM chat_messages
        WHERE role = 'user' AND isRead = 0
        GROUP BY username
    """)
    fun getUnreadCountsFlow(): Flow<List<ThreadUnreadCount>>

    @Query("UPDATE chat_messages SET isRead = 1 WHERE username = :username AND role = 'user' AND isRead = 0")
    suspend fun markThreadAsRead(username: String)
}

data class ThreadUnreadCount(
    val username: String,
    val unreadCount: Int
)

// ==================== Q&A DAO ====================

@Dao
interface QADao {
    @Query("SELECT * FROM qa_data WHERE createdBy = :username ORDER BY timestamp DESC")
    fun getAllQAsFlow(username: String): Flow<List<QAEntity>>
    
    @Query("SELECT * FROM qa_data WHERE createdBy = :username ORDER BY timestamp DESC")
    suspend fun getAllQAs(username: String): List<QAEntity>
    
    @Query("SELECT * FROM qa_data WHERE id = :id AND createdBy = :username")
    suspend fun getQAById(id: String, username: String): QAEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQA(qa: QAEntity)
    
    @Update
    suspend fun updateQA(qa: QAEntity)
    
    @Query("DELETE FROM qa_data WHERE id = :id AND createdBy = :username")
    suspend fun deleteQA(id: String, username: String)
    
    @Query("DELETE FROM qa_data WHERE createdBy = :username")
    suspend fun deleteAllQAs(username: String)
    
    @Query("""
        SELECT * FROM qa_data 
        WHERE createdBy = :username 
        AND (question LIKE '%' || :query || '%' OR answer LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
    """)
    suspend fun searchQAs(query: String, username: String): List<QAEntity>
}

// ==================== TUYA DEVICE DAO ====================

@Dao
interface TuyaDeviceDao {
    @Query("SELECT * FROM tuya_devices ORDER BY name ASC")
    fun getAllDevicesFlow(): Flow<List<TuyaDeviceEntity>>
    
    @Query("SELECT * FROM tuya_devices ORDER BY name ASC")
    suspend fun getAllDevices(): List<TuyaDeviceEntity>
    
    @Query("SELECT * FROM tuya_devices WHERE id = :deviceId")
    suspend fun getDeviceById(deviceId: String): TuyaDeviceEntity?
    
    @Query("SELECT * FROM tuya_devices WHERE name = :name")
    suspend fun getDeviceByName(name: String): TuyaDeviceEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: TuyaDeviceEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllDevices(devices: List<TuyaDeviceEntity>)
    
    @Query("UPDATE tuya_devices SET online = :online, lastSeen = :timestamp WHERE id = :deviceId")
    suspend fun updateOnlineStatus(deviceId: String, online: Boolean, timestamp: Long)

    // ✅ MỚI: cập nhật RIÊNG 4 cột điều khiển local — KHÔNG dùng insertDevice/insertAllDevices
    // (OnConflictStrategy.REPLACE, ghi đè NGUYÊN dòng) cho việc này, vì scanDevices() gọi
    // insertAllDevices() định kỳ với entity dựng lại từ Cloud API (không có local_key) sẽ xoá
    // mất các cột này nếu không tách riêng. Xem ghi chú "SỬA" trong TuyaManager.scanDevices().
    @Query("""
        UPDATE tuya_devices
        SET localKey = :localKey, lastKnownIp = :lastKnownIp,
            protocolVersion = :protocolVersion, localSwitchDpId = :localSwitchDpId
        WHERE id = :deviceId
    """)
    suspend fun updateLocalControlInfo(
        deviceId: String,
        localKey: String?,
        lastKnownIp: String?,
        protocolVersion: String?,
        localSwitchDpId: String?
    )
    
    @Query("DELETE FROM tuya_devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String)
    
    @Query("DELETE FROM tuya_devices")
    suspend fun deleteAllDevices()
}

// ==================== MQTT DEVICE DAO ====================

// ✅ MỚI (Tầng 3): CRUD tối thiểu — chỉ những gì MqttDeviceController.listDevices()/
// deleteDevice()/getStatus() thật sự cần, theo đúng khuôn TuyaDeviceDao ở trên.
@Dao
interface MqttDeviceDao {
    @Query("SELECT * FROM mqtt_devices ORDER BY name ASC")
    fun getAllDevicesFlow(): Flow<List<MqttDeviceEntity>>

    @Query("SELECT * FROM mqtt_devices ORDER BY name ASC")
    suspend fun getAllDevices(): List<MqttDeviceEntity>

    @Query("SELECT * FROM mqtt_devices WHERE id = :deviceId")
    suspend fun getDeviceById(deviceId: String): MqttDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: MqttDeviceEntity)

    // ✅ Ghi trạng thái nhận được qua subscribe — tách riêng khỏi insertDevice() giống lý do
    // TuyaDeviceDao.updateLocalControlInfo() tách riêng: tránh ghi đè mất topic/name nếu
    // sau này có nơi khác insertDevice() lại với entity dựng thiếu field.
    @Query("UPDATE mqtt_devices SET lastKnownState = :state, online = :online, lastSeen = :timestamp WHERE id = :deviceId")
    suspend fun updateState(deviceId: String, state: Boolean, online: Boolean, timestamp: Long)

    @Query("DELETE FROM mqtt_devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    // ✅ MỚI (version 25 — mục 4.3 kế hoạch): cập nhật RIÊNG deviceLanIp/deviceMac, tách khỏi
    // insertDevice() (REPLACE, ghi đè nguyên dòng) đúng lý do TuyaDeviceDao.updateLocalControlInfo()
    // đã tách riêng — TasmotaLanDiscovery chạy độc lập, ghi qua insertDevice() sẽ xoá mất
    // commandTopic/stateTopic đã lưu trước đó. Gọi từ TasmotaLanDiscovery sau mỗi lần dò LAN
    // thành công.
    @Query("UPDATE mqtt_devices SET deviceLanIp = :lanIp, deviceMac = :mac WHERE id = :deviceId")
    suspend fun updateLanInfo(deviceId: String, lanIp: String?, mac: String?)

    // ✅ MỚI (version 25): cập nhật RIÊNG brokerMode theo deviceId — gọi từ
    // MqttBrokerElectionManager sau mỗi lần bầu.
    @Query("UPDATE mqtt_devices SET brokerMode = :brokerMode WHERE id = :deviceId")
    suspend fun updateBrokerMode(deviceId: String, brokerMode: String)

    // ✅ MỚI (version 25): danh sách thiết bị có deviceLanIp đã biết — cần cho
    // TasmotaMqttHostMigrator biết migrate thiết bị nào, bỏ qua (không silently fail) thiết bị
    // chưa có IP đã biết — đúng tinh thần rủi ro mục 5.3.
    @Query("SELECT * FROM mqtt_devices WHERE deviceLanIp IS NOT NULL")
    suspend fun getDevicesWithLanIp(): List<MqttDeviceEntity>
}

// ==================== CAMERA DAO ====================

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY timestamp DESC")
    fun getAllCamerasFlow(): Flow<List<CameraConfigEntity>>
    
    @Query("SELECT * FROM cameras ORDER BY timestamp DESC")
    suspend fun getAllCameras(): List<CameraConfigEntity>
    
    @Query("SELECT * FROM cameras WHERE customerId = :customerId")
    suspend fun getCamerasByCustomer(customerId: String): List<CameraConfigEntity>
    
    @Query("SELECT * FROM cameras WHERE id = :cameraId")
    suspend fun getCameraById(cameraId: String): CameraConfigEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: CameraConfigEntity)
    
    @Update
    suspend fun updateCamera(camera: CameraConfigEntity)
    
    @Query("DELETE FROM cameras WHERE id = :cameraId")
    suspend fun deleteCamera(cameraId: String)
    
    @Query("DELETE FROM cameras WHERE customerId = :customerId")
    suspend fun deleteCamerasByCustomer(customerId: String)
    
    @Query("SELECT * FROM cameras WHERE manualOff = 0")
    suspend fun getActiveCameras(): List<CameraConfigEntity>
    
    @Query("SELECT * FROM customer_settings WHERE customerId = :customerId")
    suspend fun getCustomerSetting(customerId: String): CustomerSettingEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomerSetting(setting: CustomerSettingEntity)
    
    @Query("UPDATE customer_settings SET smartMode = :enabled, updatedAt = :timestamp WHERE customerId = :customerId")
    suspend fun updateSmartMode(customerId: String, enabled: Boolean, timestamp: Long)

    @Query("UPDATE customer_settings SET lastFacebookPageId = :pageId WHERE customerId = :customerId")
    suspend fun updateLastFacebookPageId(customerId: String, pageId: String)
    
    @Query("UPDATE customer_settings SET isActive = :active, updatedAt = :timestamp WHERE customerId = :customerId")
    suspend fun updateActiveStatus(customerId: String, active: Boolean, timestamp: Long)
    
    @Query("DELETE FROM customer_settings WHERE customerId = :customerId")
    suspend fun deleteCustomerSetting(customerId: String)

    @Query("UPDATE cameras SET smartMode = :enabled WHERE id = :cameraId")
    suspend fun updateCameraSmartMode(cameraId: String, enabled: Int)
}

// ==================== ALERT DAO ====================

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun getAllAlertsFlow(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE cameraId = :cameraId ORDER BY timestamp DESC")
    fun getAlertsByCameraFlow(cameraId: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE id = :alertId")
    suspend fun getAlertById(alertId: String): AlertEntity?

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM alerts WHERE timestamp >= :since")
    fun getAlertCountSinceFlow(since: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertEntity)

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :alertId")
    suspend fun markAsRead(alertId: String)

    @Query("UPDATE alerts SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("UPDATE alerts SET isRead = 1 WHERE cameraId = :cameraId")
    suspend fun markAllAsReadForCamera(cameraId: String)

    @Query("DELETE FROM alerts WHERE id = :alertId")
    suspend fun deleteAlert(alertId: String)

    @Query("DELETE FROM alerts")
    suspend fun deleteAllAlerts()

    @Query("DELETE FROM alerts WHERE cameraId = :cameraId")
    suspend fun deleteAlertsByCamera(cameraId: String)

    @Query("DELETE FROM alerts WHERE timestamp < :beforeTimestamp")
    suspend fun deleteAlertsOlderThan(beforeTimestamp: Long)

    @Query("SELECT * FROM alerts WHERE cameraId = :cameraId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAlertForCamera(cameraId: String): AlertEntity?

    @Query("UPDATE alerts SET endTime = :endTime WHERE id = :alertId")
    suspend fun updateAlertEndTime(alertId: String, endTime: Long)

    // ✅ MỚI: dùng khi gộp cảnh báo (merge alert) — trước đây chỉ có updateAlertEndTime(),
    // khiến push notification hiện nội dung MỚI nhất (aiComment mới) nhưng alert_history vẫn
    // giữ nội dung/ảnh của lần alert ĐẦU TIÊN trong chuỗi gộp, gây lệch thông tin khi người
    // dùng bấm vào thông báo để xem chi tiết. imagePath dùng COALESCE để không xóa ảnh cũ nếu
    // lần merge này không kèm ảnh mới.
    // ✅ SỬA: trước đây chỉ cập nhật diff, còn delta/deltaTrigger/absDiffTrigger/drift/
    // baselineDiff/driftTrigger vẫn giữ nguyên của lần quét ĐẦU TIÊN tạo nhóm — khiến diff
    // hiển thị (mới) và delta/drift hiển thị (cũ) thuộc 2 lần quét khác nhau, không nhất
    // quán. Đặc biệt gây sai lệch khi người dùng bấm "Báo giả" trên 1 alert đã gộp: giá trị
    // gửi vào markFalsePositiveAndLearn() là cặp diff/delta không cùng lần quét thật. Giờ cập
    // nhật toàn bộ theo lần quét mới nhất để 1 alert luôn phản ánh đúng 1 lần quét duy nhất.
    @Query("""
        UPDATE alerts
        SET endTime = :endTime,
            aiComment = :aiComment,
            diff = :diff,
            delta = :delta,
            deltaTrigger = :deltaTrigger,
            absDiffTrigger = :absDiffTrigger,
            drift = :drift,
            baselineDiff = :baselineDiff,
            driftTrigger = :driftTrigger,
            imagePath = COALESCE(:imagePath, imagePath),
            aiStateJson = COALESCE(:aiStateJson, aiStateJson)
        WHERE id = :alertId
    """)
    suspend fun mergeAlertUpdate(
        alertId: String,
        endTime: Long,
        aiComment: String,
        diff: Int,
        delta: Int,
        deltaTrigger: Int,
        absDiffTrigger: Int,
        drift: Int,
        baselineDiff: Int,
        driftTrigger: Int,
        imagePath: String?,
        aiStateJson: String?
    )
}

// ==================== SCHEDULE DAO ====================

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE enabled = 1 ORDER BY createdAt DESC")
    fun getActiveSchedulesFlow(): Flow<List<ScheduleEntity>>
    
    @Query("SELECT * FROM schedules ORDER BY createdAt DESC")
    suspend fun getAllSchedules(): List<ScheduleEntity>
    
    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: String): ScheduleEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity)
    
    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)
    
    @Query("UPDATE schedules SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun updateLastRun(id: String, timestamp: Long)
    
    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)
    
    @Query("UPDATE schedules SET enabled = :enabled WHERE id = :id")
    suspend fun toggleSchedule(id: String, enabled: Int)
}

// ==================== APP CONFIG DAO ====================

@Dao
interface AppConfigDao {
    @Query("SELECT * FROM app_config ORDER BY pluginId ASC, key ASC")
    fun getAllConfigsFlow(): Flow<List<AppConfigEntity>>

    @Query("SELECT * FROM app_config WHERE pluginId = :pluginId ORDER BY key ASC")
    fun getConfigsByPluginFlow(pluginId: String): Flow<List<AppConfigEntity>>

    @Query("SELECT * FROM app_config WHERE key = :key")
    suspend fun getConfig(key: String): AppConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AppConfigEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(config: AppConfigEntity)

    @Query("DELETE FROM app_config WHERE key = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM app_config ORDER BY pluginId ASC, key ASC")
    suspend fun getAll(): List<AppConfigEntity>
}

// ==================== MULTI FACEBOOK PAGES DAO ====================

@Dao
interface FacebookPageDao {
    @Query("SELECT * FROM facebook_pages")
    fun getAllPagesFlow(): Flow<List<FacebookPageEntity>>

    @Query("SELECT * FROM facebook_pages")
    suspend fun getAllPages(): List<FacebookPageEntity>

    @Query("SELECT * FROM facebook_pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageById(pageId: String): FacebookPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<FacebookPageEntity>)

    @Query("DELETE FROM facebook_pages WHERE id = :pageId")
    suspend fun deletePage(pageId: String)

    @Query("DELETE FROM facebook_pages")
    suspend fun clearAll()
}

// ==================== EVENT LOG DAO (TRÍ NHỚ) ====================

@Dao
interface EventLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: EventLogEntity)

    @Query("SELECT * FROM event_logs WHERE timestamp >= :since AND timestamp <= :until ORDER BY timestamp ASC")
    suspend fun getLogsInTimeframe(since: Long, until: Long): List<EventLogEntity>

    @Query("SELECT * FROM event_logs WHERE source = :source ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestLogsBySource(source: String, limit: Int): List<EventLogEntity>

    @Query("DELETE FROM event_logs WHERE timestamp < :beforeTimestamp")
    suspend fun pruneLogsOlderThan(beforeTimestamp: Long)

    // ✅ MỚI (Tuần 5 - Console CRUD): Cho phép xóa từng log và lấy Flow quan sát danh sách thời gian thực
    @Query("DELETE FROM event_logs WHERE id = :id")
    suspend fun deleteLogById(id: String)

    @Query("DELETE FROM event_logs")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC LIMIT 100")
    fun getLatestLogsFlow(): Flow<List<EventLogEntity>>
}

// ==================== WORLD STATE DAO (BẢN SAO SỐ) ====================

@Dao
interface WorldStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: WorldStateEntity)

    @Query("SELECT * FROM world_state WHERE source = :source AND sourceId = :sourceId LIMIT 1")
    suspend fun getState(source: String, sourceId: String): WorldStateEntity?

    // ✅ MỚI (Tuần 5 - Console CRUD): Hỗ trợ dọn dẹp các trạng thái mồ côi/cũ khi đổi thiết bị và lấy Flow quan sát
    @Query("DELETE FROM world_state WHERE id = :id")
    suspend fun deleteStateById(id: String)

    @Query("DELETE FROM world_state WHERE source = :source AND sourceId = :sourceId")
    suspend fun deleteStateBySourceAndId(source: String, sourceId: String)

    @Query("SELECT * FROM world_state ORDER BY id ASC")
    fun getAllStatesFlow(): Flow<List<WorldStateEntity>>
}

// ==================== MIGRATIONS ====================

// ✅ SỬA: version 17 (thêm cột EventLogEntity.analysisSource) đã được cài cho khách hàng thật ở
// version 16 — KHÔNG được để fallbackToDestructiveMigration() xoá sạch DB của họ nữa. Viết
// Migration thật, chỉ 1 ALTER TABLE ADD COLUMN đúng bằng đúng thay đổi entity đã ghi trong
// comment ở @Database bên dưới (EventLogEntity thêm analysisSource: String? = null).
// ⚠️ TỪ NAY VỀ SAU: mỗi lần bump version, PHẢI viết thêm 1 Migration(N, N+1) tương ứng ở đây rồi
// add vào .addMigrations(...) phía dưới — KHÔNG dựa vào fallbackToDestructiveMigration() nữa.
// Room ưu tiên dùng Migration đã đăng ký nếu có đường đi hợp lệ; fallbackToDestructiveMigration()
// chỉ nên còn lại như lưới an toàn cho các bản dev/test rất cũ không còn ai dùng, KHÔNG phải cơ
// chế "migration" chính thức cho bản đã phát hành.
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE event_logs ADD COLUMN analysisSource TEXT")
    }
}

// ✅ MỚI: version 18 — CameraConfigEntity thêm snapshotUsername/snapshotPassword (Basic Auth cho
// camera LAN). Theo đúng quy tắc đã đặt ở trên: bump version PHẢI kèm Migration thật, không dựa
// vào fallbackToDestructiveMigration() để tránh mất dữ liệu khách hàng đang chạy version 17.
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN snapshotUsername TEXT")
        db.execSQL("ALTER TABLE cameras ADD COLUMN snapshotPassword TEXT")
    }
}

// ✅ MỚI: version 19 — CameraConfigEntity thêm snapshotUrlRemote (URL cloud/public dự phòng khi
// LAN không kết nối được, vd đang ở ngoài mạng nhà). Theo đúng quy tắc: bump version PHẢI kèm
// Migration thật, không dựa vào fallbackToDestructiveMigration() để tránh mất dữ liệu khách hàng
// đang chạy version 18.
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN snapshotUrlRemote TEXT")
    }
}

// ✅ MỚI: version 20 — CameraConfigEntity thêm enableAlarmPush/alarmSecret (báo động camera
// qua Alarm Server URL, xem camera_alarm_secrets phía gateway). Entity đã có 2 cột này
// nhưng version DB chưa từng được bump kèm migration tương ứng — đây là nguyên nhân khiến
// SELECT * FROM cameras ném "no such column: enableAlarmPush" âm thầm (bị BackupRestorer
// nuốt lỗi), làm seed/import tưởng thành công nhưng camera không được nạp vào bộ nhớ.
// enableAlarmPush mặc định 0 (tắt) để khớp default trong Entity.kt, alarmSecret mặc định
// NULL vì chưa có secret nào được cấp cho tới khi người dùng bật toggle.
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN enableAlarmPush INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cameras ADD COLUMN alarmSecret TEXT DEFAULT NULL")
    }
}

// ✅ MỚI: version 21 — CameraConfigEntity thêm 6 cột RTSP/ONVIF (onvifSupported,
// onvifEventUrl, onvifEnabled, rtspSupported, rtspUrl, rtspEnabled — xem
// CameraCapabilityProber.kt). Entity đã có 6 cột này từ trước nhưng CHƯA TỪNG được bump
// version kèm migration — cùng lỗi đã gặp với enableAlarmPush ở MIGRATION_19_20: mọi
// SELECT * FROM cameras hiện tại đang ném "no such column" âm thầm (bị BackupRestorer
// nuốt lỗi), coi như tính năng RTSP/ONVIF/camera-alarm đã merge nhưng KHÔNG chạy được
// trên máy đã cài version 20. Vá ngay tại đây, không trì hoãn thêm.
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN onvifSupported INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cameras ADD COLUMN onvifEventUrl TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN onvifEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cameras ADD COLUMN rtspSupported INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cameras ADD COLUMN rtspUrl TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN rtspEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

// ✅ MỚI: version 22 — TuyaDeviceEntity thêm 4 cột điều khiển LOCAL qua LAN (localKey,
// lastKnownIp, protocolVersion, localSwitchDpId — xem TuyaLocalController.kt +
// TuyaManager.fetchLocalKey()/resolveLocalDpId()). Tất cả nullable/mặc định NULL —
// thiết bị cũ chưa từng fetch local_key vẫn hoạt động bình thường qua Cloud API như cũ,
// chỉ khi nào các cột này có giá trị thì TuyaManager mới thử đường local trước.
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tuya_devices ADD COLUMN localKey TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE tuya_devices ADD COLUMN lastKnownIp TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE tuya_devices ADD COLUMN protocolVersion TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE tuya_devices ADD COLUMN localSwitchDpId TEXT DEFAULT NULL")
    }
}

// ✅ MỚI: version 23 — thêm bảng mqtt_devices (Tầng 3, xem MqttDeviceEntity/MqttDeviceDao).
// Bảng mới hoàn toàn nên dùng CREATE TABLE, không phải ALTER TABLE ADD COLUMN như các
// migration trước — không có dữ liệu cũ nào bị ảnh hưởng.
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mqtt_devices (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                topic TEXT NOT NULL,
                online INTEGER NOT NULL DEFAULT 0,
                lastKnownState INTEGER NOT NULL DEFAULT 0,
                lastSeen INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

// ✅ MỚI: version 24 — track Cloud Broker V1 (xem MQTT_CLOUD_BROKER_PLAN.md mục 3). Thêm cột
// cho MqttDeviceEntity: brokerSource, discoverySource, commandTopic, stateTopic, onPayload,
// offPayload. Giữ tương thích ngược với thiết bị đã thêm qua bản thử nghiệm trước (MIGRATION_22_23
// — chỉ có cột `topic`, không có commandTopic riêng): copy topic -> commandTopic cho dữ liệu cũ,
// gán discoverySource = 'manual' (đúng thực tế — bản cũ chỉ có nhập tay, không có discovery).
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN brokerSource TEXT NOT NULL DEFAULT 'cloud'")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN discoverySource TEXT NOT NULL DEFAULT 'manual'")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN commandTopic TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN stateTopic TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN onPayload TEXT NOT NULL DEFAULT 'ON'")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN offPayload TEXT NOT NULL DEFAULT 'OFF'")
        // Dữ liệu cũ: copy topic đã có sẵn sang commandTopic để không mất khả năng điều khiển
        // thiết bị đã thêm từ trước khi có migration này.
        db.execSQL("UPDATE mqtt_devices SET commandTopic = topic WHERE commandTopic = ''")
    }
}

// ✅ MỚI: version 24 → 25 (MQTT_SYMMETRIC_BROKER_PLAN.md mục 4.2). Thêm 3 cột cho
// MqttDeviceEntity phục vụ Symmetric Broker Election + Tasmota MqttHost migration:
// deviceLanIp, deviceMac (nullable, không phá dữ liệu cũ), brokerMode (NOT NULL DEFAULT
// 'cloud' — khớp đúng brokerSource mặc định đã có từ MIGRATION_23_24, không tạo trạng thái
// mới mâu thuẫn cho dữ liệu cũ). Theo đúng khuôn ALTER TABLE ... ADD COLUMN các migration
// trước (17→24 toàn dùng cách này, không CREATE lại bảng). KHÔNG đổi/đụng cột `topic` cũ.
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN deviceLanIp TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN deviceMac TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE mqtt_devices ADD COLUMN brokerMode TEXT NOT NULL DEFAULT 'cloud'")
    }
}

// ✅ MỚI: version 25 → 26 — CameraConfigEntity thêm deviceId/vendor (danh tính camera từ QR
// scan/LAN discovery — xem CameraQrDiscovery.kt/CameraDiscoveryDialog.kt). Trước đây 2 giá trị
// này chỉ được ghi vào landinfo dạng text tự do, không query được. Cả 2 nullable TEXT, DEFAULT
// NULL — camera cũ (thêm thủ công, không qua discovery/QR) không có gì để điền, giữ nguyên hành
// vi cũ (đọc ra null). Cùng khuôn ALTER TABLE ADD COLUMN như mọi migration 17→25 trước đó.
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN deviceId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN vendor TEXT DEFAULT NULL")
    }
}

// ✅ MỚI: version 26 → 27 — CameraConfigEntity thêm 4 cột cho tính năng verify/gợi ý
// snapshotUrlRemote (xem ghi chú tại khai báo field trong Entity.kt):
// snapshotUrlRemoteLastVerifiedAt/snapshotUrlRemoteHealthy (health-check định kỳ) và
// ddnsHost/ddnsPort (gợi ý dò URL cloud khi có DDNS/port-forward). Tất cả nullable, DEFAULT
// NULL — camera cũ không có gì để điền, giữ nguyên hành vi đọc ra null, cùng khuôn ALTER TABLE
// ADD COLUMN như mọi migration 17→26 trước đó.
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN snapshotUrlRemoteLastVerifiedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN snapshotUrlRemoteHealthy INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN ddnsHost TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE cameras ADD COLUMN ddnsPort INTEGER DEFAULT NULL")
    }
}

// ✅ MỚI: version 27 → 28 — CameraConfigEntity thêm cột onvifMotionObserved (xem ghi chú tại
// khai báo field trong Entity.kt / OnvifEventRelay). Nullable, DEFAULT NULL — camera cũ (chưa
// từng chạy PullPoint đủ mẫu) giữ nguyên hành vi đọc ra null, cùng khuôn ALTER TABLE ADD COLUMN
// như mọi migration 17→27 trước đó.
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cameras ADD COLUMN onvifMotionObserved INTEGER DEFAULT NULL")
    }
}

// ✅ MỚI: version 28 → 29 — vá lệch schema bảng cameras phát hiện qua crash
// "Migration didn't properly handle: cameras" trên máy đã cài từ bản cũ (đi qua đủ
// chuỗi migration), không xảy ra trên máy cài mới (Room tự CREATE TABLE đúng entity).
//
// Nguyên nhân: MIGRATION_25_26 vẫn ADD COLUMN deviceId/vendor, nhưng CameraConfigEntity
// đã bỏ 2 field này từ lâu. Đồng thời entity đã thêm field onvifDeviceServiceUrl
// (@ColumnInfo defaultValue NULL) nhưng CHƯA từng có migration nào ADD COLUMN nó vào DB
// thật — 2 lỗi cộng dồn khiến "Found" (DB thật) và "Expected" (entity) không khớp:
// Found thừa deviceId/vendor, thiếu onvifDeviceServiceUrl.
//
// SQLite trên nhiều bản Android còn hỗ trợ hạn chế ALTER TABLE DROP COLUMN, nên xoá cột
// theo cách chuẩn Room khuyến nghị: tạo bảng mới đúng khớp entity hiện tại (giữ đúng thứ
// tự cột trong Entity.kt để khỏi lệch TableInfo lần nữa), copy dữ liệu qua bằng danh sách
// cột tường minh (bỏ deviceId/vendor, cột mới onvifDeviceServiceUrl lấy default NULL), xoá
// bảng cũ, đổi tên bảng mới về "cameras".
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cameras_new (
                id TEXT NOT NULL PRIMARY KEY,
                customerId TEXT NOT NULL,
                customername TEXT NOT NULL,
                customeremail TEXT NOT NULL,
                snapshoturl TEXT NOT NULL,
                landinfo TEXT,
                snapshotPath TEXT,
                timestamp INTEGER NOT NULL,
                status TEXT NOT NULL,
                isOnline INTEGER NOT NULL,
                manualOff INTEGER NOT NULL,
                smartMode INTEGER NOT NULL,
                aiPrompt TEXT NOT NULL,
                aiPositiveKeywords TEXT NOT NULL,
                aiNegativeKeywords TEXT NOT NULL,
                enableCooldown INTEGER NOT NULL,
                enableNotification INTEGER NOT NULL,
                alertActions TEXT NOT NULL,
                snapshotUsername TEXT,
                snapshotPassword TEXT,
                snapshotUrlRemote TEXT,
                enableAlarmPush INTEGER NOT NULL DEFAULT 0,
                alarmSecret TEXT DEFAULT NULL,
                onvifSupported INTEGER NOT NULL DEFAULT 0,
                onvifEventUrl TEXT DEFAULT NULL,
                onvifDeviceServiceUrl TEXT DEFAULT NULL,
                onvifEnabled INTEGER NOT NULL DEFAULT 0,
                rtspSupported INTEGER NOT NULL DEFAULT 0,
                rtspUrl TEXT DEFAULT NULL,
                rtspEnabled INTEGER NOT NULL DEFAULT 0,
                snapshotUrlRemoteLastVerifiedAt INTEGER DEFAULT NULL,
                snapshotUrlRemoteHealthy INTEGER DEFAULT NULL,
                ddnsHost TEXT DEFAULT NULL,
                ddnsPort INTEGER DEFAULT NULL,
                onvifMotionObserved INTEGER DEFAULT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO cameras_new (
                id, customerId, customername, customeremail, snapshoturl, landinfo,
                snapshotPath, timestamp, status, isOnline, manualOff, smartMode, aiPrompt,
                aiPositiveKeywords, aiNegativeKeywords, enableCooldown, enableNotification,
                alertActions, snapshotUsername, snapshotPassword, snapshotUrlRemote,
                enableAlarmPush, alarmSecret, onvifSupported, onvifEventUrl,
                onvifDeviceServiceUrl, onvifEnabled, rtspSupported, rtspUrl, rtspEnabled,
                snapshotUrlRemoteLastVerifiedAt, snapshotUrlRemoteHealthy, ddnsHost, ddnsPort,
                onvifMotionObserved
            )
            SELECT
                id, customerId, customername, customeremail, snapshoturl, landinfo,
                snapshotPath, timestamp, status, isOnline, manualOff, smartMode, aiPrompt,
                aiPositiveKeywords, aiNegativeKeywords, enableCooldown, enableNotification,
                alertActions, snapshotUsername, snapshotPassword, snapshotUrlRemote,
                enableAlarmPush, alarmSecret, onvifSupported, onvifEventUrl,
                NULL, onvifEnabled, rtspSupported, rtspUrl, rtspEnabled,
                snapshotUrlRemoteLastVerifiedAt, snapshotUrlRemoteHealthy, ddnsHost, ddnsPort,
                onvifMotionObserved
            FROM cameras
            """.trimIndent()
        )
        db.execSQL("DROP TABLE cameras")
        db.execSQL("ALTER TABLE cameras_new RENAME TO cameras")
    }
}

// ✅ MỚI: version 29 → 30 — call_contacts/call_logs (CallContactEntity/CallLogEntity) đã có
// trong entities = [...] và Dao dùng thẳng NHƯNG chưa từng bump version hay viết migration
// tạo bảng cho chúng. Version annotation vẫn ở 29 trong khi entity list đã thêm 2 bảng mới
// → schema thật (Room tính từ entities) và schema lưu trong room_master_table của máy đã
// cài từ trước (chưa có 2 bảng call_*) LỆCH NHAU dù version number không đổi. Room phát
// hiện lệch khi checkIdentity() ở onOpen(), không tìm được migration nào khớp (không có
// version nào tăng để onUpgrade() kích hoạt) → rơi thẳng vào fallbackToDestructiveMigration()
// đang bật ở dưới, XOÁ SẠCH toàn bộ DB (không chỉ call_contacts — cả chat_messages, cameras,
// tuya_devices... mất theo) rồi tạo lại rỗng. Đây là nguyên nhân "đã lưu số nhưng bị mất
// danh bạ" — bảng được tạo lại nhưng rỗng vì toàn bộ DB vừa bị destructive-recreate.
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS call_contacts (
                deviceCode TEXT NOT NULL PRIMARY KEY,
                displayName TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                isFavorite INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                lastCalledAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS call_logs (
                callId TEXT NOT NULL PRIMARY KEY,
                peerDeviceCode TEXT NOT NULL,
                peerName TEXT,
                direction TEXT NOT NULL,
                status TEXT NOT NULL,
                isVideo INTEGER NOT NULL,
                startedAt INTEGER NOT NULL,
                durationSec INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_peerDeviceCode ON call_logs(peerDeviceCode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_startedAt ON call_logs(startedAt)")
    }
}

// ==================== DATABASE ====================

@Database(
    entities = [
        ChatMessageEntity::class,
        QAEntity::class,
        CameraConfigEntity::class,
        CustomerSettingEntity::class,
        AlertEntity::class,
        ScheduleEntity::class,
        TuyaDeviceEntity::class,
        AppConfigEntity::class,
        CustomerEntity::class,
        FacebookPageEntity::class,
        EventLogEntity::class,   
        WorldStateEntity::class,
        CallContactEntity::class,
        CallLogEntity::class,
        MqttDeviceEntity::class
    ],
    // bump 24 → 25 để track MQTT Symmetric Broker (mục 4.2) thêm deviceLanIp/deviceMac/
    // brokerMode vào MqttDeviceEntity (xem MIGRATION_24_25).
    // Trước đó bump 23 → 24 để track Cloud Broker V1 thêm cột MqttDeviceEntity (xem MIGRATION_23_24).
    // Trước đó bump 22 → 23 để thêm bảng mqtt_devices (Tầng 3 — xem MIGRATION_22_23).
    // ⚠️ Version 20 ĐÃ cài cho khách hàng thật — bắt buộc dùng đủ chuỗi Migration 16→...→26
    // (không chỉ riêng migration mới nhất) ở dưới, KHÔNG được để fallbackToDestructiveMigration()
    // xoá dữ liệu của họ.
    // ✅ SỬA: bump 25 → 26 — MIGRATION_25_26 (CameraConfigEntity thêm deviceId/vendor) đã được
    // viết sẵn nhưng version annotation vẫn để 25 và chưa có trong .addMigrations(...) bên dưới,
    // nghĩa là migration này KHÔNG BAO GIỜ chạy — Room sẽ phát hiện schema trong code (đã có 2 cột
    // mới) không khớp version đã export, có nguy cơ crash hoặc rơi vào fallbackToDestructiveMigration()
    // (xoá dữ liệu khách hàng thật, xem cảnh báo ngay trên). Bump version + đăng ký migration khớp
    // với MIGRATION_25_26 đã tồn tại từ trước, không đổi nội dung migration.
    // Trước đó bump 26 → 27 — MIGRATION_26_27 (CameraConfigEntity thêm 4 cột verify/DDNS cho
    // snapshotUrlRemote, xem ghi chú tại khai báo migration).
    // ✅ MỚI: bump 27 → 28 — MIGRATION_27_28 (CameraConfigEntity thêm cột onvifMotionObserved,
    // xem ghi chú tại khai báo migration).
    // ✅ MỚI: bump 28 → 29 — MIGRATION_28_29 vá lệch schema bảng cameras: entity đã bỏ
    // deviceId/vendor (còn sót lại từ MIGRATION_25_26) và đã thêm onvifDeviceServiceUrl
    // (chưa từng có migration ADD COLUMN tương ứng) — xem ghi chú đầy đủ tại khai báo
    // MIGRATION_28_29 phía trên, và crash "Migration didn't properly handle: cameras".
    // ✅ SỬA (root cause "mất danh bạ"): bump 29 → 30 — MIGRATION_29_30 tạo bảng
    // call_contacts/call_logs, vốn đã có trong entities list từ lâu nhưng CHƯA TỪNG có
    // migration tạo bảng thật, khiến máy cũ bị fallbackToDestructiveMigration() xoá sạch
    // DB mỗi lần schema lệch mà version không đổi — xem ghi chú đầy đủ tại khai báo
    // MIGRATION_29_30 phía trên.
    version = 30,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun qaDao(): QADao
    abstract fun cameraDao(): CameraDao
    abstract fun alertDao(): AlertDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun tuyaDeviceDao(): TuyaDeviceDao
    abstract fun customerDao(): CustomerDao
    abstract fun appConfigDao(): AppConfigDao
    abstract fun facebookPageDao(): FacebookPageDao
    
    abstract fun eventLogDao(): EventLogDao   
    abstract fun worldStateDao(): WorldStateDao 
    abstract fun callContactDao(): CallContactDao
    abstract fun callLogDao(): CallLogDao
    abstract fun mqttDeviceDao(): MqttDeviceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    // ✅ SỬA: đăng ký Migration thật cho 16→17, 17→18, 18→19, 19→20 — Room sẽ dùng
                    // đường đi này trước (giữ nguyên dữ liệu khách hàng). fallbackToDestructiveMigration()
                    // chỉ còn là lưới an toàn cho các bản version < 16 (nếu còn tồn tại, không rõ
                    // lịch sử) — KHÔNG áp dụng cho các bước đã có Migration cụ thể.
                    .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}