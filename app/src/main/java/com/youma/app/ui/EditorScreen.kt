package com.youma.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.canvas.ImageCanvas
import com.youma.app.ui.components.EditorBottomBar
import com.youma.app.ui.components.EditorTopBar
import com.youma.app.ui.components.PaywallDialog
import com.youma.app.ui.components.PendingExportDialog
import com.youma.app.ui.components.PurposeWatermarkSheet
import com.youma.app.ui.settings.RecognitionSettingsScreen

@Composable
fun EditorScreen(vm: EditorViewModel, onBuyClicked: () -> Unit = {}, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val registry = remember { RendererRegistry.default() }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        when (val m = state.message) {
            is EditorMessage.Exported -> {
                val extra = if (m.downscaled) "（原图过大，已压缩至 ${m.width}×${m.height}）" else ""
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
        PaywallDialog(onBuy = onBuyClicked, onDismiss = vm::dismissPaywall)
    }

    if (state.purposeSheetVisible) {
        PurposeWatermarkSheet(
            initial = state.purposeText,
            onConfirm = vm::setPurposeText,
            onDismiss = vm::dismissPurposeSheet,
        )
    }

    // 设置是整屏页而不是对话框：13 条规则各三个选项，对话框塞不下。
    if (state.settingsVisible) {
        RecognitionSettingsScreen(
            config = state.recognitionConfig,
            onChange = vm::setRecognitionConfig,
            onBack = vm::dismissSettings,
        )
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditorTopBar(
                canUndo = state.canUndo, canRedo = state.canRedo,
                onUndo = vm::undo, onRedo = vm::redo,
                onPurpose = vm::showPurposeSheet,
                onSettings = vm::showSettings,
                // 已购用户不该再看见购买入口
                onRemoveWatermark = if (state.isPro) null else vm::showPaywall,
                onClose = onClose,
            )
            if (state.aiReview == AiReview.RUNNING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Gemini Nano 正在复查整页文字，新发现的会以「AI」标签圈出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
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
            }
            val batch = state.batch
            EditorBottomBar(
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

private suspend fun SnackbarHostState.showMessage(text: String, vm: EditorViewModel) {
    showSnackbar(text)
    vm.consumeMessage()
}
