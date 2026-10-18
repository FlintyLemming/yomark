package moe.flinty.yomark.engine.genai

import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Year

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
        val found = SemanticPrompt.parse("人名|张伟\n地址|阳光花园 5 幢", texts)
        assertThat(found).containsExactly(
            SemanticPrompt.Finding(0, 3..4, SensitiveKind.PERSON_NAME),
            SemanticPrompt.Finding(2, 3..10, SensitiveKind.POSTAL_ADDRESS),
        )
    }

    /** 真机上 Nano 常把行号写成 1、2、3 往下数：写了行号也照样认，但只按原文找，不信行号。 */
    @Test fun `a line number in the reply is tolerated and ignored`() {
        assertThat(SemanticPrompt.parse("1|地址|阳光花园 5 幢", texts))
            .containsExactly(SemanticPrompt.Finding(2, 3..10, SensitiveKind.POSTAL_ADDRESS))
    }

    /** 群聊里同一个昵称出现好几次：模型说一次，每一处都打码。 */
    @Test fun `every place the quote appears on the page is found`() {
        val chat = listOf("青柠", "周六集合", "@青柠 来接我", "\"大熊\" 拍了拍 \"青柠\"")
        val found = SemanticPrompt.parse("昵称|青柠", chat)
        assertThat(found.map { it.lineIndex to it.range }).containsExactly(0 to 0..1, 2 to 1..2, 3 to 10..11)
        assertThat(found.map { it.kind }.toSet()).containsExactly(SensitiveKind.CHAT_NAME)
    }

    /** 模型复述时常吞掉或多加空格：忽略空格也能落回原行。 */
    @Test fun `a quote that differs only in spaces is still located`() {
        assertThat(SemanticPrompt.locateAll("我住在阳光花园 5 幢", "阳光花园5幢")).containsExactly(3..10)
    }

    /** 找不到出处的区间不信：模型会改写、会编。 */
    @Test fun `a quote that is not on the page is dropped`() {
        assertThat(SemanticPrompt.parse("人名|李四", texts)).isEmpty()
    }

    @Test fun `noise and unknown types are ignored`() {
        val reply = """
            好的，以下是结果：
            无
            公司|张伟
            人名 ｜ 「张伟」
        """.trimIndent()
        // 只有最后一行是有效的：全角竖线、引号都容忍
        assertThat(SemanticPrompt.parse(reply, texts))
            .containsExactly(SemanticPrompt.Finding(0, 3..4, SensitiveKind.PERSON_NAME))
    }

    /** 用户关掉的类型提示词里没有，模型照写了也不认：它找到的直接打码，不能绕过设置。 */
    @Test fun `a type that was not asked for is not taken from the reply`() {
        val kinds = setOf(SensitiveKind.POSTAL_ADDRESS)
        assertThat(SemanticPrompt.parse("人名|张伟\n地址|阳光花园 5 幢", texts, kinds))
            .containsExactly(SemanticPrompt.Finding(2, 3..10, SensitiveKind.POSTAL_ADDRESS))
    }

    @Test fun `implausibly long names are dropped`() {
        val line = listOf("这是一段很长很长很长的普通说明文字")
        assertThat(SemanticPrompt.parse("人名|这是一段很长很长很长的普通说明文字", line)).isEmpty()
    }

    /** 英文全名常常超过 8 个字符，按词数算：4 个词以内的留下，再长的是一句话，不是名字。 */
    @Test fun `english full names are kept but sentences are not`() {
        val lines = listOf("Ship to Jennifer Walsh", "María José García López", "please contact our support team for help")
        val reply = """
            人名|Jennifer Walsh
            人名|María José García López
            人名|please contact our support team for help
        """.trimIndent()
        assertThat(SemanticPrompt.parse(reply, lines)).containsExactly(
            SemanticPrompt.Finding(0, 8..21, SensitiveKind.PERSON_NAME),
            SemanticPrompt.Finding(1, 0..22, SensitiveKind.PERSON_NAME),
        )
    }

    /** Nano 常把字段名当成值、把服务号码当成电话：样子对不上类型的不认。 */
    @Test fun `a quote that does not look like its type is dropped`() {
        val lines = listOf("联系手机", "138****5678", "客服 95588", "邮箱 未填写", "密码")
        val reply = """
            电话|联系手机
            电话|138****5678
            电话|95588
            邮箱|未填写
            密码|密码
        """.trimIndent()
        assertThat(SemanticPrompt.parse(reply, lines))
            .containsExactly(SemanticPrompt.Finding(1, 0..10, SensitiveKind.PHONE))
    }

    /** 话里的「儿子」「表弟」是称呼，不是谁的名字。 */
    @Test fun `a kinship word alone is neither a name nor a nickname`() {
        val lines = listOf("儿子，帮我买张票", "我表弟也来，他QQ 1056327894", "让张伟来拿")
        val reply = """
            人名|儿子
            昵称|表弟
            人名|张伟
        """.trimIndent()
        assertThat(SemanticPrompt.parse(reply, lines))
            .containsExactly(SemanticPrompt.Finding(2, 1..2, SensitiveKind.PERSON_NAME))
    }

    /** 短信签名【铁路客服】是发件机构：模型当成聊天标题时，一整列短信都会被它误打。 */
    @Test fun `nothing inside an SMS signature is taken`() {
        val lines = listOf("【铁路客服】订单EA39271846,刘思远您已购", "铁路客服", "【交管12123】苏E·5R82M 违章")
        val found = SemanticPrompt.parse("聊天标题|铁路客服\n人名|刘思远\n车牌|苏E·5R82M", lines)
        assertThat(found.map { it.lineIndex to it.kind }).containsExactly(
            0 to SensitiveKind.PERSON_NAME,
            1 to SensitiveKind.CHAT_TITLE,
            2 to SensitiveKind.LICENSE_PLATE,
        )
    }

    /** 字母、数字的原文不能从更长的词、数中间截出来。 */
    @Test fun `a latin or digit quote is not found inside a longer word or number`() {
        val lines = listOf("Click here", "Li Wei", "code 123456", "on 12th")
        assertThat(SemanticPrompt.parse("人名|Li", lines).map { it.lineIndex }).containsExactly(1)
        assertThat(SemanticPrompt.parse("验证码|1234", lines)).isEmpty()
        assertThat(SemanticPrompt.parse("验证码|123456", lines).map { it.lineIndex }).containsExactly(2)
    }

    /** 数字隔着小数点、冒号、横杠又接着数字，是价钱、时间、另一个码的一截。 */
    @Test fun `a number is not cut out of a price, a time or a longer code`() {
        val lines = listOf("合计 ¥2087.00", "取件码 6-3-2087", "12:30 送达")
        assertThat(SemanticPrompt.parse("取件码|2087", lines)).isEmpty()
        assertThat(SemanticPrompt.parse("取件码|6-3-2087", lines).map { it.lineIndex }).containsExactly(1)
        // 两三位的号码到处都是，不认
        assertThat(SemanticPrompt.parse("取件码|12", lines)).isEmpty()
    }

    /** 原文自己带着点、横杠时，后面接的「:8080」、逗号隔开的下一个码不是它的一截。 */
    @Test fun `a quote with its own separators is still found next to a different one`() {
        assertThat(SemanticPrompt.parse("IP|192.168.1.10", listOf("http://192.168.1.10:8080/admin")).map { it.range })
            .containsExactly(7..18)
        assertThat(SemanticPrompt.parse("取件码|3-1-4022", listOf("取件码3-1-4021,3-1-4022")).map { it.range })
            .containsExactly(12..19)
        assertThat(SemanticPrompt.parse("IP|168.1.10", listOf("192.168.1.10"))).isEmpty()
    }

    @Test fun `a short verification code or password is not taken`() {
        val lines = listOf("您的验证码552817，10分钟内有效", "10月15日 共10件")
        assertThat(SemanticPrompt.parse("验证码|10\n密码|8位", lines)).isEmpty()
        assertThat(SemanticPrompt.parse("验证码|552817", lines).map { it.lineIndex }).containsExactly(0)
    }

    /** 句子中间的【】是正文，不是签名。 */
    @Test fun `a code in brackets mid-sentence is still taken`() {
        val lines = listOf("您的验证码为【552817】，5分钟内有效", "请凭取件码【3-1-4021】取件")
        assertThat(SemanticPrompt.parse("验证码|552817\n取件码|3-1-4021", lines).map { it.lineIndex }).containsExactly(0, 1)
    }

    @Test fun `the name line of a passport machine readable zone is taken`() {
        val lines = listOf("P<USAHARTLEY<<CLAIRE<ELIZABETH<<<<<<<<<<<<<<", "UNITED STATES OF AMERICA")
        assertThat(SemanticPrompt.parse("护照|P<USAHARTLEY<<CLAIRE<ELIZABETH<<<<<<<<<<<<<<\n护照|UNITED STATES OF AMERICA", lines)
            .map { it.lineIndex to it.kind }).containsExactly(0 to SensitiveKind.PASSPORT)
    }

    /** 群聊里同一个名字一段报人名、一段报昵称：同一处只留一个码，算昵称。 */
    @Test fun `one place reported under two types becomes one finding`() {
        val lines = listOf("@张伟 来一下")
        assertThat(SemanticPrompt.parse("人名|张伟\n昵称|张伟", lines))
            .containsExactly(SemanticPrompt.Finding(0, 1..2, SensitiveKind.CHAT_NAME))
        val a = SemanticPrompt.parse("人名|张伟", lines)
        val b = SemanticPrompt.parse("昵称|张伟", lines)
        assertThat(SemanticPrompt.collapse(a + b)).containsExactly(SemanticPrompt.Finding(0, 1..2, SensitiveKind.CHAT_NAME))
    }

    /** 私聊的标题是对方的备注：「妈妈」只在顶栏那一处，消息里的「妈妈」不是它。 */
    @Test fun `a chat title is only its first place on the page`() {
        val lines = listOf("< 妈妈", "妈妈说今天降温")
        assertThat(SemanticPrompt.parse("聊天标题|妈妈", lines).map { it.lineIndex }).containsExactly(0)
    }

    @Test fun `an address needs a number or an address word`() {
        val lines = listOf("G7316次上海虹桥08:35开", "江宁区翠屏山庄12栋2单元601", "4417 Brookhaven Dr")
        assertThat(SemanticPrompt.parse("地址|上海虹桥", lines)).isEmpty()
        assertThat(SemanticPrompt.parse("地址|翠屏山庄12栋2单元601\n地址|Brookhaven Dr", lines).map { it.lineIndex })
            .containsExactly(1, 2)
    }

    /** 下单日期报成生日：生日得是往年的。 */
    @Test fun `a birthday needs a year that has passed`() {
        val thisYear = Year.now().value
        val lines = listOf("下单 $thisYear-10-15", "出生 1993年7月21日", "张三 19900101 男")
        assertThat(SemanticPrompt.parse("生日|$thisYear-10-15\n生日|1993年7月21日\n生日|19900101", lines).map { it.lineIndex })
            .containsExactly(1, 2)
    }

    /** 用户关掉的那一类，模型换个类型报上来也不认。 */
    @Test fun `a vetoed quote is not taken`() {
        val lines = listOf("联系我 18910327755")
        assertThat(SemanticPrompt.parse("证件号|18910327755", lines, vetoed = { _, q -> q == "18910327755" })).isEmpty()
    }

    @Test fun `brackets copied with a signature do not get it past the signature check`() {
        val lines = listOf("【铁路客服】订单EA39271846已出票")
        assertThat(SemanticPrompt.parse("聊天标题|【铁路客服】", lines)).isEmpty()
    }

    @Test fun `ignoring spaces still finds every place`() {
        assertThat(SemanticPrompt.locateAll("阳光花园 5 幢，阳光花园 5幢", "阳光花园5幢")).containsExactly(0..7, 9..15)
    }

    /** 打开了日期，就不能同时说日期不算隐私。 */
    @Test fun `a type that is asked for is not also listed as not private`() {
        assertThat(SemanticPrompt.instructions(setOf(SensitiveKind.PERSON_NAME))).contains("普通的日期时间")
        assertThat(SemanticPrompt.instructions(setOf(SensitiveKind.PERSON_NAME, SensitiveKind.DATETIME)))
            .doesNotContain("普通的日期时间")
    }

    @Test fun `the instructions list only the types that are asked for`() {
        val asked = SemanticPrompt.instructions(setOf(SensitiveKind.PERSON_NAME, SensitiveKind.PHONE))
        assertThat(asked).contains("人名：")
        assertThat(asked).contains("电话：")
        assertThat(asked).doesNotContain("昵称：")
        assertThat(asked).doesNotContain("日期：")
        // 例子的回答里也只有问到的类型，不然模型会照着写出来
        assertThat(asked).contains("电话|18910327755")
        assertThat(asked).doesNotContain("昵称|")
        assertThat(asked).doesNotContain("取件码|")
    }

    @Test fun `nothing asked means nothing to send`() {
        assertThat(SemanticPrompt.instructions(emptySet())).isEmpty()
        assertThat(SemanticPrompt.chunks(texts, emptySet())).isEmpty()
        assertThat(SemanticPrompt.parse("人名|张伟", texts, emptySet())).isEmpty()
    }

    @Test fun `the instructions pin the reply format`() {
        val instructions = SemanticPrompt.instructions(SemanticPrompt.KINDS)
        assertThat(instructions).contains("类型|原文")
        assertThat(instructions).contains("无")
    }

    /** OCR 把一个地址拆成了两行，模型复述的是一整段：拆成两段，各落在自己那一行上，空格不算。 */
    @Test fun `a quote that spans two adjacent lines is split onto both`() {
        val card = listOf("住 址 浙江省杭州市西湖区文三路", "88号6幢2单元501 室", "公民身份号码")
        val found = SemanticPrompt.parse("地址|浙江省杭州市西湖区文三路88号6幢2单元501室", card)
        assertThat(found).containsExactly(
            SemanticPrompt.Finding(0, 4..15, SensitiveKind.POSTAL_ADDRESS),
            SemanticPrompt.Finding(1, 0..12, SensitiveKind.POSTAL_ADDRESS),
        )
    }

    /** 单行里找得到就不去拼行：同一个名字在两行交界处碰巧也拼得出来，不算。 */
    @Test fun `lines are only joined when no single line has the quote`() {
        val chat = listOf("让张", "伟来，张伟也来")
        assertThat(SemanticPrompt.parse("人名|张伟", chat)).containsExactly(SemanticPrompt.Finding(1, 3..4, SensitiveKind.PERSON_NAME))
    }

    /** 不相邻的两行拼不成一处：中间隔着一行就不是同一段字。 */
    @Test fun `lines that are not adjacent are not joined`() {
        val page = listOf("长江路88号", "收件人", "阳光新村5幢")
        assertThat(SemanticPrompt.parse("地址|长江路88号阳光新村5幢", page)).isEmpty()
    }
}
