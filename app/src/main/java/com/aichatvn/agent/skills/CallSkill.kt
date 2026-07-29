package com.aichatvn.agent.skills

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CallContactEntity
import com.aichatvn.agent.data.model.CallDirection
import com.aichatvn.agent.data.model.CallLogEntity
import com.aichatvn.agent.data.model.CallLogStatus
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallState { IDLE, DIALING, RINGING, CONNECTING, CONNECTED, ENDED, FAILED }

data class CallUiState(
    val state: CallState = CallState.IDLE,
    val remoteDeviceCode: String? = null,
    val callId: String? = null,
    val isVideo: Boolean = true,
    val errorMessage: String? = null,
    // ✅ MỚI: tên hiển thị của đối phương (lấy từ CallContactEntity nếu mã máy này đã
    // được lưu trong danh bạ) — null nếu chưa lưu, UI tự fallback về remoteDeviceCode.
    val peerDisplayName: String? = null,
    // ✅ MỚI: trạng thái mute/loa hiện tại, để CallScreen render đúng icon nút bấm mà
    // không cần tự giữ 1 bản state riêng dễ lệch với CallSkill.
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true
)

/**
 * CallSkill
 *
 * Skill gọi thoại/video P2P (WebRTC) giữa 2 thiết bị AIChatVN2, dùng
 * GatewaySignalingManager làm kênh báo hiệu (signaling) qua Webhook Gateway SSE
 * có sẵn — không cần server signaling riêng.
 *
 * Luồng: start_call (tạo offer) -> gateway chuyển tiếp -> answer_call (tạo
 * answer) -> trao đổi ICE candidate qua signaling -> kết nối P2P trực tiếp.
 *
 * ✅ MỚI: thêm danh bạ (call_contacts) để không phải nhớ/nhập lại mã máy 8 ký tự mỗi
 * lần gọi, và lịch sử gọi (call_logs) ghi lại MỖI cuộc gọi đã kết thúc (thành công,
 * nhỡ, từ chối, hay lỗi kết nối) kèm thời lượng thực tế đã nói chuyện.
 */
@Singleton
class CallSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,
    private val signalingManager: GatewaySignalingManager,
    // ✅ MỚI: notification full-screen kiểu cuộc gọi đến (CallStyle) — tách riêng khỏi
    // NotificationSkill vì đây là logic đặc thù WebRTC (nút Nghe/Từ chối gọi thẳng
    // CallSkill.execute() qua CallActionReceiver, không phải push chung).
    private val incomingCallNotificationHelper: com.aichatvn.agent.utils.IncomingCallNotificationHelper,
    logger: Logger,
) : BaseSkill("call", "Gọi thoại & Video P2P", logger), Plugin {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(dashboard = true),
        visibleOnDashboard = true,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "start_call",
                description = "Gọi video/thoại tới 1 thiết bị khác qua mã máy",
                examples = listOf("gọi cho máy A1B2C3D4"),
                parameters = listOf(
                    PluginParameter("targetDeviceCode", "string", "Mã máy cần gọi tới", true, "call"),
                    PluginParameter("video", "boolean", "Bật video hay chỉ thoại", false, "boolean")
                )
            ),
            PluginAction(
                name = "answer_call",
                description = "Nhận cuộc gọi đang đổ chuông",
                examples = listOf("nhận cuộc gọi", "nghe máy"),
                parameters = listOf(PluginParameter("callId", "string", "ID cuộc gọi đang đổ chuông", true, "call"))
            ),
            PluginAction(
                name = "reject_call",
                description = "Từ chối cuộc gọi đang đổ chuông",
                examples = listOf("từ chối cuộc gọi", "bắt máy"),
                parameters = listOf(PluginParameter("callId", "string", "ID cuộc gọi đang đổ chuông", true, "call"))
            ),
            PluginAction(
                name = "end_call",
                description = "Kết thúc cuộc gọi đang diễn ra",
                examples = listOf("kết thúc cuộc gọi", "cúp máy"),
                parameters = emptyList()
            ),
            PluginAction(
                name = "get_my_code",
                description = "Lấy mã định danh thiết bị này để máy khác gọi tới",
                examples = listOf("mã máy của tôi là gì"),
                parameters = emptyList()
            ),
            // ✅ MỚI: cho phép lưu số qua chat/voice, không chỉ qua UI DialScreen
            PluginAction(
                name = "save_contact",
                description = "Lưu 1 mã máy thành số liên hệ có tên gợi nhớ trong danh bạ",
                examples = listOf("lưu số A1B2C3D4 tên là Mẹ"),
                parameters = listOf(
                    PluginParameter("deviceCode", "string", "Mã máy cần lưu", true, "call"),
                    PluginParameter("displayName", "string", "Tên gợi nhớ", true, "call")
                )
            )
        )
    )

    private val skillScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _callUiState = MutableStateFlow(CallUiState())
    val callUiState: StateFlow<CallUiState> = _callUiState.asStateFlow()

    val eglBase: EglBase = EglBase.create()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null

    // ✅ MỚI: client HTTP riêng cho việc lấy iceServers động từ Metered TURN REST API —
    // tách khỏi httpClient của GatewaySignalingManager để không phụ thuộc lẫn nhau.
    private val turnApiClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ✅ MỚI: chuông + rung khi có cuộc gọi đến (RINGING)
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    // ✅ MỚI (sửa "người gọi không biết đang đổ chuông, máy gọi im lặng hoàn toàn"):
    // ToneGenerator phát âm "tút... tút..." tiêu chuẩn ở MÁY GỌI ĐI trong lúc DIALING —
    // trước đây chỉ máy NHẬN có âm thanh (startRingtoneAndVibration() qua Ringtone),
    // máy gọi hoàn toàn im lặng nên người gọi không có cách nào biết cuộc gọi đã tới
    // đối phương hay đang treo/lỗi mạng.
    private var ringbackToneGenerator: ToneGenerator? = null

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var incomingOfferCache: JSONObject? = null

    // ✅ MỚI: phục vụ ghi CallLogEntity khi cuộc gọi kết thúc — cần biết chiều cuộc gọi,
    // thời điểm bắt đầu (dialing/ringing), và thời điểm CONNECTED thật sự (để tính
    // durationSec chỉ tính thời gian ĐÃ NÓI CHUYỆN, không tính lúc đổ chuông/kết nối).
    private var callDirectionForCurrentCall: String? = null
    private var callStartedAtMs: Long = 0L
    private var connectedAtMs: Long = 0L

    // ✅ SỬA LỖI: job hoãn reset về IDLE sau ENDED/FAILED — được hủy bất cứ khi nào 1 cuộc
    // gọi MỚI bắt đầu (startCall/answerCall), để tránh trường hợp job reset của cuộc gọi
    // CŨ chạy trễ và đè mất state của cuộc gọi MỚI đang diễn ra.
    private var returnToIdleJob: kotlinx.coroutines.Job? = null

    private fun scheduleReturnToIdle() {
        returnToIdleJob?.cancel()
        returnToIdleJob = skillScope.launch {
            kotlinx.coroutines.delay(1500)
            if (_callUiState.value.state == CallState.ENDED || _callUiState.value.state == CallState.FAILED) {
                _callUiState.value = CallUiState(state = CallState.IDLE)
            }
        }
    }

    init {
        signalingManager.onCallSignalReceived = { signal ->
            skillScope.launch { handleIncomingSignal(signal) }
        }
        initPeerConnectionFactory()
    }

    override suspend fun execute(action: String, params: Map<String, Any>): PluginResult {
        return try {
            when (action) {
                "start_call" -> {
                    val target = params["targetDeviceCode"]?.toString()?.trim()
                        ?: return PluginResult.Failure("Thiếu mã máy targetDeviceCode")
                    val withVideo = (params["video"] as? Boolean) ?: true
                    startCall(target, withVideo)
                }
                "answer_call" -> {
                    val callId = params["callId"]?.toString()
                        ?: return PluginResult.Failure("Thiếu callId")
                    answerCall(callId)
                }
                "reject_call" -> {
                    val callId = params["callId"]?.toString()
                        ?: return PluginResult.Failure("Thiếu callId")
                    rejectCall(callId)
                }
                "end_call" -> endCall()
                "get_my_code" -> PluginResult.Success(mapOf("deviceCode" to getOrCreateMyDeviceCode()))
                "save_contact" -> {
                    val code = params["deviceCode"]?.toString()?.trim()
                        ?: return PluginResult.Failure("Thiếu deviceCode")
                    val name = params["displayName"]?.toString()?.trim()
                        ?: return PluginResult.Failure("Thiếu displayName")
                    saveContact(code, name)
                    PluginResult.Success(mapOf("deviceCode" to code.uppercase(), "displayName" to name))
                }
                else -> PluginResult.Failure("Hành động không hỗ trợ: $action")
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "Lỗi thực thi $action: ${e.message}", e)
            PluginResult.Failure("Lỗi cuộc gọi: ${e.message}")
        }
    }

    // ==================== DANH BẠ & LỊCH SỬ (MỚI) ====================

    fun observeContacts(): Flow<List<CallContactEntity>> = database.callContactDao().observeAll()

    fun observeCallLogs(): Flow<List<CallLogEntity>> = database.callLogDao().observeRecent()

    suspend fun saveContact(deviceCode: String, displayName: String) {
        val cleanCode = deviceCode.trim().uppercase()
        val cleanName = displayName.trim()
        if (cleanCode.isBlank() || cleanName.isBlank()) return
        try {
            database.callContactDao().upsert(CallContactEntity(deviceCode = cleanCode, displayName = cleanName))
        } catch (e: Exception) {
            logger.e("CallSkill", "Không lưu được contact: ${e.message}")
        }
    }

    suspend fun deleteContact(deviceCode: String) {
        try {
            database.callContactDao().delete(deviceCode.trim().uppercase())
        } catch (e: Exception) {
            logger.e("CallSkill", "Không xoá được contact: ${e.message}")
        }
    }

    private suspend fun resolvePeerDisplayName(deviceCode: String): String? =
        try {
            database.callContactDao().getByDeviceCode(deviceCode)?.displayName
        } catch (e: Exception) {
            null
        }

    /**
     * Ghi 1 dòng CallLogEntity khi cuộc gọi hiện tại kết thúc (bất kể lý do). Nhận các
     * giá trị cần thiết qua tham số (thay vì tự đọc _callUiState.value tại thời điểm gọi)
     * vì nhiều nơi gọi hàm này NGAY TRƯỚC KHI ghi đè _callUiState sang trạng thái mới —
     * đọc trực tiếp lúc đó có thể đã bị mất callId/remoteDeviceCode.
     */
    private fun finalizeCallLog(
        callId: String?,
        peerDeviceCode: String?,
        peerName: String?,
        direction: String?,
        isVideo: Boolean,
        startedAt: Long,
        explicitStatus: String? = null
    ) {
        if (callId == null || peerDeviceCode == null || direction == null || startedAt == 0L) {
            // Không đủ dữ liệu để ghi log có ý nghĩa (VD: cuộc gọi chưa từng thực sự bắt đầu)
            connectedAtMs = 0L
            callStartedAtMs = 0L
            callDirectionForCurrentCall = null
            return
        }

        val everConnected = connectedAtMs > 0L
        val status = explicitStatus ?: when {
            everConnected -> CallLogStatus.ANSWERED
            direction == CallDirection.INCOMING -> CallLogStatus.MISSED
            else -> CallLogStatus.FAILED
        }
        val durationSec = if (everConnected) {
            ((System.currentTimeMillis() - connectedAtMs) / 1000L).coerceAtLeast(0L)
        } else 0L

        skillScope.launch(Dispatchers.IO) {
            try {
                database.callLogDao().insert(
                    CallLogEntity(
                        callId = callId,
                        peerDeviceCode = peerDeviceCode,
                        peerName = peerName,
                        direction = direction,
                        status = status,
                        isVideo = isVideo,
                        startedAt = startedAt,
                        durationSec = durationSec
                    )
                )
            } catch (e: Exception) {
                logger.e("CallSkill", "Không ghi được lịch sử cuộc gọi: ${e.message}")
            }
        }

        // ✅ SỬA (nhất quán event_log/call_logs): trước đây logEvent() được gọi rải rác ở
        // 6 nơi khác nhau ngoài hàm này (rejectCall/endCall có gọi, nhưng SDP offer/answer
        // thất bại, đối phương cúp máy (call_end nhận từ xa), và ICE FAILED/DISCONNECTED thì
        // KHÔNG hề gọi) — khiến event_logs bị thiếu hẳn 1 số cuộc gọi mà call_logs vẫn có.
        // Chuyển logEvent() vào ĐÂY vì finalizeCallLog() vốn đã chạy ở TẤT CẢ 6 đường thoát
        // cuộc gọi không điều kiện — ghi cùng 1 chỗ, cùng 1 lần gọi, đảm bảo event_logs không
        // bao giờ thiếu 1 cuộc gọi nào nữa, giống pattern world_state+event_logs của CameraSkill.
        val peerLabel = peerName?.takeIf { it.isNotBlank() } ?: peerDeviceCode
        val directionLabel = if (direction == CallDirection.INCOMING) "đến" else "đi"
        val (eventType, statusLabel) = when (status) {
            CallLogStatus.ANSWERED -> "call_ended" to "đã kết thúc (${durationSec}s)"
            CallLogStatus.MISSED -> "call_missed" to "bị nhỡ"
            CallLogStatus.REJECTED -> "call_rejected" to "bị từ chối"
            else -> "call_failed" to "thất bại"
        }
        logEvent(eventType, peerDeviceCode, "Cuộc gọi $directionLabel với $peerLabel $statusLabel")

        connectedAtMs = 0L
        callStartedAtMs = 0L
        callDirectionForCurrentCall = null
    }

    // ==================== MUTE / LOA (MỚI) ====================

    // Chỉ đổi cờ enabled trên track audio local hiện có — không đụng tới track của đối phương.
    fun toggleMute() {
        val newMuted = !_callUiState.value.isMuted
        localAudioTrack?.setEnabled(!newMuted)
        _callUiState.value = _callUiState.value.copy(isMuted = newMuted)
    }

    // addLocalMediaTracks() đã mặc định bật loa ngoài cho MODE_IN_COMMUNICATION — hàm này
    // cho phép người dùng tự tắt để chuyển sang tai nghe/loa trong lúc đang gọi.
    fun toggleSpeaker() {
        val newSpeakerOn = !_callUiState.value.isSpeakerOn
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = newSpeakerOn
        _callUiState.value = _callUiState.value.copy(isSpeakerOn = newSpeakerOn)
    }

    // ==================== CORE CALL FLOW ====================

    // ✅ SỬA LỖI: Mutex bảo vệ đoạn đọc-rồi-ghi (read-then-write) bên dưới. Trước đây,
    // WebhookGatewayService.registerDeviceCode() và startCallSignalSSE() (chạy trên 2
    // coroutine RIÊNG, khởi động gần như đồng thời lúc service start) đều tự đọc/tự sinh
    // deviceCode ĐỘC LẬP, không khóa với nhau — có thể cùng thấy config rỗng, cùng sinh 2
    // mã NGẪU NHIÊN KHÁC NHAU. Giờ cả 2 nơi đó gọi thẳng hàm NÀY (nguồn duy nhất), và
    // Mutex đảm bảo dù gọi đồng thời từ nhiều coroutine, chỉ 1 mã được sinh/lưu.
    private val deviceCodeMutex = Mutex()

    suspend fun getOrCreateMyDeviceCode(): String = deviceCodeMutex.withLock {
        var existing = configProvider.getString(AppConfigDefaults.CALL_DEVICE_CODE).trim()
        if (existing.isNotBlank()) return@withLock existing
        existing = UUID.randomUUID().toString().take(8).uppercase()
        configProvider.set(AppConfigDefaults.CALL_DEVICE_CODE, existing)
        existing
    }

    private suspend fun startCall(targetDeviceCode: String, withVideo: Boolean): PluginResult {
        if (_callUiState.value.state != CallState.IDLE) {
            return PluginResult.Failure("Đang trong cuộc gọi khác")
        }
        val callId = UUID.randomUUID().toString()
        val myCode = getOrCreateMyDeviceCode()
        val peerName = resolvePeerDisplayName(targetDeviceCode)

        returnToIdleJob?.cancel()
        callDirectionForCurrentCall = CallDirection.OUTGOING
        callStartedAtMs = System.currentTimeMillis()
        connectedAtMs = 0L

        _callUiState.value = CallUiState(
            state = CallState.DIALING,
            remoteDeviceCode = targetDeviceCode,
            callId = callId,
            isVideo = withVideo,
            peerDisplayName = peerName
        )

        // ✅ MỚI: phát âm tút-tút cho người gọi ngay khi bắt đầu quay số, dừng lại ở
        // mọi điểm thoát khỏi DIALING (call_answer/call_end/FAILED) — xem
        // startRingbackTone()/stopRingbackTone().
        startRingbackTone()

        peerConnection = createPeerConnection(callId, targetDeviceCode)
        addLocalMediaTracks(withVideo)

        val offer = createLocalOffer() ?: run {
            val snapshot = _callUiState.value
            finalizeCallLog(
                snapshot.callId, snapshot.remoteDeviceCode, snapshot.peerDisplayName,
                callDirectionForCurrentCall, withVideo, callStartedAtMs, explicitStatus = CallLogStatus.FAILED
            )
            _callUiState.value = CallUiState(state = CallState.FAILED, errorMessage = "Không tạo được SDP offer")
            // ✅ SỬA LỖI: thiếu dòng này khiến state bị kẹt vĩnh viễn ở FAILED — vì
            // scheduleReturnToIdle() trước đây CHỈ được gọi từ onIceConnectionChange
            // (FAILED/DISCONNECTED/CLOSED) và từ handleIncomingSignal("call_end"). Khi
            // lỗi xảy ra NGAY TẠI ĐÂY (createOffer thất bại trước khi có peerConnection
            // để bắn ICE state), không nhánh nào trong 2 nhánh trên được kích hoạt, nên
            // CallSkill kẹt ở FAILED mãi mãi — mọi lần start_call sau đó đều bị chặn ở
            // dòng "Đang trong cuộc gọi khác" ngay đầu hàm, kể cả khi không hề có cuộc
            // gọi nào đang thực sự diễn ra. Máy bị dính lỗi này không bao giờ gửi được
            // call_offer nữa cho tới khi tắt hẳn app (reset lại singleton CallSkill).
            scheduleReturnToIdle()
            return PluginResult.Failure("Không tạo được SDP offer")
        }

        signalingManager.sendCallSignal(
            targetDeviceCode,
            JSONObject().apply {
                put("type", "call_offer")
                put("callId", callId)
                put("from", myCode)
                put("sdp", offer.description)
                put("sdpType", offer.type.canonicalForm())
                put("video", withVideo)
            }
        )

        // ✅ MỚI: đánh dấu "vừa gọi" cho contact này (nếu đã lưu) để danh bạ có thể sort
        // theo gần đây; im lặng bỏ qua nếu mã này chưa từng được lưu (không có row để update).
        skillScope.launch(Dispatchers.IO) {
            try {
                database.callContactDao().touchLastCalled(targetDeviceCode, System.currentTimeMillis())
            } catch (e: Exception) {
                // Chưa lưu contact này — không phải lỗi, bỏ qua.
            }
        }

        logEvent("outgoing_call", targetDeviceCode, "Cuộc gọi đi tới mã máy $targetDeviceCode")
        return PluginResult.Success(mapOf("callId" to callId, "state" to "dialing"))
    }

    private suspend fun answerCall(callId: String): PluginResult {
        val offerSignal = incomingOfferCache?.takeIf { it.optString("callId") == callId }
            ?: run {
                // ✅ SỬA LỖI (root cause "cuộc gọi nhỡ vẫn hiện, bấm Nghe không được"):
                // incomingOfferCache đã bị teardownPeerConnection() xóa (= null) ngay khi
                // nhận "call_end" — nghĩa là bên gọi đã cúp máy TRƯỚC KHI người này bấm
                // Nghe (cuộc gọi nhỡ). Notification/CallScreen có thể vẫn đang hiển thị
                // "cuộc gọi đến" do độ trễ dọn dẹp của hệ thống hoặc do màn hình đang tắt
                // đúng lúc call_end xử lý. Trước đây chỉ trả Failure ở đây — với người
                // dùng, bấm "Nghe" không phản hồi gì trông giống hệt app bị treo/lỗi.
                // Chủ động đưa UI về IDLE ngay để rõ ràng "cuộc gọi đã kết thúc", thay vì
                // im lặng báo lỗi và để màn hình kẹt ở trạng thái cũ.
                if (_callUiState.value.callId == callId && _callUiState.value.state != CallState.IDLE) {
                    incomingCallNotificationHelper.cancelIncomingCallNotification(callId)
                    _callUiState.value = CallUiState(state = CallState.IDLE)
                }
                return PluginResult.Failure("Cuộc gọi đã kết thúc (cuộc gọi nhỡ)")
            }

        val fromCode = offerSignal.getString("from")
        val withVideo = offerSignal.optBoolean("video", true)
        // Giữ lại tên đã resolve từ lúc RINGING (nếu có) — tạo CallUiState() mới bên dưới
        // sẽ mất field này nếu không truyền lại tường minh.
        val previousPeerName = _callUiState.value.peerDisplayName

        returnToIdleJob?.cancel()
        stopRingtoneAndVibration()
        incomingCallNotificationHelper.cancelIncomingCallNotification(callId)

        _callUiState.value = CallUiState(
            state = CallState.CONNECTING,
            remoteDeviceCode = fromCode,
            callId = callId,
            isVideo = withVideo,
            peerDisplayName = previousPeerName
        )

        peerConnection = createPeerConnection(callId, fromCode)
        addLocalMediaTracks(withVideo)

        setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSignal.getString("sdp")))
        flushPendingIceCandidates()

        val answer = createLocalAnswer() ?: run {
            val snapshot = _callUiState.value
            finalizeCallLog(
                snapshot.callId, snapshot.remoteDeviceCode, snapshot.peerDisplayName,
                callDirectionForCurrentCall, withVideo, callStartedAtMs, explicitStatus = CallLogStatus.FAILED
            )
            _callUiState.value = CallUiState(state = CallState.FAILED, errorMessage = "Không tạo được SDP answer")
            // ✅ SỬA LỖI: cùng lỗi kẹt state vĩnh viễn như nhánh createLocalOffer() ở
            // startCall() — xem giải thích chi tiết ở đó.
            scheduleReturnToIdle()
            return PluginResult.Failure("Không tạo được SDP answer")
        }

        signalingManager.sendCallSignal(
            fromCode,
            JSONObject().apply {
                put("type", "call_answer")
                put("callId", callId)
                put("from", getOrCreateMyDeviceCode())
                put("sdp", answer.description)
                put("sdpType", answer.type.canonicalForm())
            }
        )

        logEvent("call_answered", fromCode, "Đã chấp nhận cuộc gọi từ mã máy $fromCode")
        return PluginResult.Success(mapOf("callId" to callId, "state" to "connecting"))
    }

    private fun rejectCall(callId: String): PluginResult {
        val fromCode = incomingOfferCache?.optString("from")
        val snapshot = _callUiState.value
        if (fromCode != null) {
            signalingManager.sendCallSignal(
                fromCode,
                JSONObject().apply {
                    put("type", "call_end")
                    put("callId", callId)
                    put("reason", "rejected")
                }
            )
        }
        finalizeCallLog(
            snapshot.callId, snapshot.remoteDeviceCode, snapshot.peerDisplayName,
            callDirectionForCurrentCall, snapshot.isVideo, callStartedAtMs, explicitStatus = CallLogStatus.REJECTED
        )
        incomingCallNotificationHelper.cancelIncomingCallNotification(callId)
        teardownPeerConnection()
        _callUiState.value = CallUiState(state = CallState.IDLE)
        return PluginResult.Success(mapOf("state" to "rejected"))
    }

    fun endCall(): PluginResult {
        val current = _callUiState.value
        current.remoteDeviceCode?.let { remote ->
            signalingManager.sendCallSignal(
                remote,
                JSONObject().apply {
                    put("type", "call_end")
                    put("callId", current.callId)
                    put("reason", "hangup")
                }
            )
        }
        finalizeCallLog(
            current.callId, current.remoteDeviceCode, current.peerDisplayName,
            callDirectionForCurrentCall, current.isVideo, callStartedAtMs
        )
        current.callId?.let { incomingCallNotificationHelper.cancelIncomingCallNotification(it) }
        teardownPeerConnection()
        _callUiState.value = CallUiState(state = CallState.IDLE)
        return PluginResult.Success(mapOf("state" to "ended"))
    }

    private suspend fun handleIncomingSignal(signal: JSONObject) {
        when (signal.optString("type")) {
            "call_offer" -> {
                incomingOfferCache = signal
                val fromCode = signal.optString("from")
                val callId = signal.optString("callId")
                val withVideo = signal.optBoolean("video", true)

                returnToIdleJob?.cancel()
                callDirectionForCurrentCall = CallDirection.INCOMING
                callStartedAtMs = System.currentTimeMillis()
                connectedAtMs = 0L
                // ✅ MỚI: tra danh bạ theo mã máy gọi tới — nếu đã lưu, CallScreen hiện
                // tên thay vì mã 8 ký tự thô.
                val peerName = resolvePeerDisplayName(fromCode)

                _callUiState.value = CallUiState(
                    state = CallState.RINGING,
                    remoteDeviceCode = fromCode,
                    callId = callId,
                    isVideo = withVideo,
                    peerDisplayName = peerName
                )

                // ✅ MỚI: đổ chuông + rung khi có cuộc gọi đến. Trước đây RINGING chỉ
                // đổi UI (hiện nút Nghe/Từ chối) hoàn toàn im lặng — người dùng không
                // có cách nào biết có cuộc gọi đến nếu không đang nhìn màn hình.
                startRingtoneAndVibration()

                // ✅ MỚI: notification full-screen (CallStyle) — đập màn hình sáng lên +
                // hiện trên lock screen dù app đang đóng hoàn toàn, kèm 2 nút Nghe/Từ chối
                // xử lý ngay không cần mở app. Bù cho khoảng trống trước đây: RINGING chỉ
                // có ý nghĩa nếu đang có Composable quan sát _callUiState (tức app phải
                // đang mở sẵn) — WebhookGatewayService là Foreground Service nên tín hiệu
                // signaling luôn tới được, nhưng không có gì hiển thị nếu app không foreground.
                incomingCallNotificationHelper.showIncomingCallNotification(
                    callId = callId,
                    callerLabel = peerName ?: fromCode,
                    isVideo = withVideo
                )

                logEvent("incoming_call", fromCode, "Cuộc gọi đến từ mã máy $fromCode")
            }
            "call_answer" -> {
                // ✅ MỚI: log lại — trước đây nhánh này hoàn toàn im lặng, không cách nào
                // biết máy gọi có THỰC SỰ nhận được answer từ máy nghe hay không khi debug.
                logger.i("CallSkill", "📩 Đã nhận call_answer từ ${signal.optString("from")}")
                setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, signal.getString("sdp")))
                flushPendingIceCandidates()
                // ✅ MỚI: đối phương đã bắt máy — dừng âm tút-tút ngay (peerConnection vẫn
                // tiếp tục dùng, không qua teardownPeerConnection() ở đây nên phải dừng
                // riêng tại đây).
                stopRingbackTone()
                _callUiState.value = _callUiState.value.copy(state = CallState.CONNECTING)
            }
            "ice_candidate" -> {
                val candidate = IceCandidate(
                    signal.optString("sdpMid"),
                    signal.optInt("sdpMLineIndex"),
                    signal.optString("candidate")
                )
                // ✅ MỚI: log lại — để phân biệt được "không nhận được ice_candidate nào từ
                // đối phương" (lỗi ở gateway/mạng) với "có nhận nhưng ICE vẫn không kết nối
                // được" (lỗi NAT/TURN thật sự). Kèm loại candidate (relay/srflx/host) của
                // ĐỐI PHƯƠNG — nếu đối phương không bao giờ gửi được loại "relay", nghĩa là
                // TURN phía họ không hoạt động (khác với việc TURN phía mình không hoạt động).
                val remoteCandidateType = Regex("typ (\\w+)").find(candidate.sdp)?.groupValues?.get(1) ?: "unknown"
                logger.i("CallSkill", "📩 Đã nhận ice_candidate [$remoteCandidateType] từ đối phương (remoteDescription=${peerConnection?.remoteDescription != null})")
                if (peerConnection?.remoteDescription == null) {
                    pendingRemoteIceCandidates.add(candidate)
                } else {
                    peerConnection?.addIceCandidate(candidate)
                }
            }
            "call_end" -> {
                val current = _callUiState.value
                finalizeCallLog(
                    current.callId, current.remoteDeviceCode, current.peerDisplayName,
                    callDirectionForCurrentCall, current.isVideo, callStartedAtMs
                )
                current.callId?.let { incomingCallNotificationHelper.cancelIncomingCallNotification(it) }
                teardownPeerConnection()
                _callUiState.value = CallUiState(state = CallState.ENDED)
                // ✅ SỬA LỖI: ENDED không có cơ chế nào tự chuyển về IDLE trước đây, nên
                // nút "Gọi" ở DialScreen (enabled chỉ khi state == IDLE) bị kẹt vô hiệu
                // vĩnh viễn sau khi đối phương cúp máy. Đợi ngắn để UI kịp hiện "Đã kết
                // thúc" rồi tự reset về IDLE.
                scheduleReturnToIdle()
            }
        }
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private suspend fun createPeerConnection(callId: String, remoteDeviceCode: String): PeerConnection? {
        val iceServers = fetchIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // ✅ MỚI: log loại candidate (host/srflx/relay) rút ra từ chuỗi SDP của
                // chính candidate đó — dùng để chẩn đoán TURN có thực sự được dùng hay
                // không, tách biệt với việc chỉ biết "đã lấy N iceServers từ Metered"
                // (lấy được server không có nghĩa là candidate loại relay thực sự được
                // gom — ví dụ domain/API key sai định dạng, TURN server từ chối cấp
                // phát relay, hoặc mạng chặn cổng UDP/TCP tới TURN thì relay sẽ không
                // bao giờ xuất hiện dù vẫn "lấy được 5 iceServers" thành công).
                val candidateType = Regex("typ (\\w+)").find(candidate.sdp)?.groupValues?.get(1) ?: "unknown"
                val icon = when (candidateType) {
                    "relay" -> "🟢 [TURN relay]"
                    "srflx" -> "🟡 [STUN srflx]"
                    "host" -> "⚪ [Host local]"
                    else -> "❔ [$candidateType]"
                }
                logger.i("CallSkill", "🧊 Candidate mới ($icon) → gửi tới deviceCode=$remoteDeviceCode")

                signalingManager.sendCallSignal(
                    remoteDeviceCode,
                    JSONObject().apply {
                        put("type", "ice_candidate")
                        put("callId", callId)
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                // ✅ MỚI: log lại MỌI thay đổi trạng thái ICE — trước đây hoàn toàn im lặng,
                // khiến không thể biết được cuộc gọi có thực sự đạt CONNECTED hay dừng ở
                // CHECKING/FAILED chỉ bằng cách đọc log (phải đoán qua UI, không chắc chắn).
                logger.i("CallSkill", "🧊 ICE state đổi: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        // ✅ MỚI: đánh dấu thời điểm CONNECTED đầu tiên — dùng để tính
                        // durationSec khi ghi CallLogEntity (chỉ tính reconnect lần đầu,
                        // các lần CONNECTED lặp lại sau reconnect tạm thời không reset lại mốc).
                        if (connectedAtMs == 0L) connectedAtMs = System.currentTimeMillis()
                        _callUiState.value = _callUiState.value.copy(state = CallState.CONNECTED)
                    }
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED -> {
                        val current = _callUiState.value
                        finalizeCallLog(
                            current.callId, current.remoteDeviceCode, current.peerDisplayName,
                            callDirectionForCurrentCall, current.isVideo, callStartedAtMs
                        )
                        current.callId?.let { incomingCallNotificationHelper.cancelIncomingCallNotification(it) }
                        _callUiState.value = _callUiState.value.copy(state = CallState.FAILED)
                        // ✅ SỬA LỖI: cùng lỗi kẹt state như "call_end" — FAILED trước đây
                        // không bao giờ tự thoát, khiến nút "Gọi" bị vô hiệu vĩnh viễn sau
                        // khi rớt kết nối P2P (mất mạng, NAT traversal thất bại...).
                        teardownPeerConnection()
                        scheduleReturnToIdle()
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        _callUiState.value = _callUiState.value.copy(state = CallState.ENDED)
                        scheduleReturnToIdle()
                    }
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                when (track) {
                    is VideoTrack -> _remoteVideoTrack.value = track
                    // ✅ SỬA LỖI: audio track của đối phương trước đây bị bỏ qua hoàn
                    // toàn trong onTrack (chỉ xử lý nhánh VideoTrack) — WebRTC Android
                    // không tự động phát audio ra loa nếu track không được set enabled
                    // rõ ràng phía nhận. Đây là lý do gọi kết nối được (nút hiện ra) mà
                    // hoàn toàn không có âm thanh, kể cả ở cuộc gọi chỉ-thoại.
                    is AudioTrack -> track.setEnabled(true)
                    else -> {}
                }
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        })
    }

    // ✅ MỚI: Lấy danh sách iceServers (STUN + TURN) động từ Metered.ca TURN REST API.
    // TURN là bắt buộc để cuộc gọi hoạt động ổn định qua NAT symmetric (4G/LTE, nhiều
    // router WiFi) — chỉ STUN thì signaling (offer/answer) vẫn trao đổi được (nút Nghe/
    // Từ chối hiện ra bình thường) nhưng audio/video có thể không bao giờ truyền được.
    //
    // Nếu người dùng chưa điền CALL_TURN_METERED_DOMAIN/API_KEY trong Settings, hoặc gọi
    // API thất bại (mất mạng, hết quota 20GB/tháng của Open Relay Project...), fallback về STUN công khai của
    // Google như hành vi cũ — để không phá cuộc gọi hoàn toàn, dù tỉ lệ thành công sẽ phụ
    // thuộc vào NAT của 2 mạng như trước khi có TURN.
    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> {
        val fallbackStun = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val domain = configProvider.getString(AppConfigDefaults.CALL_TURN_METERED_DOMAIN).trim()
        val apiKey = configProvider.getString(AppConfigDefaults.CALL_TURN_METERED_API_KEY).trim()

        // ✅ MỚI: nhãn cảnh báo dùng chung, in đậm và khác hẳn nhãn "✅ Đã lấy N
        // iceServers" — trước đây khi fallback về STUN-only, log ("⚠️ ... Dùng STUN dự
        // phòng.") rất dễ bị đọc lướt qua/nhầm với log thành công, khiến việc chẩn đoán
        // "TURN có thực sự hoạt động không" phải suy luận gián tiếp qua log candidate
        // relay/srflx lúc gọi thực tế thay vì biết ngay từ lúc fetch. Từ giờ mọi trường
        // hợp fallback đều bắn 1 dòng riêng, tách biệt, dễ lọc trong System Logs.
        fun logFallback(reason: String) {
            logger.w(
                "CallSkill",
                "🚨🚨🚨 CUỘC GỌI SẼ DÙNG STUN-ONLY, KHÔNG CÓ TURN 🚨🚨🚨 Lý do: $reason — " +
                    "cuộc gọi CHỈ kết nối được nếu mạng của cả 2 máy không phải Symmetric NAT " +
                    "(4G/LTE Việt Nam rất hay dính loại này). Kiểm tra lại Domain/API Key Metered trong Settings."
            )
        }

        if (domain.isBlank() || apiKey.isBlank()) {
            logFallback("Chưa cấu hình TURN Server (Metered) trong Settings")
            return fallbackStun
        }

        return try {
            withContext(Dispatchers.IO) {
                val url = "https://$domain/api/v1/turn/credentials?apiKey=$apiKey"
                val request = okhttp3.Request.Builder().url(url).get().build()

                turnApiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // ✅ MỚI: đọc kèm response body — Metered trả JSON message mô tả rõ
                        // lỗi (vd: {"message":"credential not found"} khi apiKey sai/domain
                        // sai, hoặc {"message":"Invalid request. Not subscribed to any turn
                        // server plan"} khi hết/chưa có gói TURN) — trước đây chỉ log mã HTTP
                        // suông (vd "HTTP: 401"), không đủ để phân biệt "sai key" với "hết gói"
                        // với "domain sai" nếu không tự tay gọi lại API để soi.
                        val errorBody = try { response.body?.string()?.take(300) } catch (e: Exception) { null }
                        val hint = when (response.code) {
                            401 -> "→ API Key SAI hoặc đã bị thu hồi/không khớp domain '$domain' — kiểm tra lại trong dashboard Metered."
                            400 -> "→ Domain '$domain' có thể chưa đăng ký gói TURN server nào (kể cả free), hoặc thiếu tham số."
                            500 -> "→ Lỗi phía server Metered, không phải lỗi cấu hình — thử lại sau."
                            else -> "→ Kiểm tra domain/API Key hoặc trạng thái gói TURN trên Metered."
                        }
                        logFallback("Gọi API Metered thất bại, HTTP ${response.code} ($errorBody) $hint")
                        return@withContext fallbackStun
                    }

                    val bodyStr = response.body?.string() ?: run {
                        logFallback("Metered trả về HTTP thành công nhưng body rỗng")
                        return@withContext fallbackStun
                    }
                    val arr = org.json.JSONArray(bodyStr)
                    val servers = mutableListOf<PeerConnection.IceServer>()
                    var turnUrlCount = 0

                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val urlsValue = item.opt("urls")
                        val urlList = when (urlsValue) {
                            is String -> listOf(urlsValue)
                            is org.json.JSONArray -> (0 until urlsValue.length()).map { urlsValue.getString(it) }
                            else -> emptyList()
                        }
                        if (urlList.isEmpty()) continue

                        turnUrlCount += urlList.count { it.startsWith("turn:") || it.startsWith("turns:") }

                        val username = item.optString("username", "")
                        val credential = item.optString("credential", "")

                        val builder = PeerConnection.IceServer.builder(urlList)
                        if (username.isNotBlank()) builder.setUsername(username)
                        if (credential.isNotBlank()) builder.setPassword(credential)
                        servers.add(builder.createIceServer())
                    }

                    when {
                        servers.isEmpty() -> {
                            logFallback("Metered trả về danh sách iceServers rỗng")
                            fallbackStun
                        }
                        // ✅ MỚI: Metered trả HTTP 200 + JSON hợp lệ KHÔNG đồng nghĩa với có
                        // TURN thật — nếu response chỉ toàn URL "stun:" (không có "turn:"/
                        // "turns:" nào), về bản chất vẫn là STUN-only dù code cũ từng log
                        // "✅ Đã lấy N iceServers (STUN+TURN)" gây hiểu lầm là có TURN.
                        turnUrlCount == 0 -> {
                            logFallback("Metered trả HTTP 200 nhưng KHÔNG có URL turn:/turns: nào trong response (chỉ có STUN) — kiểm tra gói TURN trên Metered có đang active không")
                            servers
                        }
                        else -> {
                            logger.i("CallSkill", "✅ Đã lấy ${servers.size} iceServers từ Metered, gồm $turnUrlCount URL turn:/turns: thật sự.")
                            servers
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logFallback("Exception khi gọi Metered: ${e.message}")
            fallbackStun
        }
    }

    private fun addLocalMediaTracks(withVideo: Boolean) {
        val factory = peerConnectionFactory ?: return

        // ✅ SỬA LỖI: đây là nguyên nhân chính của "kết nối được nhưng im lặng hoàn
        // toàn, kể cả gọi thoại". WebRTC tạo/nhận audio track không có nghĩa là hệ
        // điều hành Android tự route âm thanh ra loa — cần chủ động chuyển AudioManager
        // sang MODE_IN_COMMUNICATION (đúng ngữ cảnh gọi VoIP) và bật loa ngoài, nếu
        // không audio session có thể vẫn ở MODE_NORMAL và không phát ra được.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio_${UUID.randomUUID()}", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack)

        if (withVideo) {
            videoCapturer = createCameraCapturer()
            val videoSource = factory.createVideoSource(false)
            val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoCapturer?.initialize(surfaceHelper, context, videoSource.capturerObserver)
            videoCapturer?.startCapture(640, 480, 30)

            val vTrack = factory.createVideoTrack("video_${UUID.randomUUID()}", videoSource)
            _localVideoTrack.value = vTrack
            peerConnection?.addTrack(vTrack)
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return enumerator.createCapturer(deviceName, null)
    }

    private suspend fun createLocalOffer(): SessionDescription? = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: return@suspendCancellableCoroutine cont.resume(null)
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                cont.resume(sdp)
            }
            override fun onCreateFailure(error: String) {
                cont.resumeWithException(IllegalStateException("createOffer thất bại: $error"))
            }
        }, MediaConstraints())
    }

    private suspend fun createLocalAnswer(): SessionDescription? = suspendCancellableCoroutine { cont ->
        val pc = peerConnection ?: return@suspendCancellableCoroutine cont.resume(null)
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                cont.resume(sdp)
            }
            override fun onCreateFailure(error: String) {
                cont.resumeWithException(IllegalStateException("createAnswer thất bại: $error"))
            }
        }, MediaConstraints())
    }

    private fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    private fun flushPendingIceCandidates() {
        pendingRemoteIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingRemoteIceCandidates.clear()
    }

    private fun teardownPeerConnection() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        peerConnection?.close()
        peerConnection = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        pendingRemoteIceCandidates.clear()
        incomingOfferCache = null
        stopRingtoneAndVibration()
        // ✅ MỚI: dừng âm ringback (nếu đang DIALING) ở đây — teardownPeerConnection()
        // là điểm dọn dẹp chung cho MỌI đường thoát khỏi cuộc gọi (call_answer nhận
        // được, call_end, FAILED, reject, ICE closed), nên đặt ở đây đảm bảo không bao
        // giờ sót trường hợp nào khiến âm tút-tút kêu mãi không dừng.
        stopRingbackTone()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    // ✅ MỚI: phát chuông (âm mặc định của hệ thống cho cuộc gọi đến) + rung liên tục
    // theo mẫu (không dùng vibrate() 1 lần vì sẽ tắt ngay). Idempotent — gọi nhiều lần
    // liên tiếp (ví dụ nhận trùng 1 signal do mạng lặp) không tạo nhiều Ringtone chồng.
    private fun startRingtoneAndVibration() {
        stopRingtoneAndVibration()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "Không phát được chuông cuộc gọi đến: ${e.message}")
        }

        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0, 800, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "Không rung được cho cuộc gọi đến: ${e.message}")
        }
    }

    private fun stopRingtoneAndVibration() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // bỏ qua — chuông có thể đã tự dừng
        }
        ringtone = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // bỏ qua
        }
        vibrator = null
    }

    // ✅ MỚI: âm "tút... tút..." nghe ở máy GỌI ĐI trong lúc chờ đối phương bắt máy
    // (DIALING) — dùng TONE_SUP_RINGTONE chuẩn Android (âm báo hiệu cuộc gọi đang đổ
    // chuông, khác hẳn TONE_CDMA/DTMF). startTone() với duration=-1 phát lặp liên tục
    // cho tới khi release() — không cần tự vòng lặp delay/replay thủ công.
    private fun startRingbackTone() {
        stopRingbackTone()
        try {
            ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
            ringbackToneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, -1)
        } catch (e: Exception) {
            logger.e("CallSkill", "Không phát được âm ringback (đang gọi đi): ${e.message}")
        }
    }

    private fun stopRingbackTone() {
        try {
            ringbackToneGenerator?.stopTone()
            ringbackToneGenerator?.release()
        } catch (e: Exception) {
            // bỏ qua — tone có thể đã tự dừng
        }
        ringbackToneGenerator = null
    }

    private fun logEvent(eventType: String, sourceId: String, summary: String) {
        skillScope.launch(Dispatchers.IO) {
            try {
                database.eventLogDao().insertLog(
                    EventLogEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "call",
                        sourceId = sourceId,
                        eventType = eventType,
                        value = _callUiState.value.state.name,
                        summary = summary
                    )
                )
            } catch (e: Exception) {
                logger.e("CallSkill", "Không thể ghi EventLog cuộc gọi: ${e.message}")
            }
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}