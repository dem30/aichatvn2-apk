package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.tools.camera.DiscoveredCamera

@Composable
fun CameraCard(
    camera: CameraConfigEntity,
    isAdmin: Boolean,
    isSmartMode: Boolean,
    isMasterSmartOff: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleSmartMode: () -> Unit,
    onTest: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val isTracking = camera.manualOff == 0
    val isOnline = camera.isOnline == 1

    val cardColor = when {
        camera.manualOff == 1 -> MaterialTheme.colorScheme.surfaceVariant
        !isOnline -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(camera.customername, style = MaterialTheme.typography.titleMedium)
                    Text(
                        camera.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        camera.manualOff == 1 -> MaterialTheme.colorScheme.surfaceVariant
                        !isOnline -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = when {
                            camera.manualOff == 1 -> "⏸ Đã tắt"
                            !isOnline -> "🔴 Mất kết nối"
                            else -> "🟢 Hoạt động"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = camera.landinfo ?: "Chưa có thông tin thửa đất",
                style = MaterialTheme.typography.bodyMedium
            )

            if (camera.aiPrompt.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "🤖 ${camera.aiPrompt.take(60)}${if (camera.aiPrompt.length > 60) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isAdmin) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Theo dõi", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (isTracking) "Đang bật — camera được quét tự động"
                            else "Đã tắt — không quét",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isTracking, onCheckedChange = { onToggleActive() })
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Smart Mode", style = MaterialTheme.typography.labelMedium)
                        Text(
                            when {
                                isMasterSmartOff -> "⚠️ Master tắt — bật tổng ở TopBar để có hiệu lực"
                                isSmartMode -> "Bật — AI phân tích & gửi email khi có biến động"
                                else -> "Tắt — chỉ so sánh ảnh, không gọi AI"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMasterSmartOff) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSmartMode,
                        onCheckedChange = { onToggleSmartMode() },
                        enabled = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onTest) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test ngay")
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Sửa")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)




@Composable
fun CameraDialog(
    camera: CameraConfigEntity?,
    customer: CustomerEntity?,          // ✅ MỚI
    defaults: CameraSkill.NewCameraDefaults, // ✅ MỚI: giá trị mặc định lấy từ AppConfigDefaults, dùng khi camera == null
    onDismiss: () -> Unit,
    onSave: (Map<String, Any>) -> Unit
) {
    var id by remember { mutableStateOf(camera?.id ?: java.util.UUID.randomUUID().toString()) }
    // ✅ SỬA: luôn lấy từ Customer thật, không cho gõ tay để tránh lệch dữ liệu
    val customerId = customer?.id ?: camera?.customerId ?: ""
    val customerName = customer?.name ?: camera?.customername ?: ""
    val customerEmail = customer?.email ?: camera?.customeremail ?: ""


    
    var snapshotUrl by remember { mutableStateOf(camera?.snapshoturl ?: "") }
    // ✅ MỚI: Basic Auth cho camera LAN — nạp sẵn từ camera đang sửa (nếu có), để trống khi thêm mới.
    var snapshotUsername by remember { mutableStateOf(camera?.snapshotUsername ?: "") }
    var snapshotPassword by remember { mutableStateOf(camera?.snapshotPassword ?: "") }
    // ✅ MỚI: URL dự phòng cloud/public — dùng khi LAN không kết nối được (vd đang ở ngoài mạng nhà).
    var snapshotUrlRemote by remember { mutableStateOf(camera?.snapshotUrlRemote ?: "") }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    var landInfo by remember { mutableStateOf(camera?.landinfo ?: "") }
    // ✅ SỬA: camera mới lấy prompt/từ khoá mặc định từ AppConfigDefaults (đã nạp qua `defaults`)
    // thay vì gõ cứng "cảnh báo"/"bình thường" trực tiếp trong UI.
    var aiPrompt by remember { mutableStateOf(camera?.aiPrompt ?: defaults.aiPrompt) }
    var aiPositiveKeywords by remember { mutableStateOf(camera?.aiPositiveKeywords ?: defaults.aiPositiveKeywords) }
    var aiNegativeKeywords by remember { mutableStateOf(camera?.aiNegativeKeywords ?: defaults.aiNegativeKeywords) }

    val context = LocalContext.current
    var locationError by remember { mutableStateOf<String?>(null) }
    var gpsLoading by remember { mutableStateOf(false) }

    // ✅ MỚI: dialog tự động dò tìm camera LAN — tách riêng khỏi AlertDialog chính bên dưới để
    // tránh lồng dialog-trong-dialog. Khi người dùng chọn 1 kết quả, tự điền lại 3 state ở trên
    // rồi đóng dialog dò tìm — người dùng vẫn thấy form chính, chỉ cần bấm "Lưu" như bình thường.
    if (showDiscoveryDialog) {
        CameraDiscoveryDialog(
            onCameraSelected = { discovered: DiscoveredCamera ->
                snapshotUrl = discovered.snapshotUrl
                snapshotUsername = discovered.username ?: ""
                snapshotPassword = discovered.password ?: ""
                showDiscoveryDialog = false
            },
            onDismiss = { showDiscoveryDialog = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (camera == null) "Thêm Camera" else "Sửa Camera") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Mã camera") }, modifier = Modifier.fillMaxWidth(), enabled = camera == null)
                Text("Khách hàng: $customerName ($customerId)", style = MaterialTheme.typography.bodyMedium)
if (customerEmail.isNotBlank()) Text("Email: $customerEmail", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(value = snapshotUrl, onValueChange = { snapshotUrl = it }, label = { Text("URL ảnh chụp") }, modifier = Modifier.fillMaxWidth())

                // ✅ MỚI: nút 1-click dò tìm camera LAN — tự điền URL + tài khoản/mật khẩu nếu tìm
                // thấy, người dùng vẫn có thể sửa tay các ô bên dưới sau khi dò.
                OutlinedButton(
                    onClick = { showDiscoveryDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tự động dò tìm camera trong mạng")
                }

                OutlinedTextField(
                    value = snapshotUsername,
                    onValueChange = { snapshotUsername = it },
                    label = { Text("Tài khoản camera (nếu cần)") },
                    modifier = Modifier.fillMaxWidth()
                )
                var showPassword by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = snapshotPassword,
                    onValueChange = { snapshotPassword = it },
                    label = { Text("Mật khẩu camera (nếu cần)") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Ẩn mật khẩu" else "Hiện mật khẩu"
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = snapshotUrlRemote,
                    onValueChange = { snapshotUrlRemote = it },
                    label = { Text("URL dự phòng (cloud, dùng khi ra ngoài mạng nhà)") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Để trống nếu chỉ dùng trong mạng nhà. Lấy từ app gốc của hãng camera.") }
                )

                OutlinedTextField(value = landInfo, onValueChange = { landInfo = it }, label = { Text("Thông tin thửa đất") }, modifier = Modifier.fillMaxWidth())

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Cài đặt AI", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(value = aiPrompt, onValueChange = { aiPrompt = it }, label = { Text("AI Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 4)
                OutlinedTextField(value = aiPositiveKeywords, onValueChange = { aiPositiveKeywords = it }, label = { Text("Từ khóa cảnh báo (phân cách bằng dấu phẩy)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("cảnh báo, phát hiện, xâm nhập") })
                OutlinedTextField(value = aiNegativeKeywords, onValueChange = { aiNegativeKeywords = it }, label = { Text("Từ khóa bình thường (phân cách bằng dấu phẩy)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("bình thường, không có") })

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            locationError = null
                            val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!hasFine && !hasCoarse) { locationError = "Chưa cấp quyền vị trí"; return@OutlinedButton }
                            gpsLoading = true
                            val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                            fusedClient.lastLocation
                                .addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        landInfo = if (landInfo.isBlank()) "${loc.latitude},${loc.longitude}"
                                                   else "$landInfo\nVị trí: ${loc.latitude},${loc.longitude}"
                                        locationError = null
                                        gpsLoading = false
                                    } else {
                                        val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
                                            .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                                            .setMaxUpdateAgeMillis(0)
                                            .build()
                                        fusedClient.getCurrentLocation(req, null)
                                            .addOnSuccessListener { fresh ->
                                                if (fresh != null) {
                                                    landInfo = if (landInfo.isBlank()) "${fresh.latitude},${fresh.longitude}"
                                                               else "$landInfo\nVị trí: ${fresh.latitude},${fresh.longitude}"
                                                } else {
                                                    locationError = "Không lấy được vị trí, bật GPS rồi thử lại"
                                                }
                                                gpsLoading = false
                                            }
                                            .addOnFailureListener { e -> locationError = "Lỗi: ${e.message}"; gpsLoading = false }
                                    }
                                }
                                .addOnFailureListener { e -> locationError = "Lỗi: ${e.message}"; gpsLoading = false }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !gpsLoading
                    ) {
                        if (gpsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (gpsLoading) "Đang lấy..." else "📍 Lấy vị trí")
                    }

                    OutlinedButton(
                        onClick = {
                            locationError = null
                            val m = Regex("(-?\\d{1,3}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)").find(landInfo)
                            if (m != null) {
                                val lat = m.groupValues[1]; val lng = m.groupValues[2]
                                try {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
                                } catch (e: Exception) { locationError = "Không mở được bản đồ" }
                            } else locationError = "Chưa có tọa độ, bấm 'Lấy vị trí' trước"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("🗺️ Mở bản đồ") }
                }

                if (locationError != null) {
                    Text(locationError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (id.isNotBlank() && snapshotUrl.isNotBlank()) {
                    onSave(mapOf(
                        "id" to id.trim(),
                        "customerId" to customerId.trim(),
                        "customername" to customerName.trim(),
                        "customeremail" to customerEmail.trim(),
                        "snapshoturl" to snapshotUrl.trim(),
                        "snapshotUsername" to snapshotUsername.trim(),
                        "snapshotPassword" to snapshotPassword.trim(),
                        "snapshotUrlRemote" to snapshotUrlRemote.trim(),
                        "landinfo" to landInfo.trim(),
                        "aiPrompt" to aiPrompt.trim(),
                        "aiPositiveKeywords" to aiPositiveKeywords.trim(),
                        "aiNegativeKeywords" to aiNegativeKeywords.trim()
                    ))
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}