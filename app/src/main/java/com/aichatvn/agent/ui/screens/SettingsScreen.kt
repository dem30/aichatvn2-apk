package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.work.WorkManager
import com.aichatvn.agent.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val groqApiKey by viewModel.groqApiKey.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()
    val gmailClientId by viewModel.gmailClientId.collectAsState()
    val gmailClientSecret by viewModel.gmailClientSecret.collectAsState()
    val gmailRefreshToken by viewModel.gmailRefreshToken.collectAsState()
    val gmailSender by viewModel.gmailSender.collectAsState()

    var groqKeyInput by remember(groqApiKey) { mutableStateOf(groqApiKey) }
    var gmailClientIdInput by remember(gmailClientId) { mutableStateOf(gmailClientId) }
    var gmailClientSecretInput by remember(gmailClientSecret) { mutableStateOf(gmailClientSecret) }
    var gmailRefreshTokenInput by remember(gmailRefreshToken) { mutableStateOf(gmailRefreshToken) }
    var gmailSenderInput by remember(gmailSender) { mutableStateOf(gmailSender) }
    var testEmailAddress by remember { mutableStateOf("") }
    var showSaved by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var testEmailResult by remember { mutableStateOf<String?>(null) }
    
    // Worker schedule status
    var workerStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    
    LaunchedEffect(Unit) {
        val workManager = WorkManager.getInstance(context)
        try {
            val cameraScheduleInfo = workManager.getWorkInfosForUniqueWork("camera_schedule_work").get()
            val smartScanInfo = workManager.getWorkInfosForUniqueWork("smart_scan_15min_work").get()
            
            workerStatus = mapOf(
                "Camera Schedule (30 phút)" to when (cameraScheduleInfo.firstOrNull()?.state?.name) {
                    "ENQUEUED" -> "⏳ Đang chờ"
                    "RUNNING" -> "🔄 Đang chạy"
                    "SUCCEEDED" -> "✅ Thành công"
                    "FAILED" -> "❌ Thất bại"
                    else -> "⏸ Chưa kích hoạt"
                },
                "Smart Scan (15 phút)" to when (smartScanInfo.firstOrNull()?.state?.name) {
                    "ENQUEUED" -> "⏳ Đang chờ"
                    "RUNNING" -> "🔄 Đang chạy"
                    "SUCCEEDED" -> "✅ Thành công"
                    "FAILED" -> "❌ Thất bại"
                    else -> "⏸ Chưa kích hoạt"
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
            // Card hiển thị trạng thái worker schedule
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
                        if (success) {
                            errorMessage = null
                            showSaved = true
                        } else {
                            errorMessage = message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("🔌 Kiểm tra kết nối Groq")
            }

            HorizontalDivider()

            Text("📧 Gmail API", style = MaterialTheme.typography.titleMedium)
            
            OutlinedTextField(
                value = gmailClientIdInput,
                onValueChange = { gmailClientIdInput = it },
                label = { Text("Client ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = gmailClientSecretInput,
                onValueChange = { gmailClientSecretInput = it },
                label = { Text("Client Secret") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = gmailRefreshTokenInput,
                onValueChange = { gmailRefreshTokenInput = it },
                label = { Text("Refresh Token") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = gmailSenderInput,
                onValueChange = { gmailSenderInput = it },
                label = { Text("Email gửi") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            // Test email section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📧 Kiểm tra gửi email", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = testEmailAddress,
                        onValueChange = { testEmailAddress = it },
                        label = { Text("Email nhận test") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("nhanvien@example.com") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                testEmailResult = null
                                val result = viewModel.testSendEmail(
                                    testEmailAddress,
                                    gmailClientIdInput,
                                    gmailClientSecretInput,
                                    gmailRefreshTokenInput,
                                    gmailSenderInput
                                )
                                testEmailResult = result
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = testEmailAddress.isNotBlank() && gmailSenderInput.isNotBlank()
                    ) {
                        Text("📤 Gửi email test")
                    }
                    if (testEmailResult != null) {
                        Text(
                            text = testEmailResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testEmailResult!!.startsWith("✅")) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Chế độ tối", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = darkMode, onCheckedChange = { viewModel.toggleDarkMode(it) })
            }

            HorizontalDivider()

            Button(
                onClick = {
                    viewModel.saveGroqApiKey(groqKeyInput)
                    viewModel.saveGmailSettings(
                        gmailClientIdInput,
                        gmailClientSecretInput,
                        gmailRefreshTokenInput,
                        gmailSenderInput
                    )
                    showSaved = true
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lưu cài đặt")
            }

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "❌ $errorMessage",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (showSaved) {
                LaunchedEffect(Unit) {
                    delay(2000)
                    showSaved = false
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        text = "✅ Đã lưu!",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}