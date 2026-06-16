package com.aichatvn.agent.core.conversation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aichatvn.agent.data.dataStore
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationContext @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    
    private val cache = mutableMapOf<String, String>()
    
    private object Keys {
        const val LAST_PLUGIN = "last_plugin"
        const val LAST_ACTION = "last_action"
        const val LAST_ENTITY = "last_entity"
        const val LAST_CAMERA_ID = "last_camera_id"
        const val LAST_EMAIL_TO = "last_email_to"
        
        const val PENDING_PLUGIN = "pending_plugin"
        const val PENDING_ACTION = "pending_action"
        const val PENDING_PARAMS = "pending_params"
        const val PENDING_MISSING_PARAMS = "pending_missing_params"
    }
    
    private val lastPluginKey = stringPreferencesKey(Keys.LAST_PLUGIN)
    private val lastActionKey = stringPreferencesKey(Keys.LAST_ACTION)
    private val lastEntityKey = stringPreferencesKey(Keys.LAST_ENTITY)
    private val lastCameraIdKey = stringPreferencesKey(Keys.LAST_CAMERA_ID)
    private val lastEmailToKey = stringPreferencesKey(Keys.LAST_EMAIL_TO)
    private val pendingPluginKey = stringPreferencesKey(Keys.PENDING_PLUGIN)
    private val pendingActionKey = stringPreferencesKey(Keys.PENDING_ACTION)
    private val pendingParamsKey = stringPreferencesKey(Keys.PENDING_PARAMS)
    private val pendingMissingParamsKey = stringPreferencesKey(Keys.PENDING_MISSING_PARAMS)
    
    suspend fun set(key: String, value: String) {
        cache[key] = value
        if (key.startsWith("last_") || key.startsWith("pending_")) {
            try {
                val dataStoreKey = when (key) {
                    Keys.LAST_PLUGIN -> lastPluginKey
                    Keys.LAST_ACTION -> lastActionKey
                    Keys.LAST_ENTITY -> lastEntityKey
                    Keys.LAST_CAMERA_ID -> lastCameraIdKey
                    Keys.LAST_EMAIL_TO -> lastEmailToKey
                    Keys.PENDING_PLUGIN -> pendingPluginKey
                    Keys.PENDING_ACTION -> pendingActionKey
                    Keys.PENDING_PARAMS -> pendingParamsKey
                    Keys.PENDING_MISSING_PARAMS -> pendingMissingParamsKey
                    else -> return
                }
                context.dataStore.edit { prefs ->
                    prefs[dataStoreKey] = value
                }
            } catch (e: Exception) {
                logger.e("ConversationContext", "Failed to save $key: ${e.message}")
            }
        }
    }
    
    suspend fun get(key: String): String? {
        cache[key]?.let { return it }
        
        if (key.startsWith("last_") || key.startsWith("pending_")) {
            try {
                val dataStoreKey = when (key) {
                    Keys.LAST_PLUGIN -> lastPluginKey
                    Keys.LAST_ACTION -> lastActionKey
                    Keys.LAST_ENTITY -> lastEntityKey
                    Keys.LAST_CAMERA_ID -> lastCameraIdKey
                    Keys.LAST_EMAIL_TO -> lastEmailToKey
                    Keys.PENDING_PLUGIN -> pendingPluginKey
                    Keys.PENDING_ACTION -> pendingActionKey
                    Keys.PENDING_PARAMS -> pendingParamsKey
                    Keys.PENDING_MISSING_PARAMS -> pendingMissingParamsKey
                    else -> return null
                }
                val prefs = context.dataStore.data.first()
                val value = prefs[dataStoreKey]
                if (value != null && value.isNotEmpty()) {
                    cache[key] = value
                    return value
                }
            } catch (e: Exception) {
                logger.e("ConversationContext", "Failed to load $key: ${e.message}")
            }
        }
        return null
    }
    
    suspend fun setPending(pluginId: String, action: String, params: Map<String, Any>, missingParams: List<String> = emptyList()) {
        set(Keys.PENDING_PLUGIN, pluginId)
        set(Keys.PENDING_ACTION, action)
        val jsonParams = JSONObject(params).toString()
        set(Keys.PENDING_PARAMS, jsonParams)
        set(Keys.PENDING_MISSING_PARAMS, JSONObject(mapOf("missing" to missingParams)).toString())
        logger.d("ConversationContext", "Saved pending: $pluginId.$action, params=$jsonParams, missing=$missingParams")
    }
    
    suspend fun getPendingPlugin(): String? {
        val value = get(Keys.PENDING_PLUGIN)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun getPendingAction(): String? {
        val value = get(Keys.PENDING_ACTION)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun getPendingParams(): Map<String, Any> {
        val paramsStr = get(Keys.PENDING_PARAMS) ?: return emptyMap()
        if (paramsStr.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(paramsStr)
            jsonToMap(json)
        } catch (e: Exception) {
            logger.e("ConversationContext", "Failed to parse pending params: ${e.message}")
            emptyMap()
        }
    }
    
    suspend fun getPendingMissingParams(): List<String> {
        val missingStr = get(Keys.PENDING_MISSING_PARAMS) ?: return emptyList()
        if (missingStr.isBlank()) return emptyList()
        return try {
            val json = JSONObject(missingStr)
            val arr = json.optJSONArray("missing") ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            logger.e("ConversationContext", "Failed to parse missing params: ${e.message}")
            emptyList()
        }
    }
    
    suspend fun clearPending() {
        set(Keys.PENDING_PLUGIN, "")
        set(Keys.PENDING_ACTION, "")
        set(Keys.PENDING_PARAMS, "")
        set(Keys.PENDING_MISSING_PARAMS, "")
        cache.remove(Keys.PENDING_PLUGIN)
        cache.remove(Keys.PENDING_ACTION)
        cache.remove(Keys.PENDING_PARAMS)
        cache.remove(Keys.PENDING_MISSING_PARAMS)
        logger.d("ConversationContext", "Cleared pending")
    }
    
    suspend fun hasPending(): Boolean {
        val plugin = getPendingPlugin()
        val action = getPendingAction()
        return !plugin.isNullOrEmpty() && !action.isNullOrEmpty()
    }
    
    suspend fun getLastPlugin(): String? {
        val value = get(Keys.LAST_PLUGIN)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun setLastPlugin(pluginId: String) = set(Keys.LAST_PLUGIN, pluginId)
    
    suspend fun getLastAction(): String? {
        val value = get(Keys.LAST_ACTION)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun setLastAction(action: String) = set(Keys.LAST_ACTION, action)
    
    suspend fun getLastEntity(): String? {
        val value = get(Keys.LAST_ENTITY)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun setLastEntity(entity: String) = set(Keys.LAST_ENTITY, entity)
    
    suspend fun getLastCameraId(): String? {
        val value = get(Keys.LAST_CAMERA_ID)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun setLastCameraId(cameraId: String) = set(Keys.LAST_CAMERA_ID, cameraId)
    
    suspend fun getLastEmailTo(): String? {
        val value = get(Keys.LAST_EMAIL_TO)
        return if (value.isNullOrEmpty()) null else value
    }
    
    suspend fun setLastEmailTo(email: String) = set(Keys.LAST_EMAIL_TO, email)
    
    suspend fun getContextString(): String {
        val lastPlugin = getLastPlugin()
        val lastAction = getLastAction()
        val lastEntity = getLastEntity()
        val lastCamera = getLastCameraId()
        val lastEmail = getLastEmailTo()
        val pendingPlugin = getPendingPlugin()
        val pendingAction = getPendingAction()
        val pendingMissing = getPendingMissingParams()
        
        return buildString {
            if (pendingPlugin != null && pendingAction != null) {
                append("- Đang chờ bạn cung cấp thông tin cho: $pendingPlugin.$pendingAction\n")
                if (pendingMissing.isNotEmpty()) {
                    append("- Cần cung cấp: ${pendingMissing.joinToString()}\n")
                }
            }
            if (lastPlugin != null) append("- Plugin cuối: $lastPlugin\n")
            if (lastAction != null) append("- Hành động cuối: $lastAction\n")
            if (lastEntity != null) append("- Đối tượng cuối: $lastEntity\n")
            if (lastCamera != null) append("- Camera cuối: $lastCamera\n")
            if (lastEmail != null) append("- Email cuối: $lastEmail\n")
        }
    }
    
    suspend fun getContextForLLM(): String {
        val lastPlugin = getLastPlugin()
        val lastAction = getLastAction()
        val lastEntity = getLastEntity()
        val lastCamera = getLastCameraId()
        val lastEmail = getLastEmailTo()
        val pendingPlugin = getPendingPlugin()
        val pendingAction = getPendingAction()
        val pendingMissing = getPendingMissingParams()
        
        return buildString {
            if (pendingPlugin != null && pendingAction != null) {
                append("Người dùng đang trong quá trình thực hiện hành động '$pendingAction' của plugin '$pendingPlugin'.\n")
                if (pendingMissing.isNotEmpty()) {
                    append("Các tham số còn thiếu: ${pendingMissing.joinToString()}\n")
                }
                append("Câu trả lời của người dùng là thông tin bổ sung cho hành động này.\n")
            }
            if (lastPlugin != null && lastAction != null && lastEntity != null) {
                append("Người dùng vừa thực hiện hành động '$lastAction' trên '$lastEntity' của plugin '$lastPlugin'.\n")
                append("Câu 'tắt nó đi' có nghĩa là tắt '$lastEntity'.\n")
            } else if (lastCamera != null) {
                append("Người dùng vừa thao tác với camera '$lastCamera'.\n")
                append("Câu 'tắt nó đi' có nghĩa là tắt camera '$lastCamera'.\n")
            } else if (lastEmail != null) {
                append("Người dùng vừa gửi email tới '$lastEmail'.\n")
            }
        }
    }
    
    // ============= JSON CONVERTERS (đệ quy triệt để) =============
    
    private fun jsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            map[key] = convertJsonValue(json.get(key))
        }
        return map
    }
    
    private fun convertJsonValue(value: Any): Any {
        return when (value) {
            is JSONObject -> jsonToMap(value)
            is JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    list.add(convertJsonValue(value.get(i))) // ✅ Đệ quy triệt để
                }
                list
            }
            else -> value
        }
    }
    
    fun clearMemory() {
        cache.clear()
    }
    
    suspend fun loadFromStore() {
        try {
            val prefs = context.dataStore.data.first()
            
            prefs[lastPluginKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.LAST_PLUGIN] = it }
            prefs[lastActionKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.LAST_ACTION] = it }
            prefs[lastEntityKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.LAST_ENTITY] = it }
            prefs[lastCameraIdKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.LAST_CAMERA_ID] = it }
            prefs[lastEmailToKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.LAST_EMAIL_TO] = it }
            prefs[pendingPluginKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.PENDING_PLUGIN] = it }
            prefs[pendingActionKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.PENDING_ACTION] = it }
            prefs[pendingParamsKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.PENDING_PARAMS] = it }
            prefs[pendingMissingParamsKey]?.takeIf { it.isNotEmpty() }?.let { cache[Keys.PENDING_MISSING_PARAMS] = it }
        } catch (e: Exception) {
            logger.e("ConversationContext", "Failed to load from store: ${e.message}")
        }
    }
}