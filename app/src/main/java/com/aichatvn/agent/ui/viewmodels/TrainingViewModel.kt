package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

// Cấu trúc dữ liệu phục vụ hiển thị phân tích chẩn đoán cho người dùng trên UI
data class DiagnosticInfo(
    val query: String,
    val intentMatches: List<Pair<QAEntity, Double>>,
    val aliasMatches: List<Pair<QAEntity, Double>>,
    val bestAliasMatches: Map<String, Pair<QAEntity, Double>>,
    val intentThreshold: Float,
    val aliasThreshold: Float
)

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val trainingSkill: TrainingSkill,
    private val configProvider: AppConfigProvider // Tiêm configProvider để lấy cấu hình ngưỡng trực tiếp từ DB
) : ViewModel() {

    val qaList: StateFlow<List<QAEntity>> = trainingSkill.qaList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<QAEntity>>(emptyList())
    val searchResults: StateFlow<List<QAEntity>> = _searchResults.asStateFlow()

    private val _diagnosticInfo = MutableStateFlow<DiagnosticInfo?>(null)
    val diagnosticInfo: StateFlow<DiagnosticInfo?> = _diagnosticInfo.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var activeSearchQuery: String = ""
    private val PAGE_SIZE = 20

    init {
        viewModelScope.launch {
            loadMoreQAs()
        }
    }

    fun loadMoreQAs() {
        viewModelScope.launch {
            if (!_hasMore.value || _isLoading.value) return@launch
            _isLoading.value = true

            val result = trainingSkill.getQAsPaginated(_currentPage.value, PAGE_SIZE, "default_user")
            if (result is PluginResult.Success) {
                @Suppress("UNCHECKED_CAST")
                val newQAs = (result.data as? Map<String, Any>)?.get("qas") as? List<QAEntity> ?: emptyList()
                _hasMore.value = newQAs.size == PAGE_SIZE
                if (newQAs.isNotEmpty()) {
                    _currentPage.value++
                }
            }
            _isLoading.value = false
        }
    }

    // Reset lại trạng thái phân trang và nạp lại dữ liệu trang đầu tiên
    private fun resetAndReload() {
        _currentPage.value = 1
        _hasMore.value = true
        viewModelScope.launch {
            loadMoreQAs()
        }
    }

    // Loại bỏ overload 3 tham số để tránh mặc định hóa sai lệch kiểu dữ liệu
    fun addQA(question: String, answer: String, type: String, category: String) {
        viewModelScope.launch {
            trainingSkill.addQA(question, answer, type, category, "default_user")
            resetAndReload()
        }
    }

    // Loại bỏ overload cũ để tránh ghi đè làm mất kiểu dữ liệu gốc "intent" thành "alias"
    fun updateQA(id: String, question: String?, answer: String?, type: String?, category: String?) {
        viewModelScope.launch {
            trainingSkill.updateQA(id, question, answer, type, category, "default_user")
            resetAndReload()
            if (activeSearchQuery.isNotBlank()) {
                refreshSearchResults()
            }
        }
    }

    fun deleteQA(id: String) {
        viewModelScope.launch {
            trainingSkill.deleteQA(id, "default_user")
            resetAndReload()
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
            resetAndReload()
            if (activeSearchQuery.isNotBlank()) {
                refreshSearchResults()
            }
            _isLoading.value = false
        }
    }

    private suspend fun refreshSearchResults() {
        searchQAs(activeSearchQuery)
    }

    fun deleteAllQAs() {
        viewModelScope.launch {
            trainingSkill.deleteAllQAs("default_user")
            resetAndReload()
            clearSearch()
        }
    }

    fun searchQAs(query: String) {
        activeSearchQuery = query
        viewModelScope.launch {
            _isLoading.value = true
            
            // Đọc cấu hình ngưỡng hiện hành từ DB
            val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
            val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)

            // Thiết lập gọi tìm kiếm không giới hạn ngưỡng thô để lấy đầy đủ chi tiết chẩn đoán
            val matchResult = trainingSkill.fuzzyMatchCategorized(
                query = query,
                username = "default_user",
                intentThreshold = 0.0f, // Đặt ngưỡng 0.0 để có thể phân tích cả các phương án bị loại
                aliasThreshold = 0.0f
            )

            // Cập nhật thông tin phân tích
            _diagnosticInfo.value = DiagnosticInfo(
                query = query,
                intentMatches = matchResult.intentMatches,
                aliasMatches = matchResult.aliasMatches,
                bestAliasMatches = matchResult.bestAliasMatches,
                intentThreshold = intentThreshold,
                aliasThreshold = aliasThreshold
            )

            // Lưu danh sách thô đã lọc theo ngưỡng chuẩn vào searchResults để giữ tính nguyên bản của danh sách hiển thị
            val filteredIntents = matchResult.intentMatches.filter { it.second >= intentThreshold }
            val filteredAliases = matchResult.aliasMatches.filter { it.second >= aliasThreshold }
            _searchResults.value = (filteredIntents + filteredAliases).map { it.first }

            _isLoading.value = false
        }
    }

    fun clearSearch() {
        activeSearchQuery = ""
        _searchResults.value = emptyList()
        _diagnosticInfo.value = null
    }

    fun exportQAToJson(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = trainingSkill.exportQAs("default_user")
            if (result is PluginResult.Success) {
                val data = result.data as? Map<*, *>
                val jsonString = data?.get("json") as? String
                if (jsonString != null) {
                    try {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = File(downloadsDir, "qa_export_${System.currentTimeMillis()}.json")
                        withContext(Dispatchers.IO) {
                            file.writeText(jsonString)
                        }
                        _exportResult.value = "✅ Đã lưu tại: ${file.absolutePath}"
                    } catch (e: Exception) {
                        _exportResult.value = "❌ Lỗi lưu file: ${e.message}"
                    }
                } else {
                    _exportResult.value = "❌ Export thất bại: Dữ liệu rỗng"
                }
            } else {
                _exportResult.value = "❌ Export thất bại"
            }
            _isLoading.value = false
        }
    }

    fun importQAFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (jsonString.isNullOrBlank()) {
                    _importResult.value = "❌ File rỗng"
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
                val lines = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines()
                } ?: emptyList()

                if (lines.size <= 1) {
                    _importResult.value = "❌ File CSV không có dữ liệu hợp lệ"
                    _isLoading.value = false
                    return@launch
                }

                val csvLines = lines.drop(1)
                val jsonArray = JSONArray()
                var skipped = 0

                for (line in csvLines) {
                    val cols = line.split(",").map { it.trim().removeSurrounding("\"") }
                    if (cols.size >= 2 && cols[0].isNotBlank() && cols[1].isNotBlank()) {
                        val obj = JSONObject().apply {
                            put("question", cols[0])
                            put("answer", cols[1])
                            put("type", cols.getOrElse(2) { "alias" })
                            put("category", cols.getOrElse(3) { "general" })
                        }
                        jsonArray.put(obj)
                    } else {
                        skipped++
                    }
                }

                if (jsonArray.length() > 0) {
                    val result = trainingSkill.importQAs(jsonArray.toString(), "default_user")
                    _importResult.value = when (result) {
                        is PluginResult.Success -> {
                            val data = result.data as? Map<*, *>
                            val imported = data?.get("imported") as? Int ?: jsonArray.length()
                            "✅ Đã import thành công $imported dòng CSV" + if (skipped > 0) ", bỏ qua $skipped dòng lỗi" else ""
                        }
                        is PluginResult.Failure -> "❌ Import CSV thất bại: ${result.error}"
                        else -> "❌ Import CSV thất bại"
                    }
                } else {
                    _importResult.value = "❌ Không tìm thấy dòng CSV nào hợp lệ để xử lý"
                }
            } catch (e: Exception) {
                _importResult.value = "❌ Lỗi đọc CSV: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun clearExportResult() { _exportResult.value = null }
    fun clearImportResult() { _importResult.value = null }
}