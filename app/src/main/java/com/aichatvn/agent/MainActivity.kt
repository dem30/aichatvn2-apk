package com.aichatvn.agent

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aichatvn.agent.ui.navigation.AppNavigator
import com.aichatvn.agent.ui.theme.AIChatVN2Theme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: com.aichatvn.agent.utils.Logger

    

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logger.i("MainActivity", "🚀 App khởi động - v3")

        // ✅ TaskScheduler đã được khởi động trong MainApplication.onCreate() —
        // không gọi lại ở đây vì Activity có thể recreate nhiều lần.

        setContent {
            // ✅ Xin CAMERA + POST_NOTIFICATIONS (Android 13+)
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                listOf(Manifest.permission.CAMERA)
            }

            val permissionState = rememberMultiplePermissionsState(permissions)

            LaunchedEffect(Unit) {
                if (!permissionState.allPermissionsGranted) {
                    permissionState.launchMultiplePermissionRequest()
                }
            }

            AIChatVN2Theme(darkTheme = false) {
                AppNavigator()
            }
        }
    }
}