package com.aichatvn.agent.core.camera

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

private val frameSequence = AtomicLong(0L)

sealed class FrameData {
    data class ByteArrayData(val data: ByteArray) : FrameData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ByteArrayData
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }
    
    data class ByteBufferData(val buffer: ByteBuffer) : FrameData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ByteBufferData
            return buffer == other.buffer
        }
        override fun hashCode(): Int = buffer.hashCode()
    }
    
    data class ReferenceData(val reference: Any, val size: Int) : FrameData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReferenceData
            return reference == other.reference
        }
        override fun hashCode(): Int = reference.hashCode()
    }
}

data class CameraFrame(
    val id: Long = frameSequence.incrementAndGet(),
    val data: FrameData,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val cameraId: String,
    val width: Int = 0,
    val height: Int = 0,
    val format: String = "jpeg",
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraFrame
        return id == other.id
    }
    
    override fun hashCode(): Int = id.hashCode()
    
    override fun toString(): String {
        return "CameraFrame(id=$id, source='$source', cameraId='$cameraId', " +
                "timestamp=$timestamp, ${width}x$height, format='$format')"
    }
}