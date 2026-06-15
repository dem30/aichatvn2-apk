package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginRegistry @Inject constructor(
    private val logger: Logger
) {
    private val plugins = ConcurrentHashMap<String, Plugin>()
    private var isLocked = false

    fun register(plugin: Plugin) {
        if (isLocked) {
            logger.w("PluginRegistry", "Cannot register after lock - ignoring ${plugin.manifest.id}")
            return
        }
        plugins[plugin.manifest.id] = plugin
        logger.i("PluginRegistry", "Registered plugin: ${plugin.manifest.id} v${plugin.manifest.version}")
    }

    fun lock() {
        isLocked = true
        logger.i("PluginRegistry", "Locked - registered ${plugins.size} plugins: ${plugins.keys}")
    }

    fun getPlugin(id: String): Plugin? = plugins[id]

    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    fun getAllManifests(): List<PluginManifest> = plugins.values.map { it.manifest }

    fun getActionsDescription(): String {
        if (plugins.isEmpty()) return "Hiện tại chưa có plugin nào được cài đặt."
        return plugins.values.joinToString("\n") { plugin ->
            val manifest = plugin.manifest
            buildString {
                append("📦 **${manifest.id}** - ${manifest.description}\n")
                manifest.actions.forEach { action ->
                    append("   • `${action.name}`: ${action.description}\n")
                    if (action.parameters.isNotEmpty()) {
                        append("     Tham số: ${action.parameters.joinToString { "${it.name}:${it.type}" }}\n")
                    }
                }
            }
        }
    }

    suspend fun initializeAll(context: PluginContext) {
        plugins.values.forEach { plugin ->
            try {
                plugin.initialize(context)
                logger.i("PluginRegistry", "Initialized plugin: ${plugin.manifest.id}")
            } catch (e: Exception) {
                logger.e("PluginRegistry", "Failed to initialize ${plugin.manifest.id}: ${e.message}", e)
            }
        }
    }

    suspend fun shutdownAll() {
        plugins.values.forEach { plugin ->
            try {
                plugin.shutdown()
                logger.i("PluginRegistry", "Shutdown plugin: ${plugin.manifest.id}")
            } catch (e: Exception) {
                logger.e("PluginRegistry", "Failed to shutdown ${plugin.manifest.id}: ${e.message}", e)
            }
        }
    }
}