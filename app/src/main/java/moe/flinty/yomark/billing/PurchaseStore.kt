package moe.flinty.yomark.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.billingDataStore: DataStore<Preferences> by preferencesDataStore(name = "purchase")

/**
 * 购买态的本地缓存（spec §10）。
 *
 * queryPurchasesAsync 首次校验需要网络；结果缓存在这里，此后飞行模式下也认。
 * 已购用户在无网环境导出不会突然出现水印——这对一个主打离线的 app 是必须的。
 *
 * 开源版没有购买，点过「去除水印」记在另一个键里（[freeUnlocked]），不和购买态混用。
 *
 * **缓存对 root 设备是可篡改的，这一点是接受的。** 它保护的是一个水印，不是一道安全边界；
 * 为它引入服务端校验会同时破坏零网络与无账号两个前提。不要「顺手加个校验接口」。
 */
class PurchaseStore internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.billingDataStore)

    val isPro: Flow<Boolean> = store.data.map { it[KEY_PRO] ?: false }.distinctUntilChanged()

    /**
     * 开源版里点过「去除水印」。和 [isPro] 分开记：以后换回内购版，不会凭开源版的状态白送一份已购。
     */
    val freeUnlocked: Flow<Boolean> = store.data.map { it[KEY_FREE_UNLOCKED] ?: false }.distinctUntilChanged()

    /** 能不能去掉品牌水印：内购版看买没买，开源版看点没点过「去除水印」。 */
    fun unlocked(freeEdition: Boolean): Flow<Boolean> = if (freeEdition) freeUnlocked else isPro

    suspend fun unlockFree() {
        store.edit { it[KEY_FREE_UNLOCKED] = true }
    }

    /** 刚买成、还没放过感谢页（见 ui.thanks.ThanksActivity）。 */
    val thanksPending: Flow<Boolean> = store.data.map { it[KEY_THANKS] ?: false }.distinctUntilChanged()

    suspend fun setPro(value: Boolean) {
        store.edit {
            it[KEY_PRO] = value
            // 退款 / 撤单：还没来得及看的感谢页也不放了
            if (!value) it.remove(KEY_THANKS)
        }
    }

    /**
     * 点亮已购。[fresh]：这是一笔刚付的钱，不是重装后恢复的老购买。从未购变成已购的那一下记一笔待谢。
     *
     * 判断和写入在同一次 edit 里：首页和编辑器各有一个 BillingClient，同一笔购买两边可能都收到，
     * 感谢页也只放一次。
     */
    suspend fun grant(fresh: Boolean) {
        store.edit {
            if (fresh && it[KEY_PRO] != true) it[KEY_THANKS] = true
            it[KEY_PRO] = true
        }
    }

    suspend fun clearThanks() {
        store.edit { it.remove(KEY_THANKS) }
    }

    private companion object {
        val KEY_PRO = booleanPreferencesKey("pro")
        val KEY_THANKS = booleanPreferencesKey("thanks_pending")
        val KEY_FREE_UNLOCKED = booleanPreferencesKey("free_unlocked")
    }
}
