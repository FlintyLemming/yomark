package moe.flinty.yomark.debug

import android.content.Context
import moe.flinty.yomark.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 整个进程共用的那一份调试记录：每个编辑页都往这里记，设置 → 开发者 → 调试记录也看这一份。
 * 第一次用时建起来，从磁盘读回之前的记录，之后一直跟着设置走——保留几次改了就裁，调试信息关了就连文件一起清掉。
 */
fun sharedTraceLog(context: Context): TraceLog =
    shared ?: synchronized(lock) { shared ?: create(context.applicationContext).also { shared = it } }

private val lock = Any()
@Volatile private var shared: TraceLog? = null

private fun create(app: Context): TraceLog {
    val log = TraceLog(store = TraceStore(File(app.noBackupFilesDir, "debug_trace")))
    val settings = SettingsStore(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        // 先定下保留几次再读：读的时候会把超出的删掉
        log.setCapacity(settings.debugTraceCapacity.first())
        log.restore()
        log.persist()
    }
    scope.launch { settings.debugTraceCapacity.collect(log::setCapacity) }
    scope.launch { settings.debugTrace.collect { on -> if (!on) log.clear() } }
    return log
}
