package com.youma.app.core.model

enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,

    /** 兜底：一串长得像标识符、但不属于上面任何一类的数字。默认仅圈出。 */
    LONG_NUMBER,

    /** 日期与时间。出厂仅圈出——时间在截图里无处不在，默认打码会打扰所有人。 */
    DATETIME,

    // PERSON_NAME / POSTAL_ADDRESS 由 LabeledFieldRule 以**标签锚定**产出，不是 NER：
    // 认得出「收货人」「收货地址」这类字段名后面的值，认不出自由行文里的人名地址。
    // 完整覆盖仍然要等 NER（spec §14 的接口已留）。ORG_NAME 至今没有产出方。
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,

    FACE, BARCODE, MANUAL,
}

/** 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }
