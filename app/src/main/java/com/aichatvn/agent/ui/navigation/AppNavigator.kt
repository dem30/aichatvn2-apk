package com.aichatvn.agent.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aichatvn.agent.R
import com.aichatvn.agent.ui.screens.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichatvn.agent.ui.viewmodels.NavBadgeViewModel
import com.aichatvn.agent.ui.viewmodels.HouseManagerViewModel
import com.aichatvn.agent.ui.viewmodels.CallViewModel
import com.aichatvn.agent.skills.CallState

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Dashboard    : Screen("dashboard",   R.string.tab_dashboard,   Icons.Default.Dashboard)
    object Chat         : Screen("chat_screen", R.string.tab_chat,        Icons.Default.Chat)
    object Customer     : Screen("customer",    R.string.tab_camera,      Icons.Default.People)
    object Training     : Screen("training",    R.string.tab_training,    Icons.Default.School)
    object Schedule     : Screen("schedule",    R.string.tab_schedule,    Icons.Default.Schedule)
    object Logs         : Screen("logs",        R.string.tab_logs,        Icons.Default.BugReport)
    object Settings     : Screen("settings",    R.string.tab_settings,    Icons.Default.Settings)
    object Tuya         : Screen("tuya",        R.string.tab_settings,    Icons.Default.Devices)

    object HouseManager : Screen("house_manager", R.string.tab_house_manager, Icons.Default.SmartToy)

    // ✅ MỚI: Tab gộp "Thiết bị & Tự động hóa" — container chứa HouseManager/Tuya/Schedule/Customer
    // dưới dạng sub-tab nội bộ, để bottom nav chỉ còn 4 mục thay vì 7. Các route con (house_manager,
    // tuya, schedule, customer) VẪN giữ nguyên và có thể điều hướng trực tiếp tới như trước — màn
    // hình gốc của chúng không đổi, chỉ đổi cách chúng được truy cập từ bottom nav.
    object Automation   : Screen("automation", R.string.tab_automation, Icons.Default.SmartToy)

    // ✅ MỚI: Tab gộp "Khác" — chứa Cài đặt, Huấn luyện AI, và (khi bật Chế độ nhà phát triển) Nhật ký
    object More         : Screen("more", R.string.tab_more, Icons.Default.MoreHoriz)

    companion object {
        const val INBOX_ROUTE = "inbox"
        const val DIAGNOSTICS_ROUTE = "diagnostics"
        const val PIPELINE_GRAPH_ROUTE = "pipeline_graph"
        const val DIAL_ROUTE = "dial"
        const val CALL_ROUTE = "call"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigator(
    pendingDeepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Điều hướng Deep Link
    LaunchedEffect(pendingDeepLinkRoute) {
        if (pendingDeepLinkRoute != null) {
            navController.navigate(pendingDeepLinkRoute) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    val navBadgeViewModel: NavBadgeViewModel = hiltViewModel()
    val totalUnreadCount by navBadgeViewModel.totalUnreadCount.collectAsState()

    val callViewModel: CallViewModel = hiltViewModel()
    val callState by callViewModel.callUiState.collectAsState()

    // Tự động bật CallScreen khi có cuộc gọi đến (RINGING)
    LaunchedEffect(callState.state) {
        if (callState.state == CallState.RINGING && currentRoute != Screen.CALL_ROUTE) {
            navController.navigate(Screen.CALL_ROUTE) { launchSingleTop = true }
        }
    }

    // ✅ Bottom nav rút gọn từ 7 mục xuống 4: người mới chỉ thấy "Nhà của tôi / Trò chuyện /
    // Thiết bị & Tự động hóa / Khác" — các khái niệm kỹ thuật (Quản gia AI, Tuya, Lịch, Camera
    // theo khách, Huấn luyện, Cài đặt, Log) được gom bên trong 2 tab container thay vì bày hết ra.
    val screens = listOf(
        Screen.Dashboard,
        Screen.Chat,
        Screen.Automation,
        Screen.More
    )

    // Bọc toàn bộ Scaffold trong một Box để vẽ Overlay UI (Floating Call Bubble)
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(stringResource(screen.titleRes)) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                if (screen == Screen.Chat) {
                                    if (currentRoute != Screen.Chat.route) {
                                        navController.navigate(Screen.Chat.route) {
                                            popUpTo(Screen.Chat.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                } else if (currentRoute == screen.route) {
                                    navController.popBackStack(screen.route, inclusive = false)
                                } else {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                // ✅ SỬA (UX): trước đây mở app vào thẳng Chat — người mới thấy khung chat trống,
                // không có cảm giác "AI đang lo cho nhà mình". Đổi sang Dashboard làm màn khởi
                // động để khối tóm tắt "🏠 Nhà đang an toàn" là thứ đầu tiên họ thấy.
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(Screen.Dashboard.route)   { DashboardScreen(navController) }
                composable(Screen.Customer.route)    { CustomerScreen(navController) }
                composable(Screen.Training.route)    { TrainingScreen(navController) }
                composable(Screen.Schedule.route)    { ScheduleScreen(navController) }
                composable(Screen.Logs.route)        { LogScreen(navController) }
                composable(Screen.Settings.route)    { SettingsScreen(navController) }
                composable(Screen.Tuya.route)        { TuyaScreen(navController) }

                composable(Screen.HouseManager.route) {
                    val houseViewModel: HouseManagerViewModel = hiltViewModel()
                    HouseManagerScreen(viewModel = houseViewModel, navController = navController)
                }

                // ✅ MỚI: Container "Thiết bị & Tự động hóa" — TabRow nội bộ chuyển giữa 4 màn hình
                // gốc, không đổi logic bên trong bất kỳ màn nào trong số đó.
                composable(Screen.Automation.route) {
                    AutomationHubScreen(navController = navController)
                }

                // ✅ MỚI: Container "Khác" — chứa Cài đặt + Huấn luyện AI; Nhật ký/Diagnostics/
                // Pipeline chỉ xuất hiện khi bật "Chế độ nhà phát triển" trong Settings.
                composable(Screen.More.route) {
                    MoreHubScreen(navController = navController)
                }

                composable(Screen.Chat.route) { ChatScreen(navController, unreadInboxCount = totalUnreadCount) }
                composable(Screen.INBOX_ROUTE) { InboxScreen(navController) }
                composable(Screen.DIAGNOSTICS_ROUTE) { DiagnosticsScreen(navController) }
                composable(Screen.PIPELINE_GRAPH_ROUTE) { PipelineGraphScreen(navController) }
                composable(Screen.DIAL_ROUTE) { DialScreen(navController, callViewModel) }

                composable(Screen.CALL_ROUTE) {
                    CallScreen(vm = callViewModel, onClose = { navController.popBackStack() })
                }

                composable(
                    route = "chat_screen?username={username}",
                    arguments = listOf(
                        navArgument("username") {
                            type = NavType.StringType
                            defaultValue = "default_user"
                        }
                    )
                ) { ChatScreen(navController, unreadInboxCount = totalUnreadCount) }

                composable(
                    route = "customer_cameras/{customerId}",
                    arguments = listOf(navArgument("customerId") { type = NavType.StringType })
                ) { CustomerCameraScreen(navController) }

                composable(
                    route = "alert_history?cameraId={cameraId}",
                    arguments = listOf(
                        navArgument("cameraId") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { AlertHistoryScreen(navController) }

                composable(
                    route = "camera_detail/{cameraId}",
                    arguments = listOf(navArgument("cameraId") { type = NavType.StringType })
                ) { CameraDetailScreen(navController) }
            }
        }

        // =========================================================================
        // ✅ IN-APP FLOATING CALL BUBBLE (Bong bóng cuộc gọi nổi trong ứng dụng)
        // Hiển thị khi đang có cuộc gọi hoạt động VÀ người dùng KHÔNG đứng ở CallScreen
        // =========================================================================
        val isCallActive = when (callState.state) {
    CallState.DIALING,
    CallState.RINGING,
    CallState.CONNECTING,
    CallState.CONNECTED -> true

    CallState.IDLE,
    CallState.ENDED,
    CallState.FAILED -> false
}
        val isNotOnCallScreen = currentRoute != Screen.CALL_ROUTE

        AnimatedVisibility(
            visible = isCallActive && isNotOnCallScreen,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        ) {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 6.dp,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        navController.navigate(Screen.CALL_ROUTE) {
                            launchSingleTop = true
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Quay lại cuộc gọi",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (callState.state == CallState.RINGING) "Đang đổ chuông..." else "Đang gọi...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}