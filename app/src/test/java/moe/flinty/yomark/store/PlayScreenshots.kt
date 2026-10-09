package moe.flinty.yomark.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToKey
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.rules.RuleClassifier
import moe.flinty.yomark.ui.EditorScreen
import moe.flinty.yomark.ui.EditorUiState
import moe.flinty.yomark.ui.EditorViewModel
import moe.flinty.yomark.ui.settings.TextSettingsScreen
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.roundToInt

/**
 * Google Play 商品详情的手机截图（docs/play/screenshots/）用的编辑器截图。不是测试，平时跳过；要重拍时：
 *
 *     YOMARK_PLAY_SHOTS_DIR=$PWD/docs/play/shots ./gradlew -Pyomark.freeEdition=false \
 *         :app:testDebugUnitTest --tests '*PlayScreenshots*'
 *     NODE_PATH="$(npm root -g)" node docs/play/src/render.cjs screenshots
 *     python3 docs/play/src/finalize.py
 *
 * 带 -Pyomark.freeEdition=false 拍的是上架 Play 的内购版。截图本身不进仓库，只有配好文字的成品进。
 *
 * 和 ReadmeScreenshots 一样走真东西：随包的 PP-OCR 模型、出厂规则、真的 EditorViewModel，
 * 出厂的文字识别就是 PP-OCR，所以图上的框就是真机上会出的框。人脸和条码要 ML Kit 的原生库，JVM 上跑不了，
 * 两张样图里也没有。样图是 readme/order-page.png 与 play/chat-page.png，人名、地址、号码全是编的。
 *
 * 窗口取 412×860 dp：常见手机去掉状态栏和导航栏后的大小。Robolectric 没有系统栏，截出来就是应用自己画的部分。
 * 地区取 zh-CN：PhoneRule 按系统地区解析没写国家码的号码，Robolectric 默认的 en-US 认不出 138 开头的手机号。
 * 主题色取「蓝」，与天蓝的打码块一个色系；应用里出厂是跟随壁纸。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
class PlayScreenshots {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = System.getenv("YOMARK_PLAY_SHOTS_DIR")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue("YOMARK_PLAY_SHOTS_DIR 未设置", outDir != null)

    private val context get() = compose.activity.applicationContext

    private object NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://play/1")
    }

    /** 识别在闸门打开之前一直挂着：识别中的样子与落码的时间点就都由测试定，不看 JVM 上跑模型要多久。 */
    private class GatedRecognizer(private val delegate: TextRecognizer) : TextRecognizer {
        val gate = CompletableDeferred<Unit>()
        override val id get() = delegate.id
        override suspend fun recognize(image: SourceImage): List<TextLine> {
            gate.await()
            return delegate.recognize(image)
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { YomarkTheme(ThemeColor.BLUE) { Surface { content() } } }
    }

    private fun editor(recognizer: TextRecognizer = PaddleTextRecognizer(context)): EditorViewModel {
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink),
            // 与 buildEngine 同一套规则，只是不带 ML Kit 的人脸、条码
            engineProvider = { config ->
                RedactionEngine(recognizer, emptyList(), listOf(RuleClassifier(RuleCatalog.rulesFor(config))), CandidateMerger())
            },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
        )
        show { EditorScreen(vm = vm, onSettings = {}, onNavigateUp = {}) }
        compose.mainClock.autoAdvance = false
        return vm
    }

    /** 把测试资源里的样图拷进缓存目录，像 Photo Picker 给的 Uri 一样交给编辑器。 */
    private fun sample(resource: String, name: String = resource.substringAfterLast('/')): Uri {
        val file = File(context.cacheDir, name)
        file.outputStream().use { out -> javaClass.getResourceAsStream(resource)!!.use { it.copyTo(out) } }
        return file.toUri()
    }

    /** 等到 [done]。[frames] 为 false 时只处理主线程消息、不推动画时钟，画面停在原处。 */
    private fun waitFor(vm: EditorViewModel, frames: Boolean = true, done: (EditorUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 120_000
        while (!done(vm.state.value)) {
            val s = vm.state.value
            check(System.currentTimeMillis() < deadline) {
                "等不到：image=${s.image != null} analyzing=${s.analyzing} items=${s.plan.items.size} message=${s.message}"
            }
            if (frames) compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** 选图、等识别完、等落码那一遍光走完。 */
    private fun open(vararg samples: Uri): EditorViewModel {
        val vm = editor()
        if (samples.size == 1) vm.onImageChosen(samples.single()) else vm.onImagesChosen(samples.toList())
        waitFor(vm) { it.image != null && !it.analyzing && it.plan.items.isNotEmpty() }
        println("识别结果：" + vm.state.value.plan.items.joinToString { "${it.kind}/${it.state}" })
        advance(SETTLE_MS)
        return vm
    }

    private fun advance(ms: Long) {
        repeat((ms / FRAME_MS).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /**
     * 拍一张。Robolectric 推帧只走组合与布局，不走绘制；画布却是在绘制里才按自己的尺寸摆好 viewport，
     * 「删除」工具条又是在布局里读 viewport 定位置的。所以先画一遍让 viewport 就位，推一帧让工具条按它重摆，再真拍。
     * 真机上每一帧都画，没有这个问题。
     */
    private fun shoot(name: String) {
        render()
        compose.mainClock.advanceTimeByFrame()
        val bmp = render()
        outDir!!.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * captureToImage 在 Robolectric 上等不到重绘，直接把整个窗口画进一张位图。
     *
     * AlertDialog 是另一个窗口，activity 的 decorView 里没有它：照系统的样子先压一层暗幕，再把它画在正中。
     * 它的大小照 ViewRootImpl 量 WRAP_CONTENT 窗口的办法重新量：先按 config_prefDialogWidth 试，
     * 放不下才放宽到整屏；对话框主题的最小宽度由 DecorView 自己撑开。Robolectric 直接按整屏宽排，与真机不符。
     */
    private fun render(): Bitmap {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            val canvas = Canvas(bmp)
            decor.draw(canvas)
            ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { dialog ->
                val window = dialog.window ?: return@forEach
                val attrs = window.attributes
                if (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((attrs.dimAmount * 255).roundToInt(), 0, 0, 0))
                }
                val view = window.decorView
                measureLikeViewRoot(view, attrs, bmp.width, bmp.height)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                canvas.withTranslation((bmp.width - view.width) / 2f, (bmp.height - view.height) / 2f) { view.draw(this) }
            }
        }
        return bmp
    }

    private fun measureLikeViewRoot(view: View, attrs: WindowManager.LayoutParams, width: Int, height: Int) {
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        val res = context.resources
        val prefId = res.getIdentifier("config_prefDialogWidth", "dimen", "android")
        val base = if (prefId != 0) res.getDimensionPixelSize(prefId) else 0
        if (attrs.width == WindowManager.LayoutParams.WRAP_CONTENT && base in 1 until width) {
            view.measure(View.MeasureSpec.makeMeasureSpec(base, View.MeasureSpec.AT_MOST), heightSpec)
            if (view.measuredWidthAndState and View.MEASURED_STATE_TOO_SMALL == 0) return
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), heightSpec)
    }

    /** 快递详情页识别完：有把握的打上码，拿不准的圈出来。 */
    @Test fun auto() {
        open(sample("/readme/order-page.png"))
        shoot("auto")
    }

    /**
     * 聊天记录识别完的那一下：光自上而下扫过，上半截已经落了码，光底下的正在盖实，下半截还压着暗幕。
     * 识别由闸门放行，放行时光走到哪儿、之后拍哪一帧都是定的，重拍出来是同一张。
     */
    @Test fun reveal() {
        val recognizer = GatedRecognizer(PaddleTextRecognizer(context))
        val vm = editor(recognizer)
        vm.onImageChosen(sample("/play/chat-page.png"))
        waitFor(vm, frames = false) { it.image != null && it.analyzing }
        advance(SCAN_AT_MS)
        recognizer.gate.complete(Unit)
        waitFor(vm, frames = false) { !it.analyzing && it.plan.items.isNotEmpty() }
        advance(REVEAL_AT_MS)
        shoot("reveal")
    }

    /**
     * 换成表情、整张图「应用到全部」，再把调节面板收起来：面板开着时占去四成高度，图就小得看不清码了。
     */
    @Test fun styles() {
        val vm = open(sample("/play/chat-page.png"))
        compose.runOnUiThread {
            vm.onStyleChipClick(MaskStyle.EMOJI)
            vm.applyLookToAll()
            vm.dismissStylePanel()
        }
        advance(1_000)
        shoot("styles")
    }

    /** 商品那一行规则不管，自己拖一个框补上：刚画完的框是选中的，四角有手柄，上面是「删除」。 */
    @Test fun manual() {
        val vm = open(sample("/readme/order-page.png"))
        // 样图里商品那一行（缩略图 + 品名 + 规格），量自 order-page.png 的像素：缩略图 (80, 1208)–(209, 1337)
        compose.runOnUiThread { vm.onManualBox(Quad.fromRect(RectF(68f, 1198f, 612f, 1348f))) }
        advance(1_000)
        shoot("manual")
    }

    /** 还有圈出没打码的就点导出：先列出来再问一次。 */
    @Test fun reminder() {
        val vm = open(sample("/play/chat-page.png"))
        compose.runOnUiThread { vm.requestExport() }
        advance(1_000)
        shoot("reminder")
    }

    /** 用途水印：整张图铺一行字。调节面板点「完成」收起，顶栏的水印按钮着主题色。 */
    @Test fun purpose() {
        val vm = open(sample("/readme/order-page.png"))
        compose.runOnUiThread {
            vm.setPurposeText("仅供快递理赔使用")
            vm.showPurposeSheet()
            vm.dismissPurposeSheet()
        }
        advance(1_000)
        shoot("purpose")
    }

    /** 从相册一次分享三张进来：底栏是「1 / 3」和「下一张」。 */
    @Test fun batch() {
        open(
            sample("/play/chat-page.png"),
            sample("/readme/order-page.png"),
            sample("/readme/order-page.png", "order-page-2.png"),
        )
        shoot("batch")
    }

    /**
     * 设置 › 文字：每一类一行，小字是它眼下怎么处理，点进去能改成打码、仅圈出或关闭。出厂配置，
     * 列表滚到各类开头，默认打码的几类（人名、电话、地址……）和默认只圈出的几类（快递单号、日期时间、网址……）同在一屏。
     */
    @Test fun rules() {
        show { TextSettingsScreen(RecognitionConfig(), onChange = {}, onOpenEngine = {}, onOpenRule = {}, onNavigateUp = {}) }
        compose.waitForIdle()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("name")
        compose.waitForIdle()
        shoot("rules")
    }

    private companion object {
        const val FRAME_MS = 16L

        /** 落码那一遍光走完，再多留一点。 */
        const val SETTLE_MS = 6_000L

        /** 识别中的光走到这一趟的哪儿再放行识别（ScanEffect.PASS_MS 一趟 2 秒）。 */
        const val SCAN_AT_MS = 1_200L

        /** 结果到达后过多久拍：光刚走过取件码那一行，那块码还在盖实（挑自 1.2–1.6 秒每 0.1 秒一张）。 */
        const val REVEAL_AT_MS = 1_400L
    }
}
