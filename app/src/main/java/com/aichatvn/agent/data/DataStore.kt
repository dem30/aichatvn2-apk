package com.aichatvn.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// Định nghĩa mở rộng Context toàn cục để giải quyết lỗi biên dịch
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")