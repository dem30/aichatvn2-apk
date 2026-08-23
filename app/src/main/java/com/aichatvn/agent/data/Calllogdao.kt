package com.aichatvn.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichatvn.agent.data.model.CallLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {

    // Giới hạn 200 dòng gần nhất — lịch sử gọi không cần giữ vô hạn, tránh phình DB
    @Query("SELECT * FROM call_logs ORDER BY startedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: CallLogEntity)

    @Query("DELETE FROM call_logs")
    suspend fun clearAll()

    // ✅ MỚI: xoá 1 dòng lịch sử cụ thể — dùng cho nút "Xoá" trong dialog chi tiết ở
    // tab "Gần đây", khác với clearAll() (xoá toàn bộ lịch sử).
    @Query("DELETE FROM call_logs WHERE callId = :callId")
    suspend fun delete(callId: String)
}