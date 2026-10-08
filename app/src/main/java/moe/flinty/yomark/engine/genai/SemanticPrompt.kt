package moe.flinty.yomark.engine.genai

import moe.flinty.yomark.core.model.SensitiveKind

/**
 * 给端侧大模型的提示词，以及把它的回答落回到行内区间。
 *
 * 纯文本进、纯文本出，不碰模型也不碰 Android，单测直接覆盖。
 *
 * 回答格式故意用「行号|类型|原文」而不是 JSON：Gemini Nano 这种尺寸的模型
 * 写 JSON 经常少括号、多逗号，一行一条的竖线格式坏一条只丢一条。
 *
 * **原文必须能在那一行里找到**，找不到就丢——模型会改写、会编，
 * 一个找不到出处的区间没法落到像素上，也不该被信任。
 */
object SemanticPrompt {

    /** 一次请求。提示词里的第 n 行对应原来的 lines[lineIndices[n - 1]]。 */
    class Chunk(val prompt: String, val lineIndices: List<Int>)

    data class Finding(val lineIndex: Int, val range: IntRange, val kind: SensitiveKind)

    /**
     * 把可用的行切成若干次请求。Nano 的输入上限约 4000 token，中文一字约一 token，
     * 每次留到 1200 字，给提示词本身和回答留足余量。
     *
     * @param texts null 表示这一行不送（置信度太低、没有文字内容）。
     */
    fun chunks(texts: List<String?>, budget: Int = CHUNK_CHARS): List<Chunk> {
        val out = ArrayList<Chunk>()
        var body = StringBuilder()
        var indices = ArrayList<Int>()
        fun flush() {
            if (indices.isEmpty()) return
            out += Chunk(INSTRUCTIONS + body, indices)
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

    fun parse(reply: String, chunk: Chunk, texts: List<String>): List<Finding> =
        reply.lineSequence().mapNotNull { row ->
            val m = ROW.matchEntire(row.trim()) ?: return@mapNotNull null
            val n = m.groupValues[1].toInt()
            val lineIndex = chunk.lineIndices.getOrNull(n - 1) ?: return@mapNotNull null
            val kind = KINDS[m.groupValues[2]] ?: return@mapNotNull null
            val quote = m.groupValues[3].trim().trim(*QUOTES)
            if (quote.length < 2 || quote.length > MAX_SPAN) return@mapNotNull null
            if (kind == SensitiveKind.PERSON_NAME && quote.length > MAX_NAME) return@mapNotNull null
            val range = locate(texts[lineIndex], quote) ?: return@mapNotNull null
            Finding(lineIndex, range, kind)
        }.distinct().toList()

    /**
     * 原文在行里的位置。先精确找；找不到再忽略空格找——
     * OCR 行里的空格是按像素补的，模型复述时常常把它吞掉或多加一个。
     */
    internal fun locate(line: String, quote: String): IntRange? {
        val exact = line.indexOf(quote)
        if (exact >= 0) return exact until exact + quote.length

        val origin = line.indices.filter { !line[it].isWhitespace() }
        val compactLine = origin.map { line[it] }.joinToString("")
        val compactQuote = quote.filterNot { it.isWhitespace() }
        if (compactQuote.isEmpty()) return null
        val at = compactLine.indexOf(compactQuote)
        if (at < 0) return null
        return origin[at]..origin[at + compactQuote.length - 1]
    }

    private val KINDS = mapOf(
        "人名" to SensitiveKind.PERSON_NAME,
        "地址" to SensitiveKind.POSTAL_ADDRESS,
        "电话" to SensitiveKind.PHONE,
        "号码" to SensitiveKind.LONG_NUMBER,
    )

    private val ROW = Regex("""(\d+)\s*[|｜]\s*(人名|地址|电话|号码)\s*[|｜]\s*(.+)""")
    private val QUOTES = charArrayOf('"', '\'', '「', '」', '“', '”', '‘', '’', '`')

    private const val CHUNK_CHARS = 1200
    private const val MAX_LINE_CHARS = 120
    private const val MAX_SPAN = 60
    private const val MAX_NAME = 8

    internal val INSTRUCTIONS = """
        |你在帮用户给手机截图打码。下面是截图里识别出的文字，每行以「行号: 」开头。
        |请找出其中能指向具体某个人的隐私信息，只包括四类：
        |人名：真实姓名，或带姓的称呼（如「王先生」）
        |地址：住址、收货地址、小区楼栋门牌
        |电话：手机号、座机号，包括打了星号的
        |号码：身份证号、银行卡号、账号、取件码、验证码等个人号码
        |不要输出：店铺名、公司名、商品名、按钮和提示文字、城市名、日期时间、价格。
        |每找到一处输出一行，格式为：行号|类型|原文
        |原文必须从该行逐字复制，只复制隐私的那一段，不加引号、不加解释。
        |一处也没有就只输出：无
        |
        |""".trimMargin()
}
