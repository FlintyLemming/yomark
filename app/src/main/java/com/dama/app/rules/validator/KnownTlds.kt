package com.dama.app.rules.validator

/**
 * 邮箱与 URL 的域名后缀白名单（spec §6：EMAIL 的校验是「域名必须有已知 TLD」）。
 *
 * 不追求完整——完整的 TLD 表要 1500 多条，而误报一个 `foo@bar.baz` 只是麻烦。
 * 所有两字母后缀一律视为国家码，这覆盖了绝大多数长尾。
 */
object KnownTlds {

    fun isKnown(tld: String): Boolean {
        val t = tld.lowercase()
        if (t.length == 2 && t.all { it in 'a'..'z' }) return true    // 任意 ccTLD
        return COMMON.contains(t)
    }

    private val COMMON = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name", "pro",
        "app", "dev", "io", "ai", "co", "me", "tv", "cc", "xyz", "site", "online", "store",
        "tech", "cloud", "email", "blog", "shop", "live", "news", "media", "digital",
        "agency", "studio", "design", "software", "systems", "network", "solutions",
        "company", "group", "team", "world", "life", "today", "space", "website", "page",
        "link", "click", "fun", "wiki", "top", "vip", "one", "art", "inc", "ltd", "llc",
    )
}
