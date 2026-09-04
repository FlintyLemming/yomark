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
import com.dama.app.data.SettingsStore
import com.dama.app.engine.RedactionEngine
import com.dama.app.export.ExportOutcome
import com.dama.app.export.ExportRequest
import com.dama.app.export.Exporter
import com.dama.app.render.EraseRenderer
import com.dama.app.ui.batch.BatchItem
import com.dama.app.ui.batch.BatchSession
import com.dama.app.ui.canvas.GestureRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(
    private val intake: ImageIntake,
    private val exporter: Exporter,
    private val engine: RedactionEngine,
    private val settings: SettingsStore,
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

    /** 用户这次会话里已经自己选过样式。选过之后就不再被「上次样式」的异步恢复覆盖。 */
    private var styleChosenByUser = false

    init {
        // 恢复出来的 plan 自带上一次会话的样式，比设置里的「上次样式」更贴近用户当下的上下文。
        if (!restore()) restoreLastStyle()
    }

    /**
     * 「记住上次样式」（spec §12 M5）。读设置是异步的，用户完全可能抢在它之前
     * 就点了样式栏——那时以用户的当次选择为准，不要把他刚点的样式改回去。
     */
    private fun restoreLastStyle() {
        viewModelScope.launch {
            val style = settings.lastStyle.first()
            if (styleChosenByUser) return@launch
            uiState = uiState.copy(plan = uiState.plan.copy(style = style))
        }
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
    private fun restore(): Boolean {
        val path = savedState.get<String>(KEY_FILE) ?: return false
        val mime = savedState.get<String>(KEY_MIME) ?: return false
        val plan = savedState.get<MaskPlan>(KEY_PLAN) ?: return false
        val file = java.io.File(path)
        if (!file.exists()) {
            clearSavedState()                          // 副本被清掉了，静默退回 Picker
            return false
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
        return true
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
     * 批量入口（spec §7.6）。单张时不开批量会话，行为与 onImageChosen 完全一致——
     * 「下一张」按钮只在真的有下一张时出现。
     */
    fun onImagesChosen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            onImageChosen(uris.first())
            return
        }
        // 私有副本只在会话开始时清一次。批量导出要在走完最后一张之后读回**每一张**的副本，
        // 像 onImageChosen 那样逐张 clear() 会把前面几张就地删掉，导出只剩最后一张。
        intake.clear()
        intakeResult = null
        undoStack.clear()
        uiState = EditorUiState(
            plan = MaskPlan.empty(_state.value.plan.style),
            batch = BatchSession(
                items = uris.map { BatchItem(it, null, "image/jpeg", null, 1f) },
                index = 0,
            ),
        )
        loadBatchCurrent()
    }

    /** 保存当前这张的编辑，推进到下一张。 */
    fun nextImage() {
        val batch = _state.value.batch ?: return
        val saved = batch.withPlan(_state.value.plan)
        if (saved.isLast) return
        uiState = _state.value.copy(batch = saved.advance())
        loadBatchCurrent()
    }

    /**
     * 批量里的每一张都走和单张一样的载入 + 识别路径。
     *
     * 批量会话不进 SavedStateHandle：进程被杀之后回来只恢复当前这一张
     * （restore() 那条路），批量退化成单张。这是有意的降级——把整个会话
     * 序列化回来，用户会面对一个「我刚才编到第几张了」完全说不清的状态。
     */
    private fun loadBatchCurrent() {
        val batch = _state.value.batch ?: return
        uiState = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            val loaded = runCatching {
                val taken = intake.copyToPrivate(batch.current.uri, ioDispatcher)
                taken to SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType, ioDispatcher)
            }.getOrNull()
            if (loaded == null) {
                uiState = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("无法打开这张图片"),
                )
                return@launch
            }
            val (taken, image) = loaded
            intakeResult = taken
            undoStack.clear()
            uiState = _state.value.copy(
                image = image,
                plan = MaskPlan.empty(_state.value.plan.style),
                loading = false,
                analyzing = true,
                canUndo = false,
                canRedo = false,
                selectedManualId = null,
                degradeNote = null,
                batch = _state.value.batch?.withLoaded(taken.file, taken.mimeType, image.scale),
            )
            analyze(image)
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
        styleChosenByUser = true
        mutate { it.copy(style = style) }
        _state.value = _state.value.copy(degradeNote = degradeNoteFor(style))
        viewModelScope.launch { settings.setLastStyle(style) }
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
        val degrading = masked.count { eraser.willDegrade(image.bitmap, it.quad, image.scale) }
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

    /**
     * 批量导出：走完最后一张后一次性导出全部（spec §7.6）。
     * 拦截按**全部图片**的待打码数之和判定，不是只看当前这张——
     * 只看当前这张的话，前面几张里被放过的 OUTLINED 就永远没有第二次机会了。
     */
    fun exportBatch(applyWatermark: Boolean) {
        val batch = _state.value.batch?.withPlan(_state.value.plan) ?: return
        uiState = _state.value.copy(batch = batch)
        if (batch.totalPending > 0) {
            uiState = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runBatchExport(batch, applyWatermark)
    }

    fun confirmMaskAllAndExport(applyWatermark: Boolean) {
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) {
            val cleared = batch.withPlan(_state.value.plan).maskAllEverywhere()
            uiState = _state.value.copy(
                batch = cleared,
                plan = cleared.current.plan ?: _state.value.plan.maskAll(),
            )
            runBatchExport(cleared, applyWatermark)
        } else {
            mutate { it.maskAll() }
            runExport(_state.value.plan, applyWatermark)
        }
    }

    fun confirmExportAnyway(applyWatermark: Boolean) {
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) runBatchExport(batch.withPlan(_state.value.plan), applyWatermark)
        else runExport(_state.value.plan, applyWatermark)
    }

    fun showPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = true) }
    fun dismissPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = false) }
    fun setPurposeText(text: String?) {
        _state.value = _state.value.copy(purposeText = text, purposeSheetVisible = false)
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
                    purposeText = _state.value.purposeText,
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

    private fun runBatchExport(batch: BatchSession, applyWatermark: Boolean) {
        uiState = _state.value.copy(exporting = true)
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            batch.items.forEach { item ->
                val file = item.file ?: return@forEach     // 还没走到的图没有副本，也没有编辑
                val plan = item.plan ?: return@forEach
                val outcome = exporter.export(
                    ExportRequest(
                        file = file,
                        mimeType = item.mimeType,
                        plan = plan,
                        analysisScale = item.analysisScale,
                        applyWatermark = applyWatermark,
                        purposeText = _state.value.purposeText,
                    ),
                    dispatcher = ioDispatcher,
                )
                if (outcome is ExportOutcome.Success) ok++ else failed++
            }
            uiState = _state.value.copy(
                exporting = false,
                message = if (failed == 0) EditorMessage.BatchExported(ok)
                else EditorMessage.Error("$ok 张已保存，$failed 张失败"),
            )
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
