package com.aichatvn.agent.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cung cấp Groq API key, đọc lazy ngay trước mỗi request (KHÔNG đọc lúc init).
 *
 * ✅ SỬA: đã bỏ fallback BuildConfig.GROQ_API_KEY (GitHub Actions secret) — server không còn
 * giữ key build-time nào nữa, chỉ còn duy nhất 1 nguồn: key user tự nhập trong Settings UI →
 * lưu trong DataStore ("groq_api_key").
 *
 * Trả về null nếu key trống → GroqClientTool sẽ hiện cảnh báo cho user.
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
        return userKey.takeIf { !it.isNullOrBlank() }
    }
}