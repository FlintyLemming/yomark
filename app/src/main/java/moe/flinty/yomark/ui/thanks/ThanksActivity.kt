package moe.flinty.yomark.ui.thanks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.billing.BillingRepository
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.ui.layoutEdgeToEdge
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 买成后的感谢页。
 *
 * 购买在 Play 的界面里进行，回到应用时购买框已经关了；没有这一页，用户只看到右上角的按钮悄悄没了，
 * 分不清买没买成。它是单独一个 Activity，压在来的那一页上：返回交给系统（预见式返回照样有跨页动画），
 * 点「好的」也是 finish 回去。由前台那一页来放（[showThanksWhenPurchased]），一笔购买只放一次。
 */
class ThanksActivity : YomarkActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(applicationContext)
        setContent {
            YomarkTheme(settings) {
                Surface { ThanksScreen(onDone = ::finish) }
            }
        }
    }
}

/**
 * 刚买成就放感谢页。首页和编辑器在 onCreate 里各调一次。
 *
 * 只在这一页在前台（RESUMED）时放：从 Play 的购买界面回来、它一收起就放，应用在后台时到账的等回到前台再放；
 * 首页压在编辑器下面时不抢着放。放了就清掉待谢，回到另一页不会再放一次。
 */
fun ComponentActivity.showThanksWhenPurchased(billing: BillingRepository) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            billing.thanksPending.filter { it }.collect {
                startActivity(Intent(this@showThanksWhenPurchased, ThanksActivity::class.java))
                billing.dismissThanks()
            }
        }
    }
}
