package space.securechat.sdk.keys

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * 密钥派生单元测试
 *
 * ⚠️ 测试向量来自 TS SDK 用同一助记词实际运行结果，确保跨端一致
 * 如果测试失败，说明派生算法与 TS SDK 不一致，严禁上线！
 */
class KeyDerivationTest {

    // 固定助记词（测试专用，不要用于真实账号）
    private val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `助记词验证 - 合法12词`() {
        assertTrue(KeyDerivation.validateMnemonic(TEST_MNEMONIC))
    }

    @Test
    fun `助记词验证 - 非法`() {
        assertFalse(KeyDerivation.validateMnemonic("invalid mnemonic words"))
        assertFalse(KeyDerivation.validateMnemonic("abandon abandon")) // 不足12词
    }

    @Test
    fun `生成助记词 - 格式正确`() {
        val mnemonic = KeyDerivation.newMnemonic()
        val words = mnemonic.trim().split("\\s+".toRegex())
        assertEquals(12, words.size)
        assertTrue(KeyDerivation.validateMnemonic(mnemonic))
    }

    @Test
    fun `Ed25519 与 X25519 公钥长度 - 均为32字节`() {
        val identity = KeyDerivation.deriveIdentity(TEST_MNEMONIC)
        assertEquals(32, identity.signingKey.publicKey.size)
        assertEquals(32, identity.ecdhKey.publicKey.size)
        assertEquals(32, identity.signingKey.privateKey.size)
        assertEquals(32, identity.ecdhKey.privateKey.size)
    }

    @Test
    fun `Ed25519 签名 - 确定性（相同助记词每次相同公钥）`() {
        val key1 = KeyDerivation.deriveSigningKey(TEST_MNEMONIC)
        val key2 = KeyDerivation.deriveSigningKey(TEST_MNEMONIC)
        assertArrayEquals(key1.publicKey, key2.publicKey)
        assertArrayEquals(key1.privateKey, key2.privateKey)
    }

    @Test
    fun `ECDH 共享密钥 - 双向一致`() {
        val aliceMnemonic = KeyDerivation.newMnemonic()
        val bobMnemonic = KeyDerivation.newMnemonic()
        val alice = KeyDerivation.deriveIdentity(aliceMnemonic)
        val bob = KeyDerivation.deriveIdentity(bobMnemonic)

        val aliceShared = KeyDerivation.computeSharedSecret(alice.ecdhKey.privateKey, bob.ecdhKey.publicKey)
        val bobShared = KeyDerivation.computeSharedSecret(bob.ecdhKey.privateKey, alice.ecdhKey.publicKey)
        assertArrayEquals(aliceShared, bobShared)
    }

    @Test
    fun `HKDF 派生会话密钥 - 长度32字节`() {
        val secret = ByteArray(32) { it.toByte() }
        val key = KeyDerivation.deriveSessionKey(secret, "c-test-conv-id")
        assertEquals(32, key.size)
    }

    @Test
    fun `安全码 - 对称性（AB == BA）`() {
        val pubA = ByteArray(32) { it.toByte() }
        val pubB = ByteArray(32) { (it + 100).toByte() }
        val codeAB = KeyDerivation.computeSecurityCode(pubA, pubB)
        val codeBA = KeyDerivation.computeSecurityCode(pubB, pubA)
        assertEquals(codeAB, codeBA)
        assertEquals(60, codeAB.length)
    }

    /**
     * ✅ 跨端公钥一致性验证（与 Web 端严格对齐）
     *
     * 向量来源：在 sdk-typescript/ 执行：
     *   npx vitest run tests/vec.test.ts
     *
     * 同一助记词（"abandon × 11 + about"）在 TS SDK 与 Android SDK 派生公钥相同
     * 证明 SLIP-0010 派生、BIP-39 seed 算法完全对齐
     */
    @Test
    fun `跨端公钥一致性 - Android 派生结果必须与 Web 端完全相同`() {
        // 来自 TS SDK 实际运行（2026-04-04 验证）
        val expectedSigPub  = "fC5586FwH7Koaiwko/34Y0t6rYCIbAwKUm1E0j/o4Zo="
        val expectedEcdhPub = "NJiwxcmC9F8mDvwpB5BKRkxowvlTRypDqFrNCtPulgM="

        val identity = KeyDerivation.deriveIdentity(TEST_MNEMONIC)
        assertEquals(
            "Ed25519 公钥与 Web 端不一致！派生路径或 HMAC 算法有误",
            expectedSigPub,
            Base64.getEncoder().encodeToString(identity.signingKey.publicKey)
        )
        assertEquals(
            "X25519 公钥与 Web 端不一致！派生路径或 HMAC 算法有误",
            expectedEcdhPub,
            Base64.getEncoder().encodeToString(identity.ecdhKey.publicKey)
        )
    }

