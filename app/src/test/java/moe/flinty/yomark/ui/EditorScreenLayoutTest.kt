package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction

/**
 * 编辑器在哪些屏幕上换成宽屏版式（宽屏增补设计）：手机没有框列表，横过来样式和导出挪到画布右边；
 * 平板、阔折叠在画布下面或右边多出列表，点列表的一行就切换那一块。
 * 哪些尺寸算宽屏的细账在 EditorLayoutTest，列表本身在 MaskItemListTest。
 *
 * 手机的底栏是一行工具：点「打码」「编辑」就地展开，返回收起；用途水印在「编辑」里，对比在这一行，导出在右上角。
 * 手机上样式的调节面板是弹出来盖在图上的：弹出时底栏不动，吸管取色时让开再回来，点外面、按返回都收起。
 * 这几条用真的 ViewModel 走一遍，这一行自己的细节在 EditorToolbarTest，面板自己的控件在 StyleBarTest。
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

    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `a phone has one row of tools, export up top, and no list`() {
        open()
        val compare = compose.onNodeWithText("按住对比").getUnclippedBoundsInRoot()
        for (label in listOf("打码", "编辑")) {
            val group = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertThat(group.top).isLessThan(compare.bottom)
            assertThat(group.bottom).isGreaterThan(compare.top)
        }
        assertThat(compose.onNodeWithText("导出").getUnclippedBoundsInRoot().bottom).isLessThan(screenBounds().bottom * 0.15f)
        compose.onNodeWithContentDescription("按住对比原图").assertDoesNotExist()   // 顶栏上那一个挪下来了
        compose.onNodeWithText("色块").assertDoesNotExist()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
    }

    /** 点一组，它滑到最左边变成返回，工具就地展开，对比让开；系统返回先把它收回去，不退出编辑器。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone a group unfolds in place and back folds it`() {
        open()
        val bar = compose.onNodeWithText("打码").getUnclippedBoundsInRoot()
        compose.onNodeWithText("打码").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马赛克").assertIsDisplayed()
        compose.onNodeWithText("按住对比").assertDoesNotExist()
        compose.onNodeWithText("导出").assertIsDisplayed()     // 在右上角，展开时也在
        assertThat(compose.onNodeWithText("打码").getUnclippedBoundsInRoot().left).isLessThan(bar.left)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马赛克").assertDoesNotExist()
        compose.onNodeWithText("按住对比").assertIsDisplayed()
        assertThat(compose.onNodeWithText("打码").getUnclippedBoundsInRoot()).isEqualTo(bar)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    /** 手机竖着拿，用途水印在「编辑」里，顶栏上没有它。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the purpose watermark is under edit, not in the top bar`() {
        val vm = open()
        compose.onNodeWithContentDescription("用途水印").assertDoesNotExist()
        compose.onNodeWithText("编辑").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
    }

    /** 手机上样式的调节面板从底下弹出来盖在图上，不把底栏和画布往上顶。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the style panel pops up over the image instead of pushing it up`() {
        val vm = open()
        val bar = compose.onNodeWithText("打码").getUnclippedBoundsInRoot()
        compose.runOnUiThread {
            // 色块本来就选着，再点一下就弹出面板。别换成带安全性说明的样式：说明会把样式栏往上顶
            vm.onStyleChipClick(MaskStyle.SOLID)
        }
        frames(SETTLE_FRAMES)
        compose.onNode(hasText("恢复默认") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertThat(compose.onNodeWithText("打码").getUnclippedBoundsInRoot()).isEqualTo(bar)
        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 吸管：弹出的面板先让开，点图取色，面板带着取到的颜色弹回来。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the sheet steps aside for the eyedropper and comes back with the colour`() {
        val vm = open()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithContentDescription("从图上取色").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNodeWithText("点一下图片，取那里的颜色").assertIsDisplayed()
        compose.runOnUiThread { vm.onTap(PointF(200f, 200f)) }   // 白图
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(Color.WHITE)
        compose.onNodeWithText("点一下图片，取那里的颜色").assertDoesNotExist()
        compose.onNode(hasText("恢复默认") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription("白色").assertIsSelected()
    }

    /** 点面板外面（Material 3 的暗幕，读屏念「Close sheet」）、按返回，都把面板收起。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone tapping outside the sheet or pressing back folds it`() {
        val vm = open()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        // 点暗幕 = 面板的「关闭」语义动作；暗幕的读屏文字随系统语言变，不按它找
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()

        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 手机横过来：样式和导出挪到画布右边，和平板横着拿一样，只是没有列表。 */
    @Config(qualifiers = "zh-rCN-sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `a phone on its side moves the styles and export right of the image, without the list`() {
        open()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        val screen = screenBounds()
        for (label in listOf("色块", "马克笔", "导出")) {
            assertThat(compose.onNodeWithText(label).getUnclippedBoundsInRoot().left).isGreaterThan(screen.right * 0.6f)
        }
    }

    /** 手机横着拿，右边那一栏矮：调节面板弹出来时顶替样式方块，不盖在图上；导出还在底下，收起后方块回来。 */
    @Config(qualifiers = "zh-rCN-sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `on a phone held sideways the style panel takes the styles' place`() {
        val vm = open()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }   // 点当前这一种，弹出面板
        frames(SETTLE_FRAMES)
        val reset = compose.onNode(hasText("恢复默认") and !hasAnyAncestor(isDialog()))
        reset.assertIsDisplayed()
        assertThat(reset.getUnclippedBoundsInRoot().left).isGreaterThan(screenBounds().right * 0.6f)
        compose.onNodeWithText("马克笔").assertDoesNotExist()
        compose.onNodeWithText("导出").assertIsDisplayed()
        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `an upright tablet lists the boxes below the image`() {
        open()
        compose.onNodeWithText("导出").assertExists()
        compose.onNodeWithText("1 处未打码").assertExists()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.top).isGreaterThan(screen.bottom * 0.6f)
        // 样式方块铺满左半边，列表占右半边
        val solid = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        val marker = compose.onNodeWithText("马克笔").getUnclippedBoundsInRoot()
        assertThat(marker.right).isLessThan(list.left)
        assertThat(marker.right).isGreaterThan(screen.right * 0.45f)
        assertThat(solid.width).isGreaterThan(100.dp)
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w1280dp-h800dp-land-xhdpi")
    @Test fun `a tablet on its side lists the boxes right of the image, below the styles`() {
        open()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.left).isGreaterThan(screen.right * 0.6f)
        // 样式方块铺满这一栏的宽，在列表上面
        val solid = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        val pixelate = compose.onNodeWithText("马赛克").getUnclippedBoundsInRoot()
        assertThat(solid.bottom).isLessThan(list.top)
        assertThat(pixelate.right - solid.left).isGreaterThan(screen.width * 0.25f)
    }

    @Config(qualifiers = "zh-rCN-sw528dp-w528dp-h848dp-port-xhdpi")
    @Test fun `a wide fold gets the list`() {
        open()
        listBounds()
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `tapping a row masks or unmasks that box`() {
        val vm = open()
        compose.onNodeWithText("网址").performClick()
        assertThat(vm.state.value.plan.find("url")!!.state).isEqualTo(MaskState.MASKED)
        compose.onNodeWithText("电话").performClick()
        assertThat(vm.state.value.plan.find("phone")!!.state).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.canUndo).isTrue()
    }

    /** 样式的调节面板弹出来时占列表的位置，收起后列表回来。 */
    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `the style panel takes the list's place while it is open`() {
        val vm = open()
        compose.runOnUiThread {
            vm.onStyleChipClick(MaskStyle.PIXELATE)   // 换成马赛克
            vm.onStyleChipClick(MaskStyle.PIXELATE)   // 再点一下才弹出面板
        }
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
