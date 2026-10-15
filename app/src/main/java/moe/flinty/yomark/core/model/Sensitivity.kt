package moe.flinty.yomark.core.model

enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,

    /** 兜底：一串长得像标识符、但不属于上面任何一类的数字。出厂关着。 */
    LONG_NUMBER,

    /** 日期与时间。出厂关着——时间在截图里无处不在，默认打码会打扰所有人。 */
    DATETIME,

    /**
     * 出生日期：字段名（「出生日期」「Date of birth」「DOB」）指明了的那个日期，或者护照机读码里的生日。
     * 和姓名、证件号凑在一起就能冒用身份，又只认有字段名、有机读码的，误报少，出厂打码。
     */
    BIRTH_DATE,

    /** 驿站、快递柜的取件码。凭它就能把包裹取走，默认打码。 */
    PICKUP_CODE,

    /** 车牌。凭它能查到车、找到人，写法独特的和字段名后面的默认打码。 */
    LICENSE_PLATE,

    // PERSON_NAME / POSTAL_ADDRESS 不是 NER 产出的：标签锚定认字段名后面的值，
    // 另外人名认电话前面的那几个字、地址认门牌的形状（NameBeforePhone / AddressShape）。
    // 自由行文里的人名地址规则认不全（AI 增强识别也报这两类），完整覆盖要等 NER（spec §14 的接口已留）。
    // ORG_NAME 至今没有产出方。
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,

    /**
     * 证件号码：身份证、通行证、驾驶证、社保卡上的号码，港台、外国的身份证号，通行证上的机读码。
     * 有校验位、形状够特别、页上写着是哪儿的证件或者字段名指明了的才认，误报少，出厂打码。
     * 护照号和护照、身份证的机读码另算一类（PASSPORT）。
     */
    ID_NUMBER,

    /** 车架号（车辆识别代号，VIN）和发动机号。车架号有校验位，出厂打码。 */
    VIN,

    /**
     * 聊天截图里发言者的头像、昵称，和顶栏的聊天标题（对方名字或群名）。版面（chat/ChatLayout）先判断这一页是不是聊天记录，是才认；
     * AI 增强识别（GeminiNanoClassifier）也从文字里报昵称和聊天标题，不看是不是聊天页。
     * 三类共用设置 → 实验室里「聊天记录」一个开关，出厂关着；关着时版面和 AI 都不找。
     */
    CHAT_AVATAR, CHAT_NAME, CHAT_TITLE,

    FACE, BARCODE, MANUAL,
}

/**
 * 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。
 * 最后两个不是识别出来的，是用户自己加的：MANUAL 是拉的框、手画的线，LINE 是点一下没打码的字打上的码，
 * 框是文字识别给的那一段（TextLine.segments 里的一个）。
 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL, LINE }
