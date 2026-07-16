package com.aichatvn.agent.utils

import com.aichatvn.agent.data.WorldStateDao
import com.aichatvn.agent.data.model.WorldStateEntity
import org.json.JSONObject

object WorldStateHelper {

    // ✅ Ghi/merge 1 attribute vào world_state của (source, sourceId), giữ nguyên các thuộc tính khác
    suspend fun setAttribute(dao: WorldStateDao, source: String, sourceId: String, key: String, value: String) {
        val id = "$source:$sourceId"
        val existing = dao.getState(source, sourceId)
        val json = existing?.let {
            try { JSONObject(it.attributesJson) } catch (e: Exception) { JSONObject() }
        } ?: JSONObject()
        json.put(key, value)
        dao.upsertState(
            WorldStateEntity(
                id = id,
                source = source,
                sourceId = sourceId,
                attributesJson = json.toString(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getAttribute(dao: WorldStateDao, source: String, sourceId: String, key: String): String? {
        val state = dao.getState(source, sourceId) ?: return null
        return try {
            JSONObject(state.attributesJson).optString(key, "").ifBlank { null }
        } catch (e: Exception) { null }
    }

    // Lớp chứa thông tin điều kiện phân tích
    data class WorldStateCondition(val source: String, val sourceId: String, val attrKey: String, val expected: String)

    fun parseCondition(raw: String): WorldStateCondition? {
        if (raw.isBlank()) return null
        val eqIdx = raw.indexOf('=')
        if (eqIdx < 0) return null
        val pathPart = raw.substring(0, eqIdx).trim()
        val expected = raw.substring(eqIdx + 1).trim()
        val segments = pathPart.split(".")
        if (segments.size < 3) return null
        val source = segments[0]
        val attrKey = segments.last()
        val sourceId = segments.subList(1, segments.size - 1).joinToString(".")
        return WorldStateCondition(source, sourceId, attrKey, expected)
    }
}