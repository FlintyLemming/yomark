package moe.flinty.yomark.rules

/**
 * 英文人名、地址规则用的名单，放在 resources 的 `moe/flinty/yomark/rules/en/` 下，第一次用到时才读（几十毫秒）。
 * 规则跑在 Dispatchers.Default 上，读名单不卡界面。
 *
 * 来源（都是公开数据整理成的纯文本，一行一项；整理方法见 tools/model-eval/en-lists/README.md）：
 * - 名：美国人口普查局 1990 年的常见名，加上社会保障局（SSA）1950–2025 年出生人口里最常见的 2 万个名。公有领域；
 * - 姓：普查局 2010 年姓氏前 3 万。公有领域；
 * - 模糊的名、姓：同时是普通英文词的（Will、Grant、Park……），从上面两张表与 WordNet 3.0 交出来。WordNet 的许可见同目录的 LICENSE-wordnet.txt；
 * - 品牌：OpenStreetMap name-suggestion-index 里的连锁品牌、店名。BSD 3-Clause，见同目录的 LICENSE-name-suggestion-index.md；
 * - 街道后缀、房号用词：USPS Publication 28 附录 C1、C2。美国政府作品。
 */
internal object EnglishLists {

    /** 绝对路径：release 包里 R8 会给这个类改名、可能挪包，相对路径就找不到了。 */
    private fun lines(name: String): List<String> =
        EnglishLists::class.java.getResourceAsStream("/moe/flinty/yomark/rules/en/$name")?.bufferedReader(Charsets.UTF_8)?.useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        } ?: emptyList()

    private fun lowerSet(name: String): Set<String> = lines(name).mapTo(HashSet()) { it.lowercase() }

    val firstNames by lazy { lowerSet("first_names.txt") }
    val lastNames by lazy { lowerSet("last_names.txt") }
    val ambiguousFirst by lazy { lowerSet("ambiguous_first.txt") }
    val ambiguousLast by lazy { lowerSet("ambiguous_last.txt") }

    /** 品牌，小写。多词的品牌另收一份去掉所有格的：表里是「Trader Joe's」，屏幕上常写「Trader Joe」。 */
    val brands by lazy {
        lines("brands.txt").flatMapTo(HashSet()) { b ->
            val k = b.lowercase()
            if (' ' in k) listOf(k, k.removeSuffix("'s")) else listOf(k)
        }
    }

    /** 街道后缀的全部写法，全大写（AVENUE、AVE、AV……）。 */
    val streetSuffixes by lazy { lines("usps_suffixes.txt") }

    /** 房号用词，全大写（APT、SUITE、FL、BLDG……）。 */
    val unitWords by lazy { lines("usps_units.txt") }

    /** 整段正好是一个品牌名（不分大小写，可带所有格）。 */
    fun isBrand(s: String): Boolean {
        val k = s.trim().lowercase()
        return k in brands || k.removeSuffix("'s") in brands
    }
}
