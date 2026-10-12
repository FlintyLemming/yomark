package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test

/**
 * 车牌号、车架号（逐行）。号牌全是编的；车架号是 GB 16735 附录 A 的「LFWADRJF011002346」、
 * 维基百科的「1M8GDM9AXKP042788」和常见的演示号码「1HGCM82633A004352」。
 */
class VehicleRuleTest {

    private val plate = DefaultRuleSet.rules.first { it.id == "plate" }
    private val vin = DefaultRuleSet.rules.first { it.id == "vin" }

    private fun plates(text: String) = plate.findIn(text).map { text.substring(it.range) }
    private fun vins(text: String) = vin.findIn(text).map { text.substring(it.range) }

    // ---------- 车牌 ----------

    @Test fun `ordinary and new energy plates with any separator`() {
        listOf("京A·12345", "沪AA1234", "京A·D12345", "京AD12345", "京A·12345D", "苏E·DF1234", "京A 12345", "沪A-12345", "粤B•1234Z")
            .forEach { assertThat(plates("车辆 $it 已入场")).containsExactly(it) }
    }

    /** 句子里紧挨着汉字也认：「您的车辆粤B12345已入场」。 */
    @Test fun `a plate inside a sentence`() {
        assertThat(plates("您的车辆粤B12345已入场，请于30分钟内离场")).containsExactly("粤B12345")
    }

    @Test fun `special plates`() {
        listOf("粤Z·1A23港", "京A·1234学", "京A·0001警", "鲁A·1234挂", "京X·1234应急", "224·001使", "使224·001", "粤204·01领", "WJ京1234X", "应急京123X")
            .forEach { assertThat(plates(it)).containsExactly(it) }
    }

    /** 序号里不会有 I、O：OCR 读成的 I、O 当 1、0 看。 */
    @Test fun `ocr letters in the serial`() {
        assertThat(plates("京A·I2345")).containsExactly("京A·I2345")
        assertThat(plates("沪B1O0O1")).containsExactly("沪B1O0O1")
    }

    @Test fun `not plates`() {
        assertThat(plates("京A1234")).isEmpty()                      // 少一位
        assertThat(plates("京A·1234567")).isEmpty()                  // 多了
        assertThat(plates("粤ICP备12345678号")).isEmpty()
        assertThat(plates("京ICP证030173号")).isEmpty()
        assertThat(plates("经营许可证：粤B2-20090059")).isEmpty()
        assertThat(plates("沪ABCD12")).isEmpty()                      // 序号三个字母
        assertThat(plates("京A1234学校")).isEmpty()                   // 一句话，不是教练车
        assertThat(plates("沪A1234港")).isEmpty()                     // 港澳车只挂粤 Z
        assertThat(plates("已有123 456使用")).isEmpty()
        assertThat(plates("IMG12345")).isEmpty()
    }

    @Test fun `plates after a label are read leniently`() {
        assertThat(plates("车牌号：粤B12345")).containsExactly("粤B12345")
        assertThat(plates("号牌号码 Plate No. 沪AA1234")).containsExactly("沪AA1234")
        assertThat(plates("车牌号码 VA·12345")).containsExactly("VA·12345")      // 军牌只在字段名后面认
        assertThat(plates("车牌 粤B***45")).containsExactly("粤B***45")
        assertThat(plates("车牌识别")).isEmpty()
        assertThat(plates("无车牌 12345")).isEmpty()
    }

    // ---------- 车架号 ----------

    @Test fun `vins with a valid check digit on their own`() {
        listOf("LFWADRJF011002346", "1M8GDM9AXKP042788", "1HGCM82633A004352")
            .forEach { assertThat(vins("车架号 $it")).containsExactly(it) }
        assertThat(vins("LFWADRJF011002346")).containsExactly("LFWADRJF011002346")
        assertThat(vins("LFWADRJFO11002346")).containsExactly("LFWADRJFO11002346")   // 第九位的 0 读成了 O
    }

    @Test fun `not vins on their own`() {
        assertThat(vins("LFWADRJF011002347")).isEmpty()              // 校验位不对
        assertThat(vins("11111111111111111")).isEmpty()              // 没有字母
        assertThat(vins("XLFWADRJF011002346")).isEmpty()             // 18 位
        assertThat(vins("ABCDEFGHJ12345678")).isEmpty()
        // 校验位对得上，但最后四位不是数字：一串编号碰巧过了校验，不是车架号；写在字段名后面的照样认
        assertThat(vins("LFWADRJF5110023AB")).isEmpty()
        assertThat(vins("VIN LFWADRJF5110023AB")).containsExactly("LFWADRJF5110023AB")
    }

    /** 字段名后面的 17 位不验校验位，打了星的、中间补了空格的也收；行驶证上中英文字段名连在一起。 */
    @Test fun `vins and engine numbers after a label`() {
        assertThat(vins("车辆识别代号 LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("VIN: LFWADRJF0******46")).containsExactly("LFWADRJF0******46")
        assertThat(vins("车架号 LFWADRJF0 11002347")).containsExactly("LFWADRJF0 11002347")
        assertThat(vins("车辆识别代号VIN LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("发动机号码 Engine No. CSS123456")).containsExactly("CSS123456")
        assertThat(vins("发动机号 4G15S12345")).containsExactly("4G15S12345")
        assertThat(vins("发动机号 ENGINE")).isEmpty()
    }

    @Test fun `masked by default as their own kinds`() {
        assertThat(plate.enabledByDefault).isTrue()
        assertThat(plate.kind).isEqualTo(SensitiveKind.LICENSE_PLATE)
        assertThat(vin.enabledByDefault).isTrue()
        assertThat(vin.kind).isEqualTo(SensitiveKind.VIN)
    }
}
