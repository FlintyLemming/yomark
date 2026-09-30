package com.youma.app.core.model

/** 类型的显示名。画布小标签与导出拦截对话框共用同一套措辞。 */
object SensitiveKindLabels {

    fun display(kind: SensitiveKind): String = when (kind) {
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
        SensitiveKind.PICKUP_CODE -> "取件码"
        SensitiveKind.POSTAL_ADDRESS -> "地址"
        SensitiveKind.PERSON_NAME -> "人名"
        SensitiveKind.ORG_NAME -> "机构名"
        SensitiveKind.FACE -> "人脸"
        SensitiveKind.BARCODE -> "条码"
        SensitiveKind.MANUAL -> "手动"
    }

    /**
     * 「2 个网址」「1 个 IP 地址」——中文与拉丁字母之间补一个空格，纯中文名不补。
     * spec §7.4 的例子就是这么排的，拦截对话框逐字用它。
     */
    fun plural(kind: SensitiveKind, count: Int): String {
        val name = display(kind)
        val gap = if (name.first().code < CJK_START) " " else ""
        return "$count 个$gap$name"
    }

    /** CJK 部首补充区的起点。低于它的都按拉丁字符处理。 */
    private const val CJK_START = 0x2E80
}
