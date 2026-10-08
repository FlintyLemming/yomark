package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 没有字段名的地址（菜鸟快递详情页实测漏检）：靠门牌的形状认。
 *
 * 收件地址前面只写着「送至」，淘宝收货卡片什么都不写，
 * 驿站地址夹在一句通知中间——标签锚定一个都接不住。
 */
class AddressShapeTest {

    private val addressRule = DefaultRuleSet.rules.first { it.id == "address" }
    private val shape = AddressShape(confidence = 0.7f)

    private fun addresses(text: String): List<String> =
        addressRule.findIn(text).map { text.substring(it.range) }

    private fun shaped(text: String): List<String> =
        shape.findIn(text).map { text.substring(it.range) }

    // ---------- 截图里的三处 ----------
    @Test fun `the recipient address is found by its shape alone`() {
        assertThat(shaped("送至 祁门路33号四方新村23幢605室")).containsExactly("祁门路33号四方新村23幢605室")
    }

    /** 驿站地址夹在句子里：左边界停在「至」，右边界停在最后一个门牌词。 */
    @Test fun `an address inside a sentence is cut out of it`() {
        assertThat(addresses("您的快件已暂存至合肥四方新村10栋101室店菜鸟驿"))
            .containsExactly("合肥四方新村10栋101室")
    }

    @Test fun `a bare station address`() {
        assertThat(addresses("合肥四方新村10栋101室店")).containsExactly("合肥四方新村10栋101室")
        assertThat(addresses("四方新村10栋101室")).containsExactly("四方新村10栋101室")
    }

    @Test fun `the same address when the recognizer splits every character`() {
        assertThat(shaped("送 至 祁 门 路 33 号 四 方 新 村 23 幢 605 室"))
            .containsExactly("祁 门 路 33 号 四 方 新 村 23 幢 605 室")
    }

    // ---------- 其他常见版面 ----------
    /** 淘宝收货卡片：省市区之间有空格、没有字段名。左边界要推到省。 */
    @Test fun `a province city district prefix is included`() {
        assertThat(addresses("安徽省 合肥市 蜀山区 祁门路33号四方新村23幢605室"))
            .containsExactly("安徽省 合肥市 蜀山区 祁门路33号四方新村23幢605室")
    }

    @Test fun `a road and house number alone is an address`() {
        assertThat(addresses("北京市朝阳区建国路 88 号")).containsExactly("北京市朝阳区建国路 88 号")
    }

    /** 单元后面没带「室」的房号也要遮，不能只遮到「2单元」把 501 露出来。 */
    @Test fun `a trailing room number without a suffix is included`() {
        assertThat(addresses("阳光小区3栋2单元501")).containsExactly("阳光小区3栋2单元501")
    }

    @Test fun `a rural address`() {
        assertThat(addresses("张家村3组15号")).containsExactly("张家村3组15号")
    }

    /** 字段名与形状认出同一个地址时只出一个区间，而且以字段名后的值为准。 */
    @Test fun `a labelled address is reported once`() {
        assertThat(addresses("收货地址 北京市朝阳区建国路88号")).containsExactly("北京市朝阳区建国路88号")
    }

    // ---------- 不是地址 ----------
    @Test fun `a floor plan is not an address`() {
        assertThat(addresses("3室2厅1卫 89㎡ 中楼层/18层")).isEmpty()
    }

    @Test fun `metro lines exits and dates are not addresses`() {
        assertThat(addresses("地铁2号线3号口")).isEmpty()
        assertThat(addresses("高速公路5号出口")).isEmpty()
        assertThat(addresses("10月1号发货")).isEmpty()
    }

    /** 「组」只在「3组15号」这种村组门牌里算数，「第1组 第2组」是分组。 */
    @Test fun `numbered groups are not addresses`() {
        assertThat(addresses("第1组 第2组")).isEmpty()
    }

    /** 一个楼栋单元词单独出现不算：「第3单元」是课本，「5号楼」没有上下文。 */
    @Test fun `a single unit word is not an address`() {
        assertThat(addresses("第3单元 第2课")).isEmpty()
        assertThat(addresses("5号楼")).isEmpty()
        assertThat(addresses("5楼")).isEmpty()
    }

    @Test fun `other lines of the logistics page are not addresses`() {
        listOf(
            "收 合肥市",
            "距收货地193米",
            "已放入菜鸟驿站",
            "天猫 乐事旗舰店 >",
            "【乐事X蔚蓝档案】薯片多口味... ¥99.9",
            "申通快递 773443899958908 复制 | 打电话",
        ).forEach { assertThat(addresses(it)).isEmpty() }
    }
}
