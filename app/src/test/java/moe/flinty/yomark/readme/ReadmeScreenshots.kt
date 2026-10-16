package moe.flinty.yomark.readme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import moe.flinty.yomark.ui.EditorScreen
import moe.flinty.yomark.ui.EditorViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 样式对照图（docs/readme/styles.png，落地页的样式小图从它裁）用的截图。不是测试，平时跳过；要重拍时：
 *
 *     YOMARK_SHOTS_DIR=$PWD/docs/readme/shots ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshots*'
 *     node docs/readme/src/render.cjs styles
 *
 * 截图本身不进仓库，只有合成好的 styles.png 进。头图 hero.png 直接用样图的内容排版，不依赖这里的截图。
 *
 * 编辑器走的是真东西：随包的 PP-OCR 模型、默认规则表、真的 EditorViewModel。
 * 样图 readme/order-page.png 由 docs/readme/src/order-page.html 渲染，照着快递详情页排的版，人名、地址、号码全是编的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ReadmeScreenshots {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = System.getenv("YOMARK_SHOTS_DIR")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue("YOMARK_SHOTS_DIR 未设置", outDir != null)

    private object NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://readme/1")
    }

    private fun openSample(): EditorViewModel {
        val context = compose.activity.applicationContext
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink),
            engineProvider = {
                RedactionEngine(PaddleTextRecognizer(context), emptyList(), listOf(RuleClassifier(DefaultRuleSet.rules)), CandidateMerger())
            },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
        )
        compose.setContent {
            MaterialTheme { Surface { EditorScreen(vm = vm, onSettings = {}, onNavigateUp = {}) } }
        }
        val sample = File(context.cacheDir, "order-page.png")
        sample.outputStream().use { out ->
            javaClass.getResourceAsStream("/readme/order-page.png")!!.use { it.copyTo(out) }
        }
        compose.mainClock.autoAdvance = false
        vm.onImageChosen(sample.toUri())
        val deadline = System.currentTimeMillis() + 120_000
        while (true) {
            val s = vm.state.value
            if (s.image != null && !s.analyzing && s.plan.items.isNotEmpty()) break
            check(System.currentTimeMillis() < deadline) {
                "识别没跑完：image=${s.image != null} loading=${s.loading} analyzing=${s.analyzing} " +
                    "items=${s.plan.items.size} message=${s.message}"
            }
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        println("识别结果：" + vm.state.value.plan.items.joinToString { "${it.kind}/${it.source}" })
        settle()
        return vm
    }

    /** 落码那一遍光走完。 */
    private fun settle() {
        repeat(6_000 / 16) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** captureToImage 在 Robolectric 上等不到重绘，直接把整个窗口画进一张位图。 */
    private fun shoot(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        outDir!!.mkdirs()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun editor() {
        openSample()
        shoot("editor")
    }

    @Test fun styles() {
        val vm = openSample()
        // 文本不是盖在码上的样子：码换成文本就没了，不拍
        MaskStyle.entries.filter { it != MaskStyle.SOLID && it != MaskStyle.TEXT }.forEach { style ->
            // 对照图要整张图都换成这种：马克笔、画笔换样式时已有的码不跟着变，直接改 plan
            compose.runOnUiThread {
                vm.setStyle(style)
                val look = vm.state.value.brush
                val plan = vm.state.value.plan
                vm.replacePlanForTest(plan.copy(items = plan.items.map { it.copy(look = look) }))
            }
            settle()
            shoot("editor-${style.name.lowercase()}")
        }
    }
}
