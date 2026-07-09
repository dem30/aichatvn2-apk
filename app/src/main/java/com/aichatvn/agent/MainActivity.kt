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

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        logger.i("MainActivity", "🚀 App khởi động - v3")

        pendingDeepLinkRoute = intent?.getStringExtra(NotificationSkill.DEEP_LINK_EXTRA)

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
                    onDeepLinkConsumed = { pendingDeepLinkRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLinkRoute = intent.getStringExtra(NotificationSkill.DEEP_LINK_EXTRA)
    }
}