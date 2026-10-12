package moe.flinty.yomark.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.ui.batch.BatchThumbnails
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 批量的胶片条：点哪张换哪张（缝隙里点下去算近的那张），轻划正好走一张，用力甩才连跳几张，慢慢拖停在拖到的地方；
 * 手指拖着经过一张轻震一下，编辑器自己滑过去时不震。外面换了图条带跟着滑过去，停在中间的那张比别的宽。
 * 排法的细账在 FilmstripGeometryTest。图是横着的 4:3，展开后比窄条宽得多。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h860dp-xxhdpi")
class BatchFilmstripTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun uris(count: Int) = (1..count).map { n ->
        val file = File(context.cacheDir, "strip-$n.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Uri.fromFile(file)
    }

    private var count = 5
    private var selected by mutableIntStateOf(0)
    private val picked = mutableListOf<Int>()
    private val ticks = mutableListOf<HapticFeedbackType>()

    private fun show(initial: Int = 0, images: Int = 5) {
        count = images
        selected = initial
        val uris = uris(images)
        val thumbnails = BatchThumbnails(context.contentResolver, 64)
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks += hapticFeedbackType
            }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    Box(Modifier.width(300.dp)) {
                        BatchFilmstrip(
                            uris = uris,
                            selected = selected,
                            onSelect = { picked += it; selected = it },
                            thumbnails = thumbnails,
                            modifier = Modifier.fillMaxWidth().testTag(STRIP),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun cell(i: Int) = compose.onNodeWithContentDescription("第 ${i + 1} 张，共 $count 张")
    private fun strip() = compose.onNodeWithTag(STRIP)

    private fun centerX(bounds: DpRect) = (bounds.left + bounds.right) / 2
    private fun px(dp: Dp) = with(compose.density) { dp.toPx() }

    /** 窄条的步长：28 dp 宽加 2 dp 的缝。 */
    private val pitch = 30.dp

    private fun assertCentered(i: Int) {
        cell(i).assertIsSelected()
        val off = centerX(cell(i).getUnclippedBoundsInRoot()) - centerX(strip().getUnclippedBoundsInRoot())
        assertThat(abs(off.value)).isLessThan(1f)
    }

    /**
     * 从条带中线往左（[images] 为正，往后面的图）或往右甩一下，一路匀速，每秒 [speed]。
     * 手指先要挪过触摸的起始阈值，条带才跟着走，所以手指一共挪「阈值 + [images] 张」，条带走 [images] 张（最多多出一步）。
     * 一路匀速，量出来的松手速度才准：前面一大步、后面小碎步的话，测速会当成在急刹车。
     */
    private fun flick(images: Float, speed: Dp) = strip().performTouchInput {
        val dir = if (images >= 0f) -1f else 1f
        val stepMillis = 4L
        val stepPx = px(speed) * stepMillis / 1000f
        val steps = ceil((viewConfiguration.touchSlop + px(pitch) * abs(images)) / stepPx).toInt()
        down(Offset(centerX, centerY))
        repeat(steps) { moveBy(Offset(dir * stepPx, 0f), delayMillis = stepMillis) }
        up()
    }

    /** 同上，但只管松手那一刻的速度，路上怎么加速由测试框架定。甩得远时用它。 */
    private fun swipe(images: Float, speed: Dp) = strip().performTouchInput {
        val from = Offset(centerX, centerY)
        swipeWithVelocity(from, from - Offset(px(pitch) * images, 0f), endVelocity = px(speed))
    }

    @Test fun `tapping a thumbnail picks it`() {
        show()
        cell(2).performClick()
        compose.waitForIdle()

        assertThat(picked).containsExactly(2)
        cell(2).assertIsSelected()
        cell(0).assertIsNotSelected()
    }

    @Test fun `the picked thumbnail slides into the middle`() {
        show()
        cell(3).performClick()
        compose.waitForIdle()
        assertCentered(3)
    }

    @Test fun `tapping in the gap between two thumbnails picks the nearer one`() {
        show()
        val a = cell(1).getUnclippedBoundsInRoot()
        val b = cell(2).getUnclippedBoundsInRoot()
        val left = strip().getUnclippedBoundsInRoot().left
        val nearB = (a.right + b.left) / 2 + 0.5.dp                    // 缝的正中偏右一点
        strip().performTouchInput { click(Offset(px(nearB - left), centerY)) }
        compose.waitForIdle()

        assertThat(picked).containsExactly(2)
    }

    @Test fun `the one in the middle is wider than the rest`() {
        show(initial = 2)
        val current = cell(2).getUnclippedBoundsInRoot()
        val other = cell(3).getUnclippedBoundsInRoot()
        assertThat((current.right - current.left).value).isGreaterThan((other.right - other.left).value)
    }

    @Test fun `a light swipe moves exactly one image, however far the finger went`() {
        show(initial = 3, images = 8)
        flick(images = 1.7f, speed = 1000.dp)          // 手指划过快两张，离第 5 张更近，也只走一张
        compose.waitForIdle()
        assertThat(picked).containsExactly(4)
        assertCentered(4)

        flick(images = -0.3f, speed = 1000.dp)         // 往右轻轻一划，还没划过半张，也走一张
        compose.waitForIdle()
        assertThat(picked).containsExactly(4, 3).inOrder()
        assertCentered(3)
    }

    @Test fun `a hard fling jumps several images`() {
        show(images = 12)
        swipe(images = 3.5f, speed = 5000.dp)                         // 手指只拖过三张半
        compose.waitForIdle()

        assertThat(picked.last()).isAtLeast(7)
        assertCentered(picked.last())
    }

    @Test fun `a slow drag comes to rest where it was dragged`() {
        show(images = 8)
        strip().performTouchInput {
            val from = Offset(centerX, centerY)
            swipe(from, from - Offset(px(pitch) * 3.3f, 0f), durationMillis = 2_000)
        }
        compose.waitForIdle()
        assertCentered(3)
    }

    @Test fun `dragging across images ticks once for each`() {
        show(images = 8)
        strip().performTouchInput {
            val from = Offset(centerX, centerY)
            swipe(from, from - Offset(px(pitch) * 3.3f, 0f), durationMillis = 2_000)
        }
        compose.waitForIdle()
        assertThat(ticks).hasSize(3)
    }

    @Test fun `moves the editor makes itself do not tick`() {
        show()
        cell(3).performClick()                         // 点了一张，条带自己滑过去
        compose.waitForIdle()
        selected = 1                                   // 如「去看看」跳到了没看过的那张
        compose.waitForIdle()

        assertCentered(1)
        assertThat(ticks).isEmpty()
    }

    @Test fun `when the editor switches images the strip follows`() {
        show()
        selected = 4                                    // 如「去看看」跳到了没看过的那张
        compose.waitForIdle()

        assertCentered(4)
        assertThat(picked).isEmpty()                    // 外面换的，不回头再报一次
    }

    private companion object {
        const val STRIP = "strip"
    }
}
