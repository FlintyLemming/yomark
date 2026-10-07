package com.yomark.app.core.model

enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,

    /** 兜底：一串长得像标识符、但不属于上面任何一类的数字。默认仅圈出。 */
    LONG_NUMBER,

    /** 日期与时间。出厂仅圈出——时间在截图里无处不在，默认打码会打扰所有人。 */
    DATETIME,

    /** 驿站、快递柜的取件码。凭它就能把包裹取走，默认打码。 */
    PICKUP_CODE,

    // PERSON_NAME / POSTAL_ADDRESS 不是 NER 产出的：标签锚定认字段名后面的值，
    // 另外人名认电话前面的那几个字、地址认门牌的形状（NameBeforePhone / AddressShape）。
    // 自由行文里的人名地址仍然认不出，完整覆盖要等 NER（spec §14 的接口已留）。
    // ORG_NAME 至今没有产出方。
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,

    FACE, BARCODE, MANUAL,
}

/** 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }
