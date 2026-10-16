package moe.flinty.yomark.ui.onboarding

import android.graphics.PointF
import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.first
import kotlinx.parcelize.Parcelize
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.ui.canvas.FrostEffect
import moe.flinty.yomark.ui.canvas.GestureRules
import moe.flinty.yomark.ui.canvas.ScanEffect
import moe.flinty.yomark.ui.canvas.ScanStyle

/**
 * 引导走到哪儿了，转屏、进程被杀都要留着的那一份。码不存：由 [toggled] 和聊天开没开现算（见 TourSample.plan）。
 *
 * @param step 第几步（0 起）
 * @param revealed 识别动效演完了没有（或者没等它演完就按了「下一步」）。演完了的，回来时码直接在图上，不再演一遍
 * @param toggled 点过、和出厂不一样的码，按点的先后：识别出的是点掉了，「line-」开头的是点上了
 * @param tapped 点字打上过码：第二步的提示就不用再框了
 * @param sample [toggled] 是在哪一张样图上点的（TourSample.key）。中途换了语言、样图跟着换了，点过的就不算数
 */
@Parcelize
internal data class TourProgress(
    val step: Int = 0,
    val revealed: Boolean = false,
    val toggled: List<String> = emptyList(),
    val tapped: Boolean = false,
    val sample: String = "",
) : Parcelable

/**
 * 引导页的状态：第几步、图上的码，点一下怎么变。点一下的规矩照编辑器（EditorViewModel.onTap）：
 * 点码拿掉，原地再点一下打回来，点没打码的一段字就把这一段遮住；没有撤销、没有手动框。
 */
@Stable
internal class TourState(saved: MutableState<TourProgress>, chatOn: Boolean, val sample: TourSample) {

    var progress by saved

    init {
        if (progress.sample != sample.key) progress = progress.copy(toggled = emptyList(), sample = sample.key)
    }

    /** 落码那一遍演完了，图上点得动了。编辑器识别中也点不了字（还没有字）。不存：回来时演完了的直接就是 true。 */
    var settled by mutableStateOf(progress.revealed)

    /** 聊天的码露出了几处（[TourSample.chat] 的前几个）。实验室一打开，一处一处抹上去。 */
    var chatShown by mutableIntStateOf(if (chatOn) sample.chat.size else 0)

    val plan: MaskPlan by derivedStateOf { sample.plan(chatShown, progress.toggled) }

    val linkMasked: Boolean by derivedStateOf { TourSample.lineId(sample.linkIndex) in progress.toggled }

    /** 识别动效到点了：码落下来。 */
    fun reveal() {
        if (!progress.revealed) progress = progress.copy(revealed = true)
    }

    /** 翻到了第 [page] 页。识别动效还没演完就翻走了，码跟着落下来：不让人等。 */
    fun goTo(page: Int) {
        val step = page.coerceIn(0, LAST_STEP)
        if (step == progress.step) return
        progress = progress.copy(step = step, revealed = progress.revealed || step > 0)
    }

    /**
     * 点了图上的 [p]（图像坐标），[reach] 是手指偏出一段字多远还算点中它（图像像素），和编辑器一样。
     * 码还没落下来时不理。
     */
    fun onTap(p: PointF, reach: Float) {
        if (!settled) return
        val toggled = progress.toggled
        val defaults = sample.defaults(chatShown)
        val dismissed = toggled.mapNotNull { id -> defaults.firstOrNull { it.candidateId == id } }
        progress = when (val target = GestureRules.tapTarget(plan.items, sample.lines, p, reach, dismissed)) {
            // 点上的一段再点一下拿掉；识别出的点掉了记着，原地再点一下打回来
            is GestureRules.TapTarget.Item -> {
                val id = target.item.candidateId
                progress.copy(toggled = if (target.item.byUser) toggled - id else toggled + id)
            }
            is GestureRules.TapTarget.Dismissed -> progress.copy(toggled = toggled - target.item.candidateId)
            is GestureRules.TapTarget.Line -> {
                val id = TourSample.lineId(sample.lines.indexOf(target.quad))
                // 点在已经遮上的一段旁边、又在手指的余量里：这一段只有一块码
                if (id in toggled) return
                progress.copy(toggled = toggled + id, tapped = true)
            }
            null -> return
        }
    }

    /** 读屏用：在链接上点一下，和手指点它一样。 */
    fun toggleLink() = onTap(sample.linkCenter, 0f)

    /**
     * 实验室里的「识别聊天记录」开关变了。打开：等翻页停稳（[ready]），标题和头像隔 [CHAT_STAGGER_MS] 一处一处抹上；
     * 关上：一起揭掉，点掉过的也忘掉。[animate] 为 false（系统关了动画）时一下子都上。
     */
    suspend fun syncChat(on: Boolean, animate: Boolean, ready: () -> Boolean) {
        if (!on) {
            val chatIds = sample.chat.map { it.candidateId }.toSet()
            Snapshot.withMutableSnapshot {
                chatShown = 0
                progress = progress.copy(toggled = progress.toggled.filterNot { it in chatIds })
            }
            return
        }
        if (chatShown >= sample.chat.size) return
        if (!animate) {
            chatShown = sample.chat.size
            return
        }
        snapshotFlow { settled && ready() }.first { it }
        while (chatShown < sample.chat.size) {
            chatShown++
            awaitMillis(CHAT_STAGGER_MS)
        }
    }

    companion object {
        const val STEPS = 3
        const val LAST_STEP = STEPS - 1
    }
}

/** 记住引导的进度（转屏、进程被杀后接着来）。[animate] 为 false 时一开始就是演完了的样子。 */
@Composable
internal fun rememberTourState(sample: TourSample, chatOn: Boolean, animate: Boolean): TourState {
    val saved = rememberSaveable { mutableStateOf(TourProgress(revealed = !animate, sample = sample.key)) }
    return remember { TourState(saved, chatOn, sample) }
}

/** 识别动效（磨砂）演多久再落码。扫光那种别取 2 秒整：结果正好卡在一遍光扫完时，收尾要多扫一遍（见 ScanEffect.settleMs）。 */
internal const val SCAN_MS = 1800L

/** 落码演完后再等这么一下才点得动：画布的时钟和这里的各数各的，差一两帧。 */
internal const val SETTLE_MARGIN_MS = 50L

/** 聊天的码一处一处抹上去，前后隔多久。 */
internal const val CHAT_STAGGER_MS = 60L

/** 落码那一遍要多久，和画布里一样（见 ImageCanvas）。[scannedMs] 是识别动效演了多久。 */
internal fun settleMs(style: ScanStyle, scannedMs: Long): Long = when (style) {
    ScanStyle.FROST -> FrostEffect.SETTLE_MS
    ScanStyle.SWEEP -> ScanEffect.settleMs(scannedMs)
}

/**
 * 按帧数时间等 [ms]。和画布上的动效用同一个时钟：退到后台时一起停，测试里跟着 mainClock 走。
 */
internal suspend fun awaitMillis(ms: Long) {
    val start = withFrameMillis { it }
    while (withFrameMillis { it } - start < ms) Unit
}
