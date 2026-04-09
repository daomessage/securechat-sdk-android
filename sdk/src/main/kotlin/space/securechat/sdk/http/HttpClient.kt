package space.securechat.sdk.http

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 🔒 HttpClient — Retrofit 封装，自动注入 JWT
 *
 * AI 约束：
 *   - CORE_API_BASE 硬编码，不可传参覆盖（与 TS SDK 一致）
 *   - JWT Token 由 AuthManager 注入，HttpClient 不关心获取方式
 *
 * 对标 sdk-typescript/src/http.ts
 */
class HttpClient {

    companion object {
        const val CORE_API_BASE = "https://relay.daomessage.com"
    }

    private val tokenRef = AtomicReference<String?>()

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val okhttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = tokenRef.get()?.let { token ->
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } ?: chain.request()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("$CORE_API_BASE/")
        .client(okhttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val api: ApiService = retrofit.create(ApiService::class.java)

    fun setToken(token: String) { tokenRef.set(token) }
    fun getToken(): String? = tokenRef.get()
    fun clearToken() { tokenRef.set(null) }
}

// ─── Retrofit API 接口 ────────────────────────────────────────────────────

interface ApiService {

    // ── PoW + 注册 ──────────────────────────────────────────────────
    @POST("api/v1/pow/challenge")
    suspend fun getPowChallenge(): PowChallengeResponse

    @POST("api/v1/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    // ── 认证 ────────────────────────────────────────────────────────
    @POST("api/v1/auth/challenge")
    suspend fun getAuthChallenge(@Body body: AuthChallengeRequest): AuthChallengeResponse

    @POST("api/v1/auth/verify")
    suspend fun verifyAuth(@Body body: AuthVerifyRequest): AuthVerifyResponse

    // ── 好友 ────────────────────────────────────────────────────────
    @GET("api/v1/friends")
    suspend fun getFriends(): List<FriendProfile>

    @GET("api/v1/users/{aliasId}")
    suspend fun lookupUser(@Path("aliasId") aliasId: String): LookupUserResponse

    @POST("api/v1/friends/request")
    suspend fun sendFriendRequest(@Body body: FriendRequestBody): Unit

    @PUT("api/v1/friends/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") friendshipId: Long): AcceptFriendResponse

    // ── 会话 ────────────────────────────────────────────────────────
    @GET("api/v1/conversations/active")
    suspend fun getActiveConversations(): ActiveConversationsResponse

    // ── 媒体上传 ────────────────────────────────────────────────────
    @POST("api/v1/media/upload-url")
    suspend fun getUploadUrl(@Body body: UploadUrlRequest): UploadUrlResponse

    @GET("api/v1/media/download")
    suspend fun getDownloadUrl(@Query("key") key: String): DownloadUrlResponse

    // ── 频道 ────────────────────────────────────────────────────────
    @GET("api/v1/channels/mine")
    suspend fun getMyChannels(): List<ChannelInfo>

    @GET("api/v1/channels/search")
    suspend fun searchChannels(@Query("q") query: String): List<ChannelInfo>

    @POST("api/v1/channels")
    suspend fun createChannel(@Body body: CreateChannelRequest): CreateChannelResponse

    @GET("api/v1/channels/{id}")
    suspend fun getChannelDetail(@Path("id") id: String): ChannelInfo

    @GET("api/v1/channels/{id}/posts")
    suspend fun getChannelPosts(@Path("id") id: String): List<ChannelPost>

    @POST("api/v1/channels/{id}/posts")
    suspend fun postToChannel(@Path("id") id: String, @Body body: ChannelPostRequest): ChannelPostResponse

    @POST("api/v1/channels/{id}/subscribe")
    suspend fun subscribeChannel(@Path("id") id: String, @Body body: Map<String, String> = emptyMap()): Unit

    @DELETE("api/v1/channels/{id}/subscribe")
    suspend fun unsubscribeChannel(@Path("id") id: String): Unit

    // ── 靓号 ────────────────────────────────────────────────────────
    @GET("api/v1/vanity/search")
    suspend fun searchVanity(@Query("q") query: String): VanitySearchResponse

    @POST("api/v1/vanity/purchase")
    suspend fun purchaseVanity(@Body body: VanityPurchaseRequest): VanityPurchaseResponse

    @POST("api/v1/vanity/bind")
    suspend fun bindVanity(@Body body: VanityBindRequest): VanityBindResponse

    // ── Push ─────────────────────────────────────────────────────────
    @POST("api/v1/push/register")
    suspend fun registerPush(@Body body: PushRegisterRequest): Unit
}

// ─── 请求/响应数据类（snake_case，与服务端 JSON 一致）───────────────────

data class PowChallengeResponse(
    val challenge_string: String,
    val difficulty: Int,
    val expires_in: Int = 300
)

data class RegisterRequest(
    val ed25519_public_key: String,
    val x25519_public_key: String,
    val nickname: String,
    val pow_nonce: String = ""
)
data class RegisterResponse(val uuid: String, val alias_id: String)

data class AuthChallengeRequest(val user_uuid: String)
data class AuthChallengeResponse(val challenge: String)

data class AuthVerifyRequest(
    val user_uuid: String,
    val challenge: String,
    val signature: String   // Base64(Ed25519 signature)
)
data class AuthVerifyResponse(val token: String)

// FriendsResponse 已移除：服务端直接返回 JSON 数组
data class FriendProfile(
    val friendship_id: Long,
    val alias_id: String,
    val nickname: String,
    val status: String,         // pending | accepted | rejected
    val direction: String,      // sent | received
    val conversation_id: String? = null,
    val x25519_public_key: String = "",
    val created_at: String? = null
)

data class LookupUserResponse(
    val alias_id: String,
    val nickname: String,
    val x25519_public_key: String
)

data class FriendRequestBody(val to_alias_id: String)
data class AcceptFriendResponse(val conversation_id: String)

data class ActiveConversationsResponse(val conversations: List<ConversationSummary>)
data class ConversationSummary(val conv_id: String)

data class UploadUrlRequest(val content_type: String, val conversation_id: String)
data class UploadUrlResponse(val upload_url: String, val media_key: String)
data class DownloadUrlResponse(val download_url: String)

data class ChannelInfo(
    val id: String,
    val alias_id: String? = null,
    val name: String,
    val description: String,
    val role: String? = null,
    val is_subscribed: Boolean? = null,
    val for_sale: Boolean? = null,
    val sale_price: Double? = null
)
data class CreateChannelRequest(val name: String, val description: String, val is_public: Boolean)
data class CreateChannelResponse(val channel_id: String)
data class ChannelPost(
    val id: String,
    val type: String,
    val content: String,
    val created_at: String,
    val author_alias_id: String
)
data class ChannelPostRequest(val content: String, val type: String = "text")
data class ChannelPostResponse(val post_id: String)

data class VanitySearchResponse(val items: List<VanityItem>)
data class VanityItem(val alias_id: String, val price_usdt: Double, val status: String)
data class VanityPurchaseRequest(val alias_id: String)
data class VanityPurchaseResponse(
    val order_id: String,
    val alias_id: String,
    val amount_usdt: Double,
    val payment_url: String,
    val expired_at: Long
)
data class VanityBindRequest(val order_id: String)
data class VanityBindResponse(val alias_id: String)

data class PushRegisterRequest(
    val token: String,       // FCM token
    val platform: String = "android"
)
