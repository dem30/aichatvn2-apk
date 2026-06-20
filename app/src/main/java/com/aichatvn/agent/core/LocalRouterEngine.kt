package com.aichatvn.agent.core

import android.content.Context
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRouterEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {

    companion object {
        private const val MODEL_FILENAME =
            "SmolLM2-135M-Instruct-Q4_K_M.gguf"

        private const val MODEL_URL =
            "https://github.com/dem30/aichatvn2-apk/releases/download/models/SmolLM2-135M-Instruct-Q4_K_M.gguf"

        private const val SAFE_FALLBACK =
            """{"plugin":"chat","action":"none","params":{}}"""
    }

    @Volatile
    private var downloaded = false

    private fun ensureModelDownloaded() {
        if (downloaded) return

        val modelFile = File(context.filesDir, MODEL_FILENAME)

        if (!modelFile.exists()) {

            logger.d(
                "LocalRouterEngine",
                "Downloading model..."
            )

            URL(MODEL_URL).openStream().use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            logger.d(
                "LocalRouterEngine",
                "Model downloaded: ${modelFile.absolutePath}"
            )
        }

        downloaded = true
    }

    suspend fun predictIntent(prompt: String): String {

        return try {

            ensureModelDownloaded()

            SAFE_FALLBACK

        } catch (e: Exception) {

            logger.e(
                "LocalRouterEngine",
                e.message ?: "download error",
                e
            )

            SAFE_FALLBACK
        }
    }
}