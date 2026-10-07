package com.yomark.app.eval

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yomark.app.core.model.Candidate
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.engine.RedactionEngine
import com.yomark.app.engine.buildEngine
import com.yomark.app.rules.DefaultRuleSet
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

data class Metrics(
    val outlineRate: Double,
    val maskedRecall: Double,
    val precision: Double,
    val sampleCount: Int,
    val truthCount: Int,
)

@RunWith(AndroidJUnit4::class)
class EvaluationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 默认打码的类型集合，直接从规则表读，不硬编码——规则表改了指标口径自动跟着改。 */
    private val maskedByDefaultKinds: Set<SensitiveKind> =
        DefaultRuleSet.rules.filter { it.enabledByDefault }.map { it.kind }.toSet()

    private suspend fun evaluate(engine: RedactionEngine, samples: List<Sample>): Metrics {
        var truthTotal = 0
        var covered = 0
        var maskedTruth = 0
        var maskedCovered = 0
        var candidateTotal = 0
        var candidateHit = 0

        samples.forEach { sample ->
            val candidates = engine.analyze(sample.image).candidates
            candidateTotal += candidates.size
            candidates.forEach { c ->
                if (sample.truth.any { intersects(c, it.rect) }) candidateHit++
            }
            sample.truth.forEach { t ->
                truthTotal++
                val hit = candidates.any { it.kind == t.kind && intersects(it, t.rect) }
                if (hit) covered++
                if (t.kind in maskedByDefaultKinds) {
                    maskedTruth++
                    val maskedHit = candidates.any {
                        it.kind == t.kind && it.enabledByDefault && intersects(it, t.rect)
                    }
                    if (maskedHit) maskedCovered++
                }
            }
        }

        return Metrics(
            outlineRate = if (truthTotal == 0) 1.0 else covered.toDouble() / truthTotal,
            maskedRecall = if (maskedTruth == 0) 1.0 else maskedCovered.toDouble() / maskedTruth,
            precision = if (candidateTotal == 0) 1.0 else candidateHit.toDouble() / candidateTotal,
            sampleCount = samples.size,
            truthCount = truthTotal,
        )
    }

    private fun intersects(c: Candidate, truth: RectF) = RectF(c.quad.bounds()).intersect(truth)

    @Test
    fun synthetic_samples_meet_the_m2_exit_criteria() = runTest {
        val metrics = evaluate(buildEngine(context), SyntheticSamples.generate())
        println("YOMARK-EVAL synthetic: $metrics")

        assertThat(metrics.outlineRate).isAtLeast(0.95)
        assertThat(metrics.maskedRecall).isAtLeast(0.95)
        assertThat(metrics.precision).isAtLeast(0.70)
    }

    @Test
    fun real_sample_set_meets_the_m2_exit_criteria_when_present() = runTest {
        val samples = SampleSet.loadFromAssets(context)
        if (samples.isEmpty()) {
            println("YOMARK-EVAL: androidTest/assets/samples/ 为空，跳过。见 docs/eval-sample-set.md")
            return@runTest
        }
        val metrics = evaluate(buildEngine(context), samples)
        println("YOMARK-EVAL real: $metrics")

        assertThat(metrics.outlineRate).isAtLeast(0.95)
        assertThat(metrics.maskedRecall).isAtLeast(0.95)
        assertThat(metrics.precision).isAtLeast(0.70)
    }

    /**
     * spec §13 的延迟指标写死了「1080×2400 截图」这个尺寸，所以量的是
     * SyntheticSamples.screenshot() 而不是 generate() 的 1080×300 单行条——
     * 后者跑出来的数字好看，但测不出真实开销。
     */
    @Test
    fun recognition_latency_on_a_screenshot_sized_image_is_under_800ms() = runTest {
        val engine = buildEngine(context)
        val sample = SyntheticSamples.screenshot()
        engine.analyze(sample.image)                       // 预热：首次调用含模型初始化

        val elapsed = measureTimeMillis { engine.analyze(sample.image) }
        println("YOMARK-EVAL latency: ${elapsed}ms (${sample.image.width}x${sample.image.height})")
        assertThat(elapsed).isLessThan(800L)
    }

    /** 截图尺寸的整图上，指标口径与逐行样本一致。 */
    @Test
    fun screenshot_sized_sample_meets_the_m2_exit_criteria() = runTest {
        val metrics = evaluate(buildEngine(context), listOf(SyntheticSamples.screenshot()))
        println("YOMARK-EVAL screenshot: $metrics")

        assertThat(metrics.outlineRate).isAtLeast(0.95)
        assertThat(metrics.maskedRecall).isAtLeast(0.95)
        assertThat(metrics.precision).isAtLeast(0.70)
    }
}
