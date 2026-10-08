package moe.flinty.yomark.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PurchaseResolverTest {

    @Test fun `a successful query saying owned makes the user pro`() {
        assertThat(PurchaseResolver.resolve(cached = false, outcome = QueryOutcome.Ok(owned = true))).isTrue()
    }

    @Test fun `a successful query saying not owned revokes a stale cache`() {
        // 退款 / 撤单：联网查得到就以查询结果为准
        assertThat(PurchaseResolver.resolve(cached = true, outcome = QueryOutcome.Ok(owned = false))).isFalse()
    }

    @Test fun `an unavailable query keeps the cached pro state`() {
        // 飞行模式下已购用户不能突然长出水印
        assertThat(PurchaseResolver.resolve(cached = true, outcome = QueryOutcome.Unavailable)).isTrue()
    }

    @Test fun `an unavailable query does not grant pro to a free user`() {
        assertThat(PurchaseResolver.resolve(cached = false, outcome = QueryOutcome.Unavailable)).isFalse()
    }
}
