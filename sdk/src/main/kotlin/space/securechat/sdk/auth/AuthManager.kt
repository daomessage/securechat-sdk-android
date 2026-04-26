package space.securechat.sdk.auth

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.securechat.sdk.crypto.CryptoModule
import space.securechat.sdk.db.*
import space.securechat.sdk.http.*
import space.securechat.sdk.keys.KeyDerivation
import java.util.Base64

/**
 * 🔒 AuthManager — 注册、JWT、restoreSession
 *
 * 👤 App 调用：registerAccount() 或 restoreSession()，其余 SDK 自动完成
 *
 * 对标 sdk-typescript/src/auth/manager.ts
 */
class AuthManager(
    private val context: Context,
    private val http: HttpClient,
    private val db: SecureChatDatabase
) {

    /** 当前登录账号的 UUID（WebSocket 连接需要）*/
    var internalUUID: String? = null
        private set

    /**
     * 串行化 restoreSession / reauthenticate / authenticate,防止并发自踢:
     * Service.ensureConnected 和 AppNavigation.LaunchedEffect 在同一进程几乎同时启动时,
     * 两个协程同时读到 http.getToken() == null,各跑一次 authenticate,
     * 服务端 revokeOldSessions 把先到的 JTI 撤销,30s 后 revalidateLoop 推 GOAWAY → 误踢。
     */
    private val authMutex = Mutex()

    /** 获取本地存储的 AliasID，不发起网络请求 */
    suspend fun getLocalAliasId(): String? {
        return db.identityDao().get()?.aliasId
    }

    // ── 注册新账号 ────────────────────────────────────────────────────────

    /**
     * 🔒 SDK 全自动完成（App 只需传助记词和昵称）：
     *   1. BIP-39 → Ed25519 + X25519 密钥派生
     *   2. 从服务端获取 PoW 挑战并计算 nonce
     *   3. POST /api/v1/register 上传公钥
     *   4. Challenge-Response 签名获取 JWT
     *   5. 写入 Room DB 持久化身份
     *
     * @return aliasId 服务端分配的 alias ID
     * 对标 TS SDK: client.auth.registerAccount(mnemonic, nickname)
     */
    suspend fun registerAccount(mnemonic: String, nickname: String): String {
        val identity = KeyDerivation.deriveIdentity(mnemonic)
        val sigPub = Base64.getEncoder().encodeToString(identity.signingKey.publicKey)
        val ecdhPub = Base64.getEncoder().encodeToString(identity.ecdhKey.publicKey)

        // PoW（对标 TS SDK: sha256(challenge + i.toString()) 十六进制前缀匹配）
        var powNonce = ""
        try {
            val pow = http.api.getPowChallenge()
            powNonce = CryptoModule.computePow(pow.challenge_string, pow.difficulty)
        } catch (_: Exception) {
            // PoW 失败不阻塞注册（与 TS SDK 行为一致）
        }

        // 注册
        val regResp = http.api.register(
            RegisterRequest(
                ed25519_public_key = sigPub,
                x25519_public_key = ecdhPub,
                nickname = nickname,
                pow_nonce = powNonce
            )
        )

        // 获取 JWT（用 uuid，非 alias_id，对标 TS SDK）
        val token = authenticate(regResp.uuid, identity.signingKey.privateKey)
        http.setToken(token)
        internalUUID = regResp.uuid

        // 持久化（敏感字段由 Keystore 加密，见 SecureIdentity）
        space.securechat.sdk.db.SecureIdentity.save(
            db = db,
            uuid = regResp.uuid,
            aliasId = regResp.alias_id,
            nickname = nickname,
            mnemonic = mnemonic,
            signingPublicKey = sigPub,
            ecdhPublicKey = ecdhPub,
            signingPrivateKey = Base64.getEncoder().encodeToString(identity.signingKey.privateKey),
            ecdhPrivateKey = Base64.getEncoder().encodeToString(identity.ecdhKey.privateKey),
        )

        return regResp.alias_id
    }

    // ── 恢复历史会话 ──────────────────────────────────────────────────────

    /**
     * 🔒 SDK 自动完成：读 Room DB → Ed25519 签名挑战 → 获取 JWT
     * @return (aliasId, nickname) 或 null（无本地身份）
     * 对标 TS SDK: client.restoreSession()
     *
     * 🔁 幂等保证(关键!):
     * 服务端 revokeOldSessions 每次新认证都会撤销旧 JTI,
     * 如果短时间内多处调用 restoreSession(Activity / FcmService / MessagingForegroundService),
     * 第一个 JTI 会被第二个撤销,30 秒后 revalidateLoop 命中导致 jwt_revoked GOAWAY。
     * 因此:已经有 token 时直接复用,只在第一次或主动 logout 后才重新认证。
     */
    suspend fun restoreSession(): Pair<String, String>? = authMutex.withLock {
        val stored = space.securechat.sdk.db.SecureIdentity.load(db) ?: return@withLock null

        // 已有有效 token → 复用,不重新 authenticate(避免撤销自己)
        // 在 mutex 内重新检查,防止两个协程都先读到 null 然后都进 authenticate 的并发条件。
        if (http.getToken() != null) {
            internalUUID = stored.uuid
            return@withLock stored.aliasId to stored.nickname
        }

        val privateKey = Base64.getDecoder().decode(stored.signingPrivateKey)
        val token = try {
            authenticate(stored.uuid, privateKey)
        } catch (e: Exception) {
            return@withLock null  // 认证失败（助记词彻底失效等），回 welcome 页
        }

        http.setToken(token)
        internalUUID = stored.uuid
        stored.aliasId to stored.nickname
    }

    /**
     * 强制重新认证(用于 jwt_revoked 自愈场景)
     * 不复用现有 token,始终走完整 authenticate 流程拿新 JTI。
     */
    suspend fun reauthenticate(): Pair<String, String>? = authMutex.withLock {
        val stored = space.securechat.sdk.db.SecureIdentity.load(db) ?: return@withLock null
        val privateKey = Base64.getDecoder().decode(stored.signingPrivateKey)
        val token = try {
            authenticate(stored.uuid, privateKey)
        } catch (e: Exception) {
            return@withLock null
        }
        http.setToken(token)
        internalUUID = stored.uuid
        stored.aliasId to stored.nickname
    }

    /**
     * 使用已有助记词登录（异地登录 / 换设备恢复）
     *
     * 对标 TS/iOS SDK: client.auth.loginWithMnemonic(mnemonic)
     * 逻辑：注册时如果服务端返回 409 Conflict，则从 Error Body 中拆出 uuid 和 alias_id 并重新验证。
     */
    @Deprecated(
        "Use loginWithMnemonic instead — unified naming across TS/Android/iOS SDKs.",
        ReplaceWith("loginWithMnemonic(mnemonic)")
    )
    suspend fun loginExt(mnemonic: String): String = loginWithMnemonic(mnemonic)

    suspend fun loginWithMnemonic(mnemonic: String): String {
        val identity = KeyDerivation.deriveIdentity(mnemonic)
        val sigPub = java.util.Base64.getEncoder().encodeToString(identity.signingKey.publicKey)
        val ecdhPub = java.util.Base64.getEncoder().encodeToString(identity.ecdhKey.publicKey)

        // PoW 防刷(P3 加固后服务端强制要求)
        var powNonce = ""
        try {
            val pow = http.api.getPowChallenge()
            powNonce = CryptoModule.computePow(pow.challenge_string, pow.difficulty)
        } catch (_: Exception) {
            // PoW 失败不阻塞;服务端会返回 400 让用户重试
        }

        var userUUID = ""
        var aliasId = ""

        try {
            val regResp = http.api.register(
                RegisterRequest(
                    ed25519_public_key = sigPub,
                    x25519_public_key = ecdhPub,
                    nickname = "Recovered User",
                    pow_nonce = powNonce
                )
            )
            userUUID = regResp.uuid
            aliasId = regResp.alias_id
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 409) {
                // 读取 409 返回体的 uuid 和 alias_id
                val errorBody = e.response()?.errorBody()?.string() ?: ""
                try {
                    val root = org.json.JSONObject(errorBody)
                    userUUID = root.optString("uuid", "")
                    aliasId = root.optString("alias_id", "")
                } catch (je: Exception) {
                    throw RuntimeException("从 409 解析异地登录身份失败", je)
                }
            } else {
                throw e
            }
        }

        if (userUUID.isEmpty()) {
            throw RuntimeException("恢复失败：无法获取身份标识")
        }

        // 获取 JWT Token
        val token = authenticate(userUUID, identity.signingKey.privateKey)
        http.setToken(token)
        internalUUID = userUUID

        // 持久化到 Room DB 成为本地默认身份（Keystore 加密）
        space.securechat.sdk.db.SecureIdentity.save(
            db = db,
            uuid = userUUID,
            aliasId = aliasId,
            nickname = "Recovered User",
            mnemonic = mnemonic,
            signingPublicKey = sigPub,
            ecdhPublicKey = ecdhPub,
            signingPrivateKey = java.util.Base64.getEncoder().encodeToString(identity.signingKey.privateKey),
            ecdhPrivateKey = java.util.Base64.getEncoder().encodeToString(identity.ecdhKey.privateKey),
        )

        return aliasId
    }

    // ── 内部：Challenge-Response 认证 ────────────────────────────────────

    /**
     * 对标 TS SDK: performAuthChallenge(userUUID, signingPrivateKey)
     * 用 user_uuid（非 alias_id）进行 challenge-response 认证
     */
    private suspend fun authenticate(userUUID: String, signingPrivateKey: ByteArray): String {
        // GET challenge
        val challengeResp = http.api.getAuthChallenge(AuthChallengeRequest(user_uuid = userUUID))
        val challengeBytes = challengeResp.challenge.toByteArray(Charsets.UTF_8)

        // Ed25519 签名（TS SDK 也是 TextEncoder.encode(challenge)，即 UTF-8 字节）
        val signature = KeyDerivation.signChallenge(challengeBytes, signingPrivateKey)
        val sigBase64 = Base64.getEncoder().encodeToString(signature)

        // POST verify → JWT
        val verifyResp = http.api.verifyAuth(
            AuthVerifyRequest(
                user_uuid = userUUID,
                challenge = challengeResp.challenge,
                signature = sigBase64
            )
        )
        return verifyResp.token
    }
}
