package moe.flinty.yomark.ui.onboarding

import android.content.Context
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.engine.ppocr.PpOcrModels

/**
 * 首次引导里给 PP-OCR 测速，「识别引擎」那一页按它推荐：引导一打开就在后台拿真 PP-OCR 识别样图（见 [PpOcrProbe]）。
 * 加载模型、头一遍预热都不计时，计的是之后再识别一遍，和以后每张图要等的差不多。
 * 不超过 [LIMIT_MS] 推荐 PP-OCR，超过了推荐 ML Kit。
 *
 * 计时的那一遍到点还没跑完就不等了，已经知道慢；接着跑完只为把用了多久补上（页上的字、logcat 里的 YomarkTour）。
 * 引导走完就 [stop]：结论没人看了，还没开始的那一遍不跑，不和编辑器抢 CPU。正在跑的那一遍在 ONNX Runtime 里停不下来，
 * 跑完就算；模型留在进程里，编辑器接着用。
 *
 * 结论存在 [SavedStateHandle] 里：转屏不重测（ViewModel 本来就还在），进程被杀回来也不重测；没测完就被杀的回来重测。
 *
 * @param clock 计时用的钟，测试里换成虚拟时间
 */
internal class EngineProbeModel(
    private val saved: SavedStateHandle,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    val advice: StateFlow<EngineAdvice> = saved.getStateFlow(KEY, EngineAdvice.Measuring)

    private var started = false
    private var job: Job? = null

    /** 开始测：[prepare] 加载模型、预热，不计时；[recognize] 是计时的那一遍。只测一次，再叫不理。 */
    fun start(prepare: suspend () -> Unit, recognize: suspend () -> Unit) {
        if (started || advice.value != EngineAdvice.Measuring) return
        started = true
        job = viewModelScope.launch {
            if (catching { prepare() } == null) {
                saved[KEY] = EngineAdvice.Failed
                return@launch
            }
            val run = async {
                catching {
                    val start = clock()
                    recognize()
                    clock() - start
                }
            }
            // 刚好 3 秒也算没超过：多等 1 ms，和下面按毫秒比的说法一致
            val inTime = withTimeoutOrNull(LIMIT_MS + 1) { run.join() } != null
            if (!inTime) saved[KEY] = EngineAdvice.Slow(null)
            val ms = run.await()
            if (ms != null) Log.i(TAG, "PP-OCR 识别样图 $ms ms（不含加载模型、预热）")
            saved[KEY] = when {
                ms == null -> EngineAdvice.Failed
                inTime && ms <= LIMIT_MS -> EngineAdvice.Fast(ms)
                else -> EngineAdvice.Slow(ms)
            }
        }
    }

    /** 引导走完了：不再接着测。 */
    fun stop() {
        job?.cancel()
    }

    companion object {
        /** 超过这么久就推荐 ML Kit，刚好这么久的还算快。 */
        const val LIMIT_MS = 3_000L

        private const val KEY = "engine_advice"
    }
}

/**
 * 真跑 PP-OCR 认 [sample]，和编辑器里的识别是同一个（[PaddleTextRecognizer]）。
 * [prepare] 加载模型（进程里只加载一次，见 [PpOcrModels]），再空跑一遍：头一遍要预热，比之后每一遍都慢得多。
 */
internal class PpOcrProbe(private val context: Context, private val sample: SourceImage) {

    private val recognizer = PaddleTextRecognizer(context)

    suspend fun prepare() {
        val start = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) { PpOcrModels.engine(context) }
        val loaded = SystemClock.elapsedRealtime()
        recognizer.recognize(sample)
        Log.i(TAG, "PP-OCR 加载模型 ${loaded - start} ms，头一遍（含预热）${SystemClock.elapsedRealtime() - loaded} ms")
    }

    suspend fun recognize() {
        recognizer.recognize(sample)
    }
}

/** 测速走到哪儿了，「识别引擎」那一页按它说话、按它预先选好。 */
internal sealed interface EngineAdvice : Parcelable {

    /** 还在加载、预热，或者计时的那一遍还没到 [EngineProbeModel.LIMIT_MS]。 */
    @Parcelize data object Measuring : EngineAdvice

    /** 没超过 [EngineProbeModel.LIMIT_MS] 就跑完了，用了 [ms] 毫秒：推荐 PP-OCR。 */
    @Parcelize data class Fast(val ms: Long) : EngineAdvice

    /** 超过了 [EngineProbeModel.LIMIT_MS]：推荐 ML Kit。[ms] 是跑完时一共用了多久，还没跑完是 null。 */
    @Parcelize data class Slow(val ms: Long?) : EngineAdvice

    /** PP-OCR 在这台机器上没跑成（模型加载不了、内存不够）：推荐 ML Kit。 */
    @Parcelize data object Failed : EngineAdvice
}

/** 推荐哪个引擎；还在测时是 null。 */
internal val EngineAdvice.recommended: TextEngineOption?
    get() = when (this) {
        EngineAdvice.Measuring -> null
        is EngineAdvice.Fast -> TextEngineOption.PADDLE
        is EngineAdvice.Slow, EngineAdvice.Failed -> TextEngineOption.MLKIT
    }

/** 跑 [block]；没跑成（连 OOM 也接住，同 RedactionEngine.analyze）记一笔、返回 null：这台机器跑不动 PP-OCR，正该推荐 ML Kit。 */
private inline fun <T : Any> catching(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Log.w(TAG, "PP-OCR 测速没跑成", e)
    null
}

private const val TAG = "YomarkTour"
