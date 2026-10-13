package moe.flinty.yomark.engine.ppocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** 一个像素的颜色，三个通道都是 0..255。 */
internal class Color(val r: Float, val g: Float, val b: Float) {
    /** 亮度（BT.601），0..255。 */
    val luma: Float get() = 0.299f * r + 0.587f * g + 0.114f * b
}

/**
 * 识别器眼里的一张图：按浮点坐标取色，整数坐标落在像素中心上。
 * 原图（[Argb]）和转过的图（[Turned]）对检测、识别来说是一回事。
 */
internal interface Pixels {
    val width: Int
    val height: Int
    fun sample(x: Float, y: Float): Color
}

/** ARGB 像素上的双线性采样，越界按边缘像素延伸（等价于 OpenCV 的 BORDER_REPLICATE）。 */
internal class Argb(private val pixels: IntArray, override val width: Int, override val height: Int) : Pixels {

    override fun sample(x: Float, y: Float): Color {
        val fx = x.coerceIn(0f, width - 1f)
        val fy = y.coerceIn(0f, height - 1f)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        fun ch(shift: Int): Float {
            val top = ((p00 shr shift) and 0xFF) * (1 - ax) + ((p10 shr shift) and 0xFF) * ax
            val bottom = ((p01 shr shift) and 0xFF) * (1 - ax) + ((p11 shr shift) and 0xFF) * ax
            return top * (1 - ay) + bottom * ay
        }
        return Color(ch(16), ch(8), ch(0))
    }
}

/**
 * [source] 顺时针转 [degrees] 度之后的样子（图像坐标 y 朝下，正值顺时针）。
 *
 * 不拷像素：画布上的点按反向旋转落回原图，在原图上只做一次双线性采样——先转出一张图再切行，字要插值两次，更糊。
 * 画布是转后的外接矩形，转出原图的角按边缘像素延伸，同 [Argb]。
 *
 * 框的坐标按像素边算：原图占 [0, width] × [0, height]，[toSource]、[fromSource] 在这个口径下互逆。
 * 90° 的整数倍单独算，坐标没有一点浮点误差。
 */
internal class Turned(private val source: Pixels, degrees: Float) : Pixels {

    /** 90° 的整数倍时是顺时针转了几个直角（0..3），否则为 null。 */
    private val quarter: Int?
    private val c: Float
    private val s: Float
    override val width: Int
    override val height: Int
    private val sw = source.width.toFloat()
    private val sh = source.height.toFloat()

    init {
        val d = ((degrees % 360f) + 360f) % 360f
        quarter = (0..4).firstOrNull { abs(d - it * 90f) < 1e-3f }?.let { it % 4 }
        val r = Math.toRadians(degrees.toDouble())
        c = cos(r).toFloat()
        s = sin(r).toFloat()
        when (quarter) {
            0, 2 -> { width = source.width; height = source.height }
            1, 3 -> { width = source.height; height = source.width }
            else -> {
                val ac = abs(cos(r))
                val asn = abs(sin(r))
                width = ceil(source.width * ac + source.height * asn - 1e-3).toInt()
                height = ceil(source.width * asn + source.height * ac - 1e-3).toInt()
            }
        }
    }

    private val hw = width / 2f
    private val hh = height / 2f

    /** 画布上的点 → 原图上的点。 */
    fun toSource(p: Pt): Pt = when (quarter) {
        0 -> p
        1 -> Pt(p.y, sh - p.x)
        2 -> Pt(sw - p.x, sh - p.y)
        3 -> Pt(sw - p.y, p.x)
        else -> {
            val dx = p.x - hw
            val dy = p.y - hh
            Pt(c * dx + s * dy + sw / 2, -s * dx + c * dy + sh / 2)
        }
    }

    /** 原图上的点 → 画布上的点。 */
    fun fromSource(p: Pt): Pt = when (quarter) {
        0 -> p
        1 -> Pt(sh - p.y, p.x)
        2 -> Pt(sw - p.x, sh - p.y)
        3 -> Pt(p.y, sw - p.x)
        else -> {
            val dx = p.x - sw / 2
            val dy = p.y - sh / 2
            Pt(c * dx - s * dy + hw, s * dx + c * dy + hh)
        }
    }

    // 像素中心比像素边的口径挪了半格：x + 0.5 映射过去，再退回半格。整数倍的直角化简后落在整像素上，不插值
    override fun sample(x: Float, y: Float): Color = when (quarter) {
        0 -> source.sample(x, y)
        1 -> source.sample(y, sh - 1 - x)
        2 -> source.sample(sw - 1 - x, sh - 1 - y)
        3 -> source.sample(sw - 1 - y, x)
        else -> {
            val dx = x + 0.5f - hw
            val dy = y + 0.5f - hh
            source.sample(c * dx + s * dy + sw / 2 - 0.5f, -s * dx + c * dy + sh / 2 - 0.5f)
        }
    }
}
