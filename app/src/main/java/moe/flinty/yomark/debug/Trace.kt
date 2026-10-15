package moe.flinty.yomark.debug

import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKindLabels
import moe.flinty.yomark.core.model.TextLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/*
 * 调试信息（设置 → 关于，连点版本号打开）：每次识别在各环节看到了什么——
 * PP-OCR 读出的每一行和它的框、规则命中了什么、送给 Gemini Nano 的提示词和它的原始回答。
 *
 * 不写 logcat：公开发布的 APK 就是 debug 构建，往 logcat 里打识别出的文字，等于把用户截图里的隐私送进系统日志。
 * 记录存在应用私有目录里（见 TraceStore.kt），不进备份，只在应用自己的界面上给用户本人看；
 * 关掉调试信息时连文件一并删掉。整个进程共用一份（见 TraceHistory.kt），换一张图、重开应用，之前的都还在。
 *
 * 记录器挂在协程上下文里一路往下传（[TraceRecorder]）：识别器、检测器、判定器各自往里记一段，
 * 不用在各个接口上多开一个参数。调试信息没开时上下文里没有它，各处一个字都不记。
 */

/** 一段记录属于哪个环节。文字识别和人脸、条码检测是并行跑的，记下来的先后不定，显示时按它排。 */
enum class TraceStage { INPUT, TEXT, REGION, CLASSIFY, RESULT }

/** 一大段原文：送给模型的提示词、模型的回答、异常的堆栈。界面上默认收起。 */
data class TraceBlock(val label: String, val text: String)

/**
 * 一个环节的记录。[title] 是哪一环（「文字识别 · ppocr-v6-small」），[summary] 一句话说结果（「1.8 s · 42 行」），
 * [lines] 是逐条的明细，[blocks] 是大段原文。
 */
data class TraceSection(
    val stage: TraceStage,
    val title: String,
    val summary: String,
    val lines: List<String> = emptyList(),
    val blocks: List<TraceBlock> = emptyList(),
)

/**
 * 一次识别（或一次 AI 增强识别）的整份记录。[durationMs] 为 null 是还没跑完；
 * [interrupted] 是从磁盘读回来时还没跑完——应用在那次识别中途退出了，不会再跑完。
 */
data class TraceRun(
    val number: Int,
    val title: String,
    val startedAt: Long,
    val durationMs: Long? = null,
    val sections: List<TraceSection> = emptyList(),
    val interrupted: Boolean = false,
)

/**
 * 往一次记录里写东西的口子，挂在协程上下文里。各环节用 [currentTrace] 取它，取不到就是没开调试信息。
 * 文字识别和人脸、条码检测在不同线程上同时往里写，写入都经过 [TraceLog] 的原子更新。
 */
class TraceRecorder internal constructor(
    private val log: TraceLog,
    private val number: Int,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TraceRecorder>

    /** 现在的时刻（毫秒），量用时用。和 [TraceLog] 用同一个时钟，单测里可以换掉。 */
    fun now(): Long = log.clock()

    fun add(section: TraceSection) = log.edit(number) { run ->
        // 按环节排；同一环节里按记下的先后（sortedBy 是稳定排序）
        run.copy(sections = (run.sections + section).sortedBy { it.stage })
    }

    /** 这一次跑完了，记下总用时。之后还能接着记（比如探测模型在不在），不算进用时。 */
    fun finish() = log.edit(number) { it.copy(durationMs = log.clock() - it.startedAt) }
}

/** 当前协程挂着的记录器。调试信息没开时为 null。 */
suspend fun currentTrace(): TraceRecorder? = currentCoroutineContext()[TraceRecorder]

/**
 * 跑一环并记下来：用时，和 [describe] 由结果写出的摘要与明细。
 *
 * 抛异常时记下异常和堆栈，再原样抛出去：调用方怎么处理照旧（引擎多半吞掉、降级成空结果），
 * 只是调试信息里看得见它吞掉的是什么。协程被取消（换了图）不记。
 * 写记录本身出了错也不能连累识别：结果照样交回去，记录里只写一句出了什么错。
 */
suspend fun <T> TraceRecorder.step(
    stage: TraceStage,
    title: String,
    block: suspend () -> T,
    describe: (result: T, ms: Long) -> Pair<String, List<String>>,
): T {
    val started = now()
    val result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        add(failure(stage, title, now() - started, e))
        throw e
    }
    val ms = now() - started
    runCatching { describe(result, ms) }
        .onSuccess { (summary, lines) -> add(TraceSection(stage, title, summary, lines)) }
        .onFailure { add(TraceSection(stage, title, "${formatDuration(ms)} · 记录出错：${it.describe()}")) }
    return result
}

/** 一环抛了异常：摘要里写异常，堆栈收在下面。 */
private fun failure(stage: TraceStage, title: String, ms: Long, error: Throwable) = TraceSection(
    stage = stage,
    title = title,
    summary = "${formatDuration(ms)} · 出错：${error.describe()}",
    blocks = listOf(TraceBlock("堆栈", error.stackTraceToString())),
)

/**
 * 最近几次识别的记录，最新的在前。只留 [capacity] 次（设置 → 开发者里选），挤出去的连文件一起删。
 *
 * 给了 [store] 就落盘：每次有改动，把改了的那几次各自重写一遍（[persist]），进程起来时读回来（[restore]）。
 * 没给（单测、引擎各环节的单测）就只在内存里。
 */
class TraceLog(
    capacity: Int = DEFAULT_CAPACITY,
    internal val clock: () -> Long = System::currentTimeMillis,
    private val store: TraceStore? = null,
    /** 读写磁盘用的线程。单测里换成测试的调度器，好等它写完。 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _runs = MutableStateFlow<List<TraceRun>>(emptyList())
    val runs: StateFlow<List<TraceRun>> = _runs.asStateFlow()

    @Volatile var capacity: Int = capacity.coerceAtLeast(1)
        private set

    // 编号接着磁盘上最大的那个往下数：读回来之前就开始的识别，编号也不会和读回来的撞上
    private val counter = AtomicInteger(store?.maxNumber() ?: 0)

    /** 改过、还没写盘的几次（写的时候在 [_runs] 里找不到就是被挤掉或清空了，删文件）。 */
    private val dirty: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var wipe = false
    /** 每清空一次加一：清空之前开始读的那批，读完了也不往回放。 */
    private val generation = AtomicInteger()

    /** 开始记一次。返回的记录器要挂到跑识别的那个协程的上下文里（withContext）。 */
    fun start(title: String): TraceRecorder {
        val number = counter.incrementAndGet()
        val before = _runs.getAndUpdate { (listOf(TraceRun(number, title, clock())) + it).take(capacity) }
        touch(listOf(number) + before.drop(capacity - 1).map { it.number })
        return TraceRecorder(this, number)
    }

    fun clear() {
        generation.incrementAndGet()
        _runs.value = emptyList()
        dirty.clear()
        wipe = true
        wake.trySend(Unit)
    }

    /** 改保留几次。改少了，多出来的旧记录马上删掉。 */
    fun setCapacity(value: Int) {
        capacity = value.coerceAtLeast(1)
        val before = _runs.getAndUpdate { it.take(capacity) }
        touch(before.drop(capacity).map { it.number })
    }

    /** 改第 [number] 次的记录。已经被挤出去、或者清空了的，改了也没处放，直接丢。 */
    internal fun edit(number: Int, change: (TraceRun) -> TraceRun) {
        _runs.update { runs -> runs.map { if (it.number == number) change(it) else it } }
        touch(listOf(number))
    }

    /**
     * 把磁盘上的读回来，并进内存里已有的（读的时候可能已经开始了新的一次）。先 [setCapacity] 再调：
     * 超出保留次数的旧记录在这里删掉，次数还没定就读，会把用户想留的删了。
     * 读回来时还没跑完的，标成 [TraceRun.interrupted]。
     */
    suspend fun restore() {
        val store = store ?: return
        val gen = generation.get()
        val loaded = withContext(io) { store.readAll() }.map {
            if (it.durationMs == null) it.copy(interrupted = true) else it
        }
        var merged = false
        val after = _runs.updateAndGet { current ->
            merged = generation.get() == gen
            if (!merged) return@updateAndGet current
            val known = current.map { it.number }.toSet()
            (current + loaded.filter { it.number !in known }).sortedByDescending { it.number }.take(capacity)
        }
        val kept = after.map { it.number }.toSet()
        touch(loaded.map { it.number }.filter { it !in kept || !merged })
    }

    /** 一直写下去：有改动就把改了的那几次写盘。挂在进程级的协程里跑，不会返回。 */
    suspend fun persist() {
        val store = store ?: return
        for (signal in wake) {
            withContext(io) {
                if (wipe) {
                    wipe = false
                    runCatching { store.deleteAll() }
                }
                val numbers = dirty.toList()
                dirty.removeAll(numbers.toSet())
                val current = _runs.value.associateBy { it.number }
                numbers.forEach { n ->
                    // 写不进去（磁盘满了）也不能连累识别，下次有改动再试
                    runCatching { current[n]?.let(store::write) ?: store.delete(n) }.onFailure { it.printStackTrace() }
                }
            }
        }
    }

    private fun touch(numbers: List<Int>) {
        if (store == null || numbers.isEmpty()) return
        dirty += numbers
        wake.trySend(Unit)
    }

    companion object {
        /** 保留几次可以选的档。每次都带着整页的文字和提示词，一次几十 KB 到几百 KB。 */
        val CAPACITY_OPTIONS = listOf(10, 20, 50, 100)
        const val DEFAULT_CAPACITY = 20
    }
}

// ---------- 写成文字 ----------

/** 「识别 #3」 */
val TraceRun.heading: String get() = "$title #$number"

/** 「10-11 14:02:31 · 共 2.3 s」，还没跑完是「10-11 14:02:31 · 进行中…」。记录跨天留着，所以带上日期。 */
val TraceRun.subheading: String
    get() = CLOCK.format(Instant.ofEpochMilli(startedAt)) + " · " +
        when {
            durationMs != null -> "共 ${formatDuration(durationMs)}"
            interrupted -> "没跑完（应用中途退出了）"
            else -> "进行中…"
        }

/** 整份记录写成纯文本：「复制」「分享」用，贴到哪儿都看得懂。 */
fun TraceRun.toText(): String = buildString {
    append("== ").append(heading).append(" · ").append(subheading).append(" ==\n")
    sections.forEach { s ->
        append("\n[").append(s.title).append("] ").append(s.summary).append('\n')
        s.lines.forEach { append("  ").append(it).append('\n') }
        s.blocks.forEach { b ->
            append("  --- ").append(b.label).append(" ---\n")
            b.text.lineSequence().forEach { append("  ").append(it).append('\n') }
        }
    }
}

/** 用时：一秒以内写毫秒，再长写到十分之一秒。 */
fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

/** 框的外接矩形：「左,上 宽×高」，单位是分析图的像素。 */
fun Quad.describe(): String {
    val b = bounds()
    return "${b.left.roundToInt()},${b.top.roundToInt()} ${b.width().roundToInt()}×${b.height().roundToInt()}"
}

/** OCR 的一行：「  3  0.98  12,40 300×28  收货人：张三」。行号从 1 数，别处说「第几行」都按它。 */
fun describeLine(index: Int, line: TextLine): String =
    String.format(Locale.ROOT, "%3d  %.2f  %s  %s", index + 1, line.confidence, line.quad.describe(), line.text)

/**
 * 一个候选：类型、打不打码、置信度、框，给了 [lines] 的话再加框里压着的字，
 * 最后是它的 id——从 id 看得出是哪条规则、哪个检测器出的。
 */
fun describeCandidate(candidate: Candidate, lines: List<TextLine> = emptyList()): String = buildString {
    append(SensitiveKindLabels.debugName(candidate.kind))
    append("  ").append(if (candidate.enabledByDefault) "打码" else "不打码")
    append("  ").append(String.format(Locale.ROOT, "%.2f", candidate.confidence))
    append("  ").append(candidate.quad.describe())
    val text = textUnder(candidate.quad, lines)
    if (text.isNotEmpty()) append("  「").append(text).append('」')
    append("  ").append(candidate.id)
}

/**
 * 框里压着的字：各行里中心落在框内的字（ML Kit 是词），从第一个到最后一个连同中间的空格一起取出来，
 * 跨行的用「 / 」隔开。是按几何反推的，只供看个大概。
 */
fun textUnder(quad: Quad, lines: List<TextLine>): String = lines.mapNotNull { line ->
    val hit = line.elements.filter { e ->
        val b = e.quad.bounds()
        quad.contains(PointF(b.centerX(), b.centerY()))
    }
    if (hit.isEmpty()) return@mapNotNull null
    val from = hit.minOf { it.range.first }.coerceIn(0, line.text.length)
    val to = (hit.maxOf { it.range.last } + 1).coerceIn(from, line.text.length)
    line.text.substring(from, to)
}.joinToString(" / ")

/** 异常写成一行：类名加消息。 */
fun Throwable.describe(): String = javaClass.simpleName + (message?.let { "：$it" } ?: "")

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
