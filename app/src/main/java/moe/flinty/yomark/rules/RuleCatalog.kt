package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKindLabels
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import android.content.res.Resources
import androidx.annotation.StringRes
import moe.flinty.yomark.R

/**
 * 规则表的可配置视图（2026-09-04 增补设计 §2）。
 *
 * `DefaultRuleSet` 仍然是唯一的规则清单与出厂分层；本对象只负责按
 * RecognitionConfig 把关掉的几条摘掉、把出厂关着又被打开的几条改成打码。规则的匹配行为
 * 一个字都不动——覆写只换 `enabledByDefault`。
 */
object RuleCatalog {

    val all: List<Rule> = DefaultRuleSet.rules

    /**
     * 设置页「文字」上的排法。规则表是按出厂分层排的（有校验位的在前），设置页上按截图里常见的程度排：
     * 人名、电话、地址、取件码打头，车牌跟在后面，IBAN、社保号这些海外的格式排到最后。只是显示顺序，不改识别。
     * 长数字串排在原先快递单号的位置：快递单号并进了它。
     */
    val inSettingsOrder: List<Rule> = listOf(
        "name", "phone", "address", "pickup", "idnumber", "plate", "card", "longnum", "email", "datetime",
        "url", "birthdate", "passport", "vin", "apikey", "ip", "mac", "iban", "ssn",
    ).map { id -> all.first { it.id == id } }

    /**
     * 出厂态由规则自己的 `enabledByDefault` 决定，不另立一张表——两处会走散。
     * 不打码的就是关着：误报率高的那几类（网址、日期时间……）原先出厂只圈出，2026-10-14 起没有这一档，出厂关着。
     */
    fun factoryState(id: String): RuleState =
        if (all.firstOrNull { it.id == id }?.enabledByDefault == true) RuleState.MASKED else RuleState.OFF

    fun stateOf(config: RecognitionConfig, id: String): RuleState =
        config.ruleOverrides[id] ?: factoryState(id)

    fun rulesFor(config: RecognitionConfig): List<Rule> = all.mapNotNull { rule ->
        when (stateOf(config, rule.id)) {
            RuleState.OFF -> null
            RuleState.MASKED -> rule.masked()
        }
    }

    /**
     * 设置页上这一行的名字，就是类型名。一种类型只有一条规则（几种认法合成一条，见 CompositeRule），
     * 设置页上也就只有一行——两行都叫「人名」时，用户不知道关的是哪一个。
     */
    @StringRes
    fun label(rule: Rule): Int = SensitiveKindLabels.nameRes(rule.kind)

    /**
     * 这一类的详情页上「怎么认的」那一段。用户点进某一类，最想知道的是它到底会遮什么、为什么会误遮。
     * 写的是认法，不是推荐：哪一类该打码由用户自己看。
     */
    @StringRes
    fun description(rule: Rule): Int = DESCRIPTIONS.getValue(rule.id)

    private val DESCRIPTIONS = mapOf(
        "card" to R.string.rule_desc_card,
        "iban" to R.string.rule_desc_iban,
        "ssn" to R.string.rule_desc_ssn,
        "mac" to R.string.rule_desc_mac,
        "email" to R.string.rule_desc_email,
        "phone" to R.string.rule_desc_phone,
        "passport" to R.string.rule_desc_passport,
        "idnumber" to R.string.rule_desc_idnumber,
        "vin" to R.string.rule_desc_vin,
        "apikey" to R.string.rule_desc_apikey,
        "name" to R.string.rule_desc_name,
        "address" to R.string.rule_desc_address,
        "pickup" to R.string.rule_desc_pickup,
        "plate" to R.string.rule_desc_plate,
        "url" to R.string.rule_desc_url,
        "ip" to R.string.rule_desc_ip,
        "longnum" to R.string.rule_desc_longnum,
        "datetime" to R.string.rule_desc_datetime,
        "birthdate" to R.string.rule_desc_birthdate,
    )

    /**
     * 人名页上「旁证是……」那句话，照着旁证表（DefaultRuleSet.nameAnchors）拼：表里加一行，这句话跟着变。
     * 「旁证是紧挨着名字的先生、女士这类称呼，或者同一行、上下相邻一行的电话、邮箱……」
     */
    fun nameAnchorNote(res: Resources): String {
        val (attached, nearby) = DefaultRuleSet.nameAnchors.partition { it.reach == NameAnchor.Reach.ATTACHED }
        val sep = res.getString(R.string.list_separator)
        fun names(anchors: List<NameAnchor>) = anchors.joinToString(sep) { res.getString(it.label) }
        val parts = listOfNotNull(
            attached.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.anchor_attached, names(it)) },
            nearby.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.anchor_nearby, names(it)) },
        )
        return res.getString(R.string.anchor_note, parts.joinToString(res.getString(R.string.anchor_or)))
    }

    /** 出厂关着、用户打开了的那几类，命中也打码。 */
    private fun Rule.masked(): Rule = if (enabledByDefault) this else StateOverride(this)

    /**
     * 只改打不打码的透明包装。其余原样转发，规则的行为不受配置影响——
     * 漏转 findOnPage 的话，用户打开的那一类就只剩逐行的认法，字段名在上、值在下的全认不出。
     */
    private class StateOverride(private val delegate: Rule) : Rule {
        override val enabledByDefault = true
        override val id get() = delegate.id
        override val kind get() = delegate.kind
        override val yieldsTo get() = delegate.yieldsTo
        override fun findIn(text: String) = delegate.findIn(text)
        override fun findOnPage(page: Page) = delegate.findOnPage(page)
    }
}
