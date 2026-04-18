package space.securechat.sdk.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import space.securechat.sdk.http.HttpClient
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 🔒 MediaManager — 加密媒体上传/下载
 *
 * 协议（对标 sdk-typescript/src/media/manager.ts）：
 *   上传：分片式 AES-256-GCM 加密 → Multipart Upload → 返回 media_key
 *   下载：GET /api/v1/media/download?key=xxx → 盲中转流式下载 → 分片式 AES-GCM 解密
 *
 * ⚠️ 信封格式必须与 TS SDK 完全一致：
 *   每个 Chunk: [4B ChunkLen (BigEndian)] [12B IV] [AES-GCM ciphertext + tag]
 *   其中 ChunkLen = len(ciphertext + tag)，不含自身和 IV
 *
 * ⚠️ 所有 OkHttp 同步调用必须在 Dispatchers.IO 上执行，禁止主线程网络
 * ⚠️ 不要手动添加 Authorization header —— HttpClient 的 OkHttp Interceptor 已自动注入
 */
class MediaManager(
    private val http: HttpClient,
    private val okhttp: OkHttpClient
) {
    companion object {
        private const val CHUNK_SIZE = 5 * 1024 * 1024  // 5MB，与 TS SDK 一致
        private const val AES_GCM_NONCE_LEN = 12
        private const val AES_GCM_TAG_BITS = 128
    }

    private val random = SecureRandom()

    /**
     * 下载并解密媒体文件（兼容 TS SDK 分片加密格式）
     * 通过 /api/v1/media/download?key=xxx 盲中转流式下载
     */
    suspend fun downloadAndDecrypt(mediaKey: String, sessionKey: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val apiBase = HttpClient.CORE_API_BASE
            val url = "$apiBase/api/v1/media/download?key=${java.net.URLEncoder.encode(mediaKey, "UTF-8")}"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val encryptedBytes = okhttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: ${response.code} ${response.message}")
                response.body?.bytes() ?: error("Empty response body")
            }

            // 智能解密：自动兼容 TS SDK 分片格式和旧的简单信封格式
            smartDecrypt(sessionKey, encryptedBytes)
        }

    // ── 内部：分片式加密上传（与 TS SDK encryptAndUpload 格式一致）───────────

    internal suspend fun encryptAndUpload(
        data: ByteArray,
        contentType: String,
        sessionKey: ByteArray,
        conversationId: String
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        // 1. 初始化分片上传
        val initBody = mapOf("content_type" to "application/octet-stream")
        val initJson = http.moshi.adapter(Map::class.java).toJson(initBody)
        val apiBase = HttpClient.CORE_API_BASE

        val initRequest = Request.Builder()
            .url("$apiBase/api/v1/media/upload-parts/init")
            .addHeader("Content-Type", "application/json")
            .post(initJson.toRequestBody("application/json".toMediaType()))
            .build()

        val (uploadId, mediaKey) = okhttp.newCall(initRequest).execute().use { response ->
            if (!response.isSuccessful) error("Init MPU failed: ${response.code}")
            val body = response.body?.string() ?: error("Empty init response")
            @Suppress("UNCHECKED_CAST")
            val parsed = http.moshi.adapter(Map::class.java).fromJson(body) as Map<String, Any>
            (parsed["upload_id"] as String) to (parsed["media_key"] as String)
        }

        val parts = mutableListOf<Map<String, Any>>()
        var offset = 0
        var partNumber = 1
        var totalEncryptedSize = 0

        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val chunkData = data.copyOfRange(offset, end)

            // 加密此 chunk
            val iv = ByteArray(AES_GCM_NONCE_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            val cipherBytes = cipher.doFinal(chunkData)

            // 打包信封: [4B chunkLen BigEndian] [12B IV] [ciphertext+tag]
            val chunkLen = cipherBytes.size
            val payloadSize = 4 + AES_GCM_NONCE_LEN + chunkLen
            val payload = ByteBuffer.allocate(payloadSize)
            payload.putInt(chunkLen)        // Big Endian (Java 默认)
            payload.put(iv)
            payload.put(cipherBytes)
            val payloadBytes = payload.array()

            // 上传此分片（通过 relay-server 盲代传至 S3）
            val uploadUrl = "$apiBase/api/v1/media/upload-parts/${java.net.URLEncoder.encode(uploadId, "UTF-8")}/chunk?mediaKey=${java.net.URLEncoder.encode(mediaKey, "UTF-8")}&partNumber=$partNumber"

            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .post(payloadBytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val etag = okhttp.newCall(uploadRequest).execute().use { response ->
                if (!response.isSuccessful) error("Part $partNumber upload failed: ${response.code}")
                val body = response.body?.string() ?: error("Empty upload response")
                @Suppress("UNCHECKED_CAST")
                val parsed = http.moshi.adapter(Map::class.java).fromJson(body) as Map<String, Any>
                val rawEtag = parsed["etag"] as? String ?: error("Missing ETag")
                rawEtag.replace("\"", "")
            }

            parts.add(mapOf("etag" to etag, "part_number" to partNumber))
            totalEncryptedSize += payloadSize
            offset = end
            partNumber++
        }

        // 合并分片
        val completeBody = mapOf("media_key" to mediaKey, "parts" to parts)
        val completeJson = http.moshi.adapter(Map::class.java).toJson(completeBody)
        val completeRequest = Request.Builder()
            .url("$apiBase/api/v1/media/upload-parts/${java.net.URLEncoder.encode(uploadId, "UTF-8")}/complete")
            .addHeader("Content-Type", "application/json")
            .post(completeJson.toRequestBody("application/json".toMediaType()))
            .build()

        okhttp.newCall(completeRequest).execute().use { response ->
            if (!response.isSuccessful) error("Complete MPU failed: ${response.code}")
        }

        mediaKey to totalEncryptedSize
    }

    /**
     * 智能解密：先尝试 TS SDK 分片格式，失败后回退到旧的简单信封格式
     *
     * 分片格式: 循环 { [4B chunkLen] [12B IV] [chunkLen bytes ciphertext+tag] }
     * 旧格式:   [12B IV][ciphertext+tag]  (CryptoModule.encryptBytes 的输出)
     */
    private fun smartDecrypt(sessionKey: ByteArray, encrypted: ByteArray): ByteArray {
        // 先尝试分片格式
        try {
            val result = decryptChunked(sessionKey, encrypted)
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {
            // 分片格式解密失败，尝试旧格式
        }

        // 回退：旧的简单信封格式 [12B IV][ciphertext+tag]
        try {
            return decryptSimple(sessionKey, encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("Both chunked and simple decryption failed", e)
        }
    }

    /**
     * 分片式 AES-256-GCM 解密（对标 TS SDK downloadDecryptedMedia 的解密逻辑）
     */
    private fun decryptChunked(sessionKey: ByteArray, encrypted: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val buf = ByteBuffer.wrap(encrypted)

        // 前置检查：首 4 字节作为 chunkLen，必须合理
        if (encrypted.size < 4 + AES_GCM_NONCE_LEN + 16) throw IllegalStateException("Too short for chunked")
        val peekLen = ByteBuffer.wrap(encrypted, 0, 4).int
        if (peekLen <= 0 || peekLen > encrypted.size) throw IllegalStateException("Invalid chunkLen: $peekLen")

        while (buf.hasRemaining()) {
            if (buf.remaining() < 4) throw IllegalStateException("Corrupted data: chunk length OOB")
            val chunkLen = buf.int

            if (chunkLen <= 0 || chunkLen > 10 * 1024 * 1024) throw IllegalStateException("Unreasonable chunkLen: $chunkLen")
            if (buf.remaining() < AES_GCM_NONCE_LEN) throw IllegalStateException("Corrupted data: IV OOB")
            val iv = ByteArray(AES_GCM_NONCE_LEN)
            buf.get(iv)

            if (buf.remaining() < chunkLen) throw IllegalStateException("Corrupted data: Ciphertext OOB")
            val cipherBytes = ByteArray(chunkLen)
            buf.get(cipherBytes)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            output.write(cipher.doFinal(cipherBytes))
        }

        return output.toByteArray()
    }

    /**
     * 旧的简单信封格式解密：[12B IV][ciphertext + 16B GCM tag]
     * 兼容 CryptoModule.encryptBytes 的输出
     */
    private fun decryptSimple(sessionKey: ByteArray, encrypted: ByteArray): ByteArray {
        require(encrypted.size > AES_GCM_NONCE_LEN) { "Invalid simple envelope: too short" }
        val iv = encrypted.copyOfRange(0, AES_GCM_NONCE_LEN)
        val cipherAndTag = encrypted.copyOfRange(AES_GCM_NONCE_LEN, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherAndTag)
    }

    /**
     * 根据文件头魔术字节检测音频格式，返回合适的文件扩展名
     */
    fun detectAudioExtension(data: ByteArray): String {
        if (data.size < 4) return ".bin"
        // WebM: 1A 45 DF A3
        if (data[0] == 0x1A.toByte() && data[1] == 0x45.toByte() && data[2] == 0xDF.toByte() && data[3] == 0xA3.toByte()) return ".webm"
        // M4A/MP4: xxxx 66 74 79 70 ("ftyp" at offset 4)
        if (data.size >= 8 && data[4] == 0x66.toByte() && data[5] == 0x74.toByte() && data[6] == 0x79.toByte() && data[7] == 0x70.toByte()) return ".m4a"
        // OGG: 4F 67 67 53
        if (data[0] == 0x4F.toByte() && data[1] == 0x67.toByte() && data[2] == 0x67.toByte() && data[3] == 0x53.toByte()) return ".ogg"
        // MP3: FF FB / FF F3 / FF F2 / ID3
        if ((data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) || (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte())) return ".mp3"
        return ".m4a" // 默认假设 M4A
    }

    private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"")
}
