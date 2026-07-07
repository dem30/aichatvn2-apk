package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.utils.Logger
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IThingActivatorGetToken // ✅ ĐÃ SỬA: Đúng Package SDK mới
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback // ✅ ĐÃ SỬA: Đúng Package SDK mới
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.builder.ActivatorBuilder
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.sdk.api.IResultCallback
import com.thingclips.smart.sdk.api.IThingActivator
import com.thingclips.smart.sdk.api.IThingDevice
import com.thingclips.smart.sdk.api.IThingSmartActivatorListener
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.api.ILogoutCallback // ✅ ĐÃ THÊM: Import interface Logout Callback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.sdk.bean.DeviceBean
import com.thingclips.smart.sdk.enums.ActivatorModelEnum
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class TuyaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tuyaDeviceDao: TuyaDeviceDao,
    private val logger: Logger
) {
    companion object {
        private const val POWER_DPS_KEY = "1" // Mã DP ID nguồn tiêu chuẩn của đa số thiết bị điện Tuya
    }

    private val mutex = Mutex()
    private val deviceCache = mutableMapOf<String, DeviceInfo>()
    private var activeActivator: IThingActivator? = null

    // ✅ ĐÃ THÊM: Scope chạy ngầm riêng để thực thi các tác vụ SQLite từ Callback phi tuần tự của SDK
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class DeviceInfo(
        val id: String,
        val name: String,
        val online: Boolean = false,
        val category: String = "",
        val productName: String = ""
    )

    /**
     * Đồng bộ thiết bị cục bộ từ SQLite Database vào cache RAM
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
        logger.i("TuyaManager", "📂 Loaded ${deviceCache.size} devices from DB")
    }

    /**
     * Quét thiết bị: Tự động truy vấn danh sách thiết bị của Home đầu tiên (hoặc Home mặc định)
     * để tương thích ngược với API cũ, cập nhật vào SQLite cục bộ và trả về Map.
     */
    suspend fun scanDevices(): Map<String, DeviceInfo> = withContext(Dispatchers.IO) {
        val homes = getHomeList()
        if (homes.isEmpty()) {
            logger.w("TuyaManager", "⚠️ Không tìm thấy ngôi nhà nào liên kết với tài khoản này.")
            return@withContext emptyMap()
        }
        // Chọn Home đầu tiên làm mặc định để đồng bộ hóa
        val defaultHomeId = homes.first().homeId
        return@withContext syncDevicesFromHome(defaultHomeId)
    }

    /**
     * Lấy thông tin thiết bị từ cache RAM hoặc truy vấn từ SQLite nếu chưa nạp
     */
    private suspend fun getDeviceInfo(deviceName: String): DeviceInfo = withContext(Dispatchers.IO) {
        val cached = deviceCache[deviceName]
        if (cached != null) {
            return@withContext cached
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
            return@withContext info
        }
        
        throw IllegalArgumentException("Không tìm thấy thiết bị '$deviceName' trong cơ sở dữ liệu cục bộ.")
    }

    /**
     * Cập nhật trạng thái trực tuyến của thiết bị vào SQLite và Cache
     */
    private suspend fun updateDeviceStatus(deviceId: String, online: Boolean) = withContext(Dispatchers.IO) {
        tuyaDeviceDao.updateOnlineStatus(deviceId, online, System.currentTimeMillis())
        deviceCache.values.find { it.id == deviceId }?.let { info ->
            deviceCache[info.name] = info.copy(online = online)
        }
    }

    /**
     * API Tương thích ngược: Trả về Token session hiện tại của tài khoản người dùng
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        return@withContext ThingHomeSdk.getUserInstance().user?.sid ?: ""
    }

    /**
     * Kiểm tra trạng thái đã đăng nhập hay chưa
     */
    fun isLoggedIn(): Boolean {
        return ThingHomeSdk.getUserInstance().isLogin
    }

    /**
     * Đăng nhập tài khoản Smart Life bằng Email thông qua SDK
     */
    suspend fun login(email: String, password: String, countryCode: String = "84"): Boolean {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                ThingHomeSdk.getUserInstance().loginWithEmail(
                    countryCode,
                    email,
                    password,
                    object : ILoginCallback {
                        override fun onSuccess(user: User?) {
                            logger.i("TuyaManager", "✅ Đăng nhập thành công: ${user?.username}")
                            continuation.resume(true)
                        }

                        override fun onError(code: String?, error: String?) {
                            logger.e("TuyaManager", "❌ Lỗi đăng nhập SDK: $error (Mã: $code)")
                            continuation.resumeWithException(Exception(error ?: "Unknown login error"))
                        }
                    }
                )
            }
        }
    }

    /**
     * Đăng xuất tài khoản người dùng Tuya khỏi SDK
     */
    suspend fun logout() {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                // ✅ ĐA SỬA: Sử dụng ILogoutCallback thay cho IResultCallback lỗi kiểu dữ liệu
                ThingHomeSdk.getUserInstance().logout(object : ILogoutCallback {
                    override fun onSuccess() {
                        logger.i("TuyaManager", "🔌 Đã đăng xuất tài khoản thành công.")
                        deviceCache.clear()
                        continuation.resume(Unit)
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        logger.e("TuyaManager", "❌ Lỗi đăng xuất SDK: $errorMsg (Mã: $errorCode)")
                        continuation.resumeWithException(Exception(errorMsg ?: "Logout error"))
                    }
                })
            }
        }
    }

    /**
     * Truy vấn danh sách các ngôi nhà (Home List) từ đám mây Tuya
     */
    suspend fun getHomeList(): List<HomeBean> {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                ThingHomeSdk.getHomeManagerInstance().queryHomeList(object : IThingGetHomeListCallback {
                    override fun onSuccess(homeBeans: List<HomeBean>?) {
                        continuation.resume(homeBeans ?: emptyList())
                    }

                    override fun onError(errorCode: String?, error: String?) {
                        logger.e("TuyaManager", "❌ Lỗi lấy danh sách Home: $error")
                        continuation.resumeWithException(Exception(error ?: "Get home list error"))
                    }
                })
            }
        }
    }

    /**
     * Đồng bộ hóa và ghi đè danh sách thiết bị từ một Ngôi nhà cụ thể vào SQLite DB
     */
    suspend fun syncDevicesFromHome(homeId: Long): Map<String, DeviceInfo> {
        return withContext(Dispatchers.IO) {
            val homeBean = suspendCancellableCoroutine<HomeBean> { continuation ->
                // ✅ ĐÃ SỬA: Ép kiểu ẩn danh chuẩn IThingHomeResultCallback
                ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(object : IThingHomeResultCallback {
                    override fun onSuccess(bean: HomeBean?) {
                        if (bean != null) {
                            continuation.resume(bean)
                        } else {
                            continuation.resumeWithException(Exception("Dữ liệu Home rỗng"))
                        }
                    }

                    override fun onError(errorCode: String?, errorMsg: String?) {
                        continuation.resumeWithException(Exception(errorMsg ?: "Error getting home details"))
                    }
                })
            }

            val deviceList = homeBean.deviceList ?: emptyList()
            val entities = mutableListOf<TuyaDeviceEntity>()
            deviceCache.clear()

            deviceList.forEach { dev ->
                val name = dev.name
                val id = dev.devId
                if (!name.isNullOrBlank() && !id.isNullOrBlank()) {
                    // ✅ ĐÃ SỬA: Thay thế thuộc tính productName không tồn tại bằng productId
                    val pName = dev.productId ?: ""
                    val info = DeviceInfo(
                        id = id,
                        name = name,
                        online = dev.isOnline,
                        category = dev.category ?: "",
                        productName = pName
                    )
                    deviceCache[name] = info

                    entities.add(
                        TuyaDeviceEntity(
                            id = id,
                            name = name,
                            online = dev.isOnline,
                            category = dev.category ?: "",
                            productName = pName,
                            lastSeen = System.currentTimeMillis()
                        )
                    )
                }
            }

            if (entities.isNotEmpty()) {
                tuyaDeviceDao.insertAllDevices(entities)
                logger.i("TuyaManager", "💾 Đã lưu thành công ${entities.size} thiết bị của Home [ID: $homeId] vào SQLite DB")
            }

            return@withContext deviceCache
        }
    }

    /**
     * Bật thiết bị thông minh thông qua SDK
     */
    suspend fun turnOn(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, true)
        logger.i("TuyaManager", "💡 BẬT thiết bị: ${device.name}")
    }

    /**
     * Tắt thiết bị thông minh thông qua SDK
     */
    suspend fun turnOff(deviceName: String) = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        setDeviceState(device, false)
        logger.i("TuyaManager", "🔌 TẮT thiết bị: ${device.name}")
    }

    /**
     * Đọc trạng thái Bật/Tắt hiện tại của thiết bị (Đọc từ Cache trạng thái cục bộ để tăng tốc)
     */
    suspend fun getStatus(deviceName: String): Boolean = withContext(Dispatchers.IO) {
        val device = getDeviceInfo(deviceName)
        val deviceBean = ThingHomeSdk.getDataInstance().getDeviceBean(device.id)
        val dps = deviceBean?.getDps()
        
        val value = dps?.get(POWER_DPS_KEY)
        return@withContext when (value) {
            is Boolean -> value
            is Int -> value == 1
            is String -> value == "true" || value == "1"
            else -> false
        }
    }

    /**
     * Thiết lập trạng thái rơ-le / nguồn của thiết bị
     */
    private suspend fun setDeviceState(device: DeviceInfo, state: Boolean) = withContext(Dispatchers.IO) {
        val mDevice = ThingHomeSdk.newDeviceInstance(device.id)
        val dpsCommand = "{\"$POWER_DPS_KEY\": $state}"
        
        suspendCancellableCoroutine<Unit> { continuation ->
            mDevice.publishDps(dpsCommand, object : IResultCallback {
                override fun onSuccess() {
                    // Cập nhật trạng thái trực tuyến cục bộ sau khi điều khiển thành công
                    continuation.resume(Unit)
                }

                override fun onError(code: String?, error: String?) {
                    continuation.resumeWithException(Exception("Lỗi điều khiển DP: $error (Mã: $code)"))
                }
            })
        }
        updateDeviceStatus(device.id, true)
    }

    /**
     * Khởi chạy tiến trình tìm kiếm ghép nối thiết bị qua Wi-Fi EZ Mode (SmartConfig)
     */
    suspend fun startEzPairing(
        ssid: String,
        password: String,
        homeId: Long,
        onDevicePaired: (DeviceBean) -> Unit,
        onError: (String, String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 1. Sinh Token kết nối mới từ đám mây (Token hợp lệ trong 10 phút)
                val token = suspendCancellableCoroutine<String> { continuation ->
                    // ✅ ĐÃ SỬA: Ép kiểu anonymous cho callback lấy Token kết nối
                    ThingHomeSdk.getActivatorInstance().getActivatorToken(homeId, object : IThingActivatorGetToken {
                        override fun onSuccess(token: String) {
                            continuation.resume(token)
                        }

                        override fun onFailure(errorCode: String?, errorMsg: String?) {
                            continuation.resumeWithException(
                                Exception("Không thể lấy Token kết nối: $errorMsg (Mã: $errorCode)")
                            )
                        }
                    })
                }

                // 2. Thiết lập trình cấu hình ghép nối Wi-Fi EZ Mode
                val builder = ActivatorBuilder()
                    .setSsid(ssid)
                    .setPassword(password)
                    .setContext(context)
                    // ✅ ĐÃ SỬA: Chuyển TY_EZ thành THING_EZ cho đúng hằng số enum trong SDK mới
                    .setActivatorModel(ActivatorModelEnum.THING_EZ)
                    .setTimeOut(100)
                    .setToken(token)
                    .setListener(object : IThingSmartActivatorListener {
                        override fun onError(errorCode: String?, errorMsg: String?) {
                            logger.e("TuyaManager", "❌ Lỗi ghép nối thiết bị: $errorMsg")
                            onError(errorCode ?: "ERROR", errorMsg ?: "Unknown error")
                        }

                        override fun onActiveSuccess(devResp: DeviceBean?) {
                            devResp?.let { dev ->
                                logger.i("TuyaManager", "🎉 Ghép nối thành công thiết bị: ${dev.name} [ID: ${dev.devId}]")
                                
                                // ✅ ĐÃ SỬA: Thay thế productName không tồn tại bằng productId
                                val pName = dev.productId ?: ""
                                val entity = TuyaDeviceEntity(
                                    id = dev.devId,
                                    name = dev.name ?: "Thiết bị mới",
                                    online = dev.isOnline,
                                    category = dev.category ?: "",
                                    productName = pName,
                                    lastSeen = System.currentTimeMillis()
                                )
                                
                                deviceCache[entity.name] = DeviceInfo(
                                    id = entity.id,
                                    name = entity.name,
                                    online = entity.online,
                                    category = entity.category,
                                    productName = entity.productName
                                )

                                // ✅ ĐÃ SỬA: Chạy insert SQLite bằng serviceScope cục bộ thay vì gọi trực tiếp suspend
                                serviceScope.launch {
                                    tuyaDeviceDao.insertDevice(entity)
                                }
                                onDevicePaired(dev)
                            }
                        }

                        override fun onStep(step: String?, data: Any?) {
                            logger.d("TuyaManager", "Pairing Step: $step, Data: $data")
                        }
                    })

                stopEzPairing()

                // 3. Khởi chạy tác vụ kết nối
                val activator = ThingHomeSdk.getActivatorInstance().newMultiActivator(builder)
                activeActivator = activator
                activator.start()
                logger.i("TuyaManager", "📶 Bắt đầu phát sóng quét Wi-Fi tìm thiết bị...")
            } catch (e: Exception) {
                logger.e("TuyaManager", "❌ Exception in startEzPairing: ${e.message}", e)
                onError("EXCEPTION", e.message ?: "Unknown exception")
            }
        }
    }

    /**
     * Dừng và hủy bỏ tiến trình ghép nối Wi-Fi đang chạy
     */
    fun stopEzPairing() {
        activeActivator?.stop()
        activeActivator?.onDestroy()
        activeActivator = null
        logger.i("TuyaManager", "📶 Đã dừng tiến trình quét và giải phóng trình ghép nối.")
    }
}