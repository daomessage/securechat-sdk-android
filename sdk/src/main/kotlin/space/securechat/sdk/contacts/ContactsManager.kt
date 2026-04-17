package space.securechat.sdk.contacts

import space.securechat.sdk.db.*
import space.securechat.sdk.http.*
import space.securechat.sdk.keys.KeyDerivation
import java.util.Base64

/**
 * 🔒 ContactsManager — 好友系统 + ECDH 会话建立
 *
 * 👤 App 必须在进入消息列表前调用 syncFriends()，SDK 自动完成 ECDH 密钥协商
 * 对标 sdk-typescript/src/contacts/manager.ts
 */
class ContactsManager(
    private val http: HttpClient,
    private val db: SecureChatDatabase
) {

    /**
     * 搜索用户（用于添加好友前的查询页）
     * 对标 TS SDK: client.contacts.lookupUser(aliasId)
     */
    suspend fun lookupUser(aliasId: String): UserProfile {
        val r = http.api.lookupUser(aliasId)
        return UserProfile(r.alias_id, r.nickname, r.x25519_public_key)
    }

    /**
     * 发送好友申请
     * 对标 TS SDK: client.contacts.sendFriendRequest(aliasId)
     */
    suspend fun sendFriendRequest(aliasId: String) {
        http.api.sendFriendRequest(FriendRequestBody(to_alias_id = aliasId))
    }

    /**
     * 接受好友申请并建立 ECDH 会话
     * 对标 TS SDK: client.contacts.acceptFriendRequest(friendshipId)
     *
     * 🔒 SDK 自动完成 ECDH 建立，App 只需传 friendshipId
     */
    suspend fun acceptFriendRequest(friendshipId: Long): String {
        val resp = http.api.acceptFriendRequest(friendshipId)
        // 对标 TS SDK：接受后自动 syncFriends 触发 ECDH 会话建立
        syncFriends()
        return resp.conversation_id
    }

    /**
     * 拒绝好友申请
     * 服务端将 friendship 状态置为 "rejected"；按产品设计 §11，不通知发起方。
     * 对标 TS/iOS SDK: client.contacts.rejectFriendRequest(friendshipId)
     */
    suspend fun rejectFriendRequest(friendshipId: Long) {
        http.api.rejectFriendRequest(friendshipId)
        syncFriends()
    }

    /**
     * 同步全部好友列表并建立 ECDH 会话（App 启动时必须调用一次）
     * 🔒 SDK 自动：对每个已接受的好友计算 ECDH 会话密钥并写入 Room
     *
     * @return 所有好友关系列表（含 pending/accepted）
     * 对标 TS SDK: client.contacts.syncFriends()
     */
    suspend fun syncFriends(): List<Friend> {
        // P1.9: 敏感字段由 Keystore 解密读取
        val myIdentity = space.securechat.sdk.db.SecureIdentity.load(db)
            ?: error("No identity found. Call registerAccount() or restoreSession() first.")
        val myEcdhPrivKey = Base64.getDecoder().decode(myIdentity.ecdhPrivateKey)

        val friendList = http.api.getFriends()
        val result = mutableListOf<Friend>()

        for (f in friendList) {
            val convId = f.conversation_id.orEmpty()
            // 仅对 accepted 对象进行 ECDH 会话建立
            if (f.status == "accepted" && convId.isNotEmpty() && f.x25519_public_key.isNotEmpty()) {
                val existing = db.sessionDao().get(convId)
                if (existing == null) {
                    val theirPubKey = Base64.getDecoder().decode(f.x25519_public_key)
                    val sharedSecret = KeyDerivation.computeSharedSecret(myEcdhPrivKey, theirPubKey)
                    val sessionKey = KeyDerivation.deriveSessionKey(sharedSecret, convId)

                    db.sessionDao().save(
                        SessionEntity(
                            conversationId = convId,
                            theirAliasId = f.alias_id,
                            theirEcdhPublicKey = f.x25519_public_key,
                            sessionKeyBase64 = Base64.getEncoder().encodeToString(sessionKey),
                            trustState = "unverified",
                            createdAt = System.currentTimeMillis(),
                            theirEd25519PublicKey = f.ed25519_public_key.ifEmpty { null },
                        )
                    )
                }
            }

            result.add(
                Friend(
                    friendshipId = f.friendship_id,
                    aliasId = f.alias_id,
                    nickname = f.nickname,
                    conversationId = convId,
                    ecdhPublicKey = f.x25519_public_key,
                    friendStatus = f.status,
                    friendDirection = f.direction
                )
            )
        }
        return result
    }
}

// ── 数据类型 ──────────────────────────────────────────────────────────────

data class UserProfile(val aliasId: String, val nickname: String, val ecdhPublicKey: String)

data class Friend(
    val friendshipId: Long,
    val aliasId: String,
    val nickname: String,
    val conversationId: String,
    val ecdhPublicKey: String,
    // status: "pending" | "accepted" | "rejected"
    val friendStatus: String = "accepted",
    // direction: "sent" | "received"
    val friendDirection: String = "sent"
)
