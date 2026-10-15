package moe.flinty.yomark.engine

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test

/** AI 增强识别找的就是用户开着的那几类：它找到的直接打码，关掉的不能从它这里漏回来。 */
class AiKindsTest {

    @Test fun `out of the box it looks for every kind that is masked out of the box, chat included`() {
        val kinds = aiKinds(RecognitionConfig())
        assertThat(kinds).containsAtLeast(
            SensitiveKind.PERSON_NAME, SensitiveKind.PHONE, SensitiveKind.POSTAL_ADDRESS, SensitiveKind.PICKUP_CODE,
            SensitiveKind.ID_NUMBER, SensitiveKind.PAYMENT_CARD, SensitiveKind.API_KEY,
            SensitiveKind.CHAT_NAME, SensitiveKind.CHAT_TITLE,
        )
        // 出厂关着的几类不找
        assertThat(kinds).containsNoneOf(
            SensitiveKind.DATETIME, SensitiveKind.URL, SensitiveKind.IP_ADDR, SensitiveKind.TRACKING_NO, SensitiveKind.LONG_NUMBER,
        )
    }

    @Test fun `a kind switched off is not looked for, one switched on is`() {
        val kinds = aiKinds(RecognitionConfig(ruleOverrides = mapOf("phone" to RuleState.OFF, "url" to RuleState.MASKED)))
        assertThat(kinds).doesNotContain(SensitiveKind.PHONE)
        assertThat(kinds).contains(SensitiveKind.URL)
    }

    @Test fun `with chat screenshots off it does not look for nicknames or the chat title`() {
        val kinds = aiKinds(RecognitionConfig(chatState = RuleState.OFF))
        assertThat(kinds).containsNoneOf(SensitiveKind.CHAT_NAME, SensitiveKind.CHAT_TITLE)
        assertThat(kinds).contains(SensitiveKind.PERSON_NAME)
    }
}
