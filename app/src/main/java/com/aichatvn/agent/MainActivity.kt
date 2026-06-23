package com.aichatvn.agent

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.ui.navigation.AppNavigator
import com.aichatvn.agent.ui.theme.AIChatVN2Theme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
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

            val darkMode by dataStore.data
                .map { it[booleanPreferencesKey("dark_mode")] ?: false }
                .collectAsState(initial = false)

            AIChatVN2Theme(darkTheme = darkMode) {
                AppNavigator()
            }
        }
    }
}