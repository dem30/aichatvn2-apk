package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ChatMode {
    GROQ,
    QA,
    COMBINED
}

@Singleton
class ChatSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    logger: Logger
) : BaseSkill("chat", "Chat với AI", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(),
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "chat",
                description = "Trò chuyện với AI hoặc khách hàng ngoại tuyến",
                parameters = listOf(
                    PluginParameter("message", "string", "Tin nhắn", true),
                    PluginParameter("username", "string", "Tên người dùng hoặc ID khách hàng", true),
                    PluginParameter("extraContext", "string", "Ngữ cảnh bổ sung", false)
                )
            ),
            PluginAction(
                name = "clear_history",
                description = "Xóa lịch sử chat",
                parameters = listOf(
                    PluginParameter("username", "string", "Tên người dùng", true)
                )
            )
        )
    )

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.COMBINED)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val database by lazy { AppDatabase.getDatabase(context) }
    private var currentUsername: String = "default_user"
    private val messagesMutex = Mutex()

    companion object {
        private val PAST_QUERY_KEYWORDS = setOf(
            "mấy hôm trước", "hôm qua", "hôm nay", "gần đây", "vừa rồi", "lúc nãy",
            "lịch sử", "nhật ký", "hoạt động", "đã làm gì", "đã xảy ra",
            "có ai gọi", "có ai nhắn", "tin nhắn gì", "cuộc gọi", "bỏ lỡ", "chưa đọc",
            "mấy ngày nay", "tuần trước", "tuần này", "trước đó",
            // 💡 Bổ sung từ khóa mở rộng: đảm bảo câu hỏi nhắc thẳng đến camera/thiết bị/sự kiện cũng kích hoạt buildMemoryContext
            "sự kiện", "biến cố", "cảnh báo", "báo cáo", "camera", "thiết bị"
        )

        private const val DEFAULT_MEMORY_LOOKBACK_DAYS = 3

        // ✅ MỚI: Nhận diện câu hỏi dạng ĐẾM SỐ LƯỢNG ("bao nhiêu", "mấy lần"...) — khi khớp,
        // buildMemoryContext trả về một con số tổng hợp thay vì liệt kê từng dòng log thô,
        // giảm đáng kể token gửi lên AI cho loại câu hỏi này.
        private val QUANTITY_KEYWORDS = setOf(
            "bao nhieu", "may lan", "so luong", "tong cong", "duoc may"
        )
    }

    private fun isQuantityQuery(normalizedMsg: String): Boolean =
        QUANTITY_KEYWORDS.any { normalizedMsg.contains(it) }

    // ✅ MỚI: Bắt trạng thái cụ thể được hỏi kèm trong câu đếm số lượng (vd "mấy lần OFF",
    // "bao nhiêu lần bật"). Trả về danh sách biến thể để so khớp .contains() với summary log,
    // vì chưa chắc summary được ghi bằng tiếng Việt có dấu hay tiếng Anh — xem ghi chú giới hạn
    // đã biết tại nơi gọi hàm này trong buildMemoryContext().
    //
    // ⚠️ QUAN TRỌNG: PHẢI so khớp "bật"/"tắt" trên `originalMessage` (CÒN DẤU), không phải bản
    // đã normalize() — vì normalize() bỏ dấu khiến "bật" và "bất" (như trong "bất thường") đều
    // thành "bat", dẫn đến câu "có sự kiện bất thường nào không" bị hiểu nhầm thành hỏi trạng
    // thái "bật". "on"/"off" không có vấn đề dấu nên vẫn so khớp trên bản đã chuẩn hóa.
    private fun extractStateKeyword(originalMessage: String, normalizedMsg: String): List<String>? = when {
        originalMessage.contains("tắt", ignoreCase = true) || containsAnyWord(normalizedMsg, "off") ->
            listOf("tắt", "off", "Tắt", "OFF")
        originalMessage.contains("bật", ignoreCase = true) || containsAnyWord(normalizedMsg, "on") ->
            listOf("bật", "on", "Bật", "ON")
        else -> null
    }

    private fun containsAnyWord(text: String, vararg words: String) =
        words.any { com.aichatvn.agent.core.text.VietnameseTextNormalizer.containsWholePhrase(text, it) }

    // ✅ MỚI: Ánh xạ từ tiếng Việt sang nhãn "objects" mà camera vision trả về. PHẢI khớp đúng
    // tập nhãn đóng đã siết trong GroqClientTool.STRUCTURED_VISION_SUFFIX (person, car, motorbike,
    // dog, cat, package, unknown) — nếu bên đó đổi tập nhãn, phải cập nhật lại map này theo.
    private val OBJECT_LABEL_KEYWORDS = mapOf(
        "person" to listOf("nguoi la", "co nguoi", "nguoi dot nhap", "xam nhap", "trom"),
        "car" to listOf("oto", "xe hoi", "xe oto"),
        "motorbike" to listOf("xe may"),
        "dog" to listOf("con cho", "cho "),
        "cat" to listOf("con meo", "meo "),
        "package" to listOf("goi hang", "buu kien", "shipper")
    )

    // Trả về nhãn object (tiếng Anh, khớp world_state/event log) nếu câu hỏi nhắc tới 1 loại đối
    // tượng cụ thể, dùng để lọc CHÍNH XÁC theo loại thay vì đếm/liệt kê chung mọi sự kiện.
    private fun extractObjectLabel(normalizedMsg: String): String? =
        OBJECT_LABEL_KEYWORDS.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key

    private fun logHasObject(summary: String, label: String): Boolean =
        summary.contains("[objects:", ignoreCase = true) && summary.contains(label, ignoreCase = true)

    private fun isPastMemoryQuery(message: String): Boolean {
        val norm = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(message)
        return PAST_QUERY_KEYWORDS.any { keyword ->
            val normKeyword = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(keyword)
            norm.contains(normKeyword)
        }
    }

    // ⚙️ Sửa đổi chữ ký hàm để nhận diện thêm userMessage từ khung chat gửi xuống,
    // phục vụ việc trích xuất thực thể camera/thiết bị/kênh và lọc log theo đúng đối tượng được hỏi
    private suspend fun buildMemoryContext(username: String, userMessage: String): String = withContext(Dispatchers.IO) {
        // ✅ SỬA LỖI #1: Chốt chặn an ninh chặn đứng rò rỉ dữ liệu riêng tư smarthome của chủ nhà cho khách ngoại tuyến
        if (username != "default_user") return@withContext ""

        try {
            val now = System.currentTimeMillis()

            // Đọc giới hạn số lượng dòng log tối đa cấu hình từ DB (chống Token Bloat, thay cho hard-code take(200))
            val maxLogs = configProvider.getInt(AppConfigDefaults.GLOBAL_MAX_MEMORY_LOGS, 30)

            // Truy xuất danh sách camera/thiết bị đang hoạt động để đối chiếu thực thể trong câu hỏi
            val activeCameras = database.cameraDao().getActiveCameras()
            val activeDevices = database.tuyaDeviceDao().getAllDevices()
            val normalizedMsg = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(userMessage.lowercase())

            // ✅ MỚI: Khoanh vùng thời gian THEO ĐÚNG Ý CÂU HỎI ("hôm qua", "lúc nãy", "3 ngày trước"...)
            // thay vì luôn quét cứng 3 ngày rồi mới cắt bớt bằng take(maxLogs). Nếu câu hỏi không chứa
            // tín hiệu thời gian rõ ràng, giữ nguyên hành vi mặc định cũ (3 ngày).
            val parsedRange = com.aichatvn.agent.core.text.VietnameseTimeRangeParser.parse(normalizedMsg, now)
            val since = parsedRange?.since
                ?: (now - DEFAULT_MEMORY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L)
            val rangeLabel = parsedRange?.label ?: "$DEFAULT_MEMORY_LOOKBACK_DAYS ngày gần nhất"

            // 🎯 Bước 1: Phân tích trích xuất thực thể xem câu hỏi có nhắc đích danh camera/thiết bị nào không
            val matchedCamera = activeCameras.find { cam ->
                val normCamName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(cam.customername.lowercase())
                normalizedMsg.contains(normCamName) || normalizedMsg.contains(cam.id.lowercase())
            }
            val matchedDevice = activeDevices.find { dev ->
                val normDevName = com.aichatvn.agent.core.text.VietnameseTextNormalizer.normalize(dev.name.lowercase())
                normalizedMsg.contains(normDevName) || normalizedMsg.contains(dev.id.lowercase())
            }

            // ✅ MỚI: Các kênh chat đa nền tảng (Facebook/Telegram/Website) cũng là một "đối tượng" xứng đáng
            // được lọc riêng, tránh trộn lẫn tin nhắn của kênh này với kênh khác khi Admin hỏi đích danh.
            // Chỉ áp dụng khi KHÔNG khớp camera/thiết bị, vì camera/thiết bị là ưu tiên cao hơn (cụ thể hơn).
            val platformKeywords = mapOf(
                "facebook" to listOf("facebook", "fb", "fanpage"),
                "telegram" to listOf("telegram"),
                "website" to listOf("website", "trang web", "widget web")
            )
            val matchedPlatform = if (matchedCamera == null && matchedDevice == null) {
                platformKeywords.entries.find { (_, kws) -> kws.any { normalizedMsg.contains(it) } }?.key
            } else null

            // 🎯 Bước 2: Truy vấn có bộ lọc phân vùng để tránh rò rỉ ngữ cảnh chéo giữa các camera/thiết bị/kênh
            // Dùng 'until' đã phân giải (vd "hôm qua" -> until = đầu ngày hôm nay), KHÔNG luôn dùng 'now',
            // nếu không log của "hôm nay" sẽ lẫn vào kết quả khi user hỏi về "hôm qua".
            val until = parsedRange?.until ?: now
            val rawLogs = database.eventLogDao().getLogsInTimeframe(since, until)
            val filteredLogs = when {
                matchedCamera != null -> rawLogs.filter {
                    it.sourceId == matchedCamera.id || it.summary.contains(matchedCamera.customername, ignoreCase = true)
                }
                matchedDevice != null -> rawLogs.filter {
                    it.sourceId == matchedDevice.id || it.summary.contains(matchedDevice.name, ignoreCase = true)
                }
                // Hỏi đích danh 1 kênh đa nền tảng: lấy đúng log incoming_message của kênh đó (kể cả đã đọc,
                // vì đây là truy vấn kiểm toán lịch sử, không chỉ riêng tin chưa đọc)
                matchedPlatform != null -> rawLogs.filter { it.source == matchedPlatform }
                // Hỏi chung chung: chỉ lấy các biến động quan trọng, bỏ bớt log rác hệ thống
                else -> rawLogs.filter { it.eventType in setOf("person_detected", "state_change") }
            }

            val dateFmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault())

            // ✅ MỚI: Nếu câu hỏi nhắc tới 1 loại đối tượng cụ thể ("người lạ", "con chó"...), lọc
            // tiếp trên field "objects" mà CameraSkill giờ đã ghi kèm trong summary (xem
            // saveAlertToHistory/processImageWithLearning) — dữ liệu này đến từ chính AI vision
            // model lúc phân tích snapshot (STRUCTURED_VISION_SUFFIX trong GroqClientTool), không
            // phải suy đoán qua từ khóa chung chung như trước.
            val objectLabel = extractObjectLabel(normalizedMsg)
            val objectFilteredLogs = if (objectLabel != null) {
                filteredLogs.filter { logHasObject(it.summary, objectLabel) }
            } else filteredLogs

            // ✅ MỚI: Câu hỏi dạng ĐẾM SỐ LƯỢNG ("bao nhiêu", "mấy lần"...) không cần AI đọc từng
            // dòng log — chỉ cần một con số. Đếm trực tiếp trên objectFilteredLogs đã lọc theo đúng
            // đối tượng/khoảng thời gian ở Bước 2, KHÔNG cần thêm truy vấn DB nào khác, rồi trả
            // về sớm để không nối thêm activityLines/unreadLines thô vào context (tiết kiệm token
            // đáng kể so với liệt kê từng dòng cho loại câu hỏi này).
            if (isQuantityQuery(normalizedMsg) && objectFilteredLogs.isNotEmpty()) {
                val subjectLabel = when {
                    matchedCamera != null -> "camera ${matchedCamera.customername}"
                    matchedDevice != null -> "thiết bị ${matchedDevice.name}"
                    matchedPlatform != null -> "kênh $matchedPlatform"
                    else -> "hệ thống"
                }
                // ⚠️ Nếu câu hỏi nhắc kèm 1 trạng thái cụ thể ("mấy lần OFF", "bao nhiêu lần bật"),
                // phải lọc lại theo đúng trạng thái đó trước khi đếm — nếu không, đếm luôn cả sự
                // kiện "bật" lẫn "tắt" sẽ cho ra con số sai với ý người dùng hỏi.
                // GIỚI HẠN ĐÃ BIẾT: match bằng .contains(ignoreCase) trên summary, dựa theo quy
                // ước hiện tại của CameraSkill/SmartSwitchSkill khi ghi summary (vd "Đang tắt").
                // Nếu nơi ghi log dùng từ khác (vd "OFF" viết hoa không dấu), cần bổ sung thêm
                // biến thể vào STATE_KEYWORD_VARIANTS bên dưới cho khớp.
                val stateKeyword = extractStateKeyword(userMessage, normalizedMsg)
                val countedLogs = if (stateKeyword != null) {
                    objectFilteredLogs.filter { log -> stateKeyword.any { log.summary.contains(it, ignoreCase = true) } }
                } else objectFilteredLogs
                val stateNote = if (stateKeyword != null) " (trạng thái: ${stateKeyword.first()})" else ""
                val objectNote = if (objectLabel != null) " (đối tượng: $objectLabel)" else ""
                return@withContext buildString {
                    append("<system_memory_context>\n")
                    append("Trong khoảng $rangeLabel, $subjectLabel ghi nhận ${countedLogs.size} sự kiện$stateNote$objectNote.\n")
                    append("Hãy trả lời trực tiếp con số này, không cần liệt kê chi tiết trừ khi người dùng hỏi thêm.\n")
                    append("</system_memory_context>")
                }
            }

            // 🎯 Bước 3: Áp dụng giới hạn động thay vì take(200) cứng
            // Chỉ loại incoming_message khi KHÔNG hỏi đích danh 1 kênh (tránh trùng với unreadLines phía dưới,
            // nhưng khi hỏi đích danh kênh thì vẫn cần incoming_message để trả lời được lịch sử tin nhắn kênh đó)
            val activityLines = objectFilteredLogs
                .sortedBy { it.timestamp }
                .filter { matchedPlatform != null || it.eventType != "incoming_message" }
                .take(maxLogs)
                .map { log -> "${dateFmt.format(java.util.Date(log.timestamp))}: ${log.summary}" }

            val unreadLines = try {
                database.chatMessageDao().getAllMessagesRaw(500)
                    .filter { it.role == "user" && !it.isRead && it.username != "default_user" }
                    // Nếu Admin hỏi đích danh 1 kênh, chỉ lấy tin chưa đọc của đúng kênh đó
                    .filter { matchedPlatform == null || it.username.substringBefore("_") == matchedPlatform }
                    .sortedByDescending { it.timestamp }
                    .take(10) // Tối ưu hóa từ 30 xuống 10 dòng chưa đọc để giảm token
                    .map { msg ->
                        val platform = msg.username.substringBefore("_")
                        val rawId = msg.username.substringAfter("_")
                        "${dateFmt.format(java.util.Date(msg.timestamp))}: [$platform] $rawId nhắn: \"${msg.content.take(80)}\""
                    }
            } catch (e: Exception) {
                emptyList()
            }

            // ✅ MỚI: Tra cứu World State (Bản sao số) — trạng thái SỐNG hiện tại, khác với event_logs (lịch sử
            // biến động). Trước đây buildMemoryContext không hề đọc world_state nên AI không có cách nào trả lời
            // đúng "đèn đang bật hay tắt" — chỉ có lịch sử bật/tắt trong quá khứ, không có trạng thái tức thời.
            // Chỉ tra khi khớp đích danh 1 camera/thiết bị cụ thể (world_state không áp dụng cho kênh chat).
            val worldState = when {
                matchedCamera != null -> database.worldStateDao().getState("camera", matchedCamera.id)
                matchedDevice != null -> database.worldStateDao().getState("tuya", matchedDevice.id)
                else -> null
            }

            if (activityLines.isEmpty() && unreadLines.isEmpty() && worldState == null) return@withContext ""

            buildString {
                append("<system_memory_context>\n")
                if (matchedCamera != null) {
                    append("--- Nhật ký hoạt động Camera ${matchedCamera.customername} ($rangeLabel) ---\n")
                } else if (matchedDevice != null) {
                    append("--- Nhật ký hoạt động Thiết bị ${matchedDevice.name} ($rangeLabel) ---\n")
                } else if (matchedPlatform != null) {
                    append("--- Nhật ký hoạt động kênh $matchedPlatform ($rangeLabel) ---\n")
                } else {
                    append("--- Nhật ký hoạt động tổng hợp ($rangeLabel) ---\n")
                }
                if (worldState != null) {
                    append("--- Trạng thái SỐNG hiện tại (Bản sao số, cập nhật lúc ${dateFmt.format(java.util.Date(worldState.updatedAt))}) ---\n")
                    append(worldState.attributesJson)
                    append("\n")
                }
                if (activityLines.isNotEmpty()) {
                    append(activityLines.joinToString("\n"))
                    append("\n")
                }
                if (unreadLines.isNotEmpty()) {
                    append("--- Tin nhắn khách hàng chưa đọc ---\n")
                    append(unreadLines.joinToString("\n"))
                    append("\n")
                }
                append("Hãy sử dụng dữ liệu chính xác này để trả lời câu hỏi của người dùng. Tuyệt đối không tự bịa đặt thông tin không có trong nhật ký.\n")
                append("</system_memory_context>")
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "buildMemoryContext error: ${e.message}", e)
            ""
        }
    }

    override suspend fun initialize() {
        reloadMessages(currentUsername)
    }

    override suspend fun shutdown() {}

    private suspend fun reloadMessages(username: String) {
        val loaded = withContext(Dispatchers.IO) {
            database.chatMessageDao().getMessages(username, 500)
        }
        _messages.value = loaded
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return when (action) {
            "chat" -> handleChat(params)
            "clear_history" -> handleClearHistory(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleChat(params: Map<String, Any>): PluginResult {
        val message = params["message"] as? String ?: return PluginResult.Failure("Thiếu tin nhắn")
        val username = params["username"] as? String ?: "default_user"
        val extraContext = params["extraContext"] as? String ?: ""
        return processQuery(message, extraContext, username, isManual = true) 
    }

    private suspend fun handleClearHistory(params: Map<String, Any>): PluginResult {
        val username = params["username"] as? String ?: "default_user"
        return clearHistory(username)
    }

    suspend fun saveExternalUserMessage(message: String, username: String, fileUrl: String? = null, imageBase64: String? = null) {
        val resolvedFileUrl = if (!imageBase64.isNullOrEmpty()) {
            saveIncomingChatImage(imageBase64)
        } else {
            fileUrl
        }
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionToken = "session_$username",
            username = username,
            content = message,
            role = "user", 
            type = if (resolvedFileUrl != null) "image" else "text",
            fileUrl = resolvedFileUrl,
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        withContext(Dispatchers.IO) {
            database.chatMessageDao().insertMessage(userMessage)
            
            database.eventLogDao().insertLog(
                com.aichatvn.agent.data.model.EventLogEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = userMessage.timestamp,
                    source = username.substringBefore("_"),
                    sourceId = username.substringAfter("_"),
                    eventType = "incoming_message",
                    value = message.take(200),
                    summary = "Tin nhắn mới từ ${username.substringBefore("_")} (${username.substringAfter("_")}): \"${message.take(80)}\""
                )
            )
        }
        messagesMutex.withLock {
            if (currentUsername == username) {
                _messages.value = _messages.value + userMessage
            }
        }
    }

    private fun saveIncomingChatImage(imageBase64: String): String? {
        return try {
            val bytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
            val dir = java.io.File(context.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "${UUID.randomUUID()}.jpg")
            java.io.FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            logger.e("ChatSkill", "Lỗi lưu ảnh khách gửi tới: ${e.message}")
            null
        }
    }

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
    }

    suspend fun openThread(username: String) {
        messagesMutex.withLock {
            currentUsername = username
            reloadMessages(username)
        }
    }

    suspend fun processQuery(
        message: String,
        extraContext: String = "",
        username: String,
        fileUrl: String? = null,
        imageBase64: String? = null,
        isManual: Boolean = false 
    ): PluginResult {
        return try {
            if (message.isBlank() && fileUrl.isNullOrEmpty() && imageBase64.isNullOrEmpty()) {
                return PluginResult.Success(
                    mapOf(
                        "response" to "",
                        "mode" to "history_reload_only"
                    )
                )
            }

            val isExternal = username.startsWith("facebook_") || username.startsWith("telegram_") ||
                username.startsWith("instagram_") || username.startsWith("website_")

            if (isExternal && isManual) {
                val hasImage = !imageBase64.isNullOrEmpty()
                val adminMessageId = UUID.randomUUID().toString()
                val adminMessage = ChatMessageEntity(
                    id = adminMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "assistant", 
                    type = if (hasImage) "image" else "text",
                    fileUrl = if (hasImage) fileUrl else null,
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = "human"
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(adminMessage)
                }
                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + adminMessage
                    }
                }

                val rawSenderId = username.substringAfter("_")
                if (username.startsWith("facebook_")) {
                    val pageId = withContext(Dispatchers.IO) {
                        database.cameraDao().getCustomerSetting(rawSenderId)?.lastFacebookPageId
                    } ?: extraContext.removePrefix("page_id:")
                    val fbParams = mutableMapOf<String, Any>(
                        "recipient_id" to rawSenderId,
                        "message" to message,
                        "page_id" to pageId
                    )
                    if (hasImage) fbParams["image_base64"] = imageBase64!!
                    agentKernel.executePluginAction("facebook", "send_messenger", fbParams)
                } else if (username.startsWith("telegram_")) {
                    val botToken = configProvider.getString(AppConfigDefaults.TELEGRAM_BOT_TOKEN).trim()
                    if (hasImage) {
                        sendTelegramPhoto(botToken, rawSenderId, imageBase64!!, message)
                    } else {
                        sendTelegramMessage(botToken, rawSenderId, message)
                    }
                } else if (username.startsWith("website_")) {
                    val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL).trim()
                    val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN).trim()
                    sendWebsiteReply(gatewayUrl, gatewayToken, rawSenderId, message, if (hasImage) imageBase64 else null)
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to message,
                        "messageId" to adminMessageId,
                        "mode" to "human_agent"
                    )
                )

            } else {
                val resolvedFileUrl = if (!imageBase64.isNullOrEmpty()) {
                    saveIncomingChatImage(imageBase64)
                } else {
                    fileUrl
                }

                val userMessageId = UUID.randomUUID().toString()
                val userMessage = ChatMessageEntity(
                    id = userMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = message,
                    role = "user", 
                    type = if (!resolvedFileUrl.isNullOrEmpty()) "image" else "text", 
                    fileUrl = resolvedFileUrl, 
                    timestamp = System.currentTimeMillis(),
                    isRead = !isExternal
                )
                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(userMessage)
                    // ✅ SỬA: trước đây nhánh bot tự trả lời (isExternal=true, isManual=false) chỉ ghi
                    // ChatMessageEntity, KHÔNG ghi event_log — khiến buildMemoryContext() không có
                    // audit-log vĩnh viễn để trả lời các câu hỏi lịch sử xa hơn "top 10 tin chưa đọc"
                    // (vd: tin đã đọc rồi, hoặc hỏi theo kênh cụ thể "tuần trước Telegram nhắn gì").
                    // Ghi giống hệt saveExternalUserMessage() (nhánh Người Trực) để 2 luồng nhất quán.
                    if (isExternal) {
                        database.eventLogDao().insertLog(
                            com.aichatvn.agent.data.model.EventLogEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                timestamp = userMessage.timestamp,
                                source = username.substringBefore("_"),
                                sourceId = username.substringAfter("_"),
                                eventType = "incoming_message",
                                value = message.take(200),
                                summary = "Tin nhắn mới từ ${username.substringBefore("_")} (${username.substringAfter("_")}): \"${message.take(80)}\""
                            )
                        )
                    }
                }

                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + userMessage
                    }
                }

                val blockExternalDeviceControl = isExternal && configProvider.getBoolean(
                    AppConfigDefaults.GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL,
                    false
                )

                val memoryContext = if (imageBase64.isNullOrEmpty() && fileUrl.isNullOrEmpty() && isPastMemoryQuery(message)) {
                    buildMemoryContext(username, message) // 💡 Đã truyền thêm tham số 'message' để phục vụ việc trích xuất thực thể
                } else {
                    ""
                }
                
                val finalExtraContext = if (memoryContext.isNotBlank()) {
                    if (extraContext.isNotBlank()) "$extraContext\n\n$memoryContext" else memoryContext
                } else {
                    extraContext
                }

                val response = agentKernel.chat(
                    com.aichatvn.agent.core.ChatRequest(
                        message = message,
                        username = username,
                        imageBase64 = imageBase64,
                        fileUrl = fileUrl,
                        extraContext = finalExtraContext,
                        chatMode = _chatMode.value.name,
                        allowDeviceControl = !blockExternalDeviceControl
                    )
                )

                val assistantMessageId = UUID.randomUUID().toString()
                val assistantMessage = ChatMessageEntity(
                    id = assistantMessageId,
                    sessionToken = "session_$username",
                    username = username,
                    content = response.responseText,
                    role = "assistant",
                    type = if (response.imagePath != null) "image" else "text",   
                    fileUrl = response.imagePath,                                  
                    timestamp = System.currentTimeMillis(),
                    sourcePlugin = response.usedPluginId
                )

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(assistantMessage)
                }

                messagesMutex.withLock {
                    if (currentUsername == username) {
                        _messages.value = _messages.value + assistantMessage
                    }
                }

                return PluginResult.Success(
                    mapOf(
                        "response" to response.responseText,
                        "messageId" to assistantMessageId,
                        "mode" to response.usedMode,
                        "imagePath" to response.imagePath
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Failed to process query")
        }
    }

    private suspend fun sendWebsiteReply(gatewayUrl: String, gatewayToken: String, senderId: String, text: String, imageBase64: String? = null) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$gatewayUrl/send/$gatewayToken")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("platform", "website")
                    put("recipientId", senderId)
                    put("message", text)
                    if (!imageBase64.isNullOrEmpty()) {
                        put("imageBase64", imageBase64)
                    }
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi phản hồi cho khách Website thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private suspend fun sendTelegramMessage(token: String, chatId: String, text: String) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = org.json.JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString()

                conn.outputStream.use { os ->
                    os.write(payload.toByteArray(Charsets.UTF_8))
                }
                conn.responseCode
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi phản hồi về Telegram thất bại: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private suspend fun sendTelegramPhoto(token: String, chatId: String, imageBase64: String, caption: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
                val boundary = "----AIChatVNBoundary${System.currentTimeMillis()}"
                val url = URL("https://api.telegram.org/bot$token/sendPhoto")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                conn.outputStream.use { os ->
                    fun writeField(name: String, value: String) {
                        os.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                        os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                        os.write("$value\r\n".toByteArray(Charsets.UTF_8))
                    }
                    writeField("chat_id", chatId)
                    if (caption.isNotBlank()) writeField("caption", caption)

                    os.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    os.write("Content-Disposition: form-data; name=\"photo\"; filename=\"image.jpg\"\r\n".toByteArray(Charsets.UTF_8))
                    os.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.UTF_8))
                    os.write(imageBytes)
                    os.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                if (code != 200) {
                    logger.w("ChatSkill", "⚠️ Gửi ảnh Telegram thất bại, HTTP: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                logger.e("ChatSkill", "Gửi ảnh về Telegram thất bại: ${e.message}")
            }
        }
    }

    suspend fun clearHistory(username: String): PluginResult {
        return try {
            withContext(Dispatchers.IO) {
                database.chatMessageDao().clearMessages(username)
            }
            messagesMutex.withLock {
                if (currentUsername == username) {
                    _messages.value = emptyList()
                }
            }
            PluginResult.Success(mapOf("message" to "History cleared"))
        } catch (e: Exception) {
            logger.e("ChatSkill", "Error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Lỗi khi xóa lịch sử")
        }
    }
}