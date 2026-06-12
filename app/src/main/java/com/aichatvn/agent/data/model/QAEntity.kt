package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "qa_data")
data class QAEntity(
    @PrimaryKey
    val id: String,
    val question: String,
    val answer: String,
    val category: String,
    val createdBy: String,
    val createdAt: Long,
    val timestamp: Long
)