package com.aichatvn.agent.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity

@Database(
    entities = [
        ChatMessageEntity::class,
        QAEntity::class,
        CameraConfigEntity::class,
        CustomerSettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun qaDao(): QADao
    abstract fun cameraDao(): CameraDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aichatvn_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}