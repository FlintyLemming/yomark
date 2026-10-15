package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「按住对比」（手机竖着拿时是底栏胶囊旁边的圆钮）：按住时画布只剩打码前的原图（打码块都不画），松手回到打码后。
 * 什么都还没打码时没有可比的，按钮灰掉。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下读不到像素
@Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
class EditorCompareTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity.applicationContext

    private object NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://compare/1")
    }

    /** 什么都不产出的引擎：框由测试自己放进 plan。 */
    private fun emptyEngine() = RedactionEngine(
        object : TextRecognizer {
            override val id = "none"
            override suspend fun recognize(image: SourceImage) = emptyList<TextLine>()
        },
        emptyList(),
        listOf(object : SensitivityClassifier {
            override val id = "none"
            override suspend fun isAvailable() = true
            override suspend fun classify(lines: List<TextLine>) = emptyList<Candidate>()
        }),
        CandidateMerger(),
    )

    private fun item(id: String, top: Float, kind: SensitiveKind) =
        MaskItem(id, Quad.fromRect(RectF(20f, top, 300f, top + 30f)), kind, DetectorSource.RULE)

    /** 默认样式是天蓝色块。 */
    private val phone = item("phone", 40f, SensitiveKind.PHONE)

    /** 打开一张白图，识别（什么也没有）跑完后换上 [plan]，等动效走完。 */
    private fun open(plan: MaskPlan): EditorViewModel {
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink),
            engineProvider = { emptyEngine() },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
        )
        compose.setContent { MaterialTheme { Surface { EditorScreen(vm = vm, onSettings = {}, onNavigateUp = {}) } } }
        // 识别中的扫光每帧都在走，自动推帧等不到空闲：手动推
        compose.mainClock.autoAdvance = false
        vm.onImageChosen(whiteImage())
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.state.value.image == null || vm.state.value.analyzing) {
            check(System.currentTimeMillis() < deadline) { "图没载进来：${vm.state.value}" }
            frames(1)
            Thread.sleep(10)
        }
        compose.runOnUiThread { vm.replacePlanForTest(plan) }
        frames(SETTLE_FRAMES)
        return vm
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun whiteImage(): Uri {
        val file = File(context.cacheDir, "compare.png")
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toUri()
    }

    /** captureToImage 在 Robolectric 上等不到重绘，直接把整个窗口画进一张位图。 */
    private fun window(): Bitmap {
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        return bmp
    }

    private fun Bitmap.count(color: Int): Int {
        val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        return pixels.count { it == color }
    }

    private val compareButton get() = compose.onNodeWithContentDescription("按住对比")

    @Test fun `holding compare shows the image before masking, letting go brings the masks back`() {
        open(MaskPlan(listOf(phone)))
        val masked = window()

        compareButton.assertIsEnabled().performTouchInput { down(center) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("原图").assertExists()
        val original = window()
        // 打码块（天蓝）没了：样式栏上的小样也是天蓝，只看少了多少——至少是图上那一块
        assertThat(masked.count(MaskOptions.SKY_BLUE) - original.count(MaskOptions.SKY_BLUE)).isGreaterThan(10_000)

        compareButton.performTouchInput { up() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("原图").assertDoesNotExist()
        val back = window()
        assertThat(back.count(MaskOptions.SKY_BLUE)).isEqualTo(masked.count(MaskOptions.SKY_BLUE))
    }

    @Test fun `nothing masked yet, nothing to compare`() {
        open(MaskPlan.empty())
        compareButton.assertIsNotEnabled()
    }

    @Test fun `a purpose watermark alone is worth comparing`() {
        val vm = open(MaskPlan.empty())
        compose.runOnUiThread { vm.setPurposeText("仅供测试使用"); vm.setPurposeOn(true) }
        frames(SETTLE_FRAMES)
        compareButton.assertIsEnabled()
    }

    private companion object {
        /** 落码那一遍走完。 */
        const val SETTLE_FRAMES = 400
    }
}
