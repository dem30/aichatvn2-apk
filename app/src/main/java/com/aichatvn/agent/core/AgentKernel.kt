package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private val EMAIL_REGEX = Regex("[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

/**
 * Cấu trúc dữ liệu chứa thông tin Intent QA đã được phân tích và lưu kèm score tương đồng.
 */
data class ParsedQAIntent(
    val qa: QAEntity,
    val pluginId: String,
    val action: String,
    val rawParams: Map<String, Any>,
    val score: Double
)

/**
 * Cấu trúc đại diện cho một Candidate được lọc cục bộ từ DB Search trước khi gửi tới LLM.
 */
data class LocalCandidate(
    val pluginId: String,
    val action: String,
    val description: String,
    val parameters: List<String>
)

/**
 * AgentKernel v12 — Kiến trúc 3 Tầng tối ưu hóa theo quy chuẩn metadata động và lọc ngưỡng an toàn cấu hình động.
 */
@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) {

    companion object {
        /**
         * Các param key mà resolveParamsWithQA() được phép replace.
         */
        private val ALIAS_PARAM_KEYS = setOf(
            "to", "email", "recipient",                          // email
            "device", "device_id", "deviceId",                   // thiết bị Tuya
            "camera", "camera_id", "cameraId",                   // camera
            "schedule", "schedule_id", "scheduleId"              // lịch trình / cron
        )

        /**
         * Các giá trị placeholder tĩnh mà QAInitBuilder seed vào Intent QA.
         * Nếu sau resolve() param vẫn khớp 1 trong các giá trị này → chưa resolve được,
         * không cho Tier 2 thực thi.
         */
        private val PLACEHOLDER_VALUES = setOf(
            "device_1", "device_2", "camera_1", "camera_2",
            "example@gmail.com", "example@email.com",
            "schedule_1", "schedule_id_here"
        )

        /**
         * Bảng ánh xạ nhanh từ Việt hóa sang Plugin ID tương ứng ở local (0 token).
         */
        private val PLUGIN_ID_TRANSLATIONS = mapOf(
            "thông báo" to "notification",
            "thong bao" to "notification",
            "gửi thông báo" to "notification",
            "email" to "email",
            "thư" to "email",
            "gửi mail" to "email",
            "gửi email" to "email",
            "tuya" to "tuya",
            "thiết bị" to "tuya",
            "đèn" to "tuya",
            "camera" to "camera",
            "lịch" to "schedule",
            "lịch trình" to "schedule"
        )

        /**
         * Hệ thống fallback các tham số bắt buộc theo cấu trúc cũ nếu plugin không có metadata.
         */
        private val REQUIRED_NONEMPTY_PARAMS: Map<String, Set<String>?> = mapOf(
            "device"      to null,
            "device_id"   to null,
            "deviceId"    to null,
            "camera"      to null,
            "camera_id"   to null,
            "cameraId"    to null,
            "to"          to null,
            "email"       to null,
            "recipient"   to null,
            "subject"     to setOf("send", "send_email", "sendEmail"),
            "body"        to setOf("send", "send_email", "sendEmail"),
            "title"       to setOf("send", "send_notification", "sendNotification"),
            "message"     to setOf("send", "send_notification", "sendNotification")
        )
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.visibleInQuickBar }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val devicePlugins = plugins.filter { it.visibleInQuickBar }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 1: Pending intent còn dở dang
        // ─────────────────────────────────────────────────────────────────
        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val rawQAMatchesWithScores = buildRawQAMatches(userMessage, username)
        val rawQAMatches = rawQAMatchesWithScores.map { it.first }

        // Phân tích cú pháp Intent QA một lần duy nhất kèm theo score tương đồng thu được
        val parsedQAIntents = rawQAMatchesWithScores.mapNotNull { (qa, score) ->
            if (!trainingSkill.isIntentQA(qa)) null
            else {
                try {
                    val json = JSONObject(qa.answer.trim())
                    val pluginId = json.optString("plugin", "")
                    val action = json.optString("action", "")
                    if (pluginId.isNotEmpty() && action.isNotEmpty()) {
                        val rawParams = json.optJSONObject("params")?.toMap() ?: emptyMap()
                        ParsedQAIntent(qa, pluginId, action, rawParams, score)
                    } else null
                } catch (e: Exception) {
                    logger.e("AgentKernel", "Lỗi phân tích cú pháp Intent QA ban đầu (id=${qa.id}): ${e.message}")
                    null
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 2: Fuzzy match QA Intent JSON theo ngưỡng động từ AppConfig (0 token)
        // ─────────────────────────────────────────────────────────────────
        val tier2Result = tryTier2FuzzyQAIntent(userMessage, parsedQAIntents, rawQAMatches, devicePlugins)
        if (tier2Result != null) {
            val (t2Plugin, t2Intent) = tier2Result
            logger.d("AgentKernel", "✅ Tier 2 HIT: ${t2Intent.pluginId}.${t2Intent.action} | params=${t2Intent.params}")
            return try {
                val result = executeIntent(t2Plugin, t2Intent, userMessage)
                RouterOutcome.Matched(DeviceCommandResult(pluginId = t2Intent.pluginId, result = result))
            } catch (e: Exception) {
                logger.e("AgentKernel", "Tier 2 execute error: ${e.message}", e)
                RouterOutcome.RouterFailed("Tier 2 execute failed: ${e.message}")
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 3: Chuẩn hóa ý định (Intent Formatter) dựa trên Local Candidates
        // ─────────────────────────────────────────────────────────────────
        logger.d("AgentKernel", "🔵 Tier 3: Tầng 2 miss -> Chuẩn hóa ý định qua LLM Formatter")
        return executeTier3LlmRouting(userMessage, parsedQAIntents, rawQAMatches, devicePlugins)
    }

    /**
     * Xác định các tham số bắt buộc bị thiếu dựa trên gộp cả metadata động và danh sách fallback tối thiểu.
     */
    private fun getUnresolvedParams(params: Map<String, Any>, plugin: Plugin, actionName: String): List<String> {
        val missing = mutableListOf<String>()

        // 1. Kiểm tra riêng cho lịch trình (schedule.add)
        if (plugin.id == "schedule" && actionName == "add") {
            val targetPluginId = params["pluginId"]?.toString()
            if (targetPluginId.isNullOrBlank()) {
                missing.add("pluginId")
            }

            // Validate nested params của target action trong schedule
            val targetAction = params["action"]?.toString()
            @Suppress("UNCHECKED_CAST")
            val nestedParams = params["params"] as? Map<String, Any>
            if (!targetPluginId.isNullOrBlank() && !targetAction.isNullOrBlank()) {
                val targetPlugin = plugins.find { it.id == targetPluginId }
                val targetActionObj = targetPlugin?.getActions()?.find { it.name == targetAction }
                
                if (nestedParams == null) {
                    targetActionObj?.parameters?.filter { it.required }?.forEach { param ->
                        missing.add("params.${param.name}")
                    }
                } else {
                    targetActionObj?.parameters?.filter { it.required }?.forEach { param ->
                        val v = nestedParams[param.name]?.toString() ?: ""
                        if (v.isBlank() || (param.name in ALIAS_PARAM_KEYS && v in PLACEHOLDER_VALUES)) {
                            missing.add("params.${param.name}")
                        }
                    }
                }
            }
        }

        // 2. Tìm action và tập hợp danh sách khóa tham số bắt buộc (gộp cả metadata và legacy fallback)
        val requiredParamKeys = mutableSetOf<String>()
        val action = plugin.getActions().find { it.name == actionName }

        if (action != null) {
            requiredParamKeys.addAll(action.parameters.filter { it.required }.map { it.name })
        }

        // Bổ sung legacy fallback để tránh thiếu sót đối với cấu hình plugin cũ
        for ((key, applicableActions) in REQUIRED_NONEMPTY_PARAMS) {
            val isApplicable = applicableActions == null || actionName in applicableActions
            if (isApplicable) {
                requiredParamKeys.add(key)
            }
        }

        // 3. Tiến hành kiểm tra giá trị thực tế của các tham số bắt buộc
        for (paramName in requiredParamKeys) {
            val value = params[paramName]
            val strVal = value?.toString() ?: ""
            if (strVal.isBlank() || (paramName in ALIAS_PARAM_KEYS && strVal in PLACEHOLDER_VALUES)) {
                missing.add(paramName)
            }
        }

        return missing.distinct()
    }

    private fun getQuestionForMissingParam(param: String): String {
        return when (param) {
            "device", "device_id", "deviceId"          -> "Bạn muốn điều khiển thiết bị nào?"
            "camera", "camera_id", "cameraId"          -> "Bạn muốn xem camera nào?"
            "to", "email", "recipient"                 -> "Bạn muốn gửi đến email nào?"
            "subject"                                  -> "Tiêu đề email là gì thế bạn?"
            "body"                                     -> "Nội dung email bạn muốn viết gì?"
            "title"                                    -> "Tiêu đề thông báo là gì vậy bạn?"
            "message"                                  -> "Nội dung thông báo bạn muốn gửi là gì?"
            "pluginId"                                 -> "Bạn muốn lên lịch cho chức năng nào (ví dụ: email, thông báo, thiết bị)?"
            "params.to", "params.email",
            "params.recipient"                         -> "Email nhận trong lịch định kỳ là gì?"
            "params.subject"                           -> "Tiêu đề email trong lịch định kỳ là gì?"
            "params.body"                              -> "Nội dung email trong lịch định kỳ bạn muốn gửi gì?"
            "params.device", "params.device_id",
            "params.deviceId"                          -> "Thiết bị cần điều khiển trong lịch là gì?"
            "params.camera", "params.camera_id",
            "params.cameraId"                          -> "Camera cần giám sát trong lịch là gì?"
            "params.title"                             -> "Tiêu đề thông báo trong lịch là gì?"
            "params.message"                           -> "Nội dung thông báo trong lịch bạn muốn gửi gì?"
            else                                       -> "Bạn vui lòng cung cấp thông tin cho '$param' nhé?"
        }
    }

    private fun tryTier2FuzzyQAIntent(
        userMessage: String,
        parsedQAIntents: List<ParsedQAIntent>,
        rawQAMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): Pair<Plugin, Intent>? {
        val dynamicMinScore = configProvider.allConfigs.value
            .find { it.key == AppConfigDefaults.GLOBAL_TIER2_MIN_SCORE }
            ?.value?.toDoubleOrNull() ?: 0.3

        val bestCandidate = parsedQAIntents
            .filter { it.score >= dynamicMinScore }
            .sortedByDescending { it.score }
            .firstOrNull { candidate ->
                val plugin = devicePlugins.find { it.id == candidate.pluginId }
                plugin != null && plugin.getActions().any { it.name == candidate.action }
            } ?: return null

        val plugin = devicePlugins.first { it.id == bestCandidate.pluginId }
        val resolvedParams = resolveParamsWithQA(bestCandidate.rawParams, rawQAMatches, userMessage)

        val sanitizedParams = resolvedParams.mapValues { (key, value) ->
            val strVal = value.toString()
            if (key in ALIAS_PARAM_KEYS && strVal in PLACEHOLDER_VALUES) {
                ""
            } else {
                value
            }
        }

        return Pair(plugin, Intent(bestCandidate.pluginId, bestCandidate.action, sanitizedParams))
    }

    private fun gatherLocalCandidates(
        userMessage: String,
        parsedQAIntents: List<ParsedQAIntent>,
        devicePlugins: List<Plugin>,
        isScheduleIntent: Boolean
    ): List<LocalCandidate> {
        val candidates = mutableListOf<LocalCandidate>()

        // 1. Thêm các ứng viên trực tiếp từ parsed QA Intents khớp được
        for (qaIntent in parsedQAIntents) {
            val plugin = devicePlugins.find { it.id == qaIntent.pluginId } ?: continue
            val actionObj = plugin.getActions().find { it.name == qaIntent.action } ?: continue
            val paramsSig = actionObj.parameters.map { p -> if (p.required) p.name else "${p.name}?" }
            
            candidates.add(
                LocalCandidate(
                    pluginId = plugin.id,
                    action = actionObj.name,
                    description = actionObj.description,
                    parameters = paramsSig
                )
            )
        }

        val lowerMessage = userMessage.lowercase()
        val stopWords = setOf("và", "với", "cho", "của", "để", "bởi", "tại", "trong", "ngoài", "ra", "vào", "lên", "xuống", "đang", "đã", "sẽ", "được", "bị")
        val queryWords = lowerMessage.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stopWords }

        // 2. Quét kết hợp cả ID/Tên Action lẫn Mô tả (Description) để tránh bỏ sót các plugin phụ trợ liên đới
        for (plugin in devicePlugins) {
            val pluginIdMatch = plugin.id.isNotBlank() && lowerMessage.contains(plugin.id.lowercase())
            
            for (action in plugin.getActions()) {
                val actionWords = action.name.split(Regex("(?=[A-Z])|_|\\s")).map { it.lowercase() }
                val descWords = action.description.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))

                val matchesIdOrAction = pluginIdMatch || actionWords.any { it in queryWords }
                val matchesDescription = descWords.any { it in queryWords }

                if (matchesIdOrAction || matchesDescription) {
                    val paramsSig = action.parameters.map { p -> if (p.required) p.name else "${p.name}?" }
                    val cand = LocalCandidate(
                        pluginId = plugin.id,
                        action = action.name,
                        description = action.description,
                        parameters = paramsSig
                    )
                    if (cand !in candidates) {
                        candidates.add(cand)
                    }
                }
            }
        }

        // 3. Tự động đính kèm plugin "schedule" nếu câu lệnh mang tính chất đặt lịch định kỳ
        if (isScheduleIntent) {
            val schedPlugin = devicePlugins.find { it.id == "schedule" }
            if (schedPlugin != null) {
                for (action in schedPlugin.getActions()) {
                    val paramsSig = action.parameters.map { p -> if (p.required) p.name else "${p.name}?" }
                    val candidate = LocalCandidate(
                        pluginId = "schedule",
                        action = action.name,
                        description = action.description,
                        parameters = paramsSig
                    )
                    if (candidate !in candidates) {
                        candidates.add(0, candidate)
                    }
                }
            }
        }

        return candidates
    }

    private suspend fun executeTier3LlmRouting(
        userMessage: String,
        parsedQAIntents: List<ParsedQAIntent>,
        rawQAMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): RouterOutcome {
        val isScheduleIntent = userMessage.contains(
            Regex("lịch|schedule|cron|mỗi|định kỳ|đặt lịch|tạo lịch", RegexOption.IGNORE_CASE))

        val candidates = gatherLocalCandidates(userMessage, parsedQAIntents, devicePlugins, isScheduleIntent)

        if (candidates.isEmpty()) {
            logger.d("AgentKernel", "⏭️ Tier 3 skip: Không tìm thấy Plugin/Action candidate nào khớp cục bộ -> Bỏ qua LLM")
            return RouterOutcome.NotACommand
        }

        logger.d("AgentKernel", "Tier 3 Candidates: ${candidates.map { "${it.pluginId}.${it.action}" }}")

        val candidateLines = candidates.joinToString("\n") { c ->
            "  - ${c.pluginId}.${c.action}(${c.parameters.joinToString(",")})"
        }

        val qaFacts = rawQAMatches
            .filterNot { trainingSkill.isIntentQA(it) }
            .take(3)
            .joinToString("\n") { qa ->
                "${qa.question} = ${qa.answer}"
            }

        val needsSchedule = userMessage.contains(
            Regex("lịch|schedule|cron|xoá lịch|xóa lịch", RegexOption.IGNORE_CASE))
        val schedulesContext = if (!needsSchedule) "" else try {
            val schedPlugin = devicePlugins.find { it.id == "schedule" }
            val result = schedPlugin?.execute("list", emptyMap())
            @Suppress("UNCHECKED_CAST")
            val list = (result as? PluginResult.Success)?.data as? List<*>
            if (!list.isNullOrEmpty()) {
                val lines = list.take(3).mapIndexed { i, s ->
                    val e = s as? com.aichatvn.agent.data.model.ScheduleEntity
                        ?: return@mapIndexed ""
                    "#${i + 1} id=${e.id} ${e.pluginId}.${e.action} " +
                        if (e.cron.isNotEmpty()) e.cron else "${e.intervalMinutes}m"
                }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) "<schedules>\n${lines.joinToString("\n")}\n</schedules>\n"
                else ""
            } else ""
        } catch (_: Exception) { "" }

        val shortHistory = chatHistoryManager.getRecentTurnsAsText()
        val lastDevice   = chatHistoryManager.lastMentionedDeviceId ?: "none"

        val routerPrompt = buildString {
            append("<sys>Intent Formatter. JSON only: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("Rules:\n")
            append("- Map user input to one of the active candidates below.\n")
            append("- If general talk/not a command, output plugin:\"chat\", action:\"none\".\n")
            append("- Use <qa> to map aliases (e.g., names to emails, nicknames to IDs).\n")
            append("- Optional param keys end with '?'; omit '?' in final JSON.\n")
            append("- If plugin==\"schedule\" and action==\"add\": 'params.params' MUST be a nested object containing all required parameters of the target action.\n")
            append("- Time conversion for schedule cron: '7h tối'='0 19 * * *', '8h sáng'='0 8 * * *', 'mỗi ngày lúc Xh'='0 X * * *', 'mỗi X phút'=intervalMinutes=X (set cron=\"\").\n")
            append("- Output raw JSON. No explanation.</sys>\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            if (qaFacts.isNotEmpty()) append("<qa>\n$qaFacts\n</qa>\n")
            if (schedulesContext.isNotEmpty()) append(schedulesContext)
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>$userMessage</input>\n")
            append("<output>")
        }

        val routerResultJson = try {
            withTimeout(15_000L) {
                groqClient.routeIntent(routerPrompt)
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("AgentKernel", "Tier 3 routeIntent timeout sau 15s")
            return RouterOutcome.RouterFailed("Tier 3: timeout khi gọi router")
        } catch (e: Exception) {
            logger.e("AgentKernel", "Tier 3 routeIntent error: ${e.message}", e)
            return RouterOutcome.RouterFailed("Tier 3: lỗi gọi router: ${e.message}")
        }
        val rawIntent = parseIntentResponse(routerResultJson, userMessage)
            ?: return RouterOutcome.RouterFailed("Tier 3: không parse được JSON: $routerResultJson")

        if (rawIntent.pluginId.isBlank())
            return RouterOutcome.RouterFailed("Tier 3: router trả về plugin rỗng")
        if (rawIntent.pluginId == "chat")
            return RouterOutcome.NotACommand

        val allowedIds = devicePlugins.map { it.id }.toSet()
        if (rawIntent.pluginId !in allowedIds) {
            logger.w("AgentKernel", "Tier 3: LLM đề xuất plugin không hợp lệ: ${rawIntent.pluginId}")
            return RouterOutcome.RouterFailed("Tier 3: plugin không hợp lệ: ${rawIntent.pluginId}")
        }

        val targetPlugin = devicePlugins.find { it.id == rawIntent.pluginId }
            ?: return RouterOutcome.RouterFailed("Tier 3: không tìm thấy plugin: ${rawIntent.pluginId}")

        if (targetPlugin.getActions().none { it.name == rawIntent.action }) {
            return RouterOutcome.RouterFailed("Tier 3: action không hợp lệ: ${rawIntent.action} trong plugin ${targetPlugin.id}")
        }

        val intent = rawIntent.copy(params = resolveParamsWithQA(rawIntent.params, rawQAMatches, userMessage))
        if (rawIntent.params != intent.params) {
            logger.d("AgentKernel", "🔄 Tier 3 alias fallback: ${rawIntent.params} → ${intent.params}")
        }

        logger.d("AgentKernel", "🔥 Tier 3 matched: ${intent.pluginId}.${intent.action} | params=${intent.params}")

        val result = executeIntent(targetPlugin, intent, userMessage)
        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.id, result = result))
    }

    private suspend fun executeIntent(
        plugin: Plugin,
        intent: Intent,
        userMessage: String
    ): PluginResult {
        val normalizedParams = normalizeParams(intent.params, plugin, intent.action, userMessage)
        val normalizedIntent = intent.copy(params = normalizedParams)

        val device = normalizedIntent.params["device"] ?: normalizedIntent.params["device_id"] ?: normalizedIntent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val missing = getUnresolvedParams(normalizedIntent.params, plugin, normalizedIntent.action)

        val executionResult = if (missing.isNotEmpty()) {
            val question = getQuestionForMissingParam(missing.first())
            PluginResult.NeedMoreInfo(missing, question)
        } else {
            try {
                plugin.execute(normalizedIntent.action, normalizedIntent.params)
            } catch (e: Exception) {
                logger.e("AgentKernel", "Execute error: ${e.message}", e)
                PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
            }
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = plugin.id,
                    action = normalizedIntent.action,
                    knownParams = normalizedIntent.params + mapOf("_noProgressCount" to 0),
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question
                )
            )
            else -> chatHistoryManager.clearPendingIntent()
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success ->
                (executionResult.data as? Map<*, *>)?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(userMessage, replyForHistory)

        return executionResult
    }

    private fun looksLikeNewCommand(userMessage: String, pendingPluginId: String, devicePlugins: List<Plugin>): Boolean {
        val lower = userMessage.trim().lowercase()

        val stopWords = setOf(
            "và", "với", "cho", "của", "để", "bởi", "tại", "trong", "ngoài", 
            "ra", "vào", "lên", "xuống", "đang", "đã", "sẽ", "được", "bị"
        )

        for (plugin in devicePlugins) {
            if (plugin.id == pendingPluginId) continue

            if (plugin.id.isNotBlank() && lower.contains(plugin.id.lowercase())) {
                return true
            }

            for (action in plugin.getActions()) {
                val actionWords = action.name.split(Regex("(?=[A-Z])|_|\\s"))
                    .map { it.lowercase() }
                    .filter { it.length > 2 && it !in stopWords }
                
                if (actionWords.any { lower.contains(it) }) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun tryResolvePendingIntent(
        pending: PendingIntent,
        userMessage: String,
        devicePlugins: List<Plugin>
    ): DeviceCommandResult? {
        val targetPlugin = devicePlugins.find { it.id == pending.pluginId } ?: run {
            chatHistoryManager.clearPendingIntent()
            return null
        }
        if (targetPlugin.getActions().none { it.name == pending.action }) {
            chatHistoryManager.clearPendingIntent()
            return null
        }

        val noProgressCount = pending.knownParams["_noProgressCount"]?.toString()?.toIntOrNull() ?: 0
        if (noProgressCount >= 2) {
            logger.w("AgentKernel", "⚠️ Pending stuck $noProgressCount lần liên tiếp không có tiến triển → clear & fallthrough Tier 3")
            chatHistoryManager.clearPendingIntent()
            return null
        }

        val pendingQAMatchesWithScores = buildRawQAMatches(userMessage)
        val pendingQAMatches = pendingQAMatchesWithScores.map { it.first }

        val lower = userMessage.trim().lowercase()
        val cancelWords = listOf("dừng", "hủy", "huỷ", "bỏ qua")
        if (cancelWords.any { lower.contains(it) }) {
            chatHistoryManager.clearPendingIntent()
            chatHistoryManager.addTurn(userMessage, "Đã huỷ lệnh trước đó.")
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.Success(mapOf("message" to "Đã huỷ lệnh trước đó."))
            )
        }

        val heuristicFilled = mutableMapOf<String, Any>()
        for (param in pending.missingParams) {
            val trimmed = userMessage.trim()

            val isNested = param.startsWith("params.")
            val actualKey = if (isNested) param.removePrefix("params.") else param

            // 0. Heuristic ánh xạ nhanh Việt - Anh cho pluginId (0 token)
            if (actualKey == "pluginId") {
                val mappedId = PLUGIN_ID_TRANSLATIONS[trimmed.lowercase()]
                if (mappedId != null) {
                    heuristicFilled[param] = mappedId
                    continue
                }
                val exactPlugin = devicePlugins.find { it.id.equals(trimmed, ignoreCase = true) }
                if (exactPlugin != null) {
                    heuristicFilled[param] = exactPlugin.id
                    continue
                }
            }

            // 1. Kiểm tra khớp Regex cơ bản
            if (actualKey == "to" || actualKey == "email" || actualKey == "recipient") {
                val emailMatch = EMAIL_REGEX.find(trimmed)
                if (emailMatch != null) {
                    heuristicFilled[param] = emailMatch.value
                    continue
                }
            }
            if (actualKey == "device" || actualKey == "device_id" || actualKey == "deviceId") {
                val relayRegex = Regex("relay[\\s_]?(\\d+)", RegexOption.IGNORE_CASE)
                val match = relayRegex.find(trimmed)
                if (match != null) {
                    heuristicFilled[param] = "relay${match.groupValues[1]}"
                    continue
                }
            }

            // 2. Tra cứu alias cục bộ bằng QA matches trước khi chuyển tiếp cho LLM
            if (actualKey in ALIAS_PARAM_KEYS) {
                val tempMap = mapOf(actualKey to trimmed)
                val resolvedMap = resolveParamsWithQA(tempMap, pendingQAMatches, userMessage)
                val resolvedValue = resolvedMap[actualKey]?.toString() ?: ""
                if (resolvedValue.isNotEmpty() && resolvedValue != trimmed) {
                    heuristicFilled[param] = resolvedValue
                    continue
                }
            }

            // 3. Với các tham số văn bản thuần túy (gạt bỏ nhu cầu LLM nếu người dùng nhập thẳng câu chữ)
            if (!heuristicFilled.containsKey(param)) {
                val textParams = setOf("subject", "body", "message", "title")
                if (actualKey in textParams && trimmed.isNotBlank()) {
                    heuristicFilled[param] = trimmed
                }
            }
        }

        val filled = if (heuristicFilled.size == pending.missingParams.size) {
            logger.d("AgentKernel", "⚡ Pending resolved locally via heuristic/regex/alias (0 tokens saved)")
            heuristicFilled
        } else {
            val availablePluginsCtx = devicePlugins.joinToString("\n") { p ->
                "  - pluginId: \"${p.id}\" (Hành động: ${p.getActions().map { it.name }.joinToString(", ")})"
            }

            val fillPrompt = buildString {
                append("<system>Output ONLY raw JSON, KHÔNG giải thích.\n")
                append("User đang trả lời 1 câu hỏi bổ sung thông tin cho lệnh còn thiếu param.\n")
                append("Nếu trả lời của user CUNG CẤP được giá trị cho (các) param còn thiếu, output:\n")
                append("{\"params\": {${pending.missingParams.joinToString(",") { "\"$it\": \"giá_trị\"" }}}}\n")
                append("Nếu trả lời của user là 1 yêu cầu/câu hỏi KHÁC, KHÔNG liên quan câu hỏi đang chờ, output:\n")
                append("{\"unrelated\": true}</system>\n")
                
                append("<available_plugins>\n$availablePluginsCtx\n</available_plugins>\n")
                
                if (heuristicFilled.isNotEmpty()) {
                    append("<heuristic_matches>\n")
                    heuristicFilled.forEach { (k, v) ->
                        append("Đã phát hiện heuristic cho $k = \"$v\".\n")
                    }
                    append("Do đó câu trả lời của user là RẤT LIÊN QUAN. Hãy trích xuất các param còn lại từ input của user.\n")
                    append("</heuristic_matches>\n")
                }
                append("<param_can_dien>${pending.missingParams.joinToString(", ")}</param_can_dien>\n")
                append("<cau_hoi_da_hoi>${pending.askedQuestion}</cau_hoi_da_hoi>\n")
                append("<tra_loi_cua_user>$userMessage</tra_loi_cua_user>\n")
                append("<output>")
            }

            val parsedJson = try {
                withTimeout(10_000L) {
                    val rawJson = groqClient.routeIntent(fillPrompt)
                    val cleaned = rawJson.trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    JSONObject(cleaned)
                }
            } catch (e: Exception) {
                logger.e("AgentKernel", "tryResolvePendingIntent route/parse error: ${e.message}", e)
                null
            }

            if (parsedJson == null) {
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                )
            }

            if (parsedJson.optBoolean("unrelated", false)) {
                if (heuristicFilled.isNotEmpty()) {
                    heuristicFilled
                } else {
                    if (looksLikeNewCommand(userMessage, pending.pluginId, devicePlugins)) {
                        chatHistoryManager.clearPendingIntent()
                        return null
                    } else {
                        chatHistoryManager.setPendingIntent(
                            pending.copy(createdAt = System.currentTimeMillis())
                        )
                        return DeviceCommandResult(
                            pluginId = pending.pluginId,
                            result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                        )
                    }
                }
            } else {
                val paramsObj = parsedJson.optJSONObject("params") ?: JSONObject()
                heuristicFilled.forEach { (k, v) ->
                    if (!paramsObj.has(k)) {
                        paramsObj.put(k, v)
                    }
                }
                paramsObj.toMap()
            }
        }

        val filledResolved = resolveParamsWithQA(filled, pendingQAMatches, userMessage)
        if (filled != filledResolved) {
            logger.d("AgentKernel", "🔄 Pending alias resolve: $filled → $filledResolved")
        }

        val flatFilled = filledResolved.filterKeys { !it.startsWith("params.") }
        val nestedFilled = filledResolved
            .filterKeys { it.startsWith("params.") }
            .mapKeys { it.key.removePrefix("params.") }

        val existingNested = (pending.knownParams["params"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to (it.value ?: "") } ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val mergedNested = (existingNested as Map<String, Any>) + nestedFilled

        val knownBefore = pending.knownParams.filterKeys { !it.startsWith("_") }

        val mergedParams = pending.knownParams + flatFilled +
            if (mergedNested.isNotEmpty()) mapOf("params" to mergedNested) else emptyMap()

        val normalizedMergedParams = normalizeParams(mergedParams, targetPlugin, pending.action, userMessage)

        val stillMissing = pending.missingParams.filter { key ->
            if (key.startsWith("params.")) {
                val nestedKey = key.removePrefix("params.")
                @Suppress("UNCHECKED_CAST")
                val nested = normalizedMergedParams["params"] as? Map<String, Any>
                val v = nested?.get(nestedKey)
                v == null || v.toString().equals("null", ignoreCase = true) || (v is String && v.isBlank())
            } else {
                val v = normalizedMergedParams[key]
                v == null || v.toString().equals("null", ignoreCase = true) || (v is String && v.isBlank())
            }
        }

        if (stillMissing.isNotEmpty()) {
            val knownAfter = normalizedMergedParams.filterKeys { !it.startsWith("_") }
            val madeProgress = knownAfter != knownBefore
            val newNoProgressCount = if (madeProgress) 0 else noProgressCount + 1
            logger.d("AgentKernel", "Pending progress=$madeProgress noProgressCount=$newNoProgressCount stillMissing=$stillMissing")

            val question = getQuestionForMissingParam(stillMissing.first())
            chatHistoryManager.setPendingIntent(
                pending.copy(
                    knownParams = normalizedMergedParams + mapOf("_noProgressCount" to newNoProgressCount),
                    missingParams = stillMissing,
                    askedQuestion = question,
                    createdAt = System.currentTimeMillis()
                )
            )
            chatHistoryManager.addTurn(userMessage, question)
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.NeedMoreInfo(stillMissing, question)
            )
        }

        chatHistoryManager.clearPendingIntent()

        val executionResult = try {
            targetPlugin.execute(pending.action, normalizedMergedParams)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute pending error: ${e.message}", e)
            PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
        }

        if (executionResult is PluginResult.NeedMoreInfo) {
            chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = targetPlugin.id,
                    action = pending.action,
                    knownParams = normalizedMergedParams,
                    missingParams = executionResult.missingParams,
                    askedQuestion = executionResult.question
                )
            )
        }

        val replyForHistory = when (executionResult) {
            is PluginResult.Success -> (executionResult.data as? Map<*, *>)?.get("message") as? String ?: "Đã thực hiện."
            is PluginResult.Failure -> executionResult.error
            is PluginResult.NeedMoreInfo -> executionResult.question
        }
        chatHistoryManager.addTurn(userMessage, replyForHistory)

        return DeviceCommandResult(pluginId = targetPlugin.id, result = executionResult)
    }

    suspend fun process(userMessage: String): PluginResult {
        logger.d("AgentKernel", "Processing: '$userMessage'")
        val outcome = tryDeviceCommand(userMessage)
        return when (outcome) {
            is RouterOutcome.Matched -> outcome.result.result
            is RouterOutcome.RouterFailed -> PluginResult.Failure(outcome.reason)
            is RouterOutcome.NotACommand -> PluginResult.Failure("Không phải lệnh thiết bị — dùng ChatSkill.processQuery() cho chat thường")
        }
    }

    private suspend fun buildRawQAMatches(
        message: String,
        username: String = "default_user"
    ): List<Pair<QAEntity, Double>> {
        return try {
            val result = trainingSkill.fuzzyMatchQuestion(message, username)
            when (result) {
                is PluginResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    val matches = result.data as? List<Map<String, Any>> ?: return emptyList()
                    matches.mapNotNull {
                        val qa = it["qa"] as? QAEntity
                        if (qa != null) {
                            val score = (it["score"] ?: it["similarity"] ?: it["percent"] ?: 1.0)
                                .toString().toDoubleOrNull() ?: 1.0
                            Pair(qa, score)
                        } else null
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.e("AgentKernel", "buildRawQAMatches error: ${e.message}", e)
            emptyList()
        }
    }

    private fun resolveParamsWithQA(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>,
        userMessage: String? = null
    ): Map<String, Any> {
        if (qaMatches.isEmpty()) return params

        val aliasOnlyQA = qaMatches.filterNot { trainingSkill.isIntentQA(it) }
        if (aliasOnlyQA.isEmpty()) return params
        val sortedQA = aliasOnlyQA.sortedByDescending { it.question.length }

        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                return@mapValues resolveParamsWithQA(value as Map<String, Any>, qaMatches, userMessage)
            }
            if (key !in ALIAS_PARAM_KEYS) return@mapValues value
            if (value !is String) return@mapValues value
            if (value !in PLACEHOLDER_VALUES && EMAIL_REGEX.matches(value)) return@mapValues value

            val directMatch = sortedQA.firstOrNull { qa ->
                val boundaryRegex = Regex("\\b${Regex.escape(qa.question)}\\b", RegexOption.IGNORE_CASE)
                boundaryRegex.containsMatchIn(value) || qa.question.trim().equals(value.trim(), ignoreCase = true)
            }
            if (directMatch != null) return@mapValues directMatch.answer.trim()

            if (userMessage != null) {
                val messageMatch = sortedQA.firstOrNull { qa ->
                    val escapedQuestion = Regex.escape(qa.question)
                    val regex = Regex("(?i)\\b$escapedQuestion\\b")
                    regex.containsMatchIn(userMessage) || userMessage.contains(qa.question, ignoreCase = true)
                }
                if (messageMatch != null) {
                    return@mapValues messageMatch.answer.trim()
                }
            }
            value
        }
    }

    private fun normalizeParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String, 
        userMessage: String? = null
    ): Map<String, Any> {
        val action = plugin.getActions().find { it.name == actionName } ?: return params

        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *> && plugin.id == "schedule" && actionName == "add") {
                @Suppress("UNCHECKED_CAST")
                val nested = value as Map<String, Any>
                val targetPluginId = params["pluginId"]?.toString() ?: ""
                val targetAction = params["action"]?.toString() ?: ""
                val targetPlugin = plugins.find { it.id == targetPluginId }
                return@mapValues if (targetPlugin != null && targetAction.isNotEmpty()) {
                    normalizeParams(nested, targetPlugin, targetAction, userMessage)
                } else nested
            }

            val paramMeta = action.parameters.find { it.name == key }
            if (paramMeta != null && paramMeta.type.lowercase() == "boolean") {
                val rawValue = if ((value.toString().isBlank() || value.toString() == "null") && userMessage != null) {
                    extractBooleanFromMessage(userMessage) ?: value
                } else {
                    value
                }
                parseBooleanSmart(rawValue) ?: rawValue
            } else {
                value
            }
        }
    }

    private fun parseBooleanSmart(value: Any?): Boolean? {
        if (value is Boolean) return value
        val str = value?.toString()?.trim()?.lowercase() ?: return null

        val trueWords = setOf("true", "mở", "bật", "yes", "on", "1", "kích hoạt", "hoạt động")
        val falseWords = setOf("false", "tắt", "no", "off", "0", "vô hiệu", "dừng")

        return when {
            str in trueWords -> true
            str in falseWords -> false
            else -> null
        }
    }

    private fun extractBooleanFromMessage(userMessage: String): Boolean? {
        val str = userMessage.lowercase()
        val trueWords = setOf("mở", "bật", "on", "kích hoạt", "hoạt động")
        val falseWords = setOf("tắt", "off", "vô hiệu", "dừng")

        val hasTrue = trueWords.any { str.contains(it) }
        val hasFalse = falseWords.any { str.contains(it) }

        return when {
            hasTrue && !hasFalse -> true
            hasFalse && !hasTrue -> false
            else -> null
        }
    }

    private fun parseIntentResponse(response: String, originalMessage: String): Intent? {
        return try {
            val cleaned = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json     = JSONObject(cleaned)
            val pluginId = json.optString("plugin", "").takeIf { it.isNotEmpty() } ?: return null
            val action   = json.optString("action",  "").takeIf { it.isNotEmpty() } ?: return null
            Intent(
                pluginId = pluginId,
                action   = action,
                params   = json.optJSONObject("params")?.toMap() ?: emptyMap()
            )
        } catch (e: Exception) {
            logger.e("AgentKernel", "Parse intent error: ${e.message}, response: $response")
            null
        }
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            if (value != org.json.JSONObject.NULL) {
                map[key] = when (value) {
                    is JSONObject -> value.toMap()
                    is org.json.JSONArray -> {
                        val list = mutableListOf<Any>()
                        for (i in 0 until value.length()) {
                            val item = value.get(i)
                            if (item != org.json.JSONObject.NULL) {
                                list.add(when (item) {
                                    is JSONObject -> item.toMap()
                                    else -> item
                                })
                            }
                        }
                        list
                    }
                    else -> value
                }
            }
        }
        return map
    }

    data class Intent(
        val pluginId: String,
        val action: String,
        val params: Map<String, Any> = emptyMap()
    )

    data class DeviceCommandResult(
        val pluginId: String,
        val result: PluginResult
    )

    sealed class RouterOutcome {
        data class Matched(val result: DeviceCommandResult) : RouterOutcome()
        object NotACommand : RouterOutcome()
        data class RouterFailed(val reason: String) : RouterOutcome()
    }

    sealed class PluginResult {
        data class Success(val data: Any) : PluginResult()
        data class Failure(val error: String) : PluginResult()
        data class NeedMoreInfo(val missingParams: List<String>, val question: String) : PluginResult()
    }
}