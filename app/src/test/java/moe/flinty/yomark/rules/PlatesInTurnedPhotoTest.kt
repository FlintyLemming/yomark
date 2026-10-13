package moe.flinty.yomark.rules

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.TextRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * 正着的聊天截图里发了一张车的照片，照片转了直角或者拍歪了：照片上的字少，RuleClassifier 不单分一组，
 * 上下两行的牌子、孤零零一行的牌子要按照片上的字自己的方向量（Page.boxIn），合并时也按各自的方向（Candidate.readingDegrees）。
 * 以前转 90°、180°、270°、30°、45° 的两行牌子一处都没认出；照片上摞着的四块牌子合成了一个框。
 *
 * 牌子的坐标照抄 PlatePhotoOcrTest 那张合成照片（900×1390）正着读出来的框。
 */
@RunWith(RobolectricTestRunner::class)
class PlatesInTurnedPhotoTest {

    /** 八行正着的聊天，每行二十多个字，比照片上的字长得多。 */
    private val chat = listOf(
        "今天下午我把车停在小区东门口那边了你到了之后说一声",
        "好的我大概还有二十分钟就到了路上有点堵车别着急啊",
        "你停的是哪个位置啊我怕到时候找不到你的车子在哪里",
        "就在保安亭旁边靠墙的那一排第三个车位上面写着访客",
        "我还是拍张照片发给你吧这样你一看就知道是哪一辆了",
        "收到了照片挺清楚的我等会儿直接过去找你就行了谢谢",
        "不客气路上注意安全到了门口保安问的话就说找我就行",
        "明白了那我先挂了待会儿见面再细聊这个事情先这样吧",
    ).mapIndexed { i, t -> Row(t, 40f, 100f + i * 70f, 40f) }

    private val yellow = listOf(Row(457f, 47f, Segment("苏·E", 252f, 343f)), Row(495f, 82f, Segment("05863", 204f, 415f)))

    private val japanese = listOf(Row(678f, 37f, Segment("品川 330", 515f, 624f)), Row(709f, 78f, Segment("さ 12-34", 478f, 689f)))

    /** 照片上的九行：七块牌子，两块是上下两行的。 */
    private val photo = listOf(
        Row(68f, 89f, Segment("苏B·ILE22", 122f, 463f)),
        Row(270f, 78f, Segment("粤B·D12345", 405f, 735f)),
    ) + yellow + japanese + listOf(
        Row(891f, 70f, Segment("AB12 CDE", 96f, 368f)),
        Row(1070f, 64f, Segment("AB-123-CD", 480f, 741f)),
        Row(1245f, 66f, Segment("B MW 1234", 204f, 468f)),
    )

    /** 照片（900×1390）绕自己的中心顺时针转 [degrees] 度，贴在聊天下面。框的四个角仍按阅读方向排，同 PP-OCR。 */
    private fun pasted(rows: List<Row>, degrees: Double): List<TextLine> {
        val r = Math.toRadians(degrees)
        val (cx, cy) = 450f to 695f
        return rows.textLines { x, y ->
            val dx = x - cx
            val dy = y - cy
            PointF((cx + dx * cos(r) - dy * sin(r)).toFloat(), (1800 + dx * sin(r) + dy * cos(r)).toFloat())
        }
    }

    private suspend fun plates(lines: List<TextLine>): List<Pair<String, Boolean>> =
        RuleClassifier(DefaultRuleSet.rules).classify(lines).filter { it.kind == SensitiveKind.LICENSE_PLATE }
            .map { textUnder(it.quad, lines) to it.enabledByDefault }

    private val turns = listOf(0.0, 90.0, 180.0, 270.0, 30.0, 45.0)

    @Test fun `a two-row plate in a turned photo inside a chat`() = runTest {
        turns.forEach { d ->
            assertWithMessage("转 $d°").that(plates(chat.textLines() + pasted(yellow, d))).containsExactly("苏·E / 05863" to true)
        }
    }

    @Test fun `two two-row plates in a photo turned a quarter`() = runTest {
        listOf(90.0, 180.0, 270.0).forEach { d ->
            assertWithMessage("转 $d°").that(plates(chat.textLines() + pasted(yellow + japanese, d)))
                .containsExactly("苏·E / 05863" to true, "品川 330 / さ 12-34" to true)
        }
    }

    /** 整张照片拍歪了 30°、45°：七块牌子同正着的一样，德国的那块照样只圈出。 */
    @Test fun `the whole plate photo tilted inside a chat`() = runTest {
        val upright = plates(pasted(photo, 0.0))
        assertThat(upright).containsExactly(
            "苏B·ILE22" to true, "粤B·D12345" to true, "苏·E / 05863" to true, "品川 330 / さ 12-34" to true,
            "AB12 CDE" to true, "AB-123-CD" to true, "B MW 1234" to false,
        )
        listOf(0.0, 30.0, 45.0).forEach { d ->
            assertWithMessage("转 $d°").that(plates(chat.textLines() + pasted(photo, d))).containsExactlyElementsIn(upright)
        }
    }

    /** 照片上摞着的四块法国牌子，相隔 80 像素：合并之后还是四个框，每个框只盖一块。 */
    @Test fun `stacked plates in a turned photo stay apart after merging`() = runTest {
        val stacked = listOf("AB-123-CD", "EH-207-PB", "GF-456-KL", "DM-789-ZT").mapIndexed { i, t -> Row(t, 100f, 400f + i * 80f, 36f) }
        listOf(90.0, 270.0, 30.0, 45.0).forEach { d ->
            val lines = chat.textLines() + pasted(stacked, d)
            val merged = analyze(lines).filter { it.kind == SensitiveKind.LICENSE_PLATE }
            assertWithMessage("转 $d°").that(merged.map { textUnder(it.quad, lines) })
                .containsExactly("AB-123-CD", "EH-207-PB", "GF-456-KL", "DM-789-ZT")
        }
    }

    /** 同 app 上一样识别、判定、合并（只是 OCR 换成现成的这几行）。 */
    private suspend fun analyze(lines: List<TextLine>): List<Candidate> {
        val cached = object : TextRecognizer {
            override val id = "cached"
            override suspend fun recognize(image: SourceImage) = lines
        }
        val blank = SourceImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 1f, 1, 1, "image/png")
        return RedactionEngine(cached, emptyList(), listOf(RuleClassifier(DefaultRuleSet.rules))).analyze(blank).candidates
    }
}
