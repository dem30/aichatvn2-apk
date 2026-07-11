package com.aichatvn.agent.utils

import org.json.JSONObject

fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        if (value != org.json.JSONObject.NULL) {
            map[key] = when (value) {
                is JSONObject -> value.toMap()
                is org.json.JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item != org.json.JSONObject.NULL) {
                            list.add(if (item is JSONObject) item.toMap() else item)
                        }
                    }
                    list
                }
                else -> value
            }
        }
    }
    return map
}