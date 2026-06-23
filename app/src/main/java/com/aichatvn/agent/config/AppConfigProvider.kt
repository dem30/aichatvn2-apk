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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppConfigProvider
 *
 * Singleton quản lý cấu hình toàn cục.
 *
 * CÁCH DÙNG TRONG PLUGIN:
 *   // Lấy 1 lần (suspend):
 *   val cooldown = configProvider.getLong(AppConfigDefaults.CAMERA_COOLDOWN_MS)
 *
 *   // Observe realtime (không cần restart):
 *   configProvider.observeString(AppConfigDefaults.GROQ_MODEL_TEXT).collect { model -> … }
 *
 * SEED:
 *   Khi khởi tạo, provider tự seed tất cả default values (INSERT OR IGNORE).
 *   Record đã tồn tại trong DB không bị ghi đè.
 */
@Singleton
class AppConfigProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.getDatabase(context).appConfigDao() }

    /** StateFlow toàn bộ config — UI bind trực tiếp */
    val allConfigs: StateFlow<List<AppConfigEntity>> = dao
        .getAllConfigsFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** StateFlow nhóm theo pluginId — dùng cho Settings screen */
    fun configsByPlugin(pluginId: String): Flow<List<AppConfigEntity>> =
        dao.getConfigsByPluginFlow(pluginId)

    init {
        scope.launch { seedDefaults() }
    }

    // ─────────────────────────── SEED ────────────────────────────

    private suspend fun seedDefaults() {
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

    suspend fun getString(key: String, default: String = ""): String =
        dao.getConfig(key)?.value ?: default

    suspend fun getInt(key: String, default: Int = 0): Int =
        dao.getConfig(key)?.value?.toIntOrNull() ?: default

    suspend fun getLong(key: String, default: Long = 0L): Long =
        dao.getConfig(key)?.value?.toLongOrNull() ?: default

    suspend fun getFloat(key: String, default: Float = 0f): Float =
        dao.getConfig(key)?.value?.toFloatOrNull() ?: default

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean =
        dao.getConfig(key)?.value?.toBooleanStrictOrNull() ?: default

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

    suspend fun set(key: String, value: String) {
        val existing = dao.getConfig(key)
        val entity = existing?.copy(value = value, updatedAt = System.currentTimeMillis())
            ?: AppConfigEntity(key = key, value = value, updatedAt = System.currentTimeMillis())
        dao.upsert(entity)
        logger.d("AppConfigProvider", "set $key = $value")
    }

    /** Upsert toàn bộ entity (dùng từ AppConfigSkill khi người dùng sửa qua chat) */
    suspend fun upsert(entity: AppConfigEntity) {
        dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(key: String) {
        dao.delete(key)
    }

    /** Snapshot toàn bộ — dùng cho AppConfigSkill.list() */
    suspend fun getAll(): List<AppConfigEntity> = dao.getAll()
}