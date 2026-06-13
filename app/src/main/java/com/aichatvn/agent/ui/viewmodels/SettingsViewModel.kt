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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

    val gmailClientId: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_CLIENT_ID] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailClientSecret: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_CLIENT_SECRET] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailRefreshToken: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_REFRESH_TOKEN] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val gmailSender: StateFlow<String> = context.dataStore.data
        .map { it[GMAIL_SENDER] ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

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

    fun testGroqConnection(apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val requestBody = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Test connection. Reply with 'OK'")
                        })
                    })
                    put("max_tokens", 10)
                }.toString()
                
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toOkHttpMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    onResult(true, "Kết nối thành công!")
                } else {
                    onResult(false, "Lỗi API: ${response.code} - ${response.message}")
                }
                response.close()
            } catch (e: Exception) {
                onResult(false, "Lỗi kết nối: ${e.message}")
            }
        }
    }

    private fun String.toOkHttpMediaType() = okhttp3.MediaType.parse("application/json")!!
}