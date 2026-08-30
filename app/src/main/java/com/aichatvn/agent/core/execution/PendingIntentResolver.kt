package com.aichatvn.agent.core.execution

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.*
import com.aichatvn.agent.core.AgentKernel.DeviceCommandResult
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity // ✅ THÊM IMPORT
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.DateTimeParser
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import com.aichatvn.agent.utils.toMap

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

@Singleton
class PendingIntentResolver @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val database: AppDatabase,
    private val configProvider: AppConfigProvider,
    // ⚠️ groqClient không còn được dùng trong file này (đã bỏ cuộc gọi LLM phụ ở fill-parameter
    // flow) — giữ lại tham số constructor để không đổi chữ ký DI, Vinh có thể xoá nếu xác nhận
    // không nơi nào khác cần.
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val intentExecutor: IntentExecutor,
    private val logger: Logger
) {

    private fun aliasMatchesForType(
        matchResult: TrainingSkill.MatchResult,
        semanticType: String
    ): String? {
        return matchResult.bestAliasMatches[semanticType]?.first?.answer
    }

    suspend fun tryResolvePendingIntent(
        pending: PendingIntent,
        userMessage: String,
        devicePlugins: List<Plugin>,
        traceId: String,
        mode: PipelineMode = PipelineMode.EXECUTE
    ): DeviceCommandResult? {
        val targetPlugin = devicePlugins.find { it.manifest.id == pending.pluginId } ?: run {
            chatHistoryManager.clearPendingIntent(pending.username)
            return null
        }
        val targetAction = targetPlugin.manifest.actions.find { it.name == pending.action } ?: run {
            chatHistoryManager.clearPendingIntent(pending.username)
            return null
        }

        val noProgressCount = pending.knownParams["_noProgressCount"]?.toString()?.toIntOrNull() ?: 0
        if (noProgressCount >= 2) {
            logger.w("PendingIntentResolver", "[$traceId] ⚠️ Pending bị lặp lại không có tiến triển -> xóa pending")
            
            if (mode == PipelineMode.EXECUTE) {
                chatHistoryManager.removePendingIntent(pending.username, pending.pluginId, pending.action)
                val failedPending = pending.copy(
                    knownParams = pending.knownParams + mapOf("_cancelReason" to "no_progress")
                )
                chatHistoryManager.addExpiredNotification(failedPending)
            }
            return null
        }

        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD).toFloat())
        // ✅ ĐÃ SỬA: dùng pending.username (chủ sở hữu thật của pending này) thay vì hardcode
        // "default_user" — trước đây match theo alias/intent riêng của chủ nhà dù người đang
        // trả lời dở dang có thể là 1 guest khác (Facebook/Telegram) hoàn toàn không liên quan.
        val matchResult = trainingSkill.fuzzyMatchCategorized(userMessage, pending.username, aliasThreshold = aliasThreshold)

        val localEntities = mutableMapOf<String, Any>()
        EMAIL_REGEX.find(userMessage)?.value?.let { localEntities["email"] = it }
        DateTimeParser.parseVietnameseTime(userMessage)?.let { localEntities["cron"] = it }
        DateTimeParser.parseVietnameseInterval(userMessage)?.let { localEntities["intervalMinutes"] = it }

        val heuristicFilled = mutableMapOf<String, Any>()
        for (param in pending.missingParams) {
            val trimmed = userMessage.trim()
            val isNested = param.startsWith("params.")
            val actualKey = if (isNested) param.removePrefix("params.") else param

            val paramMeta = if (isNested) {
                val targetPluginId = pending.knownParams["plugin_id"]?.toString()
                    ?: pending.knownParams["pluginId"]?.toString()
                    ?: pending.knownParams["plugin"]?.toString() ?: ""
                val targetActionName = pending.knownParams["action_id"]?.toString()
                    ?: pending.knownParams["action"]?.toString()
                    ?: pending.knownParams["actionId"]?.toString() ?: ""
                val tPlugin = devicePlugins.find { it.manifest.id == targetPluginId }
                tPlugin?.manifest?.actions?.find { it.name == targetActionName }?.parameters?.find { it.name == actualKey }
            } else {
                targetAction.parameters.find { it.name == actualKey }
            }

            if (paramMeta == null) continue

            when (paramMeta.semanticType.lowercase()) {
                "plugin_id" -> {
                    // ✅ SỬA (Bug: bấm nút chọn pluginId xong thì action biến mất): trước đây nhánh
                    // này tự điền luôn CẢ "action" từ cùng 1 secondaryIntentQA — nếu user gõ/bấm
                    // "camera", fuzzy match trả về intent camera.scan, nhánh này đọc cả plugin="camera"
                    // VÀ action="scan" rồi nhét vào heuristicFilled trong cùng 1 lượt, khiến bước
                    // hỏi action biến mất hoàn toàn. Tách ra: nhánh plugin_id CHỈ điền pluginId, để
                    // nhánh action_id tự xử lý ở lượt sau (khi người dùng thật sự chọn action).
                    // params (nested) vẫn giữ lại vì chúng không bị hỏi ở lượt này.
                    val matchedIntent = matchResult.intentMatches
                        .filter { it.first.type == "intent" }
                        .map { it.first }
                        .filterNot { it.category == "auto_init_incomplete" }
                        .firstOrNull { candidate ->
                            // Không mượn từ QA của chính schedule để tránh action="add" nhầm
                            try { JSONObject(candidate.answer).optString("plugin", "") != "schedule" }
                            catch (_: Exception) { true }
                        }
                    if (matchedIntent != null) {
                        try {
                            val secJson = JSONObject(matchedIntent.answer)
                            heuristicFilled[param] = secJson.optString("plugin", "")
                            // Chỉ pre-fill params lồng, KHÔNG pre-fill action — action phải được
                            // hỏi riêng để người dùng xác nhận, không tự suy
                            val secParams = secJson.optJSONObject("params")?.toMap() ?: emptyMap()
                            if (secParams.isNotEmpty()) {
                                heuristicFilled["params"] = secParams
                            }
                        } catch (_: Exception) {}
                    }
                }
                "action_id" -> {
                    // ✅ SỬA: cùng bộ lọc với nhánh plugin_id — không mượn từ QA auto_init_incomplete
                    // hoặc QA của chính schedule (tránh action="add" nhầm khi đang hỏi action đích)
                    val matchedIntent = matchResult.intentMatches
                        .filter { it.first.type == "intent" }
                        .map { it.first }
                        .filterNot { it.category == "auto_init_incomplete" }
                        .firstOrNull { candidate ->
                            try { JSONObject(candidate.answer).optString("plugin", "") != "schedule" }
                            catch (_: Exception) { true }
                        }
                    if (matchedIntent != null) {
                        try {
                            val secJson = JSONObject(matchedIntent.answer)
                            heuristicFilled[param] = secJson.optString("action", "")
                        } catch (_: Exception) {}
                    }
                }
                "time" -> {
                    val parsedCron = DateTimeParser.parseVietnameseTime(userMessage)
                    if (parsedCron != null) {
                        heuristicFilled[param] = parsedCron
                    }
                }
                "interval" -> {
                    val parsedInterval = DateTimeParser.parseVietnameseInterval(userMessage)
                    if (parsedInterval != null) {
                        heuristicFilled[param] = parsedInterval
                    } else {
                        val numericVal = userMessage.trim().toIntOrNull()
                        if (numericVal != null) {
                            heuristicFilled[param] = numericVal
                        }
                    }
                }
                else -> {
                    val matchedAliasVal = aliasMatchesForType(matchResult, paramMeta.semanticType)
                    if (matchedAliasVal != null) {
                        heuristicFilled[param] = matchedAliasVal
                    } else {
                        val isEmailParam = paramMeta.semanticType == "email" || actualKey == "email" || actualKey == "to" || actualKey == "recipient"
                        if (isEmailParam && localEntities.containsKey("email")) {
                            heuristicFilled[param] = localEntities["email"]!!
                        } else if (paramMeta.type.lowercase() == "string" && trimmed.isNotBlank()) {
                            val textParams = setOf("subject", "body", "message", "title")
                            if (actualKey in textParams) {
                                if (param == pending.missingParams.first()) {
                                    heuristicFilled[param] = trimmed
                                }
                            }
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val activeOptions = pending.knownParams["_options"] as? Map<String, String> ?: emptyMap()
        val askedParamNow = pending.missingParams.firstOrNull()

        // ✅ SỬA (Bug nghiêm trọng, vỡ TOÀN BỘ nút bấm chọn nhanh): nút bấm gửi thẳng VALUE thật
        // (vd "smart_switch", "true", mã camera dài) làm tin nhắn — trước đây 2 nhánh khớp bên
        // dưới chỉ nhận diện được LABEL text (khớp mờ với "Số N. label" trong câu hỏi) hoặc SỐ thứ
        // tự (regex bắt chữ số), không nhánh nào khớp trực tiếp value. Value không chứa chữ số (vd
        // "smart_switch") và không khớp label hiển thị → cả 2 nhánh cũ đều trượt → heuristicFilled
        // rỗng → hệ thống coi là "đổi chủ đề", HUỶ pending ngay (xem nhánh else phía dưới) — đúng
        // nguyên nhân "bấm nút không vào được action bên trong" / "lên lịch bị nhầm sang hỏi đáp".
        // Thêm nhánh khớp CHÍNH XÁC theo value, chạy TRƯỚC 2 nhánh fuzzy — vì đây là so khớp tuyệt
        // đối (button chỉ gửi đúng giá trị đã có sẵn trong activeOptions.values), không cần đoán.
        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val trimmed = userMessage.trim()
            val exactValueMatch = activeOptions.values.firstOrNull { it == trimmed }
            if (exactValueMatch != null) {
                heuristicFilled[askedParamNow] = exactValueMatch
                logger.d("PendingIntentResolver", "[$traceId] Người dùng bấm nút -> value \"$exactValueMatch\" khớp chính xác (bypass LLM)")
            }
        }

        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val userNorm = StringSimilarityUtil.normalizeVietnamese(userMessage.lowercase().trim())
            if (userNorm.isNotBlank()) {
                val labelByIndex = Regex("Số (\\d+)\\.\\s*(.+)").findAll(pending.askedQuestion ?: "")
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val matchedIndex = labelByIndex.entries.firstOrNull { (_, label) ->
                    val labelNorm = StringSimilarityUtil.normalizeVietnamese(label.lowercase().replace(".", " "))
                    labelNorm.contains(userNorm) || userNorm.contains(labelNorm.substringBefore(" ("))
                }?.key
                matchedIndex?.let { idx ->
                    activeOptions[idx]?.let { resolvedValue ->
                        heuristicFilled[askedParamNow] = resolvedValue
                        logger.d("PendingIntentResolver", "[$traceId] Người dùng chọn theo tên \"$userNorm\" -> option #$idx -> \"$resolvedValue\" (bypass LLM)")
                    }
                }
            }
        }

        if (askedParamNow != null && activeOptions.isNotEmpty() && !heuristicFilled.containsKey(askedParamNow)) {
            val norm = StringSimilarityUtil.normalizeVietnamese(userMessage.lowercase().trim())
            val chosenNumber = Regex("\\b(?:so|chon|cau|thu|\\s+)?\\s*(\\d+)\\b").find(norm)?.groupValues?.get(1)
                ?: Regex("\\b(\\d+)\\b").find(norm)?.value
                ?: Regex("(?<!\\w)(\\d+)(?!\\w)").find(norm)?.value
            
            chosenNumber?.let { num ->
                activeOptions[num]?.let { resolvedValue ->
                    heuristicFilled[askedParamNow] = resolvedValue
                    logger.d("PendingIntentResolver", "[$traceId] Người dùng chọn số $num -> \"$resolvedValue\" (bypass LLM)")
                }
            }
        }

        val currentAskedParam = pending.missingParams.firstOrNull()
        
        val fill = if (currentAskedParam != null && heuristicFilled.containsKey(currentAskedParam)) {
            logger.d("PendingIntentResolver", "[$traceId] Heuristic tự xử lý thành công tham số '$currentAskedParam'. Bypass LLM.")
            heuristicFilled
        } else {
            logger.d("PendingIntentResolver", "[$traceId] Heuristic không khớp tự động được '$currentAskedParam'.")

            if (mode == PipelineMode.DIAGNOSTIC) {
                logger.d("PendingIntentResolver", "[$traceId] 🔵 [DIAGNOSTIC] Tầng 1 dở dang, chưa khớp heuristic.")
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                )
            }

            // ✅ SỬA: bỏ hẳn cuộc gọi Groq phụ (model nhỏ, KHÔNG có tool db_search) từng dùng để
            // đoán xem câu trả lời có điền được tham số hay là user đổi chủ đề — cuộc gọi này vừa
            // tốn thêm 1 lượt LLM + latency, vừa không giải quyết được vấn đề gốc nếu câu trả lời
            // thực ra là 1 câu hỏi cần tra dữ liệu thật (model này không search được). Heuristic đã
            // không khớp được tham số đang hỏi → coi thẳng là user đổi chủ đề, huỷ pending ngay, để
            // tin nhắn rơi xuống pipeline chat chính (đã có Two-Pass db_search xử lý đúng và đầy đủ
            // hơn nhiều so với cuộc gọi phụ này).
            logger.d("PendingIntentResolver", "[$traceId] Coi là đổi chủ đề, huỷ pending (bỏ gọi LLM phụ để tiết kiệm token/latency).")
            chatHistoryManager.clearPendingIntent(pending.username)
            return null
        }

        val finalFilled = fill + heuristicFilled

        val flatFilled = finalFilled.filterKeys { !it.startsWith("params.") && it != "params" }
        val nestedFilled = finalFilled.filterKeys { it.startsWith("params.") }.mapKeys { it.key.removePrefix("params.") }

        @Suppress("UNCHECKED_CAST")
        val existingNested = (pending.knownParams["params"] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value ?: "") } ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val mergedNested = (existingNested as Map<String, Any>) + nestedFilled + (finalFilled["params"] as? Map<String, Any> ?: emptyMap())

        val mergedParams = pending.knownParams + flatFilled +
            if (mergedNested.isNotEmpty()) mapOf("params" to mergedNested) else emptyMap()

        val normalizedMergedParams = ParameterResolver.normalizeParams(mergedParams, targetPlugin, pending.action, plugins, userMessage)

        // ✅ SỬA (Bug nghiêm trọng: dialog lịch trình "chốt xong" nhưng bỏ sót tham số lồng):
        // trước đây chỉ LỌC LẠI đúng cái pending.missingParams đã tính 1 LẦN DUY NHẤT ở lượt đầu
        // tiên (lúc pluginId/action của schedule.add còn CHƯA có giá trị) — nên nhánh "đào vào
        // params lồng" của ParameterResolver.getUnresolvedParams() (chỉ chạy được khi
        // targetPluginId/targetAction đã biết) KHÔNG BAO GIỜ có cơ hội chạy lại sau khi 2 tham số
        // đó vừa được người dùng điền xong ở chính pending flow này — params.device/params.state
        // của action đích (vd smart_switch.set) không bao giờ lọt vào danh sách hỏi, dialog coi
        // như "đủ" rồi gọi thẳng execute(), rơi xuống ScheduleSkill.findMissingRequiredParams()
        // trả failure() cứng. Gọi lại getUnresolvedParams() ở ĐÂY thay vì lọc list cũ — hàm này
        // tự idempotent (luôn tính lại dựa trên state params hiện tại), nên gọi lại bao nhiêu lần
        // cũng ra đúng phần còn thiếu, kể cả các params.* chỉ "lộ diện" được sau khi pluginId/
        // action đã biết.
        val stillMissing = ParameterResolver.getUnresolvedParams(
            normalizedMergedParams, targetPlugin, pending.action, plugins
        )

        if (stillMissing.isNotEmpty()) {
            // ✅ SỬA (Bug thật, không chỉ lý thuyết): trước đây dùng
            // "stillMissing.size < pending.missingParams.size" làm thước đo tiến triển — sai khi
            // pluginId+action VỪA được biết đủ ở lượt này, khiến nhánh đào params lồng "mở khoá"
            // và phát hiện thêm params.device/params.state MỚI cùng lúc: tổng số thiếu có thể TĂNG
            // (vd 2 -> 3) dù người dùng vừa trả lời đúng câu được hỏi — bị tính oan là "không tiến
            // triển", cộng dồn đủ 2 lần sẽ huỷ nhầm 1 pending đang trả lời bình thường. Đổi sang đo
            // đúng bản chất: câu hỏi VỪA ĐẶT RA (missingParams.first(), tương ứng askedQuestion) có
            // còn nằm trong danh sách thiếu hay không — không quan tâm tổng số tăng/giảm do phát
            // hiện thêm việc mới.
            val askedParam = pending.missingParams.firstOrNull()
            val madeProgress = askedParam == null || askedParam !in stillMissing
            val newNoProgressCount = if (madeProgress) 0 else noProgressCount + 1
            // 🌟 SỬA: truyền thêm normalizedMergedParams làm "knownParams" — cần thiết để
            // getQuestionForMissingParam giải quyết đúng các case phân tầng (vd hỏi "action"
            // sau khi "pluginId" vừa được chọn ở lượt trước).
            val (question, options, displayOptions) = intentExecutor.getQuestionForMissingParam(stillMissing.first(), targetPlugin, pending.action, normalizedMergedParams)

            // ✅ ĐÃ SỬA: giữ nguyên createdAt gốc (để sắp thứ tự hàng đợi ổn định — xem
            // ChatHistoryManager.getActivePendingIntents()), CHỈ cập nhật lastInteractionAt
            // (để TTL 3 phút tính đúng từ lần người dùng tương tác gần nhất, không hủy oan
            // pending đang được trả lời dở dang).
            chatHistoryManager.addPendingIntent(
                pending.copy(
                    knownParams = normalizedMergedParams + mapOf(
                        "_noProgressCount" to newNoProgressCount,
                        "_options" to options,
                        // ✅ MỚI (nút bấm chọn nhanh trong chat — hoàn thiện nốt nhánh "nhiều pending
                        // xếp hàng" ở RoutingPipeline.kt): cache thêm label song song với "_options"
                        // (chỉ có value) — trước đây banner của pending KẾ TIẾP trong hàng đợi không
                        // có cách nào lấy lại label để vẽ nút, phải rớt về hỏi bằng text.
                        "_displayOptions" to displayOptions
                    ),
                    missingParams = stillMissing,
                    askedQuestion = question,
                    lastInteractionAt = System.currentTimeMillis()
                )
            )
            chatHistoryManager.addTurn(pending.username, userMessage, question)
            return DeviceCommandResult(
                pluginId = targetPlugin.manifest.id,
                result = PluginResult.NeedMoreInfo(stillMissing, question, options, displayOptions)
            )
        }

        chatHistoryManager.removePendingIntent(pending.username, pending.pluginId, pending.action)
        // ✅ SỬA (Bug nghiêm trọng: bỏ qua checkPolicy khi resolve pending intent — vd chọn số
        // "1" để chỉ định thiết bị): trước đây gọi thẳng targetPlugin.execute(...), bỏ qua HOÀN
        // TOÀN checkWorldStateGuard/checkDeviceWorldStateGuard/checkPolicy (nên Chính sách an
        // toàn vắng nhà không chặn được) VÀ cả bước tự ghi world_state khi thành công. Dùng lại
        // intentExecutor.executePluginAction() — đúng 1 con đường thực thi duy nhất đã có đủ các
        // bước bảo vệ, thay vì lặp lại (và làm thiếu) logic ở đây.
        val executionResult = try {
            intentExecutor.executePluginAction(targetPlugin.manifest.id, pending.action, normalizedMergedParams)
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success -> (executionResult.data as? Map<*, *>? )?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(pending.username, userMessage, replyForHistory)

        return DeviceCommandResult(pluginId = targetPlugin.manifest.id, result = executionResult)
    }
}