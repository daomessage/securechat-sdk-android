package space.securechat.sample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import space.securechat.sdk.SecureChatClient

/**
 * 🔔 SecureChatFirebaseService — FCM 推送处理
 *
 * 职责：
 *   1. onNewToken：新 FCM Token 注册到 relay-server
 *   2. onMessageReceived：收到推送后显示本地通知
 *
 * 推送载荷格式（零知识，data-only）：
 *   { "type": "new_msg", "conv_id": "..." }
 *
 * 对标 TS SDK: template-app/public/sw.js (Service Worker push handler)
 */
class SecureChatFirebaseService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM"
        private const val CHANNEL_ID = "securechat_messages"
        private const val CHANNEL_NAME = "新消息"
    }

    /**
     * FCM Token 刷新时自动注册到 relay-server
     * 对标 TS SDK: client.push.enablePushNotifications(registration)
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: ${token.take(12)}...")

        // 异步注册（可能尚未登录，此时忽略错误）
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = SecureChatClient.getInstance()
                client.push.register(token)
                Log.d(TAG, "FCM token registered to server")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FCM token (user may not be logged in): ${e.message}")
            }
        }
    }

    /**
     * 收到 data-only 推送消息（零知识：只含 type + conv_id）
     * App 显示通用通知，用户点击后进入对应会话
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val type = message.data["type"] ?: return
        val convId = message.data["conv_id"] ?: return

        Log.d(TAG, "Push received: type=$type conv_id=$convId")

        if (type == "new_msg") {
            showNotification(convId)
        }
    }

    private fun showNotification(convId: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0+ 需要通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SecureChat 新消息通知"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        // 点击通知打开 App
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conv_id", convId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("SecureChat")
            .setContentText("你有一条新的加密消息")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(convId.hashCode(), notification)
    }
}
