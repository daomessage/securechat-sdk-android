package space.securechat.sdk.security

import space.securechat.sdk.crypto.CryptoModule
import space.securechat.sdk.db.TrustDao
import space.securechat.sdk.db.TrustEntity
import space.securechat.sdk.keys.KeyDerivation
import java.util.Base64

/**
 * 安全码数据结构
 */
data class SecurityCode(
    val contactId: String,
    val displayCode: String, // 60s位十六进制字符串，每4字符加空格："AB12 F39C ..."
    val fingerprintHex: String // 原始验证值
)

/**
 * 信任状态实体
 */
sealed class TrustState(val status: String) {
    object Unverified : TrustState("unverified")
    data class Verified(val verifiedAt: Long, val fingerprintSnapshot: String) : TrustState("verified")
}

/**
 * 指纹突变抛出的错误（必须由UI层严肃处理）
 */
class SecurityViolationException(
    val contactId: String,
    val previousFingerprint: String,
    val currentFingerprint: String
) : Exception("Security violation detected: public key mutated for contact $contactId")

/**
 * 🔒 SecurityModule
 * 防御中间人攻击 (MITM) 核心网络门禁。
 * 对接 TS SDK: sdk-typescript/src/security/index.ts 与 computeSecurityCode
 */
class SecurityModule(private val trustDao: TrustDao) {

    /**
     * 获取指定联系人的安全指纹码
     * 根据双方 ECDH 的 Public Key 生成。算法：
     * SHA-256(min(pubA, pubB) || max(pubA, pubB)) 前30个字节（60个hex）
     */
    fun getSecurityCode(contactId: String, myEcdhPublicKey: ByteArray, theirEcdhPublicKey: ByteArray): SecurityCode {
        val fingerprintHex = computeSecurityCode(myEcdhPublicKey, theirEcdhPublicKey)
        // Insert space every 4 characters
        val displayCode = fingerprintHex.chunked(4).joinToString(" ").trim()
        return SecurityCode(contactId, displayCode, fingerprintHex)
    }

    /**
     * 验证用户输入的安全码（60位纯字母数字，忽略空格）
     * 如果通过，会自动标记 Verified
     */
    suspend fun verifyInputCode(
        contactId: String,
        inputCode: String,
        myEcdhPublicKey: ByteArray,
        theirEcdhPublicKey: ByteArray
    ): Boolean {
        val normalizedInput = inputCode.replace("\\s".toRegex(), "").lowercase()
        val localFingerprint = computeSecurityCode(myEcdhPublicKey, theirEcdhPublicKey).lowercase()
        if (normalizedInput != localFingerprint) {
            return false
        }

        markAsVerified(contactId, myEcdhPublicKey, theirEcdhPublicKey)
        return true
    }

    /**
     * 手动打上 Verified 标签，生成指纹快照存储在本地数据库中。
     */
    suspend fun markAsVerified(contactId: String, myEcdhPublicKey: ByteArray, theirEcdhPublicKey: ByteArray) {
        val fingerprintSnapshot = computeSecurityCode(myEcdhPublicKey, theirEcdhPublicKey)
        trustDao.save(
            TrustEntity(
                contactId = contactId,
                status = "verified",
                verifiedAt = System.currentTimeMillis(),
                fingerprintSnapshot = fingerprintSnapshot
            )
        )
    }

    /**
     * 获取指定联系人的信任状态
     */
    suspend fun getTrustState(contactId: String): TrustState {
        val rec = trustDao.get(contactId)
        if (rec == null || rec.status != "verified") {
            return TrustState.Unverified
        }
        return TrustState.Verified(rec.verifiedAt, rec.fingerprintSnapshot)
    }

    /**
     * 取消对联系人的通过验证身份
     */
    suspend fun resetTrustState(contactId: String) {
        trustDao.delete(contactId)
    }

    /**
     * ！！！核心守门员！！！
     * 收取到的每一条消息都必须经过它，拦截判断 ECDH PubKey 是否突变。
     * 如果突变则抛出安全例外。
     */
    suspend fun guardMessage(contactId: String, currentMyEcdh: ByteArray, currentTheirEcdh: ByteArray) {
        val trustState = getTrustState(contactId)
        if (trustState !is TrustState.Verified) {
            // 没有 Verify 过，默认为通过（黄盾警告由UI处理，而不是 SDK 层抛异常阻断通信）
            return
        }

        val currentFingerprint = computeSecurityCode(currentMyEcdh, currentTheirEcdh)
        if (currentFingerprint == trustState.fingerprintSnapshot) {
            return // 安全
        }

        // ❌ 发现公钥发生突变！意味着遭遇了重装设备、密钥清除或被中间人劫持！
        throw SecurityViolationException(contactId, trustState.fingerprintSnapshot, currentFingerprint)
    }

    /**
     * 根据双方 pubkey 进行 hex 叠加的生成
     */
    fun computeSecurityCode(myEcdhPublicKey: ByteArray, theirEcdhPublicKey: ByteArray): String {
        // 先判断两个 byte array 的大小
        val cmp = compareByteArrays(myEcdhPublicKey, theirEcdhPublicKey)
        val first = if (cmp <= 0) myEcdhPublicKey else theirEcdhPublicKey
        val second = if (cmp <= 0) theirEcdhPublicKey else myEcdhPublicKey

        val concat = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, concat, 0, first.size)
        System.arraycopy(second, 0, concat, first.size, second.size)

        val hash = KeyDerivation.sha256(concat)
        // 截取前 30 个 bytes，生成 60 位 hex (2 * 30 = 60)
        return hash.take(30).joinToString("") { "%02x".format(it) }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val aByte = a[i].toInt() and 0xFF
            val bByte = b[i].toInt() and 0xFF
            if (aByte != bByte) {
                return aByte - bByte
            }
        }
        return a.size - b.size
    }
}
