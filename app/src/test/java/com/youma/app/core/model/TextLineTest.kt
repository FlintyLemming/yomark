package com.youma.app.core.model

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextLineTest {

    /** "Card: 4111 2222" —— 三个 element，字符区间按空格拼接。 */
    private fun sampleLine(): TextLine {
        val e0 = TextElement(Quad.fromRect(RectF(0f, 0f, 40f, 10f)), "Card:", 0..4)
        val e1 = TextElement(Quad.fromRect(RectF(45f, 0f, 80f, 10f)), "4111", 6..9)
        val e2 = TextElement(Quad.fromRect(RectF(85f, 0f, 120f, 10f)), "2222", 11..14)
        return TextLine(
            quad = Quad.fromRect(RectF(0f, 0f, 120f, 10f)),
            text = "Card: 4111 2222",
            confidence = 0.9f,
            elements = listOf(e0, e1, e2),
        )
    }

    @Test
    fun `range inside one element returns that element's quad`() {
        val q = sampleLine().quadForRange(6..9)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 80f, 10f))
    }

    @Test
    fun `range spanning two elements covers both`() {
        val q = sampleLine().quadForRange(6..14)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 120f, 10f))
    }

    @Test
    fun `partial overlap of an element still takes the whole element`() {
        // 只命中 "4111" 中的 "11"，仍然整词覆盖 —— 词粒度是已知取舍
        val q = sampleLine().quadForRange(7..8)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 80f, 10f))
    }

    @Test
    fun `range hitting the label element drags the label in too`() {
        // 命中 "Card: 4111" 会连标签一起遮掉，这是 spec 5-3 明确接受的行为
        val q = sampleLine().quadForRange(0..9)
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 80f, 10f))
    }

    @Test
    fun `range matching no element falls back to the line quad`() {
        val q = sampleLine().quadForRange(100..110)
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 120f, 10f))
    }

    @Test
    fun `candidate defaults to enabled`() {
        val c = Candidate("a", Quad.fromRect(RectF(0f, 0f, 1f, 1f)), SensitiveKind.EMAIL, DetectorSource.RULE, 0.9f)
        assertThat(c.enabledByDefault).isTrue()
    }
}
