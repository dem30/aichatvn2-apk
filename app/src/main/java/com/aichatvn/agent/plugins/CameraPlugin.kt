package com.aichatvn.agent.plugins

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginContext
import com.aichatvn.agent.core.plugin.PluginEvent
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.core.plugin.PluginResult
import com.aichatvn.agent.core.plugin.Subscription
import com.aichatvn.agent.di.ApplicationScope
import com.aichatvn.agent.skills.CameraSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraPlugin @Inject constructor(
    private val cameraSkill: CameraSkill,
    @ApplicationScope private val appScope: CoroutineScope
) : Plugin {
    
    override val manifest = PluginManifest(
        id = "camera",
        name = "Camera AI Plugin",
        version = "1.0.0",
        description = "Quản lý và giám sát camera an ninh",
        author = "AIChatVN2",
        keywords = listOf("camera", "cam", "kho", "phòng", "nhà"),
        actions = listOf(
            PluginAction(
                name = "set_active",
                description = "Bật hoặc tắt camera",
                keywords = listOf("bật", "tắt", "mở", "ngưng"),
                parameters = listOf(
                    PluginParameter("customerId", "string", "Mã camera", true),
                    PluginParameter("active", "boolean", "true: bật, false: tắt", true)
                )
            ),
            PluginAction(
                name = "set_smart_mode",
                description = "Bật hoặc tắt chế độ thông minh AI",
                keywords = listOf("chế độ thông minh", "smart mode"),
                parameters = listOf(
                    PluginParameter("customerId", "string", "Mã camera", true),
                    PluginParameter("enabled", "boolean", "true: bật, false: tắt", true)
                )
            ),
            PluginAction(
                name = "list",
                description = "Liệt kê danh sách camera",
                keywords = listOf("liệt kê", "danh sách", "list"),
                parameters = emptyList()
            ),
            PluginAction(
                name = "status",
                description = "Xem trạng thái camera",
                keywords = listOf("trạng thái", "kiểm tra", "status"),
                parameters = listOf(
                    PluginParameter("customerId", "string", "Mã camera (để trống xem tất cả)", false)
                )
            ),
            PluginAction(
                name = "scan",
                description = "Quét camera ngay lập tức",
                keywords = listOf("quét", "scan"),
                parameters = listOf(
                    PluginParameter("cameraId", "string", "Mã camera (để trống quét hết)", false)
                )
            )
        ),
        publishes = listOf("CAMERA_OFFLINE", "CAMERA_ONLINE", "ALERT_DETECTED")
    )
    
    private var subscriptions = mutableListOf<Subscription>()
    private var monitoringJob: Job? = null
    private val lastKnownOnline = mutableMapOf<String, Boolean>()
    private lateinit var eventBus: com.aichatvn.agent.core.plugin.PluginEventBus
    private lateinit var context: PluginContext
    
    override suspend fun initialize(context: PluginContext) {
        this.context = context
        this.eventBus = context.eventBus
        cameraSkill.initialize()
        
        // Theo dõi trạng thái camera từ CameraSkill và publish event
        startMonitoringCameraStatus()
    }
    
    /**
     * Theo dõi trạng thái online/offline của tất cả camera liên tục (real-time)
     * bằng cách collect Flow từ DB, thay vì check 1 lần rồi dừng.
     * Mỗi khi trạng thái online của một camera thay đổi, publish CAMERA_ONLINE/CAMERA_OFFLINE.
     */
    private fun startMonitoringCameraStatus() {
        context.logger.d("CameraPlugin", "Started monitoring camera status (continuous)")
        
        monitoringJob = appScope.launch {
            cameraSkill.observeCameras().collect { cameras ->
                try {
                    cameras.forEach { camera ->
                        val isOnline = camera.isOnline == 1
                        val previous = lastKnownOnline[camera.id]
                        
                        if (previous != isOnline) {
                            lastKnownOnline[camera.id] = isOnline
                            
                            // Bỏ qua lần publish đầu tiên (khi vừa subscribe) để tránh
                            // spam event ngay lúc khởi động app cho mọi camera.
                            if (previous != null) {
                                eventBus.publish(PluginEvent(
                                    type = if (isOnline) "CAMERA_ONLINE" else "CAMERA_OFFLINE",
                                    source = manifest.id,
                                    payload = mapOf("cameraId" to camera.id)
                                ))
                            }
                        }
                    }
                    
                    // Dọn lastKnownOnline cho camera đã bị xóa khỏi DB
                    val currentIds = cameras.map { it.id }.toSet()
                    lastKnownOnline.keys.retainAll(currentIds)
                } catch (e: Exception) {
                    context.logger.e("CameraPlugin", "Error checking camera status: ${e.message}")
                }
            }
        }
    }
    
    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return try {
            when (action) {
                "set_active" -> {
                    val customerId = params["customerId"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("customerId"),
                            "Bạn muốn bật/tắt camera nào?"
                        )
                    val active = params["active"] as? Boolean 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("active"),
                            "Bạn muốn bật hay tắt camera $customerId?"
                        )
                    val result = cameraSkill.setCustomerActive(customerId, active)
                    if (result.success) {
                        // Publish event khi camera thay đổi trạng thái
                        eventBus.publishAndForget(PluginEvent(
                            type = if (active) "CAMERA_ONLINE" else "CAMERA_OFFLINE",
                            source = manifest.id,
                            payload = mapOf("cameraId" to customerId)
                        ))
                        PluginResult.Success(mapOf("message" to result.data))
                    } else {
                        PluginResult.Failure(result.error ?: "Không thể thực hiện")
                    }
                }
                "set_smart_mode" -> {
                    val customerId = params["customerId"] as? String 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("customerId"),
                            "Bạn muốn bật/tắt chế độ thông minh cho camera nào?"
                        )
                    val enabled = params["enabled"] as? Boolean 
                        ?: return PluginResult.NeedMoreInfo(
                            listOf("enabled"),
                            "Bạn muốn bật hay tắt chế độ thông minh?"
                        )
                    val result = cameraSkill.setSmartMode(customerId, enabled)
                    if (result.success) {
                        PluginResult.Success(mapOf("message" to result.data))
                    } else {
                        PluginResult.Failure(result.error ?: "Không thể thực hiện")
                    }
                }
                "list" -> {
                    val result = cameraSkill.getCamerasPaginated(1, 100)
                    if (result.success) {
                        // SỬA LỖI CAST: Trả về dữ liệu an toàn
                        val safeData = when (val data = result.data) {
                            is Map<*, *> -> data
                            is List<*> -> data
                            else -> emptyList<Any>()
                        }
                        PluginResult.Success(safeData)
                    } else {
                        PluginResult.Failure(result.error ?: "Không thể lấy danh sách")
                    }
                }
                "status" -> {
                    val customerId = params["customerId"] as? String
                    if (customerId != null) {
                        val result = cameraSkill.getCamerasPaginated(1, 100)
                        if (result.success && result.data != null) {
                            try {
                                val cameras = when (val data = result.data) {
                                    is Map<*, *> -> data["cameras"] as? List<*> ?: emptyList()
                                    is List<*> -> data
                                    else -> emptyList()
                                }
                                val camera = cameras.find { 
                                    (it as? Map<*, *>)?.get("id") == customerId 
                                }
                                if (camera != null) {
                                    PluginResult.Success(camera)
                                } else {
                                    PluginResult.Failure("Không tìm thấy camera $customerId")
                                }
                            } catch (e: Exception) {
                                PluginResult.Failure("Lỗi xử lý dữ liệu: ${e.message}")
                            }
                        } else {
                            PluginResult.Failure(result.error ?: "Không thể lấy thông tin camera")
                        }
                    } else {
                        val result = cameraSkill.getDiagnostics()
                        PluginResult.Success(result)
                    }
                }
                "scan" -> {
                    // cameraId = null hợp lệ — scan toàn bộ camera
                    val cameraId = params["cameraId"] as? String
                    val result = cameraSkill.scanCamera(cameraId, false)
                    if (result.success) {
                        // Không publish ALERT_DETECTED ở đây:
                        // CameraSkill đã tự publish qua PluginEventBus khi thực sự phát hiện bất thường.
                        // Publish thêm ở đây sẽ gây duplicate alert.
                        val data = result.data as? Map<*, *>
                        val processed = data?.get("processed") ?: 0
                        PluginResult.Success(mapOf("message" to "Đã quét $processed camera thành công"))
                    } else {
                        PluginResult.Failure(result.error ?: "Quét thất bại")
                    }
                }
                else -> PluginResult.Failure("Action không xác định: $action")
            }
        } catch (e: Exception) {
            PluginResult.Failure("Lỗi: ${e.message}")
        }
    }
    
    override suspend fun onEvent(event: PluginEvent) {
        context.logger.d("CameraPlugin", "Received event: ${event.type}")
        when (event.type) {
            "EMAIL_SENT" -> {
                // Phản ứng khi email được gửi
                context.logger.d("CameraPlugin", "Email sent event received, could trigger camera recording")
            }
        }
    }
    
    override suspend fun shutdown() {
        monitoringJob?.cancel()
        monitoringJob = null
        subscriptions.forEach { it.unsubscribe() }
        subscriptions.clear()
        cameraSkill.shutdown()
    }
}