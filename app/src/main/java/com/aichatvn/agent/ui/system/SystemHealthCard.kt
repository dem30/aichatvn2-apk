package com.aichatvn.agent.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.aichatvn.agent.data.model.SystemHealthReport
import com.aichatvn.agent.ui.viewmodels.SystemHealthViewModel

/**
 * SystemHealthCard
 *
 * Card tóm tắt cho Dashboard, đúng tinh thần UX review đã duyệt: không thuật ngữ kỹ thuật
 * (không nói "ONVIF"/"RTSP"), chỉ hiện "x/y mục đã tối ưu" + nút hành động rõ ràng.
 *
 * onOpenDetail: điều hướng sang SystemHealthScreen (route "system_health") — Card này
 * KHÔNG tự chứa NavController, nhận callback từ nơi gọi (giống pattern AiRecommendationCard
 * trong DashboardScreen.kt: onApprove/onIgnore truyền từ ngoài vào).
 */
@Composable
fun SystemHealthCard(
    onOpenDetail: () -> Unit,
    viewModel: SystemHealthViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsState()
    val isApplying by viewModel.isApplying.collectAsState()

    // Chưa có report (đang quét lần đầu) — không hiện Card trống gây khó hiểu, đợi có dữ
    // liệu thật mới render. isAuditing được UI cha (nếu cần) tự xử lý loading riêng.
    val currentReport = report ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Sức khoẻ hệ thống",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = summaryLine(currentReport),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val safeItems = currentReport.safeImprovableItems
            if (safeItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { viewModel.applySafeFixes() },
                        enabled = !isApplying,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isApplying) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Áp dụng ${safeItems.size} cải thiện", fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    TextButton(onClick = onOpenDetail) {
                        Text("Xem chi tiết", fontSize = 13.sp)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onOpenDetail) {
                    Text("Xem chi tiết", fontSize = 13.sp)
                }
            }
        }
    }
}

/** "6/9 mục đã tối ưu" — 1 dòng, không thuật ngữ kỹ thuật, theo đúng UX review đã duyệt. */
private fun summaryLine(report: SystemHealthReport): String {
    return "${report.optimizedCount}/${report.applicableCount} mục đã tối ưu"
}
