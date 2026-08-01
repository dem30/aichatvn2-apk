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
import kotlinx.coroutines.flow.first
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
    val normalizedTags: List<String>,
    // ✅ MỚI: giữ nguyên dấu tiếng Việt (chỉ lowercase) — dùng cho Tier 4 so khớp lệnh điều khiển
    // thiết bị, CHÍNH XÁC hơn hẳn bản normalizedDescription/normalizedExamples đã bỏ dấu ở trên.
    // Bỏ dấu khiến câu hỏi đào sâu không liên quan (vd "Vậy còn báo giá") dễ trùng substring với
    // mô tả/ví dụ action (sau khi bỏ dấu, nhiều từ khác nghĩa hoàn toàn lại trở thành cùng 1 chuỗi
    // ký tự) rồi bị Tier 4 hốt nhầm thành lệnh thiết bị. Không đổi 2 field cũ vì rankRelevantPlugins()
    // (Tầng 5 di sản) vẫn đang dùng bản bỏ dấu cho match rộng.
    val descriptionKeepAccent: String,
    val examplesKeepAccent: List<String>
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

// ✅ MỚI: carrier tối thiểu để interceptAndExecuteToolCall()/handleCatalogSearchToolCall()
// mang thêm imagePath (lấy từ EventLogEntity khớp nhất khi db_search trúng camera) lên tới
// ChatResponse cuối, mà không phải đổi kiểu String của cả nhánh "qa"/"groq"/"combined" trong
// chat() — chỉ 2 hàm này đổi kiểu trả về, còn responseText ở chat() vẫn là String như cũ.
private data class ToolCallOutcome(val text: String, val imagePath: String? = null)

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

        // ✅ MỚI: tăng bộ đếm lượt hỏi của user — dùng để so sánh với SearchFocus.turnIndex,
        // quyết định câu hỏi hiện tại có đang "đào sâu" vào 1 lần search DB gần đây không,
        // KHÔNG phân biệt domain (camera/tuya/đa kênh chat).
        val currentTurn = chatHistoryManager.bumpTurn(username)

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

        val baseGuard = if (request.allowDeviceControl) {
            buildFullGuard(routerFailed, outcome, maxSentences)
        } else {
            buildMinimalGuard()
        }

        // ✅ MỚI: ảnh (nếu có) tìm được từ Two-Pass db_search — gán ở nhánh "groq"/"combined"
        // bên dưới, dùng cho ChatResponse.imagePath cuối hàm. Giữ responseText là String thuần
        // cho mọi nhánh của when() như cũ, thay vì đổi cả khối sang kiểu ToolCallOutcome.
        var searchImagePath: String? = null

        var responseText = try {
            when (usedMode.lowercase()) {
                "qa" -> {
                    withTimeout(15_000L) {
                        val matches = search(message, username)
                        val qa = matches.firstOrNull()?.qa
                        qa?.answer?.let { rawAnswer ->
                            // ✅ MỚI: Dynamic Template Binding — CHỈ thay biến khi câu trả lời trong
                            // Catalog thực sự có chứa placeholder (contains("{")), không xử lý gì
                            // thêm nếu là câu tĩnh thông thường. Thuần Kotlin/SQLite, không gọi AI.
                            if (rawAnswer.contains("{")) {
                                val cameraCount = try { database.cameraDao().getActiveCameras().size } catch (e: Exception) { 0 }
                                val deviceCount = try { database.tuyaDeviceDao().getAllDevices().size } catch (e: Exception) { 0 }
                                val nowStr = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                                rawAnswer
                                    .replace("{camera_count}", cameraCount.toString())
                                    .replace("{device_count}", deviceCount.toString())
                                    .replace("{current_time}", nowStr)
                            } else {
                                rawAnswer
                            }
                        } ?: if (request.allowDeviceControl) {
                            runLocalQAEventAnalysis(message, username)
                        } else {
                            // ✅ SỬA: khách ngoại bị chặn điều khiển thiết bị tuyệt đối không được
                            // đọc WorldState/EventLog trong mode QA — trước đây hàm này chạy vô
                            // điều kiện, không hề check allowDeviceControl.
                            "Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm."
                        }
                    }
                }

                "groq" -> {
                    val historySnapshot = buildHistorySnapshot(username)

                    // ✅ SỬA: Groq mode = AI kiến thức chung THUẦN TÚY cho khách ngoại bị chặn —
                    // không còn gán CATALOG_SEARCH_TOOL_INSTRUCTION nữa (trước đây bị lẫn với
                    // Combined mode, khiến khách ngoại "chỉ chat AI" vẫn lén tra được catalog).
                    val toolGuard = if (request.allowDeviceControl) {
                        if (shouldAttachToolGuard(message, username, currentTurn)) {
                            // ✅ MỚI: lọc catalog gửi kèm theo đúng domain câu hỏi đang hỏi (camera/
                            // tuya/call/chat) — tiết kiệm token khi nhà có nhiều camera/thiết bị.
                            // Set rỗng (không xác định được domain) → buildToolCallingGuard() tự
                            // rơi về hành vi gốc, gửi đủ catalog như trước, không đoán bừa.
                            buildToolCallingGuard(resolveActiveDomains(message, username))
                        } else ""
                    } else {
                        ""
                    }

                    // ✅ SỬA: Groq mode KHÔNG còn đường nào chạm catalog QA nữa, dù admin hay
                    // khách ngoại (trước đây admin bị lén tiêm qaContext ở đây, đá chéo sang
                    // search nội bộ vốn chỉ dành cho khách ngoại). Admin → chỉ db_search qua
                    // toolGuard; khách ngoại bị chặn → AI kiến thức chung THUẦN TÚY, không có
                    // catalog dưới bất kỳ hình thức nào (thụ động lẫn qua tool).
                    val cleanContext = buildString {
                        append(baseGuard)
                        if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                        if (toolGuard.isNotEmpty()) append("\n\n$toolGuard")
                    }

                    val responsePass1 = withTimeout(15_000L) {
                        groqClient.chat(
                            message = message,
                            extraContext = cleanContext,
                            history = historySnapshot,
                            imageUrl = null
                        )
                    }

                    val outcome = interceptAndExecuteToolCall(
                        originalMessage = message,
                        responseRaw = responsePass1,
                        username = username,
                        baseContext = cleanContext,
                        historySnapshot = historySnapshot,
                        allowDeviceControl = request.allowDeviceControl,
                        // Groq mode không có đường catalog_search nào (xem comment ở trên) — luôn false.
                        allowCatalogSearch = false
                    )
                    searchImagePath = outcome.imagePath
                    outcome.text
                }

                else -> {
                    if (request.allowDeviceControl) {
                        // ✅ SỬA (theo yêu cầu): Admin (COMBINED, không bị chặn điều khiển thiết bị)
                        // KHÔNG còn pre-search catalog QA trước lượt gọi AI nữa (bỏ hẳn nhánh
                        // "matches = search(...)" / "perfectMatch" cũ — đó chính là khối "system
                        // search QA trước" nhồi sẵn kết quả mà admin không muốn nữa). Mọi tin nhắn
                        // KHÔNG phải lệnh điều khiển thiết bị (đã được tryDeviceCommand() xử lý và
                        // return sớm phía trên) đều phải đi thẳng tới AI kèm LỜI MỜI GỌI TOOL
                        // db_search — LUÔN đính kèm, không còn điều kiện qua shouldAttachToolGuard()
                        // nữa (trước đây có thể rơi vào toolGuard rỗng nếu câu hỏi không khớp từ
                        // khoá domain nào). AI tự quyết định có cần gọi db_search hay không, và nếu
                        // cần thì tự chọn đúng category phù hợp với ý định người dùng: qa (QA cục
                        // bộ/catalog chung), chat (facebook/telegram/website), call, camera, tuya —
                        // hoàn toàn tương tự tinh thần Two-Pass mà khách ngoại kênh đang dùng với
                        // catalog_search, chỉ khác tool là db_search (đã gộp luôn category=qa →
                        // catalog, xem interceptAndExecuteToolCall()).
                        val historySnapshot = buildHistorySnapshot(username)

                        // ✅ MỚI: vẫn giữ lọc catalog gửi kèm theo đúng domain câu hỏi/lịch sử gần
                        // nhất (resolveActiveDomains) để tiết kiệm token — nhưng CHÍNH KHỐI LỜI MỜI
                        // GỌI TOOL (buildToolCallingGuard) không còn bị bỏ qua trong bất kỳ trường
                        // hợp nào của nhánh admin nữa.
                        val toolGuard = buildToolCallingGuard(resolveActiveDomains(message, username))

                        val fullContext = buildString {
                            append(baseGuard)
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                            append("\n\n$toolGuard")
                        }

                        val responsePass1 = withTimeout(15_000L) {
                            groqClient.chat(
                                message = message,
                                extraContext = fullContext,
                                history = historySnapshot,
                                imageUrl = null
                            )
                        }

                        val outcome = interceptAndExecuteToolCall(
                            originalMessage = message,
                            responseRaw = responsePass1,
                            username = username,
                            baseContext = fullContext,
                            historySnapshot = historySnapshot,
                            allowDeviceControl = true,
                            // Admin không còn đường catalog_search riêng — category=qa của db_search
                            // đã tự định tuyến sang catalog (xem handler "db_search category=qa").
                            allowCatalogSearch = false
                        )
                        searchImagePath = outcome.imagePath
                        outcome.text
                    } else {
                        // ⚠️ KHÔNG ĐỘNG: giữ nguyên 100% khối Two-Pass catalog_search cho khách
                        // ngoại kênh (facebook/telegram/website bị chặn điều khiển thiết bị) —
                        // pre-search catalog + CATALOG_SEARCH_TOOL_INSTRUCTION như cũ.
                        val matches = search(message, username)
                        val perfectMatch = matches.firstOrNull()?.qa

                        if (perfectMatch != null) {
                            perfectMatch.answer
                        } else {
                            val historySnapshot = buildHistorySnapshot(username)
                            val toolGuard = CATALOG_SEARCH_TOOL_INSTRUCTION

                            val fullContext = buildString {
                                append(baseGuard)
                                if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                                append("\n\n$toolGuard")
                            }

                            val responsePass1 = withTimeout(15_000L) {
                                groqClient.chat(
                                    message = message,
                                    extraContext = fullContext,
                                    history = historySnapshot,
                                    imageUrl = null
                                )
                            }

                            val outcome = interceptAndExecuteToolCall(
                                originalMessage = message,
                                responseRaw = responsePass1,
                                username = username,
                                baseContext = fullContext,
                                historySnapshot = historySnapshot,
                                allowDeviceControl = false,
                                allowCatalogSearch = true
                            )
                            searchImagePath = outcome.imagePath
                            outcome.text
                        }
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
        return ChatResponse(responseText, usedMode, usedPluginId, searchImagePath)
    }

    private suspend fun runLocalQAEventAnalysis(userQuery: String, username: String): String = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val normalized = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userQuery.lowercase()) ?: ""

            val parsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalized, now)
            val since = parsedRange?.since ?: (now - 24 * 60 * 60 * 1000L)
            val until = parsedRange?.until ?: now

            // ✅ MỚI: MINH BẠCH FALLBACK — cùng tinh thần nhãn "(mặc định 3 ngày)" mà
            // TimeRangeResolver.resolve() (đường AI) đã làm khi không nhận diện được timeframe.
            // Trước đây khi VietnameseTimeRangeParser.parse() trả null (không tìm thấy cụm thời
            // gian nào trong câu — có thể do câu không nói rõ, hoặc do cách diễn đạt nằm ngoài các
            // dạng parser này hỗ trợ), nhãn vẫn ghi cứng "hôm nay" — giống hệt như khi user CHỦ Ý
            // hỏi về hôm nay, khiến câu trả lời trông như đã hiểu đúng ý trong khi thực chất chỉ là
            // đoán/mặc định 24 giờ qua. Sửa: phân biệt rõ 2 trường hợp bằng nhãn khác nhau.
            val label: String = if (parsedRange != null) {
                parsedRange.label
            } else {
                "24 giờ gần nhất (không xác định được mốc thời gian cụ thể, dùng mặc định)"
            }

            val isQuantity = QUANTITY_KEYWORDS.any { normalized.contains(it) }
            val isYesNo = normalized.contains("co ") && (normalized.contains("khong") || normalized.contains("phai khong") || normalized.contains("chua"))
            
            val questionType = when {
                isQuantity -> QuestionType.QUANTITY
                isYesNo -> QuestionType.YES_NO
                else -> QuestionType.OTHER
            }

            val aggregation = if (isQuantity) AggregationType.COUNT else AggregationType.NONE

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

            // ✅ MỚI: đọc từ khoá từ AppConfig (cùng 4 key GLOBAL_CAMERA_KEYWORDS/
            // GLOBAL_DEVICE_KEYWORDS/GLOBAL_CHAT_KEYWORDS/GLOBAL_CALL_KEYWORDS đã dùng ở
            // mentionsAppDomain()) thay vì hardcode riêng 1 bộ khác ở đây — trước đây hàm này có
            // bộ từ khoá THỨ HAI, lệch hẳn với mentionsAppDomain() (thiếu "call" hoàn toàn, các
            // từ camera/tuya/chat cũng không đồng bộ khi user tự sửa qua Settings).
            // Dùng lại VietnameseTextNormalizer (không phải StringSimilarityUtil.normalizeVietnamese
            // như bên AgentKernel.getXKeywordsNormalized()) để khớp đúng cách "normalized" của
            // CHÍNH hàm này đã được build ở trên — tránh trộn 2 bộ chuẩn hoá khác nhau.
            suspend fun readKeywordsNormalized(key: String, fallback: List<String>): List<String> {
                return try {
                    configProvider.getString(key, AppConfigDefaults.defaultOf(key))
                        .split(",")
                        .map { com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(it.trim().lowercase()) ?: "" }
                        .filter { it.isNotBlank() }
                } catch (e: Exception) { fallback }
            }

            val cameraKeywords = readKeywordsNormalized(AppConfigDefaults.GLOBAL_CAMERA_KEYWORDS, listOf("camera"))
            val deviceKeywords = readKeywordsNormalized(AppConfigDefaults.GLOBAL_DEVICE_KEYWORDS, listOf("thiet bi"))
            val chatKeywords = readKeywordsNormalized(AppConfigDefaults.GLOBAL_CHAT_KEYWORDS, listOf("tin nhan"))
            val callKeywordsLocal = readKeywordsNormalized(AppConfigDefaults.GLOBAL_CALL_KEYWORDS, listOf("cuoc goi"))

            when {
                matchedCam != null -> { sourceCategory = "camera"; sourceName = matchedCam.customername.ifBlank { matchedCam.id } }
                matchedDev != null -> { sourceCategory = "tuya"; sourceName = matchedDev.name.ifBlank { matchedDev.id } }
                cameraKeywords.any { normalized.contains(it) } -> sourceCategory = "camera"
                deviceKeywords.any { normalized.contains(it) } -> sourceCategory = "tuya"
                // ✅ MỚI: trước đây KHÔNG có nhánh nào gán sourceCategory = "call" trong hàm này —
                // câu hỏi cuộc gọi ở QA mode (khi miss catalog) sẽ rơi vào nhánh chung (không lọc
                // nguồn "call"), khác hẳn hành vi của luồng Groq/db_search.
                callKeywordsLocal.any { normalized.contains(it) } -> sourceCategory = "call"
                normalized.contains("facebook") || normalized.contains("fb") -> sourceCategory = "facebook"
                normalized.contains("telegram") -> sourceCategory = "telegram"
                normalized.contains("website") || normalized.contains("web") -> sourceCategory = "website"
                chatKeywords.any { normalized.contains(it) } -> sourceCategory = "chat"
            }

            // ✅ MỚI: FOLLOW-UP FALLBACK — cùng tinh thần resolveActiveDomains()/SearchFocus ở
            // đường Groq 2-pass. Câu hỏi đào sâu kiểu "họ mặc áo màu gì" không chứa từ khoá
            // camera/tuya/chat/call nào → sourceCategory ở trên vẫn null. Trước đây QA mode local
            // không có đường fallback này nên luôn search KHÔNG lọc domain, dễ trả nhầm nguồn khi
            // có nhiều camera/thiết bị. Chỉ fallback khi câu hỏi hiện tại KHÔNG tự nêu domain nào
            // (giữ đúng nguyên tắc "gợi ý lượt này luôn thắng gợi ý xuyên lượt").
            if (sourceCategory == null) {
                chatHistoryManager.getLastSearchFocus(username)?.let { focus ->
                    sourceCategory = when (focus.sourceCategory) {
                        "camera" -> "camera"
                        "tuya" -> "tuya"
                        "call" -> "call"
                        "facebook", "telegram", "website", "chat" -> focus.sourceCategory
                        else -> null
                    }
                    if (sourceCategory != null && sourceName == null) {
                        sourceName = focus.sourceIdOrName
                    }
                }
            }

            // ✅ MỚI: "keyword"/detailsKeywords — cùng khoảng cách log đã lộ ra ở đường Groq
            // 2-pass (interceptAndExecuteToolCall): trước đây hàm local này chỉ lọc theo
            // category/timeframe/object thô, không lọc theo NỘI DUNG câu hỏi (vd "nói cần đặt
            // hàng"). Không có model AI để tự tóm tắt keyword ngắn gọn ở đây như bên đường Groq —
            // nên KHÔNG thể dùng thẳng nguyên cả câu hỏi làm keyword: log.summary/last_message
            // không chứa nguyên văn câu hỏi (kèm cả từ nghi vấn, mốc thời gian, tên domain...),
            // contains() trên cả câu gần như luôn fail → lọc rỗng kết quả đúng đắn thay vì cải
            // thiện gì (vd log thực tế: "tuần trước camera có ghi nhận được con bò nào không" —
            // log.summary thật chỉ ghi "phát hiện: Một con bò lông dài màu nâu...", không chứa
            // nguyên cụm "có ghi nhận được ... nào không").
            //
            // Sửa: (1) cắt bỏ khỏi câu gốc (giữ dấu — vì log.summary tiếng Việt có dấu) các cụm
            // điều khiển ĐÃ nhận diện được ở trên (nhãn thời gian đã parse, tên domain/thiết
            // bị/camera đã match); (2) sau đó lọc thêm STOPWORD_KEYWORDS (từ hỏi/hư từ không mang
            // nội dung: "có", "nào", "không", "được", "ghi nhận"...) khỏi từng từ còn lại, giữ
            // DANH SÁCH TỪ (không phải 1 cụm dài) để nơi match có thể so khớp TỪNG TỪ với
            // log.summary thay vì đòi khớp nguyên cả cụm. Nếu không còn từ nào sau khi lọc — bỏ
            // hẳn keyword, coi như không xác định được, tránh lọc rỗng nhầm.
            // 🐛 SỬA LỖI THỰC TẾ (câu hỏi "hôm qua có ai nhắn nội dung báo giá không" luôn ra rỗng
            // dù log.summary CÓ chứa đúng "báo giá" — xem ChatSkill.recordIncomingChatEvent(), mỗi
            // tin nhắn được ghi thành 1 dòng EventLogEntity riêng với nội dung thật nhúng thẳng vào
            // summary, nên storage KHÔNG phải nguyên nhân): comment ở trên đã ghi rõ ý định "giữ
            // dấu — vì log.summary tiếng Việt có dấu", NHƯNG dòng code ngay dưới lại gọi
            // VietnameseTextNormalizer.normalize() trên `stripped` TRƯỚC KHI tách từ — nghĩa là
            // keywordParam cuối cùng luôn bị strip dấu ("báo giá" → "bao","gia"), trong khi
            // log.summary/last_message mà nó đem so ở DatabaseSearchHelper (contains(), không
            // strip dấu) vẫn giữ nguyên dấu ("báo giá") → "báo giá".contains("bao") luôn = false,
            // lọc rỗng 100% các từ khoá có dấu (gần như mọi nội dung tiếng Việt thật). Sửa: tách từ
            // từ CHÍNH `stripped` (còn dấu), chỉ dùng bản normalize (không dấu) để QUYẾT ĐỊNH từ
            // nào là stopword/quá ngắn — giữ nguyên từ CÓ DẤU trong danh sách keyword cuối cùng.
            val keywordParam: List<String> = if (!isQuantity && !isYesNo) {
                var stripped = userQuery
                parsedRange?.label?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                sourceName?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                matchedCam?.customername?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                matchedDev?.name?.let { stripped = stripped.replace(it, "", ignoreCase = true) }

                stripped
                    .split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { word ->
                        val normWord = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(word.lowercase()) ?: word.lowercase()
                        normWord.length > 1 && normWord !in STOPWORD_KEYWORDS
                    }
            } else {
                emptyList()
            }

            val deviceState = when {
                normalized.contains("bat") || normalized.contains("mo") -> "true"
                normalized.contains("tat") -> "false"
                else -> null
            }

            // ✅ MỚI: cùng Keyword Guard đã áp ở interceptAndExecuteToolCall() — category=call +
            // granularity=summary đọc thẳng callLogDao.observeRecent() theo mốc thời gian, bỏ qua
            // hoàn toàn biến `filtered` mà detailsKeywords lọc trên đó. Không ép granularity ở đây
            // (hàm local này không có tham số granularity riêng như schema AI), nhưng path
            // "call" đã return sớm summaryText bên dưới (bộ sinh câu riêng của
            // DatabaseSearchHelper.isCallCategory) nên không bị ảnh hưởng bởi keyword dù có hay
            // không — giữ nguyên hành vi cũ cho call, chỉ log cảnh báo nếu có keyword bị bỏ qua.
            if (keywordParam.isNotEmpty() && sourceCategory == "call") {
                logger.w("AgentKernel", "⚠️ [Keyword Guard] QA local: category=call + keyword — nhánh call dùng bộ sinh câu riêng, keyword không áp dụng ở đây.")
            }

            // ✅ MỚI: bỏ hẳn enum object cứng (extractObjectLabel/OBJECT_LABEL_KEYWORDS) — bộ 6
            // nhóm cũ (person/car/motorbike/dog/cat/package) thiếu rất nhiều đối tượng thực tế
            // (vd "con bò" trong log thật ở trên), không có đường mở rộng động qua AppConfig như
            // các bộ từ khoá domain khác. targetObject giờ luôn null ở QA local — không còn lọc
            // "cứng" theo loại đối tượng, để hẳn keywordParam (danh sách từ, bao gồm cả tên đối
            // tượng như "bò") lo việc lọc nội dung, đúng vai trò mà nó đã đảm nhiệm cho các domain
            // khác (chat/facebook/...).
            val contract = SearchContract(
                questionType = questionType,
                sinceMs = since,
                untilMs = until,
                timeframeLabel = label,
                sourceCategory = sourceCategory,
                sourceIdOrName = sourceName ?: matchedCam?.id ?: matchedDev?.id,
                targetObject = null,
                deviceState = deviceState,
                aggregation = aggregation,
                detailsKeywords = keywordParam
            )

            // ✅ MỚI: cùng KEYWORD_MATCH_LIMIT đã dùng ở đường Groq 2-pass — kết quả đã lọc theo
            // nội dung thì gần chính xác, không cần trả nhiều dòng về bộ sinh câu Kotlin thuần.
            val searchResult = if (keywordParam.isNotEmpty()) {
                databaseSearchHelper.executeSearchContract(contract, limit = KEYWORD_MATCH_LIMIT)
            } else {
                databaseSearchHelper.executeSearchContract(contract)
            }

            // ✅ MỚI: ghi lại SearchFocus sau search thành công — cùng cơ chế đã áp cho đường Groq
            // 2-pass, để câu hỏi đào sâu TIẾP THEO (kể cả khi lại rơi vào QA mode local) cũng có
            // domain để fallback, không chỉ có tác dụng một chiều từ Groq mode sang QA mode.
            if (sourceCategory != null) {
                chatHistoryManager.setLastSearchFocus(username, sourceCategory, sourceName ?: matchedCam?.id ?: matchedDev?.id)
            }

            // ✅ MỚI: "call" đã có bộ sinh câu RIÊNG trong DatabaseSearchHelper.isCallCategory
            // (thống kê đi/đến/nhỡ chính xác qua countMissedCalls()) — không viết lại bộ sinh câu
            // chung ở đây cho call, tránh trùng lặp logic và mất số liệu chi tiết đã có sẵn.
            if (sourceCategory == "call") {
                return@withContext searchResult.summaryText
            }

            val total = searchResult.totalCount
            val logs = searchResult.logs

            val targetName = sourceName ?: when (sourceCategory) {
                "camera" -> "camera"
                "tuya" -> "thiết bị"
                "chat" -> "tin nhắn"
                "facebook" -> "kênh Facebook"
                "telegram" -> "kênh Telegram"
                "website" -> "kênh Website"
                else -> "hệ thống"
            }
            // ✅ MỚI: giữ lại 2 tín hiệu deviceState/targetObject đã tính ở trên (contract dùng
            // chúng để LỌC dữ liệu) — nhắc lại trong câu trả lời để Admin biết đúng điều kiện đã
            // lọc, tránh câu trả lời tự nhiên nhưng mơ hồ ("có 3 sự kiện" nhưng không rõ đang lọc
            // theo trạng thái bật/tắt hay đối tượng nào).
            val stateNote = when (deviceState) {
                "true" -> " (trạng thái: bật/mở)"
                "false" -> " (trạng thái: tắt)"
                else -> ""
            }
            val objectNote = contract.targetObject?.let { " (đối tượng: $it)" } ?: ""

            // 🔊 BỘ PHÁT SINH VĂN BẢN TIẾNG VIỆT TỰ NHIÊN THUẦN KOTLIN (0 AI) — thay cho việc trả
            // thẳng summaryText dạng "--- Nhật ký hoạt động tổng hợp ---" (đọc như log thô hơn là
            // câu trả lời trực tiếp cho Admin).
            when {
                isYesNo -> {
                    if (total > 0) {
                        val latest = logs.lastOrNull()?.summary?.takeIf { it.isNotBlank() }
                        val latestNote = if (latest != null) " (Gần nhất: $latest)" else ""
                        "CÓ. Trong $label ghi nhận $total sự kiện liên quan đến $targetName$stateNote$objectNote.$latestNote"
                    } else {
                        "KHÔNG. Trong $label không ghi nhận sự kiện nào từ $targetName$stateNote$objectNote."
                    }
                }
                isQuantity -> {
                    "Trong $label, $targetName ghi nhận tổng cộng $total lần hoạt động$stateNote$objectNote."
                }
                total > 0 -> {
                    val details = logs.takeLast(3).joinToString("\n• ") { log ->
                        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))
                        "[$timeStr] ${log.summary}"
                    }
                    "Trong $label, ghi nhận $total hoạt động của $targetName:\n• $details"
                }
                else -> "Trong $label, $targetName hoạt động bình thường, không có bản ghi mới."
            }
        } catch (e: Exception) {
            logger.e("AgentKernel", "Lỗi phân tích sự kiện cục bộ trong QA Mode: ${e.message}", e)
            "Không thể phân tích dữ liệu cục bộ lúc này."
        }
    }

    private val QUANTITY_KEYWORDS = setOf("bao nhieu", "may lan", "so luong", "tong cong", "duoc may", "dem")

    // ⚠️ KHÔNG CÒN ĐƯỢC GỌI từ runLocalQAEventAnalysis() (đã bỏ enum object cứng — xem comment ở
    // đó). Giữ lại nguyên trạng ở đây (không xoá) vì không chắc còn nơi khác trong repo thật gọi
    // tới 2 khai báo này ngoài phạm vi file trích này — Vinh tự xác nhận rồi xoá nếu đúng là dead
    // code, tránh xoá nhầm nếu có call site khác chưa thấy được trong 1 file này.
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

    // ✅ MỚI: dùng trong keywordParam của runLocalQAEventAnalysis() — lọc bỏ từ hỏi/hư từ tiếng
    // Việt không mang nội dung thật (đã qua VietnameseTextNormalizer nên không dấu), để phần còn
    // lại là các từ mang nghĩa (tên vật thể, hành động...) dùng match-theo-từ với log.summary.
    // Danh sách không đầy đủ tuyệt đối — bổ sung dần khi phát hiện case fail mới, giống cách
    // GLOBAL_CAMERA_KEYWORDS... được tinh chỉnh qua từng lần debug thực tế.
    private val STOPWORD_KEYWORDS = setOf(
        "co", "khong", "nao", "duoc", "da", "se", "dang", "la", "va", "hay", "hoac",
        "the", "thi", "sao", "gi", "ai", "may", "cho", "voi", "cua", "tu", "den",
        "ghi", "nhan", "kiem", "tra", "xem", "toi", "biet",
        "camera", "thiet", "bi", "tin", "nhan", "cuoc", "goi"
    )

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
    ): ToolCallOutcome {
        val toolCall = parseToolCall(responseRaw) ?: return ToolCallOutcome(responseRaw)

        if (toolDepth >= 1) {
            logger.w("AgentKernel", "⚠️ [Tool Loop Guard] Model bỏ qua chỉ thị, vẫn gọi tool lần nữa (depth=$toolDepth). Tự trả lời từ dữ liệu đã tra được thay vì lặp lại.")
            return ToolCallOutcome(buildFallbackFromSearchResult(lastSearchResultText))
        }

        if (toolCall.tool == "catalog_search") {
            if (!allowCatalogSearch) {
                logger.w("AgentKernel", "⚠️ Chặn catalog_search: chế độ/nhánh hiện tại không được phép dùng tool này.")
                return ToolCallOutcome("Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm.")
            }
            return handleCatalogSearchToolCall(originalMessage, toolCall, username, baseContext, historySnapshot, allowDeviceControl, toolDepth)
        }

        if (!allowDeviceControl) {
            logger.w("AgentKernel", "⚠️ Chặn gọi Tool db_search: điều khiển/truy cập đang bị khoá cho username=$username")
            return ToolCallOutcome("Dạ, để bảo vệ quyền riêng tư của gia đình, nhật ký hoạt động camera và thiết bị hiện đang được khoá truy cập. Vui lòng liên hệ Quản trị viên để được mở quyền.")
        }

        // ✅ MỚI: category="qa" là QA database (catalog/FAQ/generic) đặt ĐỒNG CẤP với camera/
        // tuya/call/chat ngay trong schema db_search — admin chỉ cần quản lý 1 đường search duy
        // nhất, không cần tool catalog_search riêng nữa. Định tuyến sang đúng luồng search()
        // (fuzzyMatchChatCatalog qua handleCatalogSearchToolCall) thay vì DatabaseSearchHelper.
        val requestedCategory = toolCall.params["category"]?.trim()?.lowercase()
        if (toolCall.tool == "db_search" && requestedCategory == "qa") {
            val qaQuery = toolCall.params["target"]?.trim()?.takeIf { it.isNotBlank() } ?: originalMessage
            logger.i("AgentKernel", "📥 [Two-Pass Intercepted] db_search category=qa → định tuyến sang catalog: '$qaQuery'")
            return handleCatalogSearchToolCall(
                originalMessage = originalMessage,
                toolCall = ToolCall("catalog_search", mapOf("query" to qaQuery)),
                username = username,
                baseContext = baseContext,
                historySnapshot = historySnapshot,
                allowDeviceControl = allowDeviceControl,
                toolDepth = toolDepth
            )
        }

        logger.i("AgentKernel", "📥 [Two-Pass Intercepted] Phát hiện AI yêu cầu gọi Tool: '${toolCall.tool}' (tham số: ${toolCall.params})")

        return try {
            val rawTimeframe = toolCall.params["timeframe"] ?: "today"
            val objectLabel = toolCall.params["object"] ?: "all"
            // ✅ MỚI: schema mới ưu tiên "category" (enum cố định: camera/tuya/call/facebook/
            // telegram/website) + "target" (tên cụ thể) — model chỉ cần chọn đúng 1 trong 6 giá
            // trị thay vì tự diễn giải cả cụm string tự do như "source" cũ. Validate với enum
            // hợp lệ: nếu model trả category rác (không nằm trong 6 giá trị), coi như không có,
            // rơi xuống toàn bộ chuỗi fallback cũ bên dưới (sourceHint/SearchFocus/keyword) —
            // không vỡ hành vi cũ, chỉ THÊM một đường đi tắt chính xác hơn khi model tuân theo
            // đúng schema mới.
            val validCategories = setOf("camera", "tuya", "call", "facebook", "telegram", "website", "chat")
            val directCategory = toolCall.params["category"]?.trim()?.lowercase()?.takeIf { it in validCategories }
            val directTarget = toolCall.params["target"]?.trim()?.takeIf { it.isNotBlank() }

            // ✅ ĐÃ SỬA: nếu model không nêu rõ "source"/"category" (câu hỏi đào sâu kiểu "camera có ai qua lại
            // không" / "họ mặc áo màu gì" mà không nói tên nguồn nào), ưu tiên fallback về
            // SearchFocus.sourceIdOrName — được ghi lại sau MỌI lần executeSearchContract() thành
            // công, không phân biệt domain (camera/tuya/facebook/telegram/website), nên phủ đúng cả
            // hội thoại đa kênh chat (vốn luôn đi qua nhánh db_search, KHÔNG đi qua device intent).
            // Trước đây fallback duy nhất là getLastMentionedDevice — chỉ được IntentExecutor cập
            // nhật khi có lệnh ĐIỀU KHIỂN camera/thiết bị Tuya — nên hội thoại chat (chỉ hỏi-đáp,
            // không điều khiển thiết bị) không bao giờ có sourceHint, bị đối xử kém hơn camera/device.
            // Giữ getLastMentionedDevice làm fallback cuối cho trường hợp vừa điều khiển thiết bị
            // nhưng chưa search lần nào trong phiên (SearchFocus chưa có gì để cho).
            // 🐛 SỬA LỖI NGHIÊM TRỌNG (phát hiện từ log thực tế: câu hỏi "có tin nhắn nào nói
            // đặt hàng" bị hijack thành search camera): trước đây sourceHint LUÔN rơi xuống
            // chatHistoryManager.getLastSearchFocus(username)?.sourceIdOrName (leftover từ lượt
            // search TRƯỚC, có thể thuộc domain khác hẳn) ngay cả khi model đã tự chọn ĐÚNG
            // "category" ở lượt này (vd "chat"). Nếu leftover đó tình cờ khớp tên 1 camera/thiết
            // bị thật (vd "vinh"), khối matchedCamera/matchedDevice bên dưới sẽ GHI ĐÈ
            // resolvedSourceCategory từ "chat" thành "camera" — khiến search chạy sai domain, và
            // vì search sai lại tự ghi SearchFocus mới = camera, lỗi này tự lặp lại vô hạn ở mọi
            // lượt hỏi "chat" sau đó, không bao giờ tìm thấy tin nhắn thật.
            // Sửa: tách riêng "gợi ý của CHÍNH lượt này" (target/source model vừa cho) khỏi "gợi ý
            // xuyên lượt" (SearchFocus/lastMentionedDevice, có thể thuộc lượt search cũ khác domain
            // hẳn) — chỉ cho phép gợi ý xuyên lượt GHI ĐÈ category khi model KHÔNG tự cho category ở
            // lượt này (giữ đúng tính năng "đào sâu" cũ) hoặc khi domain trùng khớp sẵn với category
            // model chọn (không có gì thật sự bị ghi đè). Nếu gợi ý chỉ là leftover xuyên lượt VÀ
            // model đã chọn 1 category khác domain hẳn — bỏ qua gợi ý cũ, tin category của model.
            val currentTurnHint = directTarget
                ?: toolCall.params["source"]?.trim()?.takeIf { it.isNotBlank() }
            val staleFallbackHint = chatHistoryManager.getLastSearchFocus(username)?.sourceIdOrName
                ?: chatHistoryManager.getLastMentionedDevice(username)
            val sourceHint = currentTurnHint ?: staleFallbackHint

            var resolvedSourceCategory: String? = directCategory
            var resolvedSourceName: String? = null
            if (!sourceHint.isNullOrBlank()) {
                val normHint = StringSimilarityUtil.normalizeVietnamese(sourceHint.lowercase())
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

                // ✅ MỚI: nhận diện sourceHint thuộc miền "cuộc gọi" bằng đúng danh sách từ khoá
                // GLOBAL_CALL_KEYWORDS (config, không hardcode) — trước đây khối when() dưới đây
                // không có nhánh nào cho "call", nên dù model trả về source="cuộc gọi", nó bị bỏ
                // qua, resolvedSourceCategory ở lại null và SearchContract rơi vào nhánh chung
                // (else) của executeSearchContract() thay vì nhánh isCallCategory đã viết sẵn.
                val isCallHint = getCallKeywordsNormalized().any { kw -> normHint.contains(kw) }

                // matchedCamera/matchedDevice tra được từ target/sourceHint được ưu tiên hơn
                // directCategory — NHƯNG chỉ khi gợi ý đến từ chính lượt này (currentTurnHint), hoặc
                // model không tự cho category (đào sâu xuyên lượt), hoặc gợi ý cũ đó vốn đã cùng
                // domain với category model chọn (không có gì để "ghi đè" thật sự). Nếu gợi ý chỉ là
                // leftover xuyên lượt VÀ model đã chọn 1 category khác domain hẳn — bỏ qua gợi ý cũ,
                // tin category của model (fix cho bug đã tìm thấy trong log thực tế).
                val staleHintOnly = currentTurnHint == null
                val allowCrossDomainOverride = !staleHintOnly || directCategory == null
                when {
                    matchedCamera != null && (allowCrossDomainOverride || directCategory == "camera") -> {
                        resolvedSourceCategory = "camera"; resolvedSourceName = matchedCamera.customername.ifBlank { matchedCamera.id }
                    }
                    matchedDevice != null && (allowCrossDomainOverride || directCategory == "tuya") -> {
                        resolvedSourceCategory = "tuya"; resolvedSourceName = matchedDevice.name.ifBlank { matchedDevice.id }
                    }
                    isCallHint && resolvedSourceCategory == null -> resolvedSourceCategory = "call"
                    resolvedSourceCategory == null && (normHint.contains("facebook") || normHint.contains("fb")) -> resolvedSourceCategory = "facebook"
                    resolvedSourceCategory == null && normHint.contains("telegram") -> resolvedSourceCategory = "telegram"
                    resolvedSourceCategory == null && (normHint.contains("website") || normHint.contains("web")) -> resolvedSourceCategory = "website"
                }
            }

            // ✅ MỚI: model không phải lúc nào cũng điền "source"/"target" (schema tool db_search mô tả
            // field này là "tên camera/thiết bị" nên với câu hỏi cuộc gọi model hay để trống).
            // Nếu sourceHint không giúp resolve ra được gì, vẫn kiểm tra thẳng câu hỏi gốc của
            // user — cùng danh sách GLOBAL_CALL_KEYWORDS — trước khi để lọt vào nhánh chung.
            if (resolvedSourceCategory == null) {
                val normOriginal = StringSimilarityUtil.normalizeVietnamese(originalMessage.lowercase())
                if (getCallKeywordsNormalized().any { kw -> normOriginal.contains(kw) }) {
                    resolvedSourceCategory = "call"
                }
            }

            // ✅ MỚI: tương tự fallback "call" ở trên — trước đây KHÔNG có fallback nào cho kênh
            // chat (facebook/telegram/website), dù buildToolCallingGuard() giờ đã mô tả field
            // "source" nên nhận các giá trị này. Nếu model vẫn để trống/điền sai, dò thẳng câu
            // hỏi gốc như đã làm cho call, để không rơi vào nhánh chung (mất lọc theo kênh).
            if (resolvedSourceCategory == null) {
                val normOriginal = StringSimilarityUtil.normalizeVietnamese(originalMessage.lowercase())
                when {
                    normOriginal.contains("facebook") || normOriginal.contains(" fb ") || normOriginal.endsWith("fb") -> resolvedSourceCategory = "facebook"
                    normOriginal.contains("telegram") -> resolvedSourceCategory = "telegram"
                    normOriginal.contains("website") || normOriginal.contains("trang web") -> resolvedSourceCategory = "website"
                }
            }

            // ✅ MỚI (bổ sung theo yêu cầu): fallback keyword cho camera/tuya — đối xứng với
            // fallback "call"/chat ở trên. Trước đây KHÔNG có nhánh nào dò lại câu hỏi gốc cho
            // camera/thiết bị khi model trả category/source sai (vd "all" hoặc nhầm sang kênh
            // chat) — đây chính là nguyên nhân log thực tế: câu "Có thiết bị nào bật hôm qua
            // không" nhưng model trả source="all"/"telegram", khiến SearchContract rơi vào nhánh
            // chung thay vì lọc đúng theo tuya. Dùng lại đúng GLOBAL_CAMERA_KEYWORDS/
            // GLOBAL_DEVICE_KEYWORDS (config, không hardcode) — cùng nguồn đã dùng ở
            // mentionsAppDomain() và nhánh QA Mode phía trên — để nhất quán trong toàn bộ file.
            if (resolvedSourceCategory == null || resolvedSourceCategory == "all") {
                val normOriginal = StringSimilarityUtil.normalizeVietnamese(originalMessage.lowercase())
                val cameraKeywordsHere = try {
                    configProvider.getString(AppConfigDefaults.GLOBAL_CAMERA_KEYWORDS, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CAMERA_KEYWORDS))
                        .split(",").map { StringSimilarityUtil.normalizeVietnamese(it.trim().lowercase()) }.filter { it.isNotBlank() }
                } catch (e: Exception) { listOf("camera") }
                val deviceKeywordsHere = try {
                    configProvider.getString(AppConfigDefaults.GLOBAL_DEVICE_KEYWORDS, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_DEVICE_KEYWORDS))
                        .split(",").map { StringSimilarityUtil.normalizeVietnamese(it.trim().lowercase()) }.filter { it.isNotBlank() }
                } catch (e: Exception) { listOf("thiet bi") }

                when {
                    cameraKeywordsHere.any { normOriginal.contains(it) } -> resolvedSourceCategory = "camera"
                    deviceKeywordsHere.any { normOriginal.contains(it) } -> resolvedSourceCategory = "tuya"
                }
            }

            // ✅ MỚI: ĐỒNG BỘ VỚI QA LOCAL (runLocalQAEventAnalysis) — trước đây đường AI/Groq chỉ
            // biết 4 giá trị enum thô mà model tự phải "dịch" câu hỏi sang
            // (today|yesterday|last_3_days|last_7_days), rồi TimeRangeResolver.resolve() cũng chỉ
            // hiểu đúng 4 giá trị đó (mọi thứ khác rơi vào nhánh else "mặc định lùi lại 3 ngày").
            // Điều đó khiến đường AI KHÔNG THỂ xử lý "hôm kia", "tuần trước", "tháng trước", "3
            // tiếng trước", "5 ngày trước", ngày tuyệt đối ("15/5", "ngày 20 tháng 3")... trong khi
            // đường QA local (VietnameseTimeRangeParser) đã hỗ trợ đầy đủ các dạng này từ lâu.
            // Sửa: parse THẲNG câu hỏi gốc bằng đúng VietnameseTimeRangeParser mà QA local dùng —
            // nếu tìm thấy cụm thời gian tường minh, dùng luôn (khớp 100% hành vi QA local). Chỉ
            // khi câu hỏi KHÔNG chứa cụm thời gian nào parser nhận ra (vd câu hỏi đào sâu không
            // nhắc lại mốc thời gian, hoặc diễn đạt ngoài phạm vi parser) thì mới rơi về enum thô
            // AI tự phân loại (rawTimeframe) qua TimeRangeResolver.resolve() như cũ — giữ nguyên
            // vai trò "lưới an toàn" của đường cũ, không bỏ đi.
            val nowMs = System.currentTimeMillis()
            val normalizedForTime = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(originalMessage.lowercase()) ?: originalMessage.lowercase()
            val vnParsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalizedForTime, nowMs)

            var timeRange = if (vnParsedRange != null) {
                com.aichatvn.agent.utils.TimeRange(vnParsedRange.since, vnParsedRange.until, vnParsedRange.label)
            } else {
                timeRangeResolver.resolve(rawTimeframe)
            }

            // ✅ SỬA: Cost Guard trước đây chỉ chặn dựa trên ENUM thô (rawTimeframe in
            // last_3_days/last_7_days) — nên bị "lọt lưới" ngay khi timeRange thực tế đến từ
            // VietnameseTimeRangeParser ở trên (vd "tháng trước"/"7 ngày trước" có thể dài đúng
            // bằng hoặc hơn last_7_days nhưng không mang tên enum đó). Chuyển điều kiện chặn sang
            // đúng ĐỘ DÀI khoảng thời gian đã resolve (>24h), áp dụng nhất quán cho cả 2 nguồn.
            if (objectLabel == "all" && resolvedSourceCategory == null && (timeRange.until - timeRange.since) > 24 * 60 * 60 * 1000L) {
                logger.w("AgentKernel", "⚠️ [Cost Guard] Chặn combo tốn kém object=all + khoảng '${timeRange.label}' (không có source thu hẹp), ép về 'today'")
                timeRange = timeRangeResolver.resolve("today")
            }

            // ✅ MỚI: "keyword" (chuỗi tự do model chép từ câu hỏi user) → detailsKeywords.
            // Trước đây field này tồn tại sẵn trong SearchContract và ĐÃ được
            // DatabaseSearchHelper.executeSearchContract() lọc đúng (log.summary.contains(...)),
            // nhưng không có đường nào gán giá trị từ toolCall.params — nên luôn rỗng, không lọc
            // được nội dung. Giữ nguyên 1 phần tử duy nhất (không tách từ) vì contains() đã khớp cả cụm dài.
            val keywordParam = toolCall.params["keyword"]?.trim()?.takeIf { it.isNotBlank() }

            // ✅ MỚI: ĐỒNG BỘ VỚI QA LOCAL — QA local đã bỏ hẳn enum object cứng
            // (extractObjectLabel/OBJECT_LABEL_KEYWORDS, xem comment ở runLocalQAEventAnalysis)
            // vì objectAliasResolver.matches() chỉ khớp đúng 6 giá trị enum (person/car/motorbike/
            // dog/cat/package) — bất kỳ vật thể nào ngoài 6 giá trị đó (vd "con bò") đều bị model ép
            // vào 1 trong 6 giá trị SAI hoặc "all", rồi filter cứng theo giá trị sai đó xoá sạch kết
            // quả, dù log.summary có chứa đúng từ "bò". Trong khi QA local chỉ cần dò bằng từ khoá
            // nội dung là ra ngay. Sửa: đường AI KHÔNG còn dùng "object" để lọc cứng nữa (xem
            // targetObject = null bên dưới) — nếu model đã điền "keyword" thì dùng thẳng; nếu model
            // CHỈ điền "object" (quên điền keyword) thì suy ra 1 từ khoá tiếng Việt tương ứng từ
            // đúng 6 giá trị enum để vẫn lọc được qua CÙNG một cơ chế so khớp nội dung như mọi
            // trường hợp khác — không còn đường lọc cứng nào riêng cho object nữa.
            val objectFallbackKeyword = mapOf(
                "person" to "người", "car" to "ô tô", "motorbike" to "xe máy",
                "dog" to "chó", "cat" to "mèo", "package" to "gói hàng"
            )[objectLabel.lowercase()]

            // ✅ MỚI (bug thực tế: "hôm qua có ai nhắn nội dung báo giá không" luôn ra rỗng):
            // schema mô tả "target" là "tên thiết bị/camera... hoặc từ khóa nếu qa" — KHÔNG hề nói
            // gì về việc dùng target để tìm NỘI DUNG tin nhắn cho category=chat/facebook/telegram/
            // website, nên model hay điền nhầm nội dung câu hỏi ("báo giá") vào target thay vì
            // keyword. currentTurnHint/sourceHint sau đó bị gán thẳng vào contract.sourceIdOrName —
            // filter này ở DatabaseSearchHelper CHỈ so khớp trên sourceId/senderId/device_name
            // (KHÔNG so trên log.value["last_message"] — nội dung tin nhắn thật), nên 1 hint là nội
            // dung câu hỏi như "báo giá" không bao giờ khớp được → filtered rỗng NGAY TỪ bước lọc
            // sourceIdOrName, trước cả khi kịp chạy tới bước lọc keyword/last_message bên dưới.
            // Kết quả: câu trả lời chỉ còn dựa được vào thống kê tin chưa đọc/tin nhắn cuối cùng đã
            // có sẵn trong log gần nhất, đúng như hiện tượng quan sát được.
            // Sửa: với domain chat/facebook/telegram/website, sourceIdOrName CHỈ được áp dụng khi
            // hint đó đã khớp được với 1 tên/kênh THẬT SỰ đã biết (resolvedSourceName != null, vd
            // model tự nêu đúng "facebook"/"telegram"/"website"). Nếu hint chưa khớp được gì (rất
            // có thể là nội dung câu hỏi bị điền nhầm chỗ) — không dùng nó để lọc cứng theo tên
            // nữa, mà coi nó như 1 từ khoá nội dung bổ sung, đi qua đúng con đường
            // detailsKeywords/last_message đã có sẵn — cùng triết lý "tìm kiếm chủ yếu trên từ
            // khoá" đã áp cho object ở trên.
            val isChatDomain = resolvedSourceCategory in setOf("chat", "facebook", "telegram", "website")
            val currentTurnOrStaleHint = if (currentTurnHint != null || directCategory == null) sourceHint else null
            val finalSourceIdOrName = resolvedSourceName ?: (if (isChatDomain) null else currentTurnOrStaleHint)
            val unresolvedHintAsKeyword = if (isChatDomain && resolvedSourceName == null) {
                currentTurnOrStaleHint?.takeIf { it.isNotBlank() }
            } else null

            val effectiveKeyword = keywordParam ?: objectFallbackKeyword ?: unresolvedHintAsKeyword

            // ✅ MỚI: nhánh isCallCategory + granularity="summary" trong DatabaseSearchHelper đọc
            // THẲNG call_logs thô theo mốc thời gian (xem comment ở đó) — hoàn toàn KHÔNG đi qua
            // biến `filtered` đã áp detailsKeywords, nên nếu để nguyên granularity="summary" thì
            // keyword sẽ bị lờ đi hoàn toàn dù model đã điền đúng. Ép về "detail" khi có keyword
            // để filter theo nội dung thực sự có tác dụng — cùng tinh thần Cost Guard phía trên.
            val resolvedGranularity = if (effectiveKeyword != null && resolvedSourceCategory == "call") {
                logger.w("AgentKernel", "⚠️ [Keyword Guard] category=call + keyword được yêu cầu → ép granularity=detail (nhánh summary bỏ qua keyword filter)")
                "detail"
            } else {
                toolCall.params["granularity"] ?: "detail"
            }

            val contract = SearchContract(
                questionType = QuestionType.OTHER,
                sinceMs = timeRange.since,
                untilMs = timeRange.until,
                timeframeLabel = timeRange.label,
                sourceCategory = resolvedSourceCategory,
                // 🐛 Cùng fix ở trên: nếu gợi ý chỉ đến từ leftover xuyên lượt (không phải chính lượt
                // này) VÀ model đã chọn category khác domain với gợi ý đó (bị chặn ghi đè ở khối
                // trên), thì KHÔNG dùng gợi ý cũ đó làm sourceIdOrName nữa — nếu không, category dù
                // đã đúng ("chat") nhưng vẫn bị lọc nhầm theo tên cũ ("vinh"), tiếp tục trả về rỗng.
                // ✅ Cùng nguyên tắc trên: với domain chat, chỉ dùng finalSourceIdOrName khi đã thật
                // sự khớp tên/kênh — hint chưa khớp được gì đã chuyển sang effectiveKeyword ở trên.
                sourceIdOrName = finalSourceIdOrName,
                // ✅ SỬA: không còn lọc cứng theo "object" nữa (xem comment objectFallbackKeyword ở
                // trên) — khớp đúng nguyên tắc "tìm kiếm chủ yếu trên từ khoá" mà QA local đã dùng.
                targetObject = null,
                aggregation = if (originalMessage.contains("mấy lần") || originalMessage.contains("bao nhiêu")) AggregationType.COUNT else AggregationType.NONE,
                granularity = resolvedGranularity,
                detailsKeywords = effectiveKeyword?.let { listOf(it) } ?: emptyList()
            )

            val searchResult = if (effectiveKeyword != null) {
                // ✅ MỚI: khi đã có "keyword" lọc theo nội dung, kết quả còn lại đã gần chính
                // xác (đúng ý user, không phải liệt kê chung chung theo thời gian nữa) — không
                // cần trả 20 dòng mặc định về AI, chỉ cần top vài dòng khớp nhất để tiết kiệm
                // token. Vẫn dùng đúng executeSearchContract() sẵn có, chỉ giảm limit.
                databaseSearchHelper.executeSearchContract(contract, limit = KEYWORD_MATCH_LIMIT)
            } else {
                databaseSearchHelper.executeSearchContract(contract)
            }

            // ✅ MỚI: ghi lại trọng tâm search vừa thực hiện (bất kể domain camera/tuya/chat),
            // để các câu hỏi đào sâu tiếp theo (vd "họ mặc áo màu gì") tự động được coi là
            // liên quan mà không cần khớp từ khoá cứng — xem shouldAttachToolGuard().
            chatHistoryManager.setLastSearchFocus(username, resolvedSourceCategory, finalSourceIdOrName ?: sourceHint)

            // ✅ SỬA: dọn sạch cả đoạn mời gọi Tool lẫn khối <SYSTEM_MEMORY> cũ thụ động của Pass 1
            val cleanBaseContext = stripOldSystemMemory(stripDbSearchInvite(baseContext))

            val enrichedContext = buildString {
                append(cleanBaseContext)
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

            // ✅ MỚI: lấy ảnh của dòng log KHỚP GẦN NHẤT trong kết quả search vừa chạy (logs đã
            // sort tăng dần theo timestamp ở executeSearchContract(), nên phần tử cuối là mới
            // nhất) — chỉ log camera có gắn imagePath (EventLogEntity.imagePath, xem CameraSkill.
            // saveAlertToHistory()); tuya/call/chat luôn null nên tự động không có ảnh đính kèm.
            val bestImagePath = searchResult.logs.lastOrNull { it.imagePath != null }?.imagePath

            withTimeout(15_000L) {
                val responsePass2 = groqClient.chat(
                    message = originalMessage,
                    extraContext = enrichedContext,
                    history = historySnapshot,
                    imageUrl = null
                )
                
                val outcome = interceptAndExecuteToolCall(
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
                // ✅ MỚI: ưu tiên ảnh do lượt gọi sâu hơn (nếu có) tự tìm được; nếu không, dùng
                // ảnh từ chính lượt search này — trường hợp thường gặp nhất vì pass 2 thường trả
                // thẳng văn bản (không gọi tool nữa) nên outcome.imagePath ở đó luôn null.
                outcome.copy(imagePath = outcome.imagePath ?: bestImagePath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("AgentKernel", "⚠️ Gặp lỗi khi xử lý dữ liệu Two-Pass Loop, quay về Fallback lượt 1: ${e.message}", e)
            ToolCallOutcome(responseRaw)
        }
    }

    private suspend fun handleCatalogSearchToolCall(
        originalMessage: String,
        toolCall: ToolCall,
        username: String,
        baseContext: String,
        historySnapshot: List<Map<String, String>>,
        allowDeviceControl: Boolean,
        toolDepth: Int
    ): ToolCallOutcome {
        val query = toolCall.params["query"]?.takeIf { it.isNotBlank() } ?: originalMessage
        logger.i("AgentKernel", "📥 [Catalog Search Intercepted] AI yêu cầu tìm thêm trong catalogue: '$query'")

        return try {
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

            // ✅ SỬA BUG (điểm cắt sai ở nhánh admin): hàm này được gọi từ 2 nguồn khác nhau —
            // (1) model gọi thẳng tool "catalog_search" (chat khách, baseContext chứa
            // CATALOG_SEARCH_TOOL_INSTRUCTION), và (2) model gọi "db_search(category=qa)" ở chat
            // admin, được interceptAndExecuteToolCall() định tuyến sang đây (baseContext chứa
            // buildToolCallingGuard() — hướng dẫn db_search, KHÔNG phải catalog_search). Trước đây
            // chỉ gọi stripCatalogSearchInvite() — đúng cho nguồn (1) nhưng SAI marker với nguồn
            // (2), khiến toàn bộ khối "🚨 LUÔN gọi db_search..." + schema JSON + danh sách camera/
            // thiết bị bị gửi lại NGUYÊN VẸN vào Pass 2 (xem log thực tế: model vẫn gọi lại tool dù
            // đã có <SYSTEM_MEMORY> bảo không gọi nữa). Giờ dọn cả 2 loại hướng dẫn (hàm nào không
            // khớp marker sẽ tự no-op, không hại nhau) + dọn luôn <SYSTEM_MEMORY> cũ của Pass 1.
            val cleanBaseContext = stripOldSystemMemory(
                stripCatalogSearchInvite(stripDbSearchInvite(baseContext))
            )

            val enrichedContext = buildString {
                append(cleanBaseContext)
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
            ToolCallOutcome("Xin lỗi, hệ thống đang gặp sự cố khi tìm kiếm thông tin. Vui lòng thử lại sau.")
        }
    }

    private fun buildFallbackFromSearchResult(resultText: String?): String {
        val noInfoReply = "Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm."
        if (resultText.isNullOrBlank() || resultText.contains("Không tìm thấy nội dung phù hợp")) {
            return noInfoReply
        }
        if (resultText.contains("📚 Q:")) {
            val answers = Regex("A:\\s*(.+?)\\s*\\(độ tương tự").findAll(resultText)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
            return if (answers.isNotEmpty()) answers.joinToString(" ") else noInfoReply
        }
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
                    // ✅ MỚI: schema JSON đổi từ 1 field "source" tự do (gộp chung tên thiết bị /
                    // literal "cuộc gọi" / tên kênh chat, khiến model phải tự đoán ngữ nghĩa và hay
                    // trả sai — xem log thực tế: câu hỏi về thiết bị Tuya nhưng model trả
                    // source="all" hoặc source="telegram") sang 2 field tách biệt:
                    // "category" (enum cố định camera/tuya/call/facebook/telegram/website) và
                    // "target" (tên cụ thể, chỉ cần khi category=camera/tuya). Model chỉ cần chọn
                    // đúng 1 trong 6 giá trị enum thay vì tự do diễn giải cả cụm string.
                    // Vẫn đọc "source" (schema cũ) làm fallback để không vỡ nếu model/cache prompt
                    // cũ còn trả theo format cũ trong lúc chuyển đổi.
                    val category = json.optString("category", "").trim()
                    val target = json.optString("target", "").trim()
                    val legacySource = json.optString("source", "").trim()
                    // ✅ MỚI: "summary | detail" — model tự yêu cầu mức độ chi tiết cần thiết cho
                    // câu hỏi (xem buildToolCallingGuard()). Validate với 2 giá trị hợp lệ; giá trị
                    // rác/thiếu -> mặc định "detail" giống hành vi cũ, không vỡ gì với model/cache
                    // prompt cũ chưa biết field này.
                    val granularity = json.optString("granularity", "detail").trim().lowercase()
                        .takeIf { it == "summary" } ?: "detail"
                    // ✅ MỚI: "keyword" — chuỗi tự do để lọc theo NỘI DUNG log (log.summary),
                    // đưa thẳng vào SearchContract.detailsKeywords ở interceptAndExecuteToolCall().
                    // Không tách từ, không chuẩn hoá gì thêm ở đây — DatabaseSearchHelper đã dùng
                    // contains(ignoreCase=true) nên khớp cả cụm dài nguyên văn.
                    val keyword = json.optString("keyword", "").trim()
                    ToolCall(tool, buildMap {
                        put("timeframe", timeframe)
                        put("object", objectLabel)
                        if (category.isNotBlank()) put("category", category)
                        if (target.isNotBlank()) put("target", target)
                        if (legacySource.isNotBlank()) put("source", legacySource)
                        put("granularity", granularity)
                        if (keyword.isNotBlank()) put("keyword", keyword)
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
            val allMessages = database.chatMessageDao().getMessages(username, 500)

            val withoutCurrentMessage = if (allMessages.isNotEmpty() && allMessages.last().role == "user") {
                allMessages.dropLast(1)
            } else {
                allMessages
            }

            val raw = withoutCurrentMessage.takeLast(6)
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

        // ✅ MỚI: khi db_search có "keyword" (đã lọc gần chính xác theo nội dung log), chỉ trả
        // về top N dòng khớp gần nhất về thời gian cho AI thay vì mặc định 20 dòng — tương tự
        // tinh thần MAX_QA_MATCHES_IN_CONTEXT bên trên cho catalog_search.
        private const val KEYWORD_MATCH_LIMIT = 5

        // Số lượt hỏi tiếp theo sau 1 lần search DB thật mà vẫn được coi là "đang đào sâu"
        // vào cùng kết quả đó, dù câu hỏi không chứa từ khoá domain nào (camera/tuya/chat...).
        private const val FOLLOWUP_TURN_WINDOW = 2

        // ✅ MỚI: marker cố định (không phải câu chữ tiếng Việt tự nhiên) đặt NGAY ĐẦU 2 khối
        // hướng dẫn gọi tool bên dưới — để stripDbSearchInvite()/stripCatalogSearchInvite() không
        // bao giờ lệch theo các lần đổi wording sau này (đã hỏng 2 lần vì neo theo câu chữ, xem
        // comment cũ ở stripDbSearchInvite()). Model không hiểu marker này (không phải JSON/lệnh)
        // nên vô hại nếu lỡ sót lại trong context.
        private const val DB_SEARCH_GUARD_MARKER = "<!--DB_SEARCH_GUARD-->"
        private const val CATALOG_SEARCH_GUARD_MARKER = "<!--CATALOG_SEARCH_GUARD-->"

        private const val CATALOG_SEARCH_TOOL_INSTRUCTION = CATALOG_SEARCH_GUARD_MARKER + """🚨 TÌM KIẾM THÔNG TIN (catalog_search):
- Thiếu info trong <SYSTEM_MEMORY> để trả lời → trả DUY NHẤT 1 JSON thô (không markdown, không giải thích): {"tool": "catalog_search", "query": "từ khóa tìm kiếm"}
- Đã đủ info từ <SYSTEM_MEMORY> → trả lời tự nhiên bằng Tiếng Việt, KHÔNG gọi lại tool."""
    }

    private fun stripDbSearchInvite(guardText: String): String {
        // ✅ SỬA BUG (lần 2): marker cũ neo theo câu chữ "🚨 db_search" đã LẠI không khớp sau 1
        // lần đổi wording khác của buildToolCallingGuard() (giờ câu mở đầu là "🚨 LUÔN gọi
        // db_search trước khi trả lời..."), khiến hàm này im lặng trả nguyên guardText không cắt
        // gì — toàn bộ khối hướng dẫn gọi tool (schema JSON + rule + danh sách camera/thiết bị)
        // vẫn bị gửi lại NGUYÊN VẸN ở Pass 2 (xem log thực tế 19:19:38 01/08: model vẫn thấy chỉ
        // thị "LUÔN gọi db_search" nên gọi tool lần nữa dù <SYSTEM_MEMORY> đã bảo "Không gọi lại
        // tool"). Giờ neo theo DB_SEARCH_GUARD_MARKER (chuỗi cố định, không phải câu chữ tiếng
        // Việt) được chèn ngay đầu buildToolCallingGuard() — các lần đổi wording sau này không
        // còn làm marker lệch được nữa.
        val idx = guardText.indexOf(DB_SEARCH_GUARD_MARKER)
        return if (idx == -1) guardText else guardText.substring(0, idx).trimEnd()
    }

    // ✅ MỚI: stripDbSearchInvite() chỉ cắt phần toolGuard (nằm SAU marker), còn extraContext
    // (chứa <SYSTEM_MEMORY> thụ động từ ChatSkill.buildMemoryContext(), được ghép vào cleanContext
    // TRƯỚC marker) vẫn còn nguyên. Nếu không dọn thêm ở đây, enrichedContext của Pass 2 sẽ có 2
    // khối <SYSTEM_MEMORY> chồng nhau (1 cũ thụ động từ Pass 1 + 1 mới vừa db_search xong), khiến
    // model dễ lẫn lộn dữ liệu cũ/mới. Regex non-greedy + String.replace (thay hết mọi match).
    private fun stripOldSystemMemory(guardText: String): String {
        return guardText.replace(Regex("<SYSTEM_MEMORY>[\\s\\S]*?</SYSTEM_MEMORY>"), "").trim()
    }

    private fun stripCatalogSearchInvite(guardText: String): String {
        val idx = guardText.indexOf(CATALOG_SEARCH_GUARD_MARKER)
        return if (idx == -1) guardText else guardText.substring(0, idx).trimEnd()
    }

    private fun wrapCatalogSearchResult(resultText: String): String {
        return buildString {
            append("<SYSTEM_MEMORY>\n")
            append(resultText)
            append("\n\nKết quả tìm kiếm catalogue trên là cuối cùng. Nếu có nội dung liên quan, dùng ngay để trả lời (được phép là thông tin tổng quát). Nếu không, hãy nói chưa có thông tin chính xác và đề nghị liên hệ nhân viên hỗ trợ. Không gọi lại tool. Trả lời bằng văn bản tự nhiên.\n")
            append("</SYSTEM_MEMORY>")
        }
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

    private suspend fun buildMinimalGuard(): String {
        val configuredPrompt = configProvider.getString(
            AppConfigDefaults.GLOBAL_CHAT_SYSTEM_PROMPT,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CHAT_SYSTEM_PROMPT)
        )
        // ✅ MỚI: khách ngoại kênh là nhóm dễ hỏi "đặt hàng/hẹn lịch/thanh toán" nhất sau khi hỏi
        // sản phẩm — nhưng hệ thống thực tế CHỈ ghi nhận tin nhắn (đánh dấu chưa đọc) để nhân
        // viên tự xem và phản hồi sau, không hề tự động xử lý/xác nhận/hẹn lịch gì cả. Không có
        // dòng này, model dễ tự bịa ra khả năng "xử lý giúp", "đã đặt lịch", hứa hẹn thời gian cụ
        // thể mà hệ thống không làm được — gây hiểu lầm cho khách hàng thật.
        val overreachGuard = "⚠️ Khi khách muốn đặt hàng/hẹn lịch/thanh toán: hệ thống KHÔNG tự xử lý được — chỉ trả lời rằng tin nhắn đã được ghi nhận và nhân viên sẽ xem, phản hồi sớm nhất có thể. Không tự nhận đã xử lý/xác nhận đơn hàng/lịch hẹn, không hứa hẹn thời gian phản hồi cụ thể."

        return if (configuredPrompt.isNotBlank()) "$configuredPrompt\n\n$overreachGuard" else overreachGuard
    }

    private suspend fun buildFullGuard(
        routerFailed: Boolean,
        outcome: RouterOutcome?,
        maxSentences: Int
    ): String {
        // ✅ SỬA (theo log thực tế): bản trước gộp "2 nguồn trả lời" + "KHÔNG xử lý đơn hàng..."
        // vào 1 câu dài — model chỉ bắt được phần phủ định (KHÔNG có khả năng...), tự suy diễn
        // câu hỏi kiểu "quần jeans"/"sản phẩm" thuộc nhóm bị từ chối, KHÔNG hề thử gọi
        // db_search(category=qa) trước khi kết luận "không có dữ liệu". Tách lại thành 2 rule
        // riêng: (1) mệnh lệnh dứt khoát PHẢI thử tool trước khi từ chối — đặt lên đầu, không
        // pha lẫn câu phủ định nào; (2) giới hạn hẹp CHỈ về hành động (đơn hàng/thanh toán/hẹn
        // lịch), không đụng đến phạm vi trả lời thông tin.
        val antiHallucinationGuard =
            "⚠️ Không tự nhận đã điều khiển thiết bị thật (bật/tắt/mở/đóng/đặt lịch...) — " +
            "nếu chưa chắc đã thực hiện, hỏi lại rõ hơn hoặc báo chưa làm được.\n" +
            "⚠️ Trả lời tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ.\n" +
            "⚠️ Câu hỏi cần dữ liệu thật (camera/thiết bị/tin nhắn/cuộc gọi) HOẶC thông tin chung chưa chắc (sản phẩm/giá/chính sách/FAQ...) → LUÔN gọi db_search trước. TUYỆT ĐỐI không tự kết luận \"không có dữ liệu\"/\"không hỗ trợ\" khi CHƯA gọi tool.\n" +
            "⚠️ Hệ thống KHÔNG tự xử lý đơn hàng/thanh toán/hẹn lịch hay thực hiện hành động thay khách — chỉ tra cứu thông tin và ghi nhận yêu cầu để nhân viên xử lý."


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
        }
    }

    // ✅ MỚI: activeDomains rỗng = hành vi GỐC HỆT như trước (gửi đủ catalog camera+thiết bị+
    // danh bạ+kênh chat, không lọc gì cả) — an toàn cho mọi chỗ gọi cũ nếu lỡ quên truyền tham
    // số. Chỉ khi activeDomains có nội dung (đã xác định rõ domain qua resolveActiveDomains())
    // mới lọc bớt phần catalog KHÔNG liên quan, để giảm token cho nhà nhiều camera/thiết bị.
    // Phần schema JSON + 4 rule bắt buộc bên dưới giữ NGUYÊN 100% — model vẫn phải biết đủ tất
    // cả category hợp lệ dù đang trong ngữ cảnh 1 domain, vì câu hỏi có thể đổi domain giữa
    // chừng ngay lượt kế tiếp.
    private suspend fun buildToolCallingGuard(activeDomains: Set<String> = emptySet()): String {
        val includeAll = activeDomains.isEmpty()

        val cameraNames = if (includeAll || "camera" in activeDomains) {
            try {
                database.cameraDao().getActiveCameras().map {
                    if (it.customername.isNotBlank()) "${it.customername} (ID: ${it.id})" else "ID: ${it.id}"
                }
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        val deviceNames = if (includeAll || "tuya" in activeDomains) {
            try {
                database.tuyaDeviceDao().getAllDevices().map {
                    if (it.name.isNotBlank()) "${it.name} (ID: ${it.id})" else "ID: ${it.id}"
                }
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        // ✅ MỚI: danh bạ cuộc gọi THẬT (call_contacts) — trước đây "call" là nguồn DUY NHẤT
        // không có danh sách hiển thị nào trong prompt, trong khi camera/tuya đều liệt kê tên+ID
        // thật từ DB. Lưu ý: câu hỏi về cuộc gọi vẫn hợp lệ ngay cả với số lạ chưa lưu (rule 2 bên
        // dưới không đổi) — danh sách này chỉ để model biết TÊN các liên hệ đã lưu, giúp trả lời
        // tự nhiên hơn khi user hỏi kiểu "gọi cho Tuấn xem" thay vì chỉ thấy mã máy thô.
        val callContactNames = if (includeAll || "call" in activeDomains) {
            try {
                database.callContactDao().observeAll().first().map {
                    if (it.displayName.isNotBlank()) "${it.displayName} (mã máy: ${it.deviceCode})" else "mã máy: ${it.deviceCode}"
                }
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        // ✅ MỚI: lấy vài từ khoá mẫu từ GLOBAL_CALL_KEYWORDS (config) để gợi ý cho model biết
        // "source" còn có thể là "cuộc gọi" — trước đây field này chỉ mô tả "tên camera/thiết
        // bị" nên model không có cách nào biết mà yêu cầu tra lịch sử cuộc gọi qua tool này.
        val callKeywordSample = if (includeAll || "call" in activeDomains) {
            try {
                configProvider.getString(
                    AppConfigDefaults.GLOBAL_CALL_KEYWORDS,
                    AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CALL_KEYWORDS)
                ).split(",").map { it.trim() }.filter { it.isNotBlank() }.take(3)
            } catch (e: Exception) { listOf("cuộc gọi") }
        } else emptyList()

        // ✅ MỚI: kênh chat THẬT đang cấu hình (trước đây hardcode cứng "facebook, telegram,
        // website" bất kể đã cấu hình hay chưa — trong khi camera/tuya luôn đọc DB thật). Dùng
        // đúng điều kiện mà WebhookGatewayService dùng để coi 1 kênh là "đã bật":
        // facebook -> có ít nhất 1 page đã đăng ký; telegram -> có bot token; website -> có
        // gateway URL + token.
        val enabledChatChannels = if (includeAll || "chat" in activeDomains) {
            try {
                buildList {
                    if (database.facebookPageDao().getAllPages().isNotEmpty()) add("facebook")
                    if (configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim().isNotBlank()) add("telegram")
                    val gwUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                    val gwToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                    if (gwUrl.isNotBlank() && gwToken.isNotBlank()) add("website")
                }
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        // ✅ MỚI: chỉ hiển thị dòng "💬 Chat:" khi domain chat thực sự liên quan — trước đây dòng
        // này LUÔN xuất hiện (kể cả khi hỏi thuần về camera), tốn token không cần thiết.
        val showChatLine = includeAll || "chat" in activeDomains

        // ✅ SỬA (theo log thực tế — model trả lời "không có dữ liệu sản phẩm" mà CHƯA từng gọi
        // tool 1 lần nào): câu mở đầu cũ "khi cần data thật (camera/thiết bị/chat/call) hoặc câu
        // hỏi qa chưa có info" liệt kê camera/thiết bị/chat/call TRƯỚC, qa chỉ nhắc lướt cuối câu
        // — model đọc xong phần đầu đã hiểu tool này "chủ yếu để tra log", không liên tưởng câu
        // hỏi kiểu "quần jeans"/"giá bao nhiêu" cũng phải gọi tool. Sửa: đưa mệnh lệnh "LUÔN gọi
        // trước khi từ chối" lên đầu tuyệt đối, và đưa category=qa lên bullet ĐẦU TIÊN (thay vì
        // nằm giữa danh sách) vì đây chính là category hay bị bỏ sót nhất trong thực tế.
        return DB_SEARCH_GUARD_MARKER +
            "🚨 LUÔN gọi db_search trước khi trả lời \"không có\"/\"không hỗ trợ\" — kể cả câu hỏi chung/sản phẩm/giá/chính sách (category=qa), không chỉ riêng camera/thiết bị/chat/call:\n" +
            "{\"tool\":\"db_search\",\"timeframe\":\"copy cụm thời gian trong câu hỏi (vd 'hôm qua','hôm kia','3 tiếng trước','5 ngày trước','tuần trước','tháng trước','ngày 15/5'...), hoặc today|yesterday|last_3_days|last_7_days nếu câu hỏi ko nêu mốc thời gian\",\"object\":\"person|car|motorbike|dog|cat|package|all\",\"category\":\"camera|tuya|call|facebook|telegram|website|chat|qa\",\"target\":\"tên đúng theo danh sách dưới, hoặc từ khóa nếu qa\",\"keyword\":\"BẮT BUỘC ở mọi category — từ khoá/chi tiết quan trọng nhất trong câu hỏi\",\"granularity\":\"summary|detail\"}\n" +
            "- category=qa: MẶC ĐỊNH cho câu hỏi chung/sản phẩm/giá/chính sách/kiến thức đã train — kể cả khi chưa chắc có data, cứ gọi trước. category=chat: hỏi tin nhắn nhưng ko rõ kênh. Ko rõ nguồn → bỏ field category.\n" +
            "- keyword bắt buộc mọi category: thiếu = ko lọc được nội dung, kết quả ko chính xác. Luôn copy sát từ/cụm quan trọng nhất trong câu hỏi.\n" +
            "- Camera/vật thể/màu sắc/có-không → luôn db_search trước, ko tự suy diễn.\n" +
            "- timeframe: hệ thống hiểu chi tiết (hôm kia, X tiếng/ngày/tháng trước, ngày cụ thể...) — copy nguyên cụm trong câu hỏi; enum 4 giá trị chỉ dùng khi câu hỏi ko nêu mốc thời gian.\n" +
            "- object=1 trong 6 giá trị hoặc \"all\" nếu ko khớp; ko ép.\n" +
            "- chat/facebook/telegram/website: target=tên kênh only; nội dung tin nhắn luôn vào keyword, ko vào target.\n" +
            "- Thiết bị ko có trong danh sách dưới → trả lời ko lắp đặt, ko gọi tool. (call/chat/qa luôn hợp lệ)\n" +
            "- Chưa có data thật lượt này → ko bịa nội dung/giờ/chi tiết. Hỏi đào sâu (vd \"tin nhắn nói gì\") mà chưa có data → db_search lại.\n" +
            (if (cameraNames.isNotEmpty()) "📷 Camera: ${cameraNames.joinToString(", ")}\n" else "") +
            (if (deviceNames.isNotEmpty()) "🔌 Thiết bị: ${deviceNames.joinToString(", ")}\n" else "") +
            (if (callContactNames.isNotEmpty()) "📞 Danh bạ: ${callContactNames.joinToString(", ")}\n" else "") +
            (if (showChatLine) "💬 Chat: " + (if (enabledChatChannels.isNotEmpty()) enabledChatChannels.joinToString(", ") else "chưa cấu hình") else "") +
            (if (callKeywordSample.isNotEmpty()) "\n📞 category=call còn được hỏi qua các từ khác \"cuộc gọi\": ${callKeywordSample.joinToString("/")}" else "")
    }
    /**
     * ✅ MỚI: Quyết định có đính kèm buildToolCallingGuard() hay không, KHÔNG chỉ dựa vào
     * từ khoá cứng của câu hỏi hiện tại (mentionsAppDomain) mà còn dựa vào việc câu hỏi này
     * có đang đào sâu vào 1 lần search DB thật vừa xảy ra hay không — bất kể domain là gì
     * (camera, tuya, hay đa kênh chat facebook/telegram/website). mentionsAppDomain vẫn được
     * giữ làm fast-path cho câu hỏi MỞ ĐẦU 1 chủ đề mới.
     */
    private suspend fun shouldAttachToolGuard(message: String, username: String, currentTurn: Int): Boolean {
        if (mentionsAppDomain(message)) return true
        val focus = chatHistoryManager.getLastSearchFocus(username) ?: return false
        val turnsSince = currentTurn - focus.turnIndex
        return turnsSince in 0..FOLLOWUP_TURN_WINDOW
    }

    // ✅ MỚI: từ khoá miền "cuộc gọi" đọc từ AppConfig (GLOBAL_CALL_KEYWORDS) thay vì hardcode,
    // để user tự thêm/sửa qua Settings mà không cần sửa code/build lại app. Dùng chung cho cả
    // mentionsAppDomain() (quyết định có gắn tool guard hay không) và sourceHint resolve bên
    // interceptAndExecuteToolCall() (route đúng sourceCategory="call"), tránh 2 nơi lệch nhau.
    private suspend fun getCallKeywordsNormalized(): List<String> {
        val raw = configProvider.getString(
            AppConfigDefaults.GLOBAL_CALL_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CALL_KEYWORDS)
        )
        return raw.split(",")
            .map { StringSimilarityUtil.normalizeVietnamese(it.trim()) }
            .filter { it.isNotBlank() }
    }

    // ✅ MỚI: cùng khuôn getCallKeywordsNormalized() ở trên — camera và chat so khớp trên văn
    // bản ĐÃ chuẩn hoá bỏ dấu (không nhạy dấu), giống cách "call" đang làm.
    private suspend fun getCameraKeywordsNormalized(): List<String> {
        val raw = configProvider.getString(
            AppConfigDefaults.GLOBAL_CAMERA_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CAMERA_KEYWORDS)
        )
        return raw.split(",")
            .map { StringSimilarityUtil.normalizeVietnamese(it.trim()) }
            .filter { it.isNotBlank() }
    }

    private suspend fun getChatKeywordsNormalized(): List<String> {
        val raw = configProvider.getString(
            AppConfigDefaults.GLOBAL_CHAT_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CHAT_KEYWORDS)
        )
        return raw.split(",")
            .map { StringSimilarityUtil.normalizeVietnamese(it.trim()) }
            .filter { it.isNotBlank() }
    }

    // ✅ MỚI: thiết bị (đèn/cửa/quạt/bật/tắt...) VẪN giữ nguyên hành vi cũ — so khớp trên văn bản
    // GỐC CÒN DẤU (không qua normalizeVietnamese), vì bỏ dấu dễ gây nhầm giữa các từ gần giống
    // nhau (vd "đèn" ~ "đến" sau khi bỏ dấu đều thành "den"). Đây là lý do trước đây
    // diacriticSensitiveKeywords được tách riêng khỏi safeNormalizedKeywords — giữ nguyên khi
    // chuyển sang đọc từ AppConfig, không gộp chung cách so khớp với camera/chat/call.
    private suspend fun getDeviceKeywordsOriginal(): List<String> {
        val raw = configProvider.getString(
            AppConfigDefaults.GLOBAL_DEVICE_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_DEVICE_KEYWORDS)
        )
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private suspend fun mentionsAppDomain(msg: String): Boolean {
        val original = msg.trim()
        val norm = StringSimilarityUtil.normalizeVietnamese(original)

        fun containsWord(haystack: String, keyword: String, ignoreCase: Boolean = false): Boolean {
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex("(?<!\\p{L})${Regex.escape(keyword)}(?!\\p{L})", opts).containsMatchIn(haystack)
        }

        // ✅ Giữ lại nhóm từ khoá CHUNG không thuộc riêng 1 domain nào (camera/thiết bị/chat/call)
        // — đây chỉ là dấu hiệu "có khả năng đang hỏi về hoạt động/lịch sử", không định danh
        // domain cụ thể, nên không cần đưa vào AppConfig riêng theo từng kênh.
        val genericQueryKeywords = listOf(
            "liet ke", "lich su", "hom qua", "may gio", "kiem tra"
        )

        val matchesGeneric = genericQueryKeywords.any { kw -> containsWord(norm, kw) }
        val matchesCamera = getCameraKeywordsNormalized().any { kw -> containsWord(norm, kw) }
        val matchesDevice = getDeviceKeywordsOriginal().any { kw -> containsWord(original, kw, ignoreCase = true) }
        val matchesChat = getChatKeywordsNormalized().any { kw -> containsWord(norm, kw) }
        val matchesCall = getCallKeywordsNormalized().any { kw -> norm.contains(kw) }

        return matchesGeneric || matchesCamera || matchesDevice || matchesChat || matchesCall
    }

    // ✅ MỚI: TÁCH PROMPT THEO DOMAIN (Modular Tool Guard — chỉ tối ưu token, không thêm cơ chế
    // nhận diện domain thứ hai). Tái dùng NGUYÊN VẸN 4 bộ keyword (GLOBAL_CAMERA/DEVICE/CHAT/
    // CALL_KEYWORDS) mà mentionsAppDomain() ở trên đã dùng — không đọc RoutingPipeline/NLU riêng,
    // không tốn thêm 1 lượt phân tích nào mỗi turn. Khác mentionsAppDomain() ở chỗ: thay vì trả
    // Boolean "có domain nào không", hàm này trả về CHÍNH XÁC domain nào khớp, để
    // buildToolCallingGuard() biết catalog nào cần gửi.
    //
    // ⚠️ Khi câu hỏi chỉ chứa từ khoá CHUNG (genericQueryKeywords: "liệt kê", "lịch sử"...) mà
    // không khớp domain cụ thể nào, cố tình trả về SET RỖNG (không phải "tất cả domain") — để
    // buildToolCallingGuard() rơi vào nhánh an toàn phía dưới (gửi đủ catalog như hành vi gốc),
    // tránh lặp lại lỗi tự mâu thuẫn của các bản nháp trước: gán domain "GENERAL" rồi vẫn nhồi
    // hết → không tiết kiệm được token nào trong đúng trường hợp phổ biến nhất (câu hỏi mơ hồ).
    private suspend fun matchedDomainsInMessage(msg: String): Set<String> {
        val original = msg.trim()
        val norm = StringSimilarityUtil.normalizeVietnamese(original)

        fun containsWord(haystack: String, keyword: String, ignoreCase: Boolean = false): Boolean {
            val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex("(?<!\\p{L})${Regex.escape(keyword)}(?!\\p{L})", opts).containsMatchIn(haystack)
        }

        val genericQueryKeywords = listOf("liet ke", "lich su", "hom qua", "may gio", "kiem tra")
        if (genericQueryKeywords.any { kw -> containsWord(norm, kw) }) return emptySet()

        val domains = mutableSetOf<String>()
        if (getCameraKeywordsNormalized().any { kw -> containsWord(norm, kw) }) domains.add("camera")
        if (getDeviceKeywordsOriginal().any { kw -> containsWord(original, kw, ignoreCase = true) }) domains.add("tuya")
        if (getChatKeywordsNormalized().any { kw -> containsWord(norm, kw) }) domains.add("chat")
        if (getCallKeywordsNormalized().any { kw -> norm.contains(kw) }) domains.add("call")
        return domains
    }

    // ✅ MỚI: Domain của LƯỢT NÀY ưu tiên trước — nếu câu hỏi không tự nêu domain nào (vd câu hỏi
    // đào sâu "họ mặc áo màu gì" không chứa từ khoá camera/tuya/chat/call nào), fallback sang
    // SearchFocus đã ghi ở lần search DB thật gần nhất — TÁI DÙNG đúng
    // chatHistoryManager.getLastSearchFocus() mà shouldAttachToolGuard() bên dưới đã dùng, không
    // thêm nguồn dữ liệu mới. Nếu vẫn không xác định được → trả set rỗng, gọi nơi dùng tự hiểu là
    // "gửi đủ catalog như cũ", không đoán bừa.
    private suspend fun resolveActiveDomains(message: String, username: String): Set<String> {
        val currentTurnDomains = matchedDomainsInMessage(message)
        if (currentTurnDomains.isNotEmpty()) return currentTurnDomains

        return when (chatHistoryManager.getLastSearchFocus(username)?.sourceCategory) {
            "camera" -> setOf("camera")
            "tuya" -> setOf("tuya")
            "call" -> setOf("call")
            "facebook", "telegram", "website", "chat" -> setOf("chat")
            else -> emptySet()
        }
    }

    private suspend fun isExitLockPhrase(msg: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())
        val exitPhrases = configProvider.getString(
            AppConfigDefaults.GLOBAL_EXIT_LOCK_PHRASES,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_EXIT_LOCK_PHRASES)
        ).split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return norm in exitPhrases
    }

    private suspend fun parseYesNo(msg: String): Boolean? {
        val norm = StringSimilarityUtil.normalizeVietnamese(msg.trim())

        val yesKeywords = configProvider.getString(
            AppConfigDefaults.GLOBAL_CONFIRM_YES_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CONFIRM_YES_KEYWORDS)
        ).split(",").map { it.trim() }.filter { it.isNotBlank() }
        val noKeywords = configProvider.getString(
            AppConfigDefaults.GLOBAL_CONFIRM_NO_KEYWORDS,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CONFIRM_NO_KEYWORDS)
        ).split(",").map { it.trim() }.filter { it.isNotBlank() }

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

    private suspend fun handleLockConfirmation(userMessage: String, username: String, pluginId: String): RouterOutcome {
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