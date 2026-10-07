package com.yomark.app.billing

/**
 * 兑换码校验。**临时方案**：只有一个写死的码，兑换成功等同于买断去水印。
 *
 * 与 Play 购买分开存（PurchaseStore.isRedeemed）：Play 查询明确回答「没买」时会覆盖购买缓存，
 * 兑换得来的权益不能被它一并抹掉。
 */
object RedeemCode {
    private const val CODE = "qwer12345678"

    /** 忽略首尾空白和大小写——手输的码，别因为多敲一个空格就不认。 */
    fun matches(input: String): Boolean = input.trim().equals(CODE, ignoreCase = true)
}
