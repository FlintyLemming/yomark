package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Color
import moe.flinty.yomark.core.model.MaskOptions

/**
 * 样式面板、用途水印面板上能换颜色的几项。色板、自定义颜色、吸管取色都落在其中一项上。
 */
enum class ColorTarget {
    /** 色块的颜色；马赛克降级成色块时也用它。挑了一种颜色就不再是近似色。 */
    SOLID,

    /** 马克笔的颜色。只换颜色，浓淡（透明度）另外调，不跟着变。 */
    MARKER,

    /** 画笔的颜色，不透明。 */
    PEN,

    /** 文本的颜色，不透明；垫着底色时是底的颜色。 */
    TEXT,

    /**
     * 用途水印的颜色，不透明，浓淡另外调。它不是打码的样子，不在 MaskOptions 里：
     * 在 PurposeWatermarkStyle.color，吸管取到的颜色交给 EditorViewModel.setPurposeStyle。
     */
    PURPOSE;

    /** 这一项现在的颜色，不透明的 RGB。 */
    fun colorOf(options: MaskOptions): Int = when (this) {
        SOLID -> options.solidColor
        MARKER -> options.markerColor
        PEN -> options.penColor
        TEXT -> options.textColor
        PURPOSE -> error("用途水印的颜色不在 MaskOptions 里")
    } or OPAQUE

    /**
     * 把这一项换成 [rgb]。传进来的透明度一律不认：色块强制不透明，马克笔保留原来的浓淡。
     * 色块挑了颜色（色板、自定义、吸管）就是要这个颜色，近似色随之关掉。
     */
    fun withColor(options: MaskOptions, rgb: Int): MaskOptions = when (this) {
        SOLID -> options.copy(solidColor = rgb or OPAQUE, solidNear = false)
        MARKER -> options.copy(markerColor = MaskOptions.withAlpha(rgb, options.markerAlpha))
        PEN -> options.copy(penColor = rgb or OPAQUE)
        TEXT -> options.copy(textColor = rgb or OPAQUE)
        PURPOSE -> error("用途水印的颜色不在 MaskOptions 里")
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}

/**
 * 吸管：取 ([x], [y]) 周围 3×3 像素逐通道的中位数，不透明。
 * 只取一个像素的话，正好点在字的抗锯齿边上、JPEG 的噪点上，取回来的是一个谁也没想要的颜色。
 */
internal fun pickColor(bitmap: Bitmap, x: Int, y: Int): Int {
    val pixels = ArrayList<Int>(9)
    for (dx in -1..1) for (dy in -1..1) {
        val px = x + dx
        val py = y + dy
        if (px in 0 until bitmap.width && py in 0 until bitmap.height) pixels += bitmap.getPixel(px, py)
    }
    fun median(channel: (Int) -> Int) = pixels.map(channel).sorted()[pixels.size / 2]
    return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
}
