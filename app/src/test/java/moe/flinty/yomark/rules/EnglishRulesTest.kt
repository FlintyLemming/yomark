package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 英文的人名、地址（逐行，出厂规则表）。例子取自第一轮调研的英文样张（运单、订单邮件、收货卡片、聊天）。
 *
 * 要旁证的猜测（[EnglishNameGuess]）在这里只看认不认得出、带没带上 needsAnchor；有没有旁证看整页，见 NameAnchorPageTest。
 */
class EnglishRulesTest {

    private val nameRule = DefaultRuleSet.rules.first { it.id == "name" }
    private val addressRule = DefaultRuleSet.rules.first { it.id == "address" }

    private fun addresses(text: String): List<String> = addressRule.findIn(text).map { text.substring(it.range) }

    /** 不要旁证就算数的人名。 */
    private fun names(text: String): List<String> =
        nameRule.findIn(text).filter { !it.needsAnchor }.map { text.substring(it.range) }

    /** 要旁证的猜测。 */
    private fun guesses(text: String): List<String> =
        nameRule.findIn(text).filter { it.needsAnchor }.map { text.substring(it.range) }

    // ---------- 地址 ----------

    @Test fun `us street lines with units`() {
        assertThat(addresses("2847 Maple Grove Dr, Apt 12C")).containsExactly("2847 Maple Grove Dr, Apt 12C")
        assertThat(addresses("4567 OAK AVE STE 210")).containsExactly("4567 OAK AVE STE 210")
        assertThat(addresses("Ship from: 1200 INDUSTRIAL PKWY")).contains("1200 INDUSTRIAL PKWY")
    }

    @Test fun `city state and zip`() {
        assertThat(addresses("Columbus, OH 43215")).containsExactly("Columbus, OH 43215")
        assertThat(addresses("LOS ANGELES CA 90012-3456")).containsExactly("LOS ANGELES CA 90012-3456")
        assertThat(addresses("Portland, Maine 04101")).containsExactly("Portland, Maine 04101")
    }

    /** 同一行上的街道和城市连成一段。 */
    @Test fun `a one line address is one span`() {
        assertThat(addresses("Delivered to 2210 Fillmore St, San Francisco, CA 94115"))
            .containsExactly("2210 Fillmore St, San Francisco, CA 94115")
    }

    @Test fun `uk canadian australian irish and singapore postcodes`() {
        assertThat(addresses("Flat 4, 27 Kingsley Road")).containsExactly("Flat 4, 27 Kingsley Road")
        assertThat(addresses("Manchester M14 6PL")).containsExactly("Manchester M14 6PL")
        assertThat(addresses("LS6 3DQ")).containsExactly("LS6 3DQ")
        assertThat(addresses("Toronto, ON M5V 3L9")).contains("ON M5V 3L9")
        assertThat(addresses("Surry Hills NSW 2010")).containsExactly("Surry Hills NSW 2010")
        assertThat(addresses("Dublin 2, D02 X285")).contains("D02 X285")
        assertThat(addresses("Singapore 238859")).containsExactly("Singapore 238859")
    }

    @Test fun `pinyin addresses and po boxes`() {
        assertThat(addresses("Room 1204, Building 2, No. 18 Zhongshan Road"))
            .containsExactly("Room 1204, Building 2, No. 18 Zhongshan Road")
        assertThat(addresses("Xuanwu District, Nanjing, Jiangsu 210018"))
            .containsExactly("Xuanwu District, Nanjing, Jiangsu 210018")
        assertThat(addresses("PO Box 4417")).containsExactly("PO Box 4417")
    }

    /** 街道后缀里有 Walk、Park、Way 这类常用词：街名的词要大写开头。 */
    @Test fun `everyday phrases with street words are not addresses`() {
        listOf(
            "5 min walk to the station",
            "2 ways to pay",
            "Free parking for 3 hours",
            "Order #4417 shipped",
            "Your total is $42.10",
        ).forEach { assertThat(addresses(it)).isEmpty() }
    }

    /** 中文地址照旧由 AddressShape 认，英文的不抢。 */
    @Test fun `chinese addresses are unchanged`() {
        assertThat(addresses("送至 祁门路33号四方新村23幢605室")).containsExactly("祁门路33号四方新村23幢605室")
    }

    // ---------- 人名：不要旁证的 ----------

    @Test fun `english field labels`() {
        assertThat(names("Ship to: Jennifer Walsh")).containsExactly("Jennifer Walsh")
        assertThat(names("Cardholder: MARIA GARCIA")).containsExactly("MARIA GARCIA")
        assertThat(names("FULL NAME Christopher Nguyen")).containsExactly("Christopher Nguyen")
        assertThat(names("Guest name: Wei Zhang")).containsExactly("Wei Zhang")
    }

    /** 没有冒号的字段名后面是界面文案的，不算。 */
    @Test fun `labels followed by ui words are not names`() {
        listOf("Customer Reviews", "Guest Wi-Fi", "Customer Service", "Username: bob").forEach {
            assertThat(names(it)).isEmpty()
        }
    }

    @Test fun `greetings honorifics and email senders`() {
        assertThat(names("Hi David,")).containsExactly("David")
        assertThat(names("Thanks for riding, Kevin")).containsExactly("Kevin")
        assertThat(names("Dear Mr. Patel")).containsExactly("Mr. Patel")
        assertThat(names("Sarah Johnson <sarah.johnson@example.com>")).containsExactly("Sarah Johnson")
    }

    @Test fun `chat and ride share lines`() {
        assertThat(names("You rode with Carlos M.")).containsExactly("Carlos M.")
        assertThat(names("~ Rob Fletcher")).containsExactly("Rob Fletcher")
        assertThat(names("Sophie Lambert joined the group")).containsExactly("Sophie Lambert")
        assertThat(names("< Emily Carter")).containsExactly("Emily Carter")
        assertThat(names("To: Rachel Kim")).containsExactly("Rachel Kim")
    }

    @Test fun `airline style surname slash given name`() {
        assertThat(names("ZHANG/WEI MR")).containsExactly("ZHANG/WEI MR")
        assertThat(names("SFO/LAX")).isEmpty()
    }

    /** 品牌长得像称谓加姓：「Dr. Martens」。 */
    @Test fun `brands are not names`() {
        assertThat(names("New Dr. Martens boots")).isEmpty()
        assertThat(guesses("Trader Joe")).isEmpty()
    }

    // ---------- 人名：要旁证的猜测 ----------

    @Test fun `listed first and last names are guesses`() {
        assertThat(guesses("Olivia Bennett")).containsExactly("Olivia Bennett")
        assertThat(guesses("Call Michael Chen tomorrow")).containsExactly("Michael Chen")
        assertThat(guesses("Liu Yang")).containsExactly("Liu Yang")
    }

    /** 两头都是普通词的、地名的，不猜。 */
    @Test fun `common words and places are not guessed`() {
        listOf("Will Power", "San Francisco", "Mark Down", "Order Details", "Kindle Books").forEach {
            assertThat(guesses(it)).isEmpty()
        }
    }
}
