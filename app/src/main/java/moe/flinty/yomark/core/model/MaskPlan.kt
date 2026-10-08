package moe.flinty.yomark.core.model

import android.os.Parcelable
import moe.flinty.yomark.core.geometry.Quad
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/** 未圈出的区域根本不在 plan 里，因此只有两个枚举值。 */
enum class MaskState { MASKED, OUTLINED }

enum class MaskStyle { SOLID, PIXELATE, BLUR, MARKER, EMOJI, ERASE }

/**
 * 各样式的参数。一份里装着全部六种样式的参数，每块码只读它自己那种样式的几项；
 * 编辑器里调好的参数记在 SettingsStore，下次打开沿用。
 *
 * 能调的项都有范围（见 [normalized]），范围的边界是安全底线，不是审美：
 * 色块、表情底色永远不透明，马克笔永远透得过去，马赛克的块不会比出厂时更细。
 */
@Parcelize
data class MaskOptions(
    /**
     * 色块的颜色，出厂天蓝色。预览与导出是同一个渲染器，所以导出图上也是这个颜色。
     * 只换颜色不换安全性：SolidRenderer 强制不透明，换什么颜色都与原来的黑块一样不可还原；
     * 像素化太小、抹除遇到复杂背景时降级成的色块也用这个颜色。
     */
    val solidColor: Int = SKY_BLUE,
    /**
     * 马赛克的粗细：块边长约为区域短边 / divisor，再受 12px 下限约束（spec §8）。
     * 出厂的 8 就是最细的一档，只能往粗调（见 [PIXEL_DIVISOR_RANGE]、PixelateRenderer.blockSizeFor）。
     */
    val pixelBlockDivisor: Int = FINEST_PIXEL_DIVISOR,
    /** 模糊的强度：缩小倍数 = 区域短边 × 它，越大越糊。 */
    val blurRadiusRatio: Float = DEFAULT_BLUR_RATIO,
    /** 马克笔的颜色，ARGB。透明度就在 alpha 里，范围见 [MARKER_ALPHA_RANGE]。 */
    val markerColor: Int = DEFAULT_MARKER_COLOR,
    val emoji: String = DEFAULT_EMOJI,
    /** 表情底下铺的底色。不透明：表情是圆的，光靠字形盖不满矩形，四角靠它盖住。 */
    val emojiBackground: Int = EMOJI_BACKGROUND,
    /** 表情沿长边排满一排，而不是只在中间放一个。长条形的文字行上更像样。 */
    val emojiTiled: Boolean = false,
    /**
     * 当前渲染坐标系相对**原图**的比例。预览画在降采样的分析图上（< 1），导出画在原图上（= 1）。
     *
     * 渲染器里的绝对像素常数（像素化的 12px 块下限、抹除的 4px 采样环）按它换算。
     * 不换算的话，长边超过 2048 的图上预览会比导出更糊——而预览是用户判断
     * 「这块到底遮没遮住」的唯一依据，预览比导出安全等于在骗用户。
     *
     * 不是用户可调项：两个调用方（ImageCanvas / Exporter）各自在渲染前填进来。
     */
    val renderScale: Float = 1f,
) : Parcelable {

    /** 马克笔的不透明度，0～1。 */
    val markerAlpha: Float get() = (markerColor ushr 24) / 255f

    /**
     * 持久化读回来的值、滑条以外的来源都先过一遍：越界的收回范围内，该不透明的强制不透明。
     * 与 PurposeWatermarkStyle.normalized 同一个用意。
     */
    fun normalized(): MaskOptions = copy(
        solidColor = solidColor or OPAQUE,
        pixelBlockDivisor = pixelBlockDivisor.coerceIn(PIXEL_DIVISOR_RANGE),
        blurRadiusRatio = blurRadiusRatio.orIfNaN(DEFAULT_BLUR_RATIO).coerceIn(BLUR_RATIO_RANGE),
        markerColor = withAlpha(markerColor, markerAlpha.coerceIn(MARKER_ALPHA_RANGE)),
        emoji = emoji.ifBlank { DEFAULT_EMOJI },
        emojiBackground = emojiBackground or OPAQUE,
    )

    /** 只把 [style] 用到的几项放回出厂值，别的样式调好的不动。面板上的「恢复默认」。 */
    fun resetFor(style: MaskStyle): MaskOptions {
        val factory = MaskOptions()
        return when (style) {
            // 抹除的面板上调的是它降级时用的色块颜色，与色块是同一项
            MaskStyle.SOLID, MaskStyle.ERASE -> copy(solidColor = factory.solidColor)
            MaskStyle.PIXELATE -> copy(pixelBlockDivisor = factory.pixelBlockDivisor)
            MaskStyle.BLUR -> copy(blurRadiusRatio = factory.blurRadiusRatio)
            MaskStyle.MARKER -> copy(markerColor = factory.markerColor)
            MaskStyle.EMOJI -> copy(
                emoji = factory.emoji,
                emojiBackground = factory.emojiBackground,
                emojiTiled = factory.emojiTiled,
            )
        }
    }

    fun isDefaultFor(style: MaskStyle): Boolean = resetFor(style) == this

    companion object {
        /** 天蓝色 #87CEEB（CSS / X11 的 SkyBlue）。 */
        const val SKY_BLUE: Int = 0xFF87CEEB.toInt()

        /** 表情的出厂底色：近黑的深灰，什么颜色的表情放上去都看得清。 */
        const val EMOJI_BACKGROUND: Int = 0xFF333338.toInt()

        const val DEFAULT_EMOJI = "🙂"

        /** 荧光黄，60% 不透明。 */
        const val DEFAULT_MARKER_COLOR: Int = 0x99FFEB3B.toInt()

        /** 出厂的马赛克粗细，也是允许的最细一档：再细，截图上的字就更容易被逐字还原（spec §15.6）。 */
        const val FINEST_PIXEL_DIVISOR = 8

        /** 3 最粗：一条文字行上只剩一两排块。 */
        val PIXEL_DIVISOR_RANGE = 3..FINEST_PIXEL_DIVISOR

        const val DEFAULT_BLUR_RATIO = 0.08f

        /** 出厂值就是最弱的一档：更弱的模糊连「看上去遮住了」都做不到。 */
        val BLUR_RATIO_RANGE = DEFAULT_BLUR_RATIO..0.32f

        /**
         * 马克笔只做标记（spec §8），上限刻意不到 100%：调到不透明它就成了一块不受控的色块，
         * 而它的边是手绘抖动的，盖不严。下限再低就看不出标了哪儿。
         */
        val MARKER_ALPHA_RANGE = 0.2f..0.7f

        private const val OPAQUE = 0xFF000000.toInt()

        /** 把 [argb] 的透明度换成 [alpha]（0～1），颜色不变。 */
        fun withAlpha(argb: Int, alpha: Float): Int =
            ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (argb and 0x00FFFFFF)

        private fun Float.orIfNaN(fallback: Float) = if (isNaN()) fallback else this
    }
}

/**
 * 一块码长什么样：样式加它的参数。
 *
 * 每块码各自记着自己的（2026-10-07 修订，spec §7.5）：换样式不再把整张图的码一起换掉，
 * 而是从下一次打码起生效。编辑器手里那一份叫「画笔」（EditorUiState.brush），
 * 新画的框、点了打码的虚线框都用它。
 */
@Parcelize
data class MaskLook(
    val style: MaskStyle = MaskStyle.SOLID,
    val options: MaskOptions = MaskOptions(),
) : Parcelable {
    fun normalized(): MaskLook = copy(options = options.normalized())
}

@Parcelize
data class MaskItem(
    val candidateId: String,
    val quad: Quad,                   // 降采样坐标系；导出时按 1/scale 反算
    val kind: SensitiveKind,
    val source: DetectorSource,
    val state: MaskState,
    /**
     * 这块码打上时用的样子。圈出的项也带着一份，但不画；它再被打上码时换成当时的画笔，
     * 而不是沿用上一次的——用户换了样式再点虚线框，要的就是新样式。
     */
    val look: MaskLook = MaskLook(),
) : Parcelable

@Parcelize
data class MaskPlan(
    val items: List<MaskItem>,
) : Parcelable {
    /** 已识别但尚未打码的数量。导出拦截（spec §7.4）的触发条件。 */
    val pendingCount: Int get() = items.count { it.state == MaskState.OUTLINED }

    /**
     * 点一下：已打码的退回圈出；圈出的打上码，样子用 [look]（编辑器当下的画笔）。
     * 退回圈出时 look 原样留着，反正不画。
     */
    fun toggle(id: String, look: MaskLook): MaskPlan = copy(
        items = items.map {
            when {
                it.candidateId != id -> it
                it.state == MaskState.MASKED -> it.copy(state = MaskState.OUTLINED)
                else -> it.copy(state = MaskState.MASKED, look = look)
            }
        }
    )

    /**
     * 只有手动框可以被彻底删除（spec §4.2 的不对称规则）。
     * 规则/人脸/条码命中的候选永不从画面消失——候选一旦消失用户就再也找不回来。
     */
    fun remove(id: String): MaskPlan {
        val target = items.firstOrNull { it.candidateId == id } ?: return this
        if (target.source != DetectorSource.MANUAL) return this
        return copy(items = items.filterNot { it.candidateId == id })
    }

    fun add(item: MaskItem): MaskPlan = copy(items = items + item)

    fun replace(item: MaskItem): MaskPlan = copy(
        items = items.map { if (it.candidateId == item.candidateId) item else it }
    )

    /** 导出拦截里的「全部打码」：圈出的一律用 [look] 打上码，已经打了码的保持原样。 */
    fun maskAll(look: MaskLook): MaskPlan = copy(
        items = items.map { if (it.state == MaskState.OUTLINED) it.copy(state = MaskState.MASKED, look = look) else it }
    )

    /** 换掉一块码的样子（选中的手动框跟着样式栏改）。 */
    fun restyle(id: String, look: MaskLook): MaskPlan = copy(
        items = items.map { if (it.candidateId == id) it.copy(look = look) else it }
    )

    /**
     * 「应用到全部」：所有已打码的换成 [look]。圈出的不动——它们打码时自然会用那时的画笔。
     */
    fun restyleMasked(look: MaskLook): MaskPlan = copy(
        items = items.map { if (it.state == MaskState.MASKED) it.copy(look = look) else it }
    )

    /** 拦截对话框的「2 个网址、1 个 IP 地址」就是这个 map 渲染出来的。 */
    fun pendingByKind(): Map<SensitiveKind, Int> =
        items.filter { it.state == MaskState.OUTLINED }
            .groupingBy { it.kind }
            .eachCount()

    fun find(id: String): MaskItem? = items.firstOrNull { it.candidateId == id }

    companion object {
        fun empty() = MaskPlan(emptyList())
    }
}
