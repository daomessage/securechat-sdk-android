package space.securechat.sdk.keys

import org.bitcoinj.crypto.MnemonicCode
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * 🔒 KeyDerivation — 密钥体系核心
 *
 * 完全对标 sdk-typescript/src/keys/index.ts，确保相同助记词在 Android 和 Web 端
 * 派生出完全相同的公钥（跨端账号恢复能力）。
 *
 * 派生路径（SLIP-0010 硬化派生）：
 *   Ed25519: m/44'/0'/0'/0/0  — 身份认证/签名
 *   X25519:  m/44'/1'/0'/0/0  — ECDH 消息加密
 *
 * 🛡️ AI 约束：不得修改派生路径或 HMAC key（"ed25519 seed"），否则双端公钥不一致
 */
object KeyDerivation {

    // ── 助记词 ────────────────────────────────────────────────────────

    /**
     * 生成 12 词 BIP-39 英文助记词
     * 对标 TS SDK: newMnemonic()
     */
    fun newMnemonic(): String {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" ")
    }

    /**
     * 验证助记词（BIP-39 词库校验 + 校验和）
     * 对标 TS SDK: validateMnemonicWords()
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        return try {
            val words = mnemonic.trim().split("\\s+".toRegex())
            if (words.size != 12) return false
            MnemonicCode.INSTANCE.check(words)
            true
        } catch (_: Exception) { false }
    }

    /**
     * 助记词 → BIP-39 Seed（PBKDF2-HMAC-SHA512，2048 轮，无 passphrase）
     * 对标 TS SDK: mnemonicToSeedSync(mnemonic)
     */
    fun mnemonicToSeed(mnemonic: String): ByteArray {
        val words = mnemonic.trim().split("\\s+".toRegex())
        return MnemonicCode.toSeed(words, "")
    }

    // ── Ed25519 签名密钥派生 ──────────────────────────────────────────

    /**
     * 从助记词派生 Ed25519 签名密钥对
     * 路径：m/44'/0'/0'/0/0
     * 对标 TS SDK: deriveSigningKey(mnemonic)
     */
    fun deriveSigningKey(mnemonic: String): KeyPair {
        val seed = mnemonicToSeed(mnemonic)
        val privateBytes = deriveHardened(seed, intArrayOf(44, 0, 0, 0, 0))
        val privParams = Ed25519PrivateKeyParameters(privateBytes)
        val pubParams = privParams.generatePublicKey()
        return KeyPair(
            privateKey = privateBytes,
            publicKey = pubParams.encoded
        )
    }

    /**
     * 从助记词派生 X25519 ECDH 密钥对
     * 路径：m/44'/1'/0'/0/0
     * 对标 TS SDK: deriveEcdhKey(mnemonic)
     */
    fun deriveEcdhKey(mnemonic: String): KeyPair {
        val seed = mnemonicToSeed(mnemonic)
        val privateBytes = deriveHardened(seed, intArrayOf(44, 1, 0, 0, 0))
        val privParams = X25519PrivateKeyParameters(privateBytes)
        val pubParams = privParams.generatePublicKey()
        return KeyPair(
            privateKey = privateBytes,
            publicKey = pubParams.encoded
        )
    }

    /**
     * 从助记词完整派生 Identity（含两对密钥）
     * 对标 TS SDK: deriveIdentity(mnemonic)
     */
    fun deriveIdentity(mnemonic: String): Identity {
        require(validateMnemonic(mnemonic)) { "Invalid mnemonic" }
        return Identity(
            mnemonic = mnemonic,
            signingKey = deriveSigningKey(mnemonic),
            ecdhKey = deriveEcdhKey(mnemonic)
        )
    }

    // ── Ed25519 签名 ──────────────────────────────────────────────────

    /**
     * Ed25519 签名 challenge（用于 Challenge-Response 认证）
     * 对标 TS SDK: signChallenge(challenge, privateKey)
     */
    fun signChallenge(challenge: ByteArray, privateKey: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey))
        signer.update(challenge, 0, challenge.size)
        return signer.generateSignature()
    }

    // ── X25519 ECDH ───────────────────────────────────────────────────

    /**
     * ECDH 计算共享密钥
     * 对标 TS SDK: computeSharedSecret(myPrivateKey, theirPublicKey)
     */
    fun computeSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPrivateKey))
        val result = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKey), result, 0)
        return result
    }

    /**
     * HKDF-SHA256：将 SharedSecret 派生为 32 字节 AES-256 会话密钥
     * salt = SHA-256(conv_id)，info = "securechat-session-v1"
     * 对标 TS SDK: deriveSessionKey(sharedSecret, conversationId)
     * 🛡️ 不得修改 salt/info，与 Web 端协议严格对齐
     */
    fun deriveSessionKey(sharedSecret: ByteArray, conversationId: String): ByteArray {
        val convIdBytes = conversationId.toByteArray(Charsets.UTF_8)
        val salt = sha256(convIdBytes)
        val info = "securechat-session-v1".toByteArray(Charsets.UTF_8)
        return hkdf(sharedSecret, salt, info, 32)
    }

    // ── 安全码（MITM 防御）───────────────────────────────────────────

    /**
     * 计算 60 字符安全码（用于 UI 展示，用户手动比对防 MITM）
     * 算法：SHA-256(min(pubA, pubB) ‖ max(pubA, pubB))[0..30] → hex
     * 对标 TS SDK: computeSecurityCode(myEcdhPublicKey, theirEcdhPublicKey)
     */
    fun computeSecurityCode(myEcdhPublicKey: ByteArray, theirEcdhPublicKey: ByteArray): String {
        val (first, second) = if (compareBytes(myEcdhPublicKey, theirEcdhPublicKey) <= 0)
            myEcdhPublicKey to theirEcdhPublicKey
        else
            theirEcdhPublicKey to myEcdhPublicKey
        val concat = first + second
        return sha256(concat).take(30).joinToString("") { "%02x".format(it) }
    }

    // ── 内部：SLIP-0010 硬化派生 ─────────────────────────────────────

    /**
     * SLIP-0010 硬化路径派生
     * 与 TS SDK deriveHardened() 完全相同算法：
     *   key_0 = HMAC-SHA512("ed25519 seed", seed)
     *   key_i = HMAC-SHA512(IR_{i-1}, 0x00 || IL_{i-1} || ser32(index | 0x80000000))
     */
    private fun deriveHardened(seed: ByteArray, path: IntArray): ByteArray {
        var key = hmacSha512("ed25519 seed".toByteArray(Charsets.UTF_8), seed)
        for (index in path) {
            val hardened = (index.toLong() or 0x80000000L).toInt()
            val buf = ByteArray(37)
            buf[0] = 0x00
            key.copyInto(buf, destinationOffset = 1, startIndex = 0, endIndex = 32)
            ByteBuffer.wrap(buf, 33, 4).putInt(hardened)
            key = hmacSha512(key.copyOfRange(32, 64), buf)
        }
        return key.copyOfRange(0, 32)
    }

    // ── 工具函数 ──────────────────────────────────────────────────────

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(64)
        mac.doFinal(out, 0)
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(32)
        mac.doFinal(out, 0)
        return out
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    /**
     * HKDF-SHA256 实现（RFC 5869）
     * 对标 TS SDK 内部 hkdf() 函数
     */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
        val prk = hmacSha256(salt, ikm)
        // Expand
        val result = ByteArray(length)
        var prev = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val data = prev + info + byteArrayOf(counter.toByte())
            prev = hmacSha256(prk, data)
            val toCopy = minOf(prev.size, length - offset)
            prev.copyInto(result, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}

// ── 数据类型 ──────────────────────────────────────────────────────────

/** 密钥对，对标 TS SDK KeyPair */
data class KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }
    override fun hashCode() = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}

/** 完整身份，对标 TS SDK Identity */
data class Identity(
    val mnemonic: String,
    val signingKey: KeyPair,
    val ecdhKey: KeyPair
)
