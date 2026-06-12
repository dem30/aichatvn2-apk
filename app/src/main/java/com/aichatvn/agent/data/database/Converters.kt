package com.aichatvn.agent.data.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    
    @TypeConverter
    fun fromStringList(value: String?): List<String>? {
        if (value == null) return null
        val type = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(value, type)
    }
    
    @TypeConverter
    fun toStringList(list: List<String>?): String? {
        return list?.let { Gson().toJson(it) }
    }
}