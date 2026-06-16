package com.aichatvn.agent

import android.Manifest
import android.app.ActivityManager
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
import androidx.core.content.pm.PackageInfoCompat
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

    private var isServiceStarting = false

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
                    val requiredPermissions = buildList {
                        add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

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
        if (isServiceStarting) {
            logger.d("MainActivity", "Service already starting, skip")
            return
        }

        try {
            isServiceStarting = true
            val intent = Intent(this, CameraScanService::class.java)

            if (!isServiceRunning(CameraScanService::class.java)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                logger.i("MainActivity", "CameraScanService started")
            } else {
                logger.d("MainActivity", "CameraScanService already running")
            }
        } catch (e: Exception) {
            logger.e("MainActivity", "Failed to start CameraScanService: ${e.message}", e)
        } finally {
            isServiceStarting = false
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            logger.e("MainActivity", "Failed to check service status: ${e.message}", e)
            false
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

        logger.i("MainActivity", "SmartScan15MinWorker scheduled (15 min) - $workName")
    }

    override fun onResume() {
        super.onResume()
        val hasRequiredPermissions = listOf(
            Manifest.permission.CAMERA
        ).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (hasRequiredPermissions && !isServiceStarting) {
            startCameraService()
        }
    }
}