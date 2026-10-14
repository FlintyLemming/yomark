package moe.flinty.yomark.core.model

import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.flinty.yomark.core.geometry.Quad

/**
 * 马克笔、画笔下一笔怎么画（2026-10-14）：拉一个矩形框，或者按着手指照手的轨迹画。
 * 只管新画的：画好的那一笔是框还是手画的，看它自己带没带 [InkPath]。枚举名是持久化格式的一部分（SettingsStore）。
 */
enum class StrokeShape { BOX, FREEHAND }

/**
 * 手画的一笔：手指走过的点，按这一笔的外接框归一化到 0～1（见 [Ink.fit]）。
 *
 * 存相对外接框的位置，不存图上的坐标：这一笔和框一样能拖着走、拖角改大小（SelectionHandles 只改框），
 * 导出时框按比例换到原图上，这一笔跟着到位，不用另算一遍。
 */
@Parcelize
data class InkPath(val points: List<PointF>) : Parcelable

object Ink {

    /**
     * 手画的一笔放进图上：外接框四边各往外让出半个笔宽（笔是圆头的，画在框里不出框），点按外接框归一化。
     * 框外面多出来的这一圈，渲染时再按当时的笔宽让回去（见 [toImage]）。
     *
     * @param halfWidth 半个笔宽，与 [points] 同一坐标系
     */
    fun fit(points: List<PointF>, halfWidth: Float): Pair<Quad, InkPath> {
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        val w = right - left
        val h = bottom - top
        val normalized = points.map {
            PointF(if (w > 0f) (it.x - left) / w else 0.5f, if (h > 0f) (it.y - top) / h else 0.5f)
        }
        val box = RectF(left - halfWidth, top - halfWidth, right + halfWidth, bottom + halfWidth)
        return Quad.fromRect(box) to InkPath(normalized)
    }

    /**
     * [ink] 在 [quad] 里的位置：框四边各让回 [halfWidth]（框太窄时最多让到正中），点按让完的框摆回去。
     * 框被拖大拖小，这一笔跟着拉伸。
     */
    fun toImage(ink: InkPath, quad: Quad, halfWidth: Float): List<PointF> {
        val b = quad.bounds()
        val dx = halfWidth.coerceAtMost(b.width() / 2f)
        val dy = halfWidth.coerceAtMost(b.height() / 2f)
        val left = b.left + dx
        val top = b.top + dy
        val w = b.width() - dx * 2f
        val h = b.height() - dy * 2f
        return ink.points.map { PointF(left + it.x * w, top + it.y * h) }
    }

    /** 这一笔一共多长，与 [points] 同一坐标系。太短的一笔是手抖，不算画（见 ImageCanvas）。 */
    fun length(points: List<PointF>): Float =
        points.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }.sum()
}
