package com.aichatvn.agent.ui.dashboard

import com.aichatvn.agent.config.AppConfigProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRegistry @Inject constructor(
    private val configProvider: AppConfigProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
     * Đã nâng cấp: Ưu tiên giữ lại tọa độ đang hiển thị trực quan trong bộ nhớ, tránh bị dồn cục khi refresh.
     */
    fun registerNodes(nodes: List<DeviceNode>) {
        scope.launch {
            nodes.forEach { node ->
                val existing = nodeMap[node.id]
                val finalNode = if (existing != null) {
                    // Nếu node đang có sẵn trên màn hình, giữ nguyên tọa độ của nó
                    node.copy(x = existing.x, y = existing.y)
                } else {
                    // Nếu là node mới chưa có trong bộ nhớ, thử nạp lại tọa độ từ DB
                    val savedXStr = configProvider.getString("layout_x_${node.id}", "")
                    val savedYStr = configProvider.getString("layout_y_${node.id}", "")
                    val savedX = savedXStr.toFloatOrNull()
                    val savedY = savedYStr.toFloatOrNull()

                    if (savedX != null && savedY != null) {
                        node.copy(x = savedX, y = savedY)
                    } else {
                        node
                    }
                }
                nodeMap[node.id] = finalNode
            }
            syncState()
        }
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
     * Cập nhật tọa độ di chuyển mới của Node và lưu bất đồng bộ vào cơ sở dữ liệu
     */
    fun updateNodeAndPersist(id: String, transform: (DeviceNode) -> DeviceNode) {
        val currentNode = nodeMap[id] ?: return
        val updatedNode = transform(currentNode)
        nodeMap[id] = updatedNode
        syncState()

        scope.launch {
            configProvider.set("layout_x_$id", updatedNode.x.toString())
            configProvider.set("layout_y_$id", updatedNode.y.toString())
        }
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