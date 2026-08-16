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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.tools.camera.CameraCapabilityProber
import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.tools.camera.DiscoveredCredentials
import com.aichatvn.agent.tools.camera.discovery.CameraQrIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ✅ SỬA KIẾN TRÚC (theo góp ý sau case V380): trước đây "Dùng camera này" gán THẲNG kết quả
 * discovery vào form camera — với ONVIF/V380 (chưa xác nhận endpoint ảnh thật), điều này buộc
 * phải bịa "http://<IP>" làm snapshotUrl giả, khiến 1 field mang 2 nghĩa lẫn lộn (đã xác minh vs
 * chỉ là IP đoán được).
 *
 * Sửa: bấm vào 1 kết quả giờ mở [CameraVerificationDialog] — màn RIÊNG hiển thị đúng những gì
 * discovery THẬT SỰ biết (IP/port/protocol/deviceId), cho nhập username/password nếu cần, và có
 * nút "Tự dò giao thức" gọi CameraCapabilityProber thật (ONVIF → RTSP → HTTP snapshot) TRƯỚC khi
 * cho phép "Thêm camera". onCameraSelected() giờ chỉ được gọi SAU khi đã xác minh — camera lúc đó
 * chắc chắn có ít nhất 1 endpoint đã probe thành công, không còn khả năng tạo form trống hoặc URL
 * giả mạo trông như đã xác minh.
 *
 * ✅ MỚI: đối chiếu QR ↔ LAN discovery — qrIdentity (từ CameraQrDiscovery.scan(), quét mã QR trên
 * thân camera) là optional, TRUYỀN VÀO chứ dialog này không tự quét QR. Khi có qrIdentity, mỗi
 * kết quả LAN discovery có deviceId khớp (contains, ignoreCase — 2 nguồn có thể biểu diễn khác
 * định dạng dù cùng giá trị gốc, xem CameraQrDiscovery.kt) được đánh dấu "🟢 Khớp mã QR" và đẩy
 * lên đầu danh sách — CHỈ là gợi ý hiển thị, không tự động gộp/chọn thay người dùng. Chỉ khi thật
 * sự KHỚP, lúc người dùng bấm "Chọn", deviceId/manufacturer còn thiếu của kết quả LAN mới được bổ
 * sung từ qrIdentity (null-coalescing — không ghi đè giá trị probe LAN đã tự có).
 */

// ── State cho bước xác minh (probe) ────────────────────────────────────────

sealed interface VerificationState {
    data object Idle : VerificationState
    data object Probing : VerificationState
    data class Done(val result: com.aichatvn.agent.tools.camera.CapabilityProbeResult) : VerificationState
}

@HiltViewModel
class CameraVerificationViewModel @Inject constructor(
    private val capabilityProber: CameraCapabilityProber,
) : ViewModel() {
    private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    fun probe(ip: String, port: Int, username: String?, password: String?) {
        _state.value = VerificationState.Probing
        viewModelScope.launch {
            val result = capabilityProber.probe(ip, port, username, password)
            _state.value = VerificationState.Done(result)
        }
    }

    fun reset() { _state.value = VerificationState.Idle }
}

@Composable
fun CameraDiscoveryDialog(
    onCameraSelected: (DiscoveredCamera) -> Unit,
    onDismiss: () -> Unit,
    // ✅ MỚI: kết quả quét QR trước đó (nếu có) — dùng để đối chiếu deviceId với kết quả LAN
    // discovery, xem ghi chú "✅ MỚI" ở đầu file. null nghĩa là chưa quét QR, không ảnh hưởng gì
    // tới hành vi cũ.
    qrIdentity: CameraQrIdentity? = null,
    viewModel: com.aichatvn.agent.ui.viewmodels.CameraDiscoveryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var cameraToVerify by remember { mutableStateOf<DiscoveredCamera?>(null) }

    LaunchedEffect(Unit) { viewModel.startScan() }

    if (cameraToVerify != null) {
        CameraVerificationDialog(
            camera = cameraToVerify!!,
            onConfirm = { verified ->
                onCameraSelected(verified)
                cameraToVerify = null
            },
            onDismiss = { cameraToVerify = null }
        )
        // Dialog xác minh nằm ĐÈ LÊN, không thay thế dialog danh sách bên dưới — người dùng bấm
        // "Đóng" ở đây quay lại được danh sách kết quả, không mất phiên quét đang có.
        return
    }

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
                            // Kết quả khớp deviceId với qrIdentity (nếu có) lên đầu danh sách —
                            // chỉ sắp xếp lại thứ tự hiển thị, KHÔNG lọc bớt kết quả nào.
                            val sortedResults = remember(s.results, qrIdentity) {
                                s.results.sortedByDescending { isQrMatch(it, qrIdentity) }
                            }
                            if (qrIdentity != null) {
                                Text(
                                    "Đã có mã QR (deviceId: ${qrIdentity.deviceId ?: "?"}) — " +
                                        "camera khớp bên dưới được đánh dấu 🟢",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(sortedResults, key = { it.ip + it.protocol }) { cam ->
                                    val matched = isQrMatch(cam, qrIdentity)
                                    DiscoveredCameraRow(
                                        camera = cam,
                                        isQrMatch = matched,
                                        onSelect = {
                                            // Chỉ bổ sung deviceId/manufacturer từ QR khi THẬT SỰ
                                            // khớp (matched) — tránh gán nhầm danh tính QR của 1
                                            // camera khác cho camera người dùng vừa chọn.
                                            cameraToVerify = if (matched) {
                                                cam.copy(
                                                    deviceId = cam.deviceId ?: qrIdentity?.deviceId,
                                                    manufacturer = cam.manufacturer ?: qrIdentity?.vendor
                                                )
                                            } else cam
                                        }
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

/**
 * So khớp deviceId giữa 1 kết quả LAN discovery và qrIdentity — dùng contains(ignoreCase) thay vì
 * so bằng tuyệt đối, vì 2 nguồn (QR parse heuristic vs ONVIF XAddr/V380 UDP response) có thể biểu
 * diễn cùng 1 giá trị gốc khác định dạng (hoa/thường, có/không tiền tố). false nếu thiếu 1 trong 2
 * deviceId — không suy diễn khớp từ dữ liệu rỗng.
 */
private fun isQrMatch(camera: DiscoveredCamera, qrIdentity: CameraQrIdentity?): Boolean {
    val camId = camera.deviceId?.trim()
    val qrId = qrIdentity?.deviceId?.trim()
    if (camId.isNullOrBlank() || qrId.isNullOrBlank()) return false
    return camId.contains(qrId, ignoreCase = true) || qrId.contains(camId, ignoreCase = true)
}

@Composable
private fun DiscoveredCameraRow(
    camera: DiscoveredCamera,
    isQrMatch: Boolean = false,
    onSelect: () -> Unit
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

                // ✅ SỬA LỖI LAYOUT (ảnh chụp thật cho thấy chữ bị wrap từng ký tự/từ 1 dòng —
                // KHÔNG phải bug "chữ bị xoay"): trước đây Column(weight(1f)) này nằm CÙNG 1 Row
                // với Button("Dùng camera này") không giới hạn width. Compose đo phần tử KHÔNG có
                // weight trước (Button, theo intrinsic width của text dài) rồi mới chia phần còn
                // lại cho weight(1f) — với Row hẹp (dialog trên điện thoại), phần còn lại gần như
                // bằng 0, ép Column xuống còn vài dp nên mỗi từ tự wrap xuống 1 dòng.
                // Sửa: tách Button RA KHỎI Row này — đưa xuống dòng riêng bên dưới, full width.
                // Column giờ là phần tử DUY NHẤT có weight trong Row (chỉ còn cạnh icon cố định
                // 56dp), nên luôn được đo đúng, không còn bị bóp bởi bất kỳ text nào khác.
                Column(modifier = Modifier.weight(1f)) {
                    if (isQrMatch) {
                        Text(
                            "🟢 Khớp mã QR vừa quét",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(camera.ip, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Đoán: ${camera.manufacturer ?: "?"} — giao thức: ${camera.protocol}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (camera.credentialsRequired) {
                        Text(
                            "🔒 Cần đăng nhập" + (camera.credentials?.let { " (đã tự dò được)" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ✅ SỬA: đổi điều kiện theo verifiedSnapshotUrl (thay snapshotUrl cũ) — camera
                    // ONVIF/V380 luôn có verifiedSnapshotUrl == null vì chưa qua bước xác minh.
                    if (camera.verifiedSnapshotUrl == null) {
                        Text(
                            "📡 Chưa xác minh endpoint — bấm để dò giao thức thật",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Button giờ full-width, dòng riêng bên dưới — không còn tranh chấp không gian
            // ngang với Column thông tin phía trên.
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn")
            }
        }
    }
}

// ── Màn xác minh camera đã chọn ─────────────────────────────────────────────

/**
 * Hiển thị đúng những gì discovery THẬT SỰ biết (IP/port/protocol/deviceId), cho nhập
 * username/password, và bắt buộc bấm "Tự dò giao thức" (gọi CameraCapabilityProber thật) trước
 * khi cho phép "Thêm camera" — trừ khi camera đã có verifiedSnapshotUrl sẵn từ HttpSnapshotProbe
 * (trường hợp đó coi như đã xác minh xong từ bước discovery, không bắt probe lại vô ích).
 */
@Composable
private fun CameraVerificationDialog(
    camera: DiscoveredCamera,
    onConfirm: (DiscoveredCamera) -> Unit,
    onDismiss: () -> Unit,
    viewModel: CameraVerificationViewModel = hiltViewModel()
) {
    val verificationState by viewModel.state.collectAsState()
    var username by remember { mutableStateOf(camera.credentials?.username ?: "") }
    var password by remember { mutableStateOf(camera.credentials?.password ?: "") }

    // Camera từ HttpSnapshotProbe đã có verifiedSnapshotUrl sẵn — không cần bắt xác minh lại,
    // coi như đã "Done" ngay từ đầu để người dùng bấm "Thêm camera" được luôn.
    val alreadyVerified = camera.verifiedSnapshotUrl != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xác minh camera") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("IP: ${camera.ip}:${camera.port}", fontWeight = FontWeight.Bold)
                Text("Giao thức phát hiện: ${camera.protocol}", style = MaterialTheme.typography.bodyMedium)
                camera.deviceId?.let {
                    Text("Định danh: $it", style = MaterialTheme.typography.bodyMedium)
                }
                camera.manufacturer?.let {
                    Text("Hãng (đoán): $it", style = MaterialTheme.typography.labelSmall)
                }

                if (alreadyVerified) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "✅ Đã xác minh — ảnh snapshot lấy được tại: ${camera.verifiedSnapshotUrl}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Chưa xác minh được endpoint ảnh/stream thật. Nhập tài khoản nếu camera " +
                            "cần đăng nhập, rồi bấm \"Tự dò giao thức\" để thử ONVIF/RTSP/HTTP.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("Tài khoản (nếu cần)") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Mật khẩu (nếu cần)") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )

                    when (val vs = verificationState) {
                        is VerificationState.Idle -> {
                            OutlinedButton(
                                onClick = {
                                    viewModel.probe(
                                        camera.ip, camera.port,
                                        username.ifBlank { null }, password.ifBlank { null }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Tự dò giao thức")
                            }
                        }
                        is VerificationState.Probing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Đang dò ONVIF/RTSP...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        is VerificationState.Done -> {
                            val r = vs.result
                            if (r.onvifSupported || r.rtspSupported) {
                                Text(
                                    "✅ Xác minh thành công" +
                                        (if (r.rtspSupported) " — RTSP: ${r.rtspUrl}" else "") +
                                        (if (r.onvifSupported) " — có ONVIF" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    "❌ Không xác minh được ONVIF/RTSP tại địa chỉ này. Có thể camera " +
                                        "dùng giao thức riêng (như V380) chưa hỗ trợ xác minh tự động — " +
                                        "vẫn có thể thêm camera và tự nhập URL/RTSP thủ công sau.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = { viewModel.reset() }) { Text("Dò lại") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val vs = verificationState
            // Cho phép "Thêm camera" khi: đã có verifiedSnapshotUrl từ trước, HOẶC probe vừa xác
            // minh được RTSP (rtspUrl là endpoint dùng được thật — gán vào verifiedSnapshotUrl),
            // HOẶC người dùng chấp nhận thêm dù chưa xác minh (camera dùng giao thức riêng chưa
            // hỗ trợ auto-probe, như V380) — nhưng KHÔNG tự bịa URL nào cho trường hợp cuối.
            val verifiedResult = (vs as? VerificationState.Done)?.result
            val newCredentials = if (username.isNotBlank()) DiscoveredCredentials(username, password) else camera.credentials
            val newCredentialsRequired = username.isNotBlank() || camera.credentialsRequired

            val finalCamera = when {
                alreadyVerified -> camera
                // RTSP xác minh được (DESCRIBE trả 200/401 thật) — ĐÂY mới là "đã xác minh", gán
                // thẳng rtspUrl (đã kèm credential nếu cần, xem buildRtspUrlWithCredentials trong
                // CameraCapabilityProber) làm verifiedSnapshotUrl.
                verifiedResult?.rtspSupported == true && verifiedResult.rtspUrl != null ->
                    camera.copy(
                        verifiedSnapshotUrl = verifiedResult.rtspUrl,
                        credentials = newCredentials,
                        credentialsRequired = newCredentialsRequired
                    )
                // Chỉ ONVIF (không có RTSP) — có device_service URL nhưng đó KHÔNG phải endpoint
                // ảnh/stream dùng để xem trực tiếp, nên vẫn để verifiedSnapshotUrl null; chỉ cập
                // nhật credentials đã nhập để không mất nếu người dùng tự điền URL sau.
                else -> camera.copy(
                    credentials = newCredentials,
                    credentialsRequired = newCredentialsRequired
                )
            }
            Button(onClick = { onConfirm(finalCamera) }) {
                Text(if (alreadyVerified || finalCamera.verifiedSnapshotUrl != null) "Thêm camera" else "Thêm camera (chưa xác minh)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        }
    )
}