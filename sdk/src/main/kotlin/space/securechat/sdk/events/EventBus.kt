package space.securechat.sdk.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EventBus — 0.3.0 Android SDK 响应式事件总线
 *
 * 对标 sdk-typescript/src/events/streams-ext.ts
 *
 * 流分两类:
 *   - StateFlow (保持当前值, 冷启动有初值): network / sync
 *   - SharedFlow (一次性事件, 没历史概念): message / error / typing / goaway
 */

// ─── 类型 ──────────────────────────────────────────────

enum class NetworkState { DISCONNECTED, CONNECTING, CONNECTED }

sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val progress: Float, val pendingMessages: Int) : SyncState()
    data class Done(val catchUpDurationMs: Long) : SyncState()
}

enum class SDKErrorKind { AUTH, NETWORK, RATE_LIMIT, CRYPTO, SERVER, UNKNOWN }

data class SDKError(
    val kind: SDKErrorKind,
    val message: String,
    val details: Map<String, Any?>? = null,
    val at: Long = System.currentTimeMillis(),
)

data class TypingEvent(val fromAliasId: String, val conversationId: String)

data class MessageStatusEvent(
    val id: String,
    val status: String, // sending | sent | delivered | read | failed
)

data class ChannelPostEvent(
    val channelId: String,
    val postId: String,
    val fromAliasId: String,
    val text: String,
    val at: Long,
)

data class GoawayEvent(val reason: String, val at: Long = System.currentTimeMillis())

// ─── EventBus 实现 ─────────────────────────────────────

/**
 * 内部事件总线: SDK 内部持有, 通过 toPublic() 产出对外只读 view。
 */
class EventBus {
    internal val _network = MutableStateFlow(NetworkState.DISCONNECTED)
    internal val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    internal val _error = MutableSharedFlow<SDKError>(extraBufferCapacity = 32)
    internal val _message = MutableSharedFlow<Any>(extraBufferCapacity = 128) // StoredMessage 暴露类型
    internal val _typing = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 32)
    internal val _messageStatus = MutableSharedFlow<MessageStatusEvent>(extraBufferCapacity = 64)
    internal val _channelPost = MutableSharedFlow<ChannelPostEvent>(extraBufferCapacity = 32)
    internal val _goaway = MutableSharedFlow<GoawayEvent>(extraBufferCapacity = 4)

    fun emitNetwork(state: NetworkState) { _network.value = state }
    fun emitSync(state: SyncState) { _sync.value = state }
    suspend fun emitError(err: SDKError) { _error.emit(err) }
    suspend fun emitMessage(msg: Any) { _message.emit(msg) }
    suspend fun emitTyping(ev: TypingEvent) { _typing.emit(ev) }
    suspend fun emitStatus(ev: MessageStatusEvent) { _messageStatus.emit(ev) }
    suspend fun emitChannelPost(ev: ChannelPostEvent) { _channelPost.emit(ev) }
    suspend fun emitGoaway(ev: GoawayEvent) { _goaway.emit(ev) }

    fun toPublic(): PublicEventBus = PublicEventBus(this)
}

/**
 * 对外只读视图。App 通过 `client.events.network.collect { ... }` 订阅。
 */
class PublicEventBus internal constructor(bus: EventBus) {
    val network: StateFlow<NetworkState> = bus._network.asStateFlow()
    val sync: StateFlow<SyncState> = bus._sync.asStateFlow()
    val error: SharedFlow<SDKError> = bus._error.asSharedFlow()
    val message: SharedFlow<Any> = bus._message.asSharedFlow()
    val typing: SharedFlow<TypingEvent> = bus._typing.asSharedFlow()
    val messageStatus: SharedFlow<MessageStatusEvent> = bus._messageStatus.asSharedFlow()
    val channelPost: SharedFlow<ChannelPostEvent> = bus._channelPost.asSharedFlow()
    val goaway: SharedFlow<GoawayEvent> = bus._goaway.asSharedFlow()
}
