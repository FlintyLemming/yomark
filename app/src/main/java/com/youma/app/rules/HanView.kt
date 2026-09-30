package com.youma.app.rules

/**
 * 中文认法看到的行文本。
 *
 * MlKitTextRecognizer 用单个空格拼接 element。拉丁文的 element 是词，空格正好落在词界上；
 * 中文识别器的 element 却可能细到**单个汉字**——ML Kit 文档对 CJK 的 element 定义就是「一个字」——
 * 拼出来是「收 货 地 址 北 京 市」。这时任何汉字标签、人名、地址的正则都对不上。
 *
 * 所以按行判断：一行里含汉字的 token 若**多数是单字**，就认定它是逐字切出来的，
 * 去掉紧挨汉字的空格再交给认法；否则原样交出去——那时的空格是版面上真有的间隙
 * （「收货人 张三」之间那一格就是字段边界），留着它，认法才知道值从哪里开始。
 *
 * 逐字切分的行里，真实的间隙会和拼接用的空格一起被去掉，这是这个视图的代价：
 * 那一行的版面信息只剩几何坐标里还有，而规则只看文本。宁可让值多吞一两个字，
 * 也不让整行认不出来——漏遮是事故，多遮只是麻烦（spec §13）。
 *
 * **只有中文认法走这个视图。** 拉丁规则照旧跑原串：去掉「app.com/r/x 领取」之间的空格，
 * URL 的 path 就会把「领取」也吞进去。
 */
internal class HanView private constructor(
    val text: String,
    /** text 的第 i 个字符在原串里的下标。null 表示原样，不用映射。 */
    private val origin: IntArray?,
) {
    /** 视图上的区间 → 原串上的区间。去掉的空格夹在区间中间，映射回去会被一起包进来。 */
    fun toSource(range: IntRange): IntRange =
        if (origin == null) range else origin[range.first]..origin[range.last]

    companion object {
        fun of(line: String): HanView {
            if (!looksCharSplit(line)) return HanView(line, null)
            val sb = StringBuilder(line.length)
            val origin = IntArray(line.length)
            line.forEachIndexed { i, c ->
                if (c == ' ' && isJoiner(line, i)) return@forEachIndexed
                origin[sb.length] = i
                sb.append(c)
            }
            return HanView(sb.toString(), origin.copyOf(sb.length))
        }

        /**
         * 含汉字的 token 多数是单字，这一行就是逐字切出来的。
         *
         * 按多数而不是见一个单字就认：正常切分里也会冒出单字 token，
         * 菜鸟地图上的「收 合肥市」、证件上字距拉开的「姓 名」都是。
         */
        private fun looksCharSplit(line: String): Boolean {
            val han = line.split(' ').filter { t -> t.any { it.isHan() } }
            return han.size >= 2 && han.count { it.length == 1 } * 2 > han.size
        }

        /** 一侧是汉字或全角标点、另一侧不是空格：这个空格是拼接时插进去的。 */
        private fun isJoiner(s: String, i: Int): Boolean {
            if (i == 0 || i == s.lastIndex) return false
            val before = s[i - 1]
            val after = s[i + 1]
            if (before == ' ' || after == ' ') return false
            return before.isCjk() || after.isCjk()
        }
    }
}

/** CJK 统一表意文字基本区。 */
internal fun Char.isHan(): Boolean = this in '\u4e00'..'\u9fff'

/** 汉字，或 CJK 标点（、。「」【】）与全角形式（，：（）＊）。 */
private fun Char.isCjk(): Boolean = isHan() || this in '\u3001'..'\u303f' || this in '\uff01'..'\uffef'
