package space.securechat.sample.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.securechat.sample.ui.theme.*
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * CallScreen —— Jetpack Compose 通话界面
 *
 * 支持状态：CALLING / RINGING / CONNECTING / CONNECTED / ENDED
 * 操作：发起方可挂断，被叫方可接听/拒绝
 */
@Composable
fun CallScreen(
    state:         CallManager.State,
    remoteAlias:   String,
    callManager:   CallManager,
    onCallEnded:   () -> Unit
) {
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    DisposableEffect(callManager) {
        callManager.onLocalStream = { video, _ -> 
            if (video != null) localVideoTrack = video 
        }
        callManager.onRemoteStream = { video, _ -> 
            if (video != null) remoteVideoTrack = video 
        }
        onDispose {
            callManager.onLocalStream = null
            callManager.onRemoteStream = null
        }
    }

    // ── 计时器（通话中显示时长）────────────────────────────────────
    var durationSec by remember { mutableStateOf(0) }
    LaunchedEffect(state) {
        if (state == CallManager.State.CONNECTED) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                durationSec++
            }
        } else {
            durationSec = 0
        }
    }

    // ── 连接中：脉冲动画 ────────────────────────────────────────
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 0.95f,
        targetValue  = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // ── 响铃：摇摆动画 ──────────────────────────────────────────
    val ringAnim  = rememberInfiniteTransition(label = "ring")
    val ringAngle by ringAnim.animateFloat(
        initialValue = -10f,
        targetValue  = 10f,
        animationSpec = infiniteRepeatable(
            tween(200, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "ringAngle"
    )

    val isEnding = state == CallManager.State.HANGUP ||
                   state == CallManager.State.ENDED  ||
                   state == CallManager.State.REJECTED

    // 通话结束后自动返回
    LaunchedEffect(isEnding) {
        if (isEnding) {
            kotlinx.coroutines.delay(1500)
            onCallEnded()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1a1a2e), Color(0xFF16213e), Color(0xFF0f3460))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── 远端视频层 ───────────────────────────────────────────
        if (remoteVideoTrack != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(callManager.eglBaseContext, null)
                        setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                    }
                },
                update = { view -> remoteVideoTrack?.addSink(view) },
                onRelease = { view ->
                    remoteVideoTrack?.removeSink(view)
                    view.release()
                }
            )
        }

        // ── 本地视频悬浮层 ─────────────────────────────────────────
        if (localVideoTrack != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 100.dp, height = 150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(callManager.eglBaseContext, null)
                            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setEnableHardwareScaler(true)
                            setMirror(true) // 本地前置镜像
                        }
                    },
                    update = { view -> localVideoTrack?.addSink(view) },
                    onRelease = { view ->
                        localVideoTrack?.removeSink(view)
                        view.release()
                    }
                )
            }
        }

        // ── UI 控制层 ──────────────────────────────────────────────
        val isVideoMode = remoteVideoTrack != null
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isVideoMode) {
                Spacer(Modifier.height(64.dp))

                // ── 头像区域 ──────────────────────────────────────────
                val avatarScale = if (state == CallManager.State.CONNECTING ||
                                      state == CallManager.State.CALLING) pulseScale else 1f

                Box(
                    Modifier
                        .size(120.dp)
                        .scale(avatarScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(listOf(Color(0xFF6C63FF), Color(0xFF3F51B5)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = remoteAlias.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // 如果有视频，顶部推远，避免挡挡住人脸
                Spacer(Modifier.height(32.dp))
            }

            Spacer(Modifier.height(24.dp))

            // ── 对方名称 ──────────────────────────────────────────
            Text(
                text = remoteAlias.ifEmpty { "未知联系人" },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))

            // ── 状态文字 ──────────────────────────────────────────
            Text(
                text = when (state) {
                    CallManager.State.CALLING    -> "正在呼叫…"
                    CallManager.State.RINGING    -> "来电呼入"
                    CallManager.State.CONNECTING -> "正在建立加密通道…"
                    CallManager.State.CONNECTED  -> {
                        val m = durationSec / 60
                        val s = durationSec % 60
                        "%02d:%02d".format(m, s)
                    }
                    CallManager.State.HANGUP,
                    CallManager.State.ENDED      -> "通话已结束"
                    CallManager.State.REJECTED   -> "对方拒绝了通话"
                    CallManager.State.IDLE       -> ""
                },
                color = when {
                    isEnding                          -> Color(0xFFEF5350)
                    state == CallManager.State.CONNECTED -> Color(0xFF66BB6A)
                    else                              -> Color(0xFF90CAF9)
                },
                fontSize = if (state == CallManager.State.CONNECTED) 22.sp else 15.sp,
                fontFamily = if (state == CallManager.State.CONNECTED) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.Center
            )

            // E2EE 标识（通话中）
            if (state == CallManager.State.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x2266BB6A))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("🔒", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("端到端加密通话", color = Color(0xFF66BB6A), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            // ── 控制按钮区域 ──────────────────────────────────────
            if (!isEnding) {
                when (state) {
                    CallManager.State.RINGING -> {
                        // 响铃：拒绝 + 接听
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CallControlButton(
                                emoji = "📵",
                                label = "拒绝",
                                bgColor = Color(0xFFEF5350),
                                onClick = { callManager.reject() }
                            )
                            CallControlButton(
                                emoji = "📞",
                                label = "接听",
                                bgColor = Color(0xFF66BB6A),
                                size = 72.dp,
                                onClick = { callManager.answer() }
                            )
                        }
                    }

                    CallManager.State.CALLING,
                    CallManager.State.CONNECTING,
                    CallManager.State.CONNECTED -> {
                        // 通话中：挂断（中间大按钮）
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CallControlButton(
                                emoji = "📵",
                                label = "挂断",
                                bgColor = Color(0xFFEF5350),
                                size = 72.dp,
                                onClick = { callManager.hangup() }
                            )
                        }
                    }

                    else -> Unit
                }
            } else {
                // 结束态：提示文字
                Text(
                    "通话已结束，即将返回…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 48.dp)
                )
            }
        }
    }
}

// ── 控制按钮通用组件 ──────────────────────────────────────────────
@Composable
private fun CallControlButton(
    emoji: String,
    label: String,
    bgColor: Color,
    size: androidx.compose.ui.unit.Dp = 60.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = bgColor),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(size)
        ) {
            Text(emoji, fontSize = (size.value * 0.38).sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}
