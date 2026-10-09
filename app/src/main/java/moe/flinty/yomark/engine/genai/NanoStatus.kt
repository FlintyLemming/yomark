package moe.flinty.yomark.engine.genai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 本机的 Gemini Nano：有没有，是哪一版，模型在不在设备上。设置的「AI」页显示用。
 *
 * 只看不下：不像 [NanoClient.ready] 那样顺手请 AICore 去下载，打开设置看一眼不该引来一次模型下载。
 */
sealed interface NanoStatus {

    /**
     * 本机用不了：机型不支持、没装 AICore，或者 AICore 还没准备好（刚重置过的手机要等它拿到配置）。
     * [errorCode] 是问的时候 AICore 报的错（[GenAiException.getErrorCode]），没报错就是 null。
     */
    data class Unavailable(val errorCode: Int? = null) : NanoStatus

    /**
     * 本机有。[version] 是 ML Kit 报的基础模型名，如 nano-v2、nano-v3、nano-v4-fast；
     * 不同版本对同一段提示词的回答可能不一样。问不到时为 null。
     */
    data class Present(val version: String?, val model: Model) : NanoStatus

    /** 模型本身在不在设备上。下载是系统的 AICore 服务做的，不经过本应用。 */
    enum class Model { NOT_DOWNLOADED, DOWNLOADING, READY }

    companion object {

        /**
         * 先问状态，本机有才问版本。版本问不到不算失败，照样报状态。
         *
         * 两个参数就是 GenerativeModel 的 checkStatus 与 getBaseModelName，拆成函数好单测。
         * 每一步都限时：AICore 的服务连接挂住时，设置页不能一直停在「正在检测」。
         */
        suspend fun probe(
            checkStatus: suspend () -> Int,
            baseModelName: suspend () -> String,
            timeoutMs: Long = TIMEOUT_MS,
        ): NanoStatus {
            val status = try {
                withTimeoutOrNull(timeoutMs) { checkStatus() } ?: return Unavailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Unavailable((e as? GenAiException)?.errorCode)
            }
            val model = when (status) {
                FeatureStatus.AVAILABLE -> Model.READY
                FeatureStatus.DOWNLOADING -> Model.DOWNLOADING
                FeatureStatus.DOWNLOADABLE -> Model.NOT_DOWNLOADED
                else -> return Unavailable()
            }
            val version = try {
                withTimeoutOrNull(timeoutMs) { baseModelName() }?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            return Present(version, model)
        }

        private const val TIMEOUT_MS = 10_000L
    }
}
