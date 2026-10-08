package moe.flinty.yomark.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

/**
 * 一个只属于当前用例的 SettingsStore。
 *
 * 生产路径上的 `preferencesDataStore` 委托在整个进程里只创建一个 DataStore 实例，
 * 而 Robolectric 同一个测试类的各个用例共用类加载器——那个实例会带着上一个用例
 * 写进去的数据活下来，「默认值是 SOLID」这类断言会随执行顺序时好时坏。
 * 测试因此一律走内部构造器，各自拿一个独立文件。
 */
internal fun isolatedSettingsStore(context: Context): SettingsStore = SettingsStore(
    PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
    ) { File(context.filesDir, "settings-test-${UUID.randomUUID()}.preferences_pb") }
)
