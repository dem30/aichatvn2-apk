package com.aichatvn.agent.devices.mqtt

import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TasmotaMqttHostMigrator
 *
 * Khi Election đổi broker host (Camera Node offline → ad-hoc, hoặc Camera Node quay lại),
 * đẩy MqttHost/MqttPort mới vào Tasmota qua HTTP command:
 *   GET http://<deviceLanIp>/cm?cmnd=Backlog%20MqttHost%20<ip>;MqttPort%20<port>
 *
 * Chỉ migrate thiết bị CÓ deviceLanIp (phương án b + tinh thần an toàn của (c)):
 * thiết bị chưa có IP → bỏ qua, không silently fail — caller có thể báo user.
 *
 * Gọi SAU KHI broker mới đã sống ổn định (không migrate lúc broker còn khởi động).
 */
@Singleton
class TasmotaMqttHostMigrator @Inject constructor(
    private val mqttDeviceDao: MqttDeviceDao,
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "TasmotaMqttHostMigrator"
        private const val SETTLE_DELAY_MS = 2_000L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class MigrateResult(
        val totalWithIp: Int,
        val success: Int,
        val failed: List<String>, // device names
        val skippedNoIp: Int
    )

    /**
     * @param newBrokerIp IP của broker mới (Camera Node hoặc ad-hoc host)
     * @param newPort port broker (thường 1883)
     * @param newBrokerMode "camera_node_host" | "adhoc" | "cloud" — giá trị brokerMode ĐÚNG
     *   để ghi lại cho thiết bị migrate thành công (do caller — MqttBrokerElectionManager —
     *   truyền vào, vì chỉ tầng Election mới biết election vừa quyết định gì).
     * @param waitForBrokerMs chờ broker ổn định trước khi push
     */
    suspend fun migrateAll(
        newBrokerIp: String,
        newBrokerMode: String,
        newPort: Int = MqttBrokerElectionManager.DEFAULT_EMBEDDED_BROKER_PORT,
        waitForBrokerMs: Long = SETTLE_DELAY_MS
    ): MigrateResult = withContext(Dispatchers.IO) {
        if (waitForBrokerMs > 0) delay(waitForBrokerMs)

        val all = mqttDeviceDao.getAllDevices()
        val withIp = all.filter { !it.deviceLanIp.isNullOrBlank() }
        val skipped = all.size - withIp.size

        var success = 0
        val failed = mutableListOf<String>()

        for (device in withIp) {
            val ip = device.deviceLanIp!!
            val ok = pushMqttHostToTasmota(ip, newBrokerIp, newPort)
            if (ok) {
                success++
                // ⚠️ SỬA: trước đây ghi lại device.brokerMode (giá trị CŨ) — coi như no-op, tự
                // tác giả cũ ghi chú "tạm giữ nếu không biết". Giờ ghi ĐÚNG brokerMode mới do
                // caller truyền vào, khớp với broker Tasmota vừa thực sự được trỏ tới.
                mqttDeviceDao.updateBrokerMode(device.id, newBrokerMode)
                logger.i(TAG, "✅ Migrated \"${device.name}\" ($ip) → MqttHost $newBrokerIp:$newPort")
            } else {
                failed.add(device.name)
                logger.w(TAG, "❌ Migrate fail \"${device.name}\" ($ip)")
            }
        }

        if (skipped > 0) {
            logger.i(
                TAG,
                "⚠️ $skipped thiết bị chưa có deviceLanIp — bỏ qua auto-migrate. " +
                    "User có thể chạy LAN Discovery hoặc đổi tay trên web UI Tasmota."
            )
        }

        MigrateResult(withIp.size, success, failed, skipped)
    }

    /**
     * GET http://<deviceLanIp>/cm?cmnd=Backlog%20MqttHost%20<newBrokerIp>;MqttPort%20<newPort>
     *
     * ⚠️ SỬA: KHÔNG dùng URLEncoder.encode() ở đây — nó encode dấu cách thành "+" (chuẩn
     * application/x-www-form-urlencoded) thay vì "%20" (chuẩn URL path/query thật), và encode
     * luôn cả ";" thành "%3B" — Tasmota's Backlog parser cần ";" GIỮ NGUYÊN để tách 2 lệnh
     * (đúng ví dụ thật trong kế hoạch: "...MqttHost <ip>;MqttPort <port>", dấu ; không encode).
     * Nhiều thiết bị nhúng không convert "+" → space khi decode. Chỉ cần thay khoảng trắng
     * bằng %20 thủ công — IP/port/tên lệnh đều là ký tự an toàn, không cần encode toàn bộ.
     */
    suspend fun pushMqttHostToTasmota(
        deviceLanIp: String,
        newBrokerIp: String,
        newPort: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmnd = "Backlog MqttHost $newBrokerIp;MqttPort $newPort"
            val encoded = cmnd.replace(" ", "%20")
            val url = "http://$deviceLanIp/cm?cmnd=$encoded"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.d(TAG, "pushMqttHostToTasmota($deviceLanIp) lỗi: ${e.message}")
            false
        }
    }
}
