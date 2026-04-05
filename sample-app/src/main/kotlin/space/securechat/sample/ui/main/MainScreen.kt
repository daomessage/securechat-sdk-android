@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.securechat.sample.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.contacts.Friend
import space.securechat.sdk.messaging.StoredMessage
import space.securechat.sample.ui.theme.*

@Composable
fun MainScreen(
    client: SecureChatClient,
    aliasId: String, nickname: String, mnemonic: String,
    friends: List<Friend>,
    networkState: space.securechat.sdk.messaging.WSTransport.NetworkState,
    onFriendClick: (Friend) -> Unit,
    onFriendListChanged: (List<Friend>) -> Unit,
    onLogout: () -> Unit,
    lifecycleScope: kotlinx.coroutines.CoroutineScope
) {
    var activeTab by remember { mutableStateOf("messages") }
    var pendingCount by remember { mutableIntStateOf(0) }
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(friends) {
        pendingCount = friends.count { it.friendStatus == "pending" && it.friendDirection == "received" }
    }

    DisposableEffect(Unit) {
        val unsub = client.on(SecureChatClient.EVENT_MESSAGE) { msg: StoredMessage ->
            if (activeTab != "chat") {
                unreadCounts = unreadCounts.toMutableMap().also { it[msg.conversationId] = (it[msg.conversationId] ?: 0) + 1 }
            }
        }
        onDispose { unsub() }
    }

    Scaffold(
        containerColor = DarkBg,
        bottomBar = {
            NavigationBar(containerColor = SurfaceBg, tonalElevation = 0.dp) {
                listOf(
                    Triple("messages", "💬", "消息"),
                    Triple("contacts", "👥", "通讯录"),
                    Triple("channels", "📢", "频道"),
                    Triple("settings", "⚙️", "设置")
                ).forEach { (tab, icon, label) ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = {
                            BadgedBox(badge = {
                                val count = when (tab) {
                                    "messages" -> unreadCounts.values.sum()
                                    "contacts" -> pendingCount
                                    else -> 0
                                }
                                if (count > 0) Badge { Text(if (count > 99) "99+" else "$count") }
                            }) { Text(icon, fontSize = 20.sp) }
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BlueAccent, selectedTextColor = BlueAccent,
                            unselectedIconColor = ZincText, unselectedTextColor = ZincText,
                            indicatorColor = DividerColor
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            NetworkBanner(networkState)
            when (activeTab) {
                "messages" -> MessagesTabContent(client, friends, unreadCounts,
                    onFriendClick = { f ->
                        unreadCounts = unreadCounts.toMutableMap().also { it.remove(f.conversationId) }
                        onFriendClick(f)
                    }
                )
                "contacts" -> ContactsTabContent(client, friends, lifecycleScope,
                    onFriendListChanged = { updated ->
                        onFriendListChanged(updated)
                        pendingCount = updated.count { it.friendStatus == "pending" && it.friendDirection == "received" }
                    },
                    onFriendClick = { f ->
                        unreadCounts = unreadCounts.toMutableMap().also { it.remove(f.conversationId) }
                        onFriendClick(f)
                    }
                )
                "channels" -> ChannelsTabContent(client, lifecycleScope)
                "settings" -> SettingsTabContent(aliasId, nickname, mnemonic, client, lifecycleScope, onLogout)
            }
        }
    }
}

@Composable
fun NetworkBanner(networkState: space.securechat.sdk.messaging.WSTransport.NetworkState) {
    var showRecovered by remember { mutableStateOf(false) }
    var prevState by remember { mutableStateOf<space.securechat.sdk.messaging.WSTransport.NetworkState>(
        space.securechat.sdk.messaging.WSTransport.NetworkState.Closed) }

    LaunchedEffect(networkState) {
        if (prevState !is space.securechat.sdk.messaging.WSTransport.NetworkState.Connected
            && networkState is space.securechat.sdk.messaging.WSTransport.NetworkState.Connected) {
            showRecovered = true; delay(2000); showRecovered = false
        }
        prevState = networkState
    }

    val (bgColor, text) = when {
        showRecovered -> GreenOk to "✅ 连接已恢复"
        networkState is space.securechat.sdk.messaging.WSTransport.NetworkState.Disconnected ->
            RedDanger to "⚠️ 连接中断，正在重连..."
        networkState is space.securechat.sdk.messaging.WSTransport.NetworkState.Connecting ->
            AmberWarn to "🔄 正在连接..."
        else -> return
    }

    Box(Modifier.fillMaxWidth().background(bgColor).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
