package com.aichatvn.agent.tools.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sqrt

@Singleton
class ImageHashTool @Inject constructor() {

    companion object {
        private const val HASH_SIZE = 32
        private const val DCT_SIZE = 8
    }

    // Tối ưu hóa lớn: Di chuyển toàn bộ tiến trình phân tích ảnh nặng ra khỏi Luồng chính bằng Dispatchers.Default
    suspend fun calculatePhash(imageBytes: ByteArray): String = withContext(Dispatchers.Default) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) 
                ?: return@withContext "" // Chống lỗi NPE nếu mảng byte ảnh hỏng
                
            val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
            if (bitmap != scaled) bitmap.recycle()

            val pixels = Array(HASH_SIZE) { y ->
                DoubleArray(HASH_SIZE) { x ->
                    val pixel = scaled.getPixel(x, y)
                    0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                }
            }
            scaled.recycle()

            val dct = applyDCT(pixels)
            val subDct = DoubleArray(DCT_SIZE * DCT_SIZE) { i ->
                dct[i / DCT_SIZE][i % DCT_SIZE]
            }

            val mean = subDct.drop(1).average()

            buildString {
                subDct.forEach { v -> append(if (v > mean) '1' else '0') }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun applyDCT(pixels: Array<DoubleArray>): Array<DoubleArray> {
        val N = HASH_SIZE.toDouble()
        return Array(DCT_SIZE) { u ->
            DoubleArray(DCT_SIZE) { v ->
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                var sum = 0.0
                for (x in 0 until HASH_SIZE) {
                    for (y in 0 until HASH_SIZE) {
                        sum += pixels[y][x] *
                            cos((2 * x + 1) * u * Math.PI / (2 * N)) *
                            cos((2 * y + 1) * v * Math.PI / (2 * N))
                    }
                }
                (2.0 / N) * cu * cv * sum
            }
        }
    }

    fun calculateHammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != hash2.length) return Int.MAX_VALUE
        var distance = 0
        for (i in hash1.indices) {
            if (hash1[i] != hash2[i]) distance++
        }
        return distance
    }

    // Tối ưu hóa lớn: Di chuyển toàn bộ tiến trình giảm dung lượng ảnh nặng ra Dispatchers.Default
    //
    // ✅ SỬA: maxWidth/maxHeight trước đây (480x270, tỉ lệ cố định 16:9) dùng thẳng làm kích
    // thước ĐÍCH cho Bitmap.createScaledBitmap() — ép mọi ảnh về đúng 480x270 bất kể tỉ lệ
    // khung hình gốc, khiến camera lắp dọc (ảnh gốc cao hơn rộng) bị co dẹt/méo hình. Giờ
    // maxWidth/maxHeight là GIỚI HẠN cạnh dài nhất (300-400px theo yêu cầu — mục đích ảnh này
    // là pHash so sánh + AI Vision, không phải xem/lưu trữ, không cần độ phân giải cao), tỉ lệ
    // khung hình gốc luôn được giữ nguyên qua targetWidth/targetHeight tính toán bên dưới.
    suspend fun optimizeImage(imageBytes: ByteArray, maxWidth: Int = 400, maxHeight: Int = 400): ByteArray = withContext(Dispatchers.Default) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return@withContext imageBytes

            var scale = 1
            while (srcWidth / scale > maxWidth && srcHeight / scale > maxHeight) {
                scale *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions) 
                ?: return@withContext imageBytes // Chống lỗi NPE và sập app nếu ảnh thô bị hỏng từ đầu vào

            // Giữ nguyên tỉ lệ khung hình gốc: co theo cạnh dài hơn trước, cạnh còn lại tính
            // theo đúng tỉ lệ srcWidth/srcHeight — không ép cứng cả 2 chiều như trước, tránh
            // méo ảnh với camera lắp dọc hoặc tỉ lệ khác 16:9. Không phóng to nếu ảnh gốc đã
            // nhỏ hơn giới hạn sẵn (coerceAtMost giữ nguyên kích thước gốc trong trường hợp đó).
            val ratio = minOf(maxWidth.toFloat() / srcWidth, maxHeight.toFloat() / srcHeight, 1f)
            val targetWidth = (srcWidth * ratio).toInt().coerceAtLeast(1)
            val targetHeight = (srcHeight * ratio).toInt().coerceAtLeast(1)

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (bitmap != scaledBitmap) bitmap.recycle()

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            scaledBitmap.recycle()

            outputStream.toByteArray()
        } catch (e: Exception) {
            imageBytes
        }
    }
}