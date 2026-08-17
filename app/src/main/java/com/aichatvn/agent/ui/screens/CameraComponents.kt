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
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.skills.CameraSkill
import com.aichatvn.agent.tools.camera.DiscoveredCamera
import com.aichatvn.agent.tools.camera.discovery.CameraQrIdentity
import com.aichatvn.agent.tools.camera.discovery.CameraQrScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ✅ MỚI (theo góp ý "người dùng không hiểu kỹ thuật, làm hoàn toàn cho họ click"): 3 mảnh ghép
// UX thêm vào CameraDialog/CameraCard bên dưới, tất cả chỉ dùng dependency đã có sẵn (OkHttp) —
// không cần thêm thư viện mới:
// 1. UrlTestState + testSnapshotUrl(): nút "Kiểm tra kết nối" gọi thử GET thật lên URL vừa nhập,
//    báo ✅/❌ NGAY tại chỗ thay vì Lưu xong mới biết camera đen ở Dashboard.
// 2. VendorGuideDialog: hướng dẫn chung chung (không bịa chi tiết RTSP path riêng cho từng hãng —
//    rủi ro sai cao hơn lợi ích) + nút mở CH Play tìm đúng app gốc hãng camera.
// 3. CameraCard: thêm badge cảnh báo + nút "Cấu hình ngay" khi camera đã lưu nhưng chưa có URL nào
//    dùng được — trước đây người dùng phải tự nhận ra camera "🔴 Mất kết nối" là do thiếu URL hay
//    do camera thật sự offline, không phân biệt được.

/** Kết quả bấm "Kiểm tra kết nối" cho URL ảnh chụp vừa nhập/dò được. */
private sealed interface UrlTestState {
    data object Idle : UrlTestState
    data object Testing : UrlTestState
    data class Success(val message: String) : UrlTestState
    data class Failure(val message: String) : UrlTestState
}

/**
 * Gọi thử GET thật lên [url] (kèm Basic Auth nếu có tài khoản/mật khẩu) — timeout ngắn (5s) vì đây
 * là kiểm tra tương tác trực tiếp trong dialog, không phải background probe. Phân biệt 3 tình
 * huống: lỗi kết nối (sai IP/URL/không cùng mạng), lỗi HTTP (sai tài khoản/mật khẩu/path), và
 * thành công nhưng nội dung trả về không phải ảnh (URL đúng dạng nhưng trỏ sai chỗ, vd trang HTML
 * đăng nhập thay vì snapshot thật) — cả 3 đều là lỗi "chưa dùng được" nhưng cách sửa khác nhau nên
 * cần thông báo khác nhau để người dùng biết sửa gì tiếp theo.
 *
 * ✅ SỬA (phát hiện qua case thật: URL RTSP do CameraCapabilityProber xác minh vẫn báo đỏ "Expected
 * URL scheme http/https" khi bấm "Kiểm tra kết nối"): OkHttp CHỈ hiểu http/https, không hiểu
 * rtsp — trước đây hàm này luôn dùng OkHttp bất kể scheme, khiến 1 URL RTSP ĐÃ ĐƯỢC XÁC MINH THẬT
 * (DESCRIBE trả 200/401 qua CameraCapabilityProber) vẫn bị báo lỗi kết nối ngay tại đây, gây hiểu
 * lầm nghiêm trọng "camera không dùng được" trong khi RTSP đó có thể hoàn toàn ổn. Sửa: rẽ nhánh
 * theo scheme — rtsp:// dùng DESCRIBE thô qua socket (cùng kỹ thuật CameraCapabilityProber.
 * rtspDescribeOk, viết lại độc lập ở đây vì đó là hàm private trong module khác), http(s):// giữ
 * nguyên hành vi cũ.
 */
private suspend fun testSnapshotUrl(url: String, username: String, password: String): UrlTestState {
    return withContext(Dispatchers.IO) {
        if (url.startsWith("rtsp://", ignoreCase = true)) {
            return@withContext testRtspUrl(url, username, password)
        }
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val requestBuilder = okhttp3.Request.Builder().url(url)
            if (username.isNotBlank()) {
                requestBuilder.header("Authorization", okhttp3.Credentials.basic(username, password))
            }
            client.newCall(requestBuilder.build()).execute().use { resp ->
                when {
                    !resp.isSuccessful ->
                        UrlTestState.Failure("❌ Camera trả lỗi HTTP ${resp.code} — kiểm tra lại tài khoản/mật khẩu, hoặc URL đã đúng chưa.")
                    (resp.header("Content-Type") ?: "").startsWith("image/") ->
                        UrlTestState.Success("✅ Kết nối thành công — camera trả về ảnh thật.")
                    else ->
                        UrlTestState.Failure("⚠️ Kết nối được nhưng nội dung trả về không phải ảnh — URL có thể trỏ sai chỗ (vd trang đăng nhập thay vì ảnh camera).")
                }
            }
        } catch (e: Exception) {
            UrlTestState.Failure("❌ Không kết nối được: ${e.message ?: "lỗi không xác định"}. Kiểm tra điện thoại và camera có đang cùng mạng Wi-Fi nhà không.")
        }
    }
}

/**
 * ✅ MỚI: kiểm tra URL rtsp:// bằng DESCRIBE thô qua raw socket — không dùng ExoPlayer/MediaExtractor
 * ở đây vì chỉ cần xác nhận server trả lời đúng giao thức, không cần decode/hiển thị video thật
 * (hiển thị video thật là việc của màn xem camera, không phải bước "kiểm tra kết nối" này). Cùng kỹ
 * thuật CameraCapabilityProber.rtspDescribeOk() — 401 vẫn coi là "kết nối được" (server đã xác nhận
 * hiểu RTSP, chỉ là sai/thiếu credential), 200 là thành công không cần auth.
 */
private fun testRtspUrl(url: String, username: String, password: String): UrlTestState {
    return try {
        val uri = java.net.URI(url)
        val host = uri.host
        if (host.isNullOrBlank()) {
            return UrlTestState.Failure("❌ URL RTSP không hợp lệ — thiếu địa chỉ IP.")
        }
        val port = if (uri.port > 0) uri.port else 554

        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 3_000)
            socket.soTimeout = 3_000

            val request = buildString {
                append("DESCRIBE $url RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                if (username.isNotBlank()) {
                    val basic = android.util.Base64.encodeToString(
                        "$username:$password".toByteArray(), android.util.Base64.NO_WRAP
                    )
                    append("Authorization: Basic $basic\r\n")
                }
                append("Accept: application/sdp\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()

            val statusLine = socket.getInputStream().bufferedReader().readLine()
                ?: return UrlTestState.Failure("❌ Camera không phản hồi — kiểm tra điện thoại và camera có đang cùng mạng Wi-Fi nhà không.")

            when {
                statusLine.contains("RTSP/1.0 200") ->
                    UrlTestState.Success("✅ Kết nối thành công — camera phản hồi RTSP hợp lệ, không cần tài khoản.")
                statusLine.contains("RTSP/1.0 401") ->
                    if (username.isNotBlank())
                        UrlTestState.Failure("❌ Camera từ chối tài khoản/mật khẩu vừa nhập — kiểm tra lại thông tin đăng nhập của camera.")
                    else
                        UrlTestState.Failure("🔒 Camera yêu cầu đăng nhập — nhập tài khoản/mật khẩu rồi kiểm tra lại.")
                else ->
                    UrlTestState.Failure("⚠️ Camera phản hồi nhưng không đúng giao thức RTSP mong đợi ($statusLine) — URL có thể trỏ sai chỗ.")
            }
        }
    } catch (e: Exception) {
        UrlTestState.Failure("❌ Không kết nối được: ${e.message ?: "lỗi không xác định"}. Kiểm tra điện thoại và camera có đang cùng mạng Wi-Fi nhà không.")
    }
}

/**
 * Hướng dẫn CHUNG lấy URL xem camera — cố ý KHÔNG bịa chi tiết path/RTSP riêng cho từng hãng (vd
 * V380) vì chưa có tài liệu nguồn xác nhận, sai thì hại hơn lợi. Chỉ hướng người dùng tới app gốc
 * của hãng (nơi CHẮC CHẮN có cách xem/chia sẻ URL) + hỗ trợ 1-click mở đúng app đó trên CH Play.
 */
@Composable
private fun VendorGuideDialog(vendor: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (vendor != null) "Hướng dẫn lấy URL cho camera $vendor" else "Hướng dẫn lấy URL xem camera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "App chưa tự lấy được URL xem trực tiếp cho camera này. Bạn cần lấy URL đó từ " +
                        "app gốc của hãng camera (app đã dùng lúc lắp camera ban đầu):",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("1️⃣  Mở app gốc của hãng camera trên điện thoại.")
                Text("2️⃣  Vào phần Cài đặt của camera đó → tìm mục \"Chia sẻ\" / \"Share\" / \"RTSP\" / \"Xem qua trình duyệt khác\".")
                Text("3️⃣  Sao chép URL hoặc đường link hiển thị ở mục đó.")
                Text("4️⃣  Quay lại đây, dán vào ô \"URL ảnh chụp\" rồi bấm \"Kiểm tra kết nối\".")
                if (vendor != null) {
                    Text(
                        "⚠️ Một số camera $vendor chỉ xem được qua app/cloud riêng của hãng, không có " +
                            "URL truy cập trực tiếp trong mạng nhà. Nếu không tìm thấy mục URL/RTSP " +
                            "trong app hãng, camera này có thể chưa hỗ trợ thêm trực tiếp vào đây — " +
                            "vẫn xem bình thường qua app gốc của hãng.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (vendor != null) {
                TextButton(onClick = {
                    val query = android.net.Uri.encode(vendor)
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://search?q=$query&c=apps")
                            )
                        )
                    } catch (e: Exception) {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://play.google.com/store/search?q=$query&c=apps")
                            )
                        )
                    }
                }) { Text("Mở CH Play tìm app $vendor") }
            } else {
                TextButton(onClick = onDismiss) { Text("Đã hiểu") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

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

            // ✅ MỚI: camera đã lưu nhưng chưa có URL nào dùng được (cả URL LAN lẫn URL dự phòng
            // đều trống) — trước đây chỉ hiện chung chung "🔴 Mất kết nối" giống hệt trường hợp
            // camera thật sự offline, người dùng không phân biệt được là do thiếu cấu hình hay do
            // camera hỏng/mất điện. Badge riêng + nút "Cấu hình ngay" đưa thẳng vào đúng màn sửa.
            val needsSetup = camera.snapshoturl.orEmpty().isBlank() && camera.snapshotUrlRemote.orEmpty().isBlank()
            if (needsSetup) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚠️ Cần cấu hình thêm — chưa có URL xem camera",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (isAdmin) {
                        TextButton(onClick = onEdit) { Text("Cấu hình ngay") }
                    }
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
    // ✅ MỚI: XAddr thật (ONVIF device service URL) tìm được qua OnvifWsDiscoveryProbe — nạp sẵn
    // nếu đang sửa camera đã có, ghi lại khi chọn 1 kết quả dò tìm ONVIF (xem onCameraSelected).
    // Trước đây giá trị này chỉ được nhét vào landInfo dạng ghi chú text rồi mất, không dùng lại
    // được cho probeCapabilities() — xem ghi chú field onvifDeviceServiceUrl trong Entity.kt.
    var onvifDeviceServiceUrl by remember { mutableStateOf(camera?.onvifDeviceServiceUrl ?: "") }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    // ✅ MỚI: danh tính camera từ quét QR (CameraQrDiscovery) — CHỈ là state chờ đối chiếu, KHÔNG
    // tự ghi vào landInfo lúc quét xong. Đi vào landInfo ở 1 trong 2 đường: (a) khớp với 1 kết quả
    // LAN discovery lúc người dùng CHỌN (xem onCameraSelected bên dưới), hoặc (b) người dùng chủ
    // động bấm "Thêm vào ghi chú" nếu không chạy/không tìm được LAN discovery khớp — không có
    // đường nào tự động viết mà không qua 1 hành động rõ ràng của người dùng.
    var qrIdentity by remember { mutableStateOf<CameraQrIdentity?>(null) }
    var qrScanning by remember { mutableStateOf(false) }
    var landInfo by remember { mutableStateOf(camera?.landinfo ?: "") }
    // ✅ SỬA: camera mới lấy prompt/từ khoá mặc định từ AppConfigDefaults (đã nạp qua `defaults`)
    // thay vì gõ cứng "cảnh báo"/"bình thường" trực tiếp trong UI.
    var aiPrompt by remember { mutableStateOf(camera?.aiPrompt ?: defaults.aiPrompt) }
    var aiPositiveKeywords by remember { mutableStateOf(camera?.aiPositiveKeywords ?: defaults.aiPositiveKeywords) }
    var aiNegativeKeywords by remember { mutableStateOf(camera?.aiNegativeKeywords ?: defaults.aiNegativeKeywords) }

    val context = LocalContext.current
    var locationError by remember { mutableStateOf<String?>(null) }
    var gpsLoading by remember { mutableStateOf(false) }

    // ✅ MỚI: state cho nút "Kiểm tra kết nối" + dialog hướng dẫn theo hãng.
    val coroutineScope = rememberCoroutineScope()
    var urlTestState by remember { mutableStateOf<UrlTestState>(UrlTestState.Idle) }
    var showVendorGuide by remember { mutableStateOf(false) }
    // Cập nhật khi QR quét ra vendor, hoặc khi chọn 1 kết quả dò tìm LAN có manufacturer — dùng để
    // hiển thị đúng tên hãng trong VendorGuideDialog thay vì hướng dẫn chung chung nếu đã biết.
    var detectedVendor by remember { mutableStateOf<String?>(null) }

    // ✅ MỚI: quét QR — bọc CameraQrDiscovery qua ViewModel vì CameraDialog là Composable thuần,
    // không tự @Inject được (xem CameraQrScanViewModel.kt).
    val qrScanViewModel: CameraQrScanViewModel = hiltViewModel()
    val qrScanResult by qrScanViewModel.result.collectAsState()
    LaunchedEffect(qrScanResult) {
        when (val r = qrScanResult) {
            is CameraQrScanResult.Success -> {
                qrIdentity = r.identity
                detectedVendor = r.identity.vendor ?: detectedVendor
                qrScanViewModel.consume()
                // ✅ SỬA (góp ý user: "quét QR xong nhưng có tự động được gì đâu" — đúng, trước đây
                // quét xong chỉ hiện 1 dòng text nhắc người dùng TỰ bấm nút "Tự động dò tìm camera
                // trong mạng" riêng, thêm 1 bước thao tác thừa và dễ bị bỏ qua vì dòng nhắc nằm
                // dưới, không nổi bật). Quét QR xong → MỞ THẲNG dialog dò tìm mạng luôn, đúng đích
                // quét QR sinh ra để làm (đối chiếu deviceId với kết quả LAN discovery) — không có
                // lý do gì bắt người dùng phải tự tìm nút bấm tiếp theo. Vẫn giữ nguyên nguyên tắc
                // gốc "không tự động ghi form": dialog mở ra chỉ ĐỀ XUẤT kết quả khớp (đánh dấu 🟢
                // qua qrIdentity truyền vào CameraDiscoveryDialog bên dưới), người dùng vẫn phải tự
                // bấm CHỌN 1 kết quả mới thật sự điền vào form.
                showDiscoveryDialog = true
            }
            is CameraQrScanResult.Error -> {
                locationError = "Quét QR lỗi: ${r.message}"
                qrScanViewModel.consume()
            }
            is CameraQrScanResult.Cancelled -> qrScanViewModel.consume()
            null -> {}
        }
        qrScanning = false
    }

    // ✅ MỚI: dialog tự động dò tìm camera LAN — tách riêng khỏi AlertDialog chính bên dưới để
    // tránh lồng dialog-trong-dialog. Khi người dùng chọn 1 kết quả (đã qua bước xác minh trong
    // CameraVerificationDialog), tự điền lại state ở trên rồi đóng dialog dò tìm.
    if (showDiscoveryDialog) {
        CameraDiscoveryDialog(
            qrIdentity = qrIdentity, // ✅ MỚI: cho phép CameraDiscoveryDialog đánh dấu kết quả khớp QR
            onCameraSelected = { discovered: DiscoveredCamera ->
                // ✅ SỬA KIẾN TRÚC (theo góp ý sau case V380 — bỏ cách cũ bịa "http://<IP>" làm
                // URL giả). discovered.verifiedSnapshotUrl CHỈ khác null khi:
                // (a) HttpSnapshotProbe đã xác nhận ảnh JPEG thật, hoặc
                // (b) CameraVerificationDialog vừa chạy CameraCapabilityProber và tìm được RTSP —
                //     trong trường hợp đó dialog xác minh đã tự cập nhật verifiedSnapshotUrl
                //     (xem CameraVerificationDialog.onConfirm trong CameraDiscoveryDialog.kt).
                // Nếu vẫn null (người dùng chọn "Thêm camera (chưa xác minh)" khi không dò được
                // gì, vd V380 chưa hỗ trợ RTSP/ONVIF) — để trống, KHÔNG bịa giá trị, người dùng tự
                // nhập URL/RTSP thật sau khi tra cứu thêm.
                snapshotUrl = discovered.verifiedSnapshotUrl ?: ""
                snapshotUsername = discovered.credentials?.username ?: ""
                snapshotPassword = discovered.credentials?.password ?: ""
                urlTestState = UrlTestState.Idle
                detectedVendor = discovered.manufacturer ?: qrIdentity?.vendor ?: detectedVendor
                // ✅ MỚI: giữ lại XAddr thật khi kết quả chọn là ONVIF — trước đây chỉ có bản ghi
                // text trong landInfo (xem note bên dưới), giờ lưu thêm vào field riêng để
                // probeCapabilities() dùng thẳng thay vì suy đoán port 80 từ IP.
                if (discovered.protocol == "onvif" && !discovered.deviceId.isNullOrBlank()) {
                    onvifDeviceServiceUrl = discovered.deviceId
                }
                // Luôn ghi lại thông tin phần cứng đã biết vào landInfo (không có field riêng cho
                // việc này trong form) — kể cả khi đã verify, để giữ lại protocol/deviceId gốc.
                val label = when (discovered.protocol) {
                    "onvif" -> "ONVIF (XAddr: ${discovered.deviceId ?: "?"})"
                    "vendor_udp" -> "V380 UDP Discovery (deviceId: ${discovered.deviceId ?: "?"})"
                    else -> discovered.protocol
                }
                val verifiedNote = if (discovered.verifiedSnapshotUrl == null) " — CHƯA xác minh URL, cần tự nhập" else ""
                // ✅ MỚI: ghi thêm 1 dòng riêng nếu deviceId của kết quả CHỌN khớp qrIdentity —
                // CHỈ khi thực sự khớp (contains ignoreCase, cùng tiêu chí CameraDiscoveryDialog
                // dùng để đánh dấu 🟢), không suy diễn khi thiếu 1 trong 2 deviceId.
                val camId = discovered.deviceId?.trim()
                val qrId = qrIdentity?.deviceId?.trim()
                val qrMatchNote = if (!camId.isNullOrBlank() && !qrId.isNullOrBlank() &&
                    (camId.contains(qrId, ignoreCase = true) || qrId.contains(camId, ignoreCase = true))
                ) "\nĐối chiếu QR: khớp deviceId ($qrId) — đây là camera vừa quét QR." else ""
                val note = "Tìm thấy qua $label — IP ${discovered.ip}:${discovered.port}$verifiedNote.$qrMatchNote"
                landInfo = if (landInfo.isBlank()) note else "$landInfo\n$note"
                showDiscoveryDialog = false
            },
            onDismiss = { showDiscoveryDialog = false }
        )
    }

    if (showVendorGuide) {
        VendorGuideDialog(vendor = detectedVendor, onDismiss = { showVendorGuide = false })
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

                OutlinedTextField(
                    value = snapshotUrl,
                    onValueChange = { snapshotUrl = it; urlTestState = UrlTestState.Idle },
                    label = { Text("URL ảnh chụp") },
                    modifier = Modifier.fillMaxWidth()
                )

                // ✅ MỚI: kiểm tra NGAY tại chỗ URL vừa nhập/dò được có dùng được thật không, thay
                // vì phải Lưu xong ra Dashboard mới biết camera đen — và nút "Hướng dẫn" luôn có
                // sẵn cho người không biết lấy URL ở đâu, không cần đợi dò tìm/quét QR thất bại.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                urlTestState = UrlTestState.Testing
                                urlTestState = testSnapshotUrl(snapshotUrl.trim(), snapshotUsername.trim(), snapshotPassword)
                            }
                        },
                        enabled = snapshotUrl.isNotBlank() && urlTestState !is UrlTestState.Testing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (urlTestState is UrlTestState.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (urlTestState is UrlTestState.Testing) "Đang kiểm tra..." else "🔎 Kiểm tra kết nối")
                    }
                    TextButton(onClick = { showVendorGuide = true }) { Text("📖 Hướng dẫn") }
                }
                when (val ts = urlTestState) {
                    is UrlTestState.Success -> Text(ts.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    is UrlTestState.Failure -> Text(ts.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }

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

                // ✅ MỚI: quét QR trên thân camera để lấy deviceId — CHỈ tạo qrIdentity (state chờ
                // đối chiếu), KHÔNG tự ghi gì vào form. Nối vào "Tự động dò tìm camera trong mạng"
                // ở trên (đối chiếu deviceId, xem onCameraSelected) là đường được khuyến khích;
                // dòng gợi ý + nút "Thêm vào ghi chú" bên dưới chỉ dành cho trường hợp không chạy/
                // không tìm được kết quả LAN khớp.
                OutlinedButton(
                    onClick = {
                        val activity = context as? android.app.Activity
                        if (activity == null) {
                            locationError = "Không mở được camera quét QR"
                        } else {
                            qrScanning = true
                            qrScanViewModel.scan(activity)
                        }
                    },
                    enabled = !qrScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (qrScanning) "Đang quét..." else "Quét mã QR trên camera")
                }

                qrIdentity?.let { id ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "📷 Đã quét QR — deviceId: ${id.deviceId ?: "?"}" +
                                    (id.vendor?.let { v -> " ($v)" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                // ✅ SỬA: dialog dò tìm giờ đã tự mở ngay sau khi quét (xem
                                // LaunchedEffect ở trên) — dòng nhắc "bấm nút..." không còn đúng bối
                                // cảnh. Đổi thành hướng dẫn cho trường hợp còn lại: dialog không tìm
                                // thấy kết quả khớp, hoặc người dùng đã đóng dialog mà chưa chọn gì.
                                "Nếu không tìm được camera này trên mạng, có thể bấm \"Tự động dò " +
                                    "tìm\" lại hoặc thêm thẳng deviceId vào ghi chú bên dưới.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            // Chủ động (người dùng bấm), không tự động — đúng góp ý: QR không tự
                            // ý ghi vào landInfo, chỉ ghi khi có hành động rõ ràng.
                            val note = "Quét QR: deviceId=${id.deviceId ?: "?"}" +
                                (id.vendor?.let { v -> " — hãng: $v" } ?: "") +
                                " — CHƯA đối chiếu với LAN discovery."
                            landInfo = if (landInfo.isBlank()) note else "$landInfo\n$note"
                            qrIdentity = null
                        }) { Text("Thêm vào ghi chú") }
                    }
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
                // ✅ SỬA: trước đây bắt buộc snapshotUrl.isNotBlank() mới cho lưu — nếu người
                // dùng chỉ điền "URL dự phòng" (snapshotUrlRemote) và để trống URL LAN chính
                // (trường hợp hợp lệ, ví dụ camera chỉ truy cập được qua cloud), nút "Lưu" âm
                // thầm không làm gì, không báo lỗi. Giờ chỉ cần có URL LAN HOẶC URL dự phòng.
                val hasAnyUrl = snapshotUrl.isNotBlank() || snapshotUrlRemote.isNotBlank()
                if (id.isNotBlank() && hasAnyUrl) {
                    onSave(mapOf(
                        "id" to id.trim(),
                        "customerId" to customerId.trim(),
                        "customername" to customerName.trim(),
                        "customeremail" to customerEmail.trim(),
                        "snapshoturl" to snapshotUrl.trim(),
                        "snapshotUsername" to snapshotUsername.trim(),
                        "snapshotPassword" to snapshotPassword.trim(),
                        "snapshotUrlRemote" to snapshotUrlRemote.trim(),
                        "onvifDeviceServiceUrl" to onvifDeviceServiceUrl.trim(),
                        "landinfo" to landInfo.trim(),
                        "aiPrompt" to aiPrompt.trim(),
                        "aiPositiveKeywords" to aiPositiveKeywords.trim(),
                        "aiNegativeKeywords" to aiNegativeKeywords.trim()
                    ))
                } else if (!hasAnyUrl) {
                    // ✅ SỬA: trước đây bấm "Lưu" mà thiếu URL thì im lặng không làm gì, không báo
                    // lỗi — người dùng tưởng đã lưu xong. Giờ báo rõ + trỏ thẳng tới nút hướng dẫn.
                    locationError = "Cần nhập URL ảnh chụp hoặc URL dự phòng trước khi lưu — bấm " +
                        "\"📖 Hướng dẫn\" phía trên nếu chưa biết lấy URL ở đâu."
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}