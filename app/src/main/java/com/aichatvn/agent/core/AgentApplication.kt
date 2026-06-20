package com.aichatvn.agent

import android.app.Application
import com.aichatvn.agent.core.LocalRouterEngine
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * ✅ MỚI: Application class — entry point thật của app, khởi tạo trước MỌI Activity.
 *
 * Mục đích: kích hoạt tải model SmolLM2 (.gguf) NGAY KHI MỞ APP, thay vì chờ tới lượt
 * chat đầu tiên (hành vi cũ trong LocalRouterEngine.predictIntent()).
 *
 * Cần khai báo trong AndroidManifest.xml (file này KHÔNG có trong các file được upload,
 * bạn cần tự thêm thuộc tính android:name vào thẻ <application>):
 *
 *   <application
 *       android:name=".AgentApplication"
 *       ... >
 */
@HiltAndroidApp
class AgentApplication : Application() {

    @Inject
    lateinit var localRouterEngine: LocalRouterEngine

    @Inject
    lateinit var logger: Logger

    // Sống cùng vòng đời của app (không bị huỷ khi Activity/Composable bị recreate).
    // SupervisorJob: nếu việc tải model lỗi, không làm crash/huỷ các coroutine khác trong scope.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        logger.d("AgentApplication", "App khởi động -> bắt đầu tải model local router (nếu chưa có)")
        localRouterEngine.prefetchModelAsync(appScope)
    }
}