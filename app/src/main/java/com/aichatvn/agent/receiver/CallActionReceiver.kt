package com.aichatvn.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.aichatvn.agent.MainActivity
import com.aichatvn.agent.skills.CallSkill
import com.aichatvn.agent.skills.NotificationSkill
import com.aichatvn.agent.ui.navigation.Screen
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
 * Xử lý 2 nút "Nghe"/"Từ chối" gắn trực tiếp trên notification cuộc gọi đến
 * (xem IncomingCallNotificationHelper.showIncomingCallNotification()) — gọi thẳng
 * CallSkill.execute() rồi tắt notification.
 *
 * ✅ SỬA (root cause "bấm Nghe khi đang ở app khác, nói chuyện được nhưng app không mở
 * lên để 2 bên nhìn thấy nhau"): trước đây nhánh ACTION_ANSWER chỉ chạy answer_call() ngầm,
 * không có dòng nào đưa MainActivity lên foreground. CallSkill là @Singleton (không phụ
 * thuộc UI) nên WebRTC vẫn setup track/kết nối audio bình thường — nhưng CallScreen (video)
 * không bao giờ hiện ra nếu app đang ở nền. Giờ ACTION_ANSWER mở thêm MainActivity kèm deep
 * link CALL_ROUTE, xem onReceive().
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
        val action = intent.action ?: return

        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(appContext, CallSkillEntryPoint::class.java)
        val callSkill = entryPoint.callSkill()

        // ✅ Tắt notification NGAY (không đợi execute() xong) — bấm nút xong phải biến mất
        // khỏi thanh trạng thái lập tức, cảm giác giống nút bấm cuộc gọi Android thật.
        NotificationManagerCompat.from(appContext)
            .cancel(IncomingCallNotificationHelper.notificationIdForCall(callId))

        // ✅ MỚI: chỉ ACTION_ANSWER cần mở UI (Từ chối không cần hiện gì). PHẢI gọi
        // startActivity() ĐỒNG BỘ ngay tại đây, KHÔNG đưa vào receiverScope.launch{} bên
        // dưới — hệ thống chỉ cấp quyền tạm thời "khởi động Activity từ nền" (background
        // activity launch) cho đúng lượt onReceive() được kích hoạt trực tiếp từ thao tác
        // bấm nút trên notification. Nếu trì hoãn sang 1 coroutine chạy sau (dù chỉ vài
        // mili-giây), cửa sổ quyền tạm này đã đóng và Android 10+ sẽ âm thầm chặn
        // startActivity(), không có lỗi rõ ràng nào hiện ra để biết vì sao app không mở.
        if (action == ACTION_ANSWER) {
            val openCallIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationSkill.DEEP_LINK_EXTRA, Screen.CALL_ROUTE)
            }
            appContext.startActivity(openCallIntent)
        }

        // BroadcastReceiver.onReceive() chạy trên main thread và PHẢI return nhanh — không
        // được block chờ coroutine suspend fun (CallSkill.execute() là suspend). Dùng scope
        // riêng sống độc lập với receiver (receiver bị hệ thống hủy ngay sau onReceive trả
        // về), SupervisorJob để lỗi 1 lệnh không hủy scope.
        receiverScope.launch {
            when (action) {
                ACTION_ANSWER -> callSkill.execute("answer_call", mapOf("callId" to callId))
                ACTION_REJECT -> callSkill.execute("reject_call", mapOf("callId" to callId))
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.aichatvn.agent.action.CALL_ANSWER"
        const val ACTION_REJECT = "com.aichatvn.agent.action.CALL_REJECT"
        const val EXTRA_CALL_ID = "call_id"

        // ✅ Scope mức process, KHÔNG scope theo receiver instance (receiver instance chỉ
        // sống trong đúng onReceive() rồi bị GC/hủy — 1 coroutine launch trên scope của
        // receiver sẽ bị hủy giữa đường nếu Android tái sử dụng/hủy receiver trước khi
        // suspend fun execute() chạy xong, nhất là khi máy đang trong Doze).
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}