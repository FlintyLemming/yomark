package moe.flinty.yomark.rules

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 卡背面没有字段名的安全码：签名条右头、或者签名条右边白框里斜体的三位数，前面常带着卡号末四位（「8810 357」）。
 *
 * 光看三位数什么都可能是。它的旁证是签名条：条的上面、下面或者条里印着「持卡人签名」「AUTHORIZED SIGNATURE」
 * 「NOT VALID UNLESS SIGNED」，码和这行字上下相隔不过 [REACH] 个字高（签名条有码的三四倍高），左右不过 [ACROSS] 个（一张卡宽）。
 * - 码自己成一段（[cells]），前后至多多出 OCR 从斜体字边上读出的一撇、一竖；前面跟着冒号的是那个字段的值（「柜员号： 123」）；
 * - 页上还要有卡（[onCard]）：卡组织、银行、「本卡」「持卡人」这类字，或者 Luhn 对得上的卡号。快递单、表格上也印着「签名」，
 *   旁边的格子里也有三位数；
 * - 签名条上的字没读出来的，「末四位 空格 三位」这个样子够特别，页上有卡就认。
 *
 * 末四位连着安全码一起遮：条上印的是一整块。
 */
internal class CardBackCode(private val onCard: (String) -> Boolean, private val confidence: Float) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val strips = page.texts.indices.flatMap { i -> STRIP.findAll(page.texts[i]).map { i to it.range }.toList() }
        val lines = page.texts.indices.filter { j ->
            LAST_FOUR.containsMatchIn(page.texts[j]) || (strips.isNotEmpty() && THREE.containsMatchIn(page.texts[j]))
        }
        if (lines.isEmpty() || page.texts.none(onCard)) return emptyList()
        return lines.flatMap { j ->
            val text = page.texts[j]
            page.cells(j).mapNotNull { cell ->
                val code = CODE.matchEntire(text.substring(cell))?.groups?.get(1) ?: return@mapNotNull null
                if (text.substring(0, cell.first).trimEnd().lastOrNull()?.let { it in ":：" } == true) return@mapNotNull null
                val r = (cell.first + code.range.first)..(cell.first + code.range.last)
                val box = page.boxIn(j, r, ref = j)
                val anchored = ' ' in code.value || strips.any { (i, s) -> near(page.boxIn(i, s, ref = j), box) }
                if (anchored) PageMatch(j, RuleMatch(r, confidence)) else null
            }
        }
    }

    private fun near(strip: Bounds, code: Bounds): Boolean {
        val h = max(strip.height, code.height)
        if (h <= 0f) return false
        val apart = max(0f, max(strip.left, code.left) - min(strip.right, code.right))
        return abs(code.centerY - strip.centerY) <= REACH * h && apart <= ACROSS * h
    }

    private companion object {
        /** 签名条上下、条里印的字。日本的卡上是「ご署名」。 */
        val STRIP = Regex(cue("签名", "簽名", "署名").pattern + """|(?i:signature|unless ?signed)""")

        /** 一段里只有码：三位，或者末四位、空格、三位；前后至多多出斜体字边上的一撇、一竖。 */
        val CODE = Regex("""[/|\\'’]?(\d{4} +\d{3}|\d{3})[/|\\'’]?""")

        /** 这一行里有没有可能是码的数字，没有就不切段。 */
        val THREE = Regex("""(?<!\d)\d{3}(?!\d)""")
        val LAST_FOUR = Regex("""(?<!\d)\d{4} +\d{3}(?!\d)""")

        /** 码和签名条上的字上下最多隔几个字高（按两个里高的那个）。 */
        const val REACH = 4f

        /** 左右最多隔几个字高。 */
        const val ACROSS = 30f
    }
}
