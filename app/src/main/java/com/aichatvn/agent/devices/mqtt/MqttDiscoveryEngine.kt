package com.aichatvn.agent.devices.mqtt

import org.json.JSONObject

/**
 * DiscoveredMqttDevice
 *
 * Kết quả 1 thiết bị được 1 MqttDiscoveryAdapter nhận diện thành công — đủ field để map thẳng
 * sang MqttDeviceEntity (xem MqttDeviceController.discoverDevices() và
 * MqttViewModel.confirmDiscoveredDevice() trong TuyaScreen.kt), NHƯNG chưa lưu DB ngay — theo
 * yêu cầu "sau khi discovery được thiết bị, UI phải cho người dùng xem và xác nhận trước khi
 * lưu, tương tự flow Camera Discovery hiện tại".
 *
 * `uniqueId` dùng để dedupe khi cùng 1 thiết bị publish lại retained message nhiều lần trong
 * cùng 1 lần quét — KHÔNG dùng làm khoá chính DB (MqttDeviceEntity.id vẫn tự sinh UUID như cũ
 * khi người dùng bấm "Thêm" ở bước xác nhận).
 */
data class DiscoveredMqttDevice(
    val suggestedName: String,
    val uniqueId: String,
    val commandTopic: String,
    val stateTopic: String?,
    val onPayload: String,
    val offPayload: String,
    val discoverySource: String
)

/**
 * MqttDiscoveryAdapter
 *
 * 1 adapter = 1 "cách hiểu" 1 quy ước discovery cụ thể. Core (MqttDiscoveryEngine) không quan
 * tâm bên trong adapter làm gì — chỉ gọi tryParse() cho từng message nhận được lúc quét, nhận
 * về null nếu adapter đó không nhận diện được (không phải lỗi, chỉ là "không phải quy ước của
 * tôi") để engine thử adapter tiếp theo.
 */
fun interface MqttDiscoveryAdapter {
    val id: String get() = this::class.simpleName ?: "adapter"
    fun tryParse(topic: String, payload: String): DiscoveredMqttDevice?
}

/**
 * HomeAssistantDiscoveryAdapter
 *
 * Quy ước MQTT Discovery chuẩn của Home Assistant (được nhiều firmware/gateway hỗ trợ sẵn, vd
 * Tasmota có thể bật chế độ này, ESPHome mặc định bật) — retained JSON config publish tại
 * `<discovery_prefix>/<component>/[<node_id>/]<object_id>/config`. Không giả định prefix cố
 * định "homeassistant" — nhiều setup đổi prefix, nên chỉ cần khớp đuôi "/config" + payload JSON
 * hợp lệ có "command_topic". "component" phổ biến nhất cho công tắc là "switch"/"light" —
 * KHÔNG lọc theo component ở đây để không bỏ sót thiết bị dùng component khác nhưng vẫn bật
 * tắt được qua command_topic tương tự (vd "fan").
 */
class HomeAssistantDiscoveryAdapter : MqttDiscoveryAdapter {
    override val id = "home_assistant"

    override fun tryParse(topic: String, payload: String): DiscoveredMqttDevice? {
        if (!topic.endsWith("/config")) return null
        if (payload.isBlank()) return null
        return try {
            val json = JSONObject(payload)
            val commandTopic = json.optString("command_topic", "").trim()
            if (commandTopic.isBlank()) return null

            val fallbackName = topic.removeSuffix("/config").substringAfterLast("/")
            DiscoveredMqttDevice(
                suggestedName = json.optString("name", "").ifBlank { fallbackName },
                uniqueId = json.optString("unique_id", "").ifBlank { topic },
                commandTopic = commandTopic,
                stateTopic = json.optString("state_topic", "").trim().ifBlank { null },
                onPayload = json.optString("payload_on", "ON"),
                offPayload = json.optString("payload_off", "OFF"),
                discoverySource = id
            )
        } catch (e: Exception) {
            null // payload không phải JSON hợp lệ — không phải thiết bị Home Assistant Discovery
        }
    }
}

/**
 * HomieDiscoveryAdapter
 *
 * Quy ước Homie (homieiot.github.io) — mỗi device publish retained
 * `homie/<device_id>/$state` = "ready"/"init"/"lost"... để công bố sự tồn tại. Bản thân quy ước
 * Homie không CHUẨN HOÁ command/state topic cho "công tắc bật tắt" theo 1 property cố định (còn
 * tuỳ node/property id từng thiết bị khai báo qua `$nodes`, `$properties`) — nên adapter này chỉ
 * DÙNG để XÁC NHẬN "đây là 1 thiết bị Homie đang online" và gợi ý tên, còn command/state topic
 * suy ra theo quy ước property tên "switch" phổ biến nhất trong ví dụ chính thức của Homie.
 * Người dùng VẪN xem và sửa lại được ở bước xác nhận trước khi lưu (không tự lưu thẳng) — đúng
 * yêu cầu, vì suy đoán này có thể sai với thiết bị khai báo property khác tên "switch".
 */
class HomieDiscoveryAdapter : MqttDiscoveryAdapter {
    override val id = "homie"

    override fun tryParse(topic: String, payload: String): DiscoveredMqttDevice? {
        val parts = topic.split("/")
        if (parts.size != 3 || parts[0] != "homie" || parts[2] != "\$state") return null
        if (payload.trim() !in setOf("ready", "init")) return null // "lost"/"disconnected" — không gợi ý thiết bị coi như đang chết

        val deviceId = parts[1]
        return DiscoveredMqttDevice(
            suggestedName = deviceId,
            uniqueId = "homie:$deviceId",
            commandTopic = "homie/$deviceId/switch/on/set",
            stateTopic = "homie/$deviceId/switch/on",
            onPayload = "true",
            offPayload = "false",
            discoverySource = id
        )
    }
}

/**
 * MqttDiscoveryEngine
 *
 * "Core không phụ thuộc Tasmota" — engine này KHÔNG import/biết gì về adapter vendor cụ thể.
 * Danh sách adapter (kể cả adapter vendor như Tasmota) được TRUYỀN VÀO từ ngoài qua constructor
 * (Hilt @Provides — xem MqttDiscoveryModule bên dưới), engine chỉ lặp qua danh sách và hỏi từng
 * adapter "có nhận ra message này không". Muốn thêm/bớt 1 vendor adapter chỉ cần sửa danh sách ở
 * module Hilt, không đụng vào engine hay MqttDeviceController.
 */
class MqttDiscoveryEngine(
    private val adapters: List<MqttDiscoveryAdapter>
) {
    fun parse(topic: String, payload: String): DiscoveredMqttDevice? {
        for (adapter in adapters) {
            val result = try {
                adapter.tryParse(topic, payload)
            } catch (e: Exception) {
                null // 1 adapter lỗi không được làm hỏng cả lượt quét — thử adapter kế tiếp
            }
            if (result != null) return result
        }
        return null
    }
}

/**
 * TasmotaAdapter
 *
 * Adapter VENDOR — đặt CÙNG FILE cho gọn vì đây là adapter tham khảo duy nhất đi kèm, nhưng về
 * mặt kiến trúc nó độc lập hoàn toàn với MqttDiscoveryEngine (engine không import riêng class
 * này, chỉ nhận qua danh sách chung `List<MqttDiscoveryAdapter>`). Nếu sau này thấy adapter
 * vendor phình to, tách ra file riêng (vd `adapters/TasmotaAdapter.kt`) mà không cần sửa engine.
 *
 * Quy ước Tasmota mặc định (chưa bật Home Assistant Discovery riêng): lệnh gửi tới
 * `cmnd/<topic>/POWER`, trạng thái trả JSON tại `stat/<topic>/RESULT` dạng {"POWER":"ON"}.
 */
class TasmotaAdapter : MqttDiscoveryAdapter {
    override val id = "vendor:tasmota"

    private val statResultRegex = Regex("^stat/([^/]+)/RESULT$")

    override fun tryParse(topic: String, payload: String): DiscoveredMqttDevice? {
        val match = statResultRegex.find(topic) ?: return null
        val deviceTopic = match.groupValues[1]
        return try {
            val json = JSONObject(payload)
            if (!json.has("POWER")) return null
            DiscoveredMqttDevice(
                suggestedName = deviceTopic,
                uniqueId = "tasmota:$deviceTopic",
                commandTopic = "cmnd/$deviceTopic/POWER",
                stateTopic = "stat/$deviceTopic/RESULT",
                onPayload = "ON",
                offPayload = "OFF",
                discoverySource = id
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Hilt module — nơi DUY NHẤT liệt kê adapter nào được bật. Thêm adapter vendor mới (vd ESPHome
 * native API adapter khác, hoặc Zigbee2MQTT convention) chỉ cần thêm vào danh sách này.
 */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object MqttDiscoveryModule {
    @dagger.Provides
    @javax.inject.Singleton
    fun provideMqttDiscoveryEngine(): MqttDiscoveryEngine = MqttDiscoveryEngine(
        adapters = listOf(
            HomeAssistantDiscoveryAdapter(),
            HomieDiscoveryAdapter(),
            TasmotaAdapter(),
        )
    )
}