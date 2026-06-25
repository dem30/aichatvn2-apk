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
 * AgentKernel v12 — Kiến trúc 3 Tầng (đúng theo tài liệu):
 *  Tầng 1 (Pending Intent Pre-check) → Tầng 2 (Local: fuzzy-QA Intent JSON, 0 token,
 *  ngưỡng đọc động từ AppConfigProvider qua TrainingSkill) → Tầng 3 (LLM Router fallback,
 *  dùng QA alias thuần văn bản làm hint).
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
         * Các param bắt buộc phải có nội dung thật (không rỗng, không blank, không null)
         * trước khi Tier 2 được phép thực thi. Bao gồm cả các key ngoài ALIAS_PARAM_KEYS
         * như subject/body mà resolveParamsWithQA() không xử lý.
         *
         * Key → set các action áp dụng (null = áp dụng với mọi action).
         */
        private val REQUIRED_NONEMPTY_PARAMS: Map<String, Set<String>?> = mapOf(
            // device keys — bắt buộc với mọi action điều khiển thiết bị
            "device"      to null,
            "device_id"   to null,
            "deviceId"    to null,
            // camera keys
            "camera"      to null,
            "camera_id"   to null,
            "cameraId"    to null,
            // email keys
            "to"          to null,
            "email"       to null,
            "recipient"   to null,
            // email content — chỉ bắt buộc khi gửi email
            "subject"     to setOf("send", "send_email", "sendEmail"),
            "body"        to setOf("send", "send_email", "sendEmail"),
            // notification content — chỉ bắt buộc khi gửi thông báo
            "title"       to setOf("send", "send_notification", "sendNotification"),
            "message"     to setOf("send", "send_notification", "sendNotification")
        )
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.visibleInQuickBar }

    private var tier2Calls = 0
    private var tier2Hits  = 0
    private var tier3Calls = 0

    fun getRoutingMetrics(): Map<String, Any> = mapOf(
        "tier2_total"    to tier2Calls,
        "tier2_hit_rate" to if (tier2Calls > 0) tier2Hits * 100 / tier2Calls else 0,
        "tier3_total"    to tier3Calls,
        "tokens_saved_approx" to (tier2Hits * 2400)
    )

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val devicePlugins = plugins.filter { it.visibleInQuickBar }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 1 (TIẾP DIỄN): Pending intent còn dở dang
        // ─────────────────────────────────────────────────────────────────
        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins)
            if (resolved != null) return RouterOutcome.Matched(resolved)
        }

        val rawQAMatches = buildRawQAMatches(userMessage, username)

        // ─────────────────────────────────────────────────────────────────
        // TẦNG 2: Fuzzy match QA Intent JSON theo ngưỡng động (0 token)
        // ─────────────────────────────────────────────────────────────────
        tier2Calls++
        val tier2Result = tryTier2FuzzyQAIntent(userMessage, rawQAMatches, devicePlugins)
        if (tier2Result != null) {
            tier2Hits++
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
        // TẦNG 3: Phân tích LLM kết hợp gợi ý từ QA alias (Fuzzy matches)
        // ─────────────────────────────────────────────────────────────────
        tier3Calls++
        logger.d("AgentKernel", "🔵 Tier 3: Tầng 2 miss -> Gọi LLM Router")
        return executeTier3LlmRouting(userMessage, rawQAMatches, devicePlugins)
    }

    /**
     * Kiểm tra xem params sau khi resolveParamsWithQA() đã "sạch" chưa.
     * Trả về true (= có vấn đề → KHÔNG cho Tier 2 thực thi) nếu:
     *  1. Bất kỳ param nào trong ALIAS_PARAM_KEYS vẫn còn giá trị placeholder tĩnh.
     *  2. Bất kỳ param bắt buộc nào trong REQUIRED_NONEMPTY_PARAMS bị rỗng/blank
     *     (áp dụng đúng action nếu có giới hạn).
     *  3. Với action "add" của plugin "schedule", yêu cầu "pluginId" (target) không được trống.
     */
    private fun hasUnresolvedParams(params: Map<String, Any>, pluginId: String, action: String): Boolean {
        // Kiểm tra riêng cho lịch trình (schedule.add)
        if (pluginId == "schedule" && action == "add") {
            val targetPluginId = params["pluginId"]?.toString()
            if (targetPluginId.isNullOrBlank()) {
                logger.d("AgentKernel", "⚠️ Tier 2 block: schedule.add thiếu pluginId")
                return true
            }
        }

        for ((key, value) in params) {
            val strVal = value.toString()

            // 1. Placeholder tĩnh còn sót lại trong ALIAS_PARAM_KEYS
            if (key in ALIAS_PARAM_KEYS && strVal in PLACEHOLDER_VALUES) {
                logger.d("AgentKernel", "⚠️ Tier 2 block: param '$key' vẫn là placeholder '$strVal'")
                return true
            }

            // 2. Param bắt buộc rỗng/blank
            val applicableActions = REQUIRED_NONEMPTY_PARAMS[key]
            val isApplicable = applicableActions == null || action in applicableActions
            if (isApplicable && strVal.isBlank()) {
                logger.d("AgentKernel", "⚠️ Tier 2 block: param bắt buộc '$key' rỗng với action '$action'")
                return true
            }
        }
        return false
    }

    /**
     * TẦNG 2: Tìm QA Intent JSON khớp nhất bằng fuzzy match (ngưỡng động lấy qua
     * trainingSkill.fuzzyMatchQuestion()). Giữ đúng thứ tự similarity giảm dần do
     * fuzzyMatchQuestion() trả về (không sort lại theo độ dài câu hỏi), tránh chọn
     * nhầm QA dài hơn nhưng độ khớp thấp hơn.
     */
    private fun tryTier2FuzzyQAIntent(
        userMessage: String,
        rawQAMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): Pair<Plugin, Intent>? {
        for (qa in rawQAMatches) {
            if (!trainingSkill.isIntentQA(qa)) continue
            try {
                val json = JSONObject(qa.answer.trim())
                val pluginId = json.optString("plugin", "")
                val action = json.optString("action", "")
                if (pluginId.isNotEmpty() && action.isNotEmpty()) {
                    val plugin = devicePlugins.find { it.id == pluginId }
                    if (plugin != null && plugin.getActions().any { it.name == action }) {
                        val rawParams = json.optJSONObject("params")?.toMap() ?: emptyMap()
                        val resolvedParams = resolveParamsWithQA(rawParams, rawQAMatches, userMessage)

                        // ── GATE: kiểm tra params thật sự đã được resolve chưa ──
                        // Nếu còn placeholder hoặc param bắt buộc rỗng → fallthrough Tier 3
                        if (hasUnresolvedParams(resolvedParams, pluginId, action)) {
                            logger.d("AgentKernel",
                                "⏭️ Tier 2 skip (unresolved params): ${pluginId}.${action} | resolved=$resolvedParams")
                            continue
                        }

                        return Pair(plugin, Intent(pluginId, action, resolvedParams))
                    }
                }
            } catch (e: Exception) {
                logger.e("AgentKernel", "Tier 2 lỗi phân tích Intent QA (id=${qa.id}): ${e.message}")
            }
        }
        return null
    }

    private suspend fun executeTier3LlmRouting(
        userMessage: String,
        rawQAMatches: List<QAEntity>,
        devicePlugins: List<Plugin>
    ): RouterOutcome {
        // ── Lọc plugin theo QA match, không gửi toàn bộ ──
        // Intent QA nào match được → extract pluginId → chỉ load đúng plugin đó.
        // Nếu QA match rỗng (không tìm được gì) thì fallback toàn bộ để LLM không bị blind.
        val pluginIdsFromQA = rawQAMatches
            .filter { trainingSkill.isIntentQA(it) }
            .mapNotNull { qa ->
                try { JSONObject(qa.answer.trim()).optString("plugin", "").takeIf { it.isNotEmpty() } }
                catch (_: Exception) { null }
            }
            .toSet()

        val isScheduleIntent = userMessage.contains(
            Regex("lịch|schedule|cron|mỗi|định kỳ|đặt lịch|tạo lịch", RegexOption.IGNORE_CASE))

        val relevantPlugins = if (pluginIdsFromQA.isNotEmpty()) {
            val matched = devicePlugins.filter { it.id in pluginIdsFromQA }.toMutableList()
            if (isScheduleIntent) {
                devicePlugins.find { it.id == "schedule" }
                    ?.let { if (it !in matched) matched.add(0, it) }
            }
            logger.d("AgentKernel", "Tier 3 plugin filter via QA: ${matched.map { it.id }}")
            matched.ifEmpty { devicePlugins }
        } else {
            logger.d("AgentKernel", "Tier 3: QA miss -> send all plugins (${devicePlugins.size})")
            devicePlugins
        }

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

        // TẦNG 3: chỉ lấy QA alias thuần văn bản làm hint, bỏ qua QA Intent JSON
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
            append("<sys>Intent Router. JSON only: {\"plugin\":\"ID\",\"action\":\"Name\",\"params\":{}}\n")
            append("Rules:\n")
            append("- If general talk/not a command, output plugin:\"chat\", action:\"none\".\n")
            append("- Use <qa> to map aliases (e.g., names to emails, nicknames to IDs).\n")
            append("- Optional param keys end with '?'; omit '?' in final JSON.\n")
            append("- If plugin==\"schedule\" and action==\"add\": 'params.params' MUST be a nested object containing all required parameters of the target action.\n")
            append("- Output raw JSON. No explanation.</sys>\n")
            append("<plugins>\n$compactCatalog\n</plugins>\n")
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
            logger.e("AgentKernel", "Tier 3 routeIntent timeout sau 15s — mạng chậm/treo")
            return RouterOutcome.RouterFailed("Tier 3: timeout khi gọi router (mạng chậm/treo)")
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
        val device = intent.params["device"] ?: intent.params["device_id"] ?: intent.params["deviceId"]
        device?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val executionResult = try {
            plugin.execute(intent.action, intent.params)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute error: ${e.message}", e)
            PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
        }

        when (executionResult) {
            is PluginResult.NeedMoreInfo -> chatHistoryManager.setPendingIntent(
                PendingIntent(
                    pluginId = plugin.id,
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

        return executionResult
    }

    private fun looksLikeNewCommand(userMessage: String, devicePlugins: List<Plugin>): Boolean {
        val lower = userMessage.trim().lowercase()
        val commonKeywords = listOf(
            "đèn", "light", "relay", "switch", "thiết bị", "bật", "tắt", "mở", "đóng",
            "camera", "giám sát", "snapshot", "hình ảnh", "chụp", "ảnh",
            "mail", "email", "gửi", "thư",
            "lịch", "schedule", "cron", "mỗi", "định kỳ",
            "thông báo", "notification"
        )
        return commonKeywords.any { lower.contains(it) }
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

        // Build QA matches từ câu trả lời của user để resolve alias trong phần fill sau
        val pendingQAMatches = buildRawQAMatches(userMessage)

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

        val heuristicFilled = mutableMapOf<String, Any>()
        for (param in pending.missingParams) {
            val trimmed = userMessage.trim()
            if ((param == "to" || param == "email" || param == "recipient") && EMAIL_REGEX.matches(trimmed)) {
                heuristicFilled[param] = trimmed
            }
            if (param == "device" || param == "device_id" || param == "deviceId") {
                val relayRegex = Regex("relay[\\s_]?(\\d+)", RegexOption.IGNORE_CASE)
                val match = relayRegex.find(trimmed)
                if (match != null) {
                    heuristicFilled[param] = "relay${match.groupValues[1]}"
                }
            }
        }

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
                return DeviceCommandResult(
                    pluginId = pending.pluginId,
                    result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
                )
            }

            if (parsedJson.optBoolean("unrelated", false)) {
                if (heuristicFilled.isNotEmpty()) {
                    JSONObject().put("params", JSONObject(heuristicFilled))
                } else {
                    if (looksLikeNewCommand(userMessage, devicePlugins)) {
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
                parsedJson.put("params", paramsObj)
                parsedJson
            }
        }

        val filled = finalParsed?.optJSONObject("params")?.toMap() ?: emptyMap()
        val stillMissing = pending.missingParams.filter { key ->
            val v = filled[key]
            v == null || v.toString().equals("null", ignoreCase = true) || (v is String && v.isBlank())
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

        // ── Resolve alias cho params mà LLM vừa fill từ câu trả lời của user ──
        // Ví dụ: user nói "camera của vĩnh" → LLM fill cameraId="vĩnh" → resolve → "camera_01"
        // buildRawQAMatches() cần username; dùng "default_user" vì pending không lưu username.
        val filledResolved = resolveParamsWithQA(filled, pendingQAMatches, userMessage)
        if (filled != filledResolved) {
            logger.d("AgentKernel", "🔄 Pending alias resolve: $filled → $filledResolved")
        }

        val mergedParams = pending.knownParams + filledResolved
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
        val outcome = tryDeviceCommand(userMessage)
        return when (outcome) {
            is RouterOutcome.Matched -> outcome.result.result
            is RouterOutcome.RouterFailed -> PluginResult.Failure(outcome.reason)
            is RouterOutcome.NotACommand -> PluginResult.Failure("Không phải lệnh thiết bị — dùng ChatSkill.processQuery() cho chat thường")
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
            // Sử dụng trực tiếp logic fuzzy match để tự động áp dụng ngưỡng động trong TrainingSkill
            val result = trainingSkill.fuzzyMatchQuestion(message, username)
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

    private fun resolveParamsWithQA(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>,
        userMessage: String? = null
    ): Map<String, Any> {
        if (qaMatches.isEmpty()) return params

        // Chỉ dùng QA alias thuần văn bản để resolve, bỏ qua QA Intent JSON tránh gán đè dữ liệu
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
            if (EMAIL_REGEX.matches(value)) return@mapValues value

            // 1. So khớp giá trị trực tiếp của tham số (Alias matching)
            val directMatch = sortedQA.firstOrNull { qa ->
                val boundaryRegex = Regex("\\b${Regex.escape(qa.question)}\\b", RegexOption.IGNORE_CASE)
                boundaryRegex.containsMatchIn(value) || qa.question.trim().equals(value.trim(), ignoreCase = true)
            }
            if (directMatch != null) return@mapValues directMatch.answer

            // 2. So khớp alias trong cả câu nói nếu có truyền userMessage (ví dụ: "bật đèn phòng khách")
            if (userMessage != null) {
                val messageMatch = sortedQA.firstOrNull { qa ->
                    val escapedQuestion = Regex.escape(qa.question)
                    val regex = Regex("(?i)\\b$escapedQuestion\\b")
                    regex.containsMatchIn(userMessage) || userMessage.contains(qa.question, ignoreCase = true)
                }
                if (messageMatch != null) {
                    return@mapValues messageMatch.answer
                }
            }
            value
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