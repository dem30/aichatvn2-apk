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

                    interceptAndExecuteToolCall(
                        originalMessage = message,
                        responseRaw = responsePass1,
                        username = username,
                        baseContext = cleanContext,
                        historySnapshot = historySnapshot,
                        allowDeviceControl = request.allowDeviceControl,
                        // Groq mode không có đường catalog_search nào (xem comment ở trên) — luôn false.
                        allowCatalogSearch = false
                    )
                }

                else -> {
                    val matches = search(message, username)
                    val perfectMatch = matches.firstOrNull()?.qa

                    if (perfectMatch != null) {
                        perfectMatch.answer
                    } else {
                        val historySnapshot = buildHistorySnapshot(username)

                        // ✅ SỬA: Admin chỉ dùng db_search — không còn tra catalog QA thụ động ở
                        // đây nữa (trước đây qaContext build vô điều kiện, lẫn cho cả admin).
                        // Khách ngoại bị chặn cũng không còn qaContext thụ động song song với
                        // CATALOG_SEARCH_TOOL_INSTRUCTION bên dưới — tránh 2 lần search catalog
                        // trùng lặp trong cùng 1 request; khách ngoại chỉ tra catalog CHỦ ĐỘNG
                        // qua tool catalog_search khi model tự thấy cần (2-pass).
                        val toolGuard = if (request.allowDeviceControl) {
                            if (shouldAttachToolGuard(message, username, currentTurn)) {
                                // ✅ MỚI: cùng cơ chế lọc theo domain như nhánh "groq" ở trên.
                                buildToolCallingGuard(resolveActiveDomains(message, username))
                            } else ""
                        } else {
                            CATALOG_SEARCH_TOOL_INSTRUCTION
                        }

                        val fullContext = buildString {
                            append(baseGuard)
                            if (extraContext.isNotEmpty()) append("\n\n$extraContext")
                            if (toolGuard.isNotEmpty()) append("\n\n$toolGuard")
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
            val keywordParam: List<String> = if (!isQuantity && !isYesNo) {
                var stripped = userQuery
                parsedRange?.label?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                sourceName?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                matchedCam?.customername?.let { stripped = stripped.replace(it, "", ignoreCase = true) }
                matchedDev?.name?.let { stripped = stripped.replace(it, "", ignoreCase = true) }

                val strippedNormalized = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(stripped.lowercase()) ?: stripped.lowercase()
                strippedNormalized
                    .split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.length > 1 && it !in STOPWORD_KEYWORDS }
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
    ): String {
        val toolCall = parseToolCall(responseRaw) ?: return responseRaw

        if (toolDepth >= 1) {
            logger.w("AgentKernel", "⚠️ [Tool Loop Guard] Model bỏ qua chỉ thị, vẫn gọi tool lần nữa (depth=$toolDepth). Tự trả lời từ dữ liệu đã tra được thay vì lặp lại.")
            return buildFallbackFromSearchResult(lastSearchResultText)
        }

        if (toolCall.tool == "catalog_search") {
            if (!allowCatalogSearch) {
                logger.w("AgentKernel", "⚠️ Chặn catalog_search: chế độ/nhánh hiện tại không được phép dùng tool này.")
                return "Xin lỗi, hiện chưa có thông tin chính xác cho câu hỏi này. Vui lòng liên hệ nhân viên hỗ trợ để được hỗ trợ thêm."
            }
            return handleCatalogSearchToolCall(originalMessage, toolCall, username, baseContext, historySnapshot, allowDeviceControl, toolDepth)
        }

        if (!allowDeviceControl) {
            logger.w("AgentKernel", "⚠️ Chặn gọi Tool db_search: điều khiển/truy cập đang bị khoá cho username=$username")
            return "Dạ, để bảo vệ quyền riêng tư của gia đình, nhật ký hoạt động camera và thiết bị hiện đang được khoá truy cập. Vui lòng liên hệ Quản trị viên để được mở quyền."
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

            val timeframe = if (objectLabel == "all" && resolvedSourceCategory == null && rawTimeframe in setOf("last_3_days", "last_7_days")) {
                logger.w("AgentKernel", "⚠️ [Cost Guard] Chặn combo tốn kém object=all + $rawTimeframe (không có source thu hẹp), ép về 'today'")
                "today"
            } else {
                rawTimeframe
            }

            val timeRange = timeRangeResolver.resolve(timeframe)

            // ✅ MỚI: "keyword" (chuỗi tự do model chép từ câu hỏi user) → detailsKeywords.
            // Trước đây field này tồn tại sẵn trong SearchContract và ĐÃ được
            // DatabaseSearchHelper.executeSearchContract() lọc đúng (log.summary.contains(...)),
            // nhưng không có đường nào gán giá trị từ toolCall.params — nên luôn rỗng, không lọc
            // được nội dung. Giữ nguyên 1 phần tử duy nhất (không tách từ) vì contains() đã khớp cả cụm dài.
            val keywordParam = toolCall.params["keyword"]?.trim()?.takeIf { it.isNotBlank() }

            // ✅ MỚI: nhánh isCallCategory + granularity="summary" trong DatabaseSearchHelper đọc
            // THẲNG call_logs thô theo mốc thời gian (xem comment ở đó) — hoàn toàn KHÔNG đi qua
            // biến `filtered` đã áp detailsKeywords, nên nếu để nguyên granularity="summary" thì
            // keyword sẽ bị lờ đi hoàn toàn dù model đã điền đúng. Ép về "detail" khi có keyword
            // để filter theo nội dung thực sự có tác dụng — cùng tinh thần Cost Guard phía trên.
            val resolvedGranularity = if (keywordParam != null && resolvedSourceCategory == "call") {
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
                sourceIdOrName = resolvedSourceName ?: (if (currentTurnHint != null || directCategory == null) sourceHint else null),
                targetObject = objectLabel,
                aggregation = if (originalMessage.contains("mấy lần") || originalMessage.contains("bao nhiêu")) AggregationType.COUNT else AggregationType.NONE,
                granularity = resolvedGranularity,
                detailsKeywords = keywordParam?.let { listOf(it) } ?: emptyList()
            )

            val searchResult = if (keywordParam != null) {
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
            chatHistoryManager.setLastSearchFocus(username, resolvedSourceCategory, resolvedSourceName ?: sourceHint)

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

        private const val CATALOG_SEARCH_TOOL_INSTRUCTION = """🚨 TÌM KIẾM THÔNG TIN (catalog_search):
- Thiếu info trong <SYSTEM_MEMORY> để trả lời → trả DUY NHẤT 1 JSON thô (không markdown, không giải thích): {"tool": "catalog_search", "query": "từ khóa tìm kiếm"}
- Đã đủ info từ <SYSTEM_MEMORY> → trả lời tự nhiên bằng Tiếng Việt, KHÔNG gọi lại tool."""
    }

    private fun stripDbSearchInvite(guardText: String): String {
        val marker = "🚨 TRUY VẤN DỮ LIỆU THỰC TẾ (db_search):"
        val idx = guardText.indexOf(marker)
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
        val marker = "🚨 TÌM KIẾM THÔNG TIN (catalog_search):"
        val idx = guardText.indexOf(marker)
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
        return configProvider.getString(
            AppConfigDefaults.GLOBAL_CHAT_SYSTEM_PROMPT,
            AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CHAT_SYSTEM_PROMPT)
        )
    }

    private suspend fun buildFullGuard(
        routerFailed: Boolean,
        outcome: RouterOutcome?,
        maxSentences: Int
    ): String {
        val antiHallucinationGuard =
            "⚠️ Không tự nhận đã điều khiển thiết bị thật (bật/tắt/mở/đóng/đặt lịch...) — " +
            "nếu chưa chắc đã thực hiện, hỏi lại rõ hơn hoặc báo chưa làm được.\n" +
            "⚠️ Trả lời tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ."

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

        // ✅ RÚT GỌN (theo yêu cầu): trước đây mỗi field (timeframe/granularity/category/keyword)
        // có 1 dòng giải thích + ví dụ riêng — đúng nội dung cần nhắc AI mỗi lượt (vì Groq không
        // có trí nhớ giữa các lần gọi), nhưng dài hơn mức cần thiết. Giữ lại đúng 3 thứ bắt buộc
        // AI phải biết: (1) JSON schema + enum hợp lệ, (2) catalogue tên thật (camera/thiết bị/
        // danh bạ/kênh chat) để copy đúng vào target, (3) rule "không tự bịa thiết bị lạ". Bỏ các
        // câu ví dụ minh hoạ và diễn giải lặp ý đã nằm sẵn trong tên field.
        return "🚨 TRUY VẤN DỮ LIỆU THỰC TẾ (db_search):\n" +
            "Cần tra hoạt động camera/thiết bị/chat/cuộc gọi, hoặc câu hỏi chung/FAQ chưa có info → trả DUY NHẤT 1 JSON thô:\n" +
            "{\"tool\":\"db_search\",\"timeframe\":\"today|yesterday|last_3_days|last_7_days\",\"object\":\"person|car|motorbike|dog|cat|package|all\",\"category\":\"camera|tuya|call|facebook|telegram|website|chat|qa\",\"target\":\"tên thiết bị/camera đúng theo danh sách dưới, hoặc từ khóa nếu qa\",\"keyword\":\"tóm tắt ngắn nội dung cần lọc trong log, copy sát câu hỏi; để trống nếu không cần\",\"granularity\":\"summary|detail\"}\n" +
            "- NẾU câu hỏi đề cập hoặc liên quan tới Camera, sự kiện, nhận diện vật thể (mèo, chó, người, màu sắc...) hay kiểm tra có/không trên camera (dù câu hỏi kỳ lạ như 'mèo xanh', 'người áo đỏ') → BẮT BUỘC trả DUY NHẤT 1 JSON db_search để kiểm tra dữ liệu thực tế trước. TUYỆT ĐỐI KHÔNG tự bịa hay giải thích lý thuyết tính năng khi chưa check DB.\n" +
            "- category=qa cho câu hỏi chung không thuộc camera/thiết bị/cuộc gọi/tin nhắn cụ thể. category=chat khi hỏi tin nhắn nhưng không rõ kênh nào. Không rõ nguồn → bỏ hẳn field category.\n" +
            "- Chỉ gọi tool cho thiết bị khớp danh sách dưới (cuộc gọi/chat/qa luôn hợp lệ). Thiết bị lạ không có trong danh sách → trả lời KHÔNG lắp đặt, không gọi tool.\n" +
            "- TUYỆT ĐỐI không tự bịa nội dung/ngày giờ/chi tiết cụ thể của log, tin nhắn hay cuộc gọi khi chưa nhận được dữ liệu thật từ tool trong lượt này. Nếu câu hỏi đào sâu vào nội dung cụ thể của điều vừa được nhắc tới (vd \"tin nhắn nói gì\", \"lúc mấy giờ\") mà bạn CHƯA có dữ liệu đó → PHẢI trả JSON db_search để lấy lại, không tự suy diễn hay đoán.\n" +
            (if (cameraNames.isNotEmpty()) "📷 Camera: ${cameraNames.joinToString(", ")}\n" else "") +
            (if (deviceNames.isNotEmpty()) "🔌 Thiết bị: ${deviceNames.joinToString(", ")}\n" else "") +
            (if (callContactNames.isNotEmpty()) "📞 Danh bạ: ${callContactNames.joinToString(", ")}\n" else "") +
            (if (showChatLine) "💬 Chat: " + (if (enabledChatChannels.isNotEmpty()) enabledChatChannels.joinToString(", ") else "chưa cấu hình") else "") +
            (if (callKeywordSample.isNotEmpty()) "\n📞 call còn hiểu: ${callKeywordSample.joinToString("/")}" else "")
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