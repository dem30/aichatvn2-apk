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
}

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
    
    @Query("UPDATE customer_settings SET isActive = :active, updatedAt = :timestamp WHERE customerId = :customerId")
    suspend fun updateActiveStatus(customerId: String, active: Boolean, timestamp: Long)
    
    @Query("DELETE FROM customer_settings WHERE customerId = :customerId")
    suspend fun deleteCustomerSetting(customerId: String)
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

// ==================== DATABASE ====================

@Database(
    entities = [
        ChatMessageEntity::class,
        QAEntity::class,
        CameraConfigEntity::class,
        CustomerSettingEntity::class,
        AlertEntity::class,
        ScheduleEntity::class,
        TuyaDeviceEntity::class
    ],
    version = 4,  // ✅ THÊM

    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun qaDao(): QADao
    abstract fun cameraDao(): CameraDao
    abstract fun alertDao(): AlertDao
    abstract fun scheduleDao(): ScheduleDao  // ✅ THÊM

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

        // ✅ THÊM MIGRATION 2 -> 3
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)  // ✅ THÊM
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}