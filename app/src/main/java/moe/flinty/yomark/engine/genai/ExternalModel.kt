package moe.flinty.yomark.engine.genai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 实验室里的外部模型（见 docs/superpowers/specs/2026-10-15-yomark-lab-external-model-design.md）：
 * 开关和接口。存在 SettingsStore，不进 RecognitionConfig——进了的话改一个字编辑器就要重跑整张图的识别。
 *
 * @param baseUrl OpenAI 兼容接口的地址，写到 /v1 为止（https://api.openai.com/v1），写到 /chat/completions 的也认
 * @param apiKey 可以不填：自己架的、不要 key 的接口
 */
data class ExternalModelSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 开着、填好了接口地址（https）和模型：AI 增强识别改用它。 */
    val active: Boolean get() = enabled && endpoint != null && model.isNotBlank()

    /** 请求发到哪：接口地址后面接上 /chat/completions。不是 https 的为 null：地址里带着 key，Android 也不许明文。 */
    val endpoint: String?
        get() {
            val base = baseUrl.trim().trimEnd('/')
            if (!base.startsWith("https://", ignoreCase = true) || base.length <= "https://".length) return null
            return if (base.endsWith(CHAT_PATH, ignoreCase = true)) base else base + CHAT_PATH
        }

    /** 调试信息、设置页上写的：只有主机名，不写 key。 */
    val host: String get() = endpoint?.let { runCatching { URL(it).host }.getOrNull() }.orEmpty()

    private companion object {
        const val CHAT_PATH = "/chat/completions"
    }
}

/** 外部模型没答上来。[reason] 给设置页的「测试连接」挑说法，[detail] 是接口自己说的（截短了）。 */
class ExternalModelException(
    val reason: Reason,
    val status: Int = 0,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(listOfNotNull(reason.name, status.takeIf { it > 0 }?.toString(), detail).joinToString(" "), cause) {
    enum class Reason { HTTP, TIMEOUT, NETWORK, BAD_REPLY }
}

/**
 * OpenAI 兼容的 Chat Completions 接口，一问一答，不流式。阻塞的网络读写放在 IO 线程上，协程取消时打断它。
 *
 * 请求体带 temperature 0（同一页每次同一个回答）和 reasoning_effort low（不带时推理模型一次要想几十秒，实测见设计文档）。
 * 有的接口不认后两项、回 400，就只带 model 和 messages 再发一次。
 */
object ExternalModelClient {

    /** 问一句，回答的正文；不是 2xx、超时、连不上、回答里没有正文都抛 [ExternalModelException]。 */
    suspend fun ask(settings: ExternalModelSettings, prompt: String): String {
        val endpoint = settings.endpoint ?: throw ExternalModelException(ExternalModelException.Reason.NETWORK, detail = "https")
        val body = runInterruptible(Dispatchers.IO) {
            try {
                post(endpoint, settings.apiKey, requestBody(settings.model, prompt, minimal = false))
            } catch (e: ExternalModelException) {
                if (e.status != 400) throw e
                post(endpoint, settings.apiKey, requestBody(settings.model, prompt, minimal = true))
            }
        }
        return replyText(body) ?: throw ExternalModelException(ExternalModelException.Reason.BAD_REPLY, detail = body.take(DETAIL_CHARS))
    }

    /** 设置页的「测试连接」：问一句最短的话，回答所用的毫秒数。 */
    suspend fun check(settings: ExternalModelSettings): Long {
        val started = System.currentTimeMillis()
        ask(settings, PING)
        return System.currentTimeMillis() - started
    }

    internal fun requestBody(model: String, prompt: String, minimal: Boolean): String {
        val body = JSONObject()
            .put("model", model.trim())
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        if (!minimal) body.put("temperature", 0).put("reasoning_effort", "low")
        return body.toString()
    }

    /**
     * 回答的正文：choices[0].message.content，是分段数组的把文字段接起来。
     * 有的推理模型把思考写在正文里、包在 <think> 里，去掉它——思考里也可能写出「人名|张三」这样的行。
     */
    internal fun replyText(json: String): String? {
        val message = runCatching { JSONObject(json) }.getOrNull()
            ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        val content = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString("")
            else -> return null
        }
        return THINK.replace(content, "").substringAfterLast("</think>").trim()
    }

    /** 出错时接口自己说的：error.message 或 message，都没有就是正文开头一截。 */
    internal fun errorDetail(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val said = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: body.trim().takeIf { it.isNotEmpty() }
        return said?.take(DETAIL_CHARS)
    }

    private fun post(endpoint: String, apiKey: String, body: String): String {
        val conn = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            // 地址里有空格之类的：MalformedURLException、IllegalArgumentException
            throw ExternalModelException(ExternalModelException.Reason.NETWORK, detail = e.message?.take(DETAIL_CHARS), cause = e)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ExternalModelException(ExternalModelException.Reason.HTTP, status, errorDetail(text))
            return text
        } catch (e: SocketTimeoutException) {
            throw ExternalModelException(ExternalModelException.Reason.TIMEOUT, cause = e)
        } catch (e: IOException) {
            throw ExternalModelException(ExternalModelException.Reason.NETWORK, detail = e.message?.take(DETAIL_CHARS), cause = e)
        } finally {
            conn.disconnect()
        }
    }

    private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private const val PING = "只回答一个字：好"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DETAIL_CHARS = 160
}
