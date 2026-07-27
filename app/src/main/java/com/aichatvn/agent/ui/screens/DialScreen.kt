package com.aichatvn.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.aichatvn.agent.data.model.CallContactEntity
import com.aichatvn.agent.data.model.CallDirection
import com.aichatvn.agent.data.model.CallLogEntity
import com.aichatvn.agent.data.model.CallLogStatus
import com.aichatvn.agent.skills.CallState
import com.aichatvn.agent.ui.navigation.Screen
import com.aichatvn.agent.ui.viewmodels.CallViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DialScreen
 *
 * ✅ MỚI: chia 3 tab — "Bàn phím" (giữ nguyên luồng cũ: xem mã máy của mình, nhập mã cần
 * gọi), "Danh bạ" (số đã lưu, tap để gọi ngay, không cần nhớ/nhập lại mã), "Gần đây"
 * (lịch sử cuộc gọi — nhỡ/đã trả lời/từ chối, tap để gọi lại).
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
    val uiState by vm.callUiState.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val callLogs by vm.callLogs.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var targetInput by remember { mutableStateOf("") }
    var withVideo by remember { mutableStateOf(true) }

    var pendingCallAfterPermission by remember { mutableStateOf(false) }
    // ✅ MỚI: lưu lại isVideo CỦA LƯỢT GỌI đang chờ cấp quyền — trước đây launcher callback
    // dùng thẳng biến withVideo (chỉ thuộc tab Bàn phím), nên gọi lại 1 cuộc thoại từ Danh
    // bạ/Gần đây mà thiếu quyền sẽ bị xin quyền Camera rồi start_call với video=true sau khi
    // cấp quyền xong — sai với đúng loại cuộc gọi người dùng bấm.
    var pendingCallIsVideo by remember { mutableStateOf(true) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted && pendingCallAfterPermission) {
            vm.startCall(targetInput, pendingCallIsVideo)
        } else if (!allGranted) {
            Toast.makeText(context, "Cần cấp quyền Camera & Micro để gọi được", Toast.LENGTH_LONG).show()
        }
        pendingCallAfterPermission = false
    }

    fun hasCallPermissions(isVideo: Boolean): Boolean {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!isVideo) return mic
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return mic && cam
    }

    // ✅ SỬA: nhận isVideo tường minh từ nơi gọi (mặc định = withVideo của tab Bàn phím,
    // dùng cho nút "Gọi" ở tab đó) — trước đây luôn dùng withVideo bất kể nguồn gọi tới
    // từ đâu, khiến gọi lại 1 số trong Danh bạ hoặc 1 cuộc gọi CHỈ THOẠI trong Gần đây
    // vẫn bật video và xin quyền Camera không cần thiết.
    fun requestCallOrPermission(code: String, isVideo: Boolean = withVideo) {
        targetInput = code
        if (hasCallPermissions(isVideo)) {
            vm.startCall(code, isVideo)
        } else {
            pendingCallAfterPermission = true
            pendingCallIsVideo = isVideo
            val perms = if (isVideo) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            permissionLauncher.launch(perms)
        }
    }

    // Sau khi bấm "Gọi", CallSkill chuyển state khỏi IDLE (DIALING) → tự mở CallScreen.
    LaunchedEffect(uiState.state) {
        if (uiState.state != CallState.IDLE && uiState.state != CallState.ENDED && uiState.state != CallState.FAILED) {
            navController.navigate(Screen.CALL_ROUTE)
        }
    }

    val isBusy = uiState.state != CallState.IDLE && uiState.state != CallState.ENDED && uiState.state != CallState.FAILED

    Scaffold(
        topBar = { TopAppBar(title = { Text("Gọi thoại & Video") }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Bàn phím") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Danh bạ") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Gần đây") })
            }

            if (isBusy) {
                Text(
                    "Đang trong 1 cuộc gọi khác...",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }

            when (selectedTab) {
                0 -> KeypadTab(
                    vm = vm,
                    targetInput = targetInput,
                    onTargetInputChange = { targetInput = it },
                    withVideo = withVideo,
                    onWithVideoChange = { withVideo = it },
                    isBusy = isBusy,
                    onCall = { requestCallOrPermission(targetInput, withVideo) },
                    alreadySaved = contacts.any { it.deviceCode == targetInput }
                )
                1 -> ContactsTab(
                    contacts = contacts,
                    isBusy = isBusy,
                    // Danh bạ không lưu loại cuộc gọi ưa thích cho từng số — dùng withVideo
                    // của tab Bàn phím (mặc định true) như hành vi gọi trước đây.
                    onCall = { code -> requestCallOrPermission(code, withVideo) },
                    onDelete = { code -> vm.deleteContact(code) }
                )
                2 -> RecentTab(
                    callLogs = callLogs,
                    isBusy = isBusy,
                    // ✅ SỬA: gọi lại đúng loại cuộc gọi (thoại/video) của lần gọi TRƯỚC ĐÓ,
                    // khớp với icon Videocam/Call đã hiển thị trên từng dòng lịch sử.
                    onCall = { code, isVideo -> requestCallOrPermission(code, isVideo) }
                )
            }
        }
    }
}

@Composable
private fun KeypadTab(
    vm: CallViewModel,
    targetInput: String,
    onTargetInputChange: (String) -> Unit,
    withVideo: Boolean,
    onWithVideoChange: (Boolean) -> Unit,
    isBusy: Boolean,
    onCall: () -> Unit,
    alreadySaved: Boolean
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var myCode by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        myCode = vm.getMyCode()
    }

    if (showSaveDialog) {
        SaveContactDialog(
            deviceCode = targetInput,
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                vm.saveContact(targetInput, name)
                showSaveDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                onTargetInputChange(input.uppercase().filter { it.isLetterOrDigit() }.take(8))
            },
            label = { Text("Nhập mã máy cần gọi") },
            singleLine = true,
            trailingIcon = {
                if (targetInput.length == 8 && !alreadySaved) {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Lưu số")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = withVideo,
                onClick = { onWithVideoChange(true) },
                label = { Text("Video") },
                leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) }
            )
            FilterChip(
                selected = !withVideo,
                onClick = { onWithVideoChange(false) },
                label = { Text("Chỉ thoại") },
                leadingIcon = { Icon(Icons.Default.Call, contentDescription = null) }
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onCall,
            enabled = targetInput.length == 8 && !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Call, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Gọi")
        }
    }
}

@Composable
private fun ContactsTab(
    contacts: List<CallContactEntity>,
    isBusy: Boolean,
    onCall: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (contacts.isEmpty()) {
        EmptyState("Chưa có số nào được lưu.\nQua tab \"Bàn phím\", nhập mã máy rồi bấm biểu tượng lưu số.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts, key = { it.deviceCode }) { contact ->
            ContactRow(contact = contact, enabled = !isBusy, onCall = { onCall(contact.deviceCode) }, onDelete = { onDelete(contact.deviceCode) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ContactRow(
    contact: CallContactEntity,
    enabled: Boolean,
    onCall: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Xoá số đã lưu?") },
            text = { Text("${contact.displayName} (${contact.deviceCode}) sẽ bị xoá khỏi danh bạ.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Xoá") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Huỷ") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCall() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ContactAvatar(label = contact.displayName)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                if (contact.isFavorite) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                }
            }
            Text(contact.deviceCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onCall, enabled = enabled) {
            Icon(Icons.Default.Call, contentDescription = "Gọi", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Default.Delete, contentDescription = "Xoá", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RecentTab(
    callLogs: List<CallLogEntity>,
    isBusy: Boolean,
    onCall: (String, Boolean) -> Unit
) {
    if (callLogs.isEmpty()) {
        EmptyState("Chưa có cuộc gọi nào trong lịch sử.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(callLogs, key = { it.callId }) { log ->
            CallLogRow(log = log, enabled = !isBusy, onCall = { onCall(log.peerDeviceCode, log.isVideo) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun CallLogRow(
    log: CallLogEntity,
    enabled: Boolean,
    onCall: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()) }
    val isMissed = log.status == CallLogStatus.MISSED
    val (icon, iconColor) = when {
        log.status == CallLogStatus.MISSED -> Icons.Default.CallMissed to MaterialTheme.colorScheme.error
        log.direction == CallDirection.OUTGOING -> Icons.Default.CallMade to MaterialTheme.colorScheme.primary
        else -> Icons.Default.CallReceived to MaterialTheme.colorScheme.primary
    }
    val statusLabel = when (log.status) {
        CallLogStatus.MISSED -> "Nhỡ"
        CallLogStatus.REJECTED -> "Đã từ chối"
        CallLogStatus.FAILED -> "Không kết nối được"
        else -> formatDuration(log.durationSec)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCall() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ContactAvatar(label = log.peerName ?: log.peerDeviceCode)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                log.peerName ?: log.peerDeviceCode,
                style = MaterialTheme.typography.titleMedium,
                color = if (isMissed) MaterialTheme.colorScheme.error else Color.Unspecified
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(14.dp))
                Text(statusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(timeFormat.format(Date(log.startedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onCall, enabled = enabled) {
            Icon(if (log.isVideo) Icons.Default.Videocam else Icons.Default.Call, contentDescription = "Gọi lại", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ContactAvatar(label: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label.trim().take(1).uppercase().ifBlank { "?" },
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SaveContactDialog(
    deviceCode: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lưu số $deviceCode") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tên gợi nhớ (VD: Mẹ, Văn phòng...)") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name) }, enabled = name.isNotBlank()) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
