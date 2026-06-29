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

    // ✅ MÔ PHỎNG LUỒNG 5 TẦNG THỰC TẾ ĐỂ PHỤC VỤ TRỰC QUAN HÓA DIAGNOSTICS
    fun searchQAs(query: String) {
        activeSearchQuery = query
        viewModelScope.launch {
            _isLoading.value = true

            val intentThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_FUZZY_THRESHOLD, 0.3f)
            val aliasThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_ALIAS_THRESHOLD, 0.2f)
            val highConfThreshold = configProvider.getFloat(AppConfigDefaults.GLOBAL_TIER2_HIGH_CONFIDENCE, 0.80f)

            val matchResult = trainingSkill.fuzzyMatchCategorized(
                query = query,
                username = "default_user",
                intentThreshold = 0.0f,
                aliasThreshold = 0.0f
            )

            val simulatedTiers = mutableListOf<DiagnosticTier>()

            // ── TẦNG 1: TRẠNG THÁI DỞ DANG (PENDING INTENT) ──
            val hasPendingIntents = false // Giả định trạng thái dở dang ban đầu
            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 1: Trạng thái lệnh dở dang (Pending Intents)",
                    tierNum = 1,
                    matched = hasPendingIntents,
                    details = if (hasPendingIntents) {
                        "Phát hiện tiến trình đang chờ thu thập thêm thông tin. Hệ thống tạm dừng khớp câu lệnh mới để giải quyết gán dữ liệu cho tham số bị thiếu."
                    } else {
                        "Không phát hiện hàng đợi lệnh dở dang nào. Bỏ qua và chuyển tiếp xuống Tầng 2."
                    }
                )
            )

            // ── TẦNG 2: SO KHỚP Ý ĐỊNH CHẤT LƯỢNG CAO (SEMANTIC FUZZY QA MATCH) ──
            val bestIntentPair = matchResult.intentMatches.firstOrNull()
            
            // Ưu tiên schedule nếu có trùng khớp
            val wrapperPair = matchResult.intentMatches
                .filter { it.second >= intentThreshold }
                .find { it.first.answer.contains("\"plugin\":\"schedule\"") }

            val selectedT2Pair = wrapperPair ?: bestIntentPair
            val t2Score = selectedT2Pair?.second ?: 0.0
            
            val hasT2Match = selectedT2Pair != null && t2Score >= intentThreshold
            val isT2HighConf = selectedT2Pair != null && t2Score >= highConfThreshold

            val t2Details = when {
                selectedT2Pair == null -> {
                    "Không tìm thấy ý định mẫu nào khớp với nội dung bạn nhập."
                }
                wrapperPair != null -> {
                    "Phát hiện ý định Lập lịch (schedule) '${selectedT2Pair.first.question}' đạt điểm ${String.format("%.2f", t2Score)}. Được ưu tiên dựng bộ khung lập lịch để tránh xung đột."
                }
                isT2HighConf -> {
                    "Đã khớp ý định '${selectedT2Pair.first.question}' với điểm số cực cao (${String.format("%.2f", t2Score)} >= $highConfThreshold). Lệnh được thực thi trực tiếp tại Tầng 2 (Bypass qua LLM)."
                }
                else -> {
                    "Tìm thấy ý định '${selectedT2Pair.first.question}' nhưng điểm tin cậy (${String.format("%.2f", t2Score)}) chưa đạt ngưỡng thực thi nhanh ($highConfThreshold). Chuyển tiếp xuống Tầng 3."
                }
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 2: So khớp Ý định tĩnh (Exact/Fuzzy Intent Match)",
                    tierNum = 2,
                    matched = isT2HighConf || (wrapperPair != null),
                    details = t2Details,
                    score = t2Score
                )
            )

            // ── TẦNG 3: PHÂN RÃ MỆNH ĐỀ & CHỈ ĐỊNH THỰC THỂ (CLAUSE SPOTTER) ──
            val clauseSeparator = Regex("[,;]|\\bvà\\b|\\bđồng thời\\b|\\bsau đó\\b|\\brồi\\b", RegexOption.IGNORE_CASE)
            val clauses = clauseSeparator.split(query).map { it.trim() }.filter { it.isNotBlank() }
            val hasMultiClauses = clauses.size > 1
            val hasScheduleKeyword = query.contains("lịch", ignoreCase = true) || query.contains("hẹn", ignoreCase = true)

            val isT3Matched = !isT2HighConf && (hasMultiClauses || hasScheduleKeyword || hasT2Match)
            
            val t3Details = buildString {
                append("Tách câu lệnh thành ${clauses.size} mệnh đề con: ${clauses.joinToString(" | ") { "\"$it\"" }}.\n")
                if (hasScheduleKeyword) {
                    append("-> Có từ khóa thời gian/lập lịch. Ưu tiên bóc tách cấu trúc lập lịch lồng nhau (Nested) tại Tầng 3.")
                } else if (hasMultiClauses) {
                    append("-> Kích hoạt cơ chế Đa lệnh (Multi-Intents) chạy song song hoặc tuần tự.")
                } else if (hasT2Match) {
                    append("-> Khớp câu lệnh đơn '${selectedT2Pair?.first?.question}' thông qua cơ chế bóc tách tham số (Slot-Filling) của Tầng 3.")
                } else {
                    append("-> Không nhận diện được mệnh đề hoặc thực thể mẫu nào hợp lệ.")
                }
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 3: Tách mệnh đề đa lệnh & Slot-Filling (Clause Spotter)",
                    tierNum = 3,
                    matched = isT3Matched,
                    details = t3Details
                )
            )

            // ── TẦNG 4: KHỚP QUY TẮC PHẦN CỨNG/METADATA (ACTION MANIFEST MATCH) ──
            val isT4Matched = !isT2HighConf && !isT3Matched && query.length > 5
            val t4Details = if (isT4Matched) {
                "Không khớp QA huấn luyện nhưng khớp với mô tả lệnh/ví dụ trong Manifest khai báo tĩnh của các Plugin thiết bị."
            } else {
                "Bỏ qua Tầng 4 do lệnh đã được giải quyết hoặc trích xuất thành công ở các tầng trước."
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 4: So khớp Mô tả & Nhãn Plugin (Metadata Matcher)",
                    tierNum = 4,
                    matched = isT4Matched,
                    details = t4Details
                )
            )

            // ── TẦNG 5: TRÍ TUỆ NHÂN TẠO PHÂN LOẠI HẠN CHẾ (LLM ROUTING FALLBACK) ──
            val isT5Matched = !isT2HighConf && !isT3Matched && !isT4Matched
            val t5Details = if (isT5Matched) {
                "Hệ thống không tìm thấy bất kỳ mẫu đối khớp tĩnh nào. Chuyển tiếp câu lệnh lên mô hình ngôn ngữ lớn (Groq LLM) để phân tích ngữ nghĩa tự do và sinh cấu trúc JSON."
            } else {
                "Bypass (Bỏ qua gọi LLM) để tiết kiệm tài nguyên hệ thống do bộ lọc heuristic phía trên đã xử lý xong câu lệnh."
            }

            simulatedTiers.add(
                DiagnosticTier(
                    tierName = "Tầng 5: Phân loại thông minh bằng AI (LLM Fallback)",
                    tierNum = 5,
                    matched = isT5Matched,
                    details = t5Details
                )
            )

            // Cập nhật State cho View
            _diagnosticInfo.value = DiagnosticInfo(
                query = query,
                tiers = simulatedTiers,
                resolvedIntents = matchResult.intentMatches
                    .filter { it.second >= intentThreshold }
                    .map { "${it.first.question} (${String.format("%.2f", it.second)})" },
                resolvedAliases = matchResult.bestAliasMatches.mapValues { it.value.first.answer },
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