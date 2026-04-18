package space.securechat.sdk.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeystoreCipher — 用 Android Keystore 管理的 AES-256-GCM 主密钥
 *
 * 密钥永不出 Keystore 硬件/TEE 安全区。用于包装 Room 里的敏感字段
 * （mnemonic / signingPrivateKey / ecdhPrivateKey）。
 *
 * 存储格式：base64(IV ‖ ciphertext ‖ GCM tag)
 */
object KeystoreCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "securechat-master-v1"
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // 允许无用户认证即可使用（app 启动即自动解锁）
            // 若产品后续要求 Face/指纹解锁，改为 setUserAuthenticationRequired(true)
            .build()
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE
        ).apply { init(spec) }.generateKey()
    }

    /** 加密字符串 → base64(IV+CT+tag) */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val iv = cipher.iv // AES-GCM 自动生成 12B IV
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    /** 解密 base64(IV+CT+tag) → 原文 */
    fun decrypt(ciphertextB64: String): String {
        val combined = android.util.Base64.decode(ciphertextB64, android.util.Base64.NO_WRAP)
        if (combined.size <= IV_LEN) error("ciphertext too short")
        val iv = combined.copyOfRange(0, IV_LEN)
        val ct = combined.copyOfRange(IV_LEN, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** 测试/debug 用：擦除 master key（将使所有已加密数据无法解密） */
    fun wipe() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
    }
}
