package com.aichatvn.agent.data.database

import androidx.room.*
import com.aichatvn.agent.data.model.QAEntity
import kotlinx.coroutines.flow.Flow

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