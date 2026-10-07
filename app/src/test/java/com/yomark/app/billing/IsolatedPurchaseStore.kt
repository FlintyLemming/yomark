package com.yomark.app.billing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

/**
 * 一个只属于当前用例的 PurchaseStore。理由与 data.isolatedSettingsStore 一样：
 * preferencesDataStore 委托在进程内是单例，Robolectric 同类用例共用类加载器，
 * 「全新安装不是 pro」这类断言否则会随执行顺序时好时坏。
 */
internal fun isolatedPurchaseStore(context: Context): PurchaseStore = PurchaseStore(
    PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
    ) { File(context.filesDir, "purchase-test-${UUID.randomUUID()}.preferences_pb") }
)
