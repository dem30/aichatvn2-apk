package com.aichatvn.agent.tools.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aichatvn.agent.utils.Logger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Kết quả phân tích cục bộ (on-device, không tốn quota Groq).
 *
 * ⚠️ CHỦ Ý: đây KHÔNG phải thay thế cho Groq Vision — chỉ là fallback local nhẹ, độ chính xác
 * và độ chi tiết THẤP HƠN NHIỀU so với Groq. Dùng để làm giàu "summary"/EventLogEntity phục vụ
 * search local (xem AgentKernel.runLocalQAEventAnalysis()), KHÔNG dùng để đưa ra quyết định
 * cảnh báo an ninh quan trọng — luồng đó vẫn phải qua Groq khi smartMode bật.
 */
data class LocalVisionResult(
    val labels: List<String>,       // vd ["person", "vehicle"] — đã dịch sang tiếng Việt qua mapLabelToVietnamese()
    val hasFace: Boolean,
    val dominantColorName: String?,  // vd "đỏ", "đen" — null nếu không xác định được (ảnh quá tối/đơn sắc)
    val rawMlKitLabels: List<Pair<String, Float>> = emptyList()  // ✅ NEW: debug field — raw labels từ ML Kit trước khi filter
)

/**
 * ✅ MỚI: Local Vision fallback dùng ML Kit (Image Labeling + Face Detection), chạy hoàn toàn
 * on-device, KHÔNG gọi mạng, KHÔNG tốn quota Groq.
 *
 * Dùng @Inject constructor (giống ImageHashTool/GroqClientTool) — Hilt tự resolve, KHÔNG cần
 * khai báo @Provides riêng trong AppModule.kt vì Context đã có binding sẵn qua @ApplicationContext.
 *
 * Khởi tạo ImageLabeler/FaceDetector MỘT LẦN (lazy, cấp Singleton) — không tạo mới mỗi lần
 * scan, tránh leak native resource (rủi ro #5 đã nêu).
 * 
 * ✅ SỬA: confidence threshold từ 0.6 → 0.35 (ML Kit default) để nhạy cảm hơn, tránh bỏ sót
 * vật thể thật. Khi smartMode TẮT, dùng local nên không sao nếu có ít false positive — mục đích
 * chỉ là làm giàu event log/summary cho search local, KHÔNG để đưa ra cảnh báo an ninh quan trọng.
 */
@Singleton
class LocalVisionTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {
    // ✅ SỬA: Giảm threshold từ 0.6 → 0.35 (ML Kit default) để nhạy cảm hơn.
    // Giải thích: threshold 0.6 quá cao, loại bỏ nhiều vật thể hợp lệ. Khi dùng local fallback
    // (smartMode TẮT), mục đích chỉ là enrichment event log cho search, KHÔNG quyết định cảnh báo
    // quan trọng, nên lower threshold được.
    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.35f)  // ✅ SỬA từ 0.6
                .build()
        )
    }

    // FAST mode: chỉ cần biết CÓ mặt người hay không (không cần landmark/classification chi
    // tiết) — nhẹ hơn ACCURATE mode đáng kể, phù hợp chạy trên mỗi lần quét camera.
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    /**
     * Phân tích 1 ảnh — luôn chạy trên Dispatchers.Default (CPU-bound), có timeout để không
     * bao giờ treo luồng camera vô thời hạn nếu ML Kit gặp sự cố lạ.
     *
     * ⚠️ KHÔNG throw ra ngoài — mọi lỗi được bắt và trả về null, để nơi gọi (CameraSkill) tự
     * quyết định fallback, KHÔNG được để lỗi ML Kit làm gián đoạn luồng ghi nhận sự kiện chính
     * (rủi ro #4 đã nêu).
     */
    suspend fun analyze(imageBytes: ByteArray): LocalVisionResult? = withContext(Dispatchers.Default) {
        try {
            withTimeout(8_000L) {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withTimeout null

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val labelsDeferred = runLabeling(inputImage)
                val facesDeferred = runFaceDetection(inputImage)
                val dominantColor = calculateDominantColorName(bitmap)

                LocalVisionResult(
                    labels = labelsDeferred.first,
                    hasFace = facesDeferred,
                    dominantColorName = dominantColor,
                    rawMlKitLabels = labelsDeferred.second  // ✅ NEW: keep raw labels for debugging
                )
            }
        } catch (e: TimeoutCancellationException) {
            logger.w("LocalVisionTool", "⏱️ ML Kit timeout (8s) — bỏ qua phân tích local lần này")
            null
        } catch (e: CancellationException) {
            // ✅ CancellationException KHÔNG do timeout (vd coroutine cha bị hủy) — phải ném lại,
            // không được nuốt như lỗi ML Kit thường (đặt SAU catch TimeoutCancellationException
            // vì đó là subtype cụ thể hơn, cần xử lý riêng ở trên).
            throw e
        } catch (e: Exception) {
            // Bắt MỌI exception (bao gồm cả lỗi thiếu Google Play Services, model chưa sẵn
            // sàng, OOM khi decode bitmap...) — không để crash lan lên CameraSkill.
            logger.w("LocalVisionTool", "⚠️ ML Kit local analysis lỗi: ${e.message}")
            null
        }
    }

    // ✅ SỬA: Trả về Pair<filtered labels, raw labels> để debug & logging có đủ thông tin
    private suspend fun runLabeling(inputImage: InputImage): Pair<List<String>, List<Pair<String, Float>>> =
        suspendCancellableCoroutine { cont ->
            imageLabeler.process(inputImage)
                .addOnSuccessListener { labels ->
                    val rawLabels = labels.map { it.text to it.confidence }  // ✅ NEW: giữ lại raw
                    val mapped = labels
                        .sortedByDescending { it.confidence }
                        .take(10)  // ✅ SỬA từ 5 → 10 để lấy thêm labels (threshold đã giảm nên có thể nhiều hơn)
                        .mapNotNull { mapLabelToVietnamese(it.text) }
                        .distinct()
                    
                    // ✅ NEW: Debug logging — show ngay khi labels trở nên trống sau filter
                    if (rawLabels.isNotEmpty() && mapped.isEmpty()) {
                        logger.d("LocalVisionTool", "⚠️ ML Kit trả labels nhưng tất cả bị filter: raw=$rawLabels")
                    }
                    
                    if (cont.isActive) cont.resume(mapped to rawLabels)
                }
                .addOnFailureListener { e ->
                    logger.e("LocalVisionTool", "ML Kit labeling error: ${e.message}", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private suspend fun runFaceDetection(inputImage: InputImage): Boolean =
        suspendCancellableCoroutine { cont ->
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces.isNotEmpty())
                }
                .addOnFailureListener { e ->
                    logger.e("LocalVisionTool", "ML Kit face detection error: ${e.message}", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    // ML Kit trả nhãn tiếng Anh chung chung (vd "Person", "Vehicle", "Plant", "Furniture") —
    // map sang tiếng Việt cho khớp văn phong summary hiện có trong EventLogEntity, đồng thời
    // lọc bớt nhãn quá chung chung/nhiễu (vd "Font", "Material") không có giá trị cho search.
    // 
    // ✅ SỬA: Thêm nhiều mapping mới (clothing, shoes, furniture chi tiết hơn) để catch thêm
    // label từ ML Kit — trước đây quá nhiều label bị null → return nên cuối cùng trả empty list.
    private fun mapLabelToVietnamese(mlKitLabel: String): String? {
        val normalized = mlKitLabel.trim().lowercase()
        return when {
            normalized.contains("person") || normalized.contains("human") || normalized.contains("people") -> "người"
            normalized.contains("vehicle") || normalized.contains("car") || normalized.contains("automobile") -> "xe ô tô"
            normalized.contains("motorcycle") || normalized.contains("motorbike") || normalized.contains("bicycle") || normalized.contains("bike") -> "xe máy/xe đạp"
            normalized.contains("dog") || normalized.contains("puppy") -> "chó"
            normalized.contains("cat") || normalized.contains("kitten") -> "mèo"
            normalized.contains("animal") || normalized.contains("wildlife") -> "động vật"
            normalized.contains("plant") || normalized.contains("tree") || normalized.contains("flora") -> "cây cối"
            normalized.contains("furniture") || normalized.contains("chair") || normalized.contains("table") || normalized.contains("sofa") -> "đồ nội thất"
            normalized.contains("clothing") || normalized.contains("apparel") || normalized.contains("clothes") || normalized.contains("dress") -> "quần áo"
            normalized.contains("shoe") || normalized.contains("footwear") -> "giày"
            normalized.contains("bag") || normalized.contains("luggage") || normalized.contains("backpack") || normalized.contains("handbag") -> "túi/hành lý"
            normalized.contains("building") || normalized.contains("house") || normalized.contains("room") || normalized.contains("indoor") -> "tòa nhà/phòng"
            normalized.contains("outdoor") || normalized.contains("street") || normalized.contains("road") -> "ngoài trời/đường"
            normalized.contains("sky") || normalized.contains("cloud") || normalized.contains("weather") -> "bầu trời"
            normalized.contains("water") || normalized.contains("ocean") || normalized.contains("sea") -> "nước"
            normalized.contains("food") || normalized.contains("fruit") || normalized.contains("vegetable") -> "thực phẩm"
            else -> null // bỏ qua nhãn không map được — tránh nhồi rác vào summary
        }
    }

    // Không dùng AI cho màu — chỉ downsample ảnh + lấy màu trung bình vùng trung tâm khung
    // hình (nơi vật thể chính thường nằm), rẻ (vài ms), đủ dùng để tag thô "đỏ/đen/trắng...".
    private fun calculateDominantColorName(bitmap: Bitmap): String? {
        return try {
            val sampleSize = 16
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)

            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var count = 0

            // Chỉ lấy vùng trung tâm (60% giữa) — tránh nền/viền ảnh chi phối màu trung bình.
            val margin = (sampleSize * 0.2).toInt()
            for (x in margin until sampleSize - margin) {
                for (y in margin until sampleSize - margin) {
                    val pixel = scaled.getPixel(x, y)
                    totalR += (pixel shr 16) and 0xFF
                    totalG += (pixel shr 8) and 0xFF
                    totalB += pixel and 0xFF
                    count++
                }
            }
            if (scaled != bitmap) scaled.recycle()
            if (count == 0) return null

            val avgR = (totalR / count).toInt()
            val avgG = (totalG / count).toInt()
            val avgB = (totalB / count).toInt()

            colorNameFromRgb(avgR, avgG, avgB)
        } catch (e: Exception) {
            null
        }
    }

    private fun colorNameFromRgb(r: Int, g: Int, b: Int): String? {
        val max = maxOf(r, g, b)
        // Đặt tên "minC" thay vì "min" để tránh trùng với hàm kotlin.math.min đã import.
        val minC = min(r, min(g, b))
        val brightness = (r + g + b) / 3

        // Ảnh quá tối (ban đêm/IR) hoặc quá sáng (cháy sáng) — không đáng tin để gán màu.
        if (brightness < 25) return null
        if (brightness > 235 && (max - minC) < 20) return "trắng"
        // Gần như xám đều (chênh lệch kênh màu thấp) — không có màu chủ đạo rõ ràng.
        if (max - minC < 20) return if (brightness < 100) "đen/xám đậm" else "xám/trắng"

        return when {
            r > g && r > b -> "đỏ"
            g > r && g > b -> "xanh lá"
            b > r && b > g -> "xanh dương"
            r > b && g > b -> "vàng"
            else -> null
        }
    }
}
