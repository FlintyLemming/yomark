package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.PICKUP_CODE
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 字段名自己占一行、码在它下面一行的取件码：测评页 zsms-pickup-codes 的短信卡片、zshop-pdd-pickup-order 的拼多多订单详情
 * （文字照抄 tools/model-eval/nano-pages-zh.json，一行一个 TextLine 往下排）。
 *
 * 「取件码」下面的「8-5-2001」「6-3-2208」原先没有一条规则认：取件码只认同一行里字段名后面的码。出厂设置下两个码都露着，
 * 「8-5-2001」只被出厂关着的日期时间当成了日期。码下面的「复制取件码」「出示取件码」是按钮，「3 条待取件」是标题，都不是字段名。
 */
@RunWith(RobolectricTestRunner::class)
class PickupCodeLoneLabelRegressionTest {

    private val sms = listOf(
        "18:06", "4G 41%", "<", "快递", "3 条待取件", "菜鸟驿站", "17:52",
        "【菜鸟驿站】您的中通包裹已到科技园东门菜鸟驿站，请凭8-5-2001于21:00前取件，询13651873320",
        "取件码", "8-5-2001", "复制取件码", "丰巢", "15:20",
        "【丰巢】您的顺丰快递SF1398025517740已存入幸福里小区7号楼丰巢柜，凭取件码36920471取件，超时将收取保管费。",
        "兔喜生活", "11:03",
        "【兔喜生活】您的韵达快递已到幸福里小区南门兔喜快递超市，货架号B3，取件码B3-1107，营业至22:00。",
        "菜鸟驿站", "昨天 19:40",
        "【菜鸟驿站】王丽娟您好，您的京东包裹JDVA28816037452已到站，请凭3-2-1234到科技园东门菜鸟驿站领取。",
        "已取件", "全部标为已取",
    )

    private val order = listOf(
        "18:55", "5G 41%", "<", "订单详情", "客服", "待取件", "包裹已到达自提点，请在10月18日前取件",
        "取件码", "6-3-2208", "出示取件码", "自提点", "兔喜生活·多多驿站（富力桃园店）", "朝阳区富力桃园C区底商12号 08:30-21:30",
        "导航", "联系驿站", "吴海峰 177****6620", "北京市朝阳区 富力桃园A区 8号楼2单元603", "已授权 妈妈 139****1176 代取包裹",
        "三只松鼠旗舰店 >", "【百亿补贴】三只松鼠 每日坚果750g/30袋 混合果仁零食", "已拼1.2万件 · 极速退款", "¥59.9", "×1",
        "商品总价", "¥69.90", "店铺优惠", "-¥10.00", "实付", "¥59.90", "物流公司", "中通快递", "快递单号", "78294103652817",
        "订单编号", "261013-583720194628351", "下单时间", "2026-10-13 21:44:09", "多多买菜 · 猜你喜欢", "李子柒 柳州螺蛳粉 335g×3袋",
        "¥39.9", "联系商家", "确认收货",
    )

    /** 一页和它上面的取件码：短信里的（见 PickupCodeSmsRegressionTest），和「取件码」下面的。 */
    private val pages = listOf(
        sms to listOf("8-5-2001", "8-5-2001", "36920471", "B3-1107", "3-2-1234"),
        order to listOf("6-3-2208"),
    )

    /** 把每个汉字拆成独立 token，数字、拉丁、标点连成一段——中文识别器逐字切分时的样子。 */
    private fun splitEveryCharacter(line: String): String =
        Regex("""[一-鿿]|[^\s一-鿿]+""").findAll(line).joinToString(" ") { it.value }

    /** 出厂设置下整页认一遍：每个码都是取件码、都打码，没有多出来的。下面的码也像日期，同一处日期时间让给取件码。 */
    @Test fun `the codes under the label are masked with factory settings`() = runTest {
        pages.forEach { (texts, codes) ->
            val lines = textLines(*texts.toTypedArray())

            val out = RuleClassifier(DefaultRuleSet.rules).classify(lines)

            val pickup = out.filter { it.kind == PICKUP_CODE }
            assertThat(pickup.map { it.textIn(lines) }).containsExactlyElementsIn(codes)
            assertThat(pickup.all { it.enabledByDefault }).isTrue()
            assertThat(out.filter { it.kind == DATETIME }.map { it.textIn(lines) }).containsNoneIn(codes)
        }
    }

    @Test fun `the same pages split into single characters give the same codes`() = runTest {
        pages.forEach { (texts, codes) ->
            val lines = textLines(*texts.map(::splitEveryCharacter).toTypedArray())

            val out = RuleClassifier(DefaultRuleSet.rules).classify(lines)

            assertThat(out.filter { it.kind == PICKUP_CODE }.map { it.textIn(lines) }).containsExactlyElementsIn(codes)
        }
    }

    /** 字段名在左、码靠右对齐，中间隔得远，OCR 读成了同一排的两行：原先一个候选都没有。 */
    @Test fun `a label and its code read as two lines on the same row`() = runTest {
        val lines = listOf(
            Row(300f, 20f, Segment("取件码", 40f, 76f)),
            Row(300f, 20f, Segment("6-3-2208", 560f, 656f)),
        ).textLines()

        val out = RuleClassifier(DefaultRuleSet.rules).classify(lines)

        val code = out.single { it.kind == PICKUP_CODE }
        assertThat(code.quad.bounds()).isEqualTo(lines[1].quad.bounds())
        assertThat(code.enabledByDefault).isTrue()
    }
}
