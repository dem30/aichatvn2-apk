package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
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
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.ScheduleEntity
import com.aichatvn.agent.skills.SmartSwitchSkill
import com.aichatvn.agent.skills.TuyaManager
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class TuyaViewModel @Inject constructor(
    private val database: AppDatabase,
    private val tuyaManager: TuyaManager,
    private val smartSwitchSkill: SmartSwitchSkill,
    private val deviceRegistry: DeviceRegistry,
    private val configProvider: AppConfigProvider,
    // ✅ SỬA: cần AgentKernel để đi qua IntentExecutor.checkDeviceWorldStateGuard() —
    // gọi thẳng smartSwitchSkill.execute() trước đây khiến khóa an toàn (Precondition
    // Guard) bị bỏ qua hoàn toàn khi bật/tắt bằng nút trên màn hình Tuya.
    private val agentKernel: AgentKernel,
    private val logger: Logger
) : ViewModel() {

    private val _devices = MutableStateFlow<List<TuyaDeviceEntity>>(emptyList())
    val devices: StateFlow<List<TuyaDeviceEntity>> = _devices.asStateFlow()

    private val _activeCameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val activeCameras: StateFlow<List<CameraConfigEntity>> = _activeCameras.asStateFlow()

    // ✅ MỚI: Tải danh sách tất cả lịch trình để ánh xạ tên hiển thị lên UI
    private val _allSchedules = MutableStateFlow<List<ScheduleEntity>>(emptyList())
    val allSchedules: StateFlow<List<ScheduleEntity>> = _allSchedules.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _loadingDevices = MutableStateFlow<Set<String>>(emptySet())
    val loadingDevices: StateFlow<Set<String>> = _loadingDevices.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _deviceGuards = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceGuards: StateFlow<Map<String, String>> = _deviceGuards.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                database.tuyaDeviceDao().getAllDevices()
            }
            val cams = withContext(Dispatchers.IO) {
                database.cameraDao().getActiveCameras()
            }
            val scheds = withContext(Dispatchers.IO) {
                database.scheduleDao().getAllSchedules()
            }
            _devices.value = list
            _activeCameras.value = cams
            _allSchedules.value = scheds
            loadDeviceGuards()
        }
    }

    fun loadDeviceGuards() {
        viewModelScope.launch {
            val guards = mutableMapOf<String, String>()
            _devices.value.forEach { dev ->
                val guardVal = configProvider.getString("worldstate_guard_${dev.id}", "")
                if (guardVal.isNotBlank()) {
                    guards[dev.id] = guardVal
                }
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

    fun scanDevices() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val found = withContext(Dispatchers.IO) { tuyaManager.scanDevices() }
                loadDevices()
                _message.value = "✅ Tìm thấy ${found.size} thiết bị"
            } catch (e: Exception) {
                _message.value = "❌ Lỗi quét: ${e.message}"
                logger.e("TuyaViewModel", "scanDevices error", e)
            } finally {
                _isScanning.value = false
            }
        }
    }



    

    fun toggleDevice(device: TuyaDeviceEntity, turnOn: Boolean) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                // ✅ SỬA (Bug A+B): dùng device.id (không phải device.name) làm "device" param,
                // và đi qua agentKernel.executePluginAction() thay vì gọi thẳng smartSwitchSkill —
                // để checkDeviceWorldStateGuard() được áp dụng nhất quán với đường chat/Dashboard,
                // và để guardKey ("worldstate_guard_${device.id}") khớp đúng với key đã lưu.
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
                logger.e("TuyaViewModel", "toggleDevice error", e)
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    fun refreshStatus(device: TuyaDeviceEntity) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                // Trạng thái đọc (status) không ghi world_state / không qua guard nên vẫn
                // dùng device.id cho nhất quán, gọi thẳng smartSwitchSkill là an toàn ở đây.
                val result = withContext(Dispatchers.IO) {
                    smartSwitchSkill.execute("status", mapOf("device" to device.id))
                }



                
                when (result) {
                    is PluginResult.Success -> loadDevices()
                    is PluginResult.Failure -> _message.value = "❌ ${result.error}"
                    else -> {}
                }
            } catch (e: Exception) {
                _message.value = "❌ Lỗi: ${e.message}"
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    fun deleteDevice(device: TuyaDeviceEntity) {
        viewModelScope.launch {
            _loadingDevices.value = _loadingDevices.value + device.id
            try {
                withContext(Dispatchers.IO) {
                    tuyaManager.deleteDevice(device.id)
                }
                deviceRegistry.unregisterNode(device.id)
                _message.value = "🗑️ Đã xoá thiết bị \"${device.name}\""
                loadDevices()
            } catch (e: Exception) {
                _message.value = "❌ Lỗi xoá thiết bị: ${e.message}"
                logger.e("TuyaViewModel", "deleteDevice error", e)
            } finally {
                _loadingDevices.value = _loadingDevices.value - device.id
            }
        }
    }

    fun clearMessage() { _message.value = null }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuyaScreen(
    navController: NavController,
    viewModel: TuyaViewModel = hiltViewModel()
) {
    val devices by viewModel.devices.collectAsState()
    val activeCameras by viewModel.activeCameras.collectAsState()
    val allSchedules by viewModel.allSchedules.collectAsState()
    val deviceGuards by viewModel.deviceGuards.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val loadingDevices by viewModel.loadingDevices.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var deviceToDelete by remember { mutableStateOf<TuyaDeviceEntity?>(null) }
    var guardTargetDevice by remember { mutableStateOf<TuyaDeviceEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (deviceToDelete != null) {
        val target = deviceToDelete!!
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Xoá thiết bị?") },
            text = { Text("Thiết bị \"${target.name}\" sẽ bị xoá khỏi danh sách và Dashboard. Bạn có thể quét lại để thêm lại nếu cần.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDevice(target)
                    deviceToDelete = null
                }) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) { Text("Huỷ") }
            }
        )
    }

    if (guardTargetDevice != null) {
        val target = guardTargetDevice!!
        PreconditionGuardDialog(
            targetDevice = target,
            currentPrecondition = deviceGuards[target.id] ?: "",
            activeCameras = activeCameras,
            allSchedules = allSchedules,
            tuyaDevices = devices,
            onDismiss = { guardTargetDevice = null },
            onSave = { precondition ->
                viewModel.saveDeviceGuard(target.id, precondition)
                guardTargetDevice = null
            },
            onDelete = {
                viewModel.removeDeviceGuard(target.id)
                guardTargetDevice = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thiết bị Tuya") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.scanDevices() }, enabled = !isScanning) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Quét thiết bị")
                        }
                    }
                    IconButton(onClick = { viewModel.loadDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Làm mới")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.scanDevices() },
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                text = { Text("Quét thiết bị") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            EmptyTuyaState(
                isScanning = isScanning,
                onScan = { viewModel.scanDevices() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "${devices.size} thiết bị",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(devices, key = { it.id }) { device ->
                    TuyaDeviceCard(
                        device = device,
                        isLoading = device.id in loadingDevices,
                        currentGuard = deviceGuards[device.id],
                        allSchedules = allSchedules, // ✅ Truyền kèm danh sách lịch để phân giải nhãn hiển thị thân thiện
                        onTurnOn = { viewModel.toggleDevice(device, true) },
                        onTurnOff = { viewModel.toggleDevice(device, false) },
                        onRefresh = { viewModel.refreshStatus(device) },
                        onDelete = { deviceToDelete = device },
                        onConfigureGuard = { guardTargetDevice = device }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun TuyaDeviceCard(
    device: TuyaDeviceEntity,
    isLoading: Boolean,
    currentGuard: String?,
    allSchedules: List<ScheduleEntity>,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onConfigureGuard: () -> Unit
) {
    // Bóc tách chuỗi khóa bảo vệ theo định dạng "camera.id.state_scheduleId=value"
    val friendlyGuardLabel = remember(currentGuard, allSchedules) {
        if (currentGuard.isNullOrBlank()) "" else {
            try {
                val segments = currentGuard.split(".")
                val source = segments.getOrNull(0) ?: ""
                val sourceId = segments.getOrNull(1) ?: "" // Sẽ chứa cameraId (vd: "cam123")

                val stateAndExpected = segments.getOrNull(2) ?: "" // Sẽ chứa "state_sch123=normal"
                val stateParts = stateAndExpected.split("=")
                val stateKey = stateParts.getOrNull(0) ?: "" // Sẽ chứa "state_sch123"
                val expected = stateParts.getOrNull(1) ?: "" // Sẽ chứa "normal"

                if (source == "camera" && stateKey.startsWith("state_")) {
                    val scheduleId = stateKey.removePrefix("state_")
                    val matchedSched = allSchedules.find { it.id == scheduleId }
                    val labelName = matchedSched?.label?.ifBlank { "Lịch ngầm" } ?: "Lịch ngầm"
                    val stateLabel = if (expected == "normal") "Bình thường" else "Cảnh báo"
                    "$labelName ($stateLabel)"
                } else {
                    currentGuard
                }
            } catch (_: Exception) {
                currentGuard
            }
        }
    }

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
                                if (device.online)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = categoryIcon(device.category),
                            fontSize = 22.sp
                        )
                    }
                    Column {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (device.productName.isNotBlank()) {
                            Text(
                                text = device.productName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (device.online) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        contentColor = if (device.online) Color(0xFF2E7D32) else Color(0xFFC62828),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (device.online) "ONLINE" else "OFFLINE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

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

            if (!currentGuard.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Khóa bảo vệ: $friendlyGuardLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip(label = "Loại", value = device.category.ifBlank { "—" })
                InfoChip(label = "ID", value = device.id.take(12) + "…")
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

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
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Trạng thái", maxLines = 1)
                    }
                }
            }
        }
    }
}

// Hộp thoại Pop-up thiết lập khóa an toàn có phân tách lịch trình camera thông minh
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreconditionGuardDialog(
    targetDevice: TuyaDeviceEntity,
    currentPrecondition: String,
    activeCameras: List<CameraConfigEntity>,
    allSchedules: List<ScheduleEntity>,
    tuyaDevices: List<TuyaDeviceEntity>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    val initialSegments = currentPrecondition.split(".")
    val initialSource = initialSegments.getOrNull(0) ?: "camera"
    val initialSourceId = initialSegments.getOrNull(1) ?: ""

    // Đọc giá trị yêu cầu từ vị trí segment thứ 3
    val initialExpected = initialSegments.getOrNull(2)?.substringAfter("=") ?: "normal"

    var selectedSourceType by remember { mutableStateOf(initialSource) }
    var selectedCameraId by remember { mutableStateOf(if (initialSource == "camera") initialSourceId else activeCameras.firstOrNull()?.id ?: "") }
    var selectedTuyaId by remember { mutableStateOf(if (initialSource == "tuya") initialSourceId else tuyaDevices.firstOrNull { it.id != targetDevice.id }?.id ?: "") }

    // Lọc chính xác theo tham số ID của Camera trong map params của Lịch trình
    val cameraSchedules = remember(selectedCameraId, allSchedules) {
        allSchedules.filter { sched ->
            if (sched.pluginId != "camera") {
                false
            } else {
                // params là chuỗi JSON (xem ScheduleEntity.params: String), không phải Map,
                // nên phải parse JSON để lấy đúng trường "cameraId" thay vì dùng toán tử Map.
                try {
                    org.json.JSONObject(sched.params).optString("cameraId") == selectedCameraId
                } catch (e: Exception) {
                    sched.params.contains(selectedCameraId)
                }
            }
        }
    }

    // Phục hồi chính xác Lịch trình đã chọn từ chuỗi cấu hình cũ
    val initialScheduleId = remember(currentPrecondition, cameraSchedules) {
        if (initialSource == "camera") {
            val stateSegment = initialSegments.getOrNull(2) ?: ""
            val stateKey = stateSegment.substringBefore("=")
            if (stateKey.startsWith("state_")) {
                stateKey.removePrefix("state_")
            } else {
                cameraSchedules.firstOrNull()?.id ?: ""
            }
        } else {
            ""
        }
    }
    var selectedScheduleId by remember { mutableStateOf(initialScheduleId) }

    var expectedValue by remember { mutableStateOf(initialExpected) }

    var cameraExpanded by remember { mutableStateOf(false) }
    var tuyaExpanded by remember { mutableStateOf(false) }
    var scheduleExpanded by remember { mutableStateOf(false) }
    var expectedExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 Thiết lập Khóa an toàn", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Thiết bị '${targetDevice.name}' sẽ chỉ được phép Bật/Tắt khi điều kiện thực tế dưới đây thỏa mãn:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("1. Loại thiết bị điều kiện", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = selectedSourceType == "camera",
                        onClick = {
                            selectedSourceType = "camera"
                            expectedValue = "normal"
                        },
                        label = { Text("Camera") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedSourceType == "tuya",
                        onClick = {
                            selectedSourceType = "tuya"
                            expectedValue = "true"
                        },
                        label = { Text("Ổ cắm Tuya") },
                        modifier = Modifier.weight(1f)
                    )
                }



                



                if (selectedSourceType == "camera") {
    Text("2. Chọn Camera", style = MaterialTheme.typography.labelMedium)
    ExposedDropdownMenuBox(
        expanded = cameraExpanded,
        onExpandedChange = { cameraExpanded = it }
    ) {
        // 🛠️ SỬA: Hiển thị cả tên khách hàng và mã camera ID tại ô chọn chính
        val cameraName = activeCameras.find { it.id == selectedCameraId }?.let { cam ->
            "${cam.customername} (${cam.id})"
        } ?: selectedCameraId

        OutlinedTextField(
            value = cameraName.ifBlank { "Chọn camera..." },
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = cameraExpanded,
            onDismissRequest = { cameraExpanded = false }
        ) {
            activeCameras.forEach { cam ->
                DropdownMenuItem(
                    // 🛠️ SỬA: Hiển thị kết hợp cả tên khách hàng và mã camera ID trong danh sách xổ xuống
                    text = { Text("${cam.customername} (${cam.id})") },
                    onClick = {
                        selectedCameraId = cam.id
                        cameraExpanded = false
                    }
                )
            }
        }
    }
    




                    

                    if (cameraSchedules.isNotEmpty()) {
                        Text("3. Chọn Lịch trình kiểm tra", style = MaterialTheme.typography.labelMedium)
                        ExposedDropdownMenuBox(
                            expanded = scheduleExpanded,
                            onExpandedChange = { scheduleExpanded = it }
                        ) {
                            val scheduleName = cameraSchedules.find { it.id == selectedScheduleId }?.label?.ifBlank { "Lịch không tên" } ?: "Chọn lịch trình..."
                            OutlinedTextField(
                                value = scheduleName,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scheduleExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = scheduleExpanded,
                                onDismissRequest = { scheduleExpanded = false }
                            ) {
                                cameraSchedules.forEach { sched ->
                                    val itemLabel = sched.label.ifBlank { "Lịch không tên (${sched.id.take(4)})" }
                                    DropdownMenuItem(
                                        text = { Text(itemLabel) },
                                        onClick = {
                                            selectedScheduleId = sched.id
                                            scheduleExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "⚠️ Camera này chưa cấu hình lịch quét. Trạng thái sẽ được kiểm tra chung theo camera.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text("2. Chọn Ổ cắm thông minh", style = MaterialTheme.typography.labelMedium)
                    ExposedDropdownMenuBox(
                        expanded = tuyaExpanded,
                        onExpandedChange = { tuyaExpanded = it }
                    ) {
                        val devName = tuyaDevices.find { it.id == selectedTuyaId }?.name ?: selectedTuyaId
                        OutlinedTextField(
                            value = devName.ifBlank { "Chọn thiết bị..." },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tuyaExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = tuyaExpanded,
                            onDismissRequest = { tuyaExpanded = false }
                        ) {
                            val otherTuyaDevices = tuyaDevices.filter { it.id != targetDevice.id }
                            if (otherTuyaDevices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("⚠️ Không có ổ cắm Tuya nào khác để chọn (không thể chọn chính thiết bị đang cấu hình)") },
                                    onClick = {},
                                    enabled = false
                                )
                            } else {
                                otherTuyaDevices.forEach { dev ->
                                    DropdownMenuItem(
                                        text = { Text(dev.name) },
                                        onClick = {
                                            selectedTuyaId = dev.id
                                            tuyaExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Trạng thái yêu cầu", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(
                    expanded = expectedExpanded,
                    onExpandedChange = { expectedExpanded = it }
                ) {
                    val displayExpected = if (selectedSourceType == "camera") {
                        if (expectedValue == "normal") "Bình thường (normal)" else "Cảnh báo (suspicious)"
                    } else {
                        if (expectedValue == "true") "Đang bật (true)" else "Đang tắt (false)"
                    }

                    OutlinedTextField(
                        value = displayExpected,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expectedExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expectedExpanded,
                        onDismissRequest = { expectedExpanded = false }
                    ) {
                        if (selectedSourceType == "camera") {
                            DropdownMenuItem(
                                text = { Text("Bình thường (normal)") },
                                onClick = { expectedValue = "normal"; expectedExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Cảnh báo (suspicious)") },
                                onClick = { expectedValue = "suspicious"; expectedExpanded = false }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Đang bật (true)") },
                                onClick = { expectedValue = "true"; expectedExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Đang tắt (false)") },
                                onClick = { expectedValue = "false"; expectedExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (currentPrecondition.isNotBlank()) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Gỡ khóa")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) { Text("Hủy") }
                Button(
                    onClick = {
                        val finalPrecondition = if (selectedSourceType == "camera") {
                            if (selectedScheduleId.isNotBlank()) {
                                "camera.$selectedCameraId.state_$selectedScheduleId=$expectedValue"
                            } else {
                                "camera.$selectedCameraId.state=$expectedValue"
                            }
                        } else {
                            "tuya.$selectedTuyaId.state=$expectedValue"
                        }
                        onSave(finalPrecondition)
                    },
                    enabled = (selectedSourceType == "camera" && selectedCameraId.isNotBlank()) ||
                              (selectedSourceType == "tuya" && selectedTuyaId.isNotBlank())
                ) {
                    Text("Lưu")
                }
            }
        }
    )
}


@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyTuyaState(
    isScanning: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔌", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Chưa có thiết bị Tuya",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Nhấn Quét để tìm thiết bị trong tài khoản Tuya",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan, enabled = !isScanning) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Đang quét…")
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Quét thiết bị")
            }
        }
    }
}

private fun categoryIcon(category: String): String = when (category.lowercase()) {
    "dj", "dj2"       -> "💡"
    "kg", "cz"        -> "🔌"
    "fs"               -> "🌀"
    "cl"               -> "🪟"
    "wk"               -> "🌡️"
    "gw"               -> "📡"
    else               -> "⚡"
}