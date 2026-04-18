package space.securechat.sample

import android.app.Application
import space.securechat.sdk.SecureChatClient

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 🔒 SDK 初始化（一次，全局单例）
        SecureChatClient.init(this)
    }
}
