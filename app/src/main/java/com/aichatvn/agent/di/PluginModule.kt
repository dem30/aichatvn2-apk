package com.aichatvn.agent.di

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginRegistry
import com.aichatvn.agent.plugins.CameraPlugin
import com.aichatvn.agent.plugins.EmailPlugin
import com.aichatvn.agent.plugins.NotificationPlugin
import com.aichatvn.agent.plugins.SimpleLightPlugin
import com.aichatvn.agent.plugins.TrainingPlugin
import com.aichatvn.agent.utils.Logger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// CÁCH THÊM PLUGIN MỚI:
//   1. Tạo class MyPlugin : Plugin với @Singleton @Inject constructor(...)
//   2. Thêm 1 dòng @Binds @IntoSet bên dưới
//   3. Không cần sửa file nào khác
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {

    @Binds @IntoSet
    abstract fun bindCameraPlugin(p: CameraPlugin): Plugin

    @Binds @IntoSet
    abstract fun bindEmailPlugin(p: EmailPlugin): Plugin

    @Binds @IntoSet
    abstract fun bindNotificationPlugin(p: NotificationPlugin): Plugin

    @Binds @IntoSet
    abstract fun bindTrainingPlugin(p: TrainingPlugin): Plugin

    @Binds @IntoSet
    abstract fun bindSimpleLightPlugin(p: SimpleLightPlugin): Plugin
}

@Module
@InstallIn(SingletonComponent::class)
object PluginRegistryModule {

    @Provides
    @Singleton
    fun providePluginRegistry(
        logger: Logger,
        plugins: Set<@JvmSuppressWildcards Plugin>
    ): PluginRegistry {
        return PluginRegistry(logger).apply {
            plugins.forEach { register(it) }
            lock()
        }
    }
}