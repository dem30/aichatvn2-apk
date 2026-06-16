package com.aichatvn.agent.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
// Annotation để đánh dấu plugin tự động đăng ký
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AutoPlugin

sealed class PluginResult {
    data class Success(val data: Any) : PluginResult()
    data class Failure(val error: String) : PluginResult()
    data class Ask(val question: String) : PluginResult()
    data class NeedMoreInfo(val missingParams: List<String>, val question: String) : PluginResult()
}

interface Plugin {
    val manifest: PluginManifest
    
    suspend fun initialize(context: PluginContext)
    
    suspend fun execute(action: String, params: Map<String, Any>): PluginResult
    
    suspend fun onEvent(event: PluginEvent)
    
    suspend fun shutdown()
}

// Base class để viết plugin nhanh
abstract class SimplePlugin(
    override val manifest: PluginManifest
) : Plugin {
    
    protected lateinit var context: PluginContext
    protected lateinit var eventBus: PluginEventBus
    protected val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override suspend fun initialize(context: PluginContext) {
        this.context = context
        this.eventBus = context.eventBus
        onInit()
    }
    
    override suspend fun onEvent(event: PluginEvent) {
        // Override nếu cần
    }
    
    override suspend fun shutdown() {
        // ✅ Đảm bảo cancel luôn chạy, kể cả onDestroy() throw exception
        try {
            onDestroy()
        } finally {
            pluginScope.cancel()
        }
    }
    
    open suspend fun onInit() {}
    open suspend fun onDestroy() {}
}

// Extension function để dễ dàng publish event từ plugin
suspend fun Plugin.publishEvent(eventBus: PluginEventBus, type: String, payload: Map<String, Any>) {
    eventBus.publish(PluginEvent(
        type = type,
        source = manifest.id,
        payload = payload
    ))
}