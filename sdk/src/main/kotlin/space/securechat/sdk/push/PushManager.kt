package space.securechat.sdk.push

import space.securechat.sdk.http.*

/**
 * 🔒 PushManager — FCM 推送注册
 *
 * 👤 App 必须：在 FirebaseMessaging.getInstance().token 回调中拿到 token 后调用 register()
 * 对标 sdk-typescript/src/push/manager.ts（Web Push 对应 FCM）
 *
 * 服务端推送格式：{ "type": "new_msg", "conv_id": "..." }
 * App 在 FirebaseMessagingService.onMessageReceived() 中解析 conv_id 即可
 */
class PushManager(private val http: HttpClient) {

    /**
     * 注册 FCM Token 到 relay-server
     * 对标 TS SDK: client.push.enablePushNotifications(registration)
     *
     * @param fcmToken 从 FirebaseMessaging.getInstance().token 获取
     */
    suspend fun register(fcmToken: String) {
        http.api.registerPush(PushRegisterRequest(token = fcmToken, platform = "android"))
    }

    /**
     * 注销当前设备的推送订阅（用户关闭通知 / 登出时调用）
     * 对标 iOS/TS SDK: client.push.disablePush()
     */
    suspend fun disable() {
        http.api.disablePush()
    }
}
