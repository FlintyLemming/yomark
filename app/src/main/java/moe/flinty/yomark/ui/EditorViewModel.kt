package moe.flinty.yomark.ui

import android.graphics.PointF
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.IntakeResult
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.image.SourceImageLoader
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskPlanFactory
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.debug.TraceRecorder
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.export.brandWatermarkApplies
import moe.flinty.yomark.export.ExportOutcome
import moe.flinty.yomark.export.ExportRequest
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.batch.BatchItem
import moe.flinty.yomark.ui.batch.BatchSession
import moe.flinty.yomark.ui.canvas.GestureRules
import moe.flinty.yomark.ui.canvas.ScanStyle
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min
import moe.flinty.yomark.R

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
    /**
     * 调试记录（见 debug/Trace.kt）。应用里传进程共用、落盘的那一份（sharedTraceLog），
     * 换一张图、关掉编辑页再开都还在；单测里各用各的、只在内存里。
     */
    val traceLog: TraceLog = TraceLog(),
    /** 用途水印第一次打开时填的文案，按系统语言给（Activity 传 R.string.purpose_default）。 */
    private val defaultPurposeText: String = DEFAULT_PURPOSE_TEXT,
    /**
     * 别的应用来要图（见 PickImageActivity）：导出成功不提示「已保存到相册」，而是交回给它（[EditorMessage.HandedOver]），
     * 编辑器随之关掉。导出到哪儿由 Activity 给的 [exporter] 定。
     */
    val forCaller: Boolean = false,
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
     * 调试信息开着没有（设置 → 关于，连点版本号）。开着时顶栏多一个按钮，每次识别都在 [traceLog] 里记一份。
     * 不进 EditorUiState，理由同 [scanStyle]。关掉时已经记下的一并清掉。
     */
    val debugTrace: StateFlow<Boolean> = settings.debugTrace
        .onEach { on -> if (!on) traceLog.clear() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /** 画布上这张图的撤销栈。批量里每一张各有一个，切图时换（见 [batchUndo]）。 */
    private var undoStack = UndoStack()

    /** 批量里不在画布上的那几张的撤销栈。来回切着看，回到一张图上还能接着撤销它自己的改动。 */
    private val batchUndo = HashMap<Int, UndoStack>()

    /** 批量里每一张的识别记录，按下标存，只在内存里。 */
    private val batchScans = HashMap<Int, BatchScan>()

    /** 识别的流水号，只增不减。结果回来时对得上号才算数：同一张后来又重跑了、或者整批换了，旧结果自然作废。 */
    private var scanSerial = 0

    /** 批量里正在载入的那一张。又切到别的图时作废它。 */
    private var batchLoad: Job? = null

    private class BatchScan {
        /** 最近一次识别。切到别的图上它也接着跑完，结果落回这一张自己的 plan。 */
        var job: Job? = null
        var serial = 0
        /** 跑出眼下这份结果的方案。null = 还没跑完过。 */
        var ranWith: RecognitionConfig? = null
        /** 识别出的整页文字，「AI 增强识别」要用。 */
        var lines: List<TextLine>? = null
        /** 切走时增强识别到了哪一步，切回来照原样显示。跑到一半切走的算没跑过。 */
        var aiReview: AiReview = AiReview.Hidden
    }

    /** 一次识别属于批量里的哪一张、是第几次。 */
    private class BatchTarget(val index: Int, val serial: Int)

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

    /** 面板上一次连续调节（拖滑条）开始前的 plan 快照，松手时才压进撤销栈。与 dragOrigin 同理。 */
    private var lookEditOrigin: MaskPlan? = null

    /** 用户这次会话里已经自己动过画笔。动过之后就不再被持久化值的异步恢复覆盖。 */
    private var brushTouched = false
    private var brushSave: Job? = null

    /**
     * 画笔的持久化值读回来了没有。识别结果要等它：冷启动时第一张图的结果要是抢在它前面到，
     * 打上的码就成了出厂的天蓝色块，而不是用户上次调好的样子。读 DataStore 只要几毫秒，识别要一两秒，实际上不用等。
     */
    private val brushRestored: Job

    /** 用途水印外观同理：用户这次调过，就不再被持久化值的异步恢复覆盖。 */
    private var purposeStyleTouched = false
    private var purposeStyleSave: Job? = null

    /** 这次会话里最后一次非空的用途水印文案。只在内存里，不落盘。 */
    private var lastPurposeText: String? = null

    /** 导出前提醒的开关。设置还没读到时按开着算——宁可多问一次。 */
    private var exportReminder = true

    /** 设置里「换样式时一起换」。还没读到时按出厂的开着算。 */
    private var restyleAll = true

    /** 品牌水印的字样。设置还没读到时按系统语言算，和设置页没选过时的默认一致。 */
    private var watermarkStyle = BrandWatermarkStyle.defaultFor(Locale.getDefault())

    /** 设置里的「无水印」，没选过是 null（见 SettingsStore.brandWatermarkOff）。 */
    private var watermarkOff: Boolean? = null

    init {
        restore()
        brushRestored = restoreBrush()
        restorePurposeStyle()
        observeRecognitionConfig()
        viewModelScope.launch { settings.pendingExportReminder.collect { exportReminder = it } }
        viewModelScope.launch { settings.restyleAllMasks.collect { restyleAll = it } }
        viewModelScope.launch { settings.brandWatermarkStyle.collect { watermarkStyle = it } }
        viewModelScope.launch { settings.brandWatermarkOff.collect { watermarkOff = it } }
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
        uiState = _state.value.copy(analyzing = true, aiReview = AiReview.Hidden).withHistoryFlags()
        val index = _state.value.batch?.index
        if (index != null) scanBatchItem(index, image, keepOnlyManual = true)
        else viewModelScope.launch { analyze(image, keepOnlyManual = true) }
    }

    /**
     * 「记住上次样式」（spec §12 M5）：画笔的样式与各样式的参数。读设置是异步的，用户完全可能抢在它之前
     * 就点了样式栏——那时以用户的当次选择为准，不要把他刚调的改回去。
     */
    private fun restoreBrush(): Job = viewModelScope.launch {
        val style = settings.lastStyle.first()
        val options = settings.maskOptions.first()
        if (brushTouched) return@launch
        _state.value = _state.value.copy(brush = MaskLook(style, options))
    }

    /** 与 restoreBrush 同理：持久化的外观晚到时，不覆盖用户这次已经调过的。 */
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
                brush = _state.value.brush,
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
        endBatch()
        aiReviewJob?.cancel()                             // 上一张图的增强识别作废，按钮随之收起
        uiState = _state.value.copy(loading = true, message = null, aiReview = AiReview.Hidden)
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
                lookEditOrigin = null
                uiState = EditorUiState(
                    image = image,
                    plan = MaskPlan.empty(),
                    analyzing = true,
                    // 换图是换一张图，不是换一个人：购买态、画笔必须活过每一次 EditorUiState 重建。
                    isPro = _state.value.isPro,
                    purposeStyle = _state.value.purposeStyle,
                    brush = _state.value.brush,
                    stylePanelOpen = _state.value.stylePanelOpen,
                )
                analyze(image)
            }.onFailure {
                uiState = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error(R.string.error_open_image),
                )
            }
        }
    }

    /**
     * 批量入口（spec §7.6）：相册一次分享多张，或首页 Picker 里选了多张。
     * 单张时不开批量会话，行为与 onImageChosen 完全一致——底栏的胶片条只在真的有多张时出现。
     *
     * 批量会话不进 SavedStateHandle：进程被杀之后回来只恢复画布上那一张
     * （restore() 那条路），批量退化成单张。这是有意的降级——把整个会话
     * 序列化回来，用户会面对一个「哪几张看过、哪几张识别完了」完全说不清的状态。
     */
    fun onImagesChosen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            onImageChosen(uris.first())
            return
        }
        endBatch()
        aiReviewJob?.cancel()
        // 私有副本只在会话开始时清一次。批量导出要在最后读回**每一张**的副本，
        // 像 onImageChosen 那样逐张 clear() 会把前面几张就地删掉，导出只剩最后看的那一张。
        intake.clear()
        intakeResult = null
        undoStack = UndoStack()
        lookEditOrigin = null
        dragOrigin = null
        uiState = EditorUiState(
            plan = MaskPlan.empty(),
            loading = true,
            isPro = _state.value.isPro,
            purposeStyle = _state.value.purposeStyle,
            brush = _state.value.brush,
            stylePanelOpen = _state.value.stylePanelOpen,
            batch = BatchSession(items = uris.map { BatchItem(it) }, index = 0),
        )
        loadBatchItem(0)
    }

    /**
     * 胶片条停在了第 [index] 张（滑过去，或点了它）。
     *
     * 画布上这张的编辑、撤销栈、增强识别走到哪一步，都存回会话里；画布先空着（底下垫着那一张的缩略图），
     * 那一张载入后接着它自己的编辑和撤销栈来。一张图第一次被打开时才识别。
     * 切走时还没识别完的那一张接着在后台跑，结果落回它自己的 plan。
     */
    fun selectImage(index: Int) {
        val before = _state.value.batch ?: return
        if (index !in before.items.indices) return
        // 就是眼前这张：开着、或者正在载入，就没什么要做的；上次没打开成，就再试一次
        if (index == before.index && (_state.value.image != null || _state.value.loading)) return
        // 还没收尾的拖框、滑条先收尾，算进要离开的这张自己的撤销栈
        commitDrag()
        finishLookEdit()
        aiReviewJob?.cancel()
        val s = _state.value
        val batch = s.batch ?: return
        val live = s.liveBatch ?: return
        if (s.image != null) {
            batchUndo[batch.index] = undoStack
            // 增强识别跑到一半切走，就算没跑：任务已经取消了，回来时按钮还能点
            batchScans.getOrPut(batch.index) { BatchScan() }.aiReview =
                if (s.aiReview == AiReview.Running) AiReview.Ready else s.aiReview
        }
        undoStack = UndoStack()
        // 载入期间进程被杀的话，宁可什么都不恢复，也不要把上一张的图配上这里的占位 plan
        intakeResult = null
        uiState = s.copy(
            image = null,
            plan = MaskPlan.empty(),
            loading = true,
            analyzing = false,
            aiReview = AiReview.Hidden,
            selectedManualId = null,
            colorPick = null,
            canUndo = false,
            canRedo = false,
            batch = live.select(index),
        )
        loadBatchItem(index)
    }

    /**
     * 载入批量里的第 [index] 张：第一次打开时复制私有副本，之后每次从副本解码。
     * 第一次打开的这一下开始识别；切走时还在识别的，回来接着等它。
     */
    private fun loadBatchItem(index: Int) {
        batchLoad?.cancel()
        batchLoad = viewModelScope.launch {
            val item = _state.value.batch?.items?.getOrNull(index) ?: return@launch
            val loaded = try {
                val taken = item.file?.let { IntakeResult(it, item.mimeType) }
                    ?: intake.copyToPrivate(item.uri, ioDispatcher).also { copied ->
                        ensureActive()
                        _state.value.batch?.let { b ->
                            uiState = _state.value.copy(batch = b.withFile(index, copied.file, copied.mimeType))
                        }
                    }
                taken to SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType, ioDispatcher)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            ensureActive()                                  // 载入期间又切走了，或者整批换了
            val s = _state.value
            val batch = s.batch?.takeIf { it.index == index } ?: return@launch
            if (loaded == null) {
                uiState = s.copy(
                    loading = false,
                    batch = batch.withFailed(index),
                    message = EditorMessage.Error(R.string.error_open_image),
                )
                return@launch
            }
            val (taken, image) = loaded
            val stored = batch.items[index]
            val record = batchScans[index]
            val scanning = record?.job?.isActive == true
            // 打开过、也不在识别，却从没跑完过一遍识别：照第一次打开处理
            val needsScan = !scanning && record?.ranWith == null
            intakeResult = taken
            undoStack = batchUndo.remove(index) ?: UndoStack()
            lookEditOrigin = null
            dragOrigin = null
            analyzedWith = record?.ranWith
            reviewLines = record?.lines
            uiState = s.copy(
                image = image,
                plan = stored.plan ?: MaskPlan.empty(),
                loading = false,
                analyzing = needsScan || scanning,
                aiReview = if (needsScan || scanning) AiReview.Hidden else record?.aiReview ?: AiReview.Hidden,
                batch = batch.withLoaded(index, taken.file, taken.mimeType, image.scale),
            ).withHistoryFlags()
            val ranWith = record?.ranWith
            when {
                needsScan -> scanBatchItem(index, image)
                scanning -> Unit                            // 结果出来时直接落到画布上这份 plan 里
                // 这一张识别完之后改过设置：和单张一样，按新方案重跑一遍
                ranWith != null && ranWith != config -> reanalyze(image)
                // 识别早就跑完了：和刚识别完一样，看要不要摆出「AI 增强识别」
                ranWith != null && reviewLines != null && engine.canRefine &&
                    _state.value.aiReview == AiReview.Hidden -> offerAiReview(image, ranWith, engine, trace = null)
            }
        }
    }

    /** 识别批量里的第 [index] 张。[image] 必须是画布上那一张（就是 batch.current）。 */
    private fun scanBatchItem(index: Int, image: SourceImage, keepOnlyManual: Boolean = false) {
        val record = batchScans.getOrPut(index) { BatchScan() }
        val target = BatchTarget(index, ++scanSerial)
        record.serial = target.serial
        record.job = viewModelScope.launch { analyze(image, keepOnlyManual, target) }
    }

    /**
     * 批量里一张图的识别结果回来了。这张图还在画布上，就和单张一样并进画布上的 plan、看要不要摆出「AI 增强识别」；
     * 已经切走了（或者切回来了、但还在载入），就并进会话里它自己的那份 plan，下次打开时直接是识别完的样子。
     */
    private fun deliverBatchScan(
        target: BatchTarget,
        result: AnalysisResult?,
        ranWith: RecognitionConfig,
        ranOn: RedactionEngine,
        keepOnlyManual: Boolean,
        trace: TraceRecorder?,
    ) {
        val record = batchScans[target.index]
        if (record == null || record.serial != target.serial) return    // 这一张后来又重跑了，以新的那一遍为准
        record.ranWith = ranWith
        record.lines = result?.lines
        record.aiReview = AiReview.Hidden
        val current = _state.value
        val batch = current.batch ?: return
        val shown = current.image?.takeIf { batch.index == target.index }
        val base = if (shown != null) current.plan else batch.items[target.index].plan ?: MaskPlan.empty()
        // 直接打码的用结果到达这一刻的画笔，和单张一样；用户在识别期间画的手动框排在后面，不被覆盖
        val detected = MaskPlanFactory.itemsFrom(result?.candidates.orEmpty(), current.brush)
        val kept = if (keepOnlyManual) base.items.filter { it.kind == SensitiveKind.MANUAL } else base.items
        val merged = base.copy(items = detected + kept)
        if (shown == null) {
            uiState = current.copy(batch = batch.withPlanAt(target.index, merged))
            return
        }
        analyzedWith = ranWith
        reviewLines = result?.lines
        uiState = current.copy(analyzing = false, aiReview = AiReview.Hidden, plan = merged)
        if (result != null && ranOn.canRefine) offerAiReview(shown, ranWith, ranOn, trace)
    }

    /** 批量会话结束（换成单张，或者换了一批）：还在跑的载入、识别都作废，各张的撤销栈一并丢掉。 */
    private fun endBatch() {
        batchLoad?.cancel()
        batchLoad = null
        batchScans.values.forEach { it.job?.cancel() }
        batchScans.clear()
        batchUndo.clear()
    }

    /**
     * 识别（spec §7.1）：选中即进编辑器，候选已按规则表的默认状态打好码，
     * 不是「等你逐个确认」。分析期间画布可交互，结果到达时并入现有 plan。
     *
     * @param target 批量里的哪一张。为 null 时是单张：结果到达前换了图，这批结果就丢掉。
     */
    private suspend fun analyze(image: SourceImage, keepOnlyManual: Boolean = false, target: BatchTarget? = null) {
        val ranWith = config
        val ranOn = engine
        aiReviewJob?.cancel()
        // 调试信息开着时记下这一次：记录器挂在协程上下文里，识别的各环节自己往里记（见 engine/Traced.kt）
        val trace = if (debugTrace.value) traceLog.start("识别") else null
        trace?.add(inputSection(image, ranWith))
        val outcome = runCatching { withContext(trace ?: EmptyCoroutineContext) { ranOn.analyze(image) } }
        trace?.run {
            add(resultSection(outcome))
            finish()
        }
        val result = outcome.getOrNull()
        brushRestored.join()
        if (target != null) {
            deliverBatchScan(target, result, ranWith, ranOn, keepOnlyManual, trace)
            return
        }
        // 分析结果不进撤销栈——它是初始状态，不是用户动作。
        // 用户在分析期间画的手动框排在后面，不被覆盖。
        val current = _state.value
        if (current.image !== image) return            // 用户已经换了图，丢弃这批结果
        // 直接打码的用结果到达这一刻的画笔：识别期间换了样式，打上的就是新样式
        val detected = MaskPlanFactory.itemsFrom(result?.candidates.orEmpty(), current.brush)
        val kept =
            if (keepOnlyManual) current.plan.items.filter { it.kind == SensitiveKind.MANUAL }
            else current.plan.items
        analyzedWith = ranWith
        reviewLines = result?.lines
        uiState = current.copy(
            analyzing = false,
            aiReview = AiReview.Hidden,
            plan = current.plan.copy(items = detected + kept),
        )
        if (result != null && ranOn.canRefine) offerAiReview(image, ranWith, ranOn, trace)
    }

    /**
     * 调试信息里的「重新识别」：当前这张图按眼下的方案再跑一遍，好把这一次记下来——
     * 打开调试信息之前就识别完的图，不重跑就什么也看不到。走换方案后重跑的那条路，整次压一个撤销快照。
     */
    fun rerunRecognition() {
        val current = _state.value
        val image = current.image ?: return
        if (current.analyzing) return
        reanalyze(image)
    }

    /** 最近一次识别出的整页文字，「AI 增强识别」要把它送给模型。只活在内存里，重建后就没有了。 */
    private var reviewLines: List<TextLine>? = null

    private var aiReviewJob: Job? = null

    /**
     * 「AI 增强识别」不再自动跑（PP-OCR 加规则的结果已经够用，自动再跑一遍要多等好几秒）。
     * 这里只探测模型在不在：在，按钮才出现；机型不支持就什么都不出现。
     *
     * 探测也记进这一次识别的调试信息（[trace]）：按钮没出现时，看得到本机的 Nano 是什么情况。
     */
    private fun offerAiReview(
        image: SourceImage,
        ranWith: RecognitionConfig,
        ranOn: RedactionEngine,
        trace: TraceRecorder?,
    ) {
        aiReviewJob = viewModelScope.launch(trace ?: EmptyCoroutineContext) {
            if (!ranOn.refineReady()) return@launch
            val current = _state.value
            if (current.image !== image || analyzedWith != ranWith || current.analyzing) return@launch
            if (current.aiReview != AiReview.Hidden) return@launch
            uiState = current.copy(aiReview = AiReview.Ready)
        }
    }

    /**
     * 用户点了「AI 增强识别」：端侧大模型（Gemini Nano）把整页文字再看一遍，
     * 新发现的追加进 plan，只圈出、标「AI」。
     *
     * 是用户点出来的，所以进撤销栈——不想要这批框，撤销一下就整批拿掉。
     * 这期间换了图、换了方案，这一批就作废。
     */
    fun runAiReview() {
        val current = _state.value
        if (current.aiReview != AiReview.Ready && current.aiReview != AiReview.Failed) return
        val image = current.image ?: return
        val lines = reviewLines ?: return
        val ranWith = analyzedWith ?: return
        val ranOn = engine
        uiState = current.copy(aiReview = AiReview.Running)
        val trace = if (debugTrace.value) traceLog.start("AI 增强识别") else null
        aiReviewJob = viewModelScope.launch(trace ?: EmptyCoroutineContext) {
            val extra = try {
                ranOn.refine(lines, current.plan.items.map { it.quad })
            } finally {
                trace?.finish()
            }
            trace?.add(refineResultSection(extra, lines))
            ensureActive()
            val now = _state.value
            if (now.image !== image || analyzedWith != ranWith) return@launch
            if (extra == null) {
                uiState = now.copy(aiReview = AiReview.Failed, aiReviewsFinished = now.aiReviewsFinished + 1)
                return@launch
            }
            val known = now.plan.items.mapTo(HashSet()) { it.candidateId }
            val added = MaskPlanFactory.itemsFrom(extra, now.brush).filter { it.candidateId !in known }
            if (added.isNotEmpty()) undoStack.push(now.plan)
            uiState = now.copy(
                aiReview = AiReview.Done(found = added.size),
                aiReviewsFinished = now.aiReviewsFinished + 1,
                plan = now.plan.copy(items = now.plan.items + added),
            ).withHistoryFlags()
        }
    }

    // ---------- 编辑 ----------

    /**
     * 点一下：识别出的候选在 MASKED ⇄ OUTLINED 之间切换；手动框不切换，而是选中它，调出手柄与删除。
     * 手动框是用户自己画的，画它就是要打码，不想要就删掉——点它要是把码去掉了，
     * 「点一下再调一调」就成了「点一下码没了」。
     *
     * 圈出的候选被点成打码时用的是画笔，不是它上一次打码时的样子：换了样式再点虚线框，要的就是新样式。
     * 吸管在等着取色时，这一下只取颜色。
     */
    fun onTap(imagePoint: PointF) {
        if (_state.value.colorPick != null) {
            pickColorAt(imagePoint)
            return
        }
        val hit = GestureRules.hitTest(_state.value.plan.items, imagePoint)
        if (hit != null) {
            tapItem(hit)
            return
        }
        // 点空白算点了别处：选中态收起
        finishLookEdit()
        uiState = _state.value.copy(selectedManualId = null)
    }

    /**
     * 宽屏的列表上点了一行：和在画布上点那一块一样，识别出的切换打码、手动框选中。
     * 吸管在等着取色时点的是列表不是图，先收起吸管，这一下照常算数。
     */
    fun onListItemClick(id: String) {
        val item = _state.value.plan.find(id) ?: return
        cancelColorPick()
        tapItem(item)
    }

    private fun tapItem(item: MaskItem) {
        if (item.source == DetectorSource.MANUAL) {
            finishLookEdit()
            uiState = _state.value.copy(selectedManualId = item.candidateId)
            return
        }
        // 点候选也算点了别处：选中态收起
        mutate { it.toggle(item.candidateId, _state.value.brush) }
        finishLookEdit()
        uiState = _state.value.copy(selectedManualId = null)
    }

    fun onManualBox(quadInImageSpace: Quad) {
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quadInImageSpace,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            state = MaskState.MASKED,          // 落笔即打码
            look = _state.value.brush,          // 用画笔：样式栏上选的就是这一笔的样子
        )
        mutate { it.add(item) }
        // 画完直接进选中态：手柄就在手边，不用再点一下才能调大小；面板上接着调，改的就是这个框
        uiState = _state.value.copy(selectedManualId = item.candidateId, colorPick = null)
    }

    /**
     * 手画完一笔（马克笔、画笔选了手画）：和画完一个框一样，落笔就打上、画完进选中态，拖得动、删得掉。
     * [quad] 是这一笔的外接框，四边让出半个笔宽（见 Ink.fit）。
     */
    fun onInk(pointsInImageSpace: List<PointF>) {
        val s = _state.value
        val image = s.image ?: return
        if (pointsInImageSpace.isEmpty()) return
        val brush = s.brush
        val half = brush.options.halfWidth(brush.style, min(image.width, image.height).toFloat())
        val (quad, ink) = Ink.fit(pointsInImageSpace, half)
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quad,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            state = MaskState.MASKED,
            look = brush,
            ink = ink,
        )
        mutate { it.add(item) }
        uiState = _state.value.copy(selectedManualId = item.candidateId, colorPick = null)
    }

    fun clearSelection() {
        finishLookEdit()
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
        if (dragOrigin == null) {
            finishLookEdit()
            dragOrigin = _state.value.plan
        }
        uiState = _state.value.copy(plan = _state.value.plan.replace(item.copy(quad = quad)))
    }

    fun commitDrag() {
        val origin = dragOrigin ?: return
        dragOrigin = null
        if (origin == _state.value.plan) return
        undoStack.push(origin)
        uiState = _state.value.withHistoryFlags()
    }

    // ---------- 样式（spec §7.5 修订：每块码有自己的样式，样式栏是画笔） ----------

    /**
     * 点样式栏上的一项。换了样式只是换，面板不跟着弹出来——多数时候换个样式就够了，用不着调参数；
     * 宽屏上面板本来开着的话照样开着，换成新样式的参数。点的正是当前这一项，才弹出或收起面板。
     * 换样式本身照 [setStyle] 的规矩：管下一次打码，选中了手动框时也管那个框。
     */
    fun onStyleChipClick(style: MaskStyle) {
        val s = _state.value
        if (style == s.barLook.style) {
            _state.value = s.copy(stylePanelOpen = !s.stylePanelOpen, colorPick = null)
            return
        }
        setStyle(style)
    }

    /** 换样式，参数沿用样式栏上那一份里这种样式的参数。 */
    fun setStyle(style: MaskStyle) {
        val bar = _state.value.barLook
        editLook(bar.copy(style = style))
    }

    /**
     * 面板上改了样式或参数。
     *
     * 改的是画笔：从下一次打码起生效。选中了手动框时，那个框也一起换成改后的样子（刚画完的框就是选中的，画完再调颜色是最顺手的用法）。
     * 设置里开着「换样式时一起换」（出厂开着）、改后是盖住内容的样式时，已经打好的色块、马赛克、模糊也都跟着换：
     * 一张图一般只用一种打码样式。参数也一起改，不然换成马赛克再调粗细，图上就是两种粗细的马赛克。
     * 马克笔、画笔画的标注不跟着变，见 [MaskPlan.restyleRedactions]。
     *
     * @param inProgress 滑条还没松手。拖动期间码跟着变，但不压撤销栈，松手时由 [finishLookEdit] 补一次，
     *   与拖框的 previewSelectedQuad / commitDrag 同一个道理。
     */
    fun editLook(look: MaskLook, inProgress: Boolean = false) {
        val next = look.normalized()
        if (_state.value.colorPick != null) _state.value = _state.value.copy(colorPick = null)
        setBrush(next)
        val s = _state.value
        var plan = s.plan
        s.selectedItem?.let { plan = plan.restyle(it.candidateId, next) }
        if (restyleAll && next.style.redacts) plan = plan.restyleRedactions(next)
        if (plan != s.plan) {
            if (lookEditOrigin == null) lookEditOrigin = s.plan
            uiState = s.copy(plan = plan)
        }
        if (!inProgress) finishLookEdit()
    }

    /** 滑条松手：把这一段连续调节作为一步压进撤销栈。 */
    fun finishLookEdit() {
        val origin = lookEditOrigin ?: return
        lookEditOrigin = null
        if (origin == _state.value.plan) return
        undoStack.push(origin)
        uiState = _state.value.withHistoryFlags()
    }

    /** 面板上的「恢复默认」：只把当前这种样式的参数放回出厂值。 */
    fun resetLookOptions() {
        val bar = _state.value.barLook
        editLook(bar.copy(options = bar.options.resetFor(bar.style)))
    }

    fun dismissStylePanel() {
        finishLookEdit()
        _state.value = _state.value.copy(stylePanelOpen = false, colorPick = null)
    }

    /** 吸管：下一次点画布取那里的颜色，填进 [target]。 */
    fun startColorPick(target: ColorTarget) {
        _state.value = _state.value.copy(colorPick = target)
    }

    fun cancelColorPick() {
        if (_state.value.colorPick != null) _state.value = _state.value.copy(colorPick = null)
    }

    /**
     * 取的是原图上的颜色，不是画面上已经打了码的颜色：想让色块和底色融成一片，要的正是底下的颜色。
     * 点在图外面（留白处）不算，接着等。
     */
    private fun pickColorAt(imagePoint: PointF) {
        val s = _state.value
        val target = s.colorPick ?: return
        val image = s.image ?: return
        val x = imagePoint.x.toInt()
        val y = imagePoint.y.toInt()
        if (imagePoint.x < 0f || imagePoint.y < 0f || x >= image.width || y >= image.height) return
        val color = pickColor(image.bitmap, x, y)
        val bar = s.barLook
        editLook(bar.copy(options = target.withColor(bar.options, color)))
    }

    /**
     * 画笔变了：立即生效，落盘做一次去抖——拖滑条时每一帧都会进来，不让 DataStore 跟着每帧写一次。
     */
    private fun setBrush(look: MaskLook) {
        brushTouched = true
        if (look == _state.value.brush) return
        _state.value = _state.value.copy(brush = look)
        brushSave?.cancel()
        brushSave = viewModelScope.launch {
            delay(BRUSH_SAVE_DEBOUNCE_MS)
            settings.setLastStyle(look.style)
            settings.setMaskOptions(look.options)
        }
    }

    fun undo() {
        finishLookEdit()
        val restored = undoStack.undo(_state.value.plan) ?: return
        uiState = _state.value.copy(plan = restored).withHistoryFlags()
    }

    fun redo() {
        finishLookEdit()
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
     * 批量导出：看过的一次性全部导出（spec §7.6）。
     *
     * 先等还没跑完的识别：切走的那几张可能还在后台识别，这时导出，本该直接打码的东西就原样出去了——
     * 而用户根本看不到它们还没识别完。等的这一会儿按钮显示「导出中…」。
     *
     * 还有没打开过的图时先问一句（[reviewUnopened] / [exportOpenedOnly]）：没打开过的图没识别、也没人看过，不能替用户导出。
     * 拦截按**全部图片**的待打码数之和判定，不是只看当前这张——
     * 只看当前这张的话，别的几张里被放过的 OUTLINED 就永远没有第二次机会了。
     */
    fun exportBatch() {
        if (_state.value.batch == null || _state.value.exporting) return
        finishLookEdit()
        uiState = _state.value.copy(exporting = true)
        viewModelScope.launch {
            awaitBatchScans()
            val batch = _state.value.liveBatch
            if (batch == null) {
                uiState = _state.value.copy(exporting = false)
                return@launch
            }
            uiState = _state.value.copy(batch = batch, exporting = false)
            if (batch.unopened > 0) uiState = _state.value.copy(unopenedDialogVisible = true)
            else exportOpened(batch)
        }
    }

    /** 没打开过的问过了（或者本来就没有）：照常走待打码拦截，再导出看过的那些。 */
    private fun exportOpened(batch: BatchSession) {
        if (exportReminder && batch.totalPending > 0) {
            uiState = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runBatchExport(batch)
    }

    /** 等批量里还在跑的载入和识别都跑完。等的时候又切出去打开了新的一张，就接着等它。 */
    private suspend fun awaitBatchScans() {
        while (true) {
            batchLoad?.join()
            val running = batchScans.values.mapNotNull { scan -> scan.job?.takeIf { it.isActive } }
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /** 「还有几张没看过」里的「去看看」：跳到第一张没打开过的，它这时开始识别。 */
    fun reviewUnopened() {
        uiState = _state.value.copy(unopenedDialogVisible = false)
        val first = _state.value.batch?.firstUnopened ?: return
        selectImage(first)
    }

    /** 「还有几张没看过」里的「只导出看过的」：没打开过的不导出，看过的照常走待打码拦截。 */
    fun exportOpenedOnly() {
        uiState = _state.value.copy(unopenedDialogVisible = false)
        exportOpened(_state.value.liveBatch ?: return)
    }

    /** @param stopReminding 对话框里勾了「不再提示」。 */
    fun confirmMaskAllAndExport(stopReminding: Boolean = false) {
        if (stopReminding) stopExportReminder()
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.liveBatch
        val brush = _state.value.brush
        if (batch != null) {
            val cleared = batch.maskAllEverywhere(brush)
            val s = _state.value
            uiState = s.copy(
                batch = cleared,
                plan = if (s.image != null) cleared.current.plan ?: s.plan.maskAll(brush) else s.plan,
            )
            runBatchExport(cleared)
        } else {
            mutate { it.maskAll(brush) }
            runExport(_state.value.plan)
        }
    }

    fun confirmExportAnyway(stopReminding: Boolean = false) {
        if (stopReminding) stopExportReminder()
        uiState = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.liveBatch
        if (batch != null) runBatchExport(batch)
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
            colorPick = null,
            purposeText = s.purposeText ?: lastPurposeText ?: defaultPurposeText,
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
        uiState = _state.value.copy(pendingDialogVisible = false, unopenedDialogVisible = false)
    }

    fun consumeMessage() {
        uiState = _state.value.copy(message = null)
    }

    /**
     * 水印只由购买态和设置里的「无水印」决定，调用点一律不传（spec §10）。
     * 留一个 applyWatermark 参数就等于留了一条「某个入口忘了接购买态」的路。
     */
    private fun applyWatermark(): Boolean = brandWatermarkApplies(_state.value.isPro, watermarkOff)

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
                    watermarkStyle = watermarkStyle,
                    purposeText = _state.value.purposeText,
                    purposeStyle = _state.value.purposeStyle,
                ),
                dispatcher = ioDispatcher,
            )
            uiState = when (outcome) {
                // 交回去编辑器就关了，按钮不再放开，免得又导出一张
                is ExportOutcome.Success -> if (forCaller) {
                    _state.value.copy(message = EditorMessage.HandedOver(listOf(outcome.uri)))
                } else {
                    _state.value.copy(
                        exporting = false,
                        message = EditorMessage.Exported(outcome.uri, outcome.downscaled, outcome.width, outcome.height),
                    )
                }
                is ExportOutcome.Failure -> _state.value.copy(
                    exporting = false,
                    message = EditorMessage.Error(R.string.error_export),
                )
            }
        }
    }

    private fun runBatchExport(batch: BatchSession) {
        uiState = _state.value.copy(exporting = true)
        viewModelScope.launch {
            val written = ArrayList<Uri>()
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
                        watermarkStyle = watermarkStyle,
                        purposeText = _state.value.purposeText,
                        purposeStyle = _state.value.purposeStyle,
                    ),
                    dispatcher = ioDispatcher,
                )
                if (outcome is ExportOutcome.Success) written += outcome.uri else failed++
            }
            uiState = when {
                !forCaller -> _state.value.copy(
                    exporting = false,
                    message = if (failed == 0) EditorMessage.BatchExported(written.size)
                    else EditorMessage.BatchPartial(written.size, failed),
                )
                failed == 0 && written.isNotEmpty() ->
                    _state.value.copy(message = EditorMessage.HandedOver(written))
                // 交回去的缺了几张，调用方那边无从知道：这次一张都不交，再试一次
                else -> _state.value.copy(exporting = false, message = EditorMessage.Error(R.string.error_export))
            }
        }
    }

    // ---------- 内部 ----------

    /** 所有改动都先把当前 plan 压进撤销栈，再替换。还没收尾的一段面板调节先收尾，各占一步。 */
    private inline fun mutate(block: (MaskPlan) -> MaskPlan) {
        finishLookEdit()
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
        const val BRUSH_SAVE_DEBOUNCE_MS = 400L
    }

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        uiState = _state.value.copy(plan = plan)
    }
}
