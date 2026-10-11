package moe.flinty.yomark.engine

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.mlkit.BarcodeCandidates
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 真机上商品图的印花被判成条码并**默认打码**，把整块图涂黑。
 * 修法不是关掉宽松模式（那会丢掉倾斜的收款码），而是让解不出内容的那部分只圈不打码。
 */
@RunWith(RobolectricTestRunner::class)
class BarcodeCandidateTest {

    private val quad = Quad.fromRect(RectF(10f, 10f, 90f, 50f))

    @Test fun `decodable barcode is masked by default`() {
        val c = BarcodeCandidates.from(0, quad, decodable = true)
        assertThat(c.enabledByDefault).isTrue()
        assertThat(c.kind).isEqualTo(SensitiveKind.BARCODE)
        assertThat(c.source).isEqualTo(DetectorSource.BARCODE)
    }

    /** 解不出内容 = 可能只是布料纹理。圈出来，交给导出拦截兜底。 */
    @Test fun `undecodable barcode is only outlined`() {
        assertThat(BarcodeCandidates.from(0, quad, decodable = false).enabledByDefault).isFalse()
    }

    /** 设置里条码设成「仅圈出」：解得出内容的也只圈出。 */
    @Test fun `decodable barcode is only outlined when barcodes are set to outline`() {
        assertThat(BarcodeCandidates.from(0, quad, decodable = true, masked = false).enabledByDefault).isFalse()
    }

    /** 反过来不成立：设成「打码」也不会把解不出内容的疑似条码打上码。 */
    @Test fun `undecodable barcode stays outlined even when barcodes are set to mask`() {
        assertThat(BarcodeCandidates.from(0, quad, decodable = false, masked = true).enabledByDefault).isFalse()
    }

    @Test fun `undecodable barcode carries lower confidence`() {
        val sure = BarcodeCandidates.from(0, quad, decodable = true)
        val maybe = BarcodeCandidates.from(1, quad, decodable = false)
        assertThat(maybe.confidence).isLessThan(sure.confidence)
    }

    @Test fun `ids are distinct per index`() {
        assertThat(BarcodeCandidates.from(0, quad, true).id)
            .isNotEqualTo(BarcodeCandidates.from(1, quad, true).id)
    }

    /**
     * 真机上二维码打完码，色块下沿和右沿露出一条码：ML Kit 的右、下边比真实边界短 1–3 px。
     * 外扩之后，原框每个角都要落在新框里、离边至少 3 px——四个方向都要，不论倾斜。
     */
    @Test fun `quad is padded past the edges ML Kit falls short of`() {
        val tilted = Quad(PointF(100f, 50f), PointF(190f, 80f), PointF(160f, 170f), PointF(70f, 140f))
        listOf(quad, tilted).forEach { raw ->
            val padded = BarcodeCandidates.from(0, raw, decodable = true).quad
            raw.points().forEach { corner ->
                assertThat(padded.contains(corner)).isTrue()
                assertThat(padded.distanceTo(corner)).isAtLeast(3f)
            }
        }
    }

    /**
     * 外扩不能大过二维码的静区（4 个模块），否则会盖到旁边的字。
     * 取一个常见的小码：version 3（29 模块），每模块 2 px，静区 8 px。
     */
    @Test fun `padding stays inside the quiet zone of a small QR code`() {
        val side = 29 * 2f
        val qr = Quad.fromRect(RectF(100f, 100f, 100f + side, 100f + side))
        assertThat(BarcodeCandidates.marginFor(qr)).isLessThan(4 * 2f)
    }
}
