package moe.flinty.yomark.engine.genai

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 外部模型的请求体、回答解析和设置。网络本身不测（单测不联网），真接口在模拟器上验过。org.json 要 Robolectric。 */
@RunWith(RobolectricTestRunner::class)
class ExternalModelClientTest {

    @Test fun `the base url gets the chat completions path once`() {
        assertThat(ExternalModelSettings(baseUrl = "https://api.example.com/v1").endpoint)
            .isEqualTo("https://api.example.com/v1/chat/completions")
        assertThat(ExternalModelSettings(baseUrl = " https://api.example.com/v1/ ").endpoint)
            .isEqualTo("https://api.example.com/v1/chat/completions")
        assertThat(ExternalModelSettings(baseUrl = "https://api.example.com/v1/chat/completions").endpoint)
            .isEqualTo("https://api.example.com/v1/chat/completions")
    }

    /** 地址里带着 key，Android 也不许明文：不是 https 的不认。 */
    @Test fun `only https endpoints are taken`() {
        assertThat(ExternalModelSettings(baseUrl = "http://192.168.1.2:11434/v1").endpoint).isNull()
        assertThat(ExternalModelSettings(baseUrl = "api.example.com/v1").endpoint).isNull()
        assertThat(ExternalModelSettings(baseUrl = "https://").endpoint).isNull()
    }

    @Test fun `it is active only when switched on with an endpoint and a model`() {
        val filled = ExternalModelSettings(enabled = true, baseUrl = "https://api.example.com/v1", model = "m")
        assertThat(filled.active).isTrue()
        assertThat(filled.copy(apiKey = "").active).isTrue()                  // 不要 key 的接口
        assertThat(filled.copy(enabled = false).active).isFalse()
        assertThat(filled.copy(model = " ").active).isFalse()
        assertThat(filled.copy(baseUrl = "http://api.example.com/v1").active).isFalse()
        assertThat(filled.host).isEqualTo("api.example.com")
    }

    @Test fun `the request asks for temperature zero and low reasoning, and the fallback drops both`() {
        val full = JSONObject(ExternalModelClient.requestBody(" glm ", "你好", minimal = false))
        assertThat(full.getString("model")).isEqualTo("glm")
        assertThat(full.getJSONArray("messages").getJSONObject(0).getString("content")).isEqualTo("你好")
        assertThat(full.getInt("temperature")).isEqualTo(0)
        assertThat(full.getString("reasoning_effort")).isEqualTo("low")

        val minimal = JSONObject(ExternalModelClient.requestBody("glm", "你好", minimal = true))
        assertThat(minimal.has("temperature")).isFalse()
        assertThat(minimal.has("reasoning_effort")).isFalse()
    }

    @Test fun `the reply text is the first choice's content`() {
        val json = """{"choices":[{"message":{"role":"assistant","content":"人名|张伟\n"}}]}"""
        assertThat(ExternalModelClient.replyText(json)).isEqualTo("人名|张伟")
    }

    @Test fun `content given as parts is joined`() {
        val json = """{"choices":[{"message":{"content":[{"type":"text","text":"人名|"},{"type":"text","text":"张伟"}]}}]}"""
        assertThat(ExternalModelClient.replyText(json)).isEqualTo("人名|张伟")
    }

    /** 推理模型把思考写在正文里：思考里也会写出「人名|张三」这样的行，去掉。开头的 <think> 被模板吃掉的也认。 */
    @Test fun `thinking written into the content is dropped`() {
        val tagged = """{"choices":[{"message":{"content":"<think>人名|张三？不对</think>\n人名|张伟"}}]}"""
        assertThat(ExternalModelClient.replyText(tagged)).isEqualTo("人名|张伟")
        val headless = """{"choices":[{"message":{"content":"先想想，人名|张三？</think>人名|张伟"}}]}"""
        assertThat(ExternalModelClient.replyText(headless)).isEqualTo("人名|张伟")
    }

    @Test fun `a reply without content is not a reply`() {
        assertThat(ExternalModelClient.replyText("""{"choices":[]}""")).isNull()
        assertThat(ExternalModelClient.replyText("""{"choices":[{"message":{"content":null}}]}""")).isNull()
        assertThat(ExternalModelClient.replyText("<html>502 Bad Gateway</html>")).isNull()
    }

    @Test fun `an error body says what went wrong`() {
        assertThat(ExternalModelClient.errorDetail("""{"error":{"message":"Invalid API key","type":"auth"}}"""))
            .isEqualTo("Invalid API key")
        assertThat(ExternalModelClient.errorDetail("""{"message":"model not found"}""")).isEqualTo("model not found")
        assertThat(ExternalModelClient.errorDetail("Bad Gateway")).isEqualTo("Bad Gateway")
        assertThat(ExternalModelClient.errorDetail("")).isNull()
    }
}
