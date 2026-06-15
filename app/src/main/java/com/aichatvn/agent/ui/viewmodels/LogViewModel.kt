package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) : ViewModel() {

    val logs = logger.logs

    private val _filterLevel = MutableStateFlow(Logger.LogLevel.DEBUG)
    val filterLevel: StateFlow<Logger.LogLevel> = _filterLevel.asStateFlow()

    fun setFilterLevel(level: Logger.LogLevel) {
        _filterLevel.value = level
    }

    fun clearLogs() {
        logger.clear()
    }

    fun exportLogs(): File {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AIChatVN"
        )
        return logger.exportLogsToFile(downloadsDir)
    }
}