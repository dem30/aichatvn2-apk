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
import com.aichatvn.agent.service.VoiceAssistantService
import com.aichatvn.agent.skills.NotificationSkill
import com.aichatvn.agent.ui.navigation.AppNavigator
import com.aichatvn.agent.ui.theme.AIChatVN2Theme
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

    // ✅ MỚI: Route điều hướng đang chờ xử lý khi Activity được mở/đưa lên trước từ 1
    // notification (vd cảnh báo camera). Dùng mutableStateOf để đọc trong setContent { }
    // tự động kích hoạt recomposition + LaunchedEffect trong AppNavigator khi giá trị đổi,
    // kể cả khi thay đổi đến từ onNewIntent() (ngoài luồng Composable) chứ không chỉ onCreate().
    private var pendingDeepLinkRoute by mutableStateOf<String?>(null)

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.i("MainActivity", "🚀 App khởi động - v3")

        // ✅ MỚI: Trường hợp app đang bị kill hoàn toàn, bấm vào notification sẽ tạo Activity
        // mới qua onCreate() (không qua onNewIntent()) — đọc extra ngay tại đây để không bỏ lỡ.
        pendingDeepLinkRoute = intent?.getStringExtra(NotificationSkill.DEEP_LINK_EXTRA)

        setContent {
            // ✅ Danh sách quyền — RECORD_AUDIO thêm vào đây, không dùng ActivityCompat riêng
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

            // ✅ Chỉ dùng 1 cách xin quyền duy nhất: Accompanist (Compose-native)
            // Không dùng ActivityCompat.requestPermissions() — gọi 2 cách cùng lúc gây conflict
            LaunchedEffect(Unit) {
                if (!permissionState.allPermissionsGranted) {
                    permissionState.launchMultiplePermissionRequest()
                }
            }

            // ✅ ĐÃ THÊM: MainApplication.onCreate() chạy TRƯỚC màn hình xin quyền này — ở máy mới
            // cài, RECORD_AUDIO lúc đó chưa được cấp nên VoiceAssistantService bị bỏ qua hoàn toàn
            // (chỉ log cảnh báo), và không có gì gọi lại sau khi người dùng bấm "Cho phép" cả ->
            // mic không bao giờ khởi động dù đã cấp quyền. Theo dõi kết quả xin quyền ở đây, hễ
            // RECORD_AUDIO chuyển sang granted thì tự khởi động lại Service (Service cũ đã tự
            // stopSelf() nên gọi lại sẽ chạy onCreate() mới, lần này pass permission check).
            LaunchedEffect(permissionState.permissions) {
                val recordAudioGranted = permissionState.permissions
                    .find { it.permission == Manifest.permission.RECORD_AUDIO }
                    ?.status?.isGranted == true
                if (recordAudioGranted) {
                    try {
                        val voiceServiceIntent = Intent(this@MainActivity, VoiceAssistantService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(voiceServiceIntent)
                        } else {
                            startService(voiceServiceIntent)
                        }
                        logger.i("MainActivity", "🎤 RECORD_AUDIO vừa được cấp -> khởi động lại VoiceAssistantService")
                    } catch (e: Exception) {
                        logger.e("MainActivity", "❌ Không thể khởi động VoiceAssistantService sau khi cấp quyền", e)
                    }
                }
            }

            val darkMode by dataStore.data
                .map { it[booleanPreferencesKey("dark_mode")] ?: false }
                .collectAsState(initial = false)

            AIChatVN2Theme(darkTheme = darkMode) {
                AppNavigator(
                    pendingDeepLinkRoute = pendingDeepLinkRoute,
                    onDeepLinkConsumed = { pendingDeepLinkRoute = null }
                )
            }
        }
    }

    // ✅ MỚI: Trường hợp app đang chạy nền/foreground (Activity instance đã tồn tại trong task) —
    // bấm vào notification lúc này KHÔNG tạo Activity mới, Android tái sử dụng instance hiện tại
    // và gọi onNewIntent() thay vì onCreate(). Trước đây MainActivity không override hàm này nên
    // Intent mới (mang theo DEEP_LINK_EXTRA) bị coi như không tồn tại — notification bấm vào lúc
    // app đang mở sẵn hoàn toàn không có tác dụng gì.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkRoute = intent.getStringExtra(NotificationSkill.DEEP_LINK_EXTRA)
    }
}