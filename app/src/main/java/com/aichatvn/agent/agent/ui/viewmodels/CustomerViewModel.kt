package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.AgentKernel.PluginResult
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val database: AppDatabase,
    private val logger: Logger
) : ViewModel() {

    val customers: StateFlow<List<CustomerEntity>> = database.customerDao()
        .getAllCustomersFlow()
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    fun addQA(question: String, answer: String, type: String, category: String, username: String) {
        // Hàm rỗng đảm bảo cấu trúc kế thừa nếu có
    }

    fun addQA(question: String, answer: String, type: String, category: String) {
        // Hàm rỗng đảm bảo cấu trúc kế thừa nếu có
    }

    fun saveCustomer(
        id: String?,
        name: String,
        email: String,
        address: String,
        note: String
    ) {
        if (name.isBlank()) { _result.value = "❌ Tên khách hàng không được để trống"; return }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val trimmedId = id?.trim()
                val customerId = trimmedId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                
                // Di chuyển toàn bộ giao dịch SQLite I/O ra khỏi luồng chính
                val (existing, entity) = withContext(Dispatchers.IO) {
                    val ext = if (trimmedId != null) database.customerDao().getCustomerById(trimmedId) else null
                    val ent = CustomerEntity(
                        id = customerId,
                        name = name.trim(),
                        email = email.trim(),
                        address = address.trim(),
                        note = note.trim(),
                        createdAt = ext?.createdAt ?: System.currentTimeMillis()
                    )
                    database.customerDao().insertCustomer(ent)
                    Pair(ext, ent)
                }

                _result.value = if (existing == null) "✅ Đã thêm khách hàng" else "✅ Đã cập nhật"
                logger.i("CustomerViewModel", "saveCustomer OK id=$customerId")
            } catch (e: Exception) {
                _result.value = "❌ Lỗi: ${e.message}"
                logger.e("CustomerViewModel", "saveCustomer error: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteCustomer(customerId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val trimmedCustomerId = customerId.trim()
                // Di chuyển toàn bộ các thao tác xóa Cascade phức tạp ra khỏi luồng chính
                withContext(Dispatchers.IO) {
                    database.customerDao().deleteCustomer(trimmedCustomerId)
                    database.cameraDao().deleteCamerasByCustomer(trimmedCustomerId)
                    database.cameraDao().deleteCustomerSetting(trimmedCustomerId)
                }
                _result.value = "🗑️ Đã xoá khách hàng"
                logger.i("CustomerViewModel", "deleteCustomer id=$trimmedCustomerId")
            } catch (e: Exception) {
                _result.value = "❌ Lỗi xoá: ${e.message}"
                logger.e("CustomerViewModel", "deleteCustomer error: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    fun clearResult() { _result.value = null }
}