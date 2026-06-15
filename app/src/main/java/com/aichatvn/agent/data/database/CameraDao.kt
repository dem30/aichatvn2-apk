package com.aichatvn.agent.data.database

import androidx.room.*
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    
    @Query("SELECT * FROM cameras ORDER BY timestamp DESC")
    fun getAllCamerasFlow(): Flow<List<CameraConfigEntity>>
    
    // THÊM: suspend version cho CameraViewModel.loadCameras()
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
    
    // Giữ nguyên - dùng cho worker scan (chỉ quét camera đang theo dõi)
    @Query("SELECT * FROM cameras WHERE manualOff = 0")
    suspend fun getActiveCameras(): List<CameraConfigEntity>
    
    // Customer settings operations
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