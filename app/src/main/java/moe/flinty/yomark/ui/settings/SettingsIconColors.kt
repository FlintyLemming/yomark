package moe.flinty.yomark.ui.settings

import androidx.compose.ui.graphics.Color

// 由 tools/theme/gen_settings_icon_colors.mjs 生成，不要手改。
// 每种颜色是种子色的两条色调（Material Color Utilities 的 TonalPalette）：圆底取 90、彩度不超过 24，图标取 30。

internal object SettingsIconColors {

    /** 种子色 #1A73E8 */
    val blue = SettingsIconColor(
        container = Color(0xFFD8E2FF),
        content = Color(0xFF004493),
    )

    /** 种子色 #F57C00 */
    val orange = SettingsIconColor(
        container = Color(0xFFFFDCC6),
        content = Color(0xFF723600),
    )

    /** 种子色 #43A047 */
    val green = SettingsIconColor(
        container = Color(0xFFCCEBC4),
        content = Color(0xFF005313),
    )

    /** 种子色 #6750A4 */
    val purple = SettingsIconColor(
        container = Color(0xFFE9DDFF),
        content = Color(0xFF4F378A),
    )

    /** 种子色 #00897B */
    val teal = SettingsIconColor(
        container = Color(0xFFBCECE2),
        content = Color(0xFF005048),
    )

    /** 种子色 #D81B60 */
    val pink = SettingsIconColor(
        container = Color(0xFFFFD9DE),
        content = Color(0xFF90003B),
    )

    /** 种子色 #FBBC04 */
    val yellow = SettingsIconColor(
        container = Color(0xFFFDDFA6),
        content = Color(0xFF5C4300),
    )

    /** 种子色 #1A73E8，彩度压到 6 */
    val gray = SettingsIconColor(
        container = Color(0xFFE2E2E9),
        content = Color(0xFF45474C),
    )
}
