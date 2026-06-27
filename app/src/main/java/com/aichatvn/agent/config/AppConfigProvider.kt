package com.aichatvn.agent.config

import android.content.Context
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.getDatabase(context).appConfigDao() }

    val allConfigs: StateFlow<List<AppConfigEntity>> = dao
        .getAllConfigsFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun configsByPlugin(pluginId: String): Flow<List<AppConfigEntity>> =
        dao.getConfigsByPluginFlow(pluginId)

    init {
        scope.launch { seedDefaults() }
    }

    // ─────────────────────────── SEED ────────────────────────────

    private suspend fun seedDefaults() = withContext(Dispatchers.IO) {
        try {
            AppConfigDefaults.all().forEach { default ->
                dao.insertIfAbsent(default)
            }
            logger.d("AppConfigProvider", "✅ Seed config defaults hoàn tất")
        } catch (e: Exception) {
            logger.e("AppConfigProvider", "seedDefaults error: ${e.message}", e)
        }
    }

    // ─────────────────────────── READ ────────────────────────────

    suspend fun getString(key: String, default: String = ""): String = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value ?: default
    }

    suspend fun getInt(key: String, default: Int = 0): Int = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toIntOrNull() ?: default
    }

    suspend fun getLong(key: String, default: Long = 0L): Long = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toLongOrNull() ?: default
    }

    suspend fun getFloat(key: String, default: Float = 0f): Float = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toFloatOrNull() ?: default
    }

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        dao.getConfig(key)?.value?.toBooleanStrictOrNull() ?: default
    }

    // ─────────────── OBSERVE (Flow) ──────────────────────────────

    fun observeString(key: String, default: String = ""): Flow<String> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value ?: default }

    fun observeInt(key: String, default: Int = 0): Flow<Int> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value?.toIntOrNull() ?: default }

    fun observeLong(key: String, default: Long = 0L): Flow<Long> =
        allConfigs.map { list -> list.firstOrNull { it.key == key }?.value?.toLongOrNull() ?: default }

    fun observeBoolean(key: String, default: Boolean = false): Flow<Boolean> =
        allConfigs.map { list ->
            list.firstOrNull { it.key == key }?.value?.toBooleanStrictOrNull() ?: default
        }

    // ─────────────────────────── WRITE ───────────────────────────

    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        val existing = dao.getConfig(key)
        val entity = existing?.copy(value = value, updatedAt = System.currentTimeMillis())
            ?: AppConfigEntity(key = key, value = value, updatedAt = System.currentTimeMillis())
        dao.upsert(entity)
        logger.d("AppConfigProvider", "set $key = $value")
    }

    suspend fun upsert(entity: AppConfigEntity) = withContext(Dispatchers.IO) {
        dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        dao.delete(key)
    }

    suspend fun getAll(): List<AppConfigEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }
}