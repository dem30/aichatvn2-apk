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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.skills.ChatMode
import com.aichatvn.agent.ui.viewmodels.ChatViewModel
import com.aichatvn.agent.ui.viewmodels.QuickCommandGroup
import com.aichatvn.agent.tools.ai.GroqRateLimitInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val chatMode by viewModel.chatMode.collectAsState()
    val groqRateLimit by viewModel.groqRateLimit.collectAsState()
    val groqRouterRateLimit by viewModel.groqRouterRateLimit.collectAsState()
    
    var inputText by remember { mutableStateOf("") }
    var expandedMenu by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedChipTab by remember { mutableStateOf(0) } // 0=Camera 1=Đèn 2=Email 3=Khác
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
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
    
    LaunchedEffect(messages.size, typingMessage) {
        if (messages.isNotEmpty() || typingMessage.isNotEmpty()) {
            listState.animateScrollToItem(
                if (typingMessage.isNotEmpty()) messages.size else messages.size - 1
            )
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
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("Trò chuyện với AI") },
            actions = {
                // ✅ MỚI: label rate-limit Groq — báo người dùng biết còn token/đang cooldown
                // hay không trước khi họ gửi tin nhắn (gửi lúc hết token/đang limit cũng vô dụng).
                // Truyền CẢ 2 model: "chat" (chỉ gọi khi tin nhắn KHÔNG phải lệnh) và "router"
                // (gọi ở MỌI tin nhắn) — xem comment trong GroqRateLimitLabel để hiểu vì sao.
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
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chatMode == ChatMode.GROQ) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("🤖 Groq AI (Mặc định)")
                                }
                            },
                            onClick = {
                                viewModel.setChatMode(ChatMode.GROQ)
                                expandedMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chatMode == ChatMode.QA) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("📚 Q&A Database")
                                }
                            },
                            onClick = {
                                viewModel.setChatMode(ChatMode.QA)
                                expandedMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chatMode == ChatMode.COMBINED) {
                                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("🔄 Kết hợp (QA + AI)")
                                }
                            },
                            onClick = {
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
        
        // ── Gợi ý lệnh nhanh (tự động từ danh sách plugin, không hardcode) ──────
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
            
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nhập tin nhắn...") },
                enabled = !isLoading,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            
            Button(
                onClick = {
                    if (inputText.isNotBlank() || selectedImageUri != null) {
                        viewModel.sendMessageWithImage(inputText, selectedImageUri)
                        inputText = ""
                        selectedImageUri = null
                    }
                },
                enabled = !isLoading && (inputText.isNotBlank() || selectedImageUri != null),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

// ── Label rate-limit Groq (token còn lại / cooldown request) ───────────────
// "Cooldown" chỉ được hiển thị khi THẬT SỰ bị Groq từ chối (HTTP 429), lấy từ mốc thời
// gian TUYỆT ĐỐI info.cooldownUntilMillis (xem GroqRateLimitInfo). Vì luôn tính lại bằng
// (cooldownUntilMillis - thời gian hiện tại) ở MỌI lần tick, giá trị hiển thị đúng ngay cả
// khi app vừa được mở lại sau khi bị tắt hẳn hoặc đưa vào background một lúc lâu.
//
// ⚠️ Hiển thị 2 DÒNG riêng cho 2 model: "💬" (model chat - chỉ gọi khi tin nhắn KHÔNG phải
// lệnh thiết bị) và "⚡" (model router - gọi ở MỌI tin nhắn để phân loại lệnh/chat). 2 model
// có quota riêng trên Groq nên số liệu khác nhau là BÌNH THƯỜNG, không phải lỗi - tách dòng
// rõ ràng để không còn gây hiểu nhầm như khi gộp chung 1 số trước đây.

@Composable
private fun GroqRateLimitLabel(chatInfo: GroqRateLimitInfo?, routerInfo: GroqRateLimitInfo?) {
    if (chatInfo == null && routerInfo == null) return

    Column(horizontalAlignment = Alignment.End) {
        chatInfo?.let { GroqRateLimitRow(icon = "💬", info = it) }
        routerInfo?.let { GroqRateLimitRow(icon = "⚡", info = it) }
    }
}

@Composable
private fun GroqRateLimitRow(icon: String, info: GroqRateLimitInfo) {
    var nowMillis by remember(info.cooldownUntilMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info.cooldownUntilMillis) {
        while (info.cooldownUntilMillis != null && nowMillis < info.cooldownUntilMillis) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val secondsLeft = info.cooldownUntilMillis
        ?.let { ((it - nowMillis) / 1000.0).coerceAtLeast(0.0) }
        ?: 0.0
    val cooling = secondsLeft > 0.0
    val requestsLow = info.remainingRequests != null && info.remainingRequests <= 0
    val tokensLow = info.remainingTokens != null && info.remainingTokens <= 0

    // Ưu tiên hiện: đang cooldown thật (429) > số request còn lại > số token còn lại.
    val label = when {
        cooling -> "⏳${secondsLeft.toInt()}s"
        info.remainingRequests != null ->
            "${info.remainingRequests}" + (info.limitRequests?.let { "/$it" } ?: "")
        info.remainingTokens != null -> "🪙${info.remainingTokens}"
        else -> null
    } ?: return

    Text(
        text = "$icon $label",
        style = MaterialTheme.typography.labelSmall,
        color = if (cooling || requestsLow || tokensLow) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ── Chip lệnh gợi ý: lấy ĐỘNG từ ChatViewModel.quickCommandGroups, build từ
// Plugin.getActions() của từng skill (xem AgentKernel.getAvailablePluginsForUI()).
// Không còn list hardcode — thêm plugin mới ở AppModule là tự xuất hiện ở đây.

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

        // Tab nhóm (1 tab / plugin)
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

        // Chip lệnh (1 chip / action của plugin đang chọn)
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

// Giữ (long-press) vào bong bóng tin nhắn để copy nội dung — combinedClickable cần
// @OptIn vì API này còn experimental ở Compose Foundation.
// ✅ MỚI: map id plugin -> nhãn thân thiện hiển thị trên badge "lệnh" của tin nhắn.
// Plugin nào không có trong danh sách (vd thêm plugin mới sau này) vẫn hiện được nhờ
// nhánh else, không cần sửa hàm này mỗi khi thêm plugin.
private fun pluginBadgeLabel(sourcePlugin: String): String = when (sourcePlugin) {
    "learn" -> "📚 Học"
    "camera" -> "📷 Camera"
    "light" -> "💡 Đèn"
    "email" -> "📧 Email"
    "notification" -> "🔔 Thông báo"
    "schedule" -> "⏰ Lịch"
    "training" -> "📚 Học"
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
                // ✅ MỚI: badge nhỏ góc trên-phải báo đây là kết quả của 1 LỆNH điều khiển
                // (sourcePlugin != null) - giúp phân biệt với câu trả lời chat tự do.
                // Chỉ hiện cho tin nhắn của assistant, không bao giờ hiện ở tin nhắn user.
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

                if (message.type == "image" && message.fileUrl != null) {
                    val bitmap = remember(message.fileUrl) {
                        try {
                            val file = java.io.File(message.fileUrl)
                            if (file.exists()) {
                                BitmapFactory.decodeFile(message.fileUrl)
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
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