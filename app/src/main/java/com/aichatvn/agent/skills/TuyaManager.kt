package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.utils.WorldStateHelper
import com.aichatvn.agent.data.dataStore
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
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class TuyaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tuyaDeviceDao: TuyaDeviceDao,
    private val database: AppDatabase, // ✅ MỚI: cần worldStateDao() để dọn world_state khi xoá thiết bị
    // ✅ MỚI: điều khiển LOCAL qua LAN — xem fetchLocalKey()/setDeviceState() bên dưới.
    private val tuyaLocalController: com.aichatvn.agent.tools.tuya.TuyaLocalController,
    // ✅ MỚI: đọc công tắc LOCAL_ONLY_MODE_ENABLED + HOME_CAMERA_NODE_DEVICE_CODE/
    // GLOBAL_GATEWAY_URL/GLOBAL_GATEWAY_TOKEN cho nhánh fallback gateway trong
    // setDeviceState() — xem trySetDeviceStateViaGateway() bên dưới.
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) {
    companion object {
        private val CLIENT_ID = stringPreferencesKey("tuya_client_id")
        private val CLIENT_SECRET = stringPreferencesKey("tuya_client_secret")
        private val DATA_CENTER = stringPreferencesKey("tuya_data_center")
        // UID của tài khoản Smart Life mà user đã link vào Cloud Project của họ
        // (xem ở "Manage Devices" > bấm vào dòng tài khoản để thấy UID dạng ay...).
        // Đây LÀ giá trị do chính user nhập ở Settings — KHÔNG hardcode — để mỗi
        // người dùng app với Cloud Project Tuya riêng của họ vẫn quét được thiết bị.
        private val UID = stringPreferencesKey("tuya_uid")
        
        // Cập nhật URL máy chủ Singapore theo tài liệu chính thức của Tuya
        private val API_URLS = mapOf(
            "us" to "https://openapi.tuyaus.com",
            "eu" to "https://openapi.tuyaeu.com",
            "cn" to "https://openapi.tuyacn.com",
            "in" to "https://openapi.tuyain.com",
            "sg" to "https://openapi-sg.iotbing.com"
        )
        
        private const val DEFAULT_REGION = "sg"

        // Số lần thử lại tối đa và khoảng nghỉ giữa các lần, chỉ áp dụng cho thiết bị MỚI THÊM
        // (chưa từng có bản ghi trong DB trước lần scan này) khi Tuya Cloud báo online=false.
        private const val NEW_DEVICE_ONLINE_RETRY_COUNT = 4
        private const val NEW_DEVICE_ONLINE_RETRY_DELAY_MS = 3000L

        // Số lần thử lại và khoảng nghỉ khi xác minh thiết bị có thực sự đổi trạng thái switch
        // sau khi gửi lệnh bật/tắt hay không (có độ trễ lan truyền DP giữa Cloud và thiết bị).
        private const val VERIFY_STATE_RETRY_COUNT = 3
        private const val VERIFY_STATE_RETRY_DELAY_MS = 1000L
    }

    // Cache mã lệnh bật/tắt (DP code) thực tế của từng thiết bị, vì mỗi loại/model Tuya
    // có thể dùng code khác nhau ("switch", "switch_1", "switch_led"...). Không hardcode "1"
    // như trước nữa — đó là nguyên nhân lỗi "command or value not support".
    private val switchCodeCache = mutableMapOf<String, String>()

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
        logger.i("TuyaManager", "📂 Loaded ${deviceCache.size} devices from DB")
    }

    // ✅ MỚI: trước đây TuyaDeviceDao.deleteDevice() được khai báo trong Database.kt nhưng
    // KHÔNG có nơi nào trong app gọi tới — nghĩa là chưa hề có đường xoá thiết bị Tuya.
    // Hàm này xoá đồng bộ 3 nơi: bản ghi trong SQLite, cache trong bộ nhớ (deviceCache/
    // switchCodeCache), và world_state "tuya:<id>" tương ứng — tránh để lại bản ghi mồ côi
    // vĩnh viễn trong World Model Console (giống cách CameraSkill.deleteCamera() đã làm).
    suspend fun deleteDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val trimmedId = deviceId.trim()

        tuyaDeviceDao.deleteDevice(trimmedId)
        database.worldStateDao().deleteStateBySourceAndId("tuya", trimmedId)

        val cachedName = deviceCache.values.find { it.id == trimmedId }?.name
        if (cachedName != null) {
            deviceCache.remove(cachedName)
        }
        switchCodeCache.remove(trimmedId)

        logger.i("TuyaManager", "🗑️ Đã xoá thiết bị Tuya id=$trimmedId (kèm world_state)")
    }

    suspend fun scanDevices(): Map<String, DeviceInfo> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val clientSecret = prefs[CLIENT_SECRET] ?: ""
        val uid = prefs[UID] ?: ""
        val baseUrl = getApiBaseUrl()

        if (uid.isBlank()) {
            throw Exception("Chưa nhập Tuya UID trong Cài đặt. Vào 'Manage Devices' trên Tuya console, bấm vào tài khoản đã link để lấy UID (dạng ay...) rồi nhập vào Settings.")
        }

        // ✅ MỚI: chụp lại tập id ĐÃ CÓ trong DB trước khi scan ghi đè — dùng để phân biệt
        // "thiết bị mới thêm" (chưa từng xuất hiện ở lần scan trước) với thiết bị cũ đã từng
        // online. Thiết bị vừa link vào Tuya Cloud thường cần vài giây-phút để Cloud nhận
        // heartbeat đầu tiên từ chip Wi-Fi thật; nếu bấm Refresh ngay lúc đó, API Tuya trả
        // online=false dù thiết bị vật lý đã bật — không phải app lỗi, mà Cloud chưa kịp đồng
        // bộ. Trước đây app tin thẳng giá trị này, khiến thiết bị mới cài luôn báo "Mất kết
        // nối" cho tới khi người dùng vào bật/tắt tay (lúc đó Cloud đã kịp đồng bộ nên gọi
        // lệnh thành công, và setDeviceState() tự ép online=true).
        //
        // ✅ MỚI: cùng 1 lần đọc DB này còn dùng để LẤY LẠI 4 cột điều khiển local
        // (localKey/lastKnownIp/protocolVersion/localSwitchDpId) cho từng id — vì
        // insertAllDevices() bên dưới REPLACE nguyên dòng, entity dựng lại từ Cloud API
        // không hề biết các cột này. Không kế thừa ở đây thì mỗi lần Dashboard refresh/
        // đồng bộ nền (scanDevices chạy định kỳ) sẽ xoá sạch local_key đã fetch, buộc
        // fetchLocalKey() phải gọi lại Cloud mỗi lần — đúng thứ ta đang cố tránh.
        val existingEntities = tuyaDeviceDao.getAllDevices().associateBy { it.id }
        val existingIds = existingEntities.keys

        val urlPath = "/v1.0/users/$uid/devices"

        val result = fetchDevicesRaw(clientId, clientSecret, token, baseUrl, urlPath)

        deviceCache.clear()
        val deviceList = mutableListOf<TuyaDeviceEntity>()
        // ✅ SỬA: map riêng để TRẢ VỀ cho caller, khoá theo `id` thật (duy nhất trên Tuya
        // Cloud) — KHÔNG dùng chung key với deviceCache nội bộ (vẫn khoá theo `name` để
        // getDeviceInfo() tra theo tên khi lệnh gõ tay/giọng nói vẫn hoạt động bình thường).
        // Trước đây hàm này trả thẳng deviceCache (khoá theo name) khiến
        // WebhookGatewayService.syncTuyaDeviceStates() tưởng nhầm `name` là `deviceId` và
        // ghi world_state sai khoá — không bao giờ khớp với khoá `id` thật mà
        // SmartSwitchSkill dùng khi bật/tắt qua chat.
        val devicesById = mutableMapOf<String, DeviceInfo>()

        if (result != null) {
            for (i in 0 until result.length()) {
                val device = result.getJSONObject(i)
                val name = device.optString("name")
                val id = device.optString("id")
                var online = device.optBoolean("online", false)
                val category = device.optString("category", "")
                val productName = device.optString("product_name", "")

                // ✅ MỚI: thiết bị mới (chưa có trong DB trước lần scan này) mà báo offline →
                // thử lại vài lần trước khi chấp nhận là offline thật, vì rất có thể chỉ là
                // Cloud Tuya chưa kịp đồng bộ heartbeat đầu tiên. Thiết bị đã tồn tại từ trước
                // thì KHÔNG áp dụng retry này — offline thật cần báo ngay, không trì hoãn.
                if (!online && id.isNotBlank() && id !in existingIds) {
                    online = retryFetchOnlineForNewDevice(
                        deviceId = id,
                        clientId = clientId,
                        clientSecret = clientSecret,
                        baseUrl = baseUrl
                    )
                }

                if (name.isNotBlank() && id.isNotBlank()) {
                    val info = DeviceInfo(id, name, online, category, productName)
                    deviceCache[name] = info
                    devicesById[id] = info
                    logger.i("TuyaManager", "📱 $name → $id (online: $online)")

                    // ✅ MỚI: kế thừa 4 field local từ bản ghi cũ (nếu có) — xem giải thích ở
                    // existingEntities phía trên. Thiết bị lần đầu thấy (existing == null) thì
                    // các field này vẫn là null theo default của TuyaDeviceEntity, hoàn toàn
                    // bình thường — sẽ được fetchLocalKey()/discoverIp() điền vào lần đầu điều
                    // khiển hoặc lần đầu bấm "Kích hoạt điều khiển local" ở UI.
                    val existing = existingEntities[id]
                    deviceList.add(
                        TuyaDeviceEntity(
                            id = id,
                            name = name,
                            online = online,
                            category = category,
                            productName = productName,
                            lastSeen = System.currentTimeMillis(),
                            localKey = existing?.localKey,
                            lastKnownIp = existing?.lastKnownIp,
                            protocolVersion = existing?.protocolVersion,
                            localSwitchDpId = existing?.localSwitchDpId
                        )
                    )
                }
            }
        }

        if (deviceList.isNotEmpty()) {
            tuyaDeviceDao.insertAllDevices(deviceList)
            logger.i("TuyaManager", "💾 Saved ${deviceList.size} devices to DB")
        }

        logger.i("TuyaManager", "✅ Tìm thấy ${devicesById.size} thiết bị")
        devicesById
    }

    // Gọi API /v1.0/users/{uid}/devices, tách riêng để scanDevices() gọn hơn và để
    // retryFetchOnlineForNewDevice() có thể tái sử dụng cùng cơ chế ký request.
    private suspend fun fetchDevicesRaw(
        clientId: String,
        clientSecret: String,
        token: String,
        baseUrl: String,
        urlPath: String
    ): JSONArray? = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()

        val sign = calculateSignature(
            clientId = clientId,
            accessToken = token,
            timestamp = timestamp,
            nonce = nonce,
            secret = clientSecret,
            method = "GET",
            urlPathAndQuery = urlPath,
            bodyStr = ""
        )

        val url = "$baseUrl$urlPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .addHeader("sign", sign)
            .addHeader("t", timestamp.toString())
            .addHeader("nonce", nonce)
            .addHeader("sign_method", "HMAC-SHA256")
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

        json.optJSONArray("result")
    }

    // ✅ MỚI: dò lại trạng thái online của MỘT thiết bị mới thêm bằng cách gọi thẳng
    // /v1.0/devices/{id}/status (không phải API scan danh sách) vài lần với khoảng nghỉ, vì
    // Cloud Tuya có thể mất vài giây-phút để nhận heartbeat đầu tiên từ thiết bị vừa link.
    // Dừng thử ngay khi thấy online=true; nếu hết lượt vẫn false thì trả về false (offline
    // thật, không phải do thiếu thời gian đồng bộ).
    private suspend fun retryFetchOnlineForNewDevice(
        deviceId: String,
        clientId: String,
        clientSecret: String,
        baseUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(NEW_DEVICE_ONLINE_RETRY_COUNT) { attempt ->
            kotlinx.coroutines.delay(NEW_DEVICE_ONLINE_RETRY_DELAY_MS)
            try {
                val online = fetchSingleDeviceOnline(deviceId, clientId, clientSecret, baseUrl)
                logger.i(
                    "TuyaManager",
                    "🔄 Retry đồng bộ trạng thái thiết bị mới $deviceId (lần ${attempt + 1}): online=$online"
                )
                if (online) return@withContext true
            } catch (e: Exception) {
                logger.e("TuyaManager", "Retry đồng bộ trạng thái $deviceId lỗi: ${e.message}", e)
            }
        }
        false
    }

    // Gọi /v1.0/devices/{id} (chi tiết 1 thiết bị, có field "online") — nhẹ hơn gọi lại toàn
    // bộ danh sách scanDevices() chỉ để lấy trạng thái của 1 thiết bị.
    private suspend fun fetchSingleDeviceOnline(
        deviceId: String,
        clientId: String,
        clientSecret: String,
        baseUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val urlPath = "/v1.0/devices/$deviceId"
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()

        val sign = calculateSignature(
            clientId = clientId,
            accessToken = token,
            timestamp = timestamp,
            nonce = nonce,
            secret = clientSecret,
            method = "GET",
            urlPathAndQuery = urlPath,
            bodyStr = ""
        )

        val url = "$baseUrl$urlPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .addHeader("sign", sign)
            .addHeader("t", timestamp.toString())
            .addHeader("nonce", nonce)
            .addHeader("sign_method", "HMAC-SHA256")
            .get()
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Lấy chi tiết thiết bị thất bại: ${response.code}")
        }

        val json = JSONObject(response.body?.string() ?: "")
        val success = json.optBoolean("success")
        if (!success) {
            val msg = json.optString("msg", "Unknown error")
            throw Exception("Device detail API error: $msg")
        }

        json.optJSONObject("result")?.optBoolean("online", false) ?: false
    }

    // ✅ SỬA: nhận cả ID lẫn tên (deviceKey). Ưu tiên tra theo ID trước vì ID là duy nhất
    // trên Tuya Cloud (name có thể trùng nhau giữa nhiều thiết bị). Value đến từ menu chọn
    // "Số N" (AgentKernel.buildNumberedQuestion) và từ dashboard LUÔN là ID; value gõ tay/
    // giọng nói (vd "bật đèn phòng khách") LÀ tên → fallback tra theo tên ở bước 2.
    private suspend fun getDeviceInfo(deviceKey: String): DeviceInfo = withContext(Dispatchers.IO) {
        // 1) Tra theo ID trước — duy nhất, không lo trùng
        deviceCache.values.find { it.id == deviceKey }?.let { return@withContext it }
        tuyaDeviceDao.getDeviceById(deviceKey)?.let { entity ->
            val info = DeviceInfo(
                id = entity.id,
                name = entity.name,
                online = entity.online,
                category = entity.category,
                productName = entity.productName
            )
            deviceCache[entity.id] = info
            return@withContext info
        }

        // 2) Fallback: tra theo TÊN — chỉ dùng khi người dùng gõ tay/nói, không qua menu số.
        val cachedByName = deviceCache[deviceKey]
        if (cachedByName != null) {
            return@withContext cachedByName
        }
        tuyaDeviceDao.getDeviceByName(deviceKey)?.let { entity ->
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

        throw IllegalArgumentException("Không tìm thấy thiết bị '$deviceKey'")
    }

    private suspend fun updateDeviceStatus(deviceId: String, online: Boolean) = withContext(Dispatchers.IO) {
        tuyaDeviceDao.updateOnlineStatus(deviceId, online, System.currentTimeMillis())
        deviceCache.values.find { it.id == deviceId }?.let { info ->
            deviceCache[info.name] = info.copy(online = online)
        }
    }

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (accessToken != null && System.currentTimeMillis() < tokenExpiry) {
                return@withLock accessToken!!
            }
            
            val prefs = context.dataStore.data.first()
            val clientId = prefs[CLIENT_ID] ?: ""
            val clientSecret = prefs[CLIENT_SECRET] ?: ""
            val region = prefs[DATA_CENTER] ?: DEFAULT_REGION
            val baseUrl = getApiBaseUrl()
            
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw IllegalStateException("Chưa cấu hình Tuya Client ID/Secret")
            }
            
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val urlPath = "/v1.0/token?grant_type=1"
            
            // Tính chữ ký theo định dạng chuẩn mới cho API lấy Token
            val sign = calculateSignature(
                clientId = clientId,
                accessToken = null,
                timestamp = timestamp,
                nonce = nonce,
                secret = clientSecret,
                method = "GET",
                urlPathAndQuery = urlPath,
                bodyStr = ""
            )

            logger.i("TuyaManager", "Region=$region BaseUrl=$baseUrl ClientId=$clientId")
            logger.i("TuyaManager", "Timestamp=$timestamp Nonce=$nonce Sign=$sign")

            val url = "$baseUrl$urlPath"
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
            
            val body = response.body?.string().orEmpty()
            logger.i("TuyaManager", "TokenResponse: $body")
            val json = JSONObject(body)
            val success = json.optBoolean("success")
            if (!success) {
                val code = json.optInt("code")
                val msg = json.optString("msg", "Unknown error")
                throw Exception("Token API error: code=$code msg=$msg")
            }
            
            val result = json.optJSONObject("result")
            accessToken = result?.optString("access_token")
            val expireSeconds = result?.optInt("expire_time")?.takeIf { it > 0 } ?: 7200
            tokenExpiry = System.currentTimeMillis() + (expireSeconds - 60) * 1000L
            
            logger.i("TuyaManager", "🔑 Đã lấy token mới")
            accessToken!!
        }
    }

    // Hàm mã hóa SHA256 cho phần Body
    private fun sha256(data: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(data.toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // Mã SHA256 mặc định khi chuỗi rỗng
        }
    }

    // Hàm tạo chữ ký HMAC-SHA256 chung theo tài liệu nhà phát triển Tuya
    private fun calculateSignature(
        clientId: String,
        accessToken: String?,
        timestamp: Long,
        nonce: String,
        secret: String,
        method: String,
        urlPathAndQuery: String,
        bodyStr: String = ""
    ): String {
        val contentSha256 = sha256(bodyStr)
        
        // Cấu trúc chuỗi stringToSign = HTTPMethod + "\n" + Content-SHA256 + "\n" + Headers + "\n" + Url
        // Đoạn Headers để trống ("") nên có 2 dấu xuống dòng liên tiếp
        val stringToSign = "$method\n$contentSha256\n\n$urlPathAndQuery"
        
        val signString = if (accessToken.isNullOrEmpty()) {
            clientId + timestamp.toString() + nonce + stringToSign
        } else {
            clientId + accessToken + timestamp.toString() + nonce + stringToSign
        }
        
        return hmacSha256(signString, secret)
    }

    private fun hmacSha256(data: String, key: String): String {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return hash.joinToString("") { "%02X".format(it) } // Tuya yêu cầu chữ ký dạng in hoa
        } catch (e: Exception) {
            logger.e("TuyaManager", "HMAC error: ${e.message}", e)
            throw e
        }
    }

    private suspend fun getApiBaseUrl(): String {
        val prefs = context.dataStore.data.first()
        val region = prefs[DATA_CENTER] ?: DEFAULT_REGION
        return API_URLS[region]
            ?: throw IllegalStateException("Unsupported Tuya region: $region")
    }

    // ✅ MỚI: gọi Cloud API 1 LẦN DUY NHẤT để lấy local_key — dùng chính client_id/client_secret
    // người dùng đã tự nhập (mỗi khách hàng tự đăng ký Tuya riêng như đã chốt). Sau lần gọi này,
    // setDeviceState()/queryStatus không cần cloud nữa cho việc điều khiển thiết bị.
    suspend fun fetchLocalKey(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken()
            val prefs = context.dataStore.data.first()
            val clientId = prefs[CLIENT_ID] ?: return@withContext null
            val clientSecret = prefs[CLIENT_SECRET] ?: return@withContext null
            val baseUrl = getApiBaseUrl()

            val urlPath = "/v1.0/devices/$deviceId"
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val sign = calculateSignature(
                clientId = clientId, accessToken = token, timestamp = timestamp, nonce = nonce,
                secret = clientSecret, method = "GET", urlPathAndQuery = urlPath
            )
            val request = Request.Builder()
                .url("$baseUrl$urlPath")
                .addHeader("client_id", clientId)
                .addHeader("access_token", token)
                .addHeader("sign", sign)
                .addHeader("t", timestamp.toString())
                .addHeader("nonce", nonce)
                .addHeader("sign_method", "HMAC-SHA256")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                logger.w("TuyaManager", "fetchLocalKey($deviceId) HTTP lỗi: ${response.code}")
                return@withContext null
            }
            val json = JSONObject(response.body?.string() ?: "")
            if (!json.optBoolean("success")) {
                logger.w("TuyaManager", "fetchLocalKey($deviceId) API lỗi: ${json.optString("msg")}")
                return@withContext null
            }
            val localKey = json.optJSONObject("result")?.optString("local_key")
            if (localKey.isNullOrBlank()) null else localKey
        } catch (e: Exception) {
            logger.e("TuyaManager", "fetchLocalKey($deviceId) lỗi: ${e.message}", e)
            null
        }
    }

    // ✅ MỚI: khác resolveSwitchCode() (trả về CODE dạng tên "switch_1" dùng cho Cloud API) — hàm
    // này trả về DP ID dạng SỐ ("1") mà giao thức LOCAL cần trong payload {"dps":{"1": true}}.
    // Tra qua endpoint "functions" (Query Instructions of a Device) rồi khớp theo đúng code đã
    // resolve được ở resolveSwitchCode(), tránh giả định cứng DP="1" (đúng với đa số công tắc đơn
    // nhưng không chắc đúng 100% với thiết bị nhiều kênh/loại khác).
    suspend fun resolveLocalDpId(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val switchCode = resolveSwitchCode(deviceId)
            val token = getAccessToken()
            val prefs = context.dataStore.data.first()
            val clientId = prefs[CLIENT_ID] ?: return@withContext null
            val clientSecret = prefs[CLIENT_SECRET] ?: return@withContext null
            val baseUrl = getApiBaseUrl()

            val urlPath = "/v1.0/devices/$deviceId/functions"
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val sign = calculateSignature(
                clientId = clientId, accessToken = token, timestamp = timestamp, nonce = nonce,
                secret = clientSecret, method = "GET", urlPathAndQuery = urlPath
            )
            val request = Request.Builder()
                .url("$baseUrl$urlPath")
                .addHeader("client_id", clientId)
                .addHeader("access_token", token)
                .addHeader("sign", sign)
                .addHeader("t", timestamp.toString())
                .addHeader("nonce", nonce)
                .addHeader("sign_method", "HMAC-SHA256")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string() ?: "")
            if (!json.optBoolean("success")) return@withContext null

            val functions = json.optJSONObject("result")?.optJSONArray("functions") ?: return@withContext null
            for (i in 0 until functions.length()) {
                val fn = functions.getJSONObject(i)
                if (fn.optString("code") == switchCode) {
                    return@withContext fn.optString("dp_id").ifBlank { null }
                }
            }
            // Không khớp được code nào — fallback "1" (mặc định phổ biến nhất cho công tắc đơn
            // kênh), nhưng log rõ để dễ phát hiện nếu sai với thiết bị nhiều kênh.
            logger.w("TuyaManager", "resolveLocalDpId($deviceId): không khớp được code=$switchCode trong functions, fallback DP=1")
            "1"
        } catch (e: Exception) {
            logger.e("TuyaManager", "resolveLocalDpId($deviceId) lỗi: ${e.message}", e)
            null
        }
    }

    // ✅ MỚI: chuẩn bị đủ 3 thứ cần cho điều khiển local (local_key, DP id, IP LAN hiện tại) —
    // gọi trước mỗi lần setDeviceState() nếu thiết bị chưa có đủ dữ liệu, hoặc IP cũ không còn
    // đúng. Trả về null nếu bất kỳ bước nào thất bại — nơi gọi sẽ tự fallback Cloud API, KHÔNG
    // throw để không làm gián đoạn luồng bật/tắt chính.
    private suspend fun ensureLocalControlReady(deviceId: String): TuyaDeviceEntity? = withContext(Dispatchers.IO) {
        try {
            var entity = tuyaDeviceDao.getDeviceById(deviceId) ?: return@withContext null

            if (entity.localKey.isNullOrBlank()) {
                val key = fetchLocalKey(deviceId) ?: return@withContext null
                val dpId = resolveLocalDpId(deviceId) ?: "1"
                tuyaDeviceDao.updateLocalControlInfo(deviceId, key, entity.lastKnownIp, entity.protocolVersion, dpId)
                entity = tuyaDeviceDao.getDeviceById(deviceId) ?: return@withContext null
            }

            if (entity.lastKnownIp.isNullOrBlank()) {
                // Timeout ngắn (5s, không phải mặc định 12s của discoverIp) — đây là đường
                // "chạy trước mỗi lệnh bật/tắt", cần nhanh, không phải bước dò tìm ban đầu ở UI.
                val discovery = tuyaLocalController.discoverIp(deviceId, timeoutMs = 5_000L)
                    ?: return@withContext null // không cùng LAN / thiết bị offline — fallback cloud là đúng
                tuyaDeviceDao.updateLocalControlInfo(
                    deviceId, entity.localKey, discovery.ip, discovery.version, entity.localSwitchDpId
                )
                entity = tuyaDeviceDao.getDeviceById(deviceId) ?: return@withContext null
            }

            entity
        } catch (e: Exception) {
            logger.d("TuyaManager", "ensureLocalControlReady($deviceId) lỗi: ${e.message}")
            null
        }
    }

    // ✅ MỚI: thử điều khiển LOCAL trước khi rơi về Cloud API. Trả về true nếu local thành công
    // VÀ đã xác minh lại trạng thái thật khớp mong muốn — false trong MỌI trường hợp khác (kể cả
    // lỗi mạng LAN, sai local_key, không cùng Wi-Fi...), để setDeviceState() tự nối tiếp bằng
    // đường Cloud API cũ, không đổi hành vi bên ngoài của hàm turnOn()/turnOff().
    private suspend fun trySetDeviceStateLocal(deviceId: String, dpId: String, state: Boolean): Boolean {
        val entity = ensureLocalControlReady(deviceId) ?: return false
        val ip = entity.lastKnownIp ?: return false
        val localKey = entity.localKey ?: return false
        val version = entity.protocolVersion ?: "3.3"

        val sent = if (version == "3.4") {
            tuyaLocalController.sendCommand34(ip, localKey, deviceId, mapOf(dpId to state))
        } else {
            tuyaLocalController.sendCommand33(ip, localKey, deviceId, mapOf(dpId to state))
        }
        if (!sent) {
            logger.d("TuyaManager", "trySetDeviceStateLocal($deviceId): gửi lệnh local thất bại, IP có thể đã đổi — fallback cloud.")
            return false
        }

        // ⚠️ Xác minh lại chỉ áp dụng cho 3.3 — 3.4 dùng session_key riêng theo từng phiên
        // (không phải local_key), TuyaLocalController hiện chưa có queryStatus34() nên KHÔNG
        // gọi nhầm queryStatus33() ở đây (chắc chắn fail vì sai key, khiến local 3.4 luôn bị coi
        // là thất bại dù gửi lệnh thực ra đã thành công). Với 3.4, tạm tin thẳng kết quả
        // sendCommand34() — chấp nhận rủi ro không xác minh được, nhất quán với cảnh báo "chưa
        // test với thiết bị thật" đã ghi trong TuyaLocalController.
        if (version == "3.4") {
            logger.i("TuyaManager", "⚡ ($deviceId) đã gửi lệnh LOCAL 3.4 qua $ip (chưa xác minh lại được — xem ghi chú TuyaLocalController).")
            return true
        }

        // Xác minh lại NGAY bằng chính đường local (không tốn thêm 1 round-trip cloud) — chỉ 1
        // lần kiểm tra (khác verifyDeviceStateApplied() bên cloud retry nhiều lần), vì local
        // round-trip nhanh hơn nhiều, độ trễ lan truyền DP gần như không đáng kể trên LAN.
        kotlinx.coroutines.delay(300)
        val status = tuyaLocalController.queryStatus33(ip, localKey, deviceId) ?: return false
        val actual = when (val v = status[dpId]) {
            is Boolean -> v
            is Int -> v == 1
            is String -> v == "true" || v == "1"
            else -> null
        }
        if (actual != state) {
            logger.d("TuyaManager", "trySetDeviceStateLocal($deviceId): gửi được nhưng xác minh không khớp (thực tế=$actual, mong muốn=$state) — fallback cloud.")
            return false
        }
        logger.i("TuyaManager", "⚡ ($deviceId) điều khiển LOCAL thành công qua $ip — không cần gọi Cloud API.")
        return true
    }

    // ✅ MỚI: wrapper PUBLIC cho ensureLocalControlReady() (vốn private, chỉ tự chạy ngầm mỗi
    // lần turnOn()/turnOff()) — để UI (nút "Bật điều khiển nhanh" trong TuyaScreen, hoặc từ
    // màn "Sức khoẻ hệ thống") có thể chủ động kích hoạt điều khiển local NGAY, không cần đợi
    // người dùng bật/tắt thiết bị 1 lần trước đó. KHÔNG lặp lại logic fetchLocalKey/discoverIp
    // — gọi thẳng hàm private đã có, cùng 1 nguồn sự thật duy nhất.
    suspend fun enableLocalControl(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = ensureLocalControlReady(deviceId)
        entity != null && !entity.localKey.isNullOrBlank() && !entity.lastKnownIp.isNullOrBlank()
    }

    suspend fun turnOn(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, true)
        logger.i("TuyaManager", "💡 BẬT ${device.name}")
    }

    suspend fun turnOff(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, false)
        logger.i("TuyaManager", "💡 TẮT ${device.name}")
    }

    // Gọi API lấy danh sách trạng thái (status) thô của thiết bị — dùng chung cho getStatus() và resolveSwitchCode()
    private suspend fun fetchDeviceStatusList(deviceId: String): JSONArray = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val clientSecret = prefs[CLIENT_SECRET] ?: ""
        val baseUrl = getApiBaseUrl()

        val urlPath = "/v1.0/devices/$deviceId/status"
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()

        val sign = calculateSignature(
            clientId = clientId,
            accessToken = token,
            timestamp = timestamp,
            nonce = nonce,
            secret = clientSecret,
            method = "GET",
            urlPathAndQuery = urlPath,
            bodyStr = ""
        )

        val url = "$baseUrl$urlPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .addHeader("sign", sign)
            .addHeader("t", timestamp.toString())
            .addHeader("nonce", nonce)
            .addHeader("sign_method", "HMAC-SHA256")
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

        json.optJSONArray("result") ?: org.json.JSONArray()
    }

    // Tự động dò mã lệnh bật/tắt (DP code) thật của thiết bị từ danh sách status trả về,
    // vì mỗi model Tuya có thể dùng code khác nhau: "switch", "switch_1", "switch_led"...
    // Kết quả được cache lại theo deviceId để không phải gọi lại API mỗi lần điều khiển.
    private suspend fun resolveSwitchCode(deviceId: String): String {
        switchCodeCache[deviceId]?.let { return it }

        val statusList = fetchDeviceStatusList(deviceId)
        val codes = (0 until statusList.length()).map { statusList.getJSONObject(it).optString("code") }

        // Ưu tiên theo thứ tự phổ biến nhất của Tuya cho nhóm Switch/Socket/Light
        val preferredOrder = listOf("switch_led", "switch", "switch_1")
        val resolved = preferredOrder.firstOrNull { it in codes }
            ?: codes.firstOrNull { it.startsWith("switch") }
            ?: throw Exception("Không tìm thấy mã lệnh bật/tắt phù hợp cho thiết bị này (codes: $codes)")

        switchCodeCache[deviceId] = resolved
        logger.i("TuyaManager", "🔎 Đã dò mã lệnh bật/tắt cho $deviceId: $resolved")
        return resolved
    }

    // ✅ MỚI (tối ưu quota API): Tuya có endpoint /v1.0/iot-03/devices/status nhận TỐI ĐA 20
    // device_ids cùng lúc và trả trạng thái của tất cả trong 1 response — thay vì gọi
    // fetchDeviceStatusList() riêng cho từng thiết bị (N call), giờ chỉ tốn ceil(N/20) call.
    // Dùng cho cả vòng lặp nền (syncTuyaDeviceStates) lẫn Dashboard (getDashboardNodes) —
    // đây là 2 nơi tốn call nhiều nhất vì lặp qua từng thiết bị.
    //
    // Trả về Map<deviceId, isOn>. Thiết bị không dò được switch code (model lạ, hoặc chưa
    // từng bật/tắt lần nào) sẽ bị bỏ qua khỏi map — caller nên coi thiếu key là "không rõ",
    // không phải false.
    suspend fun getStatusBatch(deviceIds: List<String>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        if (deviceIds.isEmpty()) return@withContext emptyMap()

        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val clientSecret = prefs[CLIENT_SECRET] ?: ""
        val baseUrl = getApiBaseUrl()

        val result = mutableMapOf<String, Boolean>()

        // Giới hạn cứng của Tuya: tối đa 20 device_ids/lần gọi -> chia batch nếu khách có
        // nhiều hơn 20 thiết bị (vẫn rẻ hơn rất nhiều so với gọi từng thiết bị một).
        deviceIds.chunked(20).forEach { chunk ->
            val urlPath = "/v1.0/iot-03/devices/status?device_ids=${chunk.joinToString(",")}"
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()

            val sign = calculateSignature(
                clientId = clientId,
                accessToken = token,
                timestamp = timestamp,
                nonce = nonce,
                secret = clientSecret,
                method = "GET",
                urlPathAndQuery = urlPath,
                bodyStr = ""
            )

            val url = "$baseUrl$urlPath"
            val request = Request.Builder()
                .url(url)
                .addHeader("client_id", clientId)
                .addHeader("access_token", token)
                .addHeader("sign", sign)
                .addHeader("t", timestamp.toString())
                .addHeader("nonce", nonce)
                .addHeader("sign_method", "HMAC-SHA256")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                logger.e("TuyaManager", "Batch status thất bại (${chunk.size} thiết bị): ${response.code}")
                return@forEach
            }

            val json = JSONObject(response.body?.string() ?: "")
            if (!json.optBoolean("success")) {
                logger.e("TuyaManager", "Batch status API error: ${json.optString("msg", "Unknown error")}")
                return@forEach
            }

            val arr = json.optJSONArray("result") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val deviceStatus = arr.getJSONObject(i)
                val deviceId = deviceStatus.optString("id")
                if (deviceId.isBlank()) continue

                val statusList = deviceStatus.optJSONArray("status") ?: JSONArray()
                val codes = (0 until statusList.length()).map { statusList.getJSONObject(it).optString("code") }

                // Dùng switchCodeCache đã có nếu có; nếu chưa, tự dò ngay từ response này
                // (cùng logic ưu tiên với resolveSwitchCode) và cache lại luôn — không tốn
                // thêm call nào để dò code.
                val switchCode = switchCodeCache[deviceId] ?: run {
                    val preferredOrder = listOf("switch_led", "switch", "switch_1")
                    val resolved = preferredOrder.firstOrNull { it in codes }
                        ?: codes.firstOrNull { it.startsWith("switch") }
                    if (resolved != null) switchCodeCache[deviceId] = resolved
                    resolved
                }

                if (switchCode == null) {
                    logger.e("TuyaManager", "Batch status: không dò được switch code cho $deviceId (codes: $codes)")
                    continue
                }

                for (j in 0 until statusList.length()) {
                    val status = statusList.getJSONObject(j)
                    if (status.optString("code") == switchCode) {
                        val value = status.opt("value")
                        result[deviceId] = when (value) {
                            is Boolean -> value
                            is Int -> value == 1
                            is String -> value == "true" || value == "1"
                            else -> false
                        }
                        break
                    }
                }
            }
        }

        result
    }

    suspend fun getStatus(deviceName: String): Boolean = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        val switchCode = resolveSwitchCode(device.id)
        val result = fetchDeviceStatusList(device.id)

        // ✅ SỬA: dùng biến trung gian + break thay vì return@withContext lồng trong
        // if/for — cách cũ khiến trình biên dịch Kotlin (K2) báo lỗi "Missing return
        // statement" vì không chứng minh được mọi nhánh đều return.
        var statusResult = false
        for (i in 0 until result.length()) {
            val status = result.getJSONObject(i)
            if (status.optString("code") == switchCode) {
                val value = status.opt("value")
                statusResult = when (value) {
                    is Boolean -> value
                    is Int -> value == 1
                    is String -> value == "true" || value == "1"
                    else -> false
                }
                break
            }
        }

        statusResult
    }

    // ✅ SỬA: trước đây hàm này luôn kết thúc bằng updateDeviceStatus(device.id, true) — tức
    // là chỉ cần API POST /commands trả success=true (nghĩa là Tuya Cloud ĐÃ NHẬN lệnh) là app
    // coi như thiết bị đã online VÀ đã đổi trạng thái theo đúng ý muốn. Hai điều đó không đảm
    // bảo nhau: Cloud nhận lệnh không có nghĩa thiết bị vật lý đã thực thi (mất Wi-Fi cục bộ,
    // relay kẹt, điện áp yếu...). Kết quả: app báo "Đã bật" nhưng ổ cắm thực tế vẫn tắt.
    //
    // Giờ sau khi gửi lệnh, đợi một nhịp ngắn rồi gọi lại API status thật (fetchDeviceStatusList)
    // để xác minh switch code đã đổi đúng giá trị `state` mong muốn chưa — đây mới là nguồn sự
    // thật, không phải việc Cloud có nhận lệnh hay không. Nếu xác minh khớp: coi là thành công
    // thật và cập nhật online=true (thiết bị chắc chắn đang phản hồi được). Nếu không khớp: ném
    // lỗi rõ ràng cho người dùng biết lệnh KHÔNG có tác dụng, thay vì báo thành công giả.
    private suspend fun setDeviceState(device: DeviceInfo, state: Boolean) = withContext(Dispatchers.IO) {
        // ✅ MỚI: thử LOCAL trước — chỉ khi thành công VÀ xác minh khớp mới return sớm, bỏ qua
        // toàn bộ phần Cloud API bên dưới. Bất kỳ lý do gì khiến local không chắc chắn (chưa có
        // local_key, không cùng LAN, sai version, timeout...) đều rơi thẳng xuống code Cloud API
        // CŨ, KHÔNG THAY ĐỔI, nên hành vi bên ngoài của turnOn()/turnOff() không đổi kể cả khi
        // local luôn thất bại — an toàn để bật tính năng này mà không lo regression.
        val localDpId = tuyaDeviceDao.getDeviceById(device.id)?.localSwitchDpId
        if (localDpId != null && trySetDeviceStateLocal(device.id, localDpId, state)) {
            updateDeviceStatus(device.id, true)
            return@withContext
        }

        // ✅ MỚI: "Chế độ Local-only" (LOCAL_ONLY_MODE_ENABLED) đang bật — Local vừa thất bại (hoặc
        // thiết bị chưa từng resolve được dpId). KHÔNG rơi xuống Cloud API bên dưới nữa trong mọi
        // trường hợp — ném lỗi thẳng ở đây. Việc thử tiếp qua Gateway → Camera Node ở nhà (đã có
        // sẵn hạ tầng thật: WebhookGatewayService.sendDeviceCommandToHome() + handleTuyaCommand()
        // phía Camera Node, tái dùng lại chính turnOn()/turnOff() này) là trách nhiệm của TẦNG GỌI
        // (SmartSwitchSkill.handleSet()), KHÔNG đặt trong TuyaManager — vì gọi gateway cần
        // DeviceCommandGatewayClient (POST bất đồng bộ, có khái niệm "đã gửi, đang chờ Camera Node
        // xử lý" khác hẳn với true/false đồng bộ mà setDeviceState() trả về), và vì TuyaManager
        // đang được chính Camera Node TÁI SỬ DỤNG để THỰC THI lệnh nhận từ gateway — nếu
        // TuyaManager tự gọi gateway ở đây, một lệnh gửi tới Camera Node có thể vòng lặp lại gateway
        // vô hạn nếu Camera Node đó lại không có Local sẵn.
        if (configProvider.getBoolean(AppConfigDefaults.LOCAL_ONLY_MODE_ENABLED)) {
            throw Exception(
                "Chế độ Local-only đang bật: điều khiển LOCAL thất bại và Cloud đã bị tắt cho thiết bị này."
            )
        }

        val token = getAccessToken()
        val prefs = context.dataStore.data.first()
        val clientId = prefs[CLIENT_ID] ?: ""
        val clientSecret = prefs[CLIENT_SECRET] ?: ""
        val baseUrl = getApiBaseUrl()
        val switchCode = resolveSwitchCode(device.id)

        val urlPath = "/v1.0/devices/${device.id}/commands"
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        
        val bodyJson = JSONObject().apply {
            put("commands", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("code", switchCode)
                    put("value", state)
                })
            })
        }
        val bodyStr = bodyJson.toString()
        
        // Tính chữ ký chứa SHA256 mã hóa của Request Body (vì là request POST)
        val sign = calculateSignature(
            clientId = clientId,
            accessToken = token,
            timestamp = timestamp,
            nonce = nonce,
            secret = clientSecret,
            method = "POST",
            urlPathAndQuery = urlPath,
            bodyStr = bodyStr
        )
        
        val url = "$baseUrl$urlPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("client_id", clientId)
            .addHeader("access_token", token)
            .addHeader("sign", sign)
            .addHeader("t", timestamp.toString())
            .addHeader("nonce", nonce)
            .addHeader("sign_method", "HMAC-SHA256")
            .addHeader("Content-Type", "application/json")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
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

        // ✅ MỚI: Cloud nhận lệnh (success=true) KHÔNG đồng nghĩa thiết bị đã thực thi — xác
        // minh lại bằng trạng thái thật, thử vài lần vì có độ trễ lan truyền giữa lúc Cloud
        // nhận lệnh và lúc thiết bị report lại DP mới.
        val actualState = verifyDeviceStateApplied(device.id, switchCode, expectedState = state)

        if (!actualState) {
            // Lệnh đã gửi nhưng thiết bị không đổi trạng thái thật — báo lỗi rõ ràng thay vì
            // im lặng coi là thành công. online vẫn có thể true (Cloud vẫn thấy thiết bị kết
            // nối) nên chỉ cập nhật cột online theo thực tế, không đụng vào phần thất bại này.
            updateDeviceStatus(device.id, true)
            throw Exception("Lệnh đã gửi tới Tuya Cloud nhưng thiết bị không phản hồi đổi trạng thái thật. Có thể thiết bị mất kết nối cục bộ hoặc phần cứng không phản hồi.")
        }

        updateDeviceStatus(device.id, true)
    }

    // Đợi một nhịp ngắn rồi gọi lại status thật từ Tuya Cloud để xác minh thiết bị đã đổi đúng
    // giá trị mong muốn chưa. Thử vài lần vì độ trễ lan truyền DP giữa lệnh POST /commands và
    // lúc GET /status phản ánh đúng giá trị mới có thể mất 1-2 giây.
    private suspend fun verifyDeviceStateApplied(
        deviceId: String,
        switchCode: String,
        expectedState: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        repeat(VERIFY_STATE_RETRY_COUNT) { attempt ->
            kotlinx.coroutines.delay(VERIFY_STATE_RETRY_DELAY_MS)
            try {
                val statusList = fetchDeviceStatusList(deviceId)
                for (i in 0 until statusList.length()) {
                    val status = statusList.getJSONObject(i)
                    if (status.optString("code") == switchCode) {
                        val value = status.opt("value")
                        val current = when (value) {
                            is Boolean -> value
                            is Int -> value == 1
                            is String -> value == "true" || value == "1"
                            else -> false
                        }
                        logger.i(
                            "TuyaManager",
                            "🔍 Xác minh trạng thái $deviceId (lần ${attempt + 1}): thực tế=$current, mong muốn=$expectedState"
                        )
                        if (current == expectedState) return@withContext true
                    }
                }
            } catch (e: Exception) {
                logger.e("TuyaManager", "Xác minh trạng thái $deviceId lỗi: ${e.message}", e)
            }
        }
        false
    }
}