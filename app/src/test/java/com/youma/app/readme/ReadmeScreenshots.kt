package com.youma.app.readme

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
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.model.MaskStyle
import com.youma.app.data.isolatedSettingsStore
import com.youma.app.engine.CandidateMerger
import com.youma.app.engine.RedactionEngine
import com.youma.app.engine.ppocr.PaddleTextRecognizer
import com.youma.app.export.Exporter
import com.youma.app.export.ImageSink
import com.youma.app.export.WatermarkDrawer
import com.youma.app.render.RendererRegistry
import com.youma.app.rules.DefaultRuleSet
import com.youma.app.rules.RuleClassifier
import com.youma.app.ui.EditorScreen
import com.youma.app.ui.EditorViewModel
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
 * README 里的截图。不是测试，平时跳过；要重拍时：
 *
 *     YOUMA_SHOTS_DIR=$PWD/docs/readme/shots ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshots*'
 *     node docs/readme/src/render.cjs hero && node docs/readme/src/render.cjs styles
 *
 * 截图本身不进仓库，只有合成好的 docs/readme/hero.png 和 styles.png 进。
 *
 * 编辑器走的是真东西：随包的 PP-OCR 模型、默认规则表、真的 EditorViewModel。
 * 样图 readme/order-page.png 由 docs/readme/src/order-page.html 渲染，照着快递详情页排的版，人名、地址、号码全是编的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ReadmeScreenshots {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = System.getenv("YOUMA_SHOTS_DIR")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue("YOUMA_SHOTS_DIR 未设置", outDir != null)

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
        println("识别结果：" + vm.state.value.plan.items.joinToString { "${it.kind}/${it.state}" })
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
        MaskStyle.entries.filter { it != MaskStyle.SOLID }.forEach { style ->
            compose.runOnUiThread { vm.setStyle(style) }
            settle()
            shoot("editor-${style.name.lowercase()}")
        }
    }

    @Test fun purposeWatermark() {
        val vm = openSample()
        compose.runOnUiThread {
            vm.showPurposeSheet()
            vm.setPurposeText("仅供快递取件使用")
        }
        settle()
        shoot("editor-purpose")
    }
}
