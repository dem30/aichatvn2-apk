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

        initializePlugins()
        seedDemoDataIfNeeded()

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
    private fun seedDemoDataIfNeeded() {
        applicationScope.launch {
            try {
                val alreadySeeded = dataStore.data.first()[DEMO_SEEDED] ?: false
                if (alreadySeeded) return@launch

                val jsonString = assets.open(DEMO_ASSET_NAME).bufferedReader().use { it.readText() }
                // preserveMachineTokens = false: không copy website.widget_key của máy demo
                // gốc sang máy khách (mỗi máy giữ khoá riêng); global.gateway_token vẫn được
                // seed bình thường vì là mã xác minh bắt buộc để app hoạt động.
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
    }

    private fun initializePlugins() {
        applicationScope.launch {
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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}