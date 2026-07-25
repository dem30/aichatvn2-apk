package com.aichatvn.agent.utils

import com.aichatvn.agent.data.WorldStateDao
import com.aichatvn.agent.data.model.WorldStateEntity
import org.json.JSONObject

object WorldStateHelper {

    // Giới hạn độ dài 1 giá trị được lưu vào world_state.
    // world_state chỉ nên chứa dữ liệu trạng thái ngắn (on/off, số liệu, id...),
    // KHÔNG được chứa payload lớn như imageBase64/file base64 — nếu không,
    // console hiển thị sẽ phải render 1 chuỗi liền cực dài không có khoảng trắng,
    // khiến việc line-break trong Compose Text/TextView bị treo (ANR).
    private const val MAX_ATTRIBUTE_VALUE_LENGTH = 2000

    fun sanitizeAttributeValue(key: String, value: String): String {
        if (value.length <= MAX_ATTRIBUTE_VALUE_LENGTH) return value
        // Nếu là dữ liệu dạng base64/ảnh/file thì loại bỏ hẳn, không lưu ký tự nào của nó,
        // chỉ giữ lại kích thước để debug.
        val looksLikeBase64Payload = key.contains("base64", ignoreCase = true) ||
            key.contains("image", ignoreCase = true) ||
            key.contains("file", ignoreCase = true)
        return if (looksLikeBase64Payload) {
            "[omitted:${value.length}_chars]"
        } else {
            value.take(MAX_ATTRIBUTE_VALUE_LENGTH) + "...[truncated:${value.length}_chars]"
        }
    }

    // ✅ Ghi/merge 1 attribute vào world_state của (source, sourceId), giữ nguyên các thuộc tính khác
    suspend fun setAttribute(dao: WorldStateDao, source: String, sourceId: String, key: String, value: String) {
        val id = "$source:$sourceId"
        val existing = dao.getState(source, sourceId)
        val json = existing?.let {
            try { JSONObject(it.attributesJson) } catch (e: Exception) { JSONObject() }
        } ?: JSONObject()
        json.put(key, sanitizeAttributeValue(key, value))
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