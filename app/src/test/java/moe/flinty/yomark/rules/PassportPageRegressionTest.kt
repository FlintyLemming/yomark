package moe.flinty.yomark.rules

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.BIRTH_DATE
import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.PASSPORT
import moe.flinty.yomark.core.model.SensitiveKind.PERSON_NAME
import moe.flinty.yomark.core.model.SensitiveKind.POSTAL_ADDRESS
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 真机漏检的回归样本：美国护照资料页样张（Happy Traveler，用户反馈截图里用的那张）。
 *
 * 0.11.1 在这一页上一处文字都没认出来：OCR 把字距宽的大写字拆开、把斜压的水印和十来行串成一行，
 * 机读码对不上 44 个字符；字段名都在值的上面，逐行的规则够不着。只有按需跑的 AI 增强识别圈出了半行机读码。
 *
 * 行和框照抄自修好之后 PP-OCRv6 small 在这张 900×600 的图上的输出；几段拼成一行的按段给坐标。
 * 每段里的字按等宽排开（真实的字框不等宽，差别只在一两个像素）。
 */
@RunWith(RobolectricTestRunner::class)
class PassportPageRegressionTest {

    private class Seg(val text: String, val left: Float, val right: Float)

    private class Line(val top: Float, val bottom: Float, vararg val segs: Seg)

    private val page = listOf(
        Line(33f, 76f, Seg("PASSEPORT/PASAPORTE", 33f, 200f), Seg("THEUNITED STATES OF AMERICA", 281f, 881f)),
        Line(36f, 62f, Seg("PASSPORT", 62f, 170f)),
        Line(
            84f, 106f, Seg("Type/Type/Tipo", 275f, 375f), Seg("Code/Code/Código", 386f, 508f),
            Seg("Passport No.No. du Passeport/No.de Pasaporte", 529f, 825f),
        ),
        Line(100f, 135f, Seg("E00007730", 591f, 758f)),
        Line(103f, 128f, Seg("USA", 431f, 477f)),
        Line(104f, 126f, Seg("P", 318f, 339f)),
        Line(113f, 163f, Seg("USA", 49f, 181f)),
        Line(135f, 351f, Seg("MEECIMEN", 262f, 779f)),
        Line(141f, 161f, Seg("Surname/No m/A pellidos", 253f, 403f)),
        Line(158f, 184f, Seg("TRAVELER", 257f, 380f)),
        Line(184f, 203f, Seg("Given na me s/P rón oms/N om b re s", 251f, 454f)),
        Line(201f, 226f, Seg("HAPPY", 255f, 338f)),
        Line(230f, 247f, Seg("Nationalit y/N ation alit é /Nac iona lidad", 300f, 523f)),
        Line(265f, 288f, Seg("Date of birth/D ate de naissande/Fecha de", 299f, 565f)),
        Line(276f, 328f, Seg("05 FEB 1965", 299f, 454f), Seg("E", 451f, 505f)),
        Line(306f, 324f, Seg("Sex/Sexe/Sex", 300f, 382f)),
        Line(322f, 344f, Seg("F", 304f, 323f)),
        Line(342f, 364f, Seg("Place of birth/Liou de naissance/L uga r de nacimie nta", 300f, 630f)),
        Line(357f, 385f, Seg("WASHINGTON.D.C..U.S.A.", 303f, 616f)),
        Line(384f, 401f, Seg("Date of issue/Date de dé liv rance/Fecha de expedició n", 300f, 632f)),
        Line(397f, 423f, Seg("15 OCT 2020", 302f, 449f)),
        Line(420f, 441f, Seg("Date of expiration/Date d'expiration/Fecha de caducidad", 298f, 649f)),
        Line(437f, 462f, Seg("14 OCT 2030", 302f, 450f)),
        Line(454f, 480f, Seg("Eo", 177f, 263f), Seg("Authority/Autorité/Autoridad", 299f, 480f)),
        Line(459f, 479f, Seg("05 02 65", 738f, 795f)),
        Line(479f, 498f, Seg("UNITED STATES DEPARTMENT OF STATE", 298f, 663f)),
        Line(506f, 535f, Seg("P<USATRAVELER<<HAPPY<<<<<<<<<<<<<<<<<<<<<<<<", 23f, 864f)),
        Line(558f, 589f, Seg("E000077303USA6502056F301 01 49500101920<091 824", 22f, 864f)),
    )

    private val expected = listOf(
        PASSPORT to "E00007730",
        PERSON_NAME to "TRAVELER",
        PERSON_NAME to "HAPPY",
        BIRTH_DATE to "05 FEB 1965",
        POSTAL_ADDRESS to "WASHINGTON.D.C..U.S.A.",
        DATETIME to "15 OCT 2020",
        DATETIME to "14 OCT 2030",
        BIRTH_DATE to "05 02 65",
        PASSPORT to "P<USATRAVELER<<HAPPY<<<<<<<<<<<<<<<<<<<<<<<<",
        PASSPORT to "E000077303USA6502056F301 01 49500101920<091 824",
    )

    /**
     * 每段里的字等宽排开；段与段之间是一个没有框的空格（同 RowGrouper）。
     * [perWord] 时一个词一个 element（ML Kit），否则一个字一个（PP-OCR）。
     */
    private fun textLines(perWord: Boolean): List<TextLine> = page.map { line ->
        val text = line.segs.joinToString(" ") { it.text }
        val elements = ArrayList<TextElement>()
        var offset = 0
        line.segs.forEach { seg ->
            val w = (seg.right - seg.left) / seg.text.length
            fun box(r: IntRange) = Quad.fromRect(RectF(seg.left + r.first * w, line.top, seg.left + (r.last + 1) * w, line.bottom))
            val units = if (perWord) Regex("""\S+""").findAll(seg.text).map { it.range }.toList()
            else seg.text.indices.filter { seg.text[it] != ' ' }.map { it..it }
            units.forEach { r ->
                elements += TextElement(box(r), seg.text.substring(r), (r.first + offset)..(r.last + offset))
            }
            offset += seg.text.length + 1
        }
        TextLine(Quad.fromRect(RectF(line.segs.first().left, line.top, line.segs.last().right, line.bottom)), text, 0.95f, elements)
    }

    /** 候选框盖住的那几个 element 拼回原文：框的上下沿就是那一行的上下沿。 */
    private fun Candidate.text(lines: List<TextLine>): String {
        val b = quad.bounds()
        fun inside(e: TextElement) = e.quad.bounds().let { b.contains(it.centerX(), it.centerY()) }
        val line = lines.first { l ->
            val lb = l.quad.bounds()
            kotlin.math.abs(lb.top - b.top) < 0.5f && kotlin.math.abs(lb.bottom - b.bottom) < 0.5f && l.elements.any(::inside)
        }
        val hit = line.elements.filter(::inside)
        return line.text.substring(hit.minOf { it.range.first }..hit.maxOf { it.range.last })
    }

    private suspend fun found(lines: List<TextLine>, rules: List<Rule> = DefaultRuleSet.rules): List<Pair<SensitiveKind, String>> =
        RuleClassifier(rules).classify(lines).map { it.kind to it.text(lines) }

    @Test fun `everything on the passport page is found, character boxes`() = runTest {
        assertThat(found(textLines(perWord = false))).containsExactlyElementsIn(expected)
    }

    @Test fun `everything on the passport page is found, word boxes`() = runTest {
        assertThat(found(textLines(perWord = true))).containsExactlyElementsIn(expected)
    }

    /** 生日、姓名、护照号、出生地、机读码默认打码；签发日期、有效期只圈出。 */
    @Test fun `what is masked and what is only outlined`() = runTest {
        val lines = textLines(perWord = false)
        val out = RuleClassifier(DefaultRuleSet.rules).classify(lines)
        assertThat(out.filter { !it.enabledByDefault }.map { it.text(lines) }).containsExactly("15 OCT 2020", "14 OCT 2030")
    }

    /**
     * 用户在设置里改过某一类的状态，规则被包了一层：包装要把整页的认法也转过去，
     * 不然「Surname」下面的名字就只剩逐行的认法，一个也认不出。
     */
    @Test fun `a rule whose state the user changed still looks at the whole page`() = runTest {
        val config = RecognitionConfig(ruleOverrides = mapOf("name" to RuleState.OUTLINED, "birthdate" to RuleState.OUTLINED))
        val lines = textLines(perWord = false)
        val out = RuleClassifier(RuleCatalog.rulesFor(config)).classify(lines)
        assertThat(out.filter { it.kind == PERSON_NAME }.map { it.text(lines) to it.enabledByDefault })
            .containsExactly("TRAVELER" to false, "HAPPY" to false)
        // 出生日期只圈出了，同一处也不再另出一个日期时间
        assertThat(out.filter { it.kind == DATETIME }.map { it.text(lines) }).containsExactly("15 OCT 2020", "14 OCT 2030")
    }

    /** 出生日期一类关掉：生日回到日期时间，只圈出。 */
    @Test fun `with birth dates off the date of birth is an ordinary date`() = runTest {
        val config = RecognitionConfig(ruleOverrides = mapOf("birthdate" to RuleState.OFF))
        val lines = textLines(perWord = false)
        val dates = RuleClassifier(RuleCatalog.rulesFor(config)).classify(lines).filter { it.kind == DATETIME }
        assertThat(dates.map { it.text(lines) }).containsExactly("05 FEB 1965", "15 OCT 2020", "14 OCT 2030")
    }
}
