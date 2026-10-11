package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKindLabels
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState

/**
 * 规则表的可配置视图（2026-09-04 增补设计 §2）。
 *
 * `DefaultRuleSet` 仍然是唯一的规则清单与出厂分层；本对象只负责按
 * RecognitionConfig 把某几条摘掉、把另几条的初始状态改掉。规则的匹配行为
 * 一个字都不动——覆写只换 `enabledByDefault`。
 */
object RuleCatalog {

    val all: List<Rule> = DefaultRuleSet.rules

    /**
     * 设置页「文字」上的排法。规则表是按出厂分层排的（有校验位的在前），设置页上按截图里常见的程度排：
     * 人名、电话、地址、取件码打头，IBAN、社保号这些海外的格式排到最后。只是显示顺序，不改识别。
     */
    val inSettingsOrder: List<Rule> = listOf(
        "name", "phone", "address", "pickup", "card", "tracking", "email", "datetime",
        "url", "longnum", "passport", "apikey", "ip", "mac", "iban", "ssn",
    ).map { id -> all.first { it.id == id } }

    /** 出厂态由规则自己的 `enabledByDefault` 决定，不另立一张表——两处会走散。 */
    fun factoryState(id: String): RuleState =
        all.firstOrNull { it.id == id }
            ?.let { if (it.enabledByDefault) RuleState.MASKED else RuleState.OUTLINED }
            ?: RuleState.OFF

    fun stateOf(config: RecognitionConfig, id: String): RuleState =
        config.ruleOverrides[id] ?: factoryState(id)

    fun rulesFor(config: RecognitionConfig): List<Rule> = all.mapNotNull { rule ->
        when (stateOf(config, rule.id)) {
            RuleState.OFF -> null
            RuleState.MASKED -> rule.withDefaultState(true)
            RuleState.OUTLINED -> rule.withDefaultState(false)
        }
    }

    /**
     * 设置页上这一行的名字，就是类型名。一种类型只有一条规则（几种认法合成一条，见 CompositeRule），
     * 设置页上也就只有一行——两行都叫「人名」时，用户不知道关的是哪一个。
     */
    fun label(rule: Rule): String = SensitiveKindLabels.display(rule.kind)

    /**
     * 这一类的详情页上「怎么认的」那一段。用户点进某一类，最想知道的是它到底会遮什么、为什么会误遮。
     * 写的是认法，不是推荐：哪一类该打码由用户自己看。
     */
    fun description(rule: Rule): String = DESCRIPTIONS.getValue(rule.id)

    private val DESCRIPTIONS = mapOf(
        "card" to "13 到 19 位的卡号，要通过卡号的校验位（Luhn）。",
        "iban" to "国际银行账号，按国家核对长度和校验位。",
        "ssn" to "美国社会安全号，形如 123-45-6789，不会发放的号段不算。",
        "mac" to "网卡地址，形如 AA:BB:CC:DD:EE:FF。",
        "email" to "带常见域名后缀的邮箱地址。",
        "phone" to "按各地号码规则解析的电话号码，打了星的手机号也认。紧挨着「订单」「单号」的数字不算。",
        "passport" to "护照资料页底部的两行机读码，要核对校验位。",
        "apikey" to "以 sk-、ghp_、AKIA、eyJ 等开头、足够随机的长串。",
        "name" to "「收货人」「姓名」「Ship to」这类字段后面的名字，电话前面的名字，" +
            "以及英文的「Hi David,」「Mr. Patel」这类写法。" +
            "没有字段名时从字面猜，猜出来的要有旁证才算，见下面。",
        "address" to "「收货地址」「送至」这类字段后面的内容，以及带路名门牌、楼栋、房号的地址；" +
            "英文的门牌街道、城市州邮编，以及英国、加拿大、澳大利亚、爱尔兰、新加坡的邮编。",
        "pickup" to "「取件码」「取货码」这类字段后面的码。",
        "url" to "只遮路径和参数，域名保留。",
        "ip" to "IPv4 和 IPv6 地址。前面写着 v 或 version 的版本号不算。",
        "tracking" to "UPS 单号要核对校验位；其余是 12、15、20、22 位的纯数字，没有校验位，可能误圈。",
        "longnum" to "16 位以上、不属于其他任何一类的数字，比如交易号、订单号。没有校验位，可能误圈。",
        "datetime" to "日期、时间，以及连在一起的日期加时间。截图里到处都是，比如状态栏的时钟、每条聊天记录。",
    )

    /**
     * 人名页上「旁证是……」那句话，照着旁证表（DefaultRuleSet.nameAnchors）拼：表里加一行，这句话跟着变。
     * 「旁证是紧挨着名字的先生、女士这类称呼，或者同一行、上下相邻一行的电话、邮箱……」
     */
    fun nameAnchorNote(): String {
        val (attached, nearby) = DefaultRuleSet.nameAnchors.partition { it.reach == NameAnchor.Reach.ATTACHED }
        val parts = listOfNotNull(
            attached.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "紧挨着名字的") { it.label },
            nearby.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "同一行、上下相邻一行的") { it.label },
        )
        return "旁证是" + parts.joinToString("，或者") + "。有旁证的按上面的设置处理；没有的大多是商品名、店名，默认不圈。"
    }

    private fun Rule.withDefaultState(masked: Boolean): Rule =
        if (enabledByDefault == masked) this else StateOverride(this, masked)

    /** 只改初始状态的透明包装。findIn 原样转发，规则的行为不受配置影响。 */
    private class StateOverride(
        private val delegate: Rule,
        override val enabledByDefault: Boolean,
    ) : Rule {
        override val id get() = delegate.id
        override val kind get() = delegate.kind
        override fun findIn(text: String) = delegate.findIn(text)
    }
}
