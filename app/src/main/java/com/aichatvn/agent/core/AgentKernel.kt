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
                        qa?.answer ?: if (request.allowDeviceControl) {
                            runLocalQAEventAnalysis(message)
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
                        if (shouldAttachToolGuard(message, username, currentTurn)) buildToolCallingGuard() else ""
                    } else {
                        ""
                    }

                    // ✅ MỚI: khi được phép điều khiển thiết bị (admin, hoặc khách ngoại lúc mở
                    // khoá), Groq mode phải giữ được quyền tra database QA training giống Combined
                    // mode — trước đây thiếu hẳn bước này nên Admin dùng Groq mode mất sạch quyền
                    // catalog/FAQ, chỉ còn db_search (và db_search còn bị gate theo từ khoá).
                    val qaContext = if (request.allowDeviceControl) {
                        buildQAContextForAgent(message, username)
                    } else {
                        ""
                    }

                    val cleanContext = buildString {
                        append(baseGuard)
                        if (qaContext.isNotEmpty()) append("\n\n$qaContext")
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
                        // ✅ SỬA: Groq mode không còn đường catalog_search nữa (đã thay bằng
                        // qaContext tiêm thẳng ở trên khi allowDeviceControl=true, và hoàn toàn
                        // không có gì khi bị chặn) — luôn false, khác với Combined mode bên dưới.
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
                        
                        val qaContext = buildQAContextForAgent(message, username)

                        val toolGuard = if (request.allowDeviceControl) {
                            if (shouldAttachToolGuard(message, username, currentTurn)) buildToolCallingGuard() else ""
                        } else {
                            CATALOG_SEARCH_TOOL_INSTRUCTION
                        }

                        val fullContext = buildString {
                            append(baseGuard)
                            if (qaContext.isNotEmpty()) append("\n\n$qaContext")
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

    private suspend fun runLocalQAEventAnalysis(userQuery: String): String = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val normalized = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userQuery.lowercase()) ?: ""

            val parsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalized, now)
            val since = parsedRange?.since ?: (now - 24 * 60 * 60 * 1000L)
            val until = parsedRange?.until ?: now
            
            val label: String = if (parsedRange != null && parsedRange.label != null) {
                parsedRange.label
            } else {
                "hôm nay"
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

            val deviceState = when {
                normalized.contains("bat") || normalized.contains("mo") -> "true"
                normalized.contains("tat") -> "false"
                else -> null
            }

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
        "person" to listOf("nguoi la", "co nguoi", "nguoi dot nhap", "xam nhap", "trom"),
        "car" to listOf("oto", "xe hoi", "xe oto", "xe bon banh"),
        "motorbike" to listOf("xe may", "xe hai banh"),
        "dog" to listOf("con cho", "cho "),
        "cat" to listOf("con meo", "meo "),
        "package" to listOf("goi hang", "buu kien", "shipper")
    )

    private fun extractObjectLabel(normalizedMsg: String): String? =
        OBJECT_LABEL_KEYWORDS.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key

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
            val validCategories = setOf("camera", "tuya", "call", "facebook", "telegram", "website")
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
            val sourceHint = directTarget
                ?: toolCall.params["source"]?.trim()?.takeIf { it.isNotBlank() }
                ?: chatHistoryManager.getLastSearchFocus(username)?.sourceIdOrName
                ?: chatHistoryManager.getLastMentionedDevice(username)

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

                // matchedCamera/matchedDevice tra được từ target/sourceHint LUÔN được ưu tiên
                // hơn directCategory — vì nếu model đã cho tên cụ thể khớp đúng thiết bị thật,
                // đó là tín hiệu đáng tin hơn 1 category rời rạc (phòng trường hợp model điền
                // category="camera" nhưng target lại trùng tên 1 thiết bị Tuya do nhầm lẫn).
                when {
                    matchedCamera != null -> { resolvedSourceCategory = "camera"; resolvedSourceName = matchedCamera.customername.ifBlank { matchedCamera.id } }
                    matchedDevice != null -> { resolvedSourceCategory = "tuya"; resolvedSourceName = matchedDevice.name.ifBlank { matchedDevice.id } }
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

            val contract = SearchContract(
                questionType = QuestionType.OTHER,
                sinceMs = timeRange.since,
                untilMs = timeRange.until,
                timeframeLabel = timeRange.label,
                sourceCategory = resolvedSourceCategory,
                sourceIdOrName = resolvedSourceName ?: sourceHint,
                targetObject = objectLabel,
                aggregation = if (originalMessage.contains("mấy lần") || originalMessage.contains("bao nhiêu")) AggregationType.COUNT else AggregationType.NONE,
                granularity = toolCall.params["granularity"] ?: "detail"
            )

            val searchResult = databaseSearchHelper.executeSearchContract(contract)

            // ✅ MỚI: ghi lại trọng tâm search vừa thực hiện (bất kể domain camera/tuya/chat),
            // để các câu hỏi đào sâu tiếp theo (vd "họ mặc áo màu gì") tự động được coi là
            // liên quan mà không cần khớp từ khoá cứng — xem shouldAttachToolGuard().
            chatHistoryManager.setLastSearchFocus(username, resolvedSourceCategory, resolvedSourceName ?: sourceHint)

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
                    ToolCall(tool, buildMap {
                        put("timeframe", timeframe)
                        put("object", objectLabel)
                        if (category.isNotBlank()) put("category", category)
                        if (target.isNotBlank()) put("target", target)
                        if (legacySource.isNotBlank()) put("source", legacySource)
                        put("granularity", granularity)
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

        // Số lượt hỏi tiếp theo sau 1 lần search DB thật mà vẫn được coi là "đang đào sâu"
        // vào cùng kết quả đó, dù câu hỏi không chứa từ khoá domain nào (camera/tuya/chat...).
        private const val FOLLOWUP_TURN_WINDOW = 2

        private const val CATALOG_SEARCH_TOOL_INSTRUCTION = """🚨 QUY TẮC TÌM KIẾM THÔNG TIN:
- Nếu chưa có đủ thông tin trong <SYSTEM_MEMORY> để trả lời câu hỏi của người dùng, hãy trả về DUY NHẤT một chuỗi JSON thô (tuyệt đối không markdown, không giải thích):
{
  "tool": "catalog_search",
  "query": "từ khóa tìm kiếm"
}
- Nếu đã có thông tin từ <SYSTEM_MEMORY>, hãy trả lời tự nhiên bằng Tiếng Việt và KHÔNG được gọi lại tool."""
    }

    private fun stripDbSearchInvite(guardText: String): String {
        val marker = "🚨 QUY TẮC TRUY VẤN DỮ LIỆU THỰC TẾ:"
        val idx = guardText.indexOf(marker)
        return if (idx == -1) guardText else guardText.substring(0, idx).trimEnd()
    }

    private fun stripCatalogSearchInvite(guardText: String): String {
        val marker = "🚨 QUY TẮC TÌM KIẾM THÔNG TIN:"
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
            "⚠️ Bạn KHÔNG có khả năng điều khiển thiết bị thật. Nếu câu hỏi của user là yêu cầu " +
            "điều khiển thiết bị (bật/tắt/mở/đóng/đặt lịch...), TUYỆT ĐỐI không tự khẳng định đã " +
            "thực hiện hành động đó — hãy hỏi lại rõ hơn hoặc báo chưa thực hiện được.\n" +
            "⚠️ Trả lời NGẮN GỌN, đi thẳng vào trọng tâm — tối đa $maxSentences câu, trừ khi người dùng yêu cầu giải thích chi tiết hoặc liệt kê đầy đủ."

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

    private suspend fun buildToolCallingGuard(): String {
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

        // ✅ MỚI: danh bạ cuộc gọi THẬT (call_contacts) — trước đây "call" là nguồn DUY NHẤT
        // không có danh sách hiển thị nào trong prompt, trong khi camera/tuya đều liệt kê tên+ID
        // thật từ DB. Lưu ý: câu hỏi về cuộc gọi vẫn hợp lệ ngay cả với số lạ chưa lưu (rule 2 bên
        // dưới không đổi) — danh sách này chỉ để model biết TÊN các liên hệ đã lưu, giúp trả lời
        // tự nhiên hơn khi user hỏi kiểu "gọi cho Tuấn xem" thay vì chỉ thấy mã máy thô.
        val callContactNames = try {
            database.callContactDao().observeAll().first().map {
                if (it.displayName.isNotBlank()) "${it.displayName} (mã máy: ${it.deviceCode})" else "mã máy: ${it.deviceCode}"
            }
        } catch (e: Exception) { emptyList() }

        // ✅ MỚI: lấy vài từ khoá mẫu từ GLOBAL_CALL_KEYWORDS (config) để gợi ý cho model biết
        // "source" còn có thể là "cuộc gọi" — trước đây field này chỉ mô tả "tên camera/thiết
        // bị" nên model không có cách nào biết mà yêu cầu tra lịch sử cuộc gọi qua tool này.
        val callKeywordSample = try {
            configProvider.getString(
                AppConfigDefaults.GLOBAL_CALL_KEYWORDS,
                AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_CALL_KEYWORDS)
            ).split(",").map { it.trim() }.filter { it.isNotBlank() }.take(3)
        } catch (e: Exception) { listOf("cuộc gọi") }

        // ✅ MỚI: kênh chat THẬT đang cấu hình (trước đây hardcode cứng "facebook, telegram,
        // website" bất kể đã cấu hình hay chưa — trong khi camera/tuya luôn đọc DB thật). Dùng
        // đúng điều kiện mà WebhookGatewayService dùng để coi 1 kênh là "đã bật":
        // facebook -> có ít nhất 1 page đã đăng ký; telegram -> có bot token; website -> có
        // gateway URL + token.
        val enabledChatChannels = try {
            buildList {
                if (database.facebookPageDao().getAllPages().isNotEmpty()) add("facebook")
                if (configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim().isNotBlank()) add("telegram")
                val gwUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                val gwToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                if (gwUrl.isNotBlank() && gwToken.isNotBlank()) add("website")
            }
        } catch (e: Exception) { emptyList() }

        return "🚨 QUY TẮC TRUY VẤN DỮ LIỆU THỰC TẾ:\n" +
            "1. Nếu người dùng hỏi về hoạt động của camera/thiết bị/kênh chat/cuộc gọi trong quá khứ hoặc hiện tại mà bạn chưa có thông tin thô, " +
            "hãy trả về DUY NHẤT một chuỗi JSON thô có cấu trúc sau (tuyệt đối không markdown, không giải thích gì thêm):\n" +
            "{\n" +
            "  \"tool\": \"db_search\",\n" +
            "  \"timeframe\": \"today | yesterday | last_3_days | last_7_days\",\n" +
            "  \"object\": \"person | car | motorbike | dog | cat | package | all\",\n" +
            "  \"category\": \"camera | tuya | call | facebook | telegram | website\",\n" +
            "  \"target\": \"tên camera hoặc thiết bị cụ thể mà người dùng nhắc tới nếu category là camera/tuya; để trống nếu không\",\n" +
            "  \"granularity\": \"summary | detail\"\n" +
            "}\n" +
            "Lưu ý chọn \"granularity\":\n" +
            "- \"summary\": khi câu hỏi chỉ cần số liệu/kết quả tổng hợp (vd \"hôm nay có mấy cuộc gọi\", \"hôm qua có ai gọi nhỡ không\") — KHÔNG cần xem từng dòng log thô.\n" +
            "- \"detail\": khi câu hỏi cần biết diễn biến/nội dung cụ thể (vd \"lúc 5 giờ chiều nói chuyện gì\", \"cuộc gọi lúc đó kéo dài bao lâu\") hoặc khi không chắc — mặc định dùng \"detail\".\n" +
            "Lưu ý chọn \"category\":\n" +
            "- \"camera\" hoặc \"tuya\": khi hỏi về hoạt động camera/thiết bị thông minh cụ thể (kèm \"target\" đúng tên).\n" +
            "- \"call\": khi hỏi về ${callKeywordSample.joinToString("/")}, lịch sử/cuộc gọi nhỡ.\n" +
            "- \"facebook\"/\"telegram\"/\"website\": khi hỏi về tin nhắn/hội thoại của một kênh chat cụ thể.\n" +
            "- Nếu câu hỏi KHÔNG nêu rõ nguồn nào (vd hỏi tiếp theo một câu trước đó), bỏ hẳn field \"category\" thay vì đoán đại — hệ thống sẽ tự suy ra từ ngữ cảnh trước đó.\n" +
            "2. Chỉ được gọi tool cho thiết bị thật sự có tên hoặc ID trùng khớp hoặc nằm trong danh sách đăng ký dưới đây (câu hỏi về cuộc gọi hoặc kênh chat luôn hợp lệ, không cần khớp danh sách thiết bị). Nếu người dùng hỏi một thiết bị lạ không tồn tại, hãy trả lời thẳng là hệ thống không lắp đặt thiết bị đó, TUYỆT ĐỐI không được gọi tool.\n" +
            (if (cameraNames.isNotEmpty()) "📷 Camera đang có: ${cameraNames.joinToString(", ")}\n" else "") +
            (if (deviceNames.isNotEmpty()) "🔌 Thiết bị đang có: ${deviceNames.joinToString(", ")}\n" else "") +
            (if (callContactNames.isNotEmpty()) "📞 Danh bạ cuộc gọi đã lưu: ${callContactNames.joinToString(", ")}\n" else "") +
            "💬 Kênh chat hỗ trợ: " + (if (enabledChatChannels.isNotEmpty()) enabledChatChannels.joinToString(", ") else "chưa cấu hình kênh chat nào")
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