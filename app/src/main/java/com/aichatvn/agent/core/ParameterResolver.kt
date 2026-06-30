package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginParameter

object ParameterResolver {
    private val SPACE_REGEX = Regex("\\s+")

    fun isPlaceholder(value: Any?, parameter: PluginParameter?): Boolean {
        val strVal = value?.toString()?.trim()?.replace(SPACE_REGEX, " ") ?: ""
        if (strVal.isBlank()) return true
        
        if (parameter != null && parameter.placeholder.isNotBlank()) {
            val paramPlh = parameter.placeholder.trim().replace(SPACE_REGEX, " ")
            if (strVal.equals(paramPlh, ignoreCase = true)) {
                return true
            }
        }
        
        val defaultPlaceholders = setOf(
            "device_1", "device_2", "camera_1", "camera_2",
            "device 1", "device 2", "camera 1", "camera 2",
            "example@gmail.com", "example@email.com",
            "schedule_1", "schedule_id_here"
        )
        return strVal in defaultPlaceholders
    }

    fun getUnresolvedParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String,
        allPlugins: Set<Plugin>
    ): List<String> {
        val missing = mutableListOf<String>()
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return missing

        action.parameters.filter { it.required }.forEach { param ->
            val value = params[param.name]
            if (isPlaceholder(value, param)) {
                missing.add(param.name)
            }
        }

        val paramsMeta = action.parameters.find { it.semanticType == "params" || it.name == "params" }
        if (paramsMeta != null) {
            val value = params[paramsMeta.name]
            val nestedParams = value as? Map<*, *>
            val targetPluginId = params["plugin_id"]?.toString()
                ?: params["pluginId"]?.toString()
                ?: params["plugin"]?.toString() ?: ""
            val targetAction = params["action_id"]?.toString()
                ?: params["action"]?.toString()
                ?: params["actionId"]?.toString() ?: ""
            
            if (targetPluginId.isNotBlank() && targetAction.isNotBlank()) {
                val tPlugin = allPlugins.find { it.manifest.id == targetPluginId }
                val tAction = tPlugin?.manifest?.actions?.find { it.name == targetAction }
                
                if (tAction != null) {
                    if (nestedParams == null) {
                        tAction.parameters.filter { it.required }.forEach { subParam ->
                            missing.add("params.${subParam.name}")
                        }
                    } else {
                        tAction.parameters.filter { it.required }.forEach { subParam ->
                            val subVal = nestedParams[subParam.name]
                            if (isPlaceholder(subVal, subParam)) {
                                missing.add("params.${subParam.name}")
                            }
                        }
                    }
                }
            }
        }

        return missing.distinct()
    }

    fun normalizeParams(
        params: Map<String, Any>, 
        plugin: Plugin, 
        actionName: String, 
        allPlugins: Set<Plugin>,
        userMessage: String? = null
    ): Map<String, Any> {
        val action = plugin.manifest.actions.find { it.name == actionName } ?: return params
        return params.mapValues { (key, value) ->
            if (key == "params" && value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val nested = value as Map<String, Any>
                val targetPluginId = params["plugin_id"]?.toString()
                    ?: params["pluginId"]?.toString()
                    ?: params["plugin"]?.toString() ?: ""
                val targetAction = params["action_id"]?.toString()
                    ?: params["action"]?.toString()
                    ?: params["actionId"]?.toString() ?: ""
                val targetPlugin = allPlugins.find { it.manifest.id == targetPluginId }
                return@mapValues if (targetPlugin != null && targetAction.isNotEmpty()) {
                    normalizeParams(nested, targetPlugin, targetAction, allPlugins, userMessage)
                } else nested
            }

            val paramMeta = action.parameters.find { it.name == key } ?: return@mapValues value

            var rawValue = value
            if (paramMeta.type.lowercase() == "boolean" && 
                (value.toString().isBlank() || value.toString() == "null") && 
                userMessage != null) {
                rawValue = extractBooleanFromMessage(userMessage) ?: value
            }

            paramMeta.normalize(rawValue) ?: paramMeta.defaultValue ?: ""
        }
    }

    private fun extractBooleanFromMessage(userMessage: String): Boolean? {
        val str = userMessage.lowercase()
        val trueWords = setOf("mở", "bật", "on")
        val falseWords = setOf("tắt", "off")
        val hasTrue = trueWords.any { str.contains(it) }
        val hasFalse = falseWords.any { str.contains(it) }
        return when {
            hasTrue && !hasFalse -> true
            hasFalse && !hasTrue -> false
            else -> null
        }
    }
}