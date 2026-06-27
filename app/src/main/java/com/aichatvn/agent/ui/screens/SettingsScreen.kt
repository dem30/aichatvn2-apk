package com.aichatvn.agent.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.work.WorkManager
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.scheduler.TaskScheduler // ✅ ĐÃ IMPORT: Sử dụng để lấy hằng số WORK_NAME chính xác của TaskScheduler
import com.aichatvn.agent.tools.ai.PromptLogEntry
import com.aichatvn.agent.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val groqApiKey       by viewModel.groqApiKey.collectAsState()
    val darkMode         by viewModel.darkMode.collectAsState()
    val resendApiKey     by viewModel.resendApiKey.collectAsState()
    val resendSender     by viewModel.resendSender.collectAsState()
    val tuyaClientId     by viewModel.tuyaClientId.collectAsState()
    val tuyaClientSecret by viewModel.tuyaClientSecret.collectAsState()
    val exportResult     by viewModel.exportResult.collectAsState()
    val allConfigs       by viewModel.allConfigs.collectAsState()
    val promptLog        by viewModel.promptLog.collectAsState()
    val configSaveResult by viewModel.configSaveResult.collectAsState()

    var groqKeyInput          by remember(groqApiKey)     { mutableStateOf(groqApiKey) }
    var resendKeyInput        by remember(resendApiKey)   { mutableStateOf(resendApiKey) }
    var resendSenderInput     by remember(resendSender)   { mutableStateOf(resendSender) }
    var tuyaClientIdInput     by remember(tuyaClientId)   { mutableStateOf(tuyaClientId) }
    var tuyaClientSecretInput by remember(tuyaClientSecret) { mutableStateOf(tuyaClientSecret) }

    var testEmailAddress  by remember { mutableStateOf("") }
    var showSaved         by remember { mutableStateOf(false) }
    var errorMessage      by remember { mutableStateOf<String?>(null) }
    var testEmailResult   by remember { mutableStateOf<String?>(null) }
    var tuyaTestResult    by remember { mutableStateOf<String?>(null) }
    var workerStatus      by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                    if (!json.isNullOrBlank()) viewModel.importSettings(context, json)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        val wm = WorkManager.getInstance(context)
        try {
            // SỬA LỖI TRA CỨU: Đồng bộ hóa mã khóa sử dụng hằng số chuẩn của lõi TaskScheduler.WORK_NAME
            val info = wm.getWorkInfosForUniqueWork(TaskScheduler.WORK_NAME).get()
            workerStatus = mapOf(
                "Smart Scan (15 phút)" to when (info.firstOrNull()?.state?.name) {
                    "ENQUEUED"  -> "⏳ Đang chờ"
                    "RUNNING"   -> "🔄 Đang chạy"
                    "SUCCEEDED" -> "✅ Thành công"
                    "FAILED"    -> "❌ Thất bại"
                    else        -> "⏸ Chưa kích hoạt"
                }
            )
        } catch (e: Exception) {
            workerStatus = mapOf("Lỗi" to "Không thể lấy trạng thái: ${e.message}")
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Cài đặt") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("⏰ Lịch quét tự động", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    workerStatus.forEach { (name, status) ->
                        Text("• $name: $status", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Camera sẽ được quét theo lịch trình này khi app chạy ngầm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Text("🤖 Groq API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = groqKeyInput,
                onValueChange = { groqKeyInput = it },
                label = { Text("Groq API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = errorMessage != null
            )
            Button(
                onClick = {
                    viewModel.testGroqConnection(groqKeyInput) { success, message ->
                        if (success) { errorMessage = null; showSaved = true }
                        else errorMessage = message
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) { Text("🔌 Kiểm tra kết nối Groq") }

            HorizontalDivider()

            Text("🔌 Tuya Smart Life", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Đăng ký miễn phí tại developer.tuya.com → tạo Cloud Project → link Smart Life.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Lấy Client ID và Client Secret từ project.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                }
            }
            OutlinedTextField(value = tuyaClientIdInput, onValueChange = { tuyaClientIdInput = it }, label = { Text("Tuya Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = tuyaClientSecretInput, onValueChange = { tuyaClientSecretInput = it }, label = { Text("Tuya Client Secret") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveTuyaConfig(tuyaClientIdInput, tuyaClientSecretInput); showSaved = true }, modifier = Modifier.weight(1f)) { Text("💾 Lưu Tuya") }
                Button(
                    onClick = { scope.launch { tuyaTestResult = viewModel.testTuyaConnection(tuyaClientIdInput, tuyaClientSecretInput) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("🔌 Test") }
            }
            if (tuyaTestResult != null) {
                Text(tuyaTestResult!!, style = MaterialTheme.typography.bodySmall,
                    color = if (tuyaTestResult!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = { navController.navigate("tuya") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔌 Quản lý thiết bị Tuya")
            }

            HorizontalDivider()

            Text("📧 Resend Email API", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Đăng ký miễn phí tại resend.com → tạo API Key → điền vào đây.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Free: 3.000 email/tháng, 100 email/ngày.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                    Text("Email gửi phải dùng domain đã xác minh trên Resend.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                }
            }
            OutlinedTextField(value = resendKeyInput, onValueChange = { resendKeyInput = it }, label = { Text("Resend API Key") }, placeholder = { Text("re_xxxxxxxxxxxxxxxxxxxx") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(value = resendSenderInput, onValueChange = { resendSenderInput = it }, label = { Text("Email gửi (From)") }, placeholder = { Text("AIChatVN <onboarding@resend.dev>") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📤 Kiểm tra gửi email", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = testEmailAddress, onValueChange = { testEmailAddress = it }, label = { Text("Email nhận test") }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ban@example.com") })
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { testEmailResult = null; viewModel.saveResendSettings(resendKeyInput, resendSenderInput); testEmailResult = viewModel.testSendEmail(testEmailAddress) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = testEmailAddress.isNotBlank() && resendKeyInput.isNotBlank()
                    ) { Text("📤 Gửi email test") }
                    if (testEmailResult != null) {
                        Text(testEmailResult!!, style = MaterialTheme.typography.bodySmall,
                            color = if (testEmailResult!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            HorizontalDivider()

            PluginConfigSection(
                configs = allConfigs,
                configSaveResult = configSaveResult,
                onSave = { key, value -> viewModel.saveConfig(key, value) },
                onReset = { key -> viewModel.resetConfig(key) }
            )

            HorizontalDivider()

            PromptLogSection(promptLog = promptLog)

            HorizontalDivider()

            Text("💾 Sao lưu cài đặt", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { viewModel.exportSettings(context) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("📤 Export") }
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("application/json") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("📥 Import") }
            }
            if (exportResult != null) {
                Text(exportResult!!, style = MaterialTheme.typography.bodySmall,
                    color = if (exportResult!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Chế độ tối", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = darkMode, onCheckedChange = { viewModel.toggleDarkMode(it) })
            }

            HorizontalDivider()

            Button(
                onClick = {
                    viewModel.saveGroqApiKey(groqKeyInput)
                    viewModel.saveResendSettings(resendKeyInput, resendSenderInput)
                    viewModel.saveTuyaConfig(tuyaClientIdInput, tuyaClientSecretInput)
                    showSaved = true; errorMessage = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("💾 Lưu tất cả cài đặt") }

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("❌ $errorMessage", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (showSaved) {
                LaunchedEffect(Unit) { delay(2000); showSaved = false }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("✅ Đã lưu!", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PluginConfigSection(
    configs: List<AppConfigEntity>,
    configSaveResult: String?,
    onSave: (String, String) -> Unit,
    onReset: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("⚙️ Cấu hình Plugin", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Thu gọn" else "Mở rộng"
            )
        }
    }

    if (!expanded) {
        Text(
            "${configs.size} biến cấu hình — bấm mũi tên để chỉnh sửa",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (configSaveResult != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(configSaveResult, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }

    val grouped = configs.groupBy { it.pluginId }
    val pluginOrder = listOf("groq", "camera", "email", "schedule", "global")
    val sortedGroups = (pluginOrder.mapNotNull { pid -> grouped[pid]?.let { pid to it } } +
        grouped.entries.filter { it.key !in pluginOrder }.map { it.toPair() })

    sortedGroups.forEach { (pluginId, items) ->
        PluginGroupCard(
            pluginId = pluginId,
            items = items,
            onSave = onSave,
            onReset = onReset
        )
    }
}

@Composable
private fun PluginGroupCard(
    pluginId: String,
    items: List<AppConfigEntity>,
    onSave: (String, String) -> Unit,
    onReset: (String) -> Unit
) {
    val icon = when (pluginId) {
        "groq"     -> "🤖"
        "camera"   -> "📷"
        "email"    -> "📧"
        "schedule" -> "⏰"
        "global"   -> "🌐"
        else       -> "🔧"
    }

    var groupExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$icon $pluginId", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { groupExpanded = !groupExpanded }) {
                    Text(if (groupExpanded) "Thu gọn" else "${items.size} biến", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (groupExpanded) {
                Spacer(Modifier.height(4.dp))
                items.forEach { entity ->
                    ConfigItemRow(entity = entity, onSave = onSave, onReset = onReset)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ConfigItemRow(
    entity: AppConfigEntity,
    onSave: (String, String) -> Unit,
    onReset: (String) -> Unit
) {
    var inputValue by remember(entity.key, entity.value) { mutableStateOf(entity.value) }
    val isDirty = inputValue != entity.value

    Column {
        if (entity.label.isNotBlank()) {
            Text(entity.label, style = MaterialTheme.typography.labelMedium)
        }
        if (entity.description.isNotBlank()) {
            Text(entity.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                modifier = Modifier.weight(1f),
                singleLine = entity.type != "string" || entity.value.length < 80,
                label = { Text(entity.key, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)) },
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    if (isDirty) {
                        IconButton(onClick = { onSave(entity.key, inputValue) }, modifier = Modifier.size(32.dp)) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
            IconButton(onClick = { onReset(entity.key); inputValue = entity.value }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset về mặc định", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        Text(
            "Loại: ${entity.type}  •  Sửa lần cuối: ${fmtTs(entity.updatedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PromptLogSection(promptLog: List<PromptLogEntry>) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("🔍 Request gửi Groq (kèm token)", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
    }

    if (!expanded) {
        Text(
            if (promptLog.isEmpty()) "Chưa có request nào trong phiên này"
            else "${promptLog.size} request gần nhất (toàn bộ nội dung gửi/nhận + token) — bấm mũi tên để xem",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (promptLog.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text("Chưa có request nào. Gửi 1 tin nhắn để xem log ở đây.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    promptLog.forEachIndexed { idx, entry ->
        PromptLogCard(index = idx + 1, entry = entry)
    }
}

@Composable
private fun PromptLogCard(index: Int, entry: PromptLogEntry) {
    val callerColor = when (entry.caller) {
        "chat"         -> MaterialTheme.colorScheme.primary
        "routeIntent"  -> MaterialTheme.colorScheme.tertiary
        "analyzeImage" -> MaterialTheme.colorScheme.secondary
        else           -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(4.dp), color = callerColor.copy(alpha = 0.15f)) {
                        Text(entry.caller, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = callerColor)
                    }
                }
                Text(fmtTs(entry.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (entry.totalTokens != null) {
                    Text(
                        "• ${entry.promptTokens ?: "?"}→${entry.completionTokens ?: "?"} = ${entry.totalTokens} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "• đang chờ / không có usage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Gửi đi:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            val promptScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .verticalScroll(promptScrollState)
                    .padding(8.dp)
            ) {
                Text(
                    text = entry.prompt,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.response != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Groq trả về:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(2.dp))
                val responseScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .verticalScroll(responseScrollState)
                        .padding(8.dp)
                ) {
                    Text(
                        text = entry.response,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun fmtTs(millis: Long): String {
    if (millis <= 0) return "—"
    return SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date(millis))
}