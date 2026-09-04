package com.youma.app.engine

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.engine.mlkit.BarcodeCandidates
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

    @Test fun `undecodable barcode carries lower confidence`() {
        val sure = BarcodeCandidates.from(0, quad, decodable = true)
        val maybe = BarcodeCandidates.from(1, quad, decodable = false)
        assertThat(maybe.confidence).isLessThan(sure.confidence)
    }

    @Test fun `ids are distinct per index`() {
        assertThat(BarcodeCandidates.from(0, quad, true).id)
            .isNotEqualTo(BarcodeCandidates.from(1, quad, true).id)
    }
}
