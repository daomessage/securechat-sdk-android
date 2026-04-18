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
import space.securechat.sdk.channels.ChannelTradeOrder
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
                                        val err = e.message ?: ""
                                        if (err.contains("QUOTA_EXCEEDED")) {
                                            try {
                                                val order = client.channels.buyQuota()
                                                Toast.makeText(context, "配额上限！请向 ${order.payTo} 支付补充配额", Toast.LENGTH_LONG).show()
                                            } catch (ex: Exception) {
                                                Toast.makeText(context, "获取配额订单失败: ${ex.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "创建失败: $err", Toast.LENGTH_SHORT).show()
                                        }
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(ch.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                if (ch.forSale == true) {
                                    Text(
                                        "🏷 ${ch.salePrice?.toInt() ?: "?"} USDT",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.background(Color(0x26F59E0B), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
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
    var channelDetail by remember { mutableStateOf(channel) }
    var showListForSale by remember { mutableStateOf(false) }
    var tradeOrder by remember { mutableStateOf<ChannelTradeOrder?>(null) }
    var isBuying by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(channel.id) {
        try {
            posts = client.channels.getPosts(channel.id)
            channelDetail = client.channels.getDetail(channel.id)
        } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(DarkBg)) {
        // 顶部导航
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← 返回", color = ZincText) }
            Column(Modifier.weight(1f)) {
                Text(channelDetail.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (channelDetail.forSale == true) {
                    Text(
                        "🏷 出售中 · ${channelDetail.salePrice?.toInt()} USDT",
                        color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
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

        // 帖子列表
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

        // 底部操作区
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Owner: 发帖
            if (channelDetail.role == "admin" || channelDetail.role == "owner") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
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

            // Owner: 挂牌出售按钮
            if (channelDetail.role == "owner" && channelDetail.forSale != true) {
                Button(
                    onClick = { showListForSale = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF92400E)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🏷 挂牌出售此频道") }
            }

            // 买家: 购买按钮
            if (channelDetail.forSale == true && channelDetail.role != "owner") {
                Button(
                    onClick = {
                        isBuying = true
                        lifecycleScope.launch {
                            try {
                                val order = client.channels.buyChannel(channel.id)
                                tradeOrder = order
                            } catch (e: Exception) {
                                Toast.makeText(context, "购买失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally { isBuying = false }
                        }
                    },
                    enabled = !isBuying,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isBuying) "正在生成订单..." else "💰 购买此频道 · ${channelDetail.salePrice?.toInt()} USDT") }
            }
        }
    }

    // 挂牌出售对话框
    if (showListForSale) {
        var priceInput by remember { mutableStateOf("") }
        var isListing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isListing) showListForSale = false },
            containerColor = SurfaceBg,
            title = { Text("挂牌出售", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("频道: ${channelDetail.name}", color = ZincText, fontSize = 13.sp)
                    Text("⚠️ 交易完成后频道所有权将自动转移给买家", color = Color(0xFFFBBF24), fontSize = 12.sp)
                    OutlinedTextField(
                        value = priceInput, onValueChange = { priceInput = it.filter { c -> c.isDigit() } },
                        label = { Text("售价 (USDT)", color = ZincText) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = priceInput.toIntOrNull() ?: return@Button
                        if (price <= 0) return@Button
                        isListing = true
                        lifecycleScope.launch {
                            try {
                                client.channels.listForSale(channel.id, price)
                                channelDetail = client.channels.getDetail(channel.id)
                                showListForSale = false
                                Toast.makeText(context, "挂牌成功！", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "挂牌失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally { isListing = false }
                        }
                    },
                    enabled = !isListing && (priceInput.toIntOrNull() ?: 0) > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) { Text(if (isListing) "提交中..." else "确认挂牌") }
            },
            dismissButton = {
                TextButton(onClick = { showListForSale = false }, enabled = !isListing) { Text("取消", color = ZincText) }
            }
        )
    }

    // 购买支付弹窗
    if (tradeOrder != null) {
        val order = tradeOrder!!
        var isPolling by remember { mutableStateOf(false) }
        var pollStatus by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!isPolling) tradeOrder = null },
            containerColor = SurfaceBg,
            title = { Text("购买频道", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("频道: ${channelDetail.name}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("金额: ${order.priceUsdt.toInt()} USDT", color = Color(0xFFFBBF24), fontSize = 14.sp)
                    Divider(color = DividerColor)
                    Text("TRON (TRC-20) 收款地址:", color = ZincText, fontSize = 11.sp)
                    Text(order.payTo, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("address", order.payTo))
                            Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
                        }.background(Color(0xFF1C1C1E), RoundedCornerShape(8.dp)).padding(8.dp)
                    )
                    Text("请向此地址转入 ${order.priceUsdt.toInt()} USDT", color = ZincText, fontSize = 11.sp)
                    if (pollStatus.isNotEmpty()) {
                        Text(pollStatus, color = if (pollStatus.contains("失败") || pollStatus.contains("过期")) Color.Red else Color(0xFF34D399), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isPolling = true
                        pollStatus = "正在确认链上支付..."
                        lifecycleScope.launch {
                            repeat(60) {
                                try {
                                    val status = client.vanity.orderStatus(order.orderId)
                                    if (status == "confirmed") {
                                        pollStatus = "✅ 支付确认！频道已转移"
                                        channelDetail = client.channels.getDetail(channel.id)
                                        kotlinx.coroutines.delay(1500)
                                        tradeOrder = null
                                        return@launch
                                    } else if (status == "expired") {
                                        pollStatus = "❌ 订单已过期"
                                        isPolling = false
                                        return@launch
                                    }
                                } catch (_: Exception) {}
                                kotlinx.coroutines.delay(3000)
                            }
                            pollStatus = "确认超时，请稍后重试"
                            isPolling = false
                        }
                    },
                    enabled = !isPolling,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) { Text(if (isPolling) "确认中..." else "我已付款") }
            },
            dismissButton = {
                TextButton(onClick = { tradeOrder = null }, enabled = !isPolling) { Text("取消", color = ZincText) }
            }
        )
    }
}
