package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.core.conversation.ConversationContext
import com.aichatvn.agent.skills.ChatSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCore @Inject constructor(
    private val pluginRegistry: PluginRegistry,
    private val conversationContext: ConversationContext,
    private val ruleIntentResolver: RuleIntentResolver,
    private val groqClient: GroqClientTool,
    private val chatSkill: ChatSkill,
    private val eventBus: PluginEventBus,
    private val logger: Logger
) {
    
    suspend fun process(userMessage: String, username: String = "default_user"): AgentResponse {
        logger.d("AgentCore", "Processing: '$userMessage'")
        
        // 0. Kiểm tra pending action (multi-turn) - SỬA HOÀN CHỈNH
        if (conversationContext.hasPending()) {
            val pendingPluginId = conversationContext.getPendingPlugin()
            val pendingAction = conversationContext.getPendingAction()
            val pendingParams = conversationContext.getPendingParams().toMutableMap()
            val pendingMissing = conversationContext.getPendingMissingParams()
            
            if (pendingPluginId != null && pendingAction != null && pendingPluginId.isNotEmpty() && pendingMissing.isNotEmpty()) {
                logger.d("AgentCore", "Resuming pending: $pendingPluginId.$pendingAction, missing=$pendingMissing")
                val plugin = pluginRegistry.getPlugin(pendingPluginId)
                
                if (plugin != null) {
                    // Điền giá trị người dùng vừa cung cấp vào tham số đang thiếu
                    val missingKey = pendingMissing.firstOrNull()
                    if (missingKey != null) {
                        pendingParams[missingKey] = userMessage
                        logger.d("AgentCore", "Filled missing param '$missingKey' with '$userMessage'")
                    }
                    
                    // Kiểm tra còn thiếu param nào không
                    val manifest = plugin.manifest
                    val actionDef = manifest.actions.find { it.name == pendingAction }
                    val stillMissing = actionDef?.parameters
                        ?.filter { it.required }
                        ?.map { it.name }
                        ?.filter { !pendingParams.containsKey(it) } ?: emptyList()
                    
                    if (stillMissing.isNotEmpty()) {
                        // Vẫn còn thiếu, hỏi tiếp
                        val question = "Vui lòng cung cấp ${stillMissing.joinToString(", ")}"
                        conversationContext.setPending(pendingPluginId, pendingAction, pendingParams, stillMissing)
                        return AgentResponse(
                            success = true,
                            data = mapOf("response" to question)
                        )
                    }
                    
                    // Đã đủ params, thực thi
                    val result = plugin.execute(pendingAction, pendingParams)
                    conversationContext.clearPending()
                    
                    return when (result) {
                        is PluginResult.Success -> {
                            updateContext(pendingPluginId, pendingAction, pendingParams)
                            AgentResponse(
                                success = true,
                                data = mapOf("response" to formatSuccessResponse(result.data))
                            )
                        }
                        is PluginResult.Failure -> {
                            AgentResponse(success = false, error = result.error)
                        }
                        is PluginResult.Ask -> {
                            AgentResponse(success = true, data = mapOf("response" to result.question))
                        }
                        is PluginResult.NeedMoreInfo -> {
                            conversationContext.setPending(pendingPluginId, pendingAction, pendingParams, result.missingParams)
                            AgentResponse(success = true, data = mapOf("response" to result.question))
                        }
                    }
                }
            }
            // Nếu không có missing params nhưng vẫn có pending (lỗi), clear nó
            conversationContext.clearPending()
        }
        
        // 1. Kiểm tra câu hỏi về khả năng
        if (isCapabilityInquiry(userMessage)) {
            return AgentResponse(
                success = true,
                data = mapOf("response" to pluginRegistry.getActionsDescription())
            )
        }
        
        // 2. Rule resolver
        val ruleMatch = ruleIntentResolver.resolve(userMessage)
        if (ruleMatch != null) {
            logger.d("AgentCore", "Rule matched: ${ruleMatch.pluginId}.${ruleMatch.action}")
            val plugin = pluginRegistry.getPlugin(ruleMatch.pluginId)
            if (plugin != null) {
                val result = plugin.execute(ruleMatch.action, ruleMatch.params)
                updateContext(ruleMatch.pluginId, ruleMatch.action, ruleMatch.params)
                return when (result) {
                    is PluginResult.Success -> AgentResponse(
                        success = true,
                        data = mapOf("response" to formatSuccessResponse(result.data))
                    )
                    is PluginResult.Failure -> AgentResponse(success = false, error = result.error)
                    is PluginResult.Ask -> AgentResponse(success = true, data = mapOf("response" to result.question))
                    is PluginResult.NeedMoreInfo -> {
                        conversationContext.setPending(ruleMatch.pluginId, ruleMatch.action, ruleMatch.params, result.missingParams)
                        AgentResponse(success = true, data = mapOf("response" to result.question))
                    }
                }
            }
        }
        
        // 3. LLM fallback
        val context = conversationContext.getContextForLLM()
        val resolved = resolveIntentWithLLM(userMessage, context)
        
        if (resolved.needsClarification) {
            if (resolved.pluginId != null && resolved.action != null) {
                conversationContext.setPending(resolved.pluginId, resolved.action, resolved.params, resolved.missingParams)
            }
            return AgentResponse(
                success = true,
                data = mapOf("response" to resolved.question)
            )
        }
        
        if (resolved.pluginId == null || resolved.action == null) {
            conversationContext.clearPending()
            return chatSkill.processQuery(message = userMessage, username = username)
        }
        
        val plugin = pluginRegistry.getPlugin(resolved.pluginId)
        if (plugin == null) {
            conversationContext.clearPending()
            return AgentResponse(
                success = false,
                error = "Plugin '${resolved.pluginId}' not found"
            )
        }
        
        val result = plugin.execute(resolved.action, resolved.params)
        
        return when (result) {
            is PluginResult.Success -> {
                updateContext(resolved.pluginId, resolved.action, resolved.params)
                conversationContext.clearPending()
                AgentResponse(
                    success = true,
                    data = mapOf("response" to formatSuccessResponse(result.data))
                )
            }
            is PluginResult.Failure -> {
                conversationContext.clearPending()
                AgentResponse(success = false, error = result.error)
            }
            is PluginResult.Ask -> {
                AgentResponse(success = true, data = mapOf("response" to result.question))
            }
            is PluginResult.NeedMoreInfo -> {
                conversationContext.setPending(resolved.pluginId, resolved.action, resolved.params, result.missingParams)
                AgentResponse(success = true, data = mapOf("response" to result.question))
            }
        }
    }
    
    private suspend fun getCandidatePlugins(userMessage: String): List<Plugin> {
        val lower = userMessage.lowercase()
        val allPlugins = pluginRegistry.getAllPlugins()
        
        val matchedPlugins = allPlugins.filter { plugin ->
            plugin.manifest.keywords.any { keyword ->
                lower.contains(keyword.lowercase())
            }
        }
        
        return if (matchedPlugins.isNotEmpty()) matchedPlugins else allPlugins
    }
    
    private suspend fun resolveIntentWithLLM(userMessage: String, context: String): ResolvedIntent {
        val allManifests = pluginRegistry.getAllManifests()
        if (allManifests.isEmpty()) {
            return ResolvedIntent(needsClarification = false)
        }
        
        val candidatePlugins = getCandidatePlugins(userMessage)
        val candidateManifests = candidatePlugins.map { it.manifest }
        
        if (candidateManifests.isEmpty()) {
            return ResolvedIntent(needsClarification = false)
        }
        
        val actionsDescription = candidateManifests.joinToString("\n") { manifest ->
            buildString {
                append("Plugin: ${manifest.id}\n")
                append("Description: ${manifest.description}\n")
                append("Actions:\n")
                manifest.actions.forEach { action ->
                    append("  - ${action.name}: ${action.description}\n")
                    if (action.parameters.isNotEmpty()) {
                        append("    Params: ${action.parameters.joinToString { "${it.name} (${it.type})" }}\n")
                    }
                }
            }
        }
        
        val prompt = """
            Bạn là bộ phân tích ý định. Chọn plugin và action phù hợp.
            
            DANH SÁCH PLUGIN:
            $actionsDescription
            
            ${if (context.isNotEmpty()) "NGỮ CẢNH:\n$context\n" else ""}
            
            Câu: "$userMessage"
            
            QUY TẮC:
            - active = true nếu có "bật", "mở", "bật lên"
            - active = false nếu có "tắt", "ngưng", "vô hiệu"
            - enabled = true nếu có "bật chế độ", "kích hoạt"
            - Nếu thiếu tham số bắt buộc, trả {"needs_clarification": true, "question": "...", "plugin": "...", "action": "...", "missing_params": ["..."]}
            
            Trả về JSON thuần túy.
        """.trimIndent()
        
        val response = groqClient.chatForIntent(prompt)
        
        return try {
            val json = JSONObject(response)
            
            if (json.optBoolean("needs_clarification", false)) {
                val missingParams = json.optJSONArray("missing_params")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                
                return ResolvedIntent(
                    needsClarification = true,
                    question = json.optString("question", "Bạn có thể nói rõ hơn không?"),
                    pluginId = json.optString("plugin", null),
                    action = json.optString("action", null),
                    params = json.optJSONObject("params")?.toMap() ?: emptyMap(),
                    missingParams = missingParams
                )
            }
            
            val pluginId = json.optString("plugin", null)
            val action = json.optString("action", null)
            val params = json.optJSONObject("params")?.toMap() ?: emptyMap()
            
            ResolvedIntent(
                pluginId = pluginId,
                action = action,
                params = params
            )
        } catch (e: Exception) {
            logger.e("AgentCore", "LLM intent resolution failed: ${e.message}")
            ResolvedIntent()
        }
    }
    
    private suspend fun updateContext(pluginId: String, action: String, params: Map<String, Any>) {
        conversationContext.setLastPlugin(pluginId)
        conversationContext.setLastAction(action)
        
        params.forEach { (key, value) ->
            when {
                key == "customerId" || key == "cameraId" -> {
                    if (value is String) {
                        conversationContext.setLastCameraId(value)
                        conversationContext.setLastEntity(value)
                    }
                }
                key == "to" -> {
                    if (value is String) {
                        conversationContext.setLastEmailTo(value)
                    }
                }
            }
        }
    }
    
    private fun formatSuccessResponse(data: Any): String {
        return when (data) {
            is Map<*, *> -> {
                if (data["message"] != null) data["message"].toString()
                else if (data["cameras"] != null) "📷 ${data["cameras"]}"
                else "✅ Đã thực hiện thành công"
            }
            is String -> data
            is List<*> -> {
                if (data.isEmpty()) "📭 Không có dữ liệu"
                else "📋 ${data.size} mục"
            }
            else -> "✅ Đã thực hiện thành công"
        }
    }
    
    private fun isCapabilityInquiry(message: String): Boolean {
        val lower = message.lowercase()
        val patterns = listOf("làm được gì", "có thể làm", "giúp được gì", "khả năng", "chức năng")
        return patterns.any { lower.contains(it) }
    }
    
    private data class ResolvedIntent(
        val pluginId: String? = null,
        val action: String? = null,
        val params: Map<String, Any> = emptyMap(),
        val needsClarification: Boolean = false,
        val question: String? = null,
        val missingParams: List<String> = emptyList()
    )
    
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            map[key] = when (val value = get(key)) {
                is JSONObject -> value.toMap()
                else -> value
            }
        }
        return map
    }
}