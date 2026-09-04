# 有码安卓版 · 计划 04 · M2 下半：三态编辑、导出拦截与验收

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把计划 03 的引擎接到编辑器上，落地三态模型的完整交互与导出拦截，并建起可重复跑的评测 harness，达成 M2 出口指标。

**Architecture:** 候选进编辑器时按规则表的 `enabledByDefault` 分成 `MASKED` / `OUTLINED` 两态，未圈出的根本不进 `MaskPlan`。导出拦截是这套分层模型成立的**前提**而非打磨项：`pendingCount > 0` 时无例外拦截，不提供「不再提示」。评测 harness 用一批**合成**样本立刻可跑，真实 200 张样本集由人补充进同一套标注格式。

**Tech Stack:** Jetpack Compose · JSON 标注 · instrumented 评测

**Spec:** `docs/superpowers/specs/2026-09-02-youma-android-design.md`（重点 §7.2、§7.4、§13）

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-youma-android-00-index.md`

**前置:** 计划 01–03 全部完成

---

## File Structure

| 文件 | 职责 |
|---|---|
| `core/model/MaskPlanFactory.kt` | 候选 → 三态 `MaskItem` 的转换（`enabledByDefault` 在这里变成初始状态） |
| `core/model/SensitiveKindLabels.kt` | 类型 → 中文显示名，画布小标签与拦截对话框共用 |
| `ui/EditorViewModel.kt`（改） | 接入引擎、`analyzing` 状态、分析失败降级 |
| `ui/canvas/ImageCanvas.kt`（改） | `OUTLINED` 的琥珀色虚线 + 类型小标签（缩放 ≥ 0.5 时） |
| `ui/components/PendingExportDialog.kt` | 导出拦截对话框 |
| `androidTest/.../eval/SampleSet.kt` | 标注格式与加载器 |
| `androidTest/.../eval/SyntheticSamples.kt` | 合成样本生成器（带真值） |
| `androidTest/.../eval/EvaluationTest.kt` | 圈出率 / 召回率 / 精确率 / 延迟的度量 |
| `docs/eval-sample-set.md` | 真实 200 张样本集的采集与标注说明 |

---

### Task 26: 候选进编辑器

**Files:**
- Create: `app/src/main/java/com/youma/app/core/model/MaskPlanFactory.kt`
- Modify: `app/src/main/java/com/youma/app/ui/EditorViewModel.kt`
- Modify: `app/src/main/java/com/youma/app/ui/EditorUiState.kt`
- Modify: `app/src/main/java/com/youma/app/ui/EditorActivity.kt`
- Test: `app/src/test/java/com/youma/app/core/model/MaskPlanFactoryTest.kt`
- Test: `app/src/test/java/com/youma/app/ui/EditorViewModelAnalysisTest.kt`

**Interfaces:**
- Consumes: `RedactionEngine`、`Candidate`、`MaskItem`
- Produces:
  - `object MaskPlanFactory { fun itemsFrom(candidates: List<Candidate>): List<MaskItem> }`
  - `EditorUiState` 增加 `val analyzing: Boolean = false`
  - `EditorViewModel(intake, exporter, engine, ioDispatcher)` —— 构造参数新增 `engine: RedactionEngine`

**行为规格（spec §7.1）**：选中即进编辑器，且**候选已经按 §6 的默认状态打好码**，不是「等你逐个确认」。分析在载入后立即开始，期间画布可交互（可以先手动画框），分析结果到达时并入 plan。

分析产出的候选**不进撤销栈**——它是初始状态，不是用户动作。用户的第一次点击才是第一个可撤销的动作。

- [ ] **Step 1: 写 MaskPlanFactory 的失败测试**

`app/src/test/java/com/youma/app/core/model/MaskPlanFactoryTest.kt`：

```kotlin
package com.youma.app.core.model

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPlanFactoryTest {

    private fun candidate(id: String, enabled: Boolean, kind: SensitiveKind = SensitiveKind.EMAIL) =
        Candidate(id, Quad.fromRect(RectF(0f, 0f, 10f, 10f)), kind, DetectorSource.RULE, 0.9f, enabled)

    @Test
    fun `enabledByDefault true becomes MASKED`() {
        val item = MaskPlanFactory.itemsFrom(listOf(candidate("a", true))).single()
        assertThat(item.state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `enabledByDefault false becomes OUTLINED`() {
        val item = MaskPlanFactory.itemsFrom(listOf(candidate("b", false))).single()
        assertThat(item.state).isEqualTo(MaskState.OUTLINED)
    }

    @Test
    fun `identity fields carry over unchanged`() {
        val c = candidate("c", true, SensitiveKind.PAYMENT_CARD)
        val item = MaskPlanFactory.itemsFrom(listOf(c)).single()
        assertThat(item.candidateId).isEqualTo("c")
        assertThat(item.kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
        assertThat(item.source).isEqualTo(DetectorSource.RULE)
        assertThat(item.quad).isEqualTo(c.quad)
    }

    @Test
    fun `order is preserved so big regions stay first`() {
        val items = MaskPlanFactory.itemsFrom(listOf(candidate("1", true), candidate("2", false)))
        assertThat(items.map { it.candidateId }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `empty candidates yield an empty item list`() {
        assertThat(MaskPlanFactory.itemsFrom(emptyList())).isEmpty()
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

```bash
./gradlew :app:testDebugUnitTest --tests '*MaskPlanFactoryTest*'
```

`app/src/main/java/com/youma/app/core/model/MaskPlanFactory.kt`：

```kotlin
package com.youma.app.core.model

/**
 * 候选 → 三态编辑项（spec §4.1、§7.2）。
 *
 * enabledByDefault 在这里从「规则表的一列」变成「进编辑器时的初始状态」：
 * true → MASKED（已打码），false → OUTLINED（圈出未打码）。
 * 未被任何候选覆盖的区域根本不进 plan，所以 MaskState 只有两个值。
 */
object MaskPlanFactory {
    fun itemsFrom(candidates: List<Candidate>): List<MaskItem> = candidates.map { c ->
        MaskItem(
            candidateId = c.id,
            quad = c.quad,
            kind = c.kind,
            source = c.source,
            state = if (c.enabledByDefault) MaskState.MASKED else MaskState.OUTLINED,
        )
    }
}
```

再跑一次，预期 5 个测试 PASS。

- [ ] **Step 3: 写 ViewModel 接引擎的失败测试**

`app/src/test/java/com/youma/app/ui/EditorViewModelAnalysisTest.kt`：

```kotlin
package com.youma.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextLine
import com.youma.app.engine.CandidateMerger
import com.youma.app.engine.RedactionEngine
import com.youma.app.engine.SensitivityClassifier
import com.youma.app.engine.TextRecognizer
import com.youma.app.export.Exporter
import com.youma.app.export.ImageSink
import com.youma.app.export.WatermarkDrawer
import com.youma.app.render.RendererRegistry
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
class EditorViewModelAnalysisTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://fake/1")
    }

    private class StubClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        override val id = "stub"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private class DeadRecognizer : TextRecognizer {
        override val id = "dead"
        override suspend fun recognize(image: SourceImage): List<TextLine> = error("no ocr here")
    }

    private fun candidate(id: String, enabled: Boolean, l: Float, t: Float, r: Float, b: Float) =
        Candidate(id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.URL, DetectorSource.RULE, 0.8f, enabled)

    private fun vm(candidates: List<Candidate>) = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
        engine = RedactionEngine(DeadRecognizer(), emptyList(), listOf(StubClassifier(candidates)), CandidateMerger()),
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(name: String = "an.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    @Test
    fun `analysis fills the plan with tri-state items`() = runTest(dispatcher) {
        val vm = vm(listOf(
            candidate("hi", enabled = true, 10f, 10f, 60f, 30f),
            candidate("lo", enabled = false, 100f, 10f, 160f, 30f),
        ))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        val states = vm.state.value.plan.items.associate { it.candidateId to it.state }
        assertThat(states["hi"]).isEqualTo(MaskState.MASKED)
        assertThat(states["lo"]).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)
    }

    @Test
    fun `analyzing flag is cleared when analysis finishes`() = runTest(dispatcher) {
        val vm = vm(emptyList())
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.analyzing).isFalse()
    }

    @Test
    fun `analysis results are not undoable`() = runTest(dispatcher) {
        // 初始状态不是用户动作，撤销栈必须是空的
        val vm = vm(listOf(candidate("x", true, 10f, 10f, 60f, 30f)))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `the first user tap becomes the first undoable action`() = runTest(dispatcher) {
        val vm = vm(listOf(candidate("x", true, 10f, 10f, 60f, 30f)))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(30f, 20f))
        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.canUndo).isTrue()

        vm.undo()
        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `manual boxes drawn during analysis survive the merge`() = runTest(dispatcher) {
        val vm = vm(listOf(candidate("late", true, 200f, 200f, 260f, 230f)))
        vm.onImageChosen(sampleUri())
        // 分析还没回来，用户已经画了一个框
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId })
            .comparingElementsUsing(com.google.common.truth.Correspondence.from<String, String>(
                { actual, expected -> actual!!.startsWith(expected!!) }, "starts with"
            ))
            .containsAtLeast("manual-", "late")
    }

    @Test
    fun `a failing engine leaves the editor usable`() = runTest(dispatcher) {
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engine = RedactionEngine(DeadRecognizer(), emptyList(), listOf(
                object : SensitivityClassifier {
                    override val id = "boom"
                    override suspend fun isAvailable() = true
                    override suspend fun classify(lines: List<TextLine>): List<Candidate> = error("boom")
                }
            ), CandidateMerger()),
            ioDispatcher = dispatcher,
        )
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.state.value.analyzing).isFalse()
        assertThat(vm.state.value.image).isNotNull()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        assertThat(vm.state.value.plan.items).hasSize(1)     // 手动打码仍然可用
    }
}
```

- [ ] **Step 4: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*EditorViewModelAnalysisTest*'
```

预期：编译失败——`EditorViewModel` 没有 `engine` 参数。

- [ ] **Step 5: 改 ViewModel**

在 `EditorUiState` 里加一个字段：

```kotlin
    /** 识别进行中。画布此时仍可交互——用户可以先手动画框。 */
    val analyzing: Boolean = false,
```

`EditorViewModel` 的构造函数加 `private val engine: RedactionEngine,`（放在 `exporter` 之后）。

> **这会打断计划 02 写的 `EditorViewModelTest`**：它的 `vm()` 工厂没有 `engine` 参数，改完立刻编译不过。在同一个提交里把它一起改掉——加一个什么都不产出的引擎：
>
> ```kotlin
> private fun emptyEngine() = RedactionEngine(
>     object : TextRecognizer {
>         override val id = "none"
>         override suspend fun recognize(image: SourceImage) = emptyList<TextLine>()
>     },
>     emptyList(),
>     listOf(object : SensitivityClassifier {
>         override val id = "none"
>         override suspend fun isAvailable() = true
>         override suspend fun classify(lines: List<TextLine>) = emptyList<Candidate>()
>     }),
>     CandidateMerger(),
> )
> ```
>
> **不要为了少改测试而给 `engine` 一个默认值。** 默认值会让「忘了注入引擎」这种错误在生产代码里静默通过。

然后把 `onImageChosen` 改成：

```kotlin
    fun onImageChosen(uri: Uri) {
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            val loaded = runCatching {
                val result = withContext(ioDispatcher) { intake.copyToPrivate(uri) }
                result to SourceImageLoader.loadForAnalysis(result.file, result.mimeType)
            }.getOrElse {
                _state.value = _state.value.copy(loading = false, message = EditorMessage.Error("无法打开这张图片"))
                return@launch
            }
            val (result, image) = loaded
            intakeResult = result
            undoStack.clear()                          // 换图时两个栈都清空
            _state.value = EditorUiState(
                image = image,
                plan = MaskPlan.empty(_state.value.plan.style),
                analyzing = true,
            )
            analyze(image)
        }
    }

    /**
     * 识别（spec §7.1）：选中即进编辑器，候选已按规则表的默认状态打好码，
     * 不是「等你逐个确认」。分析期间画布可交互，结果到达时并入现有 plan。
     */
    private suspend fun analyze(image: SourceImage) {
        val candidates = runCatching { engine.analyze(image).candidates }.getOrElse { emptyList() }
        val detected = MaskPlanFactory.itemsFrom(candidates)
        // 分析结果不进撤销栈——它是初始状态，不是用户动作。
        // 用户在分析期间画的手动框排在后面，不被覆盖。
        val current = _state.value
        if (current.image !== image) return            // 用户已经换了图，丢弃这批结果
        _state.value = current.copy(
            analyzing = false,
            plan = current.plan.copy(items = detected + current.plan.items),
        )
    }
```

需要的 import：`com.youma.app.core.image.SourceImage`、`com.youma.app.core.model.MaskPlanFactory`、`com.youma.app.engine.RedactionEngine`。

- [ ] **Step 6: 改 Activity 的装配**

在 `EditorActivity` 的 `viewModelFactory` 里加一行：

```kotlin
                EditorViewModel(
                    intake = ImageIntake(app),
                    exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), MediaStoreSink(app)),
                    engine = buildEngine(app),
                )
```

import `com.youma.app.engine.buildEngine`。

- [ ] **Step 7: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*EditorViewModel*' --tests '*MaskPlanFactoryTest*'
git add app/src/main/java/com/youma/app app/src/test/java/com/youma/app
git commit -m "feat: run recognition on load and seed the plan with tri-state items"
```

预期：全部 PASS（含计划 02 的 12 个 ViewModel 测试）。

---

### Task 27: 三态外观与类型标签

**Files:**
- Create: `app/src/main/java/com/youma/app/core/model/SensitiveKindLabels.kt`
- Modify: `app/src/main/java/com/youma/app/ui/canvas/ImageCanvas.kt`
- Test: `app/src/test/java/com/youma/app/core/model/SensitiveKindLabelsTest.kt`

**Interfaces:**
- Consumes: `SensitiveKind`、`MaskState`、`Viewport`
- Produces:
  - `object SensitiveKindLabels { fun display(kind: SensitiveKind): String; fun plural(kind: SensitiveKind, count: Int): String }`
  - `ImageCanvas` 在 `viewport.scale >= GestureRules.LABEL_MIN_SCALE` 时给 `OUTLINED` 项绘制类型小标签

| 状态 | 外观 | 单指点击 |
|---|---|---|
| 已打码 `MASKED` | 实心遮罩（当前样式） | → 退回「圈出未打码」 |
| 圈出未打码 `OUTLINED` | 2dp 虚线框（琥珀色）+ 类型小标签 | → 打码 |
| 未圈出 | 无 | 一指拖动画新框 |

类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片。

- [ ] **Step 1: 写标签的失败测试**

`app/src/test/java/com/youma/app/core/model/SensitiveKindLabelsTest.kt`：

```kotlin
package com.youma.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveKindLabelsTest {

    @Test
    fun `every kind has a non-empty display name`() {
        SensitiveKind.entries.forEach { kind ->
            assertThat(SensitiveKindLabels.display(kind)).isNotEmpty()
        }
    }

    @Test
    fun `display names are distinct so the dialog never says the same thing twice`() {
        val names = SensitiveKind.entries.map { SensitiveKindLabels.display(it) }
        assertThat(names.toSet()).hasSize(names.size)
    }

    @Test
    fun `plural reads like the spec example`() {
        // spec §7.4：「2 个网址、1 个 IP 地址」
        assertThat(SensitiveKindLabels.plural(SensitiveKind.URL, 2)).isEqualTo("2 个网址")
        assertThat(SensitiveKindLabels.plural(SensitiveKind.IP_ADDR, 1)).isEqualTo("1 个 IP 地址")
    }
}
```

- [ ] **Step 2: 实现标签表**

`app/src/main/java/com/youma/app/core/model/SensitiveKindLabels.kt`：

```kotlin
package com.youma.app.core.model

/** 类型的显示名。画布小标签与导出拦截对话框共用同一套措辞。 */
object SensitiveKindLabels {

    fun display(kind: SensitiveKind): String = when (kind) {
        SensitiveKind.PHONE -> "电话"
        SensitiveKind.EMAIL -> "邮箱"
        SensitiveKind.PAYMENT_CARD -> "银行卡号"
        SensitiveKind.IBAN -> "IBAN"
        SensitiveKind.SSN -> "社保号"
        SensitiveKind.PASSPORT -> "护照"
        SensitiveKind.TRACKING_NO -> "快递单号"
        SensitiveKind.IP_ADDR -> "IP 地址"
        SensitiveKind.MAC_ADDR -> "MAC 地址"
        SensitiveKind.URL -> "网址"
        SensitiveKind.API_KEY -> "密钥"
        SensitiveKind.POSTAL_ADDRESS -> "地址"
        SensitiveKind.PERSON_NAME -> "人名"
        SensitiveKind.ORG_NAME -> "机构名"
        SensitiveKind.FACE -> "人脸"
        SensitiveKind.BARCODE -> "条码"
        SensitiveKind.MANUAL -> "手动"
    }

    fun plural(kind: SensitiveKind, count: Int): String = "$count 个${display(kind)}"
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*SensitiveKindLabelsTest*'
```

预期：3 个测试 PASS。

- [ ] **Step 3: 在画布上绘制类型标签**

在 `ImageCanvas.kt` 的 `OUTLINED` 绘制分支里，把

```kotlin
            sorted.filter { it.state == MaskState.OUTLINED }.forEach {
                canvas.drawPath(it.quad.toPath(), outlinePaint(viewport.scale))
            }
```

替换为：

```kotlin
            sorted.filter { it.state == MaskState.OUTLINED }.forEach { item ->
                canvas.drawPath(item.quad.toPath(), outlinePaint(viewport.scale))
                // 类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片
                if (viewport.scale >= GestureRules.LABEL_MIN_SCALE) {
                    drawKindLabel(canvas, item, viewport.scale)
                }
            }
```

在文件末尾加：

```kotlin
/** 琥珀色小标签，贴在圈出框的左上角外侧。字号按缩放反算，视觉大小恒定。 */
private fun drawKindLabel(canvas: android.graphics.Canvas, item: MaskItem, scale: Float) {
    val text = SensitiveKindLabels.display(item.kind)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        textSize = 26f / scale
    }
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
        style = Paint.Style.FILL
    }
    val b = item.quad.bounds()
    val padding = 6f / scale
    val w = textPaint.measureText(text) + padding * 2
    val h = textPaint.textSize + padding * 2
    val top = (b.top - h - 2f / scale).coerceAtLeast(0f)
    val rect = android.graphics.RectF(b.left, top, b.left + w, top + h)
    canvas.drawRoundRect(rect, 4f / scale, 4f / scale, bg)
    canvas.drawText(text, rect.left + padding, rect.bottom - padding - textPaint.descent() * 0.5f, textPaint)
}
```

需要的 import：`com.youma.app.core.model.MaskItem`、`com.youma.app.core.model.SensitiveKindLabels`。

- [ ] **Step 4: 实机确认三态外观**

```bash
./gradlew :app:installDebug
```

用一张含**银行卡号 + 网址**的截图（可以自己在备忘录里打一段 `4111 1111 1111 1111` 和 `https://app.com/r/AbCdEf123` 再截屏）走一遍：

- [ ] 进编辑器时卡号**已经是黑块**，网址是**琥珀色虚线框**
- [ ] 虚线框左上角有「网址」标签
- [ ] 放大到 0.5 倍以上标签出现，缩小到 0.5 倍以下标签消失
- [ ] 点黑块 → 变虚线框；点虚线框 → 变黑块
- [ ] 点虚线框内的空白（没有任何候选处）→ 不误触

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/youma/app/core/model/SensitiveKindLabels.kt app/src/main/java/com/youma/app/ui/canvas/ImageCanvas.kt app/src/test/java/com/youma/app/core/model/SensitiveKindLabelsTest.kt
git commit -m "feat: render outlined candidates with amber dashes and zoom-gated type labels"
```

---

### Task 28: 导出拦截对话框

**Files:**
- Create: `app/src/main/java/com/youma/app/ui/components/PendingExportDialog.kt`
- Modify: `app/src/main/java/com/youma/app/ui/EditorScreen.kt`
- Test: `app/src/androidTest/java/com/youma/app/ui/PendingExportDialogTest.kt`
- Modify: `app/build.gradle.kts`（加 Compose UI 测试依赖）

**Interfaces:**
- Consumes: `MaskPlan.pendingByKind()`、`SensitiveKindLabels`
- Produces: `@Composable fun PendingExportDialog(pendingCount: Int, byKind: Map<SensitiveKind, Int>, onMaskAll: () -> Unit, onExportAnyway: () -> Unit, onDismiss: () -> Unit)`

**这不是打磨项，是三态模型成立的前提**（spec §7.4）。三态模型开了一个二态模型没有的风险：有候选会以 `OUTLINED` 状态出厂，用户没注意就导出 = 泄露。

- **触发条件**：`plan.pendingCount > 0`，点击导出时触发，**无例外**（已在计划 02 的 VM 里实现并测过）
- **文案**：「还有 N 处已识别的内容未打码」，下方按类型列出（如「2 个网址、1 个 IP 地址」）
- **按钮**：「全部打码」（主要）/「仍然导出」（次要）
- **不提供「不再提示」**——首版刻意不给关。关掉它等于把 URL / IP / 快递单号三类彻底变成静默漏检。

- [ ] **Step 1: 加 Compose 测试依赖**

`gradle/libs.versions.toml` 的 `[libraries]` 里加：

```toml
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
```

`app/build.gradle.kts` 的 `dependencies` 里加：

```kotlin
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

预期：仍然通过（`ui-test-manifest` 只进 debug 变体，不影响 release）。

- [ ] **Step 2: 写失败的 UI 测试**

`app/src/androidTest/java/com/youma/app/ui/PendingExportDialogTest.kt`：

```kotlin
package com.youma.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.youma.app.core.model.SensitiveKind
import com.youma.app.ui.components.PendingExportDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PendingExportDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun shows_the_count_and_the_per_kind_breakdown() {
        compose.setContent {
            PendingExportDialog(
                pendingCount = 3,
                byKind = mapOf(SensitiveKind.URL to 2, SensitiveKind.IP_ADDR to 1),
                onMaskAll = {}, onExportAnyway = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("还有 3 处已识别的内容未打码").assertIsDisplayed()
        compose.onNodeWithText("2 个网址、1 个 IP 地址").assertIsDisplayed()
    }

    @Test
    fun mask_all_button_fires_its_callback() {
        var masked = false
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), { masked = true }, {}, {})
        }
        compose.onNodeWithText("全部打码").performClick()
        assertThat(masked).isTrue()
    }

    @Test
    fun export_anyway_button_fires_its_callback() {
        var exported = false
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), {}, { exported = true }, {})
        }
        compose.onNodeWithText("仍然导出").performClick()
        assertThat(exported).isTrue()
    }

    @Test
    fun there_is_no_do_not_show_again_option() {
        // spec §7.4：首版刻意不给关。这条测试守着这个决定。
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), {}, {}, {})
        }
        compose.onAllNodesWithText("不再提示").fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }
}
```

需要 import `androidx.compose.ui.test.onAllNodesWithText`。

- [ ] **Step 3: 运行确认失败**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*PendingExportDialogTest*'
```

预期：编译失败，`Unresolved reference: PendingExportDialog`

- [ ] **Step 4: 实现对话框**

`app/src/main/java/com/youma/app/ui/components/PendingExportDialog.kt`：

```kotlin
package com.youma.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.SensitiveKindLabels

/**
 * 导出拦截（spec §7.4）。
 *
 * 三态模型开了一个二态模型没有的风险：有候选会以 OUTLINED 状态出厂，
 * 用户没注意就导出 = 泄露。这个对话框**不是打磨项，是这套模型成立的前提**。
 *
 * 刻意不提供「不再提示」：它是分层默认的唯一安全网，关掉它等于把
 * URL / IP / 快递单号三类彻底变成静默漏检。有留存数据后再评估。
 */
@Composable
fun PendingExportDialog(
    pendingCount: Int,
    byKind: Map<SensitiveKind, Int>,
    onMaskAll: () -> Unit,
    onExportAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还有 $pendingCount 处已识别的内容未打码") },
        text = {
            Text(byKind.entries.joinToString("、") { (kind, n) -> SensitiveKindLabels.plural(kind, n) })
        },
        confirmButton = { TextButton(onClick = onMaskAll) { Text("全部打码") } },
        dismissButton = { TextButton(onClick = onExportAnyway) { Text("仍然导出") } },
    )
}
```

- [ ] **Step 5: 接进 EditorScreen**

在 `EditorScreen` 的 `Scaffold` 内部（`Column` 之后）加：

```kotlin
            if (state.pendingDialogVisible) {
                PendingExportDialog(
                    pendingCount = state.plan.pendingCount,
                    byKind = state.plan.pendingByKind(),
                    onMaskAll = { vm.confirmMaskAllAndExport(applyWatermark = true) },
                    onExportAnyway = { vm.confirmExportAnyway(applyWatermark = true) },
                    onDismiss = vm::dismissDialog,
                )
            }
```

import `com.youma.app.ui.components.PendingExportDialog`。

- [ ] **Step 6: 运行确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*PendingExportDialogTest*'
```

预期：4 个测试全部 PASS。

- [ ] **Step 7: 实机验一次触发率**

用一张含网址的截图：进编辑器 → 网址是虚线框 → 直接点导出。

- [ ] 对话框必然弹出，文案是「还有 1 处已识别的内容未打码」，下方写「1 个网址」
- [ ] 点「全部打码」→ 虚线框变黑块 → 立即导出
- [ ] 重新来一次，点「仍然导出」→ 直接导出，网址仍然可见
- [ ] 对话框上**没有**「不再提示」

- [ ] **Step 8: 提交**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml app/src/main/java/com/youma/app/ui app/src/androidTest/java/com/youma/app/ui
git commit -m "feat: add mandatory export interception dialog with per-kind breakdown"
```

---

### Task 29: 评测 harness 与样本集

**Files:**
- Create: `app/src/androidTest/java/com/youma/app/eval/SampleSet.kt`
- Create: `app/src/androidTest/java/com/youma/app/eval/SyntheticSamples.kt`
- Create: `app/src/androidTest/java/com/youma/app/eval/EvaluationTest.kt`
- Create: `docs/eval-sample-set.md`

**Interfaces:**
- Consumes: `buildEngine`、`SourceImage`、`Candidate`
- Produces:
  - `data class Annotation(kind: SensitiveKind, rect: RectF)`
  - `data class Sample(name: String, image: SourceImage, truth: List<Annotation>)`
  - `object SampleSet { fun loadFromAssets(context: Context, dir: String = "samples"): List<Sample> }`
  - `object SyntheticSamples { fun generate(count: Int = 24): List<Sample> }`
  - `data class Metrics(outlineRate: Double, maskedRecall: Double, precision: Double, latencyMs: Long)`
  - `object Evaluator { suspend fun run(engine: RedactionEngine, samples: List<Sample>): Metrics }`

**M2 出口指标（spec §12、§13）**：
- 默认打码类型召回率 ≥ 0.95（标注实体被 `MASKED` 覆盖的比例）
- 全类型圈出率 ≥ 0.95（标注实体被 `MASKED` 或 `OUTLINED` 覆盖的比例）
- 精确率 ≥ 0.70（`MASKED` 区域中确实覆盖敏感内容的比例）
- 识别延迟 < 800 ms（1080×2400 截图，中端机）

**关于样本集**：spec §13 要求自建 200 张真实截图并人工标注，覆盖聊天记录、订单页、证件照、快递单、银行 app、邮件列表六类。**这批图必须由人去采集与标注，不能合成**——它是后续每次改动的回归基准。

本任务交付的是**能立刻跑起来的 harness + 一批合成样本**，让指标从今天起就有数；真实样本集按 `docs/eval-sample-set.md` 的格式逐步补进 `androidTest/assets/samples/`，harness 一行都不用改。

- [ ] **Step 1: 写样本格式与加载器**

`app/src/androidTest/java/com/youma/app/eval/SampleSet.kt`：

```kotlin
package com.youma.app.eval

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.SensitiveKind
import org.json.JSONObject

/** 一处人工标注：这块像素区域里有一个此类型的敏感实体。 */
data class Annotation(val kind: SensitiveKind, val rect: RectF)

data class Sample(val name: String, val image: SourceImage, val truth: List<Annotation>)

/**
 * 从 androidTest/assets/samples/ 加载样本集。
 *
 * 目录结构：
 *   samples/chat-001.png
 *   samples/chat-001.json
 *
 * JSON 格式（坐标是图像像素，原点左上）：
 *   { "annotations": [ { "kind": "PAYMENT_CARD", "l": 120, "t": 340, "r": 520, "b": 380 } ] }
 *
 * 详见 docs/eval-sample-set.md。
 */
object SampleSet {

    fun loadFromAssets(context: Context, dir: String = "samples"): List<Sample> {
        val names = runCatching { context.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val images = names.filter { it.endsWith(".png") || it.endsWith(".jpg") }
        return images.mapNotNull { imageName ->
            val base = imageName.substringBeforeLast('.')
            val jsonName = "$base.json"
            if (jsonName !in names) return@mapNotNull null

            val bitmap = context.assets.open("$dir/$imageName").use { BitmapFactory.decodeStream(it) }
                ?: return@mapNotNull null
            val json = context.assets.open("$dir/$jsonName").use { it.readBytes().decodeToString() }
            Sample(
                name = base,
                image = SourceImage(bitmap, 1f, bitmap.width, bitmap.height, "image/png"),
                truth = parse(json),
            )
        }
    }

    private fun parse(json: String): List<Annotation> {
        val arr = JSONObject(json).getJSONArray("annotations")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Annotation(
                kind = SensitiveKind.valueOf(o.getString("kind")),
                rect = RectF(
                    o.getDouble("l").toFloat(), o.getDouble("t").toFloat(),
                    o.getDouble("r").toFloat(), o.getDouble("b").toFloat(),
                ),
            )
        }
    }
}
```

- [ ] **Step 2: 写合成样本生成器**

`app/src/androidTest/java/com/youma/app/eval/SyntheticSamples.kt`：

```kotlin
package com.youma.app.eval

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.SensitiveKind

/**
 * 合成样本：渲染已知内容的文本行，真值就是渲染时的文本框。
 *
 * 它替代不了 spec §13 要求的 200 张真实截图——真实截图有压缩噪点、
 * 深色模式、异体字号、重叠 UI，这些都是合成图给不出的。
 * 合成样本的作用是让 harness 从今天起就能跑，回归时先在这里失败一次。
 */
object SyntheticSamples {

    private data class Entry(val text: String, val kind: SensitiveKind?)

    /** 每条：一行文本 + 它应当被识别成什么（null 表示这行不该产生候选）。 */
    private val ENTRIES = listOf(
        Entry("Card 4111111111111111", SensitiveKind.PAYMENT_CARD),
        Entry("Visa 5500005555555559", SensitiveKind.PAYMENT_CARD),
        Entry("IBAN DE89370400440532013000", SensitiveKind.IBAN),
        Entry("IBAN GB82WEST12345698765432", SensitiveKind.IBAN),
        Entry("SSN 123-45-6789", SensitiveKind.SSN),
        Entry("mail alice@example.com", SensitiveKind.EMAIL),
        Entry("mail bob.smith+tag@mail.co.uk", SensitiveKind.EMAIL),
        Entry("call +1 415 555 2671", SensitiveKind.PHONE),
        Entry("mac 00:1A:2B:3C:4D:5E", SensitiveKind.MAC_ADDR),
        Entry("key sk-aB3xQ9zL2mR7tK1vP0wY8n", SensitiveKind.API_KEY),
        Entry("host 192.168.1.10", SensitiveKind.IP_ADDR),
        Entry("open https://app.com/r/AbCdEf123", SensitiveKind.URL),
        Entry("ship 1Z12345E0205271688", SensitiveKind.TRACKING_NO),
        Entry("Total 42.50 USD", null),
        Entry("Delivered on Tuesday", null),
        Entry("Thanks for your order", null),
    )

    fun generate(): List<Sample> = ENTRIES.mapIndexed { i, entry ->
        val bmp = Bitmap.createBitmap(1080, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText(entry.text, 40f, 180f, paint)
        }

        // 真值：整行文本的外接框（宁可宽，覆盖判定用 IoU > 0 的相交）
        val width = paint.measureText(entry.text)
        val truth = entry.kind?.let {
            listOf(Annotation(it, RectF(40f, 180f + paint.ascent(), 40f + width, 180f + paint.descent())))
        } ?: emptyList()

        Sample(
            name = "synthetic-%02d".format(i),
            image = SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"),
            truth = truth,
        )
    }
}
```

- [ ] **Step 3: 写评测测试**

`app/src/androidTest/java/com/youma/app/eval/EvaluationTest.kt`：

```kotlin
package com.youma.app.eval

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.SensitiveKind
import com.youma.app.engine.RedactionEngine
import com.youma.app.engine.buildEngine
import com.youma.app.rules.DefaultRuleSet
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

data class Metrics(
    val outlineRate: Double,
    val maskedRecall: Double,
    val precision: Double,
    val sampleCount: Int,
    val truthCount: Int,
)

@RunWith(AndroidJUnit4::class)
class EvaluationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 默认打码的类型集合，直接从规则表读，不硬编码——规则表改了指标口径自动跟着改。 */
    private val maskedByDefaultKinds: Set<SensitiveKind> =
        DefaultRuleSet.rules.filter { it.enabledByDefault }.map { it.kind }.toSet()

    private suspend fun evaluate(engine: RedactionEngine, samples: List<Sample>): Metrics {
        var truthTotal = 0
        var covered = 0
        var maskedTruth = 0
        var maskedCovered = 0
        var candidateTotal = 0
        var candidateHit = 0

        samples.forEach { sample ->
            val candidates = engine.analyze(sample.image).candidates
            candidateTotal += candidates.size
            candidates.forEach { c ->
                if (sample.truth.any { intersects(c, it.rect) }) candidateHit++
            }
            sample.truth.forEach { t ->
                truthTotal++
                val hit = candidates.any { it.kind == t.kind && intersects(it, t.rect) }
                if (hit) covered++
                if (t.kind in maskedByDefaultKinds) {
                    maskedTruth++
                    val maskedHit = candidates.any {
                        it.kind == t.kind && it.enabledByDefault && intersects(it, t.rect)
                    }
                    if (maskedHit) maskedCovered++
                }
            }
        }

        return Metrics(
            outlineRate = if (truthTotal == 0) 1.0 else covered.toDouble() / truthTotal,
            maskedRecall = if (maskedTruth == 0) 1.0 else maskedCovered.toDouble() / maskedTruth,
            precision = if (candidateTotal == 0) 1.0 else candidateHit.toDouble() / candidateTotal,
            sampleCount = samples.size,
            truthCount = truthTotal,
        )
    }

    private fun intersects(c: Candidate, truth: RectF) = RectF(c.quad.bounds()).intersect(truth)

    @Test
    fun synthetic_samples_meet_the_m2_exit_criteria() = runTest {
        val metrics = evaluate(buildEngine(context), SyntheticSamples.generate())
        println("YOUMA-EVAL synthetic: $metrics")

        assertThat(metrics.outlineRate).isAtLeast(0.95)
        assertThat(metrics.maskedRecall).isAtLeast(0.95)
        assertThat(metrics.precision).isAtLeast(0.70)
    }

    @Test
    fun real_sample_set_meets_the_m2_exit_criteria_when_present() = runTest {
        val samples = SampleSet.loadFromAssets(context)
        if (samples.isEmpty()) {
            println("YOUMA-EVAL: androidTest/assets/samples/ 为空，跳过。见 docs/eval-sample-set.md")
            return@runTest
        }
        val metrics = evaluate(buildEngine(context), samples)
        println("YOUMA-EVAL real: $metrics")

        assertThat(metrics.outlineRate).isAtLeast(0.95)
        assertThat(metrics.maskedRecall).isAtLeast(0.95)
        assertThat(metrics.precision).isAtLeast(0.70)
    }

    @Test
    fun recognition_latency_on_a_screenshot_sized_image_is_under_800ms() = runTest {
        val engine = buildEngine(context)
        val sample = SyntheticSamples.generate().first()
        engine.analyze(sample.image)                       // 预热：首次调用含模型初始化

        val elapsed = measureTimeMillis { engine.analyze(sample.image) }
        println("YOUMA-EVAL latency: ${elapsed}ms")
        assertThat(elapsed).isLessThan(800L)
    }
}
```

- [ ] **Step 4: 跑评测**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*EvaluationTest*'
```

预期：三个测试通过，logcat 里能看到 `YOUMA-EVAL synthetic: Metrics(...)`。

若某项指标不达标，**不要放宽阈值**——阈值来自 spec §13，改阈值等于改产品定位。回去调规则：召回不够就放宽正则（漏检是事故），精确率不够就收紧校验（误报只是麻烦，但也别泛滥）。把每次调整记进提交信息。

- [ ] **Step 5: 写样本集采集说明**

`docs/eval-sample-set.md`：

```markdown
# 有码评测样本集

这份样本集是后续每次识别改动的回归基准。spec §13 的原话是「比任何公开数据集都重要」。

## 规模与覆盖

200 张真实截图，六类各约 33 张：

| 类别 | 说明 |
|---|---|
| 聊天记录 | 微信 / WhatsApp / Slack，含分享出来的电话与链接 |
| 订单页 | 电商订单详情，含订单号、快递单号、收货电话 |
| 证件照 | 护照信息页、驾照、身份证（**用公开样张或自己的证件，不要用他人的**） |
| 快递单 | 面单照片与物流详情页 |
| 银行 app | 账户页、转账记录、卡片管理页 |
| 邮件列表 | 收件箱、邮件详情 |

## 隐私要求

**这批图会进仓库。** 只用以下三种来源：

1. 你自己的账号与证件；
2. 公开的官方样张（如 ICAO 的 MRZ 示例护照）；
3. 自己造的假数据（在真实 app 里输入编造的号码后截屏）。

**绝不使用他人的真实数据。** 若某张图里混进了第三方信息，先在图上物理涂掉再入库。

## 目录与格式

```
app/src/androidTest/assets/samples/
  chat-001.png
  chat-001.json
  bank-014.jpg
  bank-014.json
```

每张图配一个同名 JSON，坐标是图像像素、原点左上：

```json
{
  "annotations": [
    { "kind": "PAYMENT_CARD", "l": 120, "t": 340, "r": 520, "b": 380 },
    { "kind": "PHONE",        "l": 120, "t": 400, "r": 420, "b": 440 }
  ]
}
```

`kind` 取值是 `SensitiveKind` 的枚举名。**标注要框住整个实体**，宁可略宽——评测用相交判定，标窄了会把命中判成漏检。

## 标注原则

- 图里出现的**每一个**敏感实体都要标，包括那些当前规则识别不了的（人名、地址）——它们是圈出率的分母，也是未来加 NER 时的现成基准。
- 一个实体一条标注。同一个电话在页面上出现两次就标两条。
- 部分遮挡、截断的实体照标，标可见部分。

## 怎么跑

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*EvaluationTest*'
```

logcat 里搜 `YOUMA-EVAL`。样本目录为空时测试会跳过并打印提示，不会失败。

## 阈值

来自 spec §13，**不因为跑不过而调整**：

| 指标 | 阈值 |
|---|---|
| 圈出率 | ≥ 0.95 |
| 默认打码召回率 | ≥ 0.95 |
| 精确率 | ≥ 0.70 |
| 识别延迟（1080×2400，中端机） | < 800 ms |

精确率阈值刻意设得低：多遮一块只是麻烦，漏遮一块是事故。
```

- [ ] **Step 6: 提交**

```bash
git add app/src/androidTest/java/com/youma/app/eval docs/eval-sample-set.md
git commit -m "feat: add evaluation harness with synthetic samples and real sample-set format"
```

---

### Task 30: M2 出口验收

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-youma-android-design.md`（§15 的实测结论）

**Interfaces:**
- Consumes: 全部 M2 组件
- Produces: 一份可核对的验收记录

- [ ] **Step 1: 跑全量**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertDebugNoRuntimePermissions
```

- [ ] **Step 2: 实机走一遍完整流程**

用一张真实的银行 app 截图（含卡号与电话）：

- [ ] 冷启动 → Photo Picker → 选中 → **候选已经打好码**，不需要逐个确认
- [ ] 卡号、电话是黑块；网址、IP 是琥珀色虚线框
- [ ] 点黑块能取消，点虚线框能打码
- [ ] 有虚线框时点导出 → 必然弹拦截对话框
- [ ] 「全部打码」→ 全变黑块 → 导出
- [ ] 导出图在原图分辨率上，卡号确实看不见
- [ ] 全程零权限弹窗
- [ ] 飞行模式下重复一遍，行为完全一致

- [ ] **Step 3: 抓包确认零网络请求**

spec §13 的「网络请求 = 0」指标。用 mitmproxy 或 Charles 把设备流量导过来，走一遍「选图 → 识别 → 编辑 → 导出」：

```bash
# 或者用最简单的办法：断网跑一遍完整流程，行为必须与联网时完全一致
adb shell svc wifi disable && adb shell svc data disable
# ……手动走一遍……
adb shell svc wifi enable
```

- [ ] 运行期发出的请求数 = 0

- [ ] **Step 4: 记录 spec §15 的验证结论**

把这几条的实测结果写进 spec §15 对应条目（在条目末尾追加「**实测（日期）**：……」）：

- [ ] §15.1 ML Kit bundled 完全不需要网络 —— 已在计划 03 验过，确认记录在案
- [ ] §15.2 零权限下的完整闭环 —— 本任务 Step 2 已覆盖
- [ ] §15.5 词级映射的实际粒度 —— 已在计划 03 验过

- [ ] **Step 5: 记录 M2 出口数据**

在 `docs/eval-sample-set.md` 末尾追加一节，写上本次跑分的实际数值（圈出率、召回率、精确率、延迟）与日期，作为后续回归的对照基线。

- [ ] **Step 6: 提交并打标签**

```bash
git add docs
git commit -m "docs: record M2 exit metrics and spec §15 verification results"
git tag m2-recognition
```

---

## 本计划出口

> **M2 出口（spec §12）**：200 张自建样本集上，默认打码类型召回率 ≥ 0.95；全类型圈出率 ≥ 0.95。

合成样本上的指标是本计划结束时就能拿到的；真实 200 张样本集按 `docs/eval-sample-set.md` 采集完成后再跑一次，两组数都记进文档。

**这一版可以发布**：完整识别、三态编辑、导出拦截、原图导出、零权限。人脸与条码在计划 05 加。

---

## 执行记录（2026-09-03）

Task 26–30 全部执行完毕，外加索引约定之外、由 spec §15 甩给本计划的三件事
（经确认后一并做掉）。环境：模拟器 `Medium_Phone` / android-37。

**最终状态**：235 条单测 + 25 条 instrumented 全绿；`assertDebugNoRuntimePermissions`
与 `assertReleaseNoRuntimePermissions` 均通过；断网下行为与联网时逐位一致。

### 与计划文本的偏差

1. **Task 26 —— 计划给的「manual boxes during analysis」测试序列不成立。**
   `StandardTestDispatcher` 下 `onImageChosen` 之后立刻 `onManualBox`，载入协程一步都还没跑，
   框落在载入前的空 plan 上，随后被载入时的 `EditorUiState` 重置抹掉。
   而那个场景在 UI 上根本走不到——`image == null` 时 `ImageCanvas` 直接 return。
   改成用 `CompletableDeferred` 卡住分类器，精确命中计划真正描述的
   「图已载入、分析还没回来」窗口，并断言此刻 `analyzing == true`。实现未因此改动。
   另：`onImageChosen` 沿用仓库现有的「dispatcher 作参数传进 `copyToPrivate` /
   `loadForAnalysis`」写法，不改成计划里的 `withContext(ioDispatcher)` 包裹，两者等价。

2. **Task 27 —— 计划给的 `plural` 实现过不了计划自己写的测试。**
   `"$count 个${display(kind)}"` 产出「1 个IP 地址」，而 spec §7.4 的例子是
   「1 个 IP 地址」，中文与拉丁字母之间有一个空格。按 Step 4「确认样例无误则修实现」
   的口径修实现：拉丁开头的显示名前补空格，纯中文名不补。

3. **Task 28 —— `PendingExportDialog` 是替换不是新建。**
   计划 02 已经建过一个签名与措辞都不同的 M1 版（`pendingByKind` / `onMaskAllAndExport` /
   「还有 N 处只圈出、没打码」）。按计划 02 自己的注释整体替换成 spec §7.4 的完整版，
   并同步改 `EditorScreen` 的调用点。

4. **Task 28 —— 必须显式引入 espresso-core 3.7.0。**
   Compose `ui-test-junit4` 传递进来的是 3.5.0，在 android-37 上四条测试全崩：
   `NoSuchMethodException android.hardware.input.InputManager.getInstance`，
   该方法在新版本 Android 上已被移除。按索引「版本解析失败取最新稳定版并写回
   `libs.versions.toml`」的口径处理。只进 androidTest，不影响 release。

5. **Task 29 —— 计划的延迟测试量错了尺寸。**
   计划量的是 `generate().first()`，即一张 1080×300 的单行条，跑出 6ms——数字好看但
   测不出真实开销，而 spec §13 的口径写死了「1080×2400 截图」。补了
   `SyntheticSamples.screenshot()`（1080×2400，全部行竖排堆进去），延迟测试改量它，
   并加一条同尺寸的指标测试。实测 39ms。

### spec §15 甩过来、计划文本里没有对应任务的三件事

计划 04 的任务列表里没有它们，但 §15.2 / §15.5 明确写了「留给计划 04」。确认后一并做掉：

- **`RuleClassifier` 行置信度下限**（§15.5）。`MIN_LINE_CONFIDENCE = 0.5`，可注入。
- **`longnum` 长数字串兜底规则 + `SensitiveKind.LONG_NUMBER`**（§15.5）。
  规则表因此从 11 条变 12 条（8 打码 / 4 圈出），`OutlinedByDefaultRuleTest` 的两条
  完整性断言同步更新——**这条改动推翻了计划 03 出口里「恰好 11 条」的说法**。
- **Activity 重建的状态持久化**（§15.2）。`@Parcelize` + `SavedStateHandle`，
  `intake.clear()` 从 `onCleared` 挪到 `onImageChosen`。撤销栈刻意不持久化。
  没有按 §15.2 字面加「导出成功时清理」那一档——那会让用户改个样式再导出一次直接失败。

### 仍需人工过一遍的

- 缩放比跨过 0.5 时类型小标签的出现 / 消失（要双指捏合，adb 驱动不了）
- 真实银行 app / 聊天记录 / 证件照截图上的表现
- spec §13 要求的 200 张真实样本集尚未采集，`androidTest/assets/samples/` 为空，
  评测里那条测试会打印提示并跳过。采集说明见 `docs/eval-sample-set.md`。
  **因此 M2 的正式出口指标尚未达成**——合成集上的 1.00 只说明规则对自己造的输入自洽。
