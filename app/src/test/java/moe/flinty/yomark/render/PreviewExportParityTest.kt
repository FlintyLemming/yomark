package moe.flinty.yomark.render

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 预览与导出的一致性（计划 06 Task 38 Step 7 的验收项）。
 *
 * 预览画在**降采样到 2048 的分析图**上，导出画在**原图**上。两条路径共用渲染器，
 * 但渲染器里的绝对像素常数（像素化的 12px 块下限、抹除的 4px 采样环）
 * 若不按 [MaskOptions.renderScale] 折算，长边超过 2048 的图上
 * 预览会比导出更糊——预览是用户判断「遮没遮住」的唯一依据，比导出安全等于骗人。
 */
@RunWith(RobolectricTestRunner::class)
class PreviewExportParityTest {

    /** 原图 4096 宽 → 分析图 2048，scale = 0.5。 */
    private val analysisScale = 0.5f
    private val divisor = MaskOptions().pixelBlockDivisor
    private val r = PixelateRenderer()

    private fun inPreview(q: Quad) = q.scaled(analysisScale)

    @Test
    fun `pixelate block covers the same fraction of the region in preview and export`() {
        // 原图上一条 100px 高的文本行；下限生效的量级
        val export = Quad.fromRect(RectF(0f, 0f, 800f, 100f))
        val preview = inPreview(export)

        val exportFraction = r.blockSizeFor(export, divisor) / export.shortEdge()
        val previewFraction =
            r.blockSizeFor(preview, divisor, analysisScale) / preview.shortEdge()

        assertThat(previewFraction).isWithin(0.001f).of(exportFraction)
    }

    @Test
    fun `the same holds when the divisor rather than the floor decides`() {
        val export = Quad.fromRect(RectF(0f, 0f, 800f, 400f))
        val preview = inPreview(export)

        assertThat(r.blockSizeFor(preview, divisor, analysisScale) / preview.shortEdge())
            .isWithin(0.001f).of(r.blockSizeFor(export, divisor) / export.shortEdge())
    }

    @Test
    fun `pixelate falls back to solid in both spaces or in neither`() {
        listOf(20f, 26f, 40f, 80f, 96f, 200f).forEach { h ->
            val export = Quad.fromRect(RectF(0f, 0f, 600f, h))
            val preview = inPreview(export)
            assertThat(r.willFallBack(preview, divisor, analysisScale))
                .isEqualTo(r.willFallBack(export, divisor))
        }
    }

    @Test
    fun `without the scale the preview over-promises, which is the bug this guards`() {
        val export = Quad.fromRect(RectF(0f, 0f, 800f, 100f))
        val preview = inPreview(export)
        // renderScale 默认 1f = 不折算：预览的块占比明显大于导出，也就是预览更糊
        assertThat(r.blockSizeFor(preview, divisor) / preview.shortEdge())
            .isGreaterThan(r.blockSizeFor(export, divisor) / export.shortEdge())
    }
}
