package com.aichatvn.agent.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Logger @Inject constructor() {

    data class LogEntry(
        val id: Long = System.nanoTime(),
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _newLog = MutableSharedFlow<LogEntry>()
    val newLog = _newLog.asSharedFlow()

    private val maxLogEntries = 500

    // Scope riêng cho Logger, không dùng GlobalScope để tránh leak/cảnh báo
    private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        addLog(LogLevel.VERBOSE, tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        addLog(LogLevel.WARNING, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        addLog(LogLevel.ERROR, tag, message, throwable)
    }

    private fun addLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        synchronized(this) {
            val currentLogs = _logs.value.toMutableList()
            currentLogs.add(0, entry) // Thêm mới lên đầu

            while (currentLogs.size > maxLogEntries) {
                currentLogs.removeAt(currentLogs.lastIndex)
            }

            _logs.value = currentLogs
        }

        loggerScope.launch {
            _newLog.emit(entry)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getErrors(): List<LogEntry> {
        return _logs.value.filter { it.level == LogLevel.ERROR }
    }

    fun getWarnings(): List<LogEntry> {
        return _logs.value.filter { it.level == LogLevel.WARNING }
    }

    /**
     * Xuất toàn bộ log hiện tại ra file text trong cacheDir của app.
     * Trả về File đã tạo để có thể chia sẻ / đọc.
     */
    fun exportLogsToFile(cacheDir: File): File {
        val logFile = File(cacheDir, "app_logs_${System.currentTimeMillis()}.txt")
        logFile.bufferedWriter().use { writer ->
            _logs.value.asReversed().forEach { log ->
                writer.write("[${formatTimestamp(log.timestamp)}] ${log.level.name} [${log.tag}] ${log.message}")
                writer.newLine()
                log.throwable?.let {
                    writer.write(it.stackTraceToString())
                    writer.newLine()
                }
            }
        }
        return logFile
    }

    private fun formatTimestamp(timestamp: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        return format.format(java.util.Date(timestamp))
    }
}
