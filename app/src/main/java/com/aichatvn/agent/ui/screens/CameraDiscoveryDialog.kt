package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichatvn.agent.tools.camera.DiscoveredCamera

/**
 * Dialog "1-click tự động dò tìm camera trong mạng LAN".
 *
 * ⚠️ CHỦ Ý: dialog này KHÔNG tự lưu camera vào DB — chỉ trả kết quả đã người dùng xác nhận qua
 * onCameraSelected(). Nơi gọi (vd CustomerCameraScreen) tự quyết định merge kết quả vào config
 * map hiện có rồi gọi saveCamera() như luồng thêm camera thủ công đã có sẵn — không phá vỡ
 * CameraDialog cũ.
 *
 * @param onCameraSelected gọi khi người dùng bấm "Dùng camera này" trên 1 kết quả.
 * @param onDismiss gọi khi người dùng đóng dialog (hủy quét nếu đang chạy dở).
 */
@Composable
fun CameraDiscoveryDialog(
    onCameraSelected: (DiscoveredCamera) -> Unit,
    onDismiss: () -> Unit,
    viewModel: com.aichatvn.agent.ui.viewmodels.CameraDiscoveryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Tự bắt đầu quét ngay khi dialog mở — đúng tinh thần "1-click": người dùng chỉ cần bấm nút
    // mở dialog này, không cần bấm thêm 1 nút "bắt đầu" nữa bên trong.
    LaunchedEffect(Unit) { viewModel.startScan() }

    AlertDialog(
        onDismissRequest = {
            viewModel.cancelScan()
            onDismiss()
        },
        title = { Text("🔍 Tự động dò tìm camera") },
        text = {
            Box(modifier = Modifier.heightIn(min = 120.dp, max = 420.dp)) {
                when (val s = state) {
                    is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Idle -> {
                        Text("Sẵn sàng quét.", modifier = Modifier.align(Alignment.Center))
                    }
                    is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Scanning -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            val progressText = if (s.total > 0) {
                                "Đang quét mạng LAN... (${s.current}/${s.total})"
                            } else {
                                "Đang xác định dải mạng..."
                            }
                            Text(progressText, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Có thể mất 30 giây đến vài phút tuỳ số lượng thiết bị trong mạng.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Error -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "❌ ${s.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Kiểm tra điện thoại đã kết nối Wi-Fi (không phải dữ liệu di động) rồi thử lại.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Done -> {
                        if (s.results.isEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Không tìm thấy camera nào trong mạng.")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Camera có thể chưa kết nối Wi-Fi nhà, hoặc dùng path/mật khẩu " +
                                        "không nằm trong danh sách hỗ trợ. Bạn có thể nhập tay ở màn cấu hình.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(s.results, key = { it.ip + it.snapshotUrl }) { cam ->
                                    DiscoveredCameraRow(
                                        camera = cam,
                                        onSelect = { onCameraSelected(cam) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val s = state
            if (s is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Done ||
                s is com.aichatvn.agent.ui.viewmodels.DiscoveryState.Error
            ) {
                TextButton(onClick = { viewModel.startScan() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quét lại")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.cancelScan()
                onDismiss()
            }) { Text("Đóng") }
        }
    )
}

@Composable
private fun DiscoveredCameraRow(
    camera: DiscoveredCamera,
    onSelect: () -> Unit
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail nhỏ từ ảnh preview thật đã fetch được — giúp người dùng xác nhận đúng
            // camera thay vì chỉ nhìn IP/URL trừu tượng.
            val bitmap = remember(camera.previewBytes) {
                camera.previewBytes?.let {
                    try {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(camera.ip, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Đoán: ${camera.vendorGuess}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!camera.username.isNullOrBlank()) {
                    Text(
                        "🔒 Cần đăng nhập (đã tự dò được)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Button(onClick = onSelect) { Text("Dùng camera này") }
        }
    }
}
