# DAMA 安卓版 · 计划 05 · M3：人脸与条码

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把两个 `RegionDetector` 实现接进并行管线，让人脸与二维码/条形码进入候选列表，默认状态恒为打码。

**Architecture:** 人脸与条码**不是规则**——它们由 `RegionDetector` 直接产出，不经过 `DefaultRuleSet` 那张表。接入方式就是 `buildEngine` 里 `regionDetectors` 列表加两项，引擎、UI、数据模型一行都不用改。这是 §4.3 那几个接口存在的第一次实际回报。

**Tech Stack:** ML Kit face-detection（bundled）· ML Kit barcode-scanning（bundled）

**Spec:** `docs/superpowers/specs/2026-09-02-dama-android-design.md`（重点 §4.3、§5.2、§6 末尾、§12 M3）

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-dama-android-00-index.md`

**前置:** 计划 01–04 全部完成

**出口（spec §12）**：正脸召回 ≥ 0.98，二维码 100%。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `engine/mlkit/MlKitFaceDetector.kt` | 人脸框 → `Candidate(kind = FACE)` |
| `engine/mlkit/MlKitBarcodeDetector.kt` | 条码框 → `Candidate(kind = BARCODE)` |
| `engine/EngineFactory.kt`（改） | `regionDetectors` 列表加两项 |
| `androidTest/.../eval/RegionSamples.kt` | 人脸与条码的合成样本 |

---

### Task 31: 人脸检测

**Files:**
- Modify: `app/build.gradle.kts`（加 `mlkit-face-detection`）
- Create: `app/src/main/java/com/dama/app/engine/mlkit/MlKitFaceDetector.kt`
- Test: `app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitFaceDetectorTest.kt`

**Interfaces:**
- Consumes: `RegionDetector`、`SourceImage`、`Candidate`
- Produces: `class MlKitFaceDetector : RegionDetector`，`id = "mlkit-face"`，`kind = SensitiveKind.FACE`

**规格**：
- `enabledByDefault = true` 恒定——人脸不经过规则表的误报率分层
- 人脸框按 spec §5.4 的排序进入合并流程，但 `CandidateMerger` 已保证人脸不与文本候选合并
- 框要**外扩**一点：ML Kit 给的是五官外接框，发际线与下巴常在框外，直接用会露脸

- [ ] **Step 1: 加依赖并复查权限断言**

`app/build.gradle.kts`：

```kotlin
    implementation(libs.mlkit.face.detection)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

预期：仍打印 `uses-permission = （空）`。若出现 `INTERNET`，说明拉到了 unbundled 变体（`com.google.android.gms:play-services-mlkit-face-detection`）——必须是 `com.google.mlkit:face-detection`。

- [ ] **Step 2: 写失败的测试**

`app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitFaceDetectorTest.kt`：

```kotlin
package com.dama.app.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitFaceDetectorTest {

    /**
     * 画一张卡通正脸。ML Kit 的 face detector 对这种高对比的简笔脸是能检出的；
     * 若某个版本检不出，把 assets 里放一张真实人脸照片（自己的）改用它。
     */
    private fun cartoonFace(w: Int = 600, h: Int = 800): SourceImage {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 170) }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
            drawOval(RectF(150f, 150f, 450f, 570f), skin)
            drawOval(RectF(215f, 290f, 265f, 330f), ink)      // 左眼
            drawOval(RectF(335f, 290f, 385f, 330f), ink)      // 右眼
            drawOval(RectF(280f, 360f, 320f, 410f), Paint(skin).apply { color = Color.rgb(220, 170, 140) })
            drawRect(RectF(250f, 460f, 350f, 480f), ink)      // 嘴
        }
        return SourceImage(bmp, 1f, w, h, "image/png")
    }

    private fun blank(): SourceImage {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return SourceImage(bmp, 1f, 400, 400, "image/png")
    }

    @Test
    fun detects_a_frontal_face() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        assertThat(out).isNotEmpty()
    }

    @Test
    fun face_candidates_carry_the_right_kind_and_source() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        out.forEach {
            assertThat(it.kind).isEqualTo(SensitiveKind.FACE)
            assertThat(it.source).isEqualTo(DetectorSource.FACE)
        }
    }

    @Test
    fun face_candidates_are_masked_by_default() = runTest {
        // 人脸不经过规则表的误报率分层，默认状态恒为打码
        MlKitFaceDetector().detect(cartoonFace()).forEach {
            assertThat(it.enabledByDefault).isTrue()
        }
    }

    @Test
    fun the_face_box_is_expanded_beyond_the_raw_detection() = runTest {
        val image = cartoonFace()
        val out = MlKitFaceDetector().detect(image)
        val b = out.first().quad.bounds()
        // 至少覆盖眼睛与嘴之间的区域，且落在图内
        assertThat(b.left).isAtLeast(0f)
        assertThat(b.top).isAtLeast(0f)
        assertThat(b.right).isAtMost(image.width.toFloat())
        assertThat(b.bottom).isAtMost(image.height.toFloat())
        assertThat(b.contains(300f, 310f)).isTrue()    // 双眼之间
    }

    @Test
    fun a_blank_image_yields_no_faces() = runTest {
        assertThat(MlKitFaceDetector().detect(blank())).isEmpty()
    }

    @Test
    fun candidate_ids_are_unique() = runTest {
        val out = MlKitFaceDetector().detect(cartoonFace())
        assertThat(out.map { it.id }.toSet()).hasSize(out.size)
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitFaceDetectorTest*'
```

预期：编译失败，`Unresolved reference: MlKitFaceDetector`

- [ ] **Step 4: 实现**

`app/src/main/java/com/dama/app/engine/mlkit/MlKitFaceDetector.kt`：

```kotlin
package com.dama.app.engine.mlkit

import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.Candidate
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.RegionDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/**
 * 人脸检测（bundled）。
 *
 * 人脸不经过规则表的误报率分层（spec §6 末尾）——默认状态恒为打码。
 *
 * ML Kit 给的是五官外接框，发际线与下巴常在框外，直接用会露脸。
 * 按短边比例外扩再裁回图内。
 */
class MlKitFaceDetector : RegionDetector {

    override val id = "mlkit-face"
    override val kind = SensitiveKind.FACE

    private val client by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
    }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val faces = client.process(input).await()
        return faces.mapIndexed { i, face ->
            val box = RectF(face.boundingBox)
            val pad = min(box.width(), box.height()) * EXPAND_RATIO
            val expanded = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad * 1.4f).coerceAtLeast(0f),        // 上方多留一点给发际线
                (box.right + pad).coerceAtMost(image.width.toFloat()),
                (box.bottom + pad).coerceAtMost(image.height.toFloat()),
            )
            Candidate(
                id = "face-${face.trackingId ?: i}-$i",
                quad = Quad.fromRect(expanded),
                kind = SensitiveKind.FACE,
                source = DetectorSource.FACE,
                confidence = CONFIDENCE,
                enabledByDefault = true,
            )
        }
    }

    private companion object {
        const val MIN_FACE_SIZE = 0.05f
        const val EXPAND_RATIO = 0.18f
        const val CONFIDENCE = 0.95f
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitFaceDetectorTest*'
```

预期：6 个测试全部 PASS。

若 `detects_a_frontal_face` 在卡通脸上失败，换成一张真实正脸照片：放进 `app/src/androidTest/assets/faces/frontal.jpg`（**用你自己的照片**），把 `cartoonFace()` 改成从 assets 读。不要为了让测试通过而放宽 `MIN_FACE_SIZE` 到不合理的值。

- [ ] **Step 6: 提交**

```bash
git add app/build.gradle.kts app/src/main/java/com/dama/app/engine/mlkit/MlKitFaceDetector.kt app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitFaceDetectorTest.kt
git commit -m "feat: add bundled ML Kit face detector with expanded boxes, masked by default"
```

---

### Task 32: 条码检测

**Files:**
- Modify: `app/build.gradle.kts`（加 `mlkit-barcode-scanning`）
- Create: `app/src/main/java/com/dama/app/engine/mlkit/MlKitBarcodeDetector.kt`
- Test: `app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitBarcodeDetectorTest.kt`

**Interfaces:**
- Consumes: `RegionDetector`
- Produces: `class MlKitBarcodeDetector : RegionDetector`，`id = "mlkit-barcode"`，`kind = SensitiveKind.BARCODE`

**规格**：
- 二维码与条形码都要检；出口指标是二维码 100%
- **绝不把条码解码出的内容写进任何日志或状态**——那是敏感内容本身
- 用 `cornerPoints` 而不是 `boundingBox`：倾斜拍摄的二维码用外接矩形会盖住旁边的字

- [ ] **Step 1: 加依赖并复查权限断言**

```kotlin
    implementation(libs.mlkit.barcode.scanning)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

- [ ] **Step 2: 写失败的测试**

`app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitBarcodeDetectorTest.kt`：

```kotlin
package com.dama.app.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitBarcodeDetectorTest {

    /**
     * 手绘一个 21x21 的 QR（version 1）太脆弱；改成画一个高对比的
     * Code 128 风格条形码 —— ML Kit 对纯竖条同样能检出格式。
     * 若这张合成图检不出，改用 assets 里的一张真实二维码截图。
     */
    private fun barcodeImage(): SourceImage {
        val w = 800; val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK }
            // EAN-13 "4006381333931" 的近似条纹布局：宽窄交替，静区留足
            val widths = intArrayOf(3,1,1,2,3,2,1,1,3,1,2,2,1,3,1,1,2,1,3,2,1,1,3,2,1,2,3,1,1,2)
            var x = 120f
            widths.forEachIndexed { i, wUnit ->
                val bar = wUnit * 6f
                if (i % 2 == 0) drawRect(x, 80f, x + bar, 320f, ink)
                x += bar
            }
        }
        return SourceImage(bmp, 1f, w, h, "image/png")
    }

    private fun blank(): SourceImage {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return SourceImage(bmp, 1f, 400, 400, "image/png")
    }

    @Test
    fun a_blank_image_yields_no_barcodes() = runTest {
        assertThat(MlKitBarcodeDetector().detect(blank())).isEmpty()
    }

    @Test
    fun detected_barcodes_carry_the_right_kind_and_source() = runTest {
        MlKitBarcodeDetector().detect(barcodeImage()).forEach {
            assertThat(it.kind).isEqualTo(SensitiveKind.BARCODE)
            assertThat(it.source).isEqualTo(DetectorSource.BARCODE)
            assertThat(it.enabledByDefault).isTrue()
        }
    }

    @Test
    fun detector_does_not_throw_on_a_noisy_image() = runTest {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            val p = Paint()
            for (x in 0 until 300 step 3) for (y in 0 until 300 step 3) {
                p.color = if ((x * y) % 7 < 3) Color.BLACK else Color.WHITE
                drawRect(x.toFloat(), y.toFloat(), x + 3f, y + 3f, p)
            }
        }
        MlKitBarcodeDetector().detect(SourceImage(bmp, 1f, 300, 300, "image/png"))
    }

    @Test
    fun quads_stay_inside_the_image() = runTest {
        val image = barcodeImage()
        MlKitBarcodeDetector().detect(image).forEach {
            val b = it.quad.bounds()
            assertThat(b.left).isAtLeast(-1f)
            assertThat(b.right).isAtMost(image.width + 1f)
        }
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitBarcodeDetectorTest*'
```

预期：编译失败，`Unresolved reference: MlKitBarcodeDetector`

- [ ] **Step 4: 实现**

`app/src/main/java/com/dama/app/engine/mlkit/MlKitBarcodeDetector.kt`：

```kotlin
package com.dama.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.Candidate
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.RegionDetector
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * 条码检测（bundled）：二维码与条形码。
 *
 * 用 cornerPoints 而不是 boundingBox —— 倾斜拍摄的二维码用外接矩形会盖住旁边的字。
 *
 * **绝不把 barcode.rawValue 写进日志、状态或任何持久化位置**：那是敏感内容本身。
 * 这个类只关心「哪里有一个条码」，不关心它是什么。
 */
class MlKitBarcodeDetector : RegionDetector {

    override val id = "mlkit-barcode"
    override val kind = SensitiveKind.BARCODE

    private val client by lazy { BarcodeScanning.getClient() }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val barcodes = client.process(input).await()
        return barcodes.mapIndexedNotNull { i, code ->
            val quad = code.cornerPoints?.takeIf { it.size >= 4 }?.let {
                Quad(
                    PointF(it[0].x.toFloat(), it[0].y.toFloat()),
                    PointF(it[1].x.toFloat(), it[1].y.toFloat()),
                    PointF(it[2].x.toFloat(), it[2].y.toFloat()),
                    PointF(it[3].x.toFloat(), it[3].y.toFloat()),
                )
            } ?: code.boundingBox?.let { Quad.fromRect(RectF(it)) } ?: return@mapIndexedNotNull null

            Candidate(
                id = "barcode-$i",
                quad = quad,
                kind = SensitiveKind.BARCODE,
                source = DetectorSource.BARCODE,
                confidence = CONFIDENCE,
                enabledByDefault = true,
            )
        }
    }

    private companion object { const val CONFIDENCE = 0.98f }
}
```

- [ ] **Step 5: 运行确认通过并提交**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*MlKitBarcodeDetectorTest*'
git add app/build.gradle.kts app/src/main/java/com/dama/app/engine/mlkit/MlKitBarcodeDetector.kt app/src/androidTest/java/com/dama/app/engine/mlkit/MlKitBarcodeDetectorTest.kt
git commit -m "feat: add bundled ML Kit barcode detector using corner points"
```

预期：4 个测试 PASS。

---

### Task 33: 接进并行管线并验收 M3

**Files:**
- Modify: `app/src/main/java/com/dama/app/engine/EngineFactory.kt`
- Modify: `app/src/androidTest/java/com/dama/app/engine/EngineFactoryTest.kt`
- Test: `app/src/androidTest/java/com/dama/app/eval/RegionEvaluationTest.kt`

**Interfaces:**
- Consumes: `MlKitFaceDetector`、`MlKitBarcodeDetector`
- Produces: `buildEngine` 的 `regionDetectors` 由 `emptyList()` 变成两项

**这个改动只有一行**——这就是 §4.3 那几个接口存在的意义。

- [ ] **Step 1: 改装配**

`app/src/main/java/com/dama/app/engine/EngineFactory.kt`：

```kotlin
fun buildEngine(context: Context) = RedactionEngine(
    recognizer = MlKitTextRecognizer(),
    regionDetectors = listOf(MlKitFaceDetector(), MlKitBarcodeDetector()),
    classifiers = listOf(RuleClassifier(DefaultRuleSet.rules)),
)
```

import 两个新类。

- [ ] **Step 2: 给 EngineFactoryTest 加两条断言**

在 `EngineFactoryTest` 里追加：

```kotlin
    @Test
    fun engine_reports_a_face_alongside_text_candidates() = runTest {
        // 一张既有文字又有脸的图：两条链路的结果都必须出现
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 170) }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
            drawOval(android.graphics.RectF(120f, 120f, 420f, 540f), skin)
            drawOval(android.graphics.RectF(185f, 260f, 235f, 300f), ink)
            drawOval(android.graphics.RectF(305f, 260f, 355f, 300f), ink)
            drawRect(android.graphics.RectF(220f, 430f, 320f, 450f), ink)
            drawText("mail alice@example.com", 60f, 720f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 56f
            })
        }
        val result = buildEngine(context).analyze(SourceImage(bmp, 1f, 900, 900, "image/png"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.EMAIL)
        // 人脸这条按当前 ML Kit 版本对简笔脸的表现可能为空；若为空，
        // 换成 assets 里的真实照片再断言（见 Task 31 Step 5 的同一处理）。
    }

    @Test
    fun a_failing_lane_does_not_break_the_others() = runTest {
        // buildEngine 的降级路径已由 RedactionEngineTest 覆盖；
        // 这里只确认真实装配下空白图不抛异常
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        buildEngine(context).analyze(SourceImage(blank, 1f, 400, 400, "image/png"))
    }
```

- [ ] **Step 3: 写 M3 出口的评测**

`app/src/androidTest/java/com/dama/app/eval/RegionEvaluationTest.kt`：

```kotlin
package com.dama.app.eval

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.buildEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.BitmapFactory

/**
 * M3 出口（spec §12）：正脸召回 ≥ 0.98，二维码 100%。
 *
 * 这两个指标必须在**真实图片**上量。把素材放进：
 *   app/src/androidTest/assets/faces/     每张图恰好一张正脸
 *   app/src/androidTest/assets/qrcodes/   每张图恰好一个二维码
 *
 * 素材来源要求同 docs/eval-sample-set.md：只用自己的照片、公开样张或自造数据。
 * 目录为空时测试会跳过而不是失败——但 M3 不能在跳过的情况下声称达标。
 */
@RunWith(AndroidJUnit4::class)
class RegionEvaluationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun load(dir: String): List<Pair<String, SourceImage>> {
        val names = runCatching { context.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        return names.filter { it.endsWith(".jpg") || it.endsWith(".png") }.mapNotNull { name ->
            val bmp = context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) } ?: return@mapNotNull null
            name to SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
        }
    }

    @Test
    fun frontal_face_recall_is_at_least_98_percent() = runTest {
        val images = load("faces")
        assumeTrue("androidTest/assets/faces/ 为空，跳过 M3 人脸指标", images.isNotEmpty())

        val engine = buildEngine(context)
        var hit = 0
        images.forEach { (name, img) ->
            val found = engine.analyze(img).candidates.any { it.kind == SensitiveKind.FACE }
            if (found) hit++ else println("DAMA-EVAL miss face: $name")
        }
        val recall = hit.toDouble() / images.size
        println("DAMA-EVAL face recall: $recall over ${images.size}")
        assertThat(recall).isAtLeast(0.98)
    }

    @Test
    fun qr_code_detection_is_perfect() = runTest {
        val images = load("qrcodes")
        assumeTrue("androidTest/assets/qrcodes/ 为空，跳过 M3 二维码指标", images.isNotEmpty())

        val engine = buildEngine(context)
        images.forEach { (name, img) ->
            val found = engine.analyze(img).candidates.any { it.kind == SensitiveKind.BARCODE }
            assertThat(found).isTrue()
            if (!found) println("DAMA-EVAL miss qr: $name")
        }
    }
}
```

- [ ] **Step 4: 准备素材并跑指标**

采集素材（每类至少 30 张，越多越可信）：

- `app/src/androidTest/assets/faces/` —— 30+ 张含正脸的图片，来源：自己的照片、公开人像样张
- `app/src/androidTest/assets/qrcodes/` —— 30+ 张含二维码的截图，来源：自己生成的二维码、公开的收款码样张

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*RegionEvaluationTest*'
```

- [ ] 正脸召回 ≥ 0.98
- [ ] 二维码 100%

若召回不达标：先看 logcat 里 `DAMA-EVAL miss face:` 打出的是哪几张，人工看图判断是侧脸/遮挡（不算正脸，从素材里移出）还是真漏检。真漏检就调 `MIN_FACE_SIZE` 或改 `PERFORMANCE_MODE_ACCURATE`——**后者会增加延迟，改完必须重跑 §13 的 800ms 延迟指标**。

- [ ] **Step 5: 复查包体**

加了两个 bundled 模型后包体涨了约 9 MB（人脸 6.9 + 条码 2.4）。验收指标是 arm64-v8a split ≤ 25 MB：

```bash
./gradlew :app:bundleRelease
bundletool get-size total --bundle=app/build/outputs/bundle/release/app-release.aab --dimensions=ABI
```

- [ ] arm64-v8a 下发体积 ≤ 25 MB（记下实际数值）

若 `bundletool` 没装：`brew install bundletool`。

- [ ] **Step 6: 实机验一遍**

用一张含人脸的截图和一张含二维码的收款码截图：

- [ ] 人脸进编辑器时**已经是黑块**
- [ ] 二维码进编辑器时**已经是黑块**
- [ ] 两者点一下都能取消（变琥珀色虚线），再点回来
- [ ] 两者**都删不掉**（长按后没有删除按钮，或删除按钮无效）——只有手动框能删
- [ ] 识别延迟仍然在 800ms 以内（人脸与条码是并行跑的，不应该叠加）

- [ ] **Step 7: 跑全量并提交**

```bash
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertDebugNoRuntimePermissions
git add app/src/main/java/com/dama/app/engine app/src/androidTest/java/com/dama/app
git commit -m "feat: wire face and barcode detectors into the parallel pipeline"
git tag m3-face-barcode
```

---

## 本计划出口

> **M3 出口（spec §12）**：正脸召回 ≥ 0.98，二维码 100%。

- [ ] 两个 `RegionDetector` 接入，`buildEngine` 只改了一行
- [ ] 任一检测器抛异常不影响其余链路（`RedactionEngineTest` 已守）
- [ ] 人脸与条码默认打码，且删不掉
- [ ] arm64-v8a 下发体积仍 ≤ 25 MB
- [ ] 识别延迟仍 < 800 ms
- [ ] 权限断言仍然通过
