package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.conversation.ConversationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleIntentResolver @Inject constructor(
    private val pluginRegistry: PluginRegistry,
    private val conversationContext: ConversationContext,
    private val parameterExtractor: ParameterValueExtractor
) {
    
    /**
     * Dynamic resolve - không hardcode pattern nào
     * Tất cả đều đọc từ manifest của plugin
     * ⚠️ NO SIDE-EFFECT: only returns RuleResult, doesn't modify ConversationContext
     */
    suspend fun resolve(userMessage: String): RuleResult {
        val lower = userMessage.lowercase()
        
        // 1. Context-based resolution (short commands like "tắt nó đi")
        val contextMatch = resolveWithContext(userMessage, lower)
        if (contextMatch != null) return contextMatch
        
        // 2. Plugin/action matching
        val plugins = pluginRegistry.getAllPlugins()
        
        for (plugin in plugins) {
            val manifest = plugin.manifest
            
            val pluginMatched = manifest.keywords.any { keyword ->
                lower.contains(keyword.lowercase())
            }
            
            if (!pluginMatched) continue
            
            for (action in manifest.actions) {
                val actionMatched = action.keywords.any { keyword ->
                    lower.contains(keyword.lowercase())
                }
                
                if (actionMatched) {
                    val params = parameterExtractor.extract(action, userMessage, lower)
                    
                    val missingParams = action.parameters
                        .filter { it.required }
                        .filter { !params.containsKey(it.name) }
                    
                    if (missingParams.isNotEmpty()) {
                        val missingNames = missingParams.map { it.name }
                        val question = "Vui lòng cung cấp ${missingNames.joinToString(", ")}"
                        
                        // ⚠️ NO side-effect here! AgentCore will handle setPending()
                        return RuleResult.NeedMoreInfo(
                            pluginId = manifest.id,
                            action = action.name,
                            params = params,
                            missingParams = missingNames,
                            question = question
                        )
                    }
                    
                    return RuleResult.Match(
                        pluginId = manifest.id,
                        action = action.name,
                        params = params
                    )
                }
            }
        }
        
        return RuleResult.NoMatch
    }
    
    // ============= PRIVATE METHODS =============
    
    /**
     * Xử lý câu lệnh ngắn dạng "tắt nó đi", "bật nó lên"
     * Dựa vào context (lastPlugin, lastAction, lastEntity) để suy luận
     */
    private suspend fun resolveWithContext(
        userMessage: String,
        lowerMessage: String
    ): RuleResult? {
        val shortCommands = listOf("tắt nó", "bật nó", "dừng", "ngưng", "tắt đi", "bật lên")
        if (shortCommands.any { lowerMessage.contains(it) }) {
            val lastPluginId = conversationContext.getLastPlugin() ?: return null
            val lastAction = conversationContext.getLastAction() ?: return null
            val lastEntity = conversationContext.getLastEntity() ?: return null
            
            val plugin = pluginRegistry.getPlugin(lastPluginId) ?: return null
            
            val isTurnOff = lowerMessage.contains("tắt") || lowerMessage.contains("dừng") || lowerMessage.contains("ngưng")
            val isTurnOn = lowerMessage.contains("bật")
            
            // ✅ Find appropriate action based on context (turn on/off)
            // Không tái sử dụng lastAction mà tìm action đúng nghĩa với ngữ cảnh
            val targetAction = if (isTurnOn) {
                findActionByKeywords(plugin, listOf("enable", "start", "on", "bật", "mở", "kích hoạt"))
                    ?: lastAction // Fallback nếu không tìm thấy
            } else {
                findActionByKeywords(plugin, listOf("disable", "stop", "off", "tắt", "ngưng", "vô hiệu"))
                    ?: lastAction // Fallback nếu không tìm thấy
            }
            
            val actionDef = plugin.manifest.actions.find { it.name == targetAction } ?: return null
            
            // ✅ Build params chỉ cho các field thực sự tồn tại trong manifest
            val params = mutableMapOf<String, Any>()
            actionDef.parameters.forEach { param ->
                when (param.name) {
                    "customerId", "cameraId", "device", "id" -> {
                        if (param.type.lowercase() in listOf("string", "text")) {
                            params[param.name] = lastEntity
                        }
                    }
                    "active", "state", "enabled" -> {
                        if (param.type.lowercase() in listOf("boolean", "bool")) {
                            params[param.name] = isTurnOn
                        }
                    }
                }
            }
            
            return RuleResult.Match(
                pluginId = lastPluginId,
                action = targetAction,
                params = params
            )
        }
        
        return null
    }
    
    /**
     * ✅ Token-based matching to avoid false positives
     * Ví dụ: "monitor" contains "on" → false positive, cần token hóa
     */
    private fun findActionByKeywords(plugin: Plugin, keywords: List<String>): String? {
        val lowerKeywords = keywords.map { it.lowercase() }
        return plugin.manifest.actions.firstOrNull { action ->
            action.keywords.any { actionKeyword ->
                val lowerActionKeyword = actionKeyword.lowercase()
                lowerKeywords.any { targetKeyword ->
                    val targetTokens = targetKeyword.split(" ")
                    val actionTokens = lowerActionKeyword.split(" ")
                    targetTokens.any { targetToken ->
                        actionTokens.any { actionToken ->
                            actionToken == targetToken
                        }
                    }
                }
            }
        }?.name
    }
}