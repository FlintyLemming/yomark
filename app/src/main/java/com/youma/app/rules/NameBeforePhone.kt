package com.youma.app.rules

/**
 * 紧贴在电话号码前面的 2–4 个汉字：人名。
 *
 * 收货卡片、快递单、通讯录都是「名字 电话」这个排版，而且往往**没有字段名**——
 * 菜鸟的快递详情页就是光秃秃一行「沐晨冉 86-186****3392」，标签锚定对它无能为力。
 * 电话是强特征，拿它当锚：它前面那一小串汉字几乎总是这个号码的主人。
 *
 * 误报来自三处，各挡一道：
 * - 电话前面写的是字段名或说明（「联系电话 138…」「客服热线 400…」「微信同号」）——
 *   含 NOT_A_NAME 里任何一个词就否决；
 * - 电话前面是半句话（「已向 186…发送验证码」「来自 186…的来电」「我是 186…」）——
 *   这些虚词、代词从不出现在人名里，也在 NOT_A_NAME 里；
 * - 电话前面是一整句话（「如有疑问请联系 138…」）——汉字串长于 4 个就不要。
 *
 * 通讯录里的「妈妈 139…」「母亲 139…」也挡掉：称呼不是能定位到人的信息。
 *
 * 跑在 HanView 上：逐字切分的「沐 晨 冉」先拼回「沐晨冉」再数字数。
 *
 * @param phone 找电话的认法。传 PhoneRule 进来，打了星的号码也算锚。
 */
class NameBeforePhone(
    private val phone: Finder,
    private val confidence: Float,
) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        return phone.findIn(t).mapNotNull { p ->
            var end = p.range.first
            while (end > 0 && p.range.first - end < MAX_SEPARATORS && t[end - 1] in SEPARATORS) end--
            var start = end
            while (start > 0 && t[start - 1].isHan()) start--
            val name = t.substring(start, end)
            if (name.length !in 2..4 || NOT_A_NAME.any { it in name }) return@mapNotNull null
            RuleMatch(view.toSource(start until end), confidence)
        }
    }

    private companion object {
        /** 名字与号码之间允许的间隔：空白、冒号、逗号、左括号，最多几个。 */
        const val SEPARATORS = " \u3000:：,，(（"
        const val MAX_SEPARATORS = 3

        /**
         * 出现在电话前面、但不是人名的字词。含其中任何一个就不算——
         * 「号」一个字挡住手机号、账号、尾号、单号、编号一大片。
         *
         * 单字只收**从不用在人名里**的虚词代词；「那」「于」「向」是姓，不收。
         */
        val NOT_A_NAME = listOf(
            // 字段名与说明
            "号", "电话", "手机", "热线", "客服", "联系", "致电", "拨打", "呼叫", "来电", "座机",
            "固话", "传真", "微信", "专线", "总机", "分机", "服务", "咨询", "投诉", "举报", "查询",
            "预约", "订购", "售后", "前台", "办公", "公司", "门店", "商家", "店铺", "骑手", "司机",
            "快递", "驿站", "配送", "派送", "派件", "取件", "收件", "寄件", "收货", "发货", "退货",
            "签收", "用户", "会员", "绑定", "验证", "虚拟", "保护", "隐私", "本人", "本机", "备用",
            "紧急", "姓名", "名字",
            // 半句话里挡在号码前面的虚词、代词
            "已", "给", "拨", "打", "请", "或", "的", "是", "在", "到", "我", "你", "您", "他", "她",
            "它", "这个", "那个", "这边", "那边", "来自", "发送", "发至", "送至", "寄至",
            // 称呼
            "妈", "爸", "母亲", "父亲", "老公", "老婆", "爷爷", "奶奶", "外公", "外婆", "哥哥",
            "姐姐", "弟弟", "妹妹", "儿子", "女儿", "家里", "家人", "物业", "门卫", "保安", "老板",
        )
    }
}
