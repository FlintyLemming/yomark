package moe.flinty.yomark.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat

/**
 * 调节面板里滑条的名字（样式面板、用途水印面板的测试共用）：[names] 是一个面板里每一条滑条的名字，自定义颜色的三条要先点开。
 *
 * 每一个名字都一行写全，不折成两行、不截。一个面板里的滑条要么都和名字排在一行，名字那一列一样宽（滑条的头对齐）；
 * 要么都换成上下两行，名字在滑条上面。滑条的框是它给读屏的：Material 的 Slider 左右各多加了一截
 * （IncreaseHorizontalSemanticsBounds），比画出来的宽，横向的位置只拿名字比。要量 NATIVE 的字（LEGACY 量字是假的）。
 *
 * @return 滑条和名字排在一行（true），还是都换成了上下两行
 */
internal fun ComposeContentTestRule.assertSliderNamesFit(names: List<String>): Boolean {
    val rows = names.map { name ->
        // 字号大了面板放不下，在里面滚动
        val node = onNodeWithText(name).performScrollTo()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val r = results.single()
        assertThat(r.lineCount).isEqualTo(1)
        // 只看行数的话，写不下截掉了也是一行：直接看整个名字放不放得下
        assertThat(r.multiParagraph.intrinsics.maxIntrinsicWidth).isAtMost(r.layoutInput.constraints.maxWidth.toFloat())
        node.getUnclippedBoundsInRoot() to onNodeWithContentDescription(name).getUnclippedBoundsInRoot()
    }
    val beside = rows.map { (label, track) -> label.top < track.bottom && label.bottom > track.top }
    assertThat(beside.toSet()).hasSize(1)
    assertThat(rows.map { it.first.left }.toSet()).hasSize(1)
    if (beside.first()) {
        // 名字那一列的右头就是滑条的头
        assertThat(rows.map { it.first.right }.toSet()).hasSize(1)
    } else {
        rows.forEach { (label, track) -> assertThat(label.bottom).isAtMost(track.top) }
    }
    return beside.first()
}
