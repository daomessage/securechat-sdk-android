package space.securechat.sdk.contacts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import space.securechat.sdk.db.*
import space.securechat.sdk.http.*
import space.securechat.sdk.keys.KeyDerivation
import java.util.Base64

/**
 * 🔒 ContactsManager — 0.3.0 响应式好友系统
 *
 * 响应式首版:
 *   - observeFriends(): StateFlow<List<Friend>>
 *   - observeAccepted(), observePending(), observePendingCount()
 *   - accept / reject 内置乐观更新 + rollback
 *   - refresh Mutex 保证并发只发一次请求
 *
 * 对标 sdk-typescript/src/contacts/module.ts
 */
class ContactsManager(
    private val http: HttpClient,
    private val db: SecureChatDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    private val refreshMutex = Mutex()
    @Volatile private var primed = false

    // ─── 观察式 API ──────────────────────────────────────

    /** 所有好友(含 pending/accepted/rejected), 订阅即触发后台 refresh */
    fun observeFriends(): StateFlow<List<Friend>> {
        if (!primed) {
            primed = true
            scope.launch {
                try { refresh() } catch (_: Throwable) {}
            }
        }
        return _friends.asStateFlow()
    }

    /** 只看已接受的好友 */
    fun observeAccepted(): StateFlow<List<Friend>> =
        _friends.mapState { it.filter { f -> f.friendStatus == "accepted" } }

    /** 只看 pending + received(待我处理的申请) */
    fun observePending(): StateFlow<List<Friend>> =
        _friends.mapState { it.filter { f -> f.friendStatus == "pending" && f.friendDirection == "received" } }

    /** 待处理 badge 计数 */
    fun observePendingCount(): StateFlow<Int> =
        _friends.mapState { list -> list.count { f -> f.friendStatus == "pending" && f.friendDirection == "received" } }

    /** 快照读取(非订阅) */
    val friends: List<Friend> get() = _friends.value

    // ─── 命令式 ──────────────────────────────────────────

    suspend fun lookupUser(aliasId: String): UserProfile {
        val r = http.api.lookupUser(aliasId)
        return UserProfile(r.alias_id, r.nickname, r.x25519_public_key)
    }

    /** 发送好友申请,成功后 refresh */
    suspend fun sendRequest(toAliasId: String) {
        http.api.sendFriendRequest(FriendRequestBody(to_alias_id = toAliasId))
        refresh()
    }

    /** 接受好友申请(乐观更新 + rollback),返回 conversationId */
    suspend fun accept(friendshipId: Long): String {
        val before = _friends.value
        // 乐观 - 把对应项 status 改为 accepted
        _friends.value = before.map {
            if (it.friendshipId == friendshipId) it.copy(friendStatus = "accepted") else it
        }
        return try {
            val resp = http.api.acceptFriendRequest(friendshipId)
            refresh()
            resp.conversation_id
        } catch (e: Throwable) {
            _friends.value = before
            throw e
        }
    }

    /** 拒绝好友申请(乐观更新 + rollback) */
    suspend fun reject(friendshipId: Long) {
        val before = _friends.value
        _friends.value = before.map {
            if (it.friendshipId == friendshipId) it.copy(friendStatus = "rejected") else it
        }
        try {
            http.api.rejectFriendRequest(friendshipId)
            refresh()
        } catch (e: Throwable) {
            _friends.value = before
            throw e
        }
    }

    /** 手动触发 refresh(WS 收到好友事件时 SDK 内部调用) */
    suspend fun refresh() {
        refreshMutex.withLock {
            val myIdentity = SecureIdentity.load(db) ?: return
            val myEcdhPrivKey = Base64.getDecoder().decode(myIdentity.ecdhPrivateKey)

            val friendList = http.api.getFriends()
            val result = mutableListOf<Friend>()

            for (f in friendList) {
                val convId = f.conversation_id.orEmpty()
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
                    } else if (existing.theirEd25519PublicKey == null && f.ed25519_public_key.isNotEmpty()) {
                        // 补填之前未存入的 Ed25519 公钥（老会话迁移）
                        db.sessionDao().save(existing.copy(theirEd25519PublicKey = f.ed25519_public_key))
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
            _friends.value = result
        }
    }

    // ─── 0.2.x 命令式兼容层(保持老 API 调用能编译) ──

    /** @deprecated 0.3.0 请用 observeFriends() · 本方法底层 refresh() + 返回快照 */
    suspend fun syncFriends(): List<Friend> {
        refresh()
        return _friends.value
    }

    /** @deprecated 0.3.0 请用 accept() */
    suspend fun acceptFriendRequest(friendshipId: Long): String = accept(friendshipId)

    /** @deprecated 0.3.0 请用 reject() */
    suspend fun rejectFriendRequest(friendshipId: Long) = reject(friendshipId)

    /** @deprecated 0.3.0 请用 sendRequest() */
    suspend fun sendFriendRequest(toAliasId: String) = sendRequest(toAliasId)

    /** @deprecated 0.3.0 SDK 内置 Mutex + StateFlow, 无需手动 invalidate */
    fun invalidateFriendsCache() { /* no-op */ }
}

// ─── 辅助: StateFlow.map (生成新的 StateFlow) ────────────

private fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> {
    val initial = transform(value)
    val out = MutableStateFlow(initial)
    kotlinx.coroutines.GlobalScope.launch {
        collect { out.value = transform(it) }
    }
    return out.asStateFlow()
}

// ─── 数据类型 ────────────────────────────────────────────

data class UserProfile(val aliasId: String, val nickname: String, val ecdhPublicKey: String)

data class Friend(
    val friendshipId: Long,
    val aliasId: String,
    val nickname: String,
    val conversationId: String,
    val ecdhPublicKey: String,
    val friendStatus: String = "accepted",
    val friendDirection: String = "sent"
)
