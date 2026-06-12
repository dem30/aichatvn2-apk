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
import androidx.navigation.NavController
import com.aichatvn.agent.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val groqApiKey by viewModel.groqApiKey.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()

    var groqKeyInput by remember(groqApiKey) { mutableStateOf(groqApiKey) }
    var gmailClientId by remember { mutableStateOf("") }
    var gmailClientSecret by remember { mutableStateOf("") }
    var gmailRefreshToken by remember { mutableStateOf("") }
    var gmailSender by remember { mutableStateOf("") }
    var showSaved by remember { mutableStateOf(false) }

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
                singleLine = true
            )

            HorizontalDivider()

            // Gmail
            Text("📧 Gmail API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = gmailClientId, onValueChange = { gmailClientId = it },
                label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = gmailClientSecret, onValueChange = { gmailClientSecret = it },
                label = { Text("Client Secret") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(value = gmailRefreshToken, onValueChange = { gmailRefreshToken = it },
                label = { Text("Refresh Token") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(value = gmailSender, onValueChange = { gmailSender = it },
                label = { Text("Email gửi") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            HorizontalDivider()

            // Dark mode
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Chế độ tối", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = darkMode, onCheckedChange = { viewModel.toggleDarkMode(it) })
            }

            HorizontalDivider()

            // Save button
            Button(
                onClick = {
                    viewModel.saveGroqApiKey(groqKeyInput)
                    if (gmailClientId.isNotBlank()) {
                        viewModel.saveGmailSettings(gmailClientId, gmailClientSecret, gmailRefreshToken, gmailSender)
                    }
                    showSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lưu cài đặt")
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
