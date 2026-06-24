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
 * AgentKernel v10 (Fully Optimized with High-Density Tier 0 & Compact Tier 1 Prompts)
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
                Regex("(bật|tắt|mở|đóng|sáng|tối|trạng thái|kiểm tra|xem|quét|scan).*?(đèn|light|relay|thiết bị|switch)", RegexOption.IGNORE_CASE)
            ),
            "camera" to listOf(
                Regex("(camera|giám sát|snapshot|chụp|hình ảnh|ảnh|quét|scan)", RegexOption.IGNORE_CASE)
            ),
            "email" to listOf(
                Regex("(gửi|send).*?(mail|email|thư)", RegexOption.IGNORE_CASE)
            ),
            "schedule" to listOf(
                Regex("(lịch|schedule|cron)", RegexOption.IGNORE_CASE)
            ),
            "notification" to listOf(
                Regex("(thông báo|notification)", RegexOption.IGNORE_CASE)
            )
        )

        // ─────────────────────────────────────────────────────────────────
        // TIER 1: Plugin keyword scoring (smart filtering)
        // ─────────────────────────────────────────────────────────────────
        private val PLUGIN_KEYWORDS = mapOf(
            "light" to listOf(
                "đèn", "light", "relay", "switch", "thiết bị", "sáng", "tối"
            ),
            "camera" to listOf(
                "camera", "giám sát", "snapshot", "hình ảnh", "chụp", "ảnh"
            ),
            "email" to listOf(
                "mail", "email", "gửi", "thư", "tin nhắn"
            ),
            "schedule" to listOf(
                "lịch", "schedule", "cron", "mỗi", "định kỳ", "phút", "giờ", "ngày", "tuần"
            ),
            "notification" to listOf(
                "thông báo", "notification", "báo động"
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
            
            val device = t0Intent.params["device"] ?: t0Intent.params["device_id"] ?: t0Intent.params["deviceId"]
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
        val lowerMsg = userMessage.trim().lowercase()

        // 1. LIGHT SKILL (ID: "light")
        if (lowerMsg.containsAny("đèn", "light", "relay", "thiết bị", "switch")) {
            val plugin = devicePlugins.find { it.id == "light" }
            if (plugin != null) {
                if (lowerMsg.containsAny("quét", "scan", "tìm")) {
                    return Pair(plugin, Intent("light", "scan"))
                }
                if (lowerMsg.containsAny("trạng thái", "status", "kiểm tra", "xem")) {
                    val deviceName = extractDeviceName(userMessage, listOf("trạng thái", "status", "kiểm tra", "xem", "đèn", "relay", "thiết bị", "switch"))
                    if (deviceName.isNotEmpty()) {
                        val params = finalizeTierZeroParams("light", "status", mapOf("device" to deviceName), qaMatches)
                        return Pair(plugin, Intent("light", "status", params))
                    }
                }
                val isTurnOn = lowerMsg.containsAny("bật", "mở", "on", "kích hoạt", "sáng")
                val isTurnOff = lowerMsg.containsAny("tắt", "đóng", "off", "vô hiệu", "tối")
                if (isTurnOn || isTurnOff) {
                    val state = isTurnOn
                    val deviceName = extractDeviceName(userMessage, listOf("bật", "tắt", "mở", "đóng", "on", "off", "kích hoạt", "vô hiệu", "sáng", "tối", "đèn", "relay", "thiết bị", "switch"))
                    if (deviceName.isNotEmpty()) {
                        val params = finalizeTierZeroParams("light", "set", mapOf("device" to deviceName, "state" to state), qaMatches)
                        return Pair(plugin, Intent("light", "set", params))
                    }
                }
            }
        }

        // 2. CAMERA SKILL (ID: "camera")
        if (lowerMsg.containsAny("camera", "giám sát", "snapshot", "chụp", "hình ảnh", "ảnh")) {
            val plugin = devicePlugins.find { it.id == "camera" }
            if (plugin != null) {
                if (lowerMsg.containsAny("danh sách", "liệt kê", "tất cả")) {
                    return Pair(plugin, Intent("camera", "list_cameras"))
                }
                if (lowerMsg.containsAny("ai", "smart mode", "thông minh")) {
                    val enabled = lowerMsg.containsAny("bật", "mở", "on", "kích hoạt")
                    val camName = extractDeviceName(userMessage, listOf("bật", "tắt", "mở", "đóng", "ai", "smart mode", "thông minh", "chế độ", "camera"))
                    val rawParams = mutableMapOf<String, Any>("enabled" to enabled)
                    if (camName.isNotEmpty()) rawParams["cameraId"] = camName
                    val params = finalizeTierZeroParams("camera", "set_smart_mode", rawParams, qaMatches)
                    return Pair(plugin, Intent("camera", "set_smart_mode", params))
                }
                if (lowerMsg.containsAny("theo dõi", "giám sát")) {
                    val active = lowerMsg.containsAny("bật", "mở", "on", "kích hoạt")
                    val camName = extractDeviceName(userMessage, listOf("bật", "tắt", "mở", "đóng", "theo dõi", "giám sát", "camera"))
                    if (camName.isNotEmpty()) {
                        val params = finalizeTierZeroParams("camera", "set_active", mapOf("cameraId" to camName, "active" to active), qaMatches)
                        return Pair(plugin, Intent("camera", "set_active", params))
                    }
                }
                if (lowerMsg.containsAny("quét", "scan", "chụp", "hình", "ảnh", "snapshot")) {
                    val camName = extractDeviceName(userMessage, listOf("quét", "scan", "chụp", "hình", "ảnh", "snapshot", "camera"))
                    val rawParams = mutableMapOf<String, Any>()
                    if (camName.isNotEmpty()) rawParams["cameraId"] = camName
                    val params = finalizeTierZeroParams("camera", "scan", rawParams, qaMatches)
                    return Pair(plugin, Intent("camera", "scan", params))
                }
                if (lowerMsg.containsAny("trạng thái", "status", "kiểm tra", "xem")) {
                    val camName = extractDeviceName(userMessage, listOf("trạng thái", "status", "kiểm tra", "xem", "camera"))
                    if (camName.isNotEmpty()) {
                        val params = finalizeTierZeroParams("camera", "status", mapOf("cameraId" to camName), qaMatches)
                        return Pair(plugin, Intent("camera", "status", params))
                    }
                }
            }
        }

        // 3. EMAIL SKILL (ID: "email")
        if (lowerMsg.containsAny("mail", "email", "thư")) {
            val plugin = devicePlugins.find { it.id == "email" }
            if (plugin != null) {
                if (lowerMsg.contains("test")) {
                    val emailMatch = EMAIL_REGEX.find(userMessage)
                    if (emailMatch != null) {
                        return Pair(plugin, Intent("email", "test", mapOf("to" to emailMatch.value)))
                    }
                }
                
                var toVal: String? = EMAIL_REGEX.find(userMessage)?.value
                if (toVal == null) {
                    val recipientMatch = Regex("(?i)(?:cho|to|gửi)\\s+([a-zA-Z0-9àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]+(?:\\s+[a-zA-Z0-9àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]+){0,2})").find(userMessage)
                    if (recipientMatch != null) {
                        val candidate = recipientMatch.groupValues[1].trim()
                        val cleanCandidate = candidate.split(Regex("(?i)\\s+(?:nội dung|tiêu đề|chủ đề|body|subject|báo)\\b"))[0].trim()
                        if (cleanCandidate.isNotEmpty()) {
                            toVal = cleanCandidate
                        }
                    }
                }
                
                if (toVal != null) {
                    val subjectMatch = Regex("(?i)(?:tiêu đề|chủ đề|subject)[:\\s]+(.*?)(?=\\s+(?:nội dung|body|báo)|$)").find(userMessage)
                    val bodyMatch = Regex("(?i)(?:nội dung|body|báo)[:\\s]+(.*)").find(userMessage)
                    
                    val subject = subjectMatch?.groupValues[1]?.trim() ?: "Thông báo hệ thống"
                    val body = bodyMatch?.groupValues[1]?.trim() ?: userMessage
                    
                    val params = finalizeTierZeroParams("email", "send", mapOf(
                        "to" to toVal,
                        "subject" to subject,
                        "body" to body
                    ), qaMatches)
                    
                    return Pair(plugin, Intent("email", "send", params))
                }
            }
        }

        // 4. SCHEDULE SKILL (ID: "schedule")
        if (lowerMsg.containsAny("lịch", "schedule", "cron")) {
            val plugin = devicePlugins.find { it.id == "schedule" }
            if (plugin != null) {
                if (lowerMsg.containsAny("danh sách", "xem", "liệt kê", "list")) {
                    return Pair(plugin, Intent("schedule", "list"))
                }
                if (lowerMsg.containsAny("xóa", "xoá", "delete")) {
                    val idMatch = Regex("([a-f0-9\\-]{36}|\\d+)").find(userMessage)
                    if (idMatch != null) {
                        return Pair(plugin, Intent("schedule", "delete", mapOf("id" to idMatch.value)))
                    }
                }
                val isEnableToggle = lowerMsg.containsAny("bật", "kích hoạt", "enable")
                val isDisableToggle = lowerMsg.containsAny("tắt", "vô hiệu", "disable")
                if (isEnableToggle || isDisableToggle) {
                    val idMatch = Regex("([a-f0-9\\-]{36}|\\d+)").find(userMessage)
                    if (idMatch != null) {
                        return Pair(plugin, Intent("schedule", "toggle", mapOf(
                            "id" to idMatch.value,
                            "enabled" to isEnableToggle
                        )))
                    }
                }
                
                // Add schedule
                if (lowerMsg.containsAny("đặt lịch", "tạo lịch", "thêm lịch", "add schedule")) {
                    var intervalMinutes = 0
                    var cron = ""
                    val intervalMatch = Regex("(?i)(?:mỗi|định kỳ|sau)\\s+(\\d+)\\s+(?:phút|m|min)").find(userMessage)
                    if (intervalMatch != null) {
                        intervalMinutes = intervalMatch.groupValues[1].toInt()
                    } else if (lowerMsg.containsAny("mỗi giờ", "định kỳ mỗi giờ")) {
                        intervalMinutes = 60
                    }
                    val cronMatch = Regex("(?i)cron[:\\s]+([^\\n]+)").find(userMessage)
                    if (cronMatch != null) {
                        cron = cronMatch.groupValues[1].trim()
                    }
                    
                    if (intervalMinutes > 0 || cron.isNotEmpty()) {
                        var targetPluginId = ""
                        var targetAction = ""
                        val targetParams = mutableMapOf<String, Any>()
                        
                        if (lowerMsg.contains("camera")) {
                            targetPluginId = "camera"
                            targetAction = "scan"
                            val camName = extractDeviceName(userMessage, listOf("đặt lịch", "tạo lịch", "thêm lịch", "add schedule", "mỗi", "phút", "định kỳ", "camera", "quét", "scan"))
                            if (camName.isNotEmpty()) targetParams["cameraId"] = camName
                        } else if (lowerMsg.containsAny("đèn", "light", "relay", "thiết bị")) {
                            targetPluginId = "light"
                            val isOn = lowerMsg.containsAny("bật", "mở", "on")
                            val isOff = lowerMsg.containsAny("tắt", "đóng", "off")
                            if (isOn || isOff) {
                                targetAction = "set"
                                targetParams["state"] = isOn
                                val devName = extractDeviceName(userMessage, listOf("đặt lịch", "tạo lịch", "thêm lịch", "add schedule", "mỗi", "phút", "định kỳ", "đèn", "light", "relay", "thiết bị", "bật", "tắt", "mở", "đóng", "on", "off"))
                                if (devName.isNotEmpty()) targetParams["device"] = devName
                            }
                        } else if (lowerMsg.containsAny("mail", "email")) {
                            targetPluginId = "email"
                            targetAction = "send"
                            val emailMatch = EMAIL_REGEX.find(userMessage)
                            if (emailMatch != null) targetParams["to"] = emailMatch.value
                        }
                        
                        if (targetPluginId.isNotEmpty() && targetAction.isNotEmpty()) {
                            val resolvedTargetParams = resolveParamsWithQA(targetParams, qaMatches)
                            val paramsMap = mutableMapOf<String, Any>(
                                "pluginId" to targetPluginId,
                                "action" to targetAction,
                                "params" to resolvedTargetParams
                            )
                            if (cron.isNotEmpty()) paramsMap["cron"] = cron
                            if (intervalMinutes > 0) paramsMap["intervalMinutes"] = intervalMinutes
                            return Pair(plugin, Intent("schedule", "add", paramsMap))
                        }
                    }
                }
            }
        }

        // 5. NOTIFICATION SKILL (ID: "notification")
        if (lowerMsg.containsAny("thông báo", "notification")) {
            val plugin = devicePlugins.find { it.id == "notification" }
            if (plugin != null) {
                val titleMatch = Regex("(?i)(?:tiêu đề|chủ đề|title)[:\\s]+(.*?)(?=\\s+(?:nội dung|tin nhắn|message)|$)").find(userMessage)
                val msgMatch = Regex("(?i)(?:nội dung|tin nhắn|message)[:\\s]+(.*)").find(userMessage)
                
                val title = titleMatch?.groupValues[1]?.trim() ?: "Thông báo hệ thống"
                var msg = msgMatch?.groupValues[1]?.trim()
                
                if (msg == null) {
                    msg = extractDeviceName(userMessage, listOf("thông báo", "gửi thông báo", "push notification", "gửi", "tạo", "tin nhắn"))
                }
                if (msg.isNotEmpty()) {
                    return Pair(plugin, Intent("notification", "send", mapOf(
                        "title" to title,
                        "message" to msg
                    )))
                }
            }
        }

        return null
    }

    private fun extractDeviceName(text: String, stopWords: List<String>): String {
        var cleaned = text
        val politenessPhrases = listOf("vui lòng", "giúp mình", "hộ mình", "giúp", "hộ", "nhé", "nha", "đi", "với", "cho mình")
        politenessPhrases.forEach { phrase ->
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(phrase)}\\b"), "")
        }
        cleaned = cleaned.replace(Regex("[,.!?_\\-]"), " ")
        stopWords.forEach { word ->
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(word)}\\b"), "")
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun finalizeTierZeroParams(
        pluginId: String,
        actionName: String,
        rawParams: Map<String, Any>,
        qaMatches: List<QAEntity>
    ): Map<String, Any> {
        var resolved = resolveParamsWithQA(rawParams, qaMatches)
        val lastDevice = chatHistoryManager.lastMentionedDeviceId
        if (lastDevice != null) {
            val deviceKeys = setOf("device", "device_id", "deviceId")
            val hasDeviceKeyInSchema = (pluginId == "light" && actionName in setOf("set", "status"))
            if (hasDeviceKeyInSchema) {
                val foundKey = deviceKeys.firstOrNull { it in rawParams }
                if (foundKey != null && (resolved[foundKey] as? String).isNullOrBlank()) {
                    val mutable = resolved.toMutableMap()
                    mutable[foundKey] = lastDevice
                    resolved = mutable
                } else if (foundKey == null) {
                    val mutable = resolved.toMutableMap()
                    mutable["device"] = lastDevice
                    resolved = mutable
                }
            }
        }
        return resolved
    }

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
            val keywordHits = PLUGIN_KEYWORDS[plugin.id]
                ?.count { keyword ->
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(userMessage)
                } ?: 0
            score += keywordHits * 2.0

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
            allPlugins.filter { it.id in listOf("light", "schedule", "camera", "email") }.take(5)
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

        if (targetPlugin.getActions().none { it.name == rawIntent.action }) {
            return RouterOutcome.RouterFailed("Tier 1: action không hợp lệ: ${rawIntent.action} trong plugin ${targetPlugin.id}")
        }

        val intent = rawIntent.copy(params = resolveAliasParams(rawIntent.params, rawQAMatches))
        if (rawIntent.params != intent.params) {
            logger.d("AgentKernel", "🔄 Tier 1 alias fallback: ${rawIntent.params} → ${intent.params}")
        }

        logger.d("AgentKernel", "🔥 Tier 1 matched: ${intent.pluginId}.${intent.action} | params=${intent.params}")

        val device = intent.params["device"] ?: intent.params["device_id"] ?: intent.params["deviceId"]
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

    private fun looksLikeNewCommand(userMessage: String, devicePlugins: List<Plugin>): Boolean {
        val hasTier0Match = TIER_ZERO_PATTERNS.values.any { patterns ->
            patterns.any { it.containsMatchIn(userMessage) }
        }
        if (hasTier0Match) return true

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

    private fun resolveParamsWithQA(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>
    ): Map<String, Any> {
        if (qaMatches.isEmpty()) return params
        val sortedQA = qaMatches.sortedByDescending { it.question.length }
        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                return@mapValues resolveParamsWithQA(value as Map<String, Any>, qaMatches)
            }
            if (key !in ALIAS_PARAM_KEYS) return@mapValues value
            if (value !is String) return@mapValues value
            if (EMAIL_REGEX.matches(value)) return@mapValues value

            val matched = sortedQA.firstOrNull { qa ->
                val boundaryRegex = Regex("\\b${Regex.escape(qa.question)}\\b", RegexOption.IGNORE_CASE)
                boundaryRegex.containsMatchIn(value) || qa.question.trim().equals(value.trim(), ignoreCase = true)
            }
            matched?.answer ?: value
        }
    }

    private fun resolveAliasParams(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>
    ): Map<String, Any> {
        return resolveParamsWithQA(params, qaMatches)
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