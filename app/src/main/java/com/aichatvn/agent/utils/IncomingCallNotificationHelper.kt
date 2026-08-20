package com.aichatvn.agent.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.aichatvn.agent.R
import com.aichatvn.agent.receiver.CallActionReceiver
import com.aichatvn.agent.MainActivity
import com.aichatvn.agent.ui.navigation.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IncomingCallNotificationHelper
 *
 * Bắn 1 notification kiểu "cuộc gọi đến" thật sự (dùng NotificationCompat.CallStyle,
 * API tương đương ConnectionService của app thoại hệ thống) — full-screen intent để tự
 * bật màn hình + hiện trên lock screen dù app đang đóng hoàn toàn, kèm 2 nút Nghe/Từ
 * chối xử lý NGAY qua CallActionReceiver (không cần mở Activity).
 *
 * TÁCH RIÊNG khỏi NotificationSkill.kt — channel/hành vi khác hẳn (rung+ping 1 lần của
 * cảnh báo camera so với chuông đổ liên tục + full-screen của cuộc gọi), và
 * NotificationSkill là 1 Plugin có action "send" cho AI/chat gọi, không phù hợp để
 * nhúng logic CallStyle đặc thù WebRTC vào đó.
 *
 * LƯU Ý QUAN TRỌNG VỀ ÂM THANH/RUNG: CallSkill.startRingtoneAndVibration() đã tự phát
 * chuông + rung liên tục qua Ringtone/Vibrator API riêng — nên channel ở đây set
 * setSound(null)/setVibrationEnabled(false) để KHÔNG chồng thêm 1 lớp âm thanh nữa
 * (tránh nghe 2 chuông đè lên nhau, hoặc rung 2 pattern xung đột).
 */
@Singleton
class IncomingCallNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // IMPORTANCE_HIGH bắt buộc để full-screen intent + heads-up hoạt động —
                // NORMAL/LOW sẽ bị hệ thống lặng lẽ hạ xuống thành notification thường,
                // không đập màn hình lên được.
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                setSound(null, null)       // Âm thanh do CallSkill.startRingtoneAndVibration() đảm nhiệm
                enableVibration(false)     // Rung cũng vậy — tránh chồng 2 pattern
                setBypassDnd(true)         // Cuộc gọi cần xuyên qua "Không làm phiền", giống app thoại thật
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Hiển thị notification cuộc gọi đến. Gọi lại nhiều lần với CÙNG callId là an toàn —
     * notify() với cùng ID sẽ tự thay thế notification cũ, không tạo bản trùng.
     *
     * @param callId ID cuộc gọi hiện tại — dùng để tính notification ID ổn định VÀ để
     *   CallActionReceiver biết đúng cuộc gọi nào khi người dùng bấm Nghe/Từ chối.
     * @param callerLabel Tên hiển thị của người gọi (đã resolve từ danh bạ) hoặc mã máy
     *   thô nếu chưa lưu — hiển thị ngay trên notification, không phải mở app mới biết ai gọi.
     * @param isVideo Cờ để đổi nhãn "Cuộc gọi video đến" / "Cuộc gọi thoại đến".
     */
    fun showIncomingCallNotification(callId: String, callerLabel: String, isVideo: Boolean) {
        val notificationId = notificationIdForCall(callId)

        // Bấm vào PHẦN NỘI DUNG notification (không phải 2 nút Nghe/Từ chối) → mở app
        // thẳng tới CALL_ROUTE, dùng lại đúng cơ chế deep link đã có trong NotificationSkill
        // (DEEP_LINK_EXTRA) để không phải viết thêm 1 kiểu điều hướng khác.
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.aichatvn.agent.skills.NotificationSkill.DEEP_LINK_EXTRA, Screen.CALL_ROUTE)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notificationId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent: hệ thống dùng CHÍNH intent này để tự mở Activity + đập màn
        // hình sáng lên (nếu máy đang khoá) — bắt buộc phải có quyền USE_FULL_SCREEN_INTENT
        // trong Manifest (xem hướng dẫn kèm theo) để hành vi này thực sự kích hoạt, thay vì
        // chỉ rơi về 1 notification heads-up thường.
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(com.aichatvn.agent.skills.NotificationSkill.DEEP_LINK_EXTRA, Screen.CALL_ROUTE)
        }
        // ✅ SỬA: dùng requestCode RIÊNG (notificationId + FULLSCREEN_REQUEST_CODE_OFFSET) thay
        // vì trùng notificationId với contentPendingIntent ở trên. PendingIntent.getActivity()
        // coi 2 lệnh gọi là "cùng 1 PendingIntent" khi requestCode giống nhau VÀ
        // Intent.filterEquals() khớp (chỉ so action/data/component/category — KHÔNG so extras
        // hay flags). Vì cả 2 Intent ở đây đều trỏ cùng MainActivity, không khác action/data,
        // filterEquals() luôn trả về true -> nếu dùng chung notificationId, lệnh getActivity()
        // gọi SAU (fullScreenPendingIntent) sẽ ÂM THẦM GHI ĐÈ extras của PendingIntent đã tạo
        // TRƯỚC (contentPendingIntent) do FLAG_UPDATE_CURRENT, dù 2 biến Kotlin trông độc lập.
        // Hiện tại 2 extras đang giống hệt nhau nên chưa lộ triệu chứng, nhưng là bug tiềm ẩn
        // nếu sau này 1 trong 2 mang thêm dữ liệu khác — tách hẳn requestCode để mỗi cái là 1
        // PendingIntent slot riêng, không phụ thuộc nội dung extras có trùng hay không.
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, notificationId + FULLSCREEN_REQUEST_CODE_OFFSET, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = actionBroadcast(CallActionReceiver.ACTION_ANSWER, callId, notificationId)
        val rejectIntent = actionBroadcast(CallActionReceiver.ACTION_REJECT, callId, notificationId)

        val appLogoIcon = androidx.core.graphics.drawable.IconCompat.createWithResource(
            context, R.mipmap.ic_launcher
        )
        // Person cần icon để CallStyle hiện đúng avatar tròn cạnh nút Nghe/Từ chối — chưa
        // có avatar riêng của người gọi (danh bạ hiện chỉ lưu tên, không lưu ảnh), nên dùng
        // tạm logo app làm fallback thay vì để trống (mặc định sẽ hiện chữ cái đầu tên, ít
        // nhận diện thương hiệu hơn).
        val caller = Person.Builder().setName(callerLabel).setIcon(appLogoIcon).build()

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            caller,
            rejectIntent,
            answerIntent
        )

        val title = if (isVideo) "Cuộc gọi video đến" else "Cuộc gọi thoại đến"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // ✅ SỬA: icon silhouette riêng của app (đơn sắc, bắt buộc) thay cho
            // ic_notification cũ (nhìn giống chuông mặc định).
            .setSmallIcon(R.drawable.ic_notification_small)
            // ✅ MỚI: logo màu đầy đủ — một số máy/launcher vẫn đọc largeIcon của Builder
            // ngoài icon gắn trên Person, giữ cả 2 cho chắc hiển thị nhất quán.
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(callerLabel)
            .setStyle(callStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)                    // Không cho vuốt tắt như notification thường — phải Nghe/Từ chối
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(contentPendingIntent)
            .addPerson(caller)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Hiện đầy đủ trên lock screen
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Thiếu quyền POST_NOTIFICATIONS (Android 13+, người dùng từ chối cấp) — CallSkill
            // vẫn đổ chuông/rung qua Ringtone/Vibrator API riêng (không cần quyền này), nên
            // cuộc gọi KHÔNG bị mất hoàn toàn, chỉ mất phần full-screen/lock-screen. Nuốt lỗi ở
            // đây có chủ đích: hàm này được gọi lại mỗi lần "call_offer" tới, ném exception lên
            // sẽ làm crash toàn bộ CallSkill.handleIncomingSignal().
        }
    }

    /** Gọi khi cuộc gọi được nhận/từ chối/kết thúc từ bất kỳ nơi nào khác (không chỉ từ 2 nút trên notification). */
    fun cancelIncomingCallNotification(callId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdForCall(callId))
    }

    private fun actionBroadcast(action: String, callId: String, notificationId: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
        }
        // requestCode khác nhau theo action (notificationId*2 +0/+1) để 2 PendingIntent
        // Nghe/Từ chối của CÙNG 1 callId không bị hệ thống coi là trùng nhau rồi gộp/đè.
        val requestCode = when (action) {
            CallActionReceiver.ACTION_ANSWER -> notificationId * 2
            else -> notificationId * 2 + 1
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "aichatvn_incoming_calls"
        private const val CHANNEL_NAME = "Cuộc gọi đến"
        private const val CHANNEL_DESCRIPTION = "Thông báo full-screen khi có cuộc gọi thoại/video P2P đến"

        // Dùng chung công thức hashCode(String) như NotificationSkill.notificationIdForAlert()
        // — nhất quán trong codebase, và đủ để CallActionReceiver tính lại ĐÚNG notificationId
        // từ callId nhận được trong Intent, không cần truyền thêm field nào khác.
        fun notificationIdForCall(callId: String): Int = callId.hashCode()

        // ✅ MỚI: offset cộng vào notificationId để tính requestCode riêng cho
        // fullScreenPendingIntent, tách khỏi contentPendingIntent (xem giải thích ở
        // showIncomingCallNotification()). Giá trị lớn, lẻ, không liên quan gì tới công thức
        // notificationId*2 (+0/+1) của actionBroadcast() — 2 hàm getActivity()/getBroadcast()
        // vốn đã nằm trong 2 "không gian" PendingIntent tách biệt (khác component đích), nên
        // chỉ cần đảm bảo không tự đụng nhau NGAY TRONG PHẠM VI 2 lệnh getActivity() ở đây.
        private const val FULLSCREEN_REQUEST_CODE_OFFSET = 987_654_321
    }
}