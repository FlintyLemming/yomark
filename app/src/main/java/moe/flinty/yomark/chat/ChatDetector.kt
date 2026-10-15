package moe.flinty.yomark.chat

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.PageAnalyzer
import kotlin.math.max

/**
 * 聊天截图：认出是聊天记录的页上，发言者的头像、昵称和顶栏的聊天标题（版面规则见 [ChatLayout]）。
 *
 * 这里只做安卓这一层的转换：位图 → ARGB 数组，OCR 行 → 外接框，找到的框 → 候选。
 * 页面转了直角（竖着拍、倒着放）的不认：聊天截图都是正着的，转过的多半是拍的纸面。
 * 认出来的都打码；设置里关掉「聊天记录」时根本不建它。
 */
class ChatDetector : PageAnalyzer {
    override val id = "chat-layout"

    override suspend fun analyze(image: SourceImage, lines: List<TextLine>): List<Candidate> {
        if (ReadingFrame.of(lines.map { it.quad }).quarterTurns != 0) return emptyList()
        val bmp = image.bitmap
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val chatLines = lines.map { l ->
            val r = l.quad.bounds()
            val chars = arrayOfNulls<Box>(l.text.length)
            l.elements.forEach { e ->
                val c = e.quad.bounds()
                e.range.forEach { i -> if (i in chars.indices) chars[i] = Box(c.left, c.top, c.right, c.bottom) }
            }
            ChatLine(Box(r.left, r.top, r.right, r.bottom), l.text, chars.toList())
        }
        val found = ChatLayout.analyze(chatLines, Pixels(bmp.width, bmp.height, argb))
        if (!found.isChat) return emptyList()
        return candidates(found)
    }

    companion object {
        private const val CONFIDENCE = 0.85f

        /**
         * 找到的框 → 候选。头像四周放宽一点（边长的 6%）：圆头像抗锯齿的那一圈、阴影，按框遮不全。
         * 昵称、标题放宽两个像素，和文字规则的框一样贴字。
         */
        fun candidates(found: ChatFindings): List<Candidate> = buildList {
            found.avatars.forEachIndexed { i, b ->
                val pad = max(2f, 0.06f * b.width)
                add(candidate("chat-avatar-$i", b, pad, SensitiveKind.CHAT_AVATAR))
            }
            found.names.forEachIndexed { i, n -> add(candidate("chat-name-$i", n.box, 2f, SensitiveKind.CHAT_NAME)) }
            found.title?.let { add(candidate("chat-title", it.box, 2f, SensitiveKind.CHAT_TITLE)) }
        }

        private fun candidate(id: String, b: Box, pad: Float, kind: SensitiveKind) = Candidate(
            id = id,
            quad = Quad.fromRect(RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)),
            kind = kind,
            source = DetectorSource.RULE,
            confidence = CONFIDENCE,
        )
    }
}
