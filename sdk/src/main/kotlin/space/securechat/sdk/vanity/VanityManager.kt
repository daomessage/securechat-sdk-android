package space.securechat.sdk.vanity

import space.securechat.sdk.http.*

/**
 * 🔒 VanityManager — 靓号系统
 *
 * 对标 sdk-typescript/src/vanity/manager.ts
 */
class VanityManager(private val http: HttpClient) {

    /** 搜索可用靓号 */
    suspend fun search(query: String): List<VanityItem> =
        http.api.searchVanity(query).items.map { VanityItem(it.alias_id, it.price_usdt, it.status) }

    /**
     * 购买靓号（CAS 乐观锁，返回支付链接）
     * 对标 TS SDK: client.vanity.purchase(aliasId)
     */
    suspend fun purchase(aliasId: String): PurchaseResult {
        val r = http.api.purchaseVanity(VanityPurchaseRequest(aliasId))
        return PurchaseResult(
            orderId = r.order_id,
            aliasId = r.alias_id,
            amountUsdt = r.amount_usdt,
            paymentUrl = r.payment_url,
            expiredAt = r.expired_at
        )
    }

    /**
     * 绑定靓号（支付完成后 pay-worker 推送 payment_confirmed 事件，App 调用此接口）
     * 对标 TS SDK: client.vanity.bind(orderId)
     */
    suspend fun bind(orderId: String): String {
        return http.api.bindVanity(VanityBindRequest(orderId)).alias_id
    }
}

data class VanityItem(val aliasId: String, val priceUsdt: Double, val status: String)
data class PurchaseResult(
    val orderId: String,
    val aliasId: String,
    val amountUsdt: Double,
    val paymentUrl: String,
    val expiredAt: Long
)
