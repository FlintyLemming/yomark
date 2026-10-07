package com.youma.app.ui

import android.graphics.PointF
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.image.IntakeResult
import com.youma.app.core.image.SourceImage
import com.youma.app.core.image.SourceImageLoader
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskPlanFactory
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextLine
import com.youma.app.data.SettingsStore
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RedactionEngine
import com.youma.app.export.ExportOutcome
import com.youma.app.export.ExportRequest
import com.youma.app.export.Exporter
import com.youma.app.export.PurposeWatermarkStyle
import com.youma.app.render.EraseRenderer
import com.youma.app.ui.batch.BatchItem
import com.youma.app.ui.batch.BatchSession
import com.youma.app.ui.canvas.GestureRules
import com.youma.app.ui.canvas.ScanStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class EditorViewModel(
    private val intake: ImageIntake,
    private val exporter: Exporter,
    /**
     * config 变了就丢掉旧 engine 建新的（2026-09-04 增补设计 §2）。
     * ML Kit 的 client 全是 lazy，没被选中的后端不会初始化。
     */
    private val engineProvider: (RecognitionConfig) -> RedactionEngine,
    private val settings: SettingsStore,
    private val savedState: SavedStateHandle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /**
     * 识别动效的样式（设置里选的）。只影响画布上的过渡，所以不进 EditorUiState——那里每换一张图就整个重建，
     * 购买态、水印外观都得一路手动带着；这个直接跟着设置走。设置还没读到时按出厂的扫光算。
     */
    val scanStyle: StateFlow<ScanStyle> =
        settings.scanStyle.stateIn(viewModelScope, SharingStarted.Eagerly, ScanStyle.SWEEP)

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

    private var config = RecognitionConfig()
    private var engine: RedactionEngine = engineProvider(config)

    /** 当前 plan 是用哪套 config 跑出来的。null = 还没跑过识别。 */
    private var analyzedWith: RecognitionConfig? = null
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

    /** 用途水印外观同理：用户这次调过，就不再被持久化值的异步恢复覆盖。 */
    private var purposeStyleTouched = false
    private var purposeStyleSave: Job? = null

    /** 这次会话里最后一次非空的用途水印文案。只在内存里，不落盘。 */
    private var lastPurposeText: String? = null

    /** 导出前提醒的开关。设置还没读到时按开着算——宁可多问一次。 */
    private var exportReminder = true

    init {
        // 恢复出来的 plan 自带上一次会话的样式，比设置里的「上次样式」更贴近用户当下的上下文。
        if (!restore()) restoreLastStyle()
        restorePurposeStyle()
        observeRecognitionConfig()
        viewModelScope.launch { settings.pendingExportReminder.collect { exportReminder = it } }
    }

    /**
     * 设置页改动即时生效：写 DataStore，这里收到就换引擎并重跑（增补设计 §5、§6）。
     *
     * 重跑的条件是「当前 plan 是用另一套 config 跑出来的」，不是「这是第几次收到」。
     * 冷启动时持久化的 config 可能比第一张图晚到，用次数判断会让第一张图用错方案。
     */
    private fun observeRecognitionConfig() {
        viewModelScope.launch {
            settings.recognitionConfig.collect { incoming ->
                if (incoming == config) return@collect
                config = incoming
                engine = engineProvider(incoming)
                uiState = uiState.copy(recognitionConfig = incoming)
                val image = _state.value.image ?: return@collect
                if (analyzedWith != null && analyzedWith != incoming) reanalyze(image)
            }
        }
    }

    fun setRecognitionConfig(next: RecognitionConfig) {
        viewModelScope.launch { settings.setRecognitionConfig(next) }
    }

    /**
     * 换方案后的重跑。整次重跑压**一个**快照，用户不满意按一下撤销就回到切换前，
     * 这就是「切着看」这件事成立的全部依据。
     *
     * 丢掉的是用户对规则候选做过的三态切换：新方案产出的候选和旧的不是同一批，
     * 把旧的状态往新候选上贴没有正确答案。手动框留着——那是用户自己画的。
     */
    private fun reanalyze(image: SourceImage) {
        undoStack.push(_state.value.plan)
        uiState = _state.value.copy(analyzing = true, aiReview = AiReview.HIDDEN).withHistoryFlags()
        viewModelScope.launch { analyze(image, keepOnlyManual = true) }
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

    /** 与 restoreLastStyle 同理：持久化的外观晚到时，不覆盖用户这次已经调过的。 */
    private fun restorePurposeStyle() {
        viewModelScope.launch {
            val style = settings.purposeWatermarkStyle.first()
            if (purposeStyleTouched) return@launch
            _state.value = _state.value.copy(purposeStyle = style)
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
            uiState = EditorUiState(
                image = image, plan = plan,
                isPro = _state.value.isPro, purposeStyle = _state.value.purposeStyle,
            )
        }
        return true
    }

    private fun clearSavedState() {
        savedState.remove<String>(KEY_FILE)
        savedState.remove<String>(KEY_MIME)
        savedState.remove<MaskPlan>(KEY_PLAN)
    }

    fun onImageChosen(uri: Uri) {
        aiReviewJob?.cancel()                             // 上一张图的复查作废，按钮随之收起
        uiState = _state.value.copy(loading = true, message = null, aiReview = AiReview.HIDDEN)
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
                    // 换图是换一张图，不是换一个人：购买态必须活过每一次 EditorUiState 重建。
                    isPro = _state.value.isPro,
                    purposeStyle = _state.value.purposeStyle,
                )
                analyze(image)
            }.onFailure {
                uiState = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("打不开这张图片"),
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
            isPro = _state.value.isPro,
            purposeStyle = _state.value.purposeStyle,
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
        aiReviewJob?.cancel()
        uiState = _state.value.copy(loading = true, message = null, aiReview = AiReview.HIDDEN)
        viewModelScope.launch {
            val loaded = runCatching {
                val taken = intake.copyToPrivate(batch.current.uri, ioDispatcher)
                taken to SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType, ioDispatcher)
            }.getOrNull()
            if (loaded == null) {
                uiState = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("打不开这张图片"),
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
                aiReview = AiReview.HIDDEN,
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
    private suspend fun analyze(image: SourceImage, keepOnlyManual: Boolean = false) {
        val ranWith = config
        val ranOn = engine
        aiReviewJob?.cancel()
        val result = runCatching { ranOn.analyze(image) }.getOrNull()
        val detected = MaskPlanFactory.itemsFrom(result?.candidates.orEmpty())
        // 分析结果不进撤销栈——它是初始状态，不是用户动作。
        // 用户在分析期间画的手动框排在后面，不被覆盖。
        val current = _state.value
        if (current.image !== image) return            // 用户已经换了图，丢弃这批结果
        val kept =
            if (keepOnlyManual) current.plan.items.filter { it.kind == SensitiveKind.MANUAL }
            else current.plan.items
        analyzedWith = ranWith
        reviewLines = result?.lines
        uiState = current.copy(
            analyzing = false,
            aiReview = AiReview.HIDDEN,
            plan = current.plan.copy(items = detected + kept),
        )
        if (result != null && ranOn.canRefine) offerAiReview(image, ranWith, ranOn)
    }

    /** 最近一次识别出的整页文字，「AI 复查」要把它送给模型。只活在内存里，重建后就没有了。 */
    private var reviewLines: List<TextLine>? = null

    private var aiReviewJob: Job? = null

    /**
     * 「AI 复查」不再自动跑（PP-OCR 加规则的结果已经够用，自动再跑一遍要多等好几秒）。
     * 这里只探测模型在不在：在，按钮才出现；机型不支持就什么都不出现。
     */
    private fun offerAiReview(image: SourceImage, ranWith: RecognitionConfig, ranOn: RedactionEngine) {
        aiReviewJob = viewModelScope.launch {
            if (!ranOn.refineReady()) return@launch
            val current = _state.value
            if (current.image !== image || analyzedWith != ranWith || current.analyzing) return@launch
            if (current.aiReview != AiReview.HIDDEN) return@launch
            uiState = current.copy(aiReview = AiReview.READY)
        }
    }

    /**
     * 用户点了「AI 复查」：端侧大模型（Gemini Nano）把整页文字再看一遍，
     * 新发现的追加进 plan，只圈出、标「AI」。
     *
     * 是用户点出来的，所以进撤销栈——不想要这批框，撤销一下就整批拿掉。
     * 这期间换了图、换了方案，这一批就作废。
     */
    fun runAiReview() {
        val current = _state.value
        if (current.aiReview != AiReview.READY) return
        val image = current.image ?: return
        val lines = reviewLines ?: return
        val ranWith = analyzedWith ?: return
        val ranOn = engine
        uiState = current.copy(aiReview = AiReview.RUNNING)
        aiReviewJob = viewModelScope.launch {
            val extra = ranOn.refine(lines, current.plan.items.map { it.quad })
            ensureActive()
            val now = _state.value
            if (now.image !== image || analyzedWith != ranWith) return@launch
            if (extra == null) {
                uiState = now.copy(
                    aiReview = AiReview.READY,
                    message = EditorMessage.Error("AI 复查失败，请重试"),
                )
                return@launch
            }
            val known = now.plan.items.mapTo(HashSet()) { it.candidateId }
            val added = MaskPlanFactory.itemsFrom(extra).filter { it.candidateId !in known }
            if (added.isNotEmpty()) undoStack.push(now.plan)
            uiState = now.copy(
                aiReview = AiReview.DONE,
                plan = now.plan.copy(items = now.plan.items + added),
                message = EditorMessage.Notice(
                    if (added.isEmpty()) "AI 复查没有发现新内容"
                    else "AI 复查找到 ${added.size} 处，已圈出，没有打码"
                ),
            ).withHistoryFlags()
        }
    }

    // ---------- 编辑 ----------

    /**
     * 点一下：识别出的候选在 MASKED ⇄ OUTLINED 之间切换；手动框不切换，而是选中它，调出手柄与删除。
     * 手动框是用户自己画的，画它就是要打码，不想要就删掉——点它要是把码去掉了，
     * 「点一下再调一调」就成了「点一下码没了」。
     */
    fun onTap(imagePoint: PointF) {
        val hit = GestureRules.hitTest(_state.value.plan.items, imagePoint)
        if (hit?.source == DetectorSource.MANUAL) {
            uiState = _state.value.copy(selectedManualId = hit.candidateId)
            return
        }
        // 点空白、点候选都算点了别处：选中态收起
        if (hit != null) mutate { it.toggle(hit.candidateId) }
        uiState = _state.value.copy(selectedManualId = null)
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
        // 画完直接进选中态：手柄就在手边，不用再点一下才能调大小
        uiState = _state.value.copy(selectedManualId = item.candidateId)
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
        else "$degrading 处背景太复杂，已改用色块"
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
     * 导出拦截（spec §7.4）：pendingCount > 0 时先弹对话框。
     * 用户可以在对话框里勾「不再提示」，或在规则页关掉；关了就直接导出，圈出的照原样留着。
     */
    fun requestExport() {
        if (exportReminder && _state.value.plan.pendingCount > 0) {
            uiState = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runExport(_state.value.plan)
    }

    /**
     * 批量导出：走完最后一张后一次性导出全部（spec §7.6）。
     * 拦截按**全部图片**的待打码数之和判定，不是只看当前这张——
     * 只看当前这张的话，前面几张里被放过的 OUTLINED 就永远没有第二次机会了。
     */
    fun exportBatch() {
        val batch = _state.value.batch?.withPlan(_state.value.plan) ?: return
        uiState = _state.value.copy(batch = batch)
        if (exportReminder && batch.totalPending > 0) {
            uiState = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runBatchExport(batch)
    }

    /** @param stopReminding 对话框里勾了「不再提示」。 */
    fun confirmMaskAllAndExport(stopReminding: Boolean = false) {
        if (stopReminding) stopExportReminder()
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) {
            val cleared = batch.withPlan(_state.value.plan).maskAllEverywhere()
            uiState = _state.value.copy(
                batch = cleared,
                plan = cleared.current.plan ?: _state.value.plan.maskAll(),
            )
            runBatchExport(cleared)
        } else {
            mutate { it.maskAll() }
            runExport(_state.value.plan)
        }
    }

    fun confirmExportAnyway(stopReminding: Boolean = false) {
        if (stopReminding) stopExportReminder()
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) runBatchExport(batch.withPlan(_state.value.plan))
        else runExport(_state.value.plan)
    }

    /** 本地先改掉，不等 DataStore 回流：紧接着的下一次导出就不该再问。 */
    private fun stopExportReminder() {
        exportReminder = false
        viewModelScope.launch { settings.setPendingExportReminder(false) }
    }

    /** Activity 在 onCreate 里把 BillingRepository.isPro 接进来。 */
    fun observePro(flow: StateFlow<Boolean>) {
        viewModelScope.launch {
            flow.collect { pro -> _state.value = _state.value.copy(isPro = pro) }
        }
    }

    fun showPaywall() { _state.value = _state.value.copy(paywallVisible = true) }
    fun dismissPaywall() { _state.value = _state.value.copy(paywallVisible = false) }

    // ---------- 用途水印 ----------

    /**
     * 打开调节面板即打开水印：还没设文案时先填上默认措辞，画布上马上就能看到效果，
     * 用户改中间那几个字即可。不想要就点面板上的「移除」。
     */
    fun showPurposeSheet() {
        val s = _state.value
        _state.value = s.copy(
            purposeSheetVisible = true,
            purposeText = s.purposeText ?: lastPurposeText ?: DEFAULT_PURPOSE_TEXT,
        )
    }

    /** 收起面板，水印保持现状。 */
    fun dismissPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = false) }

    /** 边打字边生效。清空就等于暂时不加，面板不收，接着打字又回来。 */
    fun setPurposeText(text: String?) {
        val t = text?.takeIf { it.isNotBlank() }
        if (t != null) lastPurposeText = t
        _state.value = _state.value.copy(purposeText = t)
    }

    /** 移除水印并收起面板。文案记在内存里，这次会话里再打开面板还是它。 */
    fun removePurposeWatermark() {
        _state.value = _state.value.copy(purposeText = null, purposeSheetVisible = false)
    }

    /**
     * 拖滑条时每一帧都会进来：状态即时更新给画布预览，落盘做一次去抖，
     * 不让 DataStore 跟着滑条每帧写一次。
     */
    fun setPurposeStyle(style: PurposeWatermarkStyle) {
        val next = style.normalized()
        purposeStyleTouched = true
        if (next == _state.value.purposeStyle) return
        _state.value = _state.value.copy(purposeStyle = next)
        purposeStyleSave?.cancel()
        purposeStyleSave = viewModelScope.launch {
            delay(PURPOSE_STYLE_SAVE_DEBOUNCE_MS)
            settings.setPurposeWatermarkStyle(next)
        }
    }

    fun resetPurposeStyle() = setPurposeStyle(PurposeWatermarkStyle())

    fun dismissDialog() {
        uiState = _state.value.copy(pendingDialogVisible = false)
    }

    fun consumeMessage() {
        uiState = _state.value.copy(message = null)
    }

    /**
     * 水印只由购买态决定，调用点一律不传（spec §10）。
     * 留一个 applyWatermark 参数就等于留了一条「某个入口忘了接购买态」的路。
     */
    private fun applyWatermark(): Boolean = !_state.value.isPro

    private fun runExport(plan: MaskPlan) {
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
                    applyWatermark = applyWatermark(),
                    purposeText = _state.value.purposeText,
                    purposeStyle = _state.value.purposeStyle,
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

    private fun runBatchExport(batch: BatchSession) {
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
                        applyWatermark = applyWatermark(),
                        purposeText = _state.value.purposeText,
                        purposeStyle = _state.value.purposeStyle,
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
        const val DEFAULT_PURPOSE_TEXT = "仅供办理 XX 使用"
        const val PURPOSE_STYLE_SAVE_DEBOUNCE_MS = 400L
    }

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        uiState = _state.value.copy(plan = plan)
    }
}
