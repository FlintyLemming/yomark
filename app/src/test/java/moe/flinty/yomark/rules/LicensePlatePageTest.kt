package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 车牌的整页认法：上下两行的牌子（TwoRowPlate）、字段名在上一行的（StackedField）。
 *
 * 坐标照抄自 PlatePhotoOcrTest 那张合成照片的真实 OCR（PP-OCRv6 small，900×1390）：两行的框上下咬住一截，
 * 上一行窄、居中；日本牌的假名在探针的另一张图上自成一行，排在数字后面。
 */
class LicensePlatePageTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "plate" }

    /** 认出的每一处：（盖住的每一段字, 只圈出吗）。 */
    private fun found(page: Page): List<Pair<List<String>, Boolean>> =
        rule.findOnPage(page).map { m -> m.spans.map { page.texts[it.line].substring(it.range) } to m.match.outlineOnly }

    @Test fun `the two rows of a yellow plate are one plate`() {
        val p = page(Row(457f, 47f, Segment("苏·E", 252f, 343f)), Row(495f, 82f, Segment("05863", 204f, 415f)))
        assertThat(found(p)).containsExactly(listOf("苏·E", "05863") to false)
    }

    @Test fun `a Japanese plate, also when the kana is read as a line of its own`() {
        val whole = page(Row(678f, 37f, Segment("品川 330", 515f, 624f)), Row(709f, 78f, Segment("さ 12-34", 478f, 689f)))
        assertThat(found(whole)).containsExactly(listOf("品川 330", "さ 12-34") to false)

        val apart = page(
            Row(678f, 37f, Segment("品川 330", 515f, 624f)),
            Row(709f, 78f, Segment("12-34", 530f, 689f)),
            Row(735f, 30f, Segment("さ", 482f, 508f)),
        )
        assertThat(found(apart)).containsExactly(listOf("品川 330", "さ", "12-34") to false)
    }

    /** 越南的方牌：下一行「123.45」像个价钱，拼起来也只圈出。 */
    @Test fun `a Vietnamese square plate is only outlined`() {
        val p = page(Row(100f, 40f, Segment("51F", 160f, 240f)), Row(136f, 60f, Segment("123.45", 120f, 290f)))
        assertThat(found(p)).containsExactly(listOf("51F", "123.45") to true)
    }

    /** 不是挨着的两行不拼：中间空着两个字高，或者下一行在别的栏里；表格里「PIN」下面的「1234」也不是牌子。 */
    @Test fun `rows that are apart or do not look like a plate stay apart`() {
        assertThat(found(page(Row(100f, 30f, Segment("苏B", 200f, 260f)), Row(200f, 40f, Segment("12345", 180f, 300f))))).isEmpty()
        assertThat(found(page(Row(100f, 30f, Segment("苏B", 200f, 260f)), Row(132f, 40f, Segment("12345", 500f, 620f))))).isEmpty()
        assertThat(found(page(Row(100f, 20f, Segment("PIN", 100f, 140f)), Row(124f, 24f, Segment("1234", 100f, 160f))))).isEmpty()
    }

    /** 表单上字段名单独一行、车牌在下一行：哪国的写法都打码，只圈出的那一档也升成打码。 */
    @Test fun `a plate below a label line is masked`() {
        val us = page(Row(100f, 20f, Segment("License plate", 40f, 196f)), Row(128f, 24f, Segment("7ABC123", 40f, 140f)))
        assertThat(found(us)).containsExactly(listOf("7ABC123") to false)

        val de = page(Row(100f, 20f, Segment("Kennzeichen", 40f, 172f)), Row(128f, 24f, Segment("M AB 1234", 40f, 170f)))
        assertThat(found(de)).containsExactly(listOf("M AB 1234") to false)
    }

    /**
     * 照片转过来读，欧盟蓝条的国别字母粘在了号码上：法国的「PAB-123-CD」去掉它照样打码；它是一块牌子，不是「附近的字」，
     * 下面那块德国的牌子（「BB MW 1234」，D 读成了 B）照样圈出。以前两块都丢了。
     */
    @Test fun `a band letter glued onto the plate`() {
        val p = page(Row(100f, 64f, Segment("PAB-123-CD", 480f, 741f)), Row(250f, 66f, Segment("BB MW 1234", 204f, 468f)))
        assertThat(found(p)).containsExactly(listOf("AB-123-CD") to false, listOf("BB MW 1234") to true)
    }
}
