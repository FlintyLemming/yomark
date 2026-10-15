package moe.flinty.yomark.ui.canvas

import android.graphics.Color as AndroidColor
import android.graphics.BlendMode
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem

/**
 * 点一下打码、取消打码时的那一下动效：打码像贴胶带，从左往右抹上去；取消打码是从左往右揭开，字按阅读顺序露出来。
 *
 * 只在画布上演，plan 早就改完了：拿掉的码已经不在 plan 里，这里留着它最后的样子画完这一遍。
 * 撤销、重做、宽屏列表上点掉的也一样演；识别结果上屏有自己的落码 / 散开，不归这里。
 * 手动框、手画的一笔落下时不演：手指底下已经是它的样子了，再抹一遍像是画了两次。
 */
internal object MaskToggle {
    const val IN_MS = 280L
    const val OUT_MS = 260L

    /** 抹上 / 揭开那道边的软边宽度（dp）。 */
    const val FEATHER_DP = 28f

    /** ease-out cubic，与菜单收回去同一条曲线：起步快，尾巴不拖。 */
    private val EASING = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    /** 走了 [elapsedMs] 毫秒时这一遍走到哪儿（0..1）。 */
    fun progress(elapsedMs: Long, durationMs: Long): Float =
        EASING.transform((elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f))
}

/** 这一帧里正在抹上的码（id → 进度）与正在揭开的码（拿掉前的样子 → 进度）。 */
internal class ToggleFrame(val appearing: Map<String, Float>, val vanishing: List<Pair<MaskItem, Float>>)

/**
 * 记着上一次见到的码，plan 一变就比一比：多出来的抹上，少了的揭开。
 * 开始时间在走第一帧时才定（见 [advance]），定下来之前进度是 0：新码先不露、拿掉的码先还在，不会闪一下。
 */
internal class MaskToggles {
    private var last: Map<String, MaskItem>? = null
    private val appearing = LinkedHashMap<String, Long?>()
    private val vanishing = LinkedHashMap<String, Pair<MaskItem, Long?>>()

    val active get() = appearing.isNotEmpty() || vanishing.isNotEmpty()

    /** @param animate false 时只记下这一份，不演：第一次见、识别结果上屏、识别中 */
    fun sync(items: List<MaskItem>, animate: Boolean) {
        val now = items.associateBy { it.candidateId }
        val before = last
        last = now
        // 演到一半又变了：已经不在的不再抹，又回来的不再揭
        appearing.keys.retainAll(now.keys)
        vanishing.keys.removeAll(now.keys)
        if (before == null || !animate) return
        now.values.forEach {
            if (it.candidateId !in before && it.source != DetectorSource.MANUAL) appearing[it.candidateId] = null
        }
        before.values.forEach {
            if (it.candidateId !in now) vanishing[it.candidateId] = it to null
        }
    }

    /** 走一帧：还没开始的从这一帧算起，走完的收掉。 */
    fun advance(frameMs: Long) {
        appearing.entries.removeAll { (_, start) -> start != null && frameMs - start >= MaskToggle.IN_MS }
        appearing.replaceAll { _, start -> start ?: frameMs }
        vanishing.entries.removeAll { (_, v) -> v.second != null && frameMs - v.second!! >= MaskToggle.OUT_MS }
        vanishing.replaceAll { _, (item, start) -> item to (start ?: frameMs) }
    }

    fun frame(frameMs: Long): ToggleFrame = ToggleFrame(
        appearing.mapValues { (_, start) -> start?.let { MaskToggle.progress(frameMs - it, MaskToggle.IN_MS) } ?: 0f },
        vanishing.values.map { (item, start) -> item to (start?.let { MaskToggle.progress(frameMs - it, MaskToggle.OUT_MS) } ?: 0f) },
    )
}

/** 抹上 / 揭开用的蒙版画笔，跨帧复用。 */
internal class TogglePaints {
    val keep = Paint().apply { blendMode = BlendMode.DST_IN }
}

/**
 * 把 [draw] 画出来的一块码按进度只留一部分：一道带软边的竖线从外接框左边走到右边，
 * [laying] 时线左边的留着（抹上），否则线右边的留着（揭开）。图像坐标，[feather] 是软边宽度。
 */
internal fun drawWiped(
    canvas: android.graphics.Canvas,
    item: MaskItem,
    progress: Float,
    laying: Boolean,
    feather: Float,
    paints: TogglePaints,
    draw: () -> Unit,
) {
    val b = item.quad.bounds()
    val area = RectF(b).apply { inset(-feather, -feather) }
    val layer = canvas.saveLayer(area, null)
    draw()
    // 线从框左边外一个软边处走到右边：0 时整块都在线的右边，1 时整块都在线的左边
    val front = b.left + (b.width() + feather) * progress
    val on = AndroidColor.BLACK
    val off = AndroidColor.TRANSPARENT
    paints.keep.shader = LinearGradient(
        front - feather, 0f, front, 0f,
        if (laying) on else off, if (laying) off else on,
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(area, paints.keep)
    canvas.restoreToCount(layer)
}
