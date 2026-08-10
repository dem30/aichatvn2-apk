package com.aichatvn.agent.devices

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * DeviceModule
 *
 * ĐÂY LÀ FILE DUY NHẤT CẦN SỬA khi thêm 1 giao thức thiết bị mới (MQTT, OEM khác...).
 * Không sửa DeviceRegistry, không sửa nghiệp vụ đang gọi DeviceRegistry — chỉ cần:
 *   1. Viết MqttDeviceController.kt (implement DeviceController, @Inject constructor)
 *   2. Thêm 1 hàm @Binds @IntoSet ở dưới, trỏ tới nó
 *
 * @Binds (không phải @Provides) vì TuyaDeviceController đã có @Inject constructor —
 * @Binds chỉ khai báo "khi cần DeviceController trong Set này, dùng instance
 * TuyaDeviceController mà Hilt đã tự dựng", không tự tay gọi constructor như @Provides.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {

    @Binds
    @IntoSet
    abstract fun bindTuyaDeviceController(impl: TuyaDeviceController): DeviceController

    // Khi thêm MQTT, thêm đúng 1 hàm theo mẫu này — không đụng gì khác trong file:
    //
    // @Binds
    // @IntoSet
    // abstract fun bindMqttDeviceController(impl: MqttDeviceController): DeviceController
}
