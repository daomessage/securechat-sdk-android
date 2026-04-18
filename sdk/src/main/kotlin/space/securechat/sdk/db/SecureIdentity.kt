package space.securechat.sdk.db

import space.securechat.sdk.crypto.KeystoreCipher

/**
 * SecureIdentity — 对 IdentityEntity 敏感字段的 Keystore 加密包装
 *
 * 2026-04 加固（fix C4）：
 *   之前 mnemonic / signingPrivateKey / ecdhPrivateKey 以明文存在 Room DB 里，
 *   root 设备 / adb 备份即可 dump 得到身份。现改为由 Android Keystore
 *   AES-256-GCM master key 加密后再写入，即使数据库文件泄漏也无法解密。
 *
 * 使用方式：
 *   // 保存
 *   SecureIdentity.save(db, uuid, alias, nickname, mnemonic, sigPub, ecdhPub, sigPriv, ecdhPriv)
 *   // 读取
 *   val id = SecureIdentity.load(db)  // 返回已解密的 IdentityEntity
 */
object SecureIdentity {

    suspend fun save(
        db: SecureChatDatabase,
        uuid: String,
        aliasId: String,
        nickname: String,
        mnemonic: String,
        signingPublicKey: String,
        ecdhPublicKey: String,
        signingPrivateKey: String,
        ecdhPrivateKey: String,
    ) {
        db.identityDao().save(
            IdentityEntity(
                uuid = uuid,
                aliasId = aliasId,
                nickname = nickname,
                // 敏感字段全部经 Keystore 加密
                mnemonic          = KeystoreCipher.encrypt(mnemonic),
                signingPublicKey  = signingPublicKey,
                ecdhPublicKey     = ecdhPublicKey,
                signingPrivateKey = KeystoreCipher.encrypt(signingPrivateKey),
                ecdhPrivateKey    = KeystoreCipher.encrypt(ecdhPrivateKey),
            )
        )
    }

    /** 读取并解密身份；若 DB 无记录返回 null */
    suspend fun load(db: SecureChatDatabase): IdentityEntity? {
        val raw = db.identityDao().get() ?: return null
        return try {
            raw.copy(
                mnemonic          = KeystoreCipher.decrypt(raw.mnemonic),
                signingPrivateKey = KeystoreCipher.decrypt(raw.signingPrivateKey),
                ecdhPrivateKey    = KeystoreCipher.decrypt(raw.ecdhPrivateKey),
            )
        } catch (e: Exception) {
            android.util.Log.e("SecureIdentity", "decrypt failed — keystore master key lost?", e)
            null
        }
    }
}
