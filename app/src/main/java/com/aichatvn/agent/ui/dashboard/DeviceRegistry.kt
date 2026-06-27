package com.aichatvn.agent.ui.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRegistry @Inject constructor() {

    private val _deviceNodes = MutableStateFlow<List<DeviceNode>>(emptyList())
    /**
     * StateFlow thời gian thực lưu trữ bản sao số của toàn bộ thiết bị trong nhà.
     * UI Dashboard chỉ cần đăng ký Flow này để tự recompose tức thời khi có sự kiện đẩy về.
     */
    val deviceNodes: StateFlow<List<DeviceNode>> = _deviceNodes.asStateFlow()

    // Bộ đệm nguyên tử bảo vệ ghi dữ liệu song song đa luồng từ nhiều Plugin/Hardware cùng lúc
    private val nodeMap = ConcurrentHashMap<String, DeviceNode>()

    /**
     * Đăng ký lô danh sách node ban đầu (gọi lúc khởi động plugin hoặc khi quét mạng)
     */
    fun registerNodes(nodes: List<DeviceNode>) {
        nodes.forEach { node ->
            nodeMap[node.id] = node
        }
        syncState()
    }

    /**
     * Cập nhật trạng thái chi tiết của thiết bị theo thời gian thực (được gọi từ Plugin khi điều khiển thành công)
     */
    fun updateNode(id: String, transform: (DeviceNode) -> DeviceNode) {
        val currentNode = nodeMap[id] ?: return
        val updatedNode = transform(currentNode)
        nodeMap[id] = updatedNode
        syncState()
    }

    /**
     * Cập nhật nhanh trạng thái trực tuyến / ngoại tuyến của thiết bị
     */
    fun updateOnlineStatus(id: String, online: Boolean, rssi: Int? = null, status: String? = null) {
        updateNode(id) { current ->
            current.copy(
                online = online,
                rssi = rssi ?: current.rssi,
                status = status ?: current.status,
                lastSeen = System.currentTimeMillis()
            )
        }
    }

    private fun syncState() {
        _deviceNodes.update { nodeMap.values.toList().sortedBy { it.id } }
    }
}