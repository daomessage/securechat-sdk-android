package space.securechat.sample.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.securechat.sdk.SecureChatClient
import space.securechat.sample.ui.theme.*

@Composable
fun SettingsTabContent(
    aliasId: String, nickname: String, mnemonic: String,
    client: SecureChatClient,
    lifecycleScope: kotlinx.coroutines.CoroutineScope,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var showMnemonic by remember { mutableStateOf(false) }
    var mnemonicConfirm by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("设置", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Divider(color = DividerColor) }

        // 个人信息
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceBg)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("个人信息", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (nickname.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("昵称:", color = ZincText, fontSize = 14.sp)
                            Text(nickname, color = Color.White, fontSize = 14.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Alias ID:", color = ZincText, fontSize = 14.sp)
                        Text(aliasId, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("alias_id", aliasId))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制", color = BlueAccent, fontSize = 13.sp) }
                    }
                }
            }
        }

        // 安全设置
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp, 0.dp), colors = CardDefaults.cardColors(containerColor = SurfaceBg)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("安全", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔒 端到端加密", color = GreenOk, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("X25519-AES-GCM", color = ZincText, fontSize = 12.sp)
                    }

                    if (!showMnemonic) {
                        OutlinedButton(
                            onClick = { showMnemonic = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
                        ) { Text("查看助记词", color = AmberWarn, fontSize = 14.sp) }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⚠️ 助记词是账号的唯一凭证，请勿截图：", color = AmberWarn, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = mnemonicConfirm, onCheckedChange = { mnemonicConfirm = it })
                                Text("我明白泄露助记词将导致账号丢失", color = ZincText, fontSize = 13.sp)
                            }
                            if (mnemonicConfirm && mnemonic.isNotEmpty()) {
                                val words = mnemonic.split(" ")
                                words.chunked(3).forEachIndexed { rowIdx, row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEachIndexed { colIdx, word ->
                                            val idx = rowIdx * 3 + colIdx + 1
                                            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = DarkBg)) {
                                                Column(Modifier.padding(6.dp)) {
                                                    Text("$idx", color = ZincText, fontSize = 9.sp)
                                                    Text(word, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { showMnemonic = false; mnemonicConfirm = false }) {
                                Text("隐藏", color = ZincText)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) { Text("退出登录", color = RedDanger, fontSize = 15.sp) }
        }
    }
}
