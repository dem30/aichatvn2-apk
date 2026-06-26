package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel.PluginResult
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

    val qaList: StateFlow<List<QAEntity>> = trainingSkill.qaList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<QAEntity>>(emptyList())
    val searchResults: StateFlow<List<QAEntity>> = _searchResults.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // Track query đang active để refresh searchResults sau khi xoá
    private var activeSearchQuery: String = ""

    private val PAGE_SIZE = 20

    init {
        viewModelScope.launch {
            trainingSkill.initialize()
            loadMoreQAs()
        }
    }

    fun loadMoreQAs() {
        viewModelScope.launch {
            if (!_hasMore.value || _isLoading.value) return@launch
            _isLoading.value = true

            val result = trainingSkill.getQAsPaginated(_currentPage.value, PAGE_SIZE, "default_user")
            when (result) {
                is PluginResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    val newQAs = (result.data as? Map<String, Any>)?.get("qas") as? List<QAEntity> ?: emptyList()
                    _hasMore.value = newQAs.size == PAGE_SIZE
                    if (newQAs.isNotEmpty()) {
                        _currentPage.value++
                    }
                }
                is PluginResult.Failure -> {
                    // Log error if needed
                }
                else -> {}
            }
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
            // Nếu đang search thì refresh lại kết quả, tránh item "ma" vẫn hiện sau khi xoá
            if (activeSearchQuery.isNotBlank()) {
                refreshSearchResults()
            }
        }
    }

    fun batchDeleteQAs(ids: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            for (id in ids) {
                trainingSkill.deleteQA(id, "default_user")
            }
            // Refresh search nếu đang search
            if (activeSearchQuery.isNotBlank()) {
                refreshSearchResults()
            }
            _isLoading.value = false
        }
    }

    private suspend fun refreshSearchResults() {
        val result = trainingSkill.searchQAs(activeSearchQuery, "default_user")
        if (result is PluginResult.Success) {
            @Suppress("UNCHECKED_CAST")
            _searchResults.value = (result.data as? Map<String, Any>)?.get("results") as? List<QAEntity> ?: emptyList()
        }
    }

    fun deleteAllQAs() {
        viewModelScope.launch {
            trainingSkill.deleteAllQAs("default_user")
        }
    }

    fun searchQAs(query: String) {
        activeSearchQuery = query  // Lưu query để dùng khi refresh sau xoá
        viewModelScope.launch {
            _isLoading.value = true
            val result = trainingSkill.searchQAs(query, "default_user")
            when (result) {
                is PluginResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    _searchResults.value = (result.data as? Map<String, Any>)?.get("results") as? List<QAEntity> ?: emptyList()
                }
                is PluginResult.Failure -> {
                    _searchResults.value = emptyList()
                }
                else -> {}
            }
            _isLoading.value = false
        }
    }

    fun clearSearch() {
        activeSearchQuery = ""
        _searchResults.value = emptyList()
    }

    fun exportQAToJson(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = trainingSkill.exportQAs("default_user")
            when (result) {
                is PluginResult.Success -> {
                    val data = result.data as? Map<*, *>
                    val jsonString = data?.get("json") as? String
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
                }
                is PluginResult.Failure -> {
                    _exportResult.value = "❌ Export thất bại: ${result.error}"
                }
                else -> {}
            }
            _isLoading.value = false
        }
    }

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
                    _importResult.value = when (result) {
                        is PluginResult.Success -> {
                            val data = result.data as? Map<*, *>
                            val message = data?.get("message") as? String ?: "Import thành công"
                            "✅ $message"
                        }
                        is PluginResult.Failure -> "❌ Import thất bại: ${result.error}"
                        else -> "❌ Import thất bại"
                    }
                }
            } catch (e: Exception) {
                _importResult.value = "❌ Lỗi đọc file: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun importQAFromCsvUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val lines = context.contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.readLines()
                    ?.drop(1)
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