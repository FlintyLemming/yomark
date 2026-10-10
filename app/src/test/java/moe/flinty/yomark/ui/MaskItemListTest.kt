package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.ui.components.ItemThumbnails
import moe.flinty.yomark.ui.components.MaskItemList
import moe.flinty.yomark.ui.components.MaskListOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 宽屏的框列表：顺序、缩略图，点一行报的是哪一块。点了之后怎么改 plan 由 ViewModel 管，见 EditorViewModelTest。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class MaskItemListTest {

    @get:Rule val compose = createComposeRule()

    private fun item(
        id: String, l: Float, t: Float, r: Float, b: Float,
        kind: SensitiveKind = SensitiveKind.PHONE,
        state: MaskState = MaskState.MASKED,
        source: DetectorSource = DetectorSource.RULE,
    ) = MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), kind, source, state)

    private fun ids(items: List<MaskItem>) = MaskListOrder.of(items).map { it.candidateId }

    // ---------- 顺序 ----------

    @Test fun `items read top to bottom`() {
        val items = listOf(item("c", 10f, 300f, 200f, 330f), item("a", 10f, 100f, 200f, 130f), item("b", 10f, 200f, 200f, 230f))
        assertThat(ids(items)).containsExactly("a", "b", "c").inOrder()
    }

    /** 同一行字的两段，上沿差一两个像素：照样从左往右，不按谁高一点排。 */
    @Test fun `pieces of one line read left to right`() {
        val items = listOf(item("phone", 300f, 99f, 500f, 129f), item("name", 10f, 101f, 120f, 131f))
        assertThat(ids(items)).containsExactly("name", "phone").inOrder()
    }

    /**
     * 左边一张大头像，右边三行字（左沿差几个像素）：字还是一行一行往下排，不因为都在头像的高度里就按左右乱排。
     * 头像和中点挨着它中点的那一行算一行，排在那一行前面。
     */
    @Test fun `a tall face does not swallow the lines beside it`() {
        val items = listOf(
            item("top", 330f, 180f, 600f, 210f),
            item("face", 10f, 100f, 300f, 400f, kind = SensitiveKind.FACE, source = DetectorSource.FACE),
            item("middle", 320f, 230f, 600f, 260f),
            item("bottom", 325f, 330f, 600f, 360f),
        )
        assertThat(ids(items)).containsExactly("top", "face", "middle", "bottom").inOrder()
    }

    @Test fun `masking or unmasking does not move an item`() {
        val items = listOf(item("a", 10f, 100f, 200f, 130f), item("b", 10f, 200f, 200f, 230f), item("c", 300f, 100f, 400f, 130f))
        val toggled = items.map { if (it.candidateId == "a") it.copy(state = MaskState.OUTLINED) else it }
        assertThat(ids(toggled)).isEqualTo(ids(items))
    }

    // ---------- 缩略图 ----------

    @Test fun `the thumbnail leaves a margin around the box`() {
        // 30 px 高的一行字：上下左右各留四分之一，也就是 7.5，往外取整
        val rect = ItemThumbnails.cropRect(Quad.fromRect(RectF(100f, 100f, 300f, 130f)), 1000, 1000)
        assertThat(rect).isEqualTo(Rect(92, 92, 308, 138))
    }

    @Test fun `the thumbnail stays inside the image`() {
        val rect = ItemThumbnails.cropRect(Quad.fromRect(RectF(-20f, 990f, 50f, 1020f)), 1000, 1000)
        assertThat(rect).isEqualTo(Rect(0, 982, 58, 1000))
    }

    /** 手动框整个拖到了图外面：没有可截的，不崩。 */
    @Test fun `a box outside the image has no thumbnail`() {
        assertThat(ItemThumbnails.cropRect(Quad.fromRect(RectF(1200f, 10f, 1300f, 60f)), 1000, 1000)).isNull()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertThat(ItemThumbnails.crop(bitmap, Quad.fromRect(RectF(200f, 10f, 300f, 60f)))).isNull()
    }

    @Test fun `the thumbnail is a crop of the original pixels`() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(100f, 100f, 300f, 140f, android.graphics.Paint().apply { color = Color.RED })
        }
        val thumb = ItemThumbnails.crop(bitmap, Quad.fromRect(RectF(100f, 100f, 300f, 140f)))!!
        assertThat(thumb.width).isEqualTo(220)
        assertThat(thumb.height).isEqualTo(60)
        assertThat(thumb.getPixel(110, 30)).isEqualTo(Color.RED)
        assertThat(thumb.getPixel(2, 2)).isEqualTo(Color.WHITE)
    }

    @Test fun `a big region is scaled down`() {
        val bitmap = Bitmap.createBitmap(2000, 1600, Bitmap.Config.ARGB_8888)
        val thumb = ItemThumbnails.crop(bitmap, Quad.fromRect(RectF(100f, 100f, 1900f, 1500f)))!!
        assertThat(thumb.width).isAtMost(ItemThumbnails.MAX_WIDTH)
        assertThat(thumb.height).isAtMost(ItemThumbnails.MAX_HEIGHT)
    }

    // ---------- 列表 ----------

    private val image by lazy {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        SourceImage(bitmap, 1f, 400, 400, "image/png")
    }

    private fun show(plan: MaskPlan, analyzing: Boolean = false, selectedId: String? = null, onClick: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { Surface { MaskItemList(image, plan, analyzing, selectedId, onClick) } }
        }
    }

    private val plan = MaskPlan(
        listOf(
            item("phone", 10f, 10f, 200f, 40f),
            item("url", 10f, 100f, 300f, 130f, kind = SensitiveKind.URL, state = MaskState.OUTLINED),
            item("manual", 50f, 200f, 150f, 260f, kind = SensitiveKind.MANUAL, source = DetectorSource.MANUAL),
        ),
    )

    @Test fun `every box is listed with its kind and state`() {
        show(plan)
        compose.onNodeWithText("电话").assertIsOn()
        compose.onNodeWithText("网址").assertIsOff()
        compose.onNodeWithText("手动").assertExists()
        compose.onNodeWithText("框出 3 处").assertExists()
        compose.onNodeWithText("1 处未打码").assertExists()
    }

    @Test fun `tapping a row reports that box`() {
        val clicked = mutableListOf<String>()
        show(plan) { clicked += it }
        compose.onNodeWithText("网址").performClick()
        compose.onNodeWithText("手动").performClick()
        assertThat(clicked).containsExactly("url", "manual").inOrder()
    }

    @Test fun `the selected manual box is marked`() {
        show(plan, selectedId = "manual")
        compose.onNodeWithText("手动").assertIsSelected()
        compose.onNodeWithText("已选中").assertExists()
    }

    @Test fun `results from the model say so`() {
        show(MaskPlan(listOf(item("ai", 10f, 10f, 200f, 40f, kind = SensitiveKind.URL, source = DetectorSource.LLM))))
        compose.onNodeWithText("网址 · AI").assertExists()
    }

    /** 识别中（换方案重跑时也是）只列手动框：识别出的块这时在画布上也不画。 */
    @Test fun `while recognizing only the manual boxes are listed`() {
        show(plan, analyzing = true)
        compose.onNodeWithText("识别中…").assertExists()
        compose.onNodeWithText("手动").assertExists()
        compose.onNodeWithText("电话").assertDoesNotExist()
    }

    @Test fun `an empty result says how to add a box`() {
        show(MaskPlan.empty())
        compose.onNodeWithText("没有识别到敏感信息").assertExists()
    }
}
