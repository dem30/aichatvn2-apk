package com.aichatvn.agent

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aichatvn.agent.scheduler.TaskScheduler
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

    @Inject
    lateinit var taskScheduler: TaskScheduler

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logger.i("MainActivity", "🚀 App khởi động - v3")

        // Schedule tasks
        taskScheduler.schedule(this)

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