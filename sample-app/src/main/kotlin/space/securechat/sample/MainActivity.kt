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
        
        // Android 13 (API 33) 及以上必须动态申请推送权限才能在后台/前台显示横幅
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
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

enum class AppScreen { LOADING, WELCOME, MNEMONIC, MAIN, CHAT }

@Composable
fun SampleAppRoot(
    client: SecureChatClient,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    initialConvId: String? = null
) {
    var screen by remember { mutableStateOf(AppScreen.LOADING) }
    var aliasId by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var activeFriend by remember { mutableStateOf<Friend?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    val networkState by client.networkState.collectAsState()

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
                ChatScreen(client, f, lifecycleScope, onBack = { screen = AppScreen.MAIN })
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
