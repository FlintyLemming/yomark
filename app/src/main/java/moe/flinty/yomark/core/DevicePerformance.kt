package moe.flinty.yomark.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 这台机器快不快，只用来定「按原分辨率识别」的出厂值（见 SettingsStore.fullResolutionOcr）。
 *
 * 先看系统声明的性能等级 Build.VERSION.MEDIA_PERFORMANCE_CLASS（Android 12 起，过了 CDD 性能等级认证的机型才有，
 * 等级 12 起要求 8 GB 内存级别的处理器和内存）。没声明的（大多数国产机、老机器）按内存估：
 * 低内存设备一律算慢；物理内存 8 GB 档的（系统报出来 7 GB 出头）算快。
 *
 * Jetpack 的 core-performance-play-services 能给没声明的机型补一个等级，但要 Play 服务，开源版和国行机都没有，不用。
 */
object DevicePerformance {

    fun fast(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val mpc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        return fast(am.isLowRamDevice, mpc, memory.totalMem)
    }

    internal fun fast(lowRam: Boolean, mediaPerformanceClass: Int, totalMem: Long): Boolean = when {
        lowRam -> false
        mediaPerformanceClass != 0 -> mediaPerformanceClass >= Build.VERSION_CODES.S
        else -> totalMem >= FAST_MIN_MEMORY
    }

    /** 8 GB 的机器扣掉内核、显存保留，totalMem 报 7.0–7.6 GiB；6 GB 的报 5.5 上下。取中间。 */
    internal const val FAST_MIN_MEMORY = 6_500L * 1024 * 1024
}
