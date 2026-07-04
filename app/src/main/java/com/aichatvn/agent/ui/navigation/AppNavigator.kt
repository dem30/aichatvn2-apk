package com.aichatvn.agent.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aichatvn.agent.R
import com.aichatvn.agent.ui.screens.*

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Dashboard  : Screen("dashboard",   R.string.tab_dashboard,   Icons.Default.Dashboard)
    object Inbox      : Screen("inbox",       R.string.tab_chat,        Icons.Default.Chat) // ✅ ĐÃ SỬA: Đưa Inbox lên thanh Bottom Navigation chính [1]
    object Customer   : Screen("customer",    R.string.tab_camera,      Icons.Default.People)
    object Training   : Screen("training",    R.string.tab_training,    Icons.Default.School)
    object Schedule   : Screen("schedule",    R.string.tab_schedule,    Icons.Default.Schedule)
    object Logs       : Screen("logs",        R.string.tab_logs,        Icons.Default.BugReport)
    object Settings   : Screen("settings",    R.string.tab_settings,    Icons.Default.Settings)
    object Tuya       : Screen("tuya",        R.string.tab_settings,    Icons.Default.Devices)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigator() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Danh sách các tab chính hiển thị dưới thanh Bottom Navigation (Đã loại bỏ Diagnostics)
    val screens = listOf(
        Screen.Dashboard,
        Screen.Inbox, // ✅ ĐÃ THÊM: Hiện màn hình danh sách hộp thư ra làm tab chính [1]
        Screen.Customer,
        Screen.Training,
        Screen.Schedule,
        Screen.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(stringResource(screen.titleRes)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "chat_screen",  // ✅ ĐÃ SỬA: route KHÔNG tham số riêng cho màn hình mở đầu — tránh dùng giá trị đã điền sẵn ("...=default_user") làm startDestination cho 1 route có query-param kiểu {username}, vì Navigation Compose có thể không khớp graph đúng cách, gây crash khi back-stack bị đụng tới (bấm sang tab khác)
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route)   { DashboardScreen(navController) }
            composable(Screen.Inbox.route)       { InboxScreen(navController) } // ✅ ĐÃ ĐĂNG KÝ: Màn hình danh sách hội thoại Inbox
            composable(Screen.Customer.route)    { CustomerScreen(navController) }
            composable(Screen.Training.route)    { TrainingScreen(navController) }
            composable(Screen.Schedule.route)    { ScheduleScreen(navController) }
            composable(Screen.Logs.route)        { LogScreen(navController) }
            composable(Screen.Settings.route)    { SettingsScreen(navController) }
            composable(Screen.Tuya.route)        { TuyaScreen(navController) }

            // ✅ ĐÃ THÊM: route KHÔNG tham số — dùng làm màn hình mở đầu app (luôn là default_user,
            // ChatViewModel tự mặc định username = "default_user" khi SavedStateHandle không có
            // key "username"). Route riêng biệt hoàn toàn với route có {username} bên dưới, nên
            // không còn rủi ro Navigation Compose khớp nhầm/không khớp pattern lúc dựng NavHost.
            composable("chat_screen") { ChatScreen(navController) }

            // ✅ ĐÃ THÊM: Cấu hình màn hình Chat chi tiết nhận tham số username động từ InboxScreen chuyển sang
            composable(
                route = "chat_screen?username={username}",
                arguments = listOf(
                    navArgument("username") {
                        type = NavType.StringType
                        defaultValue = "default_user"
                    }
                )
            ) { ChatScreen(navController) }

            // Camera theo khách hàng
            composable(
                route = "customer_cameras/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.StringType })
            ) { CustomerCameraScreen(navController) }

            // Lịch sử cảnh báo
            composable(
                route = "alert_history?cameraId={cameraId}",
                arguments = listOf(
                    navArgument("cameraId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { AlertHistoryScreen(navController) }

            // Chi tiết 1 camera
            composable(
                route = "camera_detail/{cameraId}",
                arguments = listOf(navArgument("cameraId") { type = NavType.StringType })
            ) { CameraDetailScreen(navController) }
        }
    }
}