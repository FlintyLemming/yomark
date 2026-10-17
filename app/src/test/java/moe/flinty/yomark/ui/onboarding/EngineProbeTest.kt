package moe.flinty.yomark.ui.onboarding

import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.flinty.yomark.engine.TextEngineOption
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** 引导里的 PP-OCR 测速：加载、预热不计时，3 秒一到就出结论，跑完补上用时；只测一次，结论跟着进度存。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EngineProbeTest {

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = EngineProbeModel(saved, clock = { main.scheduler.currentTime })

    @Test fun `loading and warming up are not timed`() = runTest(main) {
        val probe = model()
        probe.start(prepare = { delay(8_000) }, recognize = { delay(1_200) })
        advanceTimeBy(8_500)
        runCurrent()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Measuring)
        advanceUntilIdle()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Fast(1_200))
        assertThat(probe.advice.value.recommended).isEqualTo(TextEngineOption.PADDLE)
    }

    @Test fun `exactly three seconds still counts as fast`() = runTest(main) {
        val probe = model()
        probe.start(prepare = {}, recognize = { delay(EngineProbeModel.LIMIT_MS) })
        advanceUntilIdle()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Fast(EngineProbeModel.LIMIT_MS))
    }

    /** 到点还没跑完：不等它，先推荐 ML Kit；跑完再补上用了多久。 */
    @Test fun `a slow run says so at the limit and fills in the time later`() = runTest(main) {
        val probe = model()
        probe.start(prepare = { delay(500) }, recognize = { delay(5_000) })
        advanceTimeBy(500 + EngineProbeModel.LIMIT_MS)
        runCurrent()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Measuring)
        advanceTimeBy(1)
        runCurrent()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Slow(null))
        assertThat(probe.advice.value.recommended).isEqualTo(TextEngineOption.MLKIT)
        advanceUntilIdle()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Slow(5_000))
    }

    @Test fun `a model that will not load recommends ML Kit`() = runTest(main) {
        val probe = model()
        var timed = false
        probe.start(prepare = { throw OutOfMemoryError("模型放不下") }, recognize = { timed = true })
        advanceUntilIdle()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Failed)
        assertThat(probe.advice.value.recommended).isEqualTo(TextEngineOption.MLKIT)
        assertThat(timed).isFalse()
    }

    @Test fun `a run that fails after the limit ends as failed`() = runTest(main) {
        val probe = model()
        probe.start(prepare = {}, recognize = {
            delay(4_000)
            error("ONNX Runtime 出错")
        })
        advanceUntilIdle()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Failed)
    }

    @Test fun `it measures only once`() = runTest(main) {
        val probe = model()
        var runs = 0
        repeat(2) {
            probe.start(prepare = {}, recognize = {
                runs++
                delay(100)
            })
        }
        advanceUntilIdle()
        assertThat(runs).isEqualTo(1)
    }

    /** 引导走完了：还在预热就停下，计时的那一遍不跑，也不再出结论。 */
    @Test fun `stopping skips the timed run`() = runTest(main) {
        val probe = model()
        var timed = false
        probe.start(prepare = { delay(2_000) }, recognize = { timed = true })
        advanceTimeBy(1_000)
        probe.stop()
        advanceUntilIdle()
        assertThat(timed).isFalse()
        assertThat(probe.advice.value).isEqualTo(EngineAdvice.Measuring)
    }

    /** 进程被杀回来：SavedStateHandle 里有结论就不再测；没测完就被杀的，回来重测。 */
    @Test fun `a saved verdict is not measured again`() = runTest(main) {
        val saved = SavedStateHandle()
        model(saved).start(prepare = {}, recognize = { delay(900) })
        advanceUntilIdle()
        var runs = 0
        val restored = model(saved)
        restored.start(prepare = {}, recognize = { runs++ })
        advanceUntilIdle()
        assertThat(runs).isEqualTo(0)
        assertThat(restored.advice.value).isEqualTo(EngineAdvice.Fast(900))
    }

    @Test fun `every verdict survives being parcelled`() {
        val verdicts = listOf(EngineAdvice.Measuring, EngineAdvice.Fast(1_200), EngineAdvice.Slow(null), EngineAdvice.Slow(4_200), EngineAdvice.Failed)
        for (advice in verdicts) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(advice, 0)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                val back = parcel.readParcelable<EngineAdvice>(EngineAdvice::class.java.classLoader)
                assertThat(back).isEqualTo(advice)
            } finally {
                parcel.recycle()
            }
        }
    }

    /** 真跑一遍：引导打开时 HomeActivity 用的就是它，在打包进去的样图上跑得通。 */
    @Test fun `the real PP-OCR probe runs on the bundled sample`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val probe = PpOcrProbe(context, TourSample.load(context.resources))
        runBlocking {
            val start = System.nanoTime()
            probe.prepare()
            val warm = System.nanoTime()
            probe.recognize()
            println("PP-OCR 加载模型和头一遍 ${(warm - start) / 1_000_000} ms，之后一遍 ${(System.nanoTime() - warm) / 1_000_000} ms")
        }
    }
}
