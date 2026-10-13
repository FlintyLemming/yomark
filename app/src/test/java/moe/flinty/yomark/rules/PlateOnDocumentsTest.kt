package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.model.SensitiveKind.ID_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.LICENSE_PLATE
import moe.flinty.yomark.core.model.SensitiveKind.PERSON_NAME
import moe.flinty.yomark.core.model.SensitiveKind.POSTAL_ADDRESS
import moe.flinty.yomark.core.model.SensitiveKind.VIN
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 车牌和证件、单据上别的字段同处一页：同一行的地址、车牌字段名前面的名字、车架号、行驶证副页的档案编号、车主。
 * 例子照抄车牌并进证件分支后探针跑出来的漏遮、错遮（整页、出厂规则表）。
 */
@RunWith(RobolectricTestRunner::class)
class PlateOnDocumentsTest {

    private suspend fun found(lines: List<TextLine>) =
        RuleClassifier(DefaultRuleSet.rules).classify(lines).map { it.kind to textUnder(it.quad, lines) }

    private suspend fun found(vararg texts: String) = found(textLines(*texts))

    // ---------- 同一行的地址和车牌 ----------

    /**
     * 地址取到行尾，后半截是车牌：以前地址整个让给车牌，一个字都没遮。现在截到车牌前面，夹在中间的车牌字段名不遮。
     */
    @Test fun `an address and a plate on one line are both masked`() = runTest {
        mapOf(
            "地址：南京市玄武区中山路1号 车牌：苏A12345" to "南京市玄武区中山路1号",
            "收货地址 南京市玄武区中山路1号 车牌号 苏A12345" to "南京市玄武区中山路1号",
            "住址 江苏省南京市玄武区某某路1号 号牌号码 苏A12345" to "江苏省南京市玄武区某某路1号",
            "地址：南京市玄武区中山路1号 苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号车牌苏A12345" to "南京市玄武区中山路1号",
        ).forEach { (line, address) ->
            assertWithMessage(line).that(found(line)).containsExactly(POSTAL_ADDRESS to address, LICENSE_PLATE to "苏A12345")
        }
        assertThat(found("Address: 12 Main Street, Plate No. AB12 CDE"))
            .containsExactly(POSTAL_ADDRESS to "12 Main Street", LICENSE_PLATE to "AB12 CDE")
    }

    /** 截掉车牌以后只剩一个字的，或者车牌占了大半的（「苏A12345D」像爱尔兰邮编），地址整个让掉。 */
    @Test fun `an address that is mostly the plate yields`() = runTest {
        assertThat(found("住址 1 苏A12345")).containsExactly(LICENSE_PLATE to "苏A12345")
        assertThat(found("苏A12345D")).containsExactly(LICENSE_PLATE to "苏A12345D")
    }

    // ---------- 车牌字段名是下一个字段 ----------

    /** 名字到车牌字段名为止：以前「号牌号码」不算下一个字段，「所有人」后面的名字丢了；连成一串的吞进了「车牌」。 */
    @Test fun `a plate field ends the name before it`() = runTest {
        mapOf(
            "所有人 张三 号牌号码 苏A12345" to "张三",
            "所有人 张三 车牌号 苏A12345" to "张三",
            "姓名张三车牌苏A12345" to "张三",
            "姓名 张三车牌 苏A12345" to "张三",
            "姓名 张三号牌号码苏A12345" to "张三",
        ).forEach { (line, name) ->
            assertWithMessage(line).that(found(line)).containsExactly(PERSON_NAME to name, LICENSE_PLATE to "苏A12345")
        }
    }

    /** 空着没填的「姓名 Name」，右边一栏是「车牌号码 Plate No. 苏A12345」：那一栏是下一个字段，不是名字。 */
    @Test fun `an empty name cell next to a plate field`() = runTest {
        val lines = listOf(Row("姓名 Name", 40f, 100f), Row("车牌号码 Plate No. 苏A12345", 300f, 100f)).textLines()
        assertThat(found(lines)).containsExactly(LICENSE_PLATE to "苏A12345")
    }

    /** 住址折行，下一行是值那一栏里的「号牌号码 苏A12345」：住址到上一行为止，再下一行也不接。 */
    @Test fun `an address does not continue into a plate row`() = runTest {
        val lines = listOf(
            Row("住址 江苏省南京市玄武区某某路1号", 40f, 100f),
            Row("号牌号码 苏A12345", 76f, 124f),
            Row("新街口停车场B区", 76f, 148f),
        ).textLines()
        assertThat(found(lines)).containsExactly(POSTAL_ADDRESS to "江苏省南京市玄武区某某路1号", LICENSE_PLATE to "苏A12345")
    }

    // ---------- 车架号、证件号里的车牌 ----------

    /** OCR 在车架号中间补了空格，头一段「LFV2A21」像巴西的牌：让给车架号。 */
    @Test fun `a VIN with a space inside is not also a plate`() = runTest {
        listOf("车辆识别代号 VIN LFV2A21 K6D4123456", "车架号 LFV2A21 K6D4123456").forEach { line ->
            assertWithMessage(line).that(found(line)).containsExactly(VIN to "LFV2A21 K6D4123456")
        }
    }

    // ---------- 行驶证、驾驶证副页的档案编号 ----------

    /** 「档案编号 File No.」：以前译名不认，号码只圈成快递单号。同一行在车牌后面、分成几段的也认。 */
    @Test fun `the file number on the back of a licence is an id number`() = runTest {
        assertThat(found("档案编号 File No. 320100123456")).containsExactly(ID_NUMBER to "320100123456")
        assertThat(found("号牌号码 Plate No. 苏A12345 档案编号 File No. 320100000123"))
            .containsExactly(LICENSE_PLATE to "苏A12345", ID_NUMBER to "320100000123")
        val cells = listOf(
            Row("号牌号码 Plate No.", 40f, 100f),
            Row("苏A12345", 300f, 100f),
            Row("档案编号 File No.", 500f, 100f),
            Row("320100000123", 760f, 100f),
        ).textLines()
        assertThat(found(cells)).containsExactly(LICENSE_PLATE to "苏A12345", ID_NUMBER to "320100000123")
    }

    // ---------- 车主 ----------

    /** 停车、违章页上的车主：「车主姓名」「车主」是字段名；车牌旁边从字面猜出的名字，车牌是旁证。 */
    @Test fun `the owner next to a plate`() = runTest {
        assertThat(found("车主 张三 车牌 京A·D12345")).containsExactly(PERSON_NAME to "张三", LICENSE_PLATE to "京A·D12345")
        assertThat(found("车主姓名 张三 车牌号码 京AD12345")).containsExactly(PERSON_NAME to "张三", LICENSE_PLATE to "京AD12345")
        assertThat(found("张三 苏A12345")).containsExactly(PERSON_NAME to "张三", LICENSE_PLATE to "苏A12345")
    }

    /** 「车主」也是常用词：后面紧跟着字的、跟着一句话的，不是名字。 */
    @Test fun `car owners in a chat are not names`() = runTest {
        listOf("车主请尽快挪车", "车主都很满意", "车主 麻烦挪一下车", "@车主 你的车挡路了").forEach { line ->
            assertWithMessage(line).that(found(line).filter { it.first == PERSON_NAME }).isEmpty()
        }
    }
}
