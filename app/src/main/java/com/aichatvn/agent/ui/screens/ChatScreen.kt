package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.draw.alpha
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.ui.viewmodels.ChatViewModel
import com.aichatvn.agent.ui.viewmodels.QuickCommandGroup
import com.aichatvn.agent.tools.ai.GroqRateLimitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Biến tĩnh cấp tiến trình để kiểm tra lần đầu mở app (Cold Start)
private var isColdStart = true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val chatMode by viewModel.chatMode.collectAsState()
    val groqRateLimit by viewModel.groqRateLimit.collectAsState()
    val groqRouterRateLimit by viewModel.groqRouterRateLimit.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val partialText by viewModel.partialText.collectAsState()
    val voiceModeActive by viewModel.voiceModeActive.collectAsState()
    val pausedDueToError by viewModel.pausedDueToError.collectAsState()
    val lockedPluginName by viewModel.lockedPluginName.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    var expandedMenu by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedChipTab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    
    var typingMessage by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
        if (uri != null && inputText.isBlank()) {
            inputText = "[Hình ảnh]"
        }
    }
    
    val hasDraftBubble = isListening && partialText.isNotBlank()

    LaunchedEffect(messages.size, typingMessage, hasDraftBubble, isTyping) {
        val totalItems = messages.size +
            (if (hasDraftBubble) 1 else 0) +
            (if (isTyping) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    
    LaunchedEffect(isLoading) {
        if (isLoading) {
            isTyping = true
            typingMessage = ""
        } else {
            delay(300)
            isTyping = false
            typingMessage = ""
        }
    }

    LaunchedEffect(Unit) {
        viewModel.updateLockedPluginStatus()
    }

    

    // QUẢN LÝ LẦN ĐẦU KHỞI ĐỘNG (COLD START)
    LaunchedEffect(Unit) {
        delay(300) // Chờ 300ms đảm bảo preferences/database đã load xong dữ liệu cũ
        if (isColdStart) {
            if (voiceModeActive) {
                viewModel.toggleVoiceMode() // Nếu bật từ phiên trước, chủ động tắt đi ở lần đầu mở app
            }
            isColdStart = false // Đánh dấu đã vượt qua bước khởi động lạnh thành công
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    
    // ĐƯA DISPOSABLE EFFECT VỀ ĐÚNG CÚ PHÁP CHUẨN CỦA COMPOSE
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!isColdStart && voiceModeActive) {
                        viewModel.onForeground()
                    }
                }
                Lifecycle.Event.ON_STOP  -> viewModel.onBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer)
            // QUAN TRỌNG: Khi rời màn hình Chat (chuyển tab hoặc đóng màn hình), tắt mic ngay lập tức!
            viewModel.onBackground() 
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            TopAppBar(
                title = { Text("Trò chuyện với AI") },
                actions = {
                    GroqRateLimitLabel(chatInfo = groqRateLimit, routerInfo = groqRouterRateLimit)

                    IconButton(onClick = { navController.navigate("logs") }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Xem log hệ thống")
                    }
                    IconButton(onClick = { viewModel.clearHistory() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Xóa lịch sử")
                    }
                    
                    Box {
                        IconButton(onClick = { expandedMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Chọn chế độ")
                        }
                        
                        DropdownMenu(
                            expanded = expandedMenu,
                            onDismissRequest = { expandedMenu = false }
                        ) {
                            ModeMenuItem(
                                label = "🤖 Groq AI (Mặc định)",
                                selected = chatMode == ChatMode.GROQ,
                                onSelect = {
                                    viewModel.setChatMode(ChatMode.GROQ)
                                    expandedMenu = false
                                }
                            )
                            ModeMenuItem(
                                label = "📚 Q&A Database",
                                selected = chatMode == ChatMode.QA,
                                onSelect = {
                                    viewModel.setChatMode(ChatMode.QA)
                                    expandedMenu = false
                                }
                            )
                            ModeMenuItem(
                                label = "🔄 Kết hợp (QA + AI)",
                                selected = chatMode == ChatMode.COMBINED,
                                onSelect = {
                                    viewModel.setChatMode(ChatMode.COMBINED)
                                    expandedMenu = false
                                }
                            )
                        }
                    }
                }
            )
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = when (chatMode) {
                        ChatMode.GROQ -> "🤖 Đang dùng Groq AI"
                        ChatMode.QA -> "📚 Đang dùng Q&A Database"
                        ChatMode.COMBINED -> "🔄 Đang dùng chế độ Kết hợp (QA + AI)"
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    pausedDueToError -> MaterialTheme.colorScheme.errorContainer
                    isListening -> MaterialTheme.colorScheme.errorContainer
                    voiceModeActive -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            pausedDueToError -> "⚠️ Đã tạm dừng do lỗi mạng liên tục — kiểm tra mạng rồi bật lại"
                            isListening -> "🎙️ Đang nghe..."
                            voiceModeActive -> "✅ Hands-free bật — đang chờ"
                            else -> "🔇 Hands-free tắt"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { viewModel.toggleVoiceMode() }) {
                        Text(
                            text = if (voiceModeActive) "Tắt mic" else "Bật mic",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (lockedPluginName != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked Control",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "🔒 Đang điều khiển riêng: \"$lockedPluginName\"",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "Gõ \"thoát\" để dừng",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages) { message ->
                    ChatBubble(message = message)
                }

                if (isListening && partialText.isNotBlank()) {
                    item(key = "partial_draft") {
                        Box(modifier = Modifier.alpha(0.55f)) {
                            ChatBubble(
                                message = ChatMessageEntity(
                                    id = "draft_partial",
                                    sessionToken = "session_default_user",
                                    username = "default_user",
                                    content = partialText,
                                    role = "user",
                                    type = "text",
                                    timestamp = System.currentTimeMillis(),
                                    sourcePlugin = null
                                )
                            )
                        }
                    }
                }
                
                if (isTyping) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = if (typingMessage.isNotEmpty()) typingMessage else "Đang suy nghĩ...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            QuickCommandBar(
                groups = viewModel.quickCommandGroups,
                selectedTab = selectedChipTab,
                onTabChange = { selectedChipTab = it },
                onChipClick = { inputText = it }
            )

            if (selectedImageUri != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📷 Đã chọn ảnh",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { selectedImageUri = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Bỏ chọn")
                        }
                    }
                }
            }
            
            HorizontalDivider()
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Chọn ảnh")
                }

                IconButton(
                    onClick = { viewModel.toggleVoiceMode() },
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = if (voiceModeActive) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (voiceModeActive) "Tắt hands-free" else "Bật hands-free",
                        tint = when {
                            isListening -> MaterialTheme.colorScheme.error
                            voiceModeActive -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhập tin nhắn...") },
                    enabled = !isLoading,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessageWithImage(inputText, selectedImageUri)
                            inputText = ""
                            selectedImageUri = null
                        }
                    },
                    enabled = !isLoading && inputText.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Gửi tin nhắn")
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "⏳ Đang xử lý...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeMenuItem(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(label)
            }
        },
        onClick = onSelect
    )
}

@Composable
private fun GroqRateLimitLabel(
    chatInfo: GroqRateLimitInfo?,
    routerInfo: GroqRateLimitInfo?
) {
    val info = chatInfo ?: routerInfo ?: return
    val cooling = info.isRateLimited
    val requestsLow = info.remainingRequests != null && info.remainingRequests!! < 5
    val tokensLow = info.remainingTokens != null && info.remainingTokens!! < 100

    val icon = when {
        cooling -> "🔴"
        requestsLow || tokensLow -> "🟡"
        else -> "🟢"
    }

    val label = when {
        cooling && info.cooldownUntilMillis != null -> {
            val cooldownMs = (info.cooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val cooldownSecs = (cooldownMs / 1000).toInt()
            "Chờ ${cooldownSecs}s"
        }
        requestsLow && tokensLow -> {
            "Req: ${info.remainingRequests}/${info.limitRequests} | Tk: ${info.remainingTokens}/24h"
        }
        requestsLow -> {
            "Req: ${info.remainingRequests}/${info.limitRequests}"
        }
        tokensLow -> {
            "Tk: ${info.remainingTokens}/24h"
        }
        else -> null
    } ?: return

    Text(
        text = "$icon $label",
        style = MaterialTheme.typography.labelSmall,
        color = if (cooling || requestsLow || tokensLow) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun QuickCommandBar(
    groups: List<QuickCommandGroup>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onChipClick: (String) -> Unit
) {
    if (groups.isEmpty()) return
    val safeTab = selectedTab.coerceIn(0, groups.size - 1)

    val tabScrollState = rememberScrollState()
    val chipScrollState = rememberScrollState()

    Column {
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(tabScrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groups.forEachIndexed { index, group ->
                FilterChip(
                    selected = safeTab == index,
                    onClick = { onTabChange(index) },
                    label = { Text(group.tabLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groups[safeTab].commands.forEach { cmd ->
                SuggestionChip(
                    onClick = { onChipClick(cmd.text) },
                    label = { Text(cmd.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

private fun pluginBadgeLabel(sourcePlugin: String): String = when (sourcePlugin) {
    "learn" -> "📚 Học"
    "camera" -> "📷 Camera"
    "light" -> "💡 Đèn"
    "email" -> "📧 Email"
    "notification" -> "🔔 Thông báo"
    "schedule" -> "⏰ Lịch"
    "training" -> "📚 Học"
    "router_error" -> "⚠️ Lỗi mạng"
    else -> "⚡ ${sourcePlugin.replaceFirstChar { it.uppercase() }}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        var bitmap by remember(message.fileUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
        
        LaunchedEffect(message.fileUrl) {
            if (message.type == "image" && message.fileUrl != null) {
                bitmap = withContext(Dispatchers.IO) {
                    try {
                        val file = java.io.File(message.fileUrl)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(message.fileUrl)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "Đã sao chép tin nhắn", Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (!isUser && message.sourcePlugin != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = pluginBadgeLabel(message.sourcePlugin),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (message.type == "image" && bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}