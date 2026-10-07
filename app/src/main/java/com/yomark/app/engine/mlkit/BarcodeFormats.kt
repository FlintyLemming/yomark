package com.yomark.app.engine.mlkit

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * 条码格式白名单。
 *
 * 抽成对象是为了能单测——跟 `BarcodeCandidates` 同一个理由：ML Kit 的
 * `BarcodeScannerOptions` 造出来之后读不回自己的格式集，但「该放哪些格式进去」
 * 这件事本身跟 ML Kit 无关。
 *
 * **划线的口径是「有没有强制校验」，不是「常不常见」。**
 * 不设置格式等于全格式开启，而全格式里的 ITF / CODE_39 / CODABAR 三种没有校验位，
 * 一行密集文字的竖笔画就是明暗交替的条纹、按钮边框充当静区，扫描器会**真的解出**
 * 一串垃圾数字。解得出 → `decodable = true` → 默认打码，于是按钮和文字被整块涂黑。
 *
 * 这跟 bc04d7a 修的不是同一条分支：那次降级的是「解不出内容」的疑似条码，
 * 解得出的误报走的是另一条路，那次一点没碰。
 *
 * 排除而不是像疑似条码那样降级成只圈不打码：按钮和文字是**高频**误报，
 * 降级只会把满屏黑块换成满屏虚线框加导出拦截，体感一样糟。这三种格式的主场是
 * 仓储标签和实物包装箱，在手机截图里几乎不出现，检出价值低于误报代价。
 */
object BarcodeFormats {

    val ALLOWED: List<Int> = listOf(
        // 二维：带 Reed-Solomon 纠错，纹理撞上的概率可以忽略
        Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_DATA_MATRIX,
        Barcode.FORMAT_AZTEC,
        Barcode.FORMAT_PDF417,
        // 一维：只留带校验位的。EAN/UPC 末位校验，CODE_128 mod-103
        Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_EAN_8,
        Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_CODE_128,
    )
}
