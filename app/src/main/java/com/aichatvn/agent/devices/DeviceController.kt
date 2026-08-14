package com.aichatvn.agent.devices

/**
 * DeviceController
 *
 * Hợp đồng chung cho MỌI giao thức điều khiển thiết bị (Tuya Cloud/Local, MQTT sau này,
 * OEM khác sau này...). HouseManagerSkill, SmartActionFormSheet, SystemAuditor và mọi
 * nghiệp vụ khác CHỈ được nói chuyện qua interface này (hoặc qua DeviceRegistry bên dưới)
 * — KHÔNG BAO GIỜ import thẳng TuyaManager/MqttDeviceManager/... nữa.
 *
 * Vì sao tách ra bây giờ dù chưa dùng MQTT: nếu để nghiệp vụ gọi thẳng TuyaManager, sau này
 * thêm giao thức mới sẽ phải sửa lại từng nơi gọi (if isTuya... else if isMqtt...). Với lớp
 * này, thêm giao thức mới = viết 1 class implement DeviceController + đăng ký vào
 * DeviceRegistry — không sửa file nghiệp vụ nào đã có.
 *
 * Khi thêm MQTT/OEM: viết class mới (vd MqttDeviceController) implement interface này,
 * bọc quanh MQTT client/library tương ứng — TUYỆT ĐỐI không sửa TuyaDeviceController hay
 * interface này để "tiện" cho riêng MQTT; nếu MQTT cần khả năng mà interface chưa có,
 * thêm method mới với giá trị mặc định (xem `capabilities`) để không phá driver cũ.
 */
interface DeviceController {

    /**
     * Định danh giao thức mà controller này xử lý — PHẢI khớp với giá trị cột
     * `protocol` lưu trong bảng thiết bị (xem DeviceProtocol bên dưới), để DeviceRegistry
     * chọn đúng controller cho từng thiết bị.
     */
    val protocol: DeviceProtocol

    /**
     * Bật thiết bị. deviceId là khoá định danh nội bộ ổn định (KHÔNG phải tên hiển thị
     * người dùng đặt — tên có thể trùng/đổi, id thì không).
     */
    suspend fun turnOn(deviceId: String): DeviceActionResult

    /** Tắt thiết bị. Xem ghi chú deviceId ở turnOn(). */
    suspend fun turnOff(deviceId: String): DeviceActionResult

    /**
     * Đọc trạng thái hiện tại của 1 thiết bị. Trả về null nếu controller không xác định
     * được trạng thái (mất kết nối, thiết bị không tồn tại...) — nghiệp vụ gọi phải tự
     * xử lý trường hợp null, không được giả định luôn có giá trị.
     */
    suspend fun getStatus(deviceId: String): DeviceStatus?

    /**
     * Đọc trạng thái hàng loạt — controller tự tối ưu (vd Tuya Cloud dùng 1 API call batch
     * thay vì N lần gọi getStatus). Mặc định gọi getStatus() tuần tự cho từng id; controller
     * nào có cách nhanh hơn thì override.
     */
    suspend fun getStatusBatch(deviceIds: List<String>): Map<String, DeviceStatus> {
        return deviceIds.mapNotNull { id -> getStatus(id)?.let { id to it } }.toMap()
    }

    /**
     * ✅ MỚI: đọc trạng thái hàng loạt CHỈ QUA ĐƯỜNG LOCAL (LAN), không được phép rơi
     * xuống Cloud/API hãng dù thiết bị không đọc được qua Local — dùng RIÊNG cho vòng poll
     * tần suất cao (5s/lần, xem DashboardProvider.pollLightweightStates()) nơi gọi Cloud lặp
     * lại là không chấp nhận được (chi phí quota) và còn có rủi ro 1 lỗi Cloud làm mất luôn
     * kết quả Local đã đọc thành công của thiết bị khác (xem TuyaManager.getStatusBatchLocalOnly()
     * để hiểu rõ lý do tách khỏi getStatusBatch()).
     *
     * Có default rỗng để driver nào không có khái niệm Local (vd MQTT, mọi lần đều qua
     * broker Internet — xem DeviceController.kt phần MQTT) không bắt buộc override; driver
     * nào có LOCAL_CONTROL (hiện chỉ Tuya) thì PHẢI override để vòng poll 5s thực sự hoạt
     * động, nếu không thiết bị driver đó sẽ luôn vắng mặt khỏi Dashboard poll nhanh (không
     * sai, chỉ là không tối ưu — vẫn cập nhật đúng qua getDashboardNodes() bình thường).
     */
    suspend fun getStatusBatchLocalOnly(deviceIds: List<String>): Map<String, Boolean> = emptyMap()

    /**
     * Danh sách khả năng mà driver này hỗ trợ — SystemAuditor dùng để quyết định mục nào
     * hiển thị "có thể cải thiện" cho thiết bị dùng giao thức này, tránh đề xuất bật tính
     * năng mà bản thân giao thức không hỗ trợ (vd điều khiển local-LAN chỉ Tuya có, MQTT
     * broker-based có thể không cần khái niệm này).
     */
    val capabilities: Set<DeviceCapability>

    /**
     * Khoá ổn định dùng làm `source` khi ghi/đọc world_state cho thiết bị thuộc giao thức
     * này (xem WorldStateHelper.setAttribute(dao, source, deviceId, attribute, value)) và
     * làm giá trị `precondition_source` trong DynamicOptionRegistry/PreconditionGuardDialog.
     *
     * ⚠️ BẮT BUỘC giữ nguyên giá trị đã dùng trước đây cho driver cũ (Tuya = "tuya") —
     * người dùng đã lưu sẵn các chuỗi điều kiện dạng "tuya.<id>.state=..." trong DB
     * (lịch trình/automation). Đổi giá trị này = mọi precondition cũ ngừng khớp một cách
     * ÂM THẦM (guard luôn fail, không có lỗi rõ ràng nào hiện ra).
     *
     * Với driver hoàn toàn mới (vd MQTT), chọn 1 khoá ngắn, ổn định, không dấu, không đổi
     * sau khi đã có người dùng thật lưu precondition với khoá đó — cùng ràng buộc này áp
     * dụng ngược lại cho driver mới ngay từ ngày đầu.
     */
    val worldStateSource: String

    /**
     * Nhãn hiển thị thân thiện cho MỘT DANH MỤC thiết bị theo giao thức này (không phải tên
     * riêng của 1 thiết bị cụ thể — xem DeviceSummary.displayName cho việc đó). Dùng làm
     * label trong dropdown "Nguồn" của Precondition Guard (DynamicOptionRegistry case
     * "precondition_source") — nơi người dùng chọn "thiết bị Tuya" hay "thiết bị MQTT" làm
     * điều kiện, không chọn từng thiết bị cụ thể ở bước đó.
     *
     * Có default dựa trên worldStateSource để không bắt buộc driver nào cũng phải override —
     * nhưng nên viết đè để có icon/tên tiếng Việt thân thiện hơn (xem TuyaDeviceController).
     */
    val displayName: String
        get() = "Thiết bị $worldStateSource"

    /**
     * Liệt kê toàn bộ thiết bị mà driver này đang biết — dùng cho dropdown chọn thiết bị
     * (SmartActionFormSheet qua DynamicOptionRegistry) và các nơi khác cần hiển thị danh
     * sách thiết bị theo protocol, để KHÔNG nơi nào phải query thẳng DAO cụ thể của từng
     * hãng (TuyaDeviceDao, MqttDeviceDao...) nữa — mọi truy vấn danh sách thiết bị đi qua
     * đây, giống hệt turnOn/turnOff/getStatus.
     */
    suspend fun listDevices(): List<DeviceSummary>

    /**
     * Xoá 1 thiết bị khỏi registry nội bộ (DB local của app) — KHÔNG phải "tắt thiết bị",
     * mà là "app ngừng biết tới thiết bị này". Có giá trị mặc định (Failure) vì không phải
     * giao thức nào cũng cho phép người dùng tự xoá qua app (vd 1 số nguồn chỉ đọc/broker-
     * managed) — driver nào hỗ trợ thì override, driver nào không thì giữ mặc định, không
     * bắt buộc implement.
     */
    suspend fun deleteDevice(deviceId: String): DeviceActionResult =
        DeviceActionResult.Failure("Giao thức này không hỗ trợ xoá thiết bị qua app")
}

/**
 * Tóm tắt 1 thiết bị — đủ để hiển thị trong dropdown/danh sách chọn, không mang theo
 * field đặc thù giao thức (localKey, mqttTopic...). Cần chi tiết hơn thì gọi getStatus()
 * riêng cho đúng id đã chọn.
 */
data class DeviceSummary(
    val id: String,
    val displayName: String
)

/**
 * Định danh giao thức — dùng làm giá trị cột `protocol` trong DB và key trong DeviceRegistry.
 * Thêm giao thức mới: thêm 1 hằng số ở đây, KHÔNG xoá/đổi tên hằng số cũ (giá trị đã lưu
 * trong DB của người dùng cũ phải luôn resolve được).
 */
enum class DeviceProtocol {
    TUYA_CLOUD,
    TUYA_LOCAL,
    MQTT,
    ONVIF_CAMERA,
    GENERIC_OEM
}

/**
 * Khả năng của 1 driver — dùng cho SystemAuditor quyết định đề xuất gì là hợp lý.
 */
enum class DeviceCapability {
    /** Điều khiển được qua mạng LAN nội bộ, không cần Internet/Cloud mỗi lần bật/tắt. */
    LOCAL_CONTROL,
    /** Có thể subscribe nhận trạng thái theo thời gian thực (không cần poll). */
    REALTIME_STATUS,
    /** Hỗ trợ đọc trạng thái hàng loạt hiệu quả hơn gọi từng cái. */
    BATCH_STATUS
}

/**
 * Trạng thái thiết bị — trung lập giao thức. `raw` giữ dữ liệu gốc dạng chuỗi (vd JSON payload
 * MQTT, hoặc mô tả trạng thái Tuya) để nghiệp vụ đặc thù có thể tự parse thêm nếu cần, mà
 * không buộc interface chung phải biết cấu trúc riêng của từng giao thức.
 */
data class DeviceStatus(
    val isOn: Boolean,
    val isOnline: Boolean,
    val raw: String? = null
)

/**
 * Kết quả 1 hành động điều khiển — bọc thành/bại thay vì ném exception, vì lỗi mạng/thiết bị
 * offline là tình huống BÌNH THƯỜNG cần xử lý mềm (hiện thông báo cho người dùng), không phải
 * lỗi lập trình cần crash.
 */
sealed class DeviceActionResult {
    data object Success : DeviceActionResult()
    data class Failure(val reason: String) : DeviceActionResult()
}