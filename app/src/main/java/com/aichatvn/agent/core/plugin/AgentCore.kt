package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.core.AgentResponse
import com.aichatvn.agent.core.conversation.ConversationContext
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCore @Inject constructor(
    private val pluginRegistry: PluginRegistry,
    private val conversationContext: ConversationContext,
    private val parameterExtractor: ParameterValueExtractor,
    private val ruleIntentResolver: RuleIntentResolver,
    private val groqClient: GroqClientTool,
    private val eventBus: PluginEventBus,
    private val logger: Logger
) {

    suspend fun process(userMessage: String, username: String = "default_user"): AgentResponse {
        logger.d("AgentCore", "Processing: '$userMessage'")

        // 0. Kiểm tra pending action (multi-turn)
        if (conversationContext.hasPending()) {
            val pendingPluginId = conversationContext.getPendingPlugin()
            val pendingAction = conversationContext.getPendingAction()
            
            if (pendingPluginId.isNullOrEmpty() || pendingAction.isNullOrEmpty()) {
                logger.w("AgentCore", "Pending corrupted (plugin=$pendingPluginId, action=$pendingAction), clearing")
                conversationContext.clearPending()
                // Continue to normal flow
            } else {
                val pendingParams = conversationContext.getPendingParams().toMutableMap()
                val pendingMissing = conversationContext.getPendingMissingParams()

                logger.d("AgentCore", "Resuming pending: $pendingPluginId.$pendingAction, missing=$pendingMissing")
                val plugin = pluginRegistry.getPlugin(pendingPluginId)

                if (plugin != null && pendingMissing.isNotEmpty()) {
                    val missingKey = pendingMissing.firstOrNull()
                    if (missingKey != null) {
                        // ✅ Type-aware extraction thay vì gán string thô
                        val manifest = plugin.manifest
                        val actionDef = manifest.actions.find { it.name == pendingAction }
                        
                        // ✅ Nếu actionDef null → clear pending và báo lỗi
                        if (actionDef == null) {
                            logger.w("AgentCore", "Action '$pendingAction' not found in manifest, clearing pending")
                            conversationContext.clearPending()
                            return AgentResponse(
                                success = false,
                                error = "Hành động '$pendingAction' không còn tồn tại"
                            )
                        }
                        
                        val paramDef = actionDef.parameters.find { it.name == missingKey }
                        val typedValue = if (paramDef != null) {
                            // Sử dụng ParameterValueExtractor để parse đúng kiểu
                            val extracted = parameterExtractor.extractValueByType(
                                paramDef,
                                userMessage,
                                userMessage.lowercase()
                            )
                            extracted ?: userMessage
                        } else {
                            userMessage
                        }
                        
                        pendingParams[missingKey] = typedValue
                        logger.d("AgentCore", "Filled missing param '$missingKey' with '$typedValue' (type: ${typedValue::class.simpleName})")
                    }

                    // ✅ Kiểm tra actionDef một lần nữa (đã check ở trên, nhưng để an toàn)
                    val actionDef = plugin.manifest.actions.find { it.name == pendingAction }
                    if (actionDef == null) {
                        logger.w("AgentCore", "Action '$pendingAction' not found, clearing pending")
                        conversationContext.clearPending()
                        return AgentResponse(
                            success = false,
                            error = "Hành động '$pendingAction' không còn tồn tại"
                        )
                    }
                    
                    val stillMissing = actionDef.parameters
                        .filter { it.required }
                        .map { it.name }
                        .filter { !pendingParams.containsKey(it) }

                    if (stillMissing.isNotEmpty()) {
                        val question = "Vui lòng cung cấp ${stillMissing.joinToString(", ")}"
                        conversationContext.setPending(pendingPluginId, pendingAction, pendingParams, stillMissing)
                        return AgentResponse(
                            success = true,
                            data = mapOf("response" to question)
                        )
                    }

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
                        is PluginResult.Failure -> AgentResponse(success = false, error = result.error)
                        is PluginResult.Ask -> AgentResponse(success = true, data = mapOf("response" to result.question))
                        is PluginResult.NeedMoreInfo -> {
                            conversationContext.setPending(pendingPluginId, pendingAction, pendingParams, result.missingParams)
                            AgentResponse(success = true, data = mapOf("response" to result.question))
                        }
                    }
                }
                // Plugin not found or no missing params - clear corrupted pending
                conversationContext.clearPending()
            }
        }

        // 1. Kiểm tra câu hỏi về khả năng
        if (isCapabilityInquiry(userMessage)) {
            return AgentResponse(
                success = true,
                data = mapOf("response" to pluginRegistry.getActionsDescription())
            )
        }

        // 2. Rule resolver (NO side-effect)
        val ruleResult = ruleIntentResolver.resolve(userMessage)
        when (ruleResult) {
            is RuleResult.Match -> {
                logger.d("AgentCore", "Rule matched: ${ruleResult.pluginId}.${ruleResult.action}")
                val plugin = pluginRegistry.getPlugin(ruleResult.pluginId)
                if (plugin != null) {
                    val result = plugin.execute(ruleResult.action, ruleResult.params)
                    updateContext(ruleResult.pluginId, ruleResult.action, ruleResult.params)
                    return when (result) {
                        is PluginResult.Success -> AgentResponse(
                            success = true,
                            data = mapOf("response" to formatSuccessResponse(result.data))
                        )
                        is PluginResult.Failure -> AgentResponse(success = false, error = result.error)
                        is PluginResult.Ask -> AgentResponse(success = true, data = mapOf("response" to result.question))
                        is PluginResult.NeedMoreInfo -> {
                            conversationContext.setPending(ruleResult.pluginId, ruleResult.action, ruleResult.params, result.missingParams)
                            AgentResponse(success = true, data = mapOf("response" to result.question))
                        }
                    }
                }
                conversationContext.clearPending()
                return AgentResponse(
                    success = false,
                    error = "Plugin '${ruleResult.pluginId}' not found"
                )
            }
            
            is RuleResult.NeedMoreInfo -> {
                // ✅ AgentCore manages pending state (single source of truth)
                conversationContext.setPending(
                    ruleResult.pluginId,
                    ruleResult.action,
                    ruleResult.params,
                    ruleResult.missingParams
                )
                logger.d("AgentCore", "Need more info: ${ruleResult.pluginId}.${ruleResult.action}, missing: ${ruleResult.missingParams}")
                return AgentResponse(
                    success = true,
                    data = mapOf("response" to ruleResult.question)
                )
            }
            
            RuleResult.NoMatch -> {
                // Fall through to LLM
                logger.d("AgentCore", "No rule match, falling back to LLM")
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
                data = mapOf("response" to (resolved.question ?: "Bạn có thể nói rõ hơn không?"))
            )
        }

        if (resolved.pluginId == null || resolved.action == null) {
            conversationContext.clearPending()
            return AgentResponse(
                success = true,
                data = mapOf("response" to "Xin lỗi, tôi không hiểu yêu cầu của bạn. Bạn có thể nói rõ hơn không?")
            )
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

    // ============= PRIVATE METHODS =============

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

        val response = try {
            withTimeout(15_000L) {
                groqClient.chat(prompt)
            }
        } catch (e: TimeoutCancellationException) {
            logger.w("AgentCore", "LLM intent resolution timed out after 15s — falling back to chat")
            return ResolvedIntent(needsClarification = false)
        } catch (e: Exception) {
            logger.e("AgentCore", "LLM intent resolution error: ${e.message}")
            return ResolvedIntent(needsClarification = false)
        }

        return try {
            val cleaned = response
                .trimIndent()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(cleaned)

            if (json.optBoolean("needs_clarification", false)) {
                val missingParams = json.optJSONArray("missing_params")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                return ResolvedIntent(
                    needsClarification = true,
                    question = json.optString("question", "Bạn có thể nói rõ hơn không?"),
                    pluginId = json.optString("plugin").takeIf { it.isNotEmpty() },
                    action = json.optString("action").takeIf { it.isNotEmpty() },
                    params = json.optJSONObject("params")?.toMap() ?: emptyMap(),
                    missingParams = missingParams
                )
            }

            val pluginId = json.optString("plugin").takeIf { it.isNotEmpty() }
            val action = json.optString("action").takeIf { it.isNotEmpty() }
            val params = json.optJSONObject("params")?.toMap() ?: emptyMap()

            ResolvedIntent(
                pluginId = pluginId,
                action = action,
                params = params
            )
        } catch (e: Exception) {
            logger.e("AgentCore", "Failed to parse LLM response: ${e.message}. Raw: '$response'")
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

    // ============= JSON CONVERTERS (đệ quy triệt để) =============
    
    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            map[key] = convertJsonValue(get(key))
        }
        return map
    }

    private fun convertJsonValue(value: Any): Any {
        return when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    list.add(convertJsonValue(value.get(i))) // ✅ Đệ quy triệt để
                }
                list
            }
            else -> value
        }
    }
}
