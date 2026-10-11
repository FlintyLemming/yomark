package moe.flinty.yomark.ui.thanks

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.billing.BillingRepository
import moe.flinty.yomark.billing.PurchaseStore
import moe.flinty.yomark.billing.isolatedPurchaseStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** 谁来放感谢页、什么时候放（见 [showThanksWhenPurchased]）。 */
@RunWith(RobolectricTestRunner::class)
class ThanksLauncherTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val store: PurchaseStore = isolatedPurchaseStore(context)

    /** 一个在前台的页面（首页或编辑器），接好了感谢页。 */
    private fun host(): ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.showThanksWhenPurchased(BillingRepository(context, store, activity.lifecycleScope, freeEdition = false))
        idle()
        return controller
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ComponentActivity.startedThanks(): Boolean =
        shadowOf(this).nextStartedActivity?.component?.className == ThanksActivity::class.java.name

    @Test fun `a fresh purchase opens the thank-you page once`() = runTest {
        val activity = host().get()

        store.grant(fresh = true)
        idle()
        assertThat(activity.startedThanks()).isTrue()

        // 放了就清掉：回到另一页、或者这一页再回到前台，都不会再放一次
        idle()
        assertThat(store.thanksPending.first()).isFalse()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    @Test fun `restoring an old purchase opens nothing`() = runTest {
        val activity = host().get()

        store.grant(fresh = false)
        idle()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    /** Play 的购买界面盖在上面时，这一页是 paused：等它收起、这一页回到前台再放。 */
    @Test fun `a page that is not in front waits until it is`() = runTest {
        val controller = host()
        controller.pause()

        store.grant(fresh = true)
        idle()
        assertThat(shadowOf(controller.get()).nextStartedActivity).isNull()

        controller.resume()
        idle()
        assertThat(controller.get().startedThanks()).isTrue()
    }
}
