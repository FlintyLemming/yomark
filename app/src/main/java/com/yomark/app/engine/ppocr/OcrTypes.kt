package com.yomark.app.engine.ppocr

import kotlin.math.hypot

/**
 * PP-OCR 核心里的点。核心算法一律不碰 android.graphics——
 * 这样单测能在纯 JVM 上加载真模型、跑真图，而不是只测后处理的几个函数。
 */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun length(): Float = hypot(x, y)
}

/**
 * 四边形，按阅读方向排好：左上、右上、右下、左下。
 * u 轴沿 tl→tr（文字前进方向），v 轴沿 tl→bl。
 */
data class Box(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    fun corners(): List<Pt> = listOf(tl, tr, br, bl)
    val width: Float get() = maxOf((tr - tl).length(), (br - bl).length())
    val height: Float get() = maxOf((bl - tl).length(), (br - tr).length())
    val minX: Float get() = minOf(tl.x, tr.x, br.x, bl.x)
    val maxX: Float get() = maxOf(tl.x, tr.x, br.x, bl.x)
    val minY: Float get() = minOf(tl.y, tr.y, br.y, bl.y)
    val maxY: Float get() = maxOf(tl.y, tr.y, br.y, bl.y)

    /** 框内的一点：u、v 都按 0..1 取。框是矩形，仿射插值即精确。 */
    fun at(u: Float, v: Float): Pt = tl + (tr - tl) * u + (bl - tl) * v

    /** 沿 u 轴截出 [u0, u1] 那一段，高度不变。字符级的框就是这样切出来的。 */
    fun slice(u0: Float, u1: Float): Box = Box(at(u0, 0f), at(u1, 0f), at(u1, 1f), at(u0, 1f))
}

/**
 * 一行识别结果。
 *
 * @param charBoxes 与 text 逐字对齐：text[i] 的框是 charBoxes[i]。
 *   空格是按像素间隙补出来的词界，没有框，对应位置为 null。
 */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val box: Box,
    val charBoxes: List<Box?>,
) {
    init {
        require(charBoxes.size == text.length) { "charBoxes 必须与 text 逐字对齐" }
    }
}
