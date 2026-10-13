package moe.flinty.yomark.rules

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * 竖着、倒着拍的页：规则按「下面 = +y」认版面，RuleClassifier 先把框转到阅读方向上。
 * 同一页转过来，认出来的东西和正着的一样——值在字段名下面（StackedField）、名字下一行是打了星的证件号（旁证）。
 */
@RunWith(RobolectricTestRunner::class)
class RotatedPageRuleTest {

    private val rows = listOf(
        "出票成功",
        "李思雨",
        "3201**********1234",
        "Surname/Nom/Apellidos",
        "TRAVELER",
        "Date of birth/Date de naissance",
        "05 FEB 1965",
    )

    /**
     * 正着排的一页：每个字一个 element（同 PP-OCR），字宽 20、字高 20、行距 40，再整页绕 (500, 500) 顺时针转 [degrees] 度。
     * 每个框的四个角按阅读方向排（左上、右上、右下、左下），转过去仍是这个顺序，同 PP-OCR 映射回原图的框。
     */
    private fun page(degrees: Double): List<TextLine> {
        val turn = turning(degrees)
        fun quad(l: Float, t: Float, rt: Float, b: Float) = Quad(turn(l, t), turn(rt, t), turn(rt, b), turn(l, b))
        return rows.mapIndexed { row, text ->
            val top = 100f + row * 40f
            val els = text.indices.filter { text[it] != ' ' }.map { i ->
                TextElement(quad(40f + i * 20f, top, 60f + i * 20f, top + 20f), text[i].toString(), i..i)
            }
            TextLine(quad(40f, top, 40f + text.length * 20f, top + 20f), text, 0.95f, els)
        }
    }

    private suspend fun found(lines: List<TextLine>) =
        RuleClassifier(DefaultRuleSet.rules).classify(lines).map { Triple(it.kind, textUnder(it.quad, lines), it.enabledByDefault) }

    @Test fun `the upright page finds the stacked values and the anchored name`() = runTest {
        assertThat(found(page(0.0))).containsAtLeast(
            Triple(SensitiveKind.PERSON_NAME, "李思雨", true),
            Triple(SensitiveKind.PERSON_NAME, "TRAVELER", true),
            Triple(SensitiveKind.BIRTH_DATE, "05 FEB 1965", true),
        )
    }

    @Test fun `a turned page finds the same things`() = runTest {
        val upright = found(page(0.0))
        for (degrees in listOf(90.0, 180.0, 270.0, 94.0, -3.5)) {
            assertThat(found(page(degrees))).containsExactlyElementsIn(upright)
        }
    }

    // ---------- 证件：按版面认的几种（IdDocumentRows） ----------

    /** [rows] 整页绕 (500, 500) 顺时针转 [degrees] 度，同 [page]。 */
    private fun turned(rows: List<Row>, degrees: Double): List<TextLine> = rows.textLines(turning(degrees))

    /** 绕 (500, 500) 顺时针转 [degrees] 度。 */
    private fun turning(degrees: Double): (Float, Float) -> PointF {
        val r = Math.toRadians(degrees)
        return { x, y ->
            val dx = x - 500f
            val dy = y - 500f
            PointF((500 + dx * cos(r) - dy * sin(r)).toFloat(), (500 + dx * sin(r) + dy * cos(r)).toFloat())
        }
    }

    /**
     * 版面上的几种认法都按「下面 = +y」量：住址折行（FollowingLines）、字段名读坏了的人像面（IdCardFront）、
     * 字段名和值隔着大半栏（LabeledField 接同一排右边那一段）、值在字段名下面（StackedField）、名字下面的拼音（PinyinBelow）。
     * 竖着、倒着、拍歪了的页上，RuleClassifier 先把框转到阅读方向上，认出来的和正着的一模一样。
     */
    @Test fun `id documents turned any way find the same things`() = runTest {
        val documents = mapOf(
            "身份证" to IdDocumentRows.idCard,
            "身份证照片" to IdDocumentRows.idPhoto,
            "驾驶证" to IdDocumentRows.drivingLicence,
            "行驶证" to IdDocumentRows.vehicleLicence,
            "护照" to IdDocumentRows.chinesePassport,
            "通行证" to IdDocumentRows.travelPermit,
        )
        documents.forEach { (name, rows) ->
            val upright = found(turned(rows, 0.0))
            for (degrees in listOf(90.0, 180.0, 270.0, 94.0, -3.5)) {
                assertWithMessage("$name 转 $degrees°").that(found(turned(rows, degrees))).containsExactlyElementsIn(upright)
            }
        }
    }

    /** 正着的时候认出了该认的：上面那条只比转前转后，这里看转前的就是对的。 */
    @Test fun `the upright id documents`() = runTest {
        suspend fun masked(rows: List<Row>) = found(turned(rows, 0.0)).filter { it.third }.map { it.first to it.second }
        assertThat(masked(IdDocumentRows.idPhoto)).containsAtLeast(
            SensitiveKind.PERSON_NAME to "宋语桐",
            SensitiveKind.BIRTH_DATE to "201 2 年4 月17日",
            SensitiveKind.POSTAL_ADDRESS to "浙江省杭州市上城区解放路",
            SensitiveKind.POSTAL_ADDRESS to "128号",
            SensitiveKind.ID_NUMBER to "3 301 02201204174527",
        )
        assertThat(masked(IdDocumentRows.drivingLicence)).containsAtLeast(
            SensitiveKind.ID_NUMBER to "320105198602143729",
            SensitiveKind.PERSON_NAME to "周 以宁",
            SensitiveKind.POSTAL_ADDRESS to "江苏省南京市玄武区北京东路",
            SensitiveKind.POSTAL_ADDRESS to "41号3栋2单元602室",
            SensitiveKind.BIRTH_DATE to "1986-02-14",
        )
        assertThat(masked(IdDocumentRows.chinesePassport)).containsAtLeast(
            SensitiveKind.PERSON_NAME to "周以宁",
            SensitiveKind.PERSON_NAME to "ZHOU, YINING",
            SensitiveKind.BIRTH_DATE to "14 FEB 1986",
        )
        assertThat(masked(IdDocumentRows.travelPermit)).contains(SensitiveKind.PERSON_NAME to "LI, XIAOMING")
    }

    /** 候选的框始终是图上的：转过的页上，名字的框落在转过去的那三个字上。 */
    @Test fun `candidate boxes stay in image coordinates`() = runTest {
        val lines = page(90.0)
        val name = RuleClassifier(DefaultRuleSet.rules).classify(lines).first { it.kind == SensitiveKind.PERSON_NAME }
        val b = name.quad.bounds()
        // 正着时在 (40, 140)–(100, 160)；绕 (500, 500) 顺时针转 90° 是 (x, y) → (1000 − y, x)
        val expected = RectF(840f, 40f, 860f, 100f)
        assertThat(b.left).isWithin(0.01f).of(expected.left)
        assertThat(b.top).isWithin(0.01f).of(expected.top)
        assertThat(b.right).isWithin(0.01f).of(expected.right)
        assertThat(b.bottom).isWithin(0.01f).of(expected.bottom)
    }
}
