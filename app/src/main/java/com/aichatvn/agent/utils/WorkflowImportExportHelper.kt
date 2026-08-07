// File: com/aichatvn/agent/utils/WorkflowImportExportHelper.kt
package com.aichatvn.agent.utils

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.CameraConfigEntity
import com.aichatvn.agent.data.model.HouseMood
import com.aichatvn.agent.data.model.TuyaDeviceEntity
import com.aichatvn.agent.data.model.displayName
import com.aichatvn.agent.skills.WorkflowGroup
import com.aichatvn.agent.skills.workflowGroupsFromJson
import org.json.JSONArray
import java.util.UUID

// ✅ MỚI (Nhập kịch bản qua AI ngoài): Cầu nối 2 chiều giữa "Nhóm kịch bản" (WorkflowGroup) và
// một AI bên ngoài (ChatGPT/Claude/...) — cho phép chủ nhà mô tả kịch bản bằng lời với AI ngoài,
// rồi dán JSON AI trả về vào app để tạo kịch bản, thay vì phải tự hiểu VisualTriggerBuilderDialog.
//
// ✅ ĐÃ XÁC NHẬN (đối chiếu Plugin.kt thật): field định danh của PluginParameter là "name" —
// dùng "param.name" xuyên suốt bên dưới (bản đầu từng giả định nhầm là "key", nay đã sửa).
object WorkflowImportExportHelper {

    data class ValidationError(
        val groupIndex: Int,   // -1 nếu lỗi ở cấp toàn bộ JSON (không thuộc riêng nhóm nào)
        val groupLabel: String,
        val message: String
    )

    data class ValidationResult(
        val validGroups: List<WorkflowGroup>,
        val errors: List<ValidationError>
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
    }

    // ───────────────────────── XUẤT NGỮ CẢNH CHO AI NGOÀI ─────────────────────────

    /**
     * Build 1 khối văn bản chứa: định dạng JSON cần trả về, 4 loại ngòi nổ hợp lệ, danh sách
     * camera/thiết bị Tuya THẬT của nhà (để AI không tự bịa ID), danh sách plugin/hành động khả
     * dụng, và 1 ví dụ minh hoạ dựng từ dữ liệu thật (nếu có). Chủ nhà copy khối này dán cho AI
     * ngoài kèm mô tả kịch bản mong muốn bằng lời.
     */
    fun buildAiContext(
        cameras: List<CameraConfigEntity>,
        tuyaDevices: List<TuyaDeviceEntity>,
        plugins: List<Plugin>
    ): String = buildString {
        appendLine("Bạn là trợ lý giúp tạo \"kịch bản tự động\" cho app nhà thông minh AIChatVN2.")
        appendLine("Đọc kỹ dữ liệu bên dưới, sau đó CHỈ trả lời bằng 1 JSON array đúng định dạng —")
        appendLine("KHÔNG kèm giải thích, KHÔNG bọc trong dấu ```.")
        appendLine()
        appendLine("=== ĐỊNH DẠNG JSON CẦN TRẢ VỀ ===")
        appendLine(
            """
            [
              {
                "id": "",
                "label": "Tên kịch bản do bạn đặt, ví dụ: Khoá cửa khi cả nhà đi ngủ",
                "triggerSource": "nguon.id.thuoctinh=gia_tri",
                "enabled": true,
                "steps": [
                  { "pluginId": "id_plugin_that_o_duoi", "action": "ten_hanh_dong_that_o_duoi", "params": { "ten_tham_so": "gia_tri" } }
                ]
              }
            ]
            """.trimIndent()
        )
        appendLine()
        appendLine("=== 4 LOẠI NGÒI NỔ (triggerSource) HỢP LỆ — chỉ được dùng 1 trong 4 dạng sau ===")
        appendLine("1. Camera phát hiện bất thường: \"camera.<id_camera_that>.state=suspicious\"")
        appendLine("2. Thiết bị Tuya đổi trạng thái: \"tuya.<id_thiet_bi_that>.state=true\" (true=bật) hoặc \"=false\" (tắt)")
        appendLine("3. Khách nhắn tin chứa từ khoá: \"chat.*.keyword=tu_khoa_1,tu_khoa_2\"")
        appendLine(
            "4. Chế độ nhà đổi thành: \"system.brain.mood=GIA_TRI\" — GIA_TRI phải là 1 trong: " +
                HouseMood.entries.joinToString(", ") { it.name }
        )
        appendLine()
        appendLine("=== CAMERA THẬT CỦA NHÀ NÀY (chỉ được dùng đúng id liệt kê dưới đây, không tự bịa) ===")
        if (cameras.isEmpty()) {
            appendLine("(Nhà này chưa có camera nào — không tạo ngòi nổ camera)")
        } else {
            cameras.forEach { appendLine("- id=\"${it.id.trim()}\" tên=\"${it.customername.ifBlank { "(chưa đặt tên)" }}\"") }
        }
        appendLine()
        appendLine("=== THIẾT BỊ TUYA THẬT CỦA NHÀ NÀY (chỉ được dùng đúng id liệt kê dưới đây, không tự bịa) ===")
        if (tuyaDevices.isEmpty()) {
            appendLine("(Nhà này chưa có thiết bị Tuya nào — không tạo ngòi nổ/bước tuya)")
        } else {
            tuyaDevices.forEach { appendLine("- id=\"${it.id.trim()}\" tên=\"${it.name}\"") }
        }
        appendLine()
        appendLine("=== PLUGIN / HÀNH ĐỘNG KHẢ DỤNG CHO \"steps\" (chỉ dùng đúng pluginId/action liệt kê dưới đây) ===")
        val routableActions = plugins.filter { it.manifest.routable }
            .flatMap { plugin -> plugin.manifest.actions.filter { it.enabled }.map { plugin to it } }
        if (routableActions.isEmpty()) {
            appendLine("(Không tìm thấy plugin/hành động nào khả dụng)")
        } else {
            routableActions.forEach { (plugin, action) ->
                val paramsText = action.parameters.joinToString(", ") { p ->
                    val enumText = if (p.enumValues.isNotEmpty()) ", giá trị hợp lệ: ${p.enumValues.joinToString("/")}" else ""
                    "${p.name}(${p.type}${if (p.required) ", bắt buộc" else ", tuỳ chọn"}$enumText)"
                }
                val preconditionText = if (action.requiredWorldState.isNotBlank()) {
                    " [Chỉ dùng được khi: ${action.requiredWorldState}]"
                } else ""
                appendLine(
                    "- pluginId=\"${plugin.manifest.id}\" action=\"${action.name}\" — ${action.description}." +
                        " Tham số: ${paramsText.ifBlank { "(không có)" }}$preconditionText"
                )
            }
        }
        appendLine()
        appendLine("=== VÍ DỤ MINH HOẠ (dựng từ đúng dữ liệu thật của nhà này ở trên) ===")
        val exampleAction = routableActions.firstOrNull()
        if (exampleAction == null) {
            appendLine("(Chưa có plugin nào để làm ví dụ — cứ theo đúng định dạng JSON ở trên)")
        } else {
            val (plugin, action) = exampleAction
            val exampleParams = action.parameters.associate { it.name to "<${it.name}>" }
            val exampleJson = buildString {
                append("[{\"id\":\"\",\"label\":\"Ví dụ kịch bản\",")
                append("\"triggerSource\":\"system.brain.mood=SLEEPING\",\"enabled\":true,")
                append("\"steps\":[{\"pluginId\":\"${plugin.manifest.id}\",\"action\":\"${action.name}\",")
                append("\"params\":{")
                append(exampleParams.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" })
                append("}}]}]")
            }
            appendLine(exampleJson)
        }
    }

    // ───────────────────────── NHẬP + VALIDATE JSON DÁN VÀO ─────────────────────────

    /**
     * Validate JSON do người dùng dán vào (thường là do AI ngoài sinh ra) trước khi cho lưu vào
     * danh sách kịch bản thật. KHÔNG tin tưởng mù quáng bất kỳ trường nào trong JSON — mọi
     * id camera/thiết bị/plugin/action đều phải khớp với dữ liệu thật đang có của nhà đó.
     */
    fun validate(
        json: String,
        cameras: List<CameraConfigEntity>,
        tuyaDevices: List<TuyaDeviceEntity>,
        plugins: List<Plugin>
    ): ValidationResult {
        val trimmed = json.trim()
        if (trimmed.isBlank()) {
            return ValidationResult(emptyList(), listOf(ValidationError(-1, "", "Chưa dán nội dung nào.")))
        }

        // ✅ Parse thô bằng JSONArray trước để phân biệt "JSON sai cú pháp" với "JSON hợp lệ nhưng
        // rỗng" — workflowGroupsFromJson() tự nuốt mọi exception và trả về emptyList() cho cả 2
        // trường hợp, nên không dùng trực tiếp để báo lỗi cú pháp cho người dùng được.
        try {
            JSONArray(trimmed)
        } catch (e: Exception) {
            return ValidationResult(
                emptyList(),
                listOf(ValidationError(-1, "", "JSON không đọc được — kiểm tra lại dấu ngoặc/dấu nháy. Chi tiết: ${e.message}"))
            )
        }

        val parsedGroups = workflowGroupsFromJson(trimmed)
        if (parsedGroups.isEmpty()) {
            return ValidationResult(emptyList(), listOf(ValidationError(-1, "", "Danh sách kịch bản rỗng — không có gì để nhập.")))
        }

        val cameraIds = cameras.map { it.id.trim() }.toSet()
        val tuyaIds = tuyaDevices.map { it.id.trim() }.toSet()
        val moodNames = HouseMood.entries.map { it.name }.toSet()
        val pluginById = plugins.associateBy { it.manifest.id }

        val validGroups = mutableListOf<WorkflowGroup>()
        val errors = mutableListOf<ValidationError>()

        parsedGroups.forEachIndexed { index, group ->
            val groupErrors = mutableListOf<String>()

            if (group.label.isBlank()) {
                groupErrors.add("thiếu tên kịch bản (label)")
            }

            val condition = WorldStateHelper.parseCondition(group.triggerSource)
            if (condition == null) {
                groupErrors.add(
                    "ngòi nổ (triggerSource) sai định dạng: \"${group.triggerSource}\"" +
                        " — phải theo dạng nguon.id.thuoctinh=gia_tri"
                )
            } else {
                when (condition.source) {
                    "camera" -> if (condition.sourceId !in cameraIds) {
                        groupErrors.add("camera \"${condition.sourceId}\" không tồn tại trong danh sách camera thật của nhà")
                    }
                    "tuya" -> if (condition.sourceId !in tuyaIds) {
                        groupErrors.add("thiết bị Tuya \"${condition.sourceId}\" không tồn tại trong danh sách thiết bị thật của nhà")
                    }
                    "system" -> if (condition.sourceId == "brain" && condition.attrKey == "mood" && condition.expected !in moodNames) {
                        groupErrors.add(
                            "giá trị Chế độ nhà \"${condition.expected}\" không hợp lệ — phải là 1 trong: " +
                                moodNames.joinToString(", ")
                        )
                    }
                    "chat" -> if (condition.expected.isBlank()) {
                        groupErrors.add("ngòi nổ chat thiếu từ khoá kích hoạt")
                    }
                }
            }

            if (group.steps.isEmpty()) {
                groupErrors.add("kịch bản không có bước hành động nào — sẽ không làm gì khi kích hoạt")
            }

            group.steps.forEachIndexed { stepIndex, step ->
                val plugin = pluginById[step.pluginId]
                if (plugin == null) {
                    groupErrors.add("bước ${stepIndex + 1}: plugin \"${step.pluginId}\" không tồn tại")
                    return@forEachIndexed
                }
                val action = plugin.manifest.actions.find { it.name == step.action && it.enabled }
                if (action == null) {
                    groupErrors.add("bước ${stepIndex + 1}: hành động \"${step.action}\" không tồn tại hoặc đang tắt trong plugin \"${step.pluginId}\"")
                    return@forEachIndexed
                }
                // Tham số có dependsOn chỉ bắt buộc khi điều kiện hiển thị (visibleWhen) khớp — bỏ
                // qua kiểm tra cứng cho các tham số phụ thuộc để tránh báo lỗi sai (false positive).
                action.parameters.filter { it.dependsOn == null }.forEach { param ->
                    val value = step.params[param.name]
                    if (param.required && value.isNullOrBlank()) {
                        groupErrors.add("bước ${stepIndex + 1} (\"${action.name}\"): thiếu tham số bắt buộc \"${param.name}\"")
                    } else if (!value.isNullOrBlank() && param.enumValues.isNotEmpty() && value !in param.enumValues) {
                        groupErrors.add(
                            "bước ${stepIndex + 1} (\"${action.name}\"): tham số \"${param.name}\" = \"$value\"" +
                                " không hợp lệ — phải là 1 trong: ${param.enumValues.joinToString(", ")}"
                        )
                    }
                }
            }

            if (groupErrors.isEmpty()) {
                // Không tin ID do AI tự đặt (có thể trùng kịch bản đã có sẵn) — luôn cấp ID mới.
                validGroups.add(group.copy(id = "wf_" + UUID.randomUUID().toString().take(8)))
            } else {
                errors.add(ValidationError(index, group.label.ifBlank { "(chưa đặt tên)" }, groupErrors.joinToString("; ")))
            }
        }

        return ValidationResult(validGroups, errors)
    }

    // ───────────────────────── TÓM TẮT DỄ HIỂU ĐỂ XÁC NHẬN TRƯỚC KHI LƯU ─────────────────────────

    /** 1 dòng tóm tắt tiếng Việt dễ hiểu cho mỗi nhóm hợp lệ, hiện ra để chủ nhà xác nhận trước khi lưu. */
    fun summarizeGroupsForPreview(
        groups: List<WorkflowGroup>,
        cameras: List<CameraConfigEntity>,
        tuyaDevices: List<TuyaDeviceEntity>,
        plugins: List<Plugin>
    ): List<String> {
        val cameraNameById = cameras.associate { it.id.trim() to it.customername.ifBlank { it.id } }
        val tuyaNameById = tuyaDevices.associate { it.id.trim() to it.name }
        val pluginById = plugins.associateBy { it.manifest.id }

        return groups.map { group ->
            val condition = WorldStateHelper.parseCondition(group.triggerSource)
            val triggerText = when {
                condition == null -> "(ngòi nổ không hợp lệ)"
                condition.source == "camera" -> "Camera ${cameraNameById[condition.sourceId] ?: condition.sourceId} phát hiện \"${condition.expected}\""
                condition.source == "tuya" -> "Thiết bị ${tuyaNameById[condition.sourceId] ?: condition.sourceId} chuyển sang \"${condition.expected}\""
                condition.source == "chat" -> "Khách nhắn tin chứa từ khoá: ${condition.expected}"
                condition.source == "system" && condition.attrKey == "mood" -> {
                    val mood = HouseMood.entries.find { it.name == condition.expected }
                    "Chế độ nhà chuyển sang ${mood?.displayName() ?: condition.expected}"
                }
                else -> group.triggerSource
            }

            val stepsText = if (group.steps.isEmpty()) {
                "(chưa có bước hành động nào)"
            } else {
                group.steps.joinToString(", ") { step ->
                    val action = pluginById[step.pluginId]?.manifest?.actions?.find { it.name == step.action }
                    action?.description ?: "${step.pluginId}.${step.action}"
                }
            }

            "\"${group.label}\": Khi $triggerText → $stepsText"
        }
    }
}