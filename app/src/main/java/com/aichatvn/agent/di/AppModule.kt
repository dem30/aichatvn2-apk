package com.aichatvn.agent.di

import android.content.Context
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.ChatHistoryManager
import com.aichatvn.agent.core.DialogManager
import com.aichatvn.agent.core.DialogManagerImpl
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.skills.*
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.VoiceAssistantManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

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
        return TuyaManager(context, database.tuyaDeviceDao(), logger)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
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
    fun provideLightSkill(skill: LightSkill): Plugin = skill

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
    fun provideLearnPlugin(skill: LearnPlugin): Plugin = skill

    @Provides
    @IntoSet
    @Singleton
    fun provideVisionPlugin(skill: VisionPlugin): Plugin = skill

    // Đăng ký Plugin Quản gia tự động mới
    @Provides
    @IntoSet
    @Singleton
    fun provideHousekeeperSkill(skill: HousekeeperSkill): Plugin = skill

    // Đăng ký Plugin Facebook Assistant mới
    @Provides
    @IntoSet
    @Singleton
    fun provideFacebookSkill(skill: FacebookSkill): Plugin = skill

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

    // ===== ĐĂNG KÝ VOICE ASSISTANT MANAGER DẠNG SINGLETON =====
    @Provides
    @Singleton
    fun provideVoiceAssistantManager(
        @ApplicationContext context: Context,
        agentKernel: AgentKernel,
        logger: Logger
    ): VoiceAssistantManager {
        return VoiceAssistantManager(context, agentKernel, logger)
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
        logger: Logger,
        dialogManager: DialogManager
    ): AgentKernel {
        return AgentKernel(
            plugins,
            groqClient,
            trainingSkill,
            chatHistoryManager,
            configProvider,
            database,
            logger,
            dialogManager
        )
    }
}