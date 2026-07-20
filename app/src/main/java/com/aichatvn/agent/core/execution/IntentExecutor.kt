package com.aichatvn.agent.core.execution

import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.WorldStateHelper
// ✅ THÊM IMPORTS: Nhận biết giao thức kiểm duyệt chính sách của Quản gia
import com.aichatvn.agent.skills.HouseManagerSkill
import com.aichatvn.agent.skills.PolicyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentExecutor @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val database: AppDatabase,
    private val chatHistoryManager: ChatHistoryManager,
    private val dialogManager: DialogManager,
    private val configProvider: AppConfigProvider, 
    logger: Logger,
    // ✅ ĐÃ SỬA LỖI 4: Tiêm Provider của Quản gia (đảm bảo không rủi ro phụ thuộc vòng với AppModule)
    private val houseManagerProvider: javax.inject.Provider<HouseManagerSkill>
) {

    suspend fun executeIntent(
        plugin: Plugin,
        intent: Intent,
        context: RoutingContext,
        traceId: String
    ): PluginResult {
        val normalizedParams = ParameterResolver.normalizeParams(intent.params, plugin, intent.action, plugins, context.resolvedQuery)
        val normalizedIntent = intent.copy(params = normalizedParams)

        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"] ?: normalizedIntent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(context.username, it) }

        val missing = ParameterResolver.getUnresolvedParams(normalizedIntent.params, plugin, normalizedIntent.action, plugins)

        context.traces.add(TraceNode(
            nodeId = "pending.check",
            label = "Kiểm tra tham số thiếu hụt (Pending Check)",
            input = normalizedIntent.params.toString(),
            output = if (missing.isNotEmpty()) "Thiếu: $missing" else "Đầy đủ, sẵn sàng thực thi",
            matched = missing.isEmpty(),
            codeRef = CodeReference(
                fileName = "ParameterResolver.kt",
                functionName = "getUnresolvedParams",
                hardcodedRules = "Required fields: [${plugin.manifest.actions.find { it.name == normalizedIntent.action }?.parameters?.filter { it.required }?.joinToString { it.name } ?: ""}]" +
                    if (plugin.manifest.id == "schedule") "\n" + ParameterResolver.RULE_SCHEDULE_TIME_CHECK else "",
                businessLogic = "Rà required fields của action; riêng plugin schedule áp thêm ràng buộc thời gian đặc thù."
            )
        ))

        val actionMeta = plugin.manifest.actions.find { it.name == normalizedIntent.action }
        val worldStateCondition: WorldStateHelper.WorldStateCondition? = actionMeta?.requiredWorldState?.let {
            WorldStateHelper.parseCondition(it)
        }

        val worldStateBlocked: Boolean = if (missing.isEmpty() && worldStateCondition != null) {
            checkWorldStateBlocked(worldStateCondition)
        } else {
            false
        }

        val deviceGuardBlocked: PluginResult.Failure? = if (missing.isEmpty()) {
            checkDeviceWorldStateGuard(plugin.manifest.id, normalizedIntent.action, normalizedIntent.params)
        } else null

        val traceInput: String = if (worldStateCondition != null) {
            "${worldStateCondition.source}.${worldStateCondition.sourceId}.${worldStateCondition.attrKey} == '${worldStateCondition.expected}' ?"
        } else {
            "Không có điều kiện"
        }

        val traceOutput: String = if (worldStateCondition == null) {
            "Bỏ qua (action không có ràng buộc)"
        } else if (worldStateBlocked) {
            "❌ Ràng buộc chưa thỏa mãn -> Chặn hành động"
        } else {
            "✅ Trạng thái thực tế hợp lệ"
        }

        context.traces.add(TraceNode(
            nodeId = "worldstate.check",
            label = "Kiểm tra điều kiện Thế giới thực (World State Precondition)",
            input = traceInput,
            output = traceOutput,
            matched = worldStateCondition == null || !worldStateBlocked,
            codeRef = CodeReference(
                fileName = "WorldStateHelper.kt",
                functionName = "getAttribute",
                hardcodedRules = "requiredWorldState = \"source.sourceId.attrKey=expectedValue\"",
                businessLogic = "Chặn hành động vật lý nếu trạng thái thế giới thực chưa khớp cấu hình, ngăn thiết bị kích hoạt sai ngữ cảnh (vd: chặn mở van khi bình chứa trống)."
            )
        ))

        // ✅ ĐÃ SỬA LỖI 4 (Vùng an toàn): lồng Policy Check của Quản gia trực tiếp vào chuỗi gán executionResult
        // Việc này bảo đảm hệ thống không bị return "cụt", giữ nguyên dấu vết trace và turn lịch sử hội thoại đầy đủ.
        val executionResult = if (missing.isNotEmpty()) {
            val (question, options) = getQuestionForMissingParam(missing.first(), plugin, normalizedIntent.action)
            PluginResult.NeedMoreInfo(missing, question, options)
        } else if (worldStateBlocked) {
            PluginResult.Failure("⚠️ Điều kiện thực tế chưa thỏa mãn để thực hiện \"${actionMeta?.description ?: normalizedIntent.action}\" (Cần trạng thái: ${worldStateCondition?.attrKey} = ${worldStateCondition?.expected}).")
        } else if (deviceGuardBlocked != null) {
            deviceGuardBlocked 
        } else {
            // 🧠 Kiểm duyệt chính sách vận hành của Quản gia AI trước khi thi hành lệnh vật lý
            val policyResult = houseManagerProvider.get().checkPolicy(plugin.manifest.id, normalizedIntent.action, normalizedIntent.params)
            if (policyResult is PolicyResult.Blocked) {
                PluginResult.Failure(policyResult.reason)
            } else {
                try {
                    logger.d("IntentExecutor", "[$traceId] Execute Action Trực Tiếp: ${plugin.manifest.id}.${normalizedIntent.action}")
                    plugin.execute(normalizedIntent.action, normalizedIntent.params)
                } catch (e: Exception) {
                    logger.e("IntentExecutor", "[$traceId] Execute error: ${e.message}", e)
                    PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
                }
            }
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.addPendingIntent(
                PendingIntent(
                    pluginId = plugin.manifest.id,
                    action = normalizedIntent.action,
                    knownParams = normalizedIntent.params + mapOf(
                        "_noProgressCount" to 0,
                        "_options" to executionResult.options
                    ),
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question,
                    username = context.username,
                    createdAt = System.currentTimeMillis()
                )
            )
            is PluginResult.Success -> {
                chatHistoryManager.removePendingIntent(context.username, plugin.manifest.id, intent.action)
                dialogManager.updateFocus(
                    context.username,
                    ConversationFocus(
                        pluginId = plugin.manifest.id,
                        action = normalizedIntent.action,
                        deviceId = normalizedIntent.params["device"]?.toString()
                            ?: normalizedIntent.params["device_id"]?.toString()
                            ?: normalizedIntent.params["deviceId"]?.toString(),
                        cameraId = normalizedIntent.params["camera"]?.toString()
                            ?: normalizedIntent.params["camera_id"]?.toString()
                            ?: normalizedIntent.params["cameraId"]?.toString(),
                        scheduleId = normalizedIntent.params["schedule_id"]?.toString()
                            ?: normalizedIntent.params["scheduleId"]?.toString(),
                        params = normalizedIntent.params,
                        timestamp = System.currentTimeMillis(),
                        confidence = 1.0
                    )
                )
            }
            else -> chatHistoryManager.removePendingIntent(context.username, plugin.manifest.id, intent.action)
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success ->
                (executionResult.data as? Map<*, *>? )?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }

        context.traces.add(TraceNode(
            nodeId = "execute.action",
            label = "Kích hoạt thực thi Hành động",
            input = "${plugin.manifest.id}.${normalizedIntent.action}",
            output = when (executionResult) {
                is PluginResult.Success -> "Thành công: ${(executionResult.data as? Map<*, *>)?.get("message") ?: "OK"}"
                is PluginResult.Failure -> "Thất bại: ${executionResult.error}"
                is PluginResult.NeedMoreInfo -> "Cần thêm: ${executionResult.question}"
            },
            matched = executionResult is PluginResult.Success,
            codeRef = CodeReference(
                fileName = "IntentExecutor.kt",
                functionName = "executeIntent",
                hardcodedRules = "-",
                businessLogic = "Chuẩn hóa params, cập nhật lastDevice/Focus, gọi plugin.execute() thật, cập nhật Pending nếu thiếu tham số."
            )
        ))

        chatHistoryManager.addTurn(context.username, context.originalQuery, replyForHistory)

        return executionResult
    }

    suspend fun checkWorldStateBlocked(condition: WorldStateHelper.WorldStateCondition): Boolean {
        val actualValue = WorldStateHelper.getAttribute(
            database.worldStateDao(),
            condition.source,
            condition.sourceId,
            condition.attrKey
        )
        return actualValue != condition.expected
    }

    suspend fun checkWorldStateGuard(plugin: Plugin, action: String): PluginResult.Failure? {
        val actionMeta = plugin.manifest.actions.find { it.name == action } ?: return null
        val condition = actionMeta.requiredWorldState?.let { WorldStateHelper.parseCondition(it) } ?: return null
        if (!checkWorldStateBlocked(condition)) return null
        return PluginResult.Failure(
            "⚠️ Điều kiện thực tế chưa thỏa mãn để thực hiện \"${actionMeta.description}\" " +
                "(Cần trạng thái: ${condition.attrKey} = ${condition.expected})."
        )
    }

    suspend fun checkDeviceWorldStateGuard(
        pluginId: String,
        action: String,
        params: Map<String, Any>
    ): PluginResult.Failure? {
        if (pluginId == "smart_switch" && action == "set") {
            val rawDeviceKey = params["device"]?.toString() ?: params["device_id"]?.toString() ?: params["deviceId"]?.toString() ?: ""
            val deviceEntity = if (rawDeviceKey.isNotEmpty()) {
                database.tuyaDeviceDao().getDeviceById(rawDeviceKey)
                    ?: database.tuyaDeviceDao().getDeviceByName(rawDeviceKey)
            } else null
            val deviceId = deviceEntity?.id ?: rawDeviceKey
            val displayName = deviceEntity?.name ?: rawDeviceKey
            if (deviceId.isNotEmpty()) {
                val guardKey = "worldstate_guard_$deviceId"
                val precondition = configProvider.getString(guardKey, "")
                
                if (precondition.isNotBlank()) {
                    val condition = WorldStateHelper.parseCondition(precondition)
                    if (condition != null) {
                        val currentState = WorldStateHelper.getAttribute(
                            database.worldStateDao(),
                            condition.source,
                            condition.sourceId,
                            condition.attrKey
                        )
                        
                        if (currentState != condition.expected) {
                            val expectedMsg = if (condition.expected == "true") "Bật" 
                                              else if (condition.expected == "false") "Tắt" 
                                              else condition.expected
                            val currentMsg = if (currentState == "true") "Bật" 
                                             else if (currentState == "false") "Tắt" 
                                             else (currentState ?: "Không xác định")
                                             
                            return PluginResult.Failure(
                                "❌ Hành động bị chặn: Thiết bị '$displayName' chỉ được phép kích hoạt khi " +
                                "trạng thái của '${condition.sourceId}' là '$expectedMsg' " +
                                "(Trạng thái thực tế hiện tại đang là: '$currentMsg')"
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    suspend fun executePluginAction(
        pluginId: String,
        action: String,
        params: Map<String, Any>
    ): PluginResult {
        val plugin = plugins.find { it.manifest.id == pluginId }
            ?: return PluginResult.Failure("Không tìm thấy plugin: $pluginId")

        val normalizedParams = ParameterResolver.normalizeParams(params, plugin, action, plugins, null)

        checkWorldStateGuard(plugin, action)?.let { blocked ->
            logger.d("IntentExecutor", "executePluginAction bị chặn bởi world-state: $pluginId.$action")
            return blocked
        }

        checkDeviceWorldStateGuard(pluginId, action, normalizedParams)?.let { blocked ->
            logger.d("IntentExecutor", "executePluginAction bị chặn bởi device world-state: $pluginId.$action")
            return blocked
        }

        // ✅ ĐÃ SỬA LỖI 4: Sử dụng đúng biến pluginId, action và normalizedParams trong hàm này (không gọi normalizedIntent)
        val policyResult = houseManagerProvider.get().checkPolicy(pluginId, action, normalizedParams)
        if (policyResult is PolicyResult.Blocked) {
            logger.w("IntentExecutor", "executePluginAction bị chặn bởi chính sách Quản gia: ${policyResult.reason}")
            return PluginResult.Failure(policyResult.reason)
        }

        return try {
            plugin.execute(action, normalizedParams)
        } catch (e: Exception) {
            logger.e("IntentExecutor", "Lỗi Dashboard", e)
            PluginResult.Failure(e.message ?: "Unknown error")
        }
    }

    fun getQuestionForMissingParamDiagnostic(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null
    ): String {
        val actualKey = if (param.startsWith("params.")) param.removePrefix("params.") else param
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName }
        val paramMeta = targetAction?.parameters?.find { it.name == actualKey }
        val semanticType = paramMeta?.semanticType?.lowercase() ?: ""

        val isCronField = semanticType == "time" || actualKey == "cron" || actualKey == "time"
        if (isCronField) {
            return "Bạn muốn thiết lập hẹn giờ/lên lịch vào lúc mấy giờ, ngày nào? (Ví dụ: 8h sáng mai, hoặc mỗi ngày lúc 18h)"
        }
        
        val isIntervalField = semanticType == "interval" || actualKey == "interval" || actualKey == "intervalMinutes"
        if (isIntervalField) {
            return "Bạn muốn hoạt động này được lặp lại định kỳ sau mỗi bao nhiêu phút? (Ví dụ: mỗi 10 phút)"
        }

        if (paramMeta != null && paramMeta.description.isNotBlank()) {
            return "Bạn vui lòng cung cấp thông tin cho ${paramMeta.description} nhé?"
        }

        return when (actualKey) {
            "device", "device_id", "deviceId"          -> "Bạn muốn điều khiển thiết bị nào?"
            "camera", "camera_id", "cameraId"          -> "Bạn muốn xem camera nào?"
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId", "plugin_id"                    -> "Bạn muốn lên lịch cho chức năng nào?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$actualKey' nhé?"
        }
    }

    suspend fun getQuestionForMissingParam(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null
    ): Pair<String, Map<String, String>> {
        val actualKey = if (param.startsWith("params.")) param.removePrefix("params.") else param
        val targetAction = plugin?.manifest?.actions.orEmpty().find { it.name == actionName }
        val paramMeta = targetAction?.parameters?.find { it.name == actualKey }
        val semanticType = paramMeta?.semanticType?.lowercase() ?: ""

        if (actualKey in setOf("camera", "camera_id", "cameraId")) {
            val cameras = database.cameraDao().getActiveCameras()
            if (cameras.isNotEmpty()) {
                return buildNumberedQuestion(
                    "Bạn muốn thao tác với camera nào?",
                    cameras.map { 
                        val displayName = if (!it.landinfo.isNullOrBlank()) {
                            "${it.landinfo} (${it.id})"
                        } else {
                            it.id
                        }
                        displayName to it.id 
                    }
                )
            }
        }

        if (actualKey in setOf("device", "device_id", "deviceId")) {
            val devices = database.tuyaDeviceDao().getAllDevices()
            if (devices.isNotEmpty()) {
                val duplicateNames = devices.groupBy { it.name }.filterValues { it.size > 1 }.keys
                return buildNumberedQuestion(
                    "Bạn muốn điều khiển thiết bị nào?",
                    devices.map { d ->
                        val label = if (d.name in duplicateNames) "${d.name} (${d.id.takeLast(4)})" else d.name
                        label to d.id
                    }
                )
            }
        }
        if (actualKey == "id" && plugin?.manifest?.id == "schedule") {
            val schedules = database.scheduleDao().getAllSchedules()
            if (schedules.isNotEmpty()) {
                return buildNumberedQuestion(
                    "Bạn muốn thao tác với lịch trình nào?",
                    schedules.map { "${it.pluginId}.${it.action} (${if (it.cron.isNotEmpty()) it.cron else "${it.intervalMinutes} phút"})" to it.id }
                )
            }
        }

        val isCronField = semanticType == "time" || actualKey == "cron" || actualKey == "time"
        if (isCronField) {
            return ("Bạn muốn thiết lập hẹn giờ/lên lịch vào lúc mấy giờ, ngày nào? (Ví dụ: 8h sáng mai, hoặc mỗi ngày lúc 18h)" to emptyMap())
        }
        
        val isIntervalField = semanticType == "interval" || actualKey == "interval" || actualKey == "intervalMinutes"
        if (isIntervalField) {
            return ("Bạn muốn hoạt động này được lặp lại định kỳ sau mỗi bao nhiêu phút? (Ví dụ: mỗi 10 phút)" to emptyMap())
        }

        if (paramMeta != null && paramMeta.description.isNotBlank()) {
            return ("Bạn vui lòng cung cấp thông tin cho ${paramMeta.description} nhé?" to emptyMap())
        }

        val text = when (actualKey) {
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId", "plugin_id"                    -> "Bạn muốn lên lịch cho chức năng nào?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$actualKey' nhé?"
        }
        return (text to emptyMap())
    }

    private fun buildNumberedQuestion(prompt: String, candidates: List<Pair<String, String>>): Pair<String, Map<String, String>> {
        val listText = candidates.mapIndexed { i, (label, _) -> "Số ${i + 1}. $label" }.joinToString("\n")
        val options = candidates.mapIndexed { i, (_, value) -> (i + 1).toString() to value }.toMap()
        return "$prompt\n$listText" to options
    }
}