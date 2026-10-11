package moe.flinty.yomark.ui.components

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
) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 向上回到首页，放在 Android 约定的左上角
        IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Row(Modifier.weight(1f)) {}
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, "撤销") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, "重做") }
        if (onRemoveWatermark != null) {
            IconButton(onClick = onRemoveWatermark) { Icon(Icons.Filled.WorkspacePremium, if (Edition.isFree) "全功能免费版" else "去除水印") }
        }
        IconButton(onClick = onPurpose) {
            Icon(
                Icons.Filled.Layers,
                if (purposeOn) "用途水印（已开启）" else "用途水印",
                tint = if (purposeOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        if (onDebug != null) {
            IconButton(onClick = onDebug) { Icon(Icons.Filled.BugReport, "调试信息") }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "设置") }
    }
}

@Composable
fun EditorBottomBar(
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    /** 批量进度，如「2 / 5」。单张时为 null。 */
    batchLabel: String? = null,
    /** 非 null 时说明后面还有图：主按钮是「下一张」而不是「导出」（spec §7.6）。 */
    onNext: (() -> Unit)? = null,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
) {
    // 样式的调节面板是弹出来盖在画布上的（见 StyleBar），底栏的高度不随它变
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StyleBar(model = styleBar, actions = styleActions)
        EditorActionRow(
            onExport = onExport,
            exporting = exporting,
            batchLabel = batchLabel,
            onNext = onNext,
            aiReview = aiReview,
            onAiReview = onAiReview,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 底栏的最后一行：左边「AI 增强识别」，右边批量进度和主按钮（「导出」，批量里后面还有图时是「下一张」）。
 * 手机的底栏和宽屏的样式列表那一块（WideEditorPanel）共用。
 */
@Composable
fun EditorActionRow(
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    batchLabel: String? = null,
    onNext: (() -> Unit)? = null,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AiReviewButton(aiReview, onAiReview)
        Spacer(Modifier.weight(1f))
        if (batchLabel != null) {
            Text(batchLabel, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
        }
        if (onNext != null) {
            Button(onClick = onNext) { Text("下一张") }
        } else {
            Button(onClick = onExport, enabled = !exporting) { Text(if (exporting) "导出中…" else "导出") }
        }
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
            Text("AI 增强识别")
        }
        AiReview.Running -> TextButton(onClick = {}, enabled = false) {
            // 按钮灰掉了，星星仍着主题色，看得出它在干活
            SparkleIcon(animating = true, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("增强识别中…")
        }
        is AiReview.Done -> TextButton(
            onClick = {},
            enabled = false,
            // 这是结果，要看得清，不能像别的不可点按钮那样灰下去
            colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.found == 0) "没有新发现" else "新发现 ${state.found} 处")
        }
        AiReview.Failed -> TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            SparkleIcon(animating = false, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("识别失败，点击重试")
        }
    }
}
