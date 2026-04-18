package space.securechat.sdk.messaging

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import space.securechat.sdk.crypto.CryptoModule
import space.securechat.sdk.db.*
import space.securechat.sdk.keys.KeyDerivation
import java.util.Base64
import java.util.UUID

/**
 * 🔒 MessageManager — E2EE 消息收发
 *
 * 职责（对标 TS SDK messaging/index.ts）：
 *   发送：组装 JSON 帧 → AES-GCM 加密 payload → WS 发送
 *   接收：解析 WS 帧 → 解密 → 存 Room DB → 回调 App
 *   撤回：发 retract 帧 → 本地替换
 *
 * 👤 App 调用：send* 系列函数；接收通过 SecureChatClient.on("message") 回调
 * 🔒 SDK 自动：解密、持久化、会话密钥管理
 */
class MessageManager(
    private val transport: WSTransport,
    private val db: SecureChatDatabase,
    private val security: space.securechat.sdk.security.SecurityModule,
    private val scope: CoroutineScope
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // 消息回调（SecureChatClient 注入）
    var onMessage: ((StoredMessage) -> Unit)? = null
    var onStatusChange: ((String, String) -> Unit)? = null // (msgId, status)
    var onTyping: ((String, String) -> Unit)? = null       // (fromAlias, convId)
    /** 信令帧回调（通话/Call 模块使用），注入后接收所有 call_* 类型帧 */
    var onSignal: ((Map<String, Any?>) -> Unit)? = null

    init {
        // 注册帧处理器
        transport.onFrame = { json -> scope.launch { handleFrame(json) } }
        // 连接成功后：发 sync 帧补推离线消息（对标 TS SDK onConnected）
        transport.onConnected = {
            scope.launch {
                transport.send(moshi.adapter(Map::class.java).toJson(
                    mapOf("type" to "sync", "crypto_v" to 1)
                ))
            }
        }
    }

    // ── 发送消息 ──────────────────────────────────────────────────────────

    /**
     * 发送端到端加密文本消息
     * 对标 TS SDK: messaging.send({ conversationId, toAliasId, text, replyToId })
     */
    suspend fun send(
        conversationId: String,
        toAliasId: String,
        text: String,
        replyToId: String? = null
    ): String {
        val session = getOrThrowSession(conversationId)
        val sessionKey = Base64.getDecoder().decode(session.sessionKeyBase64)

        // replyToId 包裹进加密 payload（对标 TS SDK 零知识原则：服务端看不到）
        val textForWire = if (replyToId != null) {
            moshi.adapter(Map::class.java).toJson(mapOf("text" to text, "replyToId" to replyToId))
        } else text

        val ciphertext = CryptoModule.encrypt(sessionKey, textForWire)
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val frame = mapOf(
            "type" to "msg",
            "id" to msgId,
            "to" to toAliasId,
            "conv_id" to conversationId,
            "payload" to ciphertext,
            "crypto_v" to 1
        )
        transport.send(moshi.adapter(Map::class.java).toJson(frame))

        // 乐观本地写入，状态为 sending（等 ack 回来才改 sent）
        val storedMsg = StoredMessage(
            id = msgId,
            conversationId = conversationId,
            text = text,
            isMe = true,
            time = now,
            status = "sending",
            replyToId = replyToId
        )
        db.messageDao().save(storedMsg.toEntity())

        // 通知 UI 层立即显示（对标 TS SDK: this.onMessage?.(storedMsg)）
        onMessage?.invoke(storedMsg)

        return msgId
    }

    /**
     * 发送 typing 指示
     * 对标 TS SDK: messaging.sendTyping(toAliasId, conversationId)
     */
    fun sendTyping(toAliasId: String, conversationId: String) {
        val frame = mapOf("type" to "typing", "to" to toAliasId, "conv_id" to conversationId, "crypto_v" to 1)
        transport.send(moshi.adapter(Map::class.java).toJson(frame))
    }

    /**
     * 发送已读回执
     * 对标 TS SDK: messaging.sendRead(conversationId, maxSeq, toAliasId)
     */
    fun sendRead(conversationId: String, maxSeq: Long, toAliasId: String) {
        val frame = mapOf(
            "type" to "read",
            "conv_id" to conversationId,
            "seq" to maxSeq,
            "to" to toAliasId,
            "crypto_v" to 1
        )
        transport.send(moshi.adapter(Map::class.java).toJson(frame))
    }

    /**
     * 撤回消息
     * 对标 TS SDK: messaging.sendRetract(messageId, toAliasId, conversationId)
     */
    fun sendRetract(messageId: String, toAliasId: String, conversationId: String) {
        val frame = mapOf(
            "type" to "retract",
            "id" to messageId,
            "to" to toAliasId,
            "conv_id" to conversationId,
            "crypto_v" to 1
        )
        transport.send(moshi.adapter(Map::class.java).toJson(frame))
    }

    /**
     * 发送富媒体消息 (Image / File / Voice)
     * 对标 TS SDK: JSON( { msgType, mediaUrl, caption } ) -> encrypt
     */
    suspend fun sendMedia(
        conversationId: String,
        toAliasId: String,
        msgType: String,
        mediaUrl: String,
        caption: String? = null,
        replyToId: String? = null
    ): String {
        val session = getOrThrowSession(conversationId)
        val sessionKey = Base64.getDecoder().decode(session.sessionKeyBase64)

        // 【修复PWA跨端乱码】遵守 TS 版规定的协议格式: { "type": "image", "key": "mediaUrl", ... }
        val payloadMap = mutableMapOf<String, Any>(
            "type" to msgType,
            "key" to mediaUrl
        )
        if (msgType == "image" && caption != null) {
            payloadMap["thumbnail"] = caption
        } else if (msgType == "file") {
            payloadMap["name"] = caption ?: "file_attachment"
            payloadMap["size"] = 1024
        } else if (msgType == "voice") {
            payloadMap["duration"] = 5000
        }
        replyToId?.let { payloadMap["replyToId"] = it }

        val jsonStr = moshi.adapter(Map::class.java).toJson(payloadMap)
        val ciphertext = CryptoModule.encrypt(sessionKey, jsonStr)

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val frame = mapOf(
            "type" to "msg",
            "id" to msgId,
            "to" to toAliasId,
            "conv_id" to conversationId,
            "payload" to ciphertext,
            "crypto_v" to 1
        )
        transport.send(moshi.adapter(Map::class.java).toJson(frame))

        val storedMsg = StoredMessage(
            id = msgId,
            conversationId = conversationId,
            text = caption ?: "[$msgType]",
            isMe = true,
            time = now,
            status = "sending",
            msgType = msgType,
            mediaUrl = mediaUrl,
            caption = caption,
            replyToId = replyToId
        )
        db.messageDao().save(storedMsg.toEntity())
        onMessage?.invoke(storedMsg)

        return msgId
    }

    /**
     * 会话数据全量导出 (NDJSON)
     */
    suspend fun exportConversation(conversationId: String): String {
        val messages = db.messageDao().getAll(conversationId).map { it.toModel() }
        val builder = java.lang.StringBuilder()
        val messageAdapter = moshi.adapter(StoredMessage::class.java)
        for (msg in messages) {
            builder.append(messageAdapter.toJson(msg)).append("\n")
        }
        return builder.toString()
    }

    // ── 接收帧 ────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleFrame(json: String) {
        val adapter = moshi.adapter(Map::class.java)
        val frame = adapter.fromJson(json) as? Map<String, Any?> ?: return
        val type = frame["type"] as? String ?: return

        when (type) {
            "msg"          -> handleMsg(frame)
            "ack"          -> handleAck(frame)
            "delivered"    -> handleReceiptByConvId(frame, "delivered")
            "read"         -> handleReceiptByConvId(frame, "read")
            "status_change"-> handleStatusChange(frame)
            "typing"       -> handleTypingFrame(frame)
            "retract"      -> handleRetract(frame)
            "goaway"       -> transport.forceDisconnectGoaway(frame["reason"] as? String ?: "kicked_by_server")
            // ── 通话信令帧：透传给 CallManager 处理（不解密，直接路由）──
            else           -> if (type.startsWith("call_")) onSignal?.invoke(frame)
        }
    }

    private suspend fun handleMsg(frame: Map<String, Any?>) {
        val convId = frame["conv_id"] as? String ?: return
        val msgId = frame["id"] as? String ?: return
        val payload = frame["payload"] as? String ?: return
        val fromAlias = frame["from"] as? String ?: return
        val seq = (frame["seq"] as? Number)?.toLong()
        // 优先用服务端 at 时间戳（毫秒），避免多设备排序错乱
        val serverTs = (frame["at"] as? Number)?.toLong()
            ?: (frame["ts"] as? Number)?.toLong()
            ?: System.currentTimeMillis()

        val session = db.sessionDao().get(convId) ?: return
        val sessionKey = Base64.getDecoder().decode(session.sessionKeyBase64)

        // 【安全门禁】检测 ECDH 密钥突变（防 MITM 中间人攻击）
        try {
            val identity = db.identityDao().get()
            if (identity != null) {
                val myEcdh = Base64.getDecoder().decode(identity.ecdhPublicKey)
                val theirEcdh = Base64.getDecoder().decode(session.theirEcdhPublicKey)
                security.guardMessage(session.theirAliasId, myEcdh, theirEcdh)
            }
        } catch (e: space.securechat.sdk.security.SecurityViolationException) {
            // 被安全网拦截（发现历史验证被破坏，公钥不吻合）
            android.util.Log.e("SecureChat", "【🚨 警告】发生不可防御的公钥突变拦截！", e)
            return
        }

        var plaintext = try {
            CryptoModule.decrypt(sessionKey, payload)
        } catch (_: Exception) { "[解密失败]" }
        
        var replyToId: String? = null
        var msgType: String? = null
        var mediaUrl: String? = null
        var caption: String? = null

        try {
            @Suppress("UNCHECKED_CAST")
            val parsed = moshi.adapter(Map::class.java).fromJson(plaintext) as? Map<String, Any?>
            if (parsed != null) {
                // 纯文本有 replyToId
                val parsedText = parsed["text"] as? String
                val parsedReply = parsed["replyToId"] as? String
                if (parsedText != null && parsedReply != null) {
                    plaintext = parsedText
                    replyToId = parsedReply
                }
                
                // 富媒体协议：{ "type": "image", "key": "...", ... }
                val pType = parsed["type"] as? String
                if (pType == "image" || pType == "file" || pType == "voice") {
                    msgType = pType
                    mediaUrl = parsed["key"] as? String
                    caption = parsed["thumbnail"] as? String ?: parsed["name"] as? String // 兼容 mock
                    plaintext = if (pType == "image") "[图片]" else if (pType == "file") "[文件]" else "[语音]"
                }
            }
        } catch (_: Exception) { /* 纯文本消息无 JSON 包裹 */ }

        val msg = StoredMessage(
            id = msgId,
            conversationId = convId,
            text = plaintext,
            isMe = false,
            time = serverTs,
            status = "delivered",
            seq = seq,
            fromAliasId = fromAlias,
            replyToId = replyToId,
            msgType = msgType,
            mediaUrl = mediaUrl,
            caption = caption
        )

        db.messageDao().save(msg.toEntity())
        onMessage?.invoke(msg)

        // 自动向服务器回报 delivered（对标 TS SDK §10.1）
        if (seq != null && transport.isConnected) {
            transport.send(moshi.adapter(Map::class.java).toJson(
                mapOf("type" to "delivered", "conv_id" to convId, "seq" to seq, "to" to fromAlias, "crypto_v" to 1)
            ))
        }
    }

    private suspend fun handleAck(frame: Map<String, Any?>) {
        val msgId = frame["id"] as? String ?: return
        // 服务端 ack：sending→sent（对标 TS SDK：不直接跳 delivered）
        db.messageDao().updateStatus(msgId, "sent")
        onStatusChange?.invoke(msgId, "sent")
    }

    // 对方设备 delivered/read 回执（基于 conv_id 批量更新自己发送的消息）
    private suspend fun handleReceiptByConvId(frame: Map<String, Any?>, status: String) {
        val convId = frame["conv_id"] as? String ?: return
        val myMsgs = db.messageDao().getAll(convId).filter {
            it.isMe && it.status != "read" && (status != "delivered" || it.status != "delivered")
        }
        for (msg in myMsgs) {
            db.messageDao().updateStatus(msg.id, status)
            onStatusChange?.invoke(msg.id, status)
        }
    }

    private suspend fun handleStatusChange(frame: Map<String, Any?>) {
        val msgId = frame["id"] as? String ?: return
        val status = frame["status"] as? String ?: return
        db.messageDao().updateStatus(msgId, status)
        onStatusChange?.invoke(msgId, status)
    }

    private fun handleTypingFrame(frame: Map<String, Any?>) {
        val from = frame["from"] as? String ?: return
        val convId = frame["conv_id"] as? String ?: return
        onTyping?.invoke(from, convId)
    }

    private suspend fun handleRetract(frame: Map<String, Any?>) {
        val msgId = frame["id"] as? String ?: return
        val convId = frame["conv_id"] as? String ?: return
        val serverTs = (frame["ts"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val msg = StoredMessage(
            id = msgId,
            conversationId = convId,
            text = "消息已撤回",
            isMe = false,
            time = serverTs,
            status = "delivered",
            msgType = "retracted"
        )
        db.messageDao().save(msg.toEntity())
        onMessage?.invoke(msg)

        val seq = (frame["seq"] as? Number)?.toLong()
        val fromAlias = frame["from"] as? String

        // 🔒 SDK 自动：收到新消息立即静默反射一个 delivered 回执给服务端
        if (seq != null && fromAlias != null) {
            val deliveredFrame = mapOf(
                "type" to "delivered",
                "conv_id" to convId,
                "seq" to seq,
                "to" to fromAlias,
                "crypto_v" to 1
            )
            transport.send(moshi.adapter(Map::class.java).toJson(deliveredFrame))
        }
    }

    // ── 本地查询 ──────────────────────────────────────────────────────────

    suspend fun getHistory(convId: String, limit: Int = 200, before: Long? = null): List<StoredMessage> {
        return db.messageDao().getPaged(convId, limit, before).map { it.toModel() }.reversed()
    }

    suspend fun getMessage(id: String): StoredMessage? =
        db.messageDao().getById(id)?.toModel()

    suspend fun clearAll() {
        db.messageDao().clearAll()
        db.sessionDao().clearAll()
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private suspend fun getOrThrowSession(convId: String): SessionEntity {
        return db.sessionDao().get(convId)
            ?: error("No session for convId=$convId. Call ContactsManager.syncFriends() first.")
    }
}

// ── 数据类型（对标 TS SDK StoredMessage）─────────────────────────────────

data class StoredMessage(
    val id: String,
    val conversationId: String,
    val text: String,
    val isMe: Boolean,
    val time: Long,
    val status: String,
    val msgType: String? = null,
    val mediaUrl: String? = null,
    val caption: String? = null,
    val seq: Long? = null,
    val fromAliasId: String? = null,
    val replyToId: String? = null
)

fun MessageEntity.toModel() = StoredMessage(
    id = id, conversationId = conversationId, text = text, isMe = isMe,
    time = time, status = status, msgType = msgType, mediaUrl = mediaUrl,
    caption = caption, seq = seq, fromAliasId = fromAliasId, replyToId = replyToId
)

fun StoredMessage.toEntity() = MessageEntity(
    id = id, conversationId = conversationId, text = text, isMe = isMe,
    time = time, status = status, msgType = msgType, mediaUrl = mediaUrl,
    caption = caption, seq = seq, fromAliasId = fromAliasId, replyToId = replyToId
)
