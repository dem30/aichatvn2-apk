package com.aichatvn.agent.skills

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HassManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tuyaDeviceDao: TuyaDeviceDao,
    private val logger: Logger
) {
    companion object {
        // Khai báo 2 khóa DataStore lưu cấu hình động cho Home Assistant
        val HASS_URL = stringPreferencesKey("hass_url")
        val HASS_TOKEN = stringPreferencesKey("hass_token")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val deviceCache = mutableMapOf<String, DeviceInfo>()

    data class DeviceInfo(
        val id: String,
        val name: String,
        val online: Boolean = false,
        val category: String = "",
        val productName: String = ""
    )

    /**
     * Nạp danh sách thực thể từ SQLite Database cục bộ vào bộ nhớ tạm RAM
     */
    suspend fun loadDevicesFromDB() = withContext(Dispatchers.IO) {
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
        logger.i("HassManager", "📂 Loaded ${deviceCache.size} Home Assistant entities from SQLite cache")
    }

    /**
     * Đồng bộ hóa: Gọi API lấy toàn bộ thực thể từ Home Assistant, lọc lấy Đèn (light) và Công tắc (switch)
     * rồi cập nhật đè trực tiếp vào bảng SQLite và cache RAM.
     */
    suspend fun scanDevices(): Map<String, DeviceInfo> = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val hassUrl = (prefs[HASS_URL] ?: "").trimEnd('/')
        val hassToken = (prefs[HASS_TOKEN] ?: "").trim()

        if (hassUrl.isBlank() || hassToken.isBlank()) {
            throw IllegalStateException("Chưa cấu hình Địa chỉ URL hoặc Token của Home Assistant")
        }

        val request = Request.Builder()
            .url("$hassUrl/api/states")
            .addHeader("Authorization", "Bearer $hassToken")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Đồng bộ thất bại. Mã lỗi HTTP: ${response.code}")
        }

        val responseBody = response.body?.string() ?: ""
        val jsonArray = JSONArray(responseBody)
        
        val entitiesToSave = mutableListOf<TuyaDeviceEntity>()
        deviceCache.clear()

        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val entityId = item.optString("entity_id", "")
            
            // Chỉ lọc lấy các thiết bị dạng Đèn (light), Công tắc (switch), hoặc Cảm biến rơ-le (input_boolean)
            if (entityId.startsWith("light.") || entityId.startsWith("switch.") || entityId.startsWith("input_boolean.")) {
                val state = item.optString("state", "off")
                val attributes = item.optJSONObject("attributes")
                val friendlyName = attributes?.optString("friendly_name") ?: entityId
                val isOnline = state != "unavailable" && state != "unknown"

                val category = entityId.substringBefore('.') // "light" hoặc "switch"
                
                val info = DeviceInfo(
                    id = entityId,
                    name = friendlyName,
                    online = isOnline,
                    category = category,
                    productName = state
                )
                deviceCache[friendlyName] = info

                entitiesToSave.add(
                    TuyaDeviceEntity(
                        id = entityId,
                        name = friendlyName,
                        online = isOnline,
                        category = category,
                        productName = state,
                        lastSeen = System.currentTimeMillis()
                    )
                )
            }
        }

        if (entitiesToSave.isNotEmpty()) {
            tuyaDeviceDao.deleteAllDevices() // Dọn sạch đống cũ
            tuyaDeviceDao.insertAllDevices(entitiesToSave)
            logger.i("HassManager", "💾 Saved ${entitiesToSave.size} Home Assistant entities into SQLite DB")
        }

        return@withContext deviceCache
    }

    private suspend fun getDeviceInfo(deviceName: String): DeviceInfo = withContext(Dispatchers.IO) {
        val cached = deviceCache[deviceName]
        if (cached != null) return@withContext cached

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
            return@withContext info
        }

        throw IllegalArgumentException("Không tìm thấy thực thể nào có tên '$deviceName'")
    }

    /**
     * Bật thực thể Home Assistant
     */
    suspend fun turnOn(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        callService(device.id, "turn_on")
        logger.i("HassManager", "💡 BẬT thiết bị: ${device.name}")
    }

    /**
     * Tắt thực thể Home Assistant
     */
    suspend fun turnOff(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        callService(device.id, "turn_off")
        logger.i("HassManager", "🔌 TẮT thiết bị: ${device.name}")
    }

    /**
     * Đọc trạng thái hiện tại từ bộ nhớ đệm hoặc từ API Home Assistant
     */
    suspend fun getStatus(deviceName: String): Boolean = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        
        val prefs = context.dataStore.data.first()
        val hassUrl = (prefs[HASS_URL] ?: "").trimEnd('/')
        val hassToken = (prefs[HASS_TOKEN] ?: "").trim()

        if (hassUrl.isBlank() || hassToken.isBlank()) {
            return@withContext false
        }

        val request = Request.Builder()
            .url("$hassUrl/api/states/${device.id}")
            .addHeader("Authorization", "Bearer $hassToken")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val state = json.optString("state", "off")
                val isOnline = state != "unavailable" && state != "unknown"
                
                // Cập nhật trạng thái trực tuyến của DB
                tuyaDeviceDao.updateOnlineStatus(device.id, isOnline, System.currentTimeMillis())
                return@withContext state == "on"
            }
        } catch (e: Exception) {
            logger.e("HassManager", "Error querying state for ${device.id}: ${e.message}")
        }
        return@withContext false
    }

    /**
     * Hàm nội bộ gọi dịch vụ điều khiển Home Assistant (Service Call API)
     */
    private suspend fun callService(entityId: String, service: String) = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val hassUrl = (prefs[HASS_URL] ?: "").trimEnd('/')
        val hassToken = (prefs[HASS_TOKEN] ?: "").trim()

        if (hassUrl.isBlank() || hassToken.isBlank()) {
            throw IllegalStateException("Chưa cấu hình thông tin Home Assistant")
        }

        // Lấy domain từ entity_id (ví dụ: "light.living_room" -> domain là "light")
        // Home Assistant hỗ trợ dịch vụ bật/tắt toàn cục hoặc theo domain tương ứng
        val domain = entityId.substringBefore('.')
        val url = "$hassUrl/api/services/$domain/$service"

        val bodyJson = JSONObject().apply {
            put("entity_id", entityId)
        }
        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $hassToken")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Lỗi gọi dịch vụ $service: HTTP ${response.code}")
        }
        
        // Cập nhật đệm DB
        val newState = if (service == "turn_on") "on" else "off"
        tuyaDeviceDao.updateOnlineStatus(entityId, true, System.currentTimeMillis())
        deviceCache[getDeviceInfoByEntityId(entityId)]?.let { info ->
            deviceCache[info.name] = info.copy(online = true, productName = newState)
        }
    }

    private fun getDeviceInfoByEntityId(entityId: String): String {
        return deviceCache.entries.find { it.value.id == entityId }?.key ?: ""
    }
}