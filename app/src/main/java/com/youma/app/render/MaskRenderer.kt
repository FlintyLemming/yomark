package com.youma.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskStyle

/**
 * 渲染层（spec §4.3）。每种打码样式一个实现，样式与识别完全解耦。
 * 同一个实现既画预览也画导出——这是「所见即所得」的唯一保证。
 *
 * @param source 被打码的位图。像素化/模糊/抹除需要读它的原始像素；
 *               实色块与 Emoji 不需要，但签名统一。
 * @param quad   与 canvas 同一坐标系下的目标区域。
 */
interface MaskRenderer {
    val style: MaskStyle
    fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions)
}
