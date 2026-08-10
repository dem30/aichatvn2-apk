package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.HealthItem
import com.aichatvn.agent.data.model.SystemHealthReport
import com.aichatvn.agent.skills.SystemAuditor
import com.aichatvn.agent.skills.SystemAutoConfigurer
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SystemHealthViewModel
 *
 * Lớp điều phối mỏng giữa UI và 2 lớp nghiệp vụ SHAS:
 *   - runAudit() gọi SystemAuditor (chỉ đọc) — an toàn gọi bất cứ lúc nào, kể cả tự động
 *     lúc mở màn hình (init block), không cần người dùng bấm gì.
 *   - applySafeFixes()/applyItem() gọi SystemAutoConfigurer (chỉ ghi) — CHỈ chạy khi người
 *     dùng chủ động bấm nút "Áp dụng".
 *
 * KHÔNG tự thêm logic nghiệp vụ ở đây (vd tự quyết định item nào an toàn) — quyết định đó
 * đã nằm trong SystemAuditor.safeToAutoApply và chốt chặn lại 1 lần nữa trong
 * SystemAutoConfigurer.applyItem(); ViewModel chỉ chuyển tiếp, không lặp lại logic.
 */
@HiltViewModel
class SystemHealthViewModel @Inject constructor(
    private val auditor: SystemAuditor,
    private val autoConfigurer: SystemAutoConfigurer,
    private val logger: Logger
) : ViewModel() {

    companion object {
        private const val TAG = "SystemHealthViewModel"
    }

    private val _report = MutableStateFlow<SystemHealthReport?>(null)
    val report: StateFlow<SystemHealthReport?> = _report.asStateFlow()

    private val _isAuditing = MutableStateFlow(false)
    val isAuditing: StateFlow<Boolean> = _isAuditing.asStateFlow()

    private val _isApplying = MutableStateFlow(false)
    val isApplying: StateFlow<Boolean> = _isApplying.asStateFlow()

    /** Thông báo ngắn cho UI hiện Snackbar sau khi áp dụng — null nghĩa là không có gì để hiện. */
    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    init {
        runAudit()
    }

    /**
     * Quét lại toàn bộ hệ thống. Gọi khi mở màn hình (init), kéo-để-làm-mới, hoặc sau khi
     * applySafeFixes() xong (để UI phản ánh đúng trạng thái mới nhất, không tự lạc quan
     * giả định mọi thứ đã Applied).
     */
    fun runAudit() {
        viewModelScope.launch {
            _isAuditing.value = true
            try {
                _report.value = auditor.audit()
            } catch (e: Exception) {
                logger.e(TAG, "❌ Lỗi khi quét hệ thống: ${e.message}", e)
                _resultMessage.value = "Không thể quét hệ thống lúc này, thử lại sau."
            } finally {
                _isAuditing.value = false
            }
        }
    }

    /**
     * Áp dụng toàn bộ mục an toàn hiện có trong report (SystemHealthReport.safeImprovableItems).
     * Dùng cho nút "Áp dụng tất cả" trên SystemHealthCard/Screen.
     */
    fun applySafeFixes() {
        val items = _report.value?.safeImprovableItems.orEmpty()
        if (items.isEmpty()) return
        applyItems(items)
    }

    /** Áp dụng 1 tập con item người dùng tự chọn (vd qua checkbox trong SystemHealthScreen). */
    fun applyItems(items: List<HealthItem>) {
        viewModelScope.launch {
            _isApplying.value = true
            try {
                val results = autoConfigurer.applyItems(items)
                val appliedCount = results.count { it is SystemAutoConfigurer.ApplyResult.Applied }
                val failedCount = results.size - appliedCount
                _resultMessage.value = when {
                    failedCount == 0 -> "Đã áp dụng $appliedCount cải thiện."
                    appliedCount == 0 -> "Không áp dụng được mục nào, thử lại sau."
                    else -> "Đã áp dụng $appliedCount/${results.size} cải thiện."
                }
                // Quét lại để UI phản ánh đúng trạng thái thật sau khi ghi DB, không tự suy
                // đoán report cũ đã lỗi thời ra sao.
                _report.value = auditor.audit()
            } catch (e: Exception) {
                logger.e(TAG, "❌ Lỗi khi áp dụng cải thiện: ${e.message}", e)
                _resultMessage.value = "Không thể áp dụng lúc này, thử lại sau."
            } finally {
                _isApplying.value = false
            }
        }
    }

    /** UI gọi sau khi đã hiện Snackbar, để không hiện lại cùng thông báo khi state re-render. */
    fun consumeResultMessage() {
        _resultMessage.value = null
    }
}
