package com.aichatvn.agent.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventBus @Inject constructor() {
    
    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()
    
    suspend fun emit(event: Event) {
        _events.emit(event)
    }
}

sealed class Event {
    data class CameraScanned(val cameraId: String, val hasChange: Boolean, val analysis: String?) : Event()
    data class AlertDetected(val cameraId: String, val message: String, val imageBytes: ByteArray?) : Event()
    data class QaAdded(val id: String, val question: String) : Event()
    data class QaDeleted(val id: String) : Event()
    data class SyncCompleted(val success: Boolean, val message: String) : Event()
    data class ErrorOccurred(val error: String) : Event()
}