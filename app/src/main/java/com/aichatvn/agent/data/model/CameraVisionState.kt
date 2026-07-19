package com.aichatvn.agent.data.model

import org.json.JSONArray
import org.json.JSONObject

data class VisionObject(
    val type: String,        // person, car, dog, cat, package, unknown
    val details: String,     // "đàn ông mặc áo xanh, xe máy màu đỏ..."
    val location: String,    // "gần hàng rào", "dưới hiên nhà"...
    val relations: String    // "đứng cạnh xe máy", "ở trên bàn"...
)

data class CameraVisionState(
    val state: String,       // normal / suspicious
    val confidence: Double,
    val description: String,
    val objects: List<VisionObject> = emptyList(),
    val hasPerson: Boolean = false,
    val hasVehicle: Boolean = false,
    val hasAnimal: Boolean = false
) {
    companion object {
        fun fromJson(json: String): CameraVisionState? {
            return try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("objects") ?: JSONArray()
                val objectsList = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    VisionObject(
                        type = o.optString("type", "unknown"),
                        details = o.optString("details", ""),
                        location = o.optString("location", ""),
                        relations = o.optString("relations", "")
                    )
                }
                val classification = obj.optJSONObject("question_classification") ?: JSONObject()
                CameraVisionState(
                    state = obj.optString("state", "normal"),
                    confidence = obj.optDouble("confidence", 1.0),
                    description = obj.optString("description", ""),
                    objects = objectsList,
                    hasPerson = classification.optBoolean("has_person", false),
                    hasVehicle = classification.optBoolean("has_vehicle", false),
                    hasAnimal = classification.optBoolean("has_animal", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}