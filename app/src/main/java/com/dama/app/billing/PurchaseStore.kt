package com.dama.app.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.billingDataStore: DataStore<Preferences> by preferencesDataStore(name = "purchase")

/**
 * 购买态的本地缓存（spec §10）。
 *
 * queryPurchasesAsync 首次校验需要网络；结果缓存在这里，此后飞行模式下也认。
 * 已购用户在无网环境导出不会突然出现水印——这对一个主打离线的 app 是必须的。
 *
 * **缓存对 root 设备是可篡改的，这一点是接受的。** 它保护的是一个水印，不是一道安全边界；
 * 为它引入服务端校验会同时破坏零网络与无账号两个前提。不要「顺手加个校验接口」。
 */
class PurchaseStore internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.billingDataStore)

    val isPro: Flow<Boolean> = store.data.map { it[KEY_PRO] ?: false }

    suspend fun setPro(value: Boolean) {
        store.edit { it[KEY_PRO] = value }
    }

    private companion object { val KEY_PRO = booleanPreferencesKey("pro") }
}
