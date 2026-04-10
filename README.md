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
| `getMine()` | Fetch subscribed channels |
| `search(query)` | Search public channels |
| `create(name, description)` | Create a new public channel |
| `getDetail(channelId)` | Get channel details (includes `forSale`, `salePrice`) |
| `getPosts(channelId)` | Get channel post history |
| `post(channelId, content)` | Publish content (owner only) |
| `subscribe(channelId)` | Subscribe to channel |
| `unsubscribe(channelId)` | Unsubscribe from channel |
| `canPost(info)` | Local check: `info.role == "owner"` |
| `listForSale(channelId, priceUsdt)` | Owner: list channel for sale at given USDT price |
| `buyChannel(channelId)` | Buyer: create purchase order → `ChannelTradeOrder` |

### `client.vanity` (VanityManager)

| Function | Operation |
|------|------|
| `search(query)` | Search available vanity IDs (rule engine, no JWT required) |
| `reserve(aliasId)` | Pre-registration reserve → `ReserveResult` (no JWT) |
| `purchase(aliasId)` | Post-registration purchase → `PurchaseResult` (JWT required) |
| `orderStatus(orderId)` | Poll order status → `"PENDING"` / `"confirmed"` / `"expired"` |
| `bind(orderId)` | Bind vanity ID to account after payment confirmed |

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

## 📡 WebSocket Wire Protocol

To demonstrate the transparency of the zero-trust architecture, the following specifies all plaintext control frames transmitted between the client and relay nodes. All message payloads remain securely blind-encrypted and impenetrable to interception.

### Upbound Control Frames (Client -> Server)
```json
// 1. Sync Request (Fetch offline/missed messages)
{ "type": "sync", "crypto_v": 1 }

// 2. Receipt Propagation (Delivered / Read)
{ "type": "delivered", "conv_id": "...", "seq": 102, "to": "alice_alias", "crypto_v": 1 }
{ "type": "read", "conv_id": "...", "seq": 102, "to": "alice_alias", "crypto_v": 1 }

// 3. Typing Indicator
{ "type": "typing", "conv_id": "...", "to": "alice_alias", "crypto_v": 1 }

// 4. Retract Message
{ "type": "retract", "id": "msg_uu1d", "conv_id": "...", "to": "alice_alias", "crypto_v": 1 }

// 5. Encrypted Upbound Envelope (Relay only observes routing metadata and encrypted payload bytes)
// Generated dynamically by SDK's internal encryptMessage pipeline
{ "id": "local-x", "to": "alice", "conv_id": "...", "payload": "U2FsdGVk...", "nonce": "...", "crypto_v": 1 }
```

### Downbound Control Frames (Server -> Client)
```json
// 1. Incoming Encrypted Message Delivery
{ "type": "msg", "id": "msg_uuid", "from": "bob", "conv_id": "...", "seq": 103, "at": 171000000, "payload": "U2F...", "nonce": "..." }

// 2. Server Processing ACK
{ "type": "ack", "id": "local-x", "seq": 103 }

// 3. Peer Receipt Sync
{ "type": "delivered", "conv_id": "...", "seq": 101, "to": "bob" }
{ "type": "read", "conv_id": "...", "seq": 101, "to": "bob" }

// 4. Peer Typing Status
{ "type": "typing", "from": "bob", "conv_id": "..." }

// 5. Peer Message Retraction
{ "type": "retract", "id": "msg_uuid", "from": "bob", "conv_id": "..." }

// 6. External Business Events
{ "type": "channel_post", "id": "post_uuid", "author_alias_id": "...", "content": "..." }
{ "type": "payment_confirmed", "order_id": "xxx", "ref_id": "xxx" }
```

---

## Technical Stack Architecture

- Kotlin + Coroutine Suspend Dispatch (`suspend fun` structures overriding callback hell architectures)
- Room Database (Isolated SQLite wrappers for E2EE context state maintenance)
- OkHttp WebSockets (Heartbeat + Exponential Backoff algorithms incorporated)  
- Bouncy Castle API Core (Ed25519 / X25519 / AES-256-GCM / HKDF cryptographic dependencies)
- Retrofit2 (REST API generation layer framework wrapper)
- Google FCM (Server broadcast pushes equivalent to generic Web Push)

## 🛡️ Security & Resilience

### End-to-End Encryption

| Layer | Algorithm | Purpose |
|-------|-----------|---------|
| Identity | Ed25519 | Challenge-Response authentication, message signing |
| Key Exchange | X25519 ECDH | Per-conversation session key derivation |
| Message Encryption | AES-256-GCM | All message payloads blind-encrypted client-side |
| Key Derivation | HKDF-SHA256 | Session key from shared secret + conversation ID |
| Media Encryption | AES-256-GCM | Files encrypted locally before upload to relay |

### Anti-Sybil: Proof of Work (PoW)

Registration requires solving a CPU-bound SHA-256 puzzle. Typically takes 1-3 seconds on modern devices.

### Challenge-Response Authentication

No passwords. Login uses Ed25519 digital signatures:
1. Client requests random challenge from server
2. Client signs challenge with Ed25519 private key
3. Server verifies signature and issues JWT

### WebSocket Resilience

| Mechanism | Implementation |
|-----------|---------------|
| **Heartbeat** | `ping` frame every 25s |
| **Auto-Reconnect** | Exponential backoff: `min(1s × 2^n, 30s)` with jitter |
| **GOAWAY Handling** | Server-initiated disconnect on new device login |
| **Lifecycle-Aware** | Auto-reconnects on `ConnectivityManager` network restore |

### Server-Side Protections

| Protection | Detail | SDK Impact |
|------------|--------|------------|
| **Registration Rate Limit** | 10/IP/hour | `registerAccount()` may throw 429 |
| **Message Rate Limit** | 120/user/min | `sendMessage()` may throw 429 |
| **Message Dedup** | `SETNX dedup:{msg_uuid}` (300s TTL) | Prevents duplicate messages |
| **JWT Revocation** | Redis blacklist on new device login | Stale tokens rejected with 401 |
| **Message TTL** | 24h relay purge | SDK persists locally in Room DB |
| **Media TTL** | 24h S3 purge | Client-side backup responsibility |

### Cryptographic Key Hierarchy

```
BIP-39 Mnemonic (12 words)
  └─ SLIP-0010 Hardened Derivation
       ├─ m/44'/0'/0'/0/0 → Ed25519 Signing Key (identity)
       └─ m/44'/1'/0'/0/0 → X25519 ECDH Key (encryption)
                              └─ HKDF(shared_secret, conv_id)
                                   └─ Per-conversation AES-256-GCM session key
```
