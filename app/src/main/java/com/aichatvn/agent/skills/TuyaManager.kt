package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.dao.TuyaDeviceDao
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.dataStore  // ✅ Import extension function
import com.aichatvn.agent.utils.Logger
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TuyaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tuyaDeviceDao: TuyaDeviceDao,
    private val logger: Logger
) {
    companion object {
        private val CLIENT_ID = stringPreferencesKey("tuya_client_id")
        private val CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
        private val DATA_CENTER = stringPreferencesKey("tuya_data_center")
        
        private val API_URLS = mapOf(
            "us" to "https://openapi.tuyaus.com",
            "eu" to "https://openapi.tuyaeu.com",
            "cn" to "https://openapi.tuyacn.com",
            "in" to "https://openapi.tuyain.com"
        )
        
        private const val DEFAULT_REGION = "us"
        private var powerDps = "1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private var accessToken: String? = null
    private var tokenExpiry: Long = 0L
    
    private val deviceCache = mutableMapOf<String, DeviceInfo>()

    data class DeviceInfo(
        val id: String,
        val name: String,
        val online: Boolean = false,
        val category: String = "",
        val productName: String = ""
    )

    // ============================================================
    // LOAD DEVICES TỪ DATABASE
    // ============================================================

    suspend fun loadDevicesFromDB() {
        val devices = tuyaDeviceDao.getAllDevices()
        deviceCache.clear()
        devices.forEach { entity ->
            deviceCache[entity.name] = DeviceInfo(
                id = entity.id,
                name = entity.name,
                online = entity.online,
                category = entity.category,
                productName = entity.productName
            )
        }
        logger.i("TuyaManager", "📂 Loaded ${deviceCache.size} devices from DB")
    }

    // ============================================================
    // SCAN DEVICES - QUÉT TỪ API + LƯU VÀO DB
    // ============================================================

    suspend fun scanDevices(): Map<String, DeviceInfo> {
        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val baseUrl = getApiBaseUrl()
        
        val url = "$baseUrl/v1.0/iot-01/associated-users/devices"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Quét thiết bị thất bại: ${response.code}")
        }
        
        val json = JSONObject(response.body?.string() ?: "")
        val success = json.optBoolean("success")
        if (!success) {
            val msg = json.optString("msg", "Unknown error")
            throw Exception("Scan API error: $msg")
        }
        
        val result = json.optJSONArray("result")
        deviceCache.clear()
        val deviceList = mutableListOf<TuyaDeviceEntity>()
        
        if (result != null) {
            for (i in 0 until result.length()) {
                val device = result.getJSONObject(i)
                val name = device.optString("name")
                val id = device.optString("id")
                val online = device.optBoolean("online", false)
                val category = device.optString("category", "")
                val productName = device.optString("product_name", "")
                
                if (name.isNotBlank() && id.isNotBlank()) {
                    deviceCache[name] = DeviceInfo(id, name, online, category, productName)
                    logger.i("TuyaManager", "📱 $name → $id (online: $online)")
                    
                    deviceList.add(
                        TuyaDeviceEntity(
                            id = id,
                            name = name,
                            online = online,
                            category = category,
                            productName = productName,
                            lastSeen = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        
        if (deviceList.isNotEmpty()) {
            tuyaDeviceDao.insertAllDevices(deviceList)
            logger.i("TuyaManager", "💾 Saved ${deviceList.size} devices to DB")
        }
        
        logger.i("TuyaManager", "✅ Tìm thấy ${deviceCache.size} thiết bị")
        return deviceCache
    }

    // ============================================================
    // GET DEVICE - TỪ CACHE HOẶC DB
    // ============================================================

    private suspend fun getDeviceInfo(deviceName: String): DeviceInfo {
        val cached = deviceCache[deviceName]
        if (cached != null) {
            return cached
        }
        
        val entity = tuyaDeviceDao.getDeviceByName(deviceName)
        if (entity != null) {
            val info = DeviceInfo(
                id = entity.id,
                name = entity.name,
                online = entity.online,
                category = entity.category,
                productName = entity.productName
            )
            deviceCache[entity.name] = info
            return info
        }
        
        throw IllegalArgumentException("Không tìm thấy thiết bị '$deviceName'")
    }

    // ============================================================
    // UPDATE STATUS KHI ĐIỀU KHIỂN
    // ============================================================

    private suspend fun updateDeviceStatus(deviceId: String, online: Boolean) {
        tuyaDeviceDao.updateOnlineStatus(deviceId, online, System.currentTimeMillis())
        deviceCache.values.find { it.id == deviceId }?.let { info ->
            deviceCache[info.name] = info.copy(online = online)
        }
    }

    // ============================================================
    // AUTHENTICATION
    // ============================================================

    suspend fun getAccessToken(): String {
        mutex.withLock {
            if (accessToken != null && System.currentTimeMillis() < tokenExpiry) {
                return accessToken!!
            }
            
            val prefs = context.dataStore.data.first()
            val clientId = prefs[CLIENT_ID] ?: ""
            val clientSecret = prefs[CLIENT_SECRET] ?: ""
            val baseUrl = getApiBaseUrl()
            
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw IllegalStateException("Chưa cấu hình Tuya Client ID/Secret")
            }
            
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val signString = clientId + timestamp.toString()
            val sign = hmacSha256(signString, clientSecret)
            
            val url = "$baseUrl/v1.0/token?grant_type=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("client_id", clientId)
                .addHeader("sign", sign)
                .addHeader("t", timestamp.toString())
                .addHeader("nonce", nonce)
                .addHeader("sign_method", "HMAC-SHA256")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Lấy token thất bại: ${response.code}")
            }
            
            val json = JSONObject(response.body?.string() ?: "")
            val success = json.optBoolean("success")
            if (!success) {
                val msg = json.optString("msg", "Unknown error")
                throw Exception("Token API error: $msg")
            }
            
            val result = json.optJSONObject("result")
            accessToken = result?.optString("access_token")
            val expireSeconds = result?.optInt("expires_in") ?: 7200
            tokenExpiry = System.currentTimeMillis() + (expireSeconds - 60) * 1000L
            
            logger.i("TuyaManager", "🔑 Đã lấy token mới")
            return accessToken!!
        }
    }

    private fun hmacSha256(data: String, key: String): String {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
            mac.init(secretKey)
            val hash = mac.doFinal(data.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.e("TuyaManager", "HMAC error: ${e.message}", e)
            throw e
        }
    }

    private suspend fun getApiBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        val region = prefs[DATA_CENTER] ?: DEFAULT_REGION
        return API_URLS[region] ?: API_URLS[DEFAULT_REGION]!!
    }

    // ============================================================
    // CONTROL
    // ============================================================

    suspend fun turnOn(deviceName: String) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, true)
        logger.i("TuyaManager", "💡 BẬT ${device.name}")
    }

    suspend fun turnOff(deviceName: String) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, false)
        logger.i("TuyaManager", "💡 TẮT ${device.name}")
    }

    suspend fun getStatus(deviceName: String): Boolean {
        val device = getDeviceInfo(deviceName)
        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val baseUrl = getApiBaseUrl()
        
        val url = "$baseUrl/v1.0/devices/${device.id}/status"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Lấy trạng thái thất bại: ${response.code}")
        }
        
        val json = JSONObject(response.body?.string() ?: "")
        val success = json.optBoolean("success")
        if (!success) {
            val msg = json.optString("msg", "Unknown error")
            throw Exception("Status API error: $msg")
        }
        
        val result = json.optJSONArray("result")
        if (result != null) {
            for (i in 0 until result.length()) {
                val status = result.getJSONObject(i)
                if (status.optString("code") == powerDps) {
                    val value = status.opt("value")
                    return when (value) {
                        is Boolean -> value
                        is Int -> value == 1
                        is String -> value == "true" || value == "1"
                        else -> false
                    }
                }
            }
        }
        
        return false
    }

    private suspend fun setDeviceState(device: DeviceInfo, state: Boolean) {
        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val baseUrl = getApiBaseUrl()
        
        val body = JSONObject().apply {
            put("commands", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("code", powerDps)
                    put("value", state)
                })
            })
        }.toString()
        
        val url = "$baseUrl/v1.0/devices/${device.id}/commands"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Điều khiển thất bại: ${response.code}")
        }
        
        val json = JSONObject(response.body?.string() ?: "")
        val success = json.optBoolean("success")
        if (!success) {
            val msg = json.optString("msg", "Unknown error")
            throw Exception("Control API error: $msg")
        }
        
        updateDeviceStatus(device.id, true)
    }
}