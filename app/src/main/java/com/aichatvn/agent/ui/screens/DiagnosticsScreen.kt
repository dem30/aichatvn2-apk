package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.skills.CameraSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val cameraSkill: CameraSkill
) : ViewModel() {
    private val _stats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val stats: StateFlow<Map<String, Any>> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _stats.value = cameraSkill.getDiagnostics()
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
    val stats by viewModel.stats.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Chẩn đoán hệ thống") }) }) { padding ->
        if (stats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Chưa có dữ liệu. Camera chưa được quét.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stats.entries.toList(), key = { it.key }) { (cameraId, data) ->
                    @Suppress("UNCHECKED_CAST")
                    val cameraStats = data as? Map<String, Any> ?: return@items
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Camera: $cameraId", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            StatRow("Mẫu học", "${cameraStats["samples"]}")
                            StatRow("Sự kiện thật", "${cameraStats["realEvents"]}")
                            StatRow("Ngưỡng delta", "${cameraStats["deltaTrigger"]}")
                            StatRow("Ngưỡng diff", "${cameraStats["absDiffTrigger"]}")
                            StatRow("Baseline size", "${cameraStats["baselineSize"]}")
                            val inCooldown = cameraStats["inCooldown"] as? Boolean ?: false
                            if (inCooldown) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text("⏳ Đang cooldown", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall)
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
