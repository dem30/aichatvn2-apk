package com.aichatvn.agent.tools.camera

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotFetcher @Inject constructor() {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)  // Tăng từ 10 lên 15
        .readTimeout(15, TimeUnit.SECONDS)     // Tăng từ 10 lên 15
        .build()
    
    suspend fun fetchSnapshot(url: String): ByteArray? {
        return try {
            Log.d("SnapshotFetcher", "Fetching snapshot from: $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                Log.d("SnapshotFetcher", "Success: ${bytes?.size ?: 0} bytes received")
                bytes
            } else {
                Log.e("SnapshotFetcher", "HTTP ${response.code}: ${response.message}")
                null
            }
        } catch (e: Exception) {
            Log.e("SnapshotFetcher", "Error fetching snapshot: ${e.message}", e)
            null
        }
    }
}