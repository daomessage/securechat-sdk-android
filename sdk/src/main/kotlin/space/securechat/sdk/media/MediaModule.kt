package space.securechat.sdk.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.securechat.sdk.events.EventBus
import space.securechat.sdk.events.SDKError
import space.securechat.sdk.events.SDKErrorKind

/**
 * MediaModule — 0.3.0 响应式媒介上传/下载
 *
 * 对标 sdk-typescript/src/media/module.ts
 *
 * 命令式 API:
 *   - sendImage(convId, fileBytes, sessionKey) -> messageId
 *   - sendFile(...) / sendVoice(...)
 * 响应式 API:
 *   - observeUpload(messageId) -> StateFlow<UploadProgress>
 *
 * 真实 byte-level 进度在 0.4+ 接入 MediaManager 底层回调, 当前版本用伪曲线。
 */
class MediaModule(
    private val inner: MediaManager,
    private val events: EventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val uploads = mutableMapOf<String, MutableStateFlow<UploadProgress>>()

    fun observeUpload(messageId: String): StateFlow<UploadProgress> {
        return uploads[messageId]?.asStateFlow()
            ?: MutableStateFlow(UploadProgress(messageId, UploadPhase.FAILED, 0, 0, "unknown messageId")).asStateFlow()
    }

    suspend fun sendImage(
        conversationId: String,
        bytes: ByteArray,
        sessionKey: ByteArray,
        mime: String = "image/jpeg",
    ): String = upload(MediaKind.IMAGE, bytes) {
        val (mediaKey, _) = inner.encryptAndUpload(bytes, mime, sessionKey, conversationId)
        mediaKey
    }

    suspend fun sendFile(
        conversationId: String,
        bytes: ByteArray,
        sessionKey: ByteArray,
        mime: String,
    ): String = upload(MediaKind.FILE, bytes) {
        val (mediaKey, _) = inner.encryptAndUpload(bytes, mime, sessionKey, conversationId)
        mediaKey
    }

    suspend fun sendVoice(
        conversationId: String,
        bytes: ByteArray,
        sessionKey: ByteArray,
    ): String = upload(MediaKind.VOICE, bytes) {
        val (mediaKey, _) = inner.encryptAndUpload(bytes, "audio/webm", sessionKey, conversationId)
        mediaKey
    }

    /** 兼容 0.2.x API: 下载并解密媒介 */
    suspend fun downloadAndDecrypt(mediaKey: String, sessionKey: ByteArray): ByteArray {
        return inner.downloadAndDecrypt(mediaKey, sessionKey)
    }

    fun dispose(messageId: String) {
        uploads.remove(messageId)
    }

    // ─── 内部 ───────────────────────────────────────────

    private suspend fun upload(
        kind: MediaKind,
        bytes: ByteArray,
        doUpload: suspend () -> String,
    ): String {
        val messageId = "up-${java.util.UUID.randomUUID()}"
        val total = bytes.size.toLong()
        val flow = MutableStateFlow(UploadProgress(messageId, UploadPhase.ENCRYPTING, 0, total))
        uploads[messageId] = flow

        val progressJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(300)
                tick++
                val v = flow.value
                if (v.phase == UploadPhase.ENCRYPTING && tick >= 2) {
                    flow.value = v.copy(phase = UploadPhase.UPLOADING, loaded = (total * 0.1).toLong().coerceAtMost(total))
                } else if (v.phase == UploadPhase.UPLOADING) {
                    flow.value = v.copy(loaded = (v.loaded + total * 0.1).toLong().coerceAtMost((total * 0.95).toLong()))
                } else {
                    break
                }
            }
        }

        return try {
            doUpload()
            progressJob.cancel()
            flow.value = UploadProgress(messageId, UploadPhase.DONE, total, total)
            messageId
        } catch (e: Throwable) {
            progressJob.cancel()
            flow.value = flow.value.copy(phase = UploadPhase.FAILED, error = e.message)
            events.emitError(SDKError(SDKErrorKind.NETWORK, "media upload failed: ${e.message}", mapOf("messageId" to messageId)))
            throw e
        }
    }
}

enum class UploadPhase { ENCRYPTING, UPLOADING, DONE, FAILED }
enum class MediaKind { IMAGE, FILE, VOICE }

data class UploadProgress(
    val messageId: String,
    val phase: UploadPhase,
    val loaded: Long,
    val total: Long,
    val error: String? = null,
)
