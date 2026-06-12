package com.aichatvn.agent.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichatvn.agent.data.model.QAEntity
import com.aichatvn.agent.skills.TrainingSkill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    init {
        viewModelScope.launch {
            trainingSkill.initialize()
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

    fun searchQAs(query: String) {
        viewModelScope.launch {
            val result = trainingSkill.searchQAs(query, "default_user")
            @Suppress("UNCHECKED_CAST")
            _searchResults.value = (result.data as? List<QAEntity>) ?: emptyList()
        }
    }
}
