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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
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
 * 编辑器在哪些屏幕上换成宽屏版式（宽屏增补设计）：手机没有框列表；平板、阔折叠在画布下面或右边多出列表，
 * 点列表的一行就切换那一块。哪些尺寸算宽屏的细账在 EditorLayoutTest，列表本身在 MaskItemListTest。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenLayoutTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = compose.activity.applicationContext

    private object NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://layout/1")
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

    private fun item(id: String, top: Float, kind: SensitiveKind, state: MaskState) =
        MaskItem(id, Quad.fromRect(RectF(20f, top, 300f, top + 30f)), kind, DetectorSource.RULE, state)

    private val phone = item("phone", 40f, SensitiveKind.PHONE, MaskState.MASKED)
    private val url = item("url", 120f, SensitiveKind.URL, MaskState.OUTLINED)

    /** 打开一张白图，识别（什么也没有）跑完后把 [phone]、[url] 两块放进去，等动效走完。 */
    private fun open(): EditorViewModel {
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
        compose.runOnUiThread { vm.replacePlanForTest(MaskPlan(listOf(phone, url))) }
        frames(SETTLE_FRAMES)
        return vm
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun whiteImage(): Uri {
        val file = File(context.cacheDir, "layout.png")
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toUri()
    }

    private fun listBounds() = compose.onNodeWithText("框出 2 处").getUnclippedBoundsInRoot()
    private fun screenBounds() = compose.onRoot().getUnclippedBoundsInRoot()

    @Config(qualifiers = "w412dp-h860dp-xxhdpi")
    @Test fun `a phone keeps the style bar and has no list`() {
        open()
        compose.onNodeWithText("色块").assertExists()
        compose.onNodeWithText("导出").assertExists()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
    }

    @Config(qualifiers = "sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `a phone on its side has no list either`() {
        open()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
    }

    @Config(qualifiers = "sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `an upright tablet lists the boxes below the image`() {
        open()
        compose.onNodeWithText("导出").assertExists()
        compose.onNodeWithText("1 处未打码").assertExists()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.top).isGreaterThan(screen.bottom * 0.6f)
        // 样式圆钮在列表左边
        assertThat(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().right).isLessThan(list.left)
    }

    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-xhdpi")
    @Test fun `a tablet on its side lists the boxes right of the image, below the styles`() {
        open()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.left).isGreaterThan(screen.right * 0.6f)
        assertThat(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().bottom).isLessThan(list.top)
    }

    @Config(qualifiers = "sw528dp-w528dp-h848dp-port-xhdpi")
    @Test fun `a wide fold gets the list`() {
        open()
        listBounds()
    }

    @Config(qualifiers = "sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `tapping a row masks or unmasks that box`() {
        val vm = open()
        compose.onNodeWithText("网址").performClick()
        assertThat(vm.state.value.plan.find("url")!!.state).isEqualTo(MaskState.MASKED)
        compose.onNodeWithText("电话").performClick()
        assertThat(vm.state.value.plan.find("phone")!!.state).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.canUndo).isTrue()
    }

    /** 样式的调节面板弹出来时占列表的位置，收起后列表回来。 */
    @Config(qualifiers = "sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `the style panel takes the list's place while it is open`() {
        val vm = open()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.EMOJI) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertExists()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("框出 2 处").assertExists()
    }

    private companion object {
        /** 落码那一遍和面板展开收起都走完。 */
        const val SETTLE_FRAMES = 400
    }
}
