package com.youma.app.engine.genai

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 给 Gemini Nano 的提示词与回答解析。模型本身测不了（只在支持 AICore 的真机上有），
 * 但「送什么进去、信它说的哪一句」全在这里，这一层必须钉死。
 */
class SemanticPromptTest {

    private val texts = listOf(
        "明天让张伟来拿一下",
        "12:30",
        "我住在阳光花园 5 幢",
    )

    @Test fun `lines are numbered from one and skipped lines keep their index`() {
        val chunk = SemanticPrompt.chunks(listOf(texts[0], null, texts[2])).single()
        assertThat(chunk.prompt).endsWith("1: 明天让张伟来拿一下\n2: 我住在阳光花园 5 幢\n")
        assertThat(chunk.lineIndices).containsExactly(0, 2).inOrder()
    }

    @Test fun `long pages are split into several requests`() {
        val many = List(50) { "第${it}行的一些文字内容" }
        val chunks = SemanticPrompt.chunks(many, budget = 100)
        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.flatMap { it.lineIndices }).isEqualTo(many.indices.toList())
    }

    @Test fun `a well formed reply becomes ranges on the right lines`() {
        val chunk = SemanticPrompt.chunks(listOf(texts[0], null, texts[2])).single()
        val found = SemanticPrompt.parse("1|人名|张伟\n2|地址|阳光花园 5 幢", chunk, texts)
        assertThat(found).containsExactly(
            SemanticPrompt.Finding(0, 3..4, SensitiveKind.PERSON_NAME),
            SemanticPrompt.Finding(2, 3..10, SensitiveKind.POSTAL_ADDRESS),
        )
    }

    /** 模型复述时常吞掉或多加空格：忽略空格也能落回原行。 */
    @Test fun `a quote that differs only in spaces is still located`() {
        assertThat(SemanticPrompt.locate("我住在阳光花园 5 幢", "阳光花园5幢")).isEqualTo(3..10)
    }

    /** 找不到出处的区间不信：模型会改写、会编。 */
    @Test fun `a quote that is not in the line is dropped`() {
        val chunk = SemanticPrompt.chunks(texts).single()
        assertThat(SemanticPrompt.parse("1|人名|李四", chunk, texts)).isEmpty()
    }

    @Test fun `noise, unknown types and out of range lines are ignored`() {
        val chunk = SemanticPrompt.chunks(texts).single()
        val reply = """
            好的，以下是结果：
            无
            1|公司|张伟
            9|人名|张伟
            1 ｜ 人名 ｜ 「张伟」
        """.trimIndent()
        // 只有最后一行是有效的：全角竖线、引号都容忍
        assertThat(SemanticPrompt.parse(reply, chunk, texts))
            .containsExactly(SemanticPrompt.Finding(0, 3..4, SensitiveKind.PERSON_NAME))
    }

    @Test fun `implausibly long names are dropped`() {
        val line = listOf("这是一段很长很长很长的普通说明文字")
        val chunk = SemanticPrompt.chunks(line).single()
        assertThat(SemanticPrompt.parse("1|人名|这是一段很长很长很长的普通说明文字", chunk, line)).isEmpty()
    }

    @Test fun `the instructions pin the reply format`() {
        assertThat(SemanticPrompt.INSTRUCTIONS).contains("行号|类型|原文")
        assertThat(SemanticPrompt.INSTRUCTIONS).contains("无")
    }
}
