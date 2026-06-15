package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
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

    fun exportLogs(cacheDir: java.io.File): java.io.File {
        return logger.exportLogsToFile(cacheDir)
    }
}
