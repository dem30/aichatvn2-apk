package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/**
 * AgentKernel v9
 *
 * Điểm thay đổi so với v8:
 *
 * 1. tryDeviceCommand() trả về RouterOutcome (Matched / NotACommand / RouterFailed)
 *    thay vì DeviceCommandResult? — phân biệt rõ "đây là chat thường" (NotACommand)
 *    với "router CỐ định tuyến nhưng thất bại" (RouterFailed). ChatSkill dùng
 *    RouterFailed để chèn 1 cảnh báo ngắn cho model lớn, tránh nó bịa ra là đã
 *    thực hiện hành động khi thực ra lệnh chưa chạy được.
 *
 * 2. Pending intent rõ ràng: khi plugin trả NeedMoreInfo, lưu lại pluginId/action/
 *    params đã biết/param còn thiếu vào ChatHistoryManager. Lượt chat kế tiếp ưu
 *    tiên thử điền param còn thiếu bằng 1 prompt SIÊU GỌN (không gửi lại toàn bộ
 *    catalog plugin/qa_facts/schedules) thay vì để router đoán lại từ đầu — vừa
 *    chính xác hơn vừa tiết kiệm token.
 *
 * Điểm thay đổi so với v7:
 *
 * 1. QA được đưa vào router prompt dưới dạng KEY=VALUE CỤ THỂ thay vì text thô.
 *    Router nhỏ không cần "hiểu" — chỉ cần copy đúng giá trị vào đúng param.
 *
 * 2. resolveParamsWithQA() chỉ apply với ALIAS_PARAM_KEYS (email, device, camera...) —
 *    tức là chỉ replace các param mà ngữ nghĩa rõ ràng là "định danh". Không replace
 *    blindly cho mọi param string, tránh case "lịch hôm nay → mỗi 10 phút" đè lên
 *    param sai key (vd `to`, `subject`).
 *
 * 3. QA loại "metadata/config" (Q: lịch hôm nay, A: mỗi 10 phút) được router nhỏ
 *    dùng TRỰC TIẾP từ <qa_facts> trong prompt — model nhận "lịch hôm nay = mỗi 10 phút"
 *    và tự điền vào param đúng (interval, schedule, cron...) theo plugin schema.
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
         * Chỉ gồm "định danh" (email, device ID, camera ID...) — KHÔNG gồm
         * "metadata" như interval, schedule, subject, body vì những thứ đó
         * router phải tự điền đúng key từ QA facts trong prompt.
         */
        private val ALIAS_PARAM_KEYS = setOf(
            "to", "email", "recipient",          // email
            "device", "device_id", "deviceId",   // thiết bị Tuya
            "camera", "camera_id", "cameraId"    // camera
        )
    }

    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.visibleInQuickBar }

    // ─────────────────────────────────────────────────────────────────────────
    // tryDeviceCommand — định tuyến NHANH bằng router nhỏ
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val devicePlugins = plugins.filter { it.visibleInQuickBar }
        if (devicePlugins.isEmpty()) return RouterOutcome.NotACommand

        // ✅ Nếu đang có 1 lệnh chờ bổ sung param (NeedMoreInfo từ lượt trước),
        // ưu tiên thử điền param còn thiếu bằng prompt SIÊU GỌN trước — KHÔNG
        // gọi lại router đầy đủ (catalog + qa_facts + schedules) để tiết kiệm
        // token và tránh đoán sai lại từ đầu.
        val pending = chatHistoryManager.getActivePendingIntent()
        if (pending != null) {
            val resolved = tryResolvePendingIntent(pending, userMessage, devicePlugins)
            if (resolved != null) return RouterOutcome.Matched(resolved)
            // resolved == null: pending không còn hợp lệ (plugin/action biến mất)
            // hoặc user rõ ràng hỏi việc khác → rơi tiếp xuống flow router bình thường.
        }

        val rawQAMatches = buildRawQAMatches(userMessage, username)
        val shortHistory  = chatHistoryManager.getRecentTurnsAsText()
        val lastDevice    = chatHistoryManager.lastMentionedDeviceId ?: "none"

        val compactCatalog = devicePlugins.joinToString("\n\n") { plugin ->
            val actionLines = plugin.getActions().joinToString("\n") { action ->
                val paramsSig = action.parameters.joinToString(",") { p ->
                    if (p.required) p.name else "${p.name}?"
                }
                val sig = if (paramsSig.isEmpty()) action.name
                          else "${action.name}($paramsSig)"
                "  - $sig — ${action.description}"
            }
            "plugin \"${plugin.id}\":\n$actionLines"
        }

        // ✅ QA được format thành KEY=VALUE rõ ràng thay vì text thô.
        // Router nhỏ không cần suy luận — chỉ cần nhìn vào <qa_facts> và
        // copy đúng value vào đúng param theo plugin schema.
        //
        // Ví dụ với 2 QA khác loại:
        //   Q:"vinh"        A:"vinh@gmail.com"  → "vinh = vinh@gmail.com"
        //   Q:"lịch hôm nay" A:"mỗi 10 phút"   → "lịch hôm nay = mỗi 10 phút"
        //
        // Router thấy user nói "gửi mail cho vinh lịch hôm nay":
        //   → to = vinh@gmail.com   (từ qa_facts "vinh")
        //   → interval = mỗi 10 phút (từ qa_facts "lịch hôm nay", điền vào param schedule)
        val qaFacts = if (rawQAMatches.isEmpty()) ""
        else rawQAMatches.joinToString("\n") { qa ->
            "${qa.question} = ${qa.answer}"
        }

        val routerPrompt = buildString {
            append("<system>You are an Intent Router. Output ONLY raw JSON: ")
            append("{\"plugin\": string, \"action\": string, \"params\": object}.\n")
            append("Rules:\n")
            append("- Use <qa_facts> to resolve aliases and fill param values.\n")
            append("  Example: if qa_facts says \"vinh = vinh@gmail.com\" and user says \"send to vinh\", ")
            append("set params.to = \"vinh@gmail.com\".\n")
            append("  Example: if qa_facts says \"lịch hôm nay = mỗi 10 phút\" and user says ")
            append("\"get schedule\", set the relevant schedule/interval param = \"mỗi 10 phút\".\n")
            append("- param keys with '?' are optional; omit the '?' from the key name.\n")
            append("- If general conversation (not a command), output ")
            append("{\"plugin\":\"chat\",\"action\":\"none\",\"params\":{}}.\n")
            append("- If plugin == \"schedule\" and action == \"add\": params.params PHẢI chứa ĐẦY ĐỦ ")
            append("tham số bắt buộc của action đích (params.pluginId + params.action), tra đúng tên ")
            append("param theo schema action đó trong <plugins> ở trên. Quy tắc này áp dụng cho MỌI ")
            append("plugin đích (email, camera, light...), không chỉ riêng một loại.\n")
            append("- Do NOT add explanation. Output JSON only.</system>\n")
            append("<plugins>\n$compactCatalog\n</plugins>\n")
            if (qaFacts.isNotEmpty()) {
                append("<qa_facts>\n$qaFacts\n</qa_facts>\n")
            }
            append("<context>last_device: \"$lastDevice\"</context>\n")
            append("<history>\n$shortHistory\n</history>\n")
            append("<input>User: $userMessage</input>\n")
            append("<output>")
        }

        // ✅ Đưa danh sách schedule hiện tại (với index + id thật) vào prompt để LLM
        // biết id UUID cụ thể khi user nói "xoá lịch số 1" hay "xoá lịch camera".
        val schedulesContext = try {
            val schedPlugin = devicePlugins.find { it.id == "schedule" }
            val result = schedPlugin?.execute("list", emptyMap())
            @Suppress("UNCHECKED_CAST")
            val list = (result as? PluginResult.Success)?.data as? List<*>
            if (!list.isNullOrEmpty()) {
                val lines = list.mapIndexed { i, s ->
                    val e = s as? com.aichatvn.agent.data.model.ScheduleEntity
                        ?: return@mapIndexed ""
                    "#${i + 1} id=${e.id} ${e.pluginId}.${e.action} " +
                        if (e.cron.isNotEmpty()) e.cron else "${e.intervalMinutes}m"
                }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty())
                    "<schedules>\n${lines.joinToString("\n")}\n</schedules>\n"
                else ""
            } else ""
        } catch (_: Exception) { "" }

        val fullPrompt = if (schedulesContext.isNotEmpty())
            routerPrompt.replace("<input>", "$schedulesContext<input>")
        else routerPrompt

        val routerResultJson = groqClient.routeIntent(fullPrompt)
        val rawIntent = parseIntentResponse(routerResultJson, userMessage)
            ?: return RouterOutcome.RouterFailed("Không parse được JSON từ router: $routerResultJson")

        if (rawIntent.pluginId.isBlank()) {
            return RouterOutcome.RouterFailed("Router trả về plugin rỗng")
        }
        if (rawIntent.pluginId == "chat") return RouterOutcome.NotACommand

        val targetPlugin = devicePlugins.find { it.id == rawIntent.pluginId }
            ?: return RouterOutcome.RouterFailed("Router trả về plugin không tồn tại: ${rawIntent.pluginId}")

        // ✅ resolveParamsWithQA chỉ chạy như "lưới an toàn" cho ALIAS_PARAM_KEYS.
        // Nếu router nhỏ đã điền đúng (vd to="vinh@gmail.com") → hàm này bỏ qua.
        // Nếu router nhỏ vẫn để alias (to="vinh") → hàm này resolve lại.
        // Param loại metadata (interval, schedule...) KHÔNG bị đụng tới ở đây —
        // router đã điền đúng từ <qa_facts> rồi.
        val intent = rawIntent.copy(
            params = resolveAliasParams(rawIntent.params, rawQAMatches)
        )

        if (rawIntent.params != intent.params) {
            logger.d("AgentKernel", "🔄 Alias fallback resolved: ${rawIntent.params} → ${intent.params}")
        }

        logger.d("AgentKernel", "🔥 Khớp lệnh: ${intent.pluginId} -> ${intent.action} | params=${intent.params}")

        intent.params["device"]?.toString()?.let { chatHistoryManager.updateLastDevice(it) }

        val executionResult = try {
            targetPlugin.execute(intent.action, intent.params)
        } catch (e: Exception) {
            logger.e("AgentKernel", "Execute error: ${e.message}", e)
            PluginResult.Failure("Lỗi khi thực hiện lệnh: ${e.message}")
        }

        // ✅ Lưu/xoá pending intent: nếu thiếu param thì lưu lại để lượt sau resume
        // bằng prompt gọn; nếu đã xong (Success/Failure) thì xoá để không bị kẹt.
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
     * Thử điền các param còn thiếu của 1 pending intent bằng prompt SIÊU GỌN
     * (chỉ gồm: action cần param gì, câu hỏi đã hỏi, câu trả lời của user) —
     * KHÔNG gửi lại catalog plugin / qa_facts / schedules như router đầy đủ.
     *
     * Trả về null nếu: plugin/action không còn tồn tại, hoặc model xác định
     * câu trả lời của user KHÔNG liên quan đến câu hỏi đang chờ (user hỏi việc
     * khác) — cả 2 trường hợp đều nên rơi xuống flow router bình thường.
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

        // Cancel rẻ — không cần LLM, chặn các câu kiểu "thôi", "khỏi cần", "huỷ đi"
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

        val fillPrompt = buildString {
            append("<system>Output ONLY raw JSON, KHÔNG giải thích.\n")
            append("User đang trả lời 1 câu hỏi bổ sung thông tin cho lệnh còn thiếu param.\n")
            append("Nếu trả lời của user CUNG CẤP được giá trị cho (các) param còn thiếu, output:\n")
            append("{\"params\": {${pending.missingParams.joinToString(",") { "\"$it\": \"giá_trị\"" }}}}\n")
            append("Nếu trả lời của user là 1 yêu cầu/câu hỏi KHÁC, KHÔNG liên quan câu hỏi đang chờ, output:\n")
            append("{\"unrelated\": true}</system>\n")
            append("<param_can_dien>${pending.missingParams.joinToString(", ")}</param_can_dien>\n")
            append("<cau_hoi_da_hoi>${pending.askedQuestion}</cau_hoi_da_hoi>\n")
            append("<tra_loi_cua_user>$userMessage</tra_loi_cua_user>\n")
            append("<output>")
        }

        val rawJson = groqClient.routeIntent(fillPrompt)
        val parsed = try {
            val cleaned = rawJson.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(cleaned)
        } catch (e: Exception) {
            logger.e("AgentKernel", "tryResolvePendingIntent parse error: $rawJson")
            null
        }

        if (parsed == null) {
            // Không parse được — coi như chưa điền thêm gì, hỏi lại câu cũ (không tốn LLM thêm)
            return DeviceCommandResult(
                pluginId = pending.pluginId,
                result = PluginResult.NeedMoreInfo(pending.missingParams, pending.askedQuestion)
            )
        }

        if (parsed.optBoolean("unrelated", false)) {
            chatHistoryManager.clearPendingIntent()
            return null
        }

        val filled = parsed.optJSONObject("params")?.toMap() ?: emptyMap()
        val stillMissing = pending.missingParams.filter { key ->
            val v = filled[key]
            v == null || v.toString().equals("null", ignoreCase = true) ||
                (v is String && v.isBlank())
        }

        if (stillMissing.isNotEmpty()) {
            // Vẫn chưa đủ — giữ pending intent (refresh thời điểm tạo), hỏi lại đúng phần còn thiếu
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
            // Plugin lại báo thiếu (param khác) — lưu pending mới luôn
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

    // ─────────────────────────────────────────────────────────────────────────
    // process — định tuyến ĐẦY ĐỦ bằng model chat lớn (entry-point cũ)
    // ─────────────────────────────────────────────────────────────────────────

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

    /**
     * Trả về List<QAEntity> thô — dùng cho resolveAliasParams() và build qaFacts string.
     */
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

    /**
     * Trả về String đã format (cho resolveIntentWithLLM / process()).
     */
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

    /**
     * Chỉ replace param value nếu:
     *   1. key nằm trong ALIAS_PARAM_KEYS (to, device, camera...)
     *   2. value là String chưa hợp lệ (chưa phải email thật / device ID thật)
     *   3. Tìm thấy QA match theo question ⊇ value hoặc value ⊇ question
     *
     * KHÔNG đụng đến các param khác (interval, schedule, subject, body...) —
     * những thứ đó router đã lấy từ <qa_facts> trong prompt.
     */
    private fun resolveAliasParams(
        params: Map<String, Any>,
        qaMatches: List<QAEntity>
    ): Map<String, Any> {
        if (qaMatches.isEmpty()) return params

        return params.mapValues { (key, value) ->
            // ✅ Generic: schedule.add lồng tham số thật của plugin đích trong key "params"
            // (vd email cần "to", camera cần "camera"...). Đệ quy vào đây để alias QA cũng
            // resolve được cho MỌI plugin đích, không chỉ tham số ở cấp 1.
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                return@mapValues resolveAliasParams(value as Map<String, Any>, qaMatches)
            }
            // Chỉ xử lý param key thuộc danh sách alias
            if (key !in ALIAS_PARAM_KEYS) return@mapValues value
            if (value !is String) return@mapValues value
            // Nếu đã là email hợp lệ → bỏ qua
            if (EMAIL_REGEX.matches(value)) return@mapValues value

            val matched = qaMatches.firstOrNull { qa ->
                qa.question.contains(value, ignoreCase = true) ||
                value.contains(qa.question, ignoreCase = true)
            }

            if (matched != null) {
                logger.d(
                    "AgentKernel",
                    "🔄 Alias resolved: [$key] \"$value\" → \"${matched.answer}\""
                )
                matched.answer
            } else {
                value
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM routing (dùng cho process())
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

    private fun JSONObject.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is org.json.JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        list.add(when (val item = value.get(i)) {
                            is JSONObject -> item.toMap()
                            else -> item
                        })
                    }
                    list
                }
                else -> value
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

    /**
     * Kết quả của tryDeviceCommand() — phân biệt rõ 3 trường hợp để ChatSkill biết
     * có cần cảnh báo "lệnh thất bại" cho model chat lớn hay không:
     *
     * - Matched      : router khớp được lệnh, đã (thử) thực thi — result có thể là
     *                  Success / Failure / NeedMoreInfo.
     * - NotACommand  : router xác định đây là chat thường (KHÔNG phải lệnh điều khiển) —
     *                  rơi xuống chat thường bình thường, không cần cờ báo gì thêm.
     * - RouterFailed : router CỐ định tuyến (message trông như 1 lệnh) nhưng thất bại
     *                  (JSON lỗi, hoặc trả về plugin không tồn tại) — ChatSkill cần cho
     *                  model chat lớn biết điều này để nó KHÔNG bịa ra là đã thực hiện.
     */
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