package com.aichatvn.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aichatvn.agent.scheduler.TaskScheduler
import com.aichatvn.agent.service.WebhookGatewayService

/**
 * Khởi động lại TaskScheduler và WebhookGatewayService sau khi thiết bị reboot hoặc ứng dụng được cập nhật.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                TaskScheduler.ensureRunning(context)
                
                // ✅ ĐÃ SỬA: Tự động khởi chạy lại WebhookGatewayService đa kênh sau reboot
                try {
                    val serviceIntent = Intent(context, WebhookGatewayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}