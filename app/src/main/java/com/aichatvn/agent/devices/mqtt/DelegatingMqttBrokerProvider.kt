package com.aichatvn.agent.devices.mqtt

import com.aichatvn.agent.devices.BrokerType
import com.aichatvn.agent.devices.CloudMqttBrokerProvider
import com.aichatvn.agent.devices.MqttBrokerProvider
import com.aichatvn.agent.utils.Logger
import org.eclipse.paho.client.mqttv3.MqttClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DelegatingMqttBrokerProvider
 *
 * Lớp mỏng — điểm DUY NHẤT "biết" cả Cloud lẫn Embedded broker.
 * MqttDeviceController vẫn chỉ thấy 1 interface MqttBrokerProvider, không đổi gì.
 *
 * Chọn implementation theo BrokerElectionState.role:
 * - USING_CLOUD / NONE → CloudMqttBrokerProvider
 * - HOSTING_EMBEDDED / FOLLOWING_CAMERA_NODE / FOLLOWING_ADHOC → EmbeddedMqttBrokerProvider
 *
 * Nếu Embedded connect() trả null (broker chưa sẵn sàng / không có LAN), fallback sang Cloud
 * một lần — tránh kẹt trạng thái "đang tìm broker" vô thời hạn (Acceptance Test #9).
 */
@Singleton
class DelegatingMqttBrokerProvider @Inject constructor(
    private val electionManager: MqttBrokerElectionManager,
    private val cloud: CloudMqttBrokerProvider,
    private val embedded: EmbeddedMqttBrokerProvider,
    private val logger: Logger,
) : MqttBrokerProvider {

    override val brokerType: BrokerType
        get() = when (electionManager.currentState.value.role) {
            MqttBrokerElectionManager.BrokerRole.USING_CLOUD,
            MqttBrokerElectionManager.BrokerRole.NONE -> BrokerType.CLOUD
            else -> BrokerType.EMBEDDED_CAMERA_NODE
        }

    override suspend fun connect(): MqttClient? {
        val role = electionManager.currentState.value.role
        val useCloud = role == MqttBrokerElectionManager.BrokerRole.USING_CLOUD ||
            role == MqttBrokerElectionManager.BrokerRole.NONE

        if (useCloud) {
            logger.d("DelegatingMqttBrokerProvider", "Dùng Cloud broker (role=$role)")
            return cloud.connect()
        }

        logger.d("DelegatingMqttBrokerProvider", "Thử Embedded broker (role=$role)")
        val embeddedClient = embedded.connect()
        if (embeddedClient != null) return embeddedClient

        // Fallback Cloud — không kẹt vô hạn khi embedded không sẵn sàng.
        logger.w(
            "DelegatingMqttBrokerProvider",
            "Embedded broker không kết nối được — fallback Cloud (role=$role)"
        )
        return cloud.connect()
    }
}
