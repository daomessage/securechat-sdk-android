package space.securechat.sdk.security

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.securechat.sdk.db.TrustDao
import space.securechat.sdk.db.TrustEntity
import java.security.SecureRandom

class SecurityModuleTest {

    private val fakeDao = object : TrustDao {
        val storage = mutableMapOf<String, TrustEntity>()

        override suspend fun get(contactId: String): TrustEntity? = storage[contactId]
        override suspend fun save(trust: TrustEntity) { storage[trust.contactId] = trust }
        override suspend fun delete(contactId: String) { storage.remove(contactId) }
        override suspend fun clearAll() { storage.clear() }
    }

    private val securityModule = SecurityModule(fakeDao)

    @Test
    fun testSecurityCodeComputation() {
        val myPub = ByteArray(32).apply { fill(1) }
        val theirPub = ByteArray(32).apply { fill(2) }

        val code1 = securityModule.getSecurityCode("alias_x", myPub, theirPub)
        val code2 = securityModule.getSecurityCode("alias_x", theirPub, myPub) // 交换顺序不会变

        assertEquals(60, code1.fingerprintHex.length)
        assertEquals(code1.fingerprintHex, code2.fingerprintHex)

        val displayStripped = code1.displayCode.replace(" ", "")
        assertEquals(code1.fingerprintHex.uppercase(), displayStripped.uppercase())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testVerifyInputCode() = runTest {
        val myPub = ByteArray(32).apply { fill(3) }
        val theirPub = ByteArray(32).apply { fill(4) }
        val code = securityModule.getSecurityCode("alias_y", myPub, theirPub)

        securityModule.verifyInputCode("alias_y", code.displayCode, myPub, theirPub)

        val trust = fakeDao.get("alias_y")
        assertTrue(trust != null)
        assertEquals(code.fingerprintHex, trust?.fingerprintSnapshot)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testTrustStateFetch() = runTest {
        fakeDao.save(TrustEntity("alias_z", "a1b2c3d4", System.currentTimeMillis()))
        val state = securityModule.getTrustState("alias_z")
        
        assertTrue(state is TrustState.Verified)
        assertEquals("a1b2c3d4", (state as TrustState.Verified).fingerprintSnapshot)
    }
}
