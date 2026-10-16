package moe.flinty.yomark.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevicePerformanceTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun `a declared performance class decides on its own`() {
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 31, totalMem = 4 * gib)).isTrue()
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 34, totalMem = 4 * gib)).isTrue()
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 30, totalMem = 12 * gib)).isFalse()
    }

    /** 没声明的按内存：8 GB 档的机器 totalMem 报 7 GiB 出头，6 GB 档的 5.5 上下。 */
    @Test
    fun `without a performance class memory decides`() {
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 0, totalMem = 7 * gib)).isTrue()
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 0, totalMem = 11 * gib)).isTrue()
        assertThat(DevicePerformance.fast(lowRam = false, mediaPerformanceClass = 0, totalMem = 5_600L * 1024 * 1024)).isFalse()
    }

    @Test
    fun `a low ram device is never fast`() {
        assertThat(DevicePerformance.fast(lowRam = true, mediaPerformanceClass = 33, totalMem = 12 * gib)).isFalse()
    }
}
