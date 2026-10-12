package moe.flinty.yomark.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 连不上 Play（Robolectric 里没有 Play 商店）时，购买态只跟缓存走。 */
@RunWith(RobolectricTestRunner::class)
class BillingRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * 首页和编辑器各有一个 BillingRepository。在编辑器里买成、写进缓存，回到首页时首页也得是已购：
     * 以前首页只在启动时读一次缓存，购买入口还挂着，点开就是一个早就买过的购买框。
     */
    @Test fun `a purchase recorded by the other screen shows up here`() = runTest {
        val store = isolatedPurchaseStore(context)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = false)
        billing.start()
        testScheduler.advanceUntilIdle()
        assertThat(billing.isPro.value).isFalse()

        store.grant(fresh = true)
        testScheduler.advanceUntilIdle()
        assertThat(billing.isPro.value).isTrue()
    }

    @Test fun `dismissing the thank-you clears it for every screen`() = runTest {
        val store = isolatedPurchaseStore(context)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = false)
        store.grant(fresh = true)
        assertThat(billing.thanksPending.first()).isTrue()

        // 清除是丢出去的：放感谢页的那一页紧接着就 paused，不能跟着它的协程一起被取消。
        // 写盘不在测试调度器上，这里等它落地；清不掉就一直等到 runTest 超时
        billing.dismissThanks()
        store.thanksPending.first { !it }
    }

    @Test fun `the free edition never shows a thank-you`() = runTest {
        val store = isolatedPurchaseStore(context)
        // 就算缓存里留着内购版记下的待谢，开源版也没有购买可谢
        store.grant(fresh = true)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = true)
        assertThat(billing.thanksPending.first()).isFalse()
    }

    /** 恢复购买时问不到 Play：照实说问不到，也不能因此把缓存里的已购抹掉。 */
    @Test fun `restoring without Play keeps the cache and says Play is unreachable`() = runTest {
        val store = isolatedPurchaseStore(context)
        store.grant(fresh = false)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = false)

        assertThat(billing.restore()).isEqualTo(RestoreResult.Unavailable)
        assertThat(store.isPro.first()).isTrue()
    }

    @Test fun `the free edition has nothing to restore`() = runTest {
        val billing = BillingRepository(context, isolatedPurchaseStore(context), backgroundScope, freeEdition = true)
        assertThat(billing.restore()).isEqualTo(RestoreResult.Restored)
    }
}
