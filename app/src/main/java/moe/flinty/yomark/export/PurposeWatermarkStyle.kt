package moe.flinty.yomark.export

import kotlin.math.roundToInt

/**
 * 用途水印的外观：颜色、角度、透明度、密度。文案不在这里——文案只活在当前会话里，不落盘；
 * 外观是无隐私含义的偏好，记在 SettingsStore 里，下次打开沿用。
 *
 * 默认值就是这个功能最早的固定外观（黑字、-30°、约 18%、标准间距）。
 */
data class PurposeWatermarkStyle(
    /** 不透明的 RGB。透明度单独由 [opacity] 管，两者不混在一个 ARGB 里。 */
    val color: Int = PALETTE.first(),
    /** 逆时针为负，与 Canvas.rotate 一致。 */
    val angle: Float = DEFAULT_ANGLE,
    val opacity: Float = DEFAULT_OPACITY,
    /** 1 = 标准间距；越大行距与字间空隙越小。 */
    val density: Float = DEFAULT_DENSITY,
) {

    /** 持久化读回来的值、滑条以外的来源都先过一遍：越界的收回范围内，颜色强制不透明。 */
    fun normalized() = PurposeWatermarkStyle(
        color = color or OPAQUE,
        angle = angle.coerceIn(ANGLE_RANGE),
        opacity = opacity.coerceIn(OPACITY_RANGE),
        density = density.coerceIn(DENSITY_RANGE),
    )

    /** 画笔用的 alpha。 */
    val alpha: Int get() = (opacity.coerceIn(OPACITY_RANGE) * 255f).roundToInt()

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        const val DEFAULT_ANGLE = -30f
        const val DEFAULT_OPACITY = 0.18f
        const val DEFAULT_DENSITY = 1f

        val ANGLE_RANGE = -90f..90f
        /**
         * 上限刻意不到 100%：用途水印要让证件上的字仍然读得出来，
         * 不透明的字压在号码上，等于替用户又打了一层不受控的码。
         */
        val OPACITY_RANGE = 0.05f..0.6f
        val DENSITY_RANGE = 0.5f..2f

        /** 黑、灰、白、红、蓝、绿：深浅底各有能看清的，红蓝是证件复印件上常见的盖章色。 */
        val PALETTE = listOf(
            0xFF000000.toInt(),
            0xFF757575.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFD32F2F.toInt(),
            0xFF1565C0.toInt(),
            0xFF2E7D32.toInt(),
        )
    }
}
