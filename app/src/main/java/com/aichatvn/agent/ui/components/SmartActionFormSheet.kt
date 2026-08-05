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

    // Reset lại toàn bộ form khi đổi Action
    LaunchedEffect(selectedAction) {
        paramValues.clear()
        paramBooleans.clear()
        selectedAction?.parameters?.forEach { p ->
            if (p.type == "boolean") paramBooleans[p.name] = false
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

    // Kiểm tra xem đã điền đủ các field bắt buộc ĐANG HIỂN THỊ chưa
    val canSave = selectedPlugin != null && selectedAction != null && visibleParameters
        .filter { it.required }
        .all { p -> p.type == "boolean" || !paramValues[p.name].isNullOrBlank() }

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
            if (param.type == "boolean") {
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
                    val finalParams = paramValues.filter { (k, _) ->
                        visibleParameters.any { it.name == k }
                    }.toMap() + paramBooleans.filter { (k, _) ->
                        visibleParameters.any { it.name == k }
                    }.mapValues { it.value.toString() }

                    onSave(AlertActionConfig(selectedPlugin!!.id, selectedAction!!.name, finalParams))
                },
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) { Text("Lưu") }
        }
    }
}