package com.yomark.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 中文识别器可能一个汉字一个 element，MlKitTextRecognizer 又用空格拼接 element，
 * 于是「收货地址」到了规则手里是「收 货 地 址」。HanView 把这种行拼回来，
 * 正常切分的行原样放过——那里的空格是版面上真有的间隙。
 */
class HanViewTest {

    @Test fun `a character-split line is joined back`() {
        assertThat(HanView.of("收 货 地 址 北 京 市").text).isEqualTo("收货地址北京市")
    }

    @Test fun `digits and latin letters next to han are joined too`() {
        assertThat(HanView.of("祁 门 路 33 号").text).isEqualTo("祁门路33号")
        // 拼回来之后「IP地址」仍然是一个词，标签的词中词挡板才挡得住它
        assertThat(HanView.of("IP 地 址 192.168.1.10").text).isEqualTo("IP地址192.168.1.10")
    }

    /** 只去紧挨汉字的空格。数字分组之间的空格是卡号自己的，不动。 */
    @Test fun `spaces between non-han tokens are kept`() {
        assertThat(HanView.of("卡 号 4111 1111 1111 1111").text).isEqualTo("卡号4111 1111 1111 1111")
    }

    @Test fun `full width punctuation counts as han`() {
        assertThat(HanView.of("收 件 人 ： 张 三").text).isEqualTo("收件人：张三")
    }

    /** 正常切分的行：「收 合肥市」之间是真的间隙，拼起来就成了「收合肥市」。 */
    @Test fun `a normally segmented line is left alone`() {
        assertThat(HanView.of("收 合肥市").text).isEqualTo("收 合肥市")
        assertThat(HanView.of("送至 祁门路33号四方新村23幢605室").text).isEqualTo("送至 祁门路33号四方新村23幢605室")
    }

    @Test fun `latin lines are left alone`() {
        assertThat(HanView.of("Card 4111 2222 3333").text).isEqualTo("Card 4111 2222 3333")
    }

    /** 证件上字距拉开的「姓 名」：单字 token 占多数，按逐字切分处理，标签才认得出。 */
    @Test fun `letter spaced labels are joined`() {
        assertThat(HanView.of("姓 名 张三").text).isEqualTo("姓名张三")
    }

    @Test fun `ranges map back onto the source line including the dropped spaces`() {
        val source = "收 货 地 址 北 京 市"
        val view = HanView.of(source)
        val range = view.toSource(4..6)                   // 视图上的「北京市」
        assertThat(source.substring(range)).isEqualTo("北 京 市")
    }

    @Test fun `ranges on an untouched line are unchanged`() {
        assertThat(HanView.of("收 合肥市").toSource(2..4)).isEqualTo(2..4)
    }
}
