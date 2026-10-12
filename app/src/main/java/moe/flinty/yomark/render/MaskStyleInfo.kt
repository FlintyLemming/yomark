package moe.flinty.yomark.render

import moe.flinty.yomark.core.model.MaskStyle

/**
 * 安全性分档（spec §8 的「安全性」列）。
 *
 * 用户以为自己打了码而实际没有，是这个 app 能犯的最严重的错误。样式栏按它排序：盖得住的排前面。
 * 原先 COSMETIC 与 ANNOTATION_ONLY 在样式下面各带一句提醒，2026-10-12 去掉了：
 * 模糊、马赛克能还原，马克笔盖不住，用户都知道，提醒只是占地方。
 */
enum class MaskSafety { IRREVERSIBLE, COSMETIC, ANNOTATION_ONLY }

object MaskStyleInfo {

    /** 样式栏上的顺序：不可还原的色块排最前面，外观优先的与只做标记的排后面（与 README 的样式表一致）。 */
    val ORDER: List<MaskStyle> = listOf(
        MaskStyle.SOLID, MaskStyle.PIXELATE, MaskStyle.BLUR, MaskStyle.MARKER,
    )

    fun label(style: MaskStyle): String = when (style) {
        MaskStyle.SOLID -> "色块"
        MaskStyle.PIXELATE -> "马赛克"
        MaskStyle.BLUR -> "模糊"
        MaskStyle.MARKER -> "马克笔"
    }

    /**
     * 像素化原本按 spec §8 标 IRREVERSIBLE，**实测推翻了这条**（spec §15.6）：
     * 固定块网格下只改一位数字，40–96px 字号上 10 个数字的马赛克两两全部可区分，
     * 知道字体字号的攻击者可以逐位模板还原。把块调大到不泄漏时，
     * 每字高只剩约 1 块，视觉上已经是实色块——不存在既安全又还像马赛克的参数。
     * 所以如实降级为 COSMETIC，而不是留一个假的安全承诺。
     */
    fun safety(style: MaskStyle): MaskSafety = when (style) {
        MaskStyle.SOLID -> MaskSafety.IRREVERSIBLE
        MaskStyle.BLUR, MaskStyle.PIXELATE -> MaskSafety.COSMETIC
        MaskStyle.MARKER -> MaskSafety.ANNOTATION_ONLY
    }
}
