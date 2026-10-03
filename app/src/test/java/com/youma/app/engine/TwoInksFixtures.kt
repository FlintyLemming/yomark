package com.youma.app.engine

import android.graphics.BitmapFactory
import com.youma.app.core.geometry.Quad
import com.youma.app.engine.mlkit.TwoInks
import kotlin.random.Random

/**
 * TwoInks 单测用的图。像素就是 ARGB 的 IntArray，造图、模糊、缩小都不经过 Bitmap；
 * 只有读那张照片要 BitmapFactory，所以用到它的测试得跑在 Robolectric 的 NATIVE 图形模式下。
 *
 * 码是随机模块网格，不是能扫的码——TwoInks 只看颜色统计，跟码能不能解无关。
 */
internal class Img(val w: Int, val h: Int, val px: IntArray) {
    operator fun get(x: Int, y: Int) = px[y * w + x]
    operator fun set(x: Int, y: Int, c: Int) { px[y * w + x] = c }
}

internal fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

internal val BLACK = argb(0, 0, 0)
internal val WHITE = argb(255, 255, 255)

/** modules×modules 个模块、每个 module 像素见方，四周留两个模块的静区。暗模块约占一半，跟真码一样。 */
internal fun fakeCode(modules: Int = 29, module: Int = 4, ink: Int = BLACK, paper: Int = WHITE, seed: Int = 7): Img {
    val rnd = Random(seed)
    val dark = Array(modules) { BooleanArray(modules) { rnd.nextBoolean() } }
    val quiet = 2 * module
    val side = modules * module + 2 * quiet
    val img = Img(side, side, IntArray(side * side) { paper })
    for (my in 0 until modules) for (mx in 0 until modules) {
        if (!dark[my][mx]) continue
        for (y in 0 until module) for (x in 0 until module) img[quiet + mx * module + x, quiet + my * module + y] = ink
    }
    return img
}

/**
 * 在码正中间画一块方的 logo：纯色，或者把 photo 缩放进去当头像。四周留 4 像素的白边，跟真码一样。
 */
internal fun paintSquare(img: Img, side: Int, colour: Int = BLACK, photo: Img? = null) {
    val from = (img.w - side) / 2
    for (y in from - 4 until from + side + 4) for (x in from - 4 until from + side + 4) img[x, y] = WHITE
    for (y in 0 until side) for (x in 0 until side) {
        img[from + x, from + y] = photo?.get(x * photo.w / side, y * photo.h / side) ?: colour
    }
}

/** 按 TwoInks 的取法（四边形里、中间 logo 区以外）从这张图里取像素。用到 Quad，得跑在 Robolectric 下。 */
internal fun Img.sample(quad: Quad): IntArray = TwoInks.sampleInside(w, h, quad) { y, left, row ->
    System.arraycopy(px, y * w + left, row, 0, row.size)
}

/** 可分离的方框模糊，passes 次（两次就接近高斯）。边缘按夹边处理。 */
internal fun blur(src: Img, radius: Int, passes: Int = 2): Img {
    var cur = src
    repeat(passes) {
        cur = boxPass(boxPass(cur, radius, horizontal = true), radius, horizontal = false)
    }
    return cur
}

private fun boxPass(src: Img, radius: Int, horizontal: Boolean): Img {
    val out = IntArray(src.px.size)
    for (y in 0 until src.h) for (x in 0 until src.w) {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (d in -radius..radius) {
            val sx = if (horizontal) (x + d).coerceIn(0, src.w - 1) else x
            val sy = if (horizontal) y else (y + d).coerceIn(0, src.h - 1)
            val c = src[sx, sy]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        out[y * src.w + x] = argb(r / n, g / n, b / n)
    }
    return Img(src.w, src.h, out)
}

/** 整数倍面积平均缩小——BitmapFactory 对 JPEG 的 inSampleSize 就是这个效果。 */
internal fun downscale(src: Img, factor: Int): Img {
    val w = src.w / factor
    val h = src.h / factor
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        var r = 0; var g = 0; var b = 0
        for (dy in 0 until factor) for (dx in 0 until factor) {
            val c = src[x * factor + dx, y * factor + dy]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        val n = factor * factor
        out[y * w + x] = argb(r / n, g / n, b / n)
    }
    return Img(w, h, out)
}

/**
 * 一张真实的彩色照片：scikit-image 自带的 coffee.png（CC0，摄影 Rachel Michetti），
 * 缩到 128×85——截图里一张商品图在 540 宽的分析图上差不多就这么大。
 */
internal fun coffeePhoto(): Img {
    val bytes = Img::class.java.getResourceAsStream("/barcode/coffee-thumb.png")?.use { it.readBytes() }
        ?: error("缺少测试资源 /barcode/coffee-thumb.png")
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return Img(bmp.width, bmp.height, px)
}
