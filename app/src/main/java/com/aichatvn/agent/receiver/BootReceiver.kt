package com.aichatvn.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aichatvn.agent.scheduler.TaskScheduler

/**
 * Khởi động lại TaskScheduler sau khi thiết bị reboot.
 *
 * WorkManager periodic work bị huỷ khi thiết bị tắt nguồn — BroadcastReceiver này
 * đăng ký BOOT_COMPLETED để tự động schedule lại ngay khi Android khởi động xong.
 *
 * Bắt buộc khai báo trong AndroidManifest.xml:
 *
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 * <receiver
 *     android:name=".receiver.BootReceiver"
 *     android:enabled="true"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *         <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *     </intent-filter>
 * </receiver>
 *
 * MY_PACKAGE_REPLACED: tự restart sau khi app được update (WorkManager cũng bị
 * cancel khi APK được replace).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                TaskScheduler.ensureRunning(context)
            }
        }
    }
}