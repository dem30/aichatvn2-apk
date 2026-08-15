package com.aichatvn.agent.devices.mqtt

import com.aichatvn.agent.devices.BrokerType
import com.aichatvn.agent.devices.MqttBrokerProvider
import com.aichatvn.agent.utils.Logger
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EmbeddedMqttBrokerProvider
 *
 * Implementation của MqttBrokerProvider cho broker nhúng (Camera Node host hoặc ad-hoc).
 * Nối tầng Election vào tầng Device Control mà KHÔNG sửa MqttDeviceController —
 * controller chỉ inject MqttBrokerProvider, đổi implementation nào được bind là quyết
 * định của tầng Election.
 *
 * - role == HOSTING_EMBEDDED → connect tcp://127.0.0.1:port (tránh vòng qua LAN cho chính mình)
 * - role == FOLLOWING_* → connect tcp://<activeBrokerIp>:port
 * - Chưa có election result → gọi electNow(ConnectRequested) đúng 1 lần (lazy), KHÔNG tạo vòng lặp
 *
 * isAutomaticReconnect = true — GIỐNG HỆT CloudMqttBrokerProvider.connect(), chỉ khác brokerUri.
 *
 * ⚠️ Moquette broker thật (bật/tắt Foreground Service) được điều khiển riêng theo BrokerRole
 * ở bước 4 Implementation Order — provider này CHỈ lo phía client Paho connect tới broker.
 */
@Singleton
class EmbeddedMqttBrokerProvider @Inject constructor(
    private val electionManager: MqttBrokerElectionManager,
    private val logger: Logger,
) : MqttBrokerProvider {

    override val brokerType: BrokerType = BrokerType.EMBEDDED_CAMERA_NODE

    override suspend fun connect(): MqttClient? {
        var state = electionManager.currentState.value
        var ip = state.activeBrokerIp

        // Chưa có kết quả election hợp lệ — gọi 1 lần (KHÔNG tạo vòng lặp; electNow() chỉ chạy
        // đúng 1 lần và trả kết quả).
        if (state.role == MqttBrokerElectionManager.BrokerRole.NONE ||
            (state.role != MqttBrokerElectionManager.BrokerRole.USING_CLOUD && ip.isNullOrBlank())
        ) {
            state = electionManager.electNow(
                MqttBrokerElectionManager.ElectionTrigger.ConnectRequested
            )
            ip = state.activeBrokerIp
        }

        // Vẫn không có IP (vd đang USING_CLOUD hoặc không có LAN) — trả null để caller
        // (DelegatingMqttBrokerProvider) chuyển sang Cloud.
        if (ip.isNullOrBlank() ||
            state.role == MqttBrokerElectionManager.BrokerRole.USING_CLOUD
        ) {
            logger.d("EmbeddedMqttBrokerProvider", "Không có broker embedded khả dụng (role=${state.role})")
            return null
        }

        // HOSTING_EMBEDDED trên chính máy này → loopback; FOLLOWING_* → IP LAN của host.
        val brokerUri = if (state.role == MqttBrokerElectionManager.BrokerRole.HOSTING_EMBEDDED) {
            "tcp://127.0.0.1:${electionManager.embeddedBrokerPort}"
        } else {
            "tcp://$ip:${electionManager.embeddedBrokerPort}"
        }

        return try {
            val clientId = "aichatvn2_emb_" + UUID.randomUUID().toString().take(8)
            val newClient = MqttClient(brokerUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                // Cho phép Paho tự reconnect nền — giống CloudMqttBrokerProvider.
                isAutomaticReconnect = true
                // Embedded broker nội bộ LAN — không cần username/password mặc định.
            }
            newClient.connect(options)
            logger.i("EmbeddedMqttBrokerProvider", "✅ Kết nối embedded broker: $brokerUri (role=${state.role})")
            newClient
        } catch (e: Exception) {
            logger.e("EmbeddedMqttBrokerProvider", "Kết nối embedded broker lỗi ($brokerUri): ${e.message}", e)
            null
        }
    }
}
