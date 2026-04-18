package space.securechat.sample.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Base64
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.contacts.Friend
import space.securechat.sdk.messaging.StoredMessage
import space.securechat.sdk.security.SecurityCode
import space.securechat.sdk.security.TrustState
import space.securechat.sample.ui.theme.*
import space.securechat.sample.ui.FormatUtils.formatMsgTime
import space.securechat.sample.ui.FormatUtils.formatMsgPreview

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    client: SecureChatClient,
    friend: Friend,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onStartCall: ((toAliasId: String, enableVideo: Boolean) -> Unit)? = null
) {
    var messages by remember { mutableStateOf<List<StoredMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var isFriendTyping by remember { mutableStateOf(false) }
    var actionMenuTarget by remember { mutableStateOf<StoredMessage?>(null) }
    var replyTarget by remember { mutableStateOf<StoredMessage?>(null) }
    var trustState by remember { mutableStateOf<TrustState>(TrustState.Unverified) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── 真实多媒体拾取器 ──────────────────────────────────────────────
    val pickMediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null) {
                        // 压缩为超微 Base64 当作 thumbnail 传给 PWA 测验真实的解析呈现
                        val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(original, 240, original.height * 240 / original.width, true)
                        val out = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                        val b64Thumbnail = "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                        
                        client.sendImage(friend.conversationId, friend.aliasId, bytes, b64Thumbnail)
                        android.widget.Toast.makeText(context, "图片发送中...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "无法读取图片流: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val pickFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null) {
                        var filename = "未知文档.bin"
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx >= 0) filename = cursor.getString(nameIdx)
                            }
                        }
                        client.sendFile(friend.conversationId, friend.aliasId, bytes, filename)
                        android.widget.Toast.makeText(context, "文件发送并且上云成功！", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "无法读取文件数据", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val requestMicLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            recordAndSend3SecAudio(context, lifecycleScope, client, friend)
        } else {
            android.widget.Toast.makeText(context, "需要麦克风权限才能录音测试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(friend.conversationId) {
        messages = client.getHistory(friend.conversationId, limit = 200)
        trustState = client.security.getTrustState(friend.aliasId)
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    DisposableEffect(friend.conversationId) {
        val unsubMsg = client.on(SecureChatClient.EVENT_MESSAGE) { msg: StoredMessage ->
            if (msg.conversationId == friend.conversationId) {
                messages = messages.toMutableList().also { list ->
                    val idx = list.indexOfFirst { it.id == msg.id }
                    if (idx >= 0) list[idx] = msg else list.add(msg)
                }
                val seq = msg.seq
                if (!msg.isMe && seq != null) {
                    lifecycleScope.launch { client.markAsRead(friend.conversationId, seq, friend.aliasId) }
                }
                scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
            }
        }
        val unsubStatus = client.on<(String, String) -> Unit>(SecureChatClient.EVENT_STATUS_CHANGE) { msgId, status ->
            messages = messages.map { if (it.id == msgId) it.copy(status = status) else it }
        }
        val unsubTyping = client.on<(String, String) -> Unit>(SecureChatClient.EVENT_TYPING) { aliasId, convId ->
            if (convId == friend.conversationId) {
                isFriendTyping = true
                scope.launch { delay(3000); isFriendTyping = false }
            }
        }
        onDispose {
            // 修复 Compose isAttached crash：在节点 detach 前主动清除 IME 焦点
            // 场景：通话来电导致 ChatScreen 被移除时，BasicTextField 仍持有焦点
            focusManager.clearFocus(force = true)
            unsubMsg(); unsubStatus(); unsubTyping()
        }
    }

    if (showVerifyDialog) {
        VerifySecurityDialog(
            client = client,
            friend = friend,
            onClose = { showVerifyDialog = false },
            onVerified = {
                lifecycleScope.launch {
                    trustState = client.security.getTrustState(friend.aliasId)
                }
            }
        )
    }

    // 消息操作菜单（复制 / 回复 / 撤回 / 转发）
    actionMenuTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionMenuTarget = null },
            containerColor = SurfaceBg,
            title = { Text("消息操作", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 复制
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copyText = when (msg.msgType) {
                            "image" -> "[图片消息]"
                            "voice" -> "[语音消息]"
                            "file" -> "[文件] ${msg.caption ?: ""}"
                            else -> msg.text
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", copyText))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        actionMenuTarget = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("📋 复制文本", color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                    }
                    // 回复
                    TextButton(onClick = {
                        replyTarget = msg
                        actionMenuTarget = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("↩️ 回复", color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                    }
                    // 转发（复制消息内容以便粘贴到其他对话）
                    if (msg.msgType == "text" && msg.text.isNotBlank()) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("forward", msg.text))
                            Toast.makeText(context, "消息已复制，可粘贴转发", Toast.LENGTH_SHORT).show()
                            actionMenuTarget = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🔀 转发", color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    // 撤回（仅自己发的消息）
                    if (msg.isMe && msg.msgType != "retracted") {
                        TextButton(onClick = {
                            actionMenuTarget = null
                            lifecycleScope.launch { client.retractMessage(msg.id, friend.aliasId, friend.conversationId) }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🗑️ 撤回消息", color = RedDanger, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    // 重新发送（仅发送失败的消息）
                    if (msg.isMe && msg.status == "failed") {
                        TextButton(onClick = {
                            actionMenuTarget = null
                            lifecycleScope.launch {
                                try { client.sendMessage(friend.conversationId, friend.aliasId, msg.text) }
                                catch (_: Exception) { Toast.makeText(context, "重发失败", Toast.LENGTH_SHORT).show() }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("🔄 重新发送", color = AmberWarn, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionMenuTarget = null }) { Text("取消", color = ZincText) } }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DarkBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 顶栏 (包含盾牌)
        Row(Modifier.fillMaxWidth().background(SurfaceBg).padding(8.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("←", color = ZincText, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(friend.nickname, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isFriendTyping) "正在输入..." else "🔒 端到端加密",
                    color = if (isFriendTyping) AmberWarn else ZincText, fontSize = 11.sp
                )
            }
            // 信任盾牌
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { showVerifyDialog = true }.padding(8.dp)
            ) {
                if (trustState is TrustState.Verified) {
                    Text("🛡️", color = GreenOk, fontSize = 16.sp)
                } else {
                    Text("🛡️", color = AmberWarn, fontSize = 16.sp)
                }
            }

            // 📞 语音通话按钮
            IconButton(
                onClick = { onStartCall?.invoke(friend.aliasId, false) },
                modifier = Modifier.size(36.dp)
            ) {
                Text("📞", fontSize = 18.sp)
            }

            // 📹 视频通话按钮
            IconButton(
                onClick = { onStartCall?.invoke(friend.aliasId, true) },
                modifier = Modifier.size(36.dp)
            ) {
                Text("📹", fontSize = 18.sp)
            }

            // 右上角更多菜单
            var showTopMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showTopMenu = true }) {
                    Text("⋮", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                    modifier = Modifier.background(SurfaceBg)
                ) {
                    DropdownMenuItem(
                        text = { Text("导出聊天记录", color = Color.White) },
                        onClick = {
                            showTopMenu = false
                            exportChatHistory(context, friend, messages)
                        }
                    )
                }
            }
        }
        Divider(color = DividerColor)

        // 消息列表
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages.reversed(), key = { it.id }) { msg ->
                    MessageBubble(msg = msg, client = client, onLongClick = { actionMenuTarget = msg })
                }
            }
        }

        // 回复引用条
        replyTarget?.let { reply ->
            Row(
                Modifier.fillMaxWidth().background(SurfaceBg).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(3.dp).height(32.dp).background(BlueAccent, RoundedCornerShape(2.dp))
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("回复", color = BlueAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        reply.text.take(60),
                        color = ZincText, fontSize = 12.sp, maxLines = 1
                    )
                }
                IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(24.dp)) {
                    Text("✕", color = ZincText, fontSize = 14.sp)
                }
            }
        }

        // 底部输入栏 (H5 风格重建)
        Row(
            Modifier
                .fillMaxWidth()
                .background(DarkBg)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 左侧操作按钮组
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                IconButton(onClick = {
                    pickMediaLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }, modifier = Modifier.size(36.dp)) {
                    Text("🖼️", fontSize = 20.sp)
                }
                IconButton(onClick = { pickFileLauncher.launch("*/*") }, modifier = Modifier.size(36.dp)) {
                    Text("📎", fontSize = 20.sp)
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 中间无边框圆角输入框
            androidx.compose.foundation.text.BasicTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    if (it.isNotEmpty()) client.sendTyping(friend.conversationId, friend.aliasId)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(BlueAccent),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (isRecordingAudio) {
                            Text("🎤 正在录制，松开发送...", color = BlueAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else if (inputText.isEmpty()) {
                            Text("输入消息...", color = ZincMuted, fontSize = 15.sp)
                        } else {
                            innerTextField()
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 右侧操作：语音 或 发送
            Box(
                modifier = Modifier.size(40.dp).padding(bottom = 0.dp),
                contentAlignment = Alignment.Center
            ) {
                if (inputText.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            requestMicLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            return@detectTapGestures
                                        }
                                        
                                        var recorder: android.media.MediaRecorder? = null
                                        val outputFile = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
                                        val startTime = System.currentTimeMillis()
                                        isRecordingAudio = true

                                        try {
                                            recorder = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                android.media.MediaRecorder(context)
                                            } else {
                                                @Suppress("DEPRECATION")
                                                android.media.MediaRecorder()
                                            }).apply {
                                                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                                setOutputFile(outputFile.absolutePath)
                                                prepare()
                                                start()
                                            }
                                        } catch (e: Exception) {
                                            isRecordingAudio = false
                                            return@detectTapGestures
                                        }

                                        val success = try {
                                            tryAwaitRelease()
                                        } finally {
                                            isRecordingAudio = false
                                            try {
                                                recorder?.stop()
                                                recorder?.release()
                                            } catch (e: Exception) {
                                                try { recorder?.release() } catch (_: Exception) {}
                                            }
                                        }

                                        val duration = System.currentTimeMillis() - startTime
                                        if (duration < 500) {
                                            // 太短不发送
                                        } else if (success && outputFile.exists()) {
                                            val bytes = outputFile.readBytes()
                                            lifecycleScope.launch {
                                                try {
                                                    client.sendVoice(friend.conversationId, friend.aliasId, bytes, duration)
                                                } catch (e: Exception) {
                                                    android.widget.Toast.makeText(context, "发送失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isRecordingAudio) "🔴" else "🎤", fontSize = 20.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            val text = inputText.trim(); if (text.isBlank()) return@Button
                            inputText = ""
                            val replyId = replyTarget?.id
                            replyTarget = null
                            lifecycleScope.launch {
                                try { client.sendMessage(friend.conversationId, friend.aliasId, text, replyId) }
                                catch (e: Exception) { android.widget.Toast.makeText(context, "发送失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

// ── 对接 SDK 安全盾盘的验证器 ──────────────────────────────────────────────────────────

@Composable
fun VerifySecurityDialog(
    client: SecureChatClient,
    friend: Friend,
    onClose: () -> Unit,
    onVerified: () -> Unit
) {
    var rawInput by remember { mutableStateOf("") }
    var verifyError by remember { mutableStateOf("") }
    var securityCode by remember { mutableStateOf<SecurityCode?>(null) }
    var myKeys by remember { mutableStateOf<ByteArray?>(null) }
    var theirKeys by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 修复：Dialog 关闭时 OutlinedTextField 仍可能持有焦点，需提前清除
    DisposableEffect(Unit) {
        onDispose { focusManager.clearFocus(force = true) }
    }

    LaunchedEffect(friend.conversationId) {
        try {
            val (code, myKey, theirKey) = client.getSecurityFingerprint(friend.conversationId)
            securityCode = code
            myKeys = myKey
            theirKeys = theirKey
        } catch (e: Exception) {
            verifyError = "无法获取底层握手证书进行展示"
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = SurfaceBg,
        title = {
            Column {
                Text("安全指纹验证", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("通过其他安全渠道确认前 8 个字符。如完全一致，代表无人窃听你们的端到端通信。", color = ZincText, fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (verifyError.isNotEmpty()) {
                    Text(verifyError, color = RedDanger, fontSize = 13.sp)
                } else {
                    val fullCodeHex = securityCode?.fingerprintHex ?: ""
                    val shortCode = if (fullCodeHex.length >= 8) fullCodeHex.substring(0, 8) else "..."
                    val previewCode = if (fullCodeHex.length >= 20) fullCodeHex.substring(8, 20) + "..." else ""
                    
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkBg)) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text(
                                text = shortCode,
                                color = Color.White, fontSize = 20.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            )
                            Text(
                                text = previewCode,
                                color = ZincMuted, fontSize = 20.sp, letterSpacing = 2.sp,
                                style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = rawInput, onValueChange = { rawInput = it },
                        label = { Text("在此输入对方的 8 位安全指纹前缀进行核验", color = ZincText) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetCode = securityCode?.fingerprintHex?.take(8)?.lowercase() ?: ""
                    val cleanInput = rawInput.replace(" ", "").lowercase()
                    if (cleanInput.length >= 8 && cleanInput.take(8) == targetCode) {
                        scope.launch {
                            try {
                                if (myKeys != null && theirKeys != null) {
                                    client.security.markAsVerified(friend.aliasId, myKeys!!, theirKeys!!)
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                            onVerified()
                            onClose()
                        }
                    } else {
                        verifyError = "输入有误：未能匹配前8个字符，核验拦截"
                    }
                },
                enabled = rawInput.replace(" ", "").length >= 8,
                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
            ) { Text("验证") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭", color = ZincText) }
        }
    )
}

// ── MessageBubble ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(msg: StoredMessage, client: SecureChatClient? = null, onLongClick: (() -> Unit)?) {
    val isMe = msg.isMe
    val isRetracted = msg.msgType == "retracted"

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp
                    ))
                    .background(if (isMe) BlueAccent else SurfaceBg)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { if (!isRetracted) onLongClick?.invoke() }
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    if (msg.replyToId != null) {
                        Text(
                            text = "回复: [被引用的消息]", // 引用展示
                            color = ZincMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 4.dp).background(DarkBg, RoundedCornerShape(4.dp)).padding(4.dp)
                        )
                    }
                    if (msg.msgType == "image") {
                        Text(
                            text = if (isRetracted) "消息已撤回" else "📷 相册发来的图片",
                            color = if (isRetracted) ZincText else Color.White,
                            fontSize = 15.sp
                        )
                        if (!isRetracted) {
                            ImageMessageBubble(
                                thumbnailBase64 = msg.caption,
                                mediaUrl = msg.mediaUrl,
                                conversationId = msg.conversationId,
                                client = client
                            )
                        }
                    } else if (msg.msgType == "voice" && !isRetracted) {
                        VoiceMessageBubble(mediaUrl = msg.mediaUrl, conversationId = msg.conversationId, client = client)
                    } else if (msg.msgType == "file" && !isRetracted) {
                        FileMessageBubble(msg.caption ?: "未知文件", msg.mediaUrl ?: "")
                    } else {
                        val displayContent = if (isRetracted) "消息已撤回" else formatMsgPreview(msg.text)
                        Text(
                            text = displayContent,
                            color = if (isRetracted) ZincText else Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)) {
                Text(formatMsgTime(msg.time), color = ZincMuted, fontSize = 10.sp)
                if (isMe && !isRetracted) {
                    val statusStr = when (msg.status) {
                        "sending" -> "○"
                        "sent" -> "✓"
                        "delivered" -> "✓✓"
                        "read" -> "👁✓✓"
                        "failed" -> "✗"
                        else -> ""
                    }
                    Text(
                        text = statusStr,
                        color = when (msg.status) {
                            "read" -> BlueAccent
                            "delivered" -> GreenOk
                            "failed" -> RedDanger
                            else -> ZincMuted
                        },
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// ── 录音工具函数 ──────────────────────────────────────────────
private fun recordAndSend3SecAudio(
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    client: SecureChatClient,
    friend: space.securechat.sdk.contacts.Friend
) {
    val outputFile = java.io.File(context.cacheDir, "test_audio_${System.currentTimeMillis()}.m4a")
    try {
        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.media.MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }.apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        android.widget.Toast.makeText(context, "🎤 正在录制 3 秒环境音...", android.widget.Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            kotlinx.coroutines.delay(3000)
            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                android.util.Log.e("VoiceRecord", "Stop failed: ${e.message}", e)
                try { recorder.release() } catch (e2: Exception) {}
                android.widget.Toast.makeText(context, "录音失败 (可能是模拟器麦克风无输入)，请检查音源设置", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val duration = 3000L
                val bytes = outputFile.readBytes()
                // 采用全正规 API 进行语音直转上链 S3！
                client.sendVoice(friend.conversationId, friend.aliasId, bytes, duration)
                android.widget.Toast.makeText(context, "✅ 实况录音发送并且上云成功！", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("VoiceRecord", "Upload failed: ${e.message}", e)
                android.widget.Toast.makeText(context, "上传录音失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("VoiceRecord", "Start failed: ${e.message}", e)
        android.widget.Toast.makeText(context, "无法启动麦克风录制: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}


// ── 工具函数: 解析并预览 Base64 图片 ────────────────────────────────────────────────────────
@Composable
fun Base64ImagePreview(thumbnail: String?) {
    val boxModifier = Modifier
        .padding(top = 8.dp)
        .fillMaxWidth()
        .heightIn(min = 80.dp, max = 200.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(0x33FFFFFF))

    if (thumbnail.isNullOrBlank()) {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            Text("📷 图片已发送", color = Color.Gray, fontSize = 11.sp)
        }
        return
    }

    val base64String = if (thumbnail.startsWith("data:image")) thumbnail.substringAfter("base64,") else thumbnail

    var bitmap: android.graphics.Bitmap? = null

    try {
        val decodedString = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
        bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
    } catch (_: Exception) {}

    if (bitmap != null) {
        Box(modifier = boxModifier) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
    } else {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            Text("⚠ 缩略图未能正确解码渲染", color = Color(0xFFEF5350), fontSize = 11.sp)
        }
    }
}

// ── 工具组件: 语音气泡（ExoPlayer 真实播放 — 兼容 WebM/M4A/Opus/AAC） ──────────────
@Composable
fun VoiceMessageBubble(
    mediaUrl: String? = null,
    conversationId: String? = null,
    client: SecureChatClient? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf(false) }

    // ExoPlayer 实例（主线程创建，设置正确的音频属性）
    val exoPlayer = remember {
        val audioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
            volume = 1.0f
        }
    }

    // 缓存解密后的临时文件路径，避免重复下载
    val tempFileRef = remember { mutableStateOf<java.io.File?>(null) }

    // 用 DisposableEffect 注册 listener，确保与 Compose 生命周期绑定
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                android.util.Log.d("VoiceBubble", "onIsPlayingChanged: $playing")
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.d("VoiceBubble", "onPlaybackStateChanged: $playbackState")
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    isPlaying = false
                    // 播放结束后 seek 回起点，方便下次重新播放
                    exoPlayer.seekTo(0)
                    exoPlayer.pause()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("VoiceBubble", "ExoPlayer error: ${error.message}", error)
                isPlaying = false
                playError = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            tempFileRef.value?.delete()
        }
    }

    fun playAudio() {
        if (mediaUrl.isNullOrBlank() || conversationId.isNullOrBlank() || client == null) {
            playError = true
            return
        }
        // 正在播放 → 暂停
        if (isPlaying) {
            exoPlayer.pause()
            return
        }
        // 已有解密文件（之前下载过）→ 直接继续播放
        if (tempFileRef.value != null && tempFileRef.value!!.exists()) {
            // 如果播放已结束（到末尾了），需要从头开始
            if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
            }
            exoPlayer.play()
            return
        }
        // 首次播放：下载 + 解密 + 准备
        isLoading = true
        playError = false
        scope.launch {
            try {
                val session = client.getSessionForConversation(conversationId)
                val sessionKey = java.util.Base64.getDecoder().decode(session.sessionKeyBase64)

                val tempFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val decryptedBytes = client.media.downloadAndDecrypt(mediaUrl, sessionKey)
                    val ext = client.media.detectAudioExtension(decryptedBytes)
                    android.util.Log.d("VoiceBubble", "解密完成: ${decryptedBytes.size} bytes, 格式: $ext, 头: ${decryptedBytes.take(16).joinToString(" ") { "%02X".format(it) }}")
                    val f = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}$ext")
                    f.writeBytes(decryptedBytes)
                    f
                }
                tempFileRef.value = tempFile

                // ExoPlayer 的 setMediaItem / prepare / play 必须在主线程
                val mediaItem = androidx.media3.common.MediaItem.fromUri(
                    android.net.Uri.fromFile(tempFile)
                )
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (e: Exception) {
                android.util.Log.e("VoiceBubble", "播放失败: ${e.message}", e)
                playError = true
            } finally {
                isLoading = false
            }
        }
    }

    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .widthIn(min = 120.dp, max = 200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x22FFFFFF))
            .clickable { playAudio() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(if (isPlaying) "⏸" else "▶", color = Color(0xFF64B5F6), fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(8.dp).background(Color.White, CircleShape))
            Box(Modifier.width(3.dp).height(14.dp).background(Color.White, CircleShape))
            Box(Modifier.width(3.dp).height(20.dp).background(Color.White, CircleShape))
            Box(Modifier.width(3.dp).height(10.dp).background(Color.White, CircleShape))
            Box(Modifier.width(3.dp).height(16.dp).background(Color.White, CircleShape))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            if (playError) "失败" else if (isPlaying) "播放中" else "语音",
            color = if (playError) Color(0xFFEF5350) else Color.White,
            fontSize = 12.sp
        )
    }
}

// ── 工具组件: 文件气泡 ────────────────────────────────────────────────────────
@Composable
fun FileMessageBubble(fileName: String, url: String) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x11FFFFFF))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📄", fontSize = 24.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(fileName, color = Color.White, fontSize = 14.sp, maxLines = 1)
            Text("端到端加密文件", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ── 工具组件: 图片气泡和高清预览 ────────────────────────────────────────────────────────
@Composable
fun ImageMessageBubble(thumbnailBase64: String?, mediaUrl: String?, conversationId: String, client: space.securechat.sdk.SecureChatClient?) {
    var showDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (!mediaUrl.isNullOrEmpty() && client != null) showDialog = true }
    ) {
        // Thumbnail
        if (!thumbnailBase64.isNullOrBlank()) {
            Base64ImagePreview(thumbnailBase64)
        } else {
            Box(Modifier.fillMaxWidth().height(150.dp).background(DarkBg), contentAlignment = Alignment.Center) {
                Text("📷 点击查看原图", color = ZincMuted)
            }
        }
        
        // Overlay HD hint
        if (!mediaUrl.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("HD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDialog && !mediaUrl.isNullOrEmpty() && client != null) {
        ImagePreviewDialog(mediaUrl, conversationId, client, onDismiss = { showDialog = false })
    }
}

@Composable
fun ImagePreviewDialog(mediaUrl: String, conversationId: String, client: space.securechat.sdk.SecureChatClient, onDismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isDownloading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(mediaUrl) {
        try {
            val bytes = client.downloadMedia(conversationId, mediaUrl)
            bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            errorMsg = e.message
        } finally {
            isDownloading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (isDownloading) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Full Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Text("加载失败: $errorMsg", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("← 返回", color = Color.White) }
                if (bitmap != null) {
                    TextButton(onClick = {
                        try {
                            val resolver = context.contentResolver
                            val contentValues = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "SecureChat_${System.currentTimeMillis()}.jpg")
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/SecureChat")
                            }
                            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { out ->
                                    bitmap!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                                }
                                android.widget.Toast.makeText(context, "✅ 原图已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("⬇️ 保存", color = BlueAccent)
                    }
                }
            }
        }
    }
}

// ── 导出聊天记录逻辑 ──────────────────────────────────────────────
private fun exportChatHistory(
    context: android.content.Context,
    friend: space.securechat.sdk.contacts.Friend,
    messages: List<space.securechat.sdk.messaging.StoredMessage>
) {
    if (messages.isEmpty()) {
        android.widget.Toast.makeText(context, "没有可导出的记录", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val fileName = "SecureChat_${friend.nickname}_${System.currentTimeMillis()}.txt"
        val file = java.io.File(context.cacheDir, fileName)
        
        file.bufferedWriter().use { writer ->
            writer.write("聊天记录：与 ${friend.nickname} (${friend.aliasId})\n")
            writer.write("导出时间：${space.securechat.sample.ui.FormatUtils.formatMsgTime(System.currentTimeMillis())}\n")
            writer.write("=" .repeat(40) + "\n\n")

            // 消息通常是倒序的 (最新的在前面)，导出时应该顺序列出
            messages.reversed().forEach { msg ->
                val sender = if (msg.isMe) "我" else friend.nickname
                val time = space.securechat.sample.ui.FormatUtils.formatMsgTime(msg.time)
                val content = when (msg.msgType) {
                    "text" -> msg.text
                    "voice" -> "[语音消息]"
                    "image" -> "[图片消息]"
                    "file" -> "[文件消息]"
                    "system" -> "[系统消息] ${msg.text}"
                    else -> "[未知消息]"
                }
                writer.write("[$time] $sender: $content\n")
            }
        }

        // 调用系统分享
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Chat_Export", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = android.content.Intent.createChooser(intent, "导出聊天记录")
        chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)

    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
