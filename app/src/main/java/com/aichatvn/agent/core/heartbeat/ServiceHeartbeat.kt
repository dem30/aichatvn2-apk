package com.aichatvn.agent.core.heartbeat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.aichatvn.agent.data.dataStore
import kotlinx.coroutines.flow.first

object ServiceHeartbeat {
    
    private val heartbeatKey = longPreferencesKey("service_heartbeat")
    
    @Volatile
    var isRunning = false
        private set
    
    fun markRunning() {
        isRunning = true
    }
    
    fun markStopped() {
        isRunning = false
    }
    
    suspend fun getLastHeartbeat(context: Context): Long {
        return try {
            val prefs = context.dataStore.data.first()
            prefs[heartbeatKey] ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    suspend fun updateHeartbeat(context: Context) {
        try {
            context.dataStore.edit { prefs ->
                prefs[heartbeatKey] = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}