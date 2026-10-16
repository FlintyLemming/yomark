package moe.flinty.yomark.core.model

import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.flinty.yomark.core.geometry.Quad

/**
 * 马克笔、画笔下一笔怎么画（2026-10-14）：拉一个矩形框，或者按着手指照手的轨迹画。
 * 画笔还能拉一支箭头（2026-10-16）：从按下的地方指到松手的地方。马克笔没有箭头，半透明的粗箭头看不清头。
 * 只管新画的：画好的那一笔是框、手画的还是箭头，看它自己带没带 [InkPath]、带的是不是箭头。
 * 枚举名是持久化格式的一部分（SettingsStore）。
 */
enum class StrokeShape { BOX, FREEHAND, ARROW }

/**
 * 手画的一笔：手指走过的点，按这一笔的外接框归一化到 0～1（见 [Ink.fit]）。
 * [arrow] 时是一支箭头：两个点，尾在前、箭尖在后；归一化的是整个框（连让出来的那一圈），框拖大拖小，头尾按比例跟着走。
 *
 * 存相对外接框的位置，不存图上的坐标：这一笔和框一样能拖着走、拖角改大小（SelectionHandles 只改框），
 * 导出时框按比例换到原图上，这一笔跟着到位，不用另算一遍。
 */
@Parcelize
data class InkPath(val points: List<PointF>, val arrow: Boolean = false) : Parcelable

object Ink {

    /**
     * 手画的一笔放进图上：外接框四边各往外让出 [margin]（笔是圆头的，画在框里不出框；箭头还要让出箭头的半宽，见 [margin]），
     * 点按外接框归一化。框外面多出来的这一圈，渲染时再按同样的 [margin] 让回去（见 [toImage]）。
     * 箭头按整个框归一化：让出来的那一圈是头的地方，框拖小了头尾跟着按比例收，不会框还很大箭头先缩成一个点。
     *
     * @param margin 与 [points] 同一坐标系
     */
    fun fit(points: List<PointF>, margin: Float, arrow: Boolean = false): Pair<Quad, InkPath> {
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        val box = RectF(left - margin, top - margin, right + margin, bottom + margin)
        val inner = if (arrow) box else RectF(left, top, right, bottom)
        val normalized = points.map {
            PointF(
                if (inner.width() > 0f) (it.x - inner.left) / inner.width() else 0.5f,
                if (inner.height() > 0f) (it.y - inner.top) / inner.height() else 0.5f,
            )
        }
        return Quad.fromRect(box) to InkPath(normalized, arrow)
    }

    /**
     * [ink] 在 [quad] 里的位置：框四边各让回 [margin]（框太窄时最多让到正中），点按让完的框摆回去。
     * 框被拖大拖小，这一笔跟着拉伸。箭头不让：它是按整个框归一化的（见 [fit]）。
     */
    fun toImage(ink: InkPath, quad: Quad, margin: Float): List<PointF> {
        val b = quad.bounds()
        val inset = if (ink.arrow) 0f else margin
        val dx = inset.coerceAtMost(b.width() / 2f)
        val dy = inset.coerceAtMost(b.height() / 2f)
        val left = b.left + dx
        val top = b.top + dy
        val w = b.width() - dx * 2f
        val h = b.height() - dy * 2f
        return ink.points.map { PointF(left + it.x * w, top + it.y * h) }
    }

    /**
     * 一笔的外接框四边让出多少：半个笔宽 [halfWidth]；箭头的头比线宽，让出箭头的半宽（[ARROW_HEAD_HALF] 个半笔宽），
     * 斜着的箭头，头的两个角也落在框里。
     */
    fun margin(halfWidth: Float, arrow: Boolean): Float = if (arrow) halfWidth * ARROW_HEAD_HALF else halfWidth

    /** 箭头的头多长、多宽（半宽），按半个笔宽算：笔越粗头越大，夹角约 32°。 */
    const val ARROW_HEAD_LENGTH = 8f
    const val ARROW_HEAD_HALF = 5f

    /** 这一笔一共多长，与 [points] 同一坐标系。太短的一笔是手抖，不算画（见 ImageCanvas）。 */
    fun length(points: List<PointF>): Float =
        points.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }.sum()
}
