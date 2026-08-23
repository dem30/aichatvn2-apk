package com.aichatvn.agent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aichatvn.agent.data.dataStore
// ❌ ĐÃ GỠ BỎ: import com.aichatvn.agent.service.VoiceAssistantService
import com.aichatvn.agent.skills.NotificationSkill
import com.aichatvn.agent.ui.navigation.AppNavigator
import com.aichatvn.agent.ui.theme.AIChatVN2Theme
import com.aichatvn.agent.utils.IncomingCallNotificationHelper
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: com.aichatvn.agent.utils.Logger

    private var pendingDeepLinkRoute by mutableStateOf<String?>(null)

    // ✅ MỚI: callId cần tự động trả lời — set khi Activity được mở từ nút "Nghe" trên
    // notification cuộc gọi đến (PendingIntent.getActivity() kèm AUTO_ANSWER_CALL_ID_EXTRA,
    // xem IncomingCallNotificationHelper). AppNavigator đọc giá trị này để gọi
    // CallViewModel.answer(callId) đúng 1 lần rồi báo lại qua onAutoAnswerConsumed.
    private var pendingAutoAnswerCallId by mutableStateOf<String?>(null)

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.i("MainActivity", "🚀 App khởi động - v3")

        pendingDeepLinkRoute = consumeDeepLinkExtra(intent)
        pendingAutoAnswerCallId = consumeAutoAnswerExtra(intent)

        setContent {
            // Giữ lại quyền RECORD_AUDIO để SpeechRecognizer có thể sử dụng khi chạm nói
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            } else {
                listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            }

            val permissionState = rememberMultiplePermissionsState(permissions)

            LaunchedEffect(Unit) {
                if (!permissionState.allPermissionsGranted) {
                    permissionState.launchMultiplePermissionRequest()
                }
            }

            // ❌ Đfont ĐÃ GỠ BỎ: Khối LaunchedEffect tự động khởi động VoiceAssistantService cũ tại đây

            val darkMode by dataStore.data
                .map { it[booleanPreferencesKey("dark_mode")] ?: false }
                .collectAsState(initial = false)

            AIChatVN2Theme(darkTheme = darkMode) {
                AppNavigator(
                    pendingDeepLinkRoute = pendingDeepLinkRoute,
                    onDeepLinkConsumed = { pendingDeepLinkRoute = null },
                    pendingAutoAnswerCallId = pendingAutoAnswerCallId,
                    onAutoAnswerConsumed = { pendingAutoAnswerCallId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newRoute = consumeDeepLinkExtra(intent)
        // Chỉ cập nhật khi thực sự có route mới trong Intent này.
        // Trước đây dòng này luôn gán lại (kể cả null), nhưng vì Intent gốc
        // của Activity (do Android launchMode singleTask/singleTop giữ lại)
        // có thể được redeliver mà KHÔNG mang extra deep-link, getStringExtra()
        // trả về null — nhưng nếu SAU ĐÓ có bất kỳ nguồn nào (service nền, FCM,
        // notification cũ) gọi lại onNewIntent với Intent vẫn còn extra cũ
        // "chat_screen" chưa bị xóa, pendingDeepLinkRoute sẽ bị set lại và
        // AppNavigator sẽ điều hướng ngược về Chat ngay sau khi người dùng
        // vừa bấm sang tab khác (Dashboard). consumeDeepLinkExtra() xóa extra
        // khỏi Intent ngay khi đọc, nên nó chỉ có thể kích hoạt navigate đúng 1 lần.
        if (newRoute != null) {
            pendingDeepLinkRoute = newRoute
        }

        // ✅ MỚI: cùng lý do như trên — app đang mở sẵn (dù ở màn hình nào) mà người dùng
        // bấm "Nghe" trên notification, Activity nhận lại qua onNewIntent() thay vì onCreate().
        val newAutoAnswerCallId = consumeAutoAnswerExtra(intent)
        if (newAutoAnswerCallId != null) {
            pendingAutoAnswerCallId = newAutoAnswerCallId
        }
    }

    /**
     * Đọc DEEP_LINK_EXTRA từ Intent rồi xóa ngay lập tức khỏi Intent.
     * Điều này đảm bảo extra không thể bị đọc lại (và vô tình kích hoạt
     * navigate về Chat) ở một lần gọi onNewIntent/onCreate sau đó với
     * cùng một Intent instance (Android tái sử dụng Intent trong nhiều
     * trường hợp singleTask/singleTop).
     */
    private fun consumeDeepLinkExtra(intent: Intent?): String? {
        val route = intent?.getStringExtra(NotificationSkill.DEEP_LINK_EXTRA)
        intent?.removeExtra(NotificationSkill.DEEP_LINK_EXTRA)
        return route
    }

    /** Tương tự consumeDeepLinkExtra() nhưng cho cờ tự động trả lời cuộc gọi — xem ghi chú ở pendingAutoAnswerCallId. */
    private fun consumeAutoAnswerExtra(intent: Intent?): String? {
        val callId = intent?.getStringExtra(IncomingCallNotificationHelper.AUTO_ANSWER_CALL_ID_EXTRA)
        intent?.removeExtra(IncomingCallNotificationHelper.AUTO_ANSWER_CALL_ID_EXTRA)
        return callId
    }
}