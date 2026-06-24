package com.aichatvn.agent.core

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

private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/**
 * AgentKernel v9 (Optimized with Advanced Pending Safeguards & Safe Coalescing)
 */
@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
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

        // ─────────────────────────────────────────────────────────────────
        // TIER 0: Keyword → Plugin patterns (0 token, no LLM)
        // ─────────────────────────────────────────────────────────────────

        private val TIER_ZERO_PATTERNS = mapOf(
            "light" to listOf(
                Regex("(bật|tắt|mở|đóng|sáng|tối).*?(đèn|light|phòng)", RegexOption.IGNORE_CASE)
            ),
            "device" to listOf(
                Regex("(bật|tắt|mở|đóng|kích hoạt|vô hiệu|switch|on|off).*?(?:relay|device|thiết bị)",
                      RegexOption.IGNORE_CASE),
                Regex("relay[\\s_]?\\d+", RegexOption.IGNORE_CASE)
            ),
            "camera" to listOf(
                Regex("(camera|giám sát|snapshot|chụp)", RegexOption.IGNORE_CASE),
                Regex("(hình ảnh|ảnh|snapshot).*?camera", RegexOption.IGNORE_CASE)
            ),
            "email" to listOf(
                Regex("(gửi|send).*?(mail|email|thư)", RegexOption.IGNORE_CASE),
                Regex("(mail|email).*?(cho|to|gửi)", RegexOption.IGNORE_CASE)
            ),
            "schedule" to listOf(
                Regex("(đặt lịch|tạo lịch|thêm lịch|add schedule)", RegexOption.IGNORE_CASE),
                Regex("(mỗi|định kỳ).*?(phút|giờ|ngày|tuần|lịch|schedule|cron)", RegexOption.IGNORE_CASE)
            )
        )

        /**
         * Regex extract param value trực tiếp từ text message.
         */
        private val EXTRACTABLE_PARAM_PATTERNS = mapOf(
            "device_id" to listOf(
                Regex("relay[\\s_]?(\\d+)", RegexOption.IGNORE_CASE),
                Regex("thiết bị[\\s_]?([a-z0-9_]+)", RegexOption.IGNORE_CASE)
            ),
            "camera_id" to listOf(
                Regex("camera[\\s_]?([a-z0-9_]+)", RegexOption.IGNORE_CASE)
            ),
            "to" to listOf(
                Regex("\\b[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
            ),
            "subject" to listOf(
                Regex("subject:?\\s*([\\w\\sàáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]{1,50})", RegexOption.IGNORE_CASE)
            )
        )

        // ─────────────────────────────────────────────────────────────────
        // TIER 1: Plugin keyword scoring (smart filtering)
        // ─────────────────────────────────────────────────────────────────

        private val PLUGIN_KEYWORDS = mapOf(
            "device" to listOf(
                "device", "thiết bị", "relay", "switch", "kích hoạt", "vô hiệu"
            ),
            "light" to listOf(
                "đèn", "light", "sáng", "tối", "ánh sáng", "thắp sáng"
            ),
            "camera" to listOf(
                "camera", "giám sát", "snapshot", "hình ảnh", "chụp", "ảnh"
            ),
            "email" to listOf(
                "mail", "email", "gửi", "thư", "tin nhắn"
            ),
            "schedule" to listOf(
                "lịch", "schedule", "cron", "định hạn", "mỗi", "định kỳ",
                "phút", "giờ", "ngày", "tuần"
            )
        )
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.visibleInQuickBar }

    private var tier0Calls = 0
    private var tier0Hits  = 0
    private var tier1Calls = 0

    fun getRoutingMetrics(): Map<String, Any> = mapOf(
        "tier0_total"    to tier0Calls,
        "tier0_hit_rate" to if (tier0Calls > 0) tier0Hits * 100 / tier0Calls else 0,
        "tier1_total"    to tier1Calls,
        "tokens_saved_approx" to (tier0Hits * 2400)
    )

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val devicePlugins = plugins.filter { it.visibleInQuickBar }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ─────────────────────────────────────────────────────────────────
        // PRE-CHECK: Pending intent
        // ─────────────────────────────────────────────────────────────────
        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val rawQAMatches = buildRawQAMatches(userMessage, username)

        // ─────────────────────────────────────────────────────────────────
        // TIER 0: Keyword match, không gọi LLM
        // ─────────────────────────────────────────────────────────────────
        tier0Calls++
        val tier0Result = tryTierZeroIntent(userMessage, rawQAMatches, devicePlugins)
        if (tier0Result != null) {
            tier0Hits++
            val (t0Plugin, t0Intent) = tier0Result
            logger.d("AgentKernel", "✅ Tier 0 HIT: ${t0Intent.pluginId}.${t0Intent.action} | params=${t0Intent.params}")
            
            // Cập nhật thiết bị an toàn bằng cơ chế coalescing để tránh ghi đè lỗi
            val device = t0Intent.params["device_id"] ?: t0Intent.params["deviceId"] ?: t0Intent.params["device"]
            device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

            return try {
                val result = t0Plugin.execute(t0Intent.action, t0Intent.params)
                val replyForHistory = when (result) {
                    is PluginResult.Success ->
                        (result.data as? Map<*, *>)?.get("message") as? String ?: "Đã thực hiện."
                    is PluginResult.Failure -> result.error
                    is PluginResult.NeedMoreInfo -> result.question
                }
                when (result) {
                    is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                        PendingIntent(
                            pluginId = t0Intent.pluginId,
                            action = t0Intent.action,
                            knownParams = t0Intent.params,
                            missingParams = result.missingParams,
                            askedQuestion = result.question
                        )
                    )
                    else -> chatHistoryManager.clearPendingIntent()
                }
                chatHistoryManager.addTurn(userMessage, replyForHistory)
                RouterOutcome.Matched(DeviceCommandResult(pluginId = t0Intent.pluginId, result = result))
            } catch (e: Exception) {
                logger.e("AgentKernel", "Tier 0 execute error: ${e.message}", e)
                RouterOutcome.RouterFailed("Tier 0 execute failed: ${e.message}")
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // TIER 1: Smart plugin filtering → LLM router
        // ─────────────────────────────────────────────────────────────────
        tier1Calls++
        logger.d("AgentKernel", "🔵 Tier 1: Keyword miss → LLM routing")
        return executeTier1Routing(userMessage, rawQAMatches, devicePlugins)
    }

    private suspend fun tryTierZeroIntent(
        userMessage: String,
        qaMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): Pair<Plugin, Intent>? {
        // Step 1: Detect plugin
        val detectedPluginId = TIER_ZERO_PATTERNS.entries.firstOrNull { (_, patterns) ->
            patterns.any { it.containsMatchIn(userMessage) }
        }?.key ?: run {
            logger.d("AgentKernel", "🟡 Tier 0: No keyword pattern match")
            return null
        }

        val plugin = devicePlugins.find { it.id == detectedPluginId } ?: return null
        logger.d("AgentKernel", "🔍 Tier 0: Plugin detected = $detectedPluginId")

        // Step 2: Select action
        val lowerMsg = userMessage.lowercase()
        val action = when {
            lowerMsg.containsAny("bật", "mở", "on", "kích hoạt") ->
                plugin.getActions().find { it.name.lowercase().contains("on") || it.name == "turnOn" }
                    ?: plugin.getActions().firstOrNull()
            lowerMsg.containsAny("tắt", "đóng", "off", "vô hiệu") ->
                plugin.getActions().find { it.name.lowercase().contains("off") || it.name == "turnOff" }
                    ?: plugin.getActions().firstOrNull()
            else -> plugin.getActions().firstOrNull()
        } ?: return null

        // Step 3a: Extract params từ regex pattern trong message
        val extractedParams = mutableMapOf<String, Any>()
        EXTRACTABLE_PARAM_PATTERNS.forEach { (paramName, patterns) ->
            if (paramName in action.parameters.map { it.name }) {
                patterns.forEach { pattern ->
                    if (paramName !in extractedParams) {
                        val match = pattern.find(userMessage)
                        if (match != null) {
                            val value = if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty())
                                match.groupValues[1] else match.value
                            extractedParams[paramName] = value
                            logger.d("AgentKernel", "  ✓ Tier 0 extracted $paramName = $value (regex)")
                        }
                    }
                }
            }
        }

        // Step 3b: Resolve alias params từ QA facts (Sử dụng ranh giới từ để khớp chính xác hơn)
        val paramsToResolve = action.parameters
            .filter { it.required && it.name in ALIAS_PARAM_KEYS && it.name !in extractedParams }

        val sortedQA = qaMatches.sortedByDescending { it.question.length }
        paramsToResolve.forEach { param ->
            val matchedQA = sortedQA.firstOrNull { qa ->
                val boundaryRegex = Regex("\\b${Regex.escape(qa.question)}\\b", RegexOption.IGNORE_CASE)
                boundaryRegex.containsMatchIn(userMessage)
            }
            if (matchedQA != null) {
                extractedParams[param.name] = matchedQA.answer
                logger.d("AgentKernel", "  ✓ Tier 0 resolved ${param.name} = ${matchedQA.answer} (QA)")
            }
        }

        // Step 3c: Dùng lastMentionedDeviceId làm fallback cho device_id
        val lastDevice = chatHistoryManager.lastMentionedDeviceId
        if (lastDevice != null) {
            val deviceParamNames = setOf("device_id", "deviceId", "device")
            action.parameters
                .filter { it.name in deviceParamNames && it.name !in extractedParams }
                .forEach {
                    extractedParams[it.name] = lastDevice
                    logger.d("AgentKernel", "  ✓ Tier 0 used lastMentionedDevice = $lastDevice")
                }
        }

        // Step 4: Validate
        val missingRequired = action.parameters
            .filter { it.required }
            .map { it.name }
            .filter { it !in extractedParams }

        if (missingRequired.isNotEmpty()) {
            logger.d("AgentKernel", "❌ Tier 0: Missing $missingRequired → fallback Tier 1")
            return null
        }

        logger.d("AgentKernel", "✅ Tier 0 ready: $detectedPluginId.${action.name} | params=$extractedParams")

        return Pair(
            plugin,
            Intent(pluginId = detectedPluginId, action = action.name, params = extractedParams)
        )
    }

    /**
     * Sắp xếp và chọn ra các plugin tương quan nhất.
     * Giải quyết triệt để lỗi chứa chuỗi con (contains match) bằng cách tách từ (split tokens) 
     * và chỉ so khớp trọn vẹn ranh giới từ (\bword\b).
     */
    private fun filterRelevantPlugins(
        userMessage: String,
        allPlugins: List<Plugin>
    ): List<Plugin> {
        val userWords = userMessage.lowercase()
            .split(Regex("[^\\wàáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]+"))
            .filter { it.isNotEmpty() }
            .toSet()

        val scores = allPlugins.map { plugin ->
            var score = 0.0

            // Keyword score (weight 2x) sử dụng ranh giới từ chính xác
            val keywordHits = PLUGIN_KEYWORDS[plugin.id]
                ?.count { keyword ->
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(userMessage)
                } ?: 0
            score += keywordHits * 2.0

            // Action description word match (weight 1x) chỉ so khớp ranh giới từ để loại bỏ case "ảnh" match nhầm "ảnh hưởng"
            val descHits = plugin.getActions()
                .flatMap { action ->
                    action.description.lowercase()
                        .split(Regex("[^\\wàáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]+"))
                }
                .count { word -> word.length > 2 && userWords.contains(word) }
            score += descHits.toDouble()

            if (userMessage.contains(plugin.id, ignoreCase = true)) score += 1.5

            Pair(plugin, score)
        }

        val relevant = scores
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Plugin, Double>> { it.second }.thenBy { it.first.id })
            .take(5)
            .map { it.first }

        val result = relevant.ifEmpty {
            allPlugins.filter { it.id in listOf("device", "schedule", "camera", "light", "email") }
                .take(5)
        }

        logger.d("AgentKernel", "🔵 Tier 1 plugin filter: ${result.size} selected = ${result.map { it.id }}")
        return result
    }

    private suspend fun executeTier1Routing(
        userMessage: String,
        rawQAMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): RouterOutcome {
        val relevantPlugins = filterRelevantPlugins(userMessage, devicePlugins)

        val compactCatalog = relevantPlugins.joinToString("\n\n") { plugin ->
            val actionLines = plugin.getActions().joinToString("\n") { action ->
                val paramsSig = action.parameters.joinToString(",") { p ->
                    if (p.required) p.name else "${p.name}?"
                }
                val sig = if (paramsSig.isEmpty()) action.name else "${action.name}($paramsSig)"
                "  - $sig — ${action.description}"
            }
            "plugin \"${plugin.id}\":\n$actionLines"
        }

        val qaFacts = if (rawQAMatches.isEmpty()) ""
        else rawQAMatches.take(3).joinToString("\n") { qa ->
            "${qa.question} = ${qa.answer}"
        }

        val needsSchedule = userMessage.contains(
            Regex("lịch|schedule|cron|xoá lịch|xóa lịch", RegexOption.IGNORE_CASE))
        val schedulesContext = if (!needsSchedule) "" else try {
            val schedPlugin = relevantPlugins.find { it.id == "schedule" }
                ?: devicePlugins.find { it.id == "schedule" }
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
            append("<system>You are an Intent Router. Output ONLY raw JSON: ")
            append("{\"plugin\": string, \"action\": string, \"params\": object}.\n")
            append("Rules:\n")
            append("- Only use plugins listed in <plugins> below.\n")
            append("- Use <qa_facts> to resolve aliases and fill param values.\n")
            append("  Example: \"vinh = vinh@gmail.com\" and user says \"send to vinh\" → params.to = \"vinh@gmail.com\".\n")
            append("  Example: \"lịch hôm nay = mỗi 10 phút\" → set relevant schedule param = \"mỗi 10 phút\".\n")
            append("- param keys with '?' are optional; omit '?' from key name.\n")
            append("- If general conversation (not a command), output ")
            append("{\"plugin\":\"chat\",\"action\":\"none\",\"params\":{}}.\n")
            append("- If plugin == \"schedule\" and action == \"add\": params.params PHẢI chứa ĐẦY ĐỦ ")
            append("tham số bắt buộc của action đích (params.pluginId + params.action) theo đúng schema.\n")
            append("- Do NOT add explanation. Output JSON only.</system>\n")
            append("<plugins>\n$compactCatalog\n</plugins>\n")
            if (qaFacts.isNotEmpty()) append("<qa_facts>\n$qaFacts\n</qa_facts>\n")
            if (schedulesContext.isNotEmpty()) append(schedulesContext)
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>User: $userMessage</input>\n")
            append("<output>")
        }

        val routerResultJson = try {
            withTimeout(15_000L) {
                groqClient.routeIntent(routerPrompt)
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("AgentKernel", "Tier 1 routeIntent timeout sau 15s — mạng chậm/treo")
            return RouterOutcome.RouterFailed("Tier 1: timeout khi gọi router (mạng chậm/treo)")
        } catch (e: Exception) {
            logger.e("AgentKernel", "Tier 1 routeIntent error: ${e.message}", e)
            return RouterOutcome.RouterFailed("Tier 1: lỗi gọi router: ${e.message}")
        }
        val rawIntent = parseIntentResponse(routerResultJson, userMessage)
            ?: return RouterOutcome.RouterFailed("Tier 1: không parse được JSON: $routerResultJson")

        if (rawIntent.pluginId.isBlank())
            return RouterOutcome.RouterFailed("Tier 1: router trả về plugin rỗng")
        if (rawIntent.pluginId == "chat")
            return RouterOutcome.NotACommand

        val allowedIds = relevantPlugins.map { it.id }.toSet() + devicePlugins.map { it.id }.toSet()
        if (rawIntent.pluginId !in allowedIds) {
            logger.w("AgentKernel", "Tier 1: LLM đề xuất plugin không hợp lệ: ${rawIntent.pluginId}")
            return RouterOutcome.RouterFailed("Tier 1: plugin không hợp lệ: ${rawIntent.pluginId}")
        }

        val targetPlugin = devicePlugins.find { it.id == rawIntent.pluginId }
            ?: return RouterOutcome.RouterFailed("Tier 1: không tìm thấy plugin: ${rawIntent.pluginId}")

        // Validate Action hợp lệ của plugin
        if (targetPlugin.getActions().none { it.name == rawIntent.action }) {
            return RouterOutcome.RouterFailed("Tier 1: action không hợp lệ: ${rawIntent.action} trong plugin ${targetPlugin.id}")
        }

        val intent = rawIntent.copy(params = resolveAliasParams(rawIntent.params, rawQAMatches))
        if (rawIntent.params != intent.params) {
            logger.d("AgentKernel", "🔄 Tier 1 alias fallback: ${rawIntent.params} → ${intent.params}")
        }

        logger.d("AgentKernel", "🔥 Tier 1 matched: ${intent.pluginId}.${intent.action} | params=${intent.params}")

        // Cập nhật thiết bị an toàn bằng cơ chế coalescing tránh ghi đè sai living_room
        val device = intent.params["device_id"] ?: intent.params["deviceId"] ?: intent.params["device"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val executionResult = try {
            targetPlugin.execute(intent.action, intent.params)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Tier 1 execute error: ${e.message}", e)
            PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = targetPlugin.id,
                    action = intent.action,
                    knownParams = intent.params,
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

        return RouterOutcome.Matched(DeviceCommandResult(pluginId = targetPlugin.id, result = executionResult))
    }

    /**
     * Hỗ trợ kiểm tra xem tin nhắn mới có thực sự là một lệnh điều khiển mới hay không.
     */
    private fun looksLikeNewCommand(userMessage: String, devicePlugins: List<Plugin>): Boolean {
        // 1. So khớp mẫu Regex của Tier 0
        val hasTier0Match = TIER_ZERO_PATTERNS.values.any { patterns ->
            patterns.any { it.containsMatchIn(userMessage) }
        }
        if (hasTier0Match) return true

        // 2. Chấm điểm nhanh từ khóa: nếu có ít nhất 2 từ khóa của bất kỳ plugin nào khớp chính xác, đây là lệnh mới
        val scores = devicePlugins.map { plugin ->
            val keywordHits = PLUGIN_KEYWORDS[plugin.id]
                ?.count { keyword ->
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(userMessage)
                } ?: 0
            keywordHits
        }
        if (scores.any { it >= 2 }) return true

        return false
    }

    /**
     * Bổ sung heuristics/validation nhanh để tự động điền các param định dạng rõ ràng (ví dụ email),
     * tránh việc LLM dịch nhầm sang "unrelated" rồi xóa oan ngữ cảnh pending.
     * Sử dụng block try/catch và timeout an toàn xung quanh GroqClient.
     */
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

        val lower = userMessage.trim().lowercase()
        val cancelWords = listOf("không cần", "huỷ", "hủy", "thôi khỏi", "bỏ qua", "quên đi", "khỏi cần")
        if (cancelWords.any { lower.contains(it) }) {
            chatHistoryManager.clearPendingIntent()
            chatHistoryManager.addTurn(userMessage, "Đã huỷ lệnh trước đó.")
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.Success(mapOf("message" to "Đã huỷ lệnh trước đó."))
            )
        }

        // 1. Heuristic Validation nhanh không cần LLM để bảo vệ dữ liệu cấu trúc
        val heuristicFilled = mutableMapOf<String, Any>()
        for (param in pending.missingParams) {
            val trimmed = userMessage.trim()
            if ((param == "to" || param == "email" || param == "recipient") && EMAIL_REGEX.matches(trimmed)) {
                heuristicFilled[param] = trimmed
            }
            if (param == "device_id" || param == "deviceId" || param == "device") {
                val relayRegex = Regex("relay[\\s_]?(\\d+)", RegexOption.IGNORE_CASE)
                val match = relayRegex.find(trimmed)
                if (match != null) {
                    heuristicFilled[param] = "relay${match.groupValues[1]}"
                }
            }
        }

        // 2. Định tuyến bằng LLM nếu Heuristics chưa giải quyết hết toàn bộ các tham số còn thiếu
        val finalParsed = if (heuristicFilled.size == pending.missingParams.size) {
            JSONObject().put("params", JSONObject(heuristicFilled))
        } else {
            val fillPrompt = buildString {
                append("<system>Output ONLY raw JSON, KHÔNG giải thích.\n")
                append("User đang trả lời 1 câu hỏi bổ sung thông tin cho lệnh còn thiếu param.\n")
                append("Nếu trả lời của user CUNG CẤP được giá trị cho (các) param còn thiếu, output:\n")
                append("{\"params\": {${pending.missingParams.joinToString(",") { "\"$it\": \"giá_trị\"" }}}}\n")
                append("Nếu trả lời của user là 1 yêu cầu/câu hỏi KHÁC, KHÔNG liên quan câu hỏi đang chờ, output:\n")
                append("{\"unrelated\": true}</system>\n")
                
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
                // Do mạng lỗi hoặc không parse được JSON, không xóa pending, tiếp tục hỏi lại
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                )
            }

            if (parsedJson.optBoolean("unrelated", false)) {
                if (heuristicFilled.isNotEmpty()) {
                    // Lưới bảo vệ: LLM báo "unrelated" nhưng heuristics nhận diện được một phần, bỏ qua cờ unrelated
                    JSONObject().put("params", JSONObject(heuristicFilled))
                } else {
                    // Kiểm tra xem có phải là lệnh mới hay không trước khi hủy pending
                    if (looksLikeNewCommand(userMessage, devicePlugins)) {
                        chatHistoryManager.clearPendingIntent()
                        return null // Rơi xuống Tier 0 / Tier 1 bên ngoài để xử trị lệnh mới
                    } else {
                        // Trả lời bình thường không định dạng, bảo vệ pending bằng cách giữ lại và hỏi lại
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
                // Gộp kết quả heuristics tự nhận diện vào JSON kết quả của LLM
                val paramsObj = parsedJson.optJSONObject("params") ?: JSONObject()
                heuristicFilled.forEach { (k, v) ->
                    if (!paramsObj.has(k)) {
                        paramsObj.put(k, v)
                    }
                }
                parsedJson.put("params", paramsObj)
                parsedJson
            }
        }

        val filled = finalParsed?.optJSONObject("params")?.toMap() ?: emptyMap()
        val stillMissing = pending.missingParams.filter { key ->
            val v = filled[key]
            v == null || v.toString().equals("null", ignoreCase = true) ||
                (v is String && v.isBlank())
        }

        if (stillMissing.isNotEmpty()) {
            val question = "Mình vẫn cần thêm: ${stillMissing.joinToString(", ")}. Bạn cho mình biết nhé?"
            chatHistoryManager.setPendingIntent(
                pending.copy(missingParams = stillMissing, askedQuestion = question, createdAt = System.currentTimeMillis())
            )
            chatHistoryManager.addTurn(userMessage, question)
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.NeedMoreInfo(stillMissing, question)
            )
        }

        val mergedParams = pending.knownParams + filled
        chatHistoryManager.clearPendingIntent()

        val executionResult = try {
            targetPlugin.execute(pending.action, mergedParams)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute pending error: ${e.message}", e)
            PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
        }

        if (executionResult is PluginResult.NeedMoreInfo) {
            chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = targetPlugin.id,
                    action = pending.action,
                    knownParams = mergedParams,
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

        val qaContext = buildQAContext(userMessage)
        if (qaContext.isNotEmpty()) logger.d("AgentKernel", "Found QA context:\n$qaContext")

        val intent = resolveIntentWithLLM(userMessage, qaContext)
            ?: return PluginResult.Failure("Không hiểu yêu cầu")

        logger.d("AgentKernel", "Intent: plugin=${intent.pluginId}, action=${intent.action}, params=${intent.params}")

        val plugin = findPlugin(intent)
            ?: return PluginResult.Failure("Không tìm thấy plugin cho: ${intent.pluginId}")

        return try {
            plugin.execute(intent.action, intent.params)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute error: ${e.message}", e)
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QA helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun buildRawQAMatches(
        message: String,
        username: String = "default_user"
    ): List<QAEntity> {
        return try {
            val result = trainingSkill.fuzzyMatchQuestion(message, username, 0.7f)
            when (result) {
                is PluginResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    val matches = result.data as? List<Map<String, Any>> ?: return emptyList()
                    matches.mapNotNull { it["qa"] as? QAEntity }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            logger.e("AgentKernel", "buildRawQAMatches error: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun buildQAContext(
        message: String,
        username: String = "default_user"
    ): String {
        return try {
            val result = trainingSkill.fuzzyMatchQuestion(message, username, 0.5f)
            when (result) {
                is PluginResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    val matches = result.data as? List<Map<String, Any>> ?: return ""
                    if (matches.isEmpty()) return ""
                    matches.joinToString("\n") { match ->
                        val qa  = match["qa"] as? QAEntity ?: return@joinToString ""
                        val sim = match["similarity"] as? Float ?: 0f
                        "📚 ${qa.question} → ${qa.answer} (độ tương tự: ${String.format("%.2f", sim)})"
                    }
                }
                else -> ""
            }
        } catch (e: Exception) {
            logger.e("AgentKernel", "buildQAContext error: ${e.message}", e)
            ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Alias resolver — CHỈ cho ALIAS_PARAM_KEYS, dùng làm lưới an toàn
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveAliasParams(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>
    ): Map<String, Any> {
        if (qaMatches.isEmpty()) return params

        // Sắp xếp các câu hỏi QA có độ dài dài hơn lên trước để tránh việc match nhầm chuỗi con
        val sortedQA = qaMatches.sortedByDescending { it.question.length }

        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                return@mapValues resolveAliasParams(value as Map<String, Any>, qaMatches)
            }
            if (key !in ALIAS_PARAM_KEYS) return@mapValues value
            if (value !is String) return@mapValues value
            if (EMAIL_REGEX.matches(value)) return@mapValues value

            val matched = sortedQA.firstOrNull { qa ->
                qa.question.trim().equals(value.trim(), ignoreCase = true)
            }

            if (matched != null) {
                logger.d("AgentKernel", "🔄 Alias resolved: [$key] \"$value\" → \"${matched.answer}\"")
                matched.answer
            } else {
                value
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM routing
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveIntentWithLLM(message: String, qaContext: String = ""): Intent? {
        val availablePlugins = getAvailablePluginsDescription()

        val prompt = buildString {
            append("Bạn là bộ phân tích ý định cho AI quản gia.\n\n")
            append("DANH SÁCH PLUGIN:\n$availablePlugins\n\n")
            if (qaContext.isNotEmpty()) {
                append("📚 KIẾN THỨC ĐÃ HỌC (từ Q&A):\n$qaContext\n\n")
                append("⚠️ QUAN TRỌNG: Dùng kiến thức trên để map tên riêng VÀ điền giá trị param.\n")
                append("Ví dụ: 'vinh' = 'vinh@gmail.com' → param to = 'vinh@gmail.com'.\n")
                append("Ví dụ: 'lịch hôm nay' = 'mỗi 10 phút' → param interval/schedule = 'mỗi 10 phút'.\n\n")
            }
            append("⚠️ NẾU plugin=\"schedule\" và action=\"add\": params.params PHẢI chứa đầy đủ tham số ")
            append("bắt buộc của action đích (params.pluginId + params.action) theo đúng schema action đó ")
            append("trong DANH SÁCH PLUGIN ở trên. Áp dụng cho mọi plugin đích, không riêng email.\n\n")
            append("CÂU: \"$message\"\n\n")
            append("Trả về JSON thuần túy:\n")
            append("{\"plugin\": \"tên_plugin\", \"action\": \"tên_action\", \"params\": { ... }}")
        }

        return try {
            val response = groqClient.chat(
                message      = prompt,
                extraContext = "Bạn là AI phân tích intent, chỉ trả về JSON.",
                history      = emptyList(),
                imageUrl     = null
            )
            parseIntentResponse(response, message)
        } catch (e: Exception) {
            logger.e("AgentKernel", "LLM routing error: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

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

    private fun getAvailablePluginsDescription(): String {
        if (plugins.isEmpty()) return "Không có plugin nào được cài đặt."
        return plugins.joinToString("\n\n") { plugin ->
            buildString {
                append("📦 ${plugin.id} - ${plugin.name}\n")
                plugin.getActions().forEach { action ->
                    append("  • action=\"${action.name}\" — ${action.description}\n")
                    val required = action.parameters.filter { it.required }
                    val optional = action.parameters.filter { !it.required }
                    if (required.isNotEmpty())
                        append("    params bắt buộc: ${required.joinToString { "\"${it.name}\"(${it.type})" }}\n")
                    if (optional.isNotEmpty())
                        append("    params tuỳ chọn: ${optional.joinToString { "\"${it.name}\"(${it.type})" }}\n")
                }
            }
        }
    }

    private fun findPlugin(intent: Intent): Plugin? = plugins.find { it.id == intent.pluginId }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it, ignoreCase = true) }

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            if (value != org.json.JSONObject.NULL) { // Lọc bỏ giá trị NULL của JSON để bảo vệ dữ liệu sạch
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

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes & sealed results
    // ─────────────────────────────────────────────────────────────────────────

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