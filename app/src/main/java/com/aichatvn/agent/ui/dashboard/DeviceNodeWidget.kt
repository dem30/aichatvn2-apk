package com.aichatvn.agent.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DeviceNodeWidget(
    node: DeviceNode,
    onActionClick: (actionId: String, params: Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = node.online
    val isActive = node.status.contains("bật", ignoreCase = true) || 
                   node.status.contains("hoạt động", ignoreCase = true)

    // Hiệu ứng phát sáng mờ ảo động (Neon glow pulse) khi thiết bị đang chạy
    val transition = rememberInfiniteTransition(label = "glowPulse")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val activeThemeColor = when (node.type) {
        DeviceType.LIGHT -> Color(0xFFFFD54F) // Màu đèn vàng hổ phách
        DeviceType.CAMERA -> Color(0xFF4FC3F7) // Màu camera xanh dương nhạt
        else -> Color(0xFF81C784) // Màu xanh lá cho rơ-le / bơm nước / khóa cổng
    }

    val borderStyle = when {
        !isOnline -> BorderStroke(1.5.dp, Color.Red.copy(alpha = 0.4f))
        isActive -> BorderStroke(2.dp, activeThemeColor)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = modifier
            .width(150.dp)
            .height(115.dp)
            .drawBehind {
                if (isOnline && isActive) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            asFrameworkPaint().apply {
                                color = activeThemeColor.copy(alpha = glowAlpha).toArgb()
                                setShadowLayer(
                                    16.dp.toPx(),
                                    0f,
                                    0f,
                                    activeThemeColor.copy(alpha = glowAlpha).toArgb()
                                )
                            }
                        }
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            12.dp.toPx(), 12.dp.toPx(), paint
                        )
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        border = borderStyle,
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline) {
                if (isActive) activeThemeColor.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Icon tròn mờ + Đốm tín hiệu trạng thái kết nối
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOnline) {
                                if (isActive) activeThemeColor.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.LightGray.copy(alpha = 0.2f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isOnline) node.icon else "🔌",
                        fontSize = 16.sp
                    )
                }

                // Điểm tín hiệu Xanh (Online) / Đỏ (Offline)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color.Green else Color.Red)
                )
            }

            // Body: Tên thiết bị + Chuỗi mô tả trạng thái
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = node.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = node.status.ifBlank { if (isOnline) "Bình thường" else "Ngoại tuyến" },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant else Color.Red.copy(alpha = 0.7f)
                )
            }

            // Footer: Các nút điều khiển tác vụ nhanh trực tiếp
            if (isOnline && node.supportedActions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    node.supportedActions.take(2).forEach { action ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                                .clickable {
                                    onActionClick(action.id, action.defaultParams)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = action.icon,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}