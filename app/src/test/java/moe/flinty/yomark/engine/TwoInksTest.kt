package moe.flinty.yomark.engine

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.engine.mlkit.TwoInks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * 真机上一张酒店缩略图被 ML Kit 当成疑似条码框了出来：解不出内容，所以只圈不打码，但这个框本身就是误报。
 *
 * 修法不是「解不出就不报」——解不出的里面有真码：分析图降采样后模块不到 2 像素的小二维码、
 * 被字压住一角的收款码。而是看颜色：码只有两种墨色，模糊只会把两色混合，混出来的颜色仍在两色连线上；
 * 彩色照片不在。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // 读照片要真的解码 PNG
class TwoInksTest {

    @Test fun `a crisp black and white code looks printed`() {
        val m = TwoInks.measure(fakeCode().px)!!
        assertThat(m.eta).isGreaterThan(0.95f)
        assertThat(m.offLineShare).isLessThan(0.01f)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** 关键性质：糊到明暗分不出两档（η 掉到 0.8 以下，跟照片一样低），颜色仍在一条线上。 */
    @Test fun `blurring a code does not make it look like a photo`() {
        val m = TwoInks.measure(blur(fakeCode(module = 2), radius = 1).px)!!
        assertThat(m.eta).isLessThan(TwoInks.TWO_TONE_ETA)   // 前提：只看明暗已经保不住它了
        assertThat(m.offLineShare).isLessThan(0.01f)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** 分析图是降采样过的：4 像素一个模块的码缩一半再糊一点，就是 ML Kit 解不出、导出原图却扫得开的样子。 */
    @Test fun `a small code after the analysis downscale still looks printed`() {
        val m = TwoInks.measure(blur(downscale(fakeCode(module = 3), 2), radius = 1, passes = 1).px)!!
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** 墨不一定是黑的：藏青的码混出来的颜色落在藏青与米白之间，同样在线上。 */
    @Test fun `a blurred navy code on off white still looks printed`() {
        val img = fakeCode(module = 2, ink = argb(27, 58, 92), paper = argb(244, 244, 240))
        val m = TwoInks.measure(blur(img, radius = 1).px)!!
        assertThat(m.offLineShare).isLessThan(0.01f)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /**
     * 墨和底取中位色而不是平均色：藏青墨的码稍微糊了一点，中间一块黄 logo（边长 30%，面积 9%）。
     * 平均色会被 logo 拉向黄色，连线一歪，整片墨点都算「不在线上」（离线占比约 0.4，跟照片一样高）；
     * 中位色不受这一成像素左右。这一条没跳过中间，只靠中位数。
     */
    @Test fun `a coloured logo does not tilt the ink to paper line`() {
        val img = fakeCode(modules = 33, module = 3, ink = argb(27, 58, 92))
        paintSquare(img, side = img.w * 3 / 10, colour = argb(250, 200, 0))
        val m = TwoInks.measure(blur(img, radius = 1).px)!!
        assertThat(m.eta).isLessThan(TwoInks.TWO_TONE_ETA)   // 前提：η 那条后路救不了它
        assertThat(m.offLineShare).isLessThan(TwoInks.MAX_OFF_LINE_SHARE)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** 名片码中间是一张彩色头像（这里用那张咖啡照片，边长 35%）：中间那块不看。 */
    @Test fun `a colour photo used as the avatar in the middle is not counted`() {
        val img = fakeCode(modules = 33, module = 4)
        paintSquare(img, side = img.w * 35 / 100, photo = coffeePhoto())
        val whole = Quad.fromRect(RectF(0f, 0f, img.w.toFloat(), img.h.toFloat()))
        val m = TwoInks.measure(img.sample(whole))!!
        assertThat(m.offLineShare).isLessThan(0.01f)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    @Test fun `a colour photo does not look printed`() {
        val photo = coffeePhoto()
        val m = TwoInks.measure(photo.sample(Quad.fromRect(RectF(0f, 0f, photo.w.toFloat(), photo.h.toFloat()))))!!
        assertThat(m.offLineShare).isAtLeast(TwoInks.MAX_OFF_LINE_SHARE)
        assertThat(m.eta).isLessThan(TwoInks.TWO_TONE_ETA)
        assertThat(TwoInks.looksPrinted(m)).isFalse()
    }

    /** 缩小、模糊只会让照片的颜色往均值靠，靠不到一条线上去。 */
    @Test fun `a colour photo stays rejected when smaller and blurrier`() {
        assertThat(TwoInks.looksPrinted(TwoInks.measure(downscale(coffeePhoto(), 2).px))).isFalse()
        assertThat(TwoInks.looksPrinted(TwoInks.measure(blur(coffeePhoto(), radius = 1).px))).isFalse()
    }

    /**
     * 渐变色的码：暗模块从蓝渐变到红，颜色这一条已经判它出局，但明暗清清楚楚两档。
     * η 那一条就是给这种码留的。
     */
    @Test fun `a code with gradient coloured modules is kept by its two tone brightness`() {
        val img = fakeCode(modules = 33, module = 4)
        for (y in 0 until img.h) for (x in 0 until img.w) {
            if (img[x, y] != BLACK) continue
            val t = x.toFloat() / (img.w - 1)
            img[x, y] = argb((20 + 180 * t).toInt(), (60 - 20 * t).toInt(), (200 - 160 * t).toInt())
        }
        val m = TwoInks.measure(img.px)!!
        assertThat(m.offLineShare).isAtLeast(TwoInks.MAX_OFF_LINE_SHARE)   // 前提：只看颜色会丢掉它
        assertThat(m.eta).isAtLeast(TwoInks.TWO_TONE_ETA)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** 拿不准就留着：像素太少不下判断。 */
    @Test fun `too few pixels are not judged`() {
        assertThat(TwoInks.measure(IntArray(TwoInks.MIN_PIXELS - 1) { BLACK })).isNull()
        assertThat(TwoInks.looksPrinted(null)).isTrue()
    }

    /** 近乎纯色的一块（哪怕是彩色）谈不上两种墨，不下判断。 */
    @Test fun `a nearly flat region is not judged`() {
        val rnd = Random(1)
        val px = IntArray(400) { argb(200 + rnd.nextInt(8), 120 + rnd.nextInt(8), 40 + rnd.nextInt(8)) }
        val m = TwoInks.measure(px)!!
        assertThat(m.separation).isLessThan(TwoInks.MIN_SEPARATION)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    @Test fun `a single colour region is not judged`() {
        val m = TwoInks.measure(IntArray(400) { argb(30, 160, 90) })!!
        assertThat(m.separation).isEqualTo(0f)
        assertThat(TwoInks.looksPrinted(m)).isTrue()
    }

    /** alpha 不参与判断：同一批颜色，半透明与不透明结论一样。 */
    @Test fun `alpha is ignored`() {
        val opaque = coffeePhoto().px
        val translucent = IntArray(opaque.size) { (opaque[it] and 0x00FFFFFF) or (0x40 shl 24) }
        assertThat(TwoInks.measure(translucent)).isEqualTo(TwoInks.measure(opaque))
    }
}
