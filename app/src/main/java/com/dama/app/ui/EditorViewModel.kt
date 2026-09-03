package com.dama.app.ui

import android.graphics.PointF
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.image.IntakeResult
import com.dama.app.core.image.SourceImageLoader
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.MaskItem
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskState
import com.dama.app.core.model.MaskStyle
import com.dama.app.core.model.SensitiveKind
import com.dama.app.export.ExportOutcome
import com.dama.app.export.ExportRequest
import com.dama.app.export.Exporter
import com.dama.app.ui.canvas.GestureRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(
    private val intake: ImageIntake,
    private val exporter: Exporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val undoStack = UndoStack()
    private var intakeResult: IntakeResult? = null

    // ---------- 载入 ----------

    fun onImageChosen(uri: Uri) {
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            runCatching {
                val result = intake.copyToPrivate(uri, ioDispatcher)
                val image = SourceImageLoader.loadForAnalysis(result.file, result.mimeType, ioDispatcher)
                result to image
            }.onSuccess { (result, image) ->
                intakeResult = result
                undoStack.clear()                     // 换图时两个栈都清空
                _state.value = EditorUiState(image = image, plan = MaskPlan.empty(_state.value.plan.style))
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("无法打开这张图片"),
                )
            }
        }
    }

    // ---------- 编辑 ----------

    fun onTap(imagePoint: PointF) {
        val plan = _state.value.plan
        val hit = GestureRules.hitTest(plan.items, imagePoint)
        if (hit == null) {
            _state.value = _state.value.copy(selectedManualId = null)
            return
        }
        mutate { it.toggle(hit.candidateId) }
    }

    fun onManualBox(quadInImageSpace: Quad) {
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quadInImageSpace,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            state = MaskState.MASKED,          // 落笔即打码
        )
        mutate { it.add(item) }
    }

    fun onLongPress(imagePoint: PointF) {
        val hit = GestureRules.hitTest(_state.value.plan.items, imagePoint)
        _state.value = _state.value.copy(selectedManualId = hit?.candidateId)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedManualId = null)
    }

    /** 只有手动框删得掉；MaskPlan.remove 自己会拦住其余来源。 */
    fun deleteSelected() {
        val id = _state.value.selectedManualId ?: return
        mutate { it.remove(id) }
        _state.value = _state.value.copy(selectedManualId = null)
    }

    fun moveSelected(quad: Quad) {
        val id = _state.value.selectedManualId ?: return
        val item = _state.value.plan.find(id) ?: return
        mutate { it.replace(item.copy(quad = quad)) }
    }

    fun setStyle(style: MaskStyle) = mutate { it.copy(style = style) }

    fun undo() {
        val restored = undoStack.undo(_state.value.plan) ?: return
        _state.value = _state.value.copy(plan = restored).withHistoryFlags()
    }

    fun redo() {
        val restored = undoStack.redo(_state.value.plan) ?: return
        _state.value = _state.value.copy(plan = restored).withHistoryFlags()
    }

    // ---------- 导出 ----------

    /**
     * 导出拦截（spec §7.4）：pendingCount > 0 时**无例外**先弹对话框。
     * 这是分层默认的唯一安全网，不提供「不再提示」。
     */
    fun requestExport(applyWatermark: Boolean) {
        if (_state.value.plan.pendingCount > 0) {
            _state.value = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmMaskAllAndExport(applyWatermark: Boolean) {
        mutate { it.maskAll() }
        _state.value = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmExportAnyway(applyWatermark: Boolean) {
        _state.value = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(pendingDialogVisible = false)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun runExport(plan: MaskPlan, applyWatermark: Boolean) {
        val image = _state.value.image ?: return
        val source = intakeResult ?: return
        _state.value = _state.value.copy(exporting = true)
        viewModelScope.launch {
            val outcome = exporter.export(
                ExportRequest(
                    file = source.file,
                    mimeType = source.mimeType,
                    plan = plan,
                    analysisScale = image.scale,
                    applyWatermark = applyWatermark,
                ),
                dispatcher = ioDispatcher,
            )
            _state.value = when (outcome) {
                is ExportOutcome.Success -> _state.value.copy(
                    exporting = false,
                    message = EditorMessage.Exported(outcome.uri, outcome.downscaled, outcome.width, outcome.height),
                )
                is ExportOutcome.Failure -> _state.value.copy(
                    exporting = false,
                    message = EditorMessage.Error("导出失败，请重试"),
                )
            }
        }
    }

    override fun onCleared() {
        intake.clear()          // 私有副本在 Activity 销毁时删除
        super.onCleared()
    }

    // ---------- 内部 ----------

    /** 所有改动都先把当前 plan 压进撤销栈，再替换。 */
    private inline fun mutate(block: (MaskPlan) -> MaskPlan) {
        val current = _state.value.plan
        val next = block(current)
        if (next == current) return
        undoStack.push(current)
        _state.value = _state.value.copy(plan = next).withHistoryFlags()
    }

    private fun EditorUiState.withHistoryFlags() =
        copy(canUndo = undoStack.canUndo, canRedo = undoStack.canRedo)

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        _state.value = _state.value.copy(plan = plan)
    }
}
