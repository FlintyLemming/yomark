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
            is EditorMessage.Error -> snackbar.showMessage(m.text, vm)
            null -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditorTopBar(
                canUndo = state.canUndo, canRedo = state.canRedo,
                onUndo = vm::undo, onRedo = vm::redo, onClose = onClose,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                ImageCanvas(
                    state = state,
                    registry = registry,
                    onTap = vm::onTap,
                    onDoubleTap = { _, _, _ -> },
                    onLongPress = vm::onLongPress,
                    onManualBox = vm::onManualBox,
                )
            }
            EditorBottomBar(
                style = state.plan.style,
                implemented = registry.implemented(),
                onStyleChange = vm::setStyle,
                onExport = { vm.requestExport(applyWatermark = true) },
                exporting = state.exporting,
            )
        }
    }
}

private suspend fun SnackbarHostState.showMessage(text: String, vm: EditorViewModel) {
    showSnackbar(text)
    vm.consumeMessage()
}
