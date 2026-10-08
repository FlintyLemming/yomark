package moe.flinty.yomark.ui.canvas

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.roundToInt

/** 这一帧的扫描动效按哪种样式画（见 [ScanStyle]）。 */
internal sealed interface ScanLook {
    /** 扫光（ScanEffect）。 */
    data object Sweep : ScanLook

    /**
     * 磨砂（FrostEffect）。
     *
     * @param layer 模糊图与光点。识别刚开始、还在后台准备的那几帧为 null，只压暗
     * @param origin 结果到达时从哪里散开（图像坐标），即等待图标所在的位置；null 取图像中心
     */
    class Frost(val layer: FrostLayer?, val origin: PointF? = null) : ScanLook
}

/** 磨砂的画笔、蒙版与临时对象，跨帧复用。 */
internal class FrostPaints {
    /** 模糊图拉伸回原尺寸，靠双线性插值抹匀。 */
    val blurred = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 压在模糊图上的深蓝。 */
    val dim = Paint()

    /** 云：滤色叠上去。 */
    val clouds = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SCREEN }

    val sparkles = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = FrostEffect.SPARKLE_COLOR
    }

    /** 散开时在磨砂上挖洞。 */
    val hole = Paint().apply { blendMode = BlendMode.DST_OUT }

    /** 散开时识别结果只留洞里的部分。 */
    val keep = Paint().apply { blendMode = BlendMode.DST_IN }

    /** 等待图标。 */
    val loader = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOADER_COLOR }
    val loaderPath = Path()
    val loaderMatrix = Matrix()
    val loaderBounds = RectF()

    val rect = RectF()

    companion object {
        /** 等待图标的颜色：天蓝里提亮一档，压暗的磨砂上一眼看得见。 */
        const val LOADER_COLOR: Int = 0xFFBFE6F7.toInt()
    }
}

/** 散开的圆在径向上取样的段数。 */
private const val RADIAL_STOPS = 8

/**
 * 磨砂样式下的一帧：识别出的块（散开时才有，只在洞里）、磨砂（散开时挖着洞）、手动框（始终浮在最上面）。
 * 图像本身已经画好；图像坐标，dp 按 [dp]（一 dp 合多少图像像素）折算。
 *
 * 等待图标不在这里画：它跟屏幕走，不跟图走，由画布在撤掉 viewport 之后另画。
 */
internal fun drawFrostScene(
    canvas: Canvas,
    width: Float,
    height: Float,
    dp: Float,
    time: ScanTime,
    look: ScanLook.Frost,
    paints: FrostPaints,
    drawDetected: () -> Unit,
    drawManual: () -> Unit,
) {
    val origin = look.origin ?: PointF(width / 2f, height / 2f)
    val feather = FrostEffect.FEATHER_DP * dp
    val maxEdge = FrostEffect.EDGE_MAX_DP * dp
    val start = FrostEffect.HOLE_START_DP * dp
    val end = FrostEffect.endRadius(origin.x, origin.y, width, height, maxEdge + feather)
    val since = time.sinceResult
    val radius = since?.let { FrostEffect.holeRadius(it, start, end) }
    val edge = since?.let { FrostEffect.edgeWidth(it, start, end, FrostEffect.EDGE_DP * dp, maxEdge) } ?: 0f

    // 识别中，识别出的块一律不画：换方案重跑时旧结果先退场。散开时它们画进一个离屏图层，只留洞里那部分
    if (radius != null) {
        val layer = canvas.saveLayer(null, null)
        drawDetected()
        keepInsideHole(canvas, origin, radius - edge, feather, width, height, paints)
        canvas.restoreToCount(layer)
    }

    val farthest = FrostEffect.endRadius(origin.x, origin.y, width, height, 0f)
    when {
        // 识别中：整片磨砂，用不着离屏图层
        radius == null -> drawFrost(canvas, width, height, dp, time, look.layer, paints, origin, holeRadius = -1f)
        // 散开中：磨砂画进只有图像大小的离屏图层，再在上面挖洞
        radius - edge < farthest -> {
            val layer = canvas.saveLayer(0f, 0f, width, height, null)
            drawFrost(canvas, width, height, dp, time, look.layer, paints, origin, radius)
            punchHole(canvas, origin, radius, edge, width, height, paints)
            canvas.restoreToCount(layer)
        }
        // 洞已经盖过整张图：磨砂一点不剩，只差最远那几块盖实
    }

    // 手动框是用户自己画的，识别期间也一直在，清楚地浮在磨砂上面
    drawManual()
}

/**
 * 磨砂中间的等待图标，画在屏幕坐标里（画布已撤掉 viewport），大小恒定。
 * 识别开始时放大淡入；结果到了放大淡出，洞正从它底下散开。
 */
internal fun drawLoader(canvas: Canvas, cx: Float, cy: Float, density: Float, time: ScanTime, paints: FrostPaints) {
    MorphingLoader.draw(
        canvas, cx, cy,
        container = MorphingLoader.CONTAINER_DP * density,
        ms = time.sinceScan,
        scale = FrostEffect.loaderScale(time.sinceScan, time.sinceResult),
        alpha = FrostEffect.loaderAlpha(time.sinceScan, time.sinceResult),
        paint = paints.loader,
        path = paints.loaderPath,
        matrix = paints.loaderMatrix,
        bounds = paints.loaderBounds,
    )
}

/**
 * 磨砂本身：模糊图、深蓝、云、光点。只盖图像范围。
 *
 * @param holeRadius 散开时洞的半径，洞边那圈光点跟着它走；识别中为负
 */
private fun drawFrost(
    canvas: Canvas,
    width: Float,
    height: Float,
    dp: Float,
    time: ScanTime,
    layer: FrostLayer?,
    paints: FrostPaints,
    origin: PointF,
    holeRadius: Float,
) {
    val strength = FrostEffect.strength(time.sinceScan, time.sinceResult)
    if (strength <= 0f) return
    val bounds = paints.rect.apply { set(0f, 0f, width, height) }
    if (layer != null) {
        paints.blurred.alpha = (strength * 255).roundToInt()
        canvas.drawBitmap(layer.blurred, null, bounds, paints.blurred)
    }
    paints.dim.color = FrostEffect.DIM_COLOR
    paints.dim.alpha = (FrostEffect.DIM_ALPHA * strength * 255).roundToInt()
    canvas.drawRect(bounds, paints.dim)
    if (layer == null) return

    val seconds = FrostEffect.timeSec(time.sinceScan)
    val clouds = layer.updateNoise(seconds)
    paints.clouds.alpha = (FrostEffect.CLOUD_ALPHA * strength * 255).roundToInt()
    canvas.drawBitmap(clouds, null, bounds, paints.clouds)
    layer.drawSparkles(
        canvas, seconds, FrostEffect.sparkleStrength(strength),
        diameter = FrostEffect.SPARKLE_DP * dp, paint = paints.sparkles,
        ringX = origin.x, ringY = origin.y, ringRadius = holeRadius, ringWidth = FrostEffect.RING_DP * dp,
    )
}

/** 在磨砂上挖洞：洞里清掉，边缘 [edge] 宽的一圈渐变，洞外原样。 */
private fun punchHole(
    canvas: Canvas,
    origin: PointF,
    radius: Float,
    edge: Float,
    width: Float,
    height: Float,
    paints: FrostPaints,
) {
    if (radius <= 0f) return
    // DST_OUT：画上去的不透明度就是要清掉的量，等于 1 - frost
    paints.hole.shader = radialMask(origin, radius, inner = max(0f, radius - edge)) { d ->
        1f - FrostEffect.frost(d, radius, edge)
    }
    canvas.drawRect(0f, 0f, width, height, paints.hole)
}

/**
 * 识别结果只留洞里的部分：磨砂完全揭开的那一圈（半径 [cleared]）上还没有，往里 [feather] 处盖实
 * （FrostEffect.coverage）。蒙版比图像大出一圈，探出图像边的标签与描边也一样处理。
 */
private fun keepInsideHole(
    canvas: Canvas,
    origin: PointF,
    cleared: Float,
    feather: Float,
    width: Float,
    height: Float,
    paints: FrostPaints,
) {
    if (cleared <= 0f) {
        paints.keep.shader = null
        paints.keep.color = Color.TRANSPARENT
    } else {
        // 画笔跨帧复用：上一帧可能把它清成了透明，而着色器的颜色还要再乘上画笔自己的不透明度
        paints.keep.color = Color.BLACK
        paints.keep.shader = radialMask(origin, cleared, inner = max(0f, cleared - feather)) { d ->
            FrostEffect.coverage(d, cleared, 0f, feather)
        }
    }
    canvas.drawRect(-width, -height, width * 2f, height * 2f, paints.keep)
}

/**
 * 以 [origin] 为圆心、半径 [radius] 的径向蒙版：[inner] 以内取圆心处的值，[inner] 到 [radius] 之间
 * 按 [alphaAt]（离圆心的距离 → 不透明度）取样，[radius] 以外取边上的值。渐变色标只用 alpha。
 */
private inline fun radialMask(origin: PointF, radius: Float, inner: Float, alphaAt: (Float) -> Float): RadialGradient {
    val stops = FloatArray(RADIAL_STOPS + 2)
    val colors = IntArray(RADIAL_STOPS + 2)
    stops[0] = 0f
    colors[0] = Color.argb((alphaAt(0f).coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0)
    for (i in 0..RADIAL_STOPS) {
        val d = inner + (radius - inner) * i / RADIAL_STOPS
        // 色标位置必须严格递增；inner 为 0 时第一个取样点与圆心重合，往外挪一点点
        stops[i + 1] = max(d / radius, stops[i] + 1e-4f).coerceAtMost(1f)
        colors[i + 1] = Color.argb((alphaAt(d).coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0)
    }
    return RadialGradient(origin.x, origin.y, radius, colors, stops, Shader.TileMode.CLAMP)
}
