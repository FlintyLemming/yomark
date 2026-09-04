package com.youma.app.render

import com.youma.app.core.model.MaskStyle

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

    /**
     * 像素化原本按 spec §8 标 IRREVERSIBLE，**实测推翻了这条**（spec §15.6）：
     * 固定块网格下只改一位数字，40–96px 字号上 10 个数字的马赛克两两全部可区分，
     * 知道字体字号的攻击者可以逐位模板还原。把块调大到不泄漏时，
     * 每字高只剩约 1 块，视觉上已经是实色块——不存在既安全又还像马赛克的参数。
     * 所以如实降级为 COSMETIC，而不是留一个假的安全承诺。
     */
    fun safety(style: MaskStyle): MaskSafety = when (style) {
        MaskStyle.SOLID, MaskStyle.EMOJI, MaskStyle.ERASE -> MaskSafety.IRREVERSIBLE
        MaskStyle.BLUR, MaskStyle.PIXELATE -> MaskSafety.COSMETIC
        MaskStyle.MARKER -> MaskSafety.ANNOTATION_ONLY
    }

    /** 逐样式给文案，不按档位共用一句——两种 COSMETIC 的失效方式不一样，说法也该不一样。 */
    fun note(style: MaskStyle): String? = when (style) {
        MaskStyle.BLUR -> "模糊是外观优先，非安全手段——可能被还原"
        MaskStyle.PIXELATE -> "像素化是外观优先，非安全手段——已知字体的截图可被逐位还原"
        MaskStyle.MARKER -> "马克笔仅标记、不遮蔽，底下的内容仍然可见"
        MaskStyle.SOLID, MaskStyle.EMOJI, MaskStyle.ERASE -> null
    }
}
