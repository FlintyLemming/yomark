package moe.flinty.yomark.statusbar

import moe.flinty.yomark.chat.Pixels
import moe.flinty.yomark.chat.toChatLine
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.CropFinding
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.CropFinder
import kotlin.math.ceil
import kotlin.math.min

/**
 * 实验室的「裁切状态栏」：认出截图顶上的状态栏，编辑器上压暗它，导出时裁掉（怎么认见 [StatusBar]）。
 *
 * 这里只做安卓这一层的转换：位图顶上一截 → ARGB 数组（长截图不用整张都读出来），OCR 行 → 外接框和逐字的框。
 * 页面转了直角的不找：状态栏在转正以后的图顶上，原图上在侧边。设置里关着时（出厂关着）根本不建它。
 */
class StatusBarCropper : CropFinder {
    override val id = "status-bar"

    override suspend fun find(image: SourceImage, lines: List<TextLine>): CropFinding {
        if (ReadingFrame.of(lines.map { it.quad }).quarterTurns != 0) return CropFinding(null, listOf("页面转过直角，不找"))
        val bmp = image.bitmap
        val rows = min(bmp.height, ceil(StatusBar.BAND * min(bmp.width, bmp.height)).toInt())
        val argb = IntArray(bmp.width * rows)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, rows)
        val cut = StatusBar.find(lines.map { it.toChatLine() }, Pixels(bmp.width, rows, argb), bmp.height)
        return CropFinding(cut.bottom?.toFloat(), cut.notes)
    }
}
