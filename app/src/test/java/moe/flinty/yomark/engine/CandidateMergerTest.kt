package moe.flinty.yomark.engine

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidateMergerTest {

    private fun c(
        id: String,
        l: Float, t: Float, r: Float, b: Float,
        kind: SensitiveKind = SensitiveKind.EMAIL,
        source: DetectorSource = DetectorSource.RULE,
        confidence: Float = 0.9f,
        enabled: Boolean = true,
        degrees: Float? = null,
    ) = Candidate(id, Quad.fromRect(RectF(l, t, r, b)), kind, source, confidence, enabled, degrees)

    private val merger = CandidateMerger()

    @Test
    fun `disjoint candidates all survive`() {
        val out = merger.merge(listOf(c("a", 0f, 0f, 10f, 10f), c("b", 100f, 100f, 110f, 110f)))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `heavily overlapping candidates are deduplicated`() {
        val out = merger.merge(listOf(c("a", 0f, 0f, 100f, 20f), c("b", 2f, 0f, 100f, 20f)))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `rule beats entity model when they overlap`() {
        val out = merger.merge(listOf(
            c("model", 0f, 0f, 100f, 20f, source = DetectorSource.ENTITY_MODEL),
            c("rule", 2f, 0f, 100f, 20f, source = DetectorSource.RULE),
        ))
        assertThat(out.single().id).isEqualTo("rule")
    }

    @Test
    fun `entity model beats llm when they overlap`() {
        val out = merger.merge(listOf(
            c("llm", 0f, 0f, 100f, 20f, source = DetectorSource.LLM),
            c("model", 2f, 0f, 100f, 20f, source = DetectorSource.ENTITY_MODEL),
        ))
        assertThat(out.single().id).isEqualTo("model")
    }

    @Test
    fun `adjacent same-kind candidates on one line are merged`() {
        // 行高 20，间距 8 < 20*0.5 → 合并
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 58f, 0f, 100f, 20f),
        ))
        assertThat(out).hasSize(1)
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(0f, 0f, 100f, 20f))
    }

    @Test
    fun `candidates too far apart are not merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 90f, 0f, 140f, 20f),      // 间距 40 > 10
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `candidates of different kinds are never merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, kind = SensitiveKind.EMAIL),
            c("b", 55f, 0f, 100f, 20f, kind = SensitiveKind.PHONE),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `candidates on different lines are not merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 55f, 100f, 100f, 120f),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `merging takes the OR of enabledByDefault`() {
        // 合并绝不能把默认打码的降级成仅圈出
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, enabled = false),
            c("b", 55f, 0f, 100f, 20f, enabled = true),
        ))
        assertThat(out.single().enabledByDefault).isTrue()
    }

    @Test
    fun `merged candidate keeps the highest confidence`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, confidence = 0.6f),
            c("b", 55f, 0f, 100f, 20f, confidence = 0.95f),
        ))
        assertThat(out.single().confidence).isWithin(1e-3f).of(0.95f)
    }

    @Test
    fun `output is sorted by area descending`() {
        val out = merger.merge(listOf(
            c("small", 0f, 0f, 10f, 10f),
            c("big", 200f, 200f, 400f, 300f),
            c("mid", 500f, 500f, 560f, 540f),
        ))
        assertThat(out.map { it.id }).containsExactly("big", "mid", "small").inOrder()
    }

    @Test
    fun `face and barcode candidates are never merged into text candidates`() {
        val out = merger.merge(listOf(
            c("face", 0f, 0f, 50f, 50f, kind = SensitiveKind.FACE, source = DetectorSource.FACE),
            c("text", 52f, 0f, 100f, 50f, kind = SensitiveKind.EMAIL),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `an overlapping text candidate never deduplicates a barcode away`() {
        // 条码下方常印着同一串快递单号，IoU 轻松超阈值。
        // 两条链路互不去重，否则条码候选会被静默吃掉——漏检是事故。
        val out = merger.merge(listOf(
            c("barcode", 0f, 0f, 100f, 40f, kind = SensitiveKind.BARCODE, source = DetectorSource.BARCODE),
            c("tracking", 1f, 0f, 100f, 40f, kind = SensitiveKind.LONG_NUMBER, source = DetectorSource.RULE),
        ))
        assertThat(out.map { it.id }).containsExactly("barcode", "tracking")
    }

    @Test
    fun `two overlapping faces are still deduplicated`() {
        val out = merger.merge(listOf(
            c("f1", 0f, 0f, 100f, 100f, kind = SensitiveKind.FACE, source = DetectorSource.FACE),
            c("f2", 2f, 0f, 100f, 100f, kind = SensitiveKind.FACE, source = DetectorSource.FACE),
        ))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(merger.merge(emptyList())).isEmpty()
    }

    // ---------- 竖着拍的页：行和行高按阅读方向算 ----------

    /** 阅读方向朝下的页（竖着拍的身份证）。 */
    private val downwards = ReadingFrame.of(listOf(Quad(PointF(20f, 0f), PointF(20f, 100f), PointF(0f, 100f), PointF(0f, 0f))))

    /** 图上左右挨着的两个竖条是上下两行地址：按图上的横竖算会拼成一大块，连中间的空白一起遮。 */
    @Test
    fun `stacked lines on a page turned on its side stay apart`() {
        val lines = listOf(c("a", 100f, 0f, 120f, 200f), c("b", 70f, 0f, 90f, 200f))
        assertThat(merger.merge(lines, downwards)).hasSize(2)
        assertThat(merger.merge(lines)).hasSize(1)
    }

    /** 同一行里的两段（图上上下挨着）照样拼起来，拼出来的框还在图上原处。 */
    @Test
    fun `pieces of one line on a page turned on its side are merged`() {
        val out = merger.merge(listOf(c("a", 100f, 0f, 120f, 50f), c("b", 100f, 58f, 120f, 100f)), downwards)
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(100f, 0f, 120f, 100f))
    }

    // ---------- 候选自己带着方向：正着的截图里一张转过的照片 ----------

    /**
     * 正着的页上一张竖着的证件照，两行地址在图上是左右挨着的两个竖条。候选带着照片上的方向（朝下），按它量是上下两行，不拼；
     * 没带方向的照旧按整页量。
     */
    @Test
    fun `stacked lines of a turned photo on an upright page stay apart`() {
        assertThat(merger.merge(listOf(c("a", 100f, 0f, 120f, 200f, degrees = 90f), c("b", 80f, 0f, 100f, 200f, degrees = 90f)))).hasSize(2)
        assertThat(merger.merge(listOf(c("a", 100f, 0f, 120f, 200f), c("b", 80f, 0f, 100f, 200f)))).hasSize(1)
    }

    /** 照片上同一行的两段照样拼，按照片的方向拼出来的框还在图上原处。 */
    @Test
    fun `pieces of one line in a turned photo are merged`() {
        val out = merger.merge(listOf(c("a", 100f, 0f, 120f, 50f, degrees = 90f), c("b", 100f, 58f, 120f, 100f, degrees = 90f)))
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(100f, 0f, 120f, 100f))
    }

    /** 方向不同的两处，按整页量挨着也不拼：照片上的一块牌子和聊天里的一行字。 */
    @Test
    fun `candidates read in different directions are not merged`() {
        val out = merger.merge(listOf(c("chat", 0f, 0f, 50f, 20f, degrees = 0f), c("photo", 55f, 0f, 75f, 20f, degrees = 90f)))
        assertThat(out).hasSize(2)
    }

    /** 方向不同、叠在一起的是同一处读了两遍（斜着的水印那一行里又读出一遍名字）：拼成一个，按整页的方向。 */
    @Test
    fun `overlapping candidates read in different directions are merged`() {
        val out = merger.merge(listOf(c("card", 0f, 0f, 60f, 20f, degrees = 0f), c("watermark", 40f, 5f, 90f, 30f, degrees = 30f)))
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(0f, 0f, 90f, 30f))
    }

    /** 差几度的算同一个方向：拍歪了的照片上，各行的框读出来的方向差一两度。 */
    @Test
    fun `directions a few degrees apart are the same direction`() {
        val out = merger.merge(listOf(c("a", 0f, 0f, 50f, 20f, degrees = 8f), c("b", 55f, 0f, 100f, 20f, degrees = 6.5f)))
        assertThat(out).hasSize(1)
    }
}
