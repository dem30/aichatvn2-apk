package com.aichatvn.agent.ui.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        val GMAIL_CLIENT_ID = stringPreferencesKey("gmail_client_id")
        val GMAIL_CLIENT_SECRET = stringPreferencesKey("gmail_client_secret")
        val GMAIL_REFRESH_TOKEN = stringPreferencesKey("gmail_refresh_token")
        val GMAIL_SENDER = stringPreferencesKey("gmail_sender")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }

    val groqApiKey: StateFlow<String> = context.dataStore.data
        .map { it[GROQ_API_KEY] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val darkMode: StateFlow<Boolean> = context.dataStore.data
        .map { it[DARK_MODE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun saveGroqApiKey(key: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[GROQ_API_KEY] = key }
        }
    }

    fun saveGmailSettings(clientId: String, clientSecret: String, refreshToken: String, sender: String) {
        viewModelScope.launch {
            context.dataStore.edit {
                it[GMAIL_CLIENT_ID] = clientId
                it[GMAIL_CLIENT_SECRET] = clientSecret
                it[GMAIL_REFRESH_TOKEN] = refreshToken
                it[GMAIL_SENDER] = sender
            }
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[DARK_MODE] = enabled }
        }
    }
}
