package com.aichatvn.agent.skills

import com.aichatvn.agent.utils.toMap
import javax.inject.Provider
import com.aichatvn.agent.core.execution.IntentExecutor

import android.content.Context
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AlertActionConfig
import com.aichatvn.agent.data.model.alertActionsFromJson
import com.aichatvn.agent.data.model.AlertEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.tools.camera.ImageHashTool
import com.aichatvn.agent.tools.camera.SnapshotFetcher
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.ui.dashboard.DeviceNode
import com.aichatvn.agent.ui.dashboard.DeviceType
import com.aichatvn.agent.ui.dashboard.DeviceAction as DashboardDeviceAction
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotFetcher: SnapshotFetcher,
    private val imageHashTool: ImageHashTool,
    private val groqClient: GroqClientTool,
    private val emailSkill: EmailSkill,
    private val notificationSkill: NotificationSkill,
    private val configProvider: AppConfigProvider,
    private val deviceRegistry: DeviceRegistry,
    private val intentExecutorProvider: Provider<IntentExecutor>, 
    private val houseManagerProvider: Provider<HouseManagerSkill>, // ✅ Kết nối với Quản gia (cùng package nên không cần import riêng)
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
            PluginAction(
                name = "set_smart_mode",
                description = "Bật hoặc tắt chế độ phân tích AI cho camera",
                examples = listOf("bật ai camera", "tắt ai camera"),
                exampleOverrides = mapOf(
                    "bật ai camera" to mapOf("enabled" to true),
                    "tắt ai camera" to mapOf("enabled" to false)
                ),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", false, "camera"),
                    PluginParameter("customerId", "string", "Mã khách hàng", false, "string"),
                    PluginParameter("enabled", "boolean", "Trạng thái bật/tắt AI", true, "boolean")
                )
            ),
            PluginAction(
                name = "configure",
                description = "Cập nhật cấu hình kỹ thuật cho thiết bị camera",
                examples = listOf("cấu hình camera", "cập nhật camera"),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera", true, "camera"),
                    PluginParameter("aiPrompt", "string", "Prompt AI mới", false, "string"),
                    PluginParameter("aiPositiveKeywords", "string", "Từ khoá cảnh báo", false, "string"),
                    PluginParameter("aiNegativeKeywords", "string", "Từ khoá bình thường", false, "string"),
                    PluginParameter("snapshotUrl", "string", "URL ảnh chụp", false, "string"),
                    PluginParameter("landInfo", "string", "Thông tin vị trí", false, "string"),
                    PluginParameter("enableCooldown", "number", "Bật/Tắt cooldown hoãn quét", false, "number"),
                    PluginParameter("enableNotification", "number", "Bật/Tắt gửi thông báo", false, "number")
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
    
    private data class CameraLearningState(
        var lastPhash: String = "",
        var lastDiff: Int = 0,
        val falseDeltas: MutableList<Int> = mutableListOf(),
        val falseDiffs: MutableList<Int> = mutableListOf(),
        val baselineWindow: MutableList<Int> = mutableListOf(),
        var realEvents: Int = 0,
        var deltaTrigger: Int = 10,
        var absDiffTrigger: Int = 18,
        var cooldownUntil: Long = 0L,
        // ✅ MỚI: thời điểm AI xác nhận gần nhất là "bình thường" (state=normal, không phải chỉ
        // là không gọi AI). Dùng để cắt chuỗi gộp cảnh báo — nếu có 1 lần xác nhận bình thường
        // xảy ra SAU alert gần nhất, thì lần suspicious tiếp theo phải là sự kiện MỚI, không được
        // gộp chung dù còn trong mergeWindowMs.
        var lastNormalScanAt: Long = 0L
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
        val objects: List<String>,           // ✅ MỚI: nhãn đối tượng AI phát hiện — GroqClientTool.STRUCTURED_VISION_SUFFIX
                                              // đã ép AI trả field này ở MỌI lần gọi analyzeImage(), nhưng trước đây
                                              // parseVisionResult() bỏ qua hoàn toàn, không đọc.
        val rawJson: org.json.JSONObject?    
    )
    
    private val learningStates = ConcurrentHashMap<String, CameraLearningState>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    private val pendingResets = ConcurrentHashMap<String, PendingResetState>()
    private val dailyEvents = ConcurrentHashMap<String, MutableList<DailyEvent>>()
    
    private val _diagnostics = MutableStateFlow<Map<String, Any>>(emptyMap())
    val diagnostics: StateFlow<Map<String, Any>> = _diagnostics.asStateFlow()

    private suspend fun defaultAiPrompt()       = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_AI_PROMPT))
    private suspend fun defaultPositiveKw()     = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_POSITIVE_KW)).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private suspend fun defaultNegativeKw()     = configProvider.getString(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DEFAULT_NEGATIVE_KW)).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private suspend fun cooldownDurationMs()    = configProvider.getLong(AppConfigDefaults.CAMERA_COOLDOWN_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_COOLDOWN_MS).toLong())
    private suspend fun maxDailyEvents()        = configProvider.getInt(AppConfigDefaults.CAMERA_MAX_DAILY_EVENTS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_MAX_DAILY_EVENTS).toInt())
    private suspend fun circuitBreakerThreshold() = configProvider.getInt(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_THRESHOLD, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_THRESHOLD).toInt())
    private suspend fun circuitBreakerResetMs() = configProvider.getLong(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_RESET_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_CIRCUIT_BREAKER_RESET_MS).toLong())
    private suspend fun dailyReportHour()       = configProvider.getInt(AppConfigDefaults.CAMERA_DAILY_REPORT_HOUR, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_DAILY_REPORT_HOUR).toInt())
    private suspend fun alertMergeWindowMs()    = configProvider.getLong(AppConfigDefaults.CAMERA_ALERT_MERGE_WINDOW_MS, AppConfigDefaults.defaultOf(AppConfigDefaults.CAMERA_ALERT_MERGE_WINDOW_MS).toLong())
    
    companion object {
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
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
            "configure" -> handleConfigure(params)
            else -> PluginResult.Failure("Action không xác định: $action")
        }
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
        val force = params["force"] as? Boolean
            ?: (params["force"] as? String)?.lowercase()?.let { it == "true" }
            ?: false
        val scheduleId = (params["scheduleId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
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
                        when {
                            isSuspicious -> append("• Camera $id: 🚨 CẢNH BÁO — ${aiComment ?: "phát hiện bất thường"} (email đã gửi nếu bật thông báo)\n")
                            hasChange && aiComment != null && aiComment != "No analysis" ->
                                append("• Camera $id: 🔄 Có biến động — $aiComment\n")
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
                append("• Ngưỡng học (delta/diff): ${diag.deltaTrigger}/${diag.absDiffTrigger}\n")
                append("• Sự kiện thật: ${diag.realEvents} | Mẫu học: ${diag.falseDeltas.size}\n")
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
            learningStates[tid] = CameraLearningState()
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
    
    private fun cleanupOldAlerts() {
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
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
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                withContext(Dispatchers.IO) {
                    database.eventLogDao().pruneLogsOlderThan(cutoff)
                }
            } catch (e: Exception) {
                logger.e("CameraSkill", "cleanupOldEventLogs error: ${e.message}", e)
            }
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
        val pending = pendingResets[tid]
        if (pending == null) return false
        
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
        // ✅ ĐÃ SỬA: Một số model suy luận sâu trên Groq (dòng Qwen/DeepSeek Preview) đôi khi tự
        // sinh khối suy luận ngầm <think>...</think> TRƯỚC khối JSON thật, làm hỏng việc cắt
        // chuỗi theo dấu ngoặc { } bên dưới (vì indexOf('{') bắt trúng dấu ngoặc bên trong khối
        // suy luận, không phải JSON thật). Dọn sạch khối này trước khi tìm JSON.
        val cleanedRaw = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

        val start = cleanedRaw.indexOf('{')
        val end = cleanedRaw.lastIndexOf('}')

        val cleaned = if (start in 0 until end) {
            cleanedRaw.substring(start, end + 1).trim()
        } else {
            cleanedRaw
        }

        return try {
            val json = org.json.JSONObject(cleaned)
            val state = json.optString("state", "").lowercase().trim()
            // Dùng cleanedRaw (đã bỏ <think>) làm fallback thay vì raw gốc, tránh rò rỉ khối suy
            // luận nội bộ của AI ra bình luận cảnh báo hiển thị cho chủ nhà.
            val description = json.optString("description", "").ifBlank { cleanedRaw }
            val structuredSuspicious = when (state) {
                "suspicious" -> true
                "normal" -> false
                else -> null 
            }
            // ✅ MỚI: đọc mảng "objects" — dữ liệu AI đã trả sẵn trong MỌI lần gọi analyzeImage()
            // (xem STRUCTURED_VISION_SUFFIX trong GroqClientTool) nhưng trước đây bị bỏ qua.
            // Chuẩn hóa lowercase + trim vì so khớp sau này (buildMemoryContext) làm trên chữ thường.
            val objectsRaw = json.optJSONArray("objects")
            val objects = if (objectsRaw != null) {
                (0 until objectsRaw.length())
                    .mapNotNull { i -> objectsRaw.optString(i)?.trim()?.lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
            } else emptyList()
            VisionParseResult(displayComment = description, structuredSuspicious = structuredSuspicious, objects = objects, rawJson = json)
        } catch (e: Exception) {
            // Fallback trả về văn bản đã dọn sạch <think> nếu AI không xuất ra JSON chuẩn
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
            val state = learningStates.getOrPut(tid) { CameraLearningState() }
            val now = System.currentTimeMillis()
            
            try {
                recordOnline(tid)
                
                val currentPhash = imageHashTool.calculatePhash(imageBytes)
                val optimizedBytes = imageHashTool.optimizeImage(imageBytes)
                
                var currentDiff = 0
                if (state.lastPhash.isNotEmpty()) {
                    currentDiff = imageHashTool.calculateHammingDistance(state.lastPhash, currentPhash)
                }
                
                val delta = kotlin.math.abs(currentDiff - state.lastDiff)
                val deltaTrigger = state.deltaTrigger
                val absDiffTrigger = state.absDiffTrigger
                val driftTrigger = 12
                
                val baselineDiff = if (state.baselineWindow.isNotEmpty()) {
                    state.baselineWindow.average().toInt()
                } else {
                    currentDiff
                }
                val drift = kotlin.math.abs(currentDiff - baselineDiff)
                
                val isSuddenChange = delta >= deltaTrigger || 
                                     currentDiff >= absDiffTrigger || 
                                     drift >= driftTrigger
                
                val isMature = state.baselineWindow.size >= 3
                
                val shouldReset = checkPendingReset(tid, currentDiff, absDiffTrigger)
                if (shouldReset) {
                    learningStates[tid] = CameraLearningState(
                        lastPhash = currentPhash,
                        lastDiff = currentDiff
                    )
                    return@withLock mapOf(
                        "cameraId" to tid,
                        "success" to true,
                        "hasChange" to false,
                        "message" to "Learning reset due to stable scene change"
                    )
                }
                
                val shouldCallAi = (isSuddenChange || forceAi) && isSmartMode &&
                    (forceAi || camera.enableCooldown == 0 || now >= state.cooldownUntil)
                
                var aiComment: String? = null
                var isSuspicious = false
                
                if (shouldCallAi) {
                    val prompt = if (camera.aiPrompt.isNotEmpty()) camera.aiPrompt else defaultAiPrompt()
                    val aiResult: String = try {
                        withTimeout(20_000L) {
                            groqClient.analyzeImage(optimizedBytes, prompt)
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.w("CameraSkill", "⏱️ Groq timeout (20s) camera=$tid, bỏ qua phân tích AI lần này")
                        "Không thể phân tích (AI timeout)"
                    }

                    val visionParsed = parseVisionResult(aiResult)
                    aiComment = visionParsed.displayComment
                    
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

                    val textClean = aiComment.lowercase()
                    val hasPositive = positiveKeywords.any { textClean.contains(it) }
                    val hasNegative = negativeKeywords.any { textClean.contains(it) }
                    val keywordMatch = hasPositive && !hasNegative

                    // ✅ ĐÃ SỬA: Trước đây hễ AI trả JSON có cấu trúc (structuredSuspicious != null,
                    // xảy ra ở HẦU HẾT mọi lần gọi vì GroqClientTool luôn thêm STRUCTURED_VISION_SUFFIX)
                    // thì từ khoá cảnh báo (aiPositiveKeywords/aiNegativeKeywords) do chủ nhà tự cấu
                    // hình BỊ BỎ QUA HOÀN TOÀN — khiến chuỗi hành động liên hoàn không bao giờ khớp
                    // với những gì chủ nhà đã cấu hình. Nay: nếu chủ nhà đã tự đặt từ khoá riêng
                    // (khác mặc định hệ thống), tôn trọng kết quả khớp từ khoá — kết hợp OR với
                    // structuredSuspicious để không bỏ sót khi AI tự tin xác nhận "suspicious".
                    val hasCustomKeywords = camera.aiPositiveKeywords.isNotEmpty() || camera.aiNegativeKeywords.isNotEmpty()
                    isSuspicious = when {
                        hasCustomKeywords -> keywordMatch || visionParsed.structuredSuspicious == true
                        visionParsed.structuredSuspicious != null -> {
                            logger.d("CameraSkill", "🧩 [Structured Vision] camera=$tid state=${visionParsed.structuredSuspicious}")
                            visionParsed.structuredSuspicious
                        }
                        else -> keywordMatch
                    }
                    
                    if (isSuspicious) {
                        state.realEvents++
                        state.cooldownUntil = now + cooldownDurationMs()
                        
                        val cameraDailyEvents = dailyEvents.getOrPut(tid) { mutableListOf() }
                        cameraDailyEvents.add(DailyEvent(now, aiComment, optimizedBytes))
                        if (cameraDailyEvents.size > maxDailyEvents()) {
                            cameraDailyEvents.removeAt(0)
                        }

                        // ✅ SỬA LỖI #2 & #4: Lấy thông tin cảnh báo cuối cùng từ DB TRƯỚC để kiểm tra xem có gộp biến cố liên tiếp không
                        val latestAlert = withContext(Dispatchers.IO) {
                            database.alertDao().getLatestAlertForCamera(tid)
                        }
                        val mergeWindowMs = alertMergeWindowMs()
                        val latestEffectiveTime = latestAlert?.let { it.endTime ?: it.timestamp }
                        // ✅ SỬA: trước đây chỉ so sánh mốc thời gian, nên nếu giữa 2 lần suspicious
                        // có 1 lần AI quét thấy "bình thường" (vd kẻ lạ #1 rời đi rồi kẻ lạ #2 xuất
                        // hiện, cùng nằm trong mergeWindowMs tính từ alert #1), 2 sự kiện ĐỘC LẬP này
                        // vẫn bị gộp làm 1 -> không gửi email/push mới, có nguy cơ bỏ lỡ sự kiện thật
                        // thứ hai. Thêm điều kiện: không được có lần xác nhận "bình thường" nào xảy
                        // ra SAU mốc alert gần nhất thì mới coi là cùng 1 chuỗi sự kiện để gộp.
                        val hasNormalScanSinceLastAlert = latestEffectiveTime != null && state.lastNormalScanAt > latestEffectiveTime
                        val shouldMerge = latestAlert != null &&
                            latestAlert.isSuspicious == 1 &&
                            latestEffectiveTime != null &&
                            (now - latestEffectiveTime) <= mergeWindowMs &&
                            !hasNormalScanSinceLastAlert

                        // ✅ SỬA LỖI #3: ID cảnh báo và thông báo đồng bộ thống nhất
                        val activeAlertId = if (shouldMerge && latestAlert != null) {
                            latestAlert.id // Tái sử dụng ID cũ khi gộp
                        } else {
                            UUID.randomUUID().toString() // Tạo mới hoàn toàn nếu là biến cố độc lập
                        }

                       
                      
                      
                // 🧠 ỦY QUYỀN GỬI THÔNG BÁO VÀ EMAIL MẶC ĐỊNH SANG CHO QUẢN GIA
                        val emailSent = houseManagerProvider.get().sendDefaultCameraAlerts(
                            camera = camera,
                            aiComment = aiComment,
                            imageBytes = optimizedBytes,
                            activeAlertId = activeAlertId,
                            shouldMerge = shouldMerge
                        )

                        
                        
                        // Lưu cơ sở dữ liệu lịch sử cảnh báo
                        saveAlertToHistory(
                            alertId = activeAlertId,
                            camera = camera,
                            aiComment = aiComment,
                            imageBytes = optimizedBytes,
                            diff = currentDiff,
                            deltaTrigger = deltaTrigger,
                            absDiffTrigger = absDiffTrigger,
                            emailSent = emailSent,
                            scheduleId = scheduleId,
                            aiStateJson = visionParsed.rawJson?.toString(),
                            objects = visionParsed.objects,
                            shouldMerge = shouldMerge, // Truyền cờ xác định trạng thái gộp
                            existingAlert = latestAlert
                        )
                        
                        // Chỉ kích hoạt alertActions chạy kịch bản vật lý khi KHÔNG gộp sự kiện (Chống lặp lại rơ-le)
                        if (!shouldMerge) {
                            executeAlertActions(camera, aiComment, overrideAlertActions) 
                        }
                        logger.i("CameraSkill", "🚨 ALERT handled for camera $tid (merged=$shouldMerge, id=$activeAlertId): $aiComment")
                        
                    } else {
                        // ✅ MỚI: đánh dấu thời điểm AI vừa xác nhận "bình thường" — dùng để cắt
                        // chuỗi gộp cảnh báo ở nhánh isSuspicious phía trên (xem shouldMerge).


                      
                        // Đánh dấu thời điểm quét bình thường
                    state.lastNormalScanAt = now

                    if (isMature && isSuddenChange) {
                        pendingResets[tid] = PendingResetState(currentDiff, now)
                        logger.i("CameraSkill", "⚠️ Pending reset for camera $tid - monitoring next cycle")
                    }

                    withContext(Dispatchers.IO) {
                        // ✅ MỚI: nối danh sách object vào summary để buildMemoryContext (ChatSkill) có thể
                        // lọc/đếm theo loại đối tượng khi trả lời — trước đây summary chỉ có mô tả tự do.
                        val objectsSuffix = if (visionParsed.objects.isNotEmpty()) " [objects: ${visionParsed.objects.joinToString(",")}]" else ""
                        database.eventLogDao().insertLog(
                            com.aichatvn.agent.data.model.EventLogEntity(
                                id = UUID.randomUUID().toString(),
                                timestamp = now,
                                source = "camera",
                                sourceId = tid,
                                eventType = "state_change",
                                value = "Bình thường",
                                summary = "Camera ${camera.customername} ($tid): $aiComment$objectsSuffix"
                            )
                        )
                        // Ghi nhận trạng thái chung
                        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "state", value = "normal"
                        )
                        // ✅ MỚI: Ghi riêng "objects" vào world_state — WorldStateHelper.setAttribute() merge
                        // theo key vào cùng 1 JSON blob của (source, sourceId), nên KHÔNG cần đổi schema DB.
                        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "objects",
                            value = org.json.JSONArray(visionParsed.objects).toString()
                        )
                        // ✅ MỚI: Nếu có scheduleId, ghi nhận riêng trạng thái bình thường cho lịch trình này
                        if (!scheduleId.isNullOrBlank()) {
                            com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                                database.worldStateDao(), source = "camera", sourceId = tid, key = "state_$scheduleId", value = "normal"
                            )
                        }
                    }
                    }





                    
                }
                
                if (!shouldCallAi || !isSuspicious) {
                    state.baselineWindow.add(currentDiff)
                    if (state.baselineWindow.size > 20) {
                        state.baselineWindow.removeAt(0)
                    }
                    
                    if (isSuddenChange && !isSuspicious) {
                        state.falseDeltas.add(delta)
                        state.falseDiffs.add(currentDiff)
                        if (state.falseDeltas.size > 100) {
                            state.falseDeltas.removeAt(0)
                            state.falseDiffs.removeAt(0)
                        }
                    }
                    
                    if (state.falseDeltas.size >= 3) {
                        val recentDeltas = state.falseDeltas.takeLast(30).sorted()
                        val idx = (recentDeltas.size * 0.9).toInt().coerceIn(0, recentDeltas.size - 1)
                        state.deltaTrigger = (recentDeltas[idx] + 2).coerceAtMost(25)
                        
                        val recentDiffs = state.falseDiffs.takeLast(30).sorted()
                        val idxDiff = (recentDiffs.size * 0.9).toInt().coerceIn(0, recentDiffs.size - 1)
                        state.absDiffTrigger = (recentDiffs[idxDiff] + 3).coerceAtMost(35)
                    }
                }
                
                state.lastPhash = currentPhash
                state.lastDiff = currentDiff
                
                if (camera.isOnline != 1) {
                    withContext(Dispatchers.IO) {
                        database.cameraDao().updateCamera(camera.copy(isOnline = 1, status = "online"))
                    }
                }
                
                logger.d("CameraSkill", "📷 Camera $tid scanned, hasChange=$isSuddenChange")
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
                withTimeout(20_000L) {
                    groqClient.analyzeImage(optimizedBytes, prompt)
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("CameraSkill", "⏱️ Groq timeout (20s) camera=$tid (daily report)")
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
                learningStates[tid] = CameraLearningState()
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
        learningStates[tid] = CameraLearningState()
        logger.i("CameraSkill", "🔄 Circuit Breaker reset manually for camera $tid")
    }

    fun resetAllCircuitBreakers() {
        circuitBreakers.keys.forEach { id ->
            val tid = id.trim()
            circuitBreakers[tid] = CircuitBreakerState()
        }
        logger.i("CameraSkill", "🔄 All Circuit Breakers reset manually")
    }

    fun getDiagnostics(): Map<String, Any> = _diagnostics.value
    
    private suspend fun updateDiagnostics() {
        val stats = learningStates.mapValues { (cameraId, state) ->
            val tid = cameraId.trim()
            val cb = circuitBreakers[tid]
            mapOf(
                "samples" to state.falseDeltas.size,
                "realEvents" to state.realEvents,
                "deltaTrigger" to state.deltaTrigger,
                "absDiffTrigger" to state.absDiffTrigger,
                "baselineSize" to state.baselineWindow.size,
                "inCooldown" to (state.cooldownUntil > System.currentTimeMillis()),
                "circuitBreakerOpen" to (cb?.isOpen ?: false),
                "offlineCount" to (cb?.offlineCount ?: 0),
                "pendingReset" to pendingResets.containsKey(tid)
            )
        }
        _diagnostics.value = stats
    }

    // ✅ SỬA LỖI #2 & #3: Đổi cấu trúc hàm saveAlertToHistory() để nhận trực tiếp kết quả kiểm duyệt gộp ở trên
    private suspend fun saveAlertToHistory(
        alertId: String,
        camera: CameraConfigEntity,
        aiComment: String,
        imageBytes: ByteArray?,
        diff: Int,
        deltaTrigger: Int,
        absDiffTrigger: Int,
        emailSent: Boolean,
        scheduleId: String? = null,
        aiStateJson: String? = null,
        objects: List<String> = emptyList(),       // ✅ MỚI: nhãn đối tượng AI phát hiện, để ghi vào
                                                     // event log summary + world_state (xem parseVisionResult)
        shouldMerge: Boolean = false,              // Thêm cờ gộp đã phân tích trước
        existingAlert: AlertEntity? = null         // Nhận bản ghi cuối cùng đang hoạt động
    ) {
        val tid = camera.id.trim()
        try {
            val now = System.currentTimeMillis()

            if (shouldMerge && existingAlert != null) {
                // ✅ SỬA: trước đây chỉ updateAlertEndTime() — bản ghi lịch sử vẫn giữ nguyên
                // aiComment/diff/ảnh của lần alert ĐẦU TIÊN trong chuỗi gộp, trong khi push
                // notification lại hiện aiComment MỚI nhất -> người dùng bấm vào noti thấy thông
                // tin không khớp với alert_history. Giờ lưu đè ảnh mới lên đúng file cũ (tái sử
                // dụng alertId làm tên file, không phát sinh rác) và cập nhật toàn bộ nội dung.
                val refreshedImagePath = imageBytes?.let { saveAlertImage(existingAlert.id, it) }
                withContext(Dispatchers.IO) {
                    database.alertDao().mergeAlertUpdate(
                        alertId = existingAlert.id,
                        endTime = now,
                        aiComment = aiComment,
                        diff = diff,
                        imagePath = refreshedImagePath,
                        aiStateJson = aiStateJson
                    )
                }
                logger.d("CameraSkill", "🔗 [Nén sự kiện] Đã gộp và cập nhật nội dung mới nhất vào bản ghi #${existingAlert.id}")
            } else {
                val imagePath = imageBytes?.let { saveAlertImage(alertId, it) }

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
                    deltaTrigger = deltaTrigger,
                    absDiffTrigger = absDiffTrigger,
                    imagePath = imagePath,
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



            

            // Cập nhật lưu World State kèm theo ID lịch trình nếu có
                    withContext(Dispatchers.IO) {
                        // ✅ MỚI: nối object list vào summary — trước đây chỉ có $aiComment (mô tả tự do),
                        // khiến buildMemoryContext (ChatSkill) không thể lọc/đếm theo loại đối tượng.
                        val objectsSuffix = if (objects.isNotEmpty()) " [objects: ${objects.joinToString(",")}]" else ""
                        // ✅ ĐÃ SỬA: eventType trước đây hardcode "person_detected" cho MỌI cảnh báo,
                        // kể cả khi objects chỉ có "cat"/"car" — gây sai lệch dữ liệu lịch sử (nhật ký
                        // ghi "phát hiện mèo" nhưng eventType lại là person_detected). Nay suy ra động
                        // theo đúng nhãn đối tượng AI thực sự phát hiện được.
                        val derivedEventType = when {
                            objects.any { it in listOf("person", "nguoi", "người", "trộm", "thief", "human") } -> "person_detected"
                            objects.any { it in listOf("car", "motorbike", "vehicle", "xe", "oto", "truck") } -> "vehicle_detected"
                            objects.any { it in listOf("dog", "cat", "animal", "chó", "mèo", "con vật") } -> "animal_detected"
                            else -> "motion_detected" // Mặc định là phát hiện biến động chung
                        }
                        database.eventLogDao().insertLog(
                            com.aichatvn.agent.data.model.EventLogEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                timestamp = now,
                                source = "camera",
                                sourceId = tid,
                                eventType = derivedEventType,
                                value = "Phát hiện bất thường",
                                summary = "Camera ${camera.customername} ($tid) phát hiện: $aiComment$objectsSuffix"
                            )
                        )
                        // Ghi nhận trạng thái chung
                        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "state", value = "suspicious"
                        )
                        // ✅ MỚI: Ghi riêng "objects" vào world_state (merge theo key, không đổi schema DB)
                        com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
                            database.worldStateDao(), source = "camera", sourceId = tid, key = "objects",
                            value = org.json.JSONArray(objects).toString()
                        )
                        // ✅ MỚI: Nếu có scheduleId, ghi nhận riêng trạng thái phân tách cho lịch trình này
                        if (!scheduleId.isNullOrBlank()) {
                            com.aichatvn.agent.utils.WorldStateHelper.setAttribute(
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