package space.securechat.sample.ui

import java.text.SimpleDateFormat
import java.util.*

object FormatUtils {
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

    fun formatMsgPreview(text: String): String {
        return try {
            if (text.startsWith("{")) {
                val type = Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(text)?.groupValues?.get(1)
                when (type) { "image" -> "[图片]"; "file" -> "[文件]"; "voice" -> "[语音]"; else -> text }
            } else text
        } catch (_: Exception) { text }
    }
}
