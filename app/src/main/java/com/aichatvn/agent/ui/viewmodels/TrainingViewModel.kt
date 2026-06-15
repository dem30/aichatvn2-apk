package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val trainingSkill: TrainingSkill
) : ViewModel() {

    // qaList từ TrainingSkill — reactive, tự cập nhật sau mọi CRUD
    val qaList: StateFlow<List<QAEntity>> = trainingSkill.qaList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<QAEntity>>(emptyList())
    val searchResults: StateFlow<List<QAEntity>> = _searchResults.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    // Pagination — chỉ dùng để biết khi nào dừng load thêm
    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val PAGE_SIZE = 20

    init {
        viewModelScope.launch {
            trainingSkill.initialize()
            loadMoreQAs()
        }
    }

    /**
     * Pagination: load thêm 1 trang từ DB.
     * TrainingSkill.qaList là StateFlow toàn bộ data — pagination ở đây chỉ
     * kiểm soát việc load dần để tránh tải quá nhiều record một lúc khi init.
     */
    fun loadMoreQAs() {
        viewModelScope.launch {
            if (!_hasMore.value || _isLoading.value) return@launch
            _isLoading.value = true

            val result = trainingSkill.getQAsPaginated(_currentPage.value, PAGE_SIZE, "default_user")
            @Suppress("UNCHECKED_CAST")
            val newQAs = (result.data as? Map<String, Any>)?.get("qas") as? List<QAEntity> ?: emptyList()

            _hasMore.value = newQAs.size == PAGE_SIZE
            if (newQAs.isNotEmpty()) {
                _currentPage.value++
            }

            // qaList cập nhật qua TrainingSkill.refreshQAList (Flow) — không cần thao tác thủ công
            _isLoading.value = false
        }
    }

    fun addQA(question: String, answer: String, category: String) {
        viewModelScope.launch {
            trainingSkill.addQA(question, answer, category, "default_user")
        }
    }

    fun updateQA(id: String, question: String?, answer: String?, category: String?) {
        viewModelScope.launch {
            trainingSkill.updateQA(id, question, answer, category, "default_user")
        }
    }

    fun deleteQA(id: String) {
        viewModelScope.launch {
            trainingSkill.deleteQA(id, "default_user")
        }
    }

    fun batchDeleteQAs(ids: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            for (id in ids) {
                trainingSkill.deleteQA(id, "default_user")
            }
            _isLoading.value = false
        }
    }

    fun deleteAllQAs() {
        viewModelScope.launch {
            trainingSkill.deleteAllQAs("default_user")
        }
    }

    fun searchQAs(query: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = trainingSkill.searchQAs(query, "default_user")
            @Suppress("UNCHECKED_CAST")
            _searchResults.value = (result.data as? List<QAEntity>) ?: emptyList()
            _isLoading.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
    }

    // ─── Export ──────────────────────────────────────────────────────────────

    fun exportQAToJson(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = trainingSkill.exportQAs("default_user")
            if (result.success && result.data != null) {
                val jsonString = result.data as? String
                if (jsonString != null) {
                    try {
                        val downloadsDir = android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = File(downloadsDir, "qa_export_${System.currentTimeMillis()}.json")
                        file.writeText(jsonString)
                        _exportResult.value = "✅ Đã lưu tại: ${file.absolutePath}"
                    } catch (e: Exception) {
                        _exportResult.value = "❌ Lỗi lưu file: ${e.message}"
                    }
                } else {
                    _exportResult.value = "❌ Export thất bại: Dữ liệu rỗng"
                }
            } else {
                _exportResult.value = "❌ Export thất bại: ${result.error}"
            }
            _isLoading.value = false
        }
    }

    // ─── Import — nhận URI từ ActivityResultLauncher trong TrainingScreen ────

    /**
     * Đọc file JSON từ URI (được pick bởi ActivityResultLauncher trong Screen),
     * parse và import vào DB thông qua TrainingSkill.importQAs().
     */
    fun importQAFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val jsonString = context.contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }

                if (jsonString.isNullOrBlank()) {
                    _importResult.value = "❌ File rỗng hoặc không đọc được"
                } else {
                    val result = trainingSkill.importQAs(jsonString, "default_user")
                    _importResult.value = if (result.success) {
                        "✅ Import thành công: ${result.data}"
                    } else {
                        "❌ Import thất bại: ${result.error}"
                    }
                }
            } catch (e: Exception) {
                _importResult.value = "❌ Lỗi đọc file: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    /**
     * Import CSV đơn giản: mỗi dòng là "question,answer,category"
     * (category tùy chọn, mặc định là "general")
     */
    fun importQAFromCsvUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val lines = context.contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.readLines()
                    ?.drop(1) // bỏ header
                    ?: emptyList()

                var imported = 0
                var skipped = 0
                for (line in lines) {
                    val cols = line.split(",").map { it.trim().removeSurrounding("\"") }
                    if (cols.size >= 2 && cols[0].isNotBlank() && cols[1].isNotBlank()) {
                        trainingSkill.addQA(
                            question = cols[0],
                            answer = cols[1],
                            category = cols.getOrElse(2) { "general" },
                            username = "default_user"
                        )
                        imported++
                    } else {
                        skipped++
                    }
                }
                _importResult.value = "✅ Import CSV: $imported dòng OK" +
                    if (skipped > 0) ", $skipped dòng bỏ qua" else ""
            } catch (e: Exception) {
                _importResult.value = "❌ Lỗi đọc CSV: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun clearExportResult() { _exportResult.value = null }
    fun clearImportResult() { _importResult.value = null }
}
