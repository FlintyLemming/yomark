package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 车牌，一行一行地认（真机漏检：车的照片上的「苏B·1LE22」，OCR 读成「苏B·ILE22」，没有一条规则认）。
 *
 * 例子取自调研时查的各国规定和 PP-OCR 在合成牌子上的实测输出；反例是调研时专门找来撞各种写法的截图文字：
 * 城市接航班号、人名接工号、型号、尺码、单位、金额、标准号、工单号。整页的认法（上下两行、孤零零一行、
 * 字段名在上一行）见 LicensePlatePageTest。
 */
class LicensePlateRuleTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "plate" }

    /** 一行里认出的（只圈出的那一档是整页的认法，这里不出）。 */
    private fun found(text: String): List<String> = rule.findIn(text).map { text.substring(it.range) }

    private fun assertFound(cases: Map<String, String>) = cases.forEach { (text, plate) ->
        assertWithMessage(text).that(found(text)).containsExactly(plate)
    }

    private fun assertNothing(texts: List<String>) = texts.forEach { assertWithMessage(it).that(found(it)).isEmpty() }

    @Test fun `the plate from the car photo`() {
        assertFound(
            mapOf(
                "苏B·ILE22" to "苏B·ILE22",
                "苏B·1LE22" to "苏B·1LE22",
                "苏BILE22" to "苏BILE22",
                "苏B· ILE22" to "苏B· ILE22",
                "苏B:1LE22" to "苏B:1LE22",
            )
        )
    }

    @Test fun `mainland China, every kind of plate`() {
        assertFound(
            mapOf(
                "京A·12345" to "京A·12345",
                "粤B·D12345" to "粤B·D12345",
                "粤B·DI2345" to "粤B·DI2345",
                "京A·12345D" to "京A·12345D",
                "苏A·1234学" to "苏A·1234学",
                "京A·0001警" to "京A·0001警",
                "鲁H·1234挂" to "鲁H·1234挂",
                "粤Z·7C59港" to "粤Z·7C59港",
                "沪204·01领" to "沪204·01领",
                "224·001使" to "224·001使",
                "京·X2345应急" to "京·X2345应急",
                "京A·***45" to "京A·***45",
                "冀→翼：翼A·12345" to "翼A·12345",
            )
        )
    }

    /** 车牌在一句话里：前面是引出车牌的字、省份全称，或者随便一个字（只要简称和字母之间没有空格）。 */
    @Test fun `Chinese plates in a sentence`() {
        assertFound(
            mapOf(
                "入场车辆 粤B·D12345 停车时长 2小时" to "粤B·D12345",
                "苏A·12345 白色 大众朗逸" to "苏A·12345",
                "车牌号京A12345" to "京A12345",
                "我车牌是浙A88X88" to "浙A88X88",
                "您的车辆苏B1LE22于10月12日" to "苏B1LE22",
                "刚才苏B1LE22闯红灯被拍了" to "苏B1LE22",
                "车牌号码是湖南湘A12345" to "湘A12345",
                "上海沪A·D12345" to "沪A·D12345",
                "南京苏A·12345" to "苏A·12345",
            )
        )
    }

    @Test fun `Chinese words that end in a province character are not plates`() {
        assertNothing(
            listOf(
                "北京CA1234", "南京MU2817", "东京NH0961", "西宁CZ6789", "银川CA1234", "仁川KE0890", "首尔仁川 KE0890",
                "延吉CZ6123", "伊宁CZ6823", "通辽GS7591", "普吉FD3528", "广州白云 CZ3101", "经停 银川 CA1234",
                "阿里云ECS1234", "实例名称：云GPU001", "云 RTX4090 实例", "全新X12345", "更新V20230", "拯救者新Y9000P 2024",
                "奔驰新GLE450上市", "新EQE350+ 纯电轿车", "英伟达新RTX5090显卡", "2024秋季上新 A23456",
                "张宁 E10234 产品部", "李云 A20457 已签到", "王新 S21034 三年二班", "vivo X100 曜石黑 V2309A",
                "颜色：藏青 XXL185", "京ICP备2021012345号-1", "沪B2-20120001", "沪A 600519", "京沪G1234次",
                "领取 100.00领取", "试驾京A0001试驾", "满100 200使用",
            )
        )
    }

    /** ML Kit 中文逐字切分时，「北 京 CA1234」拼回来再看。 */
    @Test fun `character-split lines are joined before looking`() {
        assertNothing(listOf("北 京 CA1234", "南 京 MU2817", "全 新 X12345", "新 X1234 学 生 版", "验 证 码 123456 使 用 期 限"))
    }

    @Test fun `Japan, one line`() {
        assertFound(
            mapOf(
                "品川 330 さ 12-34" to "品川 330 さ 12-34",
                "足立500き21-41" to "足立500き21-41",
                "横浜300 て · 123" to "横浜300 て · 123",
                "お車は品川330さ1234です" to "品川330さ1234",
            )
        )
        assertNothing(listOf("営業時間は10-18時", "品川駅 300m", "横浜 300円", "京都 30分"))
    }

    @Test fun `distinctive formats elsewhere`() {
        assertFound(
            mapOf(
                "Toyota Prius · YF19 KXP" to "YF19 KXP",
                "AB12 CDE" to "AB12 CDE",
                "Immatriculation AB-123-CD" to "AB-123-CD",
                "Avis de contravention pour le véhicule EH-207-PB" to "EH-207-PB",
                "AB 123CD" to "AB 123CD",
                "AI 456 FG" to "AI 456 FG",
                "ABC1D23" to "ABC1D23",
                "ABC-1C34" to "ABC-1C34",
                "A123BC 77" to "A123BC 77",
                "0001py 97" to "0001py 97",
                "AA 1234 BB" to "AA 1234 BB",
                "MH 12 AB 1234" to "MH 12 AB 1234",
                "21 BH 1234 AA" to "21 BH 1234 AA",
                "SBS 9889 U" to "SBS 9889 U",
                "SBAI234G" to "SBAI234G",
                "1-ABC-123" to "1-ABC-123",
                "GB-001-B" to "GB-001-B",
                "241-D-12345" to "241-D-12345",
                "51F-123.45" to "51F-123.45",
                "Congestion Charge paid for SN22 OHM" to "SN22 OHM",
            )
        )
    }

    /** 两头是尺码、单位、机场、财年的；行里写着货号、批号的；小写的；校验字母不对的。 */
    @Test fun `codes, sizes and flights that look like plates`() {
        assertNothing(
            listOf(
                "BA15 LHR 21:45 → SIN 18:05 +1", "Flight EK29 DXB · Arrived 07:10", "FY24 EPS guidance raised", "SS25 NEW IN",
                "FW24 TEE · Black", "Sony SRS-XB23 BLU portable speaker", "Google Pixel 9 Pro XL 128GB", "Specs: 12 GB 256 GB",
                "Size chart: XS 155CM · S 160CM", "Carton: NW 250 KG · GW 280 KG", "Meet AT 1200 PM sharp",
                "DL 12 ATL 0930 BOARDING", "DL 12 FEB 2025 · Delhi office closed", "Dimensions: 60 x 120 cm 25 kg",
                "Buy a 500 mb 30-day add-on", "Kit SKU TS-BLK-12-M", "SKU KB-750-BK · Keyboard tray", "LOT 2B15 EXP 2026/10",
                "Error ERR-4E12 · Contact support", "HEAD is now at ACE1B42 Bump version", "See section 151.D.2 of the code",
                "CLS 450 S", "SBA 1234 A", "Order #AB123CD", "SW1A 1AA", "1234 AB", "GB29 NWBK 6016 1331 9268 19",
            )
        )
    }

    @Test fun `after a label, any format`() {
        assertFound(
            mapOf(
                "车牌号：京A·***45" to "京A·***45",
                "車牌號碼：AB 1234" to "AB 1234",
                "車牌號碼AB1234" to "AB1234",
                "License plate: 7ABC123" to "7ABC123",
                "License plate 7ABC123" to "7ABC123",
                "Your license plate number is 7ABC123" to "7ABC123",
                "License plate (front): 8XYZ456" to "8XYZ456",
                "Plate #: 7ABC123" to "7ABC123",
                "Plate No. AB 1234" to "AB 1234",
                "Kennzeichen: M-AB 1234" to "M-AB 1234",
                "Kennzeichen: KI–12 AB" to "KI–12 AB",
                "Immatriculation : GE 123456" to "GE 123456",
                "Kenteken: GB-001-B" to "GB-001-B",
                "Vehicle registration number: MH 12 AB 1234" to "MH 12 AB 1234",
                "Nr rej. PO 12345" to "PO 12345",
                "Licence plate: 123-45-678" to "123-45-678",
                "ナンバー：12-34" to "12-34",
                "お車のナンバーは品川330さ1234です" to "品川330さ1234",
            )
        )
    }

    /** 字段名长得像、后面不是车牌的：没写冒号的英文字段名后面要对得上一种写法。 */
    @Test fun `labels that are not about plates`() {
        assertNothing(
            listOf(
                "LICENSE PLATE RECOGNITION SYSTEM V2", "PLATE NOT FOUND 404", "PLAKAT DIN A1", "NUMBER PLATE LIGHT 12V LED",
                "LICENSE PLATE FRAME 2PCS BLACK", "Vehicle Registration 2025 Renewal Notice", "Plate No. 45 — Rosa canina",
                "PLATE NO. 1 OF 4", "Plate number: 96-well, sterile", "96孔板 Plate #12", "Matrícula 2025/26 abierta",
                "Immatriculation : 552 120 222 R.C.S. Paris", "主板 VRM 14+2相供电", "HMRC VAT · VRN 123456789",
                "シリアルナンバー C02XK1ABJG5H", "フライトナンバー NH123", "ルームナンバー 1203", "マイナンバー 1234 5678 9012",
            )
        )
    }

    @Test fun `the Singapore check letter`() {
        listOf("SBS9889U", "SBS8011S", "SBS3229P", "RD3073K").forEach { p ->
            val (prefix, number, check) = Regex("([A-Z]+)([0-9]+)([A-Z])").matchEntire(p)!!.destructured
            assertWithMessage(p).that(Plates.singaporeCheck(prefix, number, check[0])).isTrue()
        }
        assertThat(Plates.singaporeCheck("SBA", "1234", 'A')).isFalse()
        assertThat(Plates.singaporeCheck("SBA", "1234", 'G')).isTrue()
    }

    @Test fun `it is masked by default`() {
        assertThat(rule.kind).isEqualTo(SensitiveKind.LICENSE_PLATE)
        assertThat(rule.enabledByDefault).isTrue()
    }
}
