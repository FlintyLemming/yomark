package moe.flinty.yomark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.billing.RestoreResult
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.ui.batch.BatchThumbnails
import moe.flinty.yomark.ui.canvas.ImageCanvas
import moe.flinty.yomark.ui.canvas.ScanEffect
import moe.flinty.yomark.ui.components.AiReviewResultPill
import moe.flinty.yomark.ui.components.BatchFilmstrip
import moe.flinty.yomark.ui.components.BigBangActions
import moe.flinty.yomark.ui.components.BigBangPanel
import moe.flinty.yomark.ui.components.BigBangPanelModel
import moe.flinty.yomark.ui.components.BigBangPlacement
import moe.flinty.yomark.ui.components.DebugTraceSheet
import moe.flinty.yomark.ui.components.EditorActionRow
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.EditorTopBar
import moe.flinty.yomark.ui.components.FoldOnTouch
import moe.flinty.yomark.ui.components.FreeEditionDialog
import moe.flinty.yomark.ui.components.MaskItemList
import moe.flinty.yomark.ui.components.PanelTop
import moe.flinty.yomark.ui.components.PaywallDialog
import moe.flinty.yomark.ui.components.PurposePanelActions
import moe.flinty.yomark.ui.components.PurposePanelModel
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.ToolGroup
import moe.flinty.yomark.ui.components.UnopenedExportDialog
import moe.flinty.yomark.ui.components.WideEditorPanel
import moe.flinty.yomark.ui.components.nearColorsOf
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    onBuyClicked: () -> Unit = {},
    onRestoreClicked: suspend () -> RestoreResult = { RestoreResult.Unavailable },
    onUnlockFree: () -> Unit = {},
    /** 别的应用来要图（vm.forCaller），图导出好了：交回给它。 */
    onHandOver: (List<Uri>) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scanStyle by vm.scanStyle.collectAsStateWithLifecycle()
    val debugTrace by vm.debugTrace.collectAsStateWithLifecycle()
    val showTextBoxes by vm.showTextBoxes.collectAsStateWithLifecycle()
    val textBoxes by vm.textBoxes.collectAsStateWithLifecycle()
    val hasText by vm.hasText.collectAsStateWithLifecycle()
    var debugSheetOpen by rememberSaveable { mutableStateOf(false) }
    val registry = remember { RendererRegistry.default() }
    val snackbar = remember { SnackbarHostState() }
    val styleActions = remember(vm) { ViewModelStyleActions(vm) }
    val purposeActions = remember(vm) { ViewModelPurposeActions(vm) }
    val bigBangActions = remember(vm) { ViewModelBigBangActions(vm) }
    // 批量里各张的缩略图：胶片条上画它，切图时画布载入的那一下也先垫上它
    val context = LocalContext.current
    val thumbnailPx = with(LocalDensity.current) { THUMBNAIL_EDGE.roundToPx() }
    val thumbnails = remember(context, thumbnailPx) { BatchThumbnails(context.contentResolver, thumbnailPx) }
    // 顶栏的「对比」按住时，画布换成打码前的原图。只是看一眼，不进 ViewModel：松手、转屏都回到打码后
    val compare = remember { MutableInteractionSource() }
    val comparing by compare.collectIsPressedAsState()
    // 手机底栏展开着的那一组（打码、编辑、工具）。只是界面上的事，不进 ViewModel
    var toolGroup by rememberSaveable { mutableStateOf<ToolGroup?>(null) }
    // 大爆炸开着、长按的手指也抬起来了：手机上它的面板从「工具」长出来，那时展开着「工具」是因为它（见下面的 LaunchedEffect），
    // 收起时回到打开前展开着的那一组
    val bigBangUp = state.bigBang?.holding == false
    var bigBangHoldsRow by rememberSaveable { mutableStateOf(false) }
    var rowBeforeBigBang by rememberSaveable { mutableStateOf<ToolGroup?>(null) }
    // 调节面板（样式的、用途水印的）开着：手机上它从所在的那一组长出来，收起时底栏回到打开它之前那样（见下面的 LaunchedEffect）
    var panelHoldsRow by rememberSaveable { mutableStateOf(false) }
    var rowBeforePanel by rememberSaveable { mutableStateOf<ToolGroup?>(null) }

    LaunchedEffect(state.message) {
        when (val m = state.message) {
            is EditorMessage.Exported -> snackbar.showMessage(
                if (m.downscaled) context.getString(R.string.saved_downscaled, m.width, m.height)
                else context.getString(R.string.saved_to_gallery),
                vm,
            )
            is EditorMessage.BatchExported -> snackbar.showMessage(context.getString(R.string.saved_batch, m.count), vm)
            is EditorMessage.BatchPartial -> snackbar.showMessage(context.getString(R.string.error_batch_partial, m.ok, m.failed), vm)
            is EditorMessage.Error -> snackbar.showMessage(context.getString(m.text), vm)
            is EditorMessage.HandedOver -> onHandOver(m.uris)
            null -> Unit
        }
    }

    if (state.unopenedDialogVisible) {
        state.batch?.let { batch ->
            UnopenedExportDialog(
                unopened = batch.unopened,
                opened = batch.opened,
                onReview = vm::reviewUnopened,
                onExportOpened = vm::exportOpenedOnly,
                onDismiss = vm::dismissDialog,
            )
        }
    }

    if (state.paywallVisible && Edition.isFree) {
        FreeEditionDialog(unlocked = state.isPro, onUnlock = onUnlockFree, onDismiss = vm::dismissPaywall)
    } else if (state.paywallVisible) {
        PaywallDialog(onBuy = onBuyClicked, onRestore = onRestoreClicked, onDismiss = vm::dismissPaywall)
    }

    // 调试信息（设置 → 关于，连点版本号打开）：顶栏的按钮弹出来，盖在画布上
    if (debugTrace && debugSheetOpen) {
        val runs by vm.traceLog.runs.collectAsStateWithLifecycle()
        DebugTraceSheet(
            runs = runs,
            onRerun = if (state.image != null && !state.analyzing) vm::rerunRecognition else null,
            onDismiss = { debugSheetOpen = false },
        )
    }

    // 手机、宽屏，各自竖着拿、横着拿（见 EditorLayouts）。转向时 Configuration 变了，这里跟着重组
    val config = LocalConfiguration.current
    val layout = EditorLayouts.choose(config.screenWidthDp, config.screenHeightDp, config.smallestScreenWidthDp)
    val atSide = layout.atSide

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // 边到边（见 enableLightEdgeToEdge）。默认只让开系统栏；横屏时挖孔在侧边，会压住顶栏和底栏的按钮。
        // 输入法不在这里：只有用途水印面板里有输入框，开着它时画布和面板那一块自己让开（见下方 imePadding）。
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { padding ->
        // consumeWindowInsets：面板的 imePadding 只补输入法比导航栏多出来的那一截，不重复算导航栏
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val batch = state.batch
            val phone = layout == EditorLayout.PHONE
            val onExport = { if (batch != null) vm.exportBatch() else vm.requestExport() }
            // 别的应用来要图时，导出就是把图交回去
            val exportLabel = if (vm.forCaller) R.string.done else R.string.export
            val canCompare = state.image != null &&
                (state.purposeText != null || state.plan.items.isNotEmpty())
            val onToolGroup: (ToolGroup?) -> Unit = {
                // 收起这一组，从它长出来的调节面板（样式的、用途水印的、大爆炸的）也一起收
                if (it == null && state.stylePanelOpen) vm.dismissStylePanel()
                if (it == null && state.purposeSheetVisible) vm.dismissPurposeSheet()
                if (it == null && state.bigBang != null) {
                    bigBangHoldsRow = false
                    vm.closeBigBang()
                }
                toolGroup = it
            }
            val panelOpen = state.stylePanelOpen || state.purposeSheetVisible
            // 手机的调节面板开着（不在等吸管取色）时，点它外面的哪儿都和点面板右上角的箭头一样，只收面板（见 FoldOnTouch）。
            // 画布那一块由底栏铺（见 EditorBottomBar），顶栏这一截铺在这里：这一下只收起，不撤销、不导出
            val panelUp = phone && toolGroup != null && panelOpen && state.colorPick == null
            val foldPanel = { if (state.purposeSheetVisible) vm.dismissPurposeSheet() else vm.dismissStylePanel() }
            Box {
                EditorTopBar(
                    canUndo = state.canUndo, canRedo = state.canRedo,
                    onUndo = vm::undo, onRedo = vm::redo,
                    // 手机竖着拿时用途水印在底栏的「编辑」里，对比在底栏，导出挪到右上角。
                    // 大爆炸开着时水印收起来：面板的位置归它，水印面板开出来看不见
                    onPurpose = if (phone || state.bigBang != null) null else vm::togglePurposeSheet,
                    purposeOn = state.purposeText != null,
                    // 设置是单独的 Activity，改动写进 DataStore，回到这里时 ViewModel 已经按新方案重跑
                    onSettings = onSettings,
                    // 已购用户不该再看见购买入口；开源版留着它，点开是免费版说明与打赏
                    onRemoveWatermark = if (state.isPro && !Edition.isFree) null else vm::showPaywall,
                    onNavigateUp = onNavigateUp,
                    onDebug = if (debugTrace) ({ debugSheetOpen = true }) else null,
                    compare = if (phone) null else compare,
                    canCompare = canCompare,
                    onExport = if (phone) onExport else null,
                    exportLabel = exportLabel,
                    exporting = state.exporting,
                )
                if (panelUp) FoldOnTouch(foldPanel, Modifier.matchParentSize())
            }
            // 手机上调节面板是从展开的那一组长出来的。宽屏开着面板转成手机时，展开它所在的那一组接着显示它：
            // 用途水印在「编辑」里，样式在它自己那一组。展开着另一组时也换过去：
            // 展开着「打码」点了两下选中的文本，文本的面板该从「编辑」里长出来。
            // 收起面板（箭头、返回键、点它外面）时底栏回到打开它之前那样（2026-10-16）：展开着「编辑」写完一处字点一下图，
            // 还在「编辑」里；从收着的底栏点两下图上的字打开的，这一组跟着收回去。开着面板换了一样工具，这一组就是自己挑的，留着
            LaunchedEffect(phone, state.stylePanelOpen, state.purposeSheetVisible, state.barLook.style) {
                if (!phone) return@LaunchedEffect
                val own = when {
                    state.purposeSheetVisible -> ToolGroup.EDIT
                    state.stylePanelOpen -> ToolGroup.of(state.barLook.style)
                    else -> null
                }
                if (own == null) {
                    // 点底栏的返回收起这一组时已经收了，不再展开回去；大爆炸借着这一行时由它收回去
                    if (panelHoldsRow && toolGroup != null && !bigBangHoldsRow) toolGroup = rowBeforePanel
                    panelHoldsRow = false
                    return@LaunchedEffect
                }
                rowBeforePanel = if (panelHoldsRow) own else toolGroup
                panelHoldsRow = true
                if (toolGroup != own) toolGroup = own
            }
            // 「编辑」里的马克笔、画笔、文本只在「编辑」展开着时拿在手里（2026-10-16）：收起「编辑」（或者展开着别的组）就放下，
            // 换回「打码」里上次用的样式（见 EditorViewModel.backToRedact），收着的底栏点图、拖框总是打码。大爆炸借走这一行时不算
            LaunchedEffect(phone, toolGroup, bigBangHoldsRow, state.brush.style) {
                if (phone && toolGroup != ToolGroup.EDIT && !bigBangHoldsRow) vm.backToRedact()
            }
            // 大爆炸的面板也是从胶囊长出来的（见 EditorBottomBar）。用得了 AI 时从「工具」那一行长出来：开着时展开「工具」，
            // 从收着的那一行打开的（长按图上的字）胶囊从「工具」那颗圆钮撑开。用不了 AI 时「工具」里只有它，
            // 面板直接从大爆炸那颗圆钮长出来，不带工具那一行（ToolGroup.BIG_BANG）。打开时定下用哪一种，开着时不换。
            // 收起时回到打开前那样，原先展开着哪一组还是哪一组
            LaunchedEffect(phone, bigBangUp) {
                if (!phone) return@LaunchedEffect
                if (bigBangUp && !bigBangHoldsRow) {
                    rowBeforeBigBang = toolGroup
                    bigBangHoldsRow = true
                    toolGroup = if (state.aiReview == AiReview.Hidden) ToolGroup.BIG_BANG else ToolGroup.TOOLS
                } else if (!bigBangUp && bigBangHoldsRow) {
                    bigBangHoldsRow = false
                    toolGroup = rowBeforeBigBang
                }
            }
            // 吸管在等取色时，返回先收吸管；样式面板、用途水印面板开着时，返回先收面板，不直接退出编辑器。
            // 都不拦的时候不常驻 BackHandler（见 EditorActivity）
            BackHandler(enabled = state.colorPick != null, onBack = vm::cancelColorPick)
            BackHandler(
                enabled = state.colorPick == null && state.stylePanelOpen && !state.purposeSheetVisible,
                onBack = vm::dismissStylePanel,
            )
            BackHandler(enabled = state.colorPick == null && state.purposeSheetVisible, onBack = vm::dismissPurposeSheet)
            // 底栏展开着一组时，返回先收回去。上面几样开着时先收它们
            BackHandler(
                enabled = phone && toolGroup != null && state.colorPick == null && !panelOpen,
                onBack = { toolGroup = null },
            )
            // 大爆炸开着时返回先收起它，什么也不打。放在最后：后登记的先拦，底栏展开着一组时也先收它
            BackHandler(enabled = state.bigBang != null, onBack = vm::closeBigBang)
            CanvasAndPanel(
                panelAtSide = atSide,
                // 用途水印面板开着时输入法弹起来，整块让开：画布也缩到键盘上面，打字时看得到整张图上的水印
                // （手机上面板盖在图上，打字时它只留文字那一格，见 PurposePanelContent）。写文本也一样
                modifier = Modifier
                    .weight(1f)
                    .then(if (state.purposeSheetVisible || writingText(state)) Modifier.imePadding() else Modifier),
                canvas = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 批量里切图的那一下，画布上先垫着这一张的缩略图，载入完换成原图
                        if (batch != null && state.image == null) {
                            BatchPlaceholder(batch.current.uri, thumbnails, Modifier.fillMaxSize())
                        }
                        if (state.loading) CircularProgressIndicator()
                        ImageCanvas(
                            state = state,
                            registry = registry,
                            onTap = vm::onTap,
                            onManualBox = vm::onManualBox,
                            onInk = vm::onInk,
                            onResize = vm::previewSelectedQuad,
                            onCommitDrag = vm::commitDrag,
                            onDeleteSelected = vm::deleteSelected,
                            onEditSelected = vm::editSelectedText,
                            plainSelection = panelUp && writingText(state),
                            scanStyle = scanStyle,
                            showOriginal = comparing,
                            textBoxes = if (showTextBoxes) textBoxes else emptyList(),
                            onLongPress = vm::onLongPress,
                            onBigBangSweepStart = vm::startBigBangSweep,
                            onBigBangSweep = vm::sweepBigBang,
                            onBigBangRelease = vm::releaseBigBang,
                            onBigBangRestore = vm::restoreBigBangSelection,
                        )
                        Column(
                            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OriginalPill(visible = comparing)
                            AnalyzingPill(visible = state.analyzing && state.image != null)
                            // 宽屏的 AI 按钮上写着结果；手机上它收在底栏的「工具」里，跑完在这儿说一声
                            if (phone) AiReviewResultPill(state.aiReview, state.aiReviewsFinished)
                            ColorPickHint(visible = state.colorPick != null, onCancel = vm::cancelColorPick)
                        }
                    }
                },
                panel = {
                    val uris = remember(batch?.items) { batch?.items?.map { it.uri } }
                    val filmstrip: (@Composable (Modifier) -> Unit)? = if (batch != null && uris != null) {
                        { modifier ->
                            BatchFilmstrip(
                                uris = uris,
                                selected = batch.index,
                                onSelect = vm::selectImage,
                                thumbnails = thumbnails,
                                modifier = modifier,
                            )
                        }
                    } else null
                    val purpose = PurposePanelModel(
                        text = state.purposeDraft,
                        on = state.purposeOn,
                        style = state.purposeStyle,
                        open = state.purposeSheetVisible,
                        picking = state.colorPick == ColorTarget.PURPOSE,
                    )
                    // 长按的手指抬起来面板才出来
                    val bang = state.bigBang?.takeIf { !it.holding }
                    val covered = remember(bang?.page, state.plan.items) { bang?.page?.covered(state.plan.items).orEmpty() }
                    when {
                        // 手机上大爆炸的面板从底栏的胶囊长出来，画布跟着让到它上面
                        layout == EditorLayout.PHONE -> EditorBottomBar(
                            toolGroup = toolGroup,
                            onToolGroup = onToolGroup,
                            styleBar = styleBarModel(state),
                            styleActions = styleActions,
                            compare = compare,
                            canCompare = canCompare,
                            aiReview = state.aiReview,
                            onAiReview = vm::runAiReview,
                            bigBangReady = hasText && state.image != null && !state.loading && !state.analyzing,
                            // 和「编辑」里的水印一样，再点一下收起
                            onBigBang = { if (state.bigBang != null) vm.closeBigBang() else vm.openBigBang() },
                            bigBang = bang?.let { BigBangPanelModel(it, covered) },
                            bigBangActions = bigBangActions,
                            purpose = purpose,
                            purposeActions = purposeActions,
                            filmstrip = filmstrip,
                            pushCanvas = writingText(state),
                        )
                        // 宽屏上大爆炸开着时顶替样式、列表那一块，画布让到它上面（左边）
                        bang != null -> BigBangPanel(
                            bang = bang,
                            covered = covered,
                            actions = bigBangActions,
                            placement = if (atSide) BigBangPlacement.SIDE else BigBangPlacement.BOTTOM,
                        )
                        else -> WideEditorPanel(
                            atSide = atSide,
                            styleBar = styleBarModel(state),
                            styleActions = styleActions,
                            purpose = purpose,
                            purposeActions = purposeActions,
                            // 手机横着拿没有列表，右边那一栏只放样式和导出
                            list = if (layout.listed) ({ modifier ->
                                MaskItemList(
                                    image = state.image,
                                    plan = state.plan,
                                    // 换图时先载入、再识别：载入的那一下也算还没出结果，不该说「没有识别到」
                                    analyzing = state.analyzing || state.loading,
                                    selectedId = state.selectedManualId,
                                    onItemClick = vm::onListItemClick,
                                    modifier = modifier,
                                )
                            }) else null,
                            actionRow = { modifier ->
                                EditorActionRow(
                                    onExport = onExport,
                                    exportLabel = exportLabel,
                                    exporting = state.exporting,
                                    aiReview = state.aiReview,
                                    onAiReview = vm::runAiReview,
                                    filmstrip = filmstrip,
                                    modifier = modifier,
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 画布和它旁边的那一块（手机的底栏或右边那一栏，宽屏的样式与列表）。
 *
 * 旁边那一块先量：在下面时高度由它自己定，在右边（[panelAtSide]）时宽度由它自己定，画布占剩下的。
 * 手机的底栏例外：它占满整块，画布让到它给的 [PanelTop] 为止，上面那一截是往上长的调节面板，盖在图上。
 * 画布永远是第一个孩子：转个方向，那一块从下面挪到右边，画布还是原来那一个，放大到哪儿、识别动效走到哪儿都接着来。
 * 分开写成 Column 和 Row 的话，一转就换成了一块新画布。
 */
@Composable
private fun CanvasAndPanel(
    panelAtSide: Boolean,
    canvas: @Composable () -> Unit,
    panel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(CANVAS), propagateMinConstraints = true) { canvas() }
            Box(Modifier.layoutId(PANEL), propagateMinConstraints = true) { panel() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panelPart = measurables.first { it.layoutId == PANEL }
        val canvasPart = measurables.first { it.layoutId == CANVAS }
        if (panelAtSide) {
            val p = panelPart.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
            val c = canvasPart.measure(Constraints.fixed((width - p.width).coerceAtLeast(0), height))
            layout(width, height) {
                c.place(0, 0)
                p.place(width - p.width, 0)
            }
        } else {
            val p = panelPart.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
            // 手机的底栏占满这一块（调节面板往上长时盖在画布上），画布只让到它说的那条线为止（见 PanelTop）
            val top = p[PanelTop]
            val reserved = if (top == AlignmentLine.Unspecified) p.height else p.height - top
            val c = canvasPart.measure(Constraints.fixed(width, (height - reserved).coerceAtLeast(0)))
            layout(width, height) {
                c.place(0, 0)
                p.place(0, height - p.height)
            }
        }
    }
}

private const val CANVAS = "canvas"
private const val PANEL = "panel"

/**
 * 样式栏要显示的东西：样式栏上那一份（选中框的或画笔）、这张图上取到的近似色。
 * 近似色要逐块采样，只在用得上时取：面板开在色块上（「近似色」那一颗要画它），或者开着近似色（色块的小样子要画它）。
 */
@Composable
private fun styleBarModel(state: EditorUiState): StyleBarModel {
    val look = state.barLook
    val wantNear = look.options.solidNear || (look.style == MaskStyle.SOLID && state.stylePanelOpen)
    val selected = state.selectedItem
    val nearColors = remember(state.image, state.plan, selected?.candidateId, wantNear) {
        if (wantNear) nearColorsOf(state.image, state.plan, selected) else emptyList()
    }
    val text = state.selectedText
    return StyleBarModel(
        look = look,
        panelOpen = state.stylePanelOpen,
        colorPick = state.colorPick,
        nearColors = nearColors,
        text = text?.text.orEmpty(),
        textKey = text?.candidateId ?: state.textPlacement?.id,
        textFocus = state.textFocus,
    )
}

/** 文本的面板开着：输入框在上面，输入法可能弹着。 */
private fun writingText(state: EditorUiState) = state.stylePanelOpen && state.barLook.style == MaskStyle.TEXT

/** 用途水印面板的动作都交给 ViewModel。 */
private class ViewModelPurposeActions(private val vm: EditorViewModel) : PurposePanelActions {
    override fun onToggle() = vm.togglePurposeSheet()
    override fun onTextChange(text: String) = vm.setPurposeText(text)
    override fun onStyleChange(style: PurposeWatermarkStyle) = vm.setPurposeStyle(style)
    override fun onReset() = vm.resetPurposeStyle()
    override fun onApply(on: Boolean) = vm.setPurposeOn(on)
    override fun onCollapse() = vm.dismissPurposeSheet()
    override fun onPickColor(pick: Boolean) = if (pick) vm.startColorPick(ColorTarget.PURPOSE) else vm.cancelColorPick()
}

/** 大爆炸面板的动作都交给 ViewModel。 */
private class ViewModelBigBangActions(private val vm: EditorViewModel) : BigBangActions {
    override fun onToggle(id: Int) = vm.toggleBigBangGlyph(id)
    override fun onSweepStart(id: Int) = vm.startBigBangSweep(id)
    override fun onSweep(ids: List<Int>) = vm.sweepBigBang(ids)
    override fun onClose() = vm.closeBigBang()
}

/** 样式栏的动作都交给 ViewModel。 */
private class ViewModelStyleActions(private val vm: EditorViewModel) : StyleBarActions {
    override fun onStyleClick(style: MaskStyle) = vm.onStyleChipClick(style)
    override fun onLookChange(look: MaskLook, inProgress: Boolean) = vm.editLook(look, inProgress)
    override fun onLookChangeFinished() = vm.finishLookEdit()
    override fun onReset() = vm.resetLookOptions()
    override fun onCollapse() = vm.dismissStylePanel()
    override fun onPickColor(target: ColorTarget?) =
        if (target == null) vm.cancelColorPick() else vm.startColorPick(target)
    override fun onTextChange(text: String) = vm.setText(text)
    override fun onTextFocusTaken() = vm.textFocusTaken()
}

/**
 * 吸管在等着取色时浮在画布顶上的提示：点图上哪儿就取哪儿的颜色。右边一个「取消」。
 * 取的是原图的颜色，不是已经打了码的颜色——想让色块和底色融成一片，要的正是底下的颜色。
 */
@Composable
private fun ColorPickHint(visible: Boolean, onCancel: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Colorize, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eyedropper_hint), style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                ) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/**
 * 批量里切图时垫在画布上的缩略图，和画布一样按比例放在正中，载入完的原图正好盖在它上面。
 * 缩略图小，放大了是糊的，正好像是原图在一点点清楚起来。
 */
@Composable
private fun BatchPlaceholder(uri: Uri, thumbnails: BatchThumbnails, modifier: Modifier = Modifier) {
    val bitmap by produceState(thumbnails.cached(uri), uri) {
        if (value == null) value = thumbnails.load(uri)
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) }
}

/** 缩略图长边的上限：胶片条上的格子 40 dp 高，切图时画布上垫底也用它。 */
private val THUMBNAIL_EDGE = 128.dp

/** 按住「对比」时浮在画布顶上的标签：现在看的是原图。和识别中的提示条一样用 Box 画底色，不吃触摸。 */
@Composable
private fun OriginalPill(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.original), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/**
 * 识别中的提示条，与画布上的识别动效一起出现（PP-OCR 一张图约 2 秒）。
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
                stringResource(R.string.analyzing),
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
