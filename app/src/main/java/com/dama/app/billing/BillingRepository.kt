package com.dama.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val PRODUCT_REMOVE_WATERMARK = "dama_remove_watermark"

/**
 * Play Billing 接入（spec §10）：一次性买断，非订阅，无服务端，无账号。
 * 付费解锁的**唯一**内容是去除品牌水印——免费版一项隐私能力都不缺。
 *
 * 购买流程是全应用**唯一**会发起网络请求的地方，且必须由用户主动触发
 * （§13 的「网络请求 = 0」指标明确排除了这条路径）。
 *
 * 行为契约（改 Play Billing 版本时这四条不能变）：
 * 缓存优先、断连不降级、只认 PRODUCT_REMOVE_WATERMARK、购买后必须 acknowledge。
 */
class BillingRepository(
    context: Context,
    private val store: PurchaseStore,
    private val scope: CoroutineScope,
) {

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { applyPurchases(purchases) }
        }
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(listener)
        // Billing 6.2 起 enablePendingPurchases 要显式说明商品类型；这里只有一次性商品。
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** 启动时调用：先用缓存点亮 UI，再尝试联网校验。 */
    fun start() {
        scope.launch { _isPro.value = store.isPro.first() }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { sync() }
                }
            }

            override fun onBillingServiceDisconnected() {
                // 断连不改变购买态：沿用缓存（PurchaseResolver 的 Unavailable 分支）
            }
        })
    }

    fun stop() = client.endConnection()

    private suspend fun sync() {
        val cached = store.isPro.first()
        val outcome = queryOwned()
        val resolved = PurchaseResolver.resolve(cached, outcome)
        if (resolved != cached) store.setPro(resolved)
        _isPro.value = resolved
    }

    private suspend fun queryOwned(): QueryOutcome = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                cont.resume(QueryOutcome.Unavailable)
            } else {
                val owned = purchases.any { it.isOurProduct() }
                scope.launch { purchases.forEach { acknowledgeIfNeeded(it) } }
                cont.resume(QueryOutcome.Ok(owned))
            }
        }
    }

    private fun Purchase.isOurProduct(): Boolean =
        products.contains(PRODUCT_REMOVE_WATERMARK) && purchaseState == Purchase.PurchaseState.PURCHASED

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        ) { /* 确认失败下次启动会重试；三天不确认 Play 会自动退款 */ }
    }

    private suspend fun applyPurchases(purchases: List<Purchase>) {
        if (purchases.none { it.isOurProduct() }) return
        purchases.forEach { acknowledgeIfNeeded(it) }
        store.setPro(true)
        _isPro.value = true
    }

    suspend fun launchPurchase(activity: Activity): Result<Unit> = runCatching {
        val details = queryProductDetails() ?: error("商品信息暂不可用")
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) error("无法启动购买流程")
    }

    private suspend fun queryProductDetails(): ProductDetails? = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_REMOVE_WATERMARK)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        // Billing 8.0 起回调第二个参数是 QueryProductDetailsResult，不再是 List<ProductDetails>。
        client.queryProductDetailsAsync(params) { _, result ->
            cont.resume(result.productDetailsList.firstOrNull())
        }
    }
}
