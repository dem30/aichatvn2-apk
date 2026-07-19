package com.aichatvn.agent.core

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.core.execution.IntentExecutor
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.data.model.SearchContract
import com.aichatvn.agent.data.model.QuestionType
import com.aichatvn.agent.data.model.AggregationType
import com.aichatvn.agent.skills.SearchMatch
import com.aichatvn.agent.skills.TrainingSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import com.aichatvn.agent.utils.DatabaseSearchHelper
import com.aichatvn.agent.utils.TimeRangeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

private data class ToolCall(val tool: String, val params: Map<String, String>)

data class DiagnosticTier(
    val tierName: String,
    val tierNum: Int,
    val matched: Boolean,
    val details: String,
    val score: Double = 0.0
)

data class CodeReference(
    val fileName: String,
    val functionName: String,
    val hardcodedRules: String,
    val businessLogic: String
)

data class TraceNode(
    val nodeId: String,
    val label: String,
    val input: String,
    val output: String,
    val matched: Boolean,
    val codeRef: CodeReference,
    val timestampNs: Long = System.nanoTime()
)

data class DiagnosticInfo(
    val query: String,
    val tiers: List<DiagnosticTier>,
    val resolvedIntents: List<String> = emptyList(),
    val resolvedAliases: Map<String, String> = emptyMap(),
    val intentMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val aliasMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val bestAliasMatches: Map<String, Pair<QAEntity, Double>> = emptyMap(),
    val intentThreshold: Float = 0.3f,
    val aliasThreshold: Float = 0.2f,
    val executionOutcome: String? = null,
    val traces: List<TraceNode> = emptyList(),
    val missingParams: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val askedQuestion: String? = null
)

data class LocalCandidate(
    val pluginId: String,
    val action: String,
    val description: String,
    val parameters: List<String>
)

data class Layer2Result(
    val plugin: Plugin,
    val intent: AgentKernel.Intent,
    val confidence: Double
)

data class NormalizedActionMetadata(
    val plugin: Plugin,
    val action: com.aichatvn.agent.core.plugin.PluginAction,
    val normalizedDescription: String,
    val normalizedExamples: List<String>,
    val normalizedTags: List<String>
)

data class PendingResultInfo(
    val plugin: Plugin,
    val actionName: String,
    val result: AgentKernel.PluginResult.NeedMoreInfo
)

sealed class Layer3Result {
    data class Single(val plugin: Plugin, val intent: AgentKernel.Intent) : Layer3Result()
    data class Nested(val wrapper: Plugin, val intent: AgentKernel.Intent) : Layer3Result()
    data class Multi(val intents: List<Pair<Plugin, AgentKernel.Intent>>) : Layer3Result()
    object NoMatch : Layer3Result()
}

enum class PipelineMode { EXECUTE, DIAGNOSTIC }

data class PipelineResult(
    val routerOutcome: AgentKernel.RouterOutcome?,
    val tiers: List<DiagnosticTier> = emptyList(),
    val finalOutcome: String? = null,
    val matchResult: TrainingSkill.MatchResult? = null,
    val traces: List<TraceNode> = emptyList()
)

data class ChatRequest(
    val message: String,
    val username: String = "default_user",
    val imageBase64: String? = null,
    val fileUrl: String? = null,
    val extraContext: String = "",
    val chatMode: String = "COMBINED",
    val allowDeviceControl: Boolean = true
)

data class ChatResponse(
    val responseText: String,
    val usedMode: String,
    val usedPluginId: String?,
    val imagePath: String? = null
)

@Singleton
class AgentKernel @Inject constructor(
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val groqClient: GroqClientTool,
    private val trainingSkill: TrainingSkill,
    private val chatHistoryManager: ChatHistoryManager,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,
    private val routingPipeline: RoutingPipeline,
    private val intentExecutor: IntentExecutor,
    private val databaseSearchHelper: DatabaseSearchHelper,
    private val timeRangeResolver: TimeRangeResolver,
    private val logger: Logger
) {
    fun getAvailablePluginsForUI(): List<Plugin> = plugins.filter { it.manifest.routable }

    suspend fun search(
        query: String,
        username: String,
        threshold: Float? = null
    ): List<SearchMatch> {
        return trainingSkill.fuzzyMatchChatCatalog(query, username, threshold)
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        val message = request.message
        val username = request.username
        val extraContext = request.extraContext
        val imageBase64 = request.imageBase64
        val fileUrl = request.fileUrl

        if (message.isBlank() && imageBase64.isNullOrEmpty() && fileUrl.isNullOrEmpty()) {
            return ChatResponse("", "empty_message_guard", null)
        }

        val expiredNotification = chatHistoryManager.popExpiredNotificationMessage(username, plugins)

        if (request.allowDeviceControl) {
            val lockedPluginId = chatHistoryManager.getLockedPlugin(username)
            
            for (plugin in plugins) {
                if (lockedPluginId != null && plugin.manifest.id != lockedPluginId) continue
                
                for (action in plugin.manifest.actions) {
                    if (!action.enabled) continue
                    val matchedPrefix = action.triggerPrefixes.find { prefix ->
                        message.startsWith(prefix, ignoreCase = true)
                    }
                    if (matchedPrefix != null) {
                        val result = executePluginAction(
                            pluginId = plugin.manifest.id,
                            action = action.name,
                            params = mapOf("message" to message, "username" to username)
                        )
                        val responseText = when (result) {
                            is PluginResult.Success -> {
                                val data = result.data as? Map<*, *>?
                                data?.get("message") as? String ?: "✅ Đã thực hiện câu lệnh tự động."
                            }
                            is PluginResult.Failure -> result.error
                            else -> "❌ Có lỗi phát sinh khi xử lý câu lệnh hệ thống."
                        }
                        val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
                        return ChatResponse(finalMsg, action.name, plugin.manifest.id)
                    }
                }
            }
        }

        if (!imageBase64.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
            val visionPlugin = plugins.find { it.manifest.capabilities.vision }
            if (visionPlugin != null) {
                val actionName = visionPlugin.manifest.actions.firstOrNull()?.name ?: "analyze"
                
                val optimizedMessage = if (message.isBlank()) {
                    "Hãy mô tả nội dung của hình ảnh này bằng tiếng Việt."
                } else {
                    "$message (Hãy trả lời hoàn toàn bằng tiếng Việt)"
                }

                val visionResult = executePluginAction(
                    pluginId = visionPlugin.manifest.id,
                    action = actionName,
                    params = mapOf(
                        "message" to optimizedMessage,
                        "username" to username,
                        "imageBase64" to (imageBase64 ?: ""),
                        "fileUrl" to (fileUrl ?: ""),
                        "extraContext" to extraContext
                    )
                )

                val responseText = when (visionResult) {
                    is PluginResult.Success -> {
                        val data = visionResult.data as? Map<*, *>?
                        data?.get("response") as? String ?: "Không có phản hồi từ mô hình thị giác."
                    }
                    is PluginResult.Failure -> visionResult.error
                    else -> "❌ Gặp lỗi trong quá trình phân tích hình ảnh."
                }
                val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
                return ChatResponse(finalMsg, actionName, visionPlugin.manifest.id)
            } else {
                return ChatResponse("⚠️ Thiết bị hiện không hỗ trợ phân tích hình ảnh (chưa cài đặt Vision Plugin).", "vision_error", null)
            }
        }

        val outcome = if (request.allowDeviceControl) tryDeviceCommand(message, username) else null
        if (outcome is RouterOutcome.Matched) {
            val deviceResult = outcome.result.result
            val responseText = when (deviceResult) {
                is PluginResult.Success -> {
                    val data = deviceResult.data as? Map<*, *>?
                    data?.get("message") as? String ?: "✅ Đã thực hiện thành công."
                }
                is PluginResult.Failure -> deviceResult.error
                is PluginResult.NeedMoreInfo -> deviceResult.question
            }
            val imagePath = (deviceResult as? PluginResult.Success)?.data
                ?.let { (it as? Map<*, *>)?.get("imagePath") as? String }
            val finalMsg = if (expiredNotification != null) "$expiredNotification\n\n$responseText" else responseText
            return ChatResponse(finalMsg, "device_control", outcome.result.pluginId, imagePath)
        }

        val routerFailed = outcome is RouterOutcome.RouterFailed
        val usedMode = request.chatMode
        val usedPluginId = if (routerFailed) "router_error" else null

        val maxSentences = configProvider.getInt(
            AppConfigDefaults.GLOBAL_CHAT_MAX_SENTENCES,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CHAT_MAX_SENTENCES).toInt()
        )

        val ANTI_HALLUCINATION_GUARD =
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được.\n" +
            "⚠️ Trả lời NGẮN GỌN, đi thẳng vào trọng tâm — tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ."

        val dynamicToolGuard = if (mentionsAppDomain(message)) buildToolCallingGuard() else ""

        val guard = buildString {
            append(ANTI_HALLUCINATION_GUARD)
            if (routerFailed) {
                append("\n⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định được chính xác (lý do nội bộ: ${(outcome as RouterOutcome.RouterFailed).reason}). Hãy báo cho user là lệnh CHƯA thực hiện được và hỏi họ nói rõ hơn, ĐỪNG khẳng định đã làm.")
            }
            append(dynamicToolGuard)
        }

        var responseText = try {
            when (usedMode.lowercase()) {
              



                "qa" -> {
    withTimeout(15_000L) {
        val matches = search(message, username)
        val qa = matches.firstOrNull()?.qa
        val localAnswer: String = runLocalQAEventAnalysis(message)

        qa?.answer ?: localAnswer
    }
}

                "groq" -> {
                    val historySnapshot = buildHistorySnapshot(username)

                    val cleanContext = buildString {
                        append(guard)
                        if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                    }

                    val responsePass1 = withTimeout(15_000L) {
                        groqClient.chat(
                            message = message,
                            extraContext = cleanContext,
                            history = historySnapshot,
                            imageUrl = null
                        )
                    }

                    interceptAndExecuteToolCall(message, responsePass1, username, cleanContext, historySnapshot)
                }

                else -> {
                    val matches = search(message, username)
                    val perfectMatch = matches.firstOrNull()?.qa

                    if (perfectMatch != null) {
                        perfectMatch.answer
                    } else {
                        val historySnapshot = buildHistorySnapshot(username)
                        
                        val qaContext = buildQAContextForAgent(message, username)
                        val fullContext = buildString {
                            append(guard)
                            if (qaContext.isNotEmpty()) append("\n\n$qaContext")
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                        }

                        val responsePass1 = withTimeout(15_000L) {
                            groqClient.chat(
                                message = message,
                                extraContext = fullContext,
                                history = historySnapshot,
                                imageUrl = null
                            )
                        }

                        interceptAndExecuteToolCall(message, responsePass1, username, fullContext, historySnapshot)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("AgentKernel", "❌ Lỗi hệ thống trong luồng chat: ${e.message}", e)
            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau."
        }
        
        if (expiredNotification != null) {
            responseText = "$expiredNotification\n\n$responseText"
        }
        return ChatResponse(responseText, usedMode, usedPluginId)
    }

    /**
     * Bộ phân tích ngữ pháp Tiếng Việt cục bộ dành riêng cho QA Mode (Không tốn token Groq).
     */
    private suspend fun runLocalQAEventAnalysis(userQuery: String): String = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val normalized = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userQuery.lowercase())

            // 1. Phân giải mốc thời gian cục bộ bằng TimeRangeParser
            val parsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalized, now)
            val since = parsedRange?.since ?: (now - 24 * 60 * 60 * 1000L)
            val until = parsedRange?.until ?: now
            
            // ✅ SỬA: Ép kiểu String phi-null tuyệt đối bằng biểu thức rẽ nhánh if-else từ thuộc tính Java/Platform Type,
            // dọn dẹp triệt độ mọi nguy cơ mismatch kiểu dữ liệu của Kotlin Compiler.
            val label: String = if (parsedRange != null && parsedRange.label != null) {
                parsedRange.label
            } else {
                "hôm nay"
            }

            // 2. Phân loại loại câu hỏi (đếm số lần, kiểm tra có hay không)
            val isQuantity = QUANTITY_KEYWORDS.any { normalized.contains(it) }
            val isYesNo = normalized.contains("co ") && (normalized.contains("khong") || normalized.contains("phai khong") || normalized.contains("chua"))
            
            val questionType = when {
                isQuantity -> QuestionType.QUANTITY
                isYesNo -> QuestionType.YES_NO
                else -> QuestionType.OTHER
            }

            val aggregation = if (isQuantity) AggregationType.COUNT else AggregationType.NONE

            // 3. Nhận diện thiết bị Tuya, Camera hoặc Kênh chat đang hoạt động trong DB
            val activeCameras = database.cameraDao().getActiveCameras()
            val activeDevices = database.tuyaDeviceDao().getAllDevices()

            val matchedCam = activeCameras.find { cam ->
                val normName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(cam.customername.lowercase())
                normalized.contains(normName) || normalized.contains(cam.id.lowercase())
            }
            val matchedDev = activeDevices.find { dev ->
                val normName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(dev.name.lowercase())
                normalized.contains(normName) || normalized.contains(dev.id.lowercase())
            }

            var sourceCategory: String? = null
            var sourceName: String? = null

            when {
                // ✅ ĐÃ SỬA: cùng lý do như interceptAndExecuteToolCall() — fallback về `.id` khi
                // tên thân thiện rỗng, tránh sourceIdOrName cuối cùng nhận "" khiến
                // DatabaseSearchHelper bỏ qua bước lọc theo đúng camera/thiết bị.
                matchedCam != null -> { sourceCategory = "camera"; sourceName = matchedCam.customername.ifBlank { matchedCam.id } }
                matchedDev != null -> { sourceCategory = "tuya"; sourceName = matchedDev.name.ifBlank { matchedDev.id } }
                normalized.contains("camera") || normalized.contains("cam") -> sourceCategory = "camera"
                normalized.contains("den") || normalized.contains("quat") || normalized.contains("thiet bi") -> sourceCategory = "tuya"
                // ✅ ĐÃ SỬA: Lọc chi tiết theo nền tảng kênh chat di động di sản thay vì dồn chung về "chat".
                // Đồng bộ hóa với DatabaseSearchHelper mới để hỗ trợ cả 3 nhãn facebook, telegram, website độc lập.
                normalized.contains("facebook") || normalized.contains("fb") -> sourceCategory = "facebook"
                normalized.contains("telegram") -> sourceCategory = "telegram"
                normalized.contains("website") || normalized.contains("web") -> sourceCategory = "website"
                normalized.contains("tin nhan") || normalized.contains("nhan tin") -> sourceCategory = "chat"
            }

            // 4. Nhận diện trạng thái thiết bị Tuya
            val deviceState = when {
                normalized.contains("bat") || normalized.contains("mo") -> "true"
                normalized.contains("tat") -> "false"
                else -> null
            }

            // 5. Đóng gói thành SearchContract và chuyển thẳng xuống DatabaseSearchHelper để tính toán số liệu
            val contract = SearchContract(
                questionType = questionType,
                sinceMs = since,
                untilMs = until,
                timeframeLabel = label,
                sourceCategory = sourceCategory,
                sourceIdOrName = sourceName ?: matchedCam?.id ?: matchedDev?.id,
                targetObject = extractObjectLabel(normalized),
                deviceState = deviceState,
                aggregation = aggregation
            )

            val searchResult = databaseSearchHelper.executeSearchContract(contract)
            searchResult.summaryText
        } catch (e: Exception) {
            logger.e("AgentKernel", "Lỗi phân tích sự kiện cục bộ trong QA Mode: ${e.message}", e)
            "Không thể phân tích dữ liệu cục bộ lúc này."
        }
    }

    private val QUANTITY_KEYWORDS = setOf("bao nhieu", "may lan", "so luong", "tong cong", "duoc may", "dem")
    
    private val OBJECT_LABEL_KEYWORDS = mapOf(
        "person" to listOf("nguoi la", "co nguoi", "nguoi dot nhap", "xam nhap", "trom", "nguoi"),
        "car" to listOf("oto", "xe hoi", "xe oto", "xe bon banh"),
        "motorbike" to listOf("xe may", "xe hai banh"),
        "dog" to listOf("con cho", "cho "),
        "cat" to listOf("con meo", "meo "),
        "package" to listOf("goi hang", "buu kien", "shipper")
    )

    private fun extractObjectLabel(normalizedMsg: String): String? =
        OBJECT_LABEL_KEYWORDS.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key

    private fun extractStateKeyword(originalMessage: String, normalizedMsg: String): List<String>? = when {
        originalMessage.contains("tắt", ignoreCase = true) || normalizedMsg.contains("off") ->
            listOf("tắt", "off", "Tắt", "OFF")
        originalMessage.contains("bật", ignoreCase = true) || normalizedMsg.contains("on") ->
            listOf("bật", "on", "Bật", "ON")
        else -> null
    }

    private suspend fun interceptAndExecuteToolCall(
        originalMessage: String,
        responseRaw: String,
        username: String,
        baseContext: String,
        historySnapshot: List<Map<String, String>>,
        toolDepth: Int = 0
    ): String {
        val toolCall = parseToolCall(responseRaw) ?: return responseRaw

        if (toolDepth >= 1) {
            logger.w("AgentKernel", "⚠️ [Tool Loop Guard] Đã đạt giới hạn lặp tool (depth=$toolDepth). Ngắt lặp!")
            return "Hệ thống phát hiện yêu cầu tìm kiếm lặp lại quá nhiều lần, xin lỗi vì chưa thể hoàn thành chi tiết này."
        }

        if (username != "default_user") {
            logger.w("AgentKernel", "⚠️ Chặn gọi Tool truy cập lịch sử từ tài khoản khách ngoại tuyến: $username")
            return "Dạ, để bảo vệ quyền riêng tư của gia đình, nhật ký hoạt động camera và thiết bị chỉ có thể được truy cập bởi tài khoản Quản trị viên (Chủ nhà) trên ứng dụng nội bộ thôi ạ."
        }

        logger.i("AgentKernel", "📥 [Two-Pass Intercepted] Phát hiện AI yêu cầu gọi Tool: '${toolCall.tool}' (tham số: ${toolCall.params})")

        return try {
            val rawTimeframe = toolCall.params["timeframe"] ?: "today"
            val objectLabel = toolCall.params["object"] ?: "all"
            val sourceHint = toolCall.params["source"]?.trim()

            var resolvedSourceCategory: String? = null
            var resolvedSourceName: String? = null
            if (!sourceHint.isNullOrBlank()) {
                val normHint = StringSimilarityUtil.normalizeVietnamese(sourceHint.lowercase())
                // ✅ ĐÃ SỬA: bổ sung so khớp theo `.id` (không chỉ customername/name) — trước đây
                // chỉ so tên thân thiện, nên nếu buildToolCallingGuard() liệt kê whitelist gồm cả
                // ID (vd "camera 1"), Groq có thể hợp lệ trả về source="camera 1" nhưng bước resolve
                // này lại không khớp được với ID đó -> resolvedSourceCategory rơi về null, dữ liệu
                // tra cứu vẫn sai dù prompt đã đúng. Đồng bộ lại với logic đã đúng sẵn ở
                // runLocalQAEventAnalysis() (nhánh QA cục bộ), vốn đã match cả customername/name lẫn id.
                val matchedCamera = try {
                    database.cameraDao().getActiveCameras().find {
                        val normCamName = StringSimilarityUtil.normalizeVietnamese(it.customername.lowercase())
                        normCamName.contains(normHint) || normHint.contains(normCamName) ||
                        normHint.contains(it.id.lowercase())
                    }
                } catch (e: Exception) { null }
                val matchedDevice = if (matchedCamera == null) {
                    try {
                        database.tuyaDeviceDao().getAllDevices().find {
                            val normDevName = StringSimilarityUtil.normalizeVietnamese(it.name.lowercase())
                            normDevName.contains(normHint) || normHint.contains(normDevName) ||
                            normHint.contains(it.id.lowercase())
                        }
                    } catch (e: Exception) { null }
                } else null

                when {
                    // ✅ ĐÃ SỬA: fallback về `.id` khi tên thân thiện rỗng — trước đây nếu
                    // customername/name là chuỗi rỗng (không phải null), sourceIdOrName sẽ nhận
                    // giá trị "" (vì `?:` chỉ bắt null, không bắt rỗng). DatabaseSearchHelper coi
                    // "" là blank nên BỎ QUA HẲN bước lọc theo đúng camera/thiết bị, trả về log của
                    // TẤT CẢ camera/thiết bị trong cùng category thay vì chỉ camera/thiết bị đã match.
                    matchedCamera != null -> { resolvedSourceCategory = "camera"; resolvedSourceName = matchedCamera.customername.ifBlank { matchedCamera.id } }
                    matchedDevice != null -> { resolvedSourceCategory = "tuya"; resolvedSourceName = matchedDevice.name.ifBlank { matchedDevice.id } }
                    normHint.contains("facebook") || normHint.contains("fb") -> resolvedSourceCategory = "facebook"
                    normHint.contains("telegram") -> resolvedSourceCategory = "telegram"
                    normHint.contains("website") || normHint.contains("web") -> resolvedSourceCategory = "website"
                }
            }

            val timeframe = if (objectLabel == "all" && resolvedSourceCategory == null && rawTimeframe in setOf("last_3_days", "last_7_days")) {
                logger.w("AgentKernel", "⚠️ [Cost Guard] Chặn combo tốn kém object=all + $rawTimeframe (không có source thu hẹp), ép về 'today'")
                "today"
            } else {
                rawTimeframe
            }

            // Phân giải mốc thời gian thực tế
            val timeRange = timeRangeResolver.resolve(timeframe)

            // ✅ NÂNG CẤP: Chuyển đổi luồng AI Two-Pass sang thực thi Hợp đồng tìm kiếm chuẩn hóa
            val contract = SearchContract(
                questionType = QuestionType.OTHER,
                sinceMs = timeRange.since,
                untilMs = timeRange.until,
                timeframeLabel = timeRange.label,
                sourceCategory = resolvedSourceCategory,
                sourceIdOrName = resolvedSourceName ?: sourceHint,
                targetObject = objectLabel,
                aggregation = if (originalMessage.contains("mấy lần") || originalMessage.contains("bao nhiêu")) AggregationType.COUNT else AggregationType.NONE
            )

            val searchResult = databaseSearchHelper.executeSearchContract(contract)

            val enrichedContext = buildString {
                append(baseContext)
                append("\n\n")
                append("<SYSTEM_MEMORY>\n")
                append(searchResult.summaryText)
                append("\n\n👉 CHỈ THỊ: Hãy sử dụng dữ liệu thực tế chính xác trong tag <SYSTEM_MEMORY> này để trả lời đầy đủ câu hỏi của người dùng. TUYỆT ĐỐI không được bịa đặt mốc thời gian không có trong tag.")
                append("\n</SYSTEM_MEMORY>")
            }

            logger.i("AgentKernel", "🚀 [Two-Pass Second Call] Đang gửi lại dữ liệu thực tế lên Groq lượt 2 (Timeout: 15 giây)...")

            withTimeout(15_000L) {
                val responsePass2 = groqClient.chat(
                    message = originalMessage,
                    extraContext = enrichedContext,
                    history = historySnapshot,
                    imageUrl = null
                )
                
                interceptAndExecuteToolCall(
                    originalMessage = originalMessage,
                    responseRaw = responsePass2,
                    username = username,
                    baseContext = enrichedContext,
                    historySnapshot = historySnapshot,
                    toolDepth = toolDepth + 1
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("AgentKernel", "⚠️ Gặp lỗi khi xử lý dữ liệu Two-Pass Loop, quay về Fallback lượt 1: ${e.message}", e)
            responseRaw
        }
    }

    private fun parseToolCall(response: String): ToolCall? {
        val trimmed = response.trim()
        val jsonStartIndex = trimmed.indexOf('{')
        val jsonEndIndex = trimmed.lastIndexOf('}')
        
        if (jsonStartIndex == -1 || jsonEndIndex == -1 || jsonStartIndex >= jsonEndIndex) {
            return null
        }

        val potentialJson = trimmed.substring(jsonStartIndex, jsonEndIndex + 1).trim()
        
        return try {
            val json = org.json.JSONObject(potentialJson)
            val tool = json.optString("tool", "")
            
            if (tool == "db_search") {
                val timeframe = json.optString("timeframe", "today")
                val objectLabel = json.optString("object", "all")
                val source = json.optString("source", "").trim()
                ToolCall(tool, buildMap {
                    put("timeframe", timeframe)
                    put("object", objectLabel)
                    if (source.isNotBlank()) put("source", source)
                })
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun truncateSmart(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val cut = text.substring(0, maxLen)
        val lastSpace = cut.lastIndexOf(' ')
        val safeCut = if (lastSpace >= (maxLen * 0.6).toInt()) cut.substring(0, lastSpace) else cut
        return "$safeCut…"
    }

    private suspend fun buildHistorySnapshot(username: String): List<Map<String, String>> {
        return try {
            val raw = database.chatMessageDao().getMessages(username, 6).reversed()
            val recentCutoffIndex = raw.size - 2
            raw.mapIndexed { index, msg ->
                val isRecent = index >= recentCutoffIndex
                val maxLen = when {
                    isRecent && msg.role == "assistant" -> 300
                    isRecent -> 200
                    msg.role == "assistant" -> 70
                    else -> 120
                }
                mapOf("role" to msg.role, "content" to truncateSmart(msg.content, maxLen))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_QA_MATCHES_IN_CONTEXT = 3
        private const val MAX_QA_ANSWER_CHARS = 200
    }

    private suspend fun buildQAContextForAgent(message: String, username: String): String {
        val matches = search(message, username, 0.7f)
            .sortedByDescending { it.similarity }
            .take(MAX_QA_MATCHES_IN_CONTEXT)
        if (matches.isEmpty()) return ""
        return matches.joinToString("\n") { match ->
            val answer = match.qa.answer.let {
                if (it.length > MAX_QA_ANSWER_CHARS) it.take(MAX_QA_ANSWER_CHARS) + "…" else it
            }
            "📚 Q: ${match.qa.question}\n   A: $answer (độ tương tự: ${String.format("%.2f", match.similarity)})"
        }
    }

    private fun extractLocalMemoryAnswer(extraContext: String): String? {
        val start = extraContext.indexOf("<SYSTEM_MEMORY>")
        val end = extraContext.indexOf("</SYSTEM_MEMORY>")
        if (start == -1 || end == -1 || end <= start) return null
        val inner = extraContext.substring(start + "<SYSTEM_MEMORY>".length, end)
            .lines()
            .filterNot { it.contains("Hãy sử dụng dữ liệu") || it.contains("Hãy trả lời trực tiếp") || it.contains("Chuẩn hóa tag") }
            .joinToString("\n")
            .trim()
        return inner.takeIf { it.isNotBlank() }
    }

    suspend fun tryDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): RouterOutcome {
        val traceId = "TR-${System.currentTimeMillis() % 100000}-${(100..999).random()}"
        logger.d("AgentKernel", "[$traceId] 🚀 Bắt đầu tiếp nhận thông điệp: '$userMessage'")

        val normMsg = StringSimilarityUtil.normalizeVietnamese(userMessage.trim())
        val isAskingPending = normMsg.contains("dang cho gi") || 
            normMsg.contains("kiem tra yeu cau") || 
            (normMsg.contains("cho") && normMsg.contains("gi") && normMsg.contains("pending")) ||
            normMsg.contains("dang bi ket")

        if (isAskingPending) {
            val active = chatHistoryManager.getActivePendingIntents(username)
            if (active.isEmpty()) {
                return RouterOutcome.Matched(
                    DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Hiện tại không có yêu cầu điều khiển thiết bị nào đang chờ xử lý.")))
                )
            }
            val first = active.first()
            val targetPlugin = plugins.find { it.manifest.id == first.pluginId }
            val targetAction = targetPlugin?.manifest?.actions?.find { it.name == first.action }
            val pluginName = targetPlugin?.manifest?.name ?: first.pluginId
            val actionDesc = targetAction?.description ?: first.action
            val banner = routingPipeline.buildPendingBanner(first, pluginName, actionDesc)
            
            @Suppress("UNCHECKED_CAST")
            val currentOptions = first.knownParams["_options"] as? Map<String, String> ?: emptyMap()
            return RouterOutcome.Matched(
                DeviceCommandResult(first.pluginId, PluginResult.NeedMoreInfo(first.missingParams, banner, currentOptions))
            )
        }

        chatHistoryManager.getPendingLockRequest(username)?.let { pluginId ->
            return handleLockConfirmation(userMessage, username, pluginId)
        }

        chatHistoryManager.getLockedPlugin(username)?.let { lockedId ->
            if (isExitLockPhrase(userMessage)) {
                chatHistoryManager.unlockPlugin(username)
                val matchedPlugin = plugins.find { it.manifest.id == lockedId }
                val displayName = matchedPlugin?.manifest?.name ?: lockedId
                return RouterOutcome.Matched(
                    DeviceCommandResult(lockedId, PluginResult.Success(mapOf("message" to "✅ Đã thoát chế độ điều khiển riêng cho \"$displayName\".")))
                )
            }
            val result = routingPipeline.process(userMessage, username, PipelineMode.EXECUTE, traceId, forcedPluginIds = listOf(lockedId))
            val outcome = result.routerOutcome
            return if (outcome == null || outcome is RouterOutcome.NotACommand) {
                RouterOutcome.Matched(
                    DeviceCommandResult(lockedId, PluginResult.Failure("⚠️ Tôi không hiểu lệnh điều khiển riêng cho \"$lockedId\". Vui lòng nói lại, hoặc gõ \"thoát\" để quay lại chat thường."))
                )
            } else {
                outcome
            }
        }

        detectLockTrigger(userMessage)?.let { targetPluginId ->
            chatHistoryManager.setPendingLockRequest(username, targetPluginId)
            val matchedPlugin = plugins.find { it.manifest.id == targetPluginId }
            val displayName = matchedPlugin?.manifest?.name ?: targetPluginId
            return RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Bạn muốn vào chế độ điều khiển riêng biệt cho \"$displayName\", đúng không?")))
            )
        }

        val result = routingPipeline.process(userMessage, username, PipelineMode.EXECUTE, traceId)
        return result.routerOutcome ?: RouterOutcome.RouterFailed("Pipeline execution error")
    }

    suspend fun explainDeviceCommand(
        userMessage: String,
        username: String = "default_user"
    ): DiagnosticInfo {
        val result = routingPipeline.process(userMessage, username, PipelineMode.DIAGNOSTIC, "DIAGNOSTIC-TRACE")
        val matchResult = result.matchResult ?: TrainingSkill.MatchResult(emptyList(), emptyList(), emptyMap())
        val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD).toFloat())
        val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD).toFloat())

        val activePending = chatHistoryManager.getActivePendingIntents(username).firstOrNull()
        @Suppress("UNCHECKED_CAST")
        val activeOptions = activePending?.knownParams?.get("_options") as? Map<String, String> ?: emptyMap()

        return DiagnosticInfo(
            query = userMessage,
            tiers = result.tiers,
            resolvedIntents = matchResult.intentMatches
                .filter { it.second >= intentThreshold }
                .map { "${it.first.question} (${String.format("%.2f", it.second)})" },
            resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
            intentMatches = matchResult.intentMatches,
            aliasMatches = matchResult.aliasMatches,
            bestAliasMatches = matchResult.bestAliasMatches,
            intentThreshold = intentThreshold,
            aliasThreshold = aliasThreshold,
            executionOutcome = result.finalOutcome,
            traces = result.traces,
            missingParams = activePending?.missingParams ?: emptyList(),
            options = activeOptions,
            askedQuestion = activePending?.askedQuestion
        )
    }

    suspend fun executePluginAction(
        pluginId: String,
        action: String,
        params: Map<String, Any>
    ): PluginResult {
        return intentExecutor.executePluginAction(pluginId, action, params)
    }

    suspend fun process(userMessage: String): PluginResult {
        val outcome = tryDeviceCommand(userMessage)
        return when (outcome) {
            is RouterOutcome.Matched -> outcome.result.result
            is RouterOutcome.RouterFailed -> PluginResult.Failure(outcome.reason)
            is RouterOutcome.NotACommand -> PluginResult.Failure("Không phải lệnh thiết bị")
        }
    }

    private suspend fun buildToolCallingGuard(): String {
        // ✅ ĐÃ SỬA: hiển thị đồng thời TÊN THÂN THIỆN và ID thật cho cả camera lẫn thiết bị Tuya —
        // trước đây chỉ hiện customername/name, khiến Groq không biết các ID hợp lệ khác (vd
        // "camera 1" khi chỉ có "vinh" trong whitelist) và có thể hiểu nhầm là thiết bị không tồn
        // tại, hoặc vẫn gọi tool với 1 source mà bước resolve phía dưới không khớp được.
        val cameraNames = try {
            database.cameraDao().getActiveCameras().map {
                if (it.customername.isNotBlank()) "${it.customername} (ID: ${it.id})" else "ID: ${it.id}"
            }
        } catch (e: Exception) { emptyList() }
        val deviceNames = try {
            database.tuyaDeviceDao().getAllDevices().map {
                if (it.name.isNotBlank()) "${it.name} (ID: ${it.id})" else "ID: ${it.id}"
            }
        } catch (e: Exception) { emptyList() }

        return "\n\n🚨 QUY TẮC TRUY VẤN DỮ LIỆU THỰC TẾ:\n" +
            "1. Nếu người dùng hỏi về hoạt động của camera/thiết bị/kênh ngoại trong quá khứ hoặc hiện tại mà bạn chưa có thông tin thô, " +
            "hãy trả về DUY NHẤT một chuỗi JSON thô có cấu trúc sau (tuyệt đối không markdown, không giải thích gì thêm):\n" +
            "{\n" +
            "  \"tool\": \"db_search\",\n" +
            "  \"timeframe\": \"today | yesterday | last_3_days | last_7_days\",\n" +
            "  \"object\": \"person | car | motorbike | dog | cat | package | all\",\n" +
            "  \"source\": \"tên camera hoặc thiết bị mà người dùng nhắc tới\"\n" +
            "}\n" +
            "2. Chỉ được gọi tool cho thiết bị thật sự có tên hoặc ID trùng khớp hoặc nằm trong danh sách đăng ký dưới đây. Nếu người dùng hỏi một thiết bị lạ không tồn tại, hãy trả lời thẳng là hệ thống không lắp đặt thiết bị đó, TUYỆT ĐỐI không được gọi tool.\n" +
            "3. Nếu kết quả tìm kiếm cục bộ trả về rỗng hoặc báo 'hoạt động bình thường / không có sự kiện phù hợp', điều đó có nghĩa là thực tế không có sự kiện gì diễn ra. Bạn hãy trả lời tự nhiên là không ghi nhận sự kiện nào, TUYỆT ĐỐI không được gọi lại tool db_search nữa.\n" +
            "4. Nếu đã được hệ thống cung cấp dữ liệu thô, hãy trả lời tự nhiên, tuyệt đối không trả về JSON nữa.\n" +
            (if (cameraNames.isNotEmpty()) "📷 Camera đang có: ${cameraNames.joinToString(", ")}\n" else "") +
            (if (deviceNames.isNotEmpty()) "🔌 Thiết bị đang có: ${deviceNames.joinToString(", ")}\n" else "") +
            "💬 Kênh chat hỗ trợ: facebook, telegram, website"
    }

    private fun mentionsAppDomain(msg: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        val domainKeywords = listOf(
            "camera", "canh bao", "phat hien", "nguoi la", "xam nhap", "quay",
            "den", "cua", "quat", "dieu hoa", "thiet bi", "bat", "tat",
            "tin nhan", "nhan tin",
            "liet ke", "lich su", "hom qua", "may gio", "kiem tra"
        )
        return domainKeywords.any { norm.contains(it) }
    }

    private fun isExitLockPhrase(msg: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        val exitPhrases = setOf("thoat dieu khien", "ra khoi dieu khien", "ket thuc dieu khien", "thoat")
        return norm in exitPhrases
    }

    private fun parseYesNo(msg: String): Boolean? {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        
        val yesKeywords = setOf("co", "dung", "u", "ok", "dong y", "chuan", "phai", "co nhe", "co chu", "dung roi")
        val noKeywords = setOf("khong", "khoi", "thoi", "huy", "sai", "khong dau", "khong nhe", "bo qua")
        
        val isYes = yesKeywords.any { kw -> 
            norm == kw || Regex("(?<!\\p{L})$kw(?!\\p{L})").containsMatchIn(norm)
        }
        val isNo = noKeywords.any { kw -> 
            norm == kw || Regex("(?<!\\p{L})$kw(?!\\p{L})").containsMatchIn(norm)
        }
        
        return when {
            isYes && !isNo -> true
            isNo && !isYes -> false
            else -> null
        }
    }

    private fun detectLockTrigger(userMessage: String): String? {
        val norm = StringSimilarityUtil.normalizeVietnamese(userMessage.trim())
        val m = Regex("^(dieu khien|khoa dieu khien)\\s+(thiet bi\\s+)?(.+)$").find(norm) ?: return null
        val target = m.groupValues[3].trim()
        if (target.isBlank()) return null

        val matched = plugins.firstOrNull { p ->
            p.manifest.routable && (
                target.contains(p.manifest.id, ignoreCase = true) ||
                StringSimilarityUtil.normalizeVietnamese(p.manifest.name).contains(target, ignoreCase = true)
            )
        }
        return matched?.manifest?.id
    }

    private fun handleLockConfirmation(userMessage: String, username: String, pluginId: String): RouterOutcome {
        val matchedPlugin = plugins.find { it.manifest.id == pluginId }
        val displayName = matchedPlugin?.manifest?.name ?: pluginId
        return when (parseYesNo(userMessage)) {
            true -> {
                chatHistoryManager.lockPlugin(username, pluginId)
                chatHistoryManager.clearLockRequest(username)
                RouterOutcome.Matched(
                    DeviceCommandResult(pluginId, PluginResult.Success(mapOf("message" to "🔒 Đã vào chế độ điều khiển riêng biệt cho \"$displayName\". Tất cả hội thoại thông thường sẽ bị chặn cho đến khi bạn yêu cầu \"thoát\".")))
                )
            }
            false -> {
                chatHistoryManager.clearLockRequest(username)
                RouterOutcome.Matched(
                    DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Đã hủy yêu cầu điều khiển riêng.")))
                )
            }
            null -> RouterOutcome.Matched(
                DeviceCommandResult("__system__", PluginResult.Success(mapOf("message" to "Xác nhận vào chế độ điều khiển riêng cho \"$displayName\" chứ? (có/không)")))
            )
        }
    }

    fun getLockedPluginId(username: String = "default_user"): String? = chatHistoryManager.getLockedPlugin(username)

    fun getLockedPluginName(username: String = "default_user"): String? {
        val id = getLockedPluginId(username) ?: return null
        return plugins.find { it.manifest.id == id }?.manifest?.name ?: id
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
        data class NeedMoreInfo(
            val missingParams: List<String>, 
            val question: String,
            val options: Map<String, String> = emptyMap()
        ) : PluginResult()
    }
}--- END OF FILE text/plain ---

package com.aichatvn.agent.utils

import com.aichatvn.agent.data.EventLogDao
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.SearchContract
import com.aichatvn.agent.data.model.QuestionType
import com.aichatvn.agent.data.model.AggregationType
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseSearchHelper @Inject constructor(
    private val eventLogDao: EventLogDao,
    private val timeRangeResolver: TimeRangeResolver,
    private val objectAliasResolver: ObjectAliasResolver,
    private val logger: Logger
) {

    companion object {
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
    }

    /**
     * Tương thích ngược: Giữ nguyên chữ ký hàm cũ cho các lớp di sản gọi.
     * Chuyển đổi các tham số thô thành cấu trúc SearchContract để chạy qua bộ lọc tập trung mới.
     */
    suspend fun executeSearch(
        timeframe: String,
        objectLabel: String,
        allowedEventTypes: List<String>? = null,
        allowedSources: List<String>? = null,
        limit: Int = 20
    ): SearchResult {
        val timeRange = timeRangeResolver.resolve(timeframe)
        val contract = SearchContract(
            questionType = QuestionType.OTHER,
            sinceMs = timeRange.since,
            untilMs = timeRange.until,
            timeframeLabel = timeRange.label,
            sourceCategory = allowedSources?.firstOrNull(),
            targetObject = objectLabel,
            aggregation = AggregationType.NONE
        )
        return executeSearchContract(contract, limit)
    }

    /**
     * NÂNG CẤP: Trung tâm xử lý Hợp đồng tìm kiếm chung.
     * Thực hiện bóc tách, lọc sâu, dán nhãn Yes/No, đếm tần suất và tính toán logic tự động.
     */
    suspend fun executeSearchContract(
        contract: SearchContract,
        limit: Int = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        // Tải các dòng dữ liệu thô trong khung thời gian yêu cầu
        val rawLogs = eventLogDao.getLogsInTimeframe(contract.sinceMs, contract.untilMs)

        // 1. NÂNG CẤP: Lọc theo Phân loại nguồn (Camera / Thiết bị Tuya / Kênh Chat)
        // Nếu sourceCategory là "chat", tự động mở rộng truy vấn tìm kiếm trên cả 3 nền tảng facebook, telegram, website
        var filtered = if (contract.sourceCategory != null) {
            if (contract.sourceCategory.equals("chat", ignoreCase = true)) {
                rawLogs.filter { 
                    it.source.equals("facebook", ignoreCase = true) || 
                    it.source.equals("telegram", ignoreCase = true) || 
                    it.source.equals("website", ignoreCase = true) 
                }
            } else {
                rawLogs.filter { it.source.equals(contract.sourceCategory, ignoreCase = true) }
            }
        } else {
            rawLogs
        }

        // 2. SỬA: Sử dụng isNullOrBlank() an toàn. Nếu chuỗi rỗng "" hoặc blank lọt xuống đây,
        // hệ thống sẽ bỏ qua, không kích hoạt bộ lọc theo ID để tương thích đúng với logic fallback của AgentKernel.
        if (!contract.sourceIdOrName.isNullOrBlank()) {
            val normHint = StringSimilarityUtil.normalizeVietnamese(contract.sourceIdOrName.lowercase())
            filtered = filtered.filter { log ->
                val normId = StringSimilarityUtil.normalizeVietnamese(log.sourceId.lowercase())
                val normSummary = StringSimilarityUtil.normalizeVietnamese(log.summary.lowercase())
                normId.contains(normHint) || normSummary.contains(normHint)
            }
        }

        // 3. Lọc theo trạng thái vật lý của thiết bị Tuya nếu có (true = bật, false = tắt)
        if (contract.deviceState != null) {
            filtered = filtered.filter { log ->
                log.value.equals(contract.deviceState, ignoreCase = true) ||
                (contract.deviceState == "true" && log.summary.contains("bật", ignoreCase = true)) ||
                (contract.deviceState == "false" && log.summary.contains("tắt", ignoreCase = true))
            }
        }

        // 4. Lọc theo lớp vật thể an ninh bằng ObjectAliasResolver (person, car, dog...)
        if (contract.targetObject != null && contract.targetObject.lowercase() != "all" && contract.targetObject.lowercase() != "none") {
            filtered = filtered.filter { log ->
                objectAliasResolver.matches(log.summary, contract.targetObject)
            }
        }

        // 5. Lọc sâu theo các từ khóa miêu tả mở rộng
        if (contract.detailsKeywords.isNotEmpty()) {
            filtered = filtered.filter { log ->
                contract.detailsKeywords.any { keyword ->
                    log.summary.contains(keyword, ignoreCase = true)
                }
            }
        }

        val totalCount = filtered.size
        val isTruncated = totalCount > limit
        val sortedLogs = filtered.sortedByDescending { it.timestamp }
        val truncatedLogs = sortedLogs.take(limit).reversed()

        // 6. TIẾN HÀNH TỔNG HỢP VÀ TỰ TÍNH TOÁN (Heuristic Query Aggregation)
        val summaryText = buildString {
            // SỬA: Đọc an toàn thông tin nhãn thời gian bằng toán tử Elvis
            val resolvedLabel = contract.timeframeLabel ?: "hôm nay"
            append("--- Nhật ký tìm kiếm tự động [${resolvedLabel.uppercase()}] ---\n")
            
            if (filtered.isEmpty()) {
                if (contract.questionType == QuestionType.YES_NO) {
                    append("💡 Câu trả lời: KHÔNG. Hệ thống không ghi nhận bất kỳ sự kiện nào trùng khớp.\n")
                } else {
                    append("Hệ thống hoạt động bình thường, không ghi nhận sự kiện phù hợp.\n")
                }
            } else {
                when (contract.aggregation) {
                    AggregationType.COUNT -> {
                        append("💡 Thống kê tần suất: Ghi nhận tổng cộng $totalCount lần diễn ra sự kiện.\n")
                        val onCount = filtered.count { it.summary.contains("bật", ignoreCase = true) || it.value == "true" }
                        val offCount = filtered.count { it.summary.contains("tắt", ignoreCase = true) || it.value == "false" }
                        if (onCount > 0 || offCount > 0) {
                            append("-> Trong đó có $onCount lần bật thiết bị và $offCount lần tắt thiết bị.\n")
                        }
                    }
                    AggregationType.COMPARE -> {
                        val cameraCount = filtered.count { it.source == "camera" }
                        val deviceCount = filtered.count { it.source == "tuya" }
                        append("💡 Phân tích so sánh: Camera ghi nhận $cameraCount lần an ninh, Thiết bị có $deviceCount lần thay đổi trạng thái.\n")
                    }
                    AggregationType.NONE -> {
                        if (contract.questionType == QuestionType.YES_NO) {
                            append("💡 Câu trả lời: CÓ. Hệ thống xác nhận ghi nhận sự kiện trùng khớp yêu cầu.\n")
                        }
                    }
                }

                append("\nChi tiết nhật ký hoạt động:\n")
                truncatedLogs.forEach { log ->
                    val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                    append("• [$timeStr] ${log.summary}\n")
                }
                if (isTruncated) {
                    append("*(Đã ẩn bớt ${totalCount - limit} sự kiện cũ để tối ưu)*\n")
                }
            }
        }

        SearchResult(
            logs = truncatedLogs,
            summaryText = summaryText,
            totalCount = totalCount,
            isTruncated = isTruncated
        )
    }
}