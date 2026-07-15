package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.data.model.CustomerSettingEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.ui.dashboard.DeviceRegistry
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class CustomerCameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    private val cameraSkill: CameraSkill,
    private val deviceRegistry: DeviceRegistry,
    private val logger: Logger
) : ViewModel() {

    val customerId: String = (savedStateHandle.get<String>("customerId") ?: "").trim()

    private val _customer = MutableStateFlow<CustomerEntity?>(null)
    val customer: StateFlow<CustomerEntity?> = _customer.asStateFlow()

    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras: StateFlow<List<CameraConfigEntity>> = _cameras.asStateFlow()

    private val _masterSmartMode = MutableStateFlow(false)
    val masterSmartMode: StateFlow<Boolean> = _masterSmartMode.asStateFlow()

    private val _cameraSmartModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val cameraSmartModes: StateFlow<Map<String, Boolean>> = _cameraSmartModes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    val cust = database.customerDao().getCustomerById(customerId)
                    val cams = database.cameraDao().getCamerasByCustomer(customerId)
                    val setting = database.cameraDao().getCustomerSetting(customerId)
                    
                    if (setting == null && customerId.isNotEmpty()) {
                        database.cameraDao().insertCustomerSetting(
                            CustomerSettingEntity(
                                customerId = customerId,
                                smartMode = 0,
                                isActive = 1,
                                updatedAt = System.currentTimeMillis(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }

                    _customer.value = cust
                    _cameras.value = cams
                    _masterSmartMode.value = setting?.smartMode == 1
                    _cameraSmartModes.value = cams.associate { it.id to (it.smartMode == 1) }
                }
            } catch (e: Exception) {
                logger.e("CustomerCameraViewModel", "Lỗi tải cấu hình: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveCamera(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                cameraSkill.saveCameraConfig(config)
            }
            load()
            _isLoading.value = false
        }
    }

    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            val trimmedId = cameraId.trim()
            withContext(Dispatchers.IO) {
                cameraSkill.deleteCamera(trimmedId)
            }
            // ✅ MỚI: Gỡ node khỏi Dashboard ngay lập tức, không đợi lần refreshDashboardNodes() kế tiếp
            deviceRegistry.unregisterNode(trimmedId)
            load()
        }
    }

    fun toggleCameraActive(cameraId: String) {
        viewModelScope.launch {
            val trimmedId = cameraId.trim()
            val camera = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(trimmedId)
            } ?: return@launch
            
            val newManualOff = if (camera.manualOff == 0) 1 else 0
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCamera(camera.copy(manualOff = newManualOff))
            }
            load()
        }
    }

    fun toggleMasterSmartMode() {
        viewModelScope.launch {
            val newMode = !_masterSmartMode.value
            withContext(Dispatchers.IO) {
                cameraSkill.execute("set_smart_mode", mapOf("customerId" to customerId, "enabled" to newMode))
            }
            _masterSmartMode.value = newMode
            logger.i("CustomerCameraViewModel", "MasterSmartMode $customerId → $newMode")
        }
    }

    fun toggleCameraSmartMode(cameraId: String) {
        viewModelScope.launch {
            val trimmedId = cameraId.trim()
            val current = _cameraSmartModes.value[trimmedId] ?: true
            val newMode = !current
            withContext(Dispatchers.IO) {
                database.cameraDao().updateCameraSmartMode(trimmedId, if (newMode) 1 else 0)
            }
            _cameraSmartModes.value = _cameraSmartModes.value.toMutableMap().apply { put(trimmedId, newMode) }
            logger.i("CustomerCameraViewModel", "CameraSmartMode $trimmedId → $newMode")
        }
    }

    fun testCamera(cameraId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _testResult.value = null
            
            val trimmedId = cameraId.trim()
            val camera = withContext(Dispatchers.IO) {
                database.cameraDao().getCameraById(trimmedId)
            }
            val setting = camera?.let {
                withContext(Dispatchers.IO) {
                    database.cameraDao().getCustomerSetting(it.customerId)
                }
            }
            val wasSmartOff = setting?.smartMode != 1
            
            try {
                if (wasSmartOff && camera != null) {
                    withContext(Dispatchers.IO) {
                        database.cameraDao().insertCustomerSetting(
                            CustomerSettingEntity(
                                customerId = camera.customerId,
                                smartMode = 1,
                                isActive = setting?.isActive ?: 1,
                                updatedAt = System.currentTimeMillis(),
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
                
                // ✅ SỬA: bỏ qua Circuit Breaker khi người dùng chủ động bấm Test, tránh
                // bị skip âm thầm rồi hiển thị nhầm "Bình thường" khi results rỗng.
                cameraSkill.resetCircuitBreaker(trimmedId)

                val result = cameraSkill.scanCamera(trimmedId, isDailyReport = false)
                
                when (result) {
                    is com.aichatvn.agent.core.AgentKernel.PluginResult.Success -> {
                        val data = result.data as? Map<*, *>
                        val results = data?.get("results") as? List<*>
                        val first = results?.firstOrNull() as? Map<*, *>
                        val fetchError = first?.get("error") as? String
                        if (first == null) {
                            val skippedCb = (data?.get("skippedCircuitBreaker") as? Int) ?: 0
                            val skippedInactive = (data?.get("skippedInactive") as? Int) ?: 0
                            _testResult.value = when {
                                skippedCb > 0 -> "⛔ Camera đang bị tạm ngưng do lỗi kết nối liên tiếp trước đó. Vui lòng bấm Test lại."
                                skippedInactive > 0 -> "⏸️ Camera hoặc khách hàng đang tắt theo dõi, nên không quét."
                                else -> "❌ Không nhận được kết quả quét từ camera"
                            }
                        } else if (fetchError != null) {
                            _testResult.value = "❌ Không thể chụp ảnh: $fetchError"
                        } else {
                            val hasChange = first?.get("hasChange") as? Boolean ?: false
                            val isSuspicious = first?.get("isSuspicious") as? Boolean ?: false
                            val aiComment = first?.get("aiComment") as? String ?: "Không có phân tích"
                            val diff = first?.get("diff") as? Int ?: 0
                            val deltaTrigger = first?.get("deltaTrigger") as? Int ?: 0
                            val absDiffTrigger = first?.get("absDiffTrigger") as? Int ?: 0
                            _testResult.value = buildString {
                                if (isSuspicious) append("⚠️ CẢNH BÁO! Email đã gửi!\n")
                                else if (hasChange) append("🔄 Có biến động nhưng AI đánh giá bình thường\n")
                                else append("✅ Bình thường\n")
                                append("━━━━━━━━━━━━━━━\n🤖 AI: $aiComment\n")
                                if (diff > 0) append("━━━━━━━━━━━━━━━\n📊 diff=$diff | delta=$deltaTrigger | absDiff=$absDiffTrigger")
                            }
                        }
                    }
                    is com.aichatvn.agent.core.AgentKernel.PluginResult.Failure ->
                        _testResult.value = "❌ ${result.error}"
                    else -> _testResult.value = "❌ Kết quả không xác định"
                }
            } catch (e: Exception) {
                _testResult.value = "❌ Exception: ${e.message}"
                logger.e("CustomerCameraViewModel", "testCamera error: ${e.message}", e)
            } finally {
                if (wasSmartOff && camera != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            database.cameraDao().insertCustomerSetting(
                                CustomerSettingEntity(
                                    customerId = camera.customerId,
                                    smartMode = 0,
                                    isActive = setting?.isActive ?: 1,
                                    updatedAt = System.currentTimeMillis(),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        logger.e("CustomerCameraViewModel", "Lỗi hạ smartMode thiết lập gốc", e)
                    }
                }
                _isLoading.value = false
            }
        }
    }

    fun clearTestResult() { _testResult.value = null }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerCameraScreen(
    navController: NavController,
    viewModel: CustomerCameraViewModel = hiltViewModel()
) {
    val customer by viewModel.customer.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val masterSmartMode by viewModel.masterSmartMode.collectAsState()
    val cameraSmartModes by viewModel.cameraSmartModes.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<CameraConfigEntity?>(null) }
    var showTestDialog by remember { mutableStateOf(false) }

    LaunchedEffect(testResult) { testResult?.let { showTestDialog = true } }

    if (showTestDialog && testResult != null) {
        AlertDialog(
            onDismissRequest = { showTestDialog = false; viewModel.clearTestResult() },
            title = { Text("📊 Kết quả kiểm tra") },
            text = { Text(testResult ?: "", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showTestDialog = false; viewModel.clearTestResult() }) { Text("Đóng") }
            }
        )
    }

    if (showAddDialog || selectedCamera != null) {
        CameraDialog(
            camera = selectedCamera,
            customer = customer,
            onDismiss = { showAddDialog = false; selectedCamera = null },
            onSave = { config ->
                val merged = config.toMutableMap().apply { put("customerId", viewModel.customerId.trim()) }
                viewModel.saveCamera(merged)
                showAddDialog = false; selectedCamera = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(customer?.name ?: "Camera khách hàng") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleMasterSmartMode() }) {
                        Icon(
                            if (masterSmartMode) Icons.Default.Psychology else Icons.Default.PsychologyAlt,
                            contentDescription = if (masterSmartMode) "Tắt AI (tổng)" else "Bật AI (tổng)",
                            tint = if (masterSmartMode) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Thêm camera")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (cameras.isEmpty()) {
                Text(
                    text = "Chưa có camera nào. Nhấn + để thêm.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cameras, key = { it.id }) { camera ->
                        val camSmartOn = cameraSmartModes[camera.id] ?: true
                        CameraCard(
                            camera = camera,
                            isAdmin = true,
                            isSmartMode = masterSmartMode && camSmartOn,
                            onEdit = { selectedCamera = camera },
                            onDelete = { viewModel.deleteCamera(camera.id) },
                            onToggleActive = { viewModel.toggleCameraActive(camera.id) },
                            onToggleSmartMode = { viewModel.toggleCameraSmartMode(camera.id) },
                            onTest = { viewModel.testCamera(camera.id) },
                            onOpenDetail = { navController.navigate("camera_detail/${camera.id}") }
                        )
                    }
                }
            }
        }
    }
}