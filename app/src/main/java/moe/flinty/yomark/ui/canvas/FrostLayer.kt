package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 磨砂这一层里只跟图有关、不跟时间走的东西：模糊过的图、光点挑在哪些格子上、噪声网格多大。
 * 一张图在识别开始时准备一次（几毫秒，放在后台线程），之后每帧只算噪声网格和光点的闪烁。
 *
 * 全部是图像坐标。尺寸按「整图适配时一 dp 合多少图像像素」（[pxPerDp]）折算：
 * 用户放大来看时，模糊和光点的疏密跟着图一起放大，就像是印在图上的一层。
 */
internal class FrostLayer private constructor(
    val width: Int,
    val height: Int,
    val pxPerDp: Float,
    /** 整张图模糊后缩小的样子；画的时候拉伸回原尺寸，双线性插值把它抹匀。 */
    val blurred: Bitmap,
    /** 光点的位置，x、y 交错。 */
    private val sparkleXY: FloatArray,
    /** 每个光点三档闪烁的强度（见 FrostNoise.sparkleBands），三个一组。 */
    private val sparkleBands: FloatArray,
    private val gridCols: Int,
    private val gridRows: Int,
) {
    val sparkleCount: Int get() = sparkleXY.size / 2

    /** 噪声一格合多少图像像素。 */
    private val noiseUnit = FrostEffect.NOISE_DP * pxPerDp

    /** 这一帧的噪声，在网格的格心上取样。 */
    private val grid = FloatArray(gridCols * gridRows)
    private val cloudPixels = IntArray(gridCols * gridRows)

    /** 云的位图，两张轮流写：上一帧那张可能还没交给渲染线程。 */
    private val clouds = Array(2) { Bitmap.createBitmap(gridCols, gridRows, Bitmap.Config.ARGB_8888) }
    private var cloudFront = 0

    private val buckets = Array(SPARKLE_LEVELS) { FloatArray(sparkleXY.size) }
    private val bucketSizes = IntArray(SPARKLE_LEVELS)

    /**
     * 把 [timeSec] 时刻的噪声算到网格上，并据此刷新云，返回这一帧云的位图。
     * 光点的遮罩也取自这张网格，所以每帧先调它，再画光点。
     */
    fun updateNoise(timeSec: Float): Bitmap {
        val drift = timeSec * FrostEffect.NOISE_SPEED
        val cellW = width.toFloat() / gridCols
        val cellH = height.toFloat() / gridRows
        val sky = FrostEffect.CLOUD_HUES[0]
        val lavender = FrostEffect.CLOUD_HUES[1]
        for (r in 0 until gridRows) {
            val y = (r + 0.5f) * cellH / noiseUnit
            for (c in 0 until gridCols) {
                val x = (c + 0.5f) * cellW / noiseUnit
                val n = FrostNoise.simplex3d(x + drift, y, drift)
                val i = r * gridCols + c
                grid[i] = n
                cloudPixels[i] = cloudColor(n, sky, lavender)
            }
        }
        cloudFront = 1 - cloudFront
        val bitmap = clouds[cloudFront]
        bitmap.setPixels(cloudPixels, 0, gridCols, 0, 0, gridCols, gridRows)
        return bitmap
    }

    /** 噪声网格上 ([x], [y]) 处的值：格心之间双线性插值，出了边就取最近的格。 */
    fun noiseAt(x: Float, y: Float): Float {
        val gx = (x / width * gridCols - 0.5f).coerceIn(0f, gridCols - 1f)
        val gy = (y / height * gridRows - 0.5f).coerceIn(0f, gridRows - 1f)
        val c0 = gx.toInt()
        val r0 = gy.toInt()
        val c1 = minOf(c0 + 1, gridCols - 1)
        val r1 = minOf(r0 + 1, gridRows - 1)
        val fx = gx - c0
        val fy = gy - r0
        val top = grid[r0 * gridCols + c0] * (1f - fx) + grid[r0 * gridCols + c1] * fx
        val bottom = grid[r1 * gridCols + c0] * (1f - fx) + grid[r1 * gridCols + c1] * fx
        return top * (1f - fy) + bottom * fy
    }

    /**
     * 光点：每个点按自己三档的闪烁算出此刻的亮度，乘上噪声遮罩（成片地出现、随噪声漂移），
     * 再按亮度分几档，每档一次 drawPoints。须在同一帧的 [updateNoise] 之后调用。
     *
     * 散开时（[ringRadius] ≥ 0）洞的边缘上另有一圈光点不看噪声、一律亮起来：磨砂像是在边上化成了光。
     *
     * @param strength 光点整体的强度（淡入）
     * @param diameter 点的直径（图像坐标）
     */
    fun drawSparkles(
        canvas: Canvas,
        timeSec: Float,
        strength: Float,
        diameter: Float,
        paint: Paint,
        ringX: Float = 0f,
        ringY: Float = 0f,
        ringRadius: Float = -1f,
        ringWidth: Float = 0f,
    ) {
        if (strength <= 0f) return
        bucketSizes.fill(0)
        for (i in 0 until sparkleCount) {
            val x = sparkleXY[2 * i]
            val y = sparkleXY[2 * i + 1]
            val mask = FrostNoise.sparkleMask(noiseAt(x, y))
            // 平方一下，成片的光点边界更分明，片与片之间留出空来
            var weight = mask * mask * FrostEffect.SPARKLE_GAIN
            if (ringRadius >= 0f) {
                val dx = x - ringX
                val dy = y - ringY
                weight += FrostEffect.RING_GAIN * FrostEffect.ring(sqrt(dx * dx + dy * dy), ringRadius, ringWidth)
            }
            if (weight <= 0f) continue
            val brightness = FrostNoise.twinkle(sparkleBands, i * FrostNoise.BANDS, timeSec) * weight * strength
            val level = minOf((brightness * SPARKLE_LEVELS).roundToInt(), SPARKLE_LEVELS)
            if (level <= 0) continue
            val bucket = buckets[level - 1]
            val n = bucketSizes[level - 1]
            bucket[n] = x
            bucket[n + 1] = y
            bucketSizes[level - 1] = n + 2
        }
        paint.strokeWidth = diameter
        for (level in 1..SPARKLE_LEVELS) {
            val n = bucketSizes[level - 1]
            if (n == 0) continue
            paint.alpha = 255 * level / SPARKLE_LEVELS
            canvas.drawPoints(buckets[level - 1], 0, n, paint)
        }
    }

    companion object {
        /** 光点的亮度分几档画。 */
        private const val SPARKLE_LEVELS = 6

        /** 光点候选格子数的上限：平板上整图适配时 dp 面积大，格子按比例放大，每帧的计算量封顶。 */
        private const val MAX_SPARKLE_CELLS = 320_000

        /** 噪声网格每边最多这么多格。 */
        private const val MAX_GRID = 96

        /** 小图上的模糊做到这个 σ（小图像素），再大就继续缩小图，三遍半径 2 的盒式模糊正好约等于它。 */
        private const val SMALL_SIGMA = 2.45f
        private const val BOX_PASSES = 3

        /**
         * @param source 分析图（与画布上画的是同一张）
         * @param pxPerDp 整图适配时一 dp 合多少图像像素
         */
        fun prepare(source: Bitmap, pxPerDp: Float): FrostLayer {
            val w = source.width
            val h = source.height
            val blurred = blur(source, FrostEffect.BLUR_DP * pxPerDp)
            val (xy, bands) = pickSparkles(w, h, pxPerDp)
            val step = FrostEffect.NOISE_GRID_DP * pxPerDp
            return FrostLayer(
                width = w,
                height = h,
                pxPerDp = pxPerDp,
                blurred = blurred,
                sparkleXY = xy,
                sparkleBands = bands,
                gridCols = ceil(w / step).toInt().coerceIn(2, MAX_GRID),
                gridRows = ceil(h / step).toInt().coerceIn(2, MAX_GRID),
            )
        }

        /**
         * 挑出会亮的格子。每一格的三角噪声值定下它三档闪烁的强度（与原版一样只有一条窄带里的格子会亮），
         * 最强的一档够亮才留下；点落在格子里由同一个噪声值决定的位置上，免得排成一张看得出来的方格。
         */
        private fun pickSparkles(w: Int, h: Int, pxPerDp: Float): Pair<FloatArray, FloatArray> {
            var cell = FrostEffect.SPARKLE_CELL_DP * pxPerDp
            val cells = (w / cell) * (h / cell)
            if (cells > MAX_SPARKLE_CELLS) cell *= sqrt(cells / MAX_SPARKLE_CELLS)
            val cols = max(1, ceil(w / cell).toInt())
            val rows = max(1, ceil(h / cell).toInt())
            val xy = FloatArrayBuilder()
            val bands = FloatArrayBuilder()
            val o = FloatArray(FrostNoise.BANDS)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val n = FrostNoise.triangleNoise(c * 1.37f + 0.31f, r * 1.73f + 0.57f)
                    FrostNoise.sparkleBands(n, o)
                    if (o.max() < FrostEffect.SPARKLE_THRESHOLD) continue
                    val x = (c + jitter(n * 173.37f)) * cell
                    val y = (r + jitter(n * 291.13f)) * cell
                    if (x >= w || y >= h) continue
                    xy.add(x); xy.add(y)
                    o.forEach(bands::add)
                }
            }
            return xy.toArray() to bands.toArray()
        }

        /** 格子里的位置，避开格子边缘：0.15..0.85。 */
        private fun jitter(seed: Float) = 0.15f + 0.7f * (seed - floor(seed))

        /**
         * 重度模糊：先逐级对半缩小（每级都过滤，不会混叠），缩到模糊半径只剩两三个像素，
         * 再在小图上做三遍盒式模糊（约等于高斯）。画的时候拉伸回原尺寸，双线性插值把它抹匀。
         *
         * @param sigma 想要的高斯 σ（图像像素）
         */
        internal fun blur(source: Bitmap, sigma: Float): Bitmap {
            val factor = max(1f, sigma / SMALL_SIGMA)
            val tw = max(1, (source.width / factor).roundToInt())
            val th = max(1, (source.height / factor).roundToInt())
            var current = source
            while (current.width / 2 >= tw && current.height / 2 >= th && current.width >= 2 && current.height >= 2) {
                val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
                if (current !== source) current.recycle()
                current = next
            }
            val pixels = IntArray(tw * th)
            if (current.width == tw && current.height == th) {
                current.getPixels(pixels, 0, tw, 0, 0, tw, th)
            } else {
                val small = Bitmap.createScaledBitmap(current, tw, th, true)
                small.getPixels(pixels, 0, tw, 0, 0, tw, th)
                if (small !== current && small !== source) small.recycle()
            }
            if (current !== source) current.recycle()

            val smallSigma = sigma / factor
            // 三遍宽 2r+1 的盒子叠起来，方差是 ((2r+1)² - 1) / 4
            val radius = ((sqrt(4f * smallSigma * smallSigma + 1f) - 1f) / 2f).roundToInt()
            if (radius > 0) {
                val scratch = IntArray(pixels.size)
                repeat(BOX_PASSES) {
                    boxBlur(pixels, scratch, tw, th, radius, horizontal = true)
                    boxBlur(scratch, pixels, tw, th, radius, horizontal = false)
                }
            }
            return Bitmap.createBitmap(pixels, tw, th, Bitmap.Config.ARGB_8888)
        }

        /** 一维盒式模糊，边缘取最近的像素。四个通道分开累加。 */
        private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val length = if (horizontal) w else h
            val span = 2 * r + 1
            for (line in 0 until lines) {
                fun at(k: Int): Int {
                    val i = k.coerceIn(0, length - 1)
                    return if (horizontal) src[line * w + i] else src[i * w + line]
                }
                var a = 0; var red = 0; var green = 0; var blue = 0
                for (k in -r..r) {
                    val p = at(k)
                    a += p ushr 24; red += (p shr 16) and 0xFF; green += (p shr 8) and 0xFF; blue += p and 0xFF
                }
                for (k in 0 until length) {
                    val out = ((a + r) / span shl 24) or ((red + r) / span shl 16) or
                        ((green + r) / span shl 8) or ((blue + r) / span)
                    if (horizontal) dst[line * w + k] = out else dst[k * w + line] = out
                    val leaving = at(k - r)
                    val entering = at(k + r + 1)
                    a += (entering ushr 24) - (leaving ushr 24)
                    red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                    green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                    blue += (entering and 0xFF) - (leaving and 0xFF)
                }
            }
        }

        /**
         * 一格云的颜色：噪声高处偏淡紫、低处偏天蓝；只在噪声高的地方浓，噪声低的地方淡到没有——
         * 那里正是光点成片的地方，云和光点各占一片，不糊在一起。
         */
        private fun cloudColor(n: Float, sky: Int, lavender: Int): Int {
            val k = smooth((n + 0.6f) / 1.2f)
            val amount = smooth((n + 0.2f) / 1f)
            return Color.argb(
                (amount * 255).roundToInt(),
                lerp(Color.red(sky), Color.red(lavender), k),
                lerp(Color.green(sky), Color.green(lavender), k),
                lerp(Color.blue(sky), Color.blue(lavender), k),
            )
        }

        private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).roundToInt()

        private fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/** 只为准备光点时攒一串 Float，免得装箱成 List<Float>。 */
private class FloatArrayBuilder {
    private var data = FloatArray(1024)
    private var size = 0

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
