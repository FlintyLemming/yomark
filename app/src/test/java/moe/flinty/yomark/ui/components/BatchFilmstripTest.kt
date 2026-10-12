package moe.flinty.yomark.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

/**
 * 批量的胶片条：点哪张换哪张，滑一下停在整张上，外面换了图条带跟着滑过去，停在中间的那张比别的宽。
 * 排法的细账在 FilmstripGeometryTest。图是横着的 4:3，展开后比窄条宽得多。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h860dp-xxhdpi")
class BatchFilmstripTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uris by lazy {
        (1..5).map { n ->
            val file = File(context.cacheDir, "strip-$n.png")
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(file)
        }
    }

    private var selected by mutableIntStateOf(0)
    private val picked = mutableListOf<Int>()

    private fun show(initial: Int = 0) {
        selected = initial
        val thumbnails = BatchThumbnails(context.contentResolver, 64)
        compose.setContent {
            MaterialTheme {
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
        compose.waitForIdle()
    }

    private fun cell(i: Int) = compose.onNodeWithContentDescription("第 ${i + 1} 张，共 ${uris.size} 张")

    private fun centerX(bounds: androidx.compose.ui.unit.DpRect) = (bounds.left + bounds.right) / 2

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

        val strip = compose.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        val current = cell(3).getUnclippedBoundsInRoot()
        assertThat(abs((centerX(current) - centerX(strip)).value)).isLessThan(1f)
    }

    @Test fun `the one in the middle is wider than the rest`() {
        show(initial = 2)
        val current = cell(2).getUnclippedBoundsInRoot()
        val other = cell(3).getUnclippedBoundsInRoot()
        assertThat((current.right - current.left).value).isGreaterThan((other.right - other.left).value)
    }

    @Test fun `swiping moves on and comes to rest on a whole image`() {
        show()
        compose.onNodeWithTag(STRIP).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150f) }
        compose.waitForIdle()

        assertThat(picked).isNotEmpty()
        assertThat(picked.last()).isGreaterThan(0)
        cell(picked.last()).assertIsSelected()
        val strip = compose.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        val current = cell(picked.last()).getUnclippedBoundsInRoot()
        assertThat(abs((centerX(current) - centerX(strip)).value)).isLessThan(1f)
    }

    @Test fun `when the editor switches images the strip follows`() {
        show()
        selected = 4                                    // 如「去看看」跳到了没看过的那张
        compose.waitForIdle()

        cell(4).assertIsSelected()
        val strip = compose.onNodeWithTag(STRIP).getUnclippedBoundsInRoot()
        val current = cell(4).getUnclippedBoundsInRoot()
        assertThat(abs((centerX(current) - centerX(strip)).value)).isLessThan(1f)
        assertThat(picked).isEmpty()                    // 外面换的，不回头再报一次
    }

    private companion object {
        const val STRIP = "strip"
    }
}
