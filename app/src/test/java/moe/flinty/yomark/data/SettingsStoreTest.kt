package moe.flinty.yomark.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.theme.ThemeColor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun store() = isolatedSettingsStore(context)

    /** 10-12 之前动过画笔的安装里存着最弱一档的马赛克、模糊：换了键，读回来是新的出厂值（中间一档）。 */
    @Test
    fun `mosaic and blur saved under the old keys fall back to the middle step`() = runTest {
        val data = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
        ) { File(context.filesDir, "settings-test-${UUID.randomUUID()}.preferences_pb") }
        data.edit {
            it[intPreferencesKey("mask_pixel_divisor")] = MaskOptions.FINEST_PIXEL_DIVISOR
            it[floatPreferencesKey("mask_blur_ratio")] = MaskOptions.WEAKEST_BLUR_RATIO
        }
        val options = SettingsStore(data).maskOptions.first()
        assertThat(options.pixelBlockDivisor).isEqualTo(MaskOptions.DEFAULT_PIXEL_DIVISOR)
        assertThat(options.blurRadiusRatio).isEqualTo(MaskOptions.DEFAULT_BLUR_RATIO)
    }

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
            solidNear = true,
            pixelBlockDivisor = 4,
            blurRadiusRatio = 0.2f,
            markerColor = MaskOptions.withAlpha(0xFF448AFF.toInt(), 0.4f),
        )
        store.setMaskOptions(tuned)
        assertThat(store.maskOptions.first()).isEqualTo(tuned)
    }

    /** 抹除并进了色块：旧安装里上次用的是抹除，读回来是色块开着近似色，抹除降级时用的颜色还记着。 */
    @Test
    fun `a brush left on the old erase style comes back as near colour`() = runTest {
        val store = store()
        store.writeRawStyleForTest("ERASE")
        store.writeRawMaskOptionsForTest(markerColor = MaskOptions.DEFAULT_MARKER_COLOR, pixelDivisor = 8, solidColor = 0xFF000000.toInt())
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
        assertThat(store.maskOptions.first().solidNear).isTrue()
        assertThat(store.maskOptions.first().solidColor).isEqualTo(0xFF000000.toInt())
    }

    /**
     * 记的是「打码」里上次选的样式。之前的版本记的是画笔停在哪一种，停在马克笔、画笔上的读回来是色块：
     * 打码的样式只从「打码」里来，马克笔、画笔调好的参数照旧在。
     */
    @Test
    fun `a brush left on the marker or the pen comes back as solid with their params kept`() = runTest {
        val store = store()
        val tuned = MaskOptions(penColor = 0xFF1E88E5.toInt(), markerColor = MaskOptions.withAlpha(0xFF448AFF.toInt(), 0.4f))
        store.setMaskOptions(tuned)
        for (style in listOf(MaskStyle.MARKER, MaskStyle.PEN)) {
            store.writeRawStyleForTest(style.name)
            assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
        }
        assertThat(store.maskOptions.first()).isEqualTo(tuned)
    }

    /** 表情去掉了：旧安装里上次用的是表情，读回来是色块。 */
    @Test
    fun `a brush left on the removed emoji style comes back as solid`() = runTest {
        val store = store()
        store.writeRawStyleForTest("EMOJI")
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
        assertThat(store.maskOptions.first().solidNear).isFalse()
    }

    /** 画笔动过一次之后近似色就记下了：关掉的不会因为旧的样式名又被打开。 */
    @Test
    fun `once written, near colour is read as written`() = runTest {
        val store = store()
        store.writeRawStyleForTest("ERASE")
        store.setMaskOptions(MaskOptions(solidNear = false))
        assertThat(store.maskOptions.first().solidNear).isFalse()
    }

    /** 手改过的、以后改了范围的旧值读回来收进范围：色块不会半透明，马克笔不会不透明，马赛克不比最细一档细。 */
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
        store.setLastStyle(MaskStyle.BLUR)
        store.setMaskOptions(MaskOptions(pixelBlockDivisor = 4))
        store.resetSettings()
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.BLUR)
        assertThat(store.maskOptions.first().pixelBlockDivisor).isEqualTo(4)
    }

    /** 没选过按机器定，选过就记住；恢复默认设置后又按机器定。 */
    @Test
    fun `full resolution follows the device until chosen`() = runTest {
        assertThat(isolatedSettingsStore(context, fastDevice = { true }).fullResolutionOcr.first()).isTrue()
        val slow = isolatedSettingsStore(context, fastDevice = { false })
        assertThat(slow.fullResolutionOcr.first()).isFalse()
        slow.setFullResolutionOcr(true)
        assertThat(slow.fullResolutionOcr.first()).isTrue()
        slow.resetSettings()
        assertThat(slow.fullResolutionOcr.first()).isFalse()
    }

    @Test
    fun `debug info is off until turned on`() = runTest {
        val store = store()
        assertThat(store.debugTrace.first()).isFalse()
        store.setDebugTrace(true)
        assertThat(store.debugTrace.first()).isTrue()
    }

    /** 「恢复默认设置」不管它：它在「关于」里自己关。 */
    @Test
    fun `reset leaves debug info alone`() = runTest {
        val store = store()
        store.setDebugTrace(true)
        store.resetSettings()
        assertThat(store.debugTrace.first()).isTrue()
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
    fun `scan effect defaults to the frost and remembers the choice`() = runTest {
        val store = store()
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.FROST)
        store.setScanStyle(ScanStyle.SWEEP)
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.SWEEP)
    }

    @Test
    fun `an unknown stored scan effect falls back to the frost rather than crashing`() = runTest {
        val store = store()
        store.writeRawScanStyleForTest("NOT_AN_EFFECT")
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.FROST)
    }

    @Test
    fun `brand watermark defaults to the chinese style on a chinese system`() = runTest {
        assertThat(isolatedSettingsStore(context) { Locale.SIMPLIFIED_CHINESE }.brandWatermarkStyle.first())
            .isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
        assertThat(isolatedSettingsStore(context) { Locale.TRADITIONAL_CHINESE }.brandWatermarkStyle.first())
            .isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
    }

    @Test
    fun `brand watermark defaults to the style without chinese on any other system`() = runTest {
        assertThat(isolatedSettingsStore(context) { Locale.US }.brandWatermarkStyle.first())
            .isEqualTo(BrandWatermarkStyle.ICON_EN)
        assertThat(isolatedSettingsStore(context) { Locale.JAPANESE }.brandWatermarkStyle.first())
            .isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    /** 没选过的人换了系统语言就跟着换；选过一次就不再跟。 */
    @Test
    fun `brand watermark follows the system language until picked`() = runTest {
        var locale = Locale.SIMPLIFIED_CHINESE
        val store = isolatedSettingsStore(context) { locale }
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
        locale = Locale.US
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_EN)

        store.setBrandWatermarkStyle(BrandWatermarkStyle.ICON_ZH_EN)
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
    }

    @Test
    fun `an unknown stored brand watermark falls back to the system language default`() = runTest {
        val store = isolatedSettingsStore(context) { Locale.US }
        store.writeRawBrandWatermarkForTest("NOT_A_STYLE")
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    @Test
    fun `reset puts the brand watermark back on the system language default`() = runTest {
        val store = isolatedSettingsStore(context) { Locale.US }
        store.setBrandWatermarkStyle(BrandWatermarkStyle.ICON_ZH_EN)
        store.resetSettings()
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    @Test
    fun `no watermark is unset until picked, and picking a style while locked leaves it unset`() = runTest {
        val store = store()
        assertThat(store.brandWatermarkOff.first()).isNull()
        // 没解锁时选字样不能写下「要水印」，否则解锁那一下水印去不掉
        store.setBrandWatermarkStyle(BrandWatermarkStyle.ICON_EN, unlocked = false)
        assertThat(store.brandWatermarkOff.first()).isNull()
    }

    @Test
    fun `picking a style once unlocked means keeping the watermark`() = runTest {
        val store = store()
        store.setBrandWatermarkOff()
        assertThat(store.brandWatermarkOff.first()).isTrue()
        store.setBrandWatermarkStyle(BrandWatermarkStyle.ICON_EN, unlocked = true)
        assertThat(store.brandWatermarkOff.first()).isFalse()
        assertThat(store.brandWatermarkStyle.first()).isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    @Test
    fun `reset forgets the no watermark choice`() = runTest {
        val store = store()
        store.setBrandWatermarkStyle(BrandWatermarkStyle.ICON_EN, unlocked = true)
        store.resetSettings()
        assertThat(store.brandWatermarkOff.first()).isNull()
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

    /** 文本的颜色、字号、底色和别的样式一样记着，下次打开编辑器还是它。 */
    @Test
    fun `text options are remembered`() = runTest {
        val store = store()
        val tuned = MaskOptions(textColor = 0xFF1E88E5.toInt(), textSize = 0.09f, textBackground = true)
        store.setMaskOptions(tuned)
        assertThat(store.maskOptions.first()).isEqualTo(tuned)
    }

    /** 画笔停在箭头上：下次打开还是箭头。 */
    @Test
    fun `a pen left on arrow comes back as arrow`() = runTest {
        val store = store()
        store.setMaskOptions(MaskOptions(penShape = StrokeShape.ARROW))
        assertThat(store.maskOptions.first().penShape).isEqualTo(StrokeShape.ARROW)
    }
}
