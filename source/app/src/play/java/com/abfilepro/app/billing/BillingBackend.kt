package com.abfilepro.app.billing

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * P28 (Play channel): Google Play Billing. The entitlement comes from what Play says is owned; a
 * failed or offline query never removes an existing entitlement, only a successful one that no
 * longer lists the purchase (cancelled or refunded) does.
 */
object BillingBackend {
    const val available = true

    var offers by mutableStateOf<List<ProOffer>>(emptyList())
        private set
    /** Short, person-readable state: empty when all is well. */
    var message by mutableStateOf("")
        private set

    private var client: BillingClient? = null
    private lateinit var appContext: Context
    private val details = mutableMapOf<String, ProductDetails>()

    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        client = BillingClient.newBuilder(appContext)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases.orEmpty()) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        connect()
    }

    private fun connect() {
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    message = ""
                    loadProducts()
                    restore()
                } else {
                    message = "unavailable"
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun loadProducts() {
        val found = mutableListOf<ProOffer>()
        fun query(type: String, ids: List<String>) {
            val products = ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() }
            client?.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, queryResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryResult.productDetailsList.forEach { d ->
                        details[d.productId] = d
                        offerOf(d)?.let { found += it }
                    }
                }
                offers = ProProducts.ALL.mapNotNull { id -> found.firstOrNull { it.productId == id } }
            }
        }
        query(BillingClient.ProductType.SUBS, ProProducts.SUBSCRIPTIONS)
        query(BillingClient.ProductType.INAPP, ProProducts.ONE_TIME)
    }

    private fun offerOf(d: ProductDetails): ProOffer? {
        d.subscriptionOfferDetails?.firstOrNull()?.let { offer ->
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return null
            return ProOffer(d.productId, phase.formattedPrice, subscription = true, period = phase.billingPeriod)
        }
        d.oneTimePurchaseOfferDetails?.let { return ProOffer(d.productId, it.formattedPrice, subscription = false) }
        return null
    }

    fun launch(activity: Activity, productId: String) {
        val d = details[productId] ?: run { message = "unavailable"; return }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).apply {
            d.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
        }.build()
        val result = client?.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build())
        if (result != null && result.responseCode != BillingClient.BillingResponseCode.OK) message = "failed"
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> { message = ""; acknowledge(purchases); restore() }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> message = "failed"
        }
    }

    private fun acknowledge(purchases: List<Purchase>) {
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase ->
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
        }
    }

    /** Asks Play what is owned and applies it (also the "restore purchases" button). */
    fun restore() {
        val owned = mutableSetOf<String>()
        var failed = false
        fun query(type: String, done: () -> Unit) {
            client?.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    acknowledge(purchases)
                    purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { owned += it.products }
                } else {
                    failed = true
                }
                done()
            } ?: run { failed = true; done() }
        }
        query(BillingClient.ProductType.SUBS) {
            query(BillingClient.ProductType.INAPP) {
                if (!failed) ProState.apply(appContext, ProPolicy.entitlementFor(owned))
            }
        }
    }
}
