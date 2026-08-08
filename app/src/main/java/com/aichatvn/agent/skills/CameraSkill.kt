package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.execution.IntentExecutor
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.alertActionsFromJson
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.camera.ImageHashTool
import com.aichatvn.agent.tools.camera.LocalVisionTool
import com.aichatvn.agent.tools.camera.LocalVisionResult
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction as DashboardDeviceAction
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.WorldStateHelper
import com.aichatvn.agent.utils.toMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class CameraSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotFetcher: SnapshotFetcher,
    private val imageHashTool: ImageHashTool,
    private val groqClient: GroqClientTool,
    // ✅ MỚI: fallback AI local (ML Kit) — chỉ dùng cho nhánh smartMode TẮT, KHÔNG thay thế Groq.
    // @Inject constructor như ImageHashTool/GroqClientTool, không cần khai báo @Provides riêng
    // trong AppModule.kt.
    private val localVisionTool: LocalVisionTool,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill,
    private val configProvider: AppConfigProvider,
    private val deviceRegistry: DeviceRegistry,
    private val intentExecutorProvider: Provider<IntentExecutor>, 
    private val houseManagerProvider: Provider<HouseManagerSkill>,
    logger: Logger,
) : BaseSkill("camera", "Quản lý camera", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true),
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "scan",
                description = "Quét camera để phát hiện thay đổi và phân tích AI",
                examples = listOf("quét camera", "chụp ảnh camera"),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", true, "camera"),
                    PluginParameter("force", "boolean", "Ép buộc AI phân tích (bỏ qua pHash & cooldown)", false, "boolean")
                )
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái kết nối của camera",
                examples = listOf("trạng thái camera", "kiểm tra camera"),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", true, "camera")
                )
            ),
            PluginAction(
                name = "set_active",
                description = "Bật hoặc tắt theo dõi của camera cụ thể",
                examples = listOf("bật camera", "tắt camera"),
                exampleOverrides = mapOf(
                    "bật camera" to mapOf("active" to true),
                    "tắt camera" to mapOf("active" to false)
                ),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", true, "camera"),
                    PluginParameter("active", "boolean", "Trạng thái bật/tắt", true, "boolean")
                )
            ),
            // Trong CameraSkill.kt - đoạn manifest actions

// Trong CameraSkill.kt - đoạn manifest actions

PluginAction(
    name = "set_smart_mode",
    description = "Bật hoặc tắt chế độ phân tích AI cho camera",
    parameters = listOf(
        PluginParameter("cameraId", "string", "Mã camera", false, "camera"),
        // 🌟 SỬA: Dùng semanticType = "customer" thay cho "string"
        PluginParameter("customerId", "string", "Chọn khách hàng", false, "customer"),
        PluginParameter("enabled", "boolean", "Trạng thái bật/tắt AI", true, "boolean")
    )
),

PluginAction(
    name = "configure",
    description = "Cập nhật cấu hình kỹ thuật cho thiết bị camera",
    parameters = listOf(
        PluginParameter("cameraId", "string", "Mã camera", true, "camera"),
        PluginParameter("aiPrompt", "string", "Prompt AI mới", false, "string"),
        PluginParameter("aiPositiveKeywords", "string", "Từ khoá cảnh báo (chỉ báo động khi khớp — dùng cho cả AI lẫn ML Kit)", false, "string"),
        PluginParameter("aiNegativeKeywords", "string", "Từ khoá bình thường (danh sách tham khảo nhãn ML Kit — copy sang ô cảnh báo để bật báo động cho nhãn đó)", false, "string"),
        PluginParameter("snapshotUrl", "string", "URL ảnh chụp", false, "string"),
        PluginParameter("landInfo", "string", "Thông tin vị trí", false, "string"),
        // 🌟 SỬA: Đổi kiểu từ "number" sang "boolean" để UI vẽ công tắc Switch thay vì ô nhập số
        PluginParameter("enableCooldown", "boolean", "Bật/Tắt cooldown hoãn quét", false, "boolean"),
        PluginParameter("enableNotification", "boolean", "Bật/Tắt gửi thông báo", false, "boolean")
    )
),


            PluginAction(
                name = "mark_false_positive",
                description = "Báo cáo báo động giả cho camera để hệ thống tự học và nâng ngưỡng lọc nhiễu",
                examples = listOf("báo động giả camera", "không có trộm camera", "báo giả"),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", true, "camera"),
                    // 🌟 SỬA: Dùng semanticType = "alert_id" thay cho "string" — mở
                    // Dropdown danh sách cảnh báo gần đây của camera đã chọn ở trên,
                    // thay vì bắt người dùng gõ tay 1 mã cảnh báo mà họ không thể nhớ.
                    PluginParameter("alertId", "string", "Chọn cảnh báo cần báo giả", false, "alert_id",
                        dependsOn = "cameraId")
                )
            ),
            
            PluginAction(
                name = "list_cameras",
                description = "Liệt kê danh sách tất cả các camera hiện có trong hệ thống",
                examples = listOf("danh sách camera", "liệt kê camera"),
                parameters = emptyList()
            )
        )
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val cameraMutexMap = ConcurrentHashMap<String, Mutex>()
    private fun getMutexForCamera(cameraId: String): Mutex =
        cameraMutexMap.getOrPut(cameraId.trim()) { Mutex() }

    // ✅ MỚI (day/night split): trước đây các mẫu học báo giả (falseDeltas/falseDiffs), baseline
    // trung bình (baselineWindow) và ngưỡng suy ra (deltaTrigger/absDiffTrigger) dùng CHUNG 1 bộ
    // cho toàn bộ 24h. Hệ quả: cú sốc pHash khi trời chuyển sáng/tối (camera bật/tắt IR) bị học
    // lẫn với nhiễu ban ngày, kéo ngưỡng lên cao và làm ngưỡng ban ngày "mượn" độ cao đó — khiến
    // người thật đi qua ban ngày (diff/delta thấp hơn cú sốc IR) không còn vượt ngưỡng để báo
    // động nữa. Giờ tách thành 2 bộ độc lập: PeriodLearningState cho "ngày" và "đêm", mỗi bộ tự
    // học/tự decay riêng theo khung giờ cấu hình được (CAMERA_DAY_START_HOUR/NIGHT_START_HOUR).
    private data class PeriodLearningState(
        val falseDeltas: MutableList<Int> = mutableListOf(),
        val falseDiffs: MutableList<Int> = mutableListOf(),
        // timestamp tương ứng 1-1 với falseDeltas/falseDiffs theo index, để "hết hạn" các mẫu
        // nhiễu cũ sau một thời gian ổn định, thay vì ngưỡng chỉ tăng mãi mãi.
        val falseSampleTimestamps: MutableList<Long> = mutableListOf(),
        val baselineWindow: MutableList<Int> = mutableListOf(),
        var deltaTrigger: Int = DEFAULT_DELTA_TRIGGER,
        var absDiffTrigger: Int = DEFAULT_ABS_DIFF_TRIGGER,
        // chỉ để hiển thị chẩn đoán (CameraDetailScreen) — không ảnh hưởng logic isSuddenChange.
        var lastBaselineDiff: Int = 0,
        var lastDrift: Int = 0
    )

    private data class CameraLearningState(
        var lastPhash: String = "",
        var lastDiff: Int = 0,
        var realEvents: Int = 0,
        var cooldownUntil: Long = 0L,
        var lastNormalScanAt: Long = 0L,
        // ✅ MỚI: mốc thời gian lần gần nhất ẢNH được lưu ở nhánh "Bình thường" (dù do có biến
        // động hay do đến hạn định kỳ) — dùng để tính "đã quá 1 tiếng chưa có ảnh nào" mà không
        // cần lưu MỌI lượt quét. Chỉ tồn tại trong RAM (giống lastNormalScanAt) — mất khi app
        // khởi động lại, chấp nhận được vì tối đa chỉ lưu dư 1 ảnh không cần thiết sau restart.
        var lastPeriodicImageAt: Long = 0L,
        val day: PeriodLearningState = PeriodLearningState(),
        val night: PeriodLearningState = PeriodLearningState()
    )

    private data class CircuitBreakerState(
        var offlineCount: Int = 0,
        var offlineSince: Long = 0L,
        var isOpen: Boolean = false,
        var halfOpenAttempted: Boolean = false
    )
    
    private data class PendingResetState(
        var diff: Int = 0,
        var timestamp: Long = 0L
    )
    
    private data class DailyEvent(
        val timestamp: Long,
        val comment: String,
        val imageBytes: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return timestamp == (other as DailyEvent).timestamp && comment == other.comment
        }
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + comment.hashCode()
            return result
        }
    }

    private data class VisionParseResult(
        val displayComment: String,         
        val structuredSuspicious: Boolean?, 
        val objects: List<String>,           
        val rawJson: JSONObject?    
    )

    /**
     * ✅ MỚI: Schema JSON THỐNG NHẤT cho aiStateJson/finalStateJson — dùng chung cho CẢ 3 nguồn
     * phân tích có thể xảy ra khi quét camera:
     *   1. "groq"            — Groq Vision phân tích thành công (mô tả AI đáng tin)
     *   2. "ml_kit_local"    — ML Kit chạy on-device (khi smartMode tắt, có kết quả thật)
     *   3. "pixel_diff_only" — Không có mô tả AI/ML nào (Groq lỗi/hết quota, hoặc ML Kit lỗi/
     *      timeout) — chỉ có suy đoán thô từ so sánh pixel, KHÔNG đáng tin cho quyết định
     *
     * Trước đây: 3 nơi khác nhau trong file này TỰ build JSONObject riêng lẻ — dễ lệch field
     * mỗi khi sửa 1 nơi mà quên sửa nơi khác (đã xảy ra: ML Kit có "hasFace", 2 nơi còn lại
     * không có). Giờ collapse về đúng 1 điểm định nghĩa schema.
     *
     * @param source Ghi NGAY trong JSON (không chỉ ở cột analysisSource riêng của
     *   EventLogEntity) — bất kỳ consumer nào đọc aiStateJson (WorldState, Combined mode, debug
     *   UI...) đều biết ngay độ tin cậy của dữ liệu mà không cần join thêm bảng khác.
     */
    private fun buildVisionStateJson(
        state: String,              // "suspicious" | "normal"
        description: String,
        objects: List<String>,
        source: String,             // "groq" | "ml_kit_local" | "pixel_diff_only"
        hasFace: Boolean = false
    ): String = JSONObject().apply {
        put("state", state)
        put("description", description)
        put("objects", JSONArray(objects))
        put("source", source)
        put("hasFace", hasFace)
    }.toString()
    
    private val learningStates = ConcurrentHashMap<String, CameraLearningState>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val pendingResets = ConcurrentHashMap<String, PendingResetState>()
    private val dailyEvents = ConcurrentHashMap<String, MutableList<DailyEvent>>()
    
    private val _diagnostics = MutableStateFlow<Map<String, Any>>(emptyMap())
    val diagnostics: StateFlow<Map<String, Any>> = _diagnostics.asStateFlow()

    // ✅ MỚI: dùng cho dialog "Thêm Camera" — trả về đúng chuỗi thô đã cấu hình trong AppConfigDefaults
    // thay vì để UI gõ cứng "cảnh báo"/"bình thường" khi tạo camera mới.
    data class NewCameraDefaults(
        val aiPrompt: String,
        val aiPositiveKeywords: String,
        val aiNegativeKeywords: String
    )

    suspend fun getNewCameraDefaults(): NewCameraDefaults = NewCameraDefaults(
        aiPrompt = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT)),
        aiPositiveKeywords = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW)),
        aiNegativeKeywords = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW))
    )

    private suspend fun defaultAiPrompt()       = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT))
    private suspend fun defaultPositiveKw()     = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW)).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private suspend fun defaultNegativeKw()     = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW)).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private suspend fun cooldownDurationMs()    = configProvider.getLong(AppConfigDefaults.CAMERA_COOLDOWN_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_COOLDOWN_MS).toLong())
    private suspend fun maxDailyEvents()        = configProvider.getInt(AppConfigDefaults.CAMERA_MAX_DAILY_EVENTS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_MAX_DAILY_EVENTS).toInt())
    private suspend fun circuitBreakerThreshold() = configProvider.getInt(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_THRESHOLD, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_THRESHOLD).toInt())
    private suspend fun circuitBreakerResetMs() = configProvider.getLong(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_RESET_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_RESET_MS).toLong())
    private suspend fun dailyReportHour()       = configProvider.getInt(AppConfigDefaults.CAMERA_DAILY_REPORT_HOUR, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DAILY_REPORT_HOUR).toInt())
    private suspend fun alertMergeWindowMs()    = configProvider.getLong(AppConfigDefaults.CAMERA_ALERT_MERGE_WINDOW_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_ALERT_MERGE_WINDOW_MS).toLong())
    private suspend fun alertRetentionDays()     = configProvider.getInt(AppConfigDefaults.CAMERA_ALERT_RETENTION_DAYS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_ALERT_RETENTION_DAYS).toInt())
    private suspend fun eventLogRetentionDays()  = configProvider.getInt(AppConfigDefaults.CAMERA_EVENT_LOG_RETENTION_DAYS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_EVENT_LOG_RETENTION_DAYS).toInt())

    // ✅ MỚI (day/night split): khung giờ do người dùng tự chỉnh trong Settings, mặc định 6h/18h.
    private suspend fun dayStartHour()   = configProvider.getInt(AppConfigDefaults.CAMERA_DAY_START_HOUR, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DAY_START_HOUR).toInt())
    private suspend fun nightStartHour() = configProvider.getInt(AppConfigDefaults.CAMERA_NIGHT_START_HOUR, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_NIGHT_START_HOUR).toInt())

    /**
     * ✅ MỚI (day/night split): true nếu thời điểm [now] rơi vào khung giờ "ban đêm" đã cấu hình.
     * Ban ngày = [dayStart, nightStart). Ban đêm = phần còn lại. Nếu cấu hình vô lý
     * (dayStart >= nightStart) thì fallback về mặc định 6/18 để không làm hỏng logic.
     */
    private suspend fun isNightTime(now: Long = System.currentTimeMillis()): Boolean {
        var dayStart = dayStartHour()
        var nightStart = nightStartHour()
        if (dayStart !in 0..23 || nightStart !in 0..23 || dayStart >= nightStart) {
            dayStart = 6
            nightStart = 18
        }
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return hour < dayStart || hour >= nightStart
    }

    private fun periodOf(state: CameraLearningState, isNight: Boolean): PeriodLearningState =
        if (isNight) state.night else state.day
    
    companion object {
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
        // Ngưỡng "trôi nền" (|currentDiff - baselineDiff|) — điều kiện thứ 3 kích hoạt isSuddenChange,
        // độc lập với deltaTrigger/absDiffTrigger. Đặt thành hằng số dùng chung để tránh lệch giá trị
        // giữa nơi tính toán (scanCamera) và nơi báo cáo chẩn đoán (updateDiagnostics).
        const val DRIFT_TRIGGER = 12

        // ✅ MỚI: khoảng cách tối thiểu giữa 2 ảnh "nền" (không do biến động) ở nhánh "Bình
        // thường" — đảm bảo luôn có ít nhất 1 ảnh/tiếng để tra cứu dù camera đứng yên hoàn toàn
        // trong thời gian dài, mà không phải lưu ảnh ở MỌI lượt quét 5 phút.
        const val PERIODIC_IMAGE_INTERVAL_MS = 60 * 60 * 1000L

        // ✅ MỚI (decay): giá trị mặc định để ngưỡng hạ về khi không còn mẫu nhiễu nào "còn hạn".
        const val DEFAULT_DELTA_TRIGGER = 10
        const val DEFAULT_ABS_DIFF_TRIGGER = 18

        // ✅ MỚI (decay): một mẫu nhiễu (false positive) chỉ có giá trị tham chiếu trong khoảng thời
        // gian này. Quá hạn mà không có mẫu mới nào bổ sung/xác nhận lại thì bị loại khỏi tập học,
        // giúp ngưỡng tự hạ dần khi camera đã ổn định trở lại (ví dụ sau đợt chuyển sáng/tối).
        const val FALSE_SAMPLE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private suspend fun buildCameraStatusText(cam: CameraConfigEntity): String {
        if (cam.isOnline != 1) return "Mất kết nối"
        val setting = database.cameraDao().getCustomerSetting(cam.customerId.trim())
        val watching = cam.manualOff == 0
        val aiOn = setting?.smartMode == 1
        return when {
            !watching -> "Đã tắt giám sát"
            aiOn -> "Đang giám sát • AI bật"
            else -> "Đang giám sát • AI tắt"
        }
    }

    override suspend fun getDashboardNodes(): List<DeviceNode> = withContext(Dispatchers.IO) {
        val cameras = database.cameraDao().getAllCameras()
        cameras.mapIndexed { index, cam ->
            val defaultX = 40f + (index % 2) * 160f
            val defaultY = 40f + (index / 2) * 160f

            val savedX = configProvider.getFloat("layout_x_${cam.id.trim()}", -1f)
            val savedY = configProvider.getFloat("layout_y_${cam.id.trim()}", -1f)
            val finalX = if (savedX >= 0f) savedX else defaultX
            val finalY = if (savedY >= 0f) savedY else defaultY

            val isOnline = cam.isOnline == 1

            DeviceNode(
                id = cam.id.trim(),
                name = cam.customername,
                type = DeviceType.CAMERA,
                pluginId = manifest.id,
                defaultAction = "scan",
                defaultParams = mapOf("cameraId" to cam.id.trim()),
                supportedActions = listOf(
                    DashboardDeviceAction(id = "scan", title = "Quét camera", icon = "📸"),
                    DashboardDeviceAction(id = "status", title = "Trạng thái", icon = "ℹ️"),
                    DashboardDeviceAction(id = "set_active", title = "Bật giám sát", icon = "🔔", defaultParams = mapOf("active" to true)),
                    DashboardDeviceAction(id = "set_active", title = "Tắt giám sát", icon = "🔕", defaultParams = mapOf("active" to false)),
                    DashboardDeviceAction(id = "set_smart_mode", title = "Bật AI", icon = "🧠", defaultParams = mapOf("enabled" to true)),
                    DashboardDeviceAction(id = "set_smart_mode", title = "Tắt AI", icon = "🚫", defaultParams = mapOf("enabled" to false))
                ),
                x = finalX,
                y = finalY,
                online = isOnline,
                icon = "📷",
                ip = "192.168.1.${10 + index}",
                battery = null,
                status = buildCameraStatusText(cam),
                room = "Thửa Đất"
            )
        }
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        logger.d("CameraSkill", "execute: action=$action")
        return when (action) {
            "scan" -> handleScan(params)
            "list_cameras" -> handleListCameras()
            "status" -> handleStatus(params)
            "set_active" -> handleSetActive(params)
            "set_smart_mode" -> handleSetSmartMode(params)
            "mark_false_positive" -> handleMarkFalsePositive(params)
            "configure" -> handleConfigure(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
    }

    private suspend fun handleMarkFalsePositive(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val cameraId = (params["cameraId"] as? String)?.trim()
            ?: return@withContext PluginResult.Failure("Thiếu cameraId. Dùng action list_cameras để xem danh sách.")
        val alertId = (params["alertId"] as? String)?.trim()

        val alert = if (!alertId.isNullOrBlank()) {
            database.alertDao().getAlertById(alertId)
        } else {
            database.alertDao().getLatestAlertForCamera(cameraId)
        }

        val diffToLearn = alert?.diff ?: 20
        val deltaToLearn = alert?.delta ?: 12

        // ✅ MỚI (day/night split): truyền THẲNG alert.timestamp (thời điểm báo động THẬT xảy ra)
        // vào markFalsePositiveAndLearn — hàm tự quyết định ngày/đêm bên trong, không bắt call
        // site tự tính isNight nữa. Trước đó tách riêng "tính isNight ở caller rồi truyền vào"
        // đã gây lỗi build thật: AlertHistoryViewModel gọi thẳng markFalsePositiveAndLearn qua
        // 1 đường vào khác (nút bấm trên danh sách Cảnh báo) và bị thiếu tham số — dồn logic
        // ngày/đêm vào 1 chỗ duy nhất trong CameraSkill để không lặp lại lỗi này ở call site mới.
        val message = markFalsePositiveAndLearn(cameraId, diffToLearn, deltaToLearn, alert?.timestamp ?: System.currentTimeMillis())

        if (alert != null) {
            database.alertDao().insertAlert(
                alert.copy(
                    isSuspicious = 0,
                    aiComment = "[Đã xác nhận Báo giả] ${alert.aiComment}"
                )
            )
        }

        PluginResult.Success(mapOf("message" to message))
    }
    
    private suspend fun handleConfigure(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val cameraId = (params["cameraId"] as? String)?.trim()
            ?: return@withContext PluginResult.Failure("Thiếu cameraId. Dùng action list_cameras để xem danh sách camera.")
        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return@withContext PluginResult.Failure("Không tìm thấy camera id=$cameraId")

        val updated = cam.copy(
            aiPrompt = (params["aiPrompt"] as? String)?.trim() ?: cam.aiPrompt,
            aiPositiveKeywords = (params["aiPositiveKeywords"] as? String)?.trim() ?: cam.aiPositiveKeywords,
            aiNegativeKeywords = (params["aiNegativeKeywords"] as? String)?.trim() ?: cam.aiNegativeKeywords,
            snapshoturl = (params["snapshotUrl"] as? String)?.trim() ?: cam.snapshoturl,
            landinfo = (params["landInfo"] as? String)?.trim() ?: cam.landinfo,
            enableCooldown = (params["enableCooldown"] as? Int) ?: (params["enableCooldown"] as? Number)?.toInt() ?: cam.enableCooldown,
            enableNotification = (params["enableNotification"] as? Int) ?: (params["enableNotification"] as? Number)?.toInt() ?: cam.enableNotification
        )
        database.cameraDao().updateCamera(updated)

        val changed = buildList<String> {
            if (updated.aiPrompt != cam.aiPrompt) add("prompt AI")
            if (updated.aiPositiveKeywords != cam.aiPositiveKeywords) add("từ khoá cảnh báo")
            if (updated.aiNegativeKeywords != cam.aiNegativeKeywords) add("từ khoá bình thường")
            if (updated.snapshoturl != cam.snapshoturl) add("URL ảnh chụp")
            if (updated.landinfo != cam.landinfo) add("vị trí")
            if (updated.enableCooldown != cam.enableCooldown) add("hoãn quét (cooldown)")
            if (updated.enableNotification != cam.enableNotification) add("gửi thông báo")
        }
        val summary = if (changed.isEmpty()) "Không có thay đổi" else "Đã cập nhật: ${changed.joinToString(", ")}"
        logger.i("CameraSkill", "configure OK cameraId=$cameraId changed=$changed")
        
        syncToDeviceRegistry()
        PluginResult.Success(mapOf("message" to "✅ Camera \"${cam.customername}\": $summary"))
    }

    private suspend fun handleListCameras(): PluginResult = withContext(Dispatchers.IO) {
        val cameras = database.cameraDao().getAllCameras()
        if (cameras.isEmpty()) return@withContext PluginResult.Success(mapOf("message" to "Chưa có camera nào"))
        val list = cameras.map { c ->
            mapOf(
                "id" to c.id.trim(),
                "name" to c.customername,
                "url" to c.snapshoturl,
                "active" to (c.manualOff == 0),
                "online" to (c.isOnline == 1),
                "enableCooldown" to (c.enableCooldown == 1),
                "enableNotification" to (c.enableNotification == 1)
            )
        }
        val summary = cameras.joinToString("\n") { "• ${it.customername} (id: ${it.id.trim()})" }
        PluginResult.Success(mapOf("cameras" to list, "message" to "Danh sách camera:\n$summary"))
    }
    
    private suspend fun handleScan(params: Map<String, Any>): PluginResult {
        val cameraId = (params["cameraId"] as? String)?.trim()
        val scheduleId = (params["scheduleId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        // ✅ SỬA: trước đây "" (kết quả resolveAliasOrFallback khi NLU không khớp gì cho semanticType
        // "boolean" — RoutingPipeline không có resolver riêng cho boolean) luôn rơi về false, khiến
        // lệnh chat "quét camera" không bao giờ ép buộc AI thật — chỉ so-diff im lặng, sai với ý đồ
        // gốc: gõ lệnh quét tay phải luôn gửi ảnh cho AI phân tích thật (nếu camera đang bật AI/smart
        // mode), không phải chỉ so pixel như quét nền tự động. Ngược lại, khi chạy từ 1 SCHEDULE
        // (scheduleId khác null, do ScheduleSkill gọi thẳng, không qua NLU) — force LUÔN là Boolean
        // true/false tường minh từ ScheduleDraft đã lưu, không bao giờ là chuỗi rỗng — nên nhánh
        // Boolean/String tường minh dưới đây vẫn ưu tiên xử lý đúng, default "true" chỉ áp dụng cho
        // đúng trường hợp lệnh chat thủ công (scheduleId rỗng) và force không được NLU xác định rõ.
        val forceParam = params["force"]
        val force = when {
            forceParam is Boolean -> forceParam
            forceParam is String && forceParam.isNotBlank() -> forceParam.equals("true", ignoreCase = true)
            scheduleId.isNullOrBlank() -> true
            else -> false
        }
        val overrideAlertActions = (params["alertActions"] as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "[]" }
            ?.let { alertActionsFromJson(it) }
            
        val result = scanCamera(
            cameraId,
            false,
            scheduleId = scheduleId,
            forceAi = force,
            overrideAlertActions = overrideAlertActions
        )

        return when (result) {
            is PluginResult.Success -> {
                val data = result.data as? Map<*, *>
                val processed = data?.get("processed") as? Int ?: 0
                val results = data?.get("results") as? List<*> ?: emptyList<Any>()
                val skippedCb = data?.get("skippedCircuitBreaker") as? Int ?: 0
                val skippedIn = data?.get("skippedInactive") as? Int ?: 0
                val warning = data?.get("warning") as? String

                if (warning != null) return PluginResult.Success(mapOf("message" to warning))

                var imagePath: String? = null
                if (cameraId != null) {
                    val single = results.firstOrNull() as? Map<*, *>
                    (single?.get("imageBytes") as? ByteArray)?.let { imagePath = saveChatImage(it) }
                }

                val summary = buildString {
                    append("📷 Đã quét $processed camera")
                    if (skippedCb > 0) append(" ($skippedCb camera bị bỏ qua do lỗi liên tiếp)")
                    if (skippedIn > 0) append(" ($skippedIn camera không hoạt động)")
                    append(".\n")

                    for (r in results) {
                        val cam = r as? Map<*, *> ?: continue
                        val id = (cam["cameraId"] as? String)?.trim() ?: continue
                        val success = cam["success"] as? Boolean ?: false
                        if (!success) {
                            val err = cam["error"] as? String ?: "lỗi không xác định"
                            append("• Camera $id: ❌ $err\n")
                            continue
                        }
                        val hasChange = cam["hasChange"] as? Boolean ?: false
                        val isSuspicious = cam["isSuspicious"] as? Boolean ?: false
                        val aiComment = cam["aiComment"] as? String
                        // ✅ SỬA: trước đây "hasChange" (= isSuddenChange, thuần pixel-diff) bị dùng
                        // làm điều kiện TIÊN QUYẾT để hiện aiComment — khiến quét thủ công (luôn có
                        // phân tích thật từ ML Kit/Groq nhờ isManualTrigger, bất kể pixel đổi nhiều
                        // hay ít) bị tóm tắt thành "✅ Bình thường" trống trơn mỗi khi pixel không đổi
                        // nhiều, dù aiComment thật sự đã có nội dung chi tiết. Giờ ưu tiên hiện
                        // aiComment bất cứ khi nào nó có nội dung thật — hasChange chỉ còn quyết định
                        // CÂU CHỮ mở đầu ("Có biến động" vs "Bình thường"), không quyết định có ẩn
                        // chi tiết hay không.
                        val hasRealComment = aiComment != null && aiComment != "No analysis"
                        when {
                            isSuspicious -> append("• Camera $id: 🚨 CẢNH BÁO — ${aiComment ?: "phát hiện bất thường"} (email đã gửi nếu bật thông báo)\n")
                            hasChange && hasRealComment -> append("• Camera $id: 🔄 Có biến động — $aiComment\n")
                            hasRealComment -> append("• Camera $id: ✅ Bình thường — $aiComment\n")
                            hasChange -> append("• Camera $id: 🔄 Có biến động nhỏ, AI đánh giá bình thường\n")
                            else -> append("• Camera $id: ✅ Bình thường\n")
                        }
                    }
                }.trimEnd()

                PluginResult.Success(mapOf("message" to summary, "imagePath" to imagePath))
            }
            is PluginResult.Failure -> PluginResult.Failure(result.error)
            else -> PluginResult.Failure("Quét thất bại")
        }
    }
    
    private suspend fun handleStatus(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val cameraId = (params["cameraId"] as? String)?.trim()
            ?: return@withContext PluginResult.Failure("Thiếu cameraId. Dùng action list_cameras để xem danh sách.")

        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return@withContext PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")

        val setting = database.cameraDao().getCustomerSetting(cam.customerId)
        val diag = learningStates[cameraId]
        val cb = circuitBreakers[cameraId]

        val text = buildString {
            append("📷 Camera: ${cam.customername} (id: ${cam.id.trim()})\n")
            append("• Trạng thái kết nối: ${if (cam.isOnline == 1) "🟢 Online" else "🔴 Offline"}\n")
            append("• Theo dõi: ${if (cam.manualOff == 0) "Bật" else "Tắt (thủ công)"}\n")
            append("• Khách hàng: ${cam.customerId.trim()} — ${if (setting?.isActive == 1) "Active" else "Inactive"}\n")
            append("• AI Smart Mode: ${if (setting?.smartMode == 1) "Bật" else "Tắt"}\n")
            append("• Bật Cooldown hoãn quét: ${if (cam.enableCooldown == 1) "Bật" else "Tắt"}\n")
            append("• Nhận cảnh báo: ${if (cam.enableNotification == 1) "Bật" else "Tắt"}\n")
            if (cam.landinfo != null) append("• Vị trí: ${cam.landinfo}\n")
            if (diag != null) {
                // ✅ MỚI (day/night split): hiển thị riêng ngưỡng ngày/đêm thay vì 1 cặp chung,
                // để admin thấy được vì sao độ nhạy ban ngày/ban đêm khác nhau.
                append("• Ngưỡng học BAN NGÀY (delta/diff): ${diag.day.deltaTrigger}/${diag.day.absDiffTrigger} (mẫu: ${diag.day.falseDeltas.size})\n")
                append("• Ngưỡng học BAN ĐÊM (delta/diff): ${diag.night.deltaTrigger}/${diag.night.absDiffTrigger} (mẫu: ${diag.night.falseDeltas.size})\n")
                append("• Sự kiện thật: ${diag.realEvents}\n")
                val inCooldown = diag.cooldownUntil > System.currentTimeMillis()
                if (inCooldown) {
                    val remaining = (diag.cooldownUntil - System.currentTimeMillis()) / 60000
                    append("• Cooldown: còn $remaining phút\n")
                }
            }
            if (cb?.isOpen == true) append("• ⚠️ Circuit Breaker OPEN (offline ${cb.offlineCount} lần liên tiếp)\n")
        }.trimEnd()

        return@withContext PluginResult.Success(mapOf("message" to text))
    }
    
    private suspend fun handleSetActive(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val cameraId = (params["cameraId"] as? String)?.trim()
            ?: return@withContext PluginResult.Failure("Bạn muốn bật/tắt camera nào? Dùng list_cameras để xem danh sách.")

        val active = params["active"] as? Boolean
            ?: (params["active"] as? String)?.lowercase()?.let { it == "true" }
            ?: return@withContext PluginResult.Failure("Bạn muốn bật hay tắt camera $cameraId?")

        val cam = database.cameraDao().getCameraById(cameraId)
            ?: return@withContext PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")

        val newManualOff = if (active) 0 else 1
        if (cam.manualOff == newManualOff) {
            val state = if (active) "đang bật" else "đang tắt"
            return@withContext PluginResult.Success(mapOf("message" to "📷 Camera \"${cam.customername}\" đã $state rồi, không cần thay đổi."))
        }

        val updatedCam = cam.copy(manualOff = newManualOff)
        database.cameraDao().updateCamera(updatedCam)
        logger.i("CameraSkill", "set_active cameraId=$cameraId active=$active (manualOff=$newManualOff)")

        val newStatusText = buildCameraStatusText(updatedCam)

        deviceRegistry.updateNode(cam.id.trim()) { current ->
            current.copy(
                status = newStatusText,
                lastSeen = System.currentTimeMillis()
            )
        }

        val msg = if (active)
            "✅ Đã bật theo dõi camera \"${cam.customername}\" — sẽ được quét ở lần scan tiếp theo."
        else
            "✅ Đã tắt theo dõi camera \"${cam.customername}\" — sẽ không quét cho đến khi bật lại."
        PluginResult.Success(mapOf("message" to msg))
    }
    
    private suspend fun handleSetSmartMode(params: Map<String, Any>): PluginResult = withContext(Dispatchers.IO) {
        val enabled = params["enabled"] as? Boolean
            ?: (params["enabled"] as? String)?.lowercase()?.let { it == "true" }
            ?: return@withContext PluginResult.Failure("Bạn muốn bật hay tắt AI smart mode?")

        val resolvedCustomerId: String
        val cameraName: String

        val cameraId = (params["cameraId"] as? String)?.trim()
        if (!cameraId.isNullOrBlank() && cameraId != "null") {
            val cam = database.cameraDao().getCameraById(cameraId)
                ?: return@withContext PluginResult.Failure("Không tìm thấy camera id=$cameraId. Dùng list_cameras để xem danh sách đúng.")
            resolvedCustomerId = cam.customerId.trim()
            cameraName = cam.customername
        } else {
            val customerId = (params["customerId"] as? String)?.trim()
                ?: return@withContext PluginResult.Failure("Thiếu cameraId hoặc customerId. Dùng list_cameras để xem danh sách.")
            val setting = database.cameraDao().getCustomerSetting(customerId)
            if (setting == null) {
                val cam = database.cameraDao().getCameraById(customerId)
                if (cam != null) {
                    resolvedCustomerId = cam.customerId.trim()
                    cameraName = cam.customername
                } else {
                    return@withContext PluginResult.Failure("Không tìm thấy customerId=$customerId và cũng không phải cameraId hợp lệ.")
                }
            } else {
                resolvedCustomerId = customerId
                val cameras = database.cameraDao().getCamerasByCustomer(customerId)
                cameraName = cameras.firstOrNull()?.customername ?: customerId
            }
        }

        val result = setSmartMode(resolvedCustomerId, enabled)
        if (result is PluginResult.Success) {
            val cameras = database.cameraDao().getCamerasByCustomer(resolvedCustomerId)
            cameras.forEach { camera ->
                val newStatusText = buildCameraStatusText(camera)
                deviceRegistry.updateNode(camera.id.trim()) { current ->
                    current.copy(
                        status = newStatusText
                    )
                }
            }
        }

        return@withContext when (result) {
            is PluginResult.Success -> {
                val state = if (enabled) "bật" else "tắt"
                PluginResult.Success(mapOf(
                    "message" to "✅ Đã $state AI Smart Mode cho camera \"$cameraName\" — AI sẽ ${if (enabled) "phân tích ảnh và gửi cảnh báo khi phát hiện bất thường." else "không được gọi, chỉ so sánh ảnh bằng pHash."}"
                ))
            }
            is PluginResult.Failure -> result
            else -> PluginResult.Failure("Không thể thực hiện")
        }
    }
    
    private val pruneMutex = Mutex()
    
    override suspend fun initialize() {
        val cameras = withContext(Dispatchers.IO) {
            database.cameraDao().getActiveCameras()
        }
        cameras.forEach { camera ->
            val tid = camera.id.trim()
            // 🧠 NẠP LẠI MẪU HỌC TỪ SQLITE (Bảo vệ dữ liệu khi RAM bị xóa / Reboot App)
            val restoredState = loadLearningStateFromDb(tid)
            learningStates[tid] = restoredState
            circuitBreakers[tid] = CircuitBreakerState()
        }
        updateDiagnostics()
        cleanupOldAlerts()
        cleanupOldEventLogs() 
        pruneOrphanedCameraState()
        scheduleDailyReport()
        
        syncToDeviceRegistry()
    }

    private suspend fun syncToDeviceRegistry() {
        try {
            val initialNodes = getDashboardNodes()
            deviceRegistry.registerNodes(manifest.id, initialNodes)
        } catch (e: Exception) {
            logger.e("CameraSkill", "Khởi tạo sơ đồ camera lên bản sao số thất bại", e)
        }
    }
    
    private suspend fun pruneOrphanedCameraState() {
        try {
            pruneMutex.withLock {
                val allIds = withContext(Dispatchers.IO) {
                    database.cameraDao().getAllCamerasFlow().first().map { it.id.trim() }.toSet()
                }
                val orphans = (learningStates.keys + circuitBreakers.keys + pendingResets.keys + dailyEvents.keys + cameraMutexMap.keys) - allIds
                orphans.forEach { id ->
                    val tid = id.trim()
                    learningStates.remove(tid)
                    circuitBreakers.remove(tid)
                    pendingResets.remove(tid)
                    dailyEvents.remove(tid)
                    cameraMutexMap.remove(tid)
                    
                    scope.launch(Dispatchers.IO) {
                        database.worldStateDao().deleteStateBySourceAndId("camera", tid)
                        database.worldStateDao().deleteStateBySourceAndId("system", "cam_learning_$tid")
                    }
                }
                if (orphans.isNotEmpty()) {
                    logger.i("CameraSkill", "🧹 Pruned ${orphans.size} orphaned camera state entries")
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "pruneOrphanedCameraState error: ${e.message}", e)
        }
    }

    /**
     * ✅ MỚI (decay, day/night split): Loại bỏ các mẫu nhiễu (false positive) đã quá hạn
     * (FALSE_SAMPLE_MAX_AGE_MS) khỏi 3 list song song của MỘT khung giờ (ngày HOẶC đêm), giữ
     * nguyên thứ tự index. Trả về true nếu có ít nhất 1 mẫu bị loại (để caller biết cần lưu DB).
     */
    private fun pruneExpiredFalseSamples(period: PeriodLearningState, now: Long): Boolean {
        if (period.falseSampleTimestamps.isEmpty()) return false
        val cutoff = now - FALSE_SAMPLE_MAX_AGE_MS
        var removedAny = false
        var i = 0
        while (i < period.falseSampleTimestamps.size) {
            if (period.falseSampleTimestamps[i] < cutoff) {
                period.falseSampleTimestamps.removeAt(i)
                if (i < period.falseDeltas.size) period.falseDeltas.removeAt(i)
                if (i < period.falseDiffs.size) period.falseDiffs.removeAt(i)
                removedAny = true
            } else {
                i++
            }
        }
        return removedAny
    }

    /**
     * ✅ MỚI (decay, day/night split): Tính lại deltaTrigger/absDiffTrigger của MỘT khung giờ từ
     * các mẫu nhiễu HIỆN CÒN HẠN của khung giờ đó.
     * - Nếu không còn mẫu nào (đã hết hạn hết) -> hạ thẳng về mặc định (DEFAULT_*).
     * - Nếu còn mẫu -> áp dụng lại đúng công thức percentile-90% + margin như cũ.
     * Trả về true nếu deltaTrigger hoặc absDiffTrigger thay đổi.
     */
    private fun recomputeThresholdsFromSamples(period: PeriodLearningState): Boolean {
        val oldDelta = period.deltaTrigger
        val oldDiff = period.absDiffTrigger

        if (period.falseDeltas.isEmpty()) {
            period.deltaTrigger = DEFAULT_DELTA_TRIGGER
            period.absDiffTrigger = DEFAULT_ABS_DIFF_TRIGGER
        } else {
            val recentDeltas = period.falseDeltas.takeLast(30).sorted()
            val idx = (recentDeltas.size * 0.9).toInt().coerceIn(0, recentDeltas.size - 1)
            period.deltaTrigger = (recentDeltas[idx] + 2).coerceIn(DEFAULT_DELTA_TRIGGER, 25)

            val recentDiffs = period.falseDiffs.takeLast(30).sorted()
            val idxDiff = (recentDiffs.size * 0.9).toInt().coerceIn(0, recentDiffs.size - 1)
            period.absDiffTrigger = (recentDiffs[idxDiff] + 3).coerceIn(DEFAULT_ABS_DIFF_TRIGGER, 35)
        }

        return period.deltaTrigger != oldDelta || period.absDiffTrigger != oldDiff
    }

    /**
     * 💾 Lưu trạng thái tự học (Mẫu học + Ngưỡng nhạy cảm) vào SQLite.
     * ✅ MỚI (day/night split): lưu 2 khối "day"/"night" độc lập trong cùng 1 JSON.
     */
    private suspend fun saveLearningStateToDb(cameraId: String, state: CameraLearningState) {
        try {
            val json = JSONObject().apply {
                put("realEvents", state.realEvents)
                put("day", periodToJson(state.day))
                put("night", periodToJson(state.night))
            }.toString()

            WorldStateHelper.setAttribute(
                database.worldStateDao(),
                source = "system",
                sourceId = "cam_learning_$cameraId",
                key = "state",
                value = json
            )
        } catch (e: Exception) {
            logger.e("CameraSkill", "Không thể lưu LearningState vào DB: ${e.message}")
        }
    }

    private fun periodToJson(period: PeriodLearningState): JSONObject = JSONObject().apply {
        put("deltaTrigger", period.deltaTrigger)
        put("absDiffTrigger", period.absDiffTrigger)
        put("falseDeltas", JSONArray(period.falseDeltas))
        put("falseDiffs", JSONArray(period.falseDiffs))
        put("falseSampleTimestamps", JSONArray(period.falseSampleTimestamps))
    }

    private fun periodFromJson(json: JSONObject?): PeriodLearningState {
        if (json == null) return PeriodLearningState()
        val falseDeltas = mutableListOf<Int>()
        val falseDiffs = mutableListOf<Int>()

        json.optJSONArray("falseDeltas")?.let { arr ->
            for (i in 0 until arr.length()) falseDeltas.add(arr.getInt(i))
        }
        json.optJSONArray("falseDiffs")?.let { arr ->
            for (i in 0 until arr.length()) falseDiffs.add(arr.getInt(i))
        }

        val now = System.currentTimeMillis()
        val timestamps = mutableListOf<Long>()
        val timestampsArr = json.optJSONArray("falseSampleTimestamps")
        if (timestampsArr != null && timestampsArr.length() == falseDeltas.size) {
            for (i in 0 until timestampsArr.length()) timestamps.add(timestampsArr.getLong(i))
        } else {
            repeat(falseDeltas.size) { timestamps.add(now) }
        }

        return PeriodLearningState(
            falseDeltas = falseDeltas,
            falseDiffs = falseDiffs,
            falseSampleTimestamps = timestamps,
            deltaTrigger = json.optInt("deltaTrigger", DEFAULT_DELTA_TRIGGER),
            absDiffTrigger = json.optInt("absDiffTrigger", DEFAULT_ABS_DIFF_TRIGGER)
        )
    }

    /**
     * 🔄 Đọc lại trạng thái tự học từ SQLite khi ứng dụng khởi động lại.
     * ✅ MỚI (day/night split): nếu dữ liệu cũ (trước bản vá) chỉ có 1 bộ mẫu phẳng (không có
     * "day"/"night"), coi toàn bộ mẫu cũ đó thuộc về khung giờ ĐANG DIỄN RA tại thời điểm load
     * (không rải đều cho cả 2 khung giờ) — vì không biết chính xác mẫu cũ thuộc ngày hay đêm, gán
     * hết vào khung giờ hiện tại là lựa chọn an toàn nhất: khung giờ còn lại bắt đầu "sạch" ở mức
     * mặc định, và khung giờ nhận dữ liệu cũ sẽ tự phân kỳ dần theo dữ liệu mới từ đây, thay vì để
     * cả 2 khung giờ cùng bị kẹt lại đúng vấn đề cũ đang muốn sửa.
     */
    private suspend fun loadLearningStateFromDb(cameraId: String): CameraLearningState {
        return try {
            val rawJson = WorldStateHelper.getAttribute(
                database.worldStateDao(),
                source = "system",
                sourceId = "cam_learning_$cameraId",
                key = "state"
            ) ?: return CameraLearningState()

            val json = JSONObject(rawJson)

            if (json.has("day") || json.has("night")) {
                CameraLearningState(
                    realEvents = json.optInt("realEvents", 0),
                    day = periodFromJson(json.optJSONObject("day")),
                    night = periodFromJson(json.optJSONObject("night"))
                )
            } else {
                // Định dạng cũ (phẳng, trước khi tách ngày/đêm) — migrate 1 lần.
                val legacyPeriod = periodFromJson(json)
                val isNight = isNightTime()
                CameraLearningState(
                    realEvents = json.optInt("realEvents", 0),
                    day = if (isNight) PeriodLearningState() else legacyPeriod,
                    night = if (isNight) legacyPeriod else PeriodLearningState()
                )
            }
        } catch (e: Exception) {
            CameraLearningState()
        }
    }

    /**
     * 🧠 HÀM PHẢN HỒI BÁO ĐỘNG GIẢ (Feedback-Based Learning)
     * Dùng cho cả Chế độ AI & Không-AI khi người dùng bấm nút "Báo động giả" trên UI hoặc Chat —
     * MỌI call site (Chat action, nút bấm trên AlertHistoryScreen, v.v.) đều gọi qua chữ ký DUY
     * NHẤT này.
     * ✅ MỚI (day/night split): [alertTimestamp] là thời điểm báo động THẬT sự xảy ra (mặc định
     * = hiện tại nếu không có, ví dụ báo giả không gắn với alert cụ thể nào). Hàm tự suy ra
     * ngày/đêm bên trong bằng isNightTime() — cố tình KHÔNG bắt caller tự tính rồi truyền
     * Boolean vào, vì đã có 1 call site (AlertHistoryViewModel.markAsFalsePositive) bị bỏ sót và
     * làm vỡ build khi tham số đó được thêm vào signature không có giá trị mặc định.
     */
    suspend fun markFalsePositiveAndLearn(
        cameraId: String,
        diff: Int,
        delta: Int,
        alertTimestamp: Long = System.currentTimeMillis()
    ): String {
        val tid = cameraId.trim()
        val cam = database.cameraDao().getCameraById(tid) ?: return "Không tìm thấy camera $tid"
        
        return getMutexForCamera(tid).withLock {
            val state = learningStates.getOrPut(tid) { loadLearningStateFromDb(tid) }
            val isNight = isNightTime(alertTimestamp)
            val period = periodOf(state, isNight)
            val now = System.currentTimeMillis()

            // ✅ MỚI (decay): loại mẫu cũ đã hết hạn trước khi nạp mẫu mới, để tập học không bị
            // "cõng" mãi những cú sốc sáng/tối từ nhiều ngày trước.
            pruneExpiredFalseSamples(period, now)

            // Nạp mẫu học phản hồi từ người dùng
            period.falseDeltas.add(delta)
            period.falseDiffs.add(diff)
            period.falseSampleTimestamps.add(now)
            if (period.falseDeltas.size > 100) {
                period.falseDeltas.removeAt(0)
                period.falseDiffs.removeAt(0)
                period.falseSampleTimestamps.removeAt(0)
            }

            val oldDelta = period.deltaTrigger
            val oldDiff = period.absDiffTrigger

            // Tự động điều chỉnh nâng ngưỡng (dùng chung công thức percentile với decay)
            recomputeThresholdsFromSamples(period)

            // Đảm bảo ngưỡng mới luôn cao hơn nhiễu vừa báo giả
            if (period.absDiffTrigger <= diff) {
                period.absDiffTrigger = (diff + 2).coerceAtMost(35)
            }

            // Lưu ngay vào SQLite
            saveLearningStateToDb(tid, state)
            updateDiagnostics()

            val periodLabel = if (isNight) "BAN ĐÊM" else "BAN NGÀY"
            "✅ Đã ghi nhận báo động giả ($periodLabel) cho Camera \"${cam.customername}\". " +
            "Ngưỡng diff nâng từ $oldDiff ➔ ${period.absDiffTrigger}, " +
            "ngưỡng delta nâng từ $oldDelta ➔ ${period.deltaTrigger} (Số mẫu học: ${period.falseDeltas.size})."
        }
    }
    
    private fun cleanupOldAlerts() {
        scope.launch {
            try {
                val retentionDays = alertRetentionDays()
                val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
                val dir = File(context.filesDir, "alert_images")
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) file.delete()
                    }
                }
                withContext(Dispatchers.IO) {
                    database.alertDao().deleteAlertsOlderThan(cutoff)
                }
            } catch (e: Exception) {
                logger.e("CameraSkill", "cleanupOldAlerts error: ${e.message}", e)
            }
        }
    }

    private fun cleanupOldEventLogs() {
        scope.launch {
            try {
                val retentionDays = eventLogRetentionDays()
                val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
                withContext(Dispatchers.IO) {
                    database.eventLogDao().pruneLogsOlderThan(cutoff)
                }
                // ✅ MỚI: chat_images/ giờ nhận ảnh từ MỌI lượt quét "Bình thường" (không chỉ
                // forceAi nữa — xem comment ở resolvedNormalImagePath trong processImageWithLearning()), nên
                // cần dọn file định kỳ giống hệt alert_images/ ở cleanupOldAlerts(), nếu không sẽ
                // phình dung lượng vô hạn vì trước đây thư mục này gần như không có file nào.
                // Dùng chung cutoff với event_logs (cùng retentionDays) để ảnh và dòng log tương
                // ứng hết hạn cùng lúc — tránh vênh giữa "log đã xoá nhưng file ảnh còn" (rác) hoặc
                // ngược lại (log tham chiếu path đã bị xoá).
                cleanupChatImages(cutoff)
            } catch (e: Exception) {
                logger.e("CameraSkill", "cleanupOldEventLogs error: ${e.message}", e)
            }
        }
    }

    private fun cleanupChatImages(cutoff: Long) {
        try {
            val dir = File(context.filesDir, "chat_images")
            if (dir.exists()) {
                var deletedCount = 0
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        if (file.delete()) deletedCount++
                    }
                }
                if (deletedCount > 0) {
                    logger.i("CameraSkill", "🧹 Đã dọn $deletedCount ảnh cũ trong chat_images/")
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "cleanupChatImages error: ${e.message}", e)
        }
    }
    
    private fun scheduleDailyReport() {
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val reportHour = dailyReportHour()
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, reportHour)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                
                var nextRun = calendar.timeInMillis
                if (nextRun <= now) {
                    nextRun += 24 * 60 * 60 * 1000L
                }
                
                val delay = nextRun - now
                delay(delay)
                sendDailyReports()
                pruneOrphanedCameraState()
                // ✅ MỚI: trước đây cleanupOldAlerts()/cleanupOldEventLogs() chỉ chạy 1 lần lúc
                // initialize() (cold start) — đủ khi chat_images/ gần như trống, nhưng giờ thư
                // mục này nhận ảnh liên tục từ MỌI lượt quét nền (kể cả forceAi=false), nên cần
                // dọn định kỳ thay vì chỉ chờ app khởi động lại.
                cleanupOldAlerts()
                cleanupOldEventLogs()
            }
        }
    }
    
    private suspend fun sendDailyReports() {
        try {
            val customers = withContext(Dispatchers.IO) {
                database.cameraDao().getActiveCameras()
            }.groupBy { it.customerId.trim() }
            
            for ((customerId, cameras) in customers) {
                val events = mutableListOf<Map<String, Any>>()
                for (camera in cameras) {
                    val tid = camera.id.trim()
                    val dailyEventsList = dailyEvents[tid] ?: continue
                    if (dailyEventsList.isNotEmpty()) {
                        events.add(mapOf(
                            "cameraId" to tid,
                            "cameraName" to camera.customername,
                            "landInfo" to (camera.landinfo ?: "N/A"),
                            "events" to dailyEventsList.map { event ->
                                mapOf(
                                    "time" to TIME_FORMATTER.format(Instant.ofEpochMilli(event.timestamp)),
                                    "comment" to event.comment
                                )
                            },
                            "hasImage" to dailyEventsList.any { it.imageBytes != null }
                        ))
                        dailyEvents[tid]?.clear()
                    }
                }
                
                if (events.isNotEmpty()) {
                    val customerSetting = withContext(Dispatchers.IO) {
                        database.cameraDao().getCustomerSetting(customerId)
                    }
                    val customerEmail = cameras.firstOrNull()?.customeremail
                    
                    if (customerEmail != null && customerSetting?.isActive == 1) {
                        sendDailySummaryEmail(customerEmail, customerId, events)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "Error sending daily reports: ${e.message}", e)
        }
    }
    
    private suspend fun sendDailySummaryEmail(to: String, customerId: String, events: List<Map<String, Any>>) {
        val subject = "📋 BÁO CÁO GIÁM SÁT ĐỊNH KỲ - ${customerId.trim()} - ${DATE_FORMATTER.format(Instant.now())}"
        
        val body = buildString {
            append("<html><body style='font-family: Arial, sans-serif;'>")
            append("<h2>BÁO CÁO GIÁM SÁT </h2>")
            append("<p>Xin chào Quý khách hàng <b>${customerId.trim()}</b>,</p>")
            append("<p>Hệ thống gửi báo cáo hôm nay (${events.size} camera có sự kiện):</p>")
            
            for (event in events) {
                append("<div style='margin-bottom: 20px; padding: 10px; border: 1px solid #ccc; border-radius: 5px;'>")
                append("<h3>📷 Camera: ${event["cameraName"]} - ${event["landInfo"]}</h3>")
                
                @Suppress("UNCHECKED_CAST")
                val eventList = event["events"] as? List<Map<String, String>> ?: emptyList()
                if (eventList.isNotEmpty()) {
                    append("<div style='background: #fff3cd; padding: 10px; border-left: 4px solid #ffc107;'>")
                    append("<strong>⚠️ NHẬT KÝ BIẾN CỐ:</strong><br>")
                    for (ev in eventList) {
                        append("• <b>[${ev["time"]}]</b>: ${ev["comment"]}<br>")
                    }
                    append("</div>")
                }
                append("</div>")
            }
            
            append("<p style='font-size: 12px; color: #666;'>Hệ thống camera giám sát AI tự động — AIChatVN2</p>")
            append("</body></html>")
        }
        
        emailSkill.sendEmail(to, subject, body, null)
    }
    
    private suspend fun isCircuitBreakerOpen(cameraId: String): Boolean {
        val tid = cameraId.trim()
        val cb = circuitBreakers[tid] ?: return false
        if (!cb.isOpen) return false

        val elapsed = System.currentTimeMillis() - cb.offlineSince
        val resetMs = circuitBreakerResetMs()

        if (elapsed > resetMs) {
            if (!cb.halfOpenAttempted) {
                cb.halfOpenAttempted = true
                logger.i("CameraSkill", "🔁 Circuit Breaker HALF-OPEN for camera $tid - thử lại...")
                return false
            } else {
                cb.offlineSince = System.currentTimeMillis()
                cb.halfOpenAttempted = false
                logger.w("CameraSkill", "🔌 Circuit Breaker reset timer for camera $tid")
                return true
            }
        }
        return true
    }
    
    private suspend fun recordOffline(cameraId: String) {
        val tid = cameraId.trim()
        val cb = circuitBreakers.getOrPut(tid) { CircuitBreakerState() }
        cb.offlineCount++
        cb.halfOpenAttempted = false
        val threshold = circuitBreakerThreshold()
        if (cb.offlineCount >= threshold) {
            cb.isOpen = true
            cb.offlineSince = System.currentTimeMillis()
            logger.w("CameraSkill", "🔌 Circuit Breaker OPEN for camera $tid (offline ${cb.offlineCount} times)")
        }
    }
    
    private suspend fun recordOnline(cameraId: String) {
        val tid = cameraId.trim()
        circuitBreakers[tid] = CircuitBreakerState()
        deviceRegistry.updateOnlineStatus(tid, true, status = "Đang hoạt động")
    }
    
    private fun checkPendingReset(cameraId: String, currentDiff: Int, absDiffTrigger: Int): Boolean {
        val tid = cameraId.trim()
        val pending = pendingResets[tid] ?: return false
        
        val diffChange = kotlin.math.abs(currentDiff - pending.diff)
        val timeSince = System.currentTimeMillis() - pending.timestamp
        
        if (timeSince > 60 * 60 * 1000L && diffChange <= 5 && currentDiff >= absDiffTrigger / 2) {
            logger.w("CameraSkill", "🔄 Pending Reset triggered for camera $tid - resetting learning state")
            pendingResets.remove(tid)
            return true
        }
        
        if (currentDiff < absDiffTrigger / 2) {
            pendingResets.remove(tid)
        }
        
        return false
    }
    
    suspend fun scanCamera(
        cameraId: String?,
        isDailyReport: Boolean,
        scheduleId: String? = null,
        forceAi: Boolean = false,
        overrideAlertActions: List<AlertActionConfig>? = null
    ): PluginResult {
        return try {
            val cameras = if (!cameraId.isNullOrBlank() && cameraId != "null") {
                withContext(Dispatchers.IO) {
                    listOfNotNull(database.cameraDao().getCameraById(cameraId.trim()))
                }
            } else {
                withContext(Dispatchers.IO) {
                    database.cameraDao().getActiveCameras()
                }
            }

            if (cameras.isEmpty()) {
                logger.w("CameraSkill", "scanCamera: không có camera nào trong DB" +
                    if (cameraId != null) " (cameraId=$cameraId không tồn tại)" else " (chưa thêm camera)")
                return PluginResult.Success(
                    mapOf(
                        "processed" to 0,
                        "results" to emptyList<Any>(),
                        "warning" to "Chưa có camera nào được cấu hình"
                    )
                )
            }

            val results = mutableListOf<Map<String, Any>>()
            var skippedCircuitBreaker = 0
            var skippedInactive = 0

            for (camera in cameras) {
                val tid = camera.id.trim()
                if (!isDailyReport && isCircuitBreakerOpen(tid)) {
                    logger.w("CameraSkill", "⏭️ Circuit Breaker OPEN - skipping camera $tid")
                    skippedCircuitBreaker++
                    continue
                }

                val customerSetting = withContext(Dispatchers.IO) {
                    database.cameraDao().getCustomerSetting(camera.customerId.trim())
                }
                if (customerSetting?.isActive != 1) {
                    logger.d("CameraSkill", "⏭️ Camera $tid skipped: " +
                        if (customerSetting == null) "không có customerSetting (chưa tạo?)"
                        else "isActive=${customerSetting.isActive} (không phải 1)")
                    skippedInactive++
                    continue
                }

                val result = if (isDailyReport) {
                    scanForDailyReport(camera)
                } else {
                    scanWithLearning(
                        camera,
                        customerSetting.smartMode == 1 && camera.smartMode == 1,
                        scheduleId = scheduleId,
                        forceAi = forceAi,
                        overrideAlertActions = overrideAlertActions
                    )
                }
                results.add(result)
            }

            if (results.isEmpty() && cameras.isNotEmpty()) {
                logger.w("CameraSkill", "scanCamera: có ${cameras.size} camera nhưng 0 được xử lý " +
                    "(skippedCircuitBreaker=$skippedCircuitBreaker, skippedInactive=$skippedInactive)")
            }

            PluginResult.Success(
                mapOf(
                    "processed" to results.size,
                    "results" to results,
                    "skippedCircuitBreaker" to skippedCircuitBreaker,
                    "skippedInactive" to skippedInactive
                )
            )

        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Camera scan failed")
        }
    }
    
    suspend fun processImage(cameraId: String, imageBytes: ByteArray): PluginResult {
        return try {
            val tid = cameraId.trim()
            val camera = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(tid)
            }
            if (camera == null) {
                logger.w("CameraSkill", "processImage: camera not found: $tid")
                return PluginResult.Failure("Camera not found")
            }
            
            val customerSetting = withContext(Dispatchers.IO) {
                database.cameraDao().getCustomerSetting(camera.customerId.trim())
            }
            if (customerSetting?.isActive != 1) {
                logger.d("CameraSkill", "processImage: camera $tid is inactive, skipping")
                return PluginResult.Success(mapOf("skipped" to true))
            }
            
            val result = processImageWithLearning(camera, imageBytes, customerSetting.smartMode == 1 && camera.smartMode == 1)
            PluginResult.Success(result)
            
        } catch (e: Exception) {
            logger.e("CameraSkill", "processImage error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Process image failed")
        }
    }

    private fun parseVisionResult(raw: String): VisionParseResult {
        val cleanedRaw = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

        val start = cleanedRaw.indexOf('{')
        val end = cleanedRaw.lastIndexOf('}')

        val cleaned = if (start in 0 until end) {
            cleanedRaw.substring(start, end + 1).trim()
        } else {
            cleanedRaw
        }

        return try {
            val json = JSONObject(cleaned)
            val state = json.optString("state", "").lowercase().trim()
            val structuredSuspicious = when (state) {
                "suspicious" -> true
                "normal" -> false
                else -> null 
            }
            
            val objectsRaw = json.optJSONArray("objects")
            val objects = mutableListOf<String>()
            val objectNames = mutableListOf<String>()
            
            if (objectsRaw != null) {
                for (i in 0 until objectsRaw.length()) {
                    val item = objectsRaw.optString(i, "").trim().lowercase()
                    if (item.isNotEmpty()) {
                        objects.add(item)
                        if (item !in listOf("người", "nguoi", "đồ vật", "do vat", "động vật", "dong vat", "thực vật", "thuc vat")) {
                            objectNames.add(item)
                        }
                    }
                }
            }

            val rawDescription = json.optString("description", "").trim()
            val description = rawDescription.ifBlank {
                if (objectNames.isNotEmpty()) {
                    "Phát hiện: ${objectNames.distinct().joinToString(", ")}"
                } else {
                    "Đã phân tích ảnh nhưng AI không cung cấp mô tả chi tiết."
                }
            }
            
            VisionParseResult(
                displayComment = description, 
                structuredSuspicious = structuredSuspicious, 
                objects = objects.distinct(), 
                rawJson = json
            )
        } catch (e: Exception) {
            logger.w("CameraSkill", "⚠️ Vision JSON parse thất bại (${e.message}) — model không trả đúng schema JSON yêu cầu.")
            VisionParseResult(displayComment = cleanedRaw, structuredSuspicious = null, objects = emptyList(), rawJson = null)
        }
    }

    private suspend fun processImageWithLearning(
        camera: CameraConfigEntity,
        imageBytes: ByteArray,
        isSmartMode: Boolean,
        scheduleId: String? = null,
        forceAi: Boolean = false,
        overrideAlertActions: List<AlertActionConfig>? = null
    ): Map<String, Any> {
        val tid = camera.id.trim()
        return getMutexForCamera(tid).withLock {
            val state = learningStates.getOrPut(tid) { loadLearningStateFromDb(tid) }
            val now = System.currentTimeMillis()
            // ✅ MỚI (day/night split): xác định khung giờ CỦA LẦN QUÉT NÀY và luôn thao tác trên
            // đúng bộ ngưỡng/mẫu học của khung giờ đó (period). state.lastPhash/lastDiff vẫn dùng
            // CHUNG (không tách) vì đó là chuỗi liên tục giữa 2 khung hình kề nhau bất kể ngày/đêm —
            // chỉ có ngưỡng suy ra từ lịch sử báo giả và baseline trung bình mới cần tách riêng.
            val isNight = isNightTime(now)
            val period = periodOf(state, isNight)
            
            try {
                recordOnline(tid)
                
                val currentPhash = imageHashTool.calculatePhash(imageBytes)
                val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
                
                var currentDiff = 0
                if (state.lastPhash.isNotEmpty()) {
                    currentDiff = imageHashTool.calculateHammingDistance(state.lastPhash, currentPhash)
                }
                
                val delta = kotlin.math.abs(currentDiff - state.lastDiff)
                val deltaTrigger = period.deltaTrigger
                val absDiffTrigger = period.absDiffTrigger
                val driftTrigger = DRIFT_TRIGGER
                
                val baselineDiff = if (period.baselineWindow.isNotEmpty()) {
                    period.baselineWindow.average().toInt()
                } else {
                    currentDiff
                }
                val drift = kotlin.math.abs(currentDiff - baselineDiff)
                period.lastBaselineDiff = baselineDiff
                period.lastDrift = drift
                
                val isSuddenChange = delta >= deltaTrigger || 
                                     currentDiff >= absDiffTrigger || 
                                     drift >= driftTrigger
                
                val isMature = period.baselineWindow.size >= 3
                
                val shouldReset = checkPendingReset(tid, currentDiff, absDiffTrigger)
                if (shouldReset) {
                    val resetState = CameraLearningState(
                        lastPhash = currentPhash,
                        lastDiff = currentDiff
                    )
                    learningStates[tid] = resetState
                    saveLearningStateToDb(tid, resetState)
                    return@withLock mapOf(
                        "cameraId" to tid,
                        "success" to true,
                        "hasChange" to false,
                        "message" to "Learning reset due to stable scene change"
                    )
                }

                // ✅ MỚI: Tách "quét thủ công" (chat gõ lệnh / nút Test trong Camera Details — cả 2
                // đều gọi scanCamera() KHÔNG kèm scheduleId) ra khỏi khái niệm forceAi. Trước đây
                // 2 khái niệm này bị trộn lẫn qua tham số forceAi (vd handleScan() default force=true
                // khi scheduleId rỗng) — dẫn tới hành vi không nhất quán giữa lệnh chat và nút Test
                // (nút Test gọi scanCamera() với forceAi=false mặc định, nên KHÔNG hề ép phân tích).
                // Giờ quy tắc rõ ràng theo đúng yêu cầu:
                //   - KHÔNG có scheduleId (thủ công: chat "quét camera" hoặc nút Test) -> LUÔN phân
                //     tích thật, hoàn toàn theo isSmartMode (bật -> Groq, tắt -> ML Kit), bất kể
                //     isSuddenChange/cooldown/forceAi ra sao.
                //   - CÓ scheduleId (do lịch trình gọi) -> giữ nguyên logic gate cũ theo
                //     isSuddenChange/cooldown, forceAi chỉ còn là cờ "cá biệt" của riêng lịch đó
                //     (ScheduleDraft.force) để bỏ qua gate — ĐÚNG nghĩa gốc của Force AI: chỉ dành
                //     cho cấu hình lịch, không áp cho quét thủ công.
                val isManualTrigger = scheduleId.isNullOrBlank()

                val shouldCallAi = isSmartMode &&
                    (isManualTrigger || forceAi || isSuddenChange) &&
                    (isManualTrigger || forceAi || camera.enableCooldown == 0 || now >= state.cooldownUntil)
                
                var aiComment: String? = null
                var isSuspicious = false
                var visionObjects: List<String> = emptyList()
                var finalStateJson: String? = null
                // ✅ MỚI: đánh dấu khi isSuspicious chỉ đến từ suy đoán pixel-diff tạm thời (Groq lỗi/hết
                // Quota), KHÔNG phải từ mô tả AI đáng tin — dùng để chặn gửi email/thông báo báo động
                // giả (giữ đúng ý đồ gốc: khi AI lỗi, không làm phiền người dùng bằng cảnh báo không rõ
                // ràng), trong khi vẫn cho phép world_state/event_log cập nhật đúng biến động thực tế.
                var aiWasUnreliable = false
                // ✅ MỚI: nguồn phân tích thực tế của lần quét này — "groq" (mô tả AI đáng tin),
                // "ml_kit_local" (fallback ML Kit khi smart mode tắt), hoặc null (suy đoán
                // pixel-diff thuần, không qua model nào — kể cả khi Groq lỗi/hết quota, vì lúc đó
                // KHÔNG có mô tả AI thật, chỉ là so sánh pixel tạm thời như code cũ đã làm).
                var analysisSource: String? = null
                
                // ✅ SỬA: Bỏ lớp lọc ML Kit TRƯỚC Groq. Trước đây ML Kit chặn Groq khi nhãn không
                // khớp aiPositiveKeywords -> nếu người dùng cấu hình từ khoá thiếu/sai, sự kiện
                // THẬT (isSuddenChange=true) bị coi là "bình thường", vừa bỏ sót cảnh báo vừa bị
                // ghi vào falseDeltas/falseDiffs khiến recomputeThresholdsFromSamples() NÂNG
                // deltaTrigger/absDiffTrigger — làm lần sau còn khó phát hiện hơn (học sai lặp lại).
                // Giờ hễ shouldCallAi=true (đã vượt ngưỡng pHash/thủ công/force) là gọi Groq thẳng,
                // không qua ML Kit trước. ML Kit chỉ còn đứng SAU Groq — làm fallback khi Groq lỗi/
                // hết quota (xem nhánh isApiError phía dưới) — không còn giữ vai trò gác cổng.

                if (shouldCallAi) {
                    val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else defaultAiPrompt()
                    val aiResult: String = try {
                        // ✅ SỬA: 20s cũ quá ngắn — log server cho thấy model vision cần hơn
                        // 30s để trả JSON đầy đủ. Giờ GroqClientTool đã hủy request đúng cách
                        // (không còn lãng phí kết nối tới tận readTimeout), nên tăng lên 55s để
                        // model có đủ thời gian trả lời thay vì bị cắt giữa chừng.
                        withTimeout(55_000L) {
                            groqClient.analyzeImage(optimizedBytes, prompt)
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.w("CameraSkill", "⏱️ Groq timeout (55s) camera=$tid")
                        "${GroqClientTool.AI_ERROR_PREFIX}Không thể phân tích (AI timeout)"
                    }

                    val isApiError = aiResult.startsWith(GroqClientTool.AI_ERROR_PREFIX)

                    if (isApiError) {
                        // ✅ SỬA (lần 2): "Force AI" nghĩa là mọi phân tích đều qua Groq, NHƯNG khi Groq
                        // lỗi/hết quota, fallback giờ PHẢI thử ML Kit local trước (có nhãn thật, đối
                        // chiếu đúng keyword camera đã cấu hình — giống hệt cách nhánh "AI tắt" phía
                        // dưới quyết định) — chỉ khi ML Kit CŨNG lỗi/timeout mới rơi về suy đoán pixel
                        // thô thuần tuý (isSuddenChange) như bản vá trước.
                        logger.w("CameraSkill", "⚠️ AI gặp sự cố ($aiResult) -> Thử ML Kit local trước khi rơi về so sánh pixel")

                        // ✅ SỬA: ML Kit không còn chạy trước (pre-filter) nữa — Groq luôn được gọi
                        // thẳng khi shouldCallAi=true, nên ở đây luôn cần gọi ML Kit mới làm fallback.
                        val fallbackLocalResult = try {
                            localVisionTool.analyze(optimizedBytes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.w("CameraSkill", "⚠️ ML Kit fallback (Groq lỗi) cũng lỗi: ${e.message}")
                            null
                        }

                        if (fallbackLocalResult != null) {
                            val fallbackLabels = fallbackLocalResult.labels
                            visionObjects = fallbackLabels.ifEmpty { listOf("chuyển động") }

                            aiComment = if (fallbackLabels.isNotEmpty() || fallbackLocalResult.hasFace) {
                                buildString {
                                    append("Phát hiện cục bộ (ML Kit, Groq lỗi/hết Quota): ")
                                    append(if (fallbackLabels.isNotEmpty()) fallbackLabels.joinToString(", ") else "chuyển động")
                                    if (fallbackLocalResult.hasFace) append(" [có khuôn mặt]")
                                    fallbackLocalResult.dominantColorName?.let { append(" — màu chủ đạo: $it") }
                                }
                            } else {
                                "Không thể phân tích AI (Lỗi kết nối/Hết Quota), ML Kit không nhận diện được vật thể cụ thể"
                            }

                            val fallbackPositiveKeywords = if (camera.aiPositiveKeywords.isNotEmpty()) {
                                camera.aiPositiveKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                            } else {
                                emptyList()
                            }
                            val fallbackLabelsText = buildString {
                                append(fallbackLabels.joinToString(" "))
                                if (fallbackLocalResult.hasFace) append(" khuôn mặt mặt người")
                            }.lowercase()

                            isSuspicious = fallbackPositiveKeywords.isNotEmpty() &&
                                fallbackPositiveKeywords.any { fallbackLabelsText.contains(it) }

                            // ML Kit chạy thành công -> có nhãn thật, đủ tin cậy để KHÔNG chặn thông
                            // báo như suy đoán pixel thuần (khác nhánh else bên dưới) — nếu khớp đúng
                            // keyword camera tự cấu hình thì vẫn gửi email như bình thường.
                            aiWasUnreliable = false
                            finalStateJson = buildVisionStateJson(
                                state = if (isSuspicious) "suspicious" else "normal",
                                description = aiComment ?: "",
                                objects = visionObjects,
                                source = "ml_kit_local",
                                hasFace = fallbackLocalResult.hasFace
                            )
                            analysisSource = "ml_kit_local"
                        } else {
                            // ML Kit CŨNG lỗi/timeout -> fallback cuối cùng: suy đoán thô từ so sánh
                            // pixel (hành vi cũ) — không có mô tả nào đáng tin, không gửi thông báo.
                            aiWasUnreliable = true
                            isSuspicious = isSuddenChange
                            aiComment = if (isSuddenChange) {
                                "Phát hiện biến động (AI tạm thời lỗi/hết Quota, ML Kit cũng lỗi, dùng so sánh pixel)"
                            } else {
                                "Không thể phân tích AI (Lỗi kết nối/Hết Quota) — không phát hiện biến động pixel"
                            }
                            visionObjects = if (isSuddenChange) listOf("chuyển động") else emptyList()
                            finalStateJson = buildVisionStateJson(
                                state = if (isSuspicious) "suspicious" else "normal",
                                description = aiComment ?: "",
                                objects = visionObjects,
                                source = "pixel_diff_only"  // Groq lỗi + ML Kit lỗi -> không có mô tả nào đáng tin
                            )
                        }
                    } else {
                        val visionParsed = parseVisionResult(aiResult)
                        aiComment = visionParsed.displayComment
                        visionObjects = visionParsed.objects

                        val positiveKeywords = if (camera.aiPositiveKeywords.isNotEmpty()) {
                            camera.aiPositiveKeywords.split(",").map { it.trim().lowercase() }
                        } else {
                            defaultPositiveKw()
                        }
                        val negativeKeywords = if (camera.aiNegativeKeywords.isNotEmpty()) {
                            camera.aiNegativeKeywords.split(",").map { it.trim().lowercase() }
                        } else {
                            defaultNegativeKw()
                        }

                        val rawTextClean = aiResult.replace(Regex("<think>[\\s\\S]*?</think>"), "").lowercase().trim()
                        val hasPositive = positiveKeywords.any { rawTextClean.contains(it) }
                        val hasNegative = negativeKeywords.any { rawTextClean.contains(it) }
                        val keywordMatch = hasPositive && !hasNegative

                        // ✅ SỬA (lần 2): Bản trước "keywordMatch && structuredSuspicious != false"
                        // vẫn còn 1 lỗ hổng ở CHIỀU NGƯỢC LẠI: nếu Groq minh thị nói "suspicious"
                        // (structuredSuspicious == true) nhưng mô tả không chứa đúng từ khoá cấu
                        // hình sẵn -> keywordMatch = false -> isSuspicious bị dập xuống false, tức
                        // phán đoán THẬT của Groq bị ghi đè ngược. keyword không nên là điều kiện
                        // quyết định ở CẢ HAI chiều khi Groq đã trả JSON hợp lệ.
                        //
                        // Nguyên tắc mới: world_state PHẢI phản ánh đúng 100% phán đoán của Groq khi
                        // Groq trả JSON hợp lệ (structuredSuspicious != null). keywordMatch không còn
                        // tham gia quyết định isSuspicious trong trường hợp này — nó chỉ còn là tín
                        // hiệu phụ trợ cho ML Kit (nhánh smartMode tắt, xem dưới) và làm fallback DUY
                        // NHẤT khi Groq trả text thô/JSON lỗi parse (structuredSuspicious == null).
                        isSuspicious = visionParsed.structuredSuspicious
                            ?: keywordMatch

                        finalStateJson = buildVisionStateJson(
                            state = if (isSuspicious) "suspicious" else "normal",
                            description = aiComment ?: "",
                            objects = visionObjects,
                            source = "groq"
                        )
                        // ✅ MỚI: đây là nhánh Groq phân tích THÀNH CÔNG (không phải fallback
                        // pixel-diff ở isApiError phía trên) — mô tả đáng tin, đánh dấu nguồn "groq".
                        analysisSource = "groq"
                    }

                } else if (!isSmartMode && (isManualTrigger || forceAi || isSuddenChange)) {
                    // ✅ SỬA (lần 2): trước đây chỉ xét isSuddenChange (không xét forceAi/thủ công) vì
                    // lo ngại ép chạy khi ảnh không đổi sẽ báo "biến động" giả (khi đó aiComment chỉ
                    // là chuỗi cứng "chuyển động"). Giờ nhánh dưới đối chiếu nhãn ML Kit thật với
                    // keyword thay vì hard-code isSuspicious=true, nên ép chạy không còn tạo báo động
                    // giả nữa. Điều kiện giờ dùng isManualTrigger (không scheduleId — chat "quét
                    // camera" hoặc nút Test) để LUÔN phân tích thật khi thao tác thủ công, đúng khớp
                    // hành vi Groq phía trên; forceAi chỉ còn ý nghĩa cho lịch trình (xem giải thích ở
                    // shouldCallAi phía trên).
                    // ✅ MỚI: thay "chuyển động" cứng bằng label thật từ ML Kit (on-device, không tốn
                    // quota Groq) — mục đích DUY NHẤT là làm giàu "summary"/EventLogEntity để search
                    // local dễ hơn (vd "hôm nay có xe không") khi smart mode tắt. KHÔNG thay thế Groq,
                    // KHÔNG đổi điều kiện shouldCallAi/isSmartMode ở trên — luồng đó giữ nguyên 100%.
                    val localResult = try {
                        localVisionTool.analyze(optimizedBytes)
                    } catch (e: CancellationException) {
                        // ✅ Không được nuốt CancellationException — phải ném lại để coroutine
                        // hủy đúng cách (giống pattern đã dùng ở AgentKernel.kt).
                        throw e
                    } catch (e: Exception) {
                        // Bất kỳ lỗi ML Kit nào khác (thiếu Play Services, model chưa sẵn sàng,
                        // OOM...) đều KHÔNG được làm gián đoạn luồng chính — fallback về hành vi cũ.
                        logger.w("CameraSkill", "⚠️ ML Kit local analysis lỗi, dùng fallback cũ: ${e.message}")
                        null
                    }

                    val labels = localResult?.labels.orEmpty()
                    visionObjects = labels.ifEmpty { listOf("chuyển động") }

                    aiComment = if (labels.isNotEmpty() || localResult?.hasFace == true) {
                        buildString {
                            append("Phát hiện cục bộ (ML Kit, chế độ AI tắt): ")
                            append(if (labels.isNotEmpty()) labels.joinToString(", ") else "chuyển động")
                            if (localResult?.hasFace == true) append(" [có khuôn mặt]")
                            localResult?.dominantColorName?.let { append(" — màu chủ đạo: $it") }
                        }
                    } else {
                        // ML Kit không nhận ra vật thể cụ thể nào (hoặc bị lỗi/timeout) — fallback
                        // y hệt hành vi cũ trước khi có ML Kit, không để "summary" trống/gây hiểu nhầm.
                        "Phát hiện biến động chuyển động (Chế độ AI tắt)"
                    }

                    // ✅ SỬA (lần 2 — đảo ngược model): field "Bình thường" (aiNegativeKeywords) giờ
                    // là DANH SÁCH NGUỒN — set sẵn qua AppConfig default (defaultNegativeKw()) TOÀN
                    // BỘ nhãn ML Kit có thể trả về (xem mapLabelToVietnamese() trong LocalVisionTool.
                    // kt: người, xe ô tô, xe máy/xe đạp, chó, mèo, động vật, cây cối, đồ nội thất,
                    // quần áo, giày, túi/hành lý, tòa nhà/phòng, ngoài trời/đường, bầu trời, nước,
                    // thực phẩm...) — vì người dùng không biết chính xác ML Kit sẽ phân loại ra nhãn
                    // gì, để họ tự gõ tay dễ sai/thiếu. Field "cảnh báo" (aiPositiveKeywords) mặc
                    // định BỎ TRỐNG (hoặc để nguyên chữ "bình thường" cũng không sao, không ảnh hưởng
                    // logic) — người dùng chỉ cần COPY đúng nhãn họ muốn cảnh báo từ field "Bình
                    // thường" sang field "cảnh báo". CHỈ báo động khi khớp đúng từ khoá cảnh báo —
                    // KHÔNG còn mặc định báo động khi không khớp gì (khác hẳn model trước). Field
                    // "Bình thường" giờ KHÔNG tham gia vào việc quyết định isSuspicious nữa — thuần
                    // là nguồn tham khảo/copy cho người dùng, không dùng để loại trừ.
                    val positiveKeywords = if (camera.aiPositiveKeywords.isNotEmpty()) {
                        camera.aiPositiveKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }

                    val labelsText = buildString {
                        append(labels.joinToString(" "))
                        if (localResult?.hasFace == true) append(" khuôn mặt mặt người")
                    }.lowercase()

                    // Không khớp từ khoá cảnh báo nào (kể cả khi field để trống) -> KHÔNG báo động,
                    // chỉ ghi log (xem nhánh else phía dưới vẫn lưu aiComment/objects đầy đủ).
                    isSuspicious = positiveKeywords.isNotEmpty() && positiveKeywords.any { labelsText.contains(it) }

                    finalStateJson = buildVisionStateJson(
                        state = if (isSuspicious) "suspicious" else "normal",
                        description = aiComment ?: "",
                        objects = visionObjects,
                        source = if (localResult != null) "ml_kit_local" else "pixel_diff_only",
                        hasFace = localResult?.hasFace ?: false
                    )

                    // Chỉ đánh dấu nguồn "ml_kit_local" khi ML Kit thực sự trả được kết quả — nếu
                    // localResult null (lỗi/timeout), đây thực chất là suy đoán pixel-diff thuần
                    // (giống hệt case Groq lỗi phía trên), không nên gắn nhãn "ml_kit_local" gây
                    // hiểu nhầm là có phân tích nội dung thật.
                    analysisSource = if (localResult != null) "ml_kit_local" else null
                }

                if (isSuspicious) {
                    state.realEvents++
                    state.cooldownUntil = now + cooldownDurationMs()
                    
                    val cameraDailyEvents = dailyEvents.getOrPut(tid) { mutableListOf() }
                    cameraDailyEvents.add(DailyEvent(now, aiComment ?: "Phát hiện bất thường", optimizedBytes))
                    if (cameraDailyEvents.size > maxDailyEvents()) {
                        cameraDailyEvents.removeAt(0)
                    }

                    val latestAlert = withContext(Dispatchers.IO) {
                        database.alertDao().getLatestAlertForCamera(tid)
                    }
                    val mergeWindowMs = alertMergeWindowMs()
                    val latestEffectiveTime = latestAlert?.let { it.endTime ?: it.timestamp }
                    val hasNormalScanSinceLastAlert = latestEffectiveTime != null && state.lastNormalScanAt > latestEffectiveTime
                    val shouldMerge = latestAlert != null &&
                        latestAlert.isSuspicious == 1 &&
                        latestEffectiveTime != null &&
                        (now - latestEffectiveTime) <= mergeWindowMs &&
                        !hasNormalScanSinceLastAlert

                    val activeAlertId = if (shouldMerge && latestAlert != null) {
                        latestAlert.id
                    } else {
                        UUID.randomUUID().toString()
                    }

                    // ✅ SỬA: khi isSuspicious chỉ đến từ suy đoán pixel-diff tạm thời (aiWasUnreliable),
                    // KHÔNG gửi email/thông báo — giữ đúng ý đồ gốc "AI lỗi -> tránh báo động giả làm
                    // phiền người dùng". Alert vẫn được lưu vào lịch sử/world_state (dưới) để
                    // check_precondition và các bước sau đọc được biến động thực tế, chỉ riêng bước GỬI
                    // THÔNG BÁO là bị chặn.
                    val emailSent = if (aiWasUnreliable) {
                        logger.w("CameraSkill", "🔕 Bỏ qua gửi thông báo cho camera $tid vì kết quả suspicious đến từ suy đoán pixel khi AI lỗi, không phải mô tả AI đáng tin")
                        false
                    } else {
                        houseManagerProvider.get().sendDefaultCameraAlerts(
                            camera = camera,
                            aiComment = aiComment ?: "Phát hiện bất thường",
                            imageBytes = optimizedBytes,
                            activeAlertId = activeAlertId,
                            shouldMerge = shouldMerge
                        )
                    }

                    saveAlertToHistory(
                        alertId = activeAlertId,
                        camera = camera,
                        aiComment = aiComment ?: "Phát hiện bất thường",
                        imageBytes = optimizedBytes,
                        diff = currentDiff,
                        delta = delta,
                        deltaTrigger = deltaTrigger,
                        absDiffTrigger = absDiffTrigger,
                        drift = drift,
                        baselineDiff = baselineDiff,
                        driftTrigger = driftTrigger,
                        emailSent = emailSent,
                        scheduleId = scheduleId,
                        aiStateJson = finalStateJson,
                        objects = visionObjects,
                        shouldMerge = shouldMerge,
                        existingAlert = latestAlert,
                        analysisSource = analysisSource
                    )
                    
                    if (!shouldMerge && !aiWasUnreliable) {
                        executeAlertActions(camera, aiComment ?: "Phát hiện bất thường", overrideAlertActions) 
                    }
                    logger.i("CameraSkill", "🚨 ALERT handled for camera $tid (merged=$shouldMerge, id=$activeAlertId): $aiComment")
                    
                } else {
                    state.lastNormalScanAt = now

                    if (isMature && isSuddenChange) {
                        pendingResets[tid] = PendingResetState(currentDiff, now)
                    }

                    withContext(Dispatchers.IO) {
                        val normalComment = aiComment ?: "Bình thường"
                        val objectsSuffix = if (visionObjects.isNotEmpty()) " [objects: ${visionObjects.joinToString(",")}]" else ""

                        // ✅ SỬA (theo yêu cầu — điều chỉnh lại bản vá trước): lưu MỌI lượt quét
                        // (kể cả quét nền 5 phút/lần đứng yên hoàn toàn) tốn dung lượng không cần
                        // thiết. Giờ chỉ lưu ảnh khi: (1) có biến động thật (isSuddenChange, bất kể
                        // biến động đó có bị coi là "cảnh báo" isSuspicious hay không — camera có
                        // thể chỉ chuyển động nhẹ không đủ ngưỡng báo động nhưng vẫn đáng lưu ảnh),
                        // (2) là lượt quét ép buộc (forceAi, người dùng chủ động yêu cầu "quét
                        // camera" nên luôn muốn thấy ảnh phản hồi), hoặc (3) đã quá
                        // PERIODIC_IMAGE_INTERVAL_MS (1 tiếng) kể từ ảnh gần nhất — đảm bảo luôn có
                        // ít nhất 1 ảnh/tiếng làm mốc tham chiếu dù camera đứng yên tuyệt đối. Ảnh
                        // vẫn dùng optimizedBytes — cùng bản đã qua ImageHashTool.optimizeImage() để
                        // giảm kích thước, giống hệt ảnh gửi cho AI phân tích, không xử lý thêm lần
                        // nào nữa.
                        val isPeriodicImageDue = now - state.lastPeriodicImageAt >= PERIODIC_IMAGE_INTERVAL_MS
                        val shouldSaveNormalImage = isSuddenChange || forceAi || isManualTrigger || isPeriodicImageDue
                        val resolvedNormalImagePath = if (shouldSaveNormalImage) {
                            state.lastPeriodicImageAt = now
                            saveChatImage(optimizedBytes)
                        } else null

                        database.eventLogDao().insertLog(
                            EventLogEntity(
                                id = UUID.randomUUID().toString(),
                                timestamp = now,
                                source = "camera",
                                sourceId = tid,
                                eventType = "state_change",
                                value = "Bình thường",
                                summary = "Camera ${camera.customername} ($tid): $normalComment$objectsSuffix",
                                imagePath = resolvedNormalImagePath
                            )
                        )
                        WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "state", value = "normal"
                        )
                        WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "objects",
                            value = JSONArray(visionObjects).toString()
                        )
                        if (!scheduleId.isNullOrBlank()) {
                            WorldStateHelper.setAttribute(
                                database.worldStateDao(), source = "camera", sourceId = tid, key = "state_$scheduleId", value = "normal"
                            )
                        }
                    }
                }
                
                if (!shouldCallAi || !isSuspicious) {
                    period.baselineWindow.add(currentDiff)
                    if (period.baselineWindow.size > 20) {
                        period.baselineWindow.removeAt(0)
                    }
                    
                    if (isSuddenChange && !isSuspicious) {
                        period.falseDeltas.add(delta)
                        period.falseDiffs.add(currentDiff)
                        period.falseSampleTimestamps.add(now)
                        if (period.falseDeltas.size > 100) {
                            period.falseDeltas.removeAt(0)
                            period.falseDiffs.removeAt(0)
                            period.falseSampleTimestamps.removeAt(0)
                        }
                    }

                    val expired = pruneExpiredFalseSamples(period, now)
                    val changed = recomputeThresholdsFromSamples(period)

                    // TỐI ƯU I/O: Chỉ ghi SQLite khi ngưỡng thực sự thay đổi (bao gồm cả do decay)!
                    if (changed || expired) {
                        saveLearningStateToDb(tid, state)
                    }
                }
                
                state.lastPhash = currentPhash
                state.lastDiff = currentDiff
                
                if (camera.isOnline != 1) {
                    withContext(Dispatchers.IO) {
                        database.cameraDao().updateCamera(camera.copy(isOnline = 1, status = "online"))
                    }
                }
                
                updateDiagnostics()
                
                return@withLock mapOf(
                    "cameraId" to tid,
                    "success" to true,
                    "hasChange" to isSuddenChange,
                    "isSuspicious" to isSuspicious,
                    "aiComment" to (aiComment ?: "No analysis"),
                    "diff" to currentDiff,
                    "delta" to delta,
                    "drift" to drift,
                    "deltaTrigger" to deltaTrigger,
                    "absDiffTrigger" to absDiffTrigger,
                    "isNight" to isNight,
                    "imageBytes" to optimizedBytes   
                )
                
            } catch (e: Exception) {
                logger.e("CameraSkill", "Error in processImageWithLearning: ${e.message}", e)
                return@withLock mapOf(
                    "cameraId" to tid,
                    "success" to false,
                    "error" to (e.message ?: "Unknown error")
                )
            }
        }
    }
    
    private suspend fun scanWithLearning(
        camera: CameraConfigEntity,
        isSmartMode: Boolean,
        scheduleId: String? = null,
        forceAi: Boolean = false,
        overrideAlertActions: List<AlertActionConfig>? = null
    ): Map<String, Any> {
        val tid = camera.id.trim()
        try {
            val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
            if (imageBytes == null) {
                handleOfflineCamera(camera)
                recordOffline(tid)
                return mapOf(
                    "cameraId" to tid,
                    "success" to false,
                    "error" to "Cannot fetch snapshot"
                )
            }

            return processImageWithLearning(
                camera,
                imageBytes,
                isSmartMode,
                scheduleId = scheduleId,
                forceAi = forceAi,
                overrideAlertActions = overrideAlertActions
            )

        } catch (e: Exception) {
            logger.e("CameraSkill", "Error in scanWithLearning: ${e.message}", e)
            return mapOf(
                "cameraId" to tid,
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    private suspend fun scanForDailyReport(camera: CameraConfigEntity): Map<String, Any> {
        val tid = camera.id.trim()
        return try {
            val imageBytes = snapshotFetcher.fetchSnapshot(camera.snapshoturl)
            if (imageBytes == null) {
                return mapOf(
                    "cameraId" to tid,
                    "success" to false,
                    "error" to "Cannot fetch snapshot for daily report"
                )
            }
            
            val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
            val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else defaultAiPrompt()
            val aiComment = try {
                // ✅ SỬA: đồng bộ với chỗ phân tích camera trực tiếp — 20s không đủ cho model
                // vision, tăng lên 55s vì GroqClientTool giờ đã hủy request đúng cách.
                withTimeout(55_000L) {
                    groqClient.analyzeImage(optimizedBytes, prompt)
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("CameraSkill", "⏱️ Groq timeout (55s) camera=$tid (daily report)")
                "Không thể phân tích (AI timeout)"
            }
            
            mapOf(
                "cameraId" to tid,
                "success" to true,
                "aiComment" to aiComment,
                "imageBytes" to optimizedBytes
            )
            
        } catch (e: Exception) {
            mapOf(
                "cameraId" to tid,
                "success" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    private suspend fun handleOfflineCamera(camera: CameraConfigEntity) {
        val tid = camera.id.trim()
        if (camera.isOnline != 0) {
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCamera(camera.copy(isOnline = 0, status = "offline"))
            }
            
            deviceRegistry.updateOnlineStatus(tid, false, status = "Mất kết nối")
            
            notificationSkill.sendNotification(
                title = "Camera ${camera.customername} mất kết nối",
                message = "Không thể kết nối đến camera. Vui lòng kiểm tra!"
            )
            sendAdminAlert(camera, "Không thể kết nối đến camera. Vui lòng kiểm tra đường truyền!")
        }
    }
    
    private suspend fun sendAdminAlert(camera: CameraConfigEntity, reason: String) {
        val subject = "🚨 [HỆ THỐNG] LỖI CAMERA: ${camera.customername} (${camera.id.trim()})"
        val body = """
            <html>
            <body>
                <h2 style="color: #dc2626;">🚨 CẢNH BÁO SỰ CỐ VẬN HÀNH CAMERA</h2>
                <p>Hệ thống phát hiện sự cố tại thiết bị camera giám sát thửa đất:</p>
                <table>
                    <tr><td><strong>Tên Camera:</strong></td><td>${camera.customername}</td></tr>
                    <tr><td><strong>Mã Camera:</strong></td><td>${camera.id.trim()}</td></tr>
                    <tr><td><strong>Mã Khách hàng:</strong></td><td>${camera.customerId.trim()}</td></tr>
                    <tr><td><strong>Thời gian:</strong></td><td>${DATETIME_FORMATTER.format(Instant.now())}</td></tr>
                </table>
                <div style="background: #fef2f2; padding: 10px; border-left: 4px solid #dc2626;">
                    <strong>Chi tiết lỗi:</strong><br>$reason
                </div>
                <p>Vui lòng kiểm tra hạ tầng đường truyền camera.</p>
            </body>
            </html>
        """.trimIndent()
        
        val adminEmail = "admin@aichatvn.com"
        if (adminEmail.isNotBlank()) {
            emailSkill.sendEmail(adminEmail, subject, body, null)
        }
    }
    
    suspend fun saveCameraConfig(config: Map<String, Any>): PluginResult {
        return try {
            val id = (config["id"] as? String)?.trim() ?: return PluginResult.Failure("Missing camera id")
            val customerId = (config["customerId"] as? String)?.trim() ?: ""
            val existing = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(id)
            }

            val camera = CameraConfigEntity(
                id = id,
                customerId = customerId,
                customername = (config["customername"] as? String ?: existing?.customername ?: "").trim(),
                customeremail = (config["customeremail"] as? String ?: existing?.customeremail ?: "").trim(),
                snapshoturl = (config["snapshoturl"] as? String ?: existing?.snapshoturl ?: "").trim(),
                landinfo = (config["landinfo"] as? String ?: existing?.landinfo)?.trim(),
                snapshotPath = existing?.snapshotPath,
                timestamp = System.currentTimeMillis(),
                status = config["status"] as? String ?: existing?.status ?: "online",
                isOnline = (config["isOnline"] as? Int) ?: (config["isOnline"] as? Number)?.toInt() ?: existing?.isOnline ?: 1,
                manualOff = (config["manualOff"] as? Int) ?: (config["manualOff"] as? Number)?.toInt() ?: existing?.manualOff ?: 0,
                aiPrompt = (config["aiPrompt"] as? String ?: existing?.aiPrompt ?: "").trim(),
                aiPositiveKeywords = (config["aiPositiveKeywords"] as? String ?: existing?.aiPositiveKeywords ?: "").trim(),
                aiNegativeKeywords = (config["aiNegativeKeywords"] as? String ?: existing?.aiNegativeKeywords ?: "").trim(),
                enableCooldown = (config["enableCooldown"] as? Int) ?: (config["enableCooldown"] as? Number)?.toInt() ?: existing?.enableCooldown ?: 1,
                enableNotification = (config["enableNotification"] as? Int) ?: (config["enableNotification"] as? Number)?.toInt() ?: existing?.enableNotification ?: 1,
                alertActions = config["alertActions"] as? String ?: existing?.alertActions ?: "[]" 
            )
            
            withContext(Dispatchers.IO) {
                database.cameraDao().insertCamera(camera)
            }
            
            val setting = withContext(Dispatchers.IO) {
                database.cameraDao().getCustomerSetting(camera.customerId)
            }
            if (setting == null) {
                database.cameraDao().insertCustomerSetting(
                    CustomerSettingEntity(
                        customerId = camera.customerId,
                        smartMode = 0,
                        isActive = 1,
                        updatedAt = System.currentTimeMillis(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            
            val tid = camera.id.trim()
            if (!learningStates.containsKey(tid)) {
                learningStates[tid] = loadLearningStateFromDb(tid)
                circuitBreakers[tid] = CircuitBreakerState()
            }
            
            syncToDeviceRegistry()
            PluginResult.Success(mapOf("message" to "Camera saved"))
            
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveCameraConfig error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Save camera failed")
        }
    }
    
    suspend fun deleteCamera(cameraId: String): PluginResult {
        return try {
            val tid = cameraId.trim()
            withContext(Dispatchers.IO) {
                database.cameraDao().deleteCamera(tid)
                database.worldStateDao().deleteStateBySourceAndId("camera", tid)
                database.worldStateDao().deleteStateBySourceAndId("system", "cam_learning_$tid")
            }
            learningStates.remove(tid)
            circuitBreakers.remove(tid)
            pendingResets.remove(tid)
            dailyEvents.remove(tid)
            cameraMutexMap.remove(tid)
            
            syncToDeviceRegistry()
            
            PluginResult.Success(mapOf("message" to "Camera deleted"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Delete camera failed")
        }
    }
    
    suspend fun deleteCustomer(customerId: String): PluginResult {
        return try {
            val trimmedCustomerId = customerId.trim()
            val cameras = withContext(Dispatchers.IO) {
                database.cameraDao().getCamerasByCustomer(trimmedCustomerId)
            }
            withContext(Dispatchers.IO) {
                database.cameraDao().deleteCamerasByCustomer(trimmedCustomerId)
                database.cameraDao().deleteCustomerSetting(trimmedCustomerId)
                cameras.forEach { camera ->
                    database.worldStateDao().deleteStateBySourceAndId("camera", camera.id.trim())
                    database.worldStateDao().deleteStateBySourceAndId("system", "cam_learning_${camera.id.trim()}")
                }
            }
            
            cameras.forEach { camera ->
                val tid = camera.id.trim()
                learningStates.remove(tid)
                circuitBreakers.remove(tid)
                pendingResets.remove(tid)
                dailyEvents.remove(tid)
                cameraMutexMap.remove(tid)
            }
            
            syncToDeviceRegistry()
            
            PluginResult.Success(mapOf("message" to "Customer deleted"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Delete customer failed")
        }
    }
    
    suspend fun setSmartMode(customerId: String, enabled: Boolean): PluginResult {
        return try {
            val trimmedCustomerId = customerId.trim()
            withContext(Dispatchers.IO) {
                database.cameraDao().updateSmartMode(trimmedCustomerId, enabled, System.currentTimeMillis())
            }
            PluginResult.Success(mapOf("message" to "Smart mode updated"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Update smart mode failed")
        }
    }
    
    suspend fun setCustomerActive(customerId: String, active: Boolean): PluginResult {
        return try {
            val trimmedCustomerId = customerId.trim()
            withContext(Dispatchers.IO) {
                database.cameraDao().updateActiveStatus(trimmedCustomerId, active, System.currentTimeMillis())
            }
            PluginResult.Success(mapOf("message" to "Customer status updated"))
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Update customer status failed")
        }
    }
    
    suspend fun getCamerasPaginated(page: Int, pageSize: Int): PluginResult {
        return try {
            val cameras = withContext(Dispatchers.IO) {
                database.cameraDao().getActiveCameras()
            }
            val start = (page - 1) * pageSize
            val end = minOf(start + pageSize, cameras.size)
            val paginated = if (start < cameras.size) cameras.subList(start, end) else emptyList()
            
            PluginResult.Success(
                mapOf(
                    "cameras" to paginated,
                    "total" to cameras.size,
                    "page" to page,
                    "pageSize" to pageSize
                )
            )
        } catch (e: Exception) {
            PluginResult.Failure(e.message ?: "Get cameras failed")
        }
    }
    
    fun observeCameras() = database.cameraDao().getAllCamerasFlow()

    suspend fun testCameraUrl(snapshotUrl: String): PluginResult {
        return try {
            logger.i("CameraSkill", "🧪 testCameraUrl: $snapshotUrl")
            val imageBytes = snapshotFetcher.fetchSnapshot(snapshotUrl)

            if (imageBytes == null) {
                logger.w("CameraSkill", "🧪 testCameraUrl: fetch trả về null (URL sai hoặc camera offline)")
                return PluginResult.Failure("Không thể fetch ảnh từ URL này. Kiểm tra: URL đúng định dạng? Camera online? Network ok?")
            }

            val optimized = imageHashTool.optimizeImage(imageBytes)
            val phash = imageHashTool.calculatePhash(optimized)
            logger.i("CameraSkill", "🧪 testCameraUrl OK: ${optimized.size} bytes, phash=$phash")

            PluginResult.Success(
                mapOf(
                    "imageBytes" to optimized,
                    "originalSize" to imageBytes.size,
                    "optimizedSize" to optimized.size,
                    "phash" to phash,
                    "message" to "Camera fetch OK — ${optimized.size} bytes"
                )
            )
        } catch (e: Exception) {
            logger.e("CameraSkill", "🧪 testCameraUrl error: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Test camera URL failed")
        }
    }

    fun resetCircuitBreaker(cameraId: String) {
        val tid = cameraId.trim()
        circuitBreakers[tid] = CircuitBreakerState()
        logger.i("CameraSkill", "🔄 Circuit Breaker reset manually for camera $tid")
    }

    // ✅ MỚI (day/night split): reset toàn bộ (cả day + night) — dùng cho nút "Reset học tập".
    fun resetLearningState(cameraId: String) {
        val tid = cameraId.trim()
        val freshState = CameraLearningState()
        learningStates[tid] = freshState
        scope.launch {
            saveLearningStateToDb(tid, freshState)
            updateDiagnostics()
        }
        logger.i("CameraSkill", "🧠 Learning state reset manually for camera $tid (day+night)")
    }

    fun resetAllCircuitBreakers() {
        circuitBreakers.keys.forEach { id ->
            val tid = id.trim()
            circuitBreakers[tid] = CircuitBreakerState()
        }
        logger.i("CameraSkill", "🔄 All Circuit Breakers reset manually")
    }

    fun getDiagnostics(): Map<String, Any> = _diagnostics.value

    // ✅ MỚI: getDiagnostics() chỉ đọc cache _diagnostics.value, được tính lần gần nhất lúc
    // quét ảnh (updateDiagnostics() private, chỉ gọi trong luồng scan). Khi người dùng đổi
    // config (vd tắt enableCooldown) từ UI, cache cũ vẫn còn nguyên tới lượt quét kế tiếp nên
    // badge "Đang cooldown" không phản hồi tức thì. Hàm public này cho phép ép tính lại ngay.
    suspend fun refreshDiagnostics() {
        updateDiagnostics()
    }

    private suspend fun updateDiagnostics() {
        // ✅ MỚI (day/night split): mỗi camera giờ báo cáo 2 bộ chỉ số (day/night) thay vì 1.
        val stats = learningStates.mapValues { (cameraId, state) ->
            val tid = cameraId.trim()
            val cb = circuitBreakers[tid]
            fun periodStats(p: PeriodLearningState) = mapOf(
                "samples" to p.falseDeltas.size,
                "deltaTrigger" to p.deltaTrigger,
                "absDiffTrigger" to p.absDiffTrigger,
                "baselineSize" to p.baselineWindow.size,
                "baselineDiff" to p.lastBaselineDiff,
                "drift" to p.lastDrift,
                "driftTrigger" to DRIFT_TRIGGER
            )
            // ✅ SỬA: inCooldown trước đây chỉ xét cooldownUntil > now, không quan tâm
            // camera.enableCooldown đang bật/tắt. Hệ quả: sau khi người dùng gạt TẮT cooldown,
            // mốc cooldownUntil cũ (đã set từ trước đó) vẫn còn trong state nên UI vẫn báo
            // "Đang cooldown" — dù logic quét thực tế (nhánh forceAi || enableCooldown==0 || ...)
            // đã bỏ qua cooldown đúng. Đọc thêm enableCooldown của camera để đồng bộ UI với hành vi thật.
            val cameraEnableCooldown = database.cameraDao().getCameraById(tid)?.enableCooldown ?: 1
            mapOf(
                "day" to periodStats(state.day),
                "night" to periodStats(state.night),
                "realEvents" to state.realEvents,
                "inCooldown" to (cameraEnableCooldown == 1 && state.cooldownUntil > System.currentTimeMillis()),
                "circuitBreakerOpen" to (cb?.isOpen ?: false),
                "offlineCount" to (cb?.offlineCount ?: 0),
                "pendingReset" to pendingResets.containsKey(tid)
            )
        }
        _diagnostics.value = stats
    }

    private suspend fun saveAlertToHistory(
        alertId: String,
        camera: CameraConfigEntity,
        aiComment: String,
        imageBytes: ByteArray?,
        diff: Int,
        delta: Int,
        deltaTrigger: Int,
        absDiffTrigger: Int,
        drift: Int = 0,
        baselineDiff: Int = 0,
        driftTrigger: Int = DRIFT_TRIGGER,
        emailSent: Boolean,
        scheduleId: String? = null,
        aiStateJson: String? = null,
        objects: List<String> = emptyList(),
        shouldMerge: Boolean = false,
        existingAlert: AlertEntity? = null,
        // ✅ MỚI: "groq" | "ml_kit_local" | null — xem comment ở EventLogEntity.analysisSource.
        // Mặc định null để không phá vỡ các call site khác (nếu có trong repo thật) chưa truyền
        // tham số này.
        analysisSource: String? = null
    ) {
        val tid = camera.id.trim()
        try {
            val now = System.currentTimeMillis()
            // ✅ MỚI: gom về 1 biến chung ở scope ngoài if/else — trước đây imagePath
            // (nhánh else) và refreshedImagePath (nhánh merge) chỉ tồn tại local trong từng
            // nhánh, nên khối eventLogDao().insertLog() dùng chung bên dưới không có cách nào
            // đọc được giá trị này để lưu kèm vào EventLogEntity.
            var resolvedImagePath: String? = null

            if (shouldMerge && existingAlert != null) {
                resolvedImagePath = imageBytes?.let { saveAlertImage(existingAlert.id, it) }
                withContext(Dispatchers.IO) {
                    database.alertDao().mergeAlertUpdate(
                        alertId = existingAlert.id,
                        endTime = now,
                        aiComment = aiComment,
                        diff = diff,
                        delta = delta,
                        deltaTrigger = deltaTrigger,
                        absDiffTrigger = absDiffTrigger,
                        drift = drift,
                        baselineDiff = baselineDiff,
                        driftTrigger = driftTrigger,
                        imagePath = resolvedImagePath,
                        aiStateJson = aiStateJson
                    )
                }
                logger.d("CameraSkill", "🔗 [Nén sự kiện] Đã gộp và cập nhật nội dung mới nhất vào bản ghi #${existingAlert.id}")
            } else {
                resolvedImagePath = imageBytes?.let { saveAlertImage(alertId, it) }

                val scheduleLabel = scheduleId?.let { id ->
                    withContext(Dispatchers.IO) {
                        val sched = database.scheduleDao().getScheduleById(id)
                        sched?.let {
                            if (!it.label.isNullOrBlank()) {
                                it.label
                            } else if (it.cron.isNotBlank()) {
                                "⏰ ${it.cron}"
                            } else {
                                "🔁 mỗi ${it.intervalMinutes} phút"
                            }
                        }
                    }
                }

                val alert = AlertEntity(
                    id = alertId,
                    cameraId = tid,
                    customerId = camera.customerId.trim(),
                    cameraName = camera.customername,
                    timestamp = now,
                    aiComment = aiComment,
                    diff = diff,
                    delta = delta,
                    deltaTrigger = deltaTrigger,
                    absDiffTrigger = absDiffTrigger,
                    drift = drift,
                    baselineDiff = baselineDiff,
                    driftTrigger = driftTrigger,
                    imagePath = resolvedImagePath,
                    emailSent = if (emailSent) 1 else 0,
                    isSuspicious = 1,
                    isRead = 0,
                    scheduleId = scheduleId,
                    scheduleLabel = scheduleLabel,
                    endTime = null, 
                    aiStateJson = aiStateJson
                )
                withContext(Dispatchers.IO) {
                    database.alertDao().insertAlert(alert)
                }
            }

            withContext(Dispatchers.IO) {
                val objectsSuffix = if (objects.isNotEmpty()) " [objects: ${objects.joinToString(",")}]" else ""
                val derivedEventType = when {
                    objects.any { it in listOf("người", "nguoi", "person", "human", "kẻ đột nhập") } -> "person_detected"
                    objects.any { it in listOf("động vật", "dong vat", "animal", "chó", "mèo", "con vật", "dog", "cat") } -> "animal_detected"
                    objects.any { it in listOf("xe máy", "ô tô", "xe đạp", "xe", "car", "motorbike", "vehicle") } -> "vehicle_detected"
                    else -> "motion_detected"
                }

                database.eventLogDao().insertLog(
                    EventLogEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        source = "camera",
                        sourceId = tid,
                        eventType = derivedEventType,
                        value = "Phát hiện bất thường",
                        summary = "Camera ${camera.customername} ($tid) phát hiện: $aiComment$objectsSuffix",
                        imagePath = resolvedImagePath,
                        analysisSource = analysisSource
                    )
                )
                WorldStateHelper.setAttribute(
                    database.worldStateDao(), source = "camera", sourceId = tid, key = "state", value = "suspicious"
                )
                WorldStateHelper.setAttribute(
                    database.worldStateDao(), source = "camera", sourceId = tid, key = "objects",
                    value = JSONArray(objects).toString()
                )
                if (!scheduleId.isNullOrBlank()) {
                    WorldStateHelper.setAttribute(
                        database.worldStateDao(), source = "camera", sourceId = tid, key = "state_$scheduleId", value = "suspicious"
                    )
                }
            }
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveAlertToHistory error: ${e.message}", e)
        }
    }

    private suspend fun executeAlertActions(
        camera: CameraConfigEntity,
        aiComment: String,
        overrideAlertActions: List<AlertActionConfig>? = null   
    ) {
        val actionsToExecute: List<AlertActionConfig> = if (!overrideAlertActions.isNullOrEmpty()) {
            overrideAlertActions
        } else {
            if (camera.alertActions.isBlank() || camera.alertActions == "[]") return
            alertActionsFromJson(camera.alertActions)
        }
        if (actionsToExecute.isEmpty()) return

        for (cfg in actionsToExecute) {
            val targetPluginId = cfg.pluginId
            val targetActionName = cfg.action
            if (targetPluginId.isBlank() || targetActionName.isBlank()) continue

            val params: Map<String, Any> = cfg.params

            try {
                val result = intentExecutorProvider.get().executePluginAction(targetPluginId, targetActionName, params)
                logger.i("CameraSkill", "🔗 Alert-action ${camera.id.trim()} -> $targetPluginId.$targetActionName: $result")
            } catch (e: Exception) {
                logger.e("CameraSkill", "🔗 Alert-action lỗi ($targetPluginId.$targetActionName): ${e.message}", e)
            }
        }
    }

    private fun saveAlertImage(alertId: String, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "alert_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$alertId.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveAlertImage error: ${e.message}", e)
            null
        }
    }

    private fun saveChatImage(bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (e: Exception) {
            logger.e("CameraSkill", "saveChatImage error: ${e.message}", e)
            null
        }
    }

    private fun buildAlertEmailBody(camera: CameraConfigEntity, analysis: String): String {
        return """
            <html>
            <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2 style="color: #dc2626;">🚨 CẢNH BÁO AN NINH KHẨN CẤP</h2>
                <p>Hệ thống phát hiện biến động bất thường tại camera <strong>${camera.customername}</strong></p>
                <table style="margin: 15px 0;">
                    <tr><td><strong>Vị trí:</strong></td><td>${camera.landinfo ?: "Không xác định"}</td></tr>
                    <tr><td><strong>Thời gian:</strong></td><td>${DATETIME_FORMATTER.format(Instant.now())}</td></tr>
                </table>
                <div style="background: #fff3cd; padding: 15px; border-left: 4px solid #ffc107;">
                    <strong>🤖 Phân tích AI:</strong><br>
                    $analysis
                </div>
            </body>
            </html>
        """.trimIndent()
    }
    
    override suspend fun shutdown() {}
}