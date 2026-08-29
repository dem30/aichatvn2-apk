package com.aichatvn.agent.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ==================== TUYA DEVICE ====================

@Entity(tableName = "tuya_devices")
data class TuyaDeviceEntity(
    @PrimaryKey
    val id: String,              // Device ID từ Tuya
    val name: String,            // Tên thiết bị (user đặt trong Tuya App)
    val online: Boolean = false, // Trạng thái online
    val category: String = "",   // Loại thiết bị (socket, light, switch...)
    val productName: String = "",// Tên sản phẩm
    val lastSeen: Long = System.currentTimeMillis(),
    // ✅ MỚI: điều khiển LOCAL qua LAN, không qua Tuya Cloud mỗi lần bật/tắt — xem
    // TuyaLocalController.kt. localKey lấy 1 LẦN DUY NHẤT qua API cloud
    // (TuyaManager.fetchLocalKey(), dùng chính client_id/client_secret người dùng đã
    // nhập), sau đó lưu lại đây, không cần gọi cloud nữa cho việc điều khiển.
    //
    // lastKnownIp là IP LAN gần nhất dò được qua UDP broadcast
    // (TuyaLocalController.discoverIp()) — CHỈ đáng tin trong phiên hiện tại, DHCP có
    // thể đổi IP bất kỳ lúc nào, nên mọi lần điều khiển local đều cần fallback dò lại
    // nếu IP cũ không phản hồi, KHÔNG coi đây là nguồn sự thật cố định.
    //
    // protocolVersion: "3.3" hoặc "3.4" — quyết định cách mã hoá gói tin (xem
    // TuyaLocalController.kt). null = chưa xác định, thử "3.3" trước theo mặc định.
    @ColumnInfo(defaultValue = "NULL")
    val localKey: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val lastKnownIp: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val protocolVersion: String? = null,
    // ✅ MỚI: DP id THẬT (số, vd "1") của lệnh bật/tắt chính — khác với switchCodeCache
    // trong TuyaManager (lưu CODE dạng tên như "switch_1" dùng cho Cloud API). Giao thức
    // LOCAL cần đúng số DP id, không phải code — xem TuyaManager.resolveLocalDpId().
    @ColumnInfo(defaultValue = "NULL")
    val localSwitchDpId: String? = null,

    // ✅ MỚI (Nhất quán Dashboard): trước đây getDashboardNodes() hardcode room="Phòng chung"
    // cho MỌI thiết bị Tuya (xem SmartSwitchSkill.kt cũ) — giờ đọc thật từ đây. null = chưa
    // đặt tên phòng, UI (DashboardScreen) tự hiển thị "Chưa phân loại" khi gặp null, KHÔNG
    // default cứng ở tầng Entity để phân biệt được "chưa đặt" với "đặt tên là Phòng chung".
    @ColumnInfo(defaultValue = "NULL")
    val room: String? = null,

    // ✅ MỚI (Dimmer): DP id THẬT của lệnh chỉnh mức độ (vd "3" cho bright_value), tách riêng
    // khỏi localSwitchDpId — cùng 1 thiết bị có thể vừa có DP switch (bật/tắt) vừa có DP
    // dimmer (mức độ), không loại trừ nhau. null = thiết bị không phải loại dimmer, hoặc
    // chưa dò được qua Cloud (xem TuyaManager.resolveDimmerCode()).
    @ColumnInfo(defaultValue = "NULL")
    val localDimmerDpId: String? = null,

    // ✅ MỚI (Dimmer): đánh dấu thiết bị này có hỗ trợ điều chỉnh mức độ hay không — dùng để
    // DeviceController.capabilities trả về DIMMABLE mà KHÔNG phải gọi Cloud API dò lại mỗi
    // lần app khởi động. Được set true lần đầu khi resolveDimmerCode() thành công.
    val isDimmable: Boolean = false
)

// ==================== MQTT DEVICE ====================

// ✅ MỚI (Tầng 3 — bài kiểm tra kiến trúc DeviceController): bảng riêng cho thiết bị MQTT,
// KHÔNG dùng chung tuya_devices — đúng khuyến nghị Bước 2 trong SHAS_INTEGRATION_GUIDE.md.
@Entity(tableName = "mqtt_devices")
data class MqttDeviceEntity(
    @PrimaryKey
    val id: String,          // Định danh nội bộ ổn định (KHÔNG phải topic — topic có thể đổi)
    val name: String,        // Tên hiển thị người dùng đặt
    val topic: String,       // ⚠️ GIỮ NGUYÊN cho tương thích ngược (thiết bị thêm qua bản thử
    // nghiệm cũ, trước khi có commandTopic riêng). Bản mới KHÔNG ghi cột này nữa — dùng
    // commandTopic thay thế. Xem MIGRATION_23_24: dữ liệu cũ tự copy topic -> commandTopic.
    val online: Boolean = false,
    // ✅ Trạng thái BẬT/TẮT cuối cùng nhận được qua subscribe — MQTT vốn mạnh ở nhận đẩy
    // realtime (DeviceCapability.REALTIME_STATUS), khác Tuya phải chủ động poll/gọi API.
    // getStatus() đọc thẳng cột này, KHÔNG có khái niệm "gọi API hỏi trạng thái" như Tuya.
    val lastKnownState: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),

    // ✅ MỚI (track Cloud Broker V1, xem MQTT_CLOUD_BROKER_PLAN.md mục 0.4b + mục 3):
    // Chỉ giá trị hợp lệ ở V1 là "cloud". Cột có sẵn từ bây giờ để V2 (Embedded Broker trên
    // Camera Node, đang HOÃN) không cần viết thêm 1 migration DB riêng khi thêm giá trị
    // "embedded_camera_node" — chỉ cần đổi logic đọc cột này, không đổi schema.
    val brokerSource: String = "cloud",

    // "home_assistant" | "generic" | "manual" — biết thiết bị được phát hiện bằng cách nào,
    // hữu ích khi debug hoặc hiển thị nhãn nguồn gốc trong UI sau này.
    val discoverySource: String = "manual",

    // Command Topic thật để publish lệnh — thay thế `topic` cũ. Bắt buộc phải có giá trị (không
    // nullable) vì mọi thiết bị đều cần biết publish lệnh vào đâu, dù qua discovery hay nhập tay.
    val commandTopic: String = "",

    // State Topic — CÓ THỂ null nếu thiết bị chỉ nhận lệnh, không tự báo trạng thái ngược (vd
    // qua Generic Fallback chỉ bắt được 1 chiều). null nghĩa là app tiếp tục dựa vào
    // lastKnownState tự cập nhật sau publish (như hành vi hiện tại), không subscribe gì thêm.
    val stateTopic: String? = null,

    val onPayload: String = "ON",
    val offPayload: String = "OFF",

    // ✅ MỚI (version 25 — MIGRATION_24_25, xem MQTT_SYMMETRIC_BROKER_PLAN.md mục 4.2):
    // IP LAN CỦA CHÍNH TASMOTA (không phải broker) — cần để pushMqttHostToTasmota() biết
    // gọi HTTP command tới đâu. Chỉ có giá trị nếu TasmotaLanDiscovery từng dò được, hoặc
    // user tự nhập. null nghĩa là chưa biết — TasmotaMqttHostMigrator BỎ QUA thiết bị này
    // (không silently fail), theo đúng phương án (c) mục 5.3.
    @ColumnInfo(defaultValue = "NULL")
    val deviceLanIp: String? = null,
    // Xác thực lại thiết bị khi IP đổi (tái dùng ý mục 4.2 file kế hoạch cũ).
    @ColumnInfo(defaultValue = "NULL")
    val deviceMac: String? = null,
    // "camera_node_host" | "adhoc" | "cloud" — dùng cho UI hiển thị + logic migrate biết
    // thiết bị nào đang trỏ vào broker nào. Default 'cloud' khớp đúng brokerSource mặc định
    // đã có từ MIGRATION_23_24, không tạo trạng thái mới mâu thuẫn cho dữ liệu cũ.
    @ColumnInfo(defaultValue = "cloud")
    val brokerMode: String = "cloud",

    // ✅ MỚI (Dimmer): topic riêng để publish mức độ 0-100% — KHÁC commandTopic (vốn chỉ
    // nhận onPayload/offPayload nhị phân). null = thiết bị này không phải loại dimmer, hoặc
    // chưa cấu hình topic dimmer (MqttDeviceController.setLevel() trả Failure khi null).
    @ColumnInfo(defaultValue = "NULL")
    val levelCommandTopic: String? = null,

    // ✅ MỚI (Dimmer): mức độ cuối cùng nhận được qua subscribe (nếu levelStateTopic có cấu
    // hình) — song song với lastKnownState (boolean) đã có, không thay thế. null = chưa từng
    // nhận được giá trị nào, hoặc thiết bị không report mức độ ngược (chỉ nhận lệnh 1 chiều).
    @ColumnInfo(defaultValue = "NULL")
    val lastKnownLevel: Int? = null,

    // ✅ MỚI (Dimmer): topic subscribe để nhận mức độ realtime — null nghĩa là app chỉ dựa
    // vào lastKnownLevel tự cập nhật ngay sau khi publish (giống cách lastKnownState hoạt
    // động khi stateTopic null), không subscribe thêm.
    @ColumnInfo(defaultValue = "NULL")
    val levelStateTopic: String? = null,

    // ✅ MỚI (Dimmer): đánh dấu thiết bị này có hỗ trợ điều chỉnh mức độ hay không — cùng vai
    // trò với isDimmable của TuyaDeviceEntity, dùng cho DeviceController.capabilities.
    val isDimmable: Boolean = false,

    // ✅ MỚI (Nhất quán Dashboard): cùng vai trò với room của TuyaDeviceEntity — null = chưa
    // đặt tên phòng. Thêm cột riêng ở đây (không dùng chung bảng) vì mqtt_devices vốn đã
    // tách bảng riêng khỏi tuya_devices (xem comment "Tầng 3" đầu MqttDeviceEntity).
    @ColumnInfo(defaultValue = "NULL")
    val room: String? = null
)

// ==================== CHAT MESSAGE ====================

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionToken: String,
    val username: String,
    val content: String,
    val role: String, // "user" or "assistant"
    val type: String, // "text" or "image" or "file"
    val fileUrl: String? = null,
    val timestamp: Long,
    // Nguồn gốc câu trả lời của Assistant (human, learn, camera, v.v.)
    val sourcePlugin: String? = null,
    // Trạng thái đã đọc — CHỈ có ý nghĩa với tin nhắn role="user" của khách ngoại kênh
    val isRead: Boolean = true,
    // ✅ MỚI (nút bấm chọn nhanh trong chat, xem AgentKernel.ChatResponse.quickReplies): mảng
    // JSON dạng [[label,value],[label,value],...] — CHỈ có ý nghĩa với tin nhắn role="assistant"
    // đang hỏi PluginResult.NeedMoreInfo có displayOptions (device/camera/plugin_id/action_id/
    // boolean...). null với MỌI tin nhắn khác (user, hoặc assistant trả lời bình thường không cần
    // chọn). Lưu chuỗi JSON thô thay vì kiểu có cấu trúc vì Room cần TypeConverter riêng cho
    // List<Pair<String,String>> — JSON string đơn giản hơn, ChatScreen.kt tự parse khi vẽ nút.
    @ColumnInfo(defaultValue = "NULL")
    val quickRepliesJson: String? = null
)

// ==================== Q&A ====================

@Entity(tableName = "qa_data")
data class QAEntity(
    @PrimaryKey
    val id: String,
    val question: String,
    val answer: String,
    val category: String,
    val type: String = "alias",
    val createdBy: String,
    val createdAt: Long,
    val timestamp: Long
)

// ==================== CUSTOMER ====================

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey
    val id: String,          // = customerId, khớp with CameraConfigEntity.customerId
    val name: String,
    val email: String,
    val address: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ==================== CAMERA ====================

@Entity(tableName = "cameras")
data class CameraConfigEntity(
    @PrimaryKey
    val id: String,
    val customerId: String,
    val customername: String,
    val customeremail: String,
    val snapshoturl: String,
    val landinfo: String? = null,
    val snapshotPath: String? = null,
    val timestamp: Long,
    val status: String = "online",
    val isOnline: Int = 1,
    val manualOff: Int = 0,
    val smartMode: Int = 1,        // per-camera AI flag; master = CustomerSettingEntity.smartMode
    val aiPrompt: String = "",
    val aiPositiveKeywords: String = "",
    val aiNegativeKeywords: String = "",
    val enableCooldown: Int = 1,       // 1 = Bật, 0 = Tắt hoãn kiểm tra AI
    val enableNotification: Int = 1,    // 1 = Bật, 0 = Tắt gửi Email/Push
    val alertActions: String = "[]",   // JSON array [{pluginId, action, params}] chạy khi isSuspicious=true
    // ✅ MỚI: Basic Auth cho camera LAN yêu cầu đăng nhập (vd http://192.168.1.50/snap.jpg cần
    // user/pass riêng, không nhúng trong URL). Optional — camera không cần auth để trống là được,
    // SnapshotFetcher.fetchSnapshot() chỉ thêm Authorization header khi username không rỗng.
    val snapshotUsername: String? = null,
    val snapshotPassword: String? = null,
    // ✅ MỚI: URL dự phòng (cloud/public URL từ app gốc của hãng camera) — dùng khi snapshoturl
    // (LAN nội bộ) không kết nối được, vd khi điện thoại đang ở ngoài mạng nhà. CameraSkill sẽ
    // thử snapshoturl (LAN) TRƯỚC, chỉ fallback qua field này nếu LAN fail — giữ nguyên lợi ích
    // offline (nhanh, không tốn data) khi đang ở nhà, đồng thời không mất khả năng xem từ xa.
    val snapshotUrlRemote: String? = null,
    // ✅ SỬA: Kotlin default value (= 0 / = null) KHÔNG được Room dịch thành SQL DEFAULT khi
    // sinh CREATE TABLE — nó chỉ áp dụng khi gọi constructor Kotlin trực tiếp. Không có
    // @ColumnInfo(defaultValue=...), cột enableAlarmPush được tạo NOT NULL nhưng KHÔNG có
    // DEFAULT ở tầng SQL. Hệ quả: BackupRestorer.kt insert bằng ContentValues (raw SQL, không
    // qua constructor) — nếu bản backup/seed cũ (trước khi thêm cột này) thiếu key
    // "enableAlarmPush", cột bị bỏ trống khỏi ContentValues → SQLite ném "NOT NULL constraint
    // failed", rollback toàn bộ transaction import/seed, coi như "chạy nhưng không có gì được
    // thêm vào". Thêm @ColumnInfo(defaultValue=...) để default cũng tồn tại ở tầng SQL, áp
    // dụng cho MỌI đường insert (kể cả ContentValues thô), không chỉ qua constructor Kotlin.
    @ColumnInfo(defaultValue = "0")
    val enableAlarmPush: Int = 0,
    @ColumnInfo(defaultValue = "NULL")
    val alarmSecret: String? = null,
    // ✅ MỚI: kết quả CameraCapabilityProber.probe() — CHỈ ghi nhận "có vẻ hỗ trợ", KHÔNG tự
    // bật tính năng nào. onvifEventUrl/rtspUrl là URL/endpoint THẬT probe tìm được, dùng trực
    // tiếp bởi open_live_view/record (rtspUrl) một khi người dùng đã tự bật rtspEnabled qua Switch
    // riêng — xem ghi chú "CHỦ Ý" trong CameraCapabilityProber.kt. Cùng lý do enableAlarmPush ở
    // trên, khai báo @ColumnInfo(defaultValue=...) tường minh để default cũng áp dụng ở tầng SQL
    // (ContentValues insert thô của BackupRestorer.kt), không chỉ qua constructor Kotlin.
    @ColumnInfo(defaultValue = "0")
    val onvifSupported: Int = 0,
    @ColumnInfo(defaultValue = "NULL")
    val onvifEventUrl: String? = null,
    // ✅ MỚI: XAddr (device service URL) mà OnvifWsDiscoveryProbe tìm được lúc dò tìm LAN, vd
    // "http://192.168.0.104:8899/onvif/device_service". Trước đây giá trị này chỉ được ghi vào
    // landInfo dạng text tự do rồi mất — probeCapabilities() phải tự suy port từ snapshoturl,
    // nên không bao giờ dùng lại được XAddr thật đã dò được. Lưu riêng field này để
    // CameraDetailViewModel.probeCapabilities() truyền thẳng vào
    // CameraCapabilityProber.probe(knownOnvifDeviceUrl = ...) thay vì suy đoán port 80 mặc định.
    @ColumnInfo(defaultValue = "NULL")
    val onvifDeviceServiceUrl: String? = null,
    @ColumnInfo(defaultValue = "0")
    val onvifEnabled: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val rtspSupported: Int = 0,
    // ⚠️ Nếu camera cần auth, URL này PHẢI đã có sẵn "rtsp://user:pass@ip:port/path" (nhúng
    // credential ngay trong URL) — xem CameraCapabilityProber.tryProbeRtsp() đã sửa để nhúng
    // sẵn, vì ExoPlayer RtspMediaSource/ffmpeg-kit không có tham số username/password riêng,
    // chỉ đọc credential từ chính URI.
    @ColumnInfo(defaultValue = "NULL")
    val rtspUrl: String? = null,
    @ColumnInfo(defaultValue = "0")
    val rtspEnabled: Int = 0,
    // ✅ MỚI: kết quả health-check định kỳ cho snapshotUrlRemote — CloudSnapshotVerifier gọi
    // fetchOneSnapshot(snapshotUrlRemote) trực tiếp (bỏ qua LAN) theo chu kỳ riêng, ghi lại ở
    // đây để SystemAuditor cảnh báo khi URL cloud hỏng thay vì chỉ phát hiện lúc client ở xa
    // thực sự cần dùng (quá trễ). snapshotUrlRemoteHealthy dùng Int? thay vì Boolean? vì Room
    // map Boolean? sang INTEGER cùng cách nhưng Int? tường minh 3 trạng thái hơn: null = chưa
    // từng kiểm tra (camera mới/chưa cấu hình snapshotUrlRemote), 1 = OK, 0 = fail.
    @ColumnInfo(defaultValue = "NULL")
    val snapshotUrlRemoteLastVerifiedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val snapshotUrlRemoteHealthy: Int? = null,
    // ✅ MỚI: DDNS hostname (+ port forward nếu khác port LAN) người dùng tự khai — dùng để GỢI
    // Ý (không tự động lưu) 1 URL snapshotUrlRemote ứng viên bằng cách thay host:port của
    // snapshoturl (LAN, đã verify) sang ddnsHost:ddnsPort, giữ nguyên path/query. Để trống nếu
    // router nhà không có port-forward/DDNS — tính năng gợi ý chỉ ẩn đi, không ảnh hưởng gì
    // khác.
    @ColumnInfo(defaultValue = "NULL")
    val ddnsHost: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val ddnsPort: Int? = null,
    // ✅ MỚI: OnvifEventRelay ghi lại quan sát thật khi PullPoint đã nhận đủ số event mẫu
    // (xem OnvifEventRelay.MOTION_CAPABILITY_SAMPLE_SIZE) nhưng KHÔNG event nào là chuyển
    // động (topic không khớp CellMotionDetector/Motion) — một số camera "tương thích ONVIF"
    // (vd nhiều dòng V380 Pro) chỉ publish topic Imaging (ImageTooBlurry/ImageTooDark), Motion
    // thật xử lý qua app/cloud riêng của hãng, không đi qua ONVIF Events. 3 trạng thái: null =
    // chưa đủ mẫu để kết luận (camera mới bật ONVIF, hoặc đang chờ đủ event), 1 = đã thấy
    // Motion event thật ít nhất 1 lần, 0 = đã quan sát đủ mẫu mà không hề thấy Motion event
    // nào — SystemAuditor dùng giá trị 0 để báo CONFLICT, gợi ý người dùng tự tắt ONVIF Switch
    // ở CameraDetailScreen thay vì để PullPoint loop chạy nền vô ích tốn pin/data.
    @ColumnInfo(defaultValue = "NULL")
    val onvifMotionObserved: Int? = null
)

@Entity(tableName = "customer_settings")
data class CustomerSettingEntity(
    @PrimaryKey
    val customerId: String,
    val smartMode: Int = 0,
    val isActive: Int = 1,
    val updatedAt: Long,
    val timestamp: Long,
    val lastFacebookPageId: String? = null
)

// ==================== ALERT ====================

@Entity(
    tableName = "alerts",
    indices = [Index(value = ["cameraId"]), Index(value = ["timestamp"])]
)
data class AlertEntity(
    @PrimaryKey
    val id: String,
    val cameraId: String,
    val customerId: String,
    val cameraName: String,
    val timestamp: Long,
    val aiComment: String,
    val diff: Int,
    // ✅ MỚI: giá trị delta THẬT đã đo (kotlin.math.abs(currentDiff - lastDiff)) tại thời điểm
    // báo động — khác với deltaTrigger (ngưỡng cấu hình). Cần để markFalsePositiveAndLearn học
    // đúng trên nhiễu quan sát được thay vì học nhầm trên ngưỡng cũ.
    val delta: Int = 0,
    val deltaTrigger: Int,
    val absDiffTrigger: Int,
    // ✅ MỚI: giá trị drift THẬT (|currentDiff - baselineDiff|) và baselineDiff (trung bình
    // baselineWindow) tại thời điểm báo động, cùng ngưỡng driftTrigger đã áp dụng. Cần để
    // UI (AlertHistoryScreen, CameraDetailScreen) hiển thị chính xác nguyên nhân kích hoạt
    // thay vì suy luận loại trừ khi cả diff và delta đều chưa vượt ngưỡng.
    val drift: Int = 0,
    val baselineDiff: Int = 0,
    val driftTrigger: Int = 12,
    val imagePath: String? = null,
    val emailSent: Int = 0,
    val isSuspicious: Int = 1,
    val isRead: Int = 0,
    val scheduleId: String? = null,
    val scheduleLabel: String? = null,
    
    // ✅ MỚI (Tuần 2 & 3 - Phase 3): Thời điểm kết thúc sự kiện nén kéo dài.
    // null nghĩa là sự kiện tức thời hoặc không áp dụng nén.
    val endTime: Long? = null,
    
    // ✅ MỚI (Tuần 1 & 2 - Phase 1): Lưu trữ raw JSON có cấu trúc nhận được từ Groq Vision
    val aiStateJson: String? = null
)

// ==================== SCHEDULE ====================

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey
    val id: String,
    val pluginId: String,      // "camera", "light", "email", "schedule"
    val action: String,          // "scan", "set", "send", "add"
    val params: String = "{}",   // JSON params
    val cron: String = "",       // "0 7 * * *" hoặc để trống nếu dùng interval
    val intervalMinutes: Int = 0, // 15, 30, 60
    val enabled: Int = 1,        // 0 = tắt, 1 = bật
    val lastRunAt: Long = 0L,
    val label: String = "",
    val createdAt: Long
)

// ==================== APP CONFIG ====================

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val type: String = "string",   // string | int | long | boolean | float
    val pluginId: String = "global",
    val label: String = "",
    val description: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

// ==================== MULTI FACEBOOK PAGES ====================

@Entity(tableName = "facebook_pages")
data class FacebookPageEntity(
    @PrimaryKey
    val id: String,                 // Page ID từ Facebook
    val name: String,               // Tên Fanpage
    val accessToken: String,        // Access Token dài hạn riêng của trang này
    val updatedAt: Long = System.currentTimeMillis()
)

// ==================== EVENT LOG (TRÍ NHỚ SỰ KIỆN) ====================

// ✅ MỚI (Tuần 2 - Phase 2): Nhật ký sự kiện phục vụ bộ nhớ ngữ cảnh và Memory-RAG.
@Entity(
    tableName = "event_logs",
    indices = [Index(value = ["source"]), Index(value = ["timestamp"])]
)
data class EventLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,       // "camera", "tuya", "system", "call", "notification"
    val sourceId: String,     // ID cụ thể của thiết bị/camera/kênh liên lạc
    val eventType: String,    // "state_change", "person_detected", "missed_call", "incoming_message"
    val value: String,        // Giá trị trạng thái thô
    val summary: String,      // Tóm tắt ngôn ngữ tự nhiên để AI dễ đọc
    // ✅ MỚI: đường dẫn ảnh cảnh báo tương ứng (copy từ AlertEntity.imagePath tại đúng thời
    // điểm ghi log này) — để db_search/DatabaseSearchHelper trả lời camera có thể đính kèm
    // đúng ảnh của sự kiện khớp, thay vì chỉ có text. null với log không có ảnh (tuya/call/chat).
    val imagePath: String? = null,
    // ✅ MỚI (LocalVisionTool/ML Kit fallback): đánh dấu NGUỒN của "summary" — "groq" (Groq Vision,
    // mô tả chi tiết, đáng tin) vs "ml_kit_local" (nhãn thô ML Kit khi smart mode tắt hoặc Groq
    // lỗi/hết quota) vs null (log không qua phân tích ảnh nào — tuya/call/chat/motion thuần).
    // Dùng để UI/báo cáo phân biệt độ tin cậy, KHÔNG trộn lẫn 2 chất lượng mô tả khác nhau khi
    // hiển thị cho người dùng — xem thảo luận trước về rủi ro nhầm lẫn nguồn.
    val analysisSource: String? = null
)

// ==================== WORLD STATE (BẢN SAO SỐ THỜI GIAN THỰC) ====================

// ✅ MỚI (Tuần 5 - Phase 5): Bản sao trạng thái mới nhất của các đối tượng trong nhà.
@Entity(tableName = "world_state")
data class WorldStateEntity(
    @PrimaryKey
    val id: String,           // Định dạng: "$source:$sourceId"
    val source: String,       // "camera", "tuya", "system", v.v.
    val sourceId: String,
    val attributesJson: String, // Lưu JSON phẳng các thuộc tính hiện tại: {"state":"on","cup_detected":"true"}
    val updatedAt: Long = System.currentTimeMillis()
)