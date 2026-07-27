package com.aichatvn.agent.skills

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
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

    // ✅ MỚI: client HTTP riêng cho việc lấy iceServers động từ Metered TURN REST API —
    // tách khỏi httpClient của GatewaySignalingManager để không phụ thuộc lẫn nhau.
    private val turnApiClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ✅ MỚI: chuông + rung khi có cuộc gọi đến (RINGING)
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()
    private var incomingOfferCache: JSONObject? = null

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
                else -> PluginResult.Failure("Hành động không hỗ trợ: $action")
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "Lỗi thực thi $action: ${e.message}", e)
            PluginResult.Failure("Lỗi cuộc gọi: ${e.message}")
        }
    }

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

        returnToIdleJob?.cancel()

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

        returnToIdleJob?.cancel()
        stopRingtoneAndVibration()

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

                returnToIdleJob?.cancel()
                _callUiState.value = CallUiState(
                    state = CallState.RINGING,
                    remoteDeviceCode = fromCode,
                    callId = callId,
                    isVideo = withVideo
                )

                // ✅ MỚI: đổ chuông + rung khi có cuộc gọi đến. Trước đây RINGING chỉ
                // đổi UI (hiện nút Nghe/Từ chối) hoàn toàn im lặng — người dùng không
                // có cách nào biết có cuộc gọi đến nếu không đang nhìn màn hình.
                startRingtoneAndVibration()

                logEvent("incoming_call", fromCode, "Cuộc gọi đến từ mã máy $fromCode")
            }
            "call_answer" -> {
                // ✅ MỚI: log lại — trước đây nhánh này hoàn toàn im lặng, không cách nào
                // biết máy gọi có THỰC SỰ nhận được answer từ máy nghe hay không khi debug.
                logger.i("CallSkill", "📩 Đã nhận call_answer từ ${signal.optString("from")}")
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
                // ✅ MỚI: log lại — để phân biệt được "không nhận được ice_candidate nào từ
                // đối phương" (lỗi ở gateway/mạng) với "có nhận nhưng ICE vẫn không kết nối
                // được" (lỗi NAT/TURN thật sự).
                logger.i("CallSkill", "📩 Đã nhận ice_candidate từ đối phương (remoteDescription=${peerConnection?.remoteDescription != null})")
                if (peerConnection?.remoteDescription == null) {
                    pendingRemoteIceCandidates.add(candidate)
                } else {
                    peerConnection?.addIceCandidate(candidate)
                }
            }
            "call_end" -> {
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
                    PeerConnection.IceConnectionState.CONNECTED ->
                        _callUiState.value = _callUiState.value.copy(state = CallState.CONNECTED)
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.DISCONNECTED -> {
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

        if (domain.isBlank() || apiKey.isBlank()) {
            logger.w("CallSkill", "⚠️ Chưa cấu hình TURN Server (Metered) trong Settings — chỉ dùng STUN công khai, cuộc gọi có thể lúc được lúc không tùy mạng.")
            return fallbackStun
        }

        return try {
            withContext(Dispatchers.IO) {
                val url = "https://$domain/api/v1/turn/credentials?apiKey=$apiKey"
                val request = okhttp3.Request.Builder().url(url).get().build()

                turnApiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("CallSkill", "⚠️ Lấy iceServers từ Metered thất bại, HTTP: ${response.code}. Dùng STUN dự phòng.")
                        return@withContext fallbackStun
                    }

                    val bodyStr = response.body?.string() ?: return@withContext fallbackStun
                    val arr = org.json.JSONArray(bodyStr)
                    val servers = mutableListOf<PeerConnection.IceServer>()

                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val urlsValue = item.opt("urls")
                        val urlList = when (urlsValue) {
                            is String -> listOf(urlsValue)
                            is org.json.JSONArray -> (0 until urlsValue.length()).map { urlsValue.getString(it) }
                            else -> emptyList()
                        }
                        if (urlList.isEmpty()) continue

                        val username = item.optString("username", "")
                        val credential = item.optString("credential", "")

                        val builder = PeerConnection.IceServer.builder(urlList)
                        if (username.isNotBlank()) builder.setUsername(username)
                        if (credential.isNotBlank()) builder.setPassword(credential)
                        servers.add(builder.createIceServer())
                    }

                    if (servers.isEmpty()) {
                        logger.w("CallSkill", "⚠️ Metered trả về danh sách iceServers rỗng. Dùng STUN dự phòng.")
                        fallbackStun
                    } else {
                        logger.i("CallSkill", "✅ Đã lấy ${servers.size} iceServers (STUN+TURN) từ Metered.")
                        servers
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("CallSkill", "❌ Lỗi khi lấy iceServers từ Metered: ${e.message}. Dùng STUN dự phòng.")
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
