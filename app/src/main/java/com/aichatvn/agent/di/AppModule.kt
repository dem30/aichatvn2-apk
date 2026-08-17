package com.aichatvn.agent.di

import com.aichatvn.agent.utils.DatabaseSearchHelper
import com.aichatvn.agent.utils.TimeRangeResolver 
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
import com.aichatvn.agent.data.CallContactDao
import com.aichatvn.agent.data.CallLogDao
import com.aichatvn.agent.data.TuyaDeviceDao
import com.aichatvn.agent.data.MqttDeviceDao
import com.aichatvn.agent.skills.*
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Provider // Thêm import Provider

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ✅ MỚI (thiết kế lại theo đúng chuẩn — xem KDoc đầy đủ ở CameraLanHttpClient.kt): 1 client
    // DÙNG CHUNG cho MỌI lời gọi HTTP/SOAP tới camera trên LAN (ONVIF, RTSP DESCRIBE qua socket
    // riêng không dùng client này, HTTP snapshot, discovery scan) — thay vì mỗi class tự dựng
    // OkHttpClient.Builder() riêng như trước (8 chỗ rải rác, mỗi nơi 1 ConnectionPool độc lập).
    //
    // ConnectionPool(0, ...): tắt hẳn việc tái sử dụng connection — buộc mỗi request tới camera mở
    // TCP mới. Lý do: rất nhiều firmware camera OEM giá rẻ (con số camera app hỗ trợ lên tới hàng
    // trăm loại) không tuân thủ keep-alive HTTP đúng chuẩn, tự đóng socket ngay sau 1 request —
    // nếu dùng pool, OkHttp sẽ có lúc lấy lại đúng connection đã bị đóng đó ra tái sử dụng, gây
    // IOException transport (unexpected end of stream, EOFException, connection reset...) không
    // liên quan gì tới nội dung request. Trên LAN, chi phí mở TCP mới mỗi lần (~1-5ms) không đáng
    // kể so với rủi ro lỗi giả này.
    //
    // Timeout để mặc định vừa phải ở đây — nơi cần timeout khác (ngắn hơn cho probe, dài hơn cho
    // long-poll PullMessages) tự derive qua `.newBuilder()...build()`, KHÔNG tạo OkHttpClient gốc
    // mới (giữ nguyên ConnectionPool/Dispatcher dùng chung).
    @Provides
    @Singleton
    @CameraLanHttpClient
    fun provideCameraLanHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .build()

    // ✅ ĐÃ SỬA: TuyaManager có @Inject constructor nên để Hilt tự dựng,
    // không cần @Provides thủ công (constructor thật cần thêm TuyaLocalController + AppConfigProvider)
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

    // ✅ MỚI: bổ sung binding cho DatabaseSearchHelper (đã inject CallContactDao/CallLogDao
    // để hiển thị tên người gọi và đọc trạng thái MISSED/REJECTED thật từ call_logs)
    @Provides
    @Singleton
    fun provideCallContactDao(database: AppDatabase): CallContactDao {
        return database.callContactDao()
    }

    @Provides
    @Singleton
    fun provideCallLogDao(database: AppDatabase): CallLogDao {
        return database.callLogDao()
    }

    // ✅ THÊM LẠI: TuyaDeviceDao bị mất binding khi provideTuyaManager() thủ công
    // (nơi trước đây gọi database.tuyaDeviceDao() ngầm) bị xóa để Hilt tự dựng TuyaManager
    // qua @Inject constructor — giờ cần khai báo riêng để Dagger biết cách cung cấp nó.
    @Provides
    @Singleton
    fun provideTuyaDeviceDao(database: AppDatabase): TuyaDeviceDao {
        return database.tuyaDeviceDao()
    }

    // ✅ MỚI (Tầng 3): cùng lý do provideTuyaDeviceDao ở trên — Hilt không tự suy ra được
    // DAO cụ thể từ AppDatabase, phải khai báo @Provides riêng cho từng DAO được inject
    // trực tiếp (ở đây là MqttDeviceController). Đúng pattern y hệt Tuya, chỉ đổi DAO.
    @Provides
    @Singleton
    fun provideMqttDeviceDao(database: AppDatabase): MqttDeviceDao {
        return database.mqttDeviceDao()
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

    // ✅ MỚI: bổ sung binding còn thiếu cho CallSkill — trước đây CallSkill không nằm
    // trong Set<Plugin> nên dù manifest.routable=true, nó vẫn không xuất hiện trong
    // danh sách plugins của SmartActionFormSheet (dropdown "Thêm hành động tự động").
    @Provides
    @IntoSet
    @Singleton
    fun provideCallSkill(skill: CallSkill): Plugin = skill

    // ✅ ĐÃ SỬA LỖI 6: Cung cấp binding Singleton chính xác cho HouseManagerSkill để tránh lỗi Dagger Missing Binding
    @Provides
    @Singleton
    fun provideHouseManagerSkill(impl: HouseManagerSkillImpl): HouseManagerSkill = impl

    // ✅ ĐÃ SỬA: Đăng ký Quản gia vào Plugin Set từ cùng một Singleton Instance
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
        // ✅ ĐÃ SỬA LỖI 6: Tiêm đúng Provider<HouseManagerSkill> đã cấu hình ở trên vào AgentKernel
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
            houseManagerProvider 
        )
    }
}