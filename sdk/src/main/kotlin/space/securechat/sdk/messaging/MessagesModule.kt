package space.securechat.sdk.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.securechat.sdk.db.SecureChatDatabase
import space.securechat.sdk.events.EventBus
import space.securechat.sdk.events.MessageStatusEvent
import space.securechat.sdk.events.TypingEvent

/**
 * MessagesModule — 0.3.0 响应式消息 API
 *
 * 包装老 MessageManager(底层 WebSocket/Outbox 引擎), 对外暴露:
 *   - observeConversations(): StateFlow<List<ConversationSummary>>
 *   - observeMessages(convId): StateFlow<List<StoredMessage>>
 *   - send(...) 返回 messageId
 *
 * 对标 sdk-typescript/src/messaging/module.ts
 */
class MessagesModule(
    private val inner: MessageManager,
    private val db: SecureChatDatabase,
    private val events: EventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    private val _byConvId = mutableMapOf<String, MutableStateFlow<List<StoredMessage>>>()
    @Volatile private var primed = false

    init {
        // 挂钩底层 MessageManager 回调,桥接到 Flow + 事件总线
        val prevMsg = inner.onMessage
        inner.onMessage = { msg ->
            prevMsg?.invoke(msg)
            onIncoming(msg)
        }
        val prevStatus = inner.onStatusChange
        inner.onStatusChange = { id, status ->
            prevStatus?.invoke(id, status)
            onStatusChange(id, status)
        }
        val prevTyping = inner.onTyping
        inner.onTyping = { from, conv ->
            prevTyping?.invoke(from, conv)
            scope.launch { events.emitTyping(TypingEvent(from, conv)) }
        }
    }

    // ─── 观察式 API ──────────────────────────────────────

    fun observeConversations(): StateFlow<List<ConversationSummary>> {
        if (!primed) {
            primed = true
            scope.launch { refreshSummary() }
        }
        return _conversations.asStateFlow()
    }

    fun observeMessages(conversationId: String): StateFlow<List<StoredMessage>> {
        val subject = _byConvId.getOrPut(conversationId) {
            MutableStateFlow<List<StoredMessage>>(emptyList()).also {
                scope.launch { loadConversation(conversationId) }
            }
        }
        return subject.asStateFlow()
    }

    // ─── 命令式 ──────────────────────────────────────────

    suspend fun send(
        conversationId: String,
        toAliasId: String,
        text: String,
        replyToId: String? = null,
    ): String {
        val id = inner.send(conversationId, toAliasId, text, replyToId)
        loadConversation(conversationId)
        return id
    }

    suspend fun getHistory(convId: String, limit: Int = 200, before: Long? = null): List<StoredMessage> {
        return inner.getHistory(convId, limit, before)
    }

    fun sendTyping(convId: String, toAliasId: String) {
        inner.sendTyping(toAliasId, convId)
    }

    fun markAsRead(convId: String, maxSeq: Long, toAliasId: String) {
        inner.sendRead(convId, maxSeq, toAliasId)
    }

    fun retract(messageId: String, toAliasId: String, conversationId: String) {
        inner.sendRetract(messageId, toAliasId, conversationId)
    }

    suspend fun clearHistory(conversationId: String) {
        // 删本地, 刷新订阅
        db.messageDao().deleteByConversation(conversationId)
        _byConvId[conversationId]?.value = emptyList()
        refreshSummary()
    }

    suspend fun clearAllConversations() {
        inner.clearAll()
        _byConvId.values.forEach { it.value = emptyList() }
        _conversations.value = emptyList()
    }

    // ─── 内部 ────────────────────────────────────────────

    private suspend fun refreshSummary() {
        try {
            val sessions = db.sessionDao().getAll()
            val summaries = sessions.map { s ->
                val msgs = inner.getHistory(s.conversationId, limit = 1)
                val last = msgs.lastOrNull()
                ConversationSummary(
                    conversationId = s.conversationId,
                    peerAliasId = s.theirAliasId,
                    peerNickname = s.theirAliasId, // 0.4+ 缓存 nickname 进 SessionEntity
                    lastMessage = last?.let {
                        LastMessageSnapshot(
                            text = it.text,
                            at = it.time,
                            fromMe = it.isMe,
                            status = it.status,
                        )
                    },
                    unreadCount = 0,
                )
            }
            _conversations.value = summaries
        } catch (_: Throwable) { /* ignore transient IO */ }
    }

    private suspend fun loadConversation(conversationId: String) {
        try {
            val msgs = inner.getHistory(conversationId, limit = 200)
            val subject = _byConvId.getOrPut(conversationId) { MutableStateFlow<List<StoredMessage>>(emptyList()) }
            subject.value = msgs
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun onIncoming(msg: StoredMessage) {
        val subject = _byConvId[msg.conversationId]
        if (subject != null) {
            val cur = subject.value
            if (cur.none { it.id == msg.id }) {
                subject.value = (cur + msg).sortedBy { it.time }
            }
        }
        scope.launch {
            events.emitMessage(msg)
            refreshSummary()
        }
    }

    private fun onStatusChange(id: String, status: String) {
        for (subject in _byConvId.values) {
            val cur = subject.value
            val idx = cur.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val next = cur.toMutableList()
                next[idx] = cur[idx].copy(status = status)
                subject.value = next
            }
        }
        scope.launch {
            events.emitStatus(MessageStatusEvent(id, status))
            refreshSummary()
        }
    }
}

// ─── 数据类 ─────────────────────────────────────────────

data class ConversationSummary(
    val conversationId: String,
    val peerAliasId: String,
    val peerNickname: String,
    val lastMessage: LastMessageSnapshot?,
    val unreadCount: Int,
)

data class LastMessageSnapshot(
    val text: String,
    val at: Long,
    val fromMe: Boolean,
    val status: String,
)
