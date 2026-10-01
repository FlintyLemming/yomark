package com.youma.app.ui.canvas

import kotlin.math.PI
import kotlin.math.sin

/**
 * 识别期间的扫描动效，与结果上屏时的淡入。
 *
 * PP-OCR 一张图要两秒左右，这两秒里画面一动不动，用户会以为卡住了。识别期间压一层浅暗幕、
 * 一道天蓝色光带自上而下反复扫过；结果到了，暗幕退掉，打码块和虚线框顺着扫描的方向自上而下依次淡入。
 *
 * 只是编辑器里的过渡：不改 plan、不进撤销栈、不进导出。时长与节奏全在这里，画布只管落笔。
 */
internal object ScanEffect {

    /** 光带从顶扫到底一趟的时长。 */
    const val SWEEP_MS = 1600
    const val VEIL_IN_MS = 200
    const val VEIL_OUT_MS = 450

    /** 结果上屏的整段淡入时长。 */
    const val REVEAL_MS = 650

    /** 最靠下的一块比最靠上的一块晚多少才开始淡入，占整段淡入时长的比例。 */
    const val REVEAL_STAGGER = 0.45f

    /** 暗幕最深时的不透明度。只压一点，让光带看得见，底下的内容照样看得清。 */
    const val VEIL_ALPHA = 0.16f

    /** 光带拖尾的高度与前沿亮线的粗细（dp，按缩放反算，屏幕上视觉恒定）。 */
    const val TRAIL_DP = 96f
    const val LINE_DP = 2f

    /** 光带的亮度包络：两端为 0、正中为 1。从底部跳回顶部的那一下因此看不出来。 */
    fun bandEnvelope(progress: Float): Float = sin(PI.toFloat() * progress.coerceIn(0f, 1f))

    /**
     * 一块候选此刻的不透明度。
     *
     * - 手动框不参与：用户自己画的，识别期间也一直在；
     * - 识别中一律藏起来：换方案重跑时旧结果先退场，不与扫描混在一起；
     * - 上屏后自上而下依次淡入。
     *
     * @param progress 淡入进度 0..1
     * @param centerY 这一块的中心纵坐标（图像坐标）
     */
    fun revealAlpha(manual: Boolean, scanning: Boolean, progress: Float, centerY: Float, imageHeight: Int): Float {
        if (manual) return 1f
        if (scanning) return 0f
        if (progress >= 1f) return 1f
        val ny = if (imageHeight > 0) (centerY / imageHeight).coerceIn(0f, 1f) else 0f
        return ((progress - ny * REVEAL_STAGGER) / (1f - REVEAL_STAGGER)).coerceIn(0f, 1f)
    }
}
