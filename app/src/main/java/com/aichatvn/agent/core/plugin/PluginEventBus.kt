package com.aichatvn.agent.core.plugin

import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

data class PluginEvent(
    val type: String,
    val source: String,
    val payload: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis()
)

data class EventResult(
    val success: Boolean,
    val error: String? = null,
    val handlerCount: Int = 0
)

interface Subscription {
    fun unsubscribe()
}

@Singleton
class PluginEventBus @Inject constructor(
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<suspend (PluginEvent) -> Unit>>()
    
    fun subscribe(eventType: String, handler: suspend (PluginEvent) -> Unit): Subscription {
        val list = subscribers.getOrPut(eventType) { CopyOnWriteArrayList() }
        list.add(handler)
        logger.d("PluginEventBus", "Subscribed to event: $eventType, total handlers: ${list.size}")
        
        return object : Subscription {
            override fun unsubscribe() {
                list.remove(handler)
                logger.d("PluginEventBus", "Unsubscribed from event: $eventType")
                
                if (list.isEmpty()) {
                    subscribers.remove(eventType)
                }
            }
        }
    }
    
    /**
     * Publish event và chờ tất cả handlers hoàn thành
     * @return EventResult với thông tin về kết quả xử lý
     */
    suspend fun publish(event: PluginEvent): EventResult {
        val handlers = subscribers[event.type] ?: return EventResult(
            success = true,
            handlerCount = 0
        )
        
        logger.d("PluginEventBus", "Publishing event: ${event.type} from ${event.source} to ${handlers.size} handlers")
        
        val deferredResults = handlers.mapIndexed { index, handler ->
            scope.async {
                try {
                    handler(event)
                    Triple(index, true, null)
                } catch (e: Exception) {
                    logger.e("PluginEventBus", "Handler $index error: ${e.message}", e)
                    Triple(index, false, e.message)
                }
            }
        }
        
        val results = deferredResults.awaitAll()
        val failures = results.filter { !it.second }
        
        if (failures.isNotEmpty()) {
            logger.w("PluginEventBus", "Event ${event.type}: ${failures.size}/${handlers.size} handlers failed")
            return EventResult(
                success = false,
                error = "${failures.size} handlers failed: ${failures.mapNotNull { it.third }.joinToString()}",
                handlerCount = handlers.size
            )
        }
        
        return EventResult(
            success = true,
            handlerCount = handlers.size
        )
    }
    
    /**
     * Publish event không chờ kết quả (fire-and-forget)
     */
    fun publishAndForget(event: PluginEvent) {
        scope.launch {
            publish(event)
        }
    }
    
    fun clearAllSubscriptions() {
        subscribers.clear()
        logger.d("PluginEventBus", "All subscriptions cleared")
    }
    
    fun getSubscriberCount(eventType: String): Int {
        return subscribers[eventType]?.size ?: 0
    }
    
    fun getAllEventTypes(): Set<String> = subscribers.keys.toSet()
}