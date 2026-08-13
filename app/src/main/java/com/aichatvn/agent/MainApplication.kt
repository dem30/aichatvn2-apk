package com.aichatvn.agent

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.QAInitBuilder
import com.aichatvn.agent.data.BackupRestorer
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.service.WebhookGatewayService
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var plugins: Set<@JvmSuppressWildcards Plugin>

    @Inject
    lateinit var qaInitBuilder: QAInitBuilder

    // ✅ MỚI: dùng để nạp sẵn dữ liệu demo (khách/camera/lịch mẫu) ở lần mở app đầu tiên
    @Inject
    lateinit var backupRestorer: BackupRestorer

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        // ✅ MỚI: cờ đánh dấu đã nạp dữ liệu demo — chỉ chạy đúng 1 lần trên mỗi máy cài đặt
        private val DEMO_SEEDED = booleanPreferencesKey("demo_data_seeded")
        private const val DEMO_ASSET_NAME = "demo_seed.json"
    }

    override fun onCreate() {
        super.onCreate() // ✅ ĐÃ SỬA: Thay thế super.super.onCreate() bị lỗi cú pháp
        logger.d("MainApplication", "App khởi động - Khởi tạo plugins")

        // ✅ SỬA (race condition qa_data): trước đây initializePlugins() và
        // seedDemoDataIfNeeded() là 2 applicationScope.launch{} độc lập, chạy song song không
        // theo thứ tự nào — có thể khiến restore()'s "DELETE FROM qa_data WHERE category !=
        // 'auto_init'" chạy xen giữa lúc buildInitialQA() đang insert, dẫn tới mất dòng
        // "auto_init_incomplete" vừa đồng bộ hoặc (nếu buildInitialQA() chạy sau, đọc cache
        // qaData cũ) chèn trùng 1 câu hỏi với 2 id khác nhau (QAEntity không có unique
        // constraint trên "question", xem Entity.kt). Gộp lại 1 coroutine, chạy TUẦN TỰ: đợi
        // initializePlugins() (bao gồm buildInitialQA()) xong hẳn rồi mới seedDemoDataIfNeeded().
        applicationScope.launch {
            initializePlugins()
            seedDemoDataIfNeeded()
        }

        // Tự động khởi chạy WebhookGatewayService khi mở ứng dụng từ Launcher
        try {
            val serviceIntent = Intent(this, WebhookGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            logger.i("MainApplication", "🚀 WebhookGatewayService started successfully on App Launch")
        } catch (e: Exception) {
            logger.e("MainApplication", "❌ Failed to start WebhookGatewayService on App Launch", e)
        }
    }

    // ✅ MỚI: Nạp dữ liệu demo (khách/camera/lịch mẫu, cooldown tắt) từ assets/demo_seed.json
    // để khách cài app xong là có sẵn dữ liệu test — chỉ chạy 1 lần duy nhất (kiểm tra cờ
    // DEMO_SEEDED trong DataStore), không đè lên dữ liệu thật nếu người dùng import/tạo
    // camera riêng sau lần chạy đầu.
    // ✅ SỬA: bỏ applicationScope.launch{} riêng — giờ là suspend fun để onCreate() gọi được
    // TUẦN TỰ sau initializePlugins(), đảm bảo buildInitialQA() luôn insert xong "auto_init"/
    // "auto_init_incomplete" QA trước khi restore() có thể DELETE/insert lại bảng qa_data.
    private suspend fun seedDemoDataIfNeeded() {
        try {
            val alreadySeeded = dataStore.data.first()[DEMO_SEEDED] ?: false
            if (alreadySeeded) return

            val jsonString = assets.open(DEMO_ASSET_NAME).bufferedReader().use { it.readText() }
            // preserveMachineTokens = false: không copy website.widget_key/call.device_code/
            // local.device_id/... của máy demo gốc sang máy khách (mỗi máy giữ định danh
            // riêng — xem MACHINE_SPECIFIC_CONFIG_KEYS trong BackupRestorer.kt); global.gateway_token
            // vẫn được seed bình thường vì là mã xác minh bắt buộc để app hoạt động.
            val result = backupRestorer.restore(this@MainApplication, jsonString, preserveMachineTokens = false)
            logger.i("MainApplication", "🌱 Đã nạp dữ liệu demo: $result")

            dataStore.edit { prefs -> prefs[DEMO_SEEDED] = true }
        } catch (e: java.io.FileNotFoundException) {
            // Không có file demo_seed.json trong assets — bỏ qua, không phải lỗi.
            logger.d("MainApplication", "Không tìm thấy $DEMO_ASSET_NAME trong assets, bỏ qua seed demo")
        } catch (e: Exception) {
            logger.e("MainApplication", "❌ Lỗi khi nạp dữ liệu demo", e)
        }
    }

    // ✅ SỬA: bỏ applicationScope.launch{} riêng — giờ là suspend fun để onCreate() gọi được
    // TUẦN TỰ trước seedDemoDataIfNeeded() (xem lý do ở onCreate()).
    private suspend fun initializePlugins() {
        plugins.forEach { plugin ->
            try {
                plugin.initialize()
                logger.i("MainApplication", "✅ Initialized plugin: ${plugin.manifest.id}")
            } catch (e: Exception) {
                logger.e("MainApplication", "❌ Failed to initialize ${plugin.manifest.id}", e)
            }
        }
        try {
            qaInitBuilder.buildInitialQA()
        } catch (e: Exception) {
            logger.e("MainApplication", "❌ Failed to build initial QA", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}