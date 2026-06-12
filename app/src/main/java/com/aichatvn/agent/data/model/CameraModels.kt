package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cameras")
data class CameraConfigEntity(
    @PrimaryKey
    val id: String,
    val customerId: String,
    val customername: String,
    val customeremail: String,
    val snapshoturl: String,
    val landinfo: String? = null,
    val snapshotPath: String? = null,
    val timestamp: Long,
    val status: String = "online",
    val isOnline: Int = 1,
    val manualOff: Int = 0,
    val aiPrompt: String = "",
    val aiPositiveKeywords: String = "",
    val aiNegativeKeywords: String = ""
)

@Entity(tableName = "customer_settings")
data class CustomerSettingEntity(
    @PrimaryKey
    val customerId: String,
    val smartMode: Int = 0,
    val isActive: Int = 1,
    val updatedAt: Long,
    val timestamp: Long
)