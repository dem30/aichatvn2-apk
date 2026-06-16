package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.conversation.ConversationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParameterValueExtractor @Inject constructor(
    private val conversationContext: ConversationContext
) {
    
    suspend fun extract(
        action: PluginAction,
        originalMessage: String,
        lowerMessage: String
    ): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        for (param in action.parameters) {
            val value = extractValueByType(param, originalMessage, lowerMessage)
            if (value != null) {
                params[param.name] = value
            }
        }
        
        return params
    }
    
    suspend fun extractValueByType(
        param: PluginParameter,
        originalMessage: String,
        lowerMessage: String
    ): Any? {
        val paramName = param.name.lowercase()
        val paramType = param.type.lowercase()
        
        return when {
            // Boolean
            paramType == "boolean" || paramType == "bool" -> {
                when {
                    lowerMessage.contains("bật") || lowerMessage.contains("mở") ||
                    lowerMessage.contains("kích hoạt") || lowerMessage.contains("bật lên") -> true
                    lowerMessage.contains("tắt") || lowerMessage.contains("ngưng") ||
                    lowerMessage.contains("vô hiệu") || lowerMessage.contains("dừng") -> false
                    else -> null
                }
            }
            
            // Email
            paramName == "to" || paramName == "email" -> {
                extractEmail(originalMessage) ?: conversationContext.getLastEmailTo()
            }
            
            // Device ID (Unicode-safe)
            paramName == "customerid" || paramName == "cameraid" || 
            paramName == "device" || paramName == "id" -> {
                extractDeviceId(originalMessage) ?: conversationContext.getLastCameraId()
            }
            
            // QA (lightweight fallback, sẽ được LLM thay thế trong v2.0)
            paramName == "question" -> extractQuestion(originalMessage)
            paramName == "answer" -> extractAnswer(originalMessage)
            
            // Search
            paramName == "query" -> extractSearchQuery(originalMessage)
            
            // Title
            paramName == "title" || paramName == "subject" -> {
                extractTitle(originalMessage) ?: "Không có tiêu đề"
            }
            
            // ✅ Tách riêng Int và Long
            paramType == "int" || paramType == "integer" -> {
                lowerMessage.split(" ").find { it.toIntOrNull() != null }?.toInt()
            }
            paramType == "long" || paramType == "number" -> {
                lowerMessage.split(" ").find { it.toLongOrNull() != null }?.toLong()
            }
            
            // Generic string
            paramType == "string" || paramType == "text" -> {
                originalMessage.takeIf { it.isNotBlank() }
            }
            
            else -> null
        }
    }
    
    // ============= PRIVATE EXTRACTORS =============
    
    // ✅ Unicode-safe: dùng .+ thay vì \w
    private fun extractDeviceId(message: String): String? {
        val patterns = listOf(
            Regex("(camera|cam|đèn)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("(phòng|kho|nhà|bãi|bếp)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("(thiết bị|device)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(message.trim())
            if (match != null && match.groupValues.size >= 3) {
                // ✅ Dùng index rõ ràng thay vì last()
                val raw = match.groupValues[2].trim()
                val cleaned = raw.replace(Regex("\\s+(đi|nhé|thì|nha|nhỉ|à|ạ)$", RegexOption.IGNORE_CASE), "")
                if (cleaned.isNotEmpty()) {
                    return cleaned
                }
            }
        }
        return null
    }
    
    private fun extractEmail(message: String): String? {
        val pattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        return pattern.find(message)?.value
    }
    
    // ⚠️ WARNING: Regex-based QA extraction is fragile and will be replaced by LLM in v2.0
    // Current implementation serves as lightweight fallback for simple patterns only.
    private fun extractQuestion(message: String): String? {
        val patterns = listOf(
            Regex("câu hỏi\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^hỏi\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^question\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(message.trim())
            if (match != null && match.groupValues.size >= 2) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractAnswer(message: String): String? {
        val patterns = listOf(
            Regex("câu trả lời\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^trả lời\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^answer\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(message.trim())
            if (match != null && match.groupValues.size >= 2) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractSearchQuery(message: String): String? {
        val pattern = Regex("tìm\\s+(.+)$", RegexOption.IGNORE_CASE)
        val match = pattern.find(message.trim())
        return match?.groupValues?.get(1)?.trim()
    }
    
    private fun extractTitle(message: String): String? {
        val pattern = Regex("(tiêu đề|title)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val match = pattern.find(message.trim())
        return match?.groupValues?.get(2)?.trim()
    }
}