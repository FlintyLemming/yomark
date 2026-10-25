package moe.flinty.yomark.ui.canvas

import android.graphics.Matrix
import android.graphics.PointF
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.transformed
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Material 3 Expressive 的几个形状（MaterialShapes）。Material 3 的这套目前只在 compose-material3 的 alpha 里，
 * 不为它升级到 alpha 版：这里用它底下那套稳定的形状库（androidx.graphics:graphics-shapes）照着定义画，数值原样照搬。
 * 都没归一化，用的地方自己缩放。
 */
internal object ExpressiveShapes {

    fun softBurst() = customPolygon(
        listOf(PointNRound(0.193f, 0.277f, CornerRounding(0.053f)), PointNRound(0.176f, 0.055f, CornerRounding(0.053f))),
        reps = 10,
    )

    fun cookie9() = RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.8f, rounding = CornerRounding(0.5f))
        .transformed(Matrix().apply { setRotate(-90f) })

    fun pentagon() = customPolygon(
        listOf(
            PointNRound(0.500f, -0.009f, CornerRounding(0.172f)),
            PointNRound(1.030f, 0.365f, CornerRounding(0.164f)),
            PointNRound(0.828f, 0.970f, CornerRounding(0.169f)),
        ),
        reps = 1,
        mirroring = true,
    )

    fun pill() = customPolygon(
        listOf(
            PointNRound(0.961f, 0.039f, CornerRounding(0.426f)),
            PointNRound(1.001f, 0.428f),
            PointNRound(1.000f, 0.609f, CornerRounding(1.000f)),
        ),
        reps = 2,
        mirroring = true,
    )

    fun sunny() = RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.8f, rounding = CornerRounding(0.15f))

    fun cookie4() = customPolygon(
        listOf(PointNRound(1.237f, 1.236f, CornerRounding(0.258f)), PointNRound(0.500f, 0.918f, CornerRounding(0.233f))),
        reps = 4,
    )

    fun oval() = RoundedPolygon.circle()
        .transformed(Matrix().apply { setScale(1f, 0.64f) })
        .transformed(Matrix().apply { setRotate(-45f) })

    private class PointNRound(val x: Float, val y: Float, val r: CornerRounding = CornerRounding.Unrounded)

    /**
     * 由一段顶点绕中心重复 [reps] 次拼出整个形状；[mirroring] 时每隔一段镜像一次。
     * 与 MaterialShapes 里的同名私有函数一致。
     */
    private fun customPolygon(
        points: List<PointNRound>,
        reps: Int,
        center: PointF = PointF(0.5f, 0.5f),
        mirroring: Boolean = false,
    ): RoundedPolygon {
        val all = repeatAround(points, reps, center, mirroring)
        return RoundedPolygon(
            vertices = FloatArray(all.size * 2) { i -> if (i % 2 == 0) all[i / 2].x else all[i / 2].y },
            perVertexRounding = all.map { it.r },
            centerX = center.x,
            centerY = center.y,
        )
    }

    private fun repeatAround(points: List<PointNRound>, reps: Int, center: PointF, mirroring: Boolean): List<PointNRound> {
        if (!mirroring) {
            return (0 until points.size * reps).map { i ->
                val p = points[i % points.size]
                val (x, y) = rotateAround(p.x, p.y, (i / points.size) * 360f / reps, center)
                PointNRound(x, y, p.r)
            }
        }
        val angles = points.map { Math.toDegrees(atan2((it.y - center.y).toDouble(), (it.x - center.x).toDouble())).toFloat() }
        val distances = points.map { hypot(it.x - center.x, it.y - center.y) }
        val sections = reps * 2
        val sectionAngle = 360f / sections
        return buildList {
            repeat(sections) { s ->
                points.indices.forEach { index ->
                    val i = if (s % 2 == 0) index else points.lastIndex - index
                    if (i > 0 || s % 2 == 0) {
                        val degrees = sectionAngle * s +
                            if (s % 2 == 0) angles[i] else sectionAngle - angles[i] + 2 * angles[0]
                        val a = Math.toRadians(degrees.toDouble())
                        add(
                            PointNRound(
                                (cos(a) * distances[i] + center.x).toFloat(),
                                (sin(a) * distances[i] + center.y).toFloat(),
                                points[i].r,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun rotateAround(x: Float, y: Float, degrees: Float, center: PointF): Pair<Float, Float> {
        val a = Math.toRadians(degrees.toDouble())
        val dx = x - center.x
        val dy = y - center.y
        return (dx * cos(a) - dy * sin(a) + center.x).toFloat() to (dx * sin(a) + dy * cos(a) + center.y).toFloat()
    }
}
