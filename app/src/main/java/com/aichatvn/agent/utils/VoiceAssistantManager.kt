package com.aichatvn.agent.utils

import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAssistantManager(
    context: Context,
    private val onListeningStateChange: (Boolean) -> Unit,
    private val onTextRecognized: (String) -> Unit,
    private val onSilence: () -> Unit = {},
    private val onPartialTranscript: (String) -> Unit = {},
    private val onListenError: (String) -> Unit = {}
) {
    private val sttHelper = SpeechRecognizerHelper(context)
    val ttsHelper = TextToSpeechHelper(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogTimer: CountDownTimer? = null
    private var safetyRunnable: Runnable? = null

    @Volatile private var destroyed = false
    @Volatile private var hardErrorRetryCount = 0
    private val isListening = AtomicBoolean(false)

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        Log.d(TAG, "startListening() called | destroyed=$destroyed isListening=${isListening.get()}")
        if (destroyed) { Log.w(TAG, "  → SKIP: destroyed"); return }
        if (!isListening.compareAndSet(false, true)) {
            Log.w(TAG, "  → SKIP: already listening")
            return
        }

        Log.d(TAG, "  → OK: starting STT")
        onListeningStateChange(true)

        sttHelper.startListening(
            onPartialResult = { partialText ->
                restartWatchdog()
                onPartialTranscript(partialText)
            },
            onResult = { text ->
                Log.d(TAG, "onResult: \"$text\"")
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening.set(false)
                onListeningStateChange(false)
                onTextRecognized(text)
                scheduleSafetyRestart()
            },
            onSoftError = {
                Log.d(TAG, "onSoftError → restart ngay")
                stopWatchdog()
                hardErrorRetryCount = 0
                isListening.set(false)
                onListeningStateChange(false)
                onSilence()
                if (!destroyed) startListening()
            },
            onHardError = { reason ->
                Log.e(TAG, "onHardError: $reason")
                stopWatchdog()
                isListening.set(false)
                onListeningStateChange(false)
                onListenError(reason)
                hardErrorRetryCount = (hardErrorRetryCount + 1).coerceAtMost(MAX_HARD_ERROR_STEPS)
                scheduleRestart(HARD_ERROR_BASE_DELAY_MS * hardErrorRetryCount)
            }
        )

        startWatchdog()
    }

    fun speakResponse(text: String) {
        Log.d(TAG, "speakResponse: \"${text.take(40)}...\" ttsReady=${ttsHelper.isReady}")
        cancelSafetyRestart()
        ttsHelper.stop()
        if (destroyed) return

        if (text.isBlank()) {
            Log.d(TAG, "  → text blank, scheduleRestart 300ms")
            scheduleRestart(300L)
            return
        }

        ttsHelper.speak(text) {
            Log.d(TAG, "TTS onDone → scheduleRestart 300ms | destroyed=$destroyed")
            if (!destroyed) scheduleRestart(300L)
        }
    }

    fun stopListening() {
        Log.d(TAG, "stopListening()")
        cancelSafetyRestart()
        stopWatchdog()
        isListening.set(false)
        sttHelper.cancelListening()
        onListeningStateChange(false)
    }

    fun destroy() {
        Log.d(TAG, "destroy()")
        destroyed = true
        cancelSafetyRestart()
        stopWatchdog()
        isListening.set(false)
        sttHelper.destroy()
        ttsHelper.shutdown()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = object : CountDownTimer(WATCHDOG_TIMEOUT_MS, WATCHDOG_TIMEOUT_MS) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                Log.w(TAG, "Watchdog fired → restart")
                isListening.set(false)
                onListeningStateChange(false)
                onSilence()
                scheduleRestart(200L)
            }
        }.start()
    }

    private fun restartWatchdog() = startWatchdog()

    private fun stopWatchdog() {
        watchdogTimer?.cancel()
        watchdogTimer = null
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        Log.d(TAG, "scheduleRestart in ${delayMs}ms")
        mainHandler.postDelayed({
            if (!destroyed) startListening()
        }, delayMs)
    }

    private fun scheduleSafetyRestart() {
        cancelSafetyRestart()
        val r = Runnable {
            Log.w(TAG, "Safety timer fired → restart (speakResponse chưa được gọi?)")
            if (!destroyed && !isListening.get()) startListening()
        }
        safetyRunnable = r
        mainHandler.postDelayed(r, RESULT_NO_TTS_TIMEOUT_MS)
    }

    private fun cancelSafetyRestart() {
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyRunnable = null
    }

    companion object {
        private const val TAG = "VoiceManager"
        private const val WATCHDOG_TIMEOUT_MS      = 25_000L
        private const val HARD_ERROR_BASE_DELAY_MS = 800L
        private const val MAX_HARD_ERROR_STEPS     = 5
        private const val RESULT_NO_TTS_TIMEOUT_MS = 15_000L
    }
}