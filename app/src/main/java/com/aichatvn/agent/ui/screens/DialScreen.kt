package com.aichatvn.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import android.widget.Toast
import com.aichatvn.agent.skills.CallState
import com.aichatvn.agent.ui.navigation.Screen
import com.aichatvn.agent.ui.viewmodels.CallViewModel

/**
 * DialScreen
 *
 * Màn hình bàn phím gọi đơn giản: hiển thị mã máy CỦA MÌNH (để share cho người kia), và ô
 * nhập mã máy CẦN GỌI TỚI (targetDeviceCode — mã 8 ký tự tự sinh trong CallSkill, KHÔNG phải
 * số điện thoại — xem CallSkill.getOrCreateMyDeviceCode()).
 *
 * vm được truyền từ AppNavigator (đã hiltViewModel() ở scope Activity) để dùng chung 1
 * instance CallViewModel với CallScreen/observer RINGING toàn cục — không tự hiltViewModel()
 * mới ở đây.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialScreen(
    navController: NavController,
    vm: CallViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by vm.callUiState.collectAsState()

    var myCode by remember { mutableStateOf("") }
    var targetInput by remember { mutableStateOf("") }
    var withVideo by remember { mutableStateOf(true) }

    // ✅ MỚI: xin quyền CAMERA + RECORD_AUDIO tại runtime (khai trong Manifest chỉ là điều
    // kiện cần, Android 6+ vẫn bắt buộc hỏi người dùng lúc chạy). pendingCallAfterPermission
    // giữ lại ý định "Gọi" của người dùng để tự bấm gọi tiếp ngay sau khi cấp quyền xong,
    // thay vì bắt họ bấm nút "Gọi" lần 2.
    var pendingCallAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted && pendingCallAfterPermission) {
            vm.startCall(targetInput, withVideo)
        } else if (!allGranted) {
            Toast.makeText(context, "Cần cấp quyền Camera & Micro để gọi được", Toast.LENGTH_LONG).show()
        }
        pendingCallAfterPermission = false
    }

    fun hasCallPermissions(): Boolean {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!withVideo) return mic
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return mic && cam
    }

    fun requestCallOrPermission() {
        if (hasCallPermissions()) {
            vm.startCall(targetInput, withVideo)
        } else {
            pendingCallAfterPermission = true
            val perms = if (withVideo) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            permissionLauncher.launch(perms)
        }
    }

    LaunchedEffect(Unit) {
        myCode = vm.getMyCode()
    }

    // Sau khi bấm "Gọi", CallSkill chuyển state khỏi IDLE (DIALING) → tự mở CallScreen.
    LaunchedEffect(uiState.state) {
        if (uiState.state != CallState.IDLE && uiState.state != CallState.ENDED && uiState.state != CallState.FAILED) {
            navController.navigate(Screen.CALL_ROUTE)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Gọi thoại & Video") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Mã máy của bạn", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            Card {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = myCode.ifBlank { "..." },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(myCode))
                        Toast.makeText(context, "Đã copy mã máy", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy mã máy")
                    }
                }
            }
            Text(
                "Gửi mã này cho người bạn muốn gọi tới (Zalo, tin nhắn...)",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(40.dp))

            OutlinedTextField(
                value = targetInput,
                onValueChange = { input ->
                    // Mã máy sinh ra ở dạng UUID.take(8).uppercase() — chỉ gồm chữ/số, tối đa 8 ký tự
                    targetInput = input.uppercase().filter { it.isLetterOrDigit() }.take(8)
                },
                label = { Text("Nhập mã máy cần gọi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = withVideo,
                    onClick = { withVideo = true },
                    label = { Text("Video") },
                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) }
                )
                FilterChip(
                    selected = !withVideo,
                    onClick = { withVideo = false },
                    label = { Text("Chỉ thoại") },
                    leadingIcon = { Icon(Icons.Default.Call, contentDescription = null) }
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { requestCallOrPermission() },
                enabled = targetInput.length == 8 && uiState.state == CallState.IDLE,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Gọi")
            }

            if (uiState.state != CallState.IDLE && uiState.state != CallState.ENDED && uiState.state != CallState.FAILED) {
                Spacer(Modifier.height(12.dp))
                Text("Đang trong 1 cuộc gọi khác...", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}