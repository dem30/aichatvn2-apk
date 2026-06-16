package com.aichatvn.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aichatvn.agent.services.CameraScanService

/**
 * BootReceiver - Khởi động CameraScanService khi Android boot completed
 * ✅ Check trạng thái enabled trước khi start
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_QUICKBOOT_POWERON -> {
                try {
                    Log.i("BootReceiver", "Device boot completed, checking monitoring state...")
                    
                    // ✅ Check trạng thái enabled từ DataStore
                    val isEnabled = isMonitoringEnabled(context)
                    
                    if (isEnabled) {
                        Log.i("BootReceiver", "Monitoring is enabled, starting CameraScanService")
                        startService(context)
                    } else {
                        Log.i("BootReceiver", "Monitoring is disabled, skipping service start")
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to check monitoring state", e)
                }
            }
        }
    }
    
    private fun startService(context: Context) {
        try {
            val serviceIntent = Intent(context, CameraScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("BootReceiver", "CameraScanService started successfully")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start CameraScanService", e)
        }
    }
    
    private fun isMonitoringEnabled(context: Context): Boolean {
        return try {
            // ✅ Mặc định true nếu chưa có setting
            // Dùng DataStore check trạng thái
            val prefs = androidx.datastore.preferences.PreferenceDataStoreFactory
                .create(
                    produceFile = { context.getFileStreamPath("monitoring_settings.preferences_pb") }
                )
            
            val key = booleanPreferencesKey("monitoring_enabled")
            // Mặc định true (user muốn giám sát)
            runBlocking {
                prefs[key] ?: true
            }
        } catch (e: Exception) {
            // Nếu lỗi, mặc định true
            Log.e("BootReceiver", "Failed to read preference, defaulting to true", e)
            true
        }
    }
    
    private fun runBlocking(block: suspend () -> Boolean): Boolean {
        return kotlinx.coroutines.runBlocking { block() }
    }
}