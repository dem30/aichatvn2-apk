package com.aichatvn.agent.core.execution

import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.AgentKernel.Intent
import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.DynamicOptionRegistry // 🌟 MỚI
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
    private val logger: Logger,
    // ✅ ĐÃ SỬA LỖI 4: Tiêm Provider của Quản gia (đảm bảo không rủi ro phụ thuộc vòng với AppModule)
    private val houseManagerProvider: javax.inject.Provider<HouseManagerSkill>,
    // 🌟 MỚI: Một nguồn duy nhất cho MỌI picker (device/camera/call/customer/house_policy/
    // plugin_id/action_id/...) — dùng chung với SmartActionFormSheet (form UI), thay cho các
    // nhánh "if (actualKey == ...)" hardcode riêng lẻ từng loại đã có trước đây.
    private val optionRegistry: DynamicOptionRegistry,
    // ✅ MỚI: dùng trong checkDeviceWorldStateGuard() bên dưới để tra thiết bị theo id/tên
    // qua MỌI giao thức đã đăng ký (Tuya, MQTT...) thay vì chỉ database.tuyaDeviceDao() —
    // cùng cách resolveDevice() đã sửa trong SmartSwitchSkill.kt, cùng pattern
    // DynamicOptionRegistry.kt đã dùng (deviceRegistry.all()).
    private val deviceControlRegistry: com.aichatvn.agent.devices.DeviceRegistry
) {

    suspend fun executeIntent(
        plugin: Plugin,
        intent: Intent,
        context: RoutingContext,
        traceId: String
    ): PluginResult {
        val normalizedParams = ParameterResolver.normalizeParams(intent.params, plugin, intent.action, plugins, context.resolvedQuery)
        val normalizedIntent = intent.copy(params = normalizedParams)

        // ✅ MỚI: thêm "cameraId" vào danh sách khoá được theo dõi — TRƯỚC ĐÂY chỉ bắt device Tuya
        // (device/device_id/deviceId), khiến ID camera vừa được thao tác (quét/chọn qua flow
        // "Bạn muốn thao tác với camera nào?") không được ghi nhớ. Đây là 1 cơ chế DÙNG CHUNG cho
        // mọi domain (không tách riêng "lastCameraByUser") — AgentKernel dùng lại giá trị này làm
        // fallback khi câu hỏi tiếp theo không nêu rõ nguồn (vd "camera có ai qua lại không").
        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"]
            ?: normalizedIntent.params["deviceId"] ?: normalizedIntent.params["cameraId"]
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
            // 🌟 SỬA: truyền thêm normalizedIntent.params làm "knownParams" — để nhánh
            // action_id trong DynamicOptionRegistry biết pluginId đã chọn là gì (dependsOn).
            val (question, options, displayOptions) = getQuestionForMissingParam(missing.first(), plugin, normalizedIntent.action, normalizedIntent.params)
            PluginResult.NeedMoreInfo(missing, question, options, displayOptions)
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
                        "_options" to executionResult.options,
                        // ✅ MỚI (nút bấm chọn nhanh — nhánh "nhiều pending xếp hàng"): cache label
                        // ngay từ lần hỏi ĐẦU TIÊN, không chỉ ở các lượt resume sau (PendingIntentResolver),
                        // vì RoutingPipeline.kt đọc "_displayOptions" của CHÍNH pending này khi có
                        // pending khác cần xử lý tiếp theo trong hàng đợi.
                        "_displayOptions" to executionResult.displayOptions
                    ),
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question,
                    username = context.username,
                    createdAt = System.currentTimeMillis()
                )
            )
            is PluginResult.Success -> {
                chatHistoryManager.removePendingIntent(context.username, plugin.manifest.id, intent.action)

                // 🧠 MỚI (Kiến trúc Bình đẳng hóa Bản sao số): Tự động ghi kết quả thi hành thành
                // công vào world_state — để mọi plugin (kể cả email/notification không có state
                // vật lý) đều để lại dấu vết cho check_precondition/Workflow Group đối chiếu.
                val now = System.currentTimeMillis()
                val statePayload = org.json.JSONObject().apply {
                    put("last_action", normalizedIntent.action)
                    put("status", "success")
                    put("timestamp", now)
                    normalizedIntent.params.forEach { (k, v) ->
                        put(k, com.aichatvn.agent.utils.WorldStateHelper.sanitizeAttributeValue(k, v.toString()))
                    }
                }.toString()

                database.worldStateDao().upsertState(
                    com.aichatvn.agent.data.model.WorldStateEntity(
                        id = "${plugin.manifest.id}:${normalizedIntent.action}",
                        source = plugin.manifest.id,
                        sourceId = normalizedIntent.action,
                        attributesJson = statePayload,
                        updatedAt = now
                    )
                )

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
            // ✅ SỬA: trước đây chỉ tra database.tuyaDeviceDao() — với thiết bị MQTT, lookup
            // luôn miss (deviceEntity == null) nên code cũ fallback deviceId = rawDeviceKey,
            // vẫn TÌNH CỜ đúng vì rawDeviceKey chính là id thật (guard vẫn hoạt động), nhưng
            // displayName hiển thị trong thông báo lỗi bị lộ ra là UUID thô thay vì tên thiết
            // bị. Sửa lại tra đúng qua mọi giao thức để displayName luôn đúng, đồng thời nhất
            // quán với resolveDevice() trong SmartSwitchSkill.kt.
            var deviceId = rawDeviceKey
            var displayName = rawDeviceKey
            if (rawDeviceKey.isNotEmpty()) {
                for (controller in deviceControlRegistry.all()) {
                    val devices = try { controller.listDevices() } catch (e: Exception) { emptyList() }
                    val match = devices.find { it.id == rawDeviceKey }
                        ?: devices.find { it.displayName.equals(rawDeviceKey, ignoreCase = true) }
                    if (match != null) {
                        deviceId = match.id
                        displayName = match.displayName
                        break
                    }
                }
            }
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
            val result = plugin.execute(action, normalizedParams)

            // 🧠 MỚI (Kiến trúc Bình đẳng hóa Bản sao số): Tự động ghi kết quả thi hành thành công
            // vào world_state cho cả đường gọi trực tiếp executePluginAction (Planner/Workflow).
            if (result is PluginResult.Success) {
                val now = System.currentTimeMillis()
                val statePayload = org.json.JSONObject().apply {
                    put("last_action", action)
                    put("status", "success")
                    put("timestamp", now)
                    normalizedParams.forEach { (k, v) ->
                        put(k, com.aichatvn.agent.utils.WorldStateHelper.sanitizeAttributeValue(k, v.toString()))
                    }
                }.toString()

                database.worldStateDao().upsertState(
                    com.aichatvn.agent.data.model.WorldStateEntity(
                        id = "$pluginId:$action",
                        source = pluginId,
                        sourceId = action,
                        attributesJson = statePayload,
                        updatedAt = now
                    )
                )
            }
            result
        } catch (e: Exception) {
            logger.e("IntentExecutor", "Lỗi Dashboard", e)
            PluginResult.Failure(e.message ?: "Unknown error")
        }
    }

    // ⚠️ Hàm dành cho màn Diagnostics/Pipeline Graph (mô phỏng câu hỏi mà KHÔNG động DB) —
    // vẫn giữ nguyên nhánh hardcode cũ vì nó chỉ hiển thị TEXT câu hỏi để debug, không cần
    // danh sách option thật. Không ảnh hưởng UX luồng chat thật (xem getQuestionForMissingParam
    // bên dưới mới là hàm dùng cho luồng chạy thật).
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

    // 🌟 SỬA (Quy về một mối): trước đây hàm này hardcode riêng từng nhánh if cho
    // camera/device/schedule-id — mỗi domain mới (call, customer, house_policy, plugin_id...)
    // phải quay lại sửa file này. Giờ MỌI semanticType đều đi qua chung
    // DynamicOptionRegistry.getOptions() — đúng nguồn dữ liệu mà SmartActionFormSheet (form UI
    // thủ công) đã dùng — nên chat NLU và form UI luôn nhất quán, thêm domain mới chỉ cần sửa
    // đúng 1 chỗ (DynamicOptionRegistry).
    suspend fun getQuestionForMissingParam(
        param: String, 
        plugin: Plugin? = null, 
        actionName: String? = null,
        // 🌟 MỚI: các tham số ĐÃ BIẾT của intent hiện tại — cần thiết để resolve các case
        // phân tầng kiểu dependsOn (vd action_id phụ thuộc pluginId đã chọn ở bước trước).
        knownParams: Map<String, Any> = emptyMap()
    ): Triple<String, Map<String, String>, List<Pair<String, String>>> {
        val isNested = param.startsWith("params.")
        val actualKey = if (isNested) param.removePrefix("params.") else param

        // Nếu là tham số LỒNG (vd "params.device" của schedule.add), phải tra đúng action ĐÍCH
        // (vd smart_switch.set) để lấy semanticType thật — không phải action "add" của chính
        // schedule — và dùng chính "params" lồng bên trong làm nguồn currentValues cho dependsOn.
        val (effectiveAction, depsSource) = if (isNested) {
            val targetPluginId = knownParams["pluginId"]?.toString() ?: knownParams["plugin_id"]?.toString() ?: ""
            val targetActionName = knownParams["action"]?.toString() ?: knownParams["action_id"]?.toString() ?: ""
            val tAction = plugins.find { it.manifest.id == targetPluginId }
                ?.manifest?.actions?.find { it.name == targetActionName }
            @Suppress("UNCHECKED_CAST")
            val nested = (knownParams["params"] as? Map<String, Any>) ?: emptyMap()
            tAction to nested
        } else {
            plugin?.manifest?.actions.orEmpty().find { it.name == actionName } to knownParams
        }

        val paramMeta = effectiveAction?.parameters?.find { it.name == actualKey }
        val semanticType = paramMeta?.semanticType ?: ""
        val stringDeps = depsSource.mapValues { it.value.toString() }

        // 🌟 MỘT NGUỒN DUY NHẤT cho MỌI loại picker: device, camera, call, customer,
        // house_policy, schedule_ref, plugin_id, action_id, chatSession, precondition_*...
        val options = optionRegistry.getOptions(semanticType, stringDeps)
        if (options.isNotEmpty()) {
            val prompt = paramMeta?.description?.takeIf { it.isNotBlank() }
                ?: "Bạn muốn chọn giá trị nào cho '$actualKey'?"
            return buildNumberedQuestion(prompt, options.map { it.label to it.value })
        }

        val isCronField = semanticType == "time" || actualKey == "cron" || actualKey == "time"
        if (isCronField) {
            return Triple("Bạn muốn thiết lập hẹn giờ/lên lịch vào lúc mấy giờ, ngày nào? (Ví dụ: 8h sáng mai, hoặc mỗi ngày lúc 18h)", emptyMap(), emptyList())
        }
        
        val isIntervalField = semanticType == "interval" || actualKey == "interval" || actualKey == "intervalMinutes"
        if (isIntervalField) {
            return Triple("Bạn muốn hoạt động này được lặp lại định kỳ sau mỗi bao nhiêu phút? (Ví dụ: mỗi 10 phút)", emptyMap(), emptyList())
        }

        // ✅ MỚI (nút bấm Có/Không cho tham số boolean, vd "state" của smart_switch.set): trước
        // đây semanticType="boolean" không có case nào trong DynamicOptionRegistry.getOptions()
        // (chỉ nhánh device/camera/call/... mới có), nên luôn rơi xuống câu hỏi tự do bên dưới —
        // người dùng phải gõ "bật"/"tắt" và chờ PluginParameter.normalize() đoán đúng. Thêm case
        // cứng ở đây (không cần đụng DynamicOptionRegistry — boolean không cần tra DB) để param
        // nào có type="boolean" cũng tự động ra 2 nút bấm rõ ràng.
        val isBooleanField = paramMeta?.type?.lowercase() == "boolean"
        if (isBooleanField) {
            val prompt = paramMeta?.description?.takeIf { it.isNotBlank() }
                ?: "Bạn muốn chọn giá trị nào cho '$actualKey'?"
            return buildNumberedQuestion(prompt, listOf("✅ Bật" to "true", "⛔ Tắt" to "false"))
        }

        if (paramMeta != null && paramMeta.description.isNotBlank()) {
            return Triple("Bạn vui lòng cung cấp thông tin cho ${paramMeta.description} nhé?", emptyMap(), emptyList())
        }

        val text = when (actualKey) {
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                      -> "Nội dung email bạn muốn viết gì?"
            "title"                                     -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                   -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId", "plugin_id"                     -> "Bạn muốn lên lịch cho chức năng nào?"
            else                                        -> "Bạn vui lòng cung cấp thông tin cho '$actualKey' nhé?"
        }
        return Triple(text, emptyMap(), emptyList())
    }

    private fun buildNumberedQuestion(
        prompt: String,
        candidates: List<Pair<String, String>>
    ): Triple<String, Map<String, String>, List<Pair<String, String>>> {
        val listText = candidates.mapIndexed { i, (label, _) -> "Số ${i + 1}. $label" }.joinToString("\n")
        val options = candidates.mapIndexed { i, (_, value) -> (i + 1).toString() to value }.toMap()
        // ✅ MỚI (nút bấm chọn nhanh trong chat): trước đây label chỉ tồn tại bên trong chuỗi
        // "listText" (text thô "Số 1. Đèn phòng khách..."), "options" trả ra chỉ có index→value —
        // đủ để PendingIntentResolver parse khi người dùng GÕ số, nhưng KHÔNG đủ để UI vẽ nút bấm
        // (nút cần label riêng, tách khỏi value thật gửi đi). Trả thêm "displayOptions" =
        // (label, value) đúng thứ tự candidates, không đánh số — UI tự quyết định hiển thị số hay
        // không, còn logic parse-khi-gõ-tay ở PendingIntentResolver giữ nguyên, dùng "options".
        return Triple(listText.let { "$prompt\n$it" }, options, candidates)
    }

}