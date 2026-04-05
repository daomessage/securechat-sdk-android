package space.securechat.sample.ui.main

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.contacts.Friend
import space.securechat.sample.ui.theme.*

fun generateQrBitmap(content: String): Bitmap? {
    return try {
        val encoder = BarcodeEncoder()
        encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 600, 600)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun ContactsTabContent(
    client: SecureChatClient,
    initialFriends: List<Friend>,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    onFriendListChanged: (List<Friend>) -> Unit,
    onFriendClick: (Friend) -> Unit
) {
    var allFriends by remember { mutableStateOf(initialFriends) }
    var searchId by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<space.securechat.sdk.contacts.UserProfile?>(null) }
    var lookupError by remember { mutableStateOf("") }
    var isLooking by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 当前用户的 Alias ID 用于生成二维码
    var myAliasId by remember { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        myAliasId = client.auth.getLocalAliasId() ?: ""
    }

    LaunchedEffect(toastMsg) {
        if (toastMsg.isNotEmpty()) { Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show(); toastMsg = "" }
    }

    // 10秒轮询
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val updated = client.contacts.syncFriends()
                allFriends = updated; onFriendListChanged(updated)
            } catch (_: Exception) {}
            delay(10_000)
        }
    }

    fun handleSearch(targetId: String) {
        searchId = targetId
        isLooking = true
        lifecycleScope.launch {
            try {
                lookupResult = client.contacts.lookupUser(searchId.trim()); lookupError = ""
            } catch (e: Exception) {
                lookupError = when {
                    e.message?.contains("404") == true -> "用户不存在"; else -> "搜索失败: ${e.message}"
                }; lookupResult = null
            } finally { isLooking = false }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val content = result.contents
            // 解析前缀 securechat://add?aliasId=xxx 或直拿文本
            val matchId = Regex("aliasId=([^&]+)").find(content)?.groupValues?.get(1) ?: content
            handleSearch(matchId)
        }
    }

    val pendingReceived = allFriends.filter { it.friendStatus == "pending" && it.friendDirection == "received" }
    val pendingSent     = allFriends.filter { it.friendStatus == "pending" && it.friendDirection == "sent" }
    val accepted        = allFriends.filter { it.friendStatus == "accepted" }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            containerColor = SurfaceBg,
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("我的交友名片", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                    val bitmap = generateQrBitmap("securechat://add?aliasId=$myAliasId")
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "My QR Code",
                            modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                        )
                    } else {
                        Text("二维码生成失败", color = RedDanger)
                    }
                    Text("扫一扫上面的二维码图案，加我为好友", color = ZincMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
                    Text(myAliasId, color = ZincMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) { Text("关闭", color = BlueAccent) }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // 搜索栏
        Row(Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchId, onValueChange = { searchId = it; lookupResult = null; lookupError = "" },
                placeholder = { Text("输入好友 Alias ID", color = ZincText) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f), singleLine = true
            )
            Button(
                onClick = {
                    if (!isLooking && searchId.isNotBlank()) handleSearch(searchId)
                },
                enabled = !isLooking && searchId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (isLooking) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("搜索")
            }
        }

        // 二维码操作区
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { showQrDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ZincMuted),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) { Text("我的二维码", color = Color.White, fontSize = 14.sp) }

            Button(
                onClick = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("请将取景框对准二维码")
                        setCameraId(0) // Use a specific camera of the device
                        setBeepEnabled(true)
                        setBarcodeImageEnabled(true)
                        setOrientationLocked(false)
                    }
                    scanLauncher.launch(options)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ZincMuted),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) { Text("扫一扫", color = Color.White, fontSize = 14.sp) }
        }

        if (lookupError.isNotEmpty()) Text(lookupError, color = RedDanger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))

        lookupResult?.let { user ->
            Card(Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceBg)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(BlueAccent, CircleShape), contentAlignment = Alignment.Center) {
                        Text(user.aliasId.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(user.aliasId, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = {
                            if (!isSending) {
                                isSending = true
                                lifecycleScope.launch {
                                    try {
                                        client.contacts.sendFriendRequest(user.aliasId)
                                        // 立即刷新好友列表
                                        val updated = client.contacts.syncFriends()
                                        allFriends = updated; onFriendListChanged(updated)
                                        toastMsg = "好友请求已发送"; lookupResult = null; searchId = ""
                                    } catch (e: Exception) {
                                        toastMsg = when {
                                            e.message?.contains("409") == true -> "请求已存在，请查看等待确认列表"
                                            e.message?.contains("400") == true -> "请求失败: ${e.message}"
                                            e.message?.contains("404") == true -> "用户不存在"
                                            else -> "添加失败: ${e.message}"
                                        }
                                    } finally { isSending = false }
                                }
                            }
                        },
                        enabled = !isSending,
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("添加", fontSize = 13.sp) }
                }
            }
        }

        Divider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn {
            // ── 分区1：收到的好友请求 ──
            if (pendingReceived.isNotEmpty()) {
                item {
                    SectionHeader("📨 收到的好友请求 (${pendingReceived.size})")
                }
                items(pendingReceived) { f ->
                    PendingRequestRow(f) {
                        lifecycleScope.launch {
                            try {
                                client.contacts.acceptFriendRequest(f.friendshipId)
                                val updated = client.contacts.syncFriends()
                                allFriends = updated; onFriendListChanged(updated)
                                toastMsg = "已接受 ${f.nickname} 的好友申请"
                            } catch (e: Exception) {
                                toastMsg = "操作失败: ${e.message}"
                                val updated = client.contacts.syncFriends()
                                allFriends = updated; onFriendListChanged(updated)
                            }
                        }
                    }
                }
            }

            // ── 分区2：等待对方确认（不可省略）──
            item { SectionHeader("⏳ 等待对方确认 (${pendingSent.size})") }
            if (pendingSent.isEmpty()) {
                item {
                    Text("暂无待确认的请求", color = ZincMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            } else {
                items(pendingSent) { f -> FriendRowSimple(f, showPending = true, onClick = null) }
            }

            // ── 分区3：我的好友 ──
            item { SectionHeader("👥 我的好友 (${accepted.size})") }
            if (accepted.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("暂无好友，搜索 Alias ID 或扫一扫添加", color = ZincMuted, fontSize = 13.sp)
                    }
                }
            } else {
                items(accepted) { f -> FriendRowSimple(f, showPending = false, onClick = { onFriendClick(f) }) }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, color = ZincText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0D0D0F)).padding(16.dp, 8.dp))
}

@Composable
fun PendingRequestRow(friend: Friend, onAccept: () -> Unit) {
    var accepting by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).background(AmberWarn, CircleShape), contentAlignment = Alignment.Center) {
            Text(friend.nickname.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(friend.nickname, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(friend.aliasId, color = ZincText, fontSize = 12.sp)
        }
        Button(
            onClick = { if (!accepting) { accepting = true; onAccept() } },
            enabled = !accepting,
            colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) { Text(if (accepting) "处理中..." else "接受", fontSize = 13.sp) }
    }
}

@Composable
fun FriendRowSimple(friend: Friend, showPending: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).background(Color(0xFF3F3F46), CircleShape), contentAlignment = Alignment.Center) {
            Text(friend.nickname.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(friend.nickname, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(friend.aliasId, color = ZincText, fontSize = 12.sp)
        }
        if (showPending) {
            Text("等待中", color = ZincMuted, fontSize = 12.sp,
                modifier = Modifier.background(SurfaceBg, RoundedCornerShape(6.dp)).padding(8.dp, 4.dp))
        } else {
            Text("→", color = ZincMuted, fontSize = 18.sp)
        }
    }
}
