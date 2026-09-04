package com.dama.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dama.app.render.RendererRegistry
import com.dama.app.ui.canvas.ImageCanvas
import com.dama.app.ui.components.EditorBottomBar
import com.dama.app.ui.components.EditorTopBar
import com.dama.app.ui.components.PendingExportDialog
import com.dama.app.ui.components.PurposeWatermarkSheet

@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
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
            onMaskAll = { vm.confirmMaskAllAndExport(applyWatermark = true) },
            onExportAnyway = { vm.confirmExportAnyway(applyWatermark = true) },
            onDismiss = vm::dismissDialog,
        )
    }

    if (state.purposeSheetVisible) {
        PurposeWatermarkSheet(
            initial = state.purposeText,
            onConfirm = vm::setPurposeText,
            onDismiss = vm::dismissPurposeSheet,
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditorTopBar(
                canUndo = state.canUndo, canRedo = state.canRedo,
                onUndo = vm::undo, onRedo = vm::redo,
                onPurpose = vm::showPurposeSheet, onClose = onClose,
            )
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
                    if (batch != null) vm.exportBatch(applyWatermark = true)
                    else vm.requestExport(applyWatermark = true)
                },
                exporting = state.exporting,
                batchLabel = batch?.let { "${it.index + 1} / ${it.total}" },
                onNext = if (batch != null && !batch.isLast) vm::nextImage else null,
            )
        }
    }
}

private suspend fun SnackbarHostState.showMessage(text: String, vm: EditorViewModel) {
    showSnackbar(text)
    vm.consumeMessage()
}
