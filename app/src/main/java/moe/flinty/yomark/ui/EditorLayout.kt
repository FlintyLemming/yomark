package moe.flinty.yomark.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 编辑器的版式（宽屏适配，见 2026-10-10 宽屏增补设计）。
 *
 * 导出总在顶栏右上角。手机竖着拿是 [PHONE]：画布在上，工具那一行在下面。横着拿是 [PHONE_SIDE]：上下分不出地方，
 * 功能方块挪到画布右边，和宽屏横着拿一样排，只是没有列表。平板、折叠屏展开、阔折叠这类宽屏
 * 多出一份「框出的内容」列表：竖着拿是 [WIDE_BOTTOM]，画布在上，下面左边是功能方块、右边是列表；
 * 横着拿是 [WIDE_SIDE]，这一块挪到画布右边，方块在上、列表在下。宽屏、手机横着拿的功能都平铺成方块（见 WideEditorPanel）。
 */
enum class EditorLayout(
    /** 功能方块在画布右边，不在下面。 */
    val atSide: Boolean,
    /** 有「框出的内容」列表。 */
    val listed: Boolean,
) {
    PHONE(atSide = false, listed = false),
    PHONE_SIDE(atSide = true, listed = false),
    WIDE_BOTTOM(atSide = false, listed = true),
    WIDE_SIDE(atSide = true, listed = true),
}

object EditorLayouts {

    /** 平板、书本式折叠屏展开后的最短边都在这以上（Android 窗口尺寸分级里「中等」宽度的起点）。 */
    const val TABLET_SMALLEST_WIDTH_DP = 600

    /**
     * 阔折叠展开后的最短边在这以上：华为 Pura X 的内屏 1320 px 宽、约 3.3 英寸，按常见的密度折算是 500～530 dp。
     * 普通手机竖着拿四百出头，把显示大小调到最小也很少超过 490。
     */
    const val WIDE_FOLD_SMALLEST_WIDTH_DP = 500

    /**
     * 阔折叠的屏幕是 16:10，长边不到短边的 1.8 倍；手机是 19.5:9、20:9，长边是短边的两倍多。
     * 只看宽度分不清「显示大小调到最小的大屏手机」和阔折叠，这条比例把两者分开。
     */
    const val WIDE_FOLD_MAX_ASPECT = 1.8f

    /**
     * 手机的窗口宽比高多出这么多，才把功能挪到右边、平铺成方块。横过来的手机宽是高的两倍上下，远在这以上；
     * 横屏分屏分到一半、小外屏这类近乎方的窗口，右边那一栏一占（至少 320 dp），画布只剩窄窄一条，工具还是放在下面。
     */
    const val PHONE_SIDE_MIN_ASPECT = 1.25f

    /**
     * 参数都取自 Configuration：[widthDp]、[heightDp] 是窗口当下的宽高，[smallestWidthDp] 是不随转向变的最短边
     * （分屏、自由窗口里是这个窗口的）。设备类型只看最短边：转个方向不会从宽屏变回手机，只是方块和列表换个位置。
     */
    fun choose(widthDp: Int, heightDp: Int, smallestWidthDp: Int): EditorLayout = when {
        isWide(widthDp, heightDp, smallestWidthDp) ->
            if (widthDp > heightDp) EditorLayout.WIDE_SIDE else EditorLayout.WIDE_BOTTOM
        widthDp > heightDp * PHONE_SIDE_MIN_ASPECT -> EditorLayout.PHONE_SIDE
        else -> EditorLayout.PHONE
    }

    private fun isWide(widthDp: Int, heightDp: Int, smallestWidthDp: Int): Boolean {
        if (smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP) return true
        if (smallestWidthDp < WIDE_FOLD_SMALLEST_WIDTH_DP) return false
        val short = min(widthDp, heightDp)
        val long = max(widthDp, heightDp)
        return short > 0 && long <= short * WIDE_FOLD_MAX_ASPECT
    }
}
