package space.securechat.sample.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.securechat.sample.ui.theme.BlueAccent
import space.securechat.sample.ui.theme.DarkBg
import space.securechat.sample.ui.theme.ZincText

@Composable
fun WelcomeScreen(
    onRegister: () -> Unit,
    onRecover: (String) -> Unit
) {
    var isRecoverMode by remember { mutableStateOf(false) }
    var inputMnemonic by remember { mutableStateOf("") }
    
    Box(Modifier.fillMaxSize().background(DarkBg).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("🔒", fontSize = 64.sp)
            Text("SecureChat", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("端到端加密 IM\n你的 Key，你做主", color = ZincText, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            
            if (!isRecoverMode) {
                Button(
                    onClick = onRegister,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("创建新账号", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                
                Text(
                    text = "或 通过助记词恢复账号",
                    color = BlueAccent,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { isRecoverMode = true }.padding(8.dp)
                )
            } else {
                OutlinedTextField(
                    value = inputMnemonic,
                    onValueChange = { inputMnemonic = it },
                    label = { Text("输入 12 位助记词，用空格分隔", color = ZincText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 3
                )
                
                Button(
                    onClick = { if (inputMnemonic.isNotBlank()) onRecover(inputMnemonic.trim().replace(Regex("\\s+"), " ")) },
                    enabled = inputMnemonic.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("恢复", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                
                Text(
                    text = "取消返回",
                    color = ZincText,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { isRecoverMode = false; inputMnemonic = "" }.padding(8.dp)
                )
            }
        }
    }
}
