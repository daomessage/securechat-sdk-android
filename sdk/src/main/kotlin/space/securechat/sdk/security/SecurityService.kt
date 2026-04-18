package space.securechat.sdk.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * SecurityService — 0.3.0 响应式信任状态 API
 *
 * 对标 sdk-typescript/src/security/module.ts
 *
 *   - observeTrust(contactId) -> StateFlow<TrustState>
 *   - getSafetyCode / verifyCode / markVerified / reset
 *
 * 底层 SecurityModule 保留作命令式引擎。
 */
class SecurityService(
    private val inner: SecurityModule,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val states = mutableMapOf<String, MutableStateFlow<TrustState>>()

    fun observeTrust(contactId: String): StateFlow<TrustState> {
        val subject = states.getOrPut(contactId) {
            MutableStateFlow<TrustState>(TrustState.Unverified).also { flow ->
                scope.launch {
                    runCatching { inner.getTrustState(contactId) }.onSuccess { flow.value = it }
                }
            }
        }
        return subject.asStateFlow()
    }

    suspend fun getTrust(contactId: String): TrustState = inner.getTrustState(contactId)

    fun getSafetyCode(
        contactId: String,
        myEcdhPublicKey: ByteArray,
        theirEcdhPublicKey: ByteArray,
    ): SecurityCode = inner.getSecurityCode(contactId, myEcdhPublicKey, theirEcdhPublicKey)

    suspend fun verifyCode(
        contactId: String,
        inputCode: String,
        myEcdhPublicKey: ByteArray,
        theirEcdhPublicKey: ByteArray,
    ): Boolean {
        val ok = inner.verifyInputCode(contactId, inputCode, myEcdhPublicKey, theirEcdhPublicKey)
        if (ok) emitCurrent(contactId)
        return ok
    }

    suspend fun markVerified(
        contactId: String,
        myEcdhPublicKey: ByteArray,
        theirEcdhPublicKey: ByteArray,
    ) {
        inner.markAsVerified(contactId, myEcdhPublicKey, theirEcdhPublicKey)
        emitCurrent(contactId)
    }

    suspend fun reset(contactId: String) {
        inner.resetTrustState(contactId)
        emitCurrent(contactId)
    }

    private suspend fun emitCurrent(contactId: String) {
        val s = inner.getTrustState(contactId)
        states[contactId]?.value = s ?: return
    }
}
