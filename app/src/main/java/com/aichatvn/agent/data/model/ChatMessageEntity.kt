package com.aichatvn.agent.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionToken: String,
    val username: String,
    val content: String,
    val role: String, // "user" or "assistant"
    val type: String, // "text" or "image" or "file"
    val fileUrl: String? = null,
    val timestamp: Long
)