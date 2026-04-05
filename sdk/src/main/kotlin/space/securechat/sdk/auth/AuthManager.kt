package space.securechat.sdk.auth

import android.content.Context
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

        // 持久化
        db.identityDao().save(
            IdentityEntity(
                uuid = regResp.uuid,
                aliasId = regResp.alias_id,
                nickname = nickname,
                mnemonic = mnemonic,
                signingPublicKey = sigPub,
                ecdhPublicKey = ecdhPub,
                signingPrivateKey = Base64.getEncoder().encodeToString(identity.signingKey.privateKey),
                ecdhPrivateKey = Base64.getEncoder().encodeToString(identity.ecdhKey.privateKey)
            )
        )

        return regResp.alias_id
    }

    // ── 恢复历史会话 ──────────────────────────────────────────────────────

    /**
     * 🔒 SDK 自动完成：读 Room DB → Ed25519 签名挑战 → 获取 JWT
     * @return (aliasId, nickname) 或 null（无本地身份）
     * 对标 TS SDK: client.restoreSession()
     */
    suspend fun restoreSession(): Pair<String, String>? {
        val stored = db.identityDao().get() ?: return null

        val privateKey = Base64.getDecoder().decode(stored.signingPrivateKey)
        val token = try {
            authenticate(stored.uuid, privateKey)
        } catch (e: Exception) {
            return null  // 认证失败（助记词彻底失效等），回 welcome 页
        }

        http.setToken(token)
        internalUUID = stored.uuid
        return stored.aliasId to stored.nickname
    }

    /**
     * 针对已有账号：利用旧助记词恢复（异地登录）
     * 逻辑对标 TS SDK：注册时如果服务端返回 409 Conflict，则从 Error Body 中拆出 uuid 和 alias_id 并重新验证。
     */
    suspend fun loginExt(mnemonic: String): String {
        val identity = KeyDerivation.deriveIdentity(mnemonic)
        val sigPub = java.util.Base64.getEncoder().encodeToString(identity.signingKey.publicKey)
        val ecdhPub = java.util.Base64.getEncoder().encodeToString(identity.ecdhKey.publicKey)

        var userUUID = ""
        var aliasId = ""

        try {
            val regResp = http.api.register(
                RegisterRequest(
                    ed25519_public_key = sigPub,
                    x25519_public_key = ecdhPub,
                    nickname = "Recovered User"
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

        // 持久化到 Room DB 成为本地默认身份
        db.identityDao().save(
            IdentityEntity(
                uuid = userUUID,
                aliasId = aliasId,
                nickname = "Recovered User",
                mnemonic = mnemonic,
                signingPublicKey = sigPub,
                ecdhPublicKey = ecdhPub,
                signingPrivateKey = java.util.Base64.getEncoder().encodeToString(identity.signingKey.privateKey),
                ecdhPrivateKey = java.util.Base64.getEncoder().encodeToString(identity.ecdhKey.privateKey)
            )
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
