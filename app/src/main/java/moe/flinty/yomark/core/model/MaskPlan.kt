package moe.flinty.yomark.core.model

import android.os.Parcelable
import moe.flinty.yomark.core.geometry.Quad
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/** 未圈出的区域根本不在 plan 里，因此只有两个枚举值。 */
enum class MaskState { MASKED, OUTLINED }

/**
 * 枚举名是持久化格式的一部分（SettingsStore 记着画笔的样式名）。
 * 原先还有一种「抹除」，2026-10-11 并进了色块，成了色块的「近似色」（见 [MaskOptions.solidNear]）；
 * 还有一种「表情」，2026-10-12 去掉了。记着这两个名字的旧设置读回来认不出，落回色块。
 * 画笔（[PEN]）2026-10-14 加上：不透明的线，和马克笔一样只做标记，可以拉框也可以手画。
 */
enum class MaskStyle {
    SOLID, PIXELATE, BLUR, MARKER, PEN;

    /**
     * 拿来打码的：色块、马赛克、模糊，底栏的「打码」。马克笔、画笔只在图上做标记（「编辑」），
     * 识别出来直接打的码、点了打码的虚线框、导出前的「全部打码」都不用它们，用「打码」里上次选的（见 EditorUiState.maskLook）。
     */
    val redacts: Boolean get() = this != MARKER && this != PEN
}

/**
 * 各样式的参数。一份里装着全部五种样式的参数，每块码只读它自己那种样式的几项；
 * 编辑器里调好的参数记在 SettingsStore，下次打开沿用。
 *
 * 能调的项都有范围（见 [normalized]），范围的边界是安全底线，不是审美：
 * 色块永远不透明，马克笔永远透得过去，马赛克的块不会比出厂时更细。
 */
@Parcelize
data class MaskOptions(
    /**
     * 色块的颜色，出厂天蓝色。预览与导出是同一个渲染器，所以导出图上也是这个颜色。
     * 只换颜色不换安全性：SolidRenderer 强制不透明，换什么颜色都与原来的黑块一样不可还原；
     * 像素化太小时降级成的色块也用这个颜色。开着 [solidNear] 时不用它，但它还记着，关掉近似色就回到它。
     */
    val solidColor: Int = SKY_BLUE,
    /**
     * 近似色：色块不填 [solidColor]，每一块各取它四周的颜色（见 NearColor），在纯色背景上看不太出打过码。
     * 照样是不透明的一整块，安全性与色块相同。原先的「抹除」就是它，只是背景太杂、四周取不到像素时降级成色块；
     * 现在总取一种近似的颜色，从不退回 [solidColor]——它只说颜色相近，不说看不出来，
     * 杂的背景上取到的颜色也比一块天蓝更融得进去。
     */
    val solidNear: Boolean = false,
    /**
     * 马赛克的粗细：块边长 = 图片决定的基准块 × (8 / divisor)，与选框大小无关（spec §8）。
     * 名字是历史遗留，存档的键也沿用它。8 是最细的一档，只能往粗调（见 [PIXEL_DIVISOR_RANGE]、PixelateRenderer.blockSizeFor）。
     */
    val pixelBlockDivisor: Int = DEFAULT_PIXEL_DIVISOR,
    /** 模糊的强度：缩小倍数 = 区域短边 × 它，越大越糊。 */
    val blurRadiusRatio: Float = DEFAULT_BLUR_RATIO,
    /** 马克笔的颜色，ARGB。透明度就在 alpha 里，范围见 [MARKER_ALPHA_RANGE]。 */
    val markerColor: Int = DEFAULT_MARKER_COLOR,
    /** 马克笔下一笔是拉框（照旧涂一块）还是手画（一道半透明的粗线）。 */
    val markerShape: StrokeShape = StrokeShape.BOX,
    /** 马克笔手画时的笔宽，按图的短边算的比例（见 [halfWidth]）。拉框时用不着。 */
    val markerWidth: Float = DEFAULT_MARKER_WIDTH,
    /** 画笔的颜色，不透明。 */
    val penColor: Int = DEFAULT_PEN_COLOR,
    /** 画笔下一笔是拉框（描一圈框线）还是手画。出厂是手画：画笔就是拿来随手画的。 */
    val penShape: StrokeShape = StrokeShape.FREEHAND,
    /** 画笔的笔宽，按图的短边算的比例（见 [halfWidth]）。 */
    val penWidth: Float = DEFAULT_PEN_WIDTH,
    /**
     * 当前渲染坐标系相对**原图**的比例。预览画在降采样的分析图上（< 1），导出画在原图上（= 1）。
     *
     * 渲染器里的绝对像素常数（像素化的 12px 块下限、近似色的 4px 采样环）按它换算。
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
        markerWidth = markerWidth.orIfNaN(DEFAULT_MARKER_WIDTH).coerceIn(MARKER_WIDTH_RANGE),
        penColor = penColor or OPAQUE,
        penWidth = penWidth.orIfNaN(DEFAULT_PEN_WIDTH).coerceIn(PEN_WIDTH_RANGE),
    )

    /** [style] 下一笔怎么画。只有马克笔、画笔能手画，别的样式一律拉框。 */
    fun shapeFor(style: MaskStyle): StrokeShape = when (style) {
        MaskStyle.MARKER -> markerShape
        MaskStyle.PEN -> penShape
        else -> StrokeShape.BOX
    }

    /**
     * [style] 的半个笔宽：图的短边（[shortSide]，与要画的那张图同一坐标系）乘上笔宽比例的一半。
     * 按比例算，预览画在分析图上、导出画在原图上，粗细看上去一样。马克笔拉框、打码的几种不描线，是 0。
     */
    fun halfWidth(style: MaskStyle, shortSide: Float): Float = when (style) {
        MaskStyle.MARKER -> markerWidth
        MaskStyle.PEN -> penWidth
        else -> 0f
    } * shortSide / 2f

    /**
     * 把 [from] 里 [style] 用到的几项换进来，别的样式的不动。
     * 编辑器的画笔从选中的框那儿接改动时用它：框带着的是它画上时的一整份，别的样式那几项可能早就过时了。
     */
    fun withParamsOf(style: MaskStyle, from: MaskOptions): MaskOptions = when (style) {
        MaskStyle.SOLID -> copy(solidColor = from.solidColor, solidNear = from.solidNear)
        MaskStyle.PIXELATE -> copy(pixelBlockDivisor = from.pixelBlockDivisor)
        MaskStyle.BLUR -> copy(blurRadiusRatio = from.blurRadiusRatio)
        MaskStyle.MARKER -> copy(markerColor = from.markerColor, markerShape = from.markerShape, markerWidth = from.markerWidth)
        MaskStyle.PEN -> copy(penColor = from.penColor, penShape = from.penShape, penWidth = from.penWidth)
    }

    /** 只把 [style] 用到的几项放回出厂值，别的样式调好的不动。面板上的「恢复默认」。 */
    fun resetFor(style: MaskStyle): MaskOptions = withParamsOf(style, MaskOptions())

    fun isDefaultFor(style: MaskStyle): Boolean = resetFor(style) == this

    companion object {
        /** 天蓝色 #87CEEB（CSS / X11 的 SkyBlue）。 */
        const val SKY_BLUE: Int = 0xFF87CEEB.toInt()

        /** 荧光黄，60% 不透明。 */
        const val DEFAULT_MARKER_COLOR: Int = 0x99FFEB3B.toInt()

        /** 允许的最细一档：再细，截图上的字就更容易被逐字还原（spec §15.6）。 */
        const val FINEST_PIXEL_DIVISOR = 8

        /** 出厂是中间的「适中」：最细一档透得太多。 */
        const val DEFAULT_PIXEL_DIVISOR = 6

        /** 3 最粗：一条文字行上只剩一两排块。 */
        val PIXEL_DIVISOR_RANGE = 3..FINEST_PIXEL_DIVISOR

        /** 最弱的一档：更弱的模糊连「看上去遮住了」都做不到。 */
        const val WEAKEST_BLUR_RATIO = 0.08f

        /** 出厂是中间的「适中」：最弱一档透得太多。 */
        const val DEFAULT_BLUR_RATIO = 0.20f

        val BLUR_RATIO_RANGE = WEAKEST_BLUR_RATIO..0.32f

        /**
         * 马克笔只做标记（spec §8），上限刻意不到 100%：调到不透明它就成了一块不受控的色块，
         * 而它的边是手绘抖动的，盖不严。下限再低就看不出标了哪儿。
         */
        val MARKER_ALPHA_RANGE = 0.2f..0.7f

        /** 马克笔手画的笔宽：出厂差不多是截图上一行字的高。 */
        const val DEFAULT_MARKER_WIDTH = 0.035f
        val MARKER_WIDTH_RANGE = 0.015f..0.07f

        /** 画笔：出厂是红色，细线。 */
        const val DEFAULT_PEN_COLOR: Int = 0xFFE53935.toInt()
        const val DEFAULT_PEN_WIDTH = 0.008f
        val PEN_WIDTH_RANGE = 0.003f..0.025f

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
 * 而是从下一次打码起生效。编辑器手里那一份叫「画笔」（EditorUiState.brush），新画的框、手画的线用它；
 * 点了打码的虚线框、识别出来直接打码的用「打码」里上次选的那一种（EditorUiState.maskLook）。
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
     * 这块码打上时用的样子。圈出的项也带着一份，但不画；它再被打上码时换成当时「打码」里的样式，
     * 而不是沿用上一次的——用户换了样式再点虚线框，要的就是新样式。
     */
    val look: MaskLook = MaskLook(),
    /**
     * 手画的一笔（马克笔、画笔手画时）：[quad] 是它的外接框，渲染器照着这条线画，不填满框。
     * null 是拉的框。手画的那一笔换成打码的样式时盖住整个外接框，宁可多盖。
     */
    val ink: InkPath? = null,
) : Parcelable

@Parcelize
data class MaskPlan(
    val items: List<MaskItem>,
) : Parcelable {
    /** 已识别但尚未打码的数量。导出拦截（spec §7.4）的触发条件。 */
    val pendingCount: Int get() = items.count { it.state == MaskState.OUTLINED }

    /**
     * 点一下：已打码的退回圈出；圈出的打上码，样子用 [look]（编辑器「打码」里眼下的样式）。
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
     * 换打码样式时一起换（设置「编辑」页的开关）：已打码、用的是盖住内容那三种样式的，都换成 [look]。
     * 马克笔、画笔画的标注不动；圈出的也不动——它们打码时自然会用那时的画笔。
     */
    fun restyleRedactions(look: MaskLook): MaskPlan = copy(
        items = items.map { if (it.state == MaskState.MASKED && it.look.style.redacts) it.copy(look = look) else it }
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
