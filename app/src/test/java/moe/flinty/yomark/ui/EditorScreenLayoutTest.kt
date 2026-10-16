package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert

/**
 * 编辑器在哪些屏幕上换成宽屏版式（宽屏增补设计）：手机没有框列表，横过来功能平铺成方块挪到画布右边；
 * 平板、阔折叠在画布下面或右边多出列表，点列表的一行就切换那一块。宽屏的导出也在顶栏右上角（2026-10-16），
 * 面板从点的那一块方块长出来，这一块自己的细节在 WideEditorPanelTest。
 * 哪些尺寸算宽屏的细账在 EditorLayoutTest，列表本身在 MaskItemListTest。
 *
 * 手机的底栏是一行工具：点「打码」「编辑」就地展开，返回收起；用途水印在「编辑」里，对比在这一行，导出在右上角。
 * 手机上样式的调节面板是从底栏往上长出来盖在图上的：底栏不动，吸管取色时缩回去再长出来，箭头、返回都收起。
 * 用途水印的面板也是这样长出来的，和样式面板换着开。
 * 这几条用真的 ViewModel 走一遍，这一行自己的细节在 EditorToolbarTest，面板自己的控件在 StyleBarTest、PurposePanelTest。
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

    private fun item(id: String, top: Float, kind: SensitiveKind) =
        MaskItem(id, Quad.fromRect(RectF(20f, top, 300f, top + 30f)), kind, DetectorSource.RULE)

    private val phone = item("phone", 40f, SensitiveKind.PHONE)
    private val url = item("url", 120f, SensitiveKind.URL)

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
        val compare = compose.onNodeWithContentDescription("按住对比").getUnclippedBoundsInRoot()
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

    /** 320 dp 宽的手机（或者调大了显示大小）：「导出」两个字放不下，换成只有图标的按钮，顶栏上别的按钮都在（见 EditorTopBarTest）。 */
    @Config(qualifiers = "en-rUS-w320dp-h640dp-xhdpi")
    @Test fun `on a 320dp phone export turns into an icon`() {
        open()
        compose.onNodeWithText("Export").assertIsNotDisplayed()
        val export = compose.onNodeWithContentDescription("Export").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(export.height).isAtMost(48.dp)
        assertThat(export.right).isAtMost(screenBounds().right)
        for (button in listOf("Back", "Undo", "Redo", "Settings")) {
            assertThat(compose.onNodeWithContentDescription(button).assertIsDisplayed().getUnclippedBoundsInRoot().right).isAtMost(export.left)
        }
    }

    /** 点一组，胶囊撑满、最左边只留返回箭头，工具就地展开，对比让开；系统返回先把它收回去，不退出编辑器。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone a group unfolds in place and back folds it`() {
        open()
        val bar = compose.onNodeWithText("打码").getUnclippedBoundsInRoot()
        compose.onNodeWithText("打码").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马赛克").assertIsDisplayed()
        compose.onNodeWithContentDescription("按住对比").assertDoesNotExist()
        compose.onNodeWithText("导出").assertIsDisplayed()     // 在右上角，展开时也在
        compose.onNodeWithText("打码").assertDoesNotExist()
        // 顶栏左上角也有一个「返回」：底栏这一个点了是收起
        val foldBack = compose.onNode(SemanticsMatcher("点了是收起") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "收起" })
        assertThat(foldBack.getUnclippedBoundsInRoot().left).isLessThan(bar.left)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马赛克").assertDoesNotExist()
        compose.onNodeWithContentDescription("按住对比").assertIsDisplayed()
        assertThat(compose.onNodeWithText("打码").getUnclippedBoundsInRoot()).isEqualTo(bar)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    /**
     * 手机竖着拿，用途水印在「编辑」里，顶栏上没有它。点它和点了再点马克笔一样，胶囊长出它的面板盖在图上，
     * 工具那一行不动；再点一下收起，这一组还展开着。打开面板不加水印，开了「应用」才加，收起后还在。
     */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the purpose watermark is under edit and grows its panel from the bar`() {
        val vm = open()
        compose.onNodeWithContentDescription("用途水印").assertDoesNotExist()
        openWatermarkPanel()
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
        val tool = compose.onNodeWithText("水印").getUnclippedBoundsInRoot()
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        assertThat(vm.state.value.purposeText).isNull()
        // 面板里填好了字，「应用」还没开：图上没有水印，不念「已开启」
        compose.onNodeWithText("水印").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        val apply = compose.onNodeWithText("应用").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(apply.bottom).isAtMost(tool.top)
        compose.onNodeWithText("应用").performClick()
        assertThat(vm.state.value.purposeText).isNotNull()
        frames(1)
        compose.onNodeWithText("水印").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启"))

        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        // 开着时选中的水印撑宽了一些，这一行上下没挪
        val folded = compose.onNodeWithText("水印").getUnclippedBoundsInRoot()
        assertThat(folded.top to folded.bottom).isEqualTo(tool.top to tool.bottom)
        compose.onNodeWithText("马克笔").assertIsDisplayed()
        assertThat(vm.state.value.purposeText).isNotNull()
    }

    /** 底栏展开「编辑」，点水印长出它的面板。 */
    private fun openWatermarkPanel() {
        compose.onNodeWithText("编辑").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
    }

    /** 水印和样式的面板是同一块地方：水印的开着时点马克笔，换成马克笔的；再点水印，换回来。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the watermark and style panels swap in place`() {
        val vm = open()
        openWatermarkPanel()
        compose.onNodeWithText("马克笔").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.MARKER)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithContentDescription("收起样式面板").assertIsDisplayed()
        compose.onNodeWithText("用途水印").assertDoesNotExist()

        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起样式面板").assertDoesNotExist()
    }

    /** 给水印用吸管：面板先缩回去，点图取色，面板带着取到的颜色长回来。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the watermark panel steps aside for the eyedropper`() {
        val vm = open()
        openWatermarkPanel()
        compose.onNodeWithContentDescription("从图上取色").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.colorPick).isEqualTo(ColorTarget.PURPOSE)
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        compose.onNodeWithText("点一下图片，取那里的颜色").assertIsDisplayed()
        compose.runOnUiThread { vm.onTap(PointF(200f, 200f)) }   // 白图
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeStyle.color).isEqualTo(Color.WHITE)
        compose.onNodeWithText("点一下图片，取那里的颜色").assertDoesNotExist()
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        compose.onNodeWithContentDescription("白色").assertIsSelected()
    }

    /** 水印的面板也是：右上角的箭头、系统返回、点它外面都只收面板，这一组还展开着。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the arrow, back, or a tap outside folds the watermark panel`() {
        val vm = open()
        openWatermarkPanel()
        compose.onNodeWithContentDescription("收起水印面板").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertIsDisplayed()

        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
        assertThat(compose.activity.isFinishing).isFalse()

        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        // 图的上半截，面板盖不到那儿
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.25f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    /** 手机上样式的调节面板从底栏往上长出来盖在图上：工具那一行不动。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the style panel grows over the image instead of pushing it up`() {
        val vm = open()
        compose.onNodeWithText("打码").performClick()
        frames(SETTLE_FRAMES)
        val tool = compose.onNodeWithText("马赛克").getUnclippedBoundsInRoot()
        compose.runOnUiThread {
            // 色块本来就选着，再点一下就长出面板
            vm.onStyleChipClick(MaskStyle.SOLID)
        }
        frames(SETTLE_FRAMES)
        val reset = compose.onNodeWithText("恢复默认").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(reset.bottom).isAtMost(tool.top)
        assertThat(compose.onNodeWithText("马赛克").getUnclippedBoundsInRoot()).isEqualTo(tool)
        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 吸管：面板先缩回去，点图取色，面板带着取到的颜色长回来。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the panel steps aside for the eyedropper and comes back with the colour`() {
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
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.onNodeWithContentDescription("白色").assertIsSelected()
    }

    /** 面板右上角的箭头、系统返回只收面板，这一组还展开着；底栏上的返回连这一组一起收。 */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the arrow, back, or folding the group folds the panel`() {
        val vm = open()
        compose.onNodeWithText("打码").performClick()
        frames(SETTLE_FRAMES)
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithContentDescription("收起样式面板").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNodeWithText("马赛克").assertIsDisplayed()

        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        // 面板收了，这一组还展开着
        compose.onNodeWithText("马赛克").assertIsDisplayed()

        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNode(SemanticsMatcher("点了是收起") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "收起" }).performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("马赛克").assertDoesNotExist()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /**
     * 面板开着时点它外面，图上、顶栏上都一样：和点面板右上角的箭头一样，只收面板，这一组还展开着（2026-10-16）。
     * 这一下只收起：点在顶栏的撤销上也不撤销。
     */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone tapping outside the panel folds just the panel`() {
        val vm = open()
        compose.onNodeWithText("打码").performClick()
        frames(SETTLE_FRAMES)
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        // 图的上半截，面板盖不到那儿
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.25f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNodeWithText("马赛克").assertIsDisplayed()

        // 先拿掉一块码，撤销点得动
        compose.runOnUiThread { vm.onTap(PointF(160f, 135f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.plan.find(url.candidateId)).isNull()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.onNodeWithContentDescription("撤销").assertIsEnabled().performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("马赛克").assertIsDisplayed()
        assertThat(vm.state.value.plan.find(url.candidateId)).isNull()       // 只收起，没撤销
    }

    /**
     * 「编辑」里拿着文本（2026-10-16）：写完一处点一下图只收面板，还在「编辑」里，点图接着写；
     * 点底栏的返回收起「编辑」，文本放下，换回打码的样式，点图不再写字。
     */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone the text tool stays in hand until edit folds`() {
        val vm = open()
        compose.onNodeWithText("编辑").performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("文本").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.TEXT)
        compose.runOnUiThread {
            vm.onTap(PointF(200f, 300f))
            vm.setText("签收")
        }
        frames(SETTLE_FRAMES)
        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.25f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.TEXT)
        // 头一下取消选中写好的那一块，再点就是接着写
        compose.runOnUiThread {
            vm.onTap(PointF(200f, 370f))
            vm.onTap(PointF(200f, 370f))
        }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.textPlacement?.center).isEqualTo(PointF(200f, 370f))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马克笔").assertIsDisplayed()

        compose.onNode(SemanticsMatcher("点了是收起") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "收起" }).performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("编辑").assertIsDisplayed()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.SOLID)
        compose.runOnUiThread { vm.onTap(PointF(200f, 370f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
    }

    /**
     * 从收着的底栏点两下图上的字改它：面板从「编辑」长出来，收起面板时这一组也收回去，回到打开前那样。
     * 改了它的颜色，画笔跟着成了文本（见 EditorViewModel.editLook），收回去也就放下了。
     */
    @Config(qualifiers = "zh-rCN-w412dp-h860dp-xxhdpi")
    @Test fun `on a phone a panel opened from the folded bar folds the bar back`() {
        val vm = open()
        val note = MaskItem(
            candidateId = "manual-note",
            quad = Quad.fromRect(RectF(40f, 250f, 200f, 290f)),
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            look = MaskLook(MaskStyle.TEXT),
            text = "签收",
        )
        compose.runOnUiThread { vm.replacePlanForTest(MaskPlan(listOf(phone, url, note))) }
        frames(SETTLE_FRAMES)
        compose.runOnUiThread {
            vm.onTap(PointF(120f, 270f))      // 选中
            vm.onTap(PointF(120f, 270f))      // 再点一下：改字
        }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
        compose.runOnUiThread { vm.editLook(note.look.copy(options = note.look.options.copy(textColor = Color.BLUE))) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.TEXT)

        compose.onRoot().performTouchInput { click(Offset(centerX, height * 0.25f)) }
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        compose.onNodeWithText("马克笔").assertDoesNotExist()
        // 「编辑」两处都有：底栏那一组，和选中的字上浮出来的那一颗
        compose.onNodeWithText("打码").assertIsDisplayed()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.SOLID)
    }

    // ---------- 宽屏、手机横着拿：所有功能平铺成方块（2026-10-16） ----------

    /** 和手机一样，导出在顶栏右上角；用途水印、对比不在顶栏上，在方块里。 */
    private fun assertExportIsUpTopAlone() {
        assertThat(compose.onNodeWithText("导出").getUnclippedBoundsInRoot().bottom).isLessThan(screenBounds().bottom * 0.2f)
        compose.onNodeWithContentDescription("用途水印").assertDoesNotExist()
        compose.onNodeWithContentDescription("按住对比原图").assertDoesNotExist()
    }

    /** 所有功能都是一块方块，不收进几组里：手机底栏里有的这里都有（用不了 AI 时没有 AI 那一块）。 */
    private val everyTile = listOf("色块", "马赛克", "模糊", "马克笔", "画笔", "文本", "水印", "大爆炸", "对比")

    /** 手机横过来：功能平铺成方块挪到画布右边，和平板横着拿一样，只是没有列表；导出在顶栏上。 */
    @Config(qualifiers = "zh-rCN-sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `a phone on its side tiles every function right of the image, without the list`() {
        open()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        val screen = screenBounds()
        for (label in everyTile) {
            assertThat(compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot().left).isGreaterThan(screen.right * 0.6f)
        }
        assertExportIsUpTopAlone()
    }

    /** 手机横着拿，右边那一栏矮：样式的面板从那一块长出来盖满这一栏，不盖在图上；收起后方块回来。 */
    @Config(qualifiers = "zh-rCN-sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `on a phone held sideways the style panel grows over the tiles`() {
        val vm = open()
        compose.runOnUiThread { vm.onStyleChipClick(MaskStyle.SOLID) }   // 点当前这一种，长出面板
        frames(SETTLE_FRAMES)
        // 这一栏矮，面板里面可能要滚动：看顶上的收起
        val collapse = compose.onNodeWithContentDescription("收起样式面板").assertIsDisplayed()
        assertThat(collapse.getUnclippedBoundsInRoot().left).isGreaterThan(screenBounds().right * 0.6f)
        compose.onNodeWithText("马克笔").assertDoesNotExist()       // 盖住了，点不着、读屏也读不到
        compose.onNodeWithText("导出").assertIsDisplayed()
        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithContentDescription("收起样式面板").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    /** 手机横着拿：点水印那一块，水印的面板和样式面板一样长出来盖满这一栏，不盖在图上。 */
    @Config(qualifiers = "zh-rCN-sw412dp-w860dp-h412dp-land-xxhdpi")
    @Test fun `on a phone held sideways the watermark tile grows its panel`() {
        val vm = open()
        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
        val title = compose.onNode(hasText("用途水印") and !hasAnyAncestor(isDialog()))
        title.assertIsDisplayed()
        assertThat(title.getUnclippedBoundsInRoot().left).isGreaterThan(screenBounds().right * 0.6f)
        compose.onNodeWithText("马克笔").assertDoesNotExist()
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `an upright tablet lists the boxes below the image`() {
        open()
        compose.onNodeWithText("框出 2 处").assertExists()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.top).isGreaterThan(screen.bottom * 0.6f)
        // 方块铺满左半边，列表占右半边；三个一行，第一行最右边是模糊
        val solid = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        val blur = compose.onNodeWithText("模糊").getUnclippedBoundsInRoot()
        assertThat(blur.right).isLessThan(list.left)
        assertThat(blur.right).isGreaterThan(screen.right * 0.45f)
        assertThat(solid.width).isGreaterThan(100.dp)
        for (label in everyTile) {
            assertThat(compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot().right).isLessThan(list.left)
        }
        assertExportIsUpTopAlone()
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w1280dp-h800dp-land-xhdpi")
    @Test fun `a tablet on its side lists the boxes right of the image, below the tiles`() {
        open()
        val list = listBounds()
        val screen = screenBounds()
        assertThat(list.left).isGreaterThan(screen.right * 0.6f)
        // 方块铺满这一栏的宽，都在列表上面
        val solid = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        val blur = compose.onNodeWithText("模糊").getUnclippedBoundsInRoot()
        assertThat(blur.right - solid.left).isGreaterThan(screen.width * 0.25f)
        for (label in everyTile) {
            assertThat(compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot().bottom).isLessThan(list.top)
        }
        assertExportIsUpTopAlone()
    }

    @Config(qualifiers = "zh-rCN-sw528dp-w528dp-h848dp-port-xhdpi")
    @Test fun `a wide fold gets the list`() {
        open()
        listBounds()
    }

    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `tapping a row takes that mask off`() {
        val vm = open()
        compose.onNodeWithText("网址").performClick()
        assertThat(vm.state.value.plan.find("url")).isNull()
        compose.onNodeWithText("电话").performClick()
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isTrue()
    }

    /**
     * 样式的调节面板从那一块方块长出来，落在列表的位置：开着时列表淡掉、点不着，方块都还在，换样式不用先收起；
     * 收起后列表回来。
     */
    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `the style panel takes the list's place while it is open`() {
        val vm = open()
        val list = listBounds()
        compose.onNodeWithText("马赛克").performClick()   // 换成马赛克
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("马赛克").performClick()   // 再点一下才长出面板
        frames(SETTLE_FRAMES)
        val reset = compose.onNodeWithText("恢复默认").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(reset.left).isAtLeast(list.left)
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        compose.onNodeWithText("色块").assertIsDisplayed()

        compose.onNodeWithText("模糊").performClick()     // 开着面板换样式：面板换成模糊的
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.BLUR)
        assertThat(vm.state.value.stylePanelOpen).isTrue()

        compose.runOnUiThread { vm.dismissStylePanel() }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNodeWithText("框出 2 处").assertExists()
    }

    /**
     * 宽屏上点水印那一块：水印的面板也从它长出来、占列表的位置，和样式面板一次只开一个；再点一下收起，列表回来。
     * 设了水印，那一块念「已开启」；打开面板还没加上时不念。
     */
    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `the watermark panel takes the list's place too`() {
        val vm = open()
        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        // 面板里填好了字，「应用」还没开：图上没有水印，不念「已开启」
        compose.onNodeWithText("水印").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText("应用").performClick()

        compose.runOnUiThread { vm.onStyleChipClick(vm.state.value.barLook.style) }
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起样式面板").assertIsDisplayed()

        compose.onNodeWithText("水印").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启")).performClick()
        frames(SETTLE_FRAMES)
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        compose.onNodeWithText("水印").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        compose.onNodeWithText("框出 2 处").assertExists()
    }

    /** 宽屏上马克笔一直拿在手里：功能平铺着，没有手机上收起「编辑」就放下那一步。 */
    @Config(qualifiers = "zh-rCN-sw800dp-w800dp-h1280dp-port-xhdpi")
    @Test fun `on a tablet the marker stays in hand`() {
        val vm = open()
        compose.onNodeWithText("马克笔").performClick()
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.MARKER)
        compose.runOnUiThread { vm.onTap(PointF(380f, 380f)) }   // 点空白
        frames(SETTLE_FRAMES)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.MARKER)
    }

    private companion object {
        /** 落码那一遍和面板展开收起都走完。 */
        const val SETTLE_FRAMES = 400
    }
}
