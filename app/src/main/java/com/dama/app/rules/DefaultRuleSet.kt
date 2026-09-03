package com.dama.app.rules

/**
 * 首版的全部规则（spec §6）。11 条，每条都有校验步骤。
 *
 * 「默认」按**误报率**划线，不按危害划线：高误报类型自动打码会让用户
 * 一直在跟 app 对着干；有校验位兜底的类型误报接近零，自动打码不打扰任何人。
 */
object DefaultRuleSet {
    val rules: List<Rule> = emptyList()      // Task 20-23 逐条填入
}
