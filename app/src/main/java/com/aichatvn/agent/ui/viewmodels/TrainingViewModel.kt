package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
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
    
    // Pagination
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

    fun loadMoreQAs() {
        viewModelScope.launch {
            if (!_hasMore.value || _isLoading.value) return@launch
            
            _isLoading.value = true
            val result = trainingSkill.getQAsPaginated(_currentPage.value, PAGE_SIZE, "default_user")
            @Suppress("UNCHECKED_CAST")
            val newQAs = (result.data as? Map<String, Any>)?.get("qas") as? List<QAEntity> ?: emptyList()
            
            if (newQAs.isNotEmpty()) {
                val currentList = qaList.value.toMutableList()
                currentList.addAll(newQAs)
                trainingSkill.refreshQAList("default_user")
            }
            
            _hasMore.value = newQAs.size == PAGE_SIZE
            _currentPage.value++
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
    
    fun exportQAToJson(context: Context) {
        viewModelScope.launch {
            val result = trainingSkill.exportQAs("default_user")
            if (result.success && result.data != null) {
                val jsonString = result.data as? String
                if (jsonString != null) {
                    // Lưu file hoặc share
                    Toast.makeText(context, "Export thành công: $jsonString", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Export thất bại: ${result.error}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    fun importQAFromJson(context: Context) {
        // Mở file picker và đọc JSON
        viewModelScope.launch {
            Toast.makeText(context, "Chức năng đang phát triển", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun importQAFromCsv(context: Context) {
        viewModelScope.launch {
            Toast.makeText(context, "Chức năng đang phát triển", Toast.LENGTH_SHORT).show()
        }
    }
}