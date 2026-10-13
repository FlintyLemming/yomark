package moe.flinty.yomark.engine

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.mlkit.FaceCandidates
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 人脸候选：出厂打码，设置里可以改成仅圈出；框按短边外扩、裁回图内。 */
@RunWith(RobolectricTestRunner::class)
class FaceCandidateTest {

    private val box = RectF(100f, 100f, 200f, 220f)

    @Test fun `a face is masked when faces are set to mask`() {
        val c = FaceCandidates.from(0, trackingId = null, box, 1000, 1000, masked = true)
        assertThat(c.enabledByDefault).isTrue()
        assertThat(c.kind).isEqualTo(SensitiveKind.FACE)
        assertThat(c.source).isEqualTo(DetectorSource.FACE)
    }

    @Test fun `a face is only outlined when faces are set to outline`() {
        assertThat(FaceCandidates.from(0, null, box, 1000, 1000, masked = false).enabledByDefault).isFalse()
    }

    /** ML Kit 的框只包住五官，发际线和下巴在外面：四边都得往外扩，上边扩得最多。 */
    @Test fun `the box grows past the features, most of all upwards`() {
        val r = FaceCandidates.from(0, null, box, 1000, 1000, masked = true).quad.bounds()
        assertThat(r.left).isLessThan(box.left)
        assertThat(r.right).isGreaterThan(box.right)
        assertThat(r.bottom).isGreaterThan(box.bottom)
        assertThat(box.top - r.top).isGreaterThan(box.left - r.left)
    }

    /**
     * 竖着拍的页（顺时针转了 90°）：转正的图上认出的脸映射回原图，发际线那一侧多留的余量跟着转到原图上头顶朝的那边（右边）。
     * 原图 600×1000，转正的图 1000×600；转正的图上的 (u, v) 是原图上的 (600 − v, u)。
     */
    @Test fun `a face found upright maps back onto the original with the hairline side kept`() {
        val c = FaceCandidates.fromUpright(0, null, box, imageWidth = 600, imageHeight = 1000, turns = 1, masked = true)
        val r = c.quad.bounds()
        val face = RectF(380f, 100f, 500f, 200f)
        assertThat(r.contains(face)).isTrue()
        assertThat(r.right - face.right).isGreaterThan(face.left - r.left)
        assertThat(r.right).isAtMost(600f)
        assertThat(c.id).endsWith("-turn1")
    }

    @Test fun `the box never leaves the image`() {
        val r = FaceCandidates.from(0, null, RectF(0f, 0f, 100f, 100f), 100, 100, masked = true).quad.bounds()
        assertThat(r.left).isAtLeast(0f)
        assertThat(r.top).isAtLeast(0f)
        assertThat(r.right).isAtMost(100f)
        assertThat(r.bottom).isAtMost(100f)
    }
}
