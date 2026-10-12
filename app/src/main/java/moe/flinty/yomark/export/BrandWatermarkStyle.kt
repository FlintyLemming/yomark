package moe.flinty.yomark.export

import java.util.Locale

/**
 * 免费导出的品牌水印用哪一版字样，图标两版都有（见 [WatermarkDrawer]）。
 *
 * 没选过就跟着系统语言走：中文系统用「有码 Yomark」，别的语言用不带中文的「Yomark」——
 * 看不懂的汉字压在图上，只会被当成乱码。枚举名是持久化格式的一部分，见 SettingsStore。
 */
enum class BrandWatermarkStyle(val text: String) {
    /** 图标 + 有码 Yomark */
    ICON_ZH_EN("有码 Yomark"),

    /** 图标 + Yomark，不带中文 */
    ICON_EN("Yomark");

    companion object {
        fun defaultFor(locale: Locale): BrandWatermarkStyle =
            if (locale.language == Locale.CHINESE.language) ICON_ZH_EN else ICON_EN
    }
}
