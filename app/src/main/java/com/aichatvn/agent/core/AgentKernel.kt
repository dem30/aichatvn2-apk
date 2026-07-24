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
import com.aichatvn.agent.skills.HouseManagerSkill
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
    private val logger: Logger,
    // ✅ Provider tránh phụ thuộc vòng: HouseManagerSkillImpl nằm trong Set<Plugin> mà AgentKernel cũng cần
    private val houseManagerProvider: javax.inject.Provider<HouseManagerSkill>
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

        // ✅ MỚI: Tách prompt theo 2 nhánh dựa trên request.allowDeviceControl (ChatSkill tính từ
        // GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL — chỉ áp dụng cho khách đa kênh, chat nội bộ luôn = true):
        // - false (admin CHƯA mở điều khiển cho khách ngoài kênh): prompt TỐI GIẢN — không nhắc thiết
        //   bị/camera/nhật ký, không mở khả năng gọi tool db_search — chỉ hướng Groq bám sát QA đã
        //   huấn luyện (catalogue Chat) để trả lời đúng trọng tâm câu hỏi sản phẩm, tránh hiểu sai.
        // - true (nội bộ, hoặc khách ngoài khi admin đã mở điều khiển): prompt ĐẦY ĐỦ như cũ (ngữ cảnh
        //   nhà thông minh + khả năng tra nhật ký sự kiện qua tool), nhưng vẫn viết súc tích.
        val guard = if (request.allowDeviceControl) {
            buildFullGuard(routerFailed, outcome, maxSentences, message)
        } else {
            buildMinimalGuard(maxSentences, includeCatalogSearch = false)
        }

        

        var responseText = try {
            when (usedMode.lowercase()) {
              



                "qa" -> {
    withTimeout(15_000L) {
        val matches = search(message, username)
        val qa = matches.firstOrNull()?.qa
        // ✅ SỬA: Trước đây runLocalQAEventAnalysis() được gán vào val nên LUÔN được thực thi
        // (query DB camera/tuya/eventLog + phân tích thời gian tiếng Việt), kể cả khi qa != null
        // đã có câu trả lời khớp sẵn từ catalog. Elvis operator (?:) chỉ chọn giá trị nào được DÙNG,
        // không ngăn được việc vế phải đã bị evaluate trước đó. Đưa lời gọi vào trực tiếp vế phải
        // của ?: để nó chỉ chạy khi thật sự cần (qa == null).
        qa?.answer ?: runLocalQAEventAnalysis(message)
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

                    interceptAndExecuteToolCall(message, responsePass1, username, cleanContext, historySnapshot, request.allowDeviceControl)
                }

                else -> {
                    val matches = search(message, username)
                    val perfectMatch = matches.firstOrNull()?.qa

                    if (perfectMatch != null) {
                        perfectMatch.answer
                    } else {
                        val historySnapshot = buildHistorySnapshot(username)
                        
                        val qaContext = buildQAContextForAgent(message, username)
                        // ✅ MỚI: Chỉ nhánh COMBINED mới có khả năng search kết hợp AI+DB thật sự.
                        // Khi khách ngoại kênh đang bị khoá điều khiển (!allowDeviceControl), dùng bản
                        // guard gộp sẵn quy tắc catalog_search (1 khối quy tắc gọn, đánh số liền mạch)
                        // thay vì `guard` gốc + nối thêm khối riêng — tránh prompt dài dòng, rời rạc.
                        // Khi đã mở khoá điều khiển, giữ nguyên `guard` = buildFullGuard() đầy đủ như
                        // default_user (không cần catalog_search ở đây).
                        val effectiveGuard = if (!request.allowDeviceControl) {
                            buildMinimalGuard(maxSentences, includeCatalogSearch = true)
                        } else {
                            guard
                        }
                        val fullContext = buildString {
                            append(effectiveGuard)
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

                        interceptAndExecuteToolCall(
                            originalMessage = message,
                            responseRaw = responsePass1,
                            username = username,
                            baseContext = fullContext,
                            historySnapshot = historySnapshot,
                            allowDeviceControl = request.allowDeviceControl,
                            allowCatalogSearch = !request.allowDeviceControl
                        )
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
            val normalized = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userQuery.lowercase()) ?: ""

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
                val normName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(cam.customername.lowercase()) ?: ""
                normalized.contains(normName) || normalized.contains(cam.id.lowercase())
            }
            val matchedDev = activeDevices.find { dev ->
                val normName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(dev.name.lowercase()) ?: ""
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
    
    // ✅ SỬA: Bỏ từ khóa "nguoi" đơn lẻ — trước đây khiến hầu hết câu có chữ "người" (vd "người
    // dùng", "người quen"...) đều bị gán nhầm targetObject = "person". Đồng bộ với danh sách ở
    // ChatSkill.kt (vốn đã không có "nguoi" đơn lẻ) để hai luồng QA cục bộ và Two-Pass AI phân loại
    // object nhất quán với nhau.
    private val OBJECT_LABEL_KEYWORDS = mapOf(
        "person" to listOf("nguoi la", "co nguoi", "nguoi dot nhap", "xam nhap", "trom"),
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
        allowDeviceControl: Boolean,
        allowCatalogSearch: Boolean = false,
        toolDepth: Int = 0,
        lastSearchResultText: String? = null
    ): String {
        val toolCall = parseToolCall(responseRaw) ?: return responseRaw

        // ✅ SỬA: Trước đây khi model (vd gpt-oss-120b qua Groq) bỏ qua chỉ thị "không gọi lại tool"
        // và vẫn phát JSON lần 2, hệ thống chỉ trả về câu báo lỗi kỹ thuật chung chung, khiến khách
        // nhận 1 câu vô nghĩa dù dữ liệu tra được (có/không có QA phù hợp) đã nằm sẵn trong tay. Giờ
        // tự tổng hợp câu trả lời tự nhiên trực tiếp từ lastSearchResultText — không tốn thêm lượt gọi
        // Groq, không phụ thuộc việc model có "nghe lời" hay không.
        if (toolDepth >= 1) {
            logger.w("AgentKernel", "⚠️ [Tool Loop Guard] Model bỏ qua chỉ thị, vẫn gọi tool lần nữa (depth=$toolDepth). Tự trả lời từ dữ liệu đã tra được thay vì lặp lại.")
            return buildFallbackFromSearchResult(lastSearchResultText)
        }

        // ✅ MỚI: Tool tìm kiếm mở rộng CHỈ trong catalogue/FAQ/generic — dành riêng cho nhánh COMBINED
        // khi khách ngoại kênh đang bị khoá điều khiển thiết bị (xem buildMinimalGuard(includeCatalogSearch=true)).
        // Hoàn toàn tách biệt với db_search (nhật ký sự kiện/thiết bị) bên dưới.
        if (toolCall.tool == "catalog_search") {
            if (!allowCatalogSearch) {
                logger.w("AgentKernel", "⚠️ Chặn catalog_search: chế độ/nhánh hiện tại không được phép dùng tool này.")
                return "Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm."
            }
            return handleCatalogSearchToolCall(originalMessage, toolCall, username, baseContext, historySnapshot, allowDeviceControl, toolDepth)
        }

        // ✅ SỬA: Trước đây chặn cứng theo `username != "default_user"`, khiến khách ngoại kênh dù đã
        // được admin MỞ KHOÁ điều khiển (allowDeviceControl = true — tức chính admin đang nhắn tin từ
        // kênh đó để điều khiển/truy cập từ xa) vẫn luôn bị từ chối truy vấn nhật ký sự kiện thật.
        // Nay đồng bộ quyền db_search với đúng cờ allowDeviceControl mà ChatSkill đã tính từ
        // GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL: khoá thì chặn (kể cả nếu AI lỡ tự bịa JSON tool),
        // mở thì cho phép truy vấn thật giống hệt default_user (admin dùng app nội bộ).
        if (!allowDeviceControl) {
            logger.w("AgentKernel", "⚠️ Chặn gọi Tool db_search: điều khiển/truy cập đang bị khoá cho username=$username")
            return "Dạ, để bảo vệ quyền riêng tư của gia đình, nhật ký hoạt động camera và thiết bị hiện đang được khoá truy cập. Vui lòng liên hệ Quản trị viên để được mở quyền."
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

            // ✅ SỬA (Lực lượng Chốt Chặn Lâm Thời): Thêm chỉ thị lâm thời cực kỳ nghiêm ngặt tại đây.
            // Điều này ép AI Pass 2 bắt buộc phải nhìn nhận kết quả trống "không ghi nhận sự kiện phù hợp"
            // là câu trả lời thực tế cuối cùng, cấm tuyệt đối AI tự ý gọi lại tool db_search vô ích.
            val enrichedContext = buildString {
                append(stripDbSearchInvite(baseContext))
                append("\n\n")
                append("<SYSTEM_MEMORY>\n")
                append(searchResult.summaryText)
                append("\n\n👉 CHỈ THỊ LÂM THỜI (HỆ THỐNG ĐÃ TRUY VẤN XONG):\n")
                append("- Đây là kết quả tìm kiếm thực tế cuối cùng từ cơ sở dữ liệu SQLite.\n")
                append("- Nếu kết quả trống hoặc báo 'không ghi nhận sự kiện phù hợp / hoạt động bình thường', nghĩa là thực tế KHÔNG có sự kiện gì diễn ra. Bạn hãy trả lời trực tiếp cho người dùng là không ghi nhận sự kiện nào.\n")
                append("- TUYỆT ĐỐI KHÔNG ĐƯỢC TRẢ VỀ JSON GỌI TOOL NỮA. Hãy trả lời bằng văn bản tự nhiên Tiếng Việt ngay lập tức.\n")
                append("</SYSTEM_MEMORY>")
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
                    allowDeviceControl = allowDeviceControl,
                    allowCatalogSearch = allowCatalogSearch,
                    toolDepth = toolDepth + 1,
                    lastSearchResultText = searchResult.summaryText
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("AgentKernel", "⚠️ Gặp lỗi khi xử lý dữ liệu Two-Pass Loop, quay về Fallback lượt 1: ${e.message}", e)
            responseRaw
        }
    }

    // ✅ MỚI: Xử lý Two-Pass cho tool catalog_search — CHỈ tìm trong catalogue Hỏi-Đáp
    // (fuzzyMatchChatCatalog qua search(), category in {faq, chat, general}), KHÔNG đụng tới
    // camera/thiết bị/nhật ký sự kiện. An toàn để dùng cho khách ngoại kênh đang bị khoá điều khiển.
    private suspend fun handleCatalogSearchToolCall(
        originalMessage: String,
        toolCall: ToolCall,
        username: String,
        baseContext: String,
        historySnapshot: List<Map<String, String>>,
        allowDeviceControl: Boolean,
        toolDepth: Int
    ): String {
        val query = toolCall.params["query"]?.takeIf { it.isNotBlank() } ?: originalMessage
        logger.i("AgentKernel", "📥 [Catalog Search Intercepted] AI yêu cầu tìm thêm trong catalogue: '$query'")

        return try {
            // Ngưỡng thấp hơn buildQAContextForAgent() (0.7f) vì đây là lượt tìm mở rộng lần 2,
            // ưu tiên độ phủ hơn để tránh bỏ sót câu trả lời phù hợp trong catalogue.
            val matches = search(query, username, 0.5f)
                .sortedByDescending { it.similarity }
                .take(MAX_QA_MATCHES_IN_CONTEXT)

            val resultText = if (matches.isEmpty()) {
                "Không tìm thấy nội dung phù hợp nào trong catalogue/FAQ cho từ khoá này."
            } else {
                matches.joinToString("\n") { match ->
                    val answer = match.qa.answer.let {
                        if (it.length > MAX_QA_ANSWER_CHARS) it.take(MAX_QA_ANSWER_CHARS) + "…" else it
                    }
                    "📚 Q: ${match.qa.question}\n   A: $answer (độ tương tự: ${String.format("%.2f", match.similarity)})"
                }
            }

            val enrichedContext = buildString {
                append(stripCatalogSearchInvite(baseContext))
                append("\n\n")
                append(wrapCatalogSearchResult(resultText))
            }

            logger.i("AgentKernel", "🚀 [Catalog Search Second Call] Đang gửi lại kết quả catalogue lên Groq lượt 2 (Timeout: 15 giây)...")

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
                    allowDeviceControl = allowDeviceControl,
                    allowCatalogSearch = true,
                    toolDepth = toolDepth + 1,
                    lastSearchResultText = resultText
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("AgentKernel", "⚠️ Gặp lỗi khi xử lý catalog_search Two-Pass: ${e.message}", e)
            "Xin lỗi, hệ thống đang gặp sự cố khi tìm kiếm thông tin. Vui lòng thử lại sau."
        }
    }

    // ✅ MỚI: Tự tổng hợp câu trả lời tự nhiên từ dữ liệu ĐÃ tra được (catalog QA hoặc db_search
    // summaryText), dùng khi model bỏ qua chỉ thị "không gọi lại tool" — tránh phải trả lời khách
    // bằng câu báo lỗi kỹ thuật vô nghĩa trong khi dữ liệu thật đã nằm sẵn trong tay.
    private fun buildFallbackFromSearchResult(resultText: String?): String {
        val noInfoReply = "Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm."
        if (resultText.isNullOrBlank() || resultText.contains("Không tìm thấy nội dung phù hợp")) {
            return noInfoReply
        }
        if (resultText.contains("📚 Q:")) {
            // Định dạng catalog QA — chỉ trích phần "A:" (câu trả lời thật), bỏ câu hỏi mẫu và
            // ghi chú độ tương tự nội bộ, vì đây là câu sẽ hiển thị trực tiếp cho khách.
            val answers = Regex("A:\\s*(.+?)\\s*\\(độ tương tự").findAll(resultText)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
            return if (answers.isNotEmpty()) answers.joinToString(" ") else noInfoReply
        }
        // db_search summaryText đã là câu văn tự nhiên sẵn — dùng thẳng.
        return resultText
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
            
            when (tool) {
                "db_search" -> {
                    val timeframe = json.optString("timeframe", "today")
                    val objectLabel = json.optString("object", "all")
                    val source = json.optString("source", "").trim()
                    ToolCall(tool, buildMap {
                        put("timeframe", timeframe)
                        put("object", objectLabel)
                        if (source.isNotBlank()) put("source", source)
                    })
                }
                "catalog_search" -> {
                    val query = json.optString("query", "").trim()
                    ToolCall(tool, buildMap {
                        put("query", query)
                    })
                }
                else -> null
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
            // ✅ SỬA: Thay vì lấy 6 tin nhắn cũ nhất rồi đảo ngược lộn xộn (getMessages(username, 6).reversed()),
            // ta lấy 500 tin nhắn và chỉ trích xuất 6 tin nhắn gần đây nhất, GIỮ NGUYÊN thứ tự thời gian tăng dần (ASC).
            // Điều này giúp Groq hiểu đúng trình tự đối thoại thực tế, không bị mất mạch ngữ cảnh.
            val allMessages = database.chatMessageDao().getMessages(username, 500)

            // ✅ MỚI: ChatSkill luôn lưu tin nhắn hiện tại của user vào DB TRƯỚC KHI gọi
            // AgentKernel.chat(), nên bản ghi mới nhất ở đây chính là tin nhắn hiện tại — nó đã được
            // gửi riêng làm lượt "user" cuối cùng trong GroqClientTool.chat(). Nếu không loại trừ, nó
            // sẽ bị gửi trùng 2 lần trong cùng 1 request (1 lần trong history, 1 lần ở cuối), vừa tốn
            // token vừa dễ khiến Groq hiểu sai/lặp lại ý.
            val withoutCurrentMessage = if (allMessages.isNotEmpty() && allMessages.last().role == "user") {
                allMessages.dropLast(1)
            } else {
                allMessages
            }

            val raw = withoutCurrentMessage.takeLast(6)
            val recentCutoffIndex = raw.size - 2 // 2 lượt cuối cùng coi là "gần đây"
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

    // ✅ MỚI: Hàm dùng chung để bọc mọi kết quả tra catalogue (dù là prefetch trước lượt 1, hay
    // kết quả sau khi model chủ động gọi tool catalog_search) vào CÙNG 1 format <SYSTEM_MEMORY>
    // kèm câu "kết quả cuối cùng, không gọi lại tool". Trước đây 2 nơi build 2 format khác nhau
    // (1 nơi bọc SYSTEM_MEMORY, 1 nơi để trần) khiến model không nhận diện được là đã có kết quả
    // search rồi, dẫn tới tự ý gọi lại tool catalog_search nhiều lần trong cùng 1 lượt.
    // ✅ MỚI: Tương tự stripCatalogSearchInvite ở trên — bóc bỏ khối mời gọi tool db_search
    // (dynamicToolGuard, luôn nằm ở CUỐI chuỗi trả về của buildFullGuard()) ra khỏi guard khi build
    // context cho lượt 2, tránh cùng kiểu mâu thuẫn: rule "trả JSON nếu chưa có dữ liệu thô" vẫn còn
    // hiệu lực trong khi SYSTEM_MEMORY ở cuối đã nói "đừng gọi lại".
    private fun stripDbSearchInvite(guardText: String): String {
        val marker = "🚨 QUY TẮC TRUY VẤN DỮ LIỆU THỰC TẾ:"
        val idx = guardText.indexOf(marker)
        return if (idx == -1) guardText else guardText.substring(0, idx).trimEnd()
    }

    // ✅ MỚI: Bóc bỏ rule mời gọi tool catalog_search (rule 4) và rule "không gọi lại tool" (rule 5,
    // lúc này đã thừa vì SYSTEM_MEMORY tự nó nói rõ) ra khỏi guard khi build context cho lượt 2 trở đi.
    // Lý do: nếu để nguyên baseContext cũ rồi chỉ NỐI THÊM SYSTEM_MEMORY vào cuối, rule 4 ("nếu không
    // có Hỏi-Đáp liên quan thì trả JSON tool") vẫn còn nguyên ở đầu prompt và VẪN đúng điều kiện kích
    // hoạt khi search ra rỗng — tạo ra 2 chỉ thị mâu thuẫn trong cùng 1 system message. Model không hề
    // "lờ" SYSTEM_MEMORY, nó đang tuân thủ đúng rule 4 vẫn còn hiệu lực. Phải xoá hẳn lời mời gọi tool
    // đó đi, không chỉ thêm lời khuyên "đừng gọi lại" vào cuối.
    private fun stripCatalogSearchInvite(guardText: String): String {
        return guardText.lineSequence()
            .filterNot {
                it.contains("\"tool\":\"catalog_search\"") ||
                it.contains("Sau khi đã có kết quả tìm kiếm thì không gọi tool lần nữa")
            }
            .joinToString("\n")
    }

    private fun wrapCatalogSearchResult(resultText: String): String {
        return buildString {
            append("<SYSTEM_MEMORY>\n")
            append(resultText)
            append("\n\nKết quả tìm kiếm catalogue trên là cuối cùng. Nếu có nội dung liên quan, dùng ngay để trả lời (được phép là thông tin tổng quát). Nếu không, hãy nói chưa có thông tin chính xác và đề nghị liên hệ nhân viên hỗ trợ. Không gọi lại tool. Trả lời bằng văn bản tự nhiên.\n")
            append("</SYSTEM_MEMORY>")
        }
    }

    private suspend fun buildQAContextForAgent(message: String, username: String): String {
        val matches = search(message, username, 0.7f)
            .sortedByDescending { it.similarity }
            .take(MAX_QA_MATCHES_IN_CONTEXT)
        if (matches.isEmpty()) return ""
        val resultText = matches.joinToString("\n") { match ->
            val answer = match.qa.answer.let {
                if (it.length > MAX_QA_ANSWER_CHARS) it.take(MAX_QA_ANSWER_CHARS) + "…" else it
            }
            "📚 Q: ${match.qa.question}\n   A: $answer (độ tương tự: ${String.format("%.2f", match.similarity)})"
        }
        return wrapCatalogSearchResult(resultText)
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

    // ✅ SỬA: Gộp guard tối giản + quy tắc catalog_search (khi cần) thành MỘT khối quy tắc đánh số
    // liền mạch, ngắn gọn — thay vì 2 đoạn văn xuôi dài + 1 khối JSON rời rạc trước đây. Dùng cho
    // khách đa kênh khi admin CHƯA mở điều khiển ngoài (GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL = true).
    // includeCatalogSearch chỉ = true khi được gọi từ nhánh COMBINED lúc bị khoá; nhánh "groq" (chat
    // cho vui) luôn gọi với includeCatalogSearch = false, không có khả năng search này.
    private fun buildMinimalGuard(maxSentences: Int, includeCatalogSearch: Boolean): String {
        val rules = mutableListOf(
            "Chỉ trả lời dựa trên Hỏi-Đáp được cung cấp.",
            "Nếu có Hỏi-Đáp liên quan, hãy dùng ngay để trả lời.",
            "Nếu Hỏi-Đáp chỉ mang tính tổng quát, vẫn dùng và có thể khuyên khách liên hệ nhân viên để biết thêm chi tiết."
        )
        if (includeCatalogSearch) {
            rules += "Chỉ khi không có Hỏi-Đáp liên quan mới trả về DUY NHẤT JSON thô: {\"tool\":\"catalog_search\",\"query\":\"...\"} (chỉ tìm trong catalogue/FAQ/thông tin chung, TUYỆT ĐỐI không phải camera/thiết bị/nhật ký)."
            rules += "Sau khi đã có kết quả tìm kiếm thì không gọi tool lần nữa."
        }
        rules += "Không bịa thông tin."
        rules += "Trả lời tối đa $maxSentences câu."

        return buildString {
            append("Bạn là trợ lý tư vấn.\n\nQuy tắc:\n")
            rules.forEachIndexed { i, rule -> append("${i + 1}. $rule\n") }
        }
    }

    // ✅ Prompt đầy đủ (giữ nguyên logic cũ) dành cho chat nội bộ, hoặc khách đa kênh khi admin đã
    // mở điều khiển: có ngữ cảnh nhà thông minh (HouseManagerSkill) + khả năng gọi tool db_search để
    // tra nhật ký sự kiện thật.
    private suspend fun buildFullGuard(
        routerFailed: Boolean,
        outcome: RouterOutcome?,
        maxSentences: Int,
        message: String
    ): String {
        val antiHallucinationGuard =
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được.\n" +
            "⚠️ Trả lời NGẮN GỌN, đi thẳng vào trọng tâm — tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ."

        val dynamicToolGuard = if (mentionsAppDomain(message)) buildToolCallingGuard() else ""

        // Đọc ngữ cảnh trực quan được quy nạp từ não bộ Quản gia (HouseManagerSkill)
        val houseManagerContext = try {
            houseManagerProvider.get().buildSystemContext()
        } catch (e: Exception) {
            logger.e("AgentKernel", "Không thể lấy ngữ cảnh từ HouseManager: ${e.message}")
            ""
        }

        return buildString {
            append(antiHallucinationGuard)
            if (routerFailed && outcome is RouterOutcome.RouterFailed) {
                append("\n⚠️ Hệ thống vừa thử nhận diện đây là 1 lệnh điều khiển thiết bị nhưng KHÔNG xác định được chính xác (lý do nội bộ: ${outcome.reason}). Hãy báo cho user là lệnh CHƯA thực hiện được và hỏi họ nói rõ hơn, ĐỪNG khẳng định đã làm.")
            }
            if (houseManagerContext.isNotEmpty()) {
                append("\n\n")
                append(houseManagerContext)
            }
            append(dynamicToolGuard)
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
        val original = msg.trim()
        val norm = StringSimilarityUtil.normalizeVietnamese(original)

        fun containsWord(haystack: String, keyword: String, ignoreCase: Boolean = false): Boolean {
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex("(?<!\\p{L})${Regex.escape(keyword)}(?!\\p{L})", opts).containsMatchIn(haystack)
        }

        // ✅ SỬA: Cụm từ dài/đặc thù, ít khả năng trùng với từ khác dù bỏ dấu -> vẫn so khớp
        // trên bản chuẩn hóa (chấp nhận người dùng gõ thiếu dấu).
        val safeNormalizedKeywords = listOf(
            "camera", "canh bao", "phat hien", "nguoi la", "xam nhap",
            "dieu hoa", "thiet bi", "tin nhan", "nhan tin",
            "liet ke", "lich su", "hom qua", "may gio", "kiem tra"
        )

        // ✅ SỬA: Trước đây các từ đơn âm này (den/cua/bat/tat/quay) so khớp trên bản ĐÃ BỎ DẤU,
        // khiến các từ tiếng Việt cực kỳ phổ biến nhưng khác nghĩa hoàn toàn bị nhận nhầm là domain
        // keyword: "của" -> "cua" (trùng "cửa"), "đến" -> "den" (trùng "đèn"), "bắt đầu" -> "bat"
        // (trùng "bật"). Hậu quả: hầu hết mọi câu chat bình thường đều bị gắn thêm
        // buildToolCallingGuard() vào prompt, tốn token Groq vô ích và có nguy cơ khiến AI tự ý trả
        // JSON gọi tool db_search cho câu hỏi không liên quan.
        // -> Các từ này chỉ khác nhau ở DẤU, nên bắt buộc so khớp trên bản GIỮ NGUYÊN dấu gốc.
        // Đánh đổi: nếu người dùng gõ hoàn toàn không dấu ("tat den"), các từ này sẽ không được nhận
        // diện qua nhánh domain-keyword nữa — nhưng lệnh điều khiển thiết bị thật sự vẫn được xử lý
        // riêng ở luồng tryDeviceCommand()/routingPipeline phía trên trong chat(), không phụ thuộc
        // vào mentionsAppDomain(). Hàm này chỉ quyết định có nhúng thêm tool-calling guard hay không.
        val diacriticSensitiveKeywords = listOf("đèn", "cửa", "quạt", "bật", "tắt", "quay")

        val matchesSafe = safeNormalizedKeywords.any { kw -> containsWord(norm, kw) }
        val matchesSensitive = diacriticSensitiveKeywords.any { kw -> containsWord(original, kw, ignoreCase = true) }

        return matchesSafe || matchesSensitive
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
}