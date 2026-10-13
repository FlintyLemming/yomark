package moe.flinty.yomark.ui

import android.app.TaskStackBuilder
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.IntentCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.flinty.yomark.billing.BillingRepository
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.billing.PurchaseStore
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.debug.sharedTraceLog
import moe.flinty.yomark.engine.buildEngine
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.FileProviderSink
import moe.flinty.yomark.export.MediaStoreSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.ui.components.restorePurchase
import moe.flinty.yomark.ui.home.HomeActivity
import moe.flinty.yomark.ui.settings.SettingsActivity
import moe.flinty.yomark.ui.thanks.showThanksWhenPurchased
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.flinty.yomark.R

/**
 * 编辑器：从首页选图进来（intent.data / clipData，可多选），或作为分享目标从相册进来（ACTION_SEND / SEND_MULTIPLE），
 * 或者别的应用来要图（见 PickImageActivity），这时从不导出的入口 EditorForCaller 进来，导出的图交回给它。
 *
 * 返回就是 finish：回到首页，或回到分享它的那个应用。系统返回不拦（不常驻 BackHandler），
 * 预见式返回的跨 Activity 动画才放得出来。例外是用途水印面板、样式面板开着，或吸管正等着取色的时候：
 * 那时的 BackHandler 只随它们存在，返回先收起它们。左上角的向上按钮按 Android 的导航约定走：
 * 从别的应用分享进来时，向上回到本应用自己的首页，而不是回到别人的应用里。
 * 别的应用来要图时例外，向上和返回一样，什么也不交，退回它那里。
 */
class EditorActivity : YomarkActivity() {

    private val settings by lazy { SettingsStore(applicationContext) }

    /** 别的应用来要图：导出的图交回给它，不进相册。只认本应用自己才打得开的那个入口（见 AndroidManifest）。 */
    private val forCaller by lazy { componentName.className == CALLER_ENTRY }

    private val vm: EditorViewModel by viewModels {
        val app = applicationContext
        viewModelFactory {
            initializer {
                EditorViewModel(
                    // 自己的编辑器可能还开着：副本另放一处，各清各的
                    intake = if (forCaller) ImageIntake(app, CALLER_INTAKE_DIR) else ImageIntake(app),
                    exporter = Exporter(
                        RendererRegistry.default(),
                        WatermarkDrawer(),
                        if (forCaller) FileProviderSink(app) else MediaStoreSink(app),
                    ),
                    engineProvider = { cfg -> buildEngine(app, cfg) },
                    settings = SettingsStore(app),
                    savedState = createSavedStateHandle(),
                    traceLog = sharedTraceLog(app),
                    // 从 Activity 取：Android 12 及以下应用内选的语言只换在 Activity 上（见 AppLocale）
                    defaultPurposeText = getString(R.string.purpose_default),
                    forCaller = forCaller,
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
        // 买成了（或者联网校验发现早就买过）：购买框跟着关掉，同首页
        if (!Edition.isFree) lifecycleScope.launch { billing.isPro.filter { it }.collect { vm.dismissPaywall() } }
        showThanksWhenPurchased(billing)

        setContent {
            YomarkTheme(settings) {
                Surface {
                    EditorScreen(
                        vm = vm,
                        onBuyClicked = {
                            lifecycleScope.launch { billing.launchPurchase(this@EditorActivity) }
                        },
                        onRestoreClicked = { restorePurchase(billing) },
                        onUnlockFree = billing::unlockFree,
                        onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onNavigateUp = if (forCaller) ::finish else ::navigateUpToHome,
                        onHandOver = ::handOver,
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

    /** 别的应用来要图，图导出好了：交回给它（读权限随结果授予），编辑器关掉。 */
    private fun handOver(images: List<Uri>) {
        if (images.isEmpty()) return
        setResult(
            RESULT_OK,
            Intent().setData(images.first())
                .apply { clipData = clipOf(images) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }

    /**
     * 批量（spec §7.6）有三个入口：相册分享来的 ACTION_SEND_MULTIPLE，首页 Picker 多选，和别的应用来要多张图。
     * 后两种选的图全放在 clipData 里（第一张同时放 intent.data）。
     */
    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> intent.clipData
            ?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(intent.data)
    }

    companion object {
        /** AndroidManifest 里那个不导出的入口（activity-alias）。 */
        private const val CALLER_ENTRY = "moe.flinty.yomark.ui.EditorForCaller"

        private const val CALLER_INTAKE_DIR = "intake-caller"

        /**
         * 选好图后打开编辑器。多张时读权限要随 clipData 一起授予，光放 data 只覆盖第一张。
         *
         * @param forCaller 别的应用来要图（见 PickImageActivity）：导出的图交回给它，不进相册
         */
        fun intent(context: Context, images: List<Uri>, forCaller: Boolean = false): Intent =
            Intent()
                .setClassName(context, if (forCaller) CALLER_ENTRY else EditorActivity::class.java.name)
                .setData(images.first())
                .apply { clipData = clipOf(images) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        private fun clipOf(images: List<Uri>): ClipData =
            ClipData.newRawUri(null, images.first())
                .also { clip -> images.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    }
}
