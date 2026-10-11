package moe.flinty.yomark.debug

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 调试记录落盘：一次一个文件，重开进程读得回来，挤掉的、清空的连文件一起删。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TraceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val dir by lazy { tmp.root.resolve("debug_trace") }
    private var time = 1_000L

    // 读写都在 backgroundScope 里：advanceUntilIdle 不等后台任务，所以这里一律用 runCurrent
    private fun TestScope.log(capacity: Int = 3): TraceLog {
        val log = TraceLog(capacity, clock = { time }, store = TraceStore(dir), io = dispatcher)
        backgroundScope.launch { log.restore(); log.persist() }
        return log
    }

    private fun files() = dir.list().orEmpty().sorted()

    private val sample = TraceRun(
        number = 7,
        title = "识别",
        startedAt = 1_234L,
        durationMs = 560L,
        sections = listOf(
            TraceSection(
                TraceStage.CLASSIFY, "判定 · nano", "1.2 s · 2 处",
                lines = listOf("姓名  打码  0.80", "带\n换行和「引号」\"的\""),
                blocks = listOf(TraceBlock("提示词", "多行\n原文")),
            ),
        ),
    )

    @Test fun aRunSurvivesTheRoundTrip() {
        assertThat(decodeRun(encodeRun(sample))).isEqualTo(sample)
        assertThat(decodeRun(encodeRun(sample.copy(durationMs = null))).durationMs).isNull()
    }

    @Test fun runsAreWrittenOneFileEachAndReadBackInAFreshProcess() = runTest(dispatcher) {
        val first = log()
        runCurrent()
        first.start("识别").apply { add(TraceSection(TraceStage.TEXT, "文字识别", "2 行")); finish() }
        first.start("AI 增强识别").finish()
        runCurrent()
        assertThat(files()).containsExactly("1.json", "2.json")

        val second = log()
        runCurrent()
        assertThat(second.runs.value.map { it.title }).containsExactly("AI 增强识别", "识别").inOrder()
        assertThat(second.runs.value[1].sections.single().summary).isEqualTo("2 行")
        // 编号接着往下数，不和读回来的撞
        assertThat(second.start("识别").let { second.runs.value.first().number }).isEqualTo(3)
    }

    @Test fun runsPushedOutAreDeletedFromDisk() = runTest(dispatcher) {
        val log = log(capacity = 2)
        runCurrent()
        repeat(4) { log.start("识别") }
        runCurrent()
        assertThat(files()).containsExactly("3.json", "4.json")
    }

    @Test fun loweringTheCapacityTrimsTheOldestAndRaisingItKeepsWhatIsLeft() = runTest(dispatcher) {
        val log = log(capacity = 5)
        runCurrent()
        repeat(5) { log.start("识别") }
        log.setCapacity(2)
        runCurrent()
        assertThat(log.runs.value.map { it.number }).containsExactly(5, 4).inOrder()
        assertThat(files()).containsExactly("4.json", "5.json")

        log.setCapacity(10)
        log.start("识别")
        runCurrent()
        assertThat(log.runs.value.map { it.number }).containsExactly(6, 5, 4).inOrder()
    }

    @Test fun clearingDeletesTheFiles() = runTest(dispatcher) {
        val log = log()
        runCurrent()
        log.start("识别").finish()
        runCurrent()
        assertThat(files()).isNotEmpty()

        log.clear()
        runCurrent()
        assertThat(dir.exists()).isFalse()
        assertThat(log(capacity = 3).also { runCurrent() }.runs.value).isEmpty()
    }

    /** 识别到一半应用被杀：读回来标成没跑完，不能一直显示「进行中」。 */
    @Test fun aRunCutShortByTheProcessDyingIsMarkedInterrupted() = runTest(dispatcher) {
        log().also { runCurrent() }.start("识别")
        runCurrent()

        val run = log().also { runCurrent() }.runs.value.single()
        assertThat(run.interrupted).isTrue()
        assertThat(run.subheading).endsWith("没跑完（应用中途退出了）")
    }

    /** 读回来之前就开始的那次留着，读回来的排在它后面。 */
    @Test fun aRunStartedBeforeRestoringIsKept() = runTest(dispatcher) {
        log().also { runCurrent() }.start("识别").finish()
        runCurrent()

        val log = log()
        log.start("识别")          // 还没 advance：restore 还没跑
        runCurrent()
        assertThat(log.runs.value.map { it.number }).containsExactly(2, 1).inOrder()
    }

    @Test fun anUnreadableFileIsSkippedAndDeleted() = runTest(dispatcher) {
        dir.mkdirs()
        dir.resolve("1.json").writeText("{ not json")
        TraceStore(dir).write(sample)

        assertThat(TraceStore(dir).readAll().map { it.number }).containsExactly(7)
        assertThat(files()).containsExactly("7.json")
    }
}
