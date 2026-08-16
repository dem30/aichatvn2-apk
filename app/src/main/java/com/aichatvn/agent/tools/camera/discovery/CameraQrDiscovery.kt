package com.aichatvn.agent.tools.camera.discovery

import android.app.Activity
import com.aichatvn.agent.utils.Logger
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Quét QR trên thân camera để lấy danh tính (Device ID/UID) — BỔ SUNG cho LAN discovery
 * (OnvifWsDiscoveryProbe/VendorUdpDiscoveryProbe/HttpSnapshotProbe), KHÔNG thay thế. QR cho biết
 * "đây là camera nào" (danh tính, đọc trực tiếp từ tem/QR trên máy — chắc chắn đúng camera người
 * dùng đang cầm), LAN discovery cho biết "camera đó đang ở IP nào trong mạng nhà" (cần camera đã
 * lên Wi-Fi mới tìm được, và không phân biệt được nếu nhà có 2 camera cùng hãng). Dùng chung
 * deviceId để đối chiếu chéo hai nguồn — xem hướng dẫn nối vào CameraComponents.kt bên dưới.
 *
 * ⚠️ THIẾT KẾ PHỔ QUÁT theo chủ ý: class này KHÔNG implement CameraProbe (không trả về
 * List<DiscoveredCamera> qua 1 lượt quét mạng) — nó là 1 hành động một-lần do người dùng chủ động
 * bấm (quét 1 mã QR cụ thể), không phải background probe. CameraQrIdentity không gắn cứng field
 * nào riêng cho V380 — vendor nào có QR chứa UID/serial/IP/URL đều dùng chung shape này, chỉ khác
 * ở logic trong parse().
 *
 * ⚠️ GIỚI HẠN ĐÃ BIẾT (đọc trước khi dùng deviceId để đối chiếu):
 * - parse() là suy luận HEURISTIC trên nhiều định dạng phổ biến (JSON phẳng, "key=value" nối bởi
 *   dấu phân cách, hoặc chuỗi UID trần) — CHƯA xác nhận 1-1 với định dạng QR thật in trên tem
 *   V380 (khác với VendorUdpDiscoveryProbe, nơi payload request đã copy nguyên byte từ source đã
 *   reverse-engineer). Nếu không khớp format nào đã biết, deviceId/vendor trả về null thay vì đoán
 *   liều — rawValue LUÔN được giữ nguyên vẹn để hiển thị cho người dùng tự đối chiếu bằng mắt.
 * - Vì vậy: đối chiếu deviceId giữa QR và VendorUdpDiscoveryProbe/OnvifWsDiscoveryProbe chỉ nên
 *   dùng để GỢI Ý ("có thể là cùng 1 camera"), KHÔNG dùng để tự động gộp/xoá kết quả mà không cho
 *   người dùng xác nhận — 2 định dạng deviceId có thể biểu diễn khác nhau dù cùng 1 giá trị gốc
 *   (vd hoa/thường, có/không tiền tố) khiến so sánh chuỗi trực tiếp bị âm tính giả.
 * - Cần thiết bị có Google Play Services (project đã giả định điều này cho
 *   play-services-mlkit-image-labeling/face-detection và play-services-location) — nếu thiếu,
 *   startScan() trả lỗi qua addOnFailureListener, xử lý bằng CameraQrScanResult.Error, không crash.
 */

data class CameraQrIdentity(
    val rawValue: String,
    val deviceId: String?,
    val vendor: String?,
)

sealed interface CameraQrScanResult {
    data class Success(val identity: CameraQrIdentity) : CameraQrScanResult
    data object Cancelled : CameraQrScanResult
    data class Error(val message: String) : CameraQrScanResult
}

@Singleton
class CameraQrDiscovery @Inject constructor(
    private val logger: Logger,
) {
    companion object {
        private const val TAG = "CameraQrDiscovery"
    }

    /**
     * Mở UI quét QR toàn màn hình của Google (chạy trong tiến trình Play Services — app không tự
     * xin quyền CAMERA cho việc này). Hàm suspend, gọi từ viewModelScope/LaunchedEffect.
     *
     * @param activity BẮT BUỘC là Activity thật (không dùng ApplicationContext) — API của Google
     * Code Scanner yêu cầu Activity để hiển thị UI quét đè lên, đúng tài liệu chính thức.
     */
    suspend fun scan(activity: Activity): CameraQrScanResult {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)

        return suspendCancellableCoroutine { cont ->
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = (barcode.rawValue ?: barcode.displayValue)?.trim()
                    if (raw.isNullOrBlank()) {
                        logger.w(TAG, "Quét được QR nhưng nội dung rỗng")
                        cont.resume(CameraQrScanResult.Error("Mã QR không có nội dung đọc được"))
                    } else {
                        logger.i(TAG, "✅ Quét QR thành công, raw=$raw")
                        cont.resume(CameraQrScanResult.Success(parse(raw)))
                    }
                }
                .addOnCanceledListener {
                    cont.resume(CameraQrScanResult.Cancelled)
                }
                .addOnFailureListener { e ->
                    logger.w(TAG, "Quét QR thất bại: ${e.message}")
                    cont.resume(
                        CameraQrScanResult.Error(e.message ?: "Không mở được camera quét QR")
                    )
                }
        }
    }

    /**
     * Suy luận deviceId/vendor từ nội dung QR — xem cảnh báo giới hạn ở đầu file. Thử lần lượt
     * vài định dạng phổ biến, dừng ở định dạng đầu tiên khớp; không khớp gì thì trả null cho cả
     * hai field, KHÔNG bịa.
     */
    fun parse(rawValue: String): CameraQrIdentity {
        parseAsFlatJson(rawValue)?.let { return it }
        parseAsKeyValuePairs(rawValue)?.let { return it }
        parseAsBareToken(rawValue)?.let { return it }

        logger.d(TAG, "Không khớp định dạng QR nào đã biết, giữ nguyên rawValue: $rawValue")
        return CameraQrIdentity(rawValue = rawValue, deviceId = null, vendor = guessVendor(rawValue))
    }

    /**
     * Thử parse JSON phẳng kiểu {"uid":"...", "vendor":"..."} — nhiều app camera TQ giá rẻ (bao
     * gồm 1 số bản V380 theo tài liệu tham khảo) encode QR dạng JSON đơn giản không lồng nhau.
     * Regex thay vì kéo thêm JSON parser dependency chỉ để đọc 1-2 giá trị — cùng tinh thần với
     * OnvifWsDiscoveryProbe.extractXAddr()/VendorUdpDiscoveryProbe.parseResponse().
     */
    private fun parseAsFlatJson(rawValue: String): CameraQrIdentity? {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        val deviceId = extractJsonField(trimmed, "uid")
            ?: extractJsonField(trimmed, "deviceId")
            ?: extractJsonField(trimmed, "device_id")
            ?: extractJsonField(trimmed, "sn")
            ?: extractJsonField(trimmed, "serial")
            ?: extractJsonField(trimmed, "id")
        val vendor = extractJsonField(trimmed, "vendor")
            ?: extractJsonField(trimmed, "brand")
            ?: guessVendor(trimmed)

        if (deviceId == null) return null
        return CameraQrIdentity(rawValue = rawValue, deviceId = deviceId, vendor = vendor)
    }

    private fun extractJsonField(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(json)
            ?: Regex("\"$key\"\\s*:\\s*([0-9]+)", RegexOption.IGNORE_CASE).find(json)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Thử parse dạng "key=value" nối bởi &, ;, hoặc khoảng trắng — ví dụ "uid=ABC123&pwd=xxx"
     * (kiểu query-string, thường gặp ở QR camera IP giá rẻ dùng làm deep-link cho app riêng của
     * hãng) hoặc "V380:ABC123" (prefix hãng + dấu hai chấm + UID).
     */
    private fun parseAsKeyValuePairs(rawValue: String): CameraQrIdentity? {
        val trimmed = rawValue.trim()

        // Dạng "VENDOR:UID" hoặc "VENDOR-UID" — prefix hãng ngắn gọn trước dấu phân cách.
        val prefixMatch = Regex("^([A-Za-z0-9]{2,10})[:\\-]([A-Za-z0-9]{4,32})$").find(trimmed)
        if (prefixMatch != null) {
            val (prefix, id) = prefixMatch.destructured
            return CameraQrIdentity(rawValue = rawValue, deviceId = id, vendor = prefix)
        }

        // Dạng query-string "key=value" nối bởi & hoặc ;
        if (trimmed.contains("=") && (trimmed.contains("&") || trimmed.contains(";"))) {
            val pairs = trimmed.split("&", ";")
                .mapNotNull { part ->
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
                }.toMap()

            val deviceId = pairs["uid"] ?: pairs["deviceid"] ?: pairs["sn"] ?: pairs["id"]
            if (deviceId != null && deviceId.isNotBlank()) {
                return CameraQrIdentity(
                    rawValue = rawValue, deviceId = deviceId, vendor = guessVendor(rawValue)
                )
            }
        }

        return null
    }

    /**
     * QR chỉ chứa 1 chuỗi alphanumeric trần (không JSON, không phân cách) — coi thẳng là
     * deviceId nếu độ dài hợp lý (4-32 ký tự, đúng khoảng UID/serial thường gặp). Ngắn/dài hơn
     * nhiều khả năng là URL hay text khác, không phải UID — để null cho an toàn.
     */
    private fun parseAsBareToken(rawValue: String): CameraQrIdentity? {
        val trimmed = rawValue.trim()
        if (!Regex("^[A-Za-z0-9_-]{4,32}$").matches(trimmed)) return null
        return CameraQrIdentity(rawValue = rawValue, deviceId = trimmed, vendor = guessVendor(trimmed))
    }

    /** Nhận diện hãng qua từ khoá xuất hiện trong nội dung QR — chỉ là gợi ý hiển thị, không ảnh hưởng logic đối chiếu deviceId. */
    private fun guessVendor(text: String): String? = when {
        text.contains("v380", ignoreCase = true) -> "V380"
        text.contains("hikvision", ignoreCase = true) -> "Hikvision"
        text.contains("dahua", ignoreCase = true) -> "Dahua"
        text.contains("tapo", ignoreCase = true) || text.contains("tplink", ignoreCase = true) -> "TP-Link Tapo"
        text.contains("onvif", ignoreCase = true) -> "ONVIF"
        else -> null
    }
}