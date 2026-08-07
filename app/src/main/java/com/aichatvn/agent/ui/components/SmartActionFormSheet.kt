// File: com/aichatvn/agent/ui/components/SmartActionFormSheet.kt

package com.aichatvn.agent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichatvn.agent.core.plugin.DynamicOptionRegistry
import com.aichatvn.agent.core.plugin.OptionItem
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.data.model.AlertActionConfig
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartActionFormSheet(
    plugins: List<Plugin>,
    optionRegistry: DynamicOptionRegistry,
    onSave: (AlertActionConfig) -> Unit,
    onCancel: () -> Unit
) {
    var pluginExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }
    var selectedPlugin by remember { mutableStateOf<Plugin?>(null) }
    var selectedAction by remember { mutableStateOf<PluginAction?>(null) }
    
    val paramValues = remember { mutableStateMapOf<String, String>() }
    val paramBooleans = remember { mutableStateMapOf<String, Boolean>() }

    // ✅ MỚI: khi action đang chọn là kiểu "wrapper" (vd schedule.add/update — có 1 tham số
    // semanticType="params" đi kèm 2 tham số semanticType="plugin_id"/"action_id"), form
    // trước đây chỉ hiện đúng field "params" thô (object) vì nó chỉ lặp qua
    // selectedAction.parameters — không biết gì về schema thật của plugin/action ĐÍCH bên
    // trong. Đây là lý do khác biệt với ScheduleScreen: ScheduleScreen cho chọn thẳng
    // Plugin/Action đích (selectedAction = action đích luôn), còn ở đây selectedAction luôn là
    // action "add" của schedule, nên phải tự resolve thêm 1 tầng để lấy đúng schema tham số.
    val paramsFieldName = selectedAction?.parameters
        ?.find { it.type == "object" && it.semanticType == "params" }?.name
    val pluginIdFieldName = selectedAction?.parameters
        ?.find { it.semanticType == "plugin_id" }?.name
    val actionIdFieldName = selectedAction?.parameters
        ?.find { it.semanticType == "action_id" }?.name

    val targetPlugin = pluginIdFieldName?.let { name -> plugins.find { it.id == paramValues[name] } }
    val targetAction = actionIdFieldName?.let { name ->
        targetPlugin?.getActions()?.find { it.name == paramValues[name] }
    }

    // Tham số của action ĐÍCH (vd action đích là "email.send") — tách riêng khỏi paramValues
    // chính vì đây là 1 tầng schema khác hẳn, tránh đụng tên trùng tham số giữa 2 tầng.
    val nestedParamValues = remember { mutableStateMapOf<String, String>() }
    val nestedParamBooleans = remember { mutableStateMapOf<String, Boolean>() }

    // Reset lại toàn bộ form khi đổi Action
    LaunchedEffect(selectedAction) {
        paramValues.clear()
        paramBooleans.clear()
        selectedAction?.parameters?.forEach { p ->
            if (p.type == "boolean") paramBooleans[p.name] = false
        }
    }

    // ✅ MỚI: reset tầng tham số lồng bên trong mỗi khi đổi Action đích (vd đổi từ
    // "email.send" sang "call.start")
    LaunchedEffect(targetAction) {
        nestedParamValues.clear()
        nestedParamBooleans.clear()
        targetAction?.parameters?.forEach { p ->
            if (p.type == "boolean") nestedParamBooleans[p.name] = false
        }
    }

    // ✅ FIX: Xoá TOÀN BỘ hậu duệ (con, cháu, chắt...) khi 1 tham số cha đổi giá trị — không chỉ
    // con trực tiếp. Trước đây chỉ xoá con cấp 1 (vd đổi "source" chỉ xoá "attribute"/"device"),
    // khiến "expected" (cháu, dependsOn="attribute") giữ lại giá trị CŨ/rác từ ngữ cảnh trước đó
    // dù đang bị ẩn, rồi khi "attribute" được chọn lại, "expected" hiện lại mang giá trị sai lệch
    // mà không bị "required" bắt lỗi (paramValues[expected] không rỗng, dù không hợp lệ nữa).
    // Đây chính là nguyên nhân bước "check_precondition" từng bị lưu với expected="" / sai lệch.
    fun clearDescendants(paramName: String) {
        selectedAction?.parameters
            ?.filter { it.dependsOn == paramName }
            ?.forEach { child ->
                paramValues.remove(child.name)
                paramBooleans.remove(child.name)
                clearDescendants(child.name)
            }
    }

    // ✅ MỚI: bản sao clearDescendants ở tầng tham số lồng (action đích), cùng lý do như bản
    // gốc ở trên nhưng áp dụng cho targetAction.parameters + nestedParamValues.
    fun clearNestedDescendants(paramName: String) {
        targetAction?.parameters
            ?.filter { it.dependsOn == paramName }
            ?.forEach { child ->
                nestedParamValues.remove(child.name)
                nestedParamBooleans.remove(child.name)
                clearNestedDescendants(child.name)
            }
    }

    // 🌟 LỌC THAM SỐ THÔNG MINH: Lọc ra danh sách tham số ĐỦ ĐIỀU KIỆN HIỂN THỊ
    val visibleParameters = remember(selectedAction, paramValues.toMap()) {
        selectedAction?.parameters?.filter { param ->
            if (param.dependsOn == null) {
                true
            } else {
                val parentValue = paramValues[param.dependsOn]
                if (parentValue.isNullOrBlank()) {
                    false
                } else if (param.visibleWhen != null) {
                    param.visibleWhen.contains(parentValue)
                } else {
                    true
                }
            }
        } ?: emptyList()
    }

    // ✅ MỚI: cùng bộ lọc dependsOn/visibleWhen nhưng áp dụng cho schema của action ĐÍCH —
    // đây chính là phần trước đây bị thiếu khiến "params" luôn hiện dạng field trống thay vì
    // các field con thật sự (recipient, subject, body...) như bên ScheduleScreen.
    val nestedVisibleParameters = remember(targetAction, nestedParamValues.toMap()) {
        targetAction?.parameters?.filter { param ->
            if (param.dependsOn == null) {
                true
            } else {
                val parentValue = nestedParamValues[param.dependsOn]
                if (parentValue.isNullOrBlank()) {
                    false
                } else if (param.visibleWhen != null) {
                    param.visibleWhen.contains(parentValue)
                } else {
                    true
                }
            }
        } ?: emptyList()
    }

    // Kiểm tra xem đã điền đủ các field bắt buộc ĐANG HIỂN THỊ chưa (cả tầng "add lịch" lẫn
    // tầng action đích bên trong, nếu đã chọn được action đích)
    val canSave = selectedPlugin != null && selectedAction != null && visibleParameters
        .filter { it.required }
        .all { p -> p.type == "boolean" || !paramValues[p.name].isNullOrBlank() } &&
        (paramsFieldName == null || targetAction == null || nestedVisibleParameters
            .filter { it.required }
            .all { p -> p.type == "boolean" || !nestedParamValues[p.name].isNullOrBlank() })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Thêm hành động tự động", style = MaterialTheme.typography.titleMedium)

        // 1. Dropdown chọn Plugin
        ExposedDropdownMenuBox(
            expanded = pluginExpanded,
            onExpandedChange = { pluginExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedPlugin?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Plugin") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pluginExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(8.dp)
            )
            ExposedDropdownMenu(
                expanded = pluginExpanded,
                onDismissRequest = { pluginExpanded = false }
            ) {
                plugins.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            selectedPlugin = p
                            selectedAction = null
                            pluginExpanded = false
                        }
                    )
                }
            }
        }

        // 2. Dropdown chọn Action
        selectedPlugin?.let { plugin ->
            ExposedDropdownMenuBox(
                expanded = actionExpanded,
                onExpandedChange = { actionExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedAction?.let { "${it.name} — ${it.description}" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Hành động") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(8.dp)
                )
                ExposedDropdownMenu(
                    expanded = actionExpanded,
                    onDismissRequest = { actionExpanded = false }
                ) {
                    plugin.getActions().forEach { act ->
                        DropdownMenuItem(
                            text = { Text("${act.name} — ${act.description}") },
                            onClick = {
                                selectedAction = act
                                actionExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // 3. Render danh sách Tham số đã qua bộ lọc thông minh (visibleParameters)
        visibleParameters.forEach { param ->
            // ✅ MỚI: đây là field đang bị "trơ" trong ảnh chụp màn hình — param "params"
            // (type=object, semanticType="params") của action wrapper (vd schedule.add) không
            // tự nó biết field nào cần điền, nó chỉ là 1 cái hộp chứa tham số của action ĐÍCH.
            // Trước đây field này render như 1 TextField object trống, buộc người dùng gõ tay
            // JSON thô. Giờ nếu đã xác định được targetAction (nhờ pluginIdFieldName/
            // actionIdFieldName ở trên), render thẳng các field thật của action đó — đúng như
            // cách ScheduleScreen làm khi người dùng chọn thẳng Plugin/Action đích.
            if (param.type == "object" && param.semanticType == "params") {
                if (targetAction == null) {
                    Text(
                        "Chọn \"${param.description}\" — cần chọn đủ chức năng và hành động cụ thể ở trên trước",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Tham số cho \"${targetAction.name}\"",
                        style = MaterialTheme.typography.labelMedium
                    )
                    nestedVisibleParameters.forEach { nParam ->
                        if (nParam.type == "boolean") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${nParam.description}${if (nParam.required) " *" else ""}")
                                Switch(
                                    checked = nestedParamBooleans[nParam.name] ?: false,
                                    onCheckedChange = { nestedParamBooleans[nParam.name] = it }
                                )
                            }
                        } else {
                            var nOptions by remember(nParam.name) { mutableStateOf<List<OptionItem>>(emptyList()) }

                            LaunchedEffect(nParam.semanticType, nestedParamValues.toMap()) {
                                nOptions = optionRegistry.getOptions(nParam.semanticType, nestedParamValues.toMap())
                            }

                            if (nOptions.isNotEmpty()) {
                                var nDropdownExpanded by remember { mutableStateOf(false) }
                                val nCurrentValue = nestedParamValues[nParam.name] ?: ""
                                val nCurrentLabel = nOptions.find { it.value == nCurrentValue }?.label ?: nCurrentValue

                                ExposedDropdownMenuBox(
                                    expanded = nDropdownExpanded,
                                    onExpandedChange = { nDropdownExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = nCurrentLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("${nParam.description}${if (nParam.required) " *" else ""}") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nDropdownExpanded) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = nDropdownExpanded,
                                        onDismissRequest = { nDropdownExpanded = false }
                                    ) {
                                        nOptions.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text(item.label) },
                                                onClick = {
                                                    val oldVal = nestedParamValues[nParam.name]
                                                    nestedParamValues[nParam.name] = item.value
                                                    if (oldVal != item.value) {
                                                        clearNestedDescendants(nParam.name)
                                                    }
                                                    nDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = nestedParamValues[nParam.name] ?: "",
                                    onValueChange = { nestedParamValues[nParam.name] = it },
                                    label = { Text("${nParam.description}${if (nParam.required) " *" else ""}") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            } else if (param.type == "boolean") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${param.description}${if (param.required) " *" else ""}")
                    Switch(
                        checked = paramBooleans[param.name] ?: false,
                        onCheckedChange = { paramBooleans[param.name] = it }
                    )
                }
            } else {
                // Tự động load danh sách Option từ DynamicOptionRegistry
                var options by remember { mutableStateOf<List<OptionItem>>(emptyList()) }

                LaunchedEffect(param.semanticType, paramValues.toMap()) {
                    options = optionRegistry.getOptions(param.semanticType, paramValues.toMap())
                }

                if (options.isNotEmpty()) {
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    val currentValue = paramValues[param.name] ?: ""
                    val currentLabel = options.find { it.value == currentValue }?.label ?: currentValue

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("${param.description}${if (param.required) " *" else ""}") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            options.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        val oldVal = paramValues[param.name]
                                        paramValues[param.name] = item.value

                                        // ✅ FIX: dùng clearDescendants() để reset đệ quy toàn bộ
                                        // hậu duệ (không chỉ con trực tiếp) khi cha đổi giá trị.
                                        if (oldVal != item.value) {
                                            clearDescendants(param.name)
                                        }
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Nếu không có Option Dropdown -> Render ô TextField bình thường
                    OutlinedTextField(
                        value = paramValues[param.name] ?: "",
                        onValueChange = { paramValues[param.name] = it },
                        label = { Text("${param.description}${if (param.required) " *" else ""}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        // 4. Các nút Lưu / Hủy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Huỷ") }
            Button(
                onClick = {
                    // ✅ MỚI: đóng gói các field lồng (đã thu thập ở trên) thành đúng JSON mà
                    // action đích mong đợi trong "params", thay vì gửi đi field "params" trống.
                    val nestedJson = if (targetAction != null && paramsFieldName != null) {
                        JSONObject().apply {
                            nestedVisibleParameters.forEach { np ->
                                when (np.type) {
                                    "boolean" -> put(np.name, nestedParamBooleans[np.name] ?: false)
                                    "number" -> nestedParamValues[np.name]?.toDoubleOrNull()?.let { put(np.name, it) }
                                    else -> nestedParamValues[np.name]?.trim()?.takeIf { it.isNotBlank() }?.let { put(np.name, it) }
                                }
                            }
                        }.toString()
                    } else null

                    val finalParams = paramValues.filter { (k, _) ->
                        visibleParameters.any { it.name == k } && k != paramsFieldName
                    }.toMap() + paramBooleans.filter { (k, _) ->
                        visibleParameters.any { it.name == k }
                    }.mapValues { it.value.toString() } +
                        (nestedJson?.let { mapOf((paramsFieldName ?: "params") to it) } ?: emptyMap())

                    onSave(AlertActionConfig(selectedPlugin!!.id, selectedAction!!.name, finalParams))
                },
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) { Text("Lưu") }
        }
    }
}