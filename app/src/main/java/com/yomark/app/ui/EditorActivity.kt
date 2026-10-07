package com.yomark.app.ui

import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.IntentCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yomark.app.billing.BillingRepository
import com.yomark.app.billing.PurchaseStore
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.data.SettingsStore
import com.yomark.app.engine.buildEngine
import com.yomark.app.export.Exporter
import com.yomark.app.export.MediaStoreSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import com.yomark.app.ui.home.HomeActivity
import com.yomark.app.ui.settings.SettingsActivity
import com.yomark.app.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 编辑器：从首页选图进来（intent.data），或作为分享目标从相册进来（ACTION_SEND / SEND_MULTIPLE）。
 *
 * 返回就是 finish：回到首页，或回到分享它的那个应用。系统返回不拦（不常驻 BackHandler），
 * 预见式返回的跨 Activity 动画才放得出来。唯一的例外是用途水印面板开着的时候：
 * 那时的 BackHandler 只随面板存在，返回先收面板。左上角的向上按钮按 Android 的导航约定走：
 * 从别的应用分享进来时，向上回到本应用自己的首页，而不是回到别人的应用里。
 */
class EditorActivity : ComponentActivity() {

    private val settings by lazy { SettingsStore(applicationContext) }

    private val vm: EditorViewModel by viewModels {
        val app = applicationContext
        viewModelFactory {
            initializer {
                EditorViewModel(
                    intake = ImageIntake(app),
                    exporter = Exporter(
                        RendererRegistry.default(),
                        WatermarkDrawer(),
                        MediaStoreSink(app),
                    ),
                    engineProvider = { cfg -> buildEngine(app, cfg) },
                    settings = SettingsStore(app),
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }

    private val billing by lazy {
        BillingRepository(applicationContext, PurchaseStore(applicationContext), lifecycleScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 先用缓存点亮购买态，再尝试联网校验。只在 Play 商店进程里发生（spec §10 / §13）。
        billing.start()
        vm.observePro(billing.isPro)

        setContent {
            YomarkTheme(settings) {
                Surface {
                    EditorScreen(
                        vm = vm,
                        onBuyClicked = {
                            lifecycleScope.launch { billing.launchPurchase(this@EditorActivity) }
                        },
                        onRedeem = billing::redeem,
                        onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onNavigateUp = ::navigateUpToHome,
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            val uris = incomingUris(intent)
            // 没有图就没有可编辑的东西。正常路径到不了这里：首页只在选好图后才启动编辑器。
            if (uris.isEmpty()) finish() else vm.onImagesChosen(uris)
        } else {
            // 重建路径：状态由 SavedStateHandle 恢复（spec §15 第 2 条）。
            // 等恢复落地再判断——恢复不出东西（副本被清掉了）才退回上一层，
            // 否则会在图正要回来的那一瞬间把编辑器关掉。
            lifecycleScope.launch {
                vm.state.first { !it.restoring }
                if (vm.state.value.image == null) finish()
            }
        }
    }

    override fun onDestroy() {
        billing.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uris = incomingUris(intent)
        if (uris.isNotEmpty()) vm.onImagesChosen(uris)
    }

    /**
     * 向上：在自己的任务里（从首页进来）就是回到下面那一层首页；
     * 在别的应用的任务里（从相册分享进来），新起本应用的任务、落在首页上。
     *
     * 不用 navigateUpTo：首页是 standard 启动模式，它会把底下那个首页也销毁重建。
     */
    private fun navigateUpToHome() {
        val home = Intent(this, HomeActivity::class.java)
        if (isTaskRoot || shouldUpRecreateTask(home)) {
            TaskStackBuilder.create(this).addNextIntent(home).startActivities()
        }
        finish()
    }

    /**
     * 批量按 spec §7.6 只从 ACTION_SEND_MULTIPLE 进——用户已经在相册里选好了，不该再选一次。
     * 首页的 Picker 一律是单选，选中的图放在 intent.data 里。
     */
    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> listOfNotNull(intent.data)
    }

    companion object {
        /** 首页选好一张图后打开编辑器。 */
        fun intent(context: Context, image: Uri): Intent =
            Intent(context, EditorActivity::class.java)
                .setData(image)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
