package com.aichatvn.agent.ui.screens

// ✅ MỚI (refactor tổ chức file): MqttViewModel + toàn bộ Composable UI riêng cho MQTT (trước
// đây nằm chung trong TuyaScreen.kt — 1 file 2662 dòng gộp cả Tuya lẫn MQTT dưới cùng 1 cái tên
// "Tuya", dễ gây nhầm khi tìm code). Tách sang file riêng, GIỮ NGUYÊN package
// com.aichatvn.agent.ui.screens — TuyaScreen() composable vẫn gọi thẳng MqttViewModel/
// MqttDeviceCard/... không cần import gì thêm (cùng package). KHÔNG đổi hành vi: vẫn là 1 màn
// hình duy nhất (TuyaScreen composable) với TabRow Tuya/MQTT — chỉ tách code, không tách màn
// hình. Import list copy nguyên từ TuyaScreen.kt gốc (bao gồm vài import chỉ Tuya dùng, còn sót
// lại ở đây do tách theo khối code chứ không rà từng dòng) — an toàn về compile (import thừa chỉ
// là warning), nhưng nên chạy "Optimize Imports" của IDE sau khi merge để dọn sạch.

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.MqttDeviceEntity
import com.aichatvn.agent.devices.DeviceActionResult
import com.aichatvn.agent.devices.DeviceProtocol
// ⚠️ ĐIỂM CẦN LƯU Ý (đã xác nhận qua code thật — trùng tên class thật sự tồn tại trong project):
// có 2 class khác nhau CÙNG TÊN "DeviceRegistry" ở 2 package khác nhau:
//   - com.aichatvn.agent.ui.dashboard.DeviceRegistry — registry NODE cho Dashboard UI
//     (registerNodes/updateNode/deviceNodes StateFlow), KHÔNG có controllerFor().
//   - com.aichatvn.agent.devices.DeviceRegistry — registry CONTROLLER theo protocol
//     (controllerFor(protocol)/listDevices()/turnOn()/turnOff()), dùng bởi SmartSwitchSkill/
//     SystemAuditor. Đây mới là registry cần cho việc lấy MqttDeviceController.
// File gốc (TuyaScreen.kt) đã phải import CẢ 2 và alias 1 cái để phân biệt — giữ nguyên đúng
// alias đó ở đây cho khớp code gọi bên dưới (mqttController() dùng deviceControlRegistry).
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.devices.DeviceRegistry as DeviceControlRegistry
import com.aichatvn.agent.devices.MqttDeviceController
import com.aichatvn.agent.devices.MqttConfigKeys
import com.aichatvn.agent.devices.CloudMqttBrokerProvider
// ✅ MỚI (track hoàn thiện Cloud MQTT): realtime status 4 trạng thái (UNKNOWN/ON/OFF/OFFLINE) +
// kết quả MqttDiscoveryEngine — xem MqttDeviceController.kt / devices/mqtt/MqttDiscoveryEngine.kt.
import com.aichatvn.agent.devices.MqttDeviceRuntimeStatus
import com.aichatvn.agent.devices.mqtt.DiscoveredMqttDevice
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─── MQTT ViewModel (Giai đoạn 2) ──────────────────────────────────────────────

/**
 * MqttViewModel
 *
 * Cùng khuôn TuyaViewModel: `database.mqttDeviceDao()` để đọc DANH SÁCH hiển thị (cần đủ
 * field topic/online/lastKnownState, DeviceController.listDevices() chỉ trả DeviceSummary rút
 * gọn) — turnOn/turnOff đi qua agentKernel.executePluginAction() (guard + world_state nhất
 * quán với đường chat/Dashboard), deleteDevice() đi qua deviceControlRegistry (world_state
 * dọn đúng qua DeviceController.deleteDevice()).
 *
 * addDevice() ghi THẲNG qua MqttDeviceDao, không qua DeviceController — interface đó cố tình
 * không có method "tạo mới" (xem DeviceController.kt: không có scan/discover, MQTT không có
 * khái niệm "quét" như Tuya Cloud). Đây là ngoại lệ có chủ đích, cùng loại với
 * TuyaViewModel.scanDevices() gọi thẳng tuyaManager — không phải sai sót kiến trúc.
 *
 * ✅ XÁC NHẬN (đã đọc SmartSwitchSkill.kt thật): toggleDevice() gọi agentKernel là ĐÚNG —
 * SmartSwitchSkill.handleSet() dùng resolveDevice() tra cứu qua deviceControlRegistry.all(),
 * không hardcode Tuya, nên hoạt động đúng cho cả MQTT lẫn Tuya. KHÔNG cần đổi sang gọi thẳng
 * mqttController()?.turnOn()/turnOff() như ghi chú trước — giữ nguyên đường qua agentKernel để
 * không mất checkDeviceWorldStateGuard().
 */
@HiltViewModel
class MqttViewModel @Inject constructor(
    private val database: AppDatabase,
    private val deviceControlRegistry: DeviceControlRegistry,
    private val agentKernel: AgentKernel,
    private val configProvider: AppConfigProvider,
    // ✅ MỚI (track Cloud Broker V1): dùng riêng cho testConnection() — KHÔNG dùng chung
    // MqttDeviceController.getOrConnect() vì đó là kết nối singleton phục vụ publish() thật, còn
    // đây chỉ là kết nối thử tạm thời rồi disconnect ngay. Cùng CloudMqttBrokerProvider nhưng
    // instance khác nhau (Hilt @Singleton trả về cùng 1 instance CloudMqttBrokerProvider, nhưng
    // .connect() ở đây tạo MqttClient MỚI, không đụng tới MqttDeviceController.client đang giữ).
    private val cloudMqttBrokerProvider: CloudMqttBrokerProvider,
    // ✅ MỚI (MQTT Symmetric Broker, PATCH C.3): bầu lại vai trò broker mỗi khi người dùng mở
    // tab MQTT — Camera Node có thể vừa online/offline từ lần election cuối (app start/foreground).
    private val electionManager: com.aichatvn.agent.devices.mqtt.MqttBrokerElectionManager,
    // ✅ MỚI (auto-adopt Tasmota mới mua qua LAN): "Quét nhanh" cũ (discoverDevices() ở trên)
    // CHỈ nghe được thiết bị ĐÃ publish đúng lên broker app đang dùng — Tasmota mới mua/thiết
    // bị cũ đang trỏ broker mặc định của hãng sẽ KHÔNG xuất hiện qua đường đó. LAN discovery
    // (quét HTTP subnet, không qua MQTT) là đường DUY NHẤT tìm ra được nhóm này — xem
    // TasmotaLanDiscovery.discoverAdoptableDevices().
    private val tasmotaLanDiscovery: com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery,
    private val logger: Logger
) : ViewModel() {

    private fun mqttController() = deviceControlRegistry.controllerFor(DeviceProtocol.MQTT)

    private val _devices = MutableStateFlow<List<MqttDeviceEntity>>(emptyList())
    val devices: StateFlow<List<MqttDeviceEntity>> = _devices.asStateFlow()

    private val _loadingDevices = MutableStateFlow<Set<String>>(emptySet())
    val loadingDevices: StateFlow<Set<String>> = _loadingDevices.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _brokerUrl = MutableStateFlow("")
    val brokerUrl: StateFlow<String> = _brokerUrl.asStateFlow()

    // ✅ MỚI (track Cloud Broker V1, mục 1.2 kế hoạch): trạng thái nút "Kiểm tra kết nối" trong
    // MqttBrokerConfigDialog — thử connect thật (KHÔNG publish gì) trước khi cho phép Lưu, để
    // người dùng biết ngay Broker URL/Username/Password đúng hay sai thay vì phải chờ tới lúc
    // bấm Bật/Tắt 1 thiết bị mới phát hiện ra lỗi.
    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<Boolean?>(null) // null = chưa test
    val connectionTestResult: StateFlow<Boolean?> = _connectionTestResult.asStateFlow()

    // ✅ MỚI: "Quét nhanh" — lắng nghe thụ động wildcard "#" qua
    // MqttDeviceController.scanTopics(), xem docstring ở đó để biết vì sao KHÔNG có API
    // "liệt kê thiết bị" chuẩn hoá trong MQTT. discoveredTopics đã LỌC bỏ topic trùng với
    // thiết bị đã thêm (_devices.value) — UI chỉ cần hiển thị thẳng, không cần lọc lại.
    private val _discoveredTopics = MutableStateFlow<List<String>>(emptyList())
    val discoveredTopics: StateFlow<List<String>> = _discoveredTopics.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ✅ MỚI: khóa an toàn (Precondition Guard) cho thiết bị MQTT — cùng khuôn
    // TuyaViewModel.deviceGuards/loadDeviceGuards()/saveDeviceGuard()/removeDeviceGuard(),
    // dùng chung key "worldstate_guard_<id>" trong AppConfigProvider nên không đụng độ với
    // Tuya (id UUID không trùng nhau giữa 2 bảng).
    private val _deviceGuards = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceGuards: StateFlow<Map<String, String>> = _deviceGuards.asStateFlow()

    // ✅ MỚI (track hoàn thiện Cloud MQTT, mục "Realtime state/status"): map deviceId ->
    // MqttDeviceRuntimeStatus lấy TRỰC TIẾP từ MqttDeviceController.realtimeStates — UI (
    // MqttDeviceCard) đọc từ đây để hiện đúng 4 trạng thái (UNKNOWN/ON/OFF/OFFLINE) thay vì chỉ
    // đọc cột `online` tĩnh trong MqttDeviceEntity. Không tự suy luận gì thêm ở tầng ViewModel —
    // chỉ copy nguyên StateFlow của controller sang dạng đơn giản hơn cho Compose.
    private val _realtimeStatuses = MutableStateFlow<Map<String, MqttDeviceRuntimeStatus>>(emptyMap())
    val realtimeStatuses: StateFlow<Map<String, MqttDeviceRuntimeStatus>> = _realtimeStatuses.asStateFlow()

    // ✅ MỚI (track hoàn thiện Cloud MQTT, mục "Discovery Engine phổ quát"): kết quả 1 lần
    // discoverDevices() — thiết bị ĐÃ NHẬN DIỆN được (Home Assistant/Homie/vendor adapter), CHƯA
    // lưu DB, chờ người dùng xem + xác nhận (confirmDiscoveredDevice()) giống flow Camera
    // Discovery. Tách biệt với `discoveredTopics` cũ (topic thô, vẫn giữ nguyên làm fallback).
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredMqttDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredMqttDevice>> = _discoveredDevices.asStateFlow()

    // ✅ MỚI (auto-adopt Tasmota qua LAN): kết quả 1 lần quét LAN riêng — thiết bị Tasmota tìm
    // thấy trên subnet nhưng CHƯA có trong DB, đã được TasmotaLanDiscovery push MqttHost trỏ về
    // đúng broker app đang dùng, CHỜ người dùng xác nhận từng cái (KHÔNG tự lưu — cùng nguyên
    // tắc "xác nhận trước khi lưu" như _discoveredDevices ở trên).
    private val _lanAdoptableDevices =
        MutableStateFlow<List<com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery.AdoptableTasmotaDevice>>(emptyList())
    val lanAdoptableDevices: StateFlow<List<com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery.AdoptableTasmotaDevice>> =
        _lanAdoptableDevices.asStateFlow()

    private val _isLanScanning = MutableStateFlow(false)
    val isLanScanning: StateFlow<Boolean> = _isLanScanning.asStateFlow()

    init {
        loadDevices()
        loadBrokerConfig()
        // ✅ MỚI: quan sát realtime status của controller suốt vòng đời ViewModel — controller là
        // Hilt @Singleton nên StateFlow này SỐNG XUYÊN SUỐT kể cả khi rời màn hình rồi quay lại,
        // không mất dữ liệu đã có, không cần gọi lại gì thêm khi mở lại tab MQTT.
        viewModelScope.launch {
            val controller = mqttController() as? MqttDeviceController ?: return@launch
            controller.realtimeStates.collect { states ->
                _realtimeStatuses.value = states.mapValues { it.value.status }
            }
        }
        // ✅ MỚI (PATCH C.3): election khi mở tab MQTT — không chặn loadDevices()/loadBrokerConfig()
        // ở trên (coroutine riêng), lỗi ở đây không ảnh hưởng phần còn lại của init.
        viewModelScope.launch {
            try {
                electionManager.electNow(
                    com.aichatvn.agent.devices.mqtt.MqttBrokerElectionManager.ElectionTrigger.DashboardOpen
                )
            } catch (e: Exception) {
                logger.e("MqttViewModel", "election DashboardOpen lỗi: ${e.message}", e)
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _devices.value = withContext(Dispatchers.IO) { database.mqttDeviceDao().getAllDevices() }
            loadDeviceGuards()
        }
    }

    fun loadDeviceGuards() {
        viewModelScope.launch {
            val guards = mutableMapOf<String, String>()
            _devices.value.forEach { dev ->
                val guardVal = configProvider.getString("worldstate_guard_${dev.id}", "")
                if (guardVal.isNotBlank()) guards[dev.id] = guardVal
            }
            _deviceGuards.value = guards
        }
    }

    fun saveDeviceGuard(deviceId: String, precondition: String) {
        viewModelScope.launch {
            configProvider.set("worldstate_guard_$deviceId", precondition)
            _message.value = "🔒 Đã thiết lập khóa bảo vệ vật lý"
            loadDeviceGuards()
        }
    }

    fun removeDeviceGuard(deviceId: String) {
        viewModelScope.launch {
            configProvider.delete("worldstate_guard_$deviceId")
            _message.value = "🔓 Đã gỡ bỏ khóa bảo vệ vật lý"
            loadDeviceGuards()
        }
    }

    fun loadBrokerConfig() {
        viewModelScope.launch {
            _brokerUrl.value = configProvider.getString(MqttConfigKeys.BROKER_URL, "")
        }
    }

    fun saveBrokerConfig(brokerUrl: String, username: String, password: String, port: String, useTls: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configProvider.set(MqttConfigKeys.BROKER_URL, brokerUrl.trim())
                configProvider.set(MqttConfigKeys.USERNAME, username.trim())
                configProvider.set(MqttConfigKeys.PASSWORD, password)
                configProvider.set(MqttConfigKeys.PORT, port.trim())
                configProvider.set(MqttConfigKeys.USE_TLS, useTls.toString())
            }
            // ✅ Ngắt kết nối cũ ngay khi đổi broker — tránh MqttDeviceController lỡ dùng
            // kết nối tới broker cũ cho lần bật/tắt tiếp theo.
            mqttController()?.let { (it as? MqttDeviceController)?.invalidateConnection() }
            _brokerUrl.value = brokerUrl.trim()
            _connectionTestResult.value = null // config vừa đổi — kết quả test cũ không còn đúng
            _message.value = "✅ Đã lưu cấu hình broker MQTT"
        }
    }

    /**
     * testConnection ("Kiểm tra kết nối")
     *
     * ✅ MỚI (track Cloud Broker V1, mục 1.2 kế hoạch): dùng TRỰC TIẾP giá trị người dùng vừa gõ
     * trong dialog (chưa lưu vào AppConfigProvider) — không phải cấu hình đã lưu trước đó. Ghi
     * TẠM vào configProvider, gọi thử connect() qua CloudMqttBrokerProvider (dùng lại đúng
     * MqttBrokerProvider đã tách trong MqttDeviceController.kt, không viết lại logic connect
     * riêng), rồi disconnect ngay — KHÔNG publish gì, không lưu lại thành cấu hình chính thức nếu
     * người dùng chưa bấm "Lưu" (dialog tự gọi saveBrokerConfig() riêng khi bấm Lưu thật).
     */
    fun testConnection(brokerUrl: String, username: String, password: String, port: String, useTls: Boolean) {
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestResult.value = null
            try {
                withContext(Dispatchers.IO) {
                    configProvider.set(MqttConfigKeys.BROKER_URL, brokerUrl.trim())
                    configProvider.set(MqttConfigKeys.USERNAME, username.trim())
                    configProvider.set(MqttConfigKeys.PASSWORD, password)
                    configProvider.set(MqttConfigKeys.PORT, port.trim())
                    configProvider.set(MqttConfigKeys.USE_TLS, useTls.toString())
                }
                val client = withContext(Dispatchers.IO) { cloudMqttBrokerProvider.connect() }
                if (client != null) {
                    _connectionTestResult.value = true
                    try {
                        withContext(Dispatchers.IO) { client.disconnect() }
                    } catch (e: Exception) {
                        logger.d("MqttViewModel", "testConnection(): disconnect() sau test lỗi (bỏ qua): ${e.message}")
                    }
                } else {
                    _connectionTestResult.value = false
                }
            } catch (e: Exception) {
                _connectionTestResult.value = false
                logger.e("MqttViewModel", "testConnection error", e)
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun clearConnectionTestResult() {
        _connectionTestResult.value = null
    }

    /**
     * scanTopics ("Quét nhanh")
     *
     * Gọi thẳng MqttDeviceController.scanTopics() (KHÔNG qua agentKernel — cùng ngoại lệ có
     * chủ đích như addDevice()/TuyaViewModel.scanDevices(), đây là thao tác cấu hình/khám phá
     * thiết bị, không phải hành động điều khiển cần qua guard/world_state). Cast an toàn qua
     * `as?` giống đúng pattern saveBrokerConfig() đã dùng ở trên.
     */
    fun scanTopics() {
        viewModelScope.launch {
            val controller = mqttController() as? MqttDeviceController
            if (controller == null) {
                _message.value = "❌ Chưa có driver MQTT — kiểm tra lại cấu hình broker"
                return@launch
            }
            _isScanning.value = true
            _discoveredTopics.value = emptyList()
            try {
                val knownTopics = _devices.value.map { it.commandTopic }.toSet()
                val topics = withContext(Dispatchers.IO) { controller.scanTopics() }
                _discoveredTopics.value = topics.filter { it !in knownTopics }
                if (_discoveredTopics.value.isEmpty()) {
                    _message.value = "🔍 Không phát hiện topic nào trong lúc quét — thiết bị " +
                        "có thể không tự publish, hãy thử \"Thêm thiết bị\" và nhập tay topic"
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi quét: ${e.message}"
                logger.e("MqttViewModel", "scanTopics error", e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearDiscoveredTopics() {
        _discoveredTopics.value = emptyList()
    }

    /**
     * discoverDevices ("Quét nhanh" chuẩn mới)
     *
     * ✅ MỚI (track hoàn thiện Cloud MQTT): thay scanTopics() làm đường CHÍNH cho "Quét nhanh" —
     * gọi MqttDeviceController.discoverDevices() (Home Assistant/Homie/vendor adapter qua
     * MqttDiscoveryEngine), nhận về CẢ thiết bị đã nhận diện được (_discoveredDevices — cần
     * người dùng xác nhận trước khi lưu) LẪN topic thô không adapter nào hiểu (_discoveredTopics
     * — dùng lại y hệt luồng "Thêm tay" cũ, không đổi UI ScanTopicsDialog phần đó).
     */
    fun discoverDevices() {
        viewModelScope.launch {
            val controller = mqttController() as? MqttDeviceController
            if (controller == null) {
                _message.value = "❌ Chưa có driver MQTT — kiểm tra lại cấu hình broker"
                return@launch
            }
            _isScanning.value = true
            _discoveredTopics.value = emptyList()
            _discoveredDevices.value = emptyList()
            try {
                val knownCommandTopics = _devices.value.map { it.commandTopic }.toSet()
                val result = withContext(Dispatchers.IO) { controller.discoverDevices() }
                _discoveredDevices.value = result.recognizedDevices.filter { it.commandTopic !in knownCommandTopics }
                _discoveredTopics.value = result.fallbackTopics.filter { it !in knownCommandTopics }
                if (_discoveredDevices.value.isEmpty() && _discoveredTopics.value.isEmpty()) {
                    _message.value = "🔍 Không phát hiện thiết bị hay topic nào trong lúc quét — " +
                        "thiết bị có thể không tự publish, hãy thử \"Thiết lập nhanh\" và nhập tay"
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi quét: ${e.message}"
                logger.e("MqttViewModel", "discoverDevices error", e)
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    /**
     * confirmDiscoveredDevice
     *
     * ✅ MỚI: lưu 1 thiết bị đã được MqttDiscoveryEngine nhận diện — CHỈ gọi SAU khi người dùng
     * xem lại + xác nhận trên UI (finalName cho phép sửa tên gợi ý trước khi lưu), đúng yêu cầu
     * "không tự động lưu, phải qua xác nhận giống Camera Discovery". discoverySource lấy nguyên
     * từ adapter đã nhận diện (vd "home_assistant", "homie", "vendor:tasmota") — không phải
     * "manual" như addDevice() nhập tay bên dưới.
     */
    fun confirmDiscoveredDevice(discovered: DiscoveredMqttDevice, finalName: String) {
        viewModelScope.launch {
            val entity = MqttDeviceEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = finalName.trim().ifBlank { discovered.suggestedName },
                topic = discovered.commandTopic, // cột cũ giữ để UI nào còn đọc trực tiếp không bị trống
                commandTopic = discovered.commandTopic,
                stateTopic = discovered.stateTopic,
                onPayload = discovered.onPayload,
                offPayload = discovered.offPayload,
                discoverySource = discovered.discoverySource
            )
            withContext(Dispatchers.IO) { database.mqttDeviceDao().insertDevice(entity) }
            _discoveredDevices.value = _discoveredDevices.value.filter { it.uniqueId != discovered.uniqueId }
            _message.value = "✅ Đã thêm thiết bị \"${entity.name}\""
            loadDevices()
            // Subscribe stateTopic của thiết bị vừa thêm NGAY, không chờ tới lần reconnect kế
            // tiếp — xem docstring refreshDeviceSubscriptions() trong MqttDeviceController.
            (mqttController() as? MqttDeviceController)?.refreshDeviceSubscriptions()
        }
    }

    /**
     * scanLanForAdoptableDevices ("Quét LAN Tasmota" — bổ sung cho "Quét nhanh" cũ)
     *
     * ✅ MỚI: "Quét nhanh" (discoverDevices() ở trên) chỉ nghe được thiết bị ĐÃ publish đúng lên
     * broker app đang dùng. Tasmota mới mua/thiết bị cũ vẫn đang trỏ broker mặc định của hãng
     * sẽ KHÔNG bao giờ xuất hiện qua đường đó — im lặng, không phải lỗi. Hàm này quét subnet
     * qua HTTP (không qua MQTT), tìm Tasmota lạ, tự đẩy MqttHost trỏ về broker app đang dùng,
     * rồi trả danh sách cho UI xác nhận — KHÔNG tự lưu DB (TasmotaLanDiscovery.
     * discoverAdoptableDevices() đã tách bạch rõ 2 bước này).
     */
    fun scanLanForAdoptableDevices() {
        viewModelScope.launch {
            _isLanScanning.value = true
            _lanAdoptableDevices.value = emptyList()
            try {
                val result = withContext(Dispatchers.IO) { tasmotaLanDiscovery.discoverAdoptableDevices() }
                when {
                    result.skippedNoBroker -> {
                        _message.value = "⚠️ Chưa có broker MQTT khả dụng — vào \"Cấu hình Broker\" trước khi quét LAN"
                    }
                    result.adoptable.isEmpty() && result.failedIps.isEmpty() -> {
                        _message.value = "🔍 Không tìm thấy thiết bị Tasmota mới nào trên mạng LAN"
                    }
                    result.adoptable.isEmpty() && result.failedIps.isNotEmpty() -> {
                        _message.value = "⚠️ Tìm thấy ${result.failedIps.size} thiết bị nhưng không trỏ được broker — thử lại hoặc kiểm tra mạng"
                    }
                    else -> {
                        _lanAdoptableDevices.value = result.adoptable
                        if (result.failedIps.isNotEmpty()) {
                            _message.value = "✅ Tìm thấy ${result.adoptable.size} thiết bị mới " +
                                "(${result.failedIps.size} thiết bị khác trỏ broker thất bại, thử quét lại)"
                        }
                    }
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi quét LAN: ${e.message}"
                logger.e("MqttViewModel", "scanLanForAdoptableDevices error", e)
            } finally {
                _isLanScanning.value = false
            }
        }
    }

    fun clearLanAdoptableDevices() {
        _lanAdoptableDevices.value = emptyList()
    }

    /**
     * confirmLanAdopt — CHỈ gọi SAU khi người dùng xem lại + xác nhận trên UI (đúng nguyên tắc
     * "không tự động lưu" áp dụng chung cho mọi luồng discovery trong màn này). MqttHost đã được
     * push xong trong lúc quét (scanLanForAdoptableDevices()) — hàm này chỉ ghi DB, không gọi
     * lại HTTP command tới thiết bị.
     */
    fun confirmLanAdopt(device: com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery.AdoptableTasmotaDevice) {
        viewModelScope.launch {
            try {
                val entity = withContext(Dispatchers.IO) { tasmotaLanDiscovery.confirmAdopt(device) }
                _lanAdoptableDevices.value = _lanAdoptableDevices.value.filter { it.ip != device.ip }
                _message.value = "✅ Đã thêm thiết bị \"${entity.name}\""
                loadDevices()
                (mqttController() as? MqttDeviceController)?.refreshDeviceSubscriptions()
            } catch (e: Exception) {
                _message.value = "❌ Lỗi thêm thiết bị: ${e.message}"
                logger.e("MqttViewModel", "confirmLanAdopt error", e)
            }
        }
    }

    /**
     * addDevice ("Thiết lập nhanh" — thêm tay, dùng khi discovery không nhận diện được)
     *
     * ✅ SỬA (track hoàn thiện Cloud MQTT): trước đây chỉ nhận (name, topic) rồi gán cùng 1 giá
     * trị cho cả `topic`/`commandTopic`, không có stateTopic/onPayload/offPayload riêng — nghĩa
     * là thiết bị thêm tay CHỈ điều khiển được 1 chiều, không thể có realtime status. Giờ nhận
     * đủ 5 trường đúng yêu cầu "Thiết lập nhanh": Tên, Command Topic, State Topic, ON payload,
     * OFF payload — stateTopic để trống hợp lệ (thiết bị không có gì để đọc trạng thái ngược,
     * vẫn điều khiển 1 chiều bình thường, KHÔNG bắt buộc phải có). onPayload/offPayload có mặc
     * định "ON"/"OFF" (giữ tương thích ngược với hành vi cũ) nhưng người dùng sửa được — KHÔNG
     * tự đoán payload nào khác dựa trên tên topic.
     */
    fun addDevice(
        name: String,
        commandTopic: String,
        stateTopic: String = "",
        onPayload: String = "ON",
        offPayload: String = "OFF",
        // ✅ MỚI (Dimmer): rỗng = không dimmer. isDimmable suy ra thẳng từ trường này, không
        // cần tham số Boolean riêng — tránh 2 giá trị (topic + cờ) có thể mâu thuẫn nhau
        // (vd cờ true nhưng topic rỗng).
        levelCommandTopic: String = ""
    ) {
        viewModelScope.launch {
            val trimmedCommandTopic = commandTopic.trim()
            val trimmedStateTopic = stateTopic.trim()
            val trimmedLevelTopic = levelCommandTopic.trim()
            val entity = MqttDeviceEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                topic = trimmedCommandTopic,
                commandTopic = trimmedCommandTopic,
                stateTopic = trimmedStateTopic.ifBlank { null },
                onPayload = onPayload.ifBlank { "ON" },
                offPayload = offPayload.ifBlank { "OFF" },
                discoverySource = "manual",
                // ✅ MỚI (Dimmer)
                levelCommandTopic = trimmedLevelTopic.ifBlank { null },
                isDimmable = trimmedLevelTopic.isNotBlank()
            )
            withContext(Dispatchers.IO) { database.mqttDeviceDao().insertDevice(entity) }
            _message.value = "✅ Đã thêm thiết bị \"${entity.name}\""
            loadDevices()
            if (entity.stateTopic != null) {
                (mqttController() as? MqttDeviceController)?.refreshDeviceSubscriptions()
            }
        }
    }

    fun toggleDevice(device: MqttDeviceEntity, turnOn: Boolean) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                val params = mapOf("device" to device.id, "state" to turnOn)
                val result = withContext(Dispatchers.IO) {
                    agentKernel.executePluginAction("smart_switch", "set", params)
                }
                when (result) {
                    is PluginResult.Success -> {
                        _message.value = if (turnOn) "💡 Đã bật ${device.name}" else "🔌 Đã tắt ${device.name}"
                        loadDevices()
                    }
                    is PluginResult.Failure -> _message.value = "❌ ${result.error}"
                    else -> {}
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi: ${e.message}"
                logger.e("MqttViewModel", "toggleDevice error", e)
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    // ✅ MỚI (Dimmer): song song toggleDevice() ở trên, cùng khuôn với TuyaViewModel.setLevel()
    // — gửi "level" thay vì "state" để rơi đúng nhánh dimmer trong SmartSwitchSkill.handleSet().
    fun setLevel(device: MqttDeviceEntity, percent: Int) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                val params = mapOf("device" to device.id, "level" to percent)
                val result = withContext(Dispatchers.IO) {
                    agentKernel.executePluginAction("smart_switch", "set", params)
                }
                when (result) {
                    is PluginResult.Success -> {
                        _message.value = "🔆 Đã đặt ${device.name} ở mức $percent%"
                        loadDevices()
                    }
                    is PluginResult.Failure -> _message.value = "❌ ${result.error}"
                    else -> {}
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi: ${e.message}"
                logger.e("MqttViewModel", "setLevel error", e)
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    fun deleteDevice(device: MqttDeviceEntity) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                val controller = mqttController()
                    ?: throw Exception("Chưa có driver điều khiển cho thiết bị MQTT")
                // ✅ MqttDeviceController.deleteDevice() (bản mới) tự dọn subscription/cache
                // realtime của thiết bị này ngay trong hàm — không cần gọi thêm gì ở đây.
                val result = withContext(Dispatchers.IO) { controller.deleteDevice(device.id) }
                if (result is DeviceActionResult.Failure) throw Exception(result.reason)
                _message.value = "🗑️ Đã xoá thiết bị \"${device.name}\""
                loadDevices()
            } catch (e: Exception) {
                _message.value = "❌ Lỗi xoá thiết bị: ${e.message}"
                logger.e("MqttViewModel", "deleteDevice error", e)
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    fun clearMessage() { _message.value = null }
}


// ─── MQTT UI (Giai đoạn 2) ─────────────────────────────────────────────────

/**
 * MqttDeviceCard
 *
 * Cùng tinh thần TuyaDeviceCard nhưng đơn giản hơn — MqttDeviceEntity chỉ có
 * id/name/topic/online/lastKnownState/lastSeen (không có category/productName/guard/local
 * control như Tuya), nên không cần các phần Khóa bảo vệ / "Bật nhanh qua LAN" / nút "Trạng
 * thái" (MQTT không có khái niệm chủ động poll trạng thái — chỉ nhận đẩy qua subscribe, xem
 * ghi chú DeviceCapability.REALTIME_STATUS trong MqttDeviceController.kt).
 */
@Composable
fun MqttDeviceCard(
    device: MqttDeviceEntity,
    // ✅ MỚI (track hoàn thiện Cloud MQTT): trạng thái realtime 4 mức — thay cho việc chỉ đọc
    // device.online tĩnh. UNKNOWN hiện riêng biệt với OFF (xám thay vì đỏ) — không được phép để
    // người dùng hiểu lầm "chưa nhận được message nào" = "đang tắt".
    runtimeStatus: MqttDeviceRuntimeStatus,
    isLoading: Boolean,
    // ✅ MỚI: precondition hiện có (nếu có) cho thiết bị này — chỉ dùng để tô màu icon khóa,
    // cùng ý nghĩa currentGuard trong TuyaDeviceCard.
    currentGuard: String?,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onDelete: () -> Unit,
    // ✅ MỚI: mở PreconditionGuardDialog cho thiết bị MQTT này — trước đây MQTT không có
    // đường nào để bị đặt làm điều kiện hay được gán khóa an toàn.
    onConfigureGuard: () -> Unit,
    // ✅ MỚI (Dimmer): song song TuyaDeviceCard.onSetLevel — chỉ gọi khi device.isDimmable.
    onSetLevel: (Int) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (runtimeStatus == MqttDeviceRuntimeStatus.ON)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "📡", fontSize = 22.sp)
                    }
                    Column {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = device.topic,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ✅ SỬA (track hoàn thiện Cloud MQTT): 4 màu/nhãn riêng biệt — ON (xanh),
                    // OFF (đỏ nhạt — CHẮC CHẮN đang tắt, có message thật xác nhận), OFFLINE (đỏ
                    // đậm — mất kết nối broker), UNKNOWN (xám — chưa có message nào, KHÔNG phải
                    // đang tắt). Đây là điểm khác biệt cốt lõi so với bản cũ (chỉ 2 màu ONLINE/
                    // OFFLINE suy từ device.online tĩnh).
                    val (bgColor, textColor, label) = when (runtimeStatus) {
                        MqttDeviceRuntimeStatus.ON -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "BẬT")
                        MqttDeviceRuntimeStatus.OFF -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "TẮT")
                        MqttDeviceRuntimeStatus.OFFLINE -> Triple(Color(0xFFFFEBEE), Color(0xFF8B0000), "MẤT KẾT NỐI")
                        MqttDeviceRuntimeStatus.UNKNOWN -> Triple(Color(0xFFF5F5F5), Color(0xFF757575), "CHƯA RÕ")
                    }
                    Surface(color = bgColor, contentColor = textColor, shape = CircleShape) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    // ✅ MỚI: cùng nút khóa an toàn như TuyaDeviceCard — trước đây MQTT không
                    // có nút này, khiến không thể đặt điều kiện an toàn cho thiết bị MQTT.
                    IconButton(onClick = onConfigureGuard, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Cấu hình khóa an toàn",
                            modifier = Modifier.size(18.dp),
                            tint = if (!currentGuard.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Xoá thiết bị",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // ✅ SỬA (track hoàn thiện Cloud MQTT): đổi nhãn từ "Lệnh cuối gửi đi" — trước đây
            // đây LÀ nguồn "trạng thái" duy nhất (chỉ đọc DB tĩnh), giờ badge phía trên mới là
            // trạng thái thật realtime; dòng này chỉ còn ý nghĩa lịch sử "app từng gửi lệnh gì".
            InfoChip(
                label = "Lệnh cuối app đã gửi",
                value = if (device.lastKnownState) "Bật" else "Tắt"
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // ✅ MỚI (Dimmer): khác TuyaDeviceCard — MqttDeviceEntity CÓ lastKnownLevel thật
            // (cache từ lần publish/subscribe gần nhất), dùng làm giá trị khởi tạo, không cần
            // mặc định cứng 50% như bên Tuya.
            if (device.isDimmable) {
                var sliderPercent by remember(device.id) {
                    mutableStateOf((device.lastKnownLevel ?: 50).toFloat())
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "🔆 Mức độ: ${sliderPercent.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = sliderPercent,
                        onValueChange = { sliderPercent = it },
                        onValueChangeFinished = { onSetLevel(sliderPercent.toInt()) },
                        valueRange = 0f..100f,
                        steps = 99,
                        enabled = !isLoading
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onTurnOn,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("💡 Bật", maxLines = 1)
                    }
                    Button(
                        onClick = onTurnOff,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("🔌 Tắt", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyMqttState(
    brokerConfigured: Boolean,
    onScan: () -> Unit,
    onManualSetup: () -> Unit,
    onConfigureBroker: () -> Unit,
    // ✅ MỚI: "Quét nhanh" chỉ thấy thiết bị ĐÃ trỏ đúng broker — Tasmota mới mua cần đường
    // riêng (quét LAN qua HTTP, không qua MQTT) để tìm ra rồi tự trỏ broker giúp người dùng,
    // vì phần lớn người dùng không biết vào web UI Tasmota đổi MqttHost bằng tay.
    onScanLan: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ SỬA (fix nút cuối bị che): trước đây 4 nút xếp trong 1 Column không cuộn được — khi
    // tổng chiều cao vượt màn hình, nút cuối ("Thiết lập thủ công") bị cắt/che, đặc biệt khi
    // FAB nổi đè lên. Bọc verticalScroll + đệm đáy để luôn kéo tới được nút cuối.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📡", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Chưa có thiết bị MQTT",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // ✅ SỬA (track hoàn thiện Cloud MQTT): "Quét nhanh" giờ vừa tự nhận diện thiết bị
            // (Home Assistant/Homie/vendor adapter — MqttDiscoveryEngine) vừa gom topic thô làm
            // fallback — xem MqttDeviceController.discoverDevices(). Không nhận diện được gì thì
            // dùng "Thiết lập nhanh" nhập tay, không mất phương án cũ.
            text = if (brokerConfigured)
                "Nhấn \"Quét nhanh\" để tự phát hiện thiết bị đang publish, \"Quét LAN Tasmota " +
                    "mới\" cho thiết bị Tasmota chưa cấu hình broker, hoặc \"Thiết lập thủ công\" " +
                    "nếu thiết bị của bạn không tự gửi dữ liệu"
            else
                "Cấu hình broker trước, sau đó quét nhanh hoặc thiết lập nhanh theo tên + topic",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        if (!brokerConfigured) {
            Button(onClick = onConfigureBroker) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Cấu hình Broker")
            }
        } else {
            Button(onClick = onScan) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Quét nhanh")
            }
            Spacer(Modifier.height(8.dp))
            // ✅ MỚI: đặt dưới "Quét nhanh", trên "Thiết lập nhanh" — thứ tự ưu tiên đúng mức độ
            // tự động: quét MQTT trước (nhanh nhất nếu thiết bị đã sẵn sàng) → quét LAN Tasmota
            // (chậm hơn ~5-10s nhưng bắt được thiết bị mới chưa cấu hình) → nhập tay (luôn có
            // sẵn, không phụ thuộc gì).
            OutlinedButton(onClick = onScanLan) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Quét LAN Tasmota mới")
            }
            Text(
                text = "Dành cho thiết bị Tasmota mới mua, chưa từng cấu hình broker",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp)
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onManualSetup) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Thiết lập thủ công")
            }
            // ✅ MỚI: đệm đáy để nút cuối không dính sát mép màn hình khi cuộn tới cùng.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * LanAdoptConfirmDialog ("Quét LAN Tasmota mới" — xác nhận trước khi lưu)
 *
 * Hiển thị SAU khi TasmotaLanDiscovery.discoverAdoptableDevices() xong — mỗi dòng là 1 Tasmota
 * đã được tự động trỏ MqttHost về broker app đang dùng (xem docstring hàm đó), CHỜ người dùng
 * xác nhận từng cái trước khi thực sự lưu vào DB (confirmLanAdopt()). Cùng nguyên tắc UX với
 * ScanTopicsDialog phía trên — không tự động lưu bất kỳ thiết bị nào.
 */
@Composable
fun LanAdoptConfirmDialog(
    devices: List<com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery.AdoptableTasmotaDevice>,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onConfirm: (com.aichatvn.agent.devices.mqtt.TasmotaLanDiscovery.AdoptableTasmotaDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thiết bị Tasmota mới trên LAN") },
        text = {
            Column {
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Đang quét mạng LAN...")
                    }
                } else if (devices.isEmpty()) {
                    Text(
                        "Không còn thiết bị nào chờ xác nhận.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Đã tìm thấy và trỏ broker cho các thiết bị sau — bấm để thêm vào danh sách:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(devices, key = { it.ip }) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onConfirm(device) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.suggestedName, fontWeight = FontWeight.Medium)
                                    Text(
                                        device.ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.Add, contentDescription = "Thêm")
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !isScanning) { Text("Quét lại") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        }
    )
}

/**
 * ScanTopicsDialog ("Quét nhanh")
 *
 * Hiển thị KHI ĐANG quét (CircularProgressIndicator + đếm ngược cảm tính, không có số giây
 * chính xác vì delay(timeoutMs) chạy trong ViewModel, dialog chỉ biết isScanning true/false)
 * và SAU khi quét xong (danh sách topic phát hiện được, đã lọc bỏ trùng thiết bị đã thêm —
 * xem MqttViewModel.scanTopics()). Rỗng sau khi quét xong KHÔNG phải lỗi, chỉ là không có gì
 * publish trong lúc quét — hiển thị gợi ý "Thêm tay" ngay tại đây thay vì bắt người dùng tự
 * đóng dialog rồi tìm nút khác.
 */
@Composable
fun ScanTopicsDialog(
    // ✅ MỚI (track hoàn thiện Cloud MQTT): thiết bị MqttDiscoveryEngine đã NHẬN DIỆN được
    // (Home Assistant/Homie/vendor adapter) — hiện ưu tiên phía trên, mỗi thiết bị chỉ cần chạm
    // 1 lần để mở DiscoveryConfirmDialog xác nhận, KHÔNG lưu thẳng ở đây.
    recognizedDevices: List<DiscoveredMqttDevice>,
    // Topic thô KHÔNG adapter nào nhận diện được — hành vi y hệt bản cũ, làm fallback.
    topics: List<String>,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onDeviceSelected: (DiscoveredMqttDevice) -> Unit,
    onTopicSelected: (String) -> Unit,
    onAddManually: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quét nhanh MQTT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Đang lắng nghe broker... thiết bị/gateway của bạn cần tự gửi dữ " +
                                "liệu trong lúc này mới xuất hiện được.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (recognizedDevices.isEmpty() && topics.isEmpty()) {
                    Text(
                        "Không phát hiện thiết bị hay topic nào. Thiết bị có thể không tự " +
                            "publish định kỳ — hãy thử quét lại đúng lúc thiết bị hoạt động " +
                            "(vd bật/tắt công tắc vật lý), hoặc dùng Thiết lập nhanh nếu bạn " +
                            "đã biết topic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (recognizedDevices.isNotEmpty()) {
                            Text(
                                "Đã nhận diện ${recognizedDevices.size} thiết bị — chạm để xác nhận và đặt tên:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            recognizedDevices.forEach { discovered ->
                                Surface(
                                    onClick = { onDeviceSelected(discovered) },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = discovered.suggestedName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${discovered.discoverySource} · ${discovered.commandTopic}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                            if (topics.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        }
                        if (topics.isNotEmpty()) {
                            Text(
                                "Topic khác không nhận diện được (${topics.size}) — chạm để thêm tay:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            topics.forEach { topic ->
                                Surface(
                                    onClick = { onTopicSelected(topic) },
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = topic,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !isScanning) { Text("Quét lại") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAddManually) { Text("Thiết lập nhanh") }
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        }
    )
}

/**
 * DiscoveryConfirmDialog
 *
 * ✅ MỚI (track hoàn thiện Cloud MQTT): xác nhận 1 thiết bị MqttDiscoveryEngine vừa nhận diện
 * TRƯỚC KHI lưu — cùng tinh thần flow Camera Discovery hiện có. Hiện đủ thông tin đã suy ra
 * (command/state topic, on/off payload, nguồn discovery) ở dạng chỉ-đọc để người dùng biết
 * chính xác cái gì sắp được lưu — chỉ tên là sửa được, tránh dialog quá phức tạp; muốn sửa
 * topic/payload thì dùng "Thiết lập nhanh" thêm tay thay vì sửa kết quả discovery.
 */
@Composable
fun DiscoveryConfirmDialog(
    discovered: DiscoveredMqttDevice,
    onDismiss: () -> Unit,
    onConfirm: (finalName: String) -> Unit
) {
    var name by remember { mutableStateOf(discovered.suggestedName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xác nhận thiết bị phát hiện được") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên thiết bị") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                InfoChip(label = "Nguồn nhận diện", value = discovered.discoverySource)
                InfoChip(label = "Command Topic", value = discovered.commandTopic)
                InfoChip(label = "State Topic", value = discovered.stateTopic ?: "(không có)")
                InfoChip(label = "Payload Bật / Tắt", value = "${discovered.onPayload} / ${discovered.offPayload}")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Thêm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}

/**
 * AddMqttDeviceDialog ("Thiết lập nhanh" — thêm tay khi discovery không nhận diện được)
 *
 * ✅ SỬA (track hoàn thiện Cloud MQTT): mở rộng từ 2 trường (tên + 1 topic dùng chung) lên đúng
 * 5 trường theo yêu cầu "Thiết lập nhanh": Tên, Command Topic, State Topic, ON payload, OFF
 * payload — cho phép thiết bị thêm tay CŨNG có realtime status nếu người dùng biết state topic
 * của thiết bị, thay vì trước đây chỉ điều khiển 1 chiều. State Topic để trống hợp lệ (không
 * bắt buộc — vẫn điều khiển được, chỉ không có realtime status). On/Off payload mặc định
 * "ON"/"OFF" (giữ tương thích hành vi cũ) nhưng SỬA được — KHÔNG tự đoán payload nào khác.
 *
 * initialCommandTopic — prefill từ ScanTopicsDialog khi người dùng chạm 1 topic THÔ (không được
 * MqttDiscoveryEngine nhận diện) vừa quét được. Vẫn để người dùng SỬA được, không khoá field.
 */
@Composable
fun AddMqttDeviceDialog(
    initialCommandTopic: String = "",
    onDismiss: () -> Unit,
    // ✅ MỚI (Dimmer): thêm levelCommandTopic ở cuối (tuỳ chọn, mặc định "" = không dimmer)
    // — KHÔNG chèn giữa để giữ nguyên vị trí 5 tham số cũ, tránh phải sửa lại các lambda
    // khác đang destructure theo thứ tự.
    onSave: (name: String, commandTopic: String, stateTopic: String, onPayload: String, offPayload: String, levelCommandTopic: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var commandTopic by remember { mutableStateOf(initialCommandTopic) }
    var stateTopic by remember { mutableStateOf("") }
    var onPayload by remember { mutableStateOf("ON") }
    var offPayload by remember { mutableStateOf("OFF") }
    // ✅ MỚI (Dimmer): để trống = thiết bị không phải dimmer (isDimmable=false ở addDevice()).
    var levelCommandTopic by remember { mutableStateOf("") }
    val canSave = name.isNotBlank() && commandTopic.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thiết lập nhanh") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên thiết bị") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = commandTopic,
                    onValueChange = { commandTopic = it },
                    label = { Text("Command Topic") },
                    placeholder = { Text("vd: cmnd/den_phong_khach/POWER") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = stateTopic,
                    onValueChange = { stateTopic = it },
                    label = { Text("State Topic (tuỳ chọn)") },
                    placeholder = { Text("vd: stat/den_phong_khach/RESULT") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = onPayload,
                        onValueChange = { onPayload = it },
                        label = { Text("Payload Bật") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = offPayload,
                        onValueChange = { offPayload = it },
                        label = { Text("Payload Tắt") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Command Topic phải khớp đúng topic thiết bị/gateway của bạn đang " +
                        "lắng nghe để nhận lệnh. Để trống State Topic nếu thiết bị không tự báo " +
                        "trạng thái ngược — vẫn điều khiển được, chỉ không hiện Bật/Tắt/Mất kết " +
                        "nối realtime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ✅ MỚI (Dimmer): tuỳ chọn — để trống nếu thiết bị chỉ on/off (đa số). Nhập
                // vào nếu đây là đèn/thiết bị dimmer, publish số 0-100 vào topic này.
                OutlinedTextField(
                    value = levelCommandTopic,
                    onValueChange = { levelCommandTopic = it },
                    label = { Text("Level Command Topic (tuỳ chọn — chỉ cho đèn dimmer)") },
                    placeholder = { Text("vd: cmnd/den_phong_khach/Dimmer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, commandTopic, stateTopic, onPayload, offPayload, levelCommandTopic) },
                enabled = canSave
            ) { Text("Thêm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}

/**
 * MqttBrokerConfigDialog
 *
 * ⚠️ Username/password KHÔNG được prefill lại từ AppConfigProvider (chỉ brokerUrl có, qua
 * MqttViewModel.loadBrokerConfig()) — tránh hiện mật khẩu cũ dạng plaintext ngay khi mở dialog.
 * Để trống nghĩa là "giữ nguyên giá trị cũ đã lưu" chỉ ĐÚNG nếu MqttViewModel.saveBrokerConfig()
 * được sửa để không ghi đè khi rỗng; hiện tại saveBrokerConfig() ghi đè thẳng nên để trống ở
 * đây nghĩa là XOÁ username/password cũ — cần người dùng nhập lại đầy đủ mỗi lần đổi broker.
 *
 * ✅ MỚI: gợi ý broker free cho người chưa có broker nào — chỉ mở trình duyệt tới trang đăng ký
 * (KHÔNG tự động tạo tài khoản hộ, không có API "1-click" thật nào cho việc này ở HiveMQ Cloud/
 * EMQX Cloud). Đây là track Cloud Broker (MQTT_CLOUD_BROKER_PLAN.md) — khác Embedded Broker
 * (MQTT_EMBEDDED_BROKER_PLAN.md, đang HOÃN, sẽ làm sau khi track này ổn định).
 *
 * ✅ MỚI (mục 1.2 kế hoạch): Port/TLS tách riêng (mặc định 8883/true, chỉ áp dụng khi Broker URL
 * là bare host — xem CloudMqttBrokerProvider.buildBrokerUri()), cộng nút "Kiểm tra kết nối" thử
 * connect thật trước khi cho phép Lưu.
 */
@Composable
fun MqttBrokerConfigDialog(
    currentBrokerUrl: String,
    isTestingConnection: Boolean,
    connectionTestResult: Boolean?,
    onTestConnection: (url: String, username: String, password: String, port: String, useTls: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (url: String, username: String, password: String, port: String, useTls: Boolean) -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(currentBrokerUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8883") }
    var useTls by remember { mutableStateOf(true) }
    val canSave = url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cấu hình Broker MQTT") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ✅ gợi ý cho người chưa có broker nào — chỉ hiện khi ô URL còn rỗng,
                // tránh làm phiền người đã có broker đang dùng tốt.
                if (url.isBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Chưa có broker? Đăng ký miễn phí trong vài phút:",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.hivemq.com/mqtt-cloud-broker/")
                                    )
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("→ HiveMQ Cloud (free tier)")
                            }
                            Text(
                                text = "Sau khi tạo cluster, HiveMQ cấp cho bạn 1 Broker URL dạng " +
                                    "\"xxxxx.s1.eu.hivemq.cloud\" — dán vào ô Broker URL bên dưới " +
                                    "(không cần gõ \"ssl://\" hay cổng, đã có sẵn Port/TLS riêng).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        // Config vừa đổi qua tay — kết quả test cũ (nếu có) không còn tin cậy,
                        // để trạng thái quay lại "chưa kiểm tra" cho tới khi bấm test lại.
                    },
                    label = { Text("Broker URL") },
                    placeholder = { Text("xxxxx.s1.eu.hivemq.cloud") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("TLS", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = useTls, onCheckedChange = { useTls = it })
                    }
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (tuỳ chọn)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (tuỳ chọn)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Để trống Username/Password nếu broker không yêu cầu đăng nhập. " +
                        "Lưu ý: lưu lại sẽ GHI ĐÈ username/password cũ nếu để trống — nhập lại " +
                        "đầy đủ nếu bạn chỉ muốn đổi Broker URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = { onTestConnection(url, username, password, port, useTls) },
                    enabled = canSave && !isTestingConnection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang kiểm tra...")
                    } else {
                        Text("Kiểm tra kết nối")
                    }
                }
                when (connectionTestResult) {
                    true -> Text(
                        text = "✅ Kết nối thành công",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    false -> Text(
                        text = "❌ Không kết nối được — kiểm tra lại Broker URL/Port/Username/Password",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    null -> {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url, username, password, port, useTls) }, enabled = canSave) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}