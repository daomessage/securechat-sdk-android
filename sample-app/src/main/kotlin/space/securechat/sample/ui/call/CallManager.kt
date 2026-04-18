package space.securechat.sample.ui.call

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.webrtc.*
import space.securechat.sdk.SecureChatClient

/**
 * CallManager —— Android 原生 WebRTC 信令状态机（正规 SDK 接入版）
 *
 * 信令通道：
 *   接收 →  client.on(EVENT_SIGNAL)   ← MessageManager 路由 call_* 帧
 *   发送 →  client.sendSignalFrame()  ← transport.send() 直接 WS 发出
 *
 * 无任何反射调用，所有信令均通过 SecureChatClient 公共 API 完成。
 */
class CallManager(
    private val context: Context,
    private val client: SecureChatClient,
    private val myAliasId: String,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "CallManager"
    }

    // ── 通话状态 ─────────────────────────────────────────────────
    enum class State { IDLE, CALLING, RINGING, CONNECTING, CONNECTED, HANGUP, REJECTED, ENDED }

    @Volatile var state: State = State.IDLE
        private set

    private var callId: String = ""
    private var remoteAlias: String = ""
    private var callerAlias: String = ""

    // ── 对外回调（MainActivity 注入）─────────────────────────────
    var onStateChange:  ((State) -> Unit)? = null
    var onLocalStream:  ((VideoTrack, AudioTrack?) -> Unit)? = null
    var onRemoteStream: ((VideoTrack?, AudioTrack?) -> Unit)? = null
    var onIncomingCall: ((fromAlias: String) -> Unit)? = null
    var onError:        ((Throwable) -> Unit)? = null

    // ── WebRTC 核心对象 ───────────────────────────────────────────
    private var factory:         PeerConnectionFactory? = null
    private var pc:              PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var capturer:        CameraVideoCapturer? = null

    val eglBaseContext: EglBase.Context by lazy { EglBase.create().eglBaseContext }

    /** 用于取消信令订阅的 unsubscribe 函数 */
    private var unsubSignal: (() -> Unit)? = null

    // ── 初始化 ────────────────────────────────────────────────────
    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglBaseContext))
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .createPeerConnectionFactory()

        // 订阅通话信令帧（正规 SDK API，无反射）
        unsubSignal = client.on(SecureChatClient.EVENT_SIGNAL) { frame: Map<String, Any?> ->
            handleSignal(frame)
        }
    }

    // ── 发起通话 ─────────────────────────────────────────────────
    fun call(toAliasId: String, enableVideo: Boolean = true, enableAudio: Boolean = true) {
        when (state) {
            // 终态状态：自动清理后允许重新发起
            State.HANGUP, State.ENDED, State.REJECTED -> {
                Log.d(TAG, "call() 从终态 $state 开始，自动重置")
                cleanup(State.IDLE)
            }
            // 通话进行中：拑绝新请求
            State.CALLING, State.RINGING, State.CONNECTING, State.CONNECTED -> {
                Log.w(TAG, "call() 当前有通话进行（$state），忽略")
                return
            }
            State.IDLE -> { /* 正常流程 */ }
        }

        callId      = java.util.UUID.randomUUID().toString()
        remoteAlias = toAliasId
        setState(State.CALLING)

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "call() [1/6] 开始获取 TURN 凭证")
                val iceServers = fetchIceServers()
                Log.d(TAG, "call() [2/6] 获取 TURN 凭证完成，准备创建 PeerConnection")
                
                pc = createPeerConnection(iceServers, toAliasId)
                Log.d(TAG, "call() [3/6] PeerConnection 创建成功，准备附加本地媒体")
                
                attachLocalMedia(enableVideo, enableAudio)
                Log.d(TAG, "call() [4/6] 附加本地媒体完成")

                val hasVideo = localVideoTrack != null
                Log.d(TAG, "call() [5/6] 准备创建 SDP Offer (hasVideo=$hasVideo)")
                val offer = awaitSdp { pc!!.createOffer(it, buildMediaConstraints(hasVideo)) }
                
                Log.d(TAG, "call() [6/6] SDP Offer 创建成功，执行 setLocalDescription")
                pc!!.setLocalDescription(SdpObserverCompat(onFail = { cleanup(State.ENDED) }), offer)

                client.sendSignalFrame(mapOf(
                    "type"      to "call_offer",
                    "to"        to toAliasId,
                    "from"      to myAliasId,
                    "call_id"   to callId,
                    "sdp"       to offer.description,
                    "sdp_type"  to "offer",
                    "crypto_v"  to 1
                ))
                Log.d(TAG, "call() 信令 call_offer 发送成功")
            } catch (e: Exception) {
                Log.e(TAG, "call() 失败 (发生异常)", e)
                onError?.invoke(e)
                cleanup(State.ENDED)
            }
        }
    }

    // ── 接听 ─────────────────────────────────────────────────────
    fun answer() {
        if (state != State.RINGING || pc == null) return

        scope.launch(Dispatchers.IO) {
            try {
                attachLocalMedia(enableVideo = true, enableAudio = true)
                val hasVideo = localVideoTrack != null
                val answer = awaitSdp { pc!!.createAnswer(it, buildMediaConstraints(hasVideo)) }
                pc!!.setLocalDescription(SdpObserverCompat(onFail = { cleanup(State.ENDED) }), answer)

                client.sendSignalFrame(mapOf(
                    "type"     to "call_answer",
                    "to"       to callerAlias,
                    "from"     to myAliasId,
                    "call_id"  to callId,
                    "sdp"      to answer.description,
                    "sdp_type" to "answer",
                    "crypto_v" to 1
                ))
                setState(State.CONNECTING)
            } catch (e: Exception) {
                Log.e(TAG, "answer() 失败", e)
                onError?.invoke(e)
                cleanup(State.ENDED)
            }
        }
    }

    // ── 拒绝 ─────────────────────────────────────────────────────
    fun reject() {
        if (state != State.RINGING) return
        client.sendSignalFrame(mapOf(
            "type"     to "call_reject",
            "to"       to callerAlias,
            "from"     to myAliasId,
            "call_id"  to callId,
            "crypto_v" to 1
        ))
        cleanup(State.REJECTED)
    }

    // ── 挂断 ─────────────────────────────────────────────────────
    fun hangup() {
        if (state == State.IDLE || state == State.HANGUP || state == State.ENDED) return
        val target = if (callerAlias.isNotEmpty()) callerAlias else remoteAlias
        client.sendSignalFrame(mapOf(
            "type"     to "call_hangup",
            "to"       to target,
            "from"     to myAliasId,
            "call_id"  to callId,
            "crypto_v" to 1
        ))
        cleanup(State.HANGUP)
    }

    // ── 释放资源 ─────────────────────────────────────────────────
    fun release() {
        unsubSignal?.invoke()
        unsubSignal = null
        cleanup(State.IDLE)
        factory?.dispose()
        factory = null
    }

    // ── 信令接收 ─────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    private fun handleSignal(frame: Map<String, Any?>) {
        val type = frame["type"] as? String ?: return
        when (type) {
            "call_offer"   -> handleOffer(frame)
            "call_answer"  -> handleAnswer(frame)
            "call_ice"     -> handleIce(frame)
            "call_hangup"  -> { if (state != State.IDLE) cleanup(State.ENDED) }
            "call_reject"  -> { if (state != State.IDLE) cleanup(State.REJECTED) }
        }
    }

    private fun handleOffer(frame: Map<String, Any?>) {
        val from = frame["from"] as? String ?: frame["From"] as? String ?: ""
        if (from.isEmpty() || from == "null") {
            Log.e(TAG, "CRITICAL ERROR: 'from' missing in call_offer...")
            return
        }
        val sdp  = frame["sdp"]  as? String ?: return
        callId      = frame["call_id"] as? String ?: ""
        callerAlias = from
        remoteAlias = from

        scope.launch(Dispatchers.IO) {
            try {
                val iceServers = fetchIceServers()
                pc = createPeerConnection(iceServers, from)
                pc!!.setRemoteDescription(
                    SdpObserverCompat(),
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
                // 等待底层设置完成后再刷入
                scope.launch(Dispatchers.Main) { 
                    flushIceCandidates()
                    onIncomingCall?.invoke(from)
                    setState(State.RINGING)
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleOffer 失败", e)
                onError?.invoke(e)
            }
        }
    }

    private val pendingCandidates = mutableListOf<IceCandidate>()

    private fun flushIceCandidates() {
        if (pc == null) return
        synchronized(pendingCandidates) {
            for (c in pendingCandidates) {
                try { pc?.addIceCandidate(c) } catch (e: Exception) {}
            }
            pendingCandidates.clear()
        }
    }

    private fun handleAnswer(frame: Map<String, Any?>) {
        val sdp = frame["sdp"] as? String ?: return
        scope.launch(Dispatchers.IO) {
            try {
                pc?.setRemoteDescription(
                    SdpObserverCompat(),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
                // 等待底层设置完成后再刷入
                scope.launch(Dispatchers.Main) { flushIceCandidates() }
            } catch (e: Exception) {
                Log.e(TAG, "handleAnswer 失败", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleIce(frame: Map<String, Any?>) {
        val candMap = (frame["candidate"] as? Map<*, *>) ?: return
        val candidate = IceCandidate(
            candMap["sdpMid"]        as? String ?: "",
            (candMap["sdpMLineIndex"] as? Double)?.toInt() ?: 0,
            candMap["candidate"]     as? String ?: ""
        )
        Log.d(TAG, "Received remote ICE candidate: ${candidate.sdpMid}")
        scope.launch(Dispatchers.IO) {
            // 如果底层 remoteDescription 还没设置好，先挂空档拦住
            if (pc?.remoteDescription == null) {
                synchronized(pendingCandidates) {
                    pendingCandidates.add(candidate)
                }
                return@launch
            }
            try { pc?.addIceCandidate(candidate) } catch (e: Exception) {}
        }
    }

    // ── PeerConnection 工厂 ──────────────────────────────────────
    private fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, remoteAlias: String): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        return factory!!.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE Candidate generated: ${candidate.sdpMid} ${candidate.sdpMLineIndex}")
                scope.launch(Dispatchers.IO) {
                    client.sendSignalFrame(mapOf(
                        "type"      to "call_ice",
                        "to"        to remoteAlias,
                        "from"      to myAliasId,
                        "call_id"   to callId,
                        "crypto_v"  to 1,
                        "candidate" to mapOf(
                            "sdpMid"        to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex.toDouble(),
                            "candidate"     to candidate.sdp
                        )
                    ))
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch(Dispatchers.Main) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED    -> setState(State.CONNECTED)
                        // FAILED 是真正终止；DISCONNECTED 是 ICE 重连瞬态（可恢复），不应立即结束通话
                        PeerConnection.PeerConnectionState.FAILED       -> {
                            Log.w(TAG, "PeerConnection FAILED, ending call")
                            cleanup(State.ENDED)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "PeerConnection DISCONNECTED (transient, waiting for reconnect)")
                            // 不做任何事，ICE 将自动尝试重连
                        }
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                scope.launch(Dispatchers.Main) {
                    Log.d(TAG, "ICE Connection State Change: $newState")
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                Log.d(TAG, "onAddTrack received: id=${track.id()} kind=${track.kind()}, streams=${mediaStreams?.size}")
                scope.launch(Dispatchers.Main) {
                    if (track.kind() == "video" && track is VideoTrack) {
                        Log.d(TAG, "onAddTrack passing VideoTrack to UI")
                        onRemoteStream?.invoke(track, null)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                Log.d(TAG, "onTrack received: id=${track.id()} kind=${track.kind()}, state=${track.state()}, cls=${track.javaClass.name}")
                scope.launch(Dispatchers.Main) {
                    if (track.kind() == "video" && track is VideoTrack) {
                        Log.d(TAG, "onTrack passing VideoTrack to UI")
                        onRemoteStream?.invoke(track, null)
                    } else if (track.kind() == "audio" && track is AudioTrack) {
                        Log.d(TAG, "onTrack passing AudioTrack to UI")
                        onRemoteStream?.invoke(null, track)
                    }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling State Change: $p0")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "onAddStream received: video=${stream.videoTracks.size}, audio=${stream.audioTracks.size}")
                scope.launch(Dispatchers.Main) {
                    val video = stream.videoTracks.firstOrNull()
                    val audio = stream.audioTracks.firstOrNull()
                    if (video != null) {
                        Log.d(TAG, "onAddStream passing VideoTrack to UI")
                        onRemoteStream?.invoke(video, null)
                    }
                    if (audio != null) {
                        Log.d(TAG, "onAddStream passing AudioTrack to UI")
                        onRemoteStream?.invoke(null, audio)
                    }
                }
            }
            override fun onRemoveStream(p0: MediaStream) {}
            override fun onDataChannel(p0: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })!!
    }

    // ── 本地媒体采集 ─────────────────────────────────────────────
    private fun attachLocalMedia(enableVideo: Boolean, enableAudio: Boolean) {
        val f = factory ?: return

        if (enableAudio) {
            localAudioTrack = f.createAudioTrack("audio0", f.createAudioSource(MediaConstraints()))
            localAudioTrack?.let { pc?.addTrack(it, listOf("stream0")) }
        }

        if (enableVideo) {
            capturer = createCameraVideoCapturer()
            if (capturer != null) {
                val videoSource = f.createVideoSource(false)
                capturer!!.initialize(
                    SurfaceTextureHelper.create("CamThread", null),
                    context,
                    videoSource.capturerObserver
                )
                capturer!!.startCapture(1280, 720, 30)
                localVideoTrack = f.createVideoTrack("video0", videoSource)
                localVideoTrack?.let { vt ->
                    pc?.addTrack(vt, listOf("stream0"))
                    scope.launch(Dispatchers.Main) {
                        onLocalStream?.invoke(vt, localAudioTrack)
                    }
                }
            } else {
                Log.w(TAG, "无可用摄像头，跳过视频轨")
            }
        }
    }

    private fun createCameraVideoCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        return enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.deviceNames.firstOrNull()
                ?.let { enumerator.createCapturer(it, null) }
    }

    // ── 状态管理 ─────────────────────────────────────────────────
    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        scope.launch(Dispatchers.Main) { onStateChange?.invoke(state) }
        
        if (newState == State.CONNECTING || newState == State.CONNECTED) {
            configureAudio(enableSpeaker = true)
        }
    }

    private fun cleanup(finalState: State) {
        configureAudio(enableSpeaker = false)
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose();        capturer        = null
        localVideoTrack?.dispose(); localVideoTrack  = null
        localAudioTrack?.dispose(); localAudioTrack  = null
        pc?.close();                pc              = null
        callerAlias = ""
        remoteAlias = ""
        callId      = ""
        setState(finalState)
    }

    private fun configureAudio(enableSpeaker: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (enableSpeaker) {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } else {
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        }
    }

    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> {
        val turnConfig = try { client.fetchTurnConfig() } catch (e: Exception) { null }
        if (turnConfig != null) {
            val servers = turnConfig.iceServers.mapNotNull { srv ->
                @Suppress("UNCHECKED_CAST")
                val urls = (srv["urls"] as? List<String>) ?: return@mapNotNull null
                val username   = srv["username"]   as? String
                val credential = srv["credential"] as? String
                val builder = PeerConnection.IceServer.builder(urls)
                if (username != null && credential != null) {
                    builder.setUsername(username).setPassword(credential)
                }
                builder.createIceServer()
            }
            if (servers.isNotEmpty()) {
                Log.d(TAG, "TURN 凭证获取成功，服务器数量: ${servers.size}")
                return servers
            }
        }
        Log.w(TAG, "TURN 凭证获取失败，降级为 Google STUN")
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    private fun buildMediaConstraints(hasVideo: Boolean = false) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        // 即便本地无摄像头（如模拟器），也要声明接收视频，否则无法看到对方画面 (recvonly)
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private suspend fun awaitSdp(block: (SdpObserver) -> Unit): SessionDescription {
        return suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
                override fun onCreateFailure(p0: String?) { cont.cancel(Exception("SDP 创建失败: $p0")) }
                override fun onSetSuccess()   {}
                override fun onSetFailure(p0: String?) {}
            })
        }
    }

    private class SdpObserverCompat(private val onFail: (() -> Unit)? = null) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) { Log.w("SdpObserver", "创建失败: $p0") }
        override fun onSetSuccess()    {}
        override fun onSetFailure(p0: String?) {
            Log.w("SdpObserver", "设置失败: $p0")
            onFail?.invoke()   // 失败时终止通话，防止 state 卡死
        }
    }
}
