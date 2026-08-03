package com.aichatvn.agent.ui.invite

import android.content.Context
import android.content.Intent

// ✅ MỚI: Tách riêng khỏi ViewModel để tái dùng ở nhiều màn nếu cần (vd sau này thêm nút mời
// trong SettingsScreen). Chia sẻ link /i/{code} — landing page tự copy code vào clipboard khi
// người được mời bấm "Tải", nên ActivationScreen bên máy họ tự dán được luôn.
object InviteShareHelper {
    fun buildInviteShareIntent(context: Context, code: String, baseUrl: String): Intent {
        val link = "${baseUrl.trimEnd('/')}/i/$code"
        val shareText = "Mình đang dùng AIChatVN2 — tải app qua link này để dùng thử nhé: $link"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        return Intent.createChooser(sendIntent, "Mời bạn dùng thử")
    }
}