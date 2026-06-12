package com.aichatvn.agent.tools.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Pure Kotlin pHash implementation — no external library required.
 * Uses a 32x32 DCT-based perceptual hash.
 */
@Singleton
class ImageHashTool @Inject constructor() {

    companion object {
        private const val HASH_SIZE = 32
        private const val DCT_SIZE = 8
    }

    fun calculatePhash(imageBytes: ByteArray): String {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
            if (bitmap != scaled) bitmap.recycle()

            // Convert to grayscale luma values
            val pixels = Array(HASH_SIZE) { y ->
                DoubleArray(HASH_SIZE) { x ->
                    val pixel = scaled.getPixel(x, y)
                    0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                }
            }
            scaled.recycle()

            // Apply 2D DCT and take top-left 8x8
            val dct = applyDCT(pixels)
            val subDct = DoubleArray(DCT_SIZE * DCT_SIZE) { i ->
                dct[i / DCT_SIZE][i % DCT_SIZE]
            }

            // Compute mean (excluding first coefficient)
            val mean = subDct.drop(1).average()

            // Build binary hash
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

    fun optimizeImage(imageBytes: ByteArray, maxWidth: Int = 480, maxHeight: Int = 270): ByteArray {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            var scale = 1
            while (options.outWidth / scale > maxWidth && options.outHeight / scale > maxHeight) {
                scale *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, maxHeight, true)
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
