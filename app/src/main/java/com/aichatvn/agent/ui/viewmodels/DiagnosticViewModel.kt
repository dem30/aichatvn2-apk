package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.DiagnosticInfo
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Số điện thoại VN: 10 số bắt đầu 0 (hoặc +84) theo sau đầu số di động hiện hành.
// Đặt trực tiếp ở đây thay vì 1 plugin riêng vì đây là công cụ kiểm toán NỘI BỘ cho Admin —
// chỉ ViewModel này gọi, không tham gia routing NLP nên không có đường nào để khách hàng
// kích hoạt qua chat và xem tin nhắn/SĐT của khách khác.
private val PHONE_REGEX = Regex("(?:\\+?84|0)(?:3|5|7|8|9)\\d{8}\\b")

data class AuditMessageResult(
    val username: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val phones: List<String>
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    private val database: AppDatabase,
    private val agentKernel: AgentKernel // ✅ MỚI: phục vụ màn Pipeline Graph (explainDeviceCommand)
) : ViewModel() {

    private val _combinedStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val combinedStats: StateFlow<Map<String, Any>> = _combinedStats.asStateFlow()

    // ───────────── Kiểm toán tin nhắn khách hàng (gộp từ AuditSkill) ─────────────
    private val _messageFilterResults = MutableStateFlow<List<AuditMessageResult>>(emptyList())
    val messageFilterResults: StateFlow<List<AuditMessageResult>> = _messageFilterResults.asStateFlow()

    private val _isFilteringMessages = MutableStateFlow(false)
    val isFilteringMessages: StateFlow<Boolean> = _isFilteringMessages.asStateFlow()

    // ───────────── Pipeline Graph (Node-Graph trực quan AgentKernel) ─────────────
    private val _pipelineTrace = MutableStateFlow<DiagnosticInfo?>(null)
    val pipelineTrace: StateFlow<DiagnosticInfo?> = _pipelineTrace.asStateFlow()

    private val _isExplaining = MutableStateFlow(false)
    val isExplaining: StateFlow<Boolean> = _isExplaining.asStateFlow()

    /**
     * Chạy thử 1 câu lệnh qua đúng pipeline chẩn đoán thật (explainDeviceCommand),
     * lấy về danh sách traces để màn PipelineGraphScreen vẽ call graph.
     * Không ảnh hưởng dữ liệu thật: dùng traceId "DIAGNOSTIC-TRACE" nội bộ trong
     * AgentKernel, KHÔNG ghi vào lịch sử chat của khách hàng thật (đã tách biệt sẵn
     * với ChatSkill.kt — xem quy tắc "chỉ ChatSkill ghi DB" đã thống nhất trước đó).
     */
    fun explainCommand(query: String, username: String = "default_user") {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isExplaining.value = true
            try {
                _pipelineTrace.value = agentKernel.explainDeviceCommand(query, username)
            } finally {
                _isExplaining.value = false
            }
        }
    }

    fun clearPipelineTrace() {
        _pipelineTrace.value = null
    }

    init {
        viewModelScope.launch {
            combine(
                cameraSkill.diagnostics,
                database.cameraDao().getAllCamerasFlow()
            ) { diagnostics, cameras ->
                val cameraStats = cameras.map { camera ->
                    mapOf(
                        "id" to camera.id,
                        "name" to camera.customername,
                        "status" to when {
                            camera.manualOff == 1 -> "Đã tắt"
                            camera.isOnline == 1 -> "Hoạt động"
                            else -> "Mất kết nối"
                        },
                        "isOnline" to camera.isOnline,
                        "manualOff" to camera.manualOff
                    )
                }

                // ✅ MỚI: Trạng thái Tuya/cảnh báo/lịch trình — gộp từ check_status của
                // HousekeeperSkill cũ, để 1 màn Diagnostics vừa xem camera vừa xem toàn cảnh hệ thống.
                val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
                val onlineTuya = tuyaDevices.count { it.online }
                val unreadAlerts = database.alertDao().getUnreadCountFlow().first()
                val schedules = database.scheduleDao().getAllSchedules()
                val activeSchedules = schedules.count { it.enabled == 1 }

                mapOf(
                    "learningStats" to diagnostics,
                    "cameras" to cameraStats,
                    "totalCameras" to cameraStats.size,
                    "onlineCameras" to cameraStats.count { it["isOnline"] == 1 && it["manualOff"] == 0 },
                    "offlineCameras" to cameraStats.count { it["isOnline"] != 1 && it["manualOff"] == 0 },
                    "disabledCameras" to cameraStats.count { it["manualOff"] == 1 },
                    "onlineTuyaDevices" to onlineTuya,
                    "totalTuyaDevices" to tuyaDevices.size,
                    "unreadAlerts" to unreadAlerts,
                    "activeSchedules" to activeSchedules,
                    "totalSchedules" to schedules.size
                )
            }
            // Tối ưu hóa lớn: Chuyển toàn bộ tác vụ ánh xạ mảng và tính toán đếm số lượng sang Dispatchers.Default
            .flowOn(Dispatchers.Default)
            .collect { _combinedStats.value = it }
        }
    }

    fun resetCircuitBreaker(cameraId: String) {
        viewModelScope.launch {
            cameraSkill.resetCircuitBreaker(cameraId)
        }
    }

    fun resetAllCircuitBreakers() {
        viewModelScope.launch {
            cameraSkill.resetAllCircuitBreakers()
        }
    }

    // ───────────── Lọc tin nhắn khách hàng ─────────────
    /**
     * Lọc tin nhắn theo từ khóa / số điện thoại / theo từng khách. Chỉ gọi từ DiagnosticsScreen
     * (màn Admin) — không có đường nào từ NLP/chat khách hàng chạm tới hàm này.
     */
    fun filterMessages(
        username: String? = null,
        keyword: String? = null,
        onlyWithPhone: Boolean = false,
        limit: Int = 100
    ) {
        viewModelScope.launch {
            _isFilteringMessages.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    val cleanKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }

                    // ⚠️ CẦN THÊM vào ChatMessageDao (chưa có trong các file đã gửi):
                    //   @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
                    //   suspend fun getAllMessagesRaw(limit: Int): List<ChatMessageEntity>
                    val pool = if (username != null) {
                        database.chatMessageDao().getMessages(username, limit)
                    } else {
                        database.chatMessageDao().getAllMessagesRaw(limit * 5) // lấy dư để lọc tiếp
                    }

                    pool.asSequence()
                        .filter { cleanKeyword == null || it.content.contains(cleanKeyword, ignoreCase = true) }
                        .filter { !onlyWithPhone || PHONE_REGEX.containsMatchIn(it.content) }
                        .take(limit)
                        .map { msg ->
                            AuditMessageResult(
                                username = msg.username,
                                role = msg.role,
                                content = msg.content,
                                timestamp = msg.timestamp,
                                phones = PHONE_REGEX.findAll(msg.content).map { it.value }.toList()
                            )
                        }
                        .toList()
                }
                _messageFilterResults.value = results
            } finally {
                _isFilteringMessages.value = false
            }
        }
    }

    fun clearMessageFilter() {
        _messageFilterResults.value = emptyList()
    }
}