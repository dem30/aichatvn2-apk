// File: com/aichatvn/agent/ui/screens/AutomationHubScreen.kt
package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.HouseManagerViewModel

/**
 * ✅ MỚI: Màn hình gộp "Thiết bị & Tự động hóa".
 *
 * Trước đây Quản gia AI, Thiết bị Tuya, Lịch, và Camera theo khách là 4 tab riêng ở bottom nav —
 * người dùng mới không phân biệt được sự khác nhau giữa chúng. Màn hình này gộp cả 4 lại dưới
 * một TabRow nội bộ, mỗi sub-tab vẫn gọi đúng composable gốc (HouseManagerScreen, TuyaScreen,
 * ScheduleScreen, CustomerScreen) — KHÔNG đổi logic hay ViewModel bên trong bất kỳ màn nào,
 * chỉ đổi cách chúng được truy cập.
 */
private enum class AutomationTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOUSE_MANAGER("Quản gia AI", Icons.Default.SmartToy),
    DEVICES("Thiết bị", Icons.Default.Devices),
    SCHEDULE("Lịch", Icons.Default.Schedule),
    CAMERAS("Camera theo khách", Icons.Default.People)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationHubScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(AutomationTab.HOUSE_MANAGER) }

    Column(modifier = modifier.fillMaxSize()) {
        // Sub-tab nội bộ — không phải TopAppBar riêng, để tránh 2 lớp thanh tiêu đề chồng nhau
        // (mỗi màn con bên dưới đã tự có TopAppBar của nó, trừ ScheduleScreen).
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            AutomationTab.entries.forEach { tab ->
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
                AutomationTab.HOUSE_MANAGER -> {
                    val houseViewModel: HouseManagerViewModel = hiltViewModel()
                    HouseManagerScreen(viewModel = houseViewModel, navController = navController)
                }
                AutomationTab.DEVICES -> TuyaScreen(navController = navController)
                AutomationTab.SCHEDULE -> ScheduleScreen(navController = navController)
                AutomationTab.CAMERAS -> CustomerScreen(navController = navController)
            }
        }
    }
}
