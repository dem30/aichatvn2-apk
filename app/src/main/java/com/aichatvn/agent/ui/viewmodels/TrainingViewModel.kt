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

data class DiagnosticTier(
    val tierName: String,
    val tierNum: Int,
    val matched: Boolean,
    val details: String,
    val score: Double = 0.0
)

data class DiagnosticInfo(
    val query: String,
    val tiers: List<DiagnosticTier>,
    val resolvedIntents: List<String> = emptyList(),
    val resolvedAliases: Map<String, String> = emptyMap(),
    // ✅ Thêm để TrainingScreen hiển thị chi tiết từng tầng
    val intentMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val aliasMatches: List<Pair<QAEntity, Double>> = emptyList(),
    val bestAliasMatches: Map<String, Pair<QAEntity, Double>> = emptyMap(),
    val intentThreshold: Float = 0.3f,
    val aliasThreshold: Float = 0.2f
)

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val trainingSkill: TrainingSkill,
    private val configProvider: AppConfigProvider
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
                _messagesValueAndMore(newQAs)
            }
            _isLoading.value = false
        }
    }

    private fun _messagesValueAndMore(newQAs: List<QAEntity>) {
        _hasMore.value = newQAs.size == PAGE_SIZE
        if (newQAs.isNotEmpty()) {
            _currentPage.value++
        }
    }

    private fun resetAndReload() {
        _currentPage.value = 1
        _hasMore.value = true
        viewModelScope.launch {
            loadMoreQAs()
        }
    }

    fun addQA(question: String, answer: String, type: String, category: String) {
        viewModelScope.launch {
            trainingSkill.addQA(question, answer, type, category, "default_user")
            resetAndReload()
        }
    }

    fun updateQA(id: String, question: String?, answer: String?, type: String?, category: String?) {
        viewModelScope.launch {
            trainingSkill.updateQA(id, question, answer, type, category, "default_user")
            resetAndReload()
        }
    }

    fun deleteQA(id: String, username: String) {
        viewModelScope.launch {
            trainingSkill.deleteQA(id, username)
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

    // ✅ Mô phỏng đầy đủ luồng đi 5 tầng thực tế của Core Engine
    fun searchQAs(query: String) {
        activeSearchQuery = query
        viewModelScope.launch {
            _isLoading.value = true

            val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
            val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
            val highConfThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.85f)

            val matchResult = trainingSkill.fuzzyMatchCategorized(
                query = query,
                username = "default_user",
                intentThreshold = 0.0f, // Ngưỡng 0.0 để phân tích cả các phương án bị loại
                aliasThreshold = 0.0f
            )

            // Dựng sơ đồ giả lập 5 Tầng logic
            val simulatedTiers = mutableListOf<DiagnosticTier>()

            // Tầng 1: Pending Intent
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 1: Trạng thái dở dang (Pending Intent)",
                    tierNum = 1,
                    matched = false,
                    details = "Không phát hiện hàng đợi lệnh dở dang cần gán tham số bổ sung."
                )
            )

            // Tầng 2: So khớp Ý định (Fuzzy Intent Match)
            val bestIntentPair = matchResult.intentMatches.firstOrNull()
            val hasBestIntent = bestIntentPair != null && bestIntentPair.second >= intentThreshold
            val isHighConf = bestIntentPair != null && bestIntentPair.second >= highConfThreshold

            val wrapperPair = matchResult.intentMatches
                .filter { it.second >= intentThreshold }
                .find { it.first.answer.contains("\"plugin\":\"schedule\"") }

            val selectedT2Pair = wrapperPair ?: bestIntentPair

            val t2Details = when {
                selectedT2Pair == null -> "Không khớp bất kỳ ý định mẫu nào trong cơ sở dữ liệu."
                wrapperPair != null -> "Phát hiện ý định Lập lịch (Wrapper) '${selectedT2Pair.first.question}' được ưu tiên so khớp trước để tránh cướp quyền."
                isHighConf -> "Khớp ý định '${selectedT2Pair.first.question}' đạt ngưỡng tin cậy cao (>= $highConfThreshold). Bypass trực tiếp không qua LLM."
                else -> "Khớp ý định '${selectedT2Pair.first.question}' nhưng chưa đạt ngưỡng tin cậy cao (< $highConfThreshold)."
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 2: So khớp Ý định (Exact/Fuzzy QA Match)",
                    tierNum = 2,
                    matched = hasBestIntent,
                    details = t2Details,
                    score = selectedT2Pair?.second ?: 0.0
                )
            )

            // Tầng 3: Phân rã mệnh đề đa lệnh (Clause & Spotter)
            val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
            val clauses = clauseSeparator.split(query).map { it.trim() }.filter { it.isNotBlank() }
            val hasMultiClauses = clauses.size > 1
            val hasWrapper = query.contains("lịch", ignoreCase = true) || query.contains("schedule", ignoreCase = true)

            val t3Details = buildString {
                append("Tách mệnh đề thô thành ${clauses.size} cụm: ${clauses.joinToString(" | ") { "\"$it\"" }}.\n")
                if (hasWrapper) {
                    append("-> Phát hiện từ khóa Wrapper (Lập lịch). Hệ thống ưu tiên xử lý khung hẹn giờ trước để đệ quy nạp thông số lồng phía sau.")
                } else if (hasMultiClauses) {
                    append("-> Phát hiện nhiều câu lệnh song song. Kích hoạt đồng thời hàng đợi đa nhiệm Pending Intents.")
                } else {
                    append("-> Câu lệnh đơn, xử lý tuần tự thông thường.")
                }
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 3: Phân rã mệnh đề & So khớp cụm từ (Clause Spotter)",
                    tierNum = 3,
                    matched = hasMultiClauses || hasWrapper,
                    details = t3Details
                )
            )

            // Tầng 4: So khớp quy tắc từ khóa mô tả Metadata
            val t4Matched = selectedT2Pair == null && query.length > 5
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 4: So khớp quy tắc từ khóa Metadata",
                    tierNum = 4,
                    matched = t4Matched,
                    details = if (t4Matched) "Không khớp Q&A tĩnh nhưng khớp mô tả/ví dụ mẫu trong Manifest của các Plugin." else "Bỏ qua do đã khớp ở tầng trước hoặc từ khóa quá ngắn."
                )
            )

            // Tầng 5: Phân loại bằng LLM (Groq Fallback)
            val t5Matched = !isHighConf && wrapperPair == null
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 5: Phân loại bằng trí tuệ nhân tạo (LLM Fallback)",
                    tierNum = 5,
                    matched = t5Matched,
                    details = if (t5Matched) "Đã gửi câu hỏi lên Groq LLM để phân loại ý định và trích xuất cấu trúc JSON." else "Bỏ qua gọi LLM nhờ khớp thô đạt điểm tin cậy cao."
                )
            )

            _diagnosticInfo.value = DiagnosticInfo(
                query = query,
                tiers = simulatedTiers,
                resolvedIntents = matchResult.intentMatches
                    .filter { it.second >= intentThreshold }
                    .map { "${it.first.question} (điểm: ${String.format("%.2f", it.second)})" },
                resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
                // ✅ Populate đầy đủ để TrainingScreen hiển thị chi tiết
                intentMatches = matchResult.intentMatches,
                aliasMatches = matchResult.aliasMatches,
                bestAliasMatches = matchResult.bestAliasMatches,
                intentThreshold = intentThreshold,
                aliasThreshold = aliasThreshold
            )

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
