package com.yomark.app.engine

import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.Candidate
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.SensitiveKind
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
    ) = Candidate(id, Quad.fromRect(RectF(l, t, r, b)), kind, source, confidence, enabled)

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
            c("tracking", 1f, 0f, 100f, 40f, kind = SensitiveKind.TRACKING_NO, source = DetectorSource.RULE),
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
}
