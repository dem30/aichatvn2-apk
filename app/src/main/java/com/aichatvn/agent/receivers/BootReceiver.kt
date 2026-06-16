package com.aichatvn.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aichatvn.agent.services.CameraScanService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "monitoring_settings")

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    Log.i("BootReceiver", "Device boot completed, checking monitoring state...")

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
            val key = booleanPreferencesKey("monitoring_enabled")

            runBlocking {
                val prefs = context.dataStore.data.first()
                prefs[key] ?: true
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to read preference, defaulting to true", e)
            true
        }
    }
}