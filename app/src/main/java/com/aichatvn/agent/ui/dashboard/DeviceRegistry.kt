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
     *
     * ✅ MỚI: Yêu cầu truyền `pluginId` tường minh (thay vì tự suy ra từ `nodes`) vì khi thiết bị
     * cuối cùng của 1 plugin bị xoá, `nodes` truyền vào sẽ RỖNG — không còn cách nào biết cần dọn
     * node "mồ côi" (đã xoá ở DB nhưng còn sót trong bộ nhớ) thuộc plugin nào nữa. Hàm sẽ coi
     * `nodes` là danh sách ĐẦY ĐỦ và MỚI NHẤT của plugin đó, rồi xoá khỏi nodeMap mọi node cùng
     * pluginId nhưng không còn xuất hiện trong danh sách mới (vd camera/thiết bị đã bị xoá).
     */
    fun registerNodes(pluginId: String, nodes: List<DeviceNode>) {
        scope.launch {
            val incomingIds = nodes.map { it.id }.toSet()

            // ✅ MỚI: Dọn node "mồ côi" — cùng pluginId, có mặt trong bộ nhớ, nhưng không còn
            // trong danh sách mới nhất từ plugin (đã bị xoá ở DB/nguồn thật).
            val staleIds = nodeMap.values
                .filter { it.pluginId == pluginId && it.id !in incomingIds }
                .map { it.id }
            if (staleIds.isNotEmpty()) {
                staleIds.forEach { id ->
                    nodeMap.remove(id)
                    configProvider.delete("layout_x_$id")
                    configProvider.delete("layout_y_$id")
                }
            }

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
     * ✅ MỚI: Xoá NGAY LẬP TỨC 1 node khỏi Dashboard — gọi ngay sau khi người dùng xoá
     * camera/thiết bị ở tầng ViewModel, để UI cập nhật tức thời thay vì phải đợi tới lần
     * refreshDashboardNodes() kế tiếp (vd lần mở lại màn hình Dashboard).
     */
    fun unregisterNode(id: String) {
        scope.launch {
            if (nodeMap.remove(id) != null) {
                configProvider.delete("layout_x_$id")
                configProvider.delete("layout_y_$id")
                syncState()
            }
        }
    }

    /**
     * ✅ MỚI: Xoá nhiều node cùng lúc — dùng khi xoá theo lô (vd xoá 1 khách hàng kéo theo
     * xoá toàn bộ camera của khách hàng đó).
     */
    fun unregisterNodes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            var changed = false
            ids.forEach { id ->
                if (nodeMap.remove(id) != null) {
                    configProvider.delete("layout_x_$id")
                    configProvider.delete("layout_y_$id")
                    changed = true
                }
            }
            if (changed) syncState()
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