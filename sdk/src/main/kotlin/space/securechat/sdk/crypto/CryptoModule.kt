package space.securechat.sdk.crypto

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import space.securechat.sdk.keys.KeyDerivation
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 🔒 CryptoModule — AES-256-GCM 加解密
 *
 * 实现 SecureChat E2EE 信封协议：
 *   加密: plaintext → { iv(12B), ciphertext, tag(16B) } → Base64 payload
 *   解密: Base64 payload → { iv, ciphertext+tag } → plaintext
 *
 * ⚠️ AI 约束：信封格式与 Web 端严格一致，不得修改
 * 对标 sdk-typescript/src/crypto/index.ts
 */
object CryptoModule {

    private val random = SecureRandom()
    private const val IV_LENGTH = 12    // AES-GCM 标准 IV
    private const val TAG_BITS = 128    // GCM authentication tag

    // ── 核心加解密 ────────────────────────────────────────────────────

    /**
     * AES-256-GCM 加密
     * 返回 Base64 编码的信封：base64(iv[12] + ciphertext + tag[16])
     * 对标 TS SDK: encryptMessage(sessionKey, plaintext)
     */
    fun encrypt(sessionKey: ByteArray, plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val envelope = iv + aesGcmEncrypt(sessionKey, iv, plaintextBytes)
        return Base64.getEncoder().encodeToString(envelope)
    }

    /**
     * AES-256-GCM 加密字节数组（媒体文件使用）
     * 返回原始信封字节：iv[12] + ciphertext + tag[16]
     */
    fun encryptBytes(sessionKey: ByteArray, data: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        return iv + aesGcmEncrypt(sessionKey, iv, data)
    }

    /**
     * AES-256-GCM 解密
     * 输入：base64(iv[12] + ciphertext + tag[16])
     * 对标 TS SDK: decryptMessage(sessionKey, base64Payload)
     * @throws IllegalArgumentException 认证失败（MITM 或数据损坏）
     */
    fun decrypt(sessionKey: ByteArray, base64Payload: String): String {
        val envelope = Base64.getDecoder().decode(base64Payload)
        require(envelope.size > IV_LENGTH) { "Invalid envelope: too short" }
        val iv = envelope.copyOfRange(0, IV_LENGTH)
        val cipherAndTag = envelope.copyOfRange(IV_LENGTH, envelope.size)
        return String(aesGcmDecrypt(sessionKey, iv, cipherAndTag), Charsets.UTF_8)
    }

    /**
     * AES-256-GCM 解密字节数组（媒体文件使用）
     * 输入原始信封字节：iv[12] + ciphertext + tag[16]
     */
    fun decryptBytes(sessionKey: ByteArray, envelope: ByteArray): ByteArray {
        require(envelope.size > IV_LENGTH) { "Invalid envelope: too short" }
        val iv = envelope.copyOfRange(0, IV_LENGTH)
        val cipherAndTag = envelope.copyOfRange(IV_LENGTH, envelope.size)
        return aesGcmDecrypt(sessionKey, iv, cipherAndTag)
    }

    // ── 内部 AES-GCM 核心 ─────────────────────────────────────────────

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, iv))
        val output = ByteArray(cipher.getOutputSize(data.size))
        val len = cipher.processBytes(data, 0, data.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherAndTag: ByteArray): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, iv))
        val output = ByteArray(cipher.getOutputSize(cipherAndTag.size))
        val len = cipher.processBytes(cipherAndTag, 0, cipherAndTag.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    // ── PoW 工作量证明 ────────────────────────────────────────────────

    /**
     * 计算 PoW nonce（防注册刷量）
     *
     * 🛡️ 算法严格对标 TS SDK auth/manager.ts:
     *   for (let i = 0; i < 10_000_000; i++) {
     *     const candidate = i.toString()
     *     const hash = sha256(encoder.encode(challenge + candidate))
     *     const hex = bytesToHex(hash)
     *     if (hex.startsWith(prefix))  // prefix = '0'.repeat(difficulty)
     *   }
     *
     * ❗ CPU 密集型，内部强制切换到 Dispatchers.Default，不会 ANR
     */
    suspend fun computePow(challenge: String, difficulty: Int): String = withContext(Dispatchers.Default) {
        val prefix = "0".repeat(difficulty)
        for (i in 0 until 10_000_000) {
            val candidate = i.toString()
            val input = (challenge + candidate).toByteArray(Charsets.UTF_8)
            val hash = KeyDerivation.sha256(input)
            val hex = hash.joinToString("") { "%02x".format(it) }
            if (hex.startsWith(prefix)) {
                return@withContext candidate
            }
        }
        // 超过 1000 万轮未找到，返回空（与 TS SDK 行为一致：powNonce 为空仍可注册）
        ""
    }
}
