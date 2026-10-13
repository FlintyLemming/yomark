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

    /**
     * 车牌前面别的字段名（「车辆：」「车位：」「Vehicle:」）不在车牌的字段名里，以前截短的地址带着它：跟着冒号的末一段一起去掉。
     * 「1号：」有数字，是地址的一截。
     */
    @Test fun `a field name before the plate is cut off with it`() = runTest {
        mapOf(
            "地址：南京市玄武区中山路1号 车辆：苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号 车型：苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号 登记车辆：苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号 停车证号：苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号 车位：苏A12345" to "南京市玄武区中山路1号",
            "地址：南京市玄武区中山路1号：苏A12345" to "南京市玄武区中山路1号",
        ).forEach { (line, address) ->
            assertWithMessage(line).that(found(line)).containsExactly(POSTAL_ADDRESS to address, LICENSE_PLATE to "苏A12345")
        }
        assertThat(found("Address: 12 Main Street, Vehicle: AB12 CDE"))
            .containsExactly(POSTAL_ADDRESS to "12 Main Street", LICENSE_PLATE to "AB12 CDE")
        val placeholder = found("地址：N/A Plate: AB12 CDE")
        assertThat(placeholder).contains(LICENSE_PLATE to "AB12 CDE")
        assertThat(placeholder.filter { it.first == POSTAL_ADDRESS && "Plate" in it.second }).isEmpty()
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

    /** 停车、违章页上的车主：「车主姓名」「车主」是字段名。 */
    @Test fun `the owner next to a plate`() = runTest {
        assertThat(found("车主 张三 车牌 京A·D12345")).containsExactly(PERSON_NAME to "张三", LICENSE_PLATE to "京A·D12345")
        assertThat(found("车主姓名 张三 车牌号码 京AD12345")).containsExactly(PERSON_NAME to "张三", LICENSE_PLATE to "京AD12345")
        assertThat(found("车主：高峰")).containsExactly(PERSON_NAME to "高峰")
        assertThat(found("所有人 Owner 高峰")).containsExactly(PERSON_NAME to "高峰")
    }

    /**
     * 车牌不是人名的旁证：停车缴费、ETC、违章页上挨着车牌的是停车场、地名、店名，从字面猜也像人名（「万象城」「汤山」「杨国福」）。
     * 以前车牌当旁证，这些全被当成车主打了码。没有字段名的「张三 苏A12345」也就只认车牌。
     */
    @Test fun `places next to a plate are not names`() = runTest {
        assertThat(found("停车缴费", "苏A12345", "万象城 B2层", "入场时间 2024-05-01 10:00").filter { it.first == PERSON_NAME }).isEmpty()
        assertThat(found("苏A12345", "汤山")).containsExactly(LICENSE_PLATE to "苏A12345")
        listOf(
            "苏A12345 白马湖", "苏A12345 程桥", "苏A12345 汤山", "苏A12345 万象城", "京A12345 周大福", "苏A12345 杨国福", "张三 苏A12345",
        ).forEach { line ->
            assertWithMessage(line).that(found(line).map { it.first }).containsExactly(LICENSE_PLATE)
        }
    }

    /**
     * 车主 app 上「车主」和后面的词只隔着空格、后面那个词是个寻常词（福利、权益、余额……）：并排的两个词，不是「车主 张三」。
     * 「所有人」「负责人」也是这样。
     */
    @Test fun `car-app words after the owner label are not names`() = runTest {
        listOf(
            "车主 福利", "车主 权益", "车主 余额", "车主 钱包", "车主 商城", "车主 平台", "车主 保养", "车主 车险", "车主 金卡", "车主 任务",
            "车主 安全", "车主 卫士", "车主 何时到", "车主 余额 120.00", "车主 权益 3", "车主 金卡 6折", "所有人 福利", "负责人 平台",
        ).forEach { line ->
            assertWithMessage(line).that(found(line).filter { it.first == PERSON_NAME }).isEmpty()
        }
        listOf("车主 张三" to "张三", "车主 张伟" to "张伟", "所有人 李明" to "李明").forEach { (line, name) ->
            assertWithMessage(line).that(found(line)).containsExactly(PERSON_NAME to name)
        }
    }

    /** 「车主」也是常用词：后面紧跟着字的、跟着一句话的，不是名字。 */
    @Test fun `car owners in a chat are not names`() = runTest {
        listOf("车主请尽快挪车", "车主都很满意", "车主 麻烦挪一下车", "@车主 你的车挡路了").forEach { line ->
            assertWithMessage(line).that(found(line).filter { it.first == PERSON_NAME }).isEmpty()
        }
    }
}
