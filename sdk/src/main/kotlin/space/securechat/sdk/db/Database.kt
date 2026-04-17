package space.securechat.sdk.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── Entities ─────────────────────────────────────────────────────────────

/**
 * 本地身份存储
 * 对标 TS SDK IndexedDB identity store（StoredIdentity）
 */
@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val id: Int = 1,   // 单条记录，固定 id=1
    val uuid: String,
    val aliasId: String,
    val nickname: String,
    val mnemonic: String,
    val signingPublicKey: String,  // Base64
    val ecdhPublicKey: String,     // Base64
    val signingPrivateKey: String, // Base64（加密敏感字段）
    val ecdhPrivateKey: String,    // Base64（加密敏感字段）
)

/**
 * 会话记录（ECDH 建立后的密钥等）
 * 对标 TS SDK IndexedDB sessions store（SessionRecord）
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val conversationId: String,
    val theirAliasId: String,
    val theirEcdhPublicKey: String, // Base64 X25519
    val sessionKeyBase64: String,
    val trustState: String,                             // "unverified" | "verified"
    val createdAt: Long,
    val theirEd25519PublicKey: String? = null,          // 新增：对端 Ed25519 签名公钥（P0.4 加固）
)

/**
 * 消息存储
 * 对标 TS SDK IndexedDB messages store（StoredMessage）
 */
@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("time")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val text: String,
    val isMe: Boolean,
    val time: Long,
    val status: String,         // sending | sent | delivered | read | failed
    val msgType: String? = null,
    val mediaUrl: String? = null,
    val caption: String? = null,
    val seq: Long? = null,
    val fromAliasId: String? = null,
    val replyToId: String? = null
)

/**
 * 信任指纹存储（用于防劫持守护）
 * 对标 TS SDK securechat_security DB (Trust State)
 */
@Entity(tableName = "trust")
data class TrustEntity(
    @PrimaryKey val contactId: String,  // 对应 alias_id
    val status: String,                 // "unverified" | "verified"
    val verifiedAt: Long,
    val fingerprintSnapshot: String
)

// ─── DAOs ─────────────────────────────────────────────────────────────────

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE id = 1 LIMIT 1")
    suspend fun get(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(identity: IdentityEntity)

    @Query("DELETE FROM identity")
    suspend fun clear()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE conversationId = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE theirAliasId = :aliasId LIMIT 1")
    suspend fun getByAlias(aliasId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE conversationId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY time ASC")
    suspend fun getAll(convId: String): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :convId AND (:before IS NULL OR time < :before)
        ORDER BY time DESC
        LIMIT :limit
    """)
    suspend fun getPaged(convId: String, limit: Int, before: Long?): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface TrustDao {
    @Query("SELECT * FROM trust WHERE contactId = :contactId LIMIT 1")
    suspend fun get(contactId: String): TrustEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(trust: TrustEntity)

    @Query("DELETE FROM trust WHERE contactId = :contactId")
    suspend fun delete(contactId: String)

    @Query("DELETE FROM trust")
    suspend fun clearAll()
}

// ─── Database ─────────────────────────────────────────────────────────────

@Database(
    entities = [IdentityEntity::class, SessionEntity::class, MessageEntity::class, TrustEntity::class],
    version = 3,
    exportSchema = false
)
abstract class SecureChatDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun trustDao(): TrustDao

    companion object {
        const val DB_NAME = "securechat.db"
    }
}
