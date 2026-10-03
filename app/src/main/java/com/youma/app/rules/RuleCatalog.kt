package com.youma.app.rules

import com.youma.app.core.model.SensitiveKindLabels
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RuleState

/**
 * 规则表的可配置视图（2026-09-04 增补设计 §2）。
 *
 * `DefaultRuleSet` 仍然是唯一的规则清单与出厂分层；本对象只负责按
 * RecognitionConfig 把某几条摘掉、把另几条的初始状态改掉。规则的匹配行为
 * 一个字都不动——覆写只换 `enabledByDefault`。
 */
object RuleCatalog {

    val all: List<Rule> = DefaultRuleSet.rules

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
     * 设置页上这一行的名字。默认就是类型名；同一类型有两条规则时得分得开——
     * 两行都叫「人名」，用户不知道关的是哪一个。
     */
    fun label(rule: Rule): String = LABELS[rule.id] ?: SensitiveKindLabels.display(rule.kind)

    private val LABELS = mapOf("name-text" to "人名（无字段名）")

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
