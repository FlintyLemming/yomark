package moe.flinty.yomark.ui.settings

import androidx.compose.ui.graphics.Color

// 由 tools/theme/gen_settings_icon_colors.mjs 生成，不要手改。
// 每种颜色是种子色的两条色调（Material Color Utilities 的 TonalPalette）：圆底取 90、彩度不超过 24，图标取 30；
// 深色界面里圆底取 30、图标取 80。

internal object SettingsIconColors {

    /** 种子色 #1A73E8 */
    val blue = SettingsIconColor(
        container = Color(0xFFD8E2FF),
        content = Color(0xFF004493),
        darkContainer = Color(0xFF384764),
        darkContent = Color(0xFFADC7FF),
    )

    /** 种子色 #F57C00 */
    val orange = SettingsIconColor(
        container = Color(0xFFFFDCC6),
        content = Color(0xFF723600),
        darkContainer = Color(0xFF633E23),
        darkContent = Color(0xFFFFB786),
    )

    /** 种子色 #43A047 */
    val green = SettingsIconColor(
        container = Color(0xFFCCEBC4),
        content = Color(0xFF005313),
        darkContainer = Color(0xFF334D30),
        darkContent = Color(0xFF7DDC7A),
    )

    /** 种子色 #6750A4 */
    val purple = SettingsIconColor(
        container = Color(0xFFE9DDFF),
        content = Color(0xFF4F378A),
        darkContainer = Color(0xFF4B4263),
        darkContent = Color(0xFFCFBCFF),
    )

    /** 种子色 #00897B */
    val teal = SettingsIconColor(
        container = Color(0xFFBCECE2),
        content = Color(0xFF005048),
        darkContainer = Color(0xFF204E47),
        darkContent = Color(0xFF70D8C8),
    )

    /** 种子色 #D81B60 */
    val pink = SettingsIconColor(
        container = Color(0xFFFFD9DE),
        content = Color(0xFF90003B),
        darkContainer = Color(0xFF653B43),
        darkContent = Color(0xFFFFB2BF),
    )

    /** 种子色 #FBBC04 */
    val yellow = SettingsIconColor(
        container = Color(0xFFFDDFA6),
        content = Color(0xFF5C4300),
        darkContainer = Color(0xFF574419),
        darkContent = Color(0xFFFBBC05),
    )

    /** 种子色 #3F51B5 */
    val indigo = SettingsIconColor(
        container = Color(0xFFDEE0FF),
        content = Color(0xFF293CA0),
        darkContainer = Color(0xFF404565),
        darkContent = Color(0xFFBAC3FF),
    )

    /** 种子色 #1A73E8，彩度压到 6 */
    val gray = SettingsIconColor(
        container = Color(0xFFE2E2E9),
        content = Color(0xFF45474C),
        darkContainer = Color(0xFF45474C),
        darkContent = Color(0xFFC6C6CD),
    )
}
