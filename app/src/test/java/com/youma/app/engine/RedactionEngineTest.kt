package com.youma.app.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedactionEngineTest {

    private fun image() = SourceImage(
        bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        scale = 1f, originalWidth = 100, originalHeight = 100, mimeType = "image/jpeg",
    )

    private fun quad() = Quad.fromRect(RectF(0f, 0f, 10f, 10f))

    private fun candidate(id: String, kind: SensitiveKind = SensitiveKind.EMAIL) =
        Candidate(id, quad(), kind, DetectorSource.RULE, 0.9f)

    private class FakeRecognizer(
        val lines: List<TextLine> = emptyList(),
        val boom: Boolean = false,
        val delayMs: Long = 0,
    ) : TextRecognizer {
        override val id = "fake-ocr"
        override suspend fun recognize(image: SourceImage): List<TextLine> {
            delay(delayMs)
            if (boom) error("ocr exploded")
            return lines
        }
    }

    private class FakeDetector(
        override val kind: SensitiveKind,
        val out: List<Candidate>,
        val boom: Boolean = false,
    ) : RegionDetector {
        override val id = "fake-$kind"
        override suspend fun detect(image: SourceImage): List<Candidate> {
            if (boom) error("detector exploded")
            return out
        }
    }

    private class FakeClassifier(
        override val id: String,
        val available: Boolean,
        val out: List<Candidate>,
        val boom: Boolean = false,
    ) : SensitivityClassifier {
        var classifyCalls = 0
        override suspend fun isAvailable() = available
        override suspend fun classify(lines: List<TextLine>): List<Candidate> {
            classifyCalls++
            if (boom) error("classifier exploded")
            return out
        }
    }

    private fun engine(
        recognizer: TextRecognizer = FakeRecognizer(),
        detectors: List<RegionDetector> = emptyList(),
        classifiers: List<SensitivityClassifier> = emptyList(),
    ) = RedactionEngine(recognizer, detectors, classifiers, CandidateMerger())

    @Test
    fun `candidates from classifiers and detectors are combined`() = runTest {
        val result = engine(
            classifiers = listOf(FakeClassifier("c", true, listOf(candidate("a")))),
            detectors = listOf(FakeDetector(SensitiveKind.FACE, listOf(candidate("b", SensitiveKind.FACE)))),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `an exploding recognizer degrades to empty lines, not a failure`() = runTest {
        val result = engine(
            recognizer = FakeRecognizer(boom = true),
            detectors = listOf(FakeDetector(SensitiveKind.FACE, listOf(candidate("f", SensitiveKind.FACE)))),
        ).analyze(image())

        assertThat(result.lines).isEmpty()
        assertThat(result.candidates.map { it.id }).containsExactly("f")
    }

    @Test
    fun `an exploding detector does not take down the others`() = runTest {
        val result = engine(
            detectors = listOf(
                FakeDetector(SensitiveKind.FACE, emptyList(), boom = true),
                FakeDetector(SensitiveKind.BARCODE, listOf(candidate("bc", SensitiveKind.BARCODE))),
            ),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("bc")
    }

    @Test
    fun `an exploding classifier does not take down the others`() = runTest {
        val result = engine(
            classifiers = listOf(
                FakeClassifier("boom", true, emptyList(), boom = true),
                FakeClassifier("ok", true, listOf(candidate("ok1"))),
            ),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("ok1")
    }

    @Test
    fun `an unavailable classifier is skipped entirely`() = runTest {
        val off = FakeClassifier("off", available = false, out = listOf(candidate("never")))
        val result = engine(classifiers = listOf(off)).analyze(image())

        assertThat(off.classifyCalls).isEqualTo(0)
        assertThat(result.candidates).isEmpty()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `text and region pipelines run in parallel`() = runTest {
        // 两条链路各睡 200ms；串行会是 400ms，并行接近 200ms。
        // runTest 的虚拟时间让这条断言稳定。
        val start = currentTime
        engine(
            recognizer = FakeRecognizer(delayMs = 200),
            detectors = listOf(object : RegionDetector {
                override val id = "slow"
                override val kind = SensitiveKind.FACE
                override suspend fun detect(image: SourceImage): List<Candidate> {
                    delay(200); return emptyList()
                }
            }),
        ).analyze(image())
        assertThat(currentTime - start).isLessThan(350L)
    }

    @Test
    fun `recognized lines are passed through to the result`() = runTest {
        val line = TextLine(quad(), "hello", 0.8f, emptyList())
        val result = engine(recognizer = FakeRecognizer(lines = listOf(line))).analyze(image())
        assertThat(result.lines).containsExactly(line)
    }

    // ---------- 第二遍（端侧大模型） ----------

    private fun at(id: String, l: Float, kind: SensitiveKind = SensitiveKind.PERSON_NAME, source: DetectorSource = DetectorSource.LLM) =
        Candidate(id, Quad.fromRect(RectF(l, 0f, l + 10f, 10f)), kind, source, 0.5f, enabledByDefault = false)

    @Test
    fun `slow classifiers do not run during analyze`() = runTest {
        val slow = FakeClassifier("nano", available = true, out = listOf(at("n", 50f)))
        val e = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(slow))
        e.analyze(image())
        assertThat(slow.classifyCalls).isEqualTo(0)
        assertThat(e.canRefine).isTrue()
    }

    @Test
    fun `refine returns only what is not already covered on screen`() = runTest {
        val slow = FakeClassifier("nano", available = true, out = listOf(at("same", 0f), at("new", 50f)))
        val e = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(slow))
        // 同一块像素已经有框了（规则的也好、手动画的也好），不论模型说它是什么类型都不再叠一个框
        val covered = listOf(at("rule", 0f, SensitiveKind.PHONE, DetectorSource.RULE).quad)
        assertThat(e.refine(emptyList(), covered)!!.map { it.id }).containsExactly("new")
    }

    @Test
    fun `refine that ran and found nothing is an empty list, not a failure`() = runTest {
        val quiet = FakeClassifier("nano", available = true, out = emptyList())
        val e = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(quiet))
        assertThat(e.refine(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `an unavailable or exploding slow classifier reports that it did not run`() = runTest {
        // 复查是用户点出来的：「没跑成」不能说成「跑了没发现」，否则用户会以为这一页是干净的
        val off = FakeClassifier("off", available = false, out = listOf(at("x", 50f)))
        val boom = FakeClassifier("boom", available = true, out = emptyList(), boom = true)
        val e = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(off, boom))
        assertThat(e.refine(emptyList(), emptyList())).isNull()
        assertThat(off.classifyCalls).isEqualTo(0)
    }

    @Test
    fun `refineReady only probes and never classifies`() = runTest {
        val on = FakeClassifier("nano", available = true, out = listOf(at("n", 50f)))
        val ready = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(on))
        assertThat(ready.refineReady()).isTrue()
        assertThat(on.classifyCalls).isEqualTo(0)

        val off = FakeClassifier("off", available = false, out = emptyList())
        val notReady = RedactionEngine(FakeRecognizer(), emptyList(), emptyList(), CandidateMerger(), refiners = listOf(off))
        assertThat(notReady.refineReady()).isFalse()
        assertThat(engine().refineReady()).isFalse()          // 设置里关了 = 没有 refiner
    }
}
