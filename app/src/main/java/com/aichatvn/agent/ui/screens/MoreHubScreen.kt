// File: com/aichatvn/agent/ui/screens/MoreHubScreen.kt
package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController

/**
 * ✅ MỚI: Màn hình gộp "Khác" — chứa Cài đặt hệ thống và Huấn luyện AI.
 *
 * Trước đây "Huấn luyện" và "Cài đặt" là 2 tab riêng ở bottom nav; "Nhật ký" (Logs) cũng là
 * một tab riêng dù chỉ dành cho việc gỡ lỗi. Gộp Training + Settings vào đây, và loại Nhật ký/
 * Diagnostics/Pipeline Graph khỏi bottom nav hoàn toàn — chúng chỉ còn truy cập được qua công
 * tắc "Chế độ nhà phát triển" bên trong SettingsScreen.
 */
private enum class MoreTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SETTINGS("Cài đặt", Icons.Default.Settings),
    TRAINING("Huấn luyện AI", Icons.Default.School)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreHubScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(MoreTab.SETTINGS) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            MoreTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(tab.icon, contentDescription = null) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                MoreTab.SETTINGS -> SettingsScreen(navController = navController)
                MoreTab.TRAINING -> TrainingScreen(navController)
            }
        }
    }
}
