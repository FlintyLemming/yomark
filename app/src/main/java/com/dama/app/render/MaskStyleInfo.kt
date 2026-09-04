package com.dama.app.render

import com.dama.app.core.model.MaskStyle

/**
 * 安全性分档（spec §8 的「安全性」列）。
 *
 * 用户以为自己打了码而实际没有，是这个 app 能犯的最严重的错误。
 * COSMETIC 与 ANNOTATION_ONLY 必须在 UI 上带着说明一起出现。
 */
enum class MaskSafety { IRREVERSIBLE, COSMETIC, ANNOTATION_ONLY }

object MaskStyleInfo {

    fun label(style: MaskStyle): String = when (style) {
        MaskStyle.SOLID -> "实色块"
        MaskStyle.PIXELATE -> "像素化"
        MaskStyle.BLUR -> "模糊"
        MaskStyle.MARKER -> "马克笔"
        MaskStyle.EMOJI -> "Emoji"
        MaskStyle.ERASE -> "抹除"
    }

    fun safety(style: MaskStyle): MaskSafety = when (style) {
        MaskStyle.SOLID, MaskStyle.PIXELATE, MaskStyle.EMOJI, MaskStyle.ERASE -> MaskSafety.IRREVERSIBLE
        MaskStyle.BLUR -> MaskSafety.COSMETIC
        MaskStyle.MARKER -> MaskSafety.ANNOTATION_ONLY
    }

    fun note(style: MaskStyle): String? = when (safety(style)) {
        MaskSafety.IRREVERSIBLE -> null
        MaskSafety.COSMETIC -> "模糊是外观优先，非安全手段——可能被还原"
        MaskSafety.ANNOTATION_ONLY -> "马克笔仅标记、不遮蔽，底下的内容仍然可见"
    }
}
