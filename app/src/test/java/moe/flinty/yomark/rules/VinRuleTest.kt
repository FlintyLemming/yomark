package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test

/**
 * 车架号、发动机号（逐行）。车架号是 GB 16735 附录 A 的「LFWADRJF011002346」、
 * 维基百科的「1M8GDM9AXKP042788」和常见的演示号码「1HGCM82633A004352」。
 */
class VinRuleTest {

    private val vin = DefaultRuleSet.rules.first { it.id == "vin" }

    private fun vins(text: String) = vin.findIn(text).map { text.substring(it.range) }

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

    /** 「DD」加时间戳再加一位的订单号碰巧过得了校验：跟在「订单号」后面的不算，跟在车架号字段名后面的照认。 */
    @Test fun `an order number that happens to pass the check digit`() {
        assertThat(vins("DD202507091718170")).containsExactly("DD202507091718170")
        assertThat(vins("订单号 DD202507091718170")).isEmpty()
        assertThat(vins("Order DD202507091718170")).isEmpty()
        assertThat(vins("车架号 DD202507091718170")).containsExactly("DD202507091718170")
    }

    /** 字段名后面的 17 位不验校验位，打了星的、中间补了空格的也收；行驶证上中英文字段名连在一起。 */
    @Test fun `vins and engine numbers after a label`() {
        assertThat(vins("车辆识别代号 LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("VIN: LFWADRJF0******46")).containsExactly("LFWADRJF0******46")
        assertThat(vins("车架号 LFWADRJF0 11002347")).containsExactly("LFWADRJF0 11002347")
        assertThat(vins("车辆识别代号VIN LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("车辆识别代号 (VIN) LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("车辆识别代号 （VIN） LFWADRJF011002347")).containsExactly("LFWADRJF011002347")
        assertThat(vins("发动机号码 Engine No. CSS123456")).containsExactly("CSS123456")
        assertThat(vins("发动机号 4G15S12345")).containsExactly("4G15S12345")
        assertThat(vins("发动机号 ENGINE")).isEmpty()
    }

    @Test fun `masked by default as its own kind`() {
        assertThat(vin.enabledByDefault).isTrue()
        assertThat(vin.kind).isEqualTo(SensitiveKind.VIN)
    }
}
