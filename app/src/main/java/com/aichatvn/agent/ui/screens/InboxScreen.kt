package com.aichatvn.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.aichatvn.agent.data.model.ChatMessageEntity
import com.aichatvn.agent.ui.viewmodels.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    // Thu thập danh sách tin nhắn cuối cùng của từng khách hàng từ DB thông qua ViewModel
    val latestThreads by viewModel.latestChatThreads.collectAsState(initial = emptyList())
    var selectedChannel by remember { mutableStateOf("all") } // "all", "facebook", "telegram", "website"

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Hộp thư đa kênh", style = MaterialTheme.typography.titleLarge) }
            ) 
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 🌐 1. THANH CHỌN KÊNH ĐA KÊNH (Channel Tabs)
            ScrollableTabRow(
                selectedTabIndex = when (selectedChannel) {
                    "all" -> 0
                    "facebook" -> 1
                    "telegram" -> 2
                    "website" -> 3
                    else -> 0
                },
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 8.dp
            ) {
                Tab(selected = selectedChannel == "all", onClick = { selectedChannel = "all" }) {
                    Text("🌎 Tất cả", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                Tab(selected = selectedChannel == "facebook", onClick = { selectedChannel = "facebook" }) {
                    Text("📘 Facebook", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                Tab(selected = selectedChannel == "telegram", onClick = { selectedChannel = "telegram" }) {
                    Text("✈️ Telegram", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                Tab(selected = selectedChannel == "website", onClick = { selectedChannel = "website" }) {
                    Text("💻 Website", modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Thực hiện bộ lọc lọc danh sách hội thoại theo kênh
            val filteredThreads = latestThreads.filter { thread ->
                when (selectedChannel) {
                    "all" -> thread.username != "default_user"
                    "facebook" -> thread.username.startsWith("facebook_")
                    "telegram" -> thread.username.startsWith("telegram_")
                    "website" -> thread.username.startsWith("website_")
                    else -> false
                }
            }

            if (filteredThreads.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Forum, 
                            contentDescription = null, 
                            modifier = Modifier.size(56.dp), 
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Chưa có cuộc trò chuyện nào", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                // 📥 2. HIỂN THỊ DANH SÁCH HỘI THOẠI (Inbox List)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredThreads, key = { it.id }) { thread ->
                        InboxItemRow(thread = thread) {
                            // Khi bấm chọn, chuyển hướng sang khung chat của ID khách tương ứng
                            navController.navigate("chat_screen?username=${thread.username}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxItemRow(
    thread: ChatMessageEntity,
    onClick: () -> Unit
) {
    val platform = thread.username.substringBefore("_")
    val rawId = thread.username.substringAfter("_")

    val (icon, channelName) = when (platform) {
        "facebook" -> Pair("📘", "Facebook Messenger")
        "telegram" -> Pair("✈️", "Telegram Bot")
        "website" -> Pair("💻", "Website Live Chat")
        else -> Pair("💬", "Khách ngoại tuyến")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon kênh hình tròn phân loại
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$channelName (ID: $rawId)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(thread.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (thread.role == "user") "Khách: ${thread.content}" else "Trợ lý: ${thread.content}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}