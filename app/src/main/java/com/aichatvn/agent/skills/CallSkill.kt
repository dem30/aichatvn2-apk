package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val errorMessage: String? = null
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
 */
@Singleton
class CallSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase,
    private val signalingManager: GatewaySignalingManager,
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

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var incomingOfferCache: JSONObject? = null

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
                else -> PluginResult.Failure("Hành động không hỗ trợ: $action")
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "Lỗi thực thi $action: ${e.message}", e)
            PluginResult.Failure("Lỗi cuộc gọi: ${e.message}")
        }
    }

    suspend fun getOrCreateMyDeviceCode(): String {
        var existing = configProvider.getString(AppConfigDefaults.CALL_DEVICE_CODE).trim()
        if (existing.isNotBlank()) return existing
        existing = UUID.randomUUID().toString().take(8).uppercase()
        configProvider.set(AppConfigDefaults.CALL_DEVICE_CODE, existing)
        return existing
    }

    private suspend fun startCall(targetDeviceCode: String, withVideo: Boolean): PluginResult {
        if (_callUiState.value.state != CallState.IDLE) {
            return PluginResult.Failure("Đang trong cuộc gọi khác")
        }
        val callId = UUID.randomUUID().toString()
        val myCode = getOrCreateMyDeviceCode()

        _callUiState.value = CallUiState(
            state = CallState.DIALING,
            remoteDeviceCode = targetDeviceCode,
            callId = callId,
            isVideo = withVideo
        )

        peerConnection = createPeerConnection(callId, targetDeviceCode)
        addLocalMediaTracks(withVideo)

        val offer = createLocalOffer() ?: run {
            _callUiState.value = CallUiState(state = CallState.FAILED, errorMessage = "Không tạo được SDP offer")
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

        logEvent("outgoing_call", targetDeviceCode, "Cuộc gọi đi tới mã máy $targetDeviceCode")
        return PluginResult.Success(mapOf("callId" to callId, "state" to "dialing"))
    }

    private suspend fun answerCall(callId: String): PluginResult {
        val offerSignal = incomingOfferCache?.takeIf { it.optString("callId") == callId }
            ?: return PluginResult.Failure("Không tìm thấy cuộc gọi $callId")

        val fromCode = offerSignal.getString("from")
        val withVideo = offerSignal.optBoolean("video", true)

        _callUiState.value = CallUiState(
            state = CallState.CONNECTING,
            remoteDeviceCode = fromCode,
            callId = callId,
            isVideo = withVideo
        )

        peerConnection = createPeerConnection(callId, fromCode)
        addLocalMediaTracks(withVideo)

        setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSignal.getString("sdp")))
        flushPendingIceCandidates()

        val answer = createLocalAnswer() ?: run {
            _callUiState.value = CallUiState(state = CallState.FAILED, errorMessage = "Không tạo được SDP answer")
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
        if (fromCode != null) {
            signalingManager.sendCallSignal(
                fromCode,
                JSONObject().apply {
                    put("type", "call_end")
                    put("callId", callId)
                    put("reason", "rejected")
                }
            )
            logEvent("call_rejected", fromCode, "Đã từ chối cuộc gọi từ mã máy $fromCode")
        }
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
            logEvent("call_ended", remote, "Đã kết thúc cuộc gọi với $remote")
        }
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

                _callUiState.value = CallUiState(
                    state = CallState.RINGING,
                    remoteDeviceCode = fromCode,
                    callId = callId,
                    isVideo = withVideo
                )

                logEvent("incoming_call", fromCode, "Cuộc gọi đến từ mã máy $fromCode")
            }
            "call_answer" -> {
                setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, signal.getString("sdp")))
                flushPendingIceCandidates()
                _callUiState.value = _callUiState.value.copy(state = CallState.CONNECTING)
            }
            "ice_candidate" -> {
                val candidate = IceCandidate(
                    signal.optString("sdpMid"),
                    signal.optInt("sdpMLineIndex"),
                    signal.optString("candidate")
                )
                if (peerConnection?.remoteDescription == null) {
                    pendingRemoteIceCandidates.add(candidate)
                } else {
                    peerConnection?.addIceCandidate(candidate)
                }
            }
            "call_end" -> {
                teardownPeerConnection()
                _callUiState.value = CallUiState(state = CallState.ENDED)
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

    private fun createPeerConnection(callId: String, remoteDeviceCode: String): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
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
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED ->
                        _callUiState.value = _callUiState.value.copy(state = CallState.CONNECTED)
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED ->
                        _callUiState.value = _callUiState.value.copy(state = CallState.FAILED)
                    PeerConnection.IceConnectionState.CLOSED ->
                        _callUiState.value = _callUiState.value.copy(state = CallState.ENDED)
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
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

    private fun addLocalMediaTracks(withVideo: Boolean) {
        val factory = peerConnectionFactory ?: return
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio_${UUID.randomUUID()}", audioSource)
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
