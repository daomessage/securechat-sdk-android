package space.securechat.sdk.vanity

import space.securechat.sdk.http.*

/**
 * 🔒 VanityManager — 靓号系统（V1.3 规则引擎版）
 *
 * 后端 vanity/rules.go 实时评估任意 8 位数字的等级和价格，
 * 无需预填表。搜索 API 公开（无需 JWT）。
 *
 * 对标 sdk-typescript/src/vanity/manager.ts
 */
class VanityManager(private val http: HttpClient) {

    /** 搜索可用靓号（规则引擎实时生成候选，排除已售/已锁）*/
    suspend fun search(query: String = ""): List<VanityItem> =
        http.api.searchVanity(query).map {
            VanityItem(
                aliasId = it.alias_id,
                priceUsdt = it.price_usdt,
                tier = it.tier,
                isFeatured = it.is_featured
            )
        }

    /** 预留靓号（注册前，无需 JWT） */
    suspend fun reserve(aliasId: String): ReserveResult {
        val r = http.api.reserveVanity(VanityReserveRequest(aliasId))
        return ReserveResult(
            orderId = r.order_id,
            aliasId = r.alias_id,
            price = r.price,
            payTo = r.pay_to,
            expiredAt = r.expired_at
        )
    }

    /**
     * 购买靓号（规则引擎定价 + INSERT-on-demand，返回支付链接）
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

    /** 轮询订单状态 */
    suspend fun orderStatus(orderId: String): String {
        return http.api.getOrderStatus(orderId).status
    }

    /**
     * 绑定靓号（支付完成后 pay-worker 推送 payment_confirmed 事件，App 调用此接口）
     * 对标 TS SDK: client.vanity.bind(orderId)
     */
    suspend fun bind(orderId: String): String {
        return http.api.bindVanity(VanityBindRequest(orderId)).alias_id
    }
}

/** 靓号项（camelCase，App 层使用）
 *  tier: "top" | "premium" | "standard" */
data class VanityItem(
    val aliasId: String,
    val priceUsdt: Int,
    val tier: String,
    val isFeatured: Boolean
)

data class ReserveResult(
    val orderId: String,
    val aliasId: String,
    val price: Double,
    val payTo: String,
    val expiredAt: String
)

data class PurchaseResult(
    val orderId: String,
    val aliasId: String,
    val amountUsdt: Double,
    val paymentUrl: String,
    val expiredAt: Long
)

