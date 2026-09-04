# 有码安卓版 · 计划 03 · M2 上半：引擎、OCR 与规则层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建起识别管线：四个可替换接口、并行且永不整体失败的 `RedactionEngine`、ML Kit 文字识别接入、11 条带校验的敏感规则、候选合并。本计划结束时引擎能对一张图产出正确的候选列表，但还没接到 UI 上。

**Architecture:** 三个接口（`TextRecognizer` / `RegionDetector` / `SensitivityClassifier`）把「用谁的模型」和其余所有代码隔离开，**全应用只有 `buildEngine` 那一个函数知道用的是 ML Kit**。规则集是数据不是代码：`DefaultRuleSet` 是一个 `List<Rule>`，加一条规则不改任何逻辑。

**Tech Stack:** ML Kit text-recognition（bundled）· libphonenumber · Kotlin 协程 `async`

**Spec:** `docs/superpowers/specs/2026-09-02-youma-android-design.md`（重点 §4、§5、§6）

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-youma-android-00-index.md`

**前置:** 计划 01、02 全部完成

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/Interfaces.kt` | `TextRecognizer` / `RegionDetector` / `SensitivityClassifier` 三个接口 |
| `engine/RedactionEngine.kt` | 并行取证 + 逐项降级 + 合并 |
| `engine/EngineFactory.kt` | `buildEngine(context)` —— 全应用唯一知道模型出处的地方 |
| `engine/mlkit/MlKitTextRecognizer.kt` | ML Kit 输出 → `TextLine` + element 字符区间 |
| `rules/Rule.kt` | `Rule` 接口、`RuleMatch`、`RegexRule` |
| `rules/RuleClassifier.kt` | 跑规则、映射区间到四边形、产出候选 |
| `rules/DefaultRuleSet.kt` | 11 条规则的**数据**定义 |
| `rules/validator/Checksums.kt` | Luhn、IBAN mod-97、SSN 段、MRZ 校验位、Shannon 熵、UPS 校验位 |
| `rules/validator/KnownTlds.kt` | 邮箱域名的 TLD 白名单 |
| `rules/PhoneRule.kt` | libphonenumber 的 `findNumbers` 封装 |
| `engine/CandidateMerger.kt` | IoU 去重、同类相邻合并、按面积排序 |

---

### Task 16: 引擎接口与并行管线

**Files:**
- Create: `app/src/main/java/com/youma/app/engine/Interfaces.kt`
- Create: `app/src/main/java/com/youma/app/engine/RedactionEngine.kt`
- Create: `app/src/main/java/com/youma/app/engine/CandidateMerger.kt`（直通占位，Task 24 换成完整实现）
- Test: `app/src/test/java/com/youma/app/engine/RedactionEngineTest.kt`

**Interfaces:**
- Consumes: `SourceImage`、`TextLine`、`Candidate`、`AnalysisResult`（计划 01）
- Produces:
  - `interface TextRecognizer { val id: String; suspend fun recognize(image: SourceImage): List<TextLine> }`
  - `interface RegionDetector { val id: String; val kind: SensitiveKind; suspend fun detect(image: SourceImage): List<Candidate> }`
  - `interface SensitivityClassifier { val id: String; suspend fun isAvailable(): Boolean; suspend fun classify(lines: List<TextLine>): List<Candidate> }`
  - `class RedactionEngine(recognizer, regionDetectors, classifiers, merger)` + `suspend fun analyze(image: SourceImage): AnalysisResult`

**两条行为规格（spec §5.2）**：
- OCR、人脸、条码三条链路互不依赖，用 `async` 并行；
- **任何一条抛异常都降级为空结果，不让整次识别失败**——用户宁可少几个候选，也不愿意看到「识别失败」。OCR 失败时 `lines` 为空，规则层自然产出零候选，编辑器仍可用于手动打码。

`classifiers` 是 List 而不是单个实例，且每个实现都要回答 `isAvailable()`。这两点合起来就是全部的扩展机制。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/engine/RedactionEngineTest.kt`：

```kotlin
package com.youma.app.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedactionEngineTest {

    private fun image() = SourceImage(
        bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
        scale = 1f, originalWidth = 100, originalHeight = 100, mimeType = "image/jpeg",
    )

    private fun quad() = Quad.fromRect(RectF(0f, 0f, 10f, 10f))

    private fun candidate(id: String, kind: SensitiveKind = SensitiveKind.EMAIL) =
        Candidate(id, quad(), kind, DetectorSource.RULE, 0.9f)

    private class FakeRecognizer(
        val lines: List<TextLine> = emptyList(),
        val boom: Boolean = false,
        val delayMs: Long = 0,
    ) : TextRecognizer {
        override val id = "fake-ocr"
        override suspend fun recognize(image: SourceImage): List<TextLine> {
            delay(delayMs)
            if (boom) error("ocr exploded")
            return lines
        }
    }

    private class FakeDetector(
        override val kind: SensitiveKind,
        val out: List<Candidate>,
        val boom: Boolean = false,
    ) : RegionDetector {
        override val id = "fake-$kind"
        override suspend fun detect(image: SourceImage): List<Candidate> {
            if (boom) error("detector exploded")
            return out
        }
    }

    private class FakeClassifier(
        override val id: String,
        val available: Boolean,
        val out: List<Candidate>,
        val boom: Boolean = false,
    ) : SensitivityClassifier {
        var classifyCalls = 0
        override suspend fun isAvailable() = available
        override suspend fun classify(lines: List<TextLine>): List<Candidate> {
            classifyCalls++
            if (boom) error("classifier exploded")
            return out
        }
    }

    private fun engine(
        recognizer: TextRecognizer = FakeRecognizer(),
        detectors: List<RegionDetector> = emptyList(),
        classifiers: List<SensitivityClassifier> = emptyList(),
    ) = RedactionEngine(recognizer, detectors, classifiers, CandidateMerger())

    @Test
    fun `candidates from classifiers and detectors are combined`() = runTest {
        val result = engine(
            classifiers = listOf(FakeClassifier("c", true, listOf(candidate("a")))),
            detectors = listOf(FakeDetector(SensitiveKind.FACE, listOf(candidate("b", SensitiveKind.FACE)))),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `an exploding recognizer degrades to empty lines, not a failure`() = runTest {
        val result = engine(
            recognizer = FakeRecognizer(boom = true),
            detectors = listOf(FakeDetector(SensitiveKind.FACE, listOf(candidate("f", SensitiveKind.FACE)))),
        ).analyze(image())

        assertThat(result.lines).isEmpty()
        assertThat(result.candidates.map { it.id }).containsExactly("f")
    }

    @Test
    fun `an exploding detector does not take down the others`() = runTest {
        val result = engine(
            detectors = listOf(
                FakeDetector(SensitiveKind.FACE, emptyList(), boom = true),
                FakeDetector(SensitiveKind.BARCODE, listOf(candidate("bc", SensitiveKind.BARCODE))),
            ),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("bc")
    }

    @Test
    fun `an exploding classifier does not take down the others`() = runTest {
        val result = engine(
            classifiers = listOf(
                FakeClassifier("boom", true, emptyList(), boom = true),
                FakeClassifier("ok", true, listOf(candidate("ok1"))),
            ),
        ).analyze(image())

        assertThat(result.candidates.map { it.id }).containsExactly("ok1")
    }

    @Test
    fun `an unavailable classifier is skipped entirely`() = runTest {
        val off = FakeClassifier("off", available = false, out = listOf(candidate("never")))
        val result = engine(classifiers = listOf(off)).analyze(image())

        assertThat(off.classifyCalls).isEqualTo(0)
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `text and region pipelines run in parallel`() = runTest {
        // 两条链路各睡 200ms；串行会是 400ms，并行接近 200ms。
        // runTest 的虚拟时间让这条断言稳定。
        val start = currentTime
        engine(
            recognizer = FakeRecognizer(delayMs = 200),
            detectors = listOf(object : RegionDetector {
                override val id = "slow"
                override val kind = SensitiveKind.FACE
                override suspend fun detect(image: SourceImage): List<Candidate> {
                    delay(200); return emptyList()
                }
            }),
        ).analyze(image())
        assertThat(currentTime - start).isLessThan(350L)
    }

    @Test
    fun `recognized lines are passed through to the result`() = runTest {
        val line = TextLine(quad(), "hello", 0.8f, emptyList())
        val result = engine(recognizer = FakeRecognizer(lines = listOf(line))).analyze(image())
        assertThat(result.lines).containsExactly(line)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*RedactionEngineTest*'
```

预期：编译失败，`Unresolved reference: RedactionEngine`

- [ ] **Step 3: 实现接口**

`app/src/main/java/com/youma/app/engine/Interfaces.kt`：

```kotlin
package com.youma.app.engine

import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextLine

/**
 * 定位层（spec §4.3）。唯一职责：把一张图变成带坐标的文本行。
 * 首版实现 = MlKitTextRecognizer。PP-OCRv5 + ONNX Runtime 走这个接口接入，
 * 引擎和上层一行都不用改。
 */
interface TextRecognizer {
    val id: String
    suspend fun recognize(image: SourceImage): List<TextLine>
}

/**
 * 非文字区域检测。人脸、条码各一个实现，按 List 注入。
 * 以后加车牌检测 = 加一个实现 + 装配处 List 里加一项。
 */
interface RegionDetector {
    val id: String
    val kind: SensitiveKind
    suspend fun detect(image: SourceImage): List<Candidate>
}

/**
 * 判定层。输入文本行，输出候选区域。
 * 首版只有 RuleClassifier。规则永远在第一位且永不缺席。
 */
interface SensitivityClassifier {
    val id: String
    /** 运行时探测。模型没就绪、设备不支持、配额耗尽都返回 false，引擎跳过它。 */
    suspend fun isAvailable(): Boolean
    suspend fun classify(lines: List<TextLine>): List<Candidate>
}
```

`app/src/main/java/com/youma/app/engine/RedactionEngine.kt`：

```kotlin
package com.youma.app.engine

import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.AnalysisResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 识别引擎（spec §4.4）。
 *
 * 任何一条链路失败都降级为空结果，不让整次识别失败——
 * 用户宁可少几个候选，也不愿意看到「识别失败」。
 */
class RedactionEngine(
    private val recognizer: TextRecognizer,
    private val regionDetectors: List<RegionDetector>,
    private val classifiers: List<SensitivityClassifier>,
    private val merger: CandidateMerger = CandidateMerger(),
) {
    suspend fun analyze(image: SourceImage): AnalysisResult = coroutineScope {
        // 文字链路和区域检测互不依赖，并行跑
        val linesJob = async { recognizer.recognize(image) }
        val regionJobs = regionDetectors.map { d ->
            async { runCatching { d.detect(image) }.getOrElse { emptyList() } }
        }

        val lines = runCatching { linesJob.await() }.getOrElse { emptyList() }
        val fromText = classifiers
            .filter { it.isAvailable() }                    // 不可用的直接跳过
            .flatMap { c -> runCatching { c.classify(lines) }.getOrElse { emptyList() } }

        AnalysisResult(
            lines = lines,
            candidates = merger.merge(fromText + regionJobs.awaitAll().flatten()),
        )
    }
}
```

- [ ] **Step 4: 先放一个直通的 CandidateMerger，让引擎测试能跑**

`app/src/main/java/com/youma/app/engine/CandidateMerger.kt`（Task 25 会替换成完整实现）：

```kotlin
package com.youma.app.engine

import com.youma.app.core.model.Candidate

class CandidateMerger {
    fun merge(candidates: List<Candidate>): List<Candidate> = candidates
}
```

- [ ] **Step 5: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*RedactionEngineTest*'
git add app/src/main/java/com/youma/app/engine app/src/test/java/com/youma/app/engine
git commit -m "feat: add pluggable recognition engine with per-lane failure degradation"
```

预期：7 个测试全部 PASS。

---

### Task 17: ML Kit 文字识别接入

**Files:**
- Modify: `app/build.gradle.kts`（加 ML Kit 与协程 play-services 依赖）
- Create: `app/src/main/java/com/youma/app/engine/mlkit/MlKitTextRecognizer.kt`
- Test: `app/src/androidTest/java/com/youma/app/engine/mlkit/MlKitTextRecognizerTest.kt`

**Interfaces:**
- Consumes: `TextRecognizer`、`SourceImage`、`TextLine`、`TextElement`、`Quad`
- Produces: `class MlKitTextRecognizer : TextRecognizer`，`id = "mlkit-text-v2-latin"`

**拼接规则（决定 §5.3 全部行为）**：`TextLine.text` 由 **element 的 text 按阅读顺序用单个空格拼接**得到，同时记录每个 element 占据的字符区间。**不使用 ML Kit 自己的 `Text.Line.text`**——它与 element 拼接结果未必一致，而规则跑在拼接串上，两者不一致会让区间映射错位。

ML Kit 的 `cornerPoints` 可能为 null（旧版本或特殊输入），此时退回 `boundingBox`。

- [ ] **Step 1: 加依赖并确认权限断言仍然通过**

在 `app/build.gradle.kts` 的 `dependencies` 里加：

```kotlin
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)
```

然后**立刻**验证零权限承诺没被这个依赖破坏：

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

预期：仍然打印 `uses-permission = （空）`。若这里出现 `INTERNET`，说明拉到了 unbundled 变体——检查 artifact 名必须是 `com.google.mlkit:text-recognition`（bundled），而不是 `com.google.android.gms:play-services-mlkit-text-recognition`。**这一步失败不许绕过。**

- [ ] **Step 2: 写 instrumented 测试**

ML Kit 需要真机/模拟器。`app/src/androidTest/java/com/youma/app/engine/mlkit/MlKitTextRecognizerTest.kt`：

```kotlin
package com.youma.app.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.youma.app.core.image.SourceImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerTest {

    /** 渲染一张有确定文字的图，避免依赖外部素材。 */
    private fun render(vararg lines: String): SourceImage {
        val bmp = Bitmap.createBitmap(1000, 200 * lines.size + 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            lines.forEachIndexed { i, s -> drawText(s, 40f, 140f + i * 180f, paint) }
        }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    @Test
    fun recognizes_rendered_text() = runTest {
        val result = MlKitTextRecognizer().recognize(render("Hello YOUMA 4111"))
        assertThat(result).isNotEmpty()
        assertThat(result.joinToString(" ") { it.text }).contains("4111")
    }

    @Test
    fun line_text_equals_elements_joined_by_single_spaces() = runTest {
        val result = MlKitTextRecognizer().recognize(render("Card 4111 2222 3333"))
        result.forEach { line ->
            assertThat(line.text).isEqualTo(line.elements.joinToString(" ") { it.text })
        }
    }

    @Test
    fun every_element_range_indexes_its_own_text() = runTest {
        val result = MlKitTextRecognizer().recognize(render("alpha beta gamma"))
        result.forEach { line ->
            line.elements.forEach { e ->
                assertThat(line.text.substring(e.range.first, e.range.last + 1)).isEqualTo(e.text)
            }
        }
    }

    @Test
    fun quads_fall_inside_the_image() = runTest {
        val image = render("bounded text")
        MlKitTextRecognizer().recognize(image).forEach { line ->
            val b = line.quad.bounds()
            assertThat(b.left).isAtLeast(-1f)
            assertThat(b.top).isAtLeast(-1f)
            assertThat(b.right).isAtMost(image.width + 1f)
            assertThat(b.bottom).isAtMost(image.height + 1f)
        }
    }

    @Test
    fun quadForRange_lands_on_the_matched_word() = runTest {
        val lines = MlKitTextRecognizer().recognize(render("prefix 4111111111111111 suffix"))
        val line = lines.firstOrNull { it.text.contains("4111") } ?: return@runTest
        val idx = line.text.indexOf("4111")
        val q = line.quadForRange(idx until idx + 4)
        // 命中区域必须落在整行之内，且比整行窄
        assertThat(q.bounds().width()).isLessThan(line.quad.bounds().width() + 1f)
        assertThat(q.bounds().left).isAtLeast(line.quad.bounds().left - 1f)
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitTextRecognizerTest*'
```

预期：编译失败，`Unresolved reference: MlKitTextRecognizer`

- [ ] **Step 4: 实现 MlKitTextRecognizer**

`app/src/main/java/com/youma/app/engine/mlkit/MlKitTextRecognizer.kt`：

```kotlin
package com.youma.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.TextElement
import com.youma.app.core.model.TextLine
import com.youma.app.engine.TextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 文字识别（bundled 拉丁脚本）。
 *
 * 图像进来时已按 EXIF 转正（spec §5.1），所以 rotationDegrees 恒为 0——
 * 全流程只有一个坐标系。
 *
 * 拼接规则：TextLine.text = elements 的 text 用单个空格连接，同时记录字符区间。
 * 不用 ML Kit 自己的 Line.text：规则跑在拼接串上，两者不一致会让区间映射错位。
 */
class MlKitTextRecognizer : TextRecognizer {

    override val id = "mlkit-text-v2-latin"

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(image: SourceImage): List<TextLine> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val result = client.process(input).await()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { it.toTextLine() }
    }

    private fun Text.Line.toTextLine(): TextLine? {
        val els = elements.mapNotNull { el ->
            val q = el.quad() ?: return@mapNotNull null
            el.text to q
        }
        if (els.isEmpty()) return null

        val sb = StringBuilder()
        val out = ArrayList<TextElement>(els.size)
        els.forEachIndexed { i, (text, quad) ->
            if (i > 0) sb.append(' ')
            val start = sb.length
            sb.append(text)
            out += TextElement(quad, text, start until sb.length)
        }

        return TextLine(
            quad = quad() ?: Quad.boundingQuad(out.map { it.quad }),
            text = sb.toString(),
            confidence = confidence.takeIf { !it.isNaN() } ?: DEFAULT_CONFIDENCE,
            elements = out,
        )
    }

    private fun Text.Line.quad(): Quad? = cornerPoints?.toQuad() ?: boundingBox?.let { Quad.fromRect(RectF(it)) }
    private fun Text.Element.quad(): Quad? = cornerPoints?.toQuad() ?: boundingBox?.let { Quad.fromRect(RectF(it)) }

    private fun Array<android.graphics.Point>.toQuad(): Quad? {
        if (size < 4) return null
        return Quad(
            PointF(this[0].x.toFloat(), this[0].y.toFloat()),
            PointF(this[1].x.toFloat(), this[1].y.toFloat()),
            PointF(this[2].x.toFloat(), this[2].y.toFloat()),
            PointF(this[3].x.toFloat(), this[3].y.toFloat()),
        )
    }

    private companion object { const val DEFAULT_CONFIDENCE = 0.9f }
}
```

若当前 ML Kit 版本的 `Text.Line` 没有 `confidence` 属性，直接用 `DEFAULT_CONFIDENCE`，并把这一点记进提交信息。

- [ ] **Step 5: 运行确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitTextRecognizerTest*'
```

预期：5 个测试全部 PASS。

- [ ] **Step 6: 验掉 spec §15.1 与 §15.5**

**§15.1 —— bundled 是否真的完全不需要网络：**

```bash
adb shell svc wifi disable && adb shell svc data disable
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitTextRecognizerTest*'
adb shell svc wifi enable
```

预期：断网状态下测试**照常全绿**。若失败，说明拉到的不是 bundled 变体，回 Step 1 检查 artifact 名。把结论写进 spec §15 第 1 条。

**§15.5 —— 词级映射的实际粒度：**

用一张真实截图（银行 app 或订单页）跑一次识别，把每行的 `text` 与 `elements` 打出来看：卡号是被拆成多个 element，还是标签和值被合成一个 element。

```kotlin
// 临时加进 MlKitTextRecognizerTest，看完即删
@Test
fun dump_element_granularity() = runTest {
    MlKitTextRecognizer().recognize(render("Card: 4111 1111 1111 1111")).forEach { line ->
        android.util.Log.i("YOUMA", "line='${line.text}' elements=${line.elements.map { it.text }}")
    }
}
```

把观察到的实际切分写进 spec §15 第 5 条。**这个必须看真实数据，不能靠推断**——它直接决定遮罩范围是否符合预期。

- [ ] **Step 7: 提交**

```bash
git add app/build.gradle.kts app/src/main/java/com/youma/app/engine/mlkit app/src/androidTest/java/com/youma/app/engine/mlkit docs/superpowers/specs
git commit -m "feat: add bundled ML Kit latin text recognizer with element-range bookkeeping"
```

---

### Task 18: 校验器

**Files:**
- Create: `app/src/main/java/com/youma/app/rules/validator/Checksums.kt`
- Create: `app/src/main/java/com/youma/app/rules/validator/KnownTlds.kt`
- Test: `app/src/test/java/com/youma/app/rules/validator/ChecksumsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces：`object Checksums`
  - `fun luhn(digits: String): Boolean`
  - `fun ibanValid(raw: String): Boolean`
  - `fun ibanLengthForCountry(cc: String): Int?`
  - `fun ssnValid(raw: String): Boolean`
  - `fun mrzTd3Line1(line: String): Boolean`
  - `fun mrzTd3Line2Valid(line: String): Boolean`
  - `fun shannonEntropy(s: String): Double`
  - `fun upsCheckDigit(tracking: String): Boolean`
  - `fun macSeparatorConsistent(raw: String): Boolean`
  - `object KnownTlds { fun isKnown(tld: String): Boolean }`

**只有正则没有校验的规则误报率高到不可用**（spec §6 开头）。这个任务把全部校验位单独实现并单独测，规则表只负责把它们串起来。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/rules/validator/ChecksumsTest.kt`：

```kotlin
package com.youma.app.rules.validator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChecksumsTest {

    // ---------- Luhn ----------
    @Test fun `luhn accepts well-known test card numbers`() {
        assertThat(Checksums.luhn("4111111111111111")).isTrue()   // Visa
        assertThat(Checksums.luhn("5500005555555559")).isTrue()   // Mastercard
        assertThat(Checksums.luhn("378282246310005")).isTrue()    // Amex
    }

    @Test fun `luhn rejects a number with one digit changed`() {
        assertThat(Checksums.luhn("4111111111111112")).isFalse()
    }

    @Test fun `luhn rejects non-digits and empty input`() {
        assertThat(Checksums.luhn("")).isFalse()
        assertThat(Checksums.luhn("41111111111111x1")).isFalse()
    }

    @Test fun `luhn rejects an all-zero run that would otherwise pass`() {
        assertThat(Checksums.luhn("0000000000000000")).isFalse()
    }

    // ---------- IBAN ----------
    @Test fun `iban accepts valid numbers with and without spaces`() {
        assertThat(Checksums.ibanValid("DE89370400440532013000")).isTrue()
        assertThat(Checksums.ibanValid("DE89 3704 0044 0532 0130 00")).isTrue()
        assertThat(Checksums.ibanValid("GB82WEST12345698765432")).isTrue()
    }

    @Test fun `iban rejects a wrong check digit`() {
        assertThat(Checksums.ibanValid("DE88370400440532013000")).isFalse()
    }

    @Test fun `iban rejects a wrong length for the country`() {
        assertThat(Checksums.ibanValid("DE8937040044053201300")).isFalse()
    }

    @Test fun `iban rejects an unknown country code`() {
        assertThat(Checksums.ibanValid("ZZ89370400440532013000")).isFalse()
        assertThat(Checksums.ibanLengthForCountry("DE")).isEqualTo(22)
    }

    // ---------- SSN ----------
    @Test fun `ssn accepts a normal number`() {
        assertThat(Checksums.ssnValid("123-45-6789")).isTrue()
    }

    @Test fun `ssn rejects invalid area group and serial segments`() {
        assertThat(Checksums.ssnValid("000-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("666-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("900-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("123-00-6789")).isFalse()
        assertThat(Checksums.ssnValid("123-45-0000")).isFalse()
    }

    // ---------- MRZ ----------
    @Test fun `mrz line one is recognized by its format`() {
        val l1 = "P<USADOE<<JOHN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        assertThat(l1.length).isEqualTo(44)
        assertThat(Checksums.mrzTd3Line1(l1)).isTrue()
        assertThat(Checksums.mrzTd3Line1("NOT AN MRZ LINE")).isFalse()
    }

    @Test fun `mrz line two validates its own check digits`() {
        // 护照号 L898902C3 校验位 6；生日 740812 校验位 2；有效期 120415 校验位 9
        val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(l2.length).isEqualTo(44)
        assertThat(Checksums.mrzTd3Line2Valid(l2)).isTrue()
    }

    @Test fun `mrz line two with a broken check digit is rejected`() {
        val broken = "L898902C31UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(Checksums.mrzTd3Line2Valid(broken)).isFalse()
    }

    // ---------- 熵 ----------
    @Test fun `entropy is low for repeated characters and high for random ones`() {
        assertThat(Checksums.shannonEntropy("aaaaaaaaaaaaaaaa")).isLessThan(1.0)
        assertThat(Checksums.shannonEntropy("aB3xQ9zL2mR7tK1v")).isGreaterThan(3.5)
    }

    // ---------- UPS ----------
    @Test fun `ups check digit validates a real-format tracking number`() {
        assertThat(Checksums.upsCheckDigit("1Z9999W99999999999".let { it })).isFalse()  // 随手编的必然不过
        assertThat(Checksums.upsCheckDigit("1Z12345E0205271688")).isTrue()
    }

    // ---------- MAC ----------
    @Test fun `mac separator consistency`() {
        assertThat(Checksums.macSeparatorConsistent("00:1A:2B:3C:4D:5E")).isTrue()
        assertThat(Checksums.macSeparatorConsistent("00-1A-2B-3C-4D-5E")).isTrue()
        assertThat(Checksums.macSeparatorConsistent("00:1A-2B:3C-4D:5E")).isFalse()
    }

    // ---------- TLD ----------
    @Test fun `known tlds cover common gtlds and any two-letter cctld`() {
        assertThat(KnownTlds.isKnown("com")).isTrue()
        assertThat(KnownTlds.isKnown("dev")).isTrue()
        assertThat(KnownTlds.isKnown("de")).isTrue()
        assertThat(KnownTlds.isKnown("zzzz")).isFalse()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChecksumsTest*'
```

预期：编译失败，`Unresolved reference: Checksums`

- [ ] **Step 3: 实现校验器**

`app/src/main/java/com/youma/app/rules/validator/Checksums.kt`：

```kotlin
package com.youma.app.rules.validator

import java.math.BigInteger
import kotlin.math.ln

/**
 * 规则表的校验步骤（spec §6）。
 * 只有正则没有校验的规则误报率高到不可用，所以每条规则都必须配一个这里的函数。
 */
object Checksums {

    // ---------- 支付卡：Luhn ----------
    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        if (!digits.all { it.isDigit() }) return false
        if (digits.all { it == '0' }) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    // ---------- IBAN：长度查表 + 重排 mod-97 ----------
    fun ibanValid(raw: String): Boolean {
        val s = raw.filterNot { it == ' ' || it == '-' }.uppercase()
        if (s.length < 5) return false
        val cc = s.take(2)
        if (!cc.all { it in 'A'..'Z' }) return false
        val expected = ibanLengthForCountry(cc) ?: return false
        if (s.length != expected) return false
        if (!s.drop(2).take(2).all { it.isDigit() }) return false
        if (!s.drop(4).all { it.isLetterOrDigit() }) return false

        val rearranged = s.drop(4) + s.take(4)
        val numeric = buildString {
            rearranged.forEach { c ->
                if (c.isDigit()) append(c) else append((c - 'A') + 10)
            }
        }
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    fun ibanLengthForCountry(cc: String): Int? = IBAN_LENGTHS[cc]

    // ---------- SSN：排除无效段 ----------
    fun ssnValid(raw: String): Boolean {
        val m = SSN_SHAPE.matchEntire(raw.trim()) ?: return false
        val area = m.groupValues[1].toInt()
        val group = m.groupValues[2].toInt()
        val serial = m.groupValues[3].toInt()
        if (area == 0 || area == 666 || area >= 900) return false
        if (group == 0) return false
        if (serial == 0) return false
        return true
    }

    // ---------- 护照 MRZ（TD3，两行各 44 字符） ----------
    fun mrzTd3Line1(line: String): Boolean =
        line.length == 44 && MRZ_L1.matches(line)

    fun mrzTd3Line2Valid(line: String): Boolean {
        if (line.length != 44) return false
        if (!line.all { it in 'A'..'Z' || it.isDigit() || it == '<' }) return false
        // 护照号 0..8 校验位 9；生日 13..18 校验位 19；有效期 21..26 校验位 27
        return mrzCheck(line.substring(0, 9)) == line[9] &&
            mrzCheck(line.substring(13, 19)) == line[19] &&
            mrzCheck(line.substring(21, 27)) == line[27]
    }

    private fun mrzCheck(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        field.forEachIndexed { i, c ->
            val v = when {
                c.isDigit() -> c - '0'
                c == '<' -> 0
                c in 'A'..'Z' -> c - 'A' + 10
                else -> return '?'
            }
            sum += v * weights[i % 3]
        }
        return '0' + (sum % 10)
    }

    // ---------- API key：Shannon 熵 ----------
    fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>()
        s.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var h = 0.0
        counts.values.forEach { c ->
            val p = c.toDouble() / s.length
            h -= p * (ln(p) / ln(2.0))
        }
        return h
    }

    // ---------- UPS 快递单号校验位 ----------
    fun upsCheckDigit(tracking: String): Boolean {
        val s = tracking.uppercase()
        if (s.length != 18 || !s.startsWith("1Z")) return false
        val body = s.substring(2, 17)
        val check = s[17]
        if (!check.isDigit()) return false
        var sum = 0
        body.forEachIndexed { i, c ->
            val v = if (c.isDigit()) c - '0' else ((c - 'A') + 2) % 10
            sum += if (i % 2 == 0) v else v * 2
        }
        val expected = (10 - sum % 10) % 10
        return expected == check - '0'
    }

    // ---------- MAC：分隔符一致性 ----------
    fun macSeparatorConsistent(raw: String): Boolean {
        val seps = raw.filter { it == ':' || it == '-' }
        return seps.length == 5 && seps.toSet().size == 1
    }

    private val SSN_SHAPE = Regex("""^(\d{3})-(\d{2})-(\d{4})$""")
    private val MRZ_L1 = Regex("""^P[A-Z<][A-Z<]{3}[A-Z<]{39}$""")

    /** 常见国家的 IBAN 长度。缺的国家会被判为无效——宁可漏检 IBAN，也不要误报一串数字。 */
    private val IBAN_LENGTHS = mapOf(
        "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16,
        "BG" to 22, "BH" to 22, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28,
        "CZ" to 24, "DE" to 22, "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24,
        "FI" to 18, "FO" to 18, "FR" to 27, "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18,
        "GR" to 27, "GT" to 28, "HR" to 21, "HU" to 28, "IE" to 22, "IL" to 23, "IQ" to 23,
        "IS" to 26, "IT" to 27, "JO" to 30, "KW" to 30, "KZ" to 20, "LB" to 28, "LC" to 32,
        "LI" to 21, "LT" to 20, "LU" to 20, "LV" to 21, "LY" to 25, "MC" to 27, "MD" to 24,
        "ME" to 22, "MK" to 19, "MR" to 27, "MT" to 31, "MU" to 30, "NL" to 18, "NO" to 15,
        "PK" to 24, "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22,
        "SA" to 24, "SC" to 31, "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "ST" to 25,
        "SV" to 28, "TL" to 23, "TN" to 24, "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24,
        "XK" to 20,
    )
}
```

`app/src/main/java/com/youma/app/rules/validator/KnownTlds.kt`：

```kotlin
package com.youma.app.rules.validator

/**
 * 邮箱与 URL 的域名后缀白名单（spec §6：EMAIL 的校验是「域名必须有已知 TLD」）。
 *
 * 不追求完整——完整的 TLD 表要 1500 多条，而误报一个 `foo@bar.baz` 只是麻烦。
 * 所有两字母后缀一律视为国家码，这覆盖了绝大多数长尾。
 */
object KnownTlds {

    fun isKnown(tld: String): Boolean {
        val t = tld.lowercase()
        if (t.length == 2 && t.all { it in 'a'..'z' }) return true    // 任意 ccTLD
        return COMMON.contains(t)
    }

    private val COMMON = setOf(
        "com", "org", "net", "edu", "gov", "mil", "int", "info", "biz", "name", "pro",
        "app", "dev", "io", "ai", "co", "me", "tv", "cc", "xyz", "site", "online", "store",
        "tech", "cloud", "email", "blog", "shop", "live", "news", "media", "digital",
        "agency", "studio", "design", "software", "systems", "network", "solutions",
        "company", "group", "team", "world", "life", "today", "space", "website", "page",
        "link", "click", "fun", "wiki", "top", "vip", "one", "art", "inc", "ltd", "llc",
    )
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChecksumsTest*'
```

预期：全部 PASS。

若 `ups check digit` 或 `mrz line two` 的样例数值不通过，**先检查是不是测试里的样例编错了**——MRZ 的官方样例 `L898902C36UTO7408122F1204159ZE184226B<<<<<10` 与 UPS 的 `1Z12345E0205271688` 都是公开文档里的标准样例，实现正确时必然通过。若确认样例无误而实现不过，修实现。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/youma/app/rules/validator app/src/test/java/com/youma/app/rules/validator
git commit -m "feat: add Luhn, IBAN mod-97, SSN, MRZ, entropy, UPS and MAC validators"
```

---

### Task 19: 规则框架与 RuleClassifier

**Files:**
- Create: `app/src/main/java/com/youma/app/rules/Rule.kt`
- Create: `app/src/main/java/com/youma/app/rules/RuleClassifier.kt`
- Create: `app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`（先只放一条规则，Task 20-23 逐条补齐）
- Test: `app/src/test/java/com/youma/app/rules/RuleClassifierTest.kt`

**Interfaces:**
- Consumes: `TextLine`、`Candidate`、`SensitivityClassifier`
- Produces:
  - `data class RuleMatch(val range: IntRange, val confidence: Float)`
  - `interface Rule { val kind: SensitiveKind; val enabledByDefault: Boolean; val id: String; fun findIn(text: String): List<RuleMatch> }`
  - `class RegexRule(id, kind, enabledByDefault, pattern, confidence, validate: (text: String, m: MatchResult) -> Boolean, select: (MatchResult) -> IntRange = { it.range }) : Rule`

`validate` 收下整行文本作为第一个参数：IP 规则要靠前文判断「这是不是版本号」，而 Kotlin 的 `MatchResult` 不暴露原串。**一开始就用这个签名，不要等到写 IP 规则时再回头改十一处。**
  - `class RuleClassifier(rules: List<Rule>) : SensitivityClassifier`，`id = "rule"`，`isAvailable() = true`
  - `object DefaultRuleSet { val rules: List<Rule> }`

**规则集是数据不是代码**（spec §6）：每条 `Rule` 携带 `pattern`、`validator`、`kind` 和 `enabledByDefault`，加一条规则不改任何逻辑。

`select` 这个参数是为 URL 规则准备的——URL 只遮 query 与 path，域名保留，所以匹配区间和遮罩区间不是同一个。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/rules/RuleClassifierTest.kt`：

```kotlin
package com.youma.app.rules

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextElement
import com.youma.app.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleClassifierTest {

    /** 每个词一个 element，等宽 50px，间距 10px。 */
    private fun line(text: String): TextLine {
        var cursor = 0
        var x = 0f
        val els = text.split(" ").map { w ->
            val start = cursor
            cursor += w.length + 1
            val e = TextElement(Quad.fromRect(RectF(x, 0f, x + 50f, 20f)), w, start until start + w.length)
            x += 60f
            e
        }
        return TextLine(Quad.fromRect(RectF(0f, 0f, x, 20f)), text, 0.95f, els)
    }

    private val digits = RegexRule(
        id = "test-digits",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        pattern = Regex("""\d{4}"""),
        confidence = 0.9f,
        validate = { _, _ -> true },
    )

    @Test
    fun `a matching rule produces a candidate`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("code 1234 end")))
        assertThat(out).hasSize(1)
        assertThat(out.single().kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
        assertThat(out.single().source).isEqualTo(DetectorSource.RULE)
    }

    @Test
    fun `the candidate quad lands on the matched word`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("code 1234 end")))
        // "1234" 是第二个词：x 从 60 到 110
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(60f, 0f, 110f, 20f))
    }

    @Test
    fun `a failing validator suppresses the candidate`() = runTest {
        val never = RegexRule("no", SensitiveKind.PAYMENT_CARD, true, Regex("""\d{4}"""), 0.9f, validate = { _, _ -> false })
        assertThat(RuleClassifier(listOf(never)).classify(listOf(line("code 1234 end")))).isEmpty()
    }

    @Test
    fun `enabledByDefault flows from the rule to the candidate`() = runTest {
        val outlined = RegexRule("o", SensitiveKind.URL, enabledByDefault = false, Regex("""\d{4}"""), 0.7f, { _, _ -> true })
        assertThat(RuleClassifier(listOf(outlined)).classify(listOf(line("x 1234"))).single().enabledByDefault)
            .isFalse()
    }

    @Test
    fun `select narrows the masked range without changing the match`() = runTest {
        // 匹配 "ab1234"，但只遮后四位
        val narrow = RegexRule(
            id = "narrow", kind = SensitiveKind.URL, enabledByDefault = true,
            pattern = Regex("""ab\d{4}"""), confidence = 0.8f, validate = { _, _ -> true },
            select = { m -> (m.range.first + 2)..m.range.last },
        )
        val out = RuleClassifier(listOf(narrow)).classify(listOf(line("ab1234 tail")))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `multiple matches in one line all become candidates`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1111 2222 3333")))
        assertThat(out).hasSize(3)
    }

    @Test
    fun `candidate ids are unique across lines and rules`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1111 2222"), line("3333 4444")))
        assertThat(out.map { it.id }.toSet()).hasSize(4)
    }

    @Test
    fun `an exploding rule does not take down the classifier`() = runTest {
        val boom = object : Rule {
            override val id = "boom"
            override val kind = SensitiveKind.EMAIL
            override val enabledByDefault = true
            override fun findIn(text: String): List<RuleMatch> = error("rule exploded")
        }
        val out = RuleClassifier(listOf(boom, digits)).classify(listOf(line("code 1234")))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `classifier is always available`() = runTest {
        assertThat(RuleClassifier(emptyList()).isAvailable()).isTrue()
    }

    @Test
    fun `empty lines produce no candidates`() = runTest {
        assertThat(RuleClassifier(listOf(digits)).classify(emptyList())).isEmpty()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*RuleClassifierTest*'
```

预期：编译失败，`Unresolved reference: RegexRule`

- [ ] **Step 3: 实现规则框架**

`app/src/main/java/com/youma/app/rules/Rule.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind

data class RuleMatch(val range: IntRange, val confidence: Float)

/**
 * 一条敏感规则。规则集是数据不是代码（spec §6）——
 * 加一条规则 = 往 DefaultRuleSet.rules 里加一个对象，不改任何逻辑。
 */
interface Rule {
    val id: String
    val kind: SensitiveKind
    /** 进编辑器时的初始状态。按误报率划线，不按危害划线（spec §6）。 */
    val enabledByDefault: Boolean
    fun findIn(text: String): List<RuleMatch>
}

/**
 * 正则 + 校验的通用规则。
 *
 * @param select 从匹配里挑出**要遮的**区间。默认是整个匹配；
 *               URL 规则用它做到「只遮 query 与 path，域名保留」。
 */
class RegexRule(
    override val id: String,
    override val kind: SensitiveKind,
    override val enabledByDefault: Boolean,
    private val pattern: Regex,
    private val confidence: Float,
    private val validate: (text: String, m: MatchResult) -> Boolean,
    private val select: (MatchResult) -> IntRange = { it.range },
) : Rule {
    override fun findIn(text: String): List<RuleMatch> =
        pattern.findAll(text)
            .filter { validate(text, it) }
            .mapNotNull { m ->
                val r = select(m)
                if (r.isEmpty() || r.first < 0 || r.last >= text.length) null
                else RuleMatch(r, confidence)
            }
            .toList()
}
```

`app/src/main/java/com/youma/app/rules/RuleClassifier.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.TextLine
import com.youma.app.engine.SensitivityClassifier

/**
 * 判定层的规则实现（spec §4.3）。
 * 规则永远在 classifiers 列表的第一位且永不缺席——它是全部识别能力的地基。
 */
class RuleClassifier(private val rules: List<Rule>) : SensitivityClassifier {

    override val id = "rule"

    /** 规则不依赖任何模型或网络，永远可用。 */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val out = ArrayList<Candidate>()
        lines.forEachIndexed { lineIndex, line ->
            rules.forEach { rule ->
                // 单条规则出错不能拖垮整次判定
                val matches = runCatching { rule.findIn(line.text) }.getOrElse { emptyList() }
                matches.forEachIndexed { i, m ->
                    out += Candidate(
                        id = "rule-${rule.id}-$lineIndex-${m.range.first}-$i",
                        quad = line.quadForRange(m.range),
                        kind = rule.kind,
                        source = DetectorSource.RULE,
                        confidence = m.confidence,
                        enabledByDefault = rule.enabledByDefault,
                    )
                }
            }
        }
        return out
    }
}
```

`app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`（占位，Task 20-23 填满）：

```kotlin
package com.youma.app.rules

/**
 * 首版的全部规则（spec §6）。11 条，每条都有校验步骤。
 *
 * 「默认」按**误报率**划线，不按危害划线：高误报类型自动打码会让用户
 * 一直在跟 app 对着干；有校验位兜底的类型误报接近零，自动打码不打扰任何人。
 */
object DefaultRuleSet {
    val rules: List<Rule> = emptyList()      // Task 20-23 逐条填入
}
```

- [ ] **Step 4: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*RuleClassifierTest*'
git add app/src/main/java/com/youma/app/rules app/src/test/java/com/youma/app/rules
git commit -m "feat: add data-driven rule framework and rule classifier"
```

预期：10 个测试全部 PASS。

---

### Task 20: 高置信规则（支付卡、IBAN、SSN、MAC）

**Files:**
- Modify: `app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`
- Test: `app/src/test/java/com/youma/app/rules/DefaultRuleSetHighConfidenceTest.kt`

**Interfaces:**
- Consumes: `RegexRule`、`Checksums`
- Produces: `DefaultRuleSet.rules` 里的四条规则，`id` 分别为 `card`、`iban`、`ssn`、`mac`，`enabledByDefault = true`

| 类型 | 匹配 | 校验 | 默认 |
|---|---|---|---|
| PAYMENT_CARD | 13–19 位数字，容忍空格与连字符分组 | Luhn 校验和 | 打码 |
| IBAN | 2 位国家码 + 2 位校验位 + BBAN，长度查国家表 | 重排后 mod-97 == 1 | 打码 |
| SSN | `\d{3}-\d{2}-\d{4}` | 排除 000 / 666 / 9xx 开头等无效段 | 打码 |
| MAC_ADDR | 六组两位十六进制 | 分隔符一致性 | 打码 |

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/rules/DefaultRuleSetHighConfidenceTest.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultRuleSetHighConfidenceTest {

    private fun rule(id: String): Rule =
        DefaultRuleSet.rules.first { it.id == id }

    private fun matched(id: String, text: String): List<String> =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    // ---------- 支付卡 ----------
    @Test fun `card matches plain grouped and hyphenated numbers`() {
        assertThat(matched("card", "pay 4111111111111111 now")).containsExactly("4111111111111111")
        assertThat(matched("card", "4111 1111 1111 1111")).containsExactly("4111 1111 1111 1111")
        assertThat(matched("card", "4111-1111-1111-1111")).containsExactly("4111-1111-1111-1111")
    }

    @Test fun `card rejects a luhn failure`() {
        assertThat(matched("card", "4111111111111112")).isEmpty()
    }

    @Test fun `card rejects a phone-length digit run`() {
        assertThat(matched("card", "call 4155551234")).isEmpty()      // 10 位，长度不够
    }

    @Test fun `card is masked by default`() {
        assertThat(rule("card").enabledByDefault).isTrue()
        assertThat(rule("card").kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
    }

    // ---------- IBAN ----------
    @Test fun `iban matches with and without spaces`() {
        assertThat(matched("iban", "IBAN DE89370400440532013000 ok")).containsExactly("DE89370400440532013000")
        assertThat(matched("iban", "DE89 3704 0044 0532 0130 00")).containsExactly("DE89 3704 0044 0532 0130 00")
    }

    @Test fun `iban rejects a bad check digit`() {
        assertThat(matched("iban", "DE88370400440532013000")).isEmpty()
    }

    // ---------- SSN ----------
    @Test fun `ssn matches a valid number and rejects invalid segments`() {
        assertThat(matched("ssn", "SSN 123-45-6789")).containsExactly("123-45-6789")
        assertThat(matched("ssn", "666-45-6789")).isEmpty()
        assertThat(matched("ssn", "123-00-6789")).isEmpty()
    }

    // ---------- MAC ----------
    @Test fun `mac matches consistent separators only`() {
        assertThat(matched("mac", "mac 00:1A:2B:3C:4D:5E")).containsExactly("00:1A:2B:3C:4D:5E")
        assertThat(matched("mac", "00-1A-2B-3C-4D-5E")).containsExactly("00-1A-2B-3C-4D-5E")
        assertThat(matched("mac", "00:1A-2B:3C-4D:5E")).isEmpty()
    }

    @Test fun `all four high-confidence rules default to masked`() {
        listOf("card", "iban", "ssn", "mac").forEach {
            assertThat(rule(it).enabledByDefault).isTrue()
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*DefaultRuleSetHighConfidenceTest*'
```

预期：FAIL —— `NoSuchElementException`（规则表还是空的）

- [ ] **Step 3: 填入四条规则**

`app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.youma.app.rules.validator.Checksums

object DefaultRuleSet {

    // ---------- 高置信：有校验位兜底，误报接近零，默认打码 ----------

    private val CARD = RegexRule(
        id = "card",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        // 13–19 位数字，容忍空格与连字符分组；前后不能紧邻数字
        pattern = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.luhn(m.value.filter { it.isDigit() }) },
    )

    private val IBAN = RegexRule(
        id = "iban",
        kind = SensitiveKind.IBAN,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Z0-9])[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{1,4}){2,8}(?![A-Z0-9])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ibanValid(m.value) },
    )

    private val SSN = RegexRule(
        id = "ssn",
        kind = SensitiveKind.SSN,
        enabledByDefault = true,
        pattern = Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ssnValid(m.value) },
    )

    private val MAC = RegexRule(
        id = "mac",
        kind = SensitiveKind.MAC_ADDR,
        enabledByDefault = true,
        pattern = Regex("""(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.macSeparatorConsistent(m.value) },
    )

    val rules: List<Rule> = listOf(CARD, IBAN, SSN, MAC)
}
```

- [ ] **Step 4: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*DefaultRuleSetHighConfidenceTest*'
git add app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt app/src/test/java/com/youma/app/rules/DefaultRuleSetHighConfidenceTest.kt
git commit -m "feat: add payment card, IBAN, SSN and MAC rules with checksum validation"
```

预期：全部 PASS。

---

### Task 21: 电话与邮箱

**Files:**
- Modify: `app/build.gradle.kts`（加 libphonenumber）
- Create: `app/src/main/java/com/youma/app/rules/PhoneRule.kt`
- Modify: `app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`
- Test: `app/src/test/java/com/youma/app/rules/PhoneAndEmailRuleTest.kt`

**Interfaces:**
- Consumes: `Rule`、`RuleMatch`、`KnownTlds`
- Produces:
  - `class PhoneRule(defaultRegion: String = "US") : Rule`，`id = "phone"`
  - `DefaultRuleSet` 里的 `email` 规则

电话交给 libphonenumber 的 `findNumbers`——**正则做不了这件事**。校验用 `isValidNumber`，并排除明显的订单号：同一行里出现 order / invoice / 订单 / 单号 等提示词时，无国际前缀（`+`）的匹配一律丢弃。

- [ ] **Step 1: 加依赖并复查权限断言**

```kotlin
    implementation(libs.libphonenumber)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

- [ ] **Step 2: 写失败的测试**

`app/src/test/java/com/youma/app/rules/PhoneAndEmailRuleTest.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneAndEmailRuleTest {

    private fun matched(rule: Rule, text: String): List<String> =
        rule.findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    private val phone = PhoneRule(defaultRegion = "US")
    private val email = DefaultRuleSet.rules.first { it.id == "email" }

    // ---------- 电话 ----------
    @Test fun `phone finds an international number`() {
        assertThat(matched(phone, "call +1 415 555 2671 today")).contains("+1 415 555 2671")
    }

    @Test fun `phone finds a national number in the default region`() {
        assertThat(matched(phone, "call (415) 555-2671")).isNotEmpty()
    }

    @Test fun `phone rejects an invalid number`() {
        assertThat(matched(phone, "call 000 000 0000")).isEmpty()
    }

    @Test fun `phone rejects a national-format match on an order line`() {
        assertThat(matched(phone, "Order no. 4155552671")).isEmpty()
        assertThat(matched(phone, "订单号 4155552671")).isEmpty()
    }

    @Test fun `phone keeps an international number even on an order line`() {
        // 带 + 的号码不会是订单号
        assertThat(matched(phone, "Order 123, call +14155552671")).isNotEmpty()
    }

    @Test fun `phone is masked by default`() {
        assertThat(phone.enabledByDefault).isTrue()
        assertThat(phone.kind).isEqualTo(SensitiveKind.PHONE)
    }

    // ---------- 邮箱 ----------
    @Test fun `email matches common addresses`() {
        assertThat(matched(email, "write to alice@example.com now")).containsExactly("alice@example.com")
        assertThat(matched(email, "a.b+tag@sub.example.co.uk")).containsExactly("a.b+tag@sub.example.co.uk")
    }

    @Test fun `email rejects an unknown tld`() {
        assertThat(matched(email, "bogus@example.zzzz")).isEmpty()
    }

    @Test fun `email rejects a bare at sign`() {
        assertThat(matched(email, "meet @alice at noon")).isEmpty()
    }

    @Test fun `email is masked by default`() {
        assertThat(email.enabledByDefault).isTrue()
        assertThat(email.kind).isEqualTo(SensitiveKind.EMAIL)
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*PhoneAndEmailRuleTest*'
```

预期：编译失败，`Unresolved reference: PhoneRule`

- [ ] **Step 4: 实现 PhoneRule 与 email 规则**

`app/src/main/java/com/youma/app/rules/PhoneRule.kt`：

```kotlin
package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * 电话号码（spec §6）：交给 libphonenumber 的 findNumbers，正则做不了这件事。
 *
 * 校验 = isValidNumber，外加排除明显的订单号：
 * 同一行出现 order / invoice / 订单 / 单号 等提示词时，无国际前缀的匹配一律丢弃。
 */
class PhoneRule(
    private val defaultRegion: String = Locale.getDefault().country.ifBlank { "US" },
) : Rule {

    override val id = "phone"
    override val kind = SensitiveKind.PHONE
    override val enabledByDefault = true

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    override fun findIn(text: String): List<RuleMatch> {
        val orderish = ORDER_HINTS.any { text.contains(it, ignoreCase = true) }
        return util.findNumbers(text, defaultRegion, PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
            .asSequence()
            .filter { util.isValidNumber(it.number()) }
            .filter { !orderish || it.rawString().trimStart().startsWith("+") }
            .map { RuleMatch(it.start() until it.end(), CONFIDENCE) }
            .toList()
    }

    private companion object {
        const val CONFIDENCE = 0.9f
        val ORDER_HINTS = listOf(
            "order", "invoice", "receipt", "ref no", "ref.", "tracking",
            "订单", "单号", "流水", "发票",
        )
    }
}
```

在 `DefaultRuleSet` 里加入 email 规则并把 PhoneRule 挂进列表：

```kotlin
import com.youma.app.rules.validator.KnownTlds

    private val EMAIL = RegexRule(
        id = "email",
        kind = SensitiveKind.EMAIL,
        enabledByDefault = true,
        // RFC 5322 简化式
        pattern = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.([A-Za-z]{2,24})(?![A-Za-z0-9.-])"""),
        confidence = 0.9f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) },
    )

    val rules: List<Rule> = listOf(CARD, IBAN, SSN, MAC, EMAIL, PhoneRule())
```

- [ ] **Step 5: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*PhoneAndEmailRuleTest*' --tests '*DefaultRuleSetHighConfidenceTest*'
git add app/build.gradle.kts app/src/main/java/com/youma/app/rules app/src/test/java/com/youma/app/rules
git commit -m "feat: add libphonenumber-backed phone rule and TLD-validated email rule"
```

预期：全部 PASS。

---

### Task 22: 护照 MRZ 与 API key

**Files:**
- Modify: `app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`
- Test: `app/src/test/java/com/youma/app/rules/PassportAndApiKeyRuleTest.kt`

**Interfaces:**
- Consumes: `Checksums.mrzTd3Line1` / `mrzTd3Line2Valid` / `shannonEntropy`
- Produces: `DefaultRuleSet` 里 `id = "passport"` 与 `id = "apikey"` 的两条规则，均 `enabledByDefault = true`

| 类型 | 匹配 | 校验 | 默认 |
|---|---|---|---|
| PASSPORT | 优先识别 MRZ 两行（各 44 字符） | MRZ 自带校验位 | 打码 |
| API_KEY | 已知前缀（`sk-`、`ghp_`、`AKIA`、JWT 的 `eyJ`） | Shannon 熵 > 3.5 | 打码 |

MRZ 的两行性质不同：第一行只能靠格式判断（`P<` 开头 + 44 字符），第二行自带三个校验位。**两行都遮**——第一行含姓名，第二行含护照号与生日。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/rules/PassportAndApiKeyRuleTest.kt`：

```kotlin
package com.youma.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PassportAndApiKeyRuleTest {

    private fun rule(id: String) = DefaultRuleSet.rules.first { it.id == id }
    private fun matched(id: String, text: String) =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    private val mrzL1 = "P<USADOE<<JOHN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
    private val mrzL2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    @Test fun `passport matches mrz line one by format`() {
        assertThat(matched("passport", mrzL1)).containsExactly(mrzL1)
    }

    @Test fun `passport matches mrz line two by its check digits`() {
        assertThat(matched("passport", mrzL2)).containsExactly(mrzL2)
    }

    @Test fun `passport rejects a line two with a broken check digit`() {
        val broken = "L898902C31UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(matched("passport", broken)).isEmpty()
    }

    @Test fun `passport ignores ordinary text of the same length`() {
        val filler = "THIS IS JUST A NORMAL SENTENCE OF LETTERS OK"
        assertThat(filler.length).isEqualTo(44)
        assertThat(matched("passport", filler)).isEmpty()
    }

    @Test fun `api key matches known prefixes`() {
        assertThat(matched("apikey", "key sk-aB3xQ9zL2mR7tK1vP0wY8n end")).hasSize(1)
        assertThat(matched("apikey", "ghp_aB3xQ9zL2mR7tK1vP0wY8nJ4hG2fD6sA1")).hasSize(1)
        assertThat(matched("apikey", "AKIAIOSFODNN7EXAMPLE")).hasSize(1)
    }

    @Test fun `api key matches a jwt`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r"
        assertThat(matched("apikey", jwt)).hasSize(1)
    }

    @Test fun `api key rejects a low-entropy string with the right prefix`() {
        assertThat(matched("apikey", "sk-aaaaaaaaaaaaaaaaaaaaaa")).isEmpty()
    }

    @Test fun `both rules are masked by default`() {
        assertThat(rule("passport").enabledByDefault).isTrue()
        assertThat(rule("apikey").enabledByDefault).isTrue()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*PassportAndApiKeyRuleTest*'
```

预期：FAIL —— `NoSuchElementException`

- [ ] **Step 3: 加入两条规则**

在 `DefaultRuleSet` 里加：

```kotlin
    private val PASSPORT = RegexRule(
        id = "passport",
        kind = SensitiveKind.PASSPORT,
        enabledByDefault = true,
        // TD3：两行各 44 字符，字符集是大写字母、数字与填充符
        pattern = Regex("""(?<![A-Z0-9<])[A-Z0-9<]{44}(?![A-Z0-9<])"""),
        confidence = 0.95f,
        validate = { _, m ->
            Checksums.mrzTd3Line1(m.value) || Checksums.mrzTd3Line2Valid(m.value)
        },
    )

    private val API_KEY = RegexRule(
        id = "apikey",
        kind = SensitiveKind.API_KEY,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Za-z0-9_-])(?:sk-|ghp_|gho_|ghs_|AKIA|eyJ)[A-Za-z0-9_\-.]{12,}"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.shannonEntropy(m.value) > 3.5 },
    )

    val rules: List<Rule> = listOf(CARD, IBAN, SSN, MAC, EMAIL, PhoneRule(), PASSPORT, API_KEY)
```

**注意 `passport` 的正则会先于其他规则匹配到一整行 MRZ**，而 MRZ 行里同时含护照号——这是期望行为，两行都整体遮掉。

- [ ] **Step 4: 运行确认通过并提交**

```bash
./gradlew :app:testDebugUnitTest --tests '*PassportAndApiKeyRuleTest*'
git add app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt app/src/test/java/com/youma/app/rules/PassportAndApiKeyRuleTest.kt
git commit -m "feat: add passport MRZ and entropy-gated API key rules"
```

预期：全部 PASS。

---

### Task 23: 仅圈出的三条规则（URL、IP、快递单号）

**Files:**
- Modify: `app/src/main/java/com/youma/app/rules/DefaultRuleSet.kt`
- Test: `app/src/test/java/com/youma/app/rules/OutlinedByDefaultRuleTest.kt`

**Interfaces:**
- Consumes: `Checksums.upsCheckDigit`、`KnownTlds`
- Produces: `DefaultRuleSet` 里 `id = "url"` / `"ip"` / `"tracking"` 三条规则，全部 `enabledByDefault = false`

| 类型 | 匹配 | 校验 | 默认 |
|---|---|---|---|
| URL | 含协议头，或常见 TLD 结尾 | **只遮 query 与 path，域名保留** | 仅圈出 |
| IP_ADDR | IPv4 / IPv6 正则 | 各段数值范围；排除版本号误报 | 仅圈出 |
| TRACKING_NO | 主流承运商格式表 | 部分承运商有校验位，无校验位的降低置信度 | 仅圈出 |

这三条的默认状态是 `OUTLINED`——它们靠导出拦截（spec §7.4）兜底，保证不会因为分层而静默漏出。**`enabledByDefault = false` 在这里不是摆设，是规则表的一列。**

URL 的 `select` 参数在这里第一次真正派上用场：匹配整个 URL，但只把 path 与 query 那一段作为遮罩区间。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/rules/OutlinedByDefaultRuleTest.kt`：

```kotlin
package com.youma.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlinedByDefaultRuleTest {

    private fun rule(id: String) = DefaultRuleSet.rules.first { it.id == id }
    private fun masked(id: String, text: String) =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    // ---------- URL：只遮 path 与 query ----------
    @Test fun `url keeps the domain and masks the path`() {
        assertThat(masked("url", "see https://app.com/r/AbCdEf123 now")).containsExactly("/r/AbCdEf123")
    }

    @Test fun `url masks the query string too`() {
        assertThat(masked("url", "https://app.com/x?token=secret123")).containsExactly("/x?token=secret123")
    }

    @Test fun `a bare domain with no path yields nothing to mask`() {
        assertThat(masked("url", "visit https://example.com")).isEmpty()
    }

    @Test fun `url without a scheme is still matched`() {
        assertThat(masked("url", "go to app.com/r/Zz9")).containsExactly("/r/Zz9")
    }

    @Test fun `url is outlined by default`() {
        assertThat(rule("url").enabledByDefault).isFalse()
    }

    // ---------- IP ----------
    @Test fun `ipv4 matches and range-checks each octet`() {
        assertThat(masked("ip", "host 192.168.1.10 up")).containsExactly("192.168.1.10")
        assertThat(masked("ip", "bad 999.1.1.1")).isEmpty()
    }

    @Test fun `ipv6 is matched`() {
        assertThat(masked("ip", "addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334")).hasSize(1)
    }

    @Test fun `a version number is not an ip address`() {
        assertThat(masked("ip", "app v1.2.3.4 released")).isEmpty()
        assertThat(masked("ip", "version 10.0.19041.1")).isEmpty()
    }

    @Test fun `ip is outlined by default`() {
        assertThat(rule("ip").enabledByDefault).isFalse()
    }

    // ---------- 快递单号 ----------
    @Test fun `ups tracking with a valid check digit is matched`() {
        assertThat(masked("tracking", "ships 1Z12345E0205271688 today")).containsExactly("1Z12345E0205271688")
    }

    @Test fun `ups tracking with a broken check digit is rejected`() {
        assertThat(masked("tracking", "1Z12345E0205271689")).isEmpty()
    }

    @Test fun `checksumless carrier formats are matched with lower confidence`() {
        val fedex = rule("tracking").findIn("track 123456789012 now")
        assertThat(fedex).hasSize(1)
        assertThat(fedex.single().confidence).isLessThan(0.7f)
    }

    @Test fun `tracking is outlined by default`() {
        assertThat(rule("tracking").enabledByDefault).isFalse()
    }

    // ---------- 规则表完整性 ----------
    @Test fun `the rule set has exactly the eleven spec rules`() {
        assertThat(DefaultRuleSet.rules.map { it.id }).containsExactly(
            "card", "iban", "ssn", "mac", "email", "phone", "passport", "apikey",
            "url", "ip", "tracking",
        )
    }

    @Test fun `exactly eight rules are masked by default and three are outlined`() {
        val (masked, outlined) = DefaultRuleSet.rules.partition { it.enabledByDefault }
        assertThat(masked).hasSize(8)
        assertThat(outlined.map { it.id }).containsExactly("url", "ip", "tracking")
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*OutlinedByDefaultRuleTest*'
```

预期：FAIL

- [ ] **Step 3: 加入三条规则**

在 `DefaultRuleSet` 里加：

```kotlin
    // ---------- 仅圈出：误报率高，靠导出拦截兜底（spec §6 / §7.4） ----------

    private val URL_PATTERN = Regex(
        """(?<![A-Za-z0-9@._-])(?:https?://)?[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.([A-Za-z]{2,24})(/[^\s]*)?"""
    )

    private val URL = RegexRule(
        id = "url",
        kind = SensitiveKind.URL,
        enabledByDefault = false,
        pattern = URL_PATTERN,
        confidence = 0.7f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) && m.groupValues[2].length > 1 },
        // 只遮 path 与 query，域名保留
        select = { m ->
            val path = m.groups[2]!!
            path.range
        },
    )

    private val IP = RegexRule(
        id = "ip",
        kind = SensitiveKind.IP_ADDR,
        enabledByDefault = false,
        pattern = Regex(
            """(?<![\w.:])(?:(?:\d{1,3}\.){3}\d{1,3}|(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4})(?![\w.:])"""
        ),
        confidence = 0.6f,
        validate = { text, m -> validIpAndNotAVersion(text, m) },
    )

    private fun validIpAndNotAVersion(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (v.contains(':')) return true                      // IPv6：正则本身已足够严格

        // 各段数值范围
        val parts = v.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.length > 1 && it.startsWith("0") }) return false
        if (parts.any { (it.toIntOrNull() ?: return false) !in 0..255 }) return false

        // 排除版本号误报：紧邻的 v / version 前缀
        val before = text.substring(0, m.range.first).takeLast(12).lowercase()
        if (before.trimEnd().endsWith("v") || before.contains("version")) return false
        return true
    }

    private val TRACKING = object : Rule {
        override val id = "tracking"
        override val kind = SensitiveKind.TRACKING_NO
        override val enabledByDefault = false

        private val ups = Regex("""(?<![A-Za-z0-9])1Z[0-9A-Za-z]{16}(?![A-Za-z0-9])""")
        // FedEx 12/15 位、USPS 20/22 位：无校验位，置信度调低
        private val plainDigits = Regex("""(?<![\d-])(?:\d{12}|\d{15}|\d{20}|\d{22})(?![\d-])""")

        override fun findIn(text: String): List<RuleMatch> {
            val out = ArrayList<RuleMatch>()
            ups.findAll(text)
                .filter { Checksums.upsCheckDigit(it.value) }
                .forEach { out += RuleMatch(it.range, 0.85f) }
            plainDigits.findAll(text)
                .filter { d -> out.none { it.range.first <= d.range.last && d.range.first <= it.range.last } }
                .forEach { out += RuleMatch(it.range, 0.5f) }   // 无校验位 → 低置信度
            return out
        }
    }

    val rules: List<Rule> = listOf(
        CARD, IBAN, SSN, MAC, EMAIL, PhoneRule(), PASSPORT, API_KEY,
        URL, IP, TRACKING,
    )
```

- [ ] **Step 4: 运行全部规则测试**

```bash
./gradlew :app:testDebugUnitTest --tests '*Rule*'
```

预期：全部 PASS，包括「规则表恰好 11 条」和「8 条默认打码 / 3 条仅圈出」。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/youma/app/rules app/src/test/java/com/youma/app/rules
git commit -m "feat: add outlined-by-default URL, IP and tracking rules completing the 11-rule set"
```

---

### Task 24: 候选合并

**Files:**
- Modify: `app/src/main/java/com/youma/app/engine/CandidateMerger.kt`（替换 Task 16 的直通实现）
- Test: `app/src/test/java/com/youma/app/engine/CandidateMergerTest.kt`

**Interfaces:**
- Consumes: `Candidate`、`Quad.iou`
- Produces: `class CandidateMerger(iouThreshold: Float = 0.6f, gapRatio: Float = 0.5f)` + `fun merge(candidates: List<Candidate>): List<Candidate>`

**三件事（spec §5.4）**：
1. IoU > 0.6 的候选按 source 优先级去重（`RULE > ENTITY_MODEL > LLM`）；
2. 同一 kind 且水平间距小于行高 0.5 倍的相邻候选合并成一个；合并后的 `enabledByDefault` 取**或**——保证合并不会把默认打码的降级成仅圈出；
3. 按面积降序输出，让 UI 先渲染大块。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/youma/app/engine/CandidateMergerTest.kt`：

```kotlin
package com.youma.app.engine

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidateMergerTest {

    private fun c(
        id: String,
        l: Float, t: Float, r: Float, b: Float,
        kind: SensitiveKind = SensitiveKind.EMAIL,
        source: DetectorSource = DetectorSource.RULE,
        confidence: Float = 0.9f,
        enabled: Boolean = true,
    ) = Candidate(id, Quad.fromRect(RectF(l, t, r, b)), kind, source, confidence, enabled)

    private val merger = CandidateMerger()

    @Test
    fun `disjoint candidates all survive`() {
        val out = merger.merge(listOf(c("a", 0f, 0f, 10f, 10f), c("b", 100f, 100f, 110f, 110f)))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `heavily overlapping candidates are deduplicated`() {
        val out = merger.merge(listOf(c("a", 0f, 0f, 100f, 20f), c("b", 2f, 0f, 100f, 20f)))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `rule beats entity model when they overlap`() {
        val out = merger.merge(listOf(
            c("model", 0f, 0f, 100f, 20f, source = DetectorSource.ENTITY_MODEL),
            c("rule", 2f, 0f, 100f, 20f, source = DetectorSource.RULE),
        ))
        assertThat(out.single().id).isEqualTo("rule")
    }

    @Test
    fun `entity model beats llm when they overlap`() {
        val out = merger.merge(listOf(
            c("llm", 0f, 0f, 100f, 20f, source = DetectorSource.LLM),
            c("model", 2f, 0f, 100f, 20f, source = DetectorSource.ENTITY_MODEL),
        ))
        assertThat(out.single().id).isEqualTo("model")
    }

    @Test
    fun `adjacent same-kind candidates on one line are merged`() {
        // 行高 20，间距 8 < 20*0.5 → 合并
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 58f, 0f, 100f, 20f),
        ))
        assertThat(out).hasSize(1)
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(0f, 0f, 100f, 20f))
    }

    @Test
    fun `candidates too far apart are not merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 90f, 0f, 140f, 20f),      // 间距 40 > 10
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `candidates of different kinds are never merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, kind = SensitiveKind.EMAIL),
            c("b", 55f, 0f, 100f, 20f, kind = SensitiveKind.PHONE),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `candidates on different lines are not merged`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f),
            c("b", 55f, 100f, 100f, 120f),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `merging takes the OR of enabledByDefault`() {
        // 合并绝不能把默认打码的降级成仅圈出
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, enabled = false),
            c("b", 55f, 0f, 100f, 20f, enabled = true),
        ))
        assertThat(out.single().enabledByDefault).isTrue()
    }

    @Test
    fun `merged candidate keeps the highest confidence`() {
        val out = merger.merge(listOf(
            c("a", 0f, 0f, 50f, 20f, confidence = 0.6f),
            c("b", 55f, 0f, 100f, 20f, confidence = 0.95f),
        ))
        assertThat(out.single().confidence).isWithin(1e-3f).of(0.95f)
    }

    @Test
    fun `output is sorted by area descending`() {
        val out = merger.merge(listOf(
            c("small", 0f, 0f, 10f, 10f),
            c("big", 200f, 200f, 400f, 300f),
            c("mid", 500f, 500f, 560f, 540f),
        ))
        assertThat(out.map { it.id }).containsExactly("big", "mid", "small").inOrder()
    }

    @Test
    fun `face and barcode candidates are never merged into text candidates`() {
        val out = merger.merge(listOf(
            c("face", 0f, 0f, 50f, 50f, kind = SensitiveKind.FACE, source = DetectorSource.FACE),
            c("text", 52f, 0f, 100f, 50f, kind = SensitiveKind.EMAIL),
        ))
        assertThat(out).hasSize(2)
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(merger.merge(emptyList())).isEmpty()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*CandidateMergerTest*'
```

预期：多条 FAIL（当前是直通实现）

- [ ] **Step 3: 实现 CandidateMerger**

`app/src/main/java/com/youma/app/engine/CandidateMerger.kt`：

```kotlin
package com.youma.app.engine

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 候选合并（spec §5.4）。
 *
 * 1. IoU > 0.6 的候选按 source 优先级去重（RULE > ENTITY_MODEL > LLM）；
 * 2. 同一 kind 且水平间距小于行高 0.5 倍的相邻候选合并；
 * 3. 按面积降序输出，让 UI 先渲染大块。
 */
class CandidateMerger(
    private val iouThreshold: Float = 0.6f,
    private val gapRatio: Float = 0.5f,
) {

    fun merge(candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val deduped = dedupe(candidates)
        val joined = joinAdjacent(deduped)
        return joined.sortedByDescending { it.quad.area() }
    }

    private fun dedupe(input: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>()
        // 优先级高的先进，后来的重叠者直接丢
        input.sortedWith(
            compareBy<Candidate> { priority(it.source) }
                .thenByDescending { it.confidence }
                .thenByDescending { it.quad.area() }
        ).forEach { cand ->
            val clash = kept.firstOrNull { it.quad.iou(cand.quad) > iouThreshold }
            if (clash == null) {
                kept += cand
            } else {
                // 被丢掉的那个如果默认打码，把这一位传给保留者——绝不降级
                if (cand.enabledByDefault && !clash.enabledByDefault) {
                    kept[kept.indexOf(clash)] = clash.copy(enabledByDefault = true)
                }
            }
        }
        return kept
    }

    private fun joinAdjacent(input: List<Candidate>): List<Candidate> {
        val remaining = input.toMutableList()
        val out = ArrayList<Candidate>()
        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var merged = true
            while (merged) {
                merged = false
                val it = remaining.iterator()
                while (it.hasNext()) {
                    val other = it.next()
                    if (canJoin(current, other)) {
                        current = join(current, other)
                        it.remove()
                        merged = true
                    }
                }
            }
            out += current
        }
        return out
    }

    private fun canJoin(a: Candidate, b: Candidate): Boolean {
        if (a.kind != b.kind) return false
        if (a.source in REGION_SOURCES || b.source in REGION_SOURCES) return false  // 人脸/条码不参与合并
        val ra = a.quad.bounds()
        val rb = b.quad.bounds()
        val lineHeight = max(ra.height(), rb.height())
        // 同一行：垂直中心相差不超过行高的一半
        if (abs(ra.centerY() - rb.centerY()) > lineHeight * 0.5f) return false
        val gap = max(0f, max(ra.left, rb.left) - min(ra.right, rb.right))
        return gap < lineHeight * gapRatio
    }

    private fun join(a: Candidate, b: Candidate): Candidate {
        val r = RectF(a.quad.bounds()).apply { union(b.quad.bounds()) }
        return a.copy(
            id = "${a.id}+${b.id}",
            quad = Quad.fromRect(r),
            confidence = max(a.confidence, b.confidence),
            // 取「或」：合并绝不把默认打码的降级成仅圈出
            enabledByDefault = a.enabledByDefault || b.enabledByDefault,
        )
    }

    private fun priority(source: DetectorSource): Int = when (source) {
        DetectorSource.RULE, DetectorSource.FACE, DetectorSource.BARCODE, DetectorSource.MANUAL -> 0
        DetectorSource.ENTITY_MODEL -> 1
        DetectorSource.LLM -> 2
    }

    private companion object {
        val REGION_SOURCES = setOf(DetectorSource.FACE, DetectorSource.BARCODE)
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*CandidateMergerTest*' --tests '*RedactionEngineTest*'
```

预期：全部 PASS。引擎测试里 `candidates from classifiers and detectors are combined` 现在会走真正的合并逻辑——两个候选 quad 相同（都是 0,0,10,10）但 kind 不同（EMAIL vs FACE），不会被合并，断言仍然成立。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/youma/app/engine/CandidateMerger.kt app/src/test/java/com/youma/app/engine/CandidateMergerTest.kt
git commit -m "feat: dedupe by source priority, join adjacent same-kind candidates, sort by area"
```

---

### Task 25: 引擎装配

**Files:**
- Create: `app/src/main/java/com/youma/app/engine/EngineFactory.kt`
- Test: `app/src/androidTest/java/com/youma/app/engine/EngineFactoryTest.kt`

**Interfaces:**
- Consumes: `MlKitTextRecognizer`、`RuleClassifier`、`DefaultRuleSet`、`RedactionEngine`
- Produces: `fun buildEngine(context: Context): RedactionEngine`

**全应用只有这一个函数知道用的是哪家的模型**（spec §4.4）。人脸与条码检测器在计划 05（M3）加进 `regionDetectors` 列表。

- [ ] **Step 1: 写 instrumented 测试**

`app/src/androidTest/java/com/youma/app/engine/EngineFactoryTest.kt`：

```kotlin
package com.youma.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineFactoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(vararg lines: String): SourceImage {
        val bmp = Bitmap.createBitmap(1400, 180 * lines.size + 120, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 72f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            lines.forEachIndexed { i, s -> drawText(s, 40f, 140f + i * 180f, paint) }
        }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    @Test
    fun engine_finds_a_card_number_end_to_end() = runTest {
        val result = buildEngine(context).analyze(render("Card 4111111111111111"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.PAYMENT_CARD)
        assertThat(result.candidates.first { it.kind == SensitiveKind.PAYMENT_CARD }.enabledByDefault).isTrue()
    }

    @Test
    fun engine_finds_an_email_end_to_end() = runTest {
        val result = buildEngine(context).analyze(render("mail alice@example.com"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.EMAIL)
    }

    @Test
    fun a_url_comes_back_outlined_by_default() = runTest {
        val result = buildEngine(context).analyze(render("open https://app.com/r/AbCdEf123"))
        val url = result.candidates.firstOrNull { it.kind == SensitiveKind.URL }
        if (url != null) assertThat(url.enabledByDefault).isFalse()
    }

    @Test
    fun a_blank_image_yields_no_candidates_and_does_not_throw() = runTest {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = buildEngine(context).analyze(SourceImage(blank, 1f, 400, 400, "image/png"))
        assertThat(result.candidates).isEmpty()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*EngineFactoryTest*'
```

预期：编译失败，`Unresolved reference: buildEngine`

- [ ] **Step 3: 实现装配函数**

`app/src/main/java/com/youma/app/engine/EngineFactory.kt`：

```kotlin
package com.youma.app.engine

import android.content.Context
import com.youma.app.engine.mlkit.MlKitTextRecognizer
import com.youma.app.rules.DefaultRuleSet
import com.youma.app.rules.RuleClassifier

/**
 * 首版装配（spec §4.4）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * v2 想加 Gemini Nano 语义判定：classifiers 里追加一项，其余不动。
 * v2 想换成自带 OCR 模型：换掉 recognizer 这一行，其余不动。
 *
 * regionDetectors 在 M3（计划 05）填入人脸与条码检测器。
 */
fun buildEngine(context: Context) = RedactionEngine(
    recognizer = MlKitTextRecognizer(),
    regionDetectors = emptyList(),
    classifiers = listOf(RuleClassifier(DefaultRuleSet.rules)),
)
```

`context` 目前没被用到——保留这个参数，因为 M3 的人脸/条码检测器与 v2 的 Gemini Nano 都需要它，改签名会牵动调用方。

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*EngineFactoryTest*'
```

预期：4 个测试全部 PASS。

- [ ] **Step 5: 跑全量并提交**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertDebugNoRuntimePermissions
git add app/src/main/java/com/youma/app/engine/EngineFactory.kt app/src/androidTest/java/com/youma/app/engine
git commit -m "feat: assemble the first-version engine in a single factory function"
```

---

## 本计划出口

- [x] 全部单测与 instrumented 测试通过（198 条单测 + 17 条 instrumented，全绿）
- [x] 权限断言仍然通过（加了 ML Kit 与 libphonenumber 之后）
- [x] 断网状态下 ML Kit 识别照常工作（spec §15.1 已验并记录）
- [x] element 切分粒度的实测结论已写进 spec §15 第 5 条
- [x] `DefaultRuleSet.rules` 恰好 11 条，8 条默认打码、3 条仅圈出

引擎已能产出候选，但还没接到编辑器上——那是计划 04。

---

## 执行记录：与计划文本的偏差（2026-09-03）

按索引约定，偏差记回本文件。以下五处实现与计划写的不同，均已在对应提交里说明。

1. **Task 16 —— `RedactionEngine` 的 `runCatching` 必须写在 `async` 内部。**
   计划 Step 3 写的是 `async { recognizer.recognize(image) }` + 外层
   `runCatching { linesJob.await() }`。结构化并发下这拦不住：子 `async` 抛异常会先取消
   父 `coroutineScope`，整次 `analyze` 跟着失败，正好违背「任何一条链路失败都降级为空结果」。
   改成和 `regionDetectors` 一样把 `runCatching` 放进 `async` 里。
   测试另需补 `import kotlinx.coroutines.test.currentTime`（顶层扩展属性）
   与 `@OptIn(ExperimentalCoroutinesApi::class)`。

2. **Task 17 Step 1 —— 权限断言当场阻断，来源不是 ML Kit 的模型下载。**
   `com.google.mlkit:text-recognition`（确为 bundled）传递依赖到
   `com.google.android.datatransport:transport-backend-cct`，即 GMS 的**遥测上报后端**，
   它把 `INTERNET` 与 `ACCESS_NETWORK_STATE` 并进了合并 manifest。
   处理：`AndroidManifest.xml` 里用 `tools:node="remove"` 摘掉这两条。
   **不要改成 exclude 掉那几个 artifact** —— ML Kit 的日志代码直接引用其类，
   摘依赖会在初始化时 `NoClassDefFoundError`。摘权限反而更强：类还在，但系统不给这个进程开网络。
   摘除后断网实测识别功能不受任何影响。

3. **Task 18 —— UPS 校验位的加倍奇偶写反了。**
   计划的 `sum += if (i % 2 == 0) v else v * 2` 会把计划自己指定的公开标准样例
   `1Z12345E0205271688` 算出校验位 6（实际是 8）。正确的是**偶数下标加倍**：
   `sum += if (i % 2 == 0) v * 2 else v`。按 Step 4 的指示「确认样例无误则修实现」。

4. **Task 24 —— `dedupe` 必须要求两个候选 `kind` 相同。**
   计划的实现只按 IoU 去重、完全不看 kind。计划预言 `RedactionEngineTest` 的
   「classifier 与 detector 的候选合并」仍会通过——预言是错的：EMAIL 与 FACE 两个候选
   quad 完全相同，IoU = 1.0，`dedupe` 直接吃掉一个。
   真实场景更糟：条码下方印着同一串快递单号时，条码候选会被文字候选静默吃掉，那是漏检。
   `dedupe` 的判定加一层 `comparable(a, b) = a.kind == b.kind`，与 `canJoin` 的口径一致。
   补了两条回归测试（条码不被重叠文字候选去重掉；两个重叠人脸仍然去重），
   `CandidateMergerTest` 因此是 15 条而不是 13 条。

5. **Task 25 —— `EngineFactoryTest` 用 `targetContext` 而非 `context`。**
   `InstrumentationRegistry.getInstrumentation().context` 是测试 APK 的 context，
   `buildEngine` 要的是被测应用的。

### 顺带发现的、留给后续计划的两件事

均已写进 spec §15 第 5 条，本计划不处理，因为都会牵动已定稿的规则测试：

- **低置信度行会照常喂进规则层。** 真实中文截图上，bundled 拉丁识别器把中文识别成
  置信度 0.25–0.4 的乱码，这些串会参与规则匹配，是误报的一个来源。
  建议计划 04 给 `RuleClassifier` 加一个置信度下限。
- **实测到一例真实漏检：28 位支付宝交易号不被任何规则命中。**
  `card` 限 13–19 位、`tracking` 限 12/15/20/22 位，28 位落在所有规则之外。
  留给计划 04 的评测 harness 量化后再决定要不要加一条「长数字串」兜底规则。
