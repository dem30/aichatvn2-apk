package com.aichatvn.agent.config

import android.content.Context
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.getDatabase(context).appConfigDao() }

    val allConfigs: StateFlow<List<AppConfigEntity>> = dao
        .getAllConfigsFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ✅ MỚI: báo hiệu riêng cho "cấu hình vừa đổi, có thể ảnh hưởng tới kết nối mạng nền
    // (deviceCode, gateway URL/token, TURN...)" — KHÔNG dùng allConfigs cho việc này vì
    // allConfigs bắn lại mỗi khi BẤT KỲ dòng nào trong app_config đổi (kể cả các key không
    // liên quan mạng, ví dụ camera.cooldown_ms), sẽ khiến WebhookGatewayService ngắt kết nối
    // SSE không cần thiết quá thường xuyên. Giữ extraBufferCapacity=1 + replay không cần —
    // đây là "tick" tức thời, người lắng nghe (Service) đã chạy sẵn từ lúc onCreate, không
    // cần replay nhỡ trước khi đăng ký; tryEmit không bao giờ chặn/mất tín hiệu vì bộ đệm 1
    // đã đủ dồn nhiều lần đổi liên tiếp thành 1 lần restart SSE.
    private val _configUpdatedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configUpdatedEvent: SharedFlow<Unit> = _configUpdatedEvent.asSharedFlow()

    fun configsByPlugin(pluginId: String): Flow<List<AppConfigEntity>> =
        dao.getConfigsByPluginFlow(pluginId)

    init {
        scope.launch {
            seedDefaults()
            cleanupDeadKeys()
        }
    }

    // ─────────────────────────── DỌN DẸP KEY RÁC ───────────────────
    // ✅ MỚI: Các key cấu hình đã bị xoá khỏi AppConfigDefaults.kt (không còn nơi nào đọc/ghi)
    // nhưng vẫn có thể còn tồn tại trong DB thật của máy đã seed từ bản build cũ hơn — vì
    // AppConfigDefaults.kt cố tình không xoá dòng cũ trong DB thật để tránh viết Migration.
    // Màn Settings liệt kê NGUYÊN VẸN mọi dòng có trong DB (không lọc theo AppConfigDefaults),
    // nên các key rác này cứ nằm ì hiển thị mãi — và nút Reset cũng không dọn được vì nó cần
    // tìm default tương ứng trong AppConfigDefaults (đã bị xoá) mới chạy. Xoá thẳng ở đây,
    // 1 lần mỗi lúc khởi động app — dao.delete() trên key không tồn tại chỉ là no-op an toàn.
    private val deadConfigKeys = listOf(
        "schedule.camera_scan_interval_min" // rác từ kiến trúc TaskScheduler cũ, xem AppConfigDefaults.kt
    )

    // ✅ SỬA: public hoá — ngoài lúc app khởi động, còn cần gọi lại đúng lúc sau khi
    // SettingsViewModel.importSettings() phục hồi bảng app_config từ file backup, vì backup cũ
    // (từ trước khi key này bị xoá khỏi AppConfigDefaults) sẽ ghi thẳng dòng rác trở lại DB,
    // vượt qua hoàn toàn lượt dọn dẹp lúc khởi động (đã chạy xong từ trước khi import diễn ra).
    suspend fun cleanupDeadKeys() = withContext(Dispatchers.IO) {
        deadConfigKeys.forEach { key ->
            try {
                dao.delete(key)
            } catch (e: Exception) {
                logger.e("AppConfigProvider", "cleanupDeadKeys lỗi với key '$key': ${e.message}", e)
            }
        }
    }

    // ─────────────────────────── SEED ────────────────────────────

    private suspend fun seedDefaults() = withContext(Dispatchers.IO) {
        try {
            AppConfigDefaults.all().forEach { default ->
                dao.insertIfAbsent(default)
            }
            logger.d("AppConfigProvider", "✅ Seed config defaults hoàn tất")
        } catch (e: Exception) {
            logger.e("AppConfigProvider", "seedDefaults error: ${e.message}", e)
        }
    }

    // ─────────────────────────── READ ────────────────────────────

    suspend fun getString(key: String, default: String = ""): String = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value ?: default
    }

    suspend fun getInt(key: String, default: Int = 0): Int = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toIntOrNull() ?: default
    }

    suspend fun getLong(key: String, default: Long = 0L): Long = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toLongOrNull() ?: default
    }

    suspend fun getFloat(key: String, default: Float = 0f): Float = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toFloatOrNull() ?: default
    }

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toBooleanStrictOrNull() ?: default
    }

    // ─────────────── OBSERVE (Flow) ──────────────────────────────

    fun observeString(key: String, default: String = ""): Flow<String> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value ?: default }

    fun observeInt(key: String, default: Int = 0): Flow<Int> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value?.toIntOrNull() ?: default }

    fun observeLong(key: String, default: Long = 0L): Flow<Long> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value?.toLongOrNull() ?: default }

    fun observeBoolean(key: String, default: Boolean = false): Flow<Boolean> =
        allConfigs.map { list ->
            list.firstOrNull { it.key == key }?.value?.toBooleanStrictOrNull() ?: default
        }

    // ─────────────────────────── WRITE ───────────────────────────

    // ✅ SỬA LỖI (root cause "gửi tin nhắn Website im lặng, không phản hồi"): set()/upsert()
    // trước đây LUÔN bắn _configUpdatedEvent bất kể key gì, kể cả các key cục bộ thuần UI
    // (dashboard_viewport_pan_x/y, dashboard_viewport_zoom, floorplan_scale, floorplan_path
    // — xem DashboardViewModel.saveViewportState()/setFloorplanScale()/setFloorplanPath()),
    // dù comment khai báo _configUpdatedEvent ở trên đã ghi rõ Ý ĐỊNH là chỉ báo hiệu cho
    // deviceCode/gateway URL/token/TURN. Hệ quả: mỗi lần người dùng pan/zoom sơ đồ nhà trong
    // Dashboard, WebhookGatewayService.forceResyncBothChannels() bị gọi oan, ngắt-nối lại cả
    // 4 kênh SSE (CloudGateway/CallSignalSSE/WidgetInboxSSE/PageInboxSSE) mất ~5-10 giây —
    // nếu đúng lúc đó khách gửi tin qua Website widget, tin nhắn bị rơi vào hộp thư đệm phía
    // server và không tới được app cho tới khi kênh ổn định lại.
    //
    // Thêm tham số affectsConnection (mặc định true để KHÔNG phá vỡ các nơi gọi hiện có như
    // SettingsScreen.saveConfig()/resetConfig() — nơi người dùng thực sự sửa cấu hình Gateway
    // và ĐÚNG là cần resync ngay). Các nơi ghi key cục bộ thuần UI cần tự truyền
    // affectsConnection = false.
    suspend fun set(key: String, value: String, affectsConnection: Boolean = true) = withContext(Dispatchers.IO) {
        val existing = dao.getConfig(key)
        val entity = existing?.copy(value = value, updatedAt = System.currentTimeMillis())
            ?: AppConfigEntity(key = key, value = value, updatedAt = System.currentTimeMillis())
        dao.upsert(entity)
        logger.d("AppConfigProvider", "set $key = $value")
        if (affectsConnection) _configUpdatedEvent.tryEmit(Unit)
    }

    suspend fun upsert(entity: AppConfigEntity, affectsConnection: Boolean = true) = withContext(Dispatchers.IO) {
        dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
        if (affectsConnection) _configUpdatedEvent.tryEmit(Unit)
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        dao.delete(key)
    }

    suspend fun getAll(): List<AppConfigEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    // ✅ MỚI: SettingsViewModel.importSettings() phục hồi bảng app_config bằng SQL thô
    // (sdb.insert() trực tiếp qua ContentValues, xem BƯỚC "data" trong import) — KHÔNG đi
    // qua set()/upsert() ở trên, nên tự nó không bắn được _configUpdatedEvent. Gọi hàm này
    // thủ công ngay sau khi import xong để WebhookGatewayService biết mà ngắt/mở lại SSE
    // với deviceCode + gateway URL/token vừa phục hồi, thay vì phải đợi Restart OnRender.
    fun notifyBulkConfigChanged() {
        _configUpdatedEvent.tryEmit(Unit)
    }
}