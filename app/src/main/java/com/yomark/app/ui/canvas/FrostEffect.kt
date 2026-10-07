package com.yomark.app.ui.canvas

import com.yomark.app.core.model.MaskOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 磨砂动效（[ScanStyle.FROST]）：识别期间整页蒙上一层闪着光点的磨砂，结果到了从中心散开。
 *
 * 照着 Android 原生壁纸选择器生成壁纸时的加载动效（AOSP WallpaperPicker2 的 LoadingAnimation）做的：
 * 原版是重度模糊 + 一层深色滤色 + 随噪声成片漂移、一闪一闪的细碎光点，加载完由一个圆从屏幕中心
 * 向外把它整个揭开。原样搬过来在截图上不成立——截图大多是白底，深色滤色在白底上什么也不剩，
 * 光点加在白上也看不见，整页只是一张发白的毛玻璃。所以这里改了几处：
 *
 * - 模糊轻得多（σ 10 dp，原版约 46 dp）：卡片、文字行还认得出形状，看得出是「这一页正在被处理」；
 * - 压一层深蓝而不是滤色：白底变成灰蓝，光点和中间的等待图标才显得出来；
 * - 加一层很淡的天蓝 / 淡紫云，与扫光、打码块同一组颜色，随噪声慢慢起伏；云和光点各占一片。
 *
 * 中间那个边转边变形的等待图标见 [MorphingLoader]。结果到了，图标放大淡出，一个洞从它底下散开：
 * 越往外越快，与原生一样冲出屏幕边缘时正快，不在角落里磨蹭；边缘随速度变柔（像运动模糊），走得越快
 * 越像雾从中间晕开，边上一圈光点亮起来。洞里是清晰的原图，打码块在磨砂完全揭开之后才由半透明慢慢
 * 盖实——用户先看得见底下原来是什么，再看着它被盖住（与扫光的落码同一个用意）。
 *
 * 与扫光一样只是编辑器里的过渡：不改 plan、不进撤销栈、不进导出。时长、轨迹全在这里，画布只管落笔；
 * 长度一律是图像坐标，由调用方按缩放把 dp 反算好了再传进来。
 */
internal object FrostEffect {

    /** 识别开始时磨砂淡入的时长：模糊、压暗、光点一起上来。 */
    const val FADE_IN_MS = 600L

    /** 等待图标入场的时长。 */
    const val LOADER_IN_MS = 420L

    /** 结果到了，等待图标放大淡出的时长。洞在它淡得差不多时从它底下冒出来。 */
    const val BURST_MS = 240L

    /**
     * 结果到了，洞从中心散开、直到最远的角上也盖实的时长。手机上约 0.44 秒磨砂就整个揭开了，
     * 剩下一点时间给最远那几块盖实。原生的圆形揭开在屏幕上看得见的部分也就半秒上下。
     */
    const val REVEAL_MS = 500L

    /** 结果到达以来，多久之后画面整个回到静止画法。洞的轨迹不随识别时长变，这就是个常数。 */
    const val SETTLE_MS = REVEAL_MS

    /** 光点与噪声的时间轴从这里起算：一开场就是闪烁到一半的样子，而不是全体从零齐步走。 */
    const val TIME_SEED_MS = 4_200L

    /** 模糊（高斯 σ，dp）。按整图适配时的缩放折算成图像像素，见 FrostLayer。 */
    const val BLUR_DP = 10f

    /** 压在模糊图上的深蓝，与它的不透明度。 */
    const val DIM_COLOR: Int = 0xFF0A1A40.toInt()
    const val DIM_ALPHA = 0.5f

    /** 云：随噪声在天蓝与淡紫之间起伏，滤色叠上去的强度。 */
    const val CLOUD_ALPHA = 0.32f
    val CLOUD_HUES: IntArray = intArrayOf(MaskOptions.SKY_BLUE, 0xFFC4B5FD.toInt())

    /**
     * 噪声一格的大小（dp）与漂移速度（格/秒）。原版一屏高 1.7 格、每秒横移并演化 0.2 屏，
     * 编辑器里的图约 650 dp 高，折合约 380 dp 一格、每秒 0.34 格。
     */
    const val NOISE_DP = 380f
    const val NOISE_SPEED = 0.34f

    /** 噪声在这么密的网格上算（dp），格与格之间双线性插值。噪声本身几百 dp 才起伏一次，这已绰绰有余。 */
    const val NOISE_GRID_DP = 16f

    /**
     * 光点：挑格子的格子大小（dp）、点的直径（dp）、亮度增益与颜色。原版逐个 0.8 dp 的像素格算，
     * 这里放大到 1.4 dp 一格、只留下会亮起来的格子（三档闪烁里最强的一档 ≥ [SPARKLE_THRESHOLD]），
     * 一屏约一万个点，每帧在 CPU 上算得过来。亮度按噪声遮罩的平方走，片与片之间留得出空来。
     */
    const val SPARKLE_CELL_DP = 1.4f
    const val SPARKLE_DP = 0.9f
    const val SPARKLE_GAIN = 0.8f
    const val SPARKLE_THRESHOLD = 0.3f
    const val SPARKLE_COLOR: Int = 0xFFE8F4FF.toInt()

    /** 洞的起始半径（dp）：比等待图标小，刚开始的那几帧藏在图标底下，看不出是凭空冒出来的。 */
    const val HOLE_START_DP = 12f

    /**
     * 洞的边缘柔化的宽度（dp）：跟着洞的速度走，等于边缘在 [MOTION_BLUR_MS] 里走过的距离，
     * 夹在 [EDGE_DP] 与 [EDGE_MAX_DP] 之间。原版是一道硬边，像开了一扇圆窗；跑快了的硬边每帧跳一大截，
     * 看着一顿一顿的。按速度柔化，起步时边缘清楚，冲出去时晕开，像运动模糊。
     */
    const val EDGE_DP = 16f
    const val EDGE_MAX_DP = 96f
    const val MOTION_BLUR_MS = 40f

    /** 打码块从磨砂完全揭开的地方开始显现、到完全盖实，洞要再走过的距离（dp）。 */
    const val FEATHER_DP = 110f

    /** 散开时洞的边缘上那圈光点：宽度（dp，边缘内外各这么宽）与亮度。 */
    const val RING_DP = 36f
    const val RING_GAIN = 1.2f

    /**
     * 磨砂的浓度（0..1）。识别开始时淡入（先快后慢）；结果到了就停在那一刻，不再往上长——
     * 识别快过淡入的话，洞外剩下的那一圈不该一边被揭开一边还在变浓。
     */
    fun strength(sinceScanMs: Long, sinceResultMs: Long?): Float =
        decelerate(scannedFor(sinceScanMs, sinceResultMs).toFloat() / FADE_IN_MS)

    /** 光点跟着磨砂淡入，只是更早到满：原版的 smoothstep(0, 0.75, alpha)。 */
    fun sparkleStrength(strength: Float): Float = smooth(strength / 0.75f)

    /** 噪声与光点的时间（秒）。只跟识别开始以来的时间走，散开时洞外的光点接着闪、云接着飘。 */
    fun timeSec(sinceScanMs: Long): Float = (sinceScanMs.coerceAtLeast(0L) + TIME_SEED_MS) / 1000f

    /**
     * 洞此刻的半径：从 [start] 长到 [end]，越来越快——一成匀速、九成二次缓入。
     * 起步慢，刚好藏在正在散开的图标底下；越往外越快，冲出最远的角时正快。
     */
    fun holeRadius(sinceResultMs: Long, start: Float, end: Float): Float {
        val p = progress(sinceResultMs)
        return start + (end - start) * (LINEAR * p + (1f - LINEAR) * p * p)
    }

    /** 洞的边缘此刻在走多快（每毫秒多少长度，与 [start]、[end] 同一口径）：从一开始就一直在加速。 */
    fun holeSpeed(sinceResultMs: Long, start: Float, end: Float): Float =
        (end - start) * (LINEAR + 2f * (1f - LINEAR) * progress(sinceResultMs)) / REVEAL_MS

    /** 洞的边缘此刻有多柔：边缘在 [MOTION_BLUR_MS] 里走过的距离，夹在 [min] 与 [max] 之间。 */
    fun edgeWidth(sinceResultMs: Long, start: Float, end: Float, min: Float, max: Float): Float =
        (holeSpeed(sinceResultMs, start, end) * MOTION_BLUR_MS).coerceIn(min, max)

    /**
     * 洞要长到多大：圆心到最远那个角的距离，再加上 [margin]。散开时传入最柔的边缘加渐显区，
     * 长到这么大时磨砂一点不剩、最远那块也已盖实。
     */
    fun endRadius(originX: Float, originY: Float, width: Float, height: Float, margin: Float): Float {
        val dx = max(originX, width - originX)
        val dy = max(originY, height - originY)
        return hypot(dx, dy) + margin
    }

    /** 磨砂在离圆心 [d] 处还剩多少：洞外是 1，洞里是 0，边缘 [edge] 宽的一圈里渐变。 */
    fun frost(d: Float, radius: Float, edge: Float): Float = 1f - smooth((radius - d) / edge)

    /**
     * 识别结果在离圆心 [d] 处显现了多少：磨砂完全揭开的地方（洞的边缘往里 [edge]）才开始显现，
     * 再往里 [feather] 处盖实。底下原来是什么，总是先清清楚楚地露一下。
     */
    fun coverage(d: Float, radius: Float, edge: Float, feather: Float): Float =
        smooth((radius - edge - d) / feather)

    /** 洞边那圈光点在离圆心 [d] 处有多亮：正在边缘上最亮，往里往外 [width] 处落到 0。 */
    fun ring(d: Float, radius: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val x = minOf(1f, abs(d - radius) / width)
        val s = 1f - x * x
        return s * s
    }

    /** 等待图标的缩放：入场时从 0.6 放大到 1；结果到了再放大到 1.5，像是它散开成了那个洞。 */
    fun loaderScale(sinceScanMs: Long, sinceResultMs: Long?): Float {
        val enter = 0.6f + 0.4f * decelerate(entered(sinceScanMs, sinceResultMs))
        return enter * (1f + 0.5f * burst(sinceResultMs))
    }

    /** 等待图标的不透明度：入场时淡入，结果到了淡出。 */
    fun loaderAlpha(sinceScanMs: Long, sinceResultMs: Long?): Float =
        smooth(entered(sinceScanMs, sinceResultMs)) * (1f - burst(sinceResultMs))

    /** 洞的半径里匀速那部分的占比，其余是二次缓入。 */
    private const val LINEAR = 0.1f

    private fun progress(sinceResultMs: Long): Float = (sinceResultMs.toFloat() / REVEAL_MS).coerceIn(0f, 1f)

    /** 识别了多久：结果到了就停在那一刻。 */
    private fun scannedFor(sinceScanMs: Long, sinceResultMs: Long?): Long =
        (if (sinceResultMs == null) sinceScanMs else sinceScanMs - sinceResultMs).coerceAtLeast(0L)

    private fun entered(sinceScanMs: Long, sinceResultMs: Long?): Float =
        scannedFor(sinceScanMs, sinceResultMs).toFloat() / LOADER_IN_MS

    private fun burst(sinceResultMs: Long?): Float =
        if (sinceResultMs == null) 0f else decelerate(sinceResultMs.toFloat() / BURST_MS)

    /** 先快后慢（三次缓出）。 */
    private fun decelerate(x: Float): Float {
        val t = 1f - x.coerceIn(0f, 1f)
        return 1f - t * t * t
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
