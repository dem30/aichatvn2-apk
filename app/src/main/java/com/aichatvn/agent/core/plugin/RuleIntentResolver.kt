package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.conversation.ConversationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleIntentResolver @Inject constructor(
    private val pluginRegistry: PluginRegistry,
    private val conversationContext: ConversationContext
) {
    
    data class RuleMatch(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any>
    )
    
    /**
     * Dynamic resolve - không hardcode pattern nào
     * Tất cả đều đọc từ manifest của plugin
     */
    suspend fun resolve(userMessage: String): RuleMatch? {
        val lower = userMessage.lowercase()
        
        // Kiểm tra context trước (câu ngắn như "tắt nó đi")
        val contextMatch = resolveWithContext(userMessage)
        if (contextMatch != null) return contextMatch
        
        val plugins = pluginRegistry.getAllPlugins()
        
        for (plugin in plugins) {
            val manifest = plugin.manifest
            
            // Plugin matching - kiểm tra keywords
            val pluginMatched = manifest.keywords.any { keyword ->
                lower.contains(keyword.lowercase())
            }
            
            if (!pluginMatched) continue
            
            for (action in manifest.actions) {
                // Dynamic action matching dựa vào keywords
                val actionMatched = action.keywords.any { keyword ->
                    lower.contains(keyword.lowercase())
                }
                
                if (actionMatched) {
                    val params = extractParamsDynamically(action, userMessage, lower)
                    
                    val missingParams = action.parameters
                        .filter { it.required }
                        .filter { !params.containsKey(it.name) }
                    
                    if (missingParams.isNotEmpty()) {
                        val missingNames = missingParams.map { it.name }
                        conversationContext.setPending(
                            manifest.id, 
                            action.name, 
                            params.toMutableMap(),
                            missingNames
                        )
                        return null
                    }
                    
                    return RuleMatch(
                        pluginId = manifest.id,
                        action = action.name,
                        params = params
                    )
                }
            }
        }
        
        return null
    }
    
    private suspend fun extractParamsDynamically(
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
    
    private suspend fun extractValueByType(
        param: PluginParameter,
        originalMessage: String,
        lowerMessage: String
    ): Any? {
        val paramName = param.name.lowercase()
        val paramType = param.type.lowercase()
        
        return when {
            // Boolean parameters
            paramType == "boolean" || paramType == "bool" -> {
                when {
                    lowerMessage.contains("bật") || lowerMessage.contains("mở") ||
                    lowerMessage.contains("kích hoạt") || lowerMessage.contains("bật lên") -> true
                    lowerMessage.contains("tắt") || lowerMessage.contains("ngưng") ||
                    lowerMessage.contains("vô hiệu") || lowerMessage.contains("dừng") -> false
                    else -> null
                }
            }
            
            // Email parameters
            paramName == "to" || paramName == "email" -> {
                extractEmail(originalMessage) ?: conversationContext.getLastEmailTo()
            }
            
            // Camera/Device ID parameters
            paramName == "customerid" || paramName == "cameraid" || 
            paramName == "device" || paramName == "id" -> {
                extractDeviceId(originalMessage) ?: conversationContext.getLastCameraId()
            }
            
            // Question/Answer parameters
            paramName == "question" -> extractQuestion(originalMessage)
            paramName == "answer" -> extractAnswer(originalMessage)
            
            // Search query
            paramName == "query" -> extractSearchQuery(originalMessage)
            
            // Title/Subject
            paramName == "title" || paramName == "subject" -> {
                extractTitle(originalMessage) ?: "Không có tiêu đề"
            }
            
            // Generic string - lấy toàn bộ message nếu không có pattern đặc biệt
            paramType == "string" || paramType == "text" -> {
                originalMessage.takeIf { it.isNotBlank() }
            }
            
            // Number
            paramType == "number" || paramType == "int" || paramType == "long" -> {
                lowerMessage.split(" ").find { it.toIntOrNull() != null }?.toInt()
            }
            
            else -> null
        }
    }
    
    private fun extractDeviceId(message: String): String? {
        val patterns = listOf(
            Regex("(camera|cam|đèn)\\s+(\\S+)", RegexOption.IGNORE_CASE),
            Regex("(phòng|kho|nhà|bãi|bếp)\\s+(\\S+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null && match.groupValues.size >= 2) {
                return match.groupValues.last()
            }
        }
        return null
    }
    
    private fun extractEmail(message: String): String? {
        val pattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        return pattern.find(message)?.value
    }
    
    private fun extractQuestion(message: String): String? {
        val pattern = Regex("câu hỏi\\s*[:]?\\s*['\"]?([^'\"]+)['\"]?")
        return pattern.find(message)?.groupValues?.get(1)
    }
    
    private fun extractAnswer(message: String): String? {
        val pattern = Regex("câu trả lời\\s*[:]?\\s*['\"]?([^'\"]+)['\"]?")
        return pattern.find(message)?.groupValues?.get(1)
    }
    
    private fun extractSearchQuery(message: String): String? {
        val pattern = Regex("tìm\\s+(.+)", RegexOption.IGNORE_CASE)
        return pattern.find(message)?.groupValues?.get(1)
    }
    
    private fun extractTitle(message: String): String? {
        val pattern = Regex("(tiêu đề|title)\\s*[:]?\\s*['\"]?([^'\"]+)['\"]?")
        return pattern.find(message)?.groupValues?.get(2)
    }
    
    private suspend fun resolveWithContext(userMessage: String): RuleMatch? {
        val lower = userMessage.lowercase()
        
        val shortCommands = listOf("tắt nó", "bật nó", "dừng", "ngưng", "tắt đi", "bật lên")
        if (shortCommands.any { lower.contains(it) }) {
            val lastPlugin = conversationContext.getLastPlugin()
            val lastAction = conversationContext.getLastAction()
            val lastEntity = conversationContext.getLastEntity()
            
            if (lastPlugin != null && lastAction != null && lastEntity != null) {
                val isTurnOff = lower.contains("tắt") || lower.contains("dừng") || lower.contains("ngưng")
                val isTurnOn = lower.contains("bật")
                
                if (isTurnOff || isTurnOn) {
                    return RuleMatch(
                        pluginId = lastPlugin,
                        action = lastAction,
                        params = mapOf(
                            "customerId" to lastEntity,
                            "cameraId" to lastEntity,
                            "device" to lastEntity,
                            "active" to isTurnOn,
                            "state" to isTurnOn,
                            "enabled" to isTurnOn
                        )
                    )
                }
            }
        }
        
        return null
    }
}