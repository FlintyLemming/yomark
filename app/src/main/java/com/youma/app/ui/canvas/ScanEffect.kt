package com.youma.app.ui.canvas

import com.youma.app.core.model.MaskOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 识别期间的扫光，与结果上屏时的「落码」。
 *
 * 上一版是浅暗幕加一道硬亮线反复扫，像扫码枪、像手机管家的病毒扫描，显得土。这一版：
 *
 * - 不画线。一束天蓝渐到淡紫的柔光自上而下扫过：光下的字被染上颜色（滤色混合），空白处只留
 *   一层很淡的光晕。看上去是光在逐行读这一页，而不是一把激光枪；
 * - 识别期间整张图压一层暗幕。白底截图占大多数，滤色在纯白上什么也染不出，光在亮底上几乎看不见；
 *   压暗之后光才显得出来；
 * - 结果到了，还在半路的那道光不掐断，提速到落码那一遍的速度把这一趟走完；紧接着光再扫最后一遍，
 *   打码块紧跟在光的后面自上而下渐显：每一块先被光照亮，再从半透明慢慢盖实。暗幕跟着同一道光
 *   自上而下揭开，光走过的地方回到原本的亮度。盖实之前那一下，底下原来是什么用户看得见——块里
 *   因此不再写类型名。
 *
 * 只是编辑器里的过渡：不改 plan、不进撤销栈、不进导出。时长、轨迹与光的形状全在这里，画布只管落笔。
 * 长度一律是图像坐标，由画布按缩放把 dp 反算好了再传进来。
 */
internal object ScanEffect {

    /** 识别中，光从顶扫到底一趟的时长。 */
    const val PASS_MS = 2000L

    /** 识别开始时光的淡入时长。 */
    const val FADE_IN_MS = 320L

    /** 结果到达后，还在半路的那道循环光由原速提到落码速度所用的时长。 */
    const val SPEED_UP_MS = 400L

    /** 落码那一遍的时长。 */
    const val REVEAL_MS = 1400L

    /** 光里的颜色左右流动一个周期的时长。 */
    const val DRIFT_MS = 3200L

    /**
     * 光的形状（dp，屏幕上视觉恒定）：中心往上拖一条长尾，往下只有一小段前沿。
     * 读得出方向，又用不着一条硬边。
     */
    const val TRAIL_DP = 150f
    const val LEAD_DP = 36f

    /** 打码块从开始显现到完全盖实，光要走过的距离（dp）。 */
    const val FEATHER_DP = 110f

    /** 光里颜色流动的空间周期（dp）。 */
    const val HUE_SPAN_DP = 280f

    /** 光染亮底下内容的强度（滤色），与空白处那层光晕的不透明度（正常叠加）。 */
    const val SCREEN_ALPHA = 0.9f
    const val TINT_ALPHA = 0.22f

    /** 识别期间压在图上的暗幕的最大不透明度。 */
    const val SCRIM_ALPHA = 0.6f

    /** 光里流动的颜色：天蓝（与打码块同色）、长春花蓝、淡紫。 */
    val HUES: IntArray = intArrayOf(MaskOptions.SKY_BLUE, 0xFFA5B4FC.toInt(), 0xFFC4B5FD.toInt())

    /**
     * 循环光这一趟里的位置为 [phaseMs]（0..[PASS_MS]）时，中心的纵坐标。
     *
     * 每一趟都从图像上方整道光完全藏着的位置出发、到下方整道光完全离开为止，
     * 从底部跳回顶部的那一下因此看不见，用不着再靠淡入淡出遮掩。
     */
    fun loopCenter(phaseMs: Float, height: Float, lead: Float, trail: Float): Float {
        val p = (phaseMs / PASS_MS).coerceIn(0f, 1f)
        return -lead + travel(p) * (height + lead + trail)
    }

    /**
     * 循环光此刻走到这一趟里的哪儿（0..[PASS_MS]）。识别中按原速一趟接一趟地走；
     * 结果到达后不掐断，提速把这一趟走完，离开图像之后为 null，把场子让给落码那一遍。
     */
    fun loopPhase(sinceScanMs: Long, sinceResultMs: Long?): Float? {
        if (sinceResultMs == null) return (sinceScanMs.coerceAtLeast(0L) % PASS_MS).toFloat()
        val atResult = phaseAtResult(sinceScanMs - sinceResultMs)
        if (sinceResultMs >= finishMs(sinceScanMs - sinceResultMs)) return null
        return (atResult + hurried(sinceResultMs.toFloat())).coerceAtMost(PASS_MS.toFloat())
    }

    /** 循环光的强度：识别开始时淡入，之后一直是满的，结果到达后也不退场。 */
    fun loopStrength(sinceScanMs: Long): Float = smooth(sinceScanMs.toFloat() / FADE_IN_MS)

    /** 结果在识别开始后 [scanAtResultMs] 到达时，那道循环光提速走完这一趟要多久。 */
    fun finishMs(scanAtResultMs: Long): Long {
        val left = PASS_MS - phaseAtResult(scanAtResultMs)
        val r = SPEED_UP_MS.toFloat()
        val ramp = hurried(r)
        val t = if (left <= ramp) {
            // t + a·t² = left
            val a = (FAST - 1f) / (2f * r)
            (-1f + sqrt(1f + 4f * a * left)) / (2f * a)
        } else {
            r + (left - ramp) / FAST
        }
        return ceil(t).toLong()
    }

    /** 落码那一遍开始以来过了多久；循环光还没走完那一趟时为 null。 */
    fun revealMs(sinceScanMs: Long, sinceResultMs: Long?): Long? {
        if (sinceResultMs == null) return null
        return (sinceResultMs - finishMs(sinceScanMs - sinceResultMs)).takeIf { it >= 0L }
    }

    /** 结果在识别开始后 [scanAtResultMs] 到达时，从那一刻到落码结束一共多久。 */
    fun settleMs(scanAtResultMs: Long): Long = finishMs(scanAtResultMs) + REVEAL_MS

    /** 落码那一遍相对循环光的速度：同样一趟路，[REVEAL_MS] 走完而不是 [PASS_MS]。 */
    private val FAST = PASS_MS.toFloat() / REVEAL_MS

    private fun phaseAtResult(scanAtResultMs: Long): Float = (scanAtResultMs.coerceAtLeast(0L) % PASS_MS).toFloat()

    /**
     * 结果到达后过了 [t] 毫秒，循环光在这一趟里走过了多少：速度在 [SPEED_UP_MS] 内
     * 由原速线性提到 [FAST] 倍，之后保持。按这一趟里的时间算，沿用同一条缓动，落码那一遍也是。
     */
    private fun hurried(t: Float): Float {
        val r = SPEED_UP_MS.toFloat()
        return if (t <= r) t + (FAST - 1f) * t * t / (2f * r) else r * (1f + FAST) / 2f + FAST * (t - r)
    }

    /**
     * 落码那道光此刻中心的纵坐标。打码块在它上方 [feather] 的距离内由无到有。
     * 起点整道光还藏在图像上方；终点渐显区和光尾都已离开图像，最底下的块也已盖实。
     */
    fun revealFront(sinceResultMs: Long, height: Float, lead: Float, trail: Float, feather: Float): Float {
        val p = (sinceResultMs.toFloat() / REVEAL_MS).coerceIn(0f, 1f)
        return -lead + travel(p) * (height + lead + feather + trail)
    }

    /** 识别结果在纵坐标 y 处显现了多少：光的中心以下还没有，中心往上 [feather] 处盖实。 */
    fun coverage(y: Float, front: Float, feather: Float): Float = smooth((front - y) / feather)

    /**
     * 暗幕在纵坐标 y 处的不透明度（0..[SCRIM_ALPHA]）。识别开始时与光一起淡入；
     * 落码那一遍（[front] 非 null）里，光走过的地方按 [coverage] 揭开，与打码块的显现严格同步。
     */
    fun scrim(y: Float, sinceScanMs: Long, front: Float?, feather: Float): Float {
        val fadeIn = smooth(sinceScanMs.toFloat() / FADE_IN_MS)
        val lifted = if (front == null) 0f else coverage(y, front, feather)
        return SCRIM_ALPHA * fadeIn * (1f - lifted)
    }

    /** 光在纵坐标 y 处的亮度：中心为 1，往上沿长尾、往下沿前沿平滑地落到 0。 */
    fun glow(y: Float, center: Float, trail: Float, lead: Float): Float =
        if (y <= center) bump((center - y) / trail) else bump((y - center) / lead)

    /** 光里颜色流动的相位（0..1）。只跟识别开始以来的时间走，提速与落码时接着流，不跳。 */
    fun drift(sinceScanMs: Long): Float = (sinceScanMs.coerceAtLeast(0L) % DRIFT_MS).toFloat() / DRIFT_MS

    /** 匀速里掺一半正弦缓动：起止柔和，中段又不至于冲得太快。 */
    fun travel(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return 0.5f * x + 0.5f * (0.5f - 0.5f * cos(PI.toFloat() * x))
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 两端导数为 0 的鼓包：d = 0 时为 1，|d| ≥ 1 时为 0。 */
    private fun bump(d: Float): Float {
        val x = minOf(1f, abs(d))
        val s = 1f - x * x
        return s * s
    }
}

/**
 * 扫光时间轴上的一帧：识别开始、结果到达（还在识别为 null）以来各过了多少毫秒。
 * 画布在识别中、结果到达后循环光提速走完那一趟、和落码那一遍里逐帧更新它，其余时候为 null——一帧都不占。
 */
internal data class ScanTime(val sinceScan: Long, val sinceResult: Long?) {
    companion object {
        /** 识别刚开始、第一帧还没到。 */
        val START = ScanTime(0L, null)
    }
}
