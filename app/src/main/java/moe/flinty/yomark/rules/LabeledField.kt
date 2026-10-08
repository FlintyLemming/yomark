package moe.flinty.yomark.rules

/**
 * 标签锚定：先认字段名，再取紧随其后的值。
 *
 * 姓名与地址没有任何形状特征——`张三` 和 `北京` 在正则眼里毫无区别，
 * 校验位、长度窗口、字符集这些手段一个都用不上。`Sensitivity.kt` 因此把
 * PERSON_NAME / POSTAL_ADDRESS 标成「v2：需要 NER」，于是 0.2.0 上这两类一条规则都没有。
 *
 * 这里换一个支点：**不判断值像不像，判断它前面写着什么。**「收货地址」四个字之后那一段，
 * 几乎必然是地址。代价是覆盖面止于有字段名的版面——订单页、快递单、证件、银行页——
 * **聊天记录里随口提到的人名地址抓不到**，那仍然需要 NER。这是近似，不是等价物。
 *
 * 遮的是**值**不是字段名：把「收货人」三个字一起涂黑，用户就看不懂自己在看什么了。
 *
 * 跑在 HanView 上：中文识别器把「收货地址」切成「收 货 地 址」时，标签照样认得出。
 *
 * @param value 值的形状。姓名与地址差别很大（地址必须含数字，姓名遇数字就该停），
 *   所以不在这里写死，由调用方给。
 */
class LabeledField(
    labels: List<String>,
    private val value: Regex,
    private val confidence: Float,
) : Finder {

    /**
     * 长标签排在前面，`收货地址` 才不会被 `地址` 抢先匹配掉半截。
     *
     * 前面的否定后顾是为了挡住词中词：汉字侧是「用户名」里的「户名」，
     * 拉丁侧是「IP地址」「MAC地址」里的「地址」和「Username」里的「Name」——
     * 这些都是界面文案，不是邮寄地址或姓名字段。紧邻字母、数字、汉字时
     * 多半不是一个独立字段名。
     *
     * 代价是「寄件人姓名 张三」这种标签叠标签的写法会被一起挡掉，
     * **少认一处好过错涂一处**。
     */
    private val label = Regex(
        "(?<![\\u4e00-\\u9fa5A-Za-z0-9])(?:" +
            labels.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            ")[：:＝=]?[ 　]*"
    )

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return label.findAll(view.text)
            .mapNotNull { l -> value.matchAt(view.text, l.range.last + 1) }
            .map { RuleMatch(view.toSource(it.range), confidence) }
            .toList()
    }
}
