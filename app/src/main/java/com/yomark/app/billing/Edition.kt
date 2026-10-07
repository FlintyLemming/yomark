package com.yomark.app.billing

import com.yomark.app.BuildConfig

/**
 * 发行版本：开源版全功能免费，以后上架 Play 的版本再走内购（spec §10 修订）。
 *
 * 开源版里购买态恒为已购（[BillingRepository.isPro]），导出不带品牌水印，也不连 Play Billing。
 * 去水印按钮照样留着，点开换成 FreeEditionDialog：说明这是全功能免费版，给一个打赏入口。
 * 切回内购只改 app/build.gradle.kts 里的 FREE_EDITION，其他代码不动。
 */
object Edition {
    val isFree: Boolean = BuildConfig.FREE_EDITION

    /** 打赏页。交给系统浏览器打开，应用本身仍然没有网络权限。 */
    const val DONATE_URL = "https://pay.mitsea.com"
}
