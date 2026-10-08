package moe.flinty.yomark.data

import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.theme.ThemeColor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun store() = isolatedSettingsStore(context)

    @Test
    fun `default style is solid`() = runTest {
        assertThat(store().lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `style survives a write and read`() = runTest {
        val store = store()
        store.setLastStyle(MaskStyle.PIXELATE)
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun `an unknown stored style falls back to solid rather than crashing`() = runTest {
        val store = store()
        store.writeRawStyleForTest("NOT_A_STYLE")
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `mask options default to the factory look`() = runTest {
        assertThat(store().maskOptions.first()).isEqualTo(MaskOptions())
    }

    @Test
    fun `mask options survive a write and read`() = runTest {
        val store = store()
        val tuned = MaskOptions(
            solidColor = 0xFF000000.toInt(),
            pixelBlockDivisor = 4,
            blurRadiusRatio = 0.2f,
            markerColor = MaskOptions.withAlpha(0xFF448AFF.toInt(), 0.4f),
            emoji = "🐱",
            emojiBackground = 0xFFFFE082.toInt(),
            emojiTiled = true,
        )
        store.setMaskOptions(tuned)
        assertThat(store.maskOptions.first()).isEqualTo(tuned)
    }

    /** 手改过的、以后改了范围的旧值读回来收进范围：色块不会半透明，马克笔不会不透明，马赛克不比出厂细。 */
    @Test
    fun `out of range stored mask options are pulled back into range`() = runTest {
        val store = store()
        store.writeRawMaskOptionsForTest(markerColor = 0xFFFFEB3B.toInt(), pixelDivisor = 64, solidColor = 0x10000000)
        val read = store.maskOptions.first()
        assertThat(read.markerAlpha).isWithin(0.01f).of(MaskOptions.MARKER_ALPHA_RANGE.endInclusive)
        assertThat(read.pixelBlockDivisor).isEqualTo(MaskOptions.FINEST_PIXEL_DIVISOR)
        assertThat(read.solidColor).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `resetting settings leaves the editor's brush alone`() = runTest {
        val store = store()
        store.setLastStyle(MaskStyle.EMOJI)
        store.setMaskOptions(MaskOptions(emoji = "🐱"))
        store.resetSettings()
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.EMOJI)
        assertThat(store.maskOptions.first().emoji).isEqualTo("🐱")
    }

    @Test
    fun `purpose watermark style defaults to the original look`() = runTest {
        assertThat(store().purposeWatermarkStyle.first()).isEqualTo(PurposeWatermarkStyle())
    }

    @Test
    fun `purpose watermark style survives a write and read`() = runTest {
        val store = store()
        val style = PurposeWatermarkStyle(
            color = PurposeWatermarkStyle.PALETTE[3], angle = 15f, opacity = 0.4f, density = 1.6f,
        )
        store.setPurposeWatermarkStyle(style)
        assertThat(store.purposeWatermarkStyle.first()).isEqualTo(style)
    }

    @Test
    fun `an out of range stored opacity is pulled back so the watermark never turns opaque`() = runTest {
        val store = store()
        store.writeRawPurposeOpacityForTest(1f)
        assertThat(store.purposeWatermarkStyle.first().opacity)
            .isEqualTo(PurposeWatermarkStyle.OPACITY_RANGE.endInclusive)
    }

    @Test
    fun `onboarding is unseen by default and sticks once marked`() = runTest {
        val store = store()
        assertThat(store.onboardingSeen.first()).isFalse()
        store.markOnboardingSeen()
        assertThat(store.onboardingSeen.first()).isTrue()
    }

    @Test
    fun `scan effect defaults to the sweep and remembers the choice`() = runTest {
        val store = store()
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.SWEEP)
        store.setScanStyle(ScanStyle.FROST)
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.FROST)
    }

    @Test
    fun `an unknown stored scan effect falls back to the sweep rather than crashing`() = runTest {
        val store = store()
        store.writeRawScanStyleForTest("NOT_AN_EFFECT")
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.SWEEP)
    }

    @Test
    fun `export reminder is on by default and remembers being turned off`() = runTest {
        val store = store()
        assertThat(store.pendingExportReminder.first()).isTrue()
        store.setPendingExportReminder(false)
        assertThat(store.pendingExportReminder.first()).isFalse()
    }

    @Test
    fun `theme color follows the system by default and remembers the choice`() = runTest {
        val store = store()
        assertThat(store.themeColor.first()).isEqualTo(ThemeColor.SYSTEM)
        store.setThemeColor(ThemeColor.GREEN)
        assertThat(store.themeColor.first()).isEqualTo(ThemeColor.GREEN)
    }

    @Test
    fun `an unknown stored theme color falls back to following the system rather than crashing`() = runTest {
        val store = store()
        store.writeRawThemeColorForTest("NOT_A_COLOR")
        assertThat(store.themeColor.first()).isEqualTo(ThemeColor.SYSTEM)
    }
}
