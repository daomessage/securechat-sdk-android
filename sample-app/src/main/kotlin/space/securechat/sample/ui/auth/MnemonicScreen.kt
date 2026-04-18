package space.securechat.sample.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.securechat.sample.ui.theme.*

@Composable
fun MnemonicScreen(mnemonic: String, onConfirm: (String) -> Unit) {
    var nick by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(DarkBg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📋 备份助记词", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SecureChat Mnemonic", mnemonic)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "助记词已复制", Toast.LENGTH_SHORT).show()
            }) {
                Text("一键复制", color = BlueAccent)
            }
        }
        
        Text("这 12 个词是你账号的唯一凭证，丢失无法恢复！", color = RedDanger, fontSize = 13.sp)

        val words = mnemonic.split(" ")
        words.chunked(3).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { colIdx, word ->
                    val idx = rowIdx * 3 + colIdx + 1
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = SurfaceBg)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("$idx", color = ZincText, fontSize = 10.sp)
                            Text(word, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text("我已安全备份助记词", color = ZincText, fontSize = 14.sp)
        }

        OutlinedTextField(
            value = nick, onValueChange = { nick = it },
            label = { Text("设置昵称", color = ZincText) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Button(
            onClick = { if (!busy && confirmed && nick.isNotBlank()) { busy = true; onConfirm(nick) } },
            enabled = confirmed && nick.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)
        ) {
            if (busy) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "注册中..." else "完成注册", fontSize = 16.sp)
        }
    }
}
