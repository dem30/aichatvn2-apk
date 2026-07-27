package com.aichatvn.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichatvn.agent.data.model.CallContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallContactDao {

    // Yêu thích lên đầu, còn lại sắp theo tên A-Z cho dễ tìm trong danh bạ
    @Query("SELECT * FROM call_contacts ORDER BY isFavorite DESC, displayName ASC")
    fun observeAll(): Flow<List<CallContactEntity>>

    @Query("SELECT * FROM call_contacts WHERE deviceCode = :deviceCode LIMIT 1")
    suspend fun getByDeviceCode(deviceCode: String): CallContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: CallContactEntity)

    @Query("DELETE FROM call_contacts WHERE deviceCode = :deviceCode")
    suspend fun delete(deviceCode: String)

    @Query("UPDATE call_contacts SET lastCalledAt = :ts WHERE deviceCode = :deviceCode")
    suspend fun touchLastCalled(deviceCode: String, ts: Long)
}