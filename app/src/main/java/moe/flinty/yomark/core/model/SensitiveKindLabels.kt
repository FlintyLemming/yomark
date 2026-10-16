package moe.flinty.yomark.core.model

import android.content.res.Resources
import androidx.annotation.StringRes
import moe.flinty.yomark.R

/** 类型的显示名。宽屏的框列表、设置页共用同一套措辞。 */
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
        SensitiveKind.CHAT_AVATAR -> R.string.kind_chat_avatar
        SensitiveKind.CHAT_NAME -> R.string.kind_chat_name
        SensitiveKind.CHAT_TITLE -> R.string.kind_chat_title
        SensitiveKind.FACE -> R.string.kind_face
        SensitiveKind.BARCODE -> R.string.kind_barcode
        SensitiveKind.MANUAL -> R.string.kind_manual
    }

    fun display(res: Resources, kind: SensitiveKind): String = res.getString(nameRes(kind))

    /**
     * 一块码的类型名：模型猜的与规则命中区分开（spec §3），Gemini Nano 的结果标「AI」。
     * 点一行字打上的码说不上是哪一类，就叫「文字」。文本就写它写的字：缩略图截的是原图，看不到写上去的字。
     */
    fun display(res: Resources, item: MaskItem): String = when {
        item.text != null -> item.text
        item.source == DetectorSource.LINE -> res.getString(R.string.kind_line)
        item.source == DetectorSource.LLM -> display(res, item.kind) + " · AI"
        else -> display(res, item.kind)
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
        SensitiveKind.CHAT_AVATAR -> "头像"
        SensitiveKind.CHAT_NAME -> "昵称"
        SensitiveKind.CHAT_TITLE -> "聊天标题"
        SensitiveKind.FACE -> "人脸"
        SensitiveKind.BARCODE -> "条码"
        SensitiveKind.MANUAL -> "手动"
    }
}
