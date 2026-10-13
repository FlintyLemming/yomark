package moe.flinty.yomark.core.model

import android.content.res.Resources
import androidx.annotation.StringRes
import moe.flinty.yomark.R

/** 类型的显示名。画布小标签、宽屏的框列表与导出拦截对话框共用同一套措辞。 */
object SensitiveKindLabels {

    @StringRes
    fun nameRes(kind: SensitiveKind): Int = when (kind) {
        SensitiveKind.PHONE -> R.string.kind_phone
        SensitiveKind.EMAIL -> R.string.kind_email
        SensitiveKind.PAYMENT_CARD -> R.string.kind_card
        SensitiveKind.IBAN -> R.string.kind_iban
        SensitiveKind.SSN -> R.string.kind_ssn
        SensitiveKind.PASSPORT -> R.string.kind_passport
        SensitiveKind.TRACKING_NO -> R.string.kind_tracking
        SensitiveKind.IP_ADDR -> R.string.kind_ip
        SensitiveKind.MAC_ADDR -> R.string.kind_mac
        SensitiveKind.URL -> R.string.kind_url
        SensitiveKind.API_KEY -> R.string.kind_api_key
        SensitiveKind.LONG_NUMBER -> R.string.kind_long_number
        SensitiveKind.DATETIME -> R.string.kind_datetime
        SensitiveKind.BIRTH_DATE -> R.string.kind_birth_date
        SensitiveKind.PICKUP_CODE -> R.string.kind_pickup
        SensitiveKind.LICENSE_PLATE -> R.string.kind_plate
        SensitiveKind.POSTAL_ADDRESS -> R.string.kind_address
        SensitiveKind.PERSON_NAME -> R.string.kind_name
        SensitiveKind.ORG_NAME -> R.string.kind_org
        SensitiveKind.ID_NUMBER -> R.string.kind_id_number
        SensitiveKind.VIN -> R.string.kind_vin
        SensitiveKind.FACE -> R.string.kind_face
        SensitiveKind.BARCODE -> R.string.kind_barcode
        SensitiveKind.MANUAL -> R.string.kind_manual
    }

    fun display(res: Resources, kind: SensitiveKind): String = res.getString(nameRes(kind))

    /** 一块码的类型名：模型猜的与规则命中区分开（spec §3），Gemini Nano 的结果标「AI」。 */
    fun display(res: Resources, item: MaskItem): String =
        display(res, item.kind) + if (item.source == DetectorSource.LLM) " · AI" else ""

    /**
     * 「2 个网址」「1 个 IP 地址」：中文里名字以字母开头的补一个空格（kind_count_latin），纯中文名不补。
     * spec §7.4 的例子就是这么排的，拦截对话框逐字用它。
     */
    fun plural(res: Resources, kind: SensitiveKind, count: Int): String {
        val name = display(res, kind)
        val format = if (name.first().code < CJK_START) R.string.kind_count_latin else R.string.kind_count
        return res.getString(format, count, name)
    }

    /** 调试记录里的类型名，固定中文、不随系统语言变：记录面向排查，不面向用户（见 debug/Trace.kt）。 */
    fun debugName(kind: SensitiveKind): String = when (kind) {
        SensitiveKind.PHONE -> "电话"
        SensitiveKind.EMAIL -> "邮箱"
        SensitiveKind.PAYMENT_CARD -> "银行卡号"
        SensitiveKind.IBAN -> "IBAN"
        SensitiveKind.SSN -> "社保号"
        SensitiveKind.PASSPORT -> "护照"
        SensitiveKind.TRACKING_NO -> "快递单号"
        SensitiveKind.IP_ADDR -> "IP 地址"
        SensitiveKind.MAC_ADDR -> "MAC 地址"
        SensitiveKind.URL -> "网址"
        SensitiveKind.API_KEY -> "密钥"
        SensitiveKind.LONG_NUMBER -> "长数字串"
        SensitiveKind.DATETIME -> "日期时间"
        SensitiveKind.BIRTH_DATE -> "出生日期"
        SensitiveKind.PICKUP_CODE -> "取件码"
        SensitiveKind.LICENSE_PLATE -> "车牌"
        SensitiveKind.POSTAL_ADDRESS -> "地址"
        SensitiveKind.PERSON_NAME -> "人名"
        SensitiveKind.ORG_NAME -> "机构名"
        SensitiveKind.ID_NUMBER -> "证件号码"
        SensitiveKind.VIN -> "车架号"
        SensitiveKind.FACE -> "人脸"
        SensitiveKind.BARCODE -> "条码"
        SensitiveKind.MANUAL -> "手动"
    }

    /** CJK 部首补充区的起点。低于它的都按拉丁字符处理。 */
    private const val CJK_START = 0x2E80
}
