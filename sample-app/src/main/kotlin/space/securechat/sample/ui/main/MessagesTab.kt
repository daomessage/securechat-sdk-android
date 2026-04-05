package space.securechat.sample.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.contacts.Friend
import space.securechat.sdk.db.SessionEntity
import space.securechat.sdk.messaging.StoredMessage
import space.securechat.sample.ui.theme.*
import space.securechat.sample.ui.FormatUtils.formatMsgTime
import space.securechat.sample.ui.FormatUtils.formatMsgPreview

data class SessionWithPreview(
    val session: SessionEntity,
    val displayName: String,
    val lastMsg: String?,
    val lastMsgTime: Long
)

@Composable
fun MessagesTabContent(
    client: SecureChatClient,
    friends: List<Friend>,
    unreadCounts: Map<String, Int>,
    onFriendClick: (Friend) -> Unit
) {
    var sessions by remember { mutableStateOf<List<SessionWithPreview>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun loadSessions() {
        val rawSessions = client.listSessions()
        val result = rawSessions.map { s ->
            val friend = friends.find { it.conversationId == s.conversationId }
            val history = client.getHistory(s.conversationId, limit = 1)
            val last = history.lastOrNull()
            SessionWithPreview(
                session = s,
                displayName = friend?.nickname ?: s.theirAliasId,
                lastMsg = last?.text?.let { formatMsgPreview(it) },
                lastMsgTime = last?.time ?: s.createdAt
            )
        }.sortedByDescending { it.lastMsgTime }
        sessions = result
    }

    LaunchedEffect(friends) { scope.launch { loadSessions() } }

    DisposableEffect(friends) {
        val unsub = client.on(SecureChatClient.EVENT_MESSAGE) { _: StoredMessage ->
            scope.launch { loadSessions() }
        }
        onDispose { unsub() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("消息", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Divider(color = DividerColor)

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("💬", fontSize = 48.sp)
                    Text("暂无会话", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("前往通讯录添加好友开始聊天", color = ZincText, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn {
                items(sessions) { sp ->
                    val friend = friends.find { it.conversationId == sp.session.conversationId }
                    val unread = unreadCounts[sp.session.conversationId] ?: 0
                    val hasUnread = unread > 0
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (friend != null) onFriendClick(friend) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.size(48.dp).background(Color(0xFF3F3F46), CircleShape), contentAlignment = Alignment.Center) {
                            Text(sp.displayName.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(sp.displayName, color = Color.White, fontSize = 15.sp,
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal)
                            if (sp.lastMsg != null)
                                Text(sp.lastMsg, color = ZincText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(formatMsgTime(sp.lastMsgTime), color = ZincText, fontSize = 11.sp)
                            if (hasUnread) {
                                Box(Modifier.size(20.dp).background(RedDanger, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(if (unread > 99) "99+" else "$unread", color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Divider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
