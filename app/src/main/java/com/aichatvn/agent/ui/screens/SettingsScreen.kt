package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val groqApiKey by viewModel.groqApiKey.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val gmailClientId by viewModel.gmailClientId.collectAsStateWithLifecycle()
    val gmailClientSecret by viewModel.gmailClientSecret.collectAsStateWithLifecycle()
    val gmailRefreshToken by viewModel.gmailRefreshToken.collectAsStateWithLifecycle()
    val gmailSender by viewModel.gmailSender.collectAsStateWithLifecycle()

    var groqKeyInput by remember(groqApiKey) { mutableStateOf(groqApiKey) }
    var gmailClientIdInput by remember(gmailClientId) { mutableStateOf(gmailClientId) }
    var gmailClientSecretInput by remember(gmailClientSecret) { mutableStateOf(gmailClientSecret) }
    var gmailRefreshTokenInput by remember(gmailRefreshToken) { mutableStateOf(gmailRefreshToken) }
    var gmailSenderInput by remember(gmailSender) { mutableStateOf(gmailSender) }
    var showSaved by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Cài đặt") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Groq API
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

            // Test Connection button
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

            // Gmail
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

            HorizontalDivider()

            // Dark mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Chế độ tối", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = darkMode, onCheckedChange = { viewModel.toggleDarkMode(it) })
            }

            HorizontalDivider()

            // Save button
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
                    Text("❌ $errorMessage", modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (showSaved) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSaved = false
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("✅ Đã lưu!", modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}