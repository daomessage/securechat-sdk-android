package space.securechat.sample.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.*

val DarkBg      = Color(0xFF09090B)
val SurfaceBg   = Color(0xFF18181B)
val DividerColor= Color(0xFF27272A)
val ZincText    = Color(0xFF71717A)
val ZincMuted   = Color(0xFF52525B)
val BlueAccent  = Color(0xFF3B82F6)
val GreenOk     = Color(0xFF22C55E)
val RedDanger   = Color(0xFFEF4444)
val AmberWarn   = Color(0xFFF59E0B)

@Composable
fun SecureChatSampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = SurfaceBg,
            primary = BlueAccent
        ),
        content = content
    )
}

// ── 时间与显示格式化抽离 ─────────────────────────────────────────────────────────

fun formatMsgTime(ts: Long): String {
    if (ts <= 0L) return ""
    val diff = System.currentTimeMillis() - ts
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        diff < 86400_000 * 2 -> "昨天"
        diff < 86400_000 * 7 ->
            arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(Calendar.DAY_OF_WEEK)]
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(ts))
    }
}

fun formatMsgPreview(text: String, replyToId: String? = null): String {
    return try {
        val baseMsg = if (text.startsWith("{")) {
            val type = Regex("\"msgType\"\\s*:\\s*\"(\\w+)\"").find(text)?.groupValues?.get(1)
            when (type) { "image" -> "[图片]"; "file" -> "[文件]"; "voice" -> "[语音]"; else -> text }
        } else text
        
        if (replyToId != null) "[回复] $baseMsg" else baseMsg
    } catch (_: Exception) { text }
}
