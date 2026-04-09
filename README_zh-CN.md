# SecureChat Android SDK

[English](./README.md) | [简体中文](./README_zh-CN.md)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin-purple.svg)

> 对标 `@daomessage_sdk/sdk`（TypeScript 版），与现有 Web/PWA 客户端完全互通的端到端加密 IM SDK

---

## 快速开始（AI Vibecoding 接入）

### 1. 添加依赖

```kotlin
// settings.gradle.kts（monorepo 本地依赖）
includeBuild("../sdk-android")

// app/build.gradle.kts
dependencies {
    implementation("space.securechat:sdk")
}
```

### 2. Application 初始化

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 🔒 SDK 自动：建 Room DB、初始化 HttpClient、BouncyCastle
        SecureChatClient.init(this)
    }
}
```

### 3. 完整注册流程（Activity/Fragment）

```kotlin
val client = SecureChatClient.getInstance()

// Step 1: 生成助记词（展示给用户, 要求备份）
val mnemonic = KeyDerivation.newMnemonic()
showMnemonicToUser(mnemonic)

// Step 2: 用户确认备份后，注册
lifecycleScope.launch {
    val aliasId = client.auth.registerAccount(mnemonic, nickname = "Alice")
    // SDK 自动完成：PoW → 密钥派生 → 注册 → JWT

    // Step 3: 连接 WebSocket
    client.connect()

    // Step 4: 同步好友（建立 ECDH 会话）
    val friends = client.contacts.syncFriends()

    // Step 5: 进入主界面
    navigateToMain(aliasId, friends)
}
```

### 4. 恢复会话（App 每次启动）

```kotlin
lifecycleScope.launch {
    val session = client.restoreSession()
    if (session == null) {
        navigateToWelcome()    // 首次使用 → 注册流程
    } else {
        val (aliasId, nickname) = session
        client.connect()
        client.contacts.syncFriends()
        navigateToMain(aliasId, nickname)
    }
}
```

### 5. 消息收发

```kotlin
// 接收（在 onStart 中注册，onStop 中注销）
val unsub = client.on(SecureChatClient.EVENT_MESSAGE) { msg: StoredMessage ->
    // 主线程回调，直接更新 RecyclerView
    adapter.addMessage(msg)
}
// onStop: unsub()

// 发送
lifecycleScope.launch {
    val msgId = client.sendMessage(conversationId, toAliasId, "Hello E2EE!")
}

// 发送 typing
client.sendTyping(conversationId, toAliasId)

// 标记已读
client.markAsRead(conversationId, maxSeq, toAliasId)
```

### 6. 退出登录

```kotlin
lifecycleScope.launch {
    client.logout()  // disconnect + 清 Room DB + 清 JWT
    navigateToWelcome()
}
```

---

## 模块 API 参考

### `client.auth`（AuthManager）

| 方法 | 说明 |
|------|------|
| `registerAccount(mnemonic, nickname)` | 注册新账号（PoW + 密钥派生 + JWT）|
| `restoreSession()` | 恢复老账号（返回 (aliasId, nickname) 或 null）|

### `client.contacts`（ContactsManager）

| 方法 | 说明 |
|------|------|
| `lookupUser(aliasId)` | 搜索用户（添加好友前的查询）|
| `sendFriendRequest(aliasId)` | 发送好友申请 |
| `acceptFriendRequest(friendshipId)` | 接受好友申请 |
| `syncFriends()` | 同步全部好友 + 自动建立 ECDH 会话 |

### `client.channels`（ChannelsManager）

| 方法 | 说明 |
|------|------|
| `getMine()` | 获取已订阅频道 |
| `search(query)` | 搜索频道 |
| `create(name, description)` | 创建频道 |
| `post(channelId, content)` | 发帖 |
| `subscribe(channelId)` | 订阅 |
| `unsubscribe(channelId)` | 取消订阅 |

### `client.vanity`（VanityManager）

| 方法 | 说明 |
|------|------|
| `search(query)` | 搜索可用靓号 |
| `purchase(aliasId)` | 购买靓号（返回支付链接）|
| `bind(orderId)` | 绑定靓号（支付后调用）|

### `client.push`（PushManager）

```kotlin
// 在 FirebaseMessagingService.onNewToken() 中调用
lifecycleScope.launch {
    client.push.register(fcmToken)
}
```

---

## 协议约束（🛡️ AI 不得修改）

| 约束项 | 值 |
|-------|-----|
| API 服务端地址 | `https://relay.daomessage.com` |
| Ed25519 派生路径 | `m/44'/0'/0'/0/0` (SLIP-0010 硬化) |
| X25519 派生路径 | `m/44'/1'/0'/0/0` (SLIP-0010 硬化) |
| HMAC key（派生根节点） | `"ed25519 seed"` |
| AES-GCM 信封格式 | `iv(12B) + ciphertext + tag(16B)` |
| HKDF salt | `SHA-256(conv_id)` |
| HKDF info | `"securechat-session-v1"` |

## 📡 WebSocket 通信规约 (Wire Protocol)

为了证明零信任架构的透明性，以下是所有在客户端与中继节点之间流转的明文控制帧协议。所有载荷（Payload）均为无法破解的盲数据。

### 上行控制帧 (Client -> Server)
```json
// 1. 同步离线消息请求
{ "type": "sync", "crypto_v": 1 }

// 2. 状态回执同步 (送达 / 已读)
{ "type": "delivered", "conv_id": "...", "seq": 102, "to": "alice_alias", "crypto_v": 1 }
{ "type": "read", "conv_id": "...", "seq": 102, "to": "alice_alias", "crypto_v": 1 }

// 3. 正在输入广播
{ "type": "typing", "conv_id": "...", "to": "alice_alias", "crypto_v": 1 }

// 4. 消息撤回
{ "type": "retract", "id": "msg_uu1d", "conv_id": "...", "to": "alice_alias", "crypto_v": 1 }

// 5. 加密消息上行 (中继节点只能看到 id, to, 和被加密包裹的 payload)
// 此结构由 sdk 内部 encryptMessage 组装生成
{ "id": "local-x", "to": "alice", "conv_id": "...", "payload": "U2FsdGVk...", "nonce": "...", "crypto_v": 1 }
```

### 下行控制帧 (Server -> Client)
```json
// 1. 收到加密消息投递
{ "type": "msg", "id": "msg_uuid", "from": "bob", "conv_id": "...", "seq": 103, "at": 171000000, "payload": "U2F...", "nonce": "..." }

// 2. 服务端入库确认 (Ack)
{ "type": "ack", "id": "local-x", "seq": 103 }

// 3. 对方状态回执
{ "type": "delivered", "conv_id": "...", "seq": 101, "to": "bob" }
{ "type": "read", "conv_id": "...", "seq": 101, "to": "bob" }

// 4. 对方正在输入
{ "type": "typing", "from": "bob", "conv_id": "..." }

// 5. 对方撤回消息
{ "type": "retract", "id": "msg_uuid", "from": "bob", "conv_id": "..." }

// 6. 其他业务事件
{ "type": "channel_post", "id": "post_uuid", "author_alias_id": "...", "content": "..." }
{ "type": "payment_confirmed", "order_id": "xxx", "ref_id": "xxx" }
```

---

## 技术栈

- Kotlin + 协程（`suspend fun` 函数，无回调地狱）
- Room 数据库（消息/会话/身份持久化）
- OkHttp WebSocket（重连心跳，指数退避）  
- Bouncy Castle（Ed25519 / X25519 / AES-256-GCM / HKDF）
- Retrofit2（REST API）
- FCM（推送，对标 Web Push）
