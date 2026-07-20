package com.aichatvn.agent.di

import com.aichatvn.agent.utils.DatabaseSearchHelper
import com.aichatvn.agent.utils.TimeRangeResolver // ✅ THÊM IMPORT: Cung cấp TimeRangeResolver cho AppModule
import android.content.Context
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.ChatHistoryManager
import com.aichatvn.agent.core.DialogManager
import com.aichatvn.agent.core.DialogManagerImpl
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.router.RoutingPipeline
import com.aichatvn.agent.core.execution.IntentExecutor
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.EventLogDao 
import com.aichatvn.agent.skills.*
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import javax.inject.Provider // ✅ THÊM IMPORT: Hỗ trợ nạp lazy Provider tránh circular dependency

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTuyaManager(
        @ApplicationContext context: Context,
        database: AppDatabase,
        logger: Logger
    ): TuyaManager {
        return TuyaManager(context, database.tuyaDeviceDao(), database, logger)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideEventLogDao(database: AppDatabase): EventLogDao {
        return database.eventLogDao()
    }

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    // ===== ĐĂNG KÝ BỘ PLUGIN CHUẨN HOÁ TRUNG TÂM (Set<Plugin>) =====

    @Provides
    @IntoSet
    @Singleton
    fun provideCameraSkill(skill: CameraSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideSmartSwitchSkill(skill: SmartSwitchSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideEmailSkill(skill: EmailSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideNotificationSkill(skill: NotificationSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideTrainingSkill(skill: TrainingSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideScheduleSkill(skill: ScheduleSkill): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideAppConfigSkill(skill: AppConfigSkill): Plugin = skill
     
    @Provides
    @IntoSet
    @Singleton
    fun provideVisionPlugin(skill: VisionPlugin): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideFacebookSkill(skill: FacebookSkill): Plugin = skill

    // ✅ THÊM: Đăng ký và bind Interface HouseManagerSkill vào Hilt Graph
    @Provides
    @Singleton
    fun provideHouseManagerSkill(impl: HouseManagerSkillImpl): HouseManagerSkill = impl

    // ✅ THÊM: Đăng ký Quản gia vào Set<Plugin> để hệ thống quét và nhận diện các action có sẵn
    @Provides
    @IntoSet
    @Singleton
    fun provideHouseManagerSkillPlugin(skill: HouseManagerSkill): Plugin = skill

    @Provides
    @Singleton
    fun provideAppConfigProvider(
        @ApplicationContext context: Context,
        logger: Logger
    ): AppConfigProvider = AppConfigProvider(context, logger)

    // ===== TẦNG 0: DIALOG MANAGER =====
    @Provides
    @Singleton
    fun provideDialogManager(): DialogManager {
        return DialogManagerImpl()
    }

    // ===== AGENT KERNEL =====
    @Provides
    @Singleton
    fun provideAgentKernel(
        plugins: Set<@JvmSuppressWildcards Plugin>, 
        groqClient: GroqClientTool,
        trainingSkill: TrainingSkill,
        chatHistoryManager: ChatHistoryManager,
        configProvider: AppConfigProvider,
        database: AppDatabase,
        routingPipeline: RoutingPipeline,
        intentExecutor: IntentExecutor,
        databaseSearchHelper: DatabaseSearchHelper,
        timeRangeResolver: TimeRangeResolver,
        logger: Logger,
        // ✅ SỬA: Tiêm thêm Provider của Quản gia AI vào chữ ký hàm dựng của Hilt
        houseManagerProvider: Provider<HouseManagerSkill> 
    ): AgentKernel {
        return AgentKernel(
            plugins,
            groqClient,
            trainingSkill,
            chatHistoryManager,
            configProvider,
            database,
            routingPipeline,
            intentExecutor,
            databaseSearchHelper,
            timeRangeResolver,
            logger,
            // ✅ SỬA: Truyền tham số thứ 12 vào hàm dựng của AgentKernel
            houseManagerProvider 
        )
    }
}