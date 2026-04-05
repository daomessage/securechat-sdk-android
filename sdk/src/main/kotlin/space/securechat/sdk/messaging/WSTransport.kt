package space.securechat.sdk.messaging

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import space.securechat.sdk.http.HttpClient
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 🔒 WSTransport — OkHttp WebSocket 封装
 *
 * 功能：
 *   - 连接/断开 WebSocket
 *   - 自动心跳（30s ping）
 *   - 指数退避重连（最多 8 次）
 *   - 离线消息队列：未连接时缓冲，连接后自动 flush
 *   - 接收帧推送给 MessageManager
 *
 * 对标 sdk-typescript/src/messaging/transport.ts
 */
class WSTransport(
    private val http: HttpClient,
    private val isOnlineFlow: StateFlow<Boolean>
) {

    companion object {
        private const val WS_URL = "wss://api.webtool.space/ws"
        private val BASE_DELAY_MS = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000, 60000, 120000)
    }

    sealed class NetworkState {
        object Connecting : NetworkState()
        object Connected : NetworkState()
        data class Disconnected(val retry: Int) : NetworkState()
        object Closed : NetworkState()
        data class Kicked(val reason: String) : NetworkState()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private var retryCount = 0
    private var shouldReconnect = true

    /** 离线消息缓冲队列：未连接时缓存，连接后 flush */
    private val pendingQueue = ConcurrentLinkedQueue<String>()

    private var pingJob: Job? = null
    private var isKicked = false

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Closed)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    val isConnected get() = _networkState.value is NetworkState.Connected

    // 帧回调（由 MessageManager 注入）
    var onFrame: ((String) -> Unit)? = null
    // 连接成功回调（MessageManager 注入，用于发 sync 帧）
    var onConnected: (() -> Unit)? = null

    init {
        // 监听系统网络恢复瞬间
        scope.launch {
            isOnlineFlow.collect { isOnline ->
                // 如果当前是在重连退避期（Disconnected 状态），且网络恢复，立即打断并直连
                if (isOnline && shouldReconnect && !isKicked && _networkState.value is NetworkState.Disconnected) {
                    val uuid = lastUuid
                    val token = lastToken
                    if (uuid != null && token != null) {
                        retryCount = 0
                        doConnect(uuid, token)
                    }
                }
            }
        }
    }

    private var lastUuid: String? = null
    private var lastToken: String? = null

    // ── 连接 ──────────────────────────────────────────────────────────────

    fun connect(uuid: String, token: String) {
        lastUuid = uuid
        lastToken = token
        shouldReconnect = true
        isKicked = false
        retryCount = 0
        doConnect(uuid, token)
    }

    private fun doConnect(uuid: String, token: String) {
        _networkState.value = NetworkState.Connecting

        val request = Request.Builder()
            .url("$WS_URL?user_uuid=$uuid&token=$token")
            .build()

        ws = http.okhttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0
                _networkState.value = NetworkState.Connected
                // 连接成功后 flush 离线队列
                flushPendingQueue()
                startPingJob()
                // 通知 MessageManager 发 sync 帧
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onFrame?.invoke(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopPingJob()
                if (isKicked) return
                _networkState.value = NetworkState.Closed
                if (shouldReconnect && isOnlineFlow.value) scheduleReconnect(uuid, token)
                else if (shouldReconnect) _networkState.value = NetworkState.Disconnected(retryCount)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopPingJob()
                if (isKicked) return
                _networkState.value = NetworkState.Disconnected(retryCount)
                if (shouldReconnect && isOnlineFlow.value) scheduleReconnect(uuid, token)
            }
        })
    }

    private fun flushPendingQueue() {
        while (pendingQueue.isNotEmpty()) {
            val msg = pendingQueue.poll() ?: break
            ws?.send(msg)
        }
    }

    private fun scheduleReconnect(uuid: String, token: String) {
        if (retryCount >= BASE_DELAY_MS.size) return
        val delayMs = BASE_DELAY_MS[retryCount++]
        _networkState.value = NetworkState.Disconnected(retryCount)
        scope.launch {
            delay(delayMs)
            // 在 delay 后，如果依然允许且确实连接已断，重试
            if (shouldReconnect && !isKicked && _networkState.value is NetworkState.Disconnected) {
                doConnect(uuid, token)
            }
        }
    }

    private fun startPingJob() {
        stopPingJob()
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000)
                send("{\"type\":\"ping\",\"crypto_v\":1}")
            }
        }
    }

    private fun stopPingJob() {
        pingJob?.cancel()
        pingJob = null
    }

    // ── 断开 ──────────────────────────────────────────────────────────────

    fun forceDisconnectGoaway(reason: String) {
        isKicked = true
        shouldReconnect = false
        _networkState.value = NetworkState.Kicked(reason)
        ws?.close(1000, "goaway")
        ws = null
        stopPingJob()
        pendingQueue.clear()
    }

    fun disconnect() {
        shouldReconnect = false
        isKicked = false
        ws?.close(1000, "user logout")
        ws = null
        stopPingJob()
        pendingQueue.clear()
        _networkState.value = NetworkState.Closed
    }

    // ── 发送帧 ────────────────────────────────────────────────────────────

    /**
     * 发送 JSON 帧（MessageManager 调用）
     * 若当前未连接，入队等待重连后自动发送
     * @return true=立即发出，false=已入队（将在重连后发送）
     */
    fun send(json: String): Boolean {
        val socket = ws
        return if (socket != null && isConnected) {
            socket.send(json)
        } else {
            pendingQueue.offer(json)
            false
        }
    }
}
