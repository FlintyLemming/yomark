package com.dama.app.render

import com.dama.app.core.model.MaskStyle

/**
 * 样式 → 渲染器。尚未实现的样式回退到实色块——
 * 回退到「更安全」的那一侧，绝不回退到不遮蔽的样式。
 */
class RendererRegistry(renderers: List<MaskRenderer>) {

    private val byStyle = renderers.associateBy { it.style }
    private val fallback = byStyle[MaskStyle.SOLID]
        ?: error("RendererRegistry 必须包含 SOLID 渲染器")

    operator fun get(style: MaskStyle): MaskRenderer = byStyle[style] ?: fallback

    fun implemented(): Set<MaskStyle> = byStyle.keys

    companion object {
        /** M1 只有实色块。后续里程碑往这个列表里加实现。 */
        fun default() = RendererRegistry(listOf(SolidRenderer()))
    }
}
