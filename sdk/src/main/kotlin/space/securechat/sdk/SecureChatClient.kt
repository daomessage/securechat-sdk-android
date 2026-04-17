package space.securechat.sdk

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import space.securechat.sdk.auth.AuthManager
import space.securechat.sdk.channels.ChannelsManager
import space.securechat.sdk.contacts.ContactsManager
import space.securechat.sdk.contacts.Friend
import space.securechat.sdk.contacts.UserProfile
import space.securechat.sdk.db.SecureChatDatabase
import space.securechat.sdk.http.HttpClient
import space.securechat.sdk.keys.KeyDerivation
import space.securechat.sdk.messaging.MessageManager
import space.securechat.sdk.messaging.StoredMessage
import space.securechat.sdk.messaging.WSTransport
import space.securechat.sdk.messaging.toEntity
import space.securechat.sdk.push.PushManager
import space.securechat.sdk.vanity.VanityManager

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║           SecureChatClient — Android SDK 门面               ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║  对标 sdk-typescript/src/client.ts                          ║
 * ║  版本: 1.0.0  协议: SecureChat v1                           ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * ## AI 快速接入指南
 *
 * ### 初始化（Application.onCreate）
 * ```kotlin
 * SecureChatClient.init(applicationContext)
 * val client = SecureChatClient.getInstance()
 * ```
 *
 * ### 新用户注册流程
 * ```kotlin
 * val mnemonic = KeyDerivation.newMnemonic()   // 生成并展示给用户备份
 * val aliasId = client.auth.registerAccount(mnemonic, nickname)
 * client.connect()
 * val friends = client.contacts.syncFriends()
 * ```
 *
 * ### 老用户恢复
 * ```kotlin
 * val (aliasId, nickname) = client.restoreSession() ?: return // null = 需注册
 * client.connect()
 * val friends = client.contacts.syncFriends()
 * ```
 *
 * ### 接收消息
 * ```kotlin
 * val unsub = client.on(SecureChatClient.EVENT_MESSAGE) { msg ->
 *     // 在主线程回调，可直接更新 UI
 *     viewModel.addMessage(msg)
 * }
 * // 在 onDestroy 中: unsub()
 * ```
 *
 * ### 发送消息
 * ```kotlin
 * val msgId = client.sendMessage(conversationId, toAliasId, "Hello E2EE!")
 * ```
 *
 * ## 🛡️ AI 约束（不得修改）
 * - CORE_API_BASE 硬编码，不接受外部参数
 * - 密钥派生路径 m/44'/0'/0'/0/0 和 m/44'/1'/0'/0/0 不可变更
 * - AES-GCM 信封格式（iv+ciphertext）不可变更（多端互通）
 */
class SecureChatClient private constructor(private val context: Context) {

    companion object {
        const val EVENT_MESSAGE      = "message"
        const val EVENT_STATUS_CHANGE = "status_change"
        const val EVENT_TYPING       = "typing"
        const val EVENT_NETWORK_STATE = "network_state"
        /** 通话信令帧事件（除 EVENT_MESSAGE/TYPING 以外的所有 call_* 帧） */
        const val EVENT_SIGNAL       = "call_signal"

        @Volatile private var instance: SecureChatClient? = null

        /**
         * 初始化 SDK（在 Application.onCreate 中调用一次）
         * 👤 App 必须调用，否则 getInstance() 会抛出异常
         */
        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = SecureChatClient(context.applicationContext)
                    }
                }
            }
        }

        /** 获取 SDK 单例 */
        fun getInstance(): SecureChatClient =
            instance ?: error("SecureChatClient not initialized. Call SecureChatClient.init(context) first.")
    }

    // ── 内部组件 ──────────────────────────────────────────────────────────

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal val db: SecureChatDatabase = Room.databaseBuilder(
        context, SecureChatDatabase::class.java, SecureChatDatabase.DB_NAME
    ).fallbackToDestructiveMigration().build()

    internal val http = HttpClient()
    internal val networkManager = space.securechat.sdk.messaging.NetworkManager(context)
    internal val transport = WSTransport(http, networkManager.isOnline)
    
    /** 安全：防中间人劫持门禁 */
    val security = space.securechat.sdk.security.SecurityModule(db.trustDao())
    
    /** 
     * 一键获取当前会话的安全指纹代码及握手公钥（用于 UI 认证界面展示比对）
     * 返回：(显示用的 60 位安全码模型, 我方大公钥, 对方大公钥)
     */
    suspend fun getSecurityFingerprint(conversationId: String): Triple<space.securechat.sdk.security.SecurityCode, ByteArray, ByteArray> {
        val session = db.sessionDao().get(conversationId) ?: error("No session found for this conversation.")
        val identity = db.identityDao().get() ?: error("No identity established.")

        val myEcdh = java.util.Base64.getDecoder().decode(identity.ecdhPublicKey)
        val theirEcdh = java.util.Base64.getDecoder().decode(session.theirEcdhPublicKey)

        val code = security.getSecurityCode(session.theirAliasId, myEcdh, theirEcdh)
        return Triple(code, myEcdh, theirEcdh)
    }

    internal val messaging = MessageManager(transport, db, security, scope)

    // ── 子模块（👤 App 直接访问）─────────────────────────────────────────

    /** 认证：注册/恢复会话 */
    val auth = AuthManager(context, http, db)

    /** 好友：查找/申请/接受/同步 */
    val contacts = ContactsManager(http, db)

    /** 频道：浏览/创建/发帖/订阅 */
    val channels = ChannelsManager(http)

    /** 靓号：搜索/购买/绑定 */
    val vanity = VanityManager(http)

    /** Push：注册 FCM Token */
    val push = PushManager(http)

    /** 多媒体：加密上传/下载 (对接 S3/R2) */
    val media = space.securechat.sdk.media.MediaManager(http, http.okhttpClient)

    // ── 事件监听 ──────────────────────────────────────────────────────────

    private val messageListeners = mutableSetOf<(StoredMessage) -> Unit>()
    private val statusListeners  = mutableSetOf<(String, String) -> Unit>()
    private val typingListeners  = mutableSetOf<(String, String) -> Unit>()
    /** 通话信令监听器（CallManager 注入） */
    private val signalListeners  = mutableSetOf<(Map<String, Any?>) -> Unit>()

    init {
        messaging.onMessage = { msg ->
            scope.launch(Dispatchers.Main) { messageListeners.forEach { it(msg) } }
        }
        messaging.onStatusChange = { id, status ->
            scope.launch(Dispatchers.Main) { statusListeners.forEach { it(id, status) } }
        }
        messaging.onTyping = { alias, convId ->
            scope.launch(Dispatchers.Main) { typingListeners.forEach { it(alias, convId) } }
        }
        // 信令帧分发到所有信令监听器（先解密 payload 再分发）
        messaging.onSignal = { frame ->
            val decrypted = decryptSignalPayload(frame)
            scope.launch(Dispatchers.Main) { signalListeners.forEach { it(decrypted) } }
        }
    }

    /**
     * 订阅事件，返回注销函数
     * ```kotlin
     * val unsub = client.on(EVENT_MESSAGE) { msg -> ... }
     * // 不再需要时：unsub()
     * ```
     * 对标 TS SDK: client.on('message', listener)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> on(event: String, listener: T): () -> Unit {
        when (event) {
            EVENT_MESSAGE      -> { messageListeners.add(listener as (StoredMessage) -> Unit);        return { messageListeners.remove(listener) } }
            EVENT_STATUS_CHANGE-> { statusListeners.add(listener as (String, String) -> Unit);        return { statusListeners.remove(listener) } }
            EVENT_TYPING       -> { typingListeners.add(listener as (String, String) -> Unit);        return { typingListeners.remove(listener) } }
            EVENT_SIGNAL       -> { signalListeners.add(listener as (Map<String, Any?>) -> Unit);     return { signalListeners.remove(listener) } }
            else               -> return {}
        }
    }

    /**
     * 发送通话信令帧（crypto_v=2：强制 Ed25519 签名 + AES-GCM 加密）
     *
     * 2026-04 安全加固：拒绝明文回退。若无会话密钥直接 throw。
     * 签名覆盖 type / call_id / from / _ts / _nonce + 所有敏感字段。
     *
     * @return true=已立即发出，false=已入队（重连后自动发送）
     */
    fun sendSignalFrame(frame: Map<String, Any?>): Boolean {
        val type   = frame["type"]    as? String ?: ""
        val to     = frame["to"]      as? String ?: ""
        val from   = frame["from"]    as? String ?: ""
        val callId = frame["call_id"] as? String ?: ""

        val sensitiveKeys = frame.keys - setOf("type", "to", "from", "call_id", "crypto_v")
        val sensitiveData = frame.filterKeys { it in sensitiveKeys }.toMutableMap()

        val sessionKey = getSessionKeyForAlias(to)
            ?: throw IllegalStateException("No session key for $to — cannot send signed signal")

        // 1) 注入时间戳 + 随机 nonce 防重放
        val nonceBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonceHex = nonceBytes.joinToString("") { "%02x".format(it) }
        val toSign = sensitiveData + mapOf(
            "type"    to type,
            "call_id" to callId,
            "from"    to from,
            "_ts"     to System.currentTimeMillis(),
            "_nonce"  to nonceHex,
        )

        // 2) Ed25519 签名（canonical: key 排序）
        // Keystore 解密在 WebSocket 路径同步读取 — SecureIdentity.load 内部 suspend
        val identity = runCatching {
            kotlinx.coroutines.runBlocking { space.securechat.sdk.db.SecureIdentity.load(db) }
        }.getOrNull() ?: throw IllegalStateException("No local identity for signing")
        val signingPriv = java.util.Base64.getDecoder().decode(identity.signingPrivateKey)

        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val sortedSigned = toSign.toSortedMap()
        val canonical = moshi.adapter(Map::class.java).toJson(sortedSigned)
        val sig = space.securechat.sdk.keys.KeyDerivation.signChallenge(canonical.toByteArray(Charsets.UTF_8), signingPriv)
        val signedInner = sortedSigned + mapOf("_sig" to sig.joinToString("") { "%02x".format(it) })

        // 3) 加密 signedInner 作为 payload
        val plaintext = moshi.adapter(Map::class.java).toJson(signedInner)
        val encrypted = space.securechat.sdk.crypto.CryptoModule.encrypt(sessionKey, plaintext)

        // 4) 外层信封（路由字段明文）
        val routeFields = mapOf<String, Any?>(
            "type"     to type,
            "to"       to to,
            "from"     to from,
            "call_id"  to callId,
            "crypto_v" to 2,
            "payload"  to encrypted,
        )
        val json = moshi.adapter(Map::class.java).toJson(routeFields)
        return transport.send(json)
    }

    /**
     * 从本地数据库获取与指定 alias 的会话密钥
     */
    private fun getSessionKeyForAlias(aliasId: String): ByteArray? {
        return try {
            val session = kotlinx.coroutines.runBlocking {
                db.sessionDao().getByAlias(aliasId)
            } ?: return null
            java.util.Base64.getDecoder().decode(session.sessionKeyBase64)
        } catch (e: Exception) {
            android.util.Log.w("SecureChatClient", "获取会话密钥失败: $aliasId", e)
            null
        }
    }

    /**
     * 解密 + 验签信令帧（crypto_v=2 强制）
     * 校验失败直接返回空 map，让 CallManager 忽略该帧
     */
    @Suppress("UNCHECKED_CAST")
    internal fun decryptSignalPayload(frame: Map<String, Any?>): Map<String, Any?> {
        val payload = frame["payload"] as? String ?: return emptyMap()
        val from = frame["from"] as? String ?: return emptyMap()
        val envelopeCallId = frame["call_id"] as? String ?: ""

        val sessionKey = getSessionKeyForAlias(from) ?: return emptyMap()
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        return try {
            val decrypted = space.securechat.sdk.crypto.CryptoModule.decrypt(sessionKey, payload)
            val inner = moshi.adapter(Map::class.java).fromJson(decrypted) as? Map<String, Any?>
                ?: return emptyMap()

            // 1) 提取签名字段
            val sigHex = inner["_sig"] as? String ?: return emptyMap()
            val ts     = (inner["_ts"] as? Number)?.toLong() ?: return emptyMap()
            val nonce  = inner["_nonce"] as? String ?: return emptyMap()

            // 2) 时间窗 60s
            if (Math.abs(System.currentTimeMillis() - ts) > 60_000) {
                android.util.Log.w("SecureChatClient", "signal replay window exceeded")
                return emptyMap()
            }

            // 3) nonce 去重
            if (!checkAndRememberNonce(nonce)) {
                android.util.Log.w("SecureChatClient", "signal nonce replay")
                return emptyMap()
            }

            // 4) 内层 from / call_id 与外层一致
            if (inner["from"] != from) return emptyMap()
            if (envelopeCallId.isNotEmpty() && (inner["call_id"] as? String) != envelopeCallId) return emptyMap()

            // 5) Ed25519 验签（对端公钥从 session 取）
            val session = db.sessionDao().let {
                runCatching { kotlinx.coroutines.runBlocking { it.getByAlias(from) } }.getOrNull()
            } ?: return emptyMap()
            val theirSigPub = java.util.Base64.getDecoder().decode(session.theirEd25519PublicKey ?: "")
            val canonicalMap = (inner - "_sig").toSortedMap()
            val canonical = moshi.adapter(Map::class.java).toJson(canonicalMap)
            val sigBytes = ByteArray(sigHex.length / 2) {
                java.lang.Integer.parseInt(sigHex.substring(it * 2, it * 2 + 2), 16).toByte()
            }
            val ok = space.securechat.sdk.keys.KeyDerivation.verifyChallenge(
                canonical.toByteArray(Charsets.UTF_8), sigBytes, theirSigPub
            )
            if (!ok) {
                android.util.Log.w("SecureChatClient", "signal signature INVALID from=$from")
                return emptyMap()
            }

            // 校验通过：合并路由 + 内层（去掉签名元数据）
            frame + inner.filterKeys { it != "_sig" && it != "_ts" && it != "_nonce" }
        } catch (e: Exception) {
            android.util.Log.e("SecureChatClient", "信令 verify/decrypt 失败", e)
            emptyMap()
        }
    }

    // 最近 5 分钟 nonce 缓存（防重放）
    private val seenNonces = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun checkAndRememberNonce(nonce: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = seenNonces[nonce]
        if (prev != null && prev > now) return false
        seenNonces[nonce] = now + 5 * 60_000
        // 清理过期
        if (seenNonces.size > 2048) {
            seenNonces.entries.removeAll { it.value < now }
        }
        return true
    }

    /**
     * 获取 TURN/STUN 服务器凭证（供 CallManager 初始化 PeerConnection）
     * 调用 relay-server /relay/turn，返回 ICE 服务器列表
     */
    suspend fun fetchTurnConfig(): TurnConfig? {
        return try {
            // 确保已登录（token 由 OkHttp 拦截器自动注入 Authorization header）
            http.getToken() ?: return null
            val request = okhttp3.Request.Builder()
                .url("${space.securechat.sdk.http.HttpClient.CORE_API_BASE}/api/v1/calls/ice-config")
                .get()
                .build()
            val response = http.okhttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            @Suppress("UNCHECKED_CAST")
            val map = moshi.adapter(Map::class.java).fromJson(body) as? Map<String, Any?> ?: return null
            @Suppress("UNCHECKED_CAST")
            val servers = map["ice_servers"] as? List<Map<String, Any?>> ?: return null
            TurnConfig(servers)
        } catch (e: Exception) {
            android.util.Log.w("SecureChatClient", "fetchTurnConfig 失败，降级为 STUN", e)
            null
        }
    }

    /** TURN 配置数据类 */
    data class TurnConfig(val iceServers: List<Map<String, Any?>>)

    // ── 连接控制 ──────────────────────────────────────────────────────────

    /**
     * 建立 WebSocket 连接（注册/恢复会话后调用）
     * 对标 TS SDK: client.connect()
     */
    fun connect() {
        val uuid = auth.internalUUID ?: error("No identity. Call registerAccount() or restoreSession() first.")
        val token = http.getToken() ?: error("No token.")
        transport.connect(uuid, token)
    }

    /** 断开连接（退出登录前调用）*/
    fun disconnect() = transport.disconnect()

    /** 网络状态 Flow，供 UI 观察 */
    val networkState: StateFlow<WSTransport.NetworkState> = transport.networkState

    val isConnected get() = transport.isConnected

    // ── 会话恢复 ──────────────────────────────────────────────────────────

    /**
     * 恢复历史会话（App 启动时调用）
     * @return (aliasId, nickname) 或 null（首次使用，引导注册流程）
     * 对标 TS SDK: client.restoreSession()
     */
    suspend fun restoreSession(): Pair<String, String>? = auth.restoreSession()

    /**
     * 获取会话信息（含 sessionKey），供媒体下载解密使用
     */
    suspend fun getSessionForConversation(conversationId: String): space.securechat.sdk.db.SessionEntity {
        return db.sessionDao().get(conversationId) ?: error("No session found for $conversationId")
    }

    // ── 消息发送 ──────────────────────────────────────────────────────────

    /**
     * 发送端到端加密文本消息
     * 对标 TS SDK: client.sendMessage(conversationId, toAliasId, text, replyToId?)
     */
    suspend fun sendMessage(
        conversationId: String,
        toAliasId: String,
        text: String,
        replyToId: String? = null
    ): String = messaging.send(conversationId, toAliasId, text, replyToId)

    /**
     * 发送富媒体消息 (图片/文件/语音) - (底层通道参数拼接，仅限特殊需求调用)
     */
    suspend fun sendMedia(
        conversationId: String,
        toAliasId: String,
        msgType: String,
        mediaUrl: String,
        caption: String? = null,
        replyToId: String? = null
    ): String = messaging.sendMedia(conversationId, toAliasId, msgType, mediaUrl, caption, replyToId)

    /**
     * 【正规原生协议级传输】发送图片
     * 调用真实 SDK 加密上传引擎与远端直连
     */
    suspend fun sendImage(
        conversationId: String,
        toAliasId: String,
        imageBytes: ByteArray,
        thumbnailBase64: String? = null,
        replyToId: String? = null
    ): String {
        val session = db.sessionDao().get(conversationId) ?: error("No session found for this conversation.")
        val sessionKey = java.util.Base64.getDecoder().decode(session.sessionKeyBase64)
        // 使用底层的传输通道，将数据真实加密上传并取得远端介质网络分配的 key
        val (mediaKey, _) = media.encryptAndUpload(imageBytes, "image/jpeg", sessionKey, conversationId)
        return sendMedia(conversationId, toAliasId, "image", mediaKey, thumbnailBase64, replyToId)
    }

    /**
     * 【正规原生协议级下载】根据 mediaUrl / mediaKey 下载媒体原文并解密
     */
    suspend fun downloadMedia(conversationId: String, mediaUrl: String): ByteArray {
        val session = db.sessionDao().get(conversationId) ?: error("No session found for this conversation.")
        val sessionKey = java.util.Base64.getDecoder().decode(session.sessionKeyBase64)
        val cleanKey = mediaUrl.replace("[img]", "").replace("[file]", "")
        return media.downloadAndDecrypt(cleanKey, sessionKey)
    }

    /**
     * 【正规原生协议级传输】发送实况语音
     */
    suspend fun sendVoice(
        conversationId: String,
        toAliasId: String,
        audioBytes: ByteArray,
        durationMs: Long,
        replyToId: String? = null
    ): String {
        val session = db.sessionDao().get(conversationId) ?: error("No session found for this conversation.")
        val sessionKey = java.util.Base64.getDecoder().decode(session.sessionKeyBase64)
        val (mediaKey, _) = media.encryptAndUpload(audioBytes, "audio/mp4", sessionKey, conversationId)
        return sendMedia(conversationId, toAliasId, "voice", mediaKey, null, replyToId)
    }

    /**
     * 【正规原生协议级传输】发送文件
     */
    suspend fun sendFile(
        conversationId: String,
        toAliasId: String,
        fileBytes: ByteArray,
        fileName: String,
        replyToId: String? = null
    ): String {
        val session = db.sessionDao().get(conversationId) ?: error("No session found for this conversation.")
        val sessionKey = java.util.Base64.getDecoder().decode(session.sessionKeyBase64)
        val (mediaKey, _) = media.encryptAndUpload(fileBytes, "application/octet-stream", sessionKey, conversationId)
        return sendMedia(conversationId, toAliasId, "file", mediaKey, fileName, replyToId)
    }

    /**
     * 撤回消息（仅可撤回自己发送的）
     * 对标 TS SDK: client.retractMessage(messageId, toAliasId, conversationId)
     */
    suspend fun retractMessage(messageId: String, toAliasId: String, conversationId: String) {
        messaging.sendRetract(messageId, toAliasId, conversationId)
        // 本地立即替换为"消息已撤回"
        val retracted = StoredMessage(
            id = messageId, conversationId = conversationId, text = "消息已撤回",
            isMe = true, time = System.currentTimeMillis(), status = "delivered",
            msgType = "retracted"
        )
        db.messageDao().save(retracted.toEntity())
        scope.launch(Dispatchers.Main) { messageListeners.forEach { it(retracted) } }
    }

    /** 获取本地存储的助记词（Keystore 解密后返回；已注册账号才有）
     *
     * ⚠️ 敏感操作：仅用于"显示恢复短语"场景。App 展示后应立即丢弃 String 引用。
     * 不应作为常规调用，也不要把返回值持久化到其它位置。
     */
    suspend fun getMnemonic(): String? {
        android.util.Log.w("SecureChatClient", "getMnemonic() called — sensitive API, UI must set FLAG_SECURE")
        return space.securechat.sdk.db.SecureIdentity.load(db)?.mnemonic
    }

    /** 查询服务端存储占用估算（字节） */
    suspend fun getStorageEstimate(): space.securechat.sdk.http.StorageEstimate {
        return http.api.getStorageEstimate()
    }

    /** 清除指定会话的本地消息历史 */
    suspend fun clearHistory(conversationId: String) {
        db.messageDao().deleteByConversation(conversationId)
    }

    /** 清除全部会话的本地消息历史 */
    suspend fun clearAllHistory() {
        messaging.clearAll()
    }

    /** 导出指定会话的 NDJSON */
    suspend fun exportConversation(conversationId: String): String =
        messaging.exportConversation(conversationId)

    /** 导出全部会话的 NDJSON（按 sessionId 串接） */
    suspend fun exportAllConversations(): String {
        val sessions = db.sessionDao().getAll()
        val sb = StringBuilder()
        for (s in sessions) {
            sb.append("# === ${s.conversationId} ===\n")
            sb.append(messaging.exportConversation(s.conversationId))
        }
        return sb.toString()
    }

    /** 发送 typing 状态 */
    fun sendTyping(conversationId: String, toAliasId: String) =
        messaging.sendTyping(toAliasId, conversationId)

    /** 标记已读 */
    fun markAsRead(conversationId: String, maxSeq: Long, toAliasId: String) =
        messaging.sendRead(conversationId, maxSeq, toAliasId)

    // ── 历史查询 ──────────────────────────────────────────────────────────

    /**
     * 获取会话历史消息（本地 Room DB）
     * 对标 TS SDK: client.getHistory(conversationId, { limit, before })
     */
    suspend fun getHistory(
        conversationId: String,
        limit: Int = 200,
        before: Long? = null
    ): List<StoredMessage> = messaging.getHistory(conversationId, limit, before)

    suspend fun getMessageData(messageId: String): StoredMessage? = messaging.getMessage(messageId)

    /**
     * 获取所有已建立的会话列表（从 Room DB 读取）
     * 对标 TS SDK: listSessions() → IndexedDB sessions store
     * 👤 App 调用：MessagesTab 显示会话列表时使用
     */
    suspend fun listSessions(): List<space.securechat.sdk.db.SessionEntity> = db.sessionDao().getAll()

    // ── 登出 / 清理 ───────────────────────────────────────────────────────

    /**
     * 清除所有本地数据（退出登录时调用）
     * 对标 TS SDK: client.clearAllHistory() + clearIdentity()
     * 👤 App 调用后应跳转到 Welcome 页
     */
    suspend fun logout() {
        disconnect()
        messaging.clearAll()
        db.identityDao().clear()
        http.clearToken()
    }
}
