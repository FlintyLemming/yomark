package moe.flinty.yomark.engine.genai

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.engine.genai.NanoStatus.Model
import org.junit.Test

/**
 * 设置「AI」页上的 Gemini Nano 状态：ML Kit 的两问（checkStatus、getBaseModelName）怎么落成一个结论。
 * 模型本身只在支持 AICore 的真机上有，这里换成两个函数喂进去。
 */
class NanoStatusTest {

    private suspend fun probe(status: suspend () -> Int, name: suspend () -> String = { "nano-v3" }) =
        NanoStatus.probe(status, name)

    @Test fun `a ready model reports its version`() = runTest {
        assertThat(probe({ FeatureStatus.AVAILABLE })).isEqualTo(NanoStatus.Present("nano-v3", Model.READY))
    }

    @Test fun `a model not yet on the device still has a version`() = runTest {
        assertThat(probe({ FeatureStatus.DOWNLOADABLE }, { "nano-v2" }))
            .isEqualTo(NanoStatus.Present("nano-v2", Model.NOT_DOWNLOADED))
        assertThat(probe({ FeatureStatus.DOWNLOADING }))
            .isEqualTo(NanoStatus.Present("nano-v3", Model.DOWNLOADING))
    }

    /** 不支持的机型上不再去问版本：ML Kit 在那儿问版本只会抛异常。 */
    @Test fun `an unsupported device has no version and is not asked for one`() = runTest {
        var asked = false
        val status = probe({ FeatureStatus.UNAVAILABLE }, { asked = true; "nano-v3" })
        assertThat(status).isEqualTo(NanoStatus.Unavailable())
        assertThat(asked).isFalse()
    }

    /** 绑不上 AICore、功能找不到之类：一样算本机用不了，错误码留着给人看。 */
    @Test fun `an AICore error counts as unavailable and keeps its code`() = runTest {
        val status = probe({ throw GenAiException(null, GenAiException.ErrorCode.NOT_AVAILABLE) })
        assertThat(status).isEqualTo(NanoStatus.Unavailable(GenAiException.ErrorCode.NOT_AVAILABLE))
    }

    @Test fun `any other failure counts as unavailable without a code`() = runTest {
        assertThat(probe({ throw IllegalStateException("no AICore") })).isEqualTo(NanoStatus.Unavailable())
    }

    @Test fun `a status check that never answers gives up`() = runTest {
        assertThat(probe({ awaitCancellation() })).isEqualTo(NanoStatus.Unavailable())
    }

    /** 版本只是附带的：问不到、问不完、问回来是空的，状态照样报。 */
    @Test fun `a version that cannot be read leaves the status intact`() = runTest {
        val ready = NanoStatus.Present(null, Model.READY)
        assertThat(probe({ FeatureStatus.AVAILABLE }, { throw IllegalStateException("aiFeature is null") })).isEqualTo(ready)
        assertThat(probe({ FeatureStatus.AVAILABLE }, { awaitCancellation() })).isEqualTo(ready)
        assertThat(probe({ FeatureStatus.AVAILABLE }, { " " })).isEqualTo(ready)
    }
}
