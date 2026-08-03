package com.aichatvn.agent.ui.activation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// ✅ MỚI: Màn chặn đầu tiên trước Dashboard khi chưa activation_status. Tự đọc clipboard 1 lần
// khi mở màn (vì link mời từ /i/{code} tự copy code vào đó), có ô nhập tay + nút Dán dự phòng.
@Composable
fun ActivationScreen(
    onActivated: () -> Unit,
    viewModel: ActivationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var codeInput by remember { mutableStateOf("") }
    var autoTried by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString()?.trim().orEmpty()
        } else ""
        // Chỉ auto-activate nếu clipboard giống định dạng mã mời (8 ký tự chữ+số, không dấu cách)
        if (clipText.length == 8 && clipText.all { it.isLetterOrDigit() }) {
            codeInput = clipText
            autoTried = true
            viewModel.activate(clipText)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ActivationUiState.Success) onActivated()
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Chào mừng bạn!", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Nhập mã mời để bắt đầu sử dụng",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.trim() },
                label = { Text("Mã mời (8 ký tự)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboardManager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            codeInput = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Dán")
                    }
                }
            )

            when (val state = uiState) {
                is ActivationUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                is ActivationUiState.Loading -> CircularProgressIndicator()
                else -> {}
            }

            Button(
                onClick = { viewModel.activate(codeInput) },
                enabled = uiState !is ActivationUiState.Loading && codeInput.isNotBlank()
            ) {
                Text("Kích hoạt")
            }

            if (autoTried && uiState is ActivationUiState.Error) {
                Text(
                    "Mã tự dán từ clipboard không hợp lệ — nhập lại tay ở trên.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
