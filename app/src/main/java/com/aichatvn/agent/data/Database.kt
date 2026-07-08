package com.aichatvn.agent.data

import android.content.Context
import androidx.room.*
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

// ==================== DAOS ====================

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
    // ✅ ĐÃ THÊM: Truy vấn quét Hộp thư đến (Inbox) - Lấy tin nhắn mới nhất của từng khách hàng để làm danh sách.
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

    // ✅ MỚI: Đếm số tin nhắn khách CHƯA ĐỌC theo từng thread — dùng để hiện badge trên InboxScreen.
    // Chỉ đếm role="user" vì tin "assistant" (AI tự trả lời hoặc admin gõ tay) không cần badge.
    @Query("""
        SELECT username, COUNT(*) as unreadCount
        FROM chat_messages
        WHERE role = 'user' AND isRead = 0
        GROUP BY username
    """)
    fun getUnreadCountsFlow(): Flow<List<ThreadUnreadCount>>

    // ✅ MỚI: Đánh dấu toàn bộ tin nhắn khách của 1 thread là đã đọc — gọi khi Admin mở
    // ChatScreen của khách đó (xem ChatViewModel.init()).
    @Query("UPDATE chat_messages SET isRead = 1 WHERE username = :username AND role = 'user' AND isRead = 0")
    suspend fun markThreadAsRead(username: String)
}

// ✅ MỚI: Kết quả gộp nhóm cho getUnreadCountsFlow()
data class ThreadUnreadCount(
    val username: String,
    val unreadCount: Int
)

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
    
    @Query("DELETE FROM tuya_devices WHERE id = :deviceId")
    suspend fun deleteDevice(deviceId: String)
    
    @Query("DELETE FROM tuya_devices")
    suspend fun deleteAllDevices()
}


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

    // ✅ MỚI: Ghi lại Page ID Facebook gần nhất mà khách (customerId = PSID) vừa nhắn tới
    @Query("UPDATE customer_settings SET lastFacebookPageId = :pageId WHERE customerId = :customerId")
    suspend fun updateLastFacebookPageId(customerId: String, pageId: String)
    
    @Query("UPDATE customer_settings SET isActive = :active, updatedAt = :timestamp WHERE customerId = :customerId")
    suspend fun updateActiveStatus(customerId: String, active: Boolean, timestamp: Long)
    
    @Query("DELETE FROM customer_settings WHERE customerId = :customerId")
    suspend fun deleteCustomerSetting(customerId: String)

    @Query("UPDATE cameras SET smartMode = :enabled WHERE id = :cameraId")
    suspend fun updateCameraSmartMode(cameraId: String, enabled: Int)
}

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
}

// ==================== SCHEDULE DAO ====================

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE enabled = 1 ORDER BY createdAt DESC")
    fun getActiveSchedulesFlow(): Flow<List<ScheduleEntity>>
    
    @Query("SELECT * FROM schedules ORDER BY createdAt DESC")
    suspend fun getAllSchedules(): List<ScheduleEntity>
    
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
        FacebookPageEntity::class // ✅ ĐĂNG KÝ: Thực thể lưu nhiều trang Facebook
    ],
    version = 12, // ✅ TĂNG PHIÊN BẢN: Tăng phiên bản cấu trúc từ 11 lên 12 (thêm isRead cho chat_messages)

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
    abstract fun facebookPageDao(): FacebookPageDao // ✅ ĐĂNG KÝ DAO của Facebook Pages

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ✅ App đang trong giai đoạn test/dev, không cần viết Migration tay cho từng version nữa.
        // fallbackToDestructiveMigration() sẽ tự XOÁ VÀ TẠO LẠI toàn bộ database mỗi khi `version`
        // trong @Database tăng lên mà không khớp version cũ trên máy — mất hết dữ liệu cũ trong DB,
        // nhưng đổi lại không cần lo migration khi đổi schema liên tục.
        // ⚠️ Nếu sau này lên bản chính thức cho người dùng thật, PHẢI viết lại Migration đầy đủ,
        // nếu không mỗi lần tăng version sẽ xoá sạch dữ liệu người dùng.

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}