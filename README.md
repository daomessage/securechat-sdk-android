# SecureChat Android SDK

[English](./README.md) | [简体中文](./README_zh-CN.md)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin-purple.svg)

> Aligned with `@daomessage_sdk/sdk` (TypeScript), providing native End-to-End Encrypted (E2EE) messaging fully interoperable with existing Web/PWA clients.

---

## Quick Start (AI Vibecoding Integration)

### 1. Add Dependencies

```kotlin
// settings.gradle.kts (monorepo local dependency)
includeBuild("../sdk-android")

// app/build.gradle.kts
dependencies {
    implementation("space.securechat:sdk")
}
```

### 2. Application Initialization

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 🔒 SDK automatically builds Room DB instances and internal BouncyCastle chains
        SecureChatClient.init(this)
    }
}
```

### 3. Full Registration Flow (Activity/Fragment)

```kotlin
val client = SecureChatClient.getInstance()

// Step 1: Generate Mnemonic (Display to user & require backup completion)
val mnemonic = KeyDerivation.newMnemonic()
showMnemonicToUser(mnemonic)

// Step 2: Register account
lifecycleScope.launch {
    val aliasId = client.auth.registerAccount(mnemonic, nickname = "Alice")
    // SDK Magic: PoW computation → Key Derivation → Registration → JWT Provision

    // Step 3: Connect WebSocket Transport
    client.connect()

    // Step 4: Sync friends infrastructure (Initiates implicit ECDH flows)
    val friends = client.contacts.syncFriends()

    // Step 5: Navigate to Home Dashboard
    navigateToMain(aliasId, friends)
}
```

### 4. Session Restoration (App Launch Recovery)

```kotlin
lifecycleScope.launch {
    val session = client.restoreSession()
    if (session == null) {
        navigateToWelcome()    // First launch or wiped local credentials -> Registration
    } else {
        val (aliasId, nickname) = session
        client.connect()
        client.contacts.syncFriends()
        navigateToMain(aliasId, nickname)
    }
}
```

### 5. Send & Receive

```kotlin
// Receiving Messages (Register in onStart, Deregister in onStop)
val unsub = client.on(SecureChatClient.EVENT_MESSAGE) { msg: StoredMessage ->
    // Automatically executes on the Main Thread for immediate RecyclerView updates
    adapter.addMessage(msg)
}
// onStop: unsub()

// Transmitting encoded message payload
lifecycleScope.launch {
    val msgId = client.sendMessage(conversationId, toAliasId, "Hello E2EE!")
}

// Emitting typing broadcast indication
client.sendTyping(conversationId, toAliasId)

// Triggering read execution sequences 
client.markAsRead(conversationId, maxSeq, toAliasId)
```

### 6. Graceful Logout

```kotlin
lifecycleScope.launch {
    client.logout()  // Drops socket + Drops Room DB schemas + Flushes JWT payload state
    navigateToWelcome()
}
```

---

## API Reference by Manager

### `client.auth` (AuthManager)

| Function | Operation |
|------|------|
| `registerAccount(mnemonic, nickname)` | Sign-in (PoW + Identity derivation + JWT minting) |
| `restoreSession()` | Wake previous identity configs (returns (aliasId, nickname) or null) |

### `client.contacts` (ContactsManager)

| Function | Operation |
|------|------|
| `lookupUser(aliasId)` | Identity search mechanism |
| `sendFriendRequest(aliasId)` | Initiate connection stream block |
| `acceptFriendRequest(friendshipId)` | Fulfill connection stream block |
| `syncFriends()` | Polling fallback for fetching networks & constructing ECDH keys |

### `client.channels` (ChannelsManager)

| Function | Operation |
|------|------|
| `getMine()` | Fetch subscribed boards |
| `search(query)` | Search directory |
| `create(name, description)` | Init public channel identity signature |
| `post(channelId, content)` | Publish payload |
| `subscribe(channelId)` | Subscribe identity signature mechanism |
| `unsubscribe(channelId)` | Revoke identity mapping bind |

### `client.vanity` (VanityManager)

| Function | Operation |
|------|------|
| `search(query)` | Enumerate unassigned special alias blocks |
| `purchase(aliasId)` | Allocate order node routing |
| `bind(orderId)` | Assign successfully negotiated node block payload |

### `client.push` (PushManager)

```kotlin
// Called dynamically inside FirebaseMessagingService.onNewToken() implementations
lifecycleScope.launch {
    client.push.register(fcmToken)
}
```

---

## Protocol Engine Directives (🛡️ DO NOT MODIFY)

| Parameter Rule | Value |
|-------|-----|
| Root API Address Block | `https://relay.daomessage.com` |
| Ed25519 Key Derivation Template | `m/44'/0'/0'/0/0` (SLIP-0010 Hardened spec compliance) |
| X25519 Key Derivation Template  | `m/44'/1'/0'/0/0` (SLIP-0010 Hardened spec compliance) |
| HMAC Root Instantiation Target | `"ed25519 seed"` |
| AES-GCM Structure Definition | `iv(12B) + ciphertext + tag(16B)` |
| HKDF Salt Vector | `SHA-256(conv_id)` |
| HKDF Info Vector | `"securechat-session-v1"` |

---

## Technical Stack Architecture

- Kotlin + Coroutine Suspend Dispatch (`suspend fun` structures overriding callback hell architectures)
- Room Database (Isolated SQLite wrappers for E2EE context state maintenance)
- OkHttp WebSockets (Heartbeat + Exponential Backoff algorithms incorporated)  
- Bouncy Castle API Core (Ed25519 / X25519 / AES-256-GCM / HKDF cryptographic dependencies)
- Retrofit2 (REST API generation layer framework wrapper)
- Google FCM (Server broadcast pushes equivalent to generic Web Push)
