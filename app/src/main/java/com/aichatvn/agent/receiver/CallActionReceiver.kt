package com.aichatvn.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aichatvn.agent.skills.CallSkill
import com.aichatvn.agent.utils.IncomingCallNotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * CallActionReceiver
 *
 * Xử lý nút "Từ chối" trên notification cuộc gọi đến (xem
 * IncomingCallNotificationHelper.showIncomingCallNotification()) — gọi thẳng
 * CallSkill.execute("reject_call") rồi tắt notification. KHÔNG cần mở Activity nào
 * (Từ chối không có gì để hiển thị).
 *
 * ✅ SỬA: nút "Nghe" KHÔNG còn đi qua receiver này nữa — trước đây answer_call() chạy
 * ngầm ở đây rồi tự startActivity(MainActivity) để mở UI, nhưng Android (10+/12+) chặn
 * kiểu "notification trampoline" này (startActivity() từ trong BroadcastReceiver), xác
 * nhận qua test thật trên 3 máy đều thất bại. "Nghe" giờ dùng PendingIntent.getActivity()
 * TRỰC TIẾP mở MainActivity kèm cờ tự trả lời — xem IncomingCallNotificationHelper +
 * AppNavigator.kt (LaunchedEffect xử lý cờ này, gọi CallViewModel.answer()).
 *
 * CallSkill là @Singleton do Hilt quản lý trong tiến trình app, nhưng BroadcastReceiver
 * không hỗ trợ @Inject field như Activity/Fragment/Service (không có ComponentTreeInternal
 * gắn sẵn) — dùng @EntryPoint để lấy đúng CÙNG 1 instance CallSkill @Singleton từ
 * ApplicationContext, thay vì tự new CallSkill() (sẽ tạo instance khác, không dùng chung
 * PeerConnection/StateFlow thật với UI).
 */
class CallActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallSkillEntryPoint {
        fun callSkill(): CallSkill
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        if (intent.action != ACTION_REJECT) return

        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(appContext, CallSkillEntryPoint::class.java)
        val callSkill = entryPoint.callSkill()

        // ✅ Tắt notification NGAY (không đợi execute() xong) — bấm nút xong phải biến mất
        // khỏi thanh trạng thái lập tức, cảm giác giống nút bấm cuộc gọi Android thật.
        NotificationManagerCompat.from(appContext)
            .cancel(IncomingCallNotificationHelper.notificationIdForCall(callId))

        // BroadcastReceiver.onReceive() chạy trên main thread và PHẢI return nhanh — không
        // được block chờ coroutine suspend fun (CallSkill.execute() là suspend). Dùng scope
        // riêng sống độc lập với receiver (receiver bị hệ thống hủy ngay sau onReceive trả
        // về), SupervisorJob để lỗi 1 lệnh không hủy scope.
        receiverScope.launch {
            callSkill.execute("reject_call", mapOf("callId" to callId))
        }
    }

    companion object {
        const val ACTION_REJECT = "com.aichatvn.agent.action.CALL_REJECT"
        const val EXTRA_CALL_ID = "call_id"

        // ✅ Scope mức process, KHÔNG scope theo receiver instance (receiver instance chỉ
        // sống trong đúng onReceive() rồi bị GC/hủy — 1 coroutine launch trên scope của
        // receiver sẽ bị hủy giữa đường nếu Android tái sử dụng/hủy receiver trước khi
        // suspend fun execute() chạy xong, nhất là khi máy đang trong Doze).
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}