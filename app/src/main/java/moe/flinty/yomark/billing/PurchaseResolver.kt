package moe.flinty.yomark.billing

sealed interface QueryOutcome {
    /**
     * 查询成功，Play 明确回答了「有没有」。
     * [fresh]：有，而且本应用还没确认（acknowledge）过它——刚付的钱，不是重装后恢复的老购买。
     */
    data class Ok(val owned: Boolean, val fresh: Boolean = false) : QueryOutcome
    /** 查不到：无网络、服务未连上、Play 服务不可用。 */
    data object Unavailable : QueryOutcome
}

/**
 * 「缓存 + 查询结果 → 是否已购」的全部规则，抽成纯函数以便测试。
 *
 * 查得到就以查询结果为准（覆盖退款/撤单）；查不到就沿用缓存（覆盖飞行模式）。
 */
object PurchaseResolver {
    fun resolve(cached: Boolean, outcome: QueryOutcome): Boolean = when (outcome) {
        is QueryOutcome.Ok -> outcome.owned
        QueryOutcome.Unavailable -> cached
    }
}
