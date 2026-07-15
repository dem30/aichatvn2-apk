package com.aichatvn.agent.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable // Đã thêm import cho chạm đơn tiêu chuẩn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.aichatvn.agent.ui.navigation.Screen
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.ui.viewmodels.ChatViewModel
import com.aichatvn.agent.ui.viewmodels.QuickCommandGroup
import com.aichatvn.agent.tools.ai.GroqRateLimitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.Dialog

private fun chatScreenTitle(username: String): String {
    if (username == "default_user") return "Trò chuyện với AI"
    val platform = username.substringBefore("_")
    val rawId = username.substringAfter("_")
    val channelName = when (platform) {
        "facebook" -> "Facebook"
        "telegram" -> "Telegram"
        "website" -> "Website"
        "instagram" -> "Instagram"
        else -> "Khách"
    }
    return "$channelName · $rawId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel(),
    unreadInboxCount: Int = 0
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val chatMode by viewModel.chatMode.collectAsState()
    val groqRateLimit by viewModel.groqRateLimit.collectAsState()
    val groqRouterRateLimit by viewModel.groqRouterRateLimit.collectAsState()
    
    val isListening by viewModel.isListening.collectAsState()
    val partialText by viewModel.partialText.collectAsState()
    val isVoiceOverlayOpen by viewModel.isVoiceOverlayOpen.collectAsState()
    val rmsDb by viewModel.rmsDb.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()
    
    val lockedPluginName by viewModel.lockedPluginName.collectAsState()
    val isBotEnabled by viewModel.isBotEnabled.collectAsState()
    val username = viewModel.username

    var inputText by remember { mutableStateOf("") }
    var expandedMenu by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedChipTab by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    
    var isTyping by remember { mutableStateOf(false) }
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
        if (uri != null && inputText.isBlank()) {
            inputText = "[Hình ảnh]"
        }
    }

    LaunchedEffect(messages.size, isTyping) {
        val totalItems = messages.size + (if (isTyping) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    
    LaunchedEffect(isLoading) {
        if (isLoading) {
            isTyping = true
        } else {
            delay(300)
            isTyping = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.updateLockedPluginStatus()
    }

    LaunchedEffect(Unit) {
        viewModel.activateThread()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            val canGoBack = navController.previousBackStackEntry != null
            TopAppBar(
                title = { Text(chatScreenTitle(username)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                        }
                    }
                },
                actions = {
                    GroqRateLimitLabel(chatInfo = groqRateLimit, routerInfo = groqRouterRateLimit)

                    IconButton(onClick = {
                        navController.navigate(Screen.INBOX_ROUTE) { launchSingleTop = true }
                    }) {
                        if (unreadInboxCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge {
                                        Text(if (unreadInboxCount > 99) "99+" else unreadInboxCount.toString())
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Forum, contentDescription = "Hộp thư đa kênh")
                            }
                        } else {
                            Icon(Icons.Default.Forum, contentDescription = "Hộp thư đa kênh")
                        }
                    }

                    IconButton(onClick = {
                        navController.navigate("logs") { launchSingleTop = true }
                    }) {
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

            if (username != "default_user") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isBotEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBotEnabled) "🤖 AI đang tự động trả lời" else "👤 Đã cướp quyền (Người trực chat tay)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isBotEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Switch(
                            checked = !isBotEnabled,
                            onCheckedChange = { isTakeover ->
                                viewModel.toggleBotSmartMode(username, isBotEnabled = !isTakeover)
                            },
                            thumbContent = {
                                Text(if (isBotEnabled) "🤖" else "👤", fontSize = 10.sp)
                            }
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
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
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
                                        text = "Đang suy nghĩ...",
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
                    onClick = { viewModel.openVoiceSearch() },
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Chạm để nói",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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

        if (isVoiceOverlayOpen) {
            PremiumVoiceOverlay(
                isListening = isListening,
                partialText = partialText,
                rmsDb = rmsDb,
                voiceError = voiceError,
                onClose = { viewModel.closeVoiceSearch() },
                onRetry = { viewModel.openVoiceSearch() }
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// ✅ HIỆU CHỈNH CHỐNG LỖI COMPILER: Sử dụng .clickable thay cho .combinedClickable
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun PremiumVoiceOverlay(
    isListening: Boolean,
    partialText: String,
    rmsDb: Float,
    voiceError: String?,
    onClose: () -> Unit,
    onRetry: () -> Unit
) {
    val volumeScale by animateFloatAsState(
        targetValue = 1f + (rmsDb.coerceAtLeast(0f) / 10f).coerceAtMost(1.5f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
    )

    val infiniteTransition = rememberInfiniteTransition()
    val slowPulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = twist(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val waveScale = if (rmsDb > 1f) volumeScale else slowPulseScale

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(24.dp)
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Đóng",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (!voiceError.isNullOrBlank()) "" 
                       else if (partialText.isBlank() || partialText == "Đang lắng nghe giọng nói...") "Hãy nói điều gì đó..." 
                       else "“ $partialText ”",
                style = MaterialTheme.typography.headlineMedium.copy(
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = if (partialText.startsWith("Đang")) Color.White.copy(alpha = 0.5f) else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .heightIn(min = 120.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(240.dp)
            ) {
                if (isListening && voiceError == null) {
                    Box(
                        modifier = Modifier
                            .size(160.dp * waveScale)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(120.dp * waveScale)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), CircleShape)
                    )
                }

                // ✅ ĐÃ SỬA DÒNG 579: Thay thế hoàn toàn sang .clickable để vượt qua bộ lọc build Gradle nghiêm ngặt
                Surface(
                    shape = CircleShape,
                    color = if (voiceError != null) MaterialTheme.colorScheme.error 
                            else if (isListening) MaterialTheme.colorScheme.primary 
                            else Color.Gray,
                    modifier = Modifier
                        .size(80.dp)
                        .clickable { // ✅ Thao tác chạm đơn ổn định 100%
                            if (voiceError != null) onRetry()
                            else onClose()
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (voiceError != null) Icons.Default.Refresh else Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (voiceError != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = voiceError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Chạm vào biểu tượng Micro để thử lại",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray
                    )
                }
            } else {
                Text(
                    text = if (isListening) "🎙️ Đang nghe... Hãy nói tròn vành rõ chữ" else "Đang xử lý...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Helper nhịp đập Pulse mặc định
private fun twist(durationMillis: Int, easing: Easing): KeyframesSpec<Float> = keyframes {
    this.durationMillis = durationMillis
    1f at 0 with easing
    1.12f at (durationMillis / 2) with easing
    1f at durationMillis
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
    "human" -> "👤 Trực tiếp"
    "learn" -> "📚 Học"
    "camera" -> "📷 Camera"
    "smart_switch" -> "💡 Đèn"
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

    var bitmap by remember(message.fileUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showFullImage by remember { mutableStateOf(false) }

    LaunchedEffect(message.fileUrl) {
        if (message.type == "image" && message.fileUrl != null) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    val file = java.io.File(message.fileUrl)
                    if (file.exists()) BitmapFactory.decodeFile(message.fileUrl) else null
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
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
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser && message.sourcePlugin != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            pluginBadgeLabel(message.sourcePlugin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .padding(bottom = 6.dp)
                            .clickable { showFullImage = true }
                    )
                }

                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (showFullImage) {
        bitmap?.let { bmp ->
            Dialog(onDismissRequest = { showFullImage = false }) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullImage = false }
                )
            }
        }
    }
}