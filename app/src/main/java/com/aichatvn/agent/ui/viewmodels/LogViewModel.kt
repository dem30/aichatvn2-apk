package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logger: Logger
) : ViewModel() {

    val logs = logger.logs

    private val _filterLevel = MutableStateFlow(Logger.LogLevel.DEBUG)
    val filterLevel: StateFlow<Logger.LogLevel> = _filterLevel.asStateFlow()

    private val _exportState = MutableStateFlow<String?>(null)
    val exportState: StateFlow<String?> = _exportState.asStateFlow()

    fun setFilterLevel(level: Logger.LogLevel) {
        _filterLevel.value = level
    }

    fun clearLogs() {
        logger.clear()
    }

    // Tối ưu hóa lớn: Di chuyển toàn bộ tiến trình ghi đĩa thô của tệp tin log lớn sang Dispatchers.IO
    // Kết quả trả về thông qua cơ chế callback bất đồng bộ để bảo vệ luồng giao diện
    fun exportLogs(onSuccess: (File) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _exportState.value = "⏳ Đang trích xuất nhật ký..."
            try {
                val file = withContext(Dispatchers.IO) {
                    val downloadsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "AIChatVN"
                    )
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    logger.exportLogsToFile(downloadsDir)
                }
                _exportState.value = "✅ Đã trích xuất tệp thành công"
                onSuccess(file)
            } catch (e: Exception) {
                _exportState.value = "❌ Lỗi: ${e.message}"
                onError(e.message ?: "Lỗi ghi đĩa không xác định")
            }
        }
    }

    fun clearExportState() {
        _exportState.value = null
    }
}