package com.youma.app.ui.canvas

import androidx.core.graphics.ColorUtils
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.SensitiveKindLabels

/**
 * 已打码的块里写上它原来是什么（「电话」「人名」…）。只画在编辑器里，不进导出。
 *
 * 打了码就看不见底下是什么，用户没法判断这一块遮得对不对，只能点开再点回去。
 * 但不套用圈出项那种琥珀色虚线框加外侧小标签：琥珀色虚线在这个界面里的意思是
 * 「还没打码」，套到已打码的块上会把两种状态混成一种；块本身的边就是范围，再框一圈是重复；
 * 外侧标签在密集截图上会糊成一片。写在块**里面**，不占块外的任何地方。
 *
 * 全是纯函数，画布只负责量字和落笔。
 */
internal object MaskedLabel {

    /** 字高占块高的上限。留出上下余量，读起来是「块上的注记」而不是「块里的内容」。 */
    const val HEIGHT_RATIO = 0.7f

    /** 左右各留的边距，按字号计。 */
    const val PAD_RATIO = 0.3f

    /** 天蓝色块上的深蓝字，带一点透明，读起来是注记；叠在天蓝上对比度仍约 5:1。 */
    const val DARK_INK: Int = 0xD90B3954.toInt()
    const val LIGHT_INK: Int = 0xD9FFFFFF.toInt()

    /**
     * 只写在实色块上：其余样式的预览本身就是用户要看的效果（模糊得够不够、Emoji 摆得正不正），
     * 在上面写字等于挡住预览。手动框不写——那是用户自己画的，他知道底下是什么。
     */
    fun applies(item: MaskItem, style: MaskStyle): Boolean =
        style == MaskStyle.SOLID && item.state == MaskState.MASKED && item.kind != SensitiveKind.MANUAL

    /** 与圈出项的小标签同一套措辞：模型猜的标「AI」，与规则命中区分开（spec §3）。 */
    fun text(item: MaskItem): String =
        SensitiveKindLabels.display(item.kind) + if (item.source == DetectorSource.LLM) " · AI" else ""

    /**
     * 能放下标签的字号（屏幕像素）。放不下返回 null，这一块就不写——放大了自然会出现。
     *
     * @param widthPerPx 标签在 1px 字号下的宽度。文字宽度与字号成正比，量一次就够。
     */
    fun fitTextSize(blockWidth: Float, blockHeight: Float, widthPerPx: Float, minPx: Float, maxPx: Float): Float? {
        if (widthPerPx <= 0f) return null
        val byHeight = blockHeight * HEIGHT_RATIO
        val byWidth = blockWidth / (widthPerPx + 2 * PAD_RATIO)
        val size = minOf(maxPx, byHeight, byWidth)
        return if (size >= minPx) size else null
    }

    /**
     * 块色偏亮写深字，偏暗写浅字。阈值取两种字对比度相等的那一点：
     * (L + 0.05)² = 1.05 × (L(深蓝) + 0.05)，L ≈ 0.25。
     * 天蓝色块（L ≈ 0.55）上是深蓝字；从旧会话恢复出来的黑块上是白字。
     */
    fun inkFor(fill: Int): Int =
        if (ColorUtils.calculateLuminance(fill) > INK_SWITCH_LUMINANCE) DARK_INK else LIGHT_INK

    private const val INK_SWITCH_LUMINANCE = 0.25
}
