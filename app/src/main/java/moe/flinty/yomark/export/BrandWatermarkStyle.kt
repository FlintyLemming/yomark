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

/**
 * 导出加不加品牌水印。没解锁（没买、开源版没点过「去除水印」）一律加；
 * 解锁了看设置里的「无水印」，没选过（[off] 为 null）按不加算，选了某个字样才加。
 */
fun brandWatermarkApplies(unlocked: Boolean, off: Boolean?): Boolean = !unlocked || off == false
