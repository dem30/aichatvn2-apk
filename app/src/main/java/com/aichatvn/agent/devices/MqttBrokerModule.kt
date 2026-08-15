package com.aichatvn.agent.devices

import com.aichatvn.agent.devices.mqtt.DelegatingMqttBrokerProvider
import com.aichatvn.agent.devices.mqtt.EmbeddedMqttBrokerProvider
import com.aichatvn.agent.devices.mqtt.MqttBrokerElectionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * MqttBrokerModule
 *
 * ✅ SỬA (MQTT Symmetric Broker): trước đây dùng @Binds tĩnh → 1 implementation duy nhất
 * (CloudMqttBrokerProvider). Giờ đổi sang @Provides động đọc BrokerElectionState.role qua
 * DelegatingMqttBrokerProvider — chọn Cloud hoặc Embedded mà MqttDeviceController không
 * biết (vẫn chỉ inject MqttBrokerProvider).
 *
 * ⚠️ XOÁ abstract class MqttBrokerModule cũ trong MqttDeviceController.kt khi merge —
 * chỉ giữ file này (tránh trùng @Module).
 */
@Module
@InstallIn(SingletonComponent::class)
object MqttBrokerModule {

    @Provides
    @Singleton
    fun provideMqttBrokerProvider(
        electionManager: MqttBrokerElectionManager,
        cloud: CloudMqttBrokerProvider,
        embedded: EmbeddedMqttBrokerProvider,
        logger: com.aichatvn.agent.utils.Logger,
    ): MqttBrokerProvider = DelegatingMqttBrokerProvider(
        electionManager = electionManager,
        cloud = cloud,
        embedded = embedded,
        logger = logger,
    )
}
