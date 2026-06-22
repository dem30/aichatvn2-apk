package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.CustomerEntity
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CustomerViewModel @Inject constructor(
    private val database: AppDatabase,
    private val logger: Logger
) : ViewModel() {

    val customers: StateFlow<List<CustomerEntity>> = database.customerDao()
        .getAllCustomersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result.asStateFlow()

    fun saveCustomer(
        id: String?,          // null = tạo mới
        name: String,
        email: String,
        address: String,
        note: String
    ) {
        if (name.isBlank()) { _result.value = "❌ Tên khách hàng không được để trống"; return }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val customerId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val existing = if (id != null) database.customerDao().getCustomerById(id) else null
                val entity = CustomerEntity(
                    id = customerId,
                    name = name.trim(),
                    email = email.trim(),
                    address = address.trim(),
                    note = note.trim(),
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                database.customerDao().insertCustomer(entity)
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
            try {
                database.customerDao().deleteCustomer(customerId)
                // Xoá luôn cameras + settings liên quan
                database.cameraDao().deleteCamerasByCustomer(customerId)
                database.cameraDao().deleteCustomerSetting(customerId)
                _result.value = "🗑️ Đã xoá khách hàng"
                logger.i("CustomerViewModel", "deleteCustomer id=$customerId")
            } catch (e: Exception) {
                _result.value = "❌ Lỗi xoá: ${e.message}"
                logger.e("CustomerViewModel", "deleteCustomer error: ${e.message}", e)
            }
        }
    }

    fun clearResult() { _result.value = null }
}
