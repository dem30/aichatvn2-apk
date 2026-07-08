package com.aichatvn.agent.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aichatvn.agent.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cung cấp Groq API key, đọc lazy ngay trước mỗi request (KHÔNG đọc lúc init).
 *
 * Thứ tự ưu tiên:
 * 1. Key user nhập trong Settings UI → lưu trong DataStore ("groq_api_key")
 * 2. Key build-time từ GitHub Actions secret → BuildConfig.GROQ_API_KEY
 *
 * Trả về null nếu cả 2 đều trống → GroqClientTool sẽ hiện cảnh báo cho user.
 */
@Singleton
class GroqApiKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
    }

    suspend fun getKey(): String? {
        val userKey = context.dataStore.data.first()[GROQ_API_KEY]?.trim()
        if (!userKey.isNullOrBlank()) return userKey

        val buildKey = BuildConfig.GROQ_API_KEY.trim()
        return buildKey.takeIf { it.isNotBlank() }
    }
}
