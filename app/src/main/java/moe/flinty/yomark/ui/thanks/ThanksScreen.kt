package moe.flinty.yomark.ui.thanks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 感谢页的内容（见 [ThanksActivity]）：会动的 🎉、一句谢谢、买到的是什么，再一个「好的」。
 *
 * 文案照 spec §10 的口径，买到的只是去掉品牌水印。顺带说一句重装、换手机后怎么恢复，买断的用户最关心这个。
 */
@Composable
fun ThanksScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val confetti = rememberConfetti()
    Box(modifier.fillMaxSize()) {
        // 彩纸在最底下一层、铺满整页：从 🎉 后面喷出来，落下时从字和按钮后面过去，不挡字
        ConfettiLayer(confetti, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            // 横着拿的手机竖向放不下，就滚动，不把按钮裁掉
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            ) {
                PartyPopper(confetti, Modifier.size(176.dp))
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(R.string.thanks_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.thanks_text),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.thanks_restore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                Button(onClick = onDone, modifier = Modifier.widthIn(min = 160.dp)) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

/**
 * 会动的 🎉：转着弹出来，喷一把彩纸，然后一直轻轻地晃。只自己喷这一把，想再看就点它。
 * 转屏重建时不再弹、不再喷：已经放过了。
 *
 * 系统关了动画（开发者选项里动画时长缩放为 0）时，这些动画都直接跳到终点：🎉 停在原位，彩纸不出现。
 */
@Composable
private fun PartyPopper(confetti: Confetti, modifier: Modifier = Modifier) {
    // 放过一次就记下，转屏重建后 🎉 直接停在原位
    var played by rememberSaveable { mutableStateOf(false) }
    val pop = remember { Animatable(if (played) 1f else 0f) }
    // 每喷一把，🎉 往上一顶、往后一仰，再弹回来
    val kick = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val fire: () -> Unit = {
        confetti.burst()
        scope.launch {
            kick.snapTo(1f)
            kick.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 400f))
        }
    }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        played = true
        launch { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
        delay(Popper.FIRST_BURST_MS)
        fire()
    }
    val wobble = rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = -Popper.WOBBLE_DEG,
        targetValue = Popper.WOBBLE_DEG,
        animationSpec = infiniteRepeatable(tween(Popper.WOBBLE_MS, easing = EaseInOut), RepeatMode.Reverse),
        label = "wobble-angle",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = pop.value * (1f + 0.05f * kick.value)
                    scaleX = s
                    scaleY = s
                }
                // 不用 primaryContainer：几套主题色里它都很浓（贴着种子色），压得 🎉 看不清
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        )
        Text(
            "🎉",
            fontSize = 96.sp,
            modifier = Modifier
                // 纯装饰：读屏不念「拉炮」，也不报一个点了没用的按钮
                .clearAndSetSemantics {}
                // 量的是没转、没缩放时的位置，喷口不跟着晃
                .onGloballyPositioned {
                    confetti.mouth = it.positionInRoot() +
                        Offset(it.size.width * Popper.MOUTH.x, it.size.height * Popper.MOUTH.y)
                }
                .graphicsLayer {
                    val s = pop.value * (1f + 0.18f * kick.value)
                    scaleX = s
                    scaleY = s
                    rotationZ = wobble.value - 45f * (1f - pop.value) - 14f * kick.value
                    // 绕着锥筒的底转，像一只手捏着它
                    transformOrigin = TransformOrigin(0.3f, 0.75f)
                }
                .clickable(interactionSource = null, indication = null, onClick = fire),
        )
    }
}

private object Popper {
    const val FIRST_BURST_MS = 200L
    const val WOBBLE_DEG = 7f
    const val WOBBLE_MS = 1200

    /** 拉炮的开口，按 🎉 那段文字的宽高归一。各家 emoji 字体里拉炮都是开口朝右上。 */
    val MOUTH = Offset(0.62f, 0.36f)
}

@Composable
private fun rememberConfetti(): Confetti {
    val scope = rememberCoroutineScope()
    return remember { Confetti(scope) }
}

/**
 * 一把一把的彩纸（[burst]）。自己画，不用一堆 emoji：要几十上百片、受重力往下落、边落边翻面。
 *
 * 每片按初速度、空气阻力和重力算位置：先冲出去，很快慢下来，再左右飘着往下落，快到时淡出。
 * 每把的时间用 Animatable 走，系统的动画时长缩放对它同样有效。
 */
@Stable
private class Confetti(private val scope: CoroutineScope, private val random: Random = Random.Default) {

    /** 喷口在根坐标里的位置。🎉 摆好之前不知道，那时不画。 */
    var mouth by mutableStateOf(Offset.Unspecified)

    val bursts = mutableStateListOf<Burst>()

    fun burst() {
        val burst = Burst(List(PIECES) { Piece.random(random) })
        bursts += burst
        scope.launch {
            burst.age.animateTo(LIFE_S, tween((LIFE_S * 1000).roundToInt(), easing = LinearEasing))
            bursts -= burst
        }
    }

    class Burst(val pieces: List<Piece>) {
        /** 喷出去多少秒了。 */
        val age = Animatable(0f)
    }

    class Piece(
        private val vx: Float,
        private val vy: Float,
        private val spin: Float,
        private val tilt: Float,
        private val flip: Float,
        private val sway: Float,
        private val swayRate: Float,
        private val swayPhase: Float,
        val color: Color,
        val ribbon: Boolean,
    ) {
        /** 喷出 [t] 秒后离喷口多远，dp。速度按 e^(-DRAG·t) 衰减，往下再趋近终端速度 GRAVITY / DRAG。 */
        fun offset(t: Float): Offset {
            val slowed = 1f - exp(-DRAG * t)
            val terminal = GRAVITY / DRAG
            return Offset(
                vx * slowed / DRAG + sway * sin(swayRate * t + swayPhase) * slowed,
                terminal * t + (vy - terminal) * slowed / DRAG,
            )
        }

        fun angle(t: Float): Float = tilt + spin * t

        /** 纸片翻到侧面时看上去变窄。 */
        fun facing(t: Float): Float = abs(cos(flip * t)).coerceAtLeast(0.2f)

        companion object {
            fun random(random: Random): Piece {
                // 主要往右上方喷（拉炮开口的方向），也有一些往正上、左上散开，铺满整屏
                val angle = (random.nextFloat() * (SPRAY_TO - SPRAY_FROM) + SPRAY_FROM) * PI.toFloat() / 180f
                val speed = random.nextFloat() * (SPEED_MAX - SPEED_MIN) + SPEED_MIN
                return Piece(
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    spin = (random.nextFloat() - 0.5f) * 720f,
                    tilt = random.nextFloat() * 360f,
                    flip = random.nextFloat() * 8f + 4f,
                    sway = random.nextFloat() * 14f + 4f,
                    swayRate = random.nextFloat() * 3f + 2.5f,
                    swayPhase = random.nextFloat() * 2f * PI.toFloat(),
                    color = COLORS[random.nextInt(COLORS.size)],
                    ribbon = random.nextFloat() < 0.7f,
                )
            }
        }
    }

    companion object {
        const val PIECES = 80
        const val LIFE_S = 3.2f
        const val FADE_S = 1.0f

        /** 空气阻力，1/s。纸片很轻，冲出去不到一秒就慢下来。 */
        const val DRAG = 2.4f

        /** dp/s²。和阻力一起定下飘落的终端速度：约 220 dp/s。 */
        const val GRAVITY = 520f

        /** 喷的方向范围，度。屏幕坐标：0 朝右，-90 朝上。 */
        const val SPRAY_FROM = -150f
        const val SPRAY_TO = -15f
        const val SPEED_MIN = 450f
        const val SPEED_MAX = 1250f

        const val RIBBON_W = 6f
        const val RIBBON_H = 12f
        const val DOT_R = 3.5f

        /** 彩纸的颜色不跟主题色走：要的就是五颜六色。 */
        val COLORS = listOf(
            Color(0xFFFF5A5F),
            Color(0xFFFFB400),
            Color(0xFF00C48C),
            Color(0xFF2E9BFF),
            Color(0xFFB45CFF),
            Color(0xFFFF7AC6),
        )
    }
}

@Composable
private fun ConfettiLayer(confetti: Confetti, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val mouth = confetti.mouth
        if (mouth.isUnspecified) return@Canvas
        val from = mouth - origin
        confetti.bursts.forEach { burst ->
            val t = burst.age.value
            burst.pieces.forEach { drawPiece(it, t, from) }
        }
    }
}

private fun DrawScope.drawPiece(piece: Confetti.Piece, t: Float, from: Offset) {
    val alpha = ((Confetti.LIFE_S - t) / Confetti.FADE_S).coerceIn(0f, 1f)
    if (alpha == 0f) return
    val o = piece.offset(t)
    val center = from + Offset(o.x.dp.toPx(), o.y.dp.toPx())
    rotate(piece.angle(t), pivot = center) {
        if (piece.ribbon) {
            val w = Confetti.RIBBON_W.dp.toPx()
            val h = Confetti.RIBBON_H.dp.toPx() * piece.facing(t)
            drawRect(piece.color, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), alpha)
        } else {
            drawCircle(piece.color, Confetti.DOT_R.dp.toPx(), center, alpha)
        }
    }
}
