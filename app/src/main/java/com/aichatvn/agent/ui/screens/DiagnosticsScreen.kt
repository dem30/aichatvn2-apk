package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.aichatvn.agent.data.database.AppDatabase
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val cameraSkill: CameraSkill,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    private val _learningStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val learningStats: StateFlow<Map<String, Any>> = _learningStats.asStateFlow()
    
    private val _cameras = MutableStateFlow<List<CameraConfigEntity>>(emptyList())
    val cameras: StateFlow<List<CameraConfigEntity>> = _cameras.asStateFlow()
    
    val combinedStats: StateFlow<Map<String, Any>> = combine(_learningStats, _cameras) { stats, cameras ->
        mapOf(
            "learningStats" to stats,
            "cameras" to cameras.map { camera ->
                val status = when {
                    camera.manualOff == 1 -> "Đã tắt"
                    camera.isOnline != 1 -> "Mất kết nối"
                    else -> "Hoạt động"
                }
                mapOf(
                    "id" to camera.id,
                    "name" to camera.customername,
                    "customerId" to camera.customerId,
                    "isOnline" to (camera.isOnline == 1),
                    "manualOff" to (camera.manualOff == 1),
                    "status" to status
                )
            },
            "totalCameras" to cameras.size,
            "onlineCameras" to cameras.count { it.isOnline == 1 && it.manualOff == 0 },
            "offlineCameras" to cameras.count { it.isOnline != 1 && it.manualOff == 0 },
            "disabledCameras" to cameras.count { it.manualOff == 1 }
        )
    }.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _learningStats.value = cameraSkill.getDiagnostics()
                _cameras.value = database.cameraDao().getActiveCameras()
                delay(5000)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val stats by viewModel.combinedStats.collectAsState()
    val learningStats = stats["learningStats"] as? Map<String, Any> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val cameras = stats["cameras"] as? List<Map<String, Any>> ?: emptyList()
    val totalCameras = stats["totalCameras"] as? Int ?: 0
    val onlineCameras = stats["onlineCameras"] as? Int ?: 0
    val offlineCameras = stats["offlineCameras"] as? Int ?: 0
    val disabledCameras = stats["disabledCameras"] as? Int ?: 0

    Scaffold(topBar = { TopAppBar(title = { Text("Chẩn đoán hệ thống") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Summary cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📷 Tổng", style = MaterialTheme.typography.labelSmall)
                        Text("$totalCameras", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🟢 Online", style = MaterialTheme.typography.labelSmall)
                        Text("$onlineCameras", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔴 Offline", style = MaterialTheme.typography.labelSmall)
                        Text("$offlineCameras", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("⛔ Tắt", style = MaterialTheme.typography.labelSmall)
                        Text("$disabledCameras", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            
            if (cameras.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📭", fontSize = TextUnit(48f, TextUnitType.Sp))
                        Spacer(Modifier.height(8.dp))
                        Text("Chưa có camera nào được cấu hình", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (learningStats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Đang tải thống kê học tập...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cameras, key = { it["id"] as? String ?: "" }) { camera ->
                        val cameraId = camera["id"] as? String ?: ""
                        val cameraName = camera["name"] as? String ?: ""
                        val cameraStatus = camera["status"] as? String ?: "Unknown"
                        val cameraStats = learningStats[cameraId] as? Map<String, Any>
                        
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cameraName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = when (cameraStatus) {
                                            "Hoạt động" -> MaterialTheme.colorScheme.primaryContainer
                                            "Mất kết nối" -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.secondaryContainer
                                        }
                                    ) {
                                        Text(
                                            text = cameraStatus,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                
                                Text(
                                    text = "ID: $cameraId",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(Modifier.height(8.dp))
                                
                                if (cameraStats != null) {
                                    StatRow("Mẫu học", "${cameraStats["samples"] ?: 0}")
                                    StatRow("Sự kiện thật", "${cameraStats["realEvents"] ?: 0}")
                                    StatRow("Ngưỡng delta", "${cameraStats["deltaTrigger"] ?: 10}")
                                    StatRow("Ngưỡng diff", "${cameraStats["absDiffTrigger"] ?: 18}")
                                    StatRow("Baseline size", "${cameraStats["baselineSize"] ?: 0}")
                                    
                                    val inCooldown = cameraStats["inCooldown"] as? Boolean ?: false
                                    if (inCooldown) {
                                        Spacer(Modifier.height(4.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                "⏳ Đang cooldown",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    
                                    val circuitBreakerOpen = cameraStats["circuitBreakerOpen"] as? Boolean ?: false
                                    if (circuitBreakerOpen) {
                                        Spacer(Modifier.height(4.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                "⚠️ Circuit Breaker OPEN",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "Chưa có dữ liệu học tập",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}