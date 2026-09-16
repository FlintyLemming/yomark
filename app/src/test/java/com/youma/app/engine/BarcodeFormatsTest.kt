package com.youma.app.engine

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.vision.barcode.common.Barcode
import com.youma.app.engine.mlkit.BarcodeFormats
import org.junit.Test

/**
 * 真机上按钮与文字被判成条码并**默认打码**（0.2.0 实测）。
 *
 * 根因不是 `enableAllPotentialBarcodes`——那条分支 bc04d7a 已经降级成只圈不打码了。
 * 根因是从来没调用过 `setBarcodeFormats()`：全格式开启时 ITF / CODE_39 / CODABAR
 * 这三种**没有强制校验位**，一行密集文字的竖笔画就是明暗交替的条纹，按钮边框充当静区，
 * 于是真的解出一串垃圾数字 → `rawValue != null` → `decodable = true` → 默认涂黑。
 *
 * 所以白名单的口径是「有校验位或有纠错码」，不是「常见」。
 */
class BarcodeFormatsTest {

    /** 这三种是 ML Kit 支持的格式里唯三没有强制校验位的，也正是误报的来源。 */
    @Test fun `formats without a mandatory check digit are excluded`() {
        assertThat(BarcodeFormats.ALLOWED).containsNoneOf(
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODABAR,
        )
    }

    /** 二维码是这个应用最要紧的目标（收款码），纠错码让它几乎不可能被纹理撞上。 */
    @Test fun `two dimensional formats with error correction are allowed`() {
        assertThat(BarcodeFormats.ALLOWED).containsAtLeast(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_PDF417,
        )
    }

    /** 一维码只留带校验位的：EAN/UPC 末位校验，CODE_128 mod-103。 */
    @Test fun `one dimensional formats with a check digit are allowed`() {
        assertThat(BarcodeFormats.ALLOWED).containsAtLeast(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
        )
    }

    /**
     * `FORMAT_ALL_FORMATS` 会让白名单彻底失效——它不是「再加一种格式」，
     * 而是「回到出问题前的全格式」。单列一条盯死它。
     */
    @Test fun `the catch all format is never in the allowlist`() {
        assertThat(BarcodeFormats.ALLOWED).doesNotContain(Barcode.FORMAT_ALL_FORMATS)
    }

    /** setBarcodeFormats(first, vararg rest) 至少要有一个，空列表会在运行期炸。 */
    @Test fun `allowlist is not empty`() {
        assertThat(BarcodeFormats.ALLOWED).isNotEmpty()
    }
}
