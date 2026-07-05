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

    // ✅ ĐÃ THÊM: Truy vấn quét Hộp thư đến (Inbox) - Lấy tin nhắn mới nhất của từng khách hàng để làm danh sách
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `alerts` (
                        `id` TEXT NOT NULL,
                        `cameraId` TEXT NOT NULL,
                        `customerId` TEXT NOT NULL,
                        `cameraName` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `aiComment` TEXT NOT NULL,
                        `diff` INTEGER NOT NULL,
                        `deltaTrigger` INTEGER NOT NULL,
                        `absDiffTrigger` INTEGER NOT NULL,
                        `imagePath` TEXT,
                        `emailSent` INTEGER NOT NULL DEFAULT 0,
                        `isSuspicious` INTEGER NOT NULL DEFAULT 1,
                        `isRead` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alerts_cameraId` ON `alerts` (`cameraId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alerts_timestamp` ON `alerts` (`timestamp`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `schedules` (
                        `id` TEXT NOT NULL,
                        `pluginId` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `params` TEXT NOT NULL,
                        `cron` TEXT NOT NULL,
                        `intervalMinutes` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `lastRunAt` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedules_enabled` ON `schedules` (`enabled`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourcePlugin TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `customers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `address` TEXT NOT NULL DEFAULT '',
                        `note` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cameras ADD COLUMN smartMode INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_config` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `type` TEXT NOT NULL DEFAULT 'string',
                        `pluginId` TEXT NOT NULL DEFAULT 'global',
                        `label` TEXT NOT NULL DEFAULT '',
                        `description` TEXT NOT NULL DEFAULT '',
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_config_pluginId` ON `app_config` (`pluginId`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qa_data ADD COLUMN type TEXT NOT NULL DEFAULT 'alias'")
            }
        }

        // ✅ MIGRATION 9 -> 10: Tự động khởi tạo bảng 'facebook_pages' lưu nhiều trang
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `facebook_pages` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `accessToken` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        // ✅ MIGRATION 10 -> 11: Thêm cột lưu Page ID Facebook gần nhất của từng khách (PSID),
        // phục vụ trả lời thủ công đúng Fanpage khi chủ app liên kết nhiều Fanpage cùng lúc.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customer_settings ADD COLUMN lastFacebookPageId TEXT")
            }
        }

        // ✅ MIGRATION 11 -> 12: Thêm cột isRead cho chat_messages, phục vụ badge tin nhắn
        // chưa đọc trên InboxScreen. DEFAULT 1 (đã đọc) để không đánh dấu nhầm toàn bộ lịch sử
        // cũ thành "chưa đọc" — chỉ tin nhắn khách MỚI gửi tới sau bản cập nhật này mới được
        // insert với isRead = 0 (xem ChatSkill.saveExternalUserMessage() và processQuery()).
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(
                        MIGRATION_1_2, 
                        MIGRATION_2_3, 
                        MIGRATION_4_5, 
                        MIGRATION_5_6, 
                        MIGRATION_6_7, 
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12 // ✅ ĐĂNG KÝ: Bản di cư isRead mới cho chat_messages
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}