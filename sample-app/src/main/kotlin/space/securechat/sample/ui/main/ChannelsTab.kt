package space.securechat.sample.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.channels.ChannelInfo
import space.securechat.sdk.http.ChannelPost
import space.securechat.sample.ui.theme.*
import space.securechat.sample.ui.FormatUtils.formatMsgTime

@Composable
fun ChannelsTabContent(client: SecureChatClient, lifecycleScope: kotlinx.coroutines.CoroutineScope) {
    var channels by remember { mutableStateOf<List<ChannelInfo>>(emptyList()) }
    var searchQ by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var selectedChannel by remember { mutableStateOf<ChannelInfo?>(null) }

    LaunchedEffect(Unit) {
        try { channels = client.channels.getMine() } catch (_: Exception) {}
    }

    if (selectedChannel != null) {
        ChannelDetailView(client, selectedChannel!!, lifecycleScope, onBack = { selectedChannel = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        var showCreateDialog by remember { mutableStateOf(false) }

        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("频道", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showCreateDialog = true }) {
                Text("➕", fontSize = 20.sp)
            }
        }
        
        if (showCreateDialog) {
            var newName by remember { mutableStateOf("") }
            var newDesc by remember { mutableStateOf("") }
            var isCreating by remember { mutableStateOf(false) }
            val context = LocalContext.current
            
            AlertDialog(
                onDismissRequest = { if (!isCreating) showCreateDialog = false },
                containerColor = SurfaceBg,
                title = { Text("新建频道", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it },
                            label = { Text("频道名称", color = ZincText) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        OutlinedTextField(
                            value = newDesc, onValueChange = { newDesc = it },
                            label = { Text("频道描述", color = ZincText) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                isCreating = true
                                lifecycleScope.launch {
                                    try {
                                        client.channels.create(newName.trim(), newDesc.trim(), true)
                                        channels = client.channels.getMine()
                                        showCreateDialog = false
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally { isCreating = false }
                                }
                            }
                        },
                        enabled = !isCreating && newName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                    ) { Text(if (isCreating) "创建中" else "创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }, enabled = !isCreating) { Text("取消", color = ZincText) }
                }
            )
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQ, onValueChange = { searchQ = it },
                placeholder = { Text("搜索频道关键词", color = ZincText) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f), singleLine = true
            )
            Button(
                onClick = {
                    if (!isSearching && searchQ.isNotBlank()) {
                        isSearching = true
                        lifecycleScope.launch {
                            try { channels = client.channels.search(searchQ) } catch (_: Exception) {}
                            finally { isSearching = false }
                        }
                    }
                },
                enabled = !isSearching,
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) { Text("搜索") }
        }
        Divider(color = DividerColor)

        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📢", fontSize = 48.sp)
                    Text("暂无频道", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("搜索关键词发现感兴趣的频道", color = ZincText, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn {
                items(channels) { ch ->
                    Row(Modifier.fillMaxWidth().clickable { selectedChannel = ch }.padding(16.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).background(BlueAccent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) { Text("📢", fontSize = 20.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(ch.description, color = ZincText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (ch.isSubscribed == true) Text("已订阅", color = GreenOk, fontSize = 12.sp)
                    }
                    Divider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
fun ChannelDetailView(
    client: SecureChatClient,
    channel: ChannelInfo,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit
) {
    var posts by remember { mutableStateOf<List<ChannelPost>>(emptyList()) }
    var postText by remember { mutableStateOf("") }
    var isSubscribed by remember { mutableStateOf(channel.isSubscribed == true) }
    val context = LocalContext.current

    LaunchedEffect(channel.id) {
        try { posts = client.channels.getPosts(channel.id) } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(DarkBg)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", color = ZincText) }
            Text(channel.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    lifecycleScope.launch {
                        try {
                            if (isSubscribed) { client.channels.unsubscribe(channel.id); isSubscribed = false }
                            else { client.channels.subscribe(channel.id); isSubscribed = true }
                        } catch (e: Exception) { Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSubscribed) SurfaceBg else BlueAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) { Text(if (isSubscribed) "取消订阅" else "订阅", fontSize = 13.sp) }
        }
        Divider(color = DividerColor)

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(posts) { post ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(post.author_alias_id, color = BlueAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(formatMsgTime(post.created_at.toLongOrNull() ?: 0L), color = ZincText, fontSize = 11.sp)
                    }
                    Text(post.content, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Divider(color = DividerColor)
            }
        }

        if (channel.role == "admin" || channel.role == "owner") {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = postText, onValueChange = { postText = it },
                    placeholder = { Text("发布内容...", color = ZincText) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.weight(1f), singleLine = true
                )
                Button(
                    onClick = {
                        val content = postText.trim(); if (content.isEmpty()) return@Button
                        postText = ""
                        lifecycleScope.launch {
                            try {
                                client.channels.post(channel.id, content)
                                posts = client.channels.getPosts(channel.id)
                            } catch (e: Exception) { Toast.makeText(context, "发布失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) { Text("发布") }
            }
        }
    }
}
