package space.securechat.sdk.channels

import space.securechat.sdk.http.*

/**
 * 🔒 ChannelsManager — 频道系统
 *
 * 对标 sdk-typescript/src/channels/manager.ts
 */
class ChannelsManager(private val http: HttpClient) {

    /** 获取已订阅/创建的频道 */
    suspend fun getMine(): List<ChannelInfo> = http.api.getMyChannels().map { it.toModel() }

    /** 搜索频道 */
    suspend fun search(query: String): List<ChannelInfo> = http.api.searchChannels(query).map { it.toModel() }

    /** 创建频道 */
    suspend fun create(name: String, description: String, isPublic: Boolean = true): String {
        return http.api.createChannel(CreateChannelRequest(name, description, isPublic)).channel_id
    }

    /** 获取频道详情 */
    suspend fun getDetail(channelId: String): ChannelInfo = http.api.getChannelDetail(channelId).toModel()

    /** 获取帖子列表 */
    suspend fun getPosts(channelId: String): List<ChannelPost> = http.api.getChannelPosts(channelId)

    /** 发帖 */
    suspend fun post(channelId: String, content: String, type: String = "text"): String {
        return http.api.postToChannel(channelId, ChannelPostRequest(content, type)).post_id
    }

    /** 订阅频道 */
    suspend fun subscribe(channelId: String) = http.api.subscribeChannel(channelId)

    /** 取消订阅 */
    suspend fun unsubscribe(channelId: String) = http.api.unsubscribeChannel(channelId)

    /** 本地判断是否可发帖（仅 owner 可发） */
    fun canPost(info: ChannelInfo?): Boolean = info?.role == "owner"

    /** Owner 挂牌出售频道 */
    suspend fun listForSale(channelId: String, priceUsdt: Int) {
        http.api.listChannelForSale(ListChannelRequest(channelId, priceUsdt))
    }

    /** 买家购买频道（返回支付订单） */
    suspend fun buyChannel(channelId: String): ChannelTradeOrder {
        val r = http.api.buyChannel(channelId)
        return ChannelTradeOrder(
            orderId = r.order_id,
            priceUsdt = r.price_usdt,
            payTo = r.pay_to,
            expiredAt = r.expired_at
        )
    }

    /** 买家购买额外创建频道配额（返回支付订单） */
    suspend fun buyQuota(): ChannelTradeOrder {
        val r = http.api.buyQuota()
        return ChannelTradeOrder(
            orderId = r.order_id,
            priceUsdt = r.price_usdt,
            payTo = r.pay_to,
            expiredAt = r.expired_at
        )
    }

    private fun space.securechat.sdk.http.ChannelInfo.toModel() = ChannelInfo(
        id = id, name = name, description = description,
        role = role, isSubscribed = is_subscribed,
        forSale = for_sale, salePrice = sale_price
    )
}

data class ChannelInfo(
    val id: String,
    val name: String,
    val description: String,
    val role: String?,
    val isSubscribed: Boolean?,
    val forSale: Boolean? = null,
    val salePrice: Double? = null
)

data class ChannelTradeOrder(
    val orderId: String,
    val priceUsdt: Double,
    val payTo: String,
    val expiredAt: String
)
