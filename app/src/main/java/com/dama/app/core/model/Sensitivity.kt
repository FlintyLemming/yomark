package com.dama.app.core.model

enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,

    // v2：需要 NER，首版不产出。枚举位先占住，接口按 spec §14 已留。
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,

    FACE, BARCODE, MANUAL,
}

/** 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }
