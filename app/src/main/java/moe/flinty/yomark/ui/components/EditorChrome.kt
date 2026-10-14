package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.ui.AiReview
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPurpose: () -> Unit,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    /** 已设用途水印时按钮着主题色，一眼看出它开着。 */
    purposeOn: Boolean = false,
    /** 去水印的购买入口（开源版是免费版说明与打赏）。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
    /** 调试信息（设置 → 关于，连点版本号打开）。没打开时传 null，按钮就不出现。 */
    onDebug: (() -> Unit)? = null,
    /** 「对比」按钮的按压状态：按住时画布显示打码前的原图（见 EditorScreen）。传 null 时按钮不出现。 */
    compare: MutableInteractionSource? = null,
    /** 有前后可比：打了码，或者设了用途水印。没有时「对比」灰掉。 */
    canCompare: Boolean = false,
) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 向上回到首页，放在 Android 约定的左上角
        IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Row(Modifier.weight(1f)) {}
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, stringResource(R.string.undo)) }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, stringResource(R.string.redo)) }
        if (onRemoveWatermark != null) {
            IconButton(onClick = onRemoveWatermark) { Icon(Icons.Filled.WorkspacePremium, stringResource(if (Edition.isFree) R.string.free_edition else R.string.remove_watermark)) }
        }
        IconButton(onClick = onPurpose) {
            Icon(
                Icons.Filled.Layers,
                stringResource(if (purposeOn) R.string.purpose_watermark_on else R.string.purpose_watermark),
                tint = if (purposeOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        if (compare != null) {
            // 按住看原图、松手回来，点一下没有动作：来回比的时候，手一松就该回到打码后的样子
            IconButton(onClick = {}, enabled = canCompare, interactionSource = compare) {
                Icon(Icons.Filled.Compare, stringResource(R.string.compare_original))
            }
        }
        if (onDebug != null) {
            IconButton(onClick = onDebug) { Icon(Icons.Filled.BugReport, stringResource(R.string.debug_info)) }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
    }
}

@Composable
fun EditorBottomBar(
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    /** 导出按钮上的字（见 [EditorActionRow]）。 */
    @StringRes exportLabel: Int = R.string.export,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    /** 批量时的胶片条（见 [EditorActionRow]）。单张时为 null。 */
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
) {
    // 样式的调节面板是弹出来盖在画布上的（见 StyleBar），底栏的高度不随它变
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StyleBar(model = styleBar, actions = styleActions)
        EditorActionRow(
            onExport = onExport,
            exportLabel = exportLabel,
            exporting = exporting,
            aiReview = aiReview,
            onAiReview = onAiReview,
            filmstrip = filmstrip,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 底栏的最后一行，手机的底栏和宽屏的样式列表那一块（WideEditorPanel）共用。
 *
 * 单张时左边「AI 增强识别」，右边「导出」。批量时左边是胶片条，占掉导出左边剩下的地方，
 * 「AI 增强识别」挪到它和导出之间；按钮出现、消失时胶片条跟着一点点变宽变窄，停在中间的那张始终在中间。
 *
 * @param filmstrip 批量时的胶片条，传进来的 Modifier 定好了它的宽。单张时为 null
 * @param exportLabel 导出按钮上的字。别的应用来要图时是「完成」：导出就是把图交回去
 */
@Composable
fun EditorActionRow(
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    @StringRes exportLabel: Int = R.string.export,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (filmstrip != null) {
            filmstrip(Modifier.weight(1f))
            Box(Modifier.animateContentSize()) { AiReviewButton(aiReview, onAiReview) }
            Spacer(Modifier.width(8.dp))
        } else {
            AiReviewButton(aiReview, onAiReview)
            Spacer(Modifier.weight(1f))
        }
        Button(onClick = onExport, enabled = !exporting) { Text(stringResource(if (exporting) R.string.exporting else exportLabel)) }
    }
}

/**
 * 「AI 增强识别」：点了才让 Gemini Nano 把整页文字再看一遍。机型不支持、设置里关了时整个不出现。
 * 跑的时候两颗星一前一后地转着交错，代替转圈的进度条。
 * 结果就写在按钮上，不另弹提示：新发现几处、没有新发现，或者没跑成（还能点，再试一次）。
 * 跑成了就停住不可点——同一页再跑一遍结果一样，不该让人以为多点几次能多找出点什么。
 */
@Composable
internal fun AiReviewButton(state: AiReview, onClick: () -> Unit) {
    when (state) {
        AiReview.Hidden -> Unit
        AiReview.Ready -> TextButton(onClick = onClick) {
            SparkleIcon(animating = false, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_review))
        }
        AiReview.Running -> TextButton(onClick = {}, enabled = false) {
            // 按钮灰掉了，星星仍着主题色，看得出它在干活
            SparkleIcon(animating = true, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_reviewing))
        }
        is AiReview.Done -> TextButton(
            onClick = {},
            enabled = false,
            // 这是结果，要看得清，不能像别的不可点按钮那样灰下去
            colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.found == 0) stringResource(R.string.ai_review_none) else stringResource(R.string.ai_review_found, state.found))
        }
        AiReview.Failed -> TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            SparkleIcon(animating = false, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_review_failed))
        }
    }
}
