package moe.flinty.yomark.ui

import android.graphics.PointF
import android.graphics.RectF
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
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskPlanFactory
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.StrokeShape
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
import moe.flinty.yomark.render.TextStamp
import moe.flinty.yomark.ui.batch.BatchItem
import moe.flinty.yomark.ui.batch.BatchSession
import moe.flinty.yomark.ui.bigbang.BigBangPage
import moe.flinty.yomark.ui.bigbang.BigBangSelection
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
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
     * 开发者里的「显示识别框」：画布上叠出 [textBoxes]。调试信息关着时一律不画。
     * 不进 EditorUiState，理由同 [scanStyle]。
     */
    val showTextBoxes: StateFlow<Boolean> = combine(debugTrace, settings.showTextBoxes) { debug, on -> debug && on }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _textBoxes = MutableStateFlow<List<Quad>>(emptyList())

    /** 画布上这张图识别出的每一段字的框，就是 [lines]（init 里恢复时就要写它，所以放在前面）。开发者里打开「显示识别框」时画出来。 */
    val textBoxes: StateFlow<List<Quad>> = _textBoxes.asStateFlow()

    private val _hasText = MutableStateFlow(false)

    /**
     * 画布上这张图识别出了字（[reviewLines] 里有不是空白的）：底栏的大爆炸点得动（还得识别完，见 EditorScreen）。
     * 不进 EditorUiState：它跟着 [takeLines] 变，那几处随后都拿换线之前的那一份 state 去 copy。
     */
    val hasText: StateFlow<Boolean> = _hasText.asStateFlow()

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
        /** 切走时这一张上点掉了哪些码（见 [dismissed]）。 */
        var dismissed: List<MaskItem> = emptyList()
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

    /** 面板上一次连续调节（拖滑条、打字）开始前的 plan 快照，松手（收起面板、点别处）时才压进撤销栈。与 dragOrigin 同理。 */
    private var lookEditOrigin: MaskPlan? = null

    /** 眼下这一段面板调节是在打字（见 [setText]）。 */
    private var typing = false

    /**
     * 这一段调节里文本的中心：改字、拖字号都以它为准，不以眼下的框为准——框贴着图边时被挪进来过，
     * 照它算的话，字越打越往里漂，删短了也回不去。块的 id 和中心，收尾时清掉。
     */
    private var textAnchor: Pair<String, PointF>? = null

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
        // 外部模型的设置不在 config 里，变了不重跑识别，只重新看一下「AI 增强识别」能不能用
        viewModelScope.launch { settings.externalModel.drop(1).collect { reprobeAiReview() } }
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
     * 丢掉的是用户对识别出的码做过的取消：新方案产出的候选和旧的不是同一批，
     * 把旧的取消往新候选上贴没有正确答案。手动框、点一行字打上的码留着——那是用户自己加的。
     */
    private fun reanalyze(image: SourceImage) {
        // 打到一半的字先收尾，算它自己一步：不然它起手前的那一份压在重跑这一步上面，撤销的先后就乱了。
        // 大爆炸也是：它摆的是这一遍之前识别出的字，重跑完就对不上了，收起来，打上的码留着
        finishLookEdit()
        endBigBang()
        undoStack.push(_state.value.plan)
        uiState = _state.value.copy(analyzing = true, aiReview = AiReview.Hidden, bigBang = null).withHistoryFlags()
        val index = _state.value.batch?.index
        if (index != null) scanBatchItem(index, image, keepOnlyManual = true)
        else viewModelScope.launch { analyze(image, keepOnlyManual = true) }
    }

    /**
     * 「记住上次样式」（spec §12 M5）：画笔回到「打码」里上次选的样式，各样式的参数是上次调好的。
     * 上次停在马克笔、画笔也一样：打开编辑器先是打码，识别出来的码也用它（见 EditorUiState.maskLook）。
     * 读设置是异步的，用户完全可能抢在它之前就点了样式栏——那时以用户的当次选择为准，不要把他刚调的改回去。
     */
    private fun restoreBrush(): Job = viewModelScope.launch {
        val style = settings.lastStyle.first()
        val options = settings.maskOptions.first()
        if (brushTouched) return@launch
        _state.value = _state.value.copy(brush = MaskLook(style, options), maskStyle = style)
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
        val savedLines = savedState.get<FloatArray>(KEY_LINES)?.let(::unpackQuads).orEmpty()
        viewModelScope.launch {
            val image = runCatching {
                SourceImageLoader.loadForAnalysis(file, mime, ioDispatcher)
            }.getOrElse {
                clearSavedState()
                uiState = uiState.copy(restoring = false)
                return@launch
            }
            intakeResult = IntakeResult(file, mime)
            lines = savedLines
            uiState = EditorUiState(
                image = image, plan = plan,
                isPro = _state.value.isPro, purposeStyle = _state.value.purposeStyle,
                brush = _state.value.brush, maskStyle = _state.value.maskStyle,
            )
        }
        return true
    }

    private fun clearSavedState() {
        savedState.remove<String>(KEY_FILE)
        savedState.remove<String>(KEY_MIME)
        savedState.remove<MaskPlan>(KEY_PLAN)
        savedState.remove<FloatArray>(KEY_LINES)
    }

    fun onImageChosen(uri: Uri) {
        endBatch()
        endBigBang()
        aiReviewJob?.cancel()                             // 上一张图的增强识别作废，按钮随之收起
        uiState = _state.value.copy(loading = true, message = null, aiReview = AiReview.Hidden, bigBang = null)
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
                takeLines(null)                       // 上一张图的行，点在这张图上不作数
                dismissed = emptyList()
                uiState = EditorUiState(
                    image = image,
                    plan = MaskPlan.empty(),
                    analyzing = true,
                    // 换图是换一张图，不是换一个人：购买态、画笔必须活过每一次 EditorUiState 重建。
                    isPro = _state.value.isPro,
                    purposeStyle = _state.value.purposeStyle,
                    brush = _state.value.brush,
                    maskStyle = _state.value.maskStyle,
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
        takeLines(null)
        dismissed = emptyList()
        undoStack = UndoStack()
        lookEditOrigin = null
        dragOrigin = null
        uiState = EditorUiState(
            plan = MaskPlan.empty(),
            loading = true,
            isPro = _state.value.isPro,
            purposeStyle = _state.value.purposeStyle,
            brush = _state.value.brush,
            maskStyle = _state.value.maskStyle,
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
        // 还没收尾的拖框、滑条、大爆炸先收尾，算进要离开的这张自己的撤销栈
        commitDrag()
        finishLookEdit()
        endBigBang()
        aiReviewJob?.cancel()
        val s = _state.value
        val batch = s.batch ?: return
        val live = s.liveBatch ?: return
        if (s.image != null) {
            batchUndo[batch.index] = undoStack
            // 增强识别跑到一半切走，就算没跑：任务已经取消了，回来时按钮还能点
            batchScans.getOrPut(batch.index) { BatchScan() }.apply {
                aiReview = if (s.aiReview == AiReview.Running) AiReview.Ready else s.aiReview
                dismissed = this@EditorViewModel.dismissed
            }
        }
        undoStack = UndoStack()
        // 载入期间进程被杀的话，宁可什么都不恢复，也不要把上一张的图配上这里的占位 plan
        intakeResult = null
        takeLines(null)
        dismissed = emptyList()
        uiState = s.copy(
            image = null,
            plan = MaskPlan.empty(),
            loading = true,
            analyzing = false,
            aiReview = AiReview.Hidden,
            selectedManualId = null,
            colorPick = null,
            textPlacement = null,
            textFocus = false,
            bigBang = null,
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
            takeLines(record?.lines)
            dismissed = record?.dismissed.orEmpty()
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
        // 直接打码的用结果到达这一刻「打码」里的样式，和单张一样；用户在识别期间画的手动框排在后面，不被覆盖
        val detected = MaskPlanFactory.itemsFrom(result?.candidates.orEmpty(), current.maskLook)
        val keep = { items: List<MaskItem> -> if (keepOnlyManual) items.filter { it.kind == SensitiveKind.MANUAL } else items }
        val merged = base.copy(items = detected + keep(base.items))
        if (shown == null) {
            uiState = current.copy(batch = batch.withPlanAt(target.index, merged))
            return
        }
        analyzedWith = ranWith
        takeLines(result?.lines)
        rebaseOpenEdits { it.copy(items = detected + keep(it.items)) }
        uiState = current.copy(analyzing = false, aiReview = AiReview.Hidden, plan = merged, bigBang = null)
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
        // 识别器自己会切到后台，可文字出来之后的聊天截图、合并等都接着跑在调用方的线程上——留在主线程上，
        // 结果出来之前画布要卡一下。整个放到后台（与导出同一个调度器）
        val outcome = runCatching { withContext(ioDispatcher + (trace ?: EmptyCoroutineContext)) { ranOn.analyze(image) } }
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
        // 直接打码的用结果到达这一刻「打码」里的样式：识别期间在「打码」里换了样式，打上的就是新样式；
        // 换成了马克笔、画笔不算，它们只做标记
        val detected = MaskPlanFactory.itemsFrom(result?.candidates.orEmpty(), current.maskLook)
        val keep = { items: List<MaskItem> -> if (keepOnlyManual) items.filter { it.kind == SensitiveKind.MANUAL } else items }
        analyzedWith = ranWith
        takeLines(result?.lines)
        rebaseOpenEdits { it.copy(items = detected + keep(it.items)) }
        uiState = current.copy(
            analyzing = false,
            aiReview = AiReview.Hidden,
            plan = current.plan.copy(items = detected + keep(current.plan.items)),
            bigBang = null,
        )
        if (result != null && ranOn.canRefine) offerAiReview(image, ranWith, ranOn, trace)
    }

    /**
     * 识别结果并进了画布上的 plan：还没收尾的拖框、面板调节（打字）起手前的那一份也照样并进去。
     * 识别结果不进撤销栈，是初始状态；不并的话，收尾时压进去的是没有识别结果的那一份，撤销一下打上的码全没了。
     */
    private fun rebaseOpenEdits(merge: (MaskPlan) -> MaskPlan) {
        lookEditOrigin = lookEditOrigin?.let(merge)
        dragOrigin = dragOrigin?.let(merge)
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

    /**
     * 画布上这张图识别出的每一段字的框（TextLine.segments）：点在没打码的字上，打码的就是它所在的那一段。
     * 不是拼好的整行：PP-OCR 把同一排上隔开的几段拼成一行给规则看（RowGrouper），并排的三个按钮、
     * 缩略图上的字和它右边的标签都在一行里，按行打码一下盖住一整排。
     * 和 [reviewLines] 不同，它跟着 plan 进 SavedStateHandle（一段八个数），重建之后照样点得上。
     * 还没识别完、识别失败时是空的。
     */
    private var lines: List<Quad> = emptyList()
        set(value) {
            field = value
            savedState[KEY_LINES] = packQuads(value)
            _textBoxes.value = value
        }

    /**
     * 用户在这张图上点掉的识别结果（规则、聊天、AI 打上的码；自己点字打的、画的不算），后点掉的在后。
     * 点掉的多半是误报，「AI 增强识别」不该在同一处再打回来（见 RedactionEngine.refine 的 dismissed）；
     * 误点掉的，原地再点一下原样打回去（见 GestureRules.tapTarget）。打回去了也留着：撤销那一下之后还点得回来。
     * 换了图才清空：改设置重跑识别、再撤销回重跑前，点掉的还是那几处。只活在内存里，和 [reviewLines] 一样。
     */
    private var dismissed: List<MaskItem> = emptyList()

    /** 换上画布上这张图识别出的文字（没有就是 null）：增强识别要送的整页文字，点字打码要的每一段的框。 */
    private fun takeLines(found: List<TextLine>?) {
        reviewLines = found
        lines = found.orEmpty().flatMap { it.segments }
        _hasText.value = found.orEmpty().any { it.text.isNotBlank() }
    }

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
            // 头一回探测要建 Nano 的客户端、连 AICore，放在主线程上画布会卡住，正赶上开始落码
            if (!withContext(ioDispatcher) { ranOn.refineReady() }) return@launch
            val current = _state.value
            if (current.image !== image || analyzedWith != ranWith || current.analyzing) return@launch
            if (current.aiReview != AiReview.Hidden) return@launch
            uiState = current.copy(aiReview = AiReview.Ready)
        }
    }

    /**
     * 实验室里的外部模型开了、关了、改了接口：这张图识别早就跑完了，按钮出不出现要重新看一次。
     * 变得能用就摆出来——跑过的（Done、Failed）也重新可点，换了模型结果可能不一样；变得不能用就收起来。
     * 正在跑的那一次不动。
     */
    private fun reprobeAiReview() {
        val current = _state.value
        val image = current.image ?: return
        val ranWith = analyzedWith ?: return
        if (current.analyzing || reviewLines == null || current.aiReview == AiReview.Running) return
        val ranOn = engine
        if (!ranOn.canRefine) return
        aiReviewJob?.cancel()
        aiReviewJob = viewModelScope.launch {
            val ready = withContext(ioDispatcher) { ranOn.refineReady() }
            val now = _state.value
            if (now.image !== image || analyzedWith != ranWith || now.analyzing || now.aiReview == AiReview.Running) return@launch
            val next = if (ready) AiReview.Ready else AiReview.Hidden
            if (now.aiReview != next) uiState = now.copy(aiReview = next)
        }
    }

    /**
     * 用户点了「AI 增强识别」：大模型（本机的 Gemini Nano，或实验室里的外部模型）把整页文字再看一遍，
     * 新发现的直接打上码追加进 plan（宽屏列表上标「AI」），用「打码」里眼下的样式。
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
                // 只算盖住了的：马克笔、画笔、文本底下的字照样看得见，模型在那儿找到的不能因为压着一块标注就丢掉
                ranOn.refine(lines, current.plan.items.filter { it.look.style.redacts }.map { it.quad }, dismissed.map { it.quad })
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
            val added = MaskPlanFactory.itemsFrom(extra, now.maskLook).filter { it.candidateId !in known }
            // 打到一半的字先收尾，算它自己一步（见 reanalyze）
            if (added.isNotEmpty()) {
                finishLookEdit()
                undoStack.push(now.plan)
            }
            uiState = _state.value.copy(
                aiReview = AiReview.Done(found = added.size),
                aiReviewsFinished = now.aiReviewsFinished + 1,
                plan = now.plan.copy(items = now.plan.items + added),
            ).withHistoryFlags()
            // 大爆炸开着时结果到了：这一批自己是一步，大爆炸接着打的码从这一刻算起（见 commitBigBang）
            if (added.isNotEmpty() && bigBangOrigin != null) bigBangOrigin = _state.value.plan
        }
    }

    // ---------- 编辑 ----------

    /**
     * 点一下（spec §7.3，2026-10-14、2026-10-15 修订；点中了什么见 GestureRules.tapTarget）：
     * - 识别出的码、点一行字打上的码：拿掉。撤销能回来，识别出的原地再点一下也原样回来；
     * - 没打码的字：它所在的那一段打上码（见 [lines]），用「打码」里眼下的样式（[EditorUiState.maskLook]）。
     *   手里拿着马克笔、画笔时也一样，它们只做标记，不拿来打码；
     * - 手动框：不拿掉，而是选中它，调出手柄与删除。手动框是用户自己画的，画它就是要打码，不想要就删掉——
     *   点它要是把码去掉了，「点一下再调一调」就成了「点一下码没了」。
     *
     * 吸管在等着取色时，这一下只取颜色。
     *
     * 文本（2026-10-15）：选中的文本再点一下是改它的字（[editSelectedText]）。手里拿着文本时，点的不是哪一块码——
     * 空白、没打码的字、点掉过的码——就是在这儿写字（[placeText]），不打码：拿起文本就是要往图上写字。
     * 这时选中着什么的话，这一下只是取消选中，和别的时候点空白一样：写完一块点一下别处，不会又开始写一块。
     *
     * 大爆炸开着时，点的是字：选上或取消那一个字（[toggleBigBangGlyph]），不打码、不写字。
     *
     * @param reach 点没落在任何一段字里时，离一段多近也算点中它（图像像素）
     */
    fun onTap(imagePoint: PointF, reach: Float = 0f) {
        if (_state.value.colorPick != null) {
            pickColorAt(imagePoint)
            return
        }
        _state.value.bigBang?.let { bang ->
            bang.page.glyphAt(imagePoint, reach)?.let { toggleBigBangGlyph(it.id) }
            return
        }
        val s = _state.value
        val plan = s.plan
        val gone = dismissed.filter { plan.find(it.candidateId) == null }
        val target = GestureRules.tapTarget(plan.items, lines, imagePoint, reach, gone)
        if (target is GestureRules.TapTarget.Item && target.item.candidateId == s.selectedManualId &&
            target.item.look.style == MaskStyle.TEXT
        ) {
            editSelectedText()
            return
        }
        if (s.brush.style == MaskStyle.TEXT && target !is GestureRules.TapTarget.Item) {
            if (s.selectedItem != null) clearSelection() else placeText(imagePoint)
            return
        }
        // 还没写字的那一处作罢：这一下点的是别的
        if (s.textPlacement != null) _state.value = s.copy(textPlacement = null)
        when (target) {
            is GestureRules.TapTarget.Item -> tapItem(target.item)
            is GestureRules.TapTarget.Dismissed -> {
                mutate { it.add(target.item) }
                finishLookEdit()
                uiState = _state.value.copy(selectedManualId = null)
            }
            is GestureRules.TapTarget.Line -> maskLine(target.quad)
            null -> {
                // 点空白算点了别处：选中态收起
                finishLookEdit()
                uiState = _state.value.copy(selectedManualId = null)
            }
        }
    }

    /**
     * 宽屏的列表上点了一行：和在画布上点那一块一样，识别出的、点上的拿掉，手动框选中。
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
            uiState = _state.value.copy(selectedManualId = item.candidateId, textPlacement = null)
            return
        }
        // 点它们也算点了别处：选中态收起
        if (!item.byUser) dismissed = dismissed.filterNot { it.candidateId == item.candidateId } + item
        mutate { it.remove(item.candidateId) }
        finishLookEdit()
        uiState = _state.value.copy(selectedManualId = null)
    }

    /** 点了没打码的字：它所在的那一段打上码，用「打码」里眼下的样式。和点识别出的码一样，不进选中态。 */
    private fun maskLine(quad: Quad) {
        val item = MaskItem(
            candidateId = "line-${UUID.randomUUID()}",
            quad = quad,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.LINE,
            look = _state.value.maskLook,
        )
        mutate { it.add(item) }
        uiState = _state.value.copy(selectedManualId = null)
    }

    // ---------- 大爆炸（2026-10-16，见 ui/bigbang） ----------

    /** [reviewLines] 拆成的字，和拆的是哪一份。同一份文字长按几次只拆一遍。 */
    private var bigBangPage: Pair<List<TextLine>, BigBangPage>? = null

    /** 这一划是在选字还是在取消（起手那个字定的，见 BigBangSelection.sweep）。 */
    private var bigBangSweepSelects = true

    /**
     * 这一次大爆炸开始前（或上一回压进撤销栈后）的 plan。开着时点字就打码，一下一下都不进撤销栈，
     * 收起时整次压一步（[commitBigBang]）：撤销一下，这一次大爆炸打的码全拿掉。不开着是 null。
     */
    private var bigBangOrigin: MaskPlan? = null

    /**
     * 这一次大爆炸打在图上的码：每一块盖着哪几个字（Glyph.id 的区间）、是哪一块码。块变长、变短还是同一块码（id 不变），
     * 划字时画布上不会每多一个字就把整块重新抹一遍（MaskToggles 按 id 认码）。
     */
    private var bigBangRuns: List<Pair<IntRange, String>> = emptyList()

    /**
     * 长按（spec §7.3，2026-10-16 增补）：按在识别出的字上（离一个字 [reach] 以内也算），打开大爆炸，按着的那个字马上打上码，
     * 手指不抬接着划是接着打（[sweepBigBang]）。返回打开了没有：没按在字上、还没识别完、识别结果是从重建里恢复的
     * （只留下了每一段的框，没有字），都不打开，这一下照旧——松手是点一下，拖开是画框。
     *
     * 打开时收起别的（见 [beginBigBang]）。
     */
    fun onLongPress(imagePoint: PointF, reach: Float): Boolean {
        val page = bigBangPageToOpen() ?: return false
        val glyph = page.glyphAt(imagePoint, reach) ?: return false
        beginBigBang(BigBangState(page, focusLine = glyph.line, holding = true))
        selectBigBang(setOf(glyph.id))
        return true
    }

    /**
     * 底栏点了大爆炸（2026-10-16，「工具」里的，或用不了 AI 时那颗圆钮）：长按要先知道有这回事，点按钮不用。不从哪个字起手，面板马上出来，
     * 从第一行字摆起，一个字也没打。打不开的时候和长按一样（没识别完、没认出字、从重建里恢复的），按钮这时灰着。
     */
    fun openBigBang() {
        val page = bigBangPageToOpen() ?: return
        beginBigBang(BigBangState(page))
    }

    /** 眼下能打开大爆炸的话，这张图拆好的字；不能就是 null。 */
    private fun bigBangPageToOpen(): BigBangPage? {
        val s = _state.value
        if (s.image == null || s.loading || s.analyzing || s.colorPick != null || s.bigBang != null) return null
        return currentBigBangPage()?.takeUnless { it.isEmpty }
    }

    /** 打开大爆炸，收起别的：选中的框、写到一半的字、吸管、样式和用途水印的面板，面板的位置这时归大爆炸。 */
    private fun beginBigBang(bang: BigBangState) {
        commitDrag()
        finishLookEdit()
        bigBangSweepSelects = true
        bigBangOrigin = _state.value.plan
        bigBangRuns = emptyList()
        uiState = _state.value.copy(
            bigBang = bang,
            selectedManualId = null,
            textPlacement = null,
            textFocus = false,
            stylePanelOpen = false,
            purposeSheetVisible = false,
        )
    }

    /** 长按打开大爆炸的那根手指抬起来了：面板这时才出来（见 BigBangState.holding）。 */
    fun releaseBigBang() {
        val bang = _state.value.bigBang ?: return
        if (bang.holding) uiState = _state.value.copy(bigBang = bang.copy(holding = false))
    }

    private fun currentBigBangPage(): BigBangPage? {
        val lines = reviewLines ?: return null
        bigBangPage?.takeIf { it.first === lines }?.let { return it.second }
        return BigBangPage.of(lines).also { bigBangPage = lines to it }
    }

    /** 点了一个字（面板上的字块，或画布上的字）：没打码的打上，这一次打上的拿掉。 */
    fun toggleBigBangGlyph(id: Int) {
        val bang = _state.value.bigBang ?: return
        if (id !in bang.page.glyphs.indices) return
        selectBigBang(BigBangSelection.toggle(bang.selected, id))
    }

    /** 一划从字 [id] 起手：它没选中，这一划就是打码，选中了就是拿掉；它自己先照这个改。 */
    fun startBigBangSweep(id: Int) {
        val bang = _state.value.bigBang ?: return
        if (id !in bang.page.glyphs.indices) return
        bigBangSweepSelects = id !in bang.selected
        sweepBigBang(listOf(id))
    }

    /** 这一划又经过了 [ids] 这几个字：照起手时定下的（[startBigBangSweep]、长按）打上或拿掉。 */
    fun sweepBigBang(ids: List<Int>) {
        val bang = _state.value.bigBang ?: return
        selectBigBang(BigBangSelection.sweep(bang.selected, ids, bigBangSweepSelects))
    }

    /**
     * 画布上一指划着字、第二根手指落下来变成了缩放平移：这一划作罢，选中的字回到它起手前的样子（[ids]），
     * 和画到一半的框、线一样。
     */
    fun restoreBigBangSelection(ids: Set<Int>) = selectBigBang(ids)

    /**
     * 选中的字换成 [selected]，图上这一次大爆炸打的码跟着换：选中的、连着的字一块码（BigBangPage.runs），
     * 用「打码」里眼下的样式，和点字打码一样是 [DetectorSource.LINE]、`kind = MANUAL`：点一下拿掉，换方案重跑时留着。
     * 已经被一块别的码整个盖住的那一块不再打一遍。不进撤销栈，见 [bigBangOrigin]。
     */
    private fun selectBigBang(selected: Set<Int>) {
        val s = _state.value
        val bang = s.bigBang ?: return
        val ids = selected.filterTo(HashSet()) { it in bang.page.glyphs.indices }
        if (ids == bang.selected) return
        val mine = bigBangRuns.mapTo(HashSet()) { it.second }
        val previous = s.plan.items.filter { it.candidateId in mine }.associateBy { it.candidateId }
        val others = s.plan.items.filterNot { it.candidateId in mine }
        val masks = others.filter { it.look.style.redacts }.map { it.quad }
        val runs = ArrayList<Pair<IntRange, String>>()
        val items = ArrayList<MaskItem>()
        for (run in bang.page.runs(ids)) {
            val quad = BigBangPage.runQuad(run)
            if (masks.any { mask -> quad.points().all(mask::contains) }) continue
            val range = run.first().id..run.last().id
            // 和上一回的哪一块盖着同一个字，就还是那一块码
            val id = bigBangRuns.firstOrNull { (r, id) -> runs.none { it.second == id } && r.first <= range.last && range.first <= r.last }
                ?.second ?: "line-${UUID.randomUUID()}"
            runs += range to id
            items += previous[id]?.copy(quad = quad) ?: MaskItem(
                candidateId = id,
                quad = quad,
                kind = SensitiveKind.MANUAL,
                source = DetectorSource.LINE,
                look = s.maskLook,
            )
        }
        bigBangRuns = runs
        uiState = s.copy(bigBang = bang.copy(selected = ids), plan = s.plan.copy(items = others + items))
    }

    /**
     * 这一次大爆炸打的码压进撤销栈，整次一步（和拖框一样，开始前的那一份压进去）。大爆炸还开着的话接着打，再压就从这一刻算起：
     * 开着时有别的改动起手（AI 增强识别的结果到了、收尾一段面板调节），各算各的一步，撤销的先后不乱。
     */
    private fun commitBigBang() {
        val origin = bigBangOrigin ?: return
        val plan = _state.value.plan
        bigBangOrigin = if (_state.value.bigBang != null) plan else null
        if (origin != plan) undoStack.push(origin)
    }

    /** 大爆炸收起：打上的码留着，整次是一步撤销。 */
    private fun endBigBang() {
        commitBigBang()
        bigBangOrigin = null
        bigBangRuns = emptyList()
    }

    /** 面板右上角的「完成」、返回：收起大爆炸，这一次打上的码都留着。想全拿掉，撤销一下。 */
    fun closeBigBang() {
        if (_state.value.bigBang == null) return
        endBigBang()
        uiState = _state.value.copy(bigBang = null).withHistoryFlags()
    }

    /**
     * 拉完一个框：落笔即打码（或标记），画完进选中态。
     * 手里拿着文本时是在框里写字：字号照框的高（画笔从此也是这个字号），字以框的中心为中心（见 [placeText]）。
     */
    fun onManualBox(quadInImageSpace: Quad) {
        val s = _state.value
        if (s.bigBang != null) return               // 大爆炸开着时一指划的是字（见 sweepBigBang）
        if (s.brush.style == MaskStyle.TEXT) {
            val image = s.image ?: return
            val box = quadInImageSpace.bounds()
            val shortSide = min(image.width, image.height).toFloat()
            val size = (TextStamp.sizeForHeight(box.height()) / shortSide).coerceIn(MaskOptions.TEXT_SIZE_RANGE)
            setBrush(s.brush.copy(options = s.brush.options.copy(textSize = size)))
            placeText(PointF(box.centerX(), box.centerY()))
            return
        }
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quadInImageSpace,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            look = _state.value.brush,          // 落笔即打码，用画笔：样式栏上选的就是这一笔的样子
        )
        mutate { it.add(item) }
        // 画完直接进选中态：手柄就在手边，不用再点一下才能调大小；面板上接着调，改的就是这个框
        uiState = _state.value.copy(selectedManualId = item.candidateId, colorPick = null, textPlacement = null)
    }

    /**
     * 手画完一笔（马克笔、画笔选了手画，或者画笔选了箭头）：和画完一个框一样，落笔就打上、画完进选中态，拖得动、删得掉。
     * [quad] 是这一笔的外接框，四边让出半个笔宽、箭头让出头的半宽（见 Ink.fit、Ink.margin）。
     * 箭头只要头尾两个点：从按下的地方指到松手的地方。
     */
    fun onInk(pointsInImageSpace: List<PointF>) {
        val s = _state.value
        if (s.bigBang != null) return
        val image = s.image ?: return
        if (pointsInImageSpace.isEmpty()) return
        val brush = s.brush
        val half = brush.options.halfWidth(brush.style, min(image.width, image.height).toFloat())
        val arrow = brush.options.shapeFor(brush.style) == StrokeShape.ARROW
        val points = if (arrow) listOf(pointsInImageSpace.first(), pointsInImageSpace.last()) else pointsInImageSpace
        val (quad, ink) = Ink.fit(points, Ink.margin(half, arrow), arrow)
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quad,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            look = brush,
            ink = ink,
        )
        mutate { it.add(item) }
        uiState = _state.value.copy(selectedManualId = item.candidateId, colorPick = null, textPlacement = null)
    }

    fun clearSelection() {
        finishLookEdit()
        uiState = _state.value.copy(selectedManualId = null, textPlacement = null)
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
        val next = if (item.look.style == MaskStyle.TEXT) resizedText(dragOrigin?.find(id) ?: item, quad) else item.copy(quad = quad)
        uiState = _state.value.copy(plan = _state.value.plan.replace(next))
    }

    /**
     * 拖文本的框（[proposed] 是照拖框的规矩算出来的，见 SelectionHandles）：拖框体是挪，照挪；
     * 拖角是放大缩小，字不变形——按拖得多的那一边等比缩放，拖的那一角的对角不动。字号跟着改，面板上看得到。
     * 放大到出了图就停在图边上（出了图的字导出时就没了），对角贴着图边时往里挪；缩小最小到字号的下限，
     * 原本就比下限小的（一长行缩到正好写得下，见 TextStamp.boxAt）不往大里拽。
     * [origin] 是起手时的样子：每一帧都从它算，不在上一帧上接着缩。
     */
    private fun resizedText(origin: MaskItem, proposed: Quad): MaskItem {
        val text = origin.text
        val image = _state.value.image
        val from = origin.quad.bounds()
        val to = proposed.bounds()
        val moved = abs(to.width() - from.width()) < SAME_EDGE && abs(to.height() - from.height()) < SAME_EDGE
        if (text == null || image == null || moved) return origin.copy(quad = proposed)
        val sw = to.width() / from.width()
        val sh = to.height() / from.height()
        val scale = if (abs(ln(sw)) >= abs(ln(sh))) sw else sh
        val shortSide = min(image.width, image.height).toFloat()
        val range = MaskOptions.TEXT_SIZE_RANGE
        val was = TextStamp.sizeIn(text, origin.quad)
        val unit = TextStamp.boxSize(text, 1f)
        val largest = min(range.endInclusive * shortSide, min(image.width / unit.width, image.height / unit.height))
        val smallest = min(range.start * shortSide, was)
        val size = (was * scale).coerceIn(smallest, max(smallest, largest))
        val box = TextStamp.boxSize(text, size)
        // 动了的那一边是拖的那一角所在的边，对边不动；出了图就整块挪回来
        val left = (if (abs(to.left - from.left) > SAME_EDGE) from.right - box.width else from.left)
            .coerceIn(0f, max(0f, image.width - box.width))
        val top = (if (abs(to.top - from.top) > SAME_EDGE) from.bottom - box.height else from.top)
            .coerceIn(0f, max(0f, image.height - box.height))
        return origin.copy(
            quad = Quad.fromRect(RectF(left, top, left + box.width, top + box.height)),
            look = origin.look.copy(options = origin.look.options.copy(textSize = size / shortSide)),
        )
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
     * 用途水印的面板开着时也一样是面板开着：换成这一种样式的面板，点的是当前这一种也是。
     * 换样式本身照 [setStyle] 的规矩：管之后画的，选中了同一组的手动框时也管那个框。
     */
    fun onStyleChipClick(style: MaskStyle) {
        val s = _state.value
        if (s.purposeSheetVisible) {
            _state.value = s.copy(purposeSheetVisible = false, stylePanelOpen = true, colorPick = null)
            if (style != s.barLook.style) setStyle(style)
            return
        }
        if (style == s.barLook.style) {
            if (s.stylePanelOpen) dismissStylePanel() else _state.value = s.copy(stylePanelOpen = true, colorPick = null)
            return
        }
        setStyle(style)
    }

    /** 换样式。这种样式的参数用画笔里调好的；选中了框时，别的几项照框自己的。 */
    fun setStyle(style: MaskStyle) {
        val s = _state.value
        editLook(MaskLook(style, s.barLook.options.withParamsOf(style, s.brush.options)))
    }

    /**
     * 面板上改了样式或参数。
     *
     * 改的是画笔：从之后画的起生效。
     * 画笔只收这一种样式的那几项：选中的框带着它画上时的一整份，别的样式那几项可能早就过时了，不能顶掉画笔里调好的。
     * 换成「打码」里的样式，它也成了之后打码用的（见 [EditorUiState.maskLook]）。
     *
     * 设置里开着「换样式时一起换」（出厂开着）、改后是「打码」里的样式时，已经打好的色块、马赛克、模糊也都跟着换：
     * 一张图一般只用一种打码样式。参数也一起改，不然换成马赛克再调粗细，图上就是两种粗细的马赛克。
     * 马克笔、画笔画的标注不跟着变，见 [MaskPlan.restyleRedactions]。
     *
     * 选中了手动框时，那个框也一起换成改后的样子（刚画完的框就是选中的，画完再调颜色是最顺手的用法）。
     * 只限同一类（见 MaskStyle.restylesTo）：马克笔、画笔不往打码的框上用，打码的样式也不把画的线变成一块码，文本只和文本换。
     * 换到另一类是换手里的工具，框原样留着，选中收起。文本换了字号，框跟着变大变小（见 [restyled]）。
     *
     * @param inProgress 滑条还没松手。拖动期间码跟着变，但不压撤销栈，松手时由 [finishLookEdit] 补一次，
     *   与拖框的 previewSelectedQuad / commitDrag 同一个道理。
     */
    fun editLook(look: MaskLook, inProgress: Boolean = false) {
        // 打完字再换颜色、字号：那是另一步，撤销时先撤它，不连着字一起没了
        if (typing) finishLookEdit()
        val next = look.normalized()
        if (_state.value.colorPick != null) _state.value = _state.value.copy(colorPick = null)
        val selected = _state.value.selectedItem
        // 箭头只有画笔画得出：选着一支箭头拿起马克笔，箭头原样留着
        if (selected != null && (!selected.look.style.restylesTo(next.style) || selected.ink?.arrow == true && next.style != MaskStyle.PEN)) {
            clearSelection()
        }
        setBrush(MaskLook(next.style, _state.value.brush.options.withParamsOf(next.style, next.options)))
        // 还没写字的那一处：换成别的工具就作罢；它带着自己的样子（字删光了拿掉的那一块）时，面板上改的是它
        _state.value.textPlacement?.let { at ->
            _state.value = _state.value.copy(
                textPlacement = if (next.style != MaskStyle.TEXT) null else if (at.look != null) at.copy(look = next) else at,
            )
        }
        val s = _state.value
        var plan = s.plan
        s.selectedItem?.let { plan = plan.replace(restyled(it, next)) }
        if (restyleAll && next.style.redacts) plan = plan.restyleRedactions(next)
        if (plan != s.plan) {
            if (lookEditOrigin == null) lookEditOrigin = s.plan
            uiState = s.copy(plan = plan)
        }
        if (!inProgress) finishLookEdit()
    }

    /**
     * 选中的那一块换成 [look]。文本换了字号，框跟着变大变小，中心不动（见 [textAnchor]）。
     * 手画的一笔、箭头换了笔宽，框让出的那一圈跟着变（见 Ink.margin），线还在原处：箭头指的地方不动。
     */
    private fun restyled(item: MaskItem, look: MaskLook): MaskItem {
        val ink = item.ink
        val image = _state.value.image
        if (ink != null && image != null) {
            val shortSide = min(image.width, image.height).toFloat()
            val was = Ink.margin(item.look.options.halfWidth(item.look.style, shortSide), ink.arrow)
            val now = Ink.margin(look.options.halfWidth(look.style, shortSide), ink.arrow)
            if (was == now) return item.copy(look = look)
            val (quad, refit) = Ink.fit(Ink.toImage(ink, item.quad, was), now, ink.arrow)
            return item.copy(look = look, quad = quad, ink = refit)
        }
        val text = item.text
        if (look.style != MaskStyle.TEXT || text == null || image == null || look.options.textSize == item.look.options.textSize) {
            return item.copy(look = look)
        }
        return item.copy(look = look, quad = textBox(text, look, anchorOf(item), image))
    }

    /** 滑条松手：把这一段连续调节作为一步压进撤销栈。 */
    fun finishLookEdit() {
        typing = false
        textAnchor = null
        // 大爆炸开着时有别的改动起手：这一次大爆炸到此为止打的码先算一步（见 commitBigBang）
        commitBigBang()
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
        _state.value = _state.value.copy(stylePanelOpen = false, colorPick = null, textPlacement = null, textFocus = false)
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
        if (target == ColorTarget.PURPOSE) {
            setPurposeStyle(s.purposeStyle.copy(color = color))
            return
        }
        val bar = s.barLook
        editLook(bar.copy(options = target.withColor(bar.options, color)))
    }

    /**
     * 画笔变了：立即生效，落盘做一次去抖——拖滑条时每一帧都会进来，不让 DataStore 跟着每帧写一次。
     * 换成的是「打码」里的样式，它也是之后打码用的那一种（[EditorUiState.maskStyle]）；落盘的样式只记这一种，
     * 马克笔、画笔只记参数。
     */
    private fun setBrush(look: MaskLook) {
        brushTouched = true
        val s = _state.value
        if (look == s.brush) return
        val maskStyle = if (look.style.redacts) look.style else s.maskStyle
        _state.value = s.copy(brush = look, maskStyle = maskStyle)
        brushSave?.cancel()
        brushSave = viewModelScope.launch {
            delay(BRUSH_SAVE_DEBOUNCE_MS)
            settings.setLastStyle(maskStyle)
            settings.setMaskOptions(look.options)
        }
    }

    fun undo() {
        // 大爆炸开着时撤销：先收起它（这一次打的码压成一步），撤销的就是这一次打的码
        closeBigBang()
        finishLookEdit()
        val restored = undoStack.undo(_state.value.plan) ?: return
        uiState = _state.value.copy(plan = restored, textPlacement = null).withHistoryFlags()
    }

    fun redo() {
        closeBigBang()
        finishLookEdit()
        val restored = undoStack.redo(_state.value.plan) ?: return
        uiState = _state.value.copy(plan = restored, textPlacement = null).withHistoryFlags()
    }

    // ---------- 文本 ----------

    /**
     * 拿着文本点了图上一处（或拉了一个框）：在这儿写字。文本的面板长出来，输入框拿焦点、弹出输入法；
     * 打下第一个字时这块文本才落下（见 [setText]），一个字没打就收起面板，什么也不留。
     */
    private fun placeText(center: PointF) {
        finishLookEdit()
        uiState = _state.value.copy(
            selectedManualId = null,
            textPlacement = TextPlacement("manual-${UUID.randomUUID()}", center),
            stylePanelOpen = true,
            purposeSheetVisible = false,
            colorPick = null,
            textFocus = true,
        )
    }

    /** 改选中的文本：面板长出来，输入框拿焦点，改的字当场落在图上。再点一下选中的文本、选中时浮出来的「编辑」都是它。 */
    fun editSelectedText() {
        val s = _state.value
        if (s.selectedText == null) return
        _state.value = s.copy(stylePanelOpen = true, purposeSheetVisible = false, colorPick = null, textFocus = true)
    }

    /** 输入框拿到焦点了：放下 [EditorUiState.textFocus]，面板收起再打开不会又抢一次焦点。 */
    fun textFocusTaken() {
        if (_state.value.textFocus) _state.value = _state.value.copy(textFocus = false)
    }

    /**
     * 文本面板的输入框改了。选中的文本当场换成这几个字，框跟着变宽变窄，中心不动；
     * 还没写字的那一处（[EditorUiState.textPlacement]）打下第一个字时落下、选中；
     * 都没有时（拿着文本点开面板就打字）写在图的正中。字删光了，那一块先拿掉，位置和样子留着，接着打还在原处。
     *
     * 只写一行：换行（粘贴进来的）换成空格。
     * 打字和拖滑条一样是一段连续的调节，整段一步撤销：面板收起、点别处时收尾（见 [finishLookEdit]）。
     */
    fun setText(typed: String) {
        val image = _state.value.image ?: return
        val editing = _state.value.selectedText
        if (editing == null && _state.value.barLook.style != MaskStyle.TEXT) return
        val text = typed.replace('\n', ' ')
        // 打字前另一段调节（拖着的滑条）先收尾
        if (!typing) finishLookEdit()
        val s = _state.value
        val origin = lookEditOrigin ?: s.plan
        val next = when {
            editing != null && text.isBlank() -> s.copy(
                plan = s.plan.remove(editing.candidateId),
                selectedManualId = null,
                textPlacement = TextPlacement(editing.candidateId, anchorOf(editing), editing.look, s.plan.items.indexOf(editing)),
            )
            editing != null -> s.copy(
                plan = s.plan.replace(editing.copy(text = text, quad = textBox(text, editing.look, anchorOf(editing), image))),
            )
            text.isBlank() -> return
            else -> {
                val at = s.textPlacement ?: TextPlacement("manual-${UUID.randomUUID()}", PointF(image.width / 2f, image.height / 2f))
                val look = at.look ?: s.brush
                textAnchor = at.id to at.center
                val item = MaskItem(
                    candidateId = at.id,
                    quad = textBox(text, look, at.center, image),
                    kind = SensitiveKind.MANUAL,
                    source = DetectorSource.MANUAL,
                    look = look,
                    text = text,
                )
                // 字删光了拿掉的那一块回到它原来那一层：用户加的码按先后叠，换了位置就压到了别的上面
                val items = s.plan.items.toMutableList().apply { add(at.index.takeIf { it in 0..size } ?: size, item) }
                s.copy(plan = s.plan.copy(items = items), selectedManualId = item.candidateId, textPlacement = null)
            }
        }
        typing = true
        if (lookEditOrigin == null) lookEditOrigin = origin
        // 撤销、重做按钮不等收尾就对：写了字就有得撤，也就没得重做了
        uiState = next.copy(
            canUndo = undoStack.canUndo || next.plan != origin,
            canRedo = undoStack.canRedo && next.plan == origin,
        )
    }

    /** [item] 这一段调节里的中心（见 [textAnchor]），头一回问时记下它眼下的中心。 */
    private fun anchorOf(item: MaskItem): PointF =
        textAnchor?.takeIf { it.first == item.candidateId }?.second
            ?: item.quad.center().also { textAnchor = item.candidateId to it }

    /** 照 [look] 的字号、以 [center] 为中心写下 [text] 的框，挪进图里（见 TextStamp.boxAt）。 */
    private fun textBox(text: String, look: MaskLook, center: PointF, image: SourceImage): Quad =
        TextStamp.boxAt(text, look.options.textSize * min(image.width, image.height), center, image.width, image.height)

    private fun Quad.center(): PointF = bounds().let { PointF(it.centerX(), it.centerY()) }

    // ---------- 导出 ----------

    /**
     * 导出。原先还有圈出没打码的内容时先拦一下（spec §7.4）；2026-10-14 起没有「圈出」，图上是什么样就导出什么样。
     */
    fun requestExport() {
        runExport(_state.value.plan)
    }

    /**
     * 批量导出：看过的一次性全部导出（spec §7.6）。
     *
     * 先等还没跑完的识别：切走的那几张可能还在后台识别，这时导出，本该直接打码的东西就原样出去了——
     * 而用户根本看不到它们还没识别完。等的这一会儿按钮显示「导出中…」。
     *
     * 还有没打开过的图时先问一句（[reviewUnopened] / [exportOpenedOnly]）：没打开过的图没识别、也没人看过，不能替用户导出。
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
            else runBatchExport(batch)
        }
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

    /** 「还有几张没看过」里的「只导出看过的」：没打开过的不导出，看过的照常导出。 */
    fun exportOpenedOnly() {
        uiState = _state.value.copy(unopenedDialogVisible = false)
        runBatchExport(_state.value.liveBatch ?: return)
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
     * 打开调节面板，水印不跟着加上：文字框是空的就先填上上次的文字或默认措辞，加不加由面板上的「应用」开关定。
     * 它和样式的调节面板是同一块地方（手机上都是底栏的胶囊长出来的），一次只开一个：样式面板开着就换成它。
     */
    fun showPurposeSheet() {
        // 大爆炸开着时面板的位置归它，开出来也看不见（顶栏的按钮这时也收起来了）
        if (_state.value.bigBang != null) return
        finishLookEdit()
        val s = _state.value
        _state.value = s.copy(
            purposeSheetVisible = true,
            stylePanelOpen = false,
            colorPick = null,
            textPlacement = null,
            textFocus = false,
            purposeDraft = s.purposeDraft.ifBlank { lastPurposeText ?: defaultPurposeText },
        )
    }

    /** 点了水印（手机底栏「编辑」里的那一项，宽屏顶栏上的按钮）：收着就打开，开着就收起，和再点一下当前的样式一样。 */
    fun togglePurposeSheet() {
        if (_state.value.purposeSheetVisible) dismissPurposeSheet() else showPurposeSheet()
    }

    /** 收起面板，水印保持现状。 */
    fun dismissPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = false, colorPick = null) }

    /** 开关开着时边打字边生效。清空就等于暂时不加，接着打字又回来。 */
    fun setPurposeText(text: String) {
        if (text.isNotBlank()) lastPurposeText = text
        _state.value = _state.value.copy(purposeDraft = text)
    }

    /** 面板上的「应用」开关：加上或去掉图上的水印，面板不收，文字留着。 */
    fun setPurposeOn(on: Boolean) {
        _state.value = _state.value.copy(purposeOn = on)
    }

    /**
     * 拖滑条时每一帧都会进来：状态即时更新给画布预览，落盘做一次去抖，
     * 不让 DataStore 跟着滑条每帧写一次。
     * 吸管在等着给水印取色时，挑了颜色、动了滑条，吸管就收起，和打码的样子一样（见 [editLook]）。
     */
    fun setPurposeStyle(style: PurposeWatermarkStyle) {
        val next = style.normalized()
        purposeStyleTouched = true
        if (_state.value.colorPick == ColorTarget.PURPOSE) _state.value = _state.value.copy(colorPick = null)
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
        uiState = _state.value.copy(unopenedDialogVisible = false)
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
        const val KEY_LINES = "lines"
        const val DEFAULT_PURPOSE_TEXT = "仅供办理 XX 使用"
        const val PURPOSE_STYLE_SAVE_DEBOUNCE_MS = 400L
        const val BRUSH_SAVE_DEBOUNCE_MS = 400L

        /** 拖文本的框时，边挪了不到这么多（图像像素）算没动：浮点数加减出来的零头。 */
        const val SAME_EDGE = 0.01f
    }

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        uiState = _state.value.copy(plan = plan)
    }
}

/** 存成一串数，一行八个（四个角的 x、y）：长截图上几百行，进 SavedStateHandle 也只有几 KB。 */
private fun packQuads(quads: List<Quad>): FloatArray = FloatArray(quads.size * 8).also { out ->
    quads.forEachIndexed { i, q ->
        q.points().forEachIndexed { k, p ->
            out[i * 8 + k * 2] = p.x
            out[i * 8 + k * 2 + 1] = p.y
        }
    }
}

private fun unpackQuads(packed: FloatArray): List<Quad> = (0 until packed.size / 8).map { i ->
    fun at(k: Int) = PointF(packed[i * 8 + k * 2], packed[i * 8 + k * 2 + 1])
    Quad(at(0), at(1), at(2), at(3))
}
