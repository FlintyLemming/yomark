# 有码安卓版 · 计划 02 · M1 下半：编辑器与导出

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把计划 01 的数据模型接上 UI 与导出，产出一个**可上架的纯手动打码工具**：冷启动直接拉系统 Photo Picker，一指画框二指导航，实色块打码，免费水印，原图分辨率导出进相册，全程零权限弹窗。

**Architecture:** 手势与视口的**决策规则**全部抽成纯函数单测（`Viewport` / `GestureRules`），Compose 只负责把触摸事件喂给它们；渲染器同一套 `MaskRenderer` 既画预览也画导出，杜绝「预览和导出不一致」这类 bug；导出走 `ImageSink` 接口，单测用假 sink，真机用 `MediaStoreSink`。

**Tech Stack:** Jetpack Compose · `ActivityResultContracts.PickVisualMedia` · `android.graphics.Canvas` · MediaStore `IS_PENDING`

**Spec:** `docs/superpowers/specs/2026-09-02-yomark-android-design.md`

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-yomark-android-00-index.md`

**前置:** 计划 01 全部完成（Quad、数据模型、UndoStack、ImageIntake、SourceImageLoader 已测通）

---

## File Structure

| 文件 | 职责 |
|---|---|
| `ui/canvas/Viewport.kt` | 图像坐标 ↔ 屏幕坐标；fit / zoom / pan / clamp；双击目标缩放 |
| `ui/canvas/GestureRules.kt` | 点击 vs 拖动的判定、小框丢弃、命中测试 —— 全是纯函数 |
| `ui/canvas/SelectionHandles.kt` | 手动框选中态的四角手柄、删除按钮、移动与调整大小 |
| `render/MaskRenderer.kt` | 渲染接口（spec §4.3） |
| `render/SolidRenderer.kt` | 实色块，M1 唯一样式 |
| `render/RendererRegistry.kt` | 样式 → 渲染器；未实现的样式回退到实色块 |
| `export/WatermarkDrawer.kt` | 品牌水印的避让计算与绘制 |
| `export/ImageSink.kt` | 写出目的地的接口 |
| `export/MediaStoreSink.kt` | `IS_PENDING` 事务写入相册 |
| `export/Exporter.kt` | 原图重绘 → 水印 → 编码 → 写出 |
| `ui/EditorUiState.kt` | UI 状态与消息 |
| `ui/EditorViewModel.kt` | 全部编辑动作与撤销栈接线 |
| `ui/canvas/ImageCanvas.kt` | Compose 画布：图像 + 遮罩 + 叠层 + 手势 |
| `ui/components/EditorChrome.kt` | 顶栏（撤销/重做/关闭）与底栏（样式/导出） |
| `ui/EditorActivity.kt` | 单 Activity 入口：无 URI 即拉 Picker；`ACTION_SEND` 直进 |

---

### Task 8: 视口变换与手势判定规则

**Files:**
- Create: `app/src/main/java/com/yomark/app/ui/canvas/Viewport.kt`
- Create: `app/src/main/java/com/yomark/app/ui/canvas/GestureRules.kt`
- Test: `app/src/test/java/com/yomark/app/ui/canvas/ViewportTest.kt`
- Test: `app/src/test/java/com/yomark/app/ui/canvas/GestureRulesTest.kt`

**Interfaces:**
- Consumes: `Quad`、`MaskPlan`、`MaskItem`（计划 01）
- Produces:
  - `data class Viewport(scale: Float, offsetX: Float, offsetY: Float)` + `imageToScreen(PointF)` / `screenToImage(PointF)` / `pan(dx, dy)` / `zoomAround(pivot: PointF, factor: Float)` / `clamped(imageW, imageH, viewW, viewH)` / `matrix(): Matrix`
  - `Viewport.Companion.fit(imageW, imageH, viewW, viewH): Viewport`
  - `Viewport.Companion.MAX_SCALE_FACTOR = 8f`、`DOUBLE_TAP_FACTOR = 2f`
  - `object GestureRules`：`isDrag(dx, dy, touchSlopPx): Boolean`、`acceptsBox(quad: Quad, minShortEdgePx: Float): Boolean`、`hitTest(items: List<MaskItem>, imagePoint: PointF): MaskItem?`、`quadFromDrag(a: PointF, b: PointF): Quad`、`LABEL_MIN_SCALE = 0.5f`

`scale` 的语义是**屏幕像素 / 图像像素**。类型小标签只在 `scale >= LABEL_MIN_SCALE` 时绘制（spec §7.2），双击在「适配屏幕」与 fit 的 2 倍之间切换（spec §7.3）。

命中测试取**面积最小**的命中项：渲染时大块先画（spec §5.4），小块画在上面，用户点到的应该是他看到的那个。

- [ ] **Step 1: 写 Viewport 的失败测试**

`app/src/test/java/com/yomark/app/ui/canvas/ViewportTest.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.PointF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewportTest {

    @Test
    fun `fit scales a wide image to the view width and centers it vertically`() {
        val v = Viewport.fit(imageW = 1000, imageH = 500, viewW = 500, viewH = 500)
        assertThat(v.scale).isWithin(1e-4f).of(0.5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(0f)
        assertThat(v.offsetY).isWithin(1e-3f).of(125f)   // (500 - 250) / 2
    }

    @Test
    fun `fit scales a tall image to the view height and centers it horizontally`() {
        val v = Viewport.fit(imageW = 500, imageH = 1000, viewW = 500, viewH = 500)
        assertThat(v.scale).isWithin(1e-4f).of(0.5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(125f)
        assertThat(v.offsetY).isWithin(1e-3f).of(0f)
    }

    @Test
    fun `imageToScreen and screenToImage round trip`() {
        val v = Viewport(scale = 0.5f, offsetX = 30f, offsetY = 40f)
        val p = PointF(200f, 100f)
        val back = v.screenToImage(v.imageToScreen(p))
        assertThat(back.x).isWithin(1e-3f).of(200f)
        assertThat(back.y).isWithin(1e-3f).of(100f)
    }

    @Test
    fun `imageToScreen applies scale then offset`() {
        val v = Viewport(scale = 2f, offsetX = 10f, offsetY = 20f)
        val s = v.imageToScreen(PointF(5f, 5f))
        assertThat(s.x).isWithin(1e-3f).of(20f)
        assertThat(s.y).isWithin(1e-3f).of(30f)
    }

    @Test
    fun `pan shifts the offset`() {
        val v = Viewport(1f, 0f, 0f).pan(15f, -5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(15f)
        assertThat(v.offsetY).isWithin(1e-3f).of(-5f)
    }

    @Test
    fun `zoomAround keeps the pivot point stationary on screen`() {
        val v = Viewport(scale = 1f, offsetX = 0f, offsetY = 0f)
        val pivot = PointF(100f, 80f)          // 屏幕坐标
        val before = v.screenToImage(pivot)
        val z = v.zoomAround(pivot, 2f)
        val after = z.screenToImage(pivot)
        assertThat(after.x).isWithin(1e-2f).of(before.x)
        assertThat(after.y).isWithin(1e-2f).of(before.y)
        assertThat(z.scale).isWithin(1e-4f).of(2f)
    }

    @Test
    fun `zoom is capped at MAX_SCALE_FACTOR times the fit scale`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)          // scale 0.5
        val z = fit.zoomAround(PointF(250f, 250f), 100f)
             .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        assertThat(z.scale).isAtMost(fit.scale * Viewport.MAX_SCALE_FACTOR + 1e-3f)
    }

    @Test
    fun `clamped never zooms out below the fit scale`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)
        val z = fit.zoomAround(PointF(250f, 250f), 0.1f)
             .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        assertThat(z.scale).isWithin(1e-3f).of(fit.scale)
    }

    @Test
    fun `clamped keeps a zoomed image from leaving the viewport`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)              // scale 0.5, 铺满
        val panned = fit.copy(scale = 1f).pan(5000f, 5000f)
            .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        // scale=1 时图像 1000px 宽，视口 500px：offset 必须落在 [-500, 0]
        assertThat(panned.offsetX).isAtMost(0f)
        assertThat(panned.offsetX).isAtLeast(-500f)
    }

    @Test
    fun `doubleTapTarget toggles between fit and twice fit`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)
        val zoomed = fit.doubleTapTarget(PointF(250f, 250f), 1000, 1000, 500, 500)
        assertThat(zoomed.scale).isWithin(1e-3f).of(fit.scale * Viewport.DOUBLE_TAP_FACTOR)
        val back = zoomed.doubleTapTarget(PointF(250f, 250f), 1000, 1000, 500, 500)
        assertThat(back.scale).isWithin(1e-3f).of(fit.scale)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*ViewportTest*'
```

预期：编译失败，`Unresolved reference: Viewport`

- [ ] **Step 3: 实现 Viewport**

`app/src/main/java/com/yomark/app/ui/canvas/Viewport.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.min

/**
 * 图像坐标 ↔ 屏幕坐标。
 * scale 的语义是「屏幕像素 / 图像像素」。
 * 屏幕点 = 图像点 * scale + offset。
 */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun imageToScreen(p: PointF) = PointF(p.x * scale + offsetX, p.y * scale + offsetY)

    fun screenToImage(p: PointF) = PointF((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    fun pan(dx: Float, dy: Float) = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** 以屏幕上的 pivot 为不动点缩放。 */
    fun zoomAround(pivot: PointF, factor: Float): Viewport {
        val ns = scale * factor
        return Viewport(
            scale = ns,
            offsetX = pivot.x - (pivot.x - offsetX) * factor,
            offsetY = pivot.y - (pivot.y - offsetY) * factor,
        )
    }

    fun matrix(): Matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(offsetX, offsetY)
    }

    /**
     * 把缩放收进 [fitScale, fitScale * MAX_SCALE_FACTOR]，
     * 并保证图像不会被拖出视口：图像比视口大时贴边，比视口小时居中。
     */
    fun clamped(imageW: Int, imageH: Int, viewW: Int, viewH: Int, fitScale: Float): Viewport {
        val s = scale.coerceIn(fitScale, fitScale * MAX_SCALE_FACTOR)
        val w = imageW * s
        val h = imageH * s
        // 缩放被夹住时，围绕视口中心重算 offset，避免夹紧后画面跳动
        val k = if (abs(scale) < 1e-6f) 1f else s / scale
        var ox = viewW / 2f - (viewW / 2f - offsetX) * k
        var oy = viewH / 2f - (viewH / 2f - offsetY) * k
        ox = if (w <= viewW) (viewW - w) / 2f else ox.coerceIn(viewW - w, 0f)
        oy = if (h <= viewH) (viewH - h) / 2f else oy.coerceIn(viewH - h, 0f)
        return Viewport(s, ox, oy)
    }

    /** 双击：在「适配屏幕」与 fit 的 2 倍之间切换（spec §7.3）。 */
    fun doubleTapTarget(pivot: PointF, imageW: Int, imageH: Int, viewW: Int, viewH: Int): Viewport {
        val fit = fit(imageW, imageH, viewW, viewH)
        val zoomedIn = scale > fit.scale * 1.05f
        return if (zoomedIn) fit
        else zoomAround(pivot, DOUBLE_TAP_FACTOR).clamped(imageW, imageH, viewW, viewH, fit.scale)
    }

    companion object {
        const val MAX_SCALE_FACTOR = 8f
        const val DOUBLE_TAP_FACTOR = 2f

        fun fit(imageW: Int, imageH: Int, viewW: Int, viewH: Int): Viewport {
            if (imageW <= 0 || imageH <= 0 || viewW <= 0 || viewH <= 0) return Viewport(1f, 0f, 0f)
            val s = min(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
            return Viewport(s, (viewW - imageW * s) / 2f, (viewH - imageH * s) / 2f)
        }
    }
}
```

- [ ] **Step 4: 运行确认 Viewport 通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*ViewportTest*'
```

预期：10 个测试全部 PASS。

- [ ] **Step 5: 写 GestureRules 的失败测试**

`app/src/test/java/com/yomark/app/ui/canvas/GestureRulesTest.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GestureRulesTest {

    private fun item(id: String, l: Float, t: Float, r: Float, b: Float) = MaskItem(
        id, Quad.fromRect(RectF(l, t, r, b)),
        SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
    )

    @Test
    fun `movement below touch slop is a tap`() {
        assertThat(GestureRules.isDrag(3f, 4f, touchSlopPx = 8f)).isFalse()
    }

    @Test
    fun `movement beyond touch slop is a drag`() {
        assertThat(GestureRules.isDrag(9f, 0f, touchSlopPx = 8f)).isTrue()
    }

    @Test
    fun `a box thinner than the minimum short edge is discarded`() {
        val thin = Quad.fromRect(RectF(0f, 0f, 100f, 10f))
        assertThat(GestureRules.acceptsBox(thin, minShortEdgePx = 16f)).isFalse()
    }

    @Test
    fun `a box at the minimum short edge is kept`() {
        val ok = Quad.fromRect(RectF(0f, 0f, 100f, 16f))
        assertThat(GestureRules.acceptsBox(ok, minShortEdgePx = 16f)).isTrue()
    }

    @Test
    fun `quadFromDrag normalizes a drag made right-to-left and bottom-to-top`() {
        val q = GestureRules.quadFromDrag(PointF(100f, 80f), PointF(20f, 10f))
        assertThat(q.bounds()).isEqualTo(RectF(20f, 10f, 100f, 80f))
    }

    @Test
    fun `hitTest returns null when nothing is under the point`() {
        assertThat(GestureRules.hitTest(listOf(item("a", 0f, 0f, 10f, 10f)), PointF(50f, 50f))).isNull()
    }

    @Test
    fun `hitTest prefers the smallest item when boxes overlap`() {
        val big = item("big", 0f, 0f, 100f, 100f)
        val small = item("small", 40f, 40f, 60f, 60f)
        val hit = GestureRules.hitTest(listOf(big, small), PointF(50f, 50f))
        assertThat(hit?.candidateId).isEqualTo("small")
    }

    @Test
    fun `label threshold matches the spec`() {
        assertThat(GestureRules.LABEL_MIN_SCALE).isEqualTo(0.5f)
    }
}
```

- [ ] **Step 6: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*GestureRulesTest*'
```

预期：编译失败，`Unresolved reference: GestureRules`

- [ ] **Step 7: 实现 GestureRules**

`app/src/main/java/com/yomark/app/ui/canvas/GestureRules.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskItem
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手势的判定规则（spec §7.3）。全是纯函数，Compose 只负责喂事件。
 * 分工是绘图类 app 的通行做法：一指画、两指导航，不设模式切换按钮。
 */
object GestureRules {

    /** 类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片。 */
    const val LABEL_MIN_SCALE = 0.5f

    /** 手动框的短边下限（dp），防误触产生 1px 框。 */
    const val MIN_BOX_DP = 16f

    fun isDrag(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        hypot(dx, dy) > touchSlopPx

    fun acceptsBox(quad: Quad, minShortEdgePx: Float): Boolean =
        quad.shortEdge() >= minShortEdgePx

    /** 起点终点归一化成轴对齐四边形。手动框永远是正的。 */
    fun quadFromDrag(a: PointF, b: PointF): Quad = Quad.fromRect(
        RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    )

    /**
     * 命中测试：取面积最小的命中项。
     * 渲染时大块先画、小块盖在上面，用户点到的应该是他看到的那个。
     */
    fun hitTest(items: List<MaskItem>, imagePoint: PointF): MaskItem? =
        items.filter { it.quad.contains(imagePoint) }.minByOrNull { it.quad.area() }
}
```

- [ ] **Step 8: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*ViewportTest*' --tests '*GestureRulesTest*'
git add app/src/main/java/com/yomark/app/ui/canvas app/src/test/java/com/yomark/app/ui/canvas
git commit -m "feat: add viewport transform and pure gesture decision rules"
```

预期：18 个测试全部 PASS。

---

### Task 9: 渲染接口与实色块

**Files:**
- Create: `app/src/main/java/com/yomark/app/render/MaskRenderer.kt`
- Create: `app/src/main/java/com/yomark/app/render/SolidRenderer.kt`
- Create: `app/src/main/java/com/yomark/app/render/RendererRegistry.kt`
- Test: `app/src/test/java/com/yomark/app/render/SolidRendererTest.kt`

**Interfaces:**
- Consumes: `Quad`、`MaskStyle`、`MaskOptions`
- Produces:
  - `interface MaskRenderer { val style: MaskStyle; fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) }`
  - `class SolidRenderer : MaskRenderer`
  - `class RendererRegistry(renderers: List<MaskRenderer>)` + `operator fun get(style: MaskStyle): MaskRenderer`
  - `RendererRegistry.Companion.default(): RendererRegistry`

同一套渲染器既画预览也画导出——这是「预览所见即导出所得」的唯一保证。绘制路径以 **Quad 为裁剪区**，不是外接矩形（spec §8）。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/yomark/app/render/SolidRendererTest.kt`：

```kotlin
package com.yomark.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class SolidRendererTest {

    private fun whiteBitmap(w: Int = 100, h: Int = 100) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test
    fun `solid fill covers the quad completely and opaquely`() {
        val bmp = whiteBitmap()
        val canvas = Canvas(bmp)
        SolidRenderer().render(canvas, bmp, Quad.fromRect(RectF(20f, 20f, 60f, 60f)), MaskOptions())

        assertThat(bmp.getPixel(40, 40)).isEqualTo(Color.BLACK)
        assertThat(Color.alpha(bmp.getPixel(40, 40))).isEqualTo(255)
    }

    @Test
    fun `pixels outside the quad are untouched`() {
        val bmp = whiteBitmap()
        SolidRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 60f, 60f)), MaskOptions())
        assertThat(bmp.getPixel(5, 5)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(90, 90)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `a rotated quad does not fill its axis-aligned corners`() {
        val bmp = whiteBitmap()
        val diamond = Quad(PointF(50f, 10f), PointF(90f, 50f), PointF(50f, 90f), PointF(10f, 50f))
        SolidRenderer().render(Canvas(bmp), bmp, diamond, MaskOptions())

        assertThat(bmp.getPixel(50, 50)).isEqualTo(Color.BLACK)   // 菱形中心
        assertThat(bmp.getPixel(12, 12)).isEqualTo(Color.WHITE)   // 外接框的角，不该被填
    }

    @Test
    fun `custom solid color is honored`() {
        val bmp = whiteBitmap()
        SolidRenderer().render(
            Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 40f, 40f)),
            MaskOptions(solidColor = Color.RED),
        )
        assertThat(bmp.getPixel(25, 25)).isEqualTo(Color.RED)
    }

    @Test
    fun `registry returns the solid renderer for SOLID`() {
        assertThat(RendererRegistry.default()[MaskStyle.SOLID]).isInstanceOf(SolidRenderer::class.java)
    }

    @Test
    fun `registry falls back to solid for styles not yet implemented`() {
        // M1 只有实色块；后续里程碑补齐后这条会自然变成「返回对应渲染器」
        assertThat(RendererRegistry.default()[MaskStyle.EMOJI]).isNotNull()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*SolidRendererTest*'
```

预期：编译失败，`Unresolved reference: SolidRenderer`

- [ ] **Step 3: 实现渲染接口与实色块**

`app/src/main/java/com/yomark/app/render/MaskRenderer.kt`：

```kotlin
package com.yomark.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskStyle

/**
 * 渲染层（spec §4.3）。每种打码样式一个实现，样式与识别完全解耦。
 * 同一个实现既画预览也画导出——这是「所见即所得」的唯一保证。
 *
 * @param source 被打码的位图。像素化/模糊/抹除需要读它的原始像素；
 *               实色块与 Emoji 不需要，但签名统一。
 * @param quad   与 canvas 同一坐标系下的目标区域。
 */
interface MaskRenderer {
    val style: MaskStyle
    fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions)
}
```

`app/src/main/java/com/yomark/app/render/SolidRenderer.kt`：

```kotlin
package com.yomark.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskStyle

/** 实色块（默认）：沿 Quad 描路径填充不透明色。不可还原。 */
class SolidRenderer : MaskRenderer {
    override val style = MaskStyle.SOLID

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        paint.color = options.solidColor
        paint.alpha = 255                      // 不透明是安全属性，不接受 options 覆盖
        canvas.drawPath(quad.toPath(), paint)
    }
}
```

`app/src/main/java/com/yomark/app/render/RendererRegistry.kt`：

```kotlin
package com.yomark.app.render

import com.yomark.app.core.model.MaskStyle

/**
 * 样式 → 渲染器。尚未实现的样式回退到实色块——
 * 回退到「更安全」的那一侧，绝不回退到不遮蔽的样式。
 */
class RendererRegistry(renderers: List<MaskRenderer>) {

    private val byStyle = renderers.associateBy { it.style }
    private val fallback = byStyle[MaskStyle.SOLID]
        ?: error("RendererRegistry 必须包含 SOLID 渲染器")

    operator fun get(style: MaskStyle): MaskRenderer = byStyle[style] ?: fallback

    fun implemented(): Set<MaskStyle> = byStyle.keys

    companion object {
        /** M1 只有实色块。后续里程碑往这个列表里加实现。 */
        fun default() = RendererRegistry(listOf(SolidRenderer()))
    }
}
```

- [ ] **Step 4: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*SolidRendererTest*'
git add app/src/main/java/com/yomark/app/render app/src/test/java/com/yomark/app/render
git commit -m "feat: add MaskRenderer interface with quad-clipped solid renderer and registry"
```

预期：6 个测试全部 PASS。

---

### Task 10: 品牌水印与避让

**Files:**
- Create: `app/src/main/java/com/yomark/app/export/WatermarkDrawer.kt`
- Test: `app/src/test/java/com/yomark/app/export/WatermarkDrawerTest.kt`

**Interfaces:**
- Consumes: `MaskItem`、`MaskState`
- Produces:
  - `enum class WatermarkCorner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }`
  - `data class WatermarkPlacement(rect: RectF, corner: WatermarkCorner, outlined: Boolean, darkInk: Boolean)`
  - `class WatermarkDrawer(text: String = "有码 Yomark")`
  - `fun placement(canvasW: Int, canvasH: Int, maskedBounds: List<RectF>): WatermarkPlacement`
  - `fun draw(canvas: Canvas, source: Bitmap, maskedBounds: List<RectF>): WatermarkPlacement`

**硬约束（spec §9.3）：水印不得与任何 `MASKED` 区域相交。** 半透明水印压在实色块上，会让人以为那个遮罩本身也是半透明的、底下的东西还在——这直接损害产品的核心承诺。

规格：高度 = 短边 4%（下限 24px），边距 = 短边 3%，避让顺序 右下 → 左下 → 左上 → 右上，四角全被占则回右下并加不透明描边。落点区域偏亮时用半透明黑，否则半透明白。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/yomark/app/export/WatermarkDrawerTest.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkDrawerTest {

    private val drawer = WatermarkDrawer()

    private fun bitmap(w: Int, h: Int, color: Int = Color.WHITE) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `default corner is bottom right`() {
        val p = drawer.placement(1000, 2000, emptyList())
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_RIGHT)
        assertThat(p.outlined).isFalse()
    }

    @Test
    fun `height is four percent of the short edge with a 24px floor`() {
        assertThat(drawer.placement(1000, 2000, emptyList()).rect.height())
            .isWithin(0.5f).of(40f)                      // 1000 * 0.04
        assertThat(drawer.placement(200, 300, emptyList()).rect.height())
            .isWithin(0.5f).of(24f)                      // 200*0.04 = 8 → 下限 24
    }

    @Test
    fun `margin is three percent of the short edge`() {
        val p = drawer.placement(1000, 2000, emptyList())
        assertThat(1000f - p.rect.right).isWithin(0.5f).of(30f)
        assertThat(2000f - p.rect.bottom).isWithin(0.5f).of(30f)
    }

    @Test
    fun `an occupied bottom right corner moves the watermark to bottom left`() {
        val masked = listOf(RectF(600f, 1700f, 1000f, 2000f))
        val p = drawer.placement(1000, 2000, masked)
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_LEFT)
        assertThat(RectF(p.rect).intersect(masked[0])).isFalse()
    }

    @Test
    fun `both bottom corners occupied moves the watermark to top left`() {
        val masked = listOf(RectF(0f, 1700f, 1000f, 2000f))
        assertThat(drawer.placement(1000, 2000, masked).corner).isEqualTo(WatermarkCorner.TOP_LEFT)
    }

    @Test
    fun `three corners occupied moves the watermark to top right`() {
        val masked = listOf(
            RectF(0f, 1700f, 1000f, 2000f),      // 整条底边
            RectF(0f, 0f, 500f, 300f),           // 左上
        )
        assertThat(drawer.placement(1000, 2000, masked).corner).isEqualTo(WatermarkCorner.TOP_RIGHT)
    }

    @Test
    fun `all four corners occupied falls back to bottom right with an outline`() {
        val masked = listOf(RectF(0f, 0f, 1000f, 2000f))
        val p = drawer.placement(1000, 2000, masked)
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_RIGHT)
        assertThat(p.outlined).isTrue()
    }

    @Test
    fun `ink turns dark on a bright background`() {
        val bmp = bitmap(400, 400, Color.WHITE)
        assertThat(drawer.draw(Canvas(bmp), bmp, emptyList()).darkInk).isTrue()
    }

    @Test
    fun `ink turns light on a dark background`() {
        val bmp = bitmap(400, 400, Color.rgb(20, 20, 24))
        assertThat(drawer.draw(Canvas(bmp), bmp, emptyList()).darkInk).isFalse()
    }

    @Test
    fun `drawing actually changes pixels inside the placement`() {
        val bmp = bitmap(400, 400, Color.WHITE)
        val p = drawer.draw(Canvas(bmp), bmp, emptyList())
        val cx = p.rect.centerX().toInt()
        val cy = p.rect.centerY().toInt()
        var changed = false
        for (dx in -6..6) for (dy in -6..6) {
            if (bmp.getPixel((cx + dx).coerceIn(0, 399), (cy + dy).coerceIn(0, 399)) != Color.WHITE) changed = true
        }
        assertThat(changed).isTrue()
    }

    @Test
    fun `drawing never touches a masked region`() {
        val bmp = bitmap(600, 600, Color.WHITE)
        val masked = RectF(300f, 300f, 600f, 600f)
        Canvas(bmp).drawRect(masked, Paint().apply { color = Color.BLACK })

        drawer.draw(Canvas(bmp), bmp, listOf(masked))

        // 遮罩区内必须仍是纯黑：水印一个像素都不许压上去
        for (x in 310 until 590 step 20) for (y in 310 until 590 step 20) {
            assertThat(bmp.getPixel(x, y)).isEqualTo(Color.BLACK)
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*WatermarkDrawerTest*'
```

预期：编译失败，`Unresolved reference: WatermarkDrawer`

- [ ] **Step 3: 实现 WatermarkDrawer**

`app/src/main/java/com/yomark/app/export/WatermarkDrawer.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

enum class WatermarkCorner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

data class WatermarkPlacement(
    val rect: RectF,
    val corner: WatermarkCorner,
    val outlined: Boolean,
    val darkInk: Boolean,
)

/**
 * 免费导出的品牌水印（spec §9.3）。
 *
 * 硬约束：**水印不得与任何 MASKED 区域相交**。半透明水印压在实色块上，
 * 会让人以为那个遮罩本身也是半透明的、底下的东西还在——这直接损害核心承诺。
 *
 * 水印是营销手段，不是安全边界。用户裁掉它是预期内的，不做任何对抗。
 */
class WatermarkDrawer(private val text: String = "有码 Yomark") {

    fun placement(canvasW: Int, canvasH: Int, maskedBounds: List<RectF>): WatermarkPlacement =
        placement(canvasW, canvasH, maskedBounds, darkInk = true)

    private fun placement(
        canvasW: Int,
        canvasH: Int,
        maskedBounds: List<RectF>,
        darkInk: Boolean,
    ): WatermarkPlacement {
        val shortEdge = min(canvasW, canvasH).toFloat()
        val h = max(shortEdge * HEIGHT_RATIO, MIN_HEIGHT_PX)
        val margin = shortEdge * MARGIN_RATIO
        val w = measureWidth(h)

        for (corner in ORDER) {
            val r = rectFor(corner, canvasW.toFloat(), canvasH.toFloat(), w, h, margin)
            if (maskedBounds.none { RectF(r).intersect(it) }) {
                return WatermarkPlacement(r, corner, outlined = false, darkInk = darkInk)
            }
        }
        // 四角全被占：回到右下并加不透明描边保证可读
        val r = rectFor(WatermarkCorner.BOTTOM_RIGHT, canvasW.toFloat(), canvasH.toFloat(), w, h, margin)
        return WatermarkPlacement(r, WatermarkCorner.BOTTOM_RIGHT, outlined = true, darkInk = darkInk)
    }

    /** 绘制时机：所有遮罩绘制完成之后，编码之前。 */
    fun draw(canvas: Canvas, source: Bitmap, maskedBounds: List<RectF>): WatermarkPlacement {
        val probe = placement(source.width, source.height, maskedBounds, darkInk = true)
        val dark = isBright(source, probe.rect)
        val p = probe.copy(darkInk = dark)

        val h = p.rect.height()
        val glyph = RectF(p.rect.left, p.rect.top, p.rect.left + h, p.rect.bottom)
        val ink = if (dark) Color.argb(150, 0, 0, 0) else Color.argb(170, 255, 255, 255)
        val counterInk = if (dark) Color.argb(200, 255, 255, 255) else Color.argb(200, 0, 0, 0)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = counterInk
            strokeWidth = max(1f, h * 0.06f)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = h * 0.72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }

        // 图标：圆角方块上一条横杠，与启动图标同构
        val radius = h * 0.22f
        canvas.drawRoundRect(glyph, radius, radius, fill)
        val bar = RectF(
            glyph.left + h * 0.22f, glyph.centerY() - h * 0.09f,
            glyph.right - h * 0.22f, glyph.centerY() + h * 0.09f,
        )
        canvas.drawRoundRect(bar, h * 0.05f, h * 0.05f, Paint(fill).apply { color = counterInk })

        val baseline = p.rect.bottom - (h - textPaint.textSize) / 2f - textPaint.descent() * 0.6f
        val textX = glyph.right + h * GAP_RATIO
        if (p.outlined) {
            canvas.drawText(text, textX, baseline, Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, h * 0.10f)
                color = counterInk
                alpha = 255
            })
            canvas.drawRoundRect(glyph, radius, radius, stroke)
        }
        canvas.drawText(text, textX, baseline, textPaint)
        return p
    }

    private fun measureWidth(h: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * 0.72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        return h + h * GAP_RATIO + paint.measureText(text)
    }

    private fun rectFor(
        corner: WatermarkCorner,
        canvasW: Float,
        canvasH: Float,
        w: Float,
        h: Float,
        margin: Float,
    ): RectF = when (corner) {
        WatermarkCorner.BOTTOM_RIGHT -> RectF(canvasW - margin - w, canvasH - margin - h, canvasW - margin, canvasH - margin)
        WatermarkCorner.BOTTOM_LEFT -> RectF(margin, canvasH - margin - h, margin + w, canvasH - margin)
        WatermarkCorner.TOP_LEFT -> RectF(margin, margin, margin + w, margin + h)
        WatermarkCorner.TOP_RIGHT -> RectF(canvasW - margin - w, margin, canvasW - margin, margin + h)
    }

    /** 落点区域偏亮 → 用半透明黑；偏暗 → 用半透明白。 */
    private fun isBright(source: Bitmap, rect: RectF): Boolean {
        var sum = 0.0
        var n = 0
        val stepX = max(1, (rect.width() / 8f).toInt())
        val stepY = max(1, (rect.height() / 4f).toInt())
        var x = rect.left.toInt().coerceIn(0, source.width - 1)
        while (x < rect.right.toInt().coerceIn(0, source.width - 1)) {
            var y = rect.top.toInt().coerceIn(0, source.height - 1)
            while (y < rect.bottom.toInt().coerceIn(0, source.height - 1)) {
                val c = source.getPixel(x, y)
                sum += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
                n++
                y += stepY
            }
            x += stepX
        }
        return if (n == 0) true else sum / n > 140.0
    }

    private companion object {
        const val HEIGHT_RATIO = 0.04f
        const val MARGIN_RATIO = 0.03f
        const val MIN_HEIGHT_PX = 24f
        const val GAP_RATIO = 0.28f
        val ORDER = listOf(
            WatermarkCorner.BOTTOM_RIGHT,
            WatermarkCorner.BOTTOM_LEFT,
            WatermarkCorner.TOP_LEFT,
            WatermarkCorner.TOP_RIGHT,
        )
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*WatermarkDrawerTest*'
```

预期：11 个测试全部 PASS。

若 `drawing never touches a masked region` 失败，说明避让逻辑或 `intersect` 的用法出了问题——`RectF.intersect` 会**修改接收者**，所以上面统一用 `RectF(r).intersect(it)` 传副本。这是最容易踩的一个坑。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/yomark/app/export/WatermarkDrawer.kt app/src/test/java/com/yomark/app/export/WatermarkDrawerTest.kt
git commit -m "feat: add brand watermark with mandatory mask-avoidance and luminance-aware ink"
```

---

### Task 11: 导出管线

**Files:**
- Create: `app/src/main/java/com/yomark/app/export/ImageSink.kt`
- Create: `app/src/main/java/com/yomark/app/export/MediaStoreSink.kt`
- Create: `app/src/main/java/com/yomark/app/export/Exporter.kt`
- Test: `app/src/test/java/com/yomark/app/export/ExporterTest.kt`
- Test: `app/src/androidTest/java/com/yomark/app/export/MediaStoreSinkTest.kt`

**Interfaces:**
- Consumes: `SourceImageLoader`、`RendererRegistry`、`WatermarkDrawer`、`MaskPlan`
- Produces:
  - `interface ImageSink { suspend fun write(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, displayName: String, mimeType: String): Uri }`
  - `class MediaStoreSink(context: Context) : ImageSink`
  - `class Exporter(registry: RendererRegistry, watermark: WatermarkDrawer, sink: ImageSink)`
  - `suspend fun export(request: ExportRequest): ExportOutcome`
  - `data class ExportRequest(file: File, mimeType: String, plan: MaskPlan, analysisScale: Float, applyWatermark: Boolean)`
  - `sealed interface ExportOutcome { data class Success(uri: Uri, width: Int, height: Int, downscaled: Boolean); data class Failure(cause: Throwable) }`
  - `internal fun Exporter.renderToBitmap(...)`（供测试直接调用）

**四条硬性要求（spec §9.2）**，每一条都对应下面一个测试：
1. 在原图分辨率上重绘，不是把预览图放大；
2. 重新编码输出，绝不保留任何可分离的图层或 alpha 通道；
3. 不携带任何 EXIF——**断言 `ExifInterface` 读不到 GPS、设备型号、时间戳**，做成自动化测试，不靠「我们没写所以肯定没有」；
4. 写入用 `IS_PENDING` 事务。

- [ ] **Step 1: 写 Exporter 的失败测试**

`app/src/test/java/com/yomark/app/export/ExporterTest.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 记下最后一次写出的位图与编码参数，替代真实的 MediaStore。 */
    private class FakeSink : ImageSink {
        var bitmap: Bitmap? = null
        var format: Bitmap.CompressFormat? = null
        var quality: Int = -1
        var mimeType: String? = null
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri {
            this.bitmap = bitmap; this.format = format
            this.quality = quality; this.mimeType = mimeType
            return Uri.parse("content://fake/1")
        }
    }

    private fun writeSource(name: String, w: Int, h: Int, png: Boolean = false): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(RectF(0f, 0f, w / 2f, h / 2f), Paint().apply { color = Color.RED })
        }
        val f = tmp.newFile(name)
        f.outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it)
        }
        // 塞进一些必须被剥掉的元数据
        if (!png) ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MODEL, "Pixel-Test")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:09:02 10:00:00")
            setLatLong(37.4220, -122.0841)
            saveAttributes()
        }
        bmp.recycle()
        return f
    }

    private fun planWith(vararg items: MaskItem) = MaskPlan(items.toList(), MaskStyle.SOLID, MaskOptions())

    private fun maskedItem(l: Float, t: Float, r: Float, b: Float, state: MaskState = MaskState.MASKED) =
        MaskItem("i-$l", Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL, state)

    private fun exporter(sink: ImageSink) =
        Exporter(RendererRegistry.default(), WatermarkDrawer(), sink)

    @Test
    fun `output keeps the original resolution not the analysis resolution`() = runTest {
        val sink = FakeSink()
        val src = writeSource("big.jpg", 4000, 3000)
        val out = exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(), analysisScale = 0.512f, applyWatermark = false)
        )
        assertThat(out).isInstanceOf(ExportOutcome.Success::class.java)
        assertThat(sink.bitmap!!.width).isEqualTo(4000)
        assertThat(sink.bitmap!!.height).isEqualTo(3000)
    }

    @Test
    fun `mask quads are scaled back to original coordinates`() = runTest {
        val sink = FakeSink()
        val src = writeSource("scale.jpg", 2000, 2000)
        // 分析图是 500x500（scale=0.25），遮罩画在 (100,100)-(200,200)
        // 反算后应覆盖原图的 (400,400)-(800,800)
        exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(maskedItem(100f, 100f, 200f, 200f)), 0.25f, false)
        )
        val bmp = sink.bitmap!!
        assertThat(bmp.getPixel(600, 600)).isEqualTo(Color.BLACK)
        assertThat(bmp.getPixel(300, 300)).isNotEqualTo(Color.BLACK)
        assertThat(bmp.getPixel(900, 900)).isNotEqualTo(Color.BLACK)
    }

    @Test
    fun `outlined items are not drawn`() = runTest {
        val sink = FakeSink()
        val src = writeSource("outlined.jpg", 400, 400)
        exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(maskedItem(50f, 50f, 150f, 150f, MaskState.OUTLINED)), 1f, false)
        )
        assertThat(sink.bitmap!!.getPixel(100, 100)).isNotEqualTo(Color.BLACK)
    }

    @Test
    fun `png source stays png and jpeg source becomes jpeg q95`() = runTest {
        val jpegSink = FakeSink()
        exporter(jpegSink).export(ExportRequest(writeSource("a.jpg", 200, 200), "image/jpeg", planWith(), 1f, false))
        assertThat(jpegSink.format).isEqualTo(Bitmap.CompressFormat.JPEG)
        assertThat(jpegSink.quality).isEqualTo(95)
        assertThat(jpegSink.mimeType).isEqualTo("image/jpeg")

        val pngSink = FakeSink()
        exporter(pngSink).export(ExportRequest(writeSource("a.png", 200, 200, png = true), "image/png", planWith(), 1f, false))
        assertThat(pngSink.format).isEqualTo(Bitmap.CompressFormat.PNG)
        assertThat(pngSink.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `encoded output carries no exif at all`() = runTest {
        val sink = FakeSink()
        val src = writeSource("exif.jpg", 300, 300)
        exporter(sink).export(ExportRequest(src, "image/jpeg", planWith(), 1f, false))

        val bytes = ByteArrayOutputStream().also { sink.bitmap!!.compress(sink.format!!, sink.quality, it) }.toByteArray()
        val exif = ExifInterface(bytes.inputStream())

        assertThat(exif.latLong).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_ORIENTATION)).isNull()
    }

    @Test
    fun `watermark is drawn only when requested`() = runTest {
        val withMark = FakeSink()
        exporter(withMark).export(ExportRequest(writeSource("w1.jpg", 600, 600), "image/jpeg", planWith(), 1f, true))
        val without = FakeSink()
        exporter(without).export(ExportRequest(writeSource("w2.jpg", 600, 600), "image/jpeg", planWith(), 1f, false))

        // 右下角水印落点：有水印那张与无水印那张必然有像素差异
        var differs = false
        for (x in 520 until 590 step 5) for (y in 520 until 590 step 5) {
            if (withMark.bitmap!!.getPixel(x, y) != without.bitmap!!.getPixel(x, y)) differs = true
        }
        assertThat(differs).isTrue()
    }

    @Test
    fun `output above 32 megapixels reports downscaling`() = runTest {
        val sink = FakeSink()
        val src = writeSource("huge.jpg", 8000, 5000)          // 40 MP
        val out = exporter(sink).export(ExportRequest(src, "image/jpeg", planWith(), 1f, false)) as ExportOutcome.Success
        assertThat(out.downscaled).isTrue()
        assertThat(out.width.toLong() * out.height).isAtMost(32_000_000L)
    }

    @Test
    fun `a broken source file yields Failure rather than throwing`() = runTest {
        val broken = tmp.newFile("broken.jpg").apply { writeText("not an image") }
        val out = exporter(FakeSink()).export(ExportRequest(broken, "image/jpeg", planWith(), 1f, false))
        assertThat(out).isInstanceOf(ExportOutcome.Failure::class.java)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*ExporterTest*'
```

预期：编译失败，`Unresolved reference: Exporter`

- [ ] **Step 3: 实现 ImageSink 与 Exporter**

`app/src/main/java/com/yomark/app/export/ImageSink.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.net.Uri

/** 导出的写出目的地。抽出来是为了让 Exporter 能在 JVM 单测里跑完整管线。 */
interface ImageSink {
    suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri
}
```

`app/src/main/java/com/yomark/app/export/Exporter.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import com.yomark.app.core.image.SourceImageLoader
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.render.RendererRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportRequest(
    val file: File,
    val mimeType: String,
    val plan: MaskPlan,
    /** SourceImage.scale：分析图边长 / 原图边长。遮罩按 1/scale 反算。 */
    val analysisScale: Float,
    val applyWatermark: Boolean,
)

sealed interface ExportOutcome {
    data class Success(val uri: Uri, val width: Int, val height: Int, val downscaled: Boolean) : ExportOutcome
    data class Failure(val cause: Throwable) : ExportOutcome
}

/**
 * 导出管线（spec §9.1）：
 *   原图全分辨率解码 + EXIF 转正 → 逐项渲染 MASKED → 品牌水印 → 重编码 → 写出
 *
 * 不携带任何 EXIF 由「重编码」天然保证——Bitmap.compress 不写 EXIF。
 * 但这条由 ExporterTest 的断言守，不靠推断。
 */
class Exporter(
    private val registry: RendererRegistry,
    private val watermark: WatermarkDrawer,
    private val sink: ImageSink,
) {

    suspend fun export(request: ExportRequest): ExportOutcome = withContext(Dispatchers.Default) {
        runCatching {
            val decoded = SourceImageLoader.loadForExport(request.file)
            val bitmap = decoded.bitmap
            val canvas = Canvas(bitmap)

            // 分析坐标 → 导出坐标。导出图本身可能因 32MP 上限被降过，所以要再乘一次。
            val exportScale = bitmap.width.toFloat() / (bitmap.width / decoded.bitmapRatio(request))
            val factor = exportFactor(decoded.width, request)

            val maskedBounds = ArrayList<RectF>()
            request.plan.items
                .filter { it.state == MaskState.MASKED }
                .forEach { item ->
                    val quad = item.quad.scaled(factor)
                    registry[request.plan.style].render(canvas, bitmap, quad, request.plan.options)
                    maskedBounds += quad.bounds()
                }

            if (request.applyWatermark) watermark.draw(canvas, bitmap, maskedBounds)

            val png = request.mimeType.equals("image/png", ignoreCase = true)
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mime = if (png) "image/png" else "image/jpeg"
            val name = "YOMARK_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                if (png) ".png" else ".jpg"

            val uri = sink.write(bitmap, format, if (png) 100 else JPEG_QUALITY, name, mime)
            ExportOutcome.Success(uri, bitmap.width, bitmap.height, decoded.downscaled)
        }.getOrElse { ExportOutcome.Failure(it) }
    }

    private companion object { const val JPEG_QUALITY = 95 }
}
```

**注意**：上面 `exportScale` / `bitmapRatio` 那两行是**错的占位**，故意留给你在下一步删掉——正确的换算只需要一个量。删掉那两行，改成：

```kotlin
            // 分析图坐标 → 导出图坐标：
            //   分析坐标 * (1/analysisScale) = 转正后的原图坐标
            //   原图坐标 * (导出图宽 / 原图宽) = 导出图坐标
            // 两者合成一个系数。原图宽从解码信息里拿：downscaled 时导出图比原图小。
            val originalWidth = (bitmap.width * decoded.downsampleFactor).toInt()
            val factor = (1f / request.analysisScale) * (bitmap.width.toFloat() / originalWidth)
```

为此给 `ExportBitmap` 补一个字段。修改 `core/image/SourceImage.kt`：

```kotlin
data class ExportBitmap(
    val bitmap: Bitmap,
    val downscaled: Boolean,
    val width: Int,
    val height: Int,
    /** 导出解码用的 inSampleSize：原图宽 = width * downsampleFactor。 */
    val downsampleFactor: Int = 1,
)
```

并在 `SourceImageLoader.loadForExport` 的返回里带上 `downsampleFactor = sample`。

于是 `Exporter.export` 里的换算就是这三行：

```kotlin
            val originalWidth = bitmap.width * decoded.downsampleFactor
            val factor = (1f / request.analysisScale) * (bitmap.width.toFloat() / originalWidth)
```

即 `factor = 1f / (request.analysisScale * decoded.downsampleFactor)`。用后者，一行写完。

- [ ] **Step 4: 修正换算并跑通测试**

把 `Exporter.export` 里的坐标换算改成：

```kotlin
            // 分析坐标 → 导出坐标。导出图可能因 32MP 上限又降过一次，两个系数合成一个。
            val factor = 1f / (request.analysisScale * decoded.downsampleFactor)
```

删掉 `exportScale` 与对 `bitmapRatio` 的调用。然后：

```bash
./gradlew :app:testDebugUnitTest --tests '*ExporterTest*'
```

预期：8 个测试全部 PASS。

- [ ] **Step 5: 实现 MediaStoreSink**

`app/src/main/java/com/yomark/app/export/MediaStoreSink.kt`：

```kotlin
package com.yomark.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 写入相册（spec §9.1 第 5 步）。
 *
 * 全程 IS_PENDING 事务：写入期间对相册不可见，避免半成品被扫到。
 * minSdk 29 起写自己创建的媒体文件不需要任何权限——这是 minSdk 定在 29 的原因。
 */
class MediaStoreSink(private val context: Context) : ImageSink {

    override suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Yomark")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore.insert 返回 null")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(format, quality, out)) throw IOException("编码失败")
            } ?: throw IOException("无法打开输出流：$uri")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)     // 事务失败：不留半成品
            throw t
        }
    }
}
```

- [ ] **Step 6: 写 MediaStoreSink 的 instrumented 测试**

`app/src/androidTest/java/com/yomark/app/export/MediaStoreSinkTest.kt`：

```kotlin
package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreSinkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writes_a_visible_non_pending_image_without_any_permission_prompt() = runTest {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val uri = MediaStoreSink(context).write(bmp, Bitmap.CompressFormat.JPEG, 95, "yomark-test.jpg", "image/jpeg")

        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun written_file_has_no_exif_metadata() = runTest {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val uri = MediaStoreSink(context).write(bmp, Bitmap.CompressFormat.JPEG, 95, "yomark-exif.jpg", "image/jpeg")

        context.contentResolver.openInputStream(uri)!!.use { input ->
            val exif = ExifInterface(input)
            assertThat(exif.latLong).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME)).isNull()
        }
        context.contentResolver.delete(uri, null, null)
    }
}
```

- [ ] **Step 7: 在模拟器上运行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MediaStoreSinkTest*'
```

预期：2 个测试 PASS，且**不出现任何权限对话框**。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/yomark/app/export app/src/main/java/com/yomark/app/core/image/SourceImage.kt app/src/main/java/com/yomark/app/core/image/SourceImageLoader.kt app/src/test/java/com/yomark/app/export app/src/androidTest/java/com/yomark/app/export
git commit -m "feat: export at original resolution with re-encode, zero EXIF and IS_PENDING write"
```

---

### Task 12: EditorViewModel

**Files:**
- Create: `app/src/main/java/com/yomark/app/ui/EditorUiState.kt`
- Create: `app/src/main/java/com/yomark/app/ui/EditorViewModel.kt`
- Test: `app/src/test/java/com/yomark/app/ui/EditorViewModelTest.kt`

**Interfaces:**
- Consumes: `ImageIntake`、`SourceImageLoader`、`Exporter`、`UndoStack`、`GestureRules`
- Produces:
  - `data class EditorUiState(image, plan, loading, exporting, selectedManualId, pendingDialogVisible, canUndo, canRedo, message)`
  - `sealed interface EditorMessage { data class Exported(uri, downscaled, width, height); data class Error(text: String) }`
  - `class EditorViewModel(intake, exporter, ioDispatcher)`
  - 动作：`onImageChosen(Uri)`、`onTap(PointF)`、`onManualBox(Quad)`、`onLongPress(PointF)`、`deleteSelected()`、`clearSelection()`、`undo()`、`redo()`、`setStyle(MaskStyle)`、`requestExport(applyWatermark: Boolean)`、`confirmMaskAllAndExport(...)`、`confirmExportAnyway(...)`、`dismissDialog()`、`consumeMessage()`

**导出拦截的判定逻辑现在就写**（spec §7.4）。M1 没有识别，`pendingCount` 恒为 0，拦截不会触发；但判定必须在这里有测试守着，等计划 04 接上对话框 UI 时不会漏。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/yomark/app/ui/EditorViewModelTest.kt`：

```kotlin
package com.yomark.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.export.Exporter
import com.yomark.app.export.ImageSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private class RecordingSink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://fake/$writes") }
    }

    private lateinit var sink: RecordingSink

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): EditorViewModel {
        sink = RecordingSink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            ioDispatcher = dispatcher,
        )
    }

    private fun sampleUri(name: String = "vm.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun manual(id: String, l: Float, t: Float, r: Float, b: Float, state: MaskState = MaskState.MASKED) =
        MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL, state)

    @Test
    fun `loading an image populates state and clears history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri())
        advanceUntilIdle()

        assertThat(vm.state.value.image).isNotNull()
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `drawing a manual box adds a masked item`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))

        val item = vm.state.value.plan.items.single()
        assertThat(item.state).isEqualTo(MaskState.MASKED)
        assertThat(item.kind).isEqualTo(SensitiveKind.MANUAL)
        assertThat(item.source).isEqualTo(DetectorSource.MANUAL)
    }

    @Test
    fun `tapping a mask toggles it to outlined`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))

        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.OUTLINED)
    }

    @Test
    fun `tapping empty space clears the selection`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onLongPress(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isNotNull()

        vm.onTap(PointF(300f, 300f))
        assertThat(vm.state.value.selectedManualId).isNull()
    }

    @Test
    fun `undo restores the plan before the last box`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onManualBox(Quad.fromRect(RectF(120f, 120f, 200f, 200f)))
        assertThat(vm.state.value.plan.items).hasSize(2)

        vm.undo()
        assertThat(vm.state.value.plan.items).hasSize(1)
        assertThat(vm.state.value.canRedo).isTrue()

        vm.redo()
        assertThat(vm.state.value.plan.items).hasSize(2)
    }

    @Test
    fun `deleting works for a manual box and is a no-op for a rule candidate`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onLongPress(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).isEmpty()

        // 塞进一个规则候选，长按选中后删不掉
        vm.replacePlanForTest(vm.state.value.plan.add(
            manual("rule", 10f, 10f, 90f, 90f).copy(source = DetectorSource.RULE, kind = SensitiveKind.URL)
        ))
        vm.onLongPress(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).hasSize(1)
    }

    @Test
    fun `changing style is undoable and global`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.setStyle(MaskStyle.PIXELATE)
        assertThat(vm.state.value.plan.style).isEqualTo(MaskStyle.PIXELATE)
        vm.undo()
        assertThat(vm.state.value.plan.style).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `export with no pending items writes immediately`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.requestExport(applyWatermark = true)
        advanceUntilIdle()

        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Exported::class.java)
    }

    @Test
    fun `export with pending items opens the interception dialog and writes nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))

        vm.requestExport(applyWatermark = true)
        advanceUntilIdle()

        assertThat(vm.state.value.pendingDialogVisible).isTrue()
        assertThat(sink.writes).isEqualTo(0)
    }

    @Test
    fun `mask all then export clears pending and writes once`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))
        vm.requestExport(applyWatermark = true); advanceUntilIdle()

        vm.confirmMaskAllAndExport(applyWatermark = true); advanceUntilIdle()

        assertThat(vm.state.value.plan.pendingCount).isEqualTo(0)
        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test
    fun `export anyway writes without masking the pending items`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))
        vm.requestExport(applyWatermark = true); advanceUntilIdle()

        vm.confirmExportAnyway(applyWatermark = true); advanceUntilIdle()

        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test
    fun `loading a second image clears the undo history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("one.jpg")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        assertThat(vm.state.value.canUndo).isTrue()

        vm.onImageChosen(sampleUri("two.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
        assertThat(vm.state.value.plan.items).isEmpty()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*EditorViewModelTest*'
```

预期：编译失败，`Unresolved reference: EditorViewModel`

- [ ] **Step 3: 实现状态与 ViewModel**

`app/src/main/java/com/yomark/app/ui/EditorUiState.kt`：

```kotlin
package com.yomark.app.ui

import android.net.Uri
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.MaskPlan

sealed interface EditorMessage {
    data class Exported(val uri: Uri, val downscaled: Boolean, val width: Int, val height: Int) : EditorMessage
    data class Error(val text: String) : EditorMessage
}

data class EditorUiState(
    val image: SourceImage? = null,
    val plan: MaskPlan = MaskPlan.empty(),
    val loading: Boolean = false,
    val exporting: Boolean = false,
    /** 长按手动框进入的选中态：出现四角手柄与删除按钮。 */
    val selectedManualId: String? = null,
    val pendingDialogVisible: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: EditorMessage? = null,
)
```

`app/src/main/java/com/yomark/app/ui/EditorViewModel.kt`：

```kotlin
package com.yomark.app.ui

import android.graphics.PointF
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.image.IntakeResult
import com.yomark.app.core.image.SourceImageLoader
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.export.ExportOutcome
import com.yomark.app.export.ExportRequest
import com.yomark.app.export.Exporter
import com.yomark.app.ui.canvas.GestureRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class EditorViewModel(
    private val intake: ImageIntake,
    private val exporter: Exporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val undoStack = UndoStack()
    private var intakeResult: IntakeResult? = null

    // ---------- 载入 ----------

    fun onImageChosen(uri: Uri) {
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            runCatching {
                val result = withContext(ioDispatcher) { intake.copyToPrivate(uri) }
                val image = SourceImageLoader.loadForAnalysis(result.file, result.mimeType)
                result to image
            }.onSuccess { (result, image) ->
                intakeResult = result
                undoStack.clear()                     // 换图时两个栈都清空
                _state.value = EditorUiState(image = image, plan = MaskPlan.empty(_state.value.plan.style))
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    message = EditorMessage.Error("无法打开这张图片"),
                )
            }
        }
    }

    // ---------- 编辑 ----------

    fun onTap(imagePoint: PointF) {
        val plan = _state.value.plan
        val hit = GestureRules.hitTest(plan.items, imagePoint)
        if (hit == null) {
            _state.value = _state.value.copy(selectedManualId = null)
            return
        }
        mutate { it.toggle(hit.candidateId) }
    }

    fun onManualBox(quadInImageSpace: Quad) {
        val item = MaskItem(
            candidateId = "manual-${UUID.randomUUID()}",
            quad = quadInImageSpace,
            kind = SensitiveKind.MANUAL,
            source = DetectorSource.MANUAL,
            state = MaskState.MASKED,          // 落笔即打码
        )
        mutate { it.add(item) }
    }

    fun onLongPress(imagePoint: PointF) {
        val hit = GestureRules.hitTest(_state.value.plan.items, imagePoint)
        _state.value = _state.value.copy(selectedManualId = hit?.candidateId)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedManualId = null)
    }

    /** 只有手动框删得掉；MaskPlan.remove 自己会拦住其余来源。 */
    fun deleteSelected() {
        val id = _state.value.selectedManualId ?: return
        mutate { it.remove(id) }
        _state.value = _state.value.copy(selectedManualId = null)
    }

    fun moveSelected(quad: Quad) {
        val id = _state.value.selectedManualId ?: return
        val item = _state.value.plan.find(id) ?: return
        mutate { it.replace(item.copy(quad = quad)) }
    }

    fun setStyle(style: MaskStyle) = mutate { it.copy(style = style) }

    fun undo() {
        val restored = undoStack.undo(_state.value.plan) ?: return
        _state.value = _state.value.copy(plan = restored).withHistoryFlags()
    }

    fun redo() {
        val restored = undoStack.redo(_state.value.plan) ?: return
        _state.value = _state.value.copy(plan = restored).withHistoryFlags()
    }

    // ---------- 导出 ----------

    /**
     * 导出拦截（spec §7.4）：pendingCount > 0 时**无例外**先弹对话框。
     * 这是分层默认的唯一安全网，不提供「不再提示」。
     */
    fun requestExport(applyWatermark: Boolean) {
        if (_state.value.plan.pendingCount > 0) {
            _state.value = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmMaskAllAndExport(applyWatermark: Boolean) {
        mutate { it.maskAll() }
        _state.value = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun confirmExportAnyway(applyWatermark: Boolean) {
        _state.value = _state.value.copy(pendingDialogVisible = false)
        runExport(_state.value.plan, applyWatermark)
    }

    fun dismissDialog() {
        _state.value = _state.value.copy(pendingDialogVisible = false)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun runExport(plan: MaskPlan, applyWatermark: Boolean) {
        val image = _state.value.image ?: return
        val source = intakeResult ?: return
        _state.value = _state.value.copy(exporting = true)
        viewModelScope.launch {
            val outcome = exporter.export(
                ExportRequest(
                    file = source.file,
                    mimeType = source.mimeType,
                    plan = plan,
                    analysisScale = image.scale,
                    applyWatermark = applyWatermark,
                )
            )
            _state.value = when (outcome) {
                is ExportOutcome.Success -> _state.value.copy(
                    exporting = false,
                    message = EditorMessage.Exported(outcome.uri, outcome.downscaled, outcome.width, outcome.height),
                )
                is ExportOutcome.Failure -> _state.value.copy(
                    exporting = false,
                    message = EditorMessage.Error("导出失败，请重试"),
                )
            }
        }
    }

    override fun onCleared() {
        intake.clear()          // 私有副本在 Activity 销毁时删除
        super.onCleared()
    }

    // ---------- 内部 ----------

    /** 所有改动都先把当前 plan 压进撤销栈，再替换。 */
    private inline fun mutate(block: (MaskPlan) -> MaskPlan) {
        val current = _state.value.plan
        val next = block(current)
        if (next == current) return
        undoStack.push(current)
        _state.value = _state.value.copy(plan = next).withHistoryFlags()
    }

    private fun EditorUiState.withHistoryFlags() =
        copy(canUndo = undoStack.canUndo, canRedo = undoStack.canRedo)

    @VisibleForTesting
    fun replacePlanForTest(plan: MaskPlan) {
        _state.value = _state.value.copy(plan = plan)
    }
}
```

`MaskPlan.empty` 需要接受样式参数——计划 01 已按 `fun empty(style: MaskStyle = MaskStyle.SOLID)` 定义，直接用。

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*EditorViewModelTest*'
```

预期：12 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/yomark/app/ui/EditorUiState.kt app/src/main/java/com/yomark/app/ui/EditorViewModel.kt app/src/test/java/com/yomark/app/ui/EditorViewModelTest.kt
git commit -m "feat: add editor view model with undo-aware mutations and export interception check"
```

---

### Task 13: Compose 画布、编辑器外壳与 Activity 入口

**Files:**
- Create: `app/src/main/java/com/yomark/app/ui/canvas/ImageCanvas.kt`
- Create: `app/src/main/java/com/yomark/app/ui/components/EditorChrome.kt`
- Create: `app/src/main/java/com/yomark/app/ui/EditorScreen.kt`
- Modify: `app/src/main/java/com/yomark/app/ui/EditorActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `EditorViewModel`、`EditorUiState`、`Viewport`、`GestureRules`、`RendererRegistry`
- Produces:
  - `@Composable fun ImageCanvas(state: EditorUiState, registry: RendererRegistry, onTap, onDoubleTap, onLongPress, onManualBox, modifier)`
  - `@Composable fun EditorTopBar(canUndo, canRedo, onUndo, onRedo, onClose)`
  - `@Composable fun EditorBottomBar(style, implemented, onStyleChange, onExport, exporting)`
  - `@Composable fun EditorScreen(vm: EditorViewModel, onPickImage: () -> Unit, onClose: () -> Unit)`

**手势分工（spec §7.3）：一指画、两指导航。** 不设模式切换按钮，也避免了「长按起手」方案里想平移时手指停顿一下就误画框的问题。

**冷启动直接拉 Photo Picker（spec §7.1）。** 用户点图标 → 立刻是一屏照片网格 → 选中即进编辑器。用户在 Picker 里按返回 = 退出 app，这是正确行为不是 bug：这个 app 没有「主页」这个概念。

- [ ] **Step 1: 实现画布**

`app/src/main/java/com/yomark/app/ui/canvas/ImageCanvas.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskState
import com.yomark.app.render.RendererRegistry
import com.yomark.app.ui.EditorUiState
import kotlin.math.abs

/**
 * 画布：图像 + 遮罩 + 叠层 + 手势。
 *
 * 遮罩用与导出**同一套** MaskRenderer 绘制，保证所见即所得。
 * 手势分工：一指画框、两指缩放平移、双击切换适配/放大、长按手动框进选中态。
 */
@Composable
fun ImageCanvas(
    state: EditorUiState,
    registry: RendererRegistry,
    onTap: (PointF) -> Unit,
    onDoubleTap: (PointF, Int, Int) -> Unit,
    onLongPress: (PointF) -> Unit,
    onManualBox: (Quad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = state.image ?: return
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val minBoxPx = with(density) { GestureRules.MIN_BOX_DP.dp.toPx() }

    var viewSize by remember { mutableStateOf(0 to 0) }
    var viewport by remember(image) { mutableStateOf(Viewport(1f, 0f, 0f)) }
    var draftQuad by remember { mutableStateOf<Quad?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var start = down.position
                    var last = down.position
                    var totalDx = 0f
                    var totalDy = 0f
                    var pointerCount = 1
                    var mode = Mode.UNDECIDED
                    val downTime = System.currentTimeMillis()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        pointerCount = maxOf(pointerCount, pressed.size)

                        if (pressed.size >= 2) {
                            // 两指：导航。已经开始画的草稿作废——用户改主意了。
                            mode = Mode.NAVIGATE
                            draftQuad = null
                            val a = pressed[0]; val b = pressed[1]
                            val prevCentroid = Offset(
                                (a.previousPosition.x + b.previousPosition.x) / 2f,
                                (a.previousPosition.y + b.previousPosition.y) / 2f,
                            )
                            val centroid = Offset(
                                (a.position.x + b.position.x) / 2f,
                                (a.position.y + b.position.y) / 2f,
                            )
                            val prevSpan = (a.previousPosition - b.previousPosition).getDistance()
                            val span = (a.position - b.position).getDistance()
                            val zoom = if (prevSpan > 1f) span / prevSpan else 1f
                            val fit = Viewport.fit(image.width, image.height, viewSize.first, viewSize.second)
                            viewport = viewport
                                .zoomAround(PointF(centroid.x, centroid.y), zoom)
                                .pan(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                                .clamped(image.width, image.height, viewSize.first, viewSize.second, fit.scale)
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val c = pressed.first()
                            totalDx = c.position.x - start.x
                            totalDy = c.position.y - start.y
                            if (mode == Mode.UNDECIDED && GestureRules.isDrag(totalDx, totalDy, touchSlopPx)) {
                                mode = Mode.DRAW
                            }
                            if (mode == Mode.DRAW) {
                                draftQuad = GestureRules.quadFromDrag(
                                    viewport.screenToImage(PointF(start.x, start.y)),
                                    viewport.screenToImage(PointF(c.position.x, c.position.y)),
                                )
                                if (c.positionChanged()) c.consume()
                            }
                            last = c.position
                        }

                        if (event.changes.all { !it.pressed }) break
                    }

                    val duration = System.currentTimeMillis() - downTime
                    when {
                        mode == Mode.DRAW -> {
                            val q = draftQuad
                            draftQuad = null
                            val minInImage = minBoxPx / viewport.scale
                            if (q != null && GestureRules.acceptsBox(q, minInImage)) onManualBox(q)
                        }
                        mode == Mode.UNDECIDED && pointerCount == 1 -> {
                            val p = viewport.screenToImage(PointF(last.x, last.y))
                            if (duration >= LONG_PRESS_MS) onLongPress(p) else onTap(p)
                        }
                    }
                }
            }
    ) {
        viewSize = size.width.toInt() to size.height.toInt()
        if (viewport.scale == 1f && viewport.offsetX == 0f && viewport.offsetY == 0f) {
            viewport = Viewport.fit(image.width, image.height, viewSize.first, viewSize.second)
        }

        drawIntoCanvas { compose ->
            val canvas = compose.nativeCanvas
            val save = canvas.save()
            canvas.concat(viewport.matrix())

            canvas.drawBitmap(image.bitmap, 0f, 0f, null)

            // 面积大的先画，小的盖在上面 —— 与 GestureRules.hitTest 的「取最小」互为对应
            val sorted = state.plan.items.sortedByDescending { it.quad.area() }
            sorted.filter { it.state == MaskState.MASKED }.forEach {
                registry[state.plan.style].render(canvas, image.bitmap, it.quad, state.plan.options)
            }
            sorted.filter { it.state == MaskState.OUTLINED }.forEach {
                canvas.drawPath(it.quad.toPath(), outlinePaint(viewport.scale))
            }
            state.selectedManualId?.let { id ->
                state.plan.find(id)?.let { canvas.drawPath(it.quad.toPath(), selectionPaint(viewport.scale)) }
            }
            draftQuad?.let { canvas.drawPath(it.toPath(), draftPaint(viewport.scale)) }

            canvas.restoreToCount(save)
        }
    }
}

private enum class Mode { UNDECIDED, DRAW, NAVIGATE }
private const val LONG_PRESS_MS = 450L

/** 2dp 虚线框（琥珀色）—— 圈出未打码的外观（spec §7.2）。线宽按缩放反算，保持视觉恒定。 */
private fun outlinePaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
    strokeWidth = 4f / scale
    pathEffect = DashPathEffect(floatArrayOf(12f / scale, 8f / scale), 0f)
}

private fun selectionPaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.WHITE
    strokeWidth = 3f / scale
}

private fun draftPaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.argb(200, 255, 255, 255)
    strokeWidth = 3f / scale
    pathEffect = DashPathEffect(floatArrayOf(10f / scale, 6f / scale), 0f)
}
```

需要额外 import `androidx.compose.ui.unit.dp`。

**双击与选中态手柄留到 Task 14**：这一步先把「一指画、两指导航、点击切换」跑通，`onDoubleTap` 参数先接成空实现。Task 14 会把它连上 `Viewport.doubleTapTarget`，并补上长按后的四角手柄。

- [ ] **Step 2: 实现顶栏与底栏**

`app/src/main/java/com/yomark/app/ui/components/EditorChrome.kt`：

```kotlin
package com.yomark.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yomark.app.core.model.MaskStyle

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, "撤销") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, "重做") }
        Row(Modifier.weight(1f)) {}
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
    }
}

@Composable
fun EditorBottomBar(
    style: MaskStyle,
    implemented: Set<MaskStyle>,
    onStyleChange: (MaskStyle) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // M1 只有实色块；M4 补齐后这里自然列出全部六种
            implemented.forEach { s ->
                Button(onClick = { onStyleChange(s) }, enabled = s != style) { Text(s.label()) }
            }
        }
        Button(onClick = onExport, enabled = !exporting) { Text(if (exporting) "导出中…" else "导出") }
    }
}

private fun MaskStyle.label() = when (this) {
    MaskStyle.SOLID -> "实色块"
    MaskStyle.PIXELATE -> "像素化"
    MaskStyle.BLUR -> "模糊"
    MaskStyle.MARKER -> "马克笔"
    MaskStyle.EMOJI -> "Emoji"
    MaskStyle.ERASE -> "抹除"
}
```

- [ ] **Step 3: 组装 EditorScreen**

`app/src/main/java/com/yomark/app/ui/EditorScreen.kt`：

```kotlin
package com.yomark.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yomark.app.render.RendererRegistry
import com.yomark.app.ui.canvas.ImageCanvas
import com.yomark.app.ui.components.EditorBottomBar
import com.yomark.app.ui.components.EditorTopBar

@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val registry = remember { RendererRegistry.default() }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        when (val m = state.message) {
            is EditorMessage.Exported -> {
                val extra = if (m.downscaled) "（原图过大，已压缩至 ${m.width}×${m.height}）" else ""
                snackbar.showMessage("已保存到相册$extra", vm)
            }
            is EditorMessage.Error -> snackbar.showMessage(m.text, vm)
            null -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                canUndo = state.canUndo, canRedo = state.canRedo,
                onUndo = vm::undo, onRedo = vm::redo, onClose = onClose,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                ImageCanvas(
                    state = state,
                    registry = registry,
                    onTap = vm::onTap,
                    onDoubleTap = { _, _, _ -> },
                    onLongPress = vm::onLongPress,
                    onManualBox = vm::onManualBox,
                )
            }
            EditorBottomBar(
                style = state.plan.style,
                implemented = registry.implemented(),
                onStyleChange = vm::setStyle,
                onExport = { vm.requestExport(applyWatermark = true) },
                exporting = state.exporting,
            )
        }
    }
}

private suspend fun SnackbarHostState.showMessage(text: String, vm: EditorViewModel) {
    showSnackbar(text)
    vm.consumeMessage()
}
```

`applyWatermark = true` 在 M1 是写死的——付费去水印在计划 07 接上购买态后改为读 `PurchaseStore`。

- [ ] **Step 4: 实现 Activity 入口**

`app/src/main/java/com/yomark/app/ui/EditorActivity.kt`（覆盖计划 01 的空壳）：

```kotlin
package com.yomark.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.export.Exporter
import com.yomark.app.export.MediaStoreSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry

/**
 * 全应用唯一的 Activity（spec §7.1）：既是启动器入口，也是分享目标。
 *
 * 冷启动无 URI 时立即拉起系统 Photo Picker，不绘制自己的首屏。
 * 用户在 Picker 里按返回 = 退出 app —— 这个 app 没有「主页」这个概念。
 */
class EditorActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels {
        val app = applicationContext
        viewModelFactory {
            initializer {
                EditorViewModel(
                    intake = ImageIntake(app),
                    exporter = Exporter(
                        RendererRegistry.default(),
                        WatermarkDrawer(),
                        MediaStoreSink(app),
                    ),
                )
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else vm.onImageChosen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface { EditorScreen(vm) { finish() } } }
        }
        if (savedInstanceState == null) route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        val shared = intent?.let { incomingUri(it) }
        if (shared != null) {
            vm.onImageChosen(shared)               // ACTION_SEND 直接跳过 Picker
        } else if (vm.state.value.image == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_SEND_MULTIPLE ->
            // 批量在 M5（计划 07）；首版先只处理第一张，行为可预期
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
        else -> null
    }
}
```

- [ ] **Step 5: 编译并手动跑一遍**

```bash
./gradlew :app:installDebug
adb shell am start -n com.yomark.app/.ui.EditorActivity
```

手动确认（每一条都亲手做一遍，不要凭代码推断）：

- [ ] 点图标后**直接**出现系统 Photo Picker，没有自绘首屏
- [ ] 全程**没有任何权限对话框**
- [ ] 在 Picker 里按返回 → app 退出，不留空壳 Activity
- [ ] 选中一张图 → 进编辑器，图片按正确方向显示（用一张竖拍照片验 EXIF 转正）
- [ ] 一指拖动画出黑框；二指捏合缩放、拖动平移；缩放时框跟着图走
- [ ] 点已有黑框 → 变琥珀色虚线；再点 → 变回黑框
- [ ] 撤销/重做按钮状态正确
- [ ] 点导出 → 相册里出现 `Pictures/Yomark/YOMARK_*.jpg`，右下角有 「有码 Yomark」水印

- [ ] **Step 6: 记录 §15.3 的验证结论**

设计文档 §15.3 把「冷启动直接拉 Photo Picker 的实际体验」列为待验证项：会不会闪白、双层 Activity、返回时留空壳。把实测结论写进 `docs/superpowers/specs/2026-09-02-yomark-android-design.md` 的 §15 对应条目下（在条目末尾追加一行「**实测（2026-09-02）**：……」）。

若体验确实不佳，退路是加一个极简首屏（一个大按钮）——但那会牺牲「打开就是相册」的即时感，**只有实测确认体验不可接受时才做**，并把决定写进 spec。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/yomark/app/ui docs/superpowers/specs
git commit -m "feat: add compose canvas, editor chrome and photo-picker-first activity entry"
```

---

### Task 14: 选中态手柄与双击缩放

**Files:**
- Create: `app/src/main/java/com/yomark/app/ui/canvas/SelectionHandles.kt`
- Modify: `app/src/main/java/com/yomark/app/ui/canvas/ImageCanvas.kt`
- Modify: `app/src/main/java/com/yomark/app/ui/EditorScreen.kt`
- Test: `app/src/test/java/com/yomark/app/ui/canvas/SelectionHandlesTest.kt`

**Interfaces:**
- Consumes: `Quad`、`Viewport.doubleTapTarget`、`EditorViewModel.moveSelected` / `deleteSelected`
- Produces:
  - `enum class HandleCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }`
  - `object SelectionHandles`：`fun handleRects(quad: Quad, sizePx: Float): Map<HandleCorner, RectF>`、`fun hitHandle(quad: Quad, point: PointF, sizePx: Float): HandleCorner?`、`fun deleteButtonRect(quad: Quad, sizePx: Float): RectF`、`fun resize(quad: Quad, corner: HandleCorner, to: PointF, minShortEdge: Float): Quad`、`fun move(quad: Quad, dx: Float, dy: Float): Quad`

spec §7.3 的最后两行是这个任务的全部规格：

| 手势 | 行为 |
|---|---|
| 双击 | 在「适配屏幕」与 200% 之间切换 |
| 长按手动框 | 进入选中态：出现四角手柄与删除按钮 |
| 选中态下拖框体 / 拖手柄 | 移动 / 调整大小 |

**手柄只对手动框出现。** 规则命中的候选进不了选中态——它们不可删也不可改，能做的只有 `MASKED ⇄ OUTLINED` 切换。调整大小时短边不得小于 16dp，与新建框用同一个下限。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/yomark/app/ui/canvas/SelectionHandlesTest.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionHandlesTest {

    private val box = Quad.fromRect(RectF(100f, 100f, 300f, 200f))

    @Test
    fun `there are four handles, one per corner`() {
        assertThat(SelectionHandles.handleRects(box, sizePx = 20f).keys)
            .containsExactlyElementsIn(HandleCorner.entries)
    }

    @Test
    fun `each handle is centred on its corner`() {
        val rects = SelectionHandles.handleRects(box, sizePx = 20f)
        assertThat(rects[HandleCorner.TOP_LEFT]!!.centerX()).isWithin(0.1f).of(100f)
        assertThat(rects[HandleCorner.TOP_LEFT]!!.centerY()).isWithin(0.1f).of(100f)
        assertThat(rects[HandleCorner.BOTTOM_RIGHT]!!.centerX()).isWithin(0.1f).of(300f)
        assertThat(rects[HandleCorner.BOTTOM_RIGHT]!!.centerY()).isWithin(0.1f).of(200f)
    }

    @Test
    fun `hitHandle finds the corner under the finger`() {
        assertThat(SelectionHandles.hitHandle(box, PointF(102f, 98f), 20f)).isEqualTo(HandleCorner.TOP_LEFT)
        assertThat(SelectionHandles.hitHandle(box, PointF(298f, 202f), 20f)).isEqualTo(HandleCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `hitHandle returns null in the middle of the box`() {
        assertThat(SelectionHandles.hitHandle(box, PointF(200f, 150f), 20f)).isNull()
    }

    @Test
    fun `the delete button sits above the top-right corner`() {
        val r = SelectionHandles.deleteButtonRect(box, sizePx = 20f)
        assertThat(r.centerY()).isLessThan(100f)
        assertThat(r.centerX()).isGreaterThan(280f)
    }

    @Test
    fun `resizing the bottom-right corner moves only that corner`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(400f, 260f), minShortEdge = 16f)
        assertThat(out.bounds()).isEqualTo(RectF(100f, 100f, 400f, 260f))
    }

    @Test
    fun `resizing the top-left corner moves only that corner`() {
        val out = SelectionHandles.resize(box, HandleCorner.TOP_LEFT, PointF(60f, 40f), minShortEdge = 16f)
        assertThat(out.bounds()).isEqualTo(RectF(60f, 40f, 300f, 200f))
    }

    @Test
    fun `resizing cannot shrink the box below the minimum short edge`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(105f, 105f), minShortEdge = 16f)
        assertThat(out.shortEdge()).isAtLeast(16f)
    }

    @Test
    fun `dragging past the opposite corner does not invert the box`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(10f, 10f), minShortEdge = 16f)
        assertThat(out.bounds().right).isGreaterThan(out.bounds().left)
        assertThat(out.bounds().bottom).isGreaterThan(out.bounds().top)
    }

    @Test
    fun `move shifts every corner by the same delta`() {
        assertThat(SelectionHandles.move(box, 25f, -10f).bounds())
            .isEqualTo(RectF(125f, 90f, 325f, 190f))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*SelectionHandlesTest*'
```

预期：编译失败，`Unresolved reference: SelectionHandles`

- [ ] **Step 3: 实现**

`app/src/main/java/com/yomark/app/ui/canvas/SelectionHandles.kt`：

```kotlin
package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad

enum class HandleCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * 手动框的选中态（spec §7.3）：四角手柄 + 删除按钮，拖框体移动、拖手柄调整大小。
 *
 * 只对 kind == MANUAL 的框出现。规则命中的候选进不了选中态——
 * 它们不可删也不可改，能做的只有 MASKED ⇄ OUTLINED 切换（spec §4.2 的不对称规则）。
 *
 * 手动框一律是轴对齐的（GestureRules.quadFromDrag 保证），所以这里用 bounds 运算是安全的。
 */
object SelectionHandles {

    fun handleRects(quad: Quad, sizePx: Float): Map<HandleCorner, RectF> {
        val b = quad.bounds()
        val h = sizePx / 2f
        fun at(x: Float, y: Float) = RectF(x - h, y - h, x + h, y + h)
        return mapOf(
            HandleCorner.TOP_LEFT to at(b.left, b.top),
            HandleCorner.TOP_RIGHT to at(b.right, b.top),
            HandleCorner.BOTTOM_RIGHT to at(b.right, b.bottom),
            HandleCorner.BOTTOM_LEFT to at(b.left, b.bottom),
        )
    }

    /** 命中判定放宽到手柄的 1.5 倍，手指比图标粗。 */
    fun hitHandle(quad: Quad, point: PointF, sizePx: Float): HandleCorner? =
        handleRects(quad, sizePx * 1.5f).entries
            .firstOrNull { it.value.contains(point.x, point.y) }
            ?.key

    fun deleteButtonRect(quad: Quad, sizePx: Float): RectF {
        val b = quad.bounds()
        val h = sizePx / 2f
        val cx = b.right
        val cy = b.top - sizePx * 1.2f
        return RectF(cx - h, cy - h, cx + h, cy + h)
    }

    fun move(quad: Quad, dx: Float, dy: Float): Quad {
        val b = quad.bounds()
        return Quad.fromRect(RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))
    }

    /**
     * 拖某个角到 [to]。对角固定不动。
     * 拖过头时不允许翻转，也不允许短边小于 minShortEdge——与新建框用同一个下限。
     */
    fun resize(quad: Quad, corner: HandleCorner, to: PointF, minShortEdge: Float): Quad {
        val b = quad.bounds()
        var left = b.left; var top = b.top; var right = b.right; var bottom = b.bottom
        when (corner) {
            HandleCorner.TOP_LEFT -> { left = to.x; top = to.y }
            HandleCorner.TOP_RIGHT -> { right = to.x; top = to.y }
            HandleCorner.BOTTOM_RIGHT -> { right = to.x; bottom = to.y }
            HandleCorner.BOTTOM_LEFT -> { left = to.x; bottom = to.y }
        }
        // 不翻转：把移动的那条边推回去，保证与固定边至少相隔 minShortEdge
        when (corner) {
            HandleCorner.TOP_LEFT -> {
                if (left > right - minShortEdge) left = right - minShortEdge
                if (top > bottom - minShortEdge) top = bottom - minShortEdge
            }
            HandleCorner.TOP_RIGHT -> {
                if (right < left + minShortEdge) right = left + minShortEdge
                if (top > bottom - minShortEdge) top = bottom - minShortEdge
            }
            HandleCorner.BOTTOM_RIGHT -> {
                if (right < left + minShortEdge) right = left + minShortEdge
                if (bottom < top + minShortEdge) bottom = top + minShortEdge
            }
            HandleCorner.BOTTOM_LEFT -> {
                if (left > right - minShortEdge) left = right - minShortEdge
                if (bottom < top + minShortEdge) bottom = top + minShortEdge
            }
        }
        return Quad.fromRect(RectF(left, top, right, bottom))
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*SelectionHandlesTest*'
```

预期：10 个测试全部 PASS。

- [ ] **Step 5: 在画布上画手柄与删除按钮**

在 `ImageCanvas` 的 `drawIntoCanvas` 块里，把选中态那一段：

```kotlin
            state.selectedManualId?.let { id ->
                state.plan.find(id)?.let { canvas.drawPath(it.quad.toPath(), selectionPaint(viewport.scale)) }
            }
```

替换为：

```kotlin
            state.selectedManualId?.let { id ->
                state.plan.find(id)?.let { item ->
                    canvas.drawPath(item.quad.toPath(), selectionPaint(viewport.scale))
                    val size = HANDLE_DP_PX / viewport.scale
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = AndroidColor.WHITE
                    }
                    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; color = AndroidColor.BLACK
                        strokeWidth = 2f / viewport.scale
                    }
                    SelectionHandles.handleRects(item.quad, size).values.forEach { r ->
                        canvas.drawRect(r, fill)
                        canvas.drawRect(r, edge)
                    }
                    // 删除按钮：白底红叉
                    val del = SelectionHandles.deleteButtonRect(item.quad, size * 1.4f)
                    canvas.drawOval(del, fill)
                    canvas.drawOval(del, edge)
                    val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = AndroidColor.rgb(0xD3, 0x2F, 0x2F)
                        strokeWidth = 3f / viewport.scale
                    }
                    val pad = del.width() * 0.28f
                    canvas.drawLine(del.left + pad, del.top + pad, del.right - pad, del.bottom - pad, cross)
                    canvas.drawLine(del.right - pad, del.top + pad, del.left + pad, del.bottom - pad, cross)
                }
            }
```

文件末尾加 `private const val HANDLE_DP_PX = 22f`，并 import `SelectionHandles`。

- [ ] **Step 6: 把手柄接进手势循环**

在 `awaitEachGesture` 里，**在判定画框之前**先检查是否落在选中框的手柄或框体上。在 `val down = awaitFirstDown(...)` 之后插入：

```kotlin
                    val selected = state.selectedManualId?.let { state.plan.find(it) }
                    val handleSize = HANDLE_DP_PX / viewport.scale
                    val downImage = viewport.screenToImage(PointF(down.position.x, down.position.y))
                    var grabbedHandle: HandleCorner? = null
                    var grabbingBody = false
                    if (selected != null) {
                        if (SelectionHandles.deleteButtonRect(selected.quad, handleSize * 1.4f)
                                .contains(downImage.x, downImage.y)) {
                            onDeleteSelected()
                            return@awaitEachGesture
                        }
                        grabbedHandle = SelectionHandles.hitHandle(selected.quad, downImage, handleSize)
                        grabbingBody = grabbedHandle == null && selected.quad.contains(downImage)
                    }
```

在单指分支里，`mode == Mode.DRAW` 之前先处理这两种情况：

```kotlin
                            if (grabbedHandle != null && selected != null) {
                                val to = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                onResize(SelectionHandles.resize(selected.quad, grabbedHandle, to, minBoxPx / viewport.scale))
                                if (c.positionChanged()) c.consume()
                            } else if (grabbingBody && selected != null) {
                                val prev = viewport.screenToImage(PointF(c.previousPosition.x, c.previousPosition.y))
                                val now = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                onResize(SelectionHandles.move(selected.quad, now.x - prev.x, now.y - prev.y))
                                if (c.positionChanged()) c.consume()
                            } else if (mode == Mode.DRAW) {
```

（原来的 `if (mode == Mode.DRAW) { … }` 变成这条链的最后一个分支。）

抬手时，`grabbedHandle != null || grabbingBody` 的情况直接 return，不要再走画框或点击的分支。

`ImageCanvas` 的签名增加两个回调：

```kotlin
    onResize: (Quad) -> Unit,
    onDeleteSelected: () -> Unit,
```

`EditorScreen` 里接成 `onResize = vm::moveSelected`、`onDeleteSelected = vm::deleteSelected`。

> **注意**：拖动手柄期间每一帧都调 `moveSelected` 会往撤销栈里压几十个快照。给 `EditorViewModel` 加一对方法解决：

```kotlin
    /** 拖动期间只改状态不压栈；抬手时调 commitDrag() 补一次。 */
    fun previewSelectedQuad(quad: Quad) {
        val id = _state.value.selectedManualId ?: return
        val item = _state.value.plan.find(id) ?: return
        if (dragOrigin == null) dragOrigin = _state.value.plan
        _state.value = _state.value.copy(plan = _state.value.plan.replace(item.copy(quad = quad)))
    }

    fun commitDrag() {
        val origin = dragOrigin ?: return
        dragOrigin = null
        if (origin == _state.value.plan) return
        undoStack.push(origin)
        _state.value = _state.value.withHistoryFlags()
    }
```

加一个字段 `private var dragOrigin: MaskPlan? = null`，并把 `moveSelected` 删掉（它的职责被这两个方法取代）。画布拖动中调 `previewSelectedQuad`，抬手调 `commitDrag`。

- [ ] **Step 7: 接双击**

在 `ImageCanvas` 的 Modifier 链上，`pointerInput` **之后**再挂一个：

```kotlin
            .pointerInput(image, viewSize) {
                detectTapGestures(onDoubleTap = { offset ->
                    viewport = viewport.doubleTapTarget(
                        PointF(offset.x, offset.y),
                        image.width, image.height, viewSize.first, viewSize.second,
                    )
                })
            }
```

import `androidx.compose.foundation.gestures.detectTapGestures`。

两层 `pointerInput` 会同时收到事件；单击由第一层处理并消费，`detectTapGestures` 的 `onDoubleTap` 只在两次快速点击时触发。若实测发现单击被双击检测吞掉（表现为点击遮罩要等 300ms 才响应），把双击改成在第一层手势循环里自己数时间戳：记录上一次 `Mode.UNDECIDED` 抬手的时刻与位置，两次间隔 < 300ms 且位移 < touchSlop 时判为双击。**以实机手感为准选一种，不要两种都留着。**

- [ ] **Step 8: 实机验证**

```bash
./gradlew :app:installDebug
```

- [ ] 双击 → 在适配屏幕与 2 倍之间切换，切换点保持在手指下
- [ ] 长按手动框 → 出现四角白色手柄与红叉删除按钮
- [ ] 拖手柄 → 只有那个角在动，对角不动
- [ ] 拖框体 → 整框平移
- [ ] 拖完抬手后按撤销 → **一次**就回到拖动前（不是要按几十次）
- [ ] 点红叉 → 框消失
- [ ] 长按**规则命中的候选** → 不出现手柄（只有手动框有选中态）

- [ ] **Step 9: 提交**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/java/com/yomark/app/ui app/src/test/java/com/yomark/app/ui/canvas/SelectionHandlesTest.kt
git commit -m "feat: add selection handles with single-snapshot drag undo and double-tap zoom"
```

---

### Task 15: M1 端到端验收

**Files:**
- Create: `app/src/androidTest/java/com/yomark/app/EndToEndManualRedactionTest.kt`
- Modify: `docs/superpowers/specs/2026-09-02-yomark-android-design.md`（§15 的实测结论）

**Interfaces:**
- Consumes: 全部 M1 组件
- Produces: 一条覆盖「载入 → 画框 → 导出 → 校验」的 instrumented 测试

- [ ] **Step 1: 写端到端测试**

`app/src/androidTest/java/com/yomark/app/EndToEndManualRedactionTest.kt`：

```kotlin
package com.yomark.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.image.SourceImageLoader
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.export.ExportOutcome
import com.yomark.app.export.ExportRequest
import com.yomark.app.export.Exporter
import com.yomark.app.export.MediaStoreSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EndToEndManualRedactionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manual_redaction_round_trip_hides_the_secret_and_strips_metadata() = runTest {
        // 1. 造一张「有秘密」的源图：白底 + 一块红色秘密区
        val src = File(context.cacheDir, "e2e-src.jpg")
        val w = 1600; val h = 1200
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).let { bmp ->
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawRect(RectF(400f, 400f, 800f, 600f), Paint().apply { color = Color.RED })
            }
            src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bmp.recycle()
        }
        ExifInterface(src.absolutePath).apply {
            setLatLong(37.4220, -122.0841)
            setAttribute(ExifInterface.TAG_MODEL, "Secret-Device")
            saveAttributes()
        }

        // 2. 走真实入口：私有副本 → 分析用解码
        val intake = ImageIntake(context)
        val taken = intake.copyToPrivate(src.toUri())
        val image = SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType)

        // 3. 在分析坐标系里把秘密区框住
        val f = image.scale
        val plan = MaskPlan(
            items = listOf(
                MaskItem(
                    "e2e", Quad.fromRect(RectF(400f * f, 400f * f, 800f * f, 600f * f)),
                    SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
                )
            ),
            style = MaskStyle.SOLID,
            options = MaskOptions(),
        )

        // 4. 导出
        val exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), MediaStoreSink(context))
        val outcome = exporter.export(
            ExportRequest(taken.file, taken.mimeType, plan, image.scale, applyWatermark = true)
        )
        assertThat(outcome).isInstanceOf(ExportOutcome.Success::class.java)
        val uri = (outcome as ExportOutcome.Success).uri

        // 5. 读回来校验
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = BitmapFactory.decodeStream(input)!!
            assertThat(out.width).isEqualTo(w)            // 原图分辨率，不是分析分辨率
            assertThat(out.height).isEqualTo(h)
            assertThat(out.getPixel(600, 500)).isEqualTo(Color.BLACK)   // 秘密被遮住
            assertThat(Color.red(out.getPixel(100, 100))).isGreaterThan(200)  // 其余不变
            out.recycle()
        }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val exif = ExifInterface(input)
            assertThat(exif.latLong).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        }

        context.contentResolver.delete(uri, null, null)
        intake.clear()
    }
}
```

- [ ] **Step 2: 运行全部测试**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertDebugNoRuntimePermissions
```

预期：全绿。

- [ ] **Step 3: 验收 M1 出口**

逐条勾（未通过的不要打勾，回到对应任务修）：

- [ ] `./gradlew :app:check` 全绿，含权限断言
- [ ] 冷启动 → Photo Picker → 编辑 → 导出，全程零权限弹窗
- [ ] 导出图是原图分辨率
- [ ] 导出图无 GPS / 型号 / 时间戳
- [ ] 免费导出带 「有码 Yomark」水印，且水印不压在任何黑块上
- [ ] 撤销栈在换图时清空
- [ ] release 构建的 arm64 split 体积（`./gradlew :app:bundleRelease` 后用 `bundletool get-size total --bundle=app/build/outputs/bundle/release/app-release.aab` 看）—— M1 阶段应远低于 25 MB，记下数值作为后续加 ML Kit 的基线

- [ ] **Step 4: 提交并打标签**

```bash
git add app/src/androidTest/java/com/yomark/app/EndToEndManualRedactionTest.kt
git commit -m "test: end-to-end manual redaction from intake to gallery with metadata assertions"
git tag m1-manual-redaction
```

---

## 本计划出口

> **M1 出口（spec §12）**：能当一个可用的手动打码工具发出去，全程零权限弹窗。

达成即可以考虑上架首个版本。识别能力在计划 03 / 04 加。

---

## 执行时与计划不符之处（2026-09-03 执行，供计划 03 起参考）

1. **导出位图必须可变，否则第一行就崩。** `Exporter.export` 里 `Canvas(bitmap)` 直接用
   `BitmapFactory` 解出的位图，那是**不可变**位图，真机与 Robolectric 都会抛
   `IllegalStateException: Immutable bitmap passed to Canvas constructor`。
   已在 `SourceImageLoader.decode` 设 `inMutable = true`，并给 `loadForExport` 的结果加
   `ensureMutable()`（只有 EXIF 旋转真的发生时才会多拷一次，大图不白白翻倍内存）。

2. **「导出不带 EXIF」不能断言 `TAG_ORIENTATION` 为 null。** androidx 的 `ExifInterface`
   会从 JPEG 的 SOF 段合成 `ImageWidth`/`ImageLength`，并把缺失的方向报成
   `ORIENTATION_UNDEFINED(0)` —— 那是库的默认值，不是文件里的数据（实测编码结果里
   连 `Exif` 标记都没有）。`ExporterTest` 的那条断言已改成：字节流中不含 EXIF 段，
   且方向读出来是 `ORIENTATION_UNDEFINED`。

3. **调度器改成调用方可注入。** `ImageIntake.copyToPrivate` / `SourceImageLoader.loadForAnalysis`
   / `loadForExport` / `Exporter.export` 各加了一个 `dispatcher` 参数（默认值与原来写死的
   一致），`EditorViewModel` 一律传自己的 `ioDispatcher`。
   不这么做，计划给的 `EditorViewModelTest` 有 5 条必然失败：这些方法内部
   `withContext(Dispatchers.IO/Default)` 跳到真实线程池，`advanceUntilIdle()` 等不到它们。

4. **`connectedDebugAndroidTest` 不接受 `--tests`。** 跑单个 instrumented 测试用
   `-Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`。

5. **`ImageCanvas` 的手势循环必须读 `rememberUpdatedState(state)`。**
   `pointerInput(image)` 的 block 只在 key 变化时重启，捕获的是重启那一刻的 `state`。
   照计划直接读 `state`，长按选中后立刻拖手柄时循环里看到的 `selectedManualId` 还是 null，
   于是拖出一个新框而不是改选中框 —— 实机复现过。

6. **双击用手势循环内的时间戳判定，不用第二层 `detectTapGestures`（计划 Step 7 的备选方案）。**
   实机验证：叠在同一个 Canvas 上的第二层 `pointerInput` 收不到本层已在处理的手势，
   `onDoubleTap` 从不触发。改成在主循环里记上一次点击的时刻与位置，300ms 内、位移小于
   2×touchSlop 判为第二击。**第二击只做缩放、不再切换遮罩状态**（第一击已经切过一次），
   这样单击零延迟，代价是双击一个遮罩会让它多切一次状态 —— 相比「每次点遮罩都等 300ms」
   这是更划算的一侧。

7. **画布补了 `clipToBounds()`。** 计划没提。不加的话双击放大后图像溢出画布区，直接压在顶栏上。

8. **`ImageCanvas` 签名与计划不同**：去掉了 `onDoubleTap`（缩放在画布内部自己处理），
   新增 `onResize` / `onCommitDrag` / `onDeleteSelected`。`onCommitDrag` 是计划 Task 14 Step 6
   要求「抬手调 commitDrag()」所必需的，计划的参数清单漏了它。

9. **补做了 `ui/components/PendingExportDialog.kt`（计划里没有，本属计划 04）。**
   计划 Task 12 写着「M1 没有识别，pendingCount 恒为 0，拦截不会触发」——**这是错的**：
   M1 的用户点一下手动框就会把它切成 `OUTLINED`，`pendingCount` 立刻大于 0。
   没有对话框 UI 时，点导出的结果是「什么都没发生」，而索引里的 Global Constraint
   写着「`pendingCount > 0` 时点击导出**必然**弹拦截对话框」。
   现在这个是最小实现（标题 + 按类型分行 + 两个按钮），计划 04 可以直接替换成完整版。

10. **未验证项**：两指捏合缩放 / 平移**没有在设备上验过** —— `adb shell input` 注入不了多点触控。
    `Viewport` 的 `zoomAround` / `pan` / `clamped` 有单测覆盖，但「两指手势喂进去」这一段
    只有代码走查。第一次拿到真机时优先手验这一条。

11. **包体基线**：`bundleRelease` 产出的 `app-release.aab` = **1.9 MB**。本机没装 bundletool，
    没跑 `get-size total`；M1 阶段没有任何 native 库，ABI split 还不起作用，
    这个数就是加 ML Kit 之前的基线。

12. **§15.2 / §15.3 的实测结论已写回 spec**（见 spec §15 对应条目下的「实测（2026-09-03）」）。
    要点：闭环与零权限成立、冷启动直接拉 Picker 的体验可接受（不加首屏）；
    但 `ACTION_SEND` 进来的图在 Activity 重建后会丢失，退回 Picker ——
    私有副本的路径没进 `onSaveInstanceState`，且 `onCleared()` 会清空 intake 目录。
    留到计划 04 与状态持久化一起做。
