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
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichatvn.agent.ui.viewmodels.NavBadgeViewModel

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    object Dashboard  : Screen("dashboard",   R.string.tab_dashboard,   Icons.Default.Dashboard)
    // ✅ ĐÃ SỬA: Tab "Trò chuyện" trỏ THẲNG vào route "chat_screen" (màn chat AI mặc định),
    // trùng với startDestination của NavHost bên dưới — đây là root thật của tab, không đăng ký
    // composable trùng lặp. Inbox không còn là tab chính, xem INBOX_ROUTE.
    object Chat       : Screen("chat_screen", R.string.tab_chat,        Icons.Default.Chat)
    object Customer   : Screen("customer",    R.string.tab_camera,      Icons.Default.People)
    object Training   : Screen("training",    R.string.tab_training,    Icons.Default.School)
    object Schedule   : Screen("schedule",    R.string.tab_schedule,    Icons.Default.Schedule)
    object Logs       : Screen("logs",        R.string.tab_logs,        Icons.Default.BugReport)
    object Settings   : Screen("settings",    R.string.tab_settings,    Icons.Default.Settings)
    object Tuya       : Screen("tuya",        R.string.tab_settings,    Icons.Default.Devices)

    companion object {
        // Inbox giờ chỉ là màn con, mở từ icon trong ChatScreen — không nằm trong Bottom Navigation.
        const val INBOX_ROUTE = "inbox"
        // ✅ MỚI: Diagnostics cũng là màn con — chưa từng được đăng ký route nên trước đây
        // không thể mở được từ bất cứ đâu trong app. Mở từ icon trong Settings/Logs.
        const val DIAGNOSTICS_ROUTE = "diagnostics"
        // ✅ MỚI: Route cho màn Node-Graph trực quan hoá pipeline AgentKernel — màn con,
        // mở từ card "🧠 Pipeline AI" trong DiagnosticsScreen.
        const val PIPELINE_GRAPH_ROUTE = "pipeline_graph"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigator(
    // ✅ MỚI: Route cần điều hướng tới khi app được mở từ 1 notification (xem
    // NotificationSkill.DEEP_LINK_EXTRA + MainActivity đọc Intent extra rồi truyền xuống đây).
    // null = mở app bình thường, không có gì cần điều hướng đặc biệt.
    pendingDeepLinkRoute: String? = null,
    // ✅ MỚI: Callback báo "đã tiêu thụ xong deep link" — để MainActivity xoá state của nó,
    // tránh trường hợp xoay màn hình / recomposition sau đó lại điều hướng lặp lại route cũ.
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // ✅ MỚI: Khi có deep link đang chờ (bấm vào notification cảnh báo camera), điều hướng
    // NGAY sang route đó — chỉ chạy 1 lần cho mỗi giá trị pendingDeepLinkRoute mới (key theo
    // chính giá trị đó), rồi báo consumed để không lặp lại nếu Composable này recompose vì lý
    // do khác (đổi theme, xoay màn hình...).
    LaunchedEffect(pendingDeepLinkRoute) {
        if (pendingDeepLinkRoute != null) {
            navController.navigate(pendingDeepLinkRoute) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    // ✅ MỚI: Gọi hiltViewModel() NGAY TẠI ĐÂY — bên ngoài mọi composable(route) { ... } của
    // NavHost — nên nó được scope theo Activity/NavGraph, KHÔNG theo từng NavBackStackEntry
    // như ChatViewModel. Nhờ đó totalUnreadCount phản ánh đúng tổng số tin nhắn chưa đọc trên
    // TOÀN APP mọi lúc, kể cả khi Admin đang đứng ở tab Dashboard/Khách hàng chứ không mở
    // ChatScreen nào.
    // ✅ ĐÃ SỬA: Không còn hiện badge ở đây (tab dưới cùng) nữa — giá trị được TRUYỀN XUỐNG
    // ChatScreen để hiện đúng chỗ: icon Hộp thư đa kênh (Forum) trên TopAppBar của nó.
    val navBadgeViewModel: NavBadgeViewModel = hiltViewModel()
    val totalUnreadCount by navBadgeViewModel.totalUnreadCount.collectAsState()

    // Danh sách các tab chính hiển thị dưới thanh Bottom Navigation (Đã loại bỏ Diagnostics)
    val screens = listOf(
        Screen.Dashboard,
        Screen.Chat, // ✅ ĐÃ SỬA: Tab "Trò chuyện" giờ là màn chat AI mặc định, không phải Inbox nữa
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
                        icon = {
                            // ✅ ĐÃ SỬA: Bỏ BadgedBox khỏi tab "Trò chuyện" ở đây — badge tổng số
                            // tin nhắn chưa đọc giờ được chuyển LÊN icon Hộp thư đa kênh (Forum)
                            // trên TopAppBar của ChatScreen (xem ChatScreen.kt tham số
                            // unreadInboxCount). Lý do: tab này dẫn vào chat cá nhân AI của chủ
                            // app (default_user), không phải Inbox khách hàng — gắn badge "số tin
                            // nhắn khách chưa đọc" vào đây dễ gây hiểu lầm nó thuộc về chat cá
                            // nhân. totalUnreadCount vẫn được thu thập ở AppNavigator (scope
                            // Activity, xem bên dưới) để truyền xuống đúng nơi cần hiển thị.
                            Icon(screen.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(screen.titleRes)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            // ✅ ĐÃ THÊM (các tab khác Chat): Nếu đang đứng ở màn con của chính
                            // tab đó thì bấm lại icon tab phải rớt hết màn con và về root của tab —
                            // không cần người dùng tự bấm back. Riêng tab "Trò chuyện" xử lý khác,
                            // xem nhánh `if (screen == Screen.Chat)` ngay bên dưới.
                            // ✅ ĐÃ SỬA — SỬA DỨT ĐIỂM: Tab "Trò chuyện" giờ được xử lý RIÊNG,
                            // tường minh, không còn dựa vào cơ chế popUpTo(startDestinationId)
                            // {saveState=true} + restoreState=true như các tab khác. Cơ chế đó
                            // vốn được thiết kế để BẢO TỒN trạng thái khi CHUYỂN SANG TAB KHÁC
                            // rồi quay lại — nó KHÔNG đảm bảo ép về đúng root khi đang đứng sâu
                            // trong các màn con CỦA CHÍNH tab đó (Inbox, chat khách X, Logs...),
                            // nhất là khi "chat_screen" (route root) và "chat_screen?username=..."
                            // (route có tham số) là 2 composable riêng biệt dễ khiến NavController
                            // khớp/so sánh route không như kỳ vọng (xem comment ở NavHost bên dưới)
                            // — đây là lý do bấm tab "Trò chuyện" từ trước tới giờ không quay về
                            // đúng màn chat admin. Giờ ép pop TOÀN BỘ back stack của tab này (kể
                            // cả entry root cũ, dùng inclusive=true) rồi tạo lại root "chat_screen"
                            // hoàn toàn mới — đảm bảo LUÔN về đúng chat admin bất kể trước đó đang
                            // đứng ở màn con nào, không phụ thuộc vào việc so khớp route đúng/sai.
                            if (screen == Screen.Chat) {
                                if (currentRoute != Screen.Chat.route) {
                                    navController.navigate(Screen.Chat.route) {
                                        popUpTo(Screen.Chat.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                                // Nếu đã đúng ở root rồi thì không cần làm gì thêm — tránh tạo
                                // lại ViewModel/mất vị trí cuộn một cách không cần thiết.
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
            startDestination = Screen.Chat.route,  // "chat_screen" — route KHÔNG tham số riêng cho màn hình mở đầu — tránh dùng giá trị đã điền sẵn ("...=default_user") làm startDestination cho 1 route có query-param kiểu {username}, vì Navigation Compose có thể không khớp graph đúng cách, gây crash khi back-stack bị đụng tới (bấm sang tab khác)
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route)   { DashboardScreen(navController) }
            composable(Screen.Customer.route)    { CustomerScreen(navController) }
            composable(Screen.Training.route)    { TrainingScreen(navController) }
            composable(Screen.Schedule.route)    { ScheduleScreen(navController) }
            composable(Screen.Logs.route)        { LogScreen(navController) }
            composable(Screen.Settings.route)    { SettingsScreen(navController) }
            composable(Screen.Tuya.route)        { TuyaScreen(navController) }

            // ✅ ĐÃ SỬA: route KHÔNG tham số — vừa là màn hình mở đầu app (default_user, xem
            // ChatViewModel.username mặc định), VỪA là root của tab "Trò chuyện" (Screen.Chat).
            // Dùng chung 1 route duy nhất, KHÔNG đăng ký 2 lần, tránh 2 khái niệm "chat" tách rời
            // như trước (chat_screen vs inbox). Route riêng biệt hoàn toàn với route có {username}
            // bên dưới, nên không rủi ro Navigation Compose khớp nhầm pattern lúc dựng NavHost.
            composable(Screen.Chat.route) { ChatScreen(navController, unreadInboxCount = totalUnreadCount) }

            // ✅ ĐÃ SỬA: Inbox (danh sách hội thoại đa kênh) không còn là tab chính — giờ là màn
            // con, mở từ icon trong TopAppBar của ChatScreen. Có back button riêng (xem InboxScreen.kt).
            composable(Screen.INBOX_ROUTE) { InboxScreen(navController) }

            // ✅ MỚI: Đăng ký route cho DiagnosticsScreen — trước đây file này tồn tại trong
            // source nhưng KHÔNG có composable() nào khai báo route "diagnostics" nên
            // navController.navigate(Screen.DIAGNOSTICS_ROUTE) sẽ luôn crash (route không tồn
            // tại trong graph), và không có cách nào mở được màn hình này từ trong app.
            composable(Screen.DIAGNOSTICS_ROUTE) { DiagnosticsScreen(navController) }
            // ✅ MỚI: Đăng ký route cho PipelineGraphScreen — màn con hiển thị call graph
            // thật của AgentKernel (TraceNode) theo từng câu lệnh Admin gõ thử.
            composable(Screen.PIPELINE_GRAPH_ROUTE) { PipelineGraphScreen(navController) }

            // ✅ ĐÃ THÊM: Cấu hình màn hình Chat chi tiết nhận tham số username động từ InboxScreen chuyển sang
            composable(
                route = "chat_screen?username={username}",
                arguments = listOf(
                    navArgument("username") {
                        type = NavType.StringType
                        defaultValue = "default_user"
                    }
                )
            ) { ChatScreen(navController, unreadInboxCount = totalUnreadCount) }

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