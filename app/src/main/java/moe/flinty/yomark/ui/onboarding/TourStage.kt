package moe.flinty.yomark.ui.onboarding

import android.graphics.PointF
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.R
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.ui.AnalyzingPill
import moe.flinty.yomark.ui.EditorUiState
import moe.flinty.yomark.ui.canvas.GestureRules
import moe.flinty.yomark.ui.canvas.ImageCanvas
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.canvas.Viewport
import moe.flinty.yomark.ui.components.EDGE_ALPHA
import moe.flinty.yomark.ui.components.contentFoldTween
import moe.flinty.yomark.ui.components.floatingSurface
import moe.flinty.yomark.ui.components.foldTween

/**
 * 引导里的那张样图：就是编辑器的画布（ImageCanvas），识别动效、落码、点一下抹上去都是编辑器里那一套，不另画。
 * 一张圆角卡片，按图的比例摆，四周不留黑边：等待图标和磨砂散开都从图的正中出来。
 *
 * 画布上面盖一层只认点一下的：两指缩放、拖出框、长按大爆炸在引导里都不要。
 *
 * 欢迎页以外每页各有一块（翻页时两页的图一起在屏幕上），画的是同一份码。
 *
 * @param page 哪一页的图：识别动效只在「自动打码」上演；从「点文字打码」起读屏能双击遮住链接
 * @param showRing 「点文字打码」框出让人点的那段链接
 */
@Composable
internal fun TourStage(
    state: TourState,
    sample: SourceImage,
    scanStyle: ScanStyle,
    page: TourPage,
    showRing: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CARD_RADIUS)
    val colors = MaterialTheme.colorScheme
    Box(modifier.aspectRatio(TourSample.WIDTH / TourSample.HEIGHT.toFloat(), matchHeightConstraintsFirst = true)) {
        Box(Modifier.matchParentSize().floatingSurface(colors.surfaceContainer, Color.Transparent, CARD_RADIUS))
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(colors.surfaceContainer)
                // 细边画在图上面：floatingSurface 的边画在底下，满幅的图会把它盖住
                .border(1.dp, colors.outlineVariant.copy(alpha = EDGE_ALPHA), shape),
        ) {
            val registry = remember { RendererRegistry.default() }
            // 识别动效只在「自动打码」的图上演：后面几页的图在这一页一动时才出来，那时码已经落下
            val analyzing = !state.progress.revealed && page == TourPage.AUTO
            val plan = state.plan
            val ui = remember(sample, plan, analyzing) { EditorUiState(image = sample, plan = plan, analyzing = analyzing) }
            ImageCanvas(
                state = ui,
                registry = registry,
                onTap = { _, _ -> },
                onManualBox = {},
                onResize = {},
                onCommitDrag = {},
                onDeleteSelected = {},
                scanStyle = scanStyle,
            )
            LinkRing(sample, showRing, animate)
            TapCatcher(state, sample, linkAction = page > TourPage.AUTO)
            // 读屏从图上就念得到「识别中…」（见 TapCatcher），提示条不再念一遍
            AnalyzingPill(visible = analyzing, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clearAndSetSemantics {})
        }
    }
}

/**
 * 盖在画布上、只认点一下：点在哪儿换成图像坐标交给 [TourState.onTap]，手指的余量和编辑器一样。
 * 读屏把整张图当一个节点：念「示例聊天截图」、遮住了几处；从「点文字打码」起双击就是点那段链接。
 */
@Composable
private fun BoxScope.TapCatcher(state: TourState, sample: SourceImage, linkAction: Boolean) {
    val current by rememberUpdatedState(state)
    val label = stringResource(R.string.onboarding_sample)
    val status = if (!state.progress.revealed) {
        stringResource(R.string.analyzing)
    } else {
        stringResource(R.string.onboarding_sample_masked, state.plan.items.size)
    }
    val linkLabel = if (linkAction && state.settled) {
        stringResource(if (state.linkMasked) R.string.item_unmask else R.string.onboarding_mask_link)
    } else {
        null
    }
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(sample) {
                detectTapGestures { pos ->
                    val vp = Viewport.fit(sample.width, sample.height, size.width, size.height)
                    current.onTap(vp.screenToImage(PointF(pos.x, pos.y)), GestureRules.LINE_REACH_DP * density / vp.scale)
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = status
                liveRegion = LiveRegionMode.Polite
                if (linkLabel != null) {
                    onClick(label = linkLabel) {
                        current.toggleLink()
                        true
                    }
                }
            },
    )
}

/**
 * 「点文字打码」框出那段链接：主题色的一圈细线，外面衬一圈底色，白底的气泡上、天蓝的码上都看得清。
 * 等落码停稳一会儿再淡进来，之后慢慢一明一暗；点过字就淡掉。系统关了动画时不闪。
 */
@Composable
private fun BoxScope.LinkRing(sample: SourceImage, show: Boolean, animate: Boolean) {
    val shown by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = if (show) foldTween(true, delayMillis = RING_DELAY_MS) else contentFoldTween(false),
        label = "ringShown",
    )
    // 点上了字时框还亮着，一边淡出一边接着呼吸：这时停下，框会先跳回全亮再淡
    val breath = if (animate && (show || shown > 0f)) {
        rememberInfiniteTransition(label = "ringBreath").animateFloat(
            initialValue = 1f,
            targetValue = BREATH_MIN,
            animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
            label = "breath",
        )
    } else {
        null
    }
    val line = MaterialTheme.colorScheme.primary
    val halo = MaterialTheme.colorScheme.surface
    Canvas(Modifier.matchParentSize()) {
        val alpha = shown * (breath?.value ?: 1f)
        if (alpha <= 0f) return@Canvas
        val vp = Viewport.fit(sample.width, sample.height, size.width.toInt(), size.height.toInt())
        val r = vp.imageToScreen(TourSample.LINK.bounds())
        val pad = RING_PAD.toPx()
        val topLeft = Offset(r.left - pad, r.top - pad)
        val box = Size(r.width() + 2 * pad, r.height() + 2 * pad)
        val corner = CornerRadius(RING_CORNER.toPx())
        drawRoundRect(halo, topLeft, box, corner, Stroke(RING_HALO.toPx()), alpha = alpha * 0.9f)
        drawRoundRect(line, topLeft, box, corner, Stroke(RING_LINE.toPx()), alpha = alpha)
    }
}

private val CARD_RADIUS = 20.dp

private val RING_PAD = 4.dp
private val RING_CORNER = 6.dp
private val RING_LINE = 2.dp
private val RING_HALO = 5.dp

/** 落码停稳后隔多久框才淡进来：先让人看清落下来的码。 */
private const val RING_DELAY_MS = 250

/** 框一明一暗，一个来回的一半。 */
private const val BREATH_MS = 1200
private const val BREATH_MIN = 0.4f
