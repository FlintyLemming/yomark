# DAMA 安卓版 · 计划 06 · M4：打码样式全集与用途水印

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐六种打码样式（实色块已有，本计划做像素化、模糊、马克笔、Emoji、抹除），落地抹除的方差降级与像素化的块尺寸下限，加上用途水印，并用去码工具验证导出图不可还原。

**Architecture:** 每种样式一个 `MaskRenderer` 实现，样式与识别完全解耦。**每种的绘制路径都以 `Quad` 为裁剪区，不是外接矩形**——倾斜文本行用外接矩形会盖住相邻内容。安全性分三档：不可还原（实色块 / 像素化 / Emoji / 抹除）、外观优先（模糊）、仅标记（马克笔），后两者在 UI 上明确标注。

**Tech Stack:** `android.graphics.Canvas` · Robolectric `NATIVE` 图形模式做像素级断言

**Spec:** `docs/superpowers/specs/2026-09-02-dama-android-design.md`（重点 §8、§7.5、§12 M4）

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-dama-android-00-index.md`

**前置:** 计划 01–05 全部完成

**出口（spec §12）**：导出图通过去码工具验证不可还原。

---

## 一处必须记录的方案偏离

spec §8 写「模糊：API 31+ 走 `RenderEffect`，以下走缩放法」。

**本计划两条路径都用缩放法**，理由：`RenderEffect` 只能作用在硬件加速的 Canvas 上，而导出是往一个软件 `Bitmap` 的 Canvas 上画，`drawRenderNode` 在那里会抛异常。若只在预览用 `RenderEffect`、导出用缩放法，预览与导出的模糊强度会不一致——**这会打破「预览所见即导出所得」这条比模糊质量重要得多的保证**。

执行到 Task 35 时，把这个决定写回 spec §8 的模糊那一行。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `render/PixelateRenderer.kt` | 像素化，含块边长下限与过小区域的降级 |
| `render/BlurRenderer.kt` | 模糊（缩放法） |
| `render/MarkerRenderer.kt` | 马克笔，半透明叠加 + 手绘抖动 |
| `render/EmojiRenderer.kt` | Emoji，按短边定字号 |
| `render/EraseRenderer.kt` | 抹除，环形采样中位色 + 方差降级 |
| `render/MaskStyleInfo.kt` | 每种样式的显示名与安全性标注 |
| `render/RendererRegistry.kt`（改） | `default()` 返回全部六种 |
| `ui/components/StyleBar.kt` | 样式选择器，带安全性说明 |
| `export/PurposeWatermarkDrawer.kt` | 用途水印（「仅供办理 XX 使用」） |
| `ui/components/PurposeWatermarkSheet.kt` | 用途水印的文案输入 |

---

### Task 34: 像素化

**Files:**
- Create: `app/src/main/java/com/dama/app/render/PixelateRenderer.kt`
- Test: `app/src/test/java/com/dama/app/render/PixelateRendererTest.kt`

**Interfaces:**
- Consumes: `MaskRenderer`、`MaskOptions.pixelBlockDivisor`
- Produces:
  - `class PixelateRenderer(fallback: MaskRenderer = SolidRenderer()) : MaskRenderer`
  - `PixelateRenderer.Companion.MIN_BLOCK_PX = 12f`
  - `fun blockSizeFor(quad: Quad, divisor: Int): Float`
  - `fun willFallBack(quad: Quad, divisor: Int): Boolean`

**规格（spec §8）**：取 Quad 外接框区域 → 缩到 1/N → 最近邻放大 → 以 Quad 裁剪绘回。
**块边长下限 = `max(区域短边 / 8, 12px)`，低于此值拒绝渲染** —— 块太小的像素化是可以被去码工具还原的。区域本身小到连一个 12px 块都放不下时，降级为实色块，而不是画一个假的马赛克。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/render/PixelateRendererTest.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PixelateRendererTest {

    /** 每 4px 一格的红绿棋盘：像素化之后必然变成大块单色。 */
    private fun checkerboard(size: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (x in 0 until size step 4) for (y in 0 until size step 4) {
            p.color = if ((x / 4 + y / 4) % 2 == 0) Color.RED else Color.GREEN
            c.drawRect(x.toFloat(), y.toFloat(), x + 4f, y + 4f, p)
        }
        return bmp
    }

    private val renderer = PixelateRenderer()

    @Test
    fun `style is PIXELATE`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun `block size is the short edge over the divisor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 320f, 160f))
        assertThat(renderer.blockSizeFor(q, divisor = 8)).isWithin(0.1f).of(20f)
    }

    @Test
    fun `block size never drops below the 12px floor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 200f, 40f))     // 短边 40 / 8 = 5 → 抬到 12
        assertThat(renderer.blockSizeFor(q, divisor = 8)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }

    @Test
    fun `a region too small for even one block falls back instead of faking a mosaic`() {
        val tiny = Quad.fromRect(RectF(0f, 0f, 20f, 10f))
        assertThat(renderer.willFallBack(tiny, divisor = 8)).isTrue()

        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, tiny, MaskOptions())
        // 降级成实色块 → 区域内是纯黑
        assertThat(bmp.getPixel(10, 5)).isEqualTo(Color.BLACK)
    }

    @Test
    fun `pixelating a checkerboard makes neighbouring pixels equal`() {
        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())

        // 块边长 = 120/8 = 15px，块内任意两点必然同色
        val a = bmp.getPixel(60, 60)
        assertThat(bmp.getPixel(61, 60)).isEqualTo(a)
        assertThat(bmp.getPixel(60, 61)).isEqualTo(a)
    }

    @Test
    fun `pixels outside the quad are untouched`() {
        val bmp = checkerboard()
        val before = bmp.getPixel(5, 5)
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(5, 5)).isEqualTo(before)
    }

    @Test
    fun `a rotated quad does not pixelate its axis-aligned corners`() {
        val bmp = checkerboard()
        val before = bmp.getPixel(12, 12)
        val diamond = Quad(PointF(100f, 20f), PointF(180f, 100f), PointF(100f, 180f), PointF(20f, 100f))
        renderer.render(Canvas(bmp), bmp, diamond, MaskOptions())
        assertThat(bmp.getPixel(12, 12)).isEqualTo(before)
    }

    @Test
    fun `a quad partly outside the bitmap does not crash`() {
        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(-50f, -50f, 60f, 60f)), MaskOptions())
    }

    @Test
    fun `a larger divisor makes smaller blocks but never below the floor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 400f, 400f))
        assertThat(renderer.blockSizeFor(q, divisor = 4)).isWithin(0.1f).of(100f)
        assertThat(renderer.blockSizeFor(q, divisor = 100)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*PixelateRendererTest*'
```

- [ ] **Step 3: 实现**

`app/src/main/java/com/dama/app/render/PixelateRenderer.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 像素化（spec §8）：外接框区域 → 缩到 1/N → 最近邻放大 → 以 Quad 裁剪绘回。
 *
 * **块边长下限 = max(区域短边/8, 12px)。** 块太小的马赛克是可以被去码工具还原的。
 * 区域小到连一个块都放不下时降级为实色块——不画一个假的马赛克糊弄用户。
 */
class PixelateRenderer(private val fallback: MaskRenderer = SolidRenderer()) : MaskRenderer {

    override val style = MaskStyle.PIXELATE

    fun blockSizeFor(quad: Quad, divisor: Int): Float =
        max(quad.shortEdge() / max(1, divisor), MIN_BLOCK_PX)

    fun willFallBack(quad: Quad, divisor: Int): Boolean =
        blockSizeFor(quad, divisor) >= quad.shortEdge()

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        if (willFallBack(quad, options.pixelBlockDivisor)) {
            fallback.render(canvas, source, quad, options)
            return
        }

        val bounds = quad.bounds()
        val src = Rect(
            bounds.left.toInt().coerceIn(0, source.width),
            bounds.top.toInt().coerceIn(0, source.height),
            bounds.right.roundToInt().coerceIn(0, source.width),
            bounds.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (src.width() <= 0 || src.height() <= 0) return

        val block = blockSizeFor(quad, options.pixelBlockDivisor)
        val smallW = max(1, (src.width() / block).roundToInt())
        val smallH = max(1, (src.height() / block).roundToInt())

        // 缩小时用双线性（取块内平均色），放大时用最近邻（保持硬边）
        val cropped = Bitmap.createBitmap(source, src.left, src.top, src.width(), src.height())
        val small = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(small, null, RectF(src), NEAREST)
        canvas.restoreToCount(save)

        if (small !== cropped) small.recycle()
        cropped.recycle()
    }

    companion object {
        const val MIN_BLOCK_PX = 12f
        private val NEAREST = Paint().apply {
            isFilterBitmap = false      // 最近邻：马赛克边缘必须是硬的
            isAntiAlias = false
            isDither = false
        }
    }
}
```

- [ ] **Step 4: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*PixelateRendererTest*'
git add app/src/main/java/com/dama/app/render/PixelateRenderer.kt app/src/test/java/com/dama/app/render/PixelateRendererTest.kt
git commit -m "feat: add pixelate renderer with 12px block floor and small-region fallback"
```

预期：9 个测试全部 PASS。

---

### Task 35: 模糊

**Files:**
- Create: `app/src/main/java/com/dama/app/render/BlurRenderer.kt`
- Test: `app/src/test/java/com/dama/app/render/BlurRendererTest.kt`
- Modify: `docs/superpowers/specs/2026-09-02-dama-android-design.md`（§8 记录方案偏离）

**Interfaces:**
- Consumes: `MaskRenderer`、`MaskOptions.blurRadiusRatio`
- Produces: `class BlurRenderer : MaskRenderer`，`style = MaskStyle.BLUR`

**安全性：外观优先，非安全。** 这一点必须在 UI 上明确标注（Task 38）。模糊在这个 app 里的定位是「让截图好看一点」，不是「让内容不可还原」。

实现用**三次缩放-放大**逼近高斯：缩到 1/k 再放大回来，重复三次，接近高斯核的效果，且预览与导出走同一条路径。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/render/BlurRendererTest.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlurRendererTest {

    /** 左半黑右半白：模糊后交界处会出现中间灰。 */
    private fun halfAndHalf(size: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, size / 2f, size.toFloat(), Paint().apply { color = Color.BLACK })
        }
        return bmp
    }

    private val renderer = BlurRenderer()

    @Test
    fun `style is BLUR`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.BLUR)
    }

    @Test
    fun `blurring a hard edge produces intermediate greys`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 180f, 180f)), MaskOptions())

        var greyFound = false
        for (x in 85..115) {
            val v = Color.red(bmp.getPixel(x, 100))
            if (v in 40..215) greyFound = true
        }
        assertThat(greyFound).isTrue()
    }

    @Test
    fun `pixels outside the quad keep their hard edge`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 180f, 60f)), MaskOptions())
        // y=150 在遮罩之外，交界仍然是硬的
        assertThat(Color.red(bmp.getPixel(99, 150))).isEqualTo(0)
        assertThat(Color.red(bmp.getPixel(101, 150))).isEqualTo(255)
    }

    @Test
    fun `a larger radius ratio blurs more`() {
        fun spread(ratio: Float): Int {
            val bmp = halfAndHalf()
            renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 190f, 190f)),
                MaskOptions(blurRadiusRatio = ratio))
            return (10..190).count { x -> Color.red(bmp.getPixel(x, 100)) in 40..215 }
        }
        assertThat(spread(0.16f)).isAtLeast(spread(0.04f))
    }

    @Test
    fun `a tiny region does not crash`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 0f, 3f, 3f)), MaskOptions())
    }

    @Test
    fun `a quad partly outside the bitmap does not crash`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(-40f, -40f, 40f, 40f)), MaskOptions())
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/render/BlurRenderer.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模糊（spec §8）。**安全性：外观优先，非安全** —— UI 必须明确标注这一点。
 *
 * 实现用三次「缩小-放大」逼近高斯核。
 *
 * 方案偏离：spec 原文写「API 31+ 走 RenderEffect」。本实现两条路径都用缩放法，
 * 因为 RenderEffect 只能作用在硬件加速的 Canvas 上，而导出画在软件 Bitmap 上；
 * 分两条路径会让预览与导出的模糊强度不一致，破坏「所见即所得」。
 */
class BlurRenderer : MaskRenderer {

    override val style = MaskStyle.BLUR

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val bounds = quad.bounds()
        val src = Rect(
            bounds.left.toInt().coerceIn(0, source.width),
            bounds.top.toInt().coerceIn(0, source.height),
            bounds.right.roundToInt().coerceIn(0, source.width),
            bounds.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (src.width() < 2 || src.height() < 2) return

        val shrink = max(2f, quad.shortEdge() * options.blurRadiusRatio)
        val smallW = max(1, (src.width() / shrink).roundToInt())
        val smallH = max(1, (src.height() / shrink).roundToInt())

        var work = Bitmap.createBitmap(source, src.left, src.top, src.width(), src.height())
        repeat(PASSES) {
            val down = Bitmap.createScaledBitmap(work, smallW, smallH, true)
            val up = Bitmap.createScaledBitmap(down, src.width(), src.height(), true)
            if (down !== work) down.recycle()
            if (work !== up) work.recycle()
            work = up
        }

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(work, null, RectF(src), SMOOTH)
        canvas.restoreToCount(save)
        work.recycle()
    }

    private companion object {
        const val PASSES = 3
        val SMOOTH = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    }
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*BlurRendererTest*'
```

预期：6 个测试全部 PASS。

- [ ] **Step 3: 把方案偏离写回 spec**

在 `docs/superpowers/specs/2026-09-02-dama-android-design.md` 的 §8 表格里，把模糊那一行的「实现」列改成：

```
| 模糊 | 同上流程，改用三次缩放逼近高斯（不用 RenderEffect：它只能作用于硬件加速 Canvas，而导出画在软件 Bitmap 上，分两条路径会让预览与导出不一致） | UI 明确标注「外观优先，非安全」 |
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/dama/app/render/BlurRenderer.kt app/src/test/java/com/dama/app/render/BlurRendererTest.kt docs/superpowers/specs
git commit -m "feat: add scaling-based blur renderer, consistent between preview and export"
```

---

### Task 36: 马克笔与 Emoji

**Files:**
- Create: `app/src/main/java/com/dama/app/render/MarkerRenderer.kt`
- Create: `app/src/main/java/com/dama/app/render/EmojiRenderer.kt`
- Test: `app/src/test/java/com/dama/app/render/MarkerAndEmojiRendererTest.kt`

**Interfaces:**
- Consumes: `MaskRenderer`、`MaskOptions.markerColor`、`MaskOptions.emoji`
- Produces: `class MarkerRenderer : MaskRenderer`、`class EmojiRenderer : MaskRenderer`

| 样式 | 实现 | 安全性 |
|---|---|---|
| 马克笔 | 半透明色叠加，路径带轻微手绘抖动 | **不遮蔽，仅标记**；UI 标注 |
| Emoji | 按 Quad 短边定字号，居中绘制文本 | 不可还原 |

马克笔是这六种里唯一**不遮蔽**的——它的用途是「圈出来给人看」，不是「盖住不让人看」。UI 上必须写清楚，否则用户会以为自己打了码。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/render/MarkerAndEmojiRendererTest.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkerAndEmojiRendererTest {

    private fun white(size: Int = 200) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    // ---------- 马克笔 ----------
    @Test fun `marker style is MARKER`() {
        assertThat(MarkerRenderer().style).isEqualTo(MaskStyle.MARKER)
    }

    @Test fun `marker tints the region without hiding it`() {
        val bmp = white()
        MarkerRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        val c = bmp.getPixel(100, 100)
        assertThat(c).isNotEqualTo(Color.WHITE)          // 确实上了色
        assertThat(Color.red(c)).isGreaterThan(120)      // 但底下还看得见：不是不透明黑
        assertThat(Color.green(c)).isGreaterThan(120)
    }

    @Test fun `marker respects the configured colour`() {
        val bmp = white()
        MarkerRenderer().render(
            Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)),
            MaskOptions(markerColor = 0x9900FF00.toInt()),
        )
        val c = bmp.getPixel(100, 100)
        assertThat(Color.green(c)).isGreaterThan(Color.red(c))
    }

    @Test fun `marker leaves the outside untouched`() {
        val bmp = white()
        MarkerRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(Color.WHITE)
    }

    // ---------- Emoji ----------
    @Test fun `emoji style is EMOJI`() {
        assertThat(EmojiRenderer().style).isEqualTo(MaskStyle.EMOJI)
    }

    @Test fun `emoji fills the region opaquely first so nothing shows through`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        // 角落必须被底色盖住 —— Emoji 是圆的，光靠字形盖不满矩形
        assertThat(bmp.getPixel(45, 45)).isNotEqualTo(Color.WHITE)
    }

    @Test fun `emoji draws something in the middle of the region`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        var varied = false
        val base = bmp.getPixel(45, 45)
        for (x in 80..120 step 4) for (y in 80..120 step 4) {
            if (bmp.getPixel(x, y) != base) varied = true
        }
        assertThat(varied).isTrue()
    }

    @Test fun `emoji leaves the outside untouched`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(Color.WHITE)
    }

    @Test fun `a tiny region does not crash either renderer`() {
        val bmp = white()
        val tiny = Quad.fromRect(RectF(0f, 0f, 2f, 2f))
        MarkerRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
        EmojiRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/render/MarkerRenderer.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import kotlin.math.sin

/**
 * 马克笔（spec §8）：半透明色叠加，路径带轻微手绘抖动。
 *
 * **安全性：不遮蔽，仅标记。** 这是六种样式里唯一不遮住内容的，
 * 用途是「圈出来给人看」而不是「盖住不让人看」。UI 必须标注。
 */
class MarkerRenderer : MaskRenderer {

    override val style = MaskStyle.MARKER

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            color = options.markerColor
        }
        canvas.drawPath(jitter(quad), paint)
    }

    /** 沿边插值几个点并加正弦扰动，让边缘像手画的而不是尺子画的。 */
    private fun jitter(quad: Quad): Path {
        val pts = quad.points()
        val amplitude = quad.shortEdge() * JITTER_RATIO
        val path = Path()
        pts.forEachIndexed { i, p ->
            val next = pts[(i + 1) % pts.size]
            for (s in 0 until SEGMENTS) {
                val t = s.toFloat() / SEGMENTS
                val phase = (i * SEGMENTS + s).toFloat()
                val nx = -(next.y - p.y)
                val ny = (next.x - p.x)
                val len = kotlin.math.hypot(nx, ny).coerceAtLeast(1e-3f)
                val off = sin(phase * 1.7f) * amplitude
                val x = p.x + (next.x - p.x) * t + nx / len * off
                val y = p.y + (next.y - p.y) * t + ny / len * off
                if (i == 0 && s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        path.close()
        return path
    }

    private companion object {
        const val SEGMENTS = 6
        const val JITTER_RATIO = 0.03f
    }
}
```

`app/src/main/java/com/dama/app/render/EmojiRenderer.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle

/**
 * Emoji（spec §8）：按 Quad 短边定字号，居中绘制文本。不可还原。
 *
 * 先用不透明底色填满 Quad 再画字形——Emoji 是圆的，光靠字形盖不满矩形，
 * 不铺底的话四角会漏出原内容。
 */
class EmojiRenderer : MaskRenderer {

    override val style = MaskStyle.EMOJI

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        bgPaint.color = BACKGROUND
        canvas.drawPath(quad.toPath(), bgPaint)

        val short = quad.shortEdge()
        if (short < MIN_RENDER_PX) return          // 太小画不下字形，铺底即可

        textPaint.textSize = short * SIZE_RATIO
        val b = quad.bounds()
        val metrics = textPaint.fontMetrics
        val baseline = b.centerY() - (metrics.ascent + metrics.descent) / 2f

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawText(options.emoji, b.centerX(), baseline, textPaint)
        canvas.restoreToCount(save)
    }

    private companion object {
        const val SIZE_RATIO = 0.86f
        const val MIN_RENDER_PX = 8f
        val BACKGROUND = Color.rgb(0x33, 0x33, 0x38)
    }
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*MarkerAndEmojiRendererTest*'
```

预期：9 个测试全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/dama/app/render app/src/test/java/com/dama/app/render/MarkerAndEmojiRendererTest.kt
git commit -m "feat: add marker (annotate-only) and emoji renderers"
```

---

### Task 37: 抹除与方差降级

**Files:**
- Create: `app/src/main/java/com/dama/app/render/EraseRenderer.kt`
- Test: `app/src/test/java/com/dama/app/render/EraseRendererTest.kt`

**Interfaces:**
- Consumes: `Quad.expand`、`MaskRenderer`
- Produces:
  - `class EraseRenderer(fallback: MaskRenderer = SolidRenderer()) : MaskRenderer`
  - `fun willDegrade(source: Bitmap, quad: Quad): Boolean`
  - `EraseRenderer.Companion.RING_PX = 4f`、`VARIANCE_THRESHOLD`

**规格（spec §8）**：采样 Quad 外扩 4px 的环形区域，取中位色填充。不可还原。

**降级策略**：环形采样区的颜色方差超过阈值时（说明背景不是纯色，中位色填充会留下明显色块），**自动降级为实色块并在 UI 上说明原因**。截图场景绝大多数是纯色或简单渐变背景，这条路径的命中率会很高；照片类复杂背景交给降级，不引 inpainting 模型。

`willDegrade` 是给 UI 用的——用户选了抹除但这张图会降级时，需要告诉他为什么。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/render/EraseRendererTest.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EraseRendererTest {

    /** 纯色背景上有一块深色文字区 —— 截图里最常见的情形。 */
    private fun flatBackground(bg: Int = Color.rgb(245, 245, 247)): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(bg)
            drawRect(RectF(70f, 90f, 130f, 110f), Paint().apply { color = Color.rgb(20, 20, 20) })
        }
        return bmp
    }

    /** 高频彩色噪点 —— 照片类复杂背景。 */
    private fun noisyBackground(): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (x in 0 until 200 step 2) for (y in 0 until 200 step 2) {
            p.color = Color.rgb((x * 7) % 256, (y * 13) % 256, ((x + y) * 5) % 256)
            c.drawRect(x.toFloat(), y.toFloat(), x + 2f, y + 2f, p)
        }
        return bmp
    }

    private val renderer = EraseRenderer()

    @Test fun `style is ERASE`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.ERASE)
    }

    @Test fun `on a flat background the region is filled with the surrounding colour`() {
        val bmp = flatBackground()
        val quad = Quad.fromRect(RectF(70f, 90f, 130f, 110f))
        assertThat(renderer.willDegrade(bmp, quad)).isFalse()

        renderer.render(Canvas(bmp), bmp, quad, MaskOptions())
        val filled = bmp.getPixel(100, 100)
        assertThat(abs(Color.red(filled) - 245)).isAtMost(8)
        assertThat(abs(Color.green(filled) - 245)).isAtMost(8)
        assertThat(abs(Color.blue(filled) - 247)).isAtMost(8)
    }

    @Test fun `the erased region no longer contains the original dark text`() {
        val bmp = flatBackground()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(70f, 90f, 130f, 110f)), MaskOptions())
        for (x in 72..128 step 4) for (y in 92..108 step 4) {
            assertThat(Color.red(bmp.getPixel(x, y))).isGreaterThan(100)
        }
    }

    @Test fun `a noisy background degrades to a solid block`() {
        val bmp = noisyBackground()
        val quad = Quad.fromRect(RectF(70f, 90f, 130f, 110f))
        assertThat(renderer.willDegrade(bmp, quad)).isTrue()

        renderer.render(Canvas(bmp), bmp, quad, MaskOptions())
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.BLACK)
    }

    @Test fun `pixels outside the quad are untouched`() {
        val bmp = flatBackground()
        val before = bmp.getPixel(10, 10)
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(70f, 90f, 130f, 110f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(before)
    }

    @Test fun `a quad at the image edge does not crash`() {
        val bmp = flatBackground()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 0f, 20f, 20f)), MaskOptions())
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(180f, 180f, 200f, 200f)), MaskOptions())
    }

    @Test fun `a quad covering the whole image degrades rather than sampling nothing`() {
        val bmp = flatBackground()
        val whole = Quad.fromRect(RectF(0f, 0f, 200f, 200f))
        renderer.render(Canvas(bmp), bmp, whole, MaskOptions())
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.BLACK)
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/render/EraseRenderer.kt`：

```kotlin
package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 抹除（spec §8）：采样 Quad 外扩 4px 的环形区域，取中位色填充。不可还原。
 *
 * **降级策略**：环形采样区颜色方差超阈值时（背景不是纯色，中位色填充会留下明显色块），
 * 自动降级为实色块。截图场景绝大多数是纯色或简单渐变背景，这条路径命中率很高；
 * 照片类复杂背景交给降级，不引 inpainting 模型。
 */
class EraseRenderer(private val fallback: MaskRenderer = SolidRenderer()) : MaskRenderer {

    override val style = MaskStyle.ERASE

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    /** 给 UI 用：用户选了抹除但这张图会降级时，需要告诉他为什么。 */
    fun willDegrade(source: Bitmap, quad: Quad): Boolean {
        val samples = ringSamples(source, quad)
        if (samples.size < MIN_SAMPLES) return true
        return variance(samples) > VARIANCE_THRESHOLD
    }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val samples = ringSamples(source, quad)
        if (samples.size < MIN_SAMPLES || variance(samples) > VARIANCE_THRESHOLD) {
            fallback.render(canvas, source, quad, options)
            return
        }
        paint.color = medianColor(samples)
        canvas.drawPath(quad.toPath(), paint)
    }

    /** Quad 外扩 RING_PX 的环形区域上的像素：在外扩框内、原框外。 */
    private fun ringSamples(source: Bitmap, quad: Quad): IntArray {
        val outer = quad.expand(RING_PX).bounds()
        val out = ArrayList<Int>(256)
        val stepX = max(1, (outer.width() / SAMPLE_STEPS).roundToInt())
        val stepY = max(1, (outer.height() / SAMPLE_STEPS).roundToInt())

        var x = outer.left.toInt()
        while (x < outer.right) {
            var y = outer.top.toInt()
            while (y < outer.bottom) {
                if (x in 0 until source.width && y in 0 until source.height) {
                    val p = android.graphics.PointF(x.toFloat(), y.toFloat())
                    if (!quad.contains(p)) out += source.getPixel(x, y)
                }
                y += stepY
            }
            x += stepX
        }
        return out.toIntArray()
    }

    private fun medianColor(samples: IntArray): Int {
        fun median(channel: (Int) -> Int): Int =
            samples.map(channel).sorted()[samples.size / 2]
        return Color.rgb(median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    /** 三通道方差之和。纯色背景接近 0；照片背景轻松上千。 */
    private fun variance(samples: IntArray): Double {
        fun varOf(channel: (Int) -> Int): Double {
            val mean = samples.sumOf { channel(it).toDouble() } / samples.size
            return samples.sumOf { val d = channel(it) - mean; d * d } / samples.size
        }
        return varOf { Color.red(it) } + varOf { Color.green(it) } + varOf { Color.blue(it) }
    }

    companion object {
        const val RING_PX = 4f
        const val VARIANCE_THRESHOLD = 900.0     // 每通道标准差约 17，纯色与浅渐变都在这以下
        private const val MIN_SAMPLES = 24
        private const val SAMPLE_STEPS = 40
    }
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*EraseRendererTest*'
```

预期：7 个测试全部 PASS。

若 `on a flat background` 的颜色偏差超过 8，把 `SAMPLE_STEPS` 调大（采样更密）；若 `a noisy background degrades` 不通过，说明 `VARIANCE_THRESHOLD` 太高——**只在这两个方向上调，不要通过放宽断言让测试变绿**。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/dama/app/render/EraseRenderer.kt app/src/test/java/com/dama/app/render/EraseRendererTest.kt
git commit -m "feat: add erase renderer with ring sampling and variance-based degradation"
```

---

### Task 38: 样式选择器与安全性标注

**Files:**
- Create: `app/src/main/java/com/dama/app/render/MaskStyleInfo.kt`
- Modify: `app/src/main/java/com/dama/app/render/RendererRegistry.kt`
- Create: `app/src/main/java/com/dama/app/ui/components/StyleBar.kt`
- Modify: `app/src/main/java/com/dama/app/ui/components/EditorChrome.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorScreen.kt`
- Test: `app/src/test/java/com/dama/app/render/MaskStyleInfoTest.kt`
- Test: `app/src/androidTest/java/com/dama/app/ui/StyleBarTest.kt`

**Interfaces:**
- Consumes: 全部六个渲染器
- Produces:
  - `enum class MaskSafety { IRREVERSIBLE, COSMETIC, ANNOTATION_ONLY }`
  - `object MaskStyleInfo { fun label(style: MaskStyle): String; fun safety(style: MaskStyle): MaskSafety; fun note(style: MaskStyle): String? }`
  - `RendererRegistry.default()` 返回全部六种
  - `@Composable fun StyleBar(style, onChange, degradeNote: String?, modifier)`

**样式是全局的（spec §7.5）**：样式选择器作用于**整张图的所有遮罩**，`MaskPlan.style` 是单值而非逐项。逐项样式要求先选中再改，把「点一下取消」这个最高频操作变成两步，而混合样式的实际需求极低。

**安全性标注不是可选项**：模糊标「外观优先，非安全」，马克笔标「仅标记，不遮蔽」。用户以为自己打了码而实际没有，是这个 app 能犯的最严重的错误。

- [ ] **Step 1: 写样式信息的失败测试**

`app/src/test/java/com/dama/app/render/MaskStyleInfoTest.kt`：

```kotlin
package com.dama.app.render

import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MaskStyleInfoTest {

    @Test fun `every style has a label`() {
        MaskStyle.entries.forEach { assertThat(MaskStyleInfo.label(it)).isNotEmpty() }
    }

    @Test fun `solid pixelate emoji and erase are irreversible`() {
        listOf(MaskStyle.SOLID, MaskStyle.PIXELATE, MaskStyle.EMOJI, MaskStyle.ERASE).forEach {
            assertThat(MaskStyleInfo.safety(it)).isEqualTo(MaskSafety.IRREVERSIBLE)
        }
    }

    @Test fun `blur is cosmetic and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.BLUR)).isEqualTo(MaskSafety.COSMETIC)
        assertThat(MaskStyleInfo.note(MaskStyle.BLUR)).contains("非安全")
    }

    @Test fun `marker is annotation only and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.MARKER)).isEqualTo(MaskSafety.ANNOTATION_ONLY)
        assertThat(MaskStyleInfo.note(MaskStyle.MARKER)).contains("不遮蔽")
    }

    @Test fun `irreversible styles carry no warning note`() {
        assertThat(MaskStyleInfo.note(MaskStyle.SOLID)).isNull()
    }

    @Test fun `the registry implements every style`() {
        assertThat(RendererRegistry.default().implemented()).containsExactlyElementsIn(MaskStyle.entries)
    }
}
```

- [ ] **Step 2: 实现样式信息并补齐 registry**

`app/src/main/java/com/dama/app/render/MaskStyleInfo.kt`：

```kotlin
package com.dama.app.render

import com.dama.app.core.model.MaskStyle

/**
 * 安全性分档（spec §8 的「安全性」列）。
 *
 * 用户以为自己打了码而实际没有，是这个 app 能犯的最严重的错误。
 * COSMETIC 与 ANNOTATION_ONLY 必须在 UI 上带着说明一起出现。
 */
enum class MaskSafety { IRREVERSIBLE, COSMETIC, ANNOTATION_ONLY }

object MaskStyleInfo {

    fun label(style: MaskStyle): String = when (style) {
        MaskStyle.SOLID -> "实色块"
        MaskStyle.PIXELATE -> "像素化"
        MaskStyle.BLUR -> "模糊"
        MaskStyle.MARKER -> "马克笔"
        MaskStyle.EMOJI -> "Emoji"
        MaskStyle.ERASE -> "抹除"
    }

    fun safety(style: MaskStyle): MaskSafety = when (style) {
        MaskStyle.SOLID, MaskStyle.PIXELATE, MaskStyle.EMOJI, MaskStyle.ERASE -> MaskSafety.IRREVERSIBLE
        MaskStyle.BLUR -> MaskSafety.COSMETIC
        MaskStyle.MARKER -> MaskSafety.ANNOTATION_ONLY
    }

    fun note(style: MaskStyle): String? = when (safety(style)) {
        MaskSafety.IRREVERSIBLE -> null
        MaskSafety.COSMETIC -> "模糊是外观优先，非安全手段——可能被还原"
        MaskSafety.ANNOTATION_ONLY -> "马克笔仅标记、不遮蔽，底下的内容仍然可见"
    }
}
```

`RendererRegistry.default()` 改成：

```kotlin
        /** 全部六种样式。 */
        fun default() = RendererRegistry(
            listOf(
                SolidRenderer(),
                PixelateRenderer(),
                BlurRenderer(),
                MarkerRenderer(),
                EmojiRenderer(),
                EraseRenderer(),
            )
        )
```

```bash
./gradlew :app:testDebugUnitTest --tests '*MaskStyleInfoTest*' --tests '*SolidRendererTest*'
```

预期：全部 PASS（`SolidRendererTest` 里那条「registry falls back」现在返回真正的 EmojiRenderer，断言 `isNotNull` 仍然成立）。

- [ ] **Step 3: 写样式选择器**

`app/src/main/java/com/dama/app/ui/components/StyleBar.kt`：

```kotlin
package com.dama.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dama.app.core.model.MaskStyle
import com.dama.app.render.MaskStyleInfo

/**
 * 样式选择器（spec §7.5）：作用于**整张图的所有遮罩**，不是逐项。
 *
 * 安全性说明与降级说明就贴在选择器下面——用户切到模糊的那一刻就该知道
 * 模糊不是安全手段，而不是导出之后才发现。
 */
@Composable
fun StyleBar(
    style: MaskStyle,
    onChange: (MaskStyle) -> Unit,
    degradeNote: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaskStyle.entries.forEach { s ->
                FilterChip(
                    selected = s == style,
                    onClick = { onChange(s) },
                    label = { Text(MaskStyleInfo.label(s)) },
                )
            }
        }
        val note = MaskStyleInfo.note(style) ?: degradeNote
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 4: 接进底栏**

把 `EditorChrome.kt` 里 `EditorBottomBar` 的样式按钮那段替换成调用 `StyleBar`，签名改为：

```kotlin
@Composable
fun EditorBottomBar(
    style: MaskStyle,
    degradeNote: String?,
    onStyleChange: (MaskStyle) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StyleBar(style = style, onChange = onStyleChange, degradeNote = degradeNote)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onExport, enabled = !exporting) { Text(if (exporting) "导出中…" else "导出") }
        }
    }
}
```

删掉原来的 `implemented` 参数与 `MaskStyle.label()` 私有扩展（改用 `MaskStyleInfo.label`）。

`EditorScreen` 里对应改成：

```kotlin
            EditorBottomBar(
                style = state.plan.style,
                degradeNote = state.degradeNote,
                onStyleChange = vm::setStyle,
                onExport = { vm.requestExport(applyWatermark = true) },
                exporting = state.exporting,
            )
```

- [ ] **Step 5: 让 ViewModel 计算抹除降级提示**

`EditorUiState` 加：

```kotlin
    /** 当前样式在这张图上会降级时的说明。抹除遇到复杂背景时非空。 */
    val degradeNote: String? = null,
```

`EditorViewModel.setStyle` 改成：

```kotlin
    fun setStyle(style: MaskStyle) {
        mutate { it.copy(style = style) }
        _state.value = _state.value.copy(degradeNote = degradeNoteFor(style))
    }

    /**
     * 抹除在复杂背景上会降级为实色块（spec §8）。用户需要知道为什么，
     * 而不是导出后发现「怎么和预览不一样」。
     */
    private fun degradeNoteFor(style: MaskStyle): String? {
        if (style != MaskStyle.ERASE) return null
        val image = _state.value.image ?: return null
        val eraser = EraseRenderer()
        val masked = _state.value.plan.items.filter { it.state == MaskState.MASKED }
        if (masked.isEmpty()) return null
        val degrading = masked.count { eraser.willDegrade(image.bitmap, it.quad) }
        return if (degrading == 0) null
        else "$degrading 处背景过于复杂，抹除已自动降级为实色块"
    }
```

import `com.dama.app.render.EraseRenderer`。

- [ ] **Step 6: 写 UI 测试**

`app/src/androidTest/java/com/dama/app/ui/StyleBarTest.kt`：

```kotlin
package com.dama.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dama.app.core.model.MaskStyle
import com.dama.app.ui.components.StyleBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class StyleBarTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun all_six_styles_are_offered() {
        compose.setContent { StyleBar(MaskStyle.SOLID, {}, null) }
        listOf("实色块", "像素化", "模糊", "马克笔", "Emoji", "抹除").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun selecting_a_style_fires_the_callback() {
        var picked: MaskStyle? = null
        compose.setContent { StyleBar(MaskStyle.SOLID, { picked = it }, null) }
        compose.onNodeWithText("像素化").performClick()
        assertThat(picked).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun blur_shows_the_not_secure_warning() {
        compose.setContent { StyleBar(MaskStyle.BLUR, {}, null) }
        compose.onNodeWithText("模糊是外观优先，非安全手段——可能被还原").assertIsDisplayed()
    }

    @Test
    fun marker_shows_the_annotation_only_warning() {
        compose.setContent { StyleBar(MaskStyle.MARKER, {}, null) }
        compose.onNodeWithText("马克笔仅标记、不遮蔽，底下的内容仍然可见").assertIsDisplayed()
    }

    @Test
    fun erase_degradation_note_is_shown_when_present() {
        compose.setContent { StyleBar(MaskStyle.ERASE, {}, "3 处背景过于复杂，抹除已自动降级为实色块") }
        compose.onNodeWithText("3 处背景过于复杂，抹除已自动降级为实色块").assertIsDisplayed()
    }
}
```

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*StyleBarTest*'
```

预期：5 个测试 PASS。

- [ ] **Step 7: 实机走一遍六种样式**

```bash
./gradlew :app:installDebug
```

- [ ] 六种样式都能选，切换后**整张图**的所有遮罩一起变
- [ ] 模糊与马克笔下方出现红色警示文案
- [ ] 抹除在纯色背景截图上真的「消失」了，在照片上降级为黑块并给出说明
- [ ] 导出后的图与预览完全一致（尤其是模糊的强度）

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/dama/app app/src/test/java/com/dama/app/render/MaskStyleInfoTest.kt app/src/androidTest/java/com/dama/app/ui/StyleBarTest.kt
git commit -m "feat: complete the six-style set with a global style bar and safety labelling"
```

---

### Task 39: 用途水印

**Files:**
- Create: `app/src/main/java/com/dama/app/export/PurposeWatermarkDrawer.kt`
- Create: `app/src/main/java/com/dama/app/ui/components/PurposeWatermarkSheet.kt`
- Modify: `app/src/main/java/com/dama/app/export/Exporter.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorUiState.kt`、`EditorViewModel.kt`、`EditorScreen.kt`
- Test: `app/src/test/java/com/dama/app/export/PurposeWatermarkDrawerTest.kt`

**Interfaces:**
- Consumes: `Canvas`、`Bitmap`
- Produces:
  - `class PurposeWatermarkDrawer` + `fun draw(canvas: Canvas, width: Int, height: Int, text: String)`
  - `ExportRequest` 增加 `val purposeText: String? = null`
  - `EditorUiState` 增加 `val purposeText: String? = null`、`val purposeSheetVisible: Boolean = false`
  - `EditorViewModel`：`fun setPurposeText(text: String?)`、`fun showPurposeSheet()`、`fun dismissPurposeSheet()`

**这与 §9.3 的品牌水印是两回事**（spec §12 M4 明确写了这一点）：品牌水印是商业模式的一部分，付费即去除；用途水印是**安全功能**——在证件照上叠「仅供办理 XX 使用」，防止照片被挪作他用。用途水印**不受购买态影响**，付费用户一样可以加。

规格：斜向 30°、平铺整张图、半透明、字号按短边比例。平铺是刻意的——单个角落的水印一裁就没。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/export/PurposeWatermarkDrawerTest.kt`：

```kotlin
package com.dama.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurposeWatermarkDrawerTest {

    private fun white(w: Int = 600, h: Int = 800) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun changedFraction(bmp: Bitmap): Double {
        var changed = 0
        var total = 0
        for (x in 0 until bmp.width step 5) for (y in 0 until bmp.height step 5) {
            total++
            if (bmp.getPixel(x, y) != Color.WHITE) changed++
        }
        return changed.toDouble() / total
    }

    @Test
    fun `the watermark is tiled across the whole image`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        // 平铺：全图各处都有痕迹，不是只在一个角
        assertThat(changedFraction(bmp)).isGreaterThan(0.05)
    }

    @Test
    fun `every quadrant carries some of the watermark`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        fun quadrantMarked(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (x in x0 until x1 step 4) for (y in y0 until y1 step 4) {
                if (bmp.getPixel(x, y) != Color.WHITE) return true
            }
            return false
        }
        assertThat(quadrantMarked(0, 0, 300, 400)).isTrue()
        assertThat(quadrantMarked(300, 0, 600, 400)).isTrue()
        assertThat(quadrantMarked(0, 400, 300, 800)).isTrue()
        assertThat(quadrantMarked(300, 400, 600, 800)).isTrue()
    }

    @Test
    fun `the watermark is translucent so the photo underneath stays readable`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        var opaqueBlack = 0
        for (x in 0 until bmp.width step 3) for (y in 0 until bmp.height step 3) {
            if (bmp.getPixel(x, y) == Color.BLACK) opaqueBlack++
        }
        assertThat(opaqueBlack).isEqualTo(0)
    }

    @Test
    fun `an empty text draws nothing`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "")
        assertThat(changedFraction(bmp)).isEqualTo(0.0)
    }

    @Test
    fun `a tiny image does not crash`() {
        val bmp = white(20, 20)
        PurposeWatermarkDrawer().draw(Canvas(bmp), 20, 20, "仅供办理签证使用")
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/export/PurposeWatermarkDrawer.kt`：

```kotlin
package com.dama.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * 用途水印：在证件照上叠「仅供办理 XX 使用」。
 *
 * **与品牌水印（WatermarkDrawer）是两回事**：品牌水印是商业模式的一部分，付费即去除；
 * 用途水印是安全功能，防止照片被挪作他用，不受购买态影响。
 *
 * 斜向平铺整张图是刻意的——单个角落的水印一裁就没。
 */
class PurposeWatermarkDrawer {

    fun draw(canvas: Canvas, width: Int, height: Int, text: String) {
        if (text.isBlank() || width <= 0 || height <= 0) return

        val shortEdge = minOf(width, height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(ALPHA, 0, 0, 0)
            textSize = max(shortEdge * SIZE_RATIO, MIN_SIZE_PX)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textWidth = paint.measureText(text)
        if (textWidth <= 0f) return

        val stepX = textWidth + shortEdge * GAP_RATIO
        val stepY = paint.textSize * LINE_RATIO

        val save = canvas.save()
        canvas.rotate(ANGLE, width / 2f, height / 2f)
        // 旋转后要覆盖原矩形，绘制范围向外扩一个对角线长度
        val diagonal = kotlin.math.hypot(width.toFloat(), height.toFloat())
        var y = -diagonal
        var row = 0
        while (y < height + diagonal) {
            val offset = if (row % 2 == 0) 0f else stepX / 2f    // 错行，避免竖直空隙
            var x = -diagonal + offset
            while (x < width + diagonal) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val ANGLE = -30f
        const val ALPHA = 46          // 约 18% 不透明度：看得见，不挡阅读
        const val SIZE_RATIO = 0.045f
        const val MIN_SIZE_PX = 14f
        const val GAP_RATIO = 0.12f
        const val LINE_RATIO = 3.2f
    }
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*PurposeWatermarkDrawerTest*'
```

预期：5 个测试 PASS。

- [ ] **Step 3: 接进导出管线**

`ExportRequest` 加字段：

```kotlin
    /** 用途水印文案（「仅供办理 XX 使用」）。与品牌水印无关，不受购买态影响。 */
    val purposeText: String? = null,
```

`Exporter` 构造参数加 `private val purposeWatermark: PurposeWatermarkDrawer = PurposeWatermarkDrawer(),`，并在 `export` 里、品牌水印**之前**绘制：

```kotlin
            request.purposeText?.let { purposeWatermark.draw(canvas, bitmap.width, bitmap.height, it) }
            if (request.applyWatermark) watermark.draw(canvas, bitmap, maskedBounds)
```

顺序不能反：品牌水印必须画在最上层，否则用途水印的斜纹会压在品牌水印上影响可读性。

- [ ] **Step 4: 加 UI**

`app/src/main/java/com/dama/app/ui/components/PurposeWatermarkSheet.kt`：

```kotlin
package com.dama.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 用途水印的文案输入。默认给一句常见措辞，用户改中间那几个字即可。 */
@Composable
fun PurposeWatermarkSheet(
    initial: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial ?: "仅供办理 XX 使用") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用途水印") },
        text = {
            Column {
                Text("斜向平铺在整张图上，防止照片被挪作他用。与去水印的付费项无关。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.ifBlank { null }) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("不加") } },
    )
}
```

`EditorUiState` 加两个字段（见 Interfaces），`EditorViewModel` 加三个方法：

```kotlin
    fun showPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = true) }
    fun dismissPurposeSheet() { _state.value = _state.value.copy(purposeSheetVisible = false) }
    fun setPurposeText(text: String?) {
        _state.value = _state.value.copy(purposeText = text, purposeSheetVisible = false)
    }
```

`runExport` 里把 `purposeText = _state.value.purposeText` 传进 `ExportRequest`。

顶栏加一个入口按钮（`EditorTopBar` 增加 `onPurpose: () -> Unit` 参数与一个 `Icons.Filled.Watermark` 之类的图标按钮，图标名以实际可用的为准，`Icons.Filled.Layers` 也可以）。

`EditorScreen` 里加：

```kotlin
            if (state.purposeSheetVisible) {
                PurposeWatermarkSheet(
                    initial = state.purposeText,
                    onConfirm = vm::setPurposeText,
                    onDismiss = vm::dismissPurposeSheet,
                )
            }
```

- [ ] **Step 5: 实机确认**

- [ ] 顶栏能打开用途水印输入
- [ ] 输入「仅供办理签证使用」→ 导出图上斜向平铺该文案
- [ ] 品牌水印仍在右下角且清晰可读（没被斜纹压花）
- [ ] 「不加」能清掉用途水印

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/dama/app app/src/test/java/com/dama/app/export/PurposeWatermarkDrawerTest.kt
git commit -m "feat: add tiled purpose watermark for ID photos, independent of the paid brand watermark"
```

---

### Task 40: 不可还原验证与 M4 出口

**Files:**
- Create: `app/src/androidTest/java/com/dama/app/export/IrreversibilityTest.kt`
- Modify: `docs/superpowers/specs/2026-09-02-dama-android-design.md`（§15.6 的实测结论）

**Interfaces:**
- Consumes: 全部渲染器 + 导出管线
- Produces: 一组「导出图里读不出原内容」的自动断言 + 一次人工的去码工具验证

**出口（spec §12 M4）**：导出图通过去码工具验证不可还原。

自动测试能守住「像素确实被覆盖了」；**去码工具验证必须人工做一次**——自动测试证明不了「攻击者还原不出来」，只有真的拿工具试过才算数。

- [ ] **Step 1: 写自动断言**

`app/src/androidTest/java/com/dama/app/export/IrreversibilityTest.kt`：

```kotlin
package com.dama.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.image.SourceImageLoader
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.MaskItem
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskState
import com.dama.app.core.model.MaskStyle
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.mlkit.MlKitTextRecognizer
import com.dama.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 不可还原（spec §12 M4、§13）。
 *
 * 判据用 OCR：如果 ML Kit 还能从导出图里读出那串卡号，那它显然没被遮住。
 * 这守得住「盖漏了」这类错误；守不住「像素化块太小可被算法还原」——
 * 后者必须人工拿去码工具验，见 Step 3。
 */
@RunWith(AndroidJUnit4::class)
class IrreversibilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secret = "4111111111111111"

    private fun sourceWithSecret(): Pair<File, RectF> {
        val bmp = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        Canvas(bmp).apply {
            drawColor(Color.rgb(245, 245, 247))
            drawText(secret, 60f, 240f, paint)
        }
        val w = paint.measureText(secret)
        val rect = RectF(50f, 240f + paint.ascent() - 10f, 70f + w, 240f + paint.descent() + 10f)

        val f = File(context.cacheDir, "irreversible-src.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f to rect
    }

    private suspend fun exportWith(style: MaskStyle): Bitmap {
        val (src, rect) = sourceWithSecret()
        val intake = ImageIntake(context)
        val taken = intake.copyToPrivate(src.toUri())
        val image = SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType)

        val plan = MaskPlan(
            items = listOf(MaskItem(
                "s", Quad.fromRect(RectF(
                    rect.left * image.scale, rect.top * image.scale,
                    rect.right * image.scale, rect.bottom * image.scale,
                )),
                SensitiveKind.PAYMENT_CARD, DetectorSource.RULE, MaskState.MASKED,
            )),
            style = style,
            options = MaskOptions(),
        )

        var captured: Bitmap? = null
        val sink = object : ImageSink {
            override suspend fun write(
                bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
                displayName: String, mimeType: String,
            ): android.net.Uri {
                // 走一遍真实的编码-解码往返：JPEG 压缩后是否仍然遮住
                val bytes = java.io.ByteArrayOutputStream()
                    .also { bitmap.compress(format, quality, it) }.toByteArray()
                captured = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return android.net.Uri.parse("content://fake/1")
            }
        }
        Exporter(RendererRegistry.default(), WatermarkDrawer(), sink)
            .export(ExportRequest(taken.file, taken.mimeType, plan, image.scale, applyWatermark = false))
        intake.clear()
        return captured!!
    }

    private suspend fun ocrText(bmp: Bitmap): String =
        MlKitTextRecognizer()
            .recognize(com.dama.app.core.image.SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
            .joinToString(" ") { it.text }

    @Test
    fun solid_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.SOLID))).doesNotContain("4111")
    }

    @Test
    fun pixelate_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.PIXELATE))).doesNotContain("4111")
    }

    @Test
    fun emoji_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.EMOJI))).doesNotContain("4111")
    }

    @Test
    fun erase_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.ERASE))).doesNotContain("4111")
    }

    @Test
    fun marker_output_is_deliberately_still_readable() {
        // 马克笔的定位就是「仅标记、不遮蔽」。这条测试守着这个语义：
        // 如果哪天有人把马克笔改成不透明的，UI 上的标注就撒谎了。
        kotlinx.coroutines.runBlocking {
            val text = ocrText(exportWith(MaskStyle.MARKER))
            assertThat(text).contains("4111")
        }
    }
}
```

- [ ] **Step 2: 运行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*IrreversibilityTest*'
```

预期：5 个测试全部 PASS。

若 `pixelate_output_is_unreadable` 失败，说明块尺寸下限不够——把 `PixelateRenderer.MIN_BLOCK_PX` 上调并重跑，同时更新 spec §8 里的数值。**这正是 spec §15.6 要求验的东西。**

- [ ] **Step 3: 人工用去码工具验证（spec §15.6）**

自动测试证明不了「攻击者还原不出来」。拿一张实际导出图，用公开的去码工具试一次：

1. 导出一张用**像素化**遮住卡号的图（用 §8 规定的默认块尺寸）
2. 用 Depix（`https://github.com/spipm/Depix`）或同类工具尝试还原
3. 记录结果

- [ ] 像素化导出图无法被还原出可读字符
- [ ] 若能还原：**上调 `MIN_BLOCK_PX` 直到还原失败**，把新数值写进 spec §8 与 `PixelateRenderer`

把结论写进 spec §15 第 6 条（「像素化块尺寸下限是否足够」）：块尺寸、用了什么工具、结果如何、日期。

- [ ] **Step 4: 复查延迟与包体**

样式全集不引入新模型，包体应无明显变化；但渲染器多了，预览的每帧开销上升：

```bash
./gradlew :app:bundleRelease
bundletool get-size total --bundle=app/build/outputs/bundle/release/app-release.aab --dimensions=ABI
```

- [ ] arm64-v8a 下发体积仍 ≤ 25 MB
- [ ] 实机上切到「模糊」后画布拖动仍然跟手（模糊是三次缩放，是六种里最贵的；若明显掉帧，把 `BlurRenderer.PASSES` 降到 2 并重跑 `BlurRendererTest`）

- [ ] **Step 5: 跑全量并提交**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertDebugNoRuntimePermissions
git add app/src/androidTest/java/com/dama/app/export/IrreversibilityTest.kt docs/superpowers/specs
git commit -m "test: assert irreversibility of masking styles via OCR round-trip"
git tag m4-styles
```

---

## 本计划出口

> **M4 出口（spec §12）**：导出图通过去码工具验证不可还原。

- [ ] 六种样式全部实现，registry 里没有回退项
- [ ] 像素化块尺寸下限经去码工具实测校准，结论写进 spec §15.6
- [ ] 模糊与马克笔在 UI 上带安全性说明
- [ ] 抹除的方差降级会告诉用户原因
- [ ] 用途水印可用，且与品牌水印互不干扰
- [ ] 包体与延迟仍达标
