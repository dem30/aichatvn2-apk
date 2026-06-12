package com.aichatvn.agent.data.database

import androidx.room.*
import com.aichatvn.agent.data.model.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

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