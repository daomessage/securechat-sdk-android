@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.securechat.sample

import com.google.firebase.messaging.FirebaseMessaging
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.securechat.sdk.SecureChatClient
import space.securechat.sdk.contacts.Friend
import space.securechat.sdk.keys.KeyDerivation
import space.securechat.sample.ui.theme.*
import space.securechat.sample.ui.auth.*
import space.securechat.sample.ui.main.*
import space.securechat.sample.ui.chat.*
import space.securechat.sample.ui.call.CallManager
import space.securechat.sample.ui.call.CallScreen
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val client get() = SecureChatClient.getInstance()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 这个特性由于 Compose Navigation 暂不够稳固，这里用简单的方式重启 Activity
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialConvId = intent?.getStringExtra("conv_id")
        
        // 动态申请推送与音视频权限（避免 WebRTC 调用相机麦克风抛出 SecurityException）
        val requiredPermissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = requiredPermissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 101)
        }
        // 开启 edge-to-edge 使 imePadding() 正确响应软键盘
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            SecureChatSampleTheme {
                SampleAppRoot(
                    client = client,
                    lifecycleScope = lifecycleScope,
                    initialConvId = initialConvId
                )
            }
        }
    }
}

enum class AppScreen { LOADING, WELCOME, MNEMONIC, MAIN, CHAT, CALL }

@Composable
fun SampleAppRoot(
    client: SecureChatClient,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    initialConvId: String? = null
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(AppScreen.LOADING) }
    var aliasId by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var activeFriend by remember { mutableStateOf<Friend?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    val networkState by client.networkState.collectAsState()

    // 通话管理器（登录后创建）
    var callManager by remember { mutableStateOf<CallManager?>(null) }
    var callState by remember { mutableStateOf(CallManager.State.IDLE) }
    var callRemoteAlias by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val session = client.restoreSession()
            if (session != null) {
                aliasId = session.first; nickname = session.second
                client.connect()
                friends = client.contacts.syncFriends()
                // 注册 FCM Token
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    lifecycleScope.launch { runCatching { client.push.register(token) } }
                }
                
                // 处理从通知点进来的直达意图
                if (!initialConvId.isNullOrEmpty()) {
                    val targetFriend = friends.find { it.conversationId == initialConvId }
                    if (targetFriend != null) {
                        activeFriend = targetFriend
                        screen = AppScreen.CHAT
                    } else {
                        screen = AppScreen.MAIN
                    }
                } else {
                    screen = AppScreen.MAIN
                }

                // 初始化通话管理器
                val mgr = CallManager(
                    context    = context,
                    client     = client,
                    myAliasId  = aliasId,
                    scope      = lifecycleScope
                )
                mgr.onStateChange = { s ->
                    callState = s
                    if (s == CallManager.State.RINGING || s == CallManager.State.CALLING) {
                        screen = AppScreen.CALL
                    }
                    if (s == CallManager.State.HANGUP || s == CallManager.State.ENDED ||
                        s == CallManager.State.REJECTED || s == CallManager.State.IDLE) {
                        // 延迟返回（CallScreen 内部也有 1.5s 延迟）
                        screen = AppScreen.CHAT
                    }
                }
                mgr.onIncomingCall = { fromAlias ->
                    callRemoteAlias = fromAlias
                    screen = AppScreen.CALL
                }
                callManager = mgr
            } else screen = AppScreen.WELCOME
        } catch (e: Exception) {
            android.util.Log.e("SecureChat", "恢复会话失败", e)
            screen = AppScreen.WELCOME
        }
    }

    when (screen) {
        AppScreen.LOADING -> LoadingScreen()

        AppScreen.WELCOME -> WelcomeScreen(
            onRegister = { mnemonic = KeyDerivation.newMnemonic(); screen = AppScreen.MNEMONIC },
            onRecover = { m->
                lifecycleScope.launch {
                    try {
                        // 利用助记词恢复
                        val resAlias = client.auth.loginExt(m) 
                        aliasId = resAlias
                        nickname = "已恢复用户_$resAlias"
                        mnemonic = m
                        client.connect()
                        friends = client.contacts.syncFriends()
                        screen = AppScreen.MAIN
                    } catch (e: Exception) {
                        android.util.Log.e("SecureChat", "恢复失败", e)
                    }
                }
            }
        )

        AppScreen.MNEMONIC -> MnemonicScreen(
            mnemonic = mnemonic,
            onConfirm = { nick ->
                lifecycleScope.launch {
                    if (isRegistering) return@launch
                    isRegistering = true
                    try {
                        aliasId = client.auth.registerAccount(mnemonic, nick)
                        nickname = nick
                        client.connect()
                        friends = client.contacts.syncFriends()
                        // 注册 FCM Token
                        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                            lifecycleScope.launch { runCatching { client.push.register(token) } }
                        }
                        screen = AppScreen.MAIN
                    } catch (e: Exception) {
                        android.util.Log.e("SecureChat", "注册失败", e)
                    } finally { isRegistering = false }
                }
            }
        )

        AppScreen.MAIN -> MainScreen(
            client = client,
            aliasId = aliasId, nickname = nickname, mnemonic = mnemonic,
            friends = friends, networkState = networkState,
            onFriendClick = { f -> activeFriend = f; screen = AppScreen.CHAT },
            onFriendListChanged = { friends = it },
            onLogout = {
                lifecycleScope.launch {
                    client.logout()
                    friends = emptyList(); aliasId = ""; nickname = ""
                    screen = AppScreen.WELCOME
                }
            },
            lifecycleScope = lifecycleScope
        )

        AppScreen.CHAT -> activeFriend?.let { f ->
            space.securechat.sample.ui.components.SwipeToBackWrapper(onBack = { screen = AppScreen.MAIN }) {
                androidx.activity.compose.BackHandler { screen = AppScreen.MAIN }
                ChatScreen(
                    client        = client,
                    friend        = f,
                    lifecycleScope = lifecycleScope,
                    onBack        = { screen = AppScreen.MAIN },
                    onStartCall   = { toAlias, enableVideo ->
                        callRemoteAlias = toAlias
                        callManager?.call(toAlias, enableVideo = enableVideo)
                        screen = AppScreen.CALL
                    }
                )
            }
        }

        AppScreen.CALL -> {
            val mgr = callManager
            if (mgr != null) {
                val friendNick = friends.find { it.aliasId == callRemoteAlias }?.nickname ?: callRemoteAlias
                CallScreen(
                    state        = callState,
                    remoteAlias  = friendNick,
                    callManager  = mgr,
                    onCallEnded  = { 
                        screen = if (activeFriend != null) AppScreen.CHAT else AppScreen.MAIN 
                    }
                )
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("🔒", fontSize = 48.sp)
            CircularProgressIndicator(color = BlueAccent)
            Text("正在加载...", color = ZincText, fontSize = 14.sp)
        }
    }
}
