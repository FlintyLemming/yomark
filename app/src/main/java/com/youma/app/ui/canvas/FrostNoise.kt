package com.youma.app.ui.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 磨砂动效里的噪声与光点，逐行照搬 AOSP 的着色器库（SystemUI 的 ShaderUtilLibrary，
 * 壁纸选择器加载动效用的就是它），改写成 CPU 上的 Kotlin。
 *
 * 原版在 GPU 上逐像素算；这里不逐像素：噪声只在一张粗网格上算（它本来就是几百 dp 一格的大起伏），
 * 光点则在准备阶段挑出会亮的那些格子，每帧只算它们的闪烁（见 FrostLayer）。
 */
internal object FrostNoise {

    /**
     * 三角分布的伪随机数，范围 [-1, 1)。原版的 triangleNoise。
     * 光点挑格子用它：每一格的值定下来，这一格亮不亮、怎么闪也就定下来了。
     */
    fun triangleNoise(x: Float, y: Float): Float {
        var nx = fract(x * 5.3987f)
        var ny = fract(y * 5.4421f)
        val d = ny * (nx + 21.5351f) + nx * (ny + 14.3137f)
        nx += d
        ny += d
        val xy = nx * ny
        return fract(xy * 95.4307f) + fract(xy * 75.04961f) - 1f
    }

    /** 每个光点叠三档闪烁（原版循环四次，第一档恒为 0，省掉）。 */
    const val BANDS = 3

    /**
     * 噪声值为 [n] 的那一格，三档闪烁各自的强度，写进 [out] 的 [offset] 起三格。原版 sparkles() 的前半段：
     * 第 i 档只在 n 略低于 0.1 + 0.02·i 的一条窄带里才不为 0，于是只有少数格子会亮。
     */
    fun sparkleBands(n: Float, out: FloatArray, offset: Int = 0) {
        for (i in 1..BANDS) {
            val l = i * 0.01f
            val h = l + 0.1f
            out[offset + i - 1] = smoothstep(n - l, h, n)
        }
    }

    /**
     * 一个光点此刻的亮度：原版 sparkles() 的后半段。每一档按自己的强度决定闪的快慢，
     * 强的闪得快，三档叠起来就是忽明忽暗、没有规律的一闪一闪。
     */
    fun twinkle(bands: FloatArray, offset: Int, timeSec: Float): Float {
        var s = 0f
        for (i in 1..BANDS) {
            val o = bands[offset + i - 1]
            if (o <= 0f) continue
            s += o * abs(sin(PI.toFloat() * o * (timeSec + 0.55f * i)))
        }
        return s
    }

    /**
     * 光点按噪声成片出现：原版 SparkleShader 的亮度遮罩，噪声取反之后提亮、压暗，
     * 噪声低的地方光点最密最亮，高的地方没有。范围 [0, 2.2]。
     */
    fun sparkleMask(noise: Float): Float = max(1.75f * (1f - noise) - 1.3f, 0f)

    /**
     * 三维 simplex 噪声，范围大致 [-1, 1]。原版的 simplex3d，连同它那个整数哈希一起照搬，
     * 这样起伏的样子与原生一致。
     */
    fun simplex3d(x: Float, y: Float, z: Float): Float {
        val f = (x + y + z) * SKEW
        val sx = floor(x + f)
        val sy = floor(y + f)
        val sz = floor(z + f)
        val g = (sx + sy + sz) * UNSKEW
        val x0 = x - (sx - g)
        val y0 = y - (sy - g)
        val z0 = z - (sz - g)

        // 落在六个四面体里的哪一个：比较 x0、y0、z0 的大小
        val ex = step(x0 - y0)
        val ey = step(y0 - z0)
        val ez = step(z0 - x0)
        val o1x = ex * (1f - ez)
        val o1y = ey * (1f - ex)
        val o1z = ez * (1f - ey)
        val o2x = 1f - ez * (1f - ex)
        val o2y = 1f - ex * (1f - ey)
        val o2z = 1f - ey * (1f - ez)

        return 32f * (
            corner(sx, sy, sz, x0, y0, z0) +
                corner(sx + o1x, sy + o1y, sz + o1z, x0 - o1x + UNSKEW, y0 - o1y + UNSKEW, z0 - o1z + UNSKEW) +
                corner(sx + o2x, sy + o2y, sz + o2z, x0 - o2x + 2f * UNSKEW, y0 - o2y + 2f * UNSKEW, z0 - o2z + 2f * UNSKEW) +
                corner(sx + 1f, sy + 1f, sz + 1f, x0 - 1f + 3f * UNSKEW, y0 - 1f + 3f * UNSKEW, z0 - 1f + 3f * UNSKEW)
            )
    }

    /** 一个顶点的贡献：(0.6 - d²)⁴ 衰减乘上哈希出的梯度与偏移的点积。 */
    private fun corner(sx: Float, sy: Float, sz: Float, cx: Float, cy: Float, cz: Float): Float {
        val w = max(0.6f - (cx * cx + cy * cy + cz * cz), 0f)
        if (w == 0f) return 0f
        // 原版的整数哈希：32 位整数乘加允许溢出回绕，GPU 上与这里的 Int 一致；% 与 GLSL 的 imod 一样向零取整
        var vx = sx.toInt() * 1671731 + 10139267
        var vy = sy.toInt() * 1671731 + 10139267
        var vz = sz.toInt() * 1671731 + 10139267
        vx += vy * vz
        vy += vz * vx
        vz += vx * vy
        vx = (10 - (vx + vx / 65536) % 10) % 10
        vy = (10 - (vy + vy / 65536) % 10) % 10
        vz = (10 - (vz + vz / 65536) % 10) % 10
        vx += vy * vz
        vy += vz * vx
        vz += vx * vy
        val w2 = w * w
        return (sin(vx.toFloat()) * cx + cos(vy.toFloat()) * cy + sin(vz.toFloat()) * cz) * w2 * w2
    }

    private const val SKEW = 1f / 3f
    private const val UNSKEW = 1f / 6f

    private fun step(edge: Float) = if (edge >= 0f) 1f else 0f

    private fun fract(x: Float) = x - floor(x)

    /** GLSL 的 smoothstep，连 edge0 > edge1 时的行为也一样（原版正是靠这一点挑出窄带）。 */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val span = edge1 - edge0
        if (span == 0f) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
