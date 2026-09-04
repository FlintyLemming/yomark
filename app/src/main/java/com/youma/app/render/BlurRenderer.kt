package com.youma.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模糊（spec §8）。**安全性：外观优先，非安全** —— UI 必须明确标注这一点。
 *
 * 实现用三次「缩小-放大」逼近高斯核。
 *
 * 方案偏离：spec 原文写「API 31+ 走 RenderEffect」。本实现两条路径都用缩放法，
 * 因为 RenderEffect 只能作用在硬件加速的 Canvas 上，而导出画在软件 Bitmap 上；
 * 分两条路径会让预览与导出的模糊强度不一致，破坏「所见即所得」。
 */
class BlurRenderer : MaskRenderer {

    override val style = MaskStyle.BLUR

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val bounds = quad.bounds()
        val src = Rect(
            bounds.left.toInt().coerceIn(0, source.width),
            bounds.top.toInt().coerceIn(0, source.height),
            bounds.right.roundToInt().coerceIn(0, source.width),
            bounds.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (src.width() < 2 || src.height() < 2) return

        val shrink = max(2f, quad.shortEdge() * options.blurRadiusRatio)
        val smallW = max(1, (src.width() / shrink).roundToInt())
        val smallH = max(1, (src.height() / shrink).roundToInt())

        var work = Bitmap.createBitmap(source, src.left, src.top, src.width(), src.height())
        repeat(PASSES) {
            val down = Bitmap.createScaledBitmap(work, smallW, smallH, true)
            val up = Bitmap.createScaledBitmap(down, src.width(), src.height(), true)
            if (down !== work) down.recycle()
            if (work !== up) work.recycle()
            work = up
        }

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(work, null, RectF(src), SMOOTH)
        canvas.restoreToCount(save)
        work.recycle()
    }

    private companion object {
        const val PASSES = 3
        val SMOOTH = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    }
}
