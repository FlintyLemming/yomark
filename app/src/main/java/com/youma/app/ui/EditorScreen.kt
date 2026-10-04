package com.youma.app.ui

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.canvas.ImageCanvas
import com.youma.app.ui.canvas.ScanEffect
import com.youma.app.ui.components.EditorBottomBar
import com.youma.app.ui.components.EditorTopBar
import com.youma.app.ui.components.PaywallDialog
import com.youma.app.ui.components.PendingExportDialog
import com.youma.app.ui.components.PurposeWatermarkPanel

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    onBuyClicked: () -> Unit = {},
    onRedeem: (String) -> Boolean = { false },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val registry = remember { RendererRegistry.default() }
    val snackbar = remember { SnackbarHostState() }

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

    if (state.paywallVisible) {
        PaywallDialog(
            onBuy = onBuyClicked,
            onRedeem = { code -> onRedeem(code).also { if (it) vm.dismissPaywall() } },
            onDismiss = vm::dismissPaywall,
        )
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
                // 已购用户不该再看见购买入口
                onRemoveWatermark = if (state.isPro) null else vm::showPaywall,
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
                    onLongPress = vm::onLongPress,
                    onManualBox = vm::onManualBox,
                    onResize = vm::previewSelectedQuad,
                    onCommitDrag = vm::commitDrag,
                    onDeleteSelected = vm::deleteSelected,
                )
                AnalyzingPill(
                    visible = state.analyzing && state.image != null,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
            val batch = state.batch
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
                style = state.plan.style,
                degradeNote = state.degradeNote,
                onStyleChange = vm::setStyle,
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
