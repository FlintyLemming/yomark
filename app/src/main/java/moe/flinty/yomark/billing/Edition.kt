package moe.flinty.yomark.billing

import moe.flinty.yomark.BuildConfig

/**
 * 发行版本：开源版全功能免费，以后上架 Play 的版本再走内购（spec §10 修订）。
 *
 * 开源版不连 Play Billing。导出默认带品牌水印，去水印按钮点开换成 FreeEditionDialog：
 * 点「去除水印」就算已购（[BillingRepository.isPro]），不用付钱；另给一个打赏入口。
 * 切回内购只改 app/build.gradle.kts 里的 FREE_EDITION，其他代码不动。
 */
object Edition {
    val isFree: Boolean = BuildConfig.FREE_EDITION

    /** 打赏页。交给系统浏览器打开，应用本身仍然没有网络权限。 */
    const val DONATE_URL = "https://pay.mitsea.com"
}
