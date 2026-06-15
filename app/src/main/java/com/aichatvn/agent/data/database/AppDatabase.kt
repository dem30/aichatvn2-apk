package com.aichatvn.agent.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        QAEntity::class,
        CameraConfigEntity::class,
        CustomerSettingEntity::class,
        AlertEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun qaDao(): QADao
    abstract fun cameraDao(): CameraDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // MIGRATION 1 -> 2: thêm bảng "alerts" để lưu lịch sử cảnh báo camera
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
