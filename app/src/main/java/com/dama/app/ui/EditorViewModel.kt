package com.dama.app.ui

import android.graphics.PointF
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.image.IntakeResult
import com.dama.app.core.image.SourceImage
import com.dama.app.core.image.SourceImageLoader
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.MaskItem
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskPlanFactory
import com.dama.app.core.model.MaskState
import com.dama.app.core.model.MaskStyle
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.RedactionEngine
import com.dama.app.export.ExportOutcome
import com.dama.app.export.ExportRequest
import com.dama.app.export.Exporter
import com.dama.app.render.EraseRenderer
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
    private val engine: RedactionEngine,
    private val savedState: SavedStateHandle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /**
     * 唯一的状态写入口。每次写都把 plan 镜像进 SavedStateHandle——
     * 持久化因此不依赖「记得在每个改动点补一行」，加新的编辑动作也不会漏掉。
     */
    private var uiState: EditorUiState
        get() = _state.value
        set(value) {
            _state.value = value
            savedState[KEY_PLAN] = value.plan
        }

    private val undoStack = UndoStack()
    private var intakeResult: IntakeResult? = null
        set(value) {
            field = value
            savedState[KEY_FILE] = value?.file?.absolutePath
            savedState[KEY_MIME] = value?.mimeType
        }

    /** 一次拖动开始前的 plan 快照，抬手时才压进撤销栈。 */
    private var dragOrigin: MaskPlan? = null

    init {
        restore()
    }

    // ---------- 载入 ----------

    /**
     * Activity 重建后的恢复（spec §15 第 2 条的实测缺口）。
     *
     * 「不保留活动」下 Activity 被销毁时 ViewModel 一并 onCleared，
     * 之前的版本因此在返回时丢图、退回 Photo Picker。私有副本的路径与整棵
     * MaskPlan 都进了 SavedStateHandle，这里直接把结果读回来——
     * **不重跑识别**：候选是已经确定的事实，重算既慢又可能和用户已做的编辑打架。
     *
     * 撤销栈不持久化。它是「这次会话里做过什么」，重建之后那段上下文
     * 对用户已经不成立了，恢复出来反而会撤销到一个他没见过的状态。
     */
    private fun restore() {
        val path = savedState.get<String>(KEY_FILE) ?: return
        val mime = savedState.get<String>(KEY_MIME) ?: return
        val plan = savedState.get<MaskPlan>(KEY_PLAN) ?: return
        val file = java.io.File(path)
        if (!file.exists()) {
            clearSavedState()                          // 副本被清掉了，静默退回 Picker
            return
        }
        uiState = uiState.copy(restoring = true)
        viewModelScope.launch {
            val image = runCatching {
                SourceImageLoader.loadForAnalysis(file, mime, ioDispatcher)
            }.getOrElse {
                clearSavedState()
                uiState = uiState.copy(restoring = false)
                return@launch
            }
            intakeResult = IntakeResult(file, mime)
            uiState = EditorUiState(image = image, plan = plan)
        }
    }

    private fun clearSavedState() {
        savedState.remove<String>(KEY_FILE)
        savedState.remove<String>(KEY_MIME)
        savedState.remove<MaskPlan>(KEY_PLAN)
    }

    fun onImageChosen(uri: Uri) {
        uiState = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            // 换图时先清掉上一张的私有副本。清理时机从 onCleared 挪到这里：
            // onCleared 会在 Activity 重建时把副本一起删掉，恢复就无从谈起了
            // （spec §15 第 2 条）。
            intake.clear()
            intakeResult = null
            runCatching {
                val result = intake.copyToPrivate(uri, ioDispatcher)
                val image = SourceImageLoader.loadForAnalysis(result.file, result.mimeType, ioDispatcher)
                result to image
            }.onSuccess { (result, image) ->
                intakeResult = result
                undoStack.clear()                     // 换图时两个栈都清空
                uiState = EditorUiState(
                    image = image,
                    plan = MaskPlan.empty(_state.value.plan.style),
                    analyzing = true,
                )
                analyze(image)
            }.onFailure {
                uiState = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("无法打开这张图片"),
                )
            }
        }
    }

    /**
     * 识别（spec §7.1）：选中即进编辑器，候选已按规则表的默认状态打好码，
     * 不是「等你逐个确认」。分析期间画布可交互，结果到达时并入现有 plan。
     */
    private suspend fun analyze(image: SourceImage) {
        val candidates = runCatching { engine.analyze(image).candidates }.getOrElse { emptyList() }
        val detected = MaskPlanFactory.itemsFrom(candidates)
        // 分析结果不进撤销栈——它是初始状态，不是用户动作。
        // 用户在分析期间画的手动框排在后面，不被覆盖。
        val current = _state.value
        if (current.image !== image) return            // 用户已经换了图，丢弃这批结果
        uiState = current.copy(
            analyzing = false,
            plan = current.plan.copy(items = detected + current.plan.items),
        )
    }

    // ---------- 编辑 ----------

    fun onTap(imagePoint: PointF) {
        val plan = _state.value.plan
        val hit = GestureRules.hitTest(plan.items, imagePoint)
        if (hit == null) {
            uiState = _state.value.copy(selectedManualId = null)
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
        uiState = _state.value.copy(selectedManualId = hit?.candidateId)
    }

    fun clearSelection() {
        uiState = _state.value.copy(selectedManualId = null)
    }

    /** 只有手动框删得掉；MaskPlan.remove 自己会拦住其余来源。 */
    fun deleteSelected() {
        val id = _state.value.selectedManualId ?: return
        mutate { it.remove(id) }
        uiState = _state.value.copy(selectedManualId = null)
    }

    /**
     * 拖动期间只改状态不压栈——每帧压一次会往撤销栈里塞几十个快照，
     * 用户想撤销一次拖动就得按几十次。抬手时由 commitDrag() 补一次。
     */
    fun previewSelectedQuad(quad: Quad) {
        val id = _state.value.selectedManualId ?: return
        val item = _state.value.plan.find(id) ?: return
        if (dragOrigin == null) dragOrigin = _state.value.plan
        uiState = _state.value.copy(plan = _state.value.plan.replace(item.copy(quad = quad)))
    }

    fun commitDrag() {
        val origin = dragOrigin ?: return
        dragOrigin = null
        if (origin == _state.value.plan) return
        undoStack.push(origin)
        uiState = _state.value.withHistoryFlags()
    }

    fun setStyle(style: MaskStyle) {
        mutate { it.copy(style = style) }
        _state.value = _state.value.copy(degradeNote = degradeNoteFor(style))
    }

    /**
     * 抹除在复杂背景上会降级为实色块（spec §8）。用户需要知道为什么，
     * 而不是导出后发现「怎么和预览不一样」。
     */
    private fun degradeNoteFor(style: MaskStyle): String? {
        if (style != MaskStyle.ERASE) return null
        val image = _state.value.image ?: return null
        val eraser = EraseRenderer()
        val masked = _state.value.plan.items.filter { it.state == MaskState.MASKED }
        if (masked.isEmpty()) return null
        val degrading = masked.count { eraser.willDegrade(image.bitmap, it.quad) }
        return if (degrading == 0) null
        else "$degrading 处背景过于复杂，抹除已自动降级为实色块"
    }

    fun undo() {
        val restored = undoStack.undo(_state.value.plan) ?: return
        uiState = _state.value.copy(plan = restored).withHistoryFlags()
    }

    fun redo() {
        val restored = undoStack.redo(_state.value.plan) ?: return
        uiState = _state.value.copy(plan = restored).withHistoryFlags()
    }

    // ---------- 导出 ----------

    /**
     * 导出拦截（spec §7.4）：pendingCount > 0 时**无例外**先弹对话框。
     * 这是分层默认的唯一安全网，不提供「不再提示」。
     */
    fun requestExport(applyWatermark: Boolean) {
        if (_state.value.plan.pendingCount > 0) {
            uiState = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmMaskAllAndExport(applyWatermark: Boolean) {
        mutate { it.maskAll() }
        uiState = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmExportAnyway(applyWatermark: Boolean) {
        uiState = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun dismissDialog() {
        uiState = _state.value.copy(pendingDialogVisible = false)
    }

    fun consumeMessage() {
        uiState = _state.value.copy(message = null)
    }

    private fun runExport(plan: MaskPlan, applyWatermark: Boolean) {
        val image = _state.value.image ?: return
        val source = intakeResult ?: return
        uiState = _state.value.copy(exporting = true)
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
            uiState = when (outcome) {
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

    // ---------- 内部 ----------

    /** 所有改动都先把当前 plan 压进撤销栈，再替换。 */
    private inline fun mutate(block: (MaskPlan) -> MaskPlan) {
        val current = _state.value.plan
        val next = block(current)
        if (next == current) return
        undoStack.push(current)
        uiState = _state.value.copy(plan = next).withHistoryFlags()
    }

    private fun EditorUiState.withHistoryFlags() =
        copy(canUndo = undoStack.canUndo, canRedo = undoStack.canRedo)

    private companion object {
        const val KEY_FILE = "intake.file"
        const val KEY_MIME = "intake.mime"
        const val KEY_PLAN = "plan"
    }

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        uiState = _state.value.copy(plan = plan)
    }
}
