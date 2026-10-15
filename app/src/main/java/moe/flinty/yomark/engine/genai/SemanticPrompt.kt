package moe.flinty.yomark.engine.genai

import moe.flinty.yomark.core.model.SensitiveKind
import java.time.Year

/**
 * 给端侧大模型的提示词，以及把它的回答落回到行内区间。
 *
 * 纯文本进、纯文本出，不碰模型也不碰 Android，单测直接覆盖。
 *
 * 问哪几类由调用方给（用户在设置里开着的那些，见 EngineFactory 的 aiKinds），提示词只列这几类，回答里别的类型一律不认。
 *
 * 回答格式故意用「类型|原文」而不是 JSON：Gemini Nano 这种尺寸的模型写 JSON 经常少括号、多逗号，
 * 一行一条的竖线格式坏一条只丢一条。也不让它写行号：真机上它常把行号写成 1、2、3 往下数，
 * 对不上原文就整条丢了（2026-10-15 用 tools/model-eval/nano_eval.py 在 Pixel 9 Pro 上量过）。
 * 原文在整页里找，**每一处都算**：同一个昵称、号码在页上出现几次就打几处码，它也就只用说一次，回答短、跑得快。
 *
 * **原文必须能在页上找到**，找不到就丢——模型会改写、会编，一个找不到出处的区间没法落到像素上，也不该被信任。
 */
object SemanticPrompt {

    /** 一次请求。提示词里的第 n 行是原来的 lines[lineIndices[n - 1]]。 */
    class Chunk(val prompt: String, val lineIndices: List<Int>)

    data class Finding(val lineIndex: Int, val range: IntRange, val kind: SensitiveKind)

    /** 提示词里的一类：回答里写的类型名、对应的类型、告诉模型这一类包括什么。一个类型可以有两个名字。 */
    private class Category(val label: String, val kind: SensitiveKind, val hint: String)

    /**
     * 能问的类型，排列就是提示词里的顺序。头像、人脸、条码是图，读文字的模型看不到，不在这里。
     * 说明里举的样子都是编的，不要换成测评页上出现过的值（tools/model-eval/nano-pages-zh.json），不然测出来的分数是虚的。
     */
    private val CATEGORIES = listOf(
        Category("人名", SensitiveKind.PERSON_NAME, "真实姓名，带姓的称呼（王先生、李老师），话里提到的别人的名字"),
        Category("昵称", SensitiveKind.CHAT_NAME, "聊天里每一个发消息的人（消息上方单独一行的名字），@ 后面的名字，「拍了拍」「撤回了」「邀请…加入了群聊」里的名字，微信号、QQ号"),
        Category("聊天标题", SensitiveKind.CHAT_TITLE, "聊天顶栏的群名或对方的名字（通常在最前面几行，去掉 < 和人数）"),
        Category("地址", SensitiveKind.POSTAL_ADDRESS, "住址、收货地址、小区名加楼栋门牌（如 xx花园3栋2单元501）"),
        Category("电话", SensitiveKind.PHONE, "手机号、座机号，包括打了星号的（如 186****0021），不含验证码"),
        Category("邮箱", SensitiveKind.EMAIL, "电子邮箱"),
        Category("证件号", SensitiveKind.ID_NUMBER, "身份证、驾驶证、社保卡、学号工号等号码，包括打了星号的"),
        Category("护照", SensitiveKind.PASSPORT, "护照号码（如 E12345678），护照底部带 < 的机读码"),
        Category("银行卡", SensitiveKind.PAYMENT_CARD, "银行卡号、信用卡号、银行账号，包括打了星号的"),
        Category("IBAN", SensitiveKind.IBAN, "国际银行账号（如 DE89 3704 ...）"),
        Category("社保号", SensitiveKind.SSN, "美国社会安全号（如 123-45-6789）"),
        Category("车牌", SensitiveKind.LICENSE_PLATE, "车牌号（如 粤B·6D8K2）"),
        Category("车架号", SensitiveKind.VIN, "17 位车架号 VIN、发动机号"),
        Category("取件码", SensitiveKind.PICKUP_CODE, "取件码、取货码、取餐码，包括短信里「凭 x-x-xxxx 取件」的码"),
        Category("生日", SensitiveKind.BIRTH_DATE, "出生日期"),
        // 都归「密钥」一类，分开写模型才认得出：只写一个「密钥」时短信里的验证码它几乎不报
        Category("验证码", SensitiveKind.API_KEY, "短信验证码、动态码、校验码"),
        Category("密码", SensitiveKind.API_KEY, "密码、Wi-Fi 密码、API 密钥、令牌"),
        Category("MAC", SensitiveKind.MAC_ADDR, "MAC 地址（如 3c:22:fb:01:9a:7e）"),
        Category("IP", SensitiveKind.IP_ADDR, "IP 地址"),
        Category("网址", SensitiveKind.URL, "带个人信息或口令的链接"),
        Category("快递单号", SensitiveKind.TRACKING_NO, "快递运单号"),
        Category("日期", SensitiveKind.DATETIME, "日期、时间"),
        Category("长数字串", SensitiveKind.LONG_NUMBER, "其他能对应到个人的长串编号"),
    )

    /** 提示词能问的全部类型。 */
    val KINDS: Set<SensitiveKind> = CATEGORIES.mapTo(LinkedHashSet()) { it.kind }

    /** 「不算隐私」那一段，按提示词里的顺序。后面跟着类型的，用户开着那一类时不写。 */
    private val NOT_PRIVATE = listOf(
        "店铺名、公司名、品牌和商品名、名人、按钮和提示文字、字段名（如「手机号」「身份证号」）" to null,
        "客服电话和服务号码（如 10010、95533）" to null,
        "商场和车站等公共地点、城市名、价格" to null,
        "订单号" to SensitiveKind.LONG_NUMBER,
        "普通的日期时间" to SensitiveKind.DATETIME,
    )

    /**
     * 例子是一段编的群聊：Nano 不看例子时常把顶栏的群名、门牌、取件码漏掉；例子里没有话里提到的人名，它就少报人名；
     * 没有验证码，它就把验证码报成电话（再被七位的门槛丢掉）。
     * 回答里只留问到了的类型，没问的类型出现在例子回答里，模型会照着写出来。
     */
    private const val EXAMPLE_TEXT = """1: 14:05
2: < 周末骑行小分队(9)
3: 青柠
4: 周六早上七点在奥森南门集合，让何嘉文也来
5: "大熊" 拍了拍 "橙子不酸"
6: 橙子不酸
7: @青柠 我住融科橄榄城5号楼1203，顺路来接我
8: 我手机号 18910327755，驿站取件码 3-1-4021
9: 【顺丰】验证码 552817，请勿告诉他人
10: 按住 说话"""

    private val EXAMPLE_REPLY = listOf(
        SensitiveKind.CHAT_TITLE to "周末骑行小分队",
        SensitiveKind.CHAT_NAME to "青柠",
        SensitiveKind.CHAT_NAME to "大熊",
        SensitiveKind.CHAT_NAME to "橙子不酸",
        SensitiveKind.PERSON_NAME to "何嘉文",
        SensitiveKind.POSTAL_ADDRESS to "融科橄榄城5号楼1203",
        SensitiveKind.PHONE to "18910327755",
        SensitiveKind.PICKUP_CODE to "3-1-4021",
        SensitiveKind.API_KEY to "552817",
    )

    /**
     * 这几类的提示词（不含页上的文字）。用 Google 给 Nano 的写法：## 分段，说明、反例、格式、例子各一段。
     * 一类都不问时为空串，调用方不该再发请求。
     */
    fun instructions(kinds: Set<SensitiveKind>): String {
        val asked = CATEGORIES.filter { it.kind in kinds }
        if (asked.isEmpty()) return ""
        val labels = asked.distinctBy { it.kind }.associate { it.kind to it.label }
        val example = EXAMPLE_REPLY.mapNotNull { (kind, text) -> labels[kind]?.let { "$it|$text" } }
        // 用户开着的类型不能再写进「不算隐私」：打开了日期，就不能同时说日期不算
        val notPrivate = NOT_PRIVATE.filter { (_, kind) -> kind == null || kind !in kinds }.joinToString("、") { it.first }
        return buildString {
            append("你在帮用户给手机截图打码。截图里识别出的文字一行一条，每行以「行号: 」开头。找出能指向具体某个人的隐私信息。\n")
            append("## 类型\n")
            asked.forEach { append(it.label).append('：').append(it.hint).append('\n') }
            append("## 不算隐私\n").append(notPrivate).append("。\n")
            append("## 输出\n")
            append("每找到一处输出一行：类型|原文\n")
            append("原文从文字里逐字复制，只复制隐私的那一段，不加引号、不加解释。同一个名字、号码只输出一次。一处也没有就只输出：无\n")
            append("## 例子\n").append(EXAMPLE_TEXT).append("\n输出：\n")
            if (example.isEmpty()) append("无\n") else example.forEach { append(it).append('\n') }
            append("## 文字\n")
        }
    }

    /**
     * 把可用的行切成若干次请求。中文一字约一 token，每次的文字留到 1200 字，
     * 连同提示词本身和回答，在 nano-v2 那一档约 4000 token 的上限以内。
     *
     * @param texts null 表示这一行不送（置信度太低、没有文字内容）。
     */
    fun chunks(texts: List<String?>, kinds: Set<SensitiveKind> = KINDS, budget: Int = CHUNK_CHARS): List<Chunk> {
        val instructions = instructions(kinds)
        if (instructions.isEmpty()) return emptyList()
        val out = ArrayList<Chunk>()
        var body = StringBuilder()
        var indices = ArrayList<Int>()
        fun flush() {
            if (indices.isEmpty()) return
            out += Chunk(instructions + body, indices)
            body = StringBuilder()
            indices = ArrayList()
        }
        texts.forEachIndexed { i, raw ->
            val text = raw?.take(MAX_LINE_CHARS) ?: return@forEachIndexed
            if (body.length + text.length > budget) flush()
            indices += i
            body.append(indices.size).append(": ").append(text).append('\n')
        }
        flush()
        return out
    }

    /**
     * 回答里的每一条落到页上：[texts] 是整页的行（送没送的都算），原文在哪几行出现，哪几处就都是；聊天标题只算第一处，
     * 群名、对方名字在顶栏，消息里同样的字（「妈妈」）不是它。
     * 只认 [kinds] 里的类型；行号写了也不看（见上面为什么）。
     *
     * [vetoed] 管用户关掉的类型：模型把关掉的那一类报成别的类型（关了电话，它把手机号报成证件号），这一条不认。
     * 同一处报成了两类的只留一类，不然叠出两个码，点一下拿不掉。
     */
    fun parse(
        reply: String,
        texts: List<String>,
        kinds: Set<SensitiveKind> = KINDS,
        vetoed: (kind: SensitiveKind, quote: String) -> Boolean = { _, _ -> false },
    ): List<Finding> {
        val labels = CATEGORIES.filter { it.kind in kinds }.associate { it.label to it.kind }
        if (labels.isEmpty()) return emptyList()
        val names = labels.keys.joinToString("|") { Regex.escape(it) }
        val row = Regex("""(?:\d+\s*[|｜]\s*)?($names)\s*[|｜]\s*(.+)""")
        return reply.lineSequence().flatMap { line ->
            val m = row.matchEntire(line.trim().trimStart('-', '*', '•', ' ')) ?: return@flatMap emptySequence()
            val kind = labels.getValue(m.groupValues[1])
            val quote = m.groupValues[2].trim().trim(*QUOTES).trim()
            if (quote.codePointCount(0, quote.length) < 2 || quote.length > MAX_SPAN) return@flatMap emptySequence()
            if (!plausible(kind, quote) || vetoed(kind, quote)) return@flatMap emptySequence()
            val found = texts.asSequence().withIndex().flatMap { (i, text) ->
                locateAll(text, quote).filterNot { inSignature(text, it) }.map { Finding(i, it, kind) }
            }
            if (kind == SensitiveKind.CHAT_TITLE) found.take(1) else found
        }.toList().let(::collapse)
    }

    /**
     * 同一处报成了几类时只留一类：聊天里的名字算昵称、标题，其余照提示词里的顺序。
     * 几段各自的回答合起来时也要再过一遍，同一个名字这一段报人名、那一段报昵称。
     */
    fun collapse(findings: List<Finding>): List<Finding> =
        findings.sortedBy { PRIORITY.indexOf(it.kind) }.distinctBy { it.lineIndex to it.range }

    private val PRIORITY = listOf(SensitiveKind.CHAT_TITLE, SensitiveKind.CHAT_NAME) + KINDS

    /**
     * 短信开头或结尾的【招商银行】【铁路客服】是签名，发件机构，里面不会是哪个人的信息。
     * 模型偶尔把它当成聊天标题、人名，原文又是每处都算，一条短信列表能误打一整列。
     * 句子中间的【】是正文（「验证码为【552817】」），照常算。
     */
    private fun inSignature(line: String, range: IntRange): Boolean {
        val open = line.lastIndexOf('【', range.first)
        if (open < 0) return false
        val close = line.indexOf('】', open)
        if (close <= range.last) return false               // 没合上，或者在原文前面就合上了
        return line.substring(0, open).isBlank() || line.substring(close + 1).isBlank()
    }

    /**
     * 原文在行里的每一处。先精确找；一处也没有再忽略空格找——
     * OCR 行里的空格是按像素补的，模型复述时常常把它吞掉或多加一个。
     * 字母、数字打头或结尾的原文不能从一个更长的词、数中间截出来：「Li」不算「Click」里的，「12」不算「123」里的。
     */
    internal fun locateAll(line: String, quote: String): List<IntRange> {
        val exact = occurrences(line, quote).map { it until it + quote.length }.filter { bounded(line, it) }.toList()
        if (exact.isNotEmpty()) return exact
        val origin = line.indices.filter { !line[it].isWhitespace() }
        val compactLine = origin.map { line[it] }.joinToString("")
        val compactQuote = quote.filterNot { it.isWhitespace() }
        if (compactQuote.isEmpty()) return emptyList()
        return occurrences(compactLine, compactQuote)
            .map { origin[it]..origin[it + compactQuote.length - 1] }.filter { bounded(line, it) }.toList()
    }

    private fun occurrences(text: String, part: String) =
        generateSequence(text.indexOf(part).takeIf { it >= 0 }) { from -> text.indexOf(part, from + 1).takeIf { it >= 0 } }

    /**
     * 原文两头不能接着同一个词、同一个数：字母数字挨着字母数字不行；数字隔着一个 . : , / - 又接着数字也不行，
     * 那是小数、时间、日期的一截（取件码说成「2087」，「¥2087.00」「6-3-2087」里的不算）。
     * 原文自己带着分隔符时，只有接着同一种分隔符才是同一个数：IP 后面的「:8080」、逗号隔开的下一个码不算。
     */
    private fun bounded(line: String, range: IntRange): Boolean {
        fun Char.word() = this < '\u0080' && isLetterOrDigit()
        val own = line.substring(range.first, range.last + 1).filter { it in SEPARATORS }
        fun joined(edge: Char, next: Int, step: Int): Boolean {
            val c = line.getOrNull(next) ?: return false
            if (edge.word() && c.word()) return true
            return edge.isDigit() && c in SEPARATORS && (own.isEmpty() || c in own) &&
                line.getOrNull(next + step)?.isDigit() == true
        }
        return !joined(line[range.first], range.first - 1, -1) && !joined(line[range.last], range.last + 1, 1)
    }

    private const val SEPARATORS = ".:,/-"

    /**
     * 原文的样子对不对得上类型。Nano 常把字段名当成值（「电话|联系手机」），把 95588、12306 这种服务号码当成电话，
     * 把「儿子」「表弟」当成人名，把车站、湖当成地址，把下单日期当成生日：号码类（验证码、密码也算）至少四个字母数字、
     * 得有数字（护照机读码的姓名行除外），电话至少七位（打了星号的手机号也剩七位），邮箱得有 @，
     * 地址得有门牌数字或路、号、小区这类字，生日得有个往年的年份（连写的「19900101」也算），单独的亲属称谓不算人名、昵称。
     */
    internal fun plausible(kind: SensitiveKind, quote: String): Boolean = when (kind) {
        SensitiveKind.PERSON_NAME -> plausibleName(quote) && quote !in KINSHIP
        // 群里叫「老妈」的昵称版面那一层会认（消息上方那一行）；话里的「儿子」「表弟」是称呼，不是谁的名字
        SensitiveKind.CHAT_NAME -> quote !in KINSHIP
        SensitiveKind.PHONE -> quote.count { it.isDigit() } >= MIN_PHONE_DIGITS
        SensitiveKind.EMAIL -> '@' in quote
        SensitiveKind.API_KEY -> quote.count { it < '\u0080' && it.isLetterOrDigit() } >= MIN_NUMBER
        SensitiveKind.BIRTH_DATE -> YEAR.findAll(quote).any { it.value.toInt() < Year.now().value }
        SensitiveKind.POSTAL_ADDRESS -> quote.any { it.isDigit() } || ADDRESS_WORDS.any { it in quote } ||
            quote.lowercase().split(Regex("[^a-z]+")).any { it in STREET_WORDS }
        // 机读码的姓名行（护照第一行）只有大写字母和 <，没有数字
        SensitiveKind.PASSPORT -> MRZ_NAME.matches(quote.replace(" ", "")) || isNumber(quote)
        in NUMBERS -> isNumber(quote)
        else -> true
    }

    private fun isNumber(quote: String) = quote.any { it.isDigit() } && quote.count { it.isLetterOrDigit() } >= MIN_NUMBER

    /** 年份，后面可以直接连着月日（「19900101」）。 */
    private val YEAR = Regex("""(?<!\d)(19|20)\d\d(?=(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(?!\d)|\D|$)""")
    private val MRZ_NAME = Regex("""(?=.{10,}$)[A-Z<]*<<[A-Z<]*""")
    private val ADDRESS_WORDS = "路街巷弄号栋幢楼室舍村镇乡区县市省庄苑".map { it.toString() } + listOf("单元", "小区", "胡同", "大厦", "公寓", "花园")
    private val STREET_WORDS = setOf(
        "street", "st", "road", "rd", "ave", "avenue", "lane", "ln", "drive", "dr", "apt", "suite", "unit", "block",
        "flat", "way", "court", "ct", "place", "pl", "boulevard", "blvd", "close", "crescent", "terrace", "square",
    )

    private val NUMBERS = setOf(
        SensitiveKind.ID_NUMBER, SensitiveKind.PAYMENT_CARD, SensitiveKind.PICKUP_CODE, SensitiveKind.PASSPORT,
        SensitiveKind.SSN, SensitiveKind.IBAN, SensitiveKind.VIN, SensitiveKind.LICENSE_PLATE, SensitiveKind.TRACKING_NO,
        SensitiveKind.IP_ADDR, SensitiveKind.MAC_ADDR, SensitiveKind.LONG_NUMBER,
    )

    /** 单独的亲属称谓、泛称。Nano 常把它们当成人名、昵称报上来，单独出现时只是称呼，打了码也遮不住谁。 */
    private val KINSHIP = setOf(
        "儿子", "女儿", "爸爸", "妈妈", "爸", "妈", "老爸", "老妈", "爹", "娘", "爷爷", "奶奶", "外公", "外婆", "姥姥", "姥爷",
        "哥哥", "姐姐", "弟弟", "妹妹", "哥", "姐", "弟", "妹", "兄弟", "姐妹", "表哥", "表姐", "表弟", "表妹",
        "堂哥", "堂姐", "堂弟", "堂妹", "老公", "老婆", "媳妇", "宝贝", "宝宝", "亲", "亲爱的", "叔叔", "阿姨",
        "舅舅", "舅妈", "姑姑", "姑父", "伯伯", "婶婶", "大哥", "大姐", "小弟", "小妹", "师傅", "老板", "老师",
        "同学", "室友", "闺蜜", "领导", "家人", "孩子", "小孩", "爱人", "对象",
    )

    /** 原文两头去掉的：各种引号，和短信签名的方括号（模型连【】一起抄过来时，按里面的字找，签名里的照样不算）。 */
    private val QUOTES = charArrayOf('"', '\'', '「', '」', '“', '”', '‘', '’', '`', '【', '】')

    private const val CHUNK_CHARS = 1200
    private const val MAX_LINE_CHARS = 120
    private const val MAX_SPAN = 60
    private const val MAX_NAME = 8
    private const val MAX_LATIN_NAME_WORDS = 4
    private const val MAX_LATIN_NAME = 40
    private const val MIN_PHONE_DIGITS = 7
    /** 号码类、验证码、密码至少几个字母数字：两三位的「12」「305」在页上到处都是，取件码、证件号、验证码没有这么短的。 */
    private const val MIN_NUMBER = 4

    /**
     * 人名的长度上限按文种算。有汉字的不超过 8 个字；没有汉字的（英文、拼音）按词数，不超过 4 个词、40 个字符：
     * 「Jennifer Walsh」有 14 个字符，按汉字的上限会被整条丢掉，「María José García López」也是一个人名。
     */
    internal fun plausibleName(quote: String): Boolean =
        if (quote.any { it in '一'..'鿿' }) quote.length <= MAX_NAME
        else quote.length <= MAX_LATIN_NAME && quote.trim().split(Regex("\\s+")).size <= MAX_LATIN_NAME_WORDS
}
