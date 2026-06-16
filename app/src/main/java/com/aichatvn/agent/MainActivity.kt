package com.aichatvn.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat  // ✅ ĐÚNG
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import com.aichatvn.agent.services.CameraScanService
import com.aichatvn.agent.ui.navigation.AppNavigator
import com.aichatvn.agent.ui.theme.AIChatVN2Theme
import com.aichatvn.agent.ui.viewmodels.SettingsViewModel
import com.aichatvn.agent.workers.SmartScan15MinWorker
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var logger: com.aichatvn.agent.utils.Logger

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        logger.i("MainActivity", "🚀 App khởi động - onCreate")

        setupWorkManager()

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val darkMode by settingsViewModel.darkMode.collectAsState()

            AIChatVN2Theme(darkTheme = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // ✅ Required: CAMERA + NOTIFICATION
                    val requiredPermissions = buildList {
                        add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    
                    // ✅ Optional: LOCATION (không bắt buộc)
                    // Hiện tại không request - để user tự cấp qua Settings nếu cần
                    // Comment đã được cập nhật để khớp với hành vi

                    val permissionState = rememberMultiplePermissionsState(requiredPermissions)
                    var isServiceStarted by remember { mutableStateOf(false) }

                    LaunchedEffect(permissionState.allPermissionsGranted) {
                        if (permissionState.allPermissionsGranted && !isServiceStarted) {
                            startCameraService()
                            isServiceStarted = true
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (!permissionState.allPermissionsGranted) {
                            permissionState.launchMultiplePermissionRequest()
                        }
                    }

                    AppNavigator()
                }
            }
        }
    }

    private fun startCameraService() {
        try {
            val intent = Intent(this, CameraScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            logger.i("MainActivity", "CameraScanService started")
        } catch (e: Exception) {
            logger.e("MainActivity", "Failed to start CameraScanService: ${e.message}", e)
        }
    }

    private fun setupWorkManager() {
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            1L
        }
        val workName = "service_watchdog_work_v$version"
        
        val watchdogRequest = PeriodicWorkRequestBuilder<SmartScan15MinWorker>(
    15, TimeUnit.MINUTES
).setConstraints(
    Constraints.Builder()
        .setRequiresBatteryNotLow(false)
        .build()
).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            workName,
            ExistingPeriodicWorkPolicy.KEEP,
            watchdogRequest
        )

        logger.i("MainActivity", "ServiceWatchdogWorker scheduled (15 min) - $workName")
    }

    override fun onResume() {
        super.onResume()
        // ✅ Chỉ start nếu service chưa chạy
        // CameraScanService tự xử lý trùng lặp, nhưng tránh gọi không cần thiết
        val hasRequiredPermissions = listOf(
            Manifest.permission.CAMERA
        ).all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (hasRequiredPermissions) {
            // ✅ Service tự xử lý nếu đã chạy
            startCameraService()
        }
    }
}