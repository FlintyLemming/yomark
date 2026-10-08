package moe.flinty.yomark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.ui.canvas.ImageCanvas
import moe.flinty.yomark.ui.canvas.ScanEffect
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.EditorTopBar
import moe.flinty.yomark.ui.components.FreeEditionDialog
import moe.flinty.yomark.ui.components.PaywallDialog
import moe.flinty.yomark.ui.components.PendingExportDialog
import moe.flinty.yomark.ui.components.PurposeWatermarkPanel
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.eraseDegradeNote

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    onBuyClicked: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scanStyle by vm.scanStyle.collectAsStateWithLifecycle()
    val registry = remember { RendererRegistry.default() }
    val snackbar = remember { SnackbarHostState() }
    val styleActions = remember(vm) { ViewModelStyleActions(vm) }

    LaunchedEffect(state.message) {
        when (val m = state.message) {
            is EditorMessage.Exported -> {
                val extra = if (m.downscaled) "（图片太大，已缩小到 ${m.width}×${m.height}）" else ""
                snackbar.showMessage("已保存到相册$extra", vm)
            }
            is EditorMessage.BatchExported -> snackbar.showMessage("已保存 ${m.count} 张到相册", vm)
            is EditorMessage.Error -> snackbar.showMessage(m.text, vm)
            is EditorMessage.Notice -> snackbar.showMessage(m.text, vm)
            null -> Unit
        }
    }

    if (state.pendingDialogVisible) {
        // 批量下拦截看的是全部图片的汇总，不是当前这一张（spec §7.6）。
        val batchWithCurrent = state.batch?.withPlan(state.plan)
        val pending = batchWithCurrent?.totalPending ?: state.plan.pendingCount
        val byKind = batchWithCurrent?.items?.mapNotNull { it.plan }
            ?.flatMap { it.pendingByKind().entries }
            ?.groupBy({ it.key }, { it.value })
            ?.mapValues { (_, counts) -> counts.sum() }
            ?: state.plan.pendingByKind()
        PendingExportDialog(
            pendingCount = pending,
            byKind = byKind,
            onMaskAll = vm::confirmMaskAllAndExport,
            onExportAnyway = vm::confirmExportAnyway,
            onDismiss = vm::dismissDialog,
        )
    }

    if (state.paywallVisible && Edition.isFree) {
        FreeEditionDialog(onDismiss = vm::dismissPaywall)
    } else if (state.paywallVisible) {
        PaywallDialog(onBuy = onBuyClicked, onDismiss = vm::dismissPaywall)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // 边到边（见 enableLightEdgeToEdge）。默认只让开系统栏；横屏时挖孔在侧边，会压住顶栏和底栏的按钮。
        // 输入法不在这里：只有用途水印面板里有输入框，由面板自己让开（见下方 imePadding）。
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { padding ->
        // consumeWindowInsets：面板的 imePadding 只补输入法比导航栏多出来的那一截，不重复算导航栏
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            EditorTopBar(
                canUndo = state.canUndo, canRedo = state.canRedo,
                onUndo = vm::undo, onRedo = vm::redo,
                onPurpose = vm::showPurposeSheet,
                purposeOn = state.purposeText != null,
                // 设置是单独的 Activity，改动写进 DataStore，回到这里时 ViewModel 已经按新方案重跑
                onSettings = onSettings,
                // 已购用户不该再看见购买入口；开源版留着它，点开是免费版说明与打赏
                onRemoveWatermark = if (state.isPro && !Edition.isFree) null else vm::showPaywall,
                onNavigateUp = onNavigateUp,
            )
            // 底栏的按钮已经写着「复查中…」，这里只给一条进度，不再重复说一遍
            if (state.aiReview == AiReview.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                ImageCanvas(
                    state = state,
                    registry = registry,
                    onTap = vm::onTap,
                    onManualBox = vm::onManualBox,
                    onResize = vm::previewSelectedQuad,
                    onCommitDrag = vm::commitDrag,
                    onDeleteSelected = vm::deleteSelected,
                    scanStyle = scanStyle,
                )
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnalyzingPill(visible = state.analyzing && state.image != null)
                    ColorPickHint(visible = state.colorPick != null, onCancel = vm::cancelColorPick)
                }
            }
            val batch = state.batch
            // 吸管在等取色时，返回先收吸管；样式面板开着时，返回先收面板。都不拦的时候不常驻 BackHandler（见 EditorActivity）
            BackHandler(enabled = state.colorPick != null, onBack = vm::cancelColorPick)
            BackHandler(
                enabled = state.colorPick == null && state.stylePanelOpen && !state.purposeSheetVisible,
                onBack = vm::dismissStylePanel,
            )
            if (state.purposeSheetVisible) {
                // 面板顶替底栏，画布留在上面实时预览。返回键先收面板，不直接退出编辑器。
                BackHandler(onBack = vm::dismissPurposeSheet)
                PurposeWatermarkPanel(
                    text = state.purposeText,
                    style = state.purposeStyle,
                    onTextChange = vm::setPurposeText,
                    onStyleChange = vm::setPurposeStyle,
                    onReset = vm::resetPurposeStyle,
                    onRemove = vm::removePurposeWatermark,
                    onDone = vm::dismissPurposeSheet,
                    modifier = Modifier.imePadding(),
                )
            } else EditorBottomBar(
                styleBar = styleBarModel(state),
                styleActions = styleActions,
                onExport = {
                    if (batch != null) vm.exportBatch() else vm.requestExport()
                },
                exporting = state.exporting,
                batchLabel = batch?.let { "${it.index + 1} / ${it.total}" },
                onNext = if (batch != null && !batch.isLast) vm::nextImage else null,
                aiReview = state.aiReview,
                onAiReview = vm::runAiReview,
            )
        }
    }
}

/**
 * 样式栏要显示的东西：样式栏上那一份（选中框的或画笔）、「应用到全部」能改几处、抹除降级了几处。
 * 降级要逐块采样，只在样式栏停在抹除上时才数。
 */
@Composable
private fun styleBarModel(state: EditorUiState): StyleBarModel {
    val look = state.barLook
    val degradeNote = remember(state.image, state.plan, look.style) {
        if (look.style == MaskStyle.ERASE) eraseDegradeNote(state.image, state.plan) else null
    }
    return StyleBarModel(
        look = look,
        panelOpen = state.stylePanelOpen,
        editingSelection = state.selectedItem != null,
        colorPick = state.colorPick,
        applicable = state.plan.items.count { it.state == MaskState.MASKED && it.look != look },
        degradeNote = degradeNote,
    )
}

/** 样式栏的动作都交给 ViewModel。 */
private class ViewModelStyleActions(private val vm: EditorViewModel) : StyleBarActions {
    override fun onStyleClick(style: MaskStyle) = vm.onStyleChipClick(style)
    override fun onLookChange(look: MaskLook, inProgress: Boolean) = vm.editLook(look, inProgress)
    override fun onLookChangeFinished() = vm.finishLookEdit()
    override fun onReset() = vm.resetLookOptions()
    override fun onApplyToAll() = vm.applyLookToAll()
    override fun onCollapse() = vm.dismissStylePanel()
    override fun onPickColor(target: ColorTarget?) =
        if (target == null) vm.cancelColorPick() else vm.startColorPick(target)
}

/**
 * 吸管在等着取色时浮在画布顶上的提示：点图上哪儿就取哪儿的颜色。右边一个「取消」。
 * 取的是原图的颜色，不是已经打了码的颜色——想让色块和底色融成一片，要的正是底下的颜色。
 */
@Composable
private fun ColorPickHint(visible: Boolean, onCancel: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Colorize, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("点一下图片，取那里的颜色", style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                ) { Text("取消") }
            }
        }
    }
}

/**
 * 识别中的提示条，与画布上的扫光一起出现（PP-OCR 一张图约 2 秒）。
 *
 * 不再放转圈的进度圈：一道与画布上同色的光从字面上掠过，告诉用户还在读，
 * 和图上那束光是一回事。光的位置只在绘制里读，走起来只重绘、不重组。
 *
 * 用 Box 画底色而不是 Surface：Material3 的 Surface 会吃掉触摸，
 * 识别期间画布仍可交互（spec §7.1），提示条底下那一块也不该点不动。
 */
@Composable
private fun AnalyzingPill(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val sheen = rememberInfiniteTransition(label = "analyzingSheen").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SHEEN_MS, easing = LinearEasing)),
            label = "sheen",
        )
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "识别中…",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    // 离屏合成，SrcAtop 才只染在字形上
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // 光带从字的左边外面走到右边外面，两头各有一段看不见的空档
                        val band = size.width * 0.6f
                        val x = -band + sheen.value * (size.width + band)
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.35f to Color(ScanEffect.HUES[0]),
                                0.65f to Color(ScanEffect.HUES[2]),
                                1f to Color.Transparent,
                                startX = x,
                                endX = x + band,
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
        }
    }
}

/** 提示条上的光掠过一次的时长。 */
private const val SHEEN_MS = 1600

private suspend fun SnackbarHostState.showMessage(text: String, vm: EditorViewModel) {
    showSnackbar(text)
    vm.consumeMessage()
}
