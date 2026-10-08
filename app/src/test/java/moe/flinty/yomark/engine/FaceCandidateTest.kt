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

    @Test fun `the box never leaves the image`() {
        val r = FaceCandidates.from(0, null, RectF(0f, 0f, 100f, 100f), 100, 100, masked = true).quad.bounds()
        assertThat(r.left).isAtLeast(0f)
        assertThat(r.top).isAtLeast(0f)
        assertThat(r.right).isAtMost(100f)
        assertThat(r.bottom).isAtMost(100f)
    }
}
