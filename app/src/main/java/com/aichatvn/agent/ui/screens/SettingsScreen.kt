package com.aichatvn.agent.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.data.model.FacebookPageEntity // ✅ ĐÃ THÊM: Thực thể trang Facebook
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
    val facebookPages    by viewModel.facebookPages.collectAsState() // ✅ ĐÃ THÊM: Quan sát danh sách trang từ DB

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

    Scaffold(topBar = { TopAppBar(title = { Text("Cấu hình hệ thống") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("🤖 Groq AI Cloud Services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = groqKeyInput,
                onValueChange = { groqKeyInput = it },
                label = { Text("Groq API Key (Khóa kết nối)") },
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

            Text("🔌 Thiết lập nhà thông minh Tuya Smart Life", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Đăng ký tài khoản miễn phí tại developer.tuya.com → Tạo Cloud Project → Liên kết app Smart Life.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Lấy Client ID và Client Secret từ project để điền xuống phía dưới.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                }
            }
            OutlinedTextField(value = tuyaClientIdInput, onValueChange = { tuyaClientIdInput = it }, label = { Text("Tuya Access ID / Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = tuyaClientSecretInput, onValueChange = { tuyaClientSecretInput = it }, label = { Text("Tuya Access Secret / Client Secret") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveTuyaConfig(tuyaClientIdInput, tuyaClientSecretInput); showSaved = true }, modifier = Modifier.weight(1f)) { Text("💾 Lưu cấu hình Tuya") }
                Button(
                    onClick = { scope.launch { tuyaTestResult = viewModel.testTuyaConnection(tuyaClientIdInput, tuyaClientSecretInput) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("🔌 Thử kết nối") }
            }
            if (tuyaTestResult != null) {
                Text(tuyaTestResult!!, style = MaterialTheme.typography.bodySmall,
                    color = if (tuyaTestResult!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = { navController.navigate("tuya") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔌 Quản lý danh sách thiết bị Tuya")
            }

            HorizontalDivider()

            Text("📧 Cấu hình gửi Mail cảnh báo bằng Resend.com", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Đăng ký tại resend.com → Tạo API Key → Điền khóa của bạn vào đây.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("Tài khoản Free: Giới hạn gửi 3.000 email/tháng, tối đa 100 email/ngày.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                    Text("Địa chỉ gửi đi (From) bắt buộc phải dùng domain đã xác minh (Verify) trên Resend.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                }
            }
            OutlinedTextField(value = resendKeyInput, onValueChange = { resendKeyInput = it }, label = { Text("Resend API Key") }, placeholder = { Text("re_xxxxxxxxxxxxxxxxxxxx") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(value = resendSenderInput, onValueChange = { resendSenderInput = it }, label = { Text("Địa chỉ Email gửi đi (From)") }, placeholder = { Text("AIChatVN <onboarding@resend.dev>") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📤 Thử nghiệm tính năng gửi Email", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = testEmailAddress, onValueChange = { testEmailAddress = it }, label = { Text("Email nhận thử nghiệm") }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("ban@example.com") })
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { testEmailResult = null; viewModel.saveResendSettings(resendKeyInput, resendSenderInput); testEmailResult = viewModel.testSendEmail(testEmailAddress) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = testEmailAddress.isNotBlank() && resendKeyInput.isNotBlank()
                    ) { Text("📤 Gửi Email thử nghiệm") }
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
                facebookPages = facebookPages, // ✅ ĐÃ SỬA: Chuyển tiếp danh sách trang xuống
                onSave = { key, value -> viewModel.saveConfig(key, value) },
                onReset = { key -> viewModel.resetConfig(key) }
            )

            HorizontalDivider()

            PromptLogSection(promptLog = promptLog)

            HorizontalDivider()

            Text("💾 Sao lưu & Phục hồi dữ liệu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { scope.launch { viewModel.exportSettings(context) } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Xuất file (Export)") }
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("application/json") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Nhập file (Import)") }
            }
            if (exportResult != null) {
                Text(exportResult!!, style = MaterialTheme.typography.bodySmall,
                    color = if (exportResult!!.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Chế độ tối (Dark Mode)", style = MaterialTheme.typography.bodyLarge)
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("✅ Đã lưu cấu hình thành công!", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                LaunchedEffect(showSaved) {
                    delay(2000)
                    showSaved = false
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
    facebookPages: List<FacebookPageEntity>, // ✅ ĐÃ SỬA: Chấp nhận danh sách trang
    onSave: (String, String) -> Unit,
    onReset: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("⚙️ Ngưỡng lọc & Tham số nâng cao", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Thu gọn" else "Mở rộng"
            )
        }
    }

    if (!expanded) {
        Text(
            "${configs.size} biến cấu hình — bấm mũi tên để mở bảng điều chỉnh",
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
    val pluginOrder = listOf("global", "groq", "camera", "email", "schedule", "facebook", "instagram", "telegram", "zalo", "website")
    val sortedGroups = (pluginOrder.mapNotNull { pid -> grouped[pid]?.let { pid to it } } +
        grouped.entries.filter { it.key !in pluginOrder }.map { it.toPair() })

    sortedGroups.forEach { (pluginId, items) ->
        PluginGroupCard(
            pluginId = pluginId,
            items = items,
            allConfigs = configs,
            facebookPages = facebookPages, // ✅ ĐÃ SỬA: Truyền tiếp danh sách trang xuống dưới
            onSave = onSave,
            onReset = onReset
        )
    }
}

@Composable
private fun PluginGroupCard(
    pluginId: String,
    items: List<AppConfigEntity>,
    allConfigs: List<AppConfigEntity>,
    facebookPages: List<FacebookPageEntity>, // ✅ ĐÃ SỬA: Chấp nhận tham số trang facebook
    onSave: (String, String) -> Unit,
    onReset: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val (icon, title) = when (pluginId) {
        "global"    -> Pair("🌐", "Cổng kết nối Gateway")
        "groq"      -> Pair("🤖", "Groq AI Cloud")
        "camera"    -> Pair("📷", "Camera giám sát thửa đất")
        "email"     -> Pair("📧", "Email thông báo cảnh báo")
        "schedule"  -> Pair("⏰", "Lịch trình tự động hóa")
        "housekeeper" -> Pair("🏠", "Quản gia tự động")
        "facebook"  -> Pair("📘", "Facebook Messenger")
        "instagram" -> Pair("📸", "Instagram Assistant")
        "telegram"  -> Pair("✈️", "Telegram Assistant")
        "zalo"      -> Pair("💬", "Zalo Official Account")
        "website"   -> Pair("💻", "Website Chat Widget")
        else        -> Pair("🔧", "Cấu hình $pluginId")
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
                Text("$icon $title", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { groupExpanded = !groupExpanded }) {
                    Text(if (groupExpanded) "Thu gọn" else "${items.size} cấu hình", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (groupExpanded) {
                Spacer(Modifier.height(4.dp))

                // ✅ ĐÃ THÊM: Nếu mở rộng thẻ cấu hình Website, tự động render mã nhúng HTML kèm nút Copy 1 chạm!
                if (pluginId == "website") {
                    val gatewayUrl = allConfigs.firstOrNull { it.key == AppConfigDefaults.GLOBAL_GATEWAY_URL }?.value ?: ""
                    val widgetKey = allConfigs.firstOrNull { it.key == AppConfigDefaults.WEBSITE_WIDGET_KEY }?.value ?: ""

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("💻 Mã nhúng Website của bạn:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            if (widgetKey.isBlank()) {
                                Text(
                                    text = "⏳ Đang chờ app kết nối Cloud Gateway lần đầu để tự sinh mã Widget Key an toàn... Hãy đảm bảo dịch vụ nền đang chạy rồi quay lại đây.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                val embedCode = "<script src=\"$gatewayUrl/widget.js?key=$widgetKey\"></script>"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = embedCode,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(embedCode))
                                        Toast.makeText(context, "📋 Đã sao chép mã nhúng Website vào khay nhớ tạm!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📋 Sao chép mã nhúng Website", style = MaterialTheme.typography.labelMedium)
                                }

                                // ✅ ĐÃ THÊM: URL nhúng riêng cho kiểu "Embed by URL" (Google Sites và các trình
                                // dựng web kéo-thả khác chặn localStorage khi dùng "Embed code" do bọc iframe
                                // sandbox không allow-same-origin — mỗi lần refresh trang sẽ mất senderId/lịch sử).
                                // Trỏ thẳng iframe tới URL này (origin thật) thì localStorage lưu bền qua các lần
                                // refresh. Xem route /widget-frame trên app.py (Render).
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "🔗 URL nhúng (kiểu \"Nhúng URL\" — dùng cho Google Sites hoặc web kéo-thả):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                val embedFrameUrl = "${gatewayUrl.trimEnd('/')}/widget-frame?key=$widgetKey"
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = embedFrameUrl,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(embedFrameUrl))
                                        Toast.makeText(context, "📋 Đã sao chép URL nhúng vào khay nhớ tạm!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("📋 Sao chép URL nhúng (Nhúng URL)", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // ✅ ĐÃ THÊM: Nút bấm kết nối tự động 1-Click cho Facebook kèm danh sách Fanpage thực tế
                if (pluginId == "facebook") {
                    val gatewayUrl = allConfigs.firstOrNull { it.key == AppConfigDefaults.GLOBAL_GATEWAY_URL }?.value ?: ""
                    val gatewayToken = allConfigs.firstOrNull { it.key == AppConfigDefaults.GLOBAL_GATEWAY_TOKEN }?.value ?: ""
                    val authUrl = "$gatewayUrl/auth/facebook?token=$gatewayToken"

                    // --- VẼ DANH SÁCH FANPAGE ĐÃ KẾT NỐI ---
                    if (facebookPages.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "💬 Fanpage đã liên kết (${facebookPages.size}):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(6.dp))
                                facebookPages.forEach { page ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                page.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                "ID: ${page.id}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                "Đang chạy",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "⚠️ Chưa có Fanpage nào kết nối. Hãy nhấp nút bên dưới để liên kết nhanh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "❌ Không thể mở trình duyệt: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("🔌 Kết nối Fanpage Facebook (1-Click)", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // ✅ ĐÃ THÊM: Nút bấm kết nối tự động 1-Click cho Zalo
                if (pluginId == "zalo") {
                    val gatewayUrl = allConfigs.firstOrNull { it.key == AppConfigDefaults.GLOBAL_GATEWAY_URL }?.value ?: ""
                    val gatewayToken = allConfigs.firstOrNull { it.key == AppConfigDefaults.GLOBAL_GATEWAY_TOKEN }?.value ?: ""
                    val authUrl = "$gatewayUrl/auth/zalo?token=$gatewayToken"

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "❌ Không thể mở trình duyệt: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("💬 Kết nối Zalo Official Account (1-Click)", style = MaterialTheme.typography.labelMedium)
                    }
                }

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
    val isNumeric = entity.type in setOf("int", "long", "float", "double", "number")
    val isBool = entity.type == "boolean"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        val displayName = if (entity.label.isNotBlank()) entity.label else entity.key
        Text(displayName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

        if (entity.label.isNotBlank()) {
            Text(
                text = "Key: ${entity.key}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }

        if (entity.description.isNotBlank()) {
            Text(entity.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(4.dp))

        if (isBool) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (inputValue.toBooleanStrictOrNull() == true) "Đang BẬT" else "Đang TẮT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = inputValue.toBooleanStrictOrNull() == true,
                    onCheckedChange = { checked ->
                        val newValue = checked.toString()
                        inputValue = newValue
                        onSave(entity.key, newValue)
                    }
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = entity.type != "string" || entity.value.length < 80,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isNumeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    trailingIcon = {
                        if (isDirty) {
                            IconButton(onClick = { onSave(entity.key, inputValue) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Check, contentDescription = "Lưu nhanh", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                )
                IconButton(onClick = { onReset(entity.key); inputValue = entity.value }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }

        Text(
            "Kiểu dữ liệu: ${entity.type.uppercase()}  •  Sửa lần cuối: ${fmtTs(entity.updatedAt)}",
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
        Text("🔍 Nhật ký cuộc gọi gửi Groq", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
    }

    if (!expanded) {
        Text(
            if (promptLog.isEmpty()) "Chưa có cuộc gọi nào trong phiên này"
            else "${promptLog.size} cuộc gọi gần nhất (chi tiết nội dung gửi/nhận kèm tokens tiêu thụ) — bấm mũi tên để xem",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (promptLog.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text("Chưa có nhật ký nào. Hãy gửi 1 tin nhắn để xem log chi tiết ở đây.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
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
                "Nội dung gửi đi:",
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