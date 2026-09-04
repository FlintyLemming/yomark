# DAMA 安卓版 · 计划 07 · M5：批量、引导页与商业化

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐首版发布所需的最后一批能力：设置持久化（记住上次样式）、首次安装引导页、`ACTION_SEND_MULTIPLE` 逐张批量流、Play Billing 一次性买断去水印与离线购买态，并跑完 §13 的全部验收指标。

**Architecture:** 购买态是**缓存优先**的：`queryPurchasesAsync` 首次校验需要网络，结果落进 DataStore，此后飞行模式下也认。已购用户在无网环境导出不会突然出现水印——这对一个主打离线的 app 是必须的。批量不做无人值守：`ACTION_SEND_MULTIPLE` 进来后逐张进同一个编辑器，走完最后一张统一导出。

**Tech Stack:** DataStore Preferences · Play Billing Library · Jetpack Compose

**Spec:** `docs/superpowers/specs/2026-09-02-dama-android-design.md`（重点 §7.6、§10、§12 M5、§13）

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-dama-android-00-index.md`

**前置:** 计划 01–06 全部完成

**出口（spec §12）**：达到 §13 的全部指标。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `data/SettingsStore.kt` | DataStore：上次样式、是否已看过引导页 |
| `ui/onboarding/OnboardingScreen.kt` | 首次安装的一屏引导 |
| `ui/batch/BatchSession.kt` | 批量会话的数据结构与推进逻辑 |
| `ui/EditorViewModel.kt`（改） | 批量流、设置读写、购买态接线 |
| `billing/PurchaseStore.kt` | 购买态的本地缓存 |
| `billing/PurchaseResolver.kt` | 「查询结果 + 缓存 → 是否已购」的纯逻辑 |
| `billing/BillingRepository.kt` | Play Billing 连接、查询、发起购买 |
| `ui/components/PaywallDialog.kt` | 去水印的购买入口 |

---

### Task 41: 设置持久化

**Files:**
- Modify: `app/build.gradle.kts`（加 datastore）
- Create: `app/src/main/java/com/dama/app/data/SettingsStore.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorViewModel.kt`、`EditorActivity.kt`
- Test: `app/src/test/java/com/dama/app/data/SettingsStoreTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `class SettingsStore(context: Context)`
  - `val lastStyle: Flow<MaskStyle>`、`suspend fun setLastStyle(style: MaskStyle)`
  - `val onboardingSeen: Flow<Boolean>`、`suspend fun markOnboardingSeen()`

「记住上次样式」是 spec §12 M5 明列的一项。`MaskPlan.style` 是全局单值，所以只需要存一个枚举名。

- [ ] **Step 1: 加依赖并复查权限断言**

```kotlin
    implementation(libs.androidx.datastore.preferences)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

- [ ] **Step 2: 写失败的测试**

`app/src/test/java/com/dama/app/data/SettingsStoreTest.kt`：

```kotlin
package com.dama.app.data

import androidx.test.core.app.ApplicationProvider
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun clean() {
        File(context.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun `default style is solid`() = runTest {
        assertThat(SettingsStore(context).lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `style survives a write and read`() = runTest {
        val store = SettingsStore(context)
        store.setLastStyle(MaskStyle.PIXELATE)
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun `an unknown stored style falls back to solid rather than crashing`() = runTest {
        val store = SettingsStore(context)
        store.writeRawStyleForTest("NOT_A_STYLE")
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `onboarding is unseen by default and sticks once marked`() = runTest {
        val store = SettingsStore(context)
        assertThat(store.onboardingSeen.first()).isFalse()
        store.markOnboardingSeen()
        assertThat(store.onboardingSeen.first()).isTrue()
    }
}
```

- [ ] **Step 3: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/data/SettingsStore.kt`：

```kotlin
package com.dama.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.annotation.VisibleForTesting
import com.dama.app.core.model.MaskStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 本地设置。全部是无隐私含义的偏好项——这里不存任何图像内容或识别结果。 */
class SettingsStore(private val context: Context) {

    val lastStyle: Flow<MaskStyle> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_STYLE] ?: return@map MaskStyle.SOLID
        runCatching { MaskStyle.valueOf(raw) }.getOrDefault(MaskStyle.SOLID)
    }

    suspend fun setLastStyle(style: MaskStyle) {
        context.settingsDataStore.edit { it[KEY_STYLE] = style.name }
    }

    val onboardingSeen: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_ONBOARDING] ?: false }

    suspend fun markOnboardingSeen() {
        context.settingsDataStore.edit { it[KEY_ONBOARDING] = true }
    }

    @VisibleForTesting
    suspend fun writeRawStyleForTest(raw: String) {
        context.settingsDataStore.edit { it[KEY_STYLE] = raw }
    }

    private companion object {
        val KEY_STYLE = stringPreferencesKey("last_style")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_seen")
    }
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*SettingsStoreTest*'
```

预期：4 个测试 PASS。

- [ ] **Step 4: 在 ViewModel 里读写**

`EditorViewModel` 构造参数加 `private val settings: SettingsStore,`，并在 `init` 里恢复上次样式：

```kotlin
    init {
        viewModelScope.launch {
            val style = settings.lastStyle.first()
            _state.value = _state.value.copy(plan = _state.value.plan.copy(style = style))
        }
    }
```

`setStyle` 里加持久化：

```kotlin
    fun setStyle(style: MaskStyle) {
        mutate { it.copy(style = style) }
        _state.value = _state.value.copy(degradeNote = degradeNoteFor(style))
        viewModelScope.launch { settings.setLastStyle(style) }
    }
```

`EditorActivity` 的 factory 里加 `settings = SettingsStore(app),`。

> **同一个提交里要一起改的**：`EditorViewModelTest`（计划 02）与 `EditorViewModelAnalysisTest`（计划 04）的 `vm()` 工厂都要补上 `settings = SettingsStore(context)`。同样**不要给 `settings` 默认值**。

> **注意**：`init` 里的恢复是异步的，而 `EditorViewModelTest` 里 `setStyle` 后 `undo()` 应回到 `SOLID` 的那条测试仍然成立（默认值就是 SOLID）。跑一遍确认没打破既有测试。

- [ ] **Step 5: 跑既有测试并提交**

```bash
./gradlew :app:testDebugUnitTest
git add app/build.gradle.kts app/src/main/java/com/dama/app app/src/test/java/com/dama/app/data
git commit -m "feat: persist the last used mask style and onboarding flag"
```

---

### Task 42: 首次安装引导页

**Files:**
- Create: `app/src/main/java/com/dama/app/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorActivity.kt`
- Test: `app/src/androidTest/java/com/dama/app/ui/OnboardingScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsStore.onboardingSeen`
- Produces: `@Composable fun OnboardingScreen(onStart: () -> Unit)`

**只在首次安装时出现一次**（spec §7.1 括号里那句「首次安装除外，见 M5 的引导页」）。此后冷启动仍然是「点图标 → 直接是一屏照片网格」。

引导页的内容就是产品的核心承诺，说清楚三件事即可：不申请任何权限、识别与编辑不联网、导出会清除元数据。

- [ ] **Step 1: 写 UI 测试**

`app/src/androidTest/java/com/dama/app/ui/OnboardingScreenTest.kt`：

```kotlin
package com.dama.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dama.app.ui.onboarding.OnboardingScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun states_the_three_core_promises() {
        compose.setContent { OnboardingScreen(onStart = {}) }
        compose.onNodeWithText("不申请任何权限").assertIsDisplayed()
        compose.onNodeWithText("识别与编辑全程不联网").assertIsDisplayed()
        compose.onNodeWithText("导出自动清除元数据").assertIsDisplayed()
    }

    @Test
    fun the_start_button_fires_its_callback() {
        var started = false
        compose.setContent { OnboardingScreen(onStart = { started = true }) }
        compose.onNodeWithText("选择图片").performClick()
        assertThat(started).isTrue()
    }
}
```

- [ ] **Step 2: 实现**

`app/src/main/java/com/dama/app/ui/onboarding/OnboardingScreen.kt`：

```kotlin
package com.dama.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次安装的一屏引导（spec §12 M5）。只出现一次。
 *
 * 内容就是产品的核心承诺本身——对一个隐私工具来说，
 * 权限列表上的空白就是最有力的产品说明。
 */
@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("DAMA", style = MaterialTheme.typography.displaySmall)
        Text(
            "给截图打码，不把它交给任何人。",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        Promise("不申请任何权限", "选图走系统相册选择器，写回相册用的是你自己创建的文件。")
        Promise("识别与编辑全程不联网", "文字、人脸、条码三个模型都编译在安装包里，飞行模式照常工作。")
        Promise("导出自动清除元数据", "重新编码输出，GPS、设备型号、拍摄时间一概不带。")

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        ) { Text("选择图片") }
    }
}

@Composable
private fun Promise(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 3: 接进 Activity**

`EditorActivity` 里加一个状态，`onCreate` 改成先读引导标记：

```kotlin
    private val settings by lazy { SettingsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = incomingUri(intent)

        setContent {
            val seen by produceState<Boolean?>(initialValue = null) {
                value = settings.onboardingSeen.first()
            }
            MaterialTheme {
                Surface {
                    when {
                        seen == null -> Unit                       // 读设置的一瞬间，什么都不画
                        seen == false && incoming == null -> OnboardingScreen {
                            lifecycleScope.launch { settings.markOnboardingSeen() }
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        else -> EditorScreen(vm) { finish() }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                // 看过引导、或从分享进来的，走原来的路由；没看过的等用户点按钮
                if (incoming != null || settings.onboardingSeen.first()) route(intent)
            }
        }
    }
```

需要 import `androidx.compose.runtime.produceState`、`androidx.compose.runtime.getValue`、`androidx.lifecycle.lifecycleScope`、`kotlinx.coroutines.flow.first`、`kotlinx.coroutines.launch`。

- [ ] **Step 4: 实机验一遍**

```bash
adb uninstall com.dama.app
./gradlew :app:installDebug
```

- [ ] 首次启动：出现引导页，**不**直接拉 Picker
- [ ] 点「选择图片」→ 进 Picker → 选图 → 进编辑器
- [ ] 杀掉进程再启动：**直接**是 Picker，引导页不再出现
- [ ] 从别的 app 分享图片进来：**跳过**引导页，直接进编辑器

- [ ] **Step 5: 提交**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*OnboardingScreenTest*'
git add app/src/main/java/com/dama/app/ui app/src/androidTest/java/com/dama/app/ui/OnboardingScreenTest.kt
git commit -m "feat: show a one-time onboarding screen stating the privacy promises"
```

---

### Task 43: 批量流

**Files:**
- Create: `app/src/main/java/com/dama/app/ui/batch/BatchSession.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorViewModel.kt`、`EditorUiState.kt`、`EditorActivity.kt`、`ui/components/EditorChrome.kt`
- Test: `app/src/test/java/com/dama/app/ui/batch/BatchSessionTest.kt`
- Test: `app/src/test/java/com/dama/app/ui/EditorViewModelBatchTest.kt`

**Interfaces:**
- Consumes: `MaskPlan`、`SourceImage`、`IntakeResult`
- Produces:
  - `data class BatchItem(uri: Uri, file: File?, mimeType: String, plan: MaskPlan?, analysisScale: Float)`
  - `data class BatchSession(items: List<BatchItem>, index: Int)` + `val current`、`val isLast`、`val total`、`fun withPlan(plan)`、`fun advance()`、`val totalPending: Int`
  - `EditorUiState` 增加 `val batch: BatchSession? = null`
  - `EditorViewModel`：`fun onImagesChosen(uris: List<Uri>)`、`fun nextImage()`、`fun exportBatch(applyWatermark: Boolean)`

**不做无人值守批量**（spec §7.6）。`ACTION_SEND_MULTIPLE` 进来后逐张进入同一个编辑器，底栏出现「下一张」，走完最后一张统一导出。

理由必须留在代码注释里：三态模型的全部价值在于人眼过一遍那些仅圈出的候选。无人值守批量只能二选一——要么把 `OUTLINED` 全部打码（等于取消分层，回到过度遮挡），要么全部不打码（静默漏检）。两个都不可接受。

**导出拦截在批量下按全部图片的 `pendingCount` 之和判定**，不是只看当前这张。

- [ ] **Step 1: 写 BatchSession 的失败测试**

`app/src/test/java/com/dama/app/ui/batch/BatchSessionTest.kt`：

```kotlin
package com.dama.app.ui.batch

import android.graphics.RectF
import android.net.Uri
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.MaskItem
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskState
import com.dama.app.core.model.MaskStyle
import com.dama.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchSessionTest {

    private fun uri(n: Int) = Uri.parse("content://test/$n")

    private fun session(count: Int) =
        BatchSession(items = (1..count).map { BatchItem(uri(it), null, "image/jpeg", null, 1f) }, index = 0)

    private fun planWithPending(n: Int) = MaskPlan(
        items = (1..n).map {
            MaskItem("p$it", Quad.fromRect(RectF(0f, 0f, 10f, 10f)),
                SensitiveKind.URL, DetectorSource.RULE, MaskState.OUTLINED)
        },
        style = MaskStyle.SOLID,
        options = MaskOptions(),
    )

    @Test fun `total is the number of items`() {
        assertThat(session(3).total).isEqualTo(3)
    }

    @Test fun `current points at the indexed item`() {
        val s = session(3).advance()
        assertThat(s.current.uri).isEqualTo(uri(2))
    }

    @Test fun `isLast is true only on the final item`() {
        var s = session(2)
        assertThat(s.isLast).isFalse()
        s = s.advance()
        assertThat(s.isLast).isTrue()
    }

    @Test fun `advancing past the end is a no-op`() {
        val s = session(1).advance().advance()
        assertThat(s.index).isEqualTo(0)
    }

    @Test fun `withPlan stores the plan on the current item only`() {
        val s = session(2).withPlan(planWithPending(1))
        assertThat(s.items[0].plan).isNotNull()
        assertThat(s.items[1].plan).isNull()
    }

    @Test fun `totalPending sums pending across every image`() {
        val s = session(3)
            .withPlan(planWithPending(2)).advance()
            .withPlan(planWithPending(1)).advance()
            .withPlan(planWithPending(0))
        assertThat(s.totalPending).isEqualTo(3)
    }

    @Test fun `an item with no plan yet contributes nothing to totalPending`() {
        assertThat(session(3).withPlan(planWithPending(2)).totalPending).isEqualTo(2)
    }

    @Test fun `maskAllEverywhere clears pending on every stored plan`() {
        val s = session(2).withPlan(planWithPending(2)).advance().withPlan(planWithPending(3))
        assertThat(s.maskAllEverywhere().totalPending).isEqualTo(0)
    }
}
```

- [ ] **Step 2: 运行确认失败，然后实现**

`app/src/main/java/com/dama/app/ui/batch/BatchSession.kt`：

```kotlin
package com.dama.app.ui.batch

import android.net.Uri
import com.dama.app.core.model.MaskPlan
import java.io.File

data class BatchItem(
    val uri: Uri,
    /** 私有副本；尚未载入的项为 null。 */
    val file: File?,
    val mimeType: String,
    /** 用户编辑过的 plan；尚未载入的项为 null。 */
    val plan: MaskPlan?,
    val analysisScale: Float,
)

/**
 * 批量会话（spec §7.6）。
 *
 * **不做无人值守批量。** 逐张进入同一个编辑器，底栏「下一张」，走完最后一张统一导出。
 *
 * 理由：三态模型的全部价值在于人眼过一遍那些仅圈出的候选。无人值守批量只能二选一——
 * 要么把 OUTLINED 全部打码（等于取消分层，回到过度遮挡），要么全部不打码（静默漏检）。
 * 两个都不可接受，所以这个功能形态本身不成立。
 */
data class BatchSession(val items: List<BatchItem>, val index: Int) {

    val total: Int get() = items.size
    val current: BatchItem get() = items[index]
    val isLast: Boolean get() = index == items.lastIndex

    /** 导出拦截在批量下看**全部**图片的待打码数之和，不是只看当前这张。 */
    val totalPending: Int get() = items.sumOf { it.plan?.pendingCount ?: 0 }

    fun withPlan(plan: MaskPlan): BatchSession =
        copy(items = items.mapIndexed { i, item -> if (i == index) item.copy(plan = plan) else item })

    fun withLoaded(file: File, mimeType: String, scale: Float): BatchSession =
        copy(items = items.mapIndexed { i, item ->
            if (i == index) item.copy(file = file, mimeType = mimeType, analysisScale = scale) else item
        })

    fun advance(): BatchSession = if (isLast) this else copy(index = index + 1)

    fun maskAllEverywhere(): BatchSession =
        copy(items = items.map { it.copy(plan = it.plan?.maskAll()) })
}
```

```bash
./gradlew :app:testDebugUnitTest --tests '*BatchSessionTest*'
```

预期：8 个测试 PASS。

- [ ] **Step 3: 写 ViewModel 批量流的失败测试**

`app/src/test/java/com/dama/app/ui/EditorViewModelBatchTest.kt`：

```kotlin
package com.dama.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.model.TextLine
import com.dama.app.data.SettingsStore
import com.dama.app.engine.CandidateMerger
import com.dama.app.engine.RedactionEngine
import com.dama.app.engine.SensitivityClassifier
import com.dama.app.engine.TextRecognizer
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.Candidate
import com.dama.app.export.Exporter
import com.dama.app.export.ImageSink
import com.dama.app.export.WatermarkDrawer
import com.dama.app.render.RendererRegistry
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
class EditorViewModelBatchTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class CountingSink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://fake/$writes") }
    }

    private lateinit var sink: CountingSink

    private fun vm(): EditorViewModel {
        sink = CountingSink()
        val engine = RedactionEngine(
            object : TextRecognizer {
                override val id = "none"
                override suspend fun recognize(image: SourceImage) = emptyList<TextLine>()
            },
            emptyList(),
            listOf(object : SensitivityClassifier {
                override val id = "none"
                override suspend fun isAvailable() = true
                override suspend fun classify(lines: List<TextLine>) = emptyList<Candidate>()
            }),
            CandidateMerger(),
        )
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engine = engine,
            settings = SettingsStore(context),
            ioDispatcher = dispatcher,
        )
    }

    private fun sampleUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    @Test
    fun `a batch starts on the first image and reports its size`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("b1.jpg"), sampleUri("b2.jpg"), sampleUri("b3.jpg")))
        advanceUntilIdle()

        assertThat(vm.state.value.batch!!.total).isEqualTo(3)
        assertThat(vm.state.value.batch!!.index).isEqualTo(0)
        assertThat(vm.state.value.image).isNotNull()
    }

    @Test
    fun `advancing keeps the edits made on the previous image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("c1.jpg"), sampleUri("c2.jpg")))
        advanceUntilIdle()

        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.nextImage(); advanceUntilIdle()

        assertThat(vm.state.value.batch!!.index).isEqualTo(1)
        assertThat(vm.state.value.plan.items).isEmpty()                       // 新图是干净的
        assertThat(vm.state.value.batch!!.items[0].plan!!.items).hasSize(1)   // 上一张的编辑还在
    }

    @Test
    fun `the undo stack is cleared when moving to the next image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("d1.jpg"), sampleUri("d2.jpg")))
        advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        assertThat(vm.state.value.canUndo).isTrue()

        vm.nextImage(); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `exporting a batch writes one file per image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("e1.jpg"), sampleUri("e2.jpg")))
        advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.nextImage(); advanceUntilIdle()

        vm.exportBatch(applyWatermark = true)
        advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(2)
    }

    @Test
    fun `a single shared image does not start a batch`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("f1.jpg")))
        advanceUntilIdle()
        assertThat(vm.state.value.batch).isNull()
    }
}
```

- [ ] **Step 4: 实现批量流**

`EditorUiState` 加 `val batch: BatchSession? = null,`。

`EditorViewModel` 加：

```kotlin
    /**
     * 批量入口。单张时不开批量会话，行为与 onImageChosen 完全一致——
     * 「下一张」按钮只在真的有下一张时出现。
     */
    fun onImagesChosen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) { onImageChosen(uris.first()); return }

        val session = BatchSession(
            items = uris.map { BatchItem(it, null, "image/jpeg", null, 1f) },
            index = 0,
        )
        _state.value = _state.value.copy(batch = session)
        loadBatchCurrent()
    }

    /** 保存当前这张的编辑，推进到下一张。 */
    fun nextImage() {
        val batch = _state.value.batch ?: return
        val saved = batch.withPlan(_state.value.plan)
        if (saved.isLast) return
        _state.value = _state.value.copy(batch = saved.advance())
        loadBatchCurrent()
    }

    private fun loadBatchCurrent() {
        val batch = _state.value.batch ?: return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val loaded = runCatching {
                val taken = withContext(ioDispatcher) { intake.copyToPrivate(batch.current.uri) }
                taken to SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType)
            }.getOrElse {
                _state.value = _state.value.copy(loading = false, message = EditorMessage.Error("无法打开这张图片"))
                return@launch
            }
            val (taken, image) = loaded
            intakeResult = taken
            undoStack.clear()
            _state.value = _state.value.copy(
                image = image,
                plan = MaskPlan.empty(_state.value.plan.style),
                loading = false,
                analyzing = true,
                canUndo = false,
                canRedo = false,
                selectedManualId = null,
                batch = _state.value.batch!!.withLoaded(taken.file, taken.mimeType, image.scale),
            )
            analyze(image)
        }
    }

    /**
     * 批量导出：走完最后一张后一次性导出全部（spec §7.6）。
     * 拦截按**全部图片**的待打码数之和判定。
     */
    fun exportBatch(applyWatermark: Boolean) {
        val batch = _state.value.batch?.withPlan(_state.value.plan) ?: return
        _state.value = _state.value.copy(batch = batch)
        if (batch.totalPending > 0) {
            _state.value = _state.value.copy(pendingDialogVisible = true)
            return
        }
        runBatchExport(batch, applyWatermark)
    }

    private fun runBatchExport(batch: BatchSession, applyWatermark: Boolean) {
        _state.value = _state.value.copy(exporting = true)
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            batch.items.forEach { item ->
                val file = item.file ?: return@forEach
                val plan = item.plan ?: return@forEach
                val outcome = exporter.export(
                    ExportRequest(file, item.mimeType, plan, item.analysisScale, applyWatermark,
                        purposeText = _state.value.purposeText)
                )
                if (outcome is ExportOutcome.Success) ok++ else failed++
            }
            _state.value = _state.value.copy(
                exporting = false,
                message = if (failed == 0) EditorMessage.BatchExported(ok)
                else EditorMessage.Error("$ok 张已保存，$failed 张失败"),
            )
        }
    }
```

`EditorMessage` 加一个分支：

```kotlin
    data class BatchExported(val count: Int) : EditorMessage
```

> `EditorScreen` 里那个 `when (val m = state.message)` 是穷尽式的，加了分支就编译不过——顺手补上：
>
> ```kotlin
>             is EditorMessage.BatchExported -> snackbar.showMessage("已保存 ${m.count} 张到相册", vm)
> ```

拦截对话框的两个确认动作也要认批量。把 `confirmMaskAllAndExport` / `confirmExportAnyway` 改成：

```kotlin
    fun confirmMaskAllAndExport(applyWatermark: Boolean) {
        _state.value = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) {
            val cleared = batch.withPlan(_state.value.plan).maskAllEverywhere()
            _state.value = _state.value.copy(batch = cleared, plan = cleared.current.plan ?: _state.value.plan.maskAll())
            runBatchExport(cleared, applyWatermark)
        } else {
            mutate { it.maskAll() }
            runExport(_state.value.plan, applyWatermark)
        }
    }

    fun confirmExportAnyway(applyWatermark: Boolean) {
        _state.value = _state.value.copy(pendingDialogVisible = false)
        val batch = _state.value.batch
        if (batch != null) runBatchExport(batch.withPlan(_state.value.plan), applyWatermark)
        else runExport(_state.value.plan, applyWatermark)
    }
```

拦截对话框的计数在批量下要用 `batch.totalPending`——`EditorScreen` 里改成：

```kotlin
                val pending = state.batch?.let { it.withPlan(state.plan).totalPending } ?: state.plan.pendingCount
                val byKind = state.batch?.let { b ->
                    b.withPlan(state.plan).items.mapNotNull { it.plan }
                        .flatMap { p -> p.pendingByKind().entries }
                        .groupBy({ it.key }, { it.value })
                        .mapValues { (_, v) -> v.sum() }
                } ?: state.plan.pendingByKind()
                PendingExportDialog(pending, byKind, …)
```

- [ ] **Step 5: 底栏加「下一张」**

`EditorBottomBar` 加 `batchLabel: String?` 与 `onNext: (() -> Unit)?` 两个参数；`onNext != null` 时把导出按钮换成「下一张」，并在旁边显示 `batchLabel`（如「2 / 5」）。`EditorScreen` 里：

```kotlin
            val batch = state.batch
            val showNext = batch != null && !batch.isLast
            EditorBottomBar(
                style = state.plan.style,
                degradeNote = state.degradeNote,
                onStyleChange = vm::setStyle,
                batchLabel = batch?.let { "${it.index + 1} / ${it.total}" },
                onNext = if (showNext) vm::nextImage else null,
                onExport = {
                    if (batch != null) vm.exportBatch(applyWatermark = true)
                    else vm.requestExport(applyWatermark = true)
                },
                exporting = state.exporting,
            )
```

- [ ] **Step 6: Activity 接 SEND_MULTIPLE 与多选 Picker**

`EditorActivity` 的 `incomingUri` 拆成 `incomingUris(intent): List<Uri>`，`ACTION_SEND_MULTIPLE` 返回全部；`route` 里调 `vm.onImagesChosen(uris)`。

Picker 也支持多选（`PickMultipleVisualMedia`）——但**单张仍走单选 Picker**，因为多选 Picker 多一步「确认」。批量只从 `ACTION_SEND_MULTIPLE` 进，与 spec §7.6 一致。

- [ ] **Step 7: 运行并实机验证**

```bash
./gradlew :app:testDebugUnitTest --tests '*Batch*' --tests '*EditorViewModel*'
./gradlew :app:installDebug
```

在相册里选 3 张图 → 分享到 DAMA：

- [ ] 进第一张，底栏显示「1 / 3」和「下一张」
- [ ] 编辑后点「下一张」→ 进第二张，编辑内容不丢
- [ ] 最后一张底栏变回「导出」
- [ ] 点导出 → 三张一起写进相册
- [ ] 任一张有 `OUTLINED` 时，拦截对话框显示的是**三张加起来**的数量

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/dama/app app/src/test/java/com/dama/app/ui
git commit -m "feat: add attended batch flow for ACTION_SEND_MULTIPLE with aggregate export interception"
```

---

### Task 44: 购买态与 Play Billing

**Files:**
- Modify: `app/build.gradle.kts`（加 billing）
- Create: `app/src/main/java/com/dama/app/billing/PurchaseStore.kt`
- Create: `app/src/main/java/com/dama/app/billing/PurchaseResolver.kt`
- Create: `app/src/main/java/com/dama/app/billing/BillingRepository.kt`
- Test: `app/src/test/java/com/dama/app/billing/PurchaseResolverTest.kt`
- Test: `app/src/test/java/com/dama/app/billing/PurchaseStoreTest.kt`

**Interfaces:**
- Consumes: `SettingsStore` 的 DataStore 模式
- Produces:
  - `class PurchaseStore(context)` + `val isPro: Flow<Boolean>` + `suspend fun setPro(Boolean)`
  - `sealed interface QueryOutcome { data class Ok(val owned: Boolean); data object Unavailable }`
  - `object PurchaseResolver { fun resolve(cached: Boolean, outcome: QueryOutcome): Boolean }`
  - `class BillingRepository(context, store, scope)` + `val isPro: StateFlow<Boolean>` + `fun start()` + `suspend fun launchPurchase(activity: Activity): Result<Unit>` + `fun stop()`
  - `const val PRODUCT_REMOVE_WATERMARK = "dama_remove_watermark"`

**商业模式（spec §10）**：
- 一次性买断，非订阅。无服务端，无账号。
- 免费版：完整识别、完整三态编辑、全部六种打码样式、原图分辨率导出、元数据清除。**一项隐私能力都不缺。**
- 付费解锁：去除品牌水印。仅此一项。

**离线购买态是硬要求**：`queryPurchasesAsync` 首次校验需要网络，结果缓存进 DataStore，此后飞行模式下也认。已购用户在无网环境导出**不会突然出现水印**。

**缓存对 root 设备可篡改，这一点接受**：它保护的是一个水印，不是一道安全边界；为它引入服务端校验会同时破坏零网络与无账号两个前提。这条决定要写进代码注释，免得后来者「顺手加个校验接口」。

- [ ] **Step 1: 写解析逻辑的失败测试**

`app/src/test/java/com/dama/app/billing/PurchaseResolverTest.kt`：

```kotlin
package com.dama.app.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PurchaseResolverTest {

    @Test fun `a successful query saying owned makes the user pro`() {
        assertThat(PurchaseResolver.resolve(cached = false, outcome = QueryOutcome.Ok(owned = true))).isTrue()
    }

    @Test fun `a successful query saying not owned revokes a stale cache`() {
        // 退款 / 撤单：联网查得到就以查询结果为准
        assertThat(PurchaseResolver.resolve(cached = true, outcome = QueryOutcome.Ok(owned = false))).isFalse()
    }

    @Test fun `an unavailable query keeps the cached pro state`() {
        // 飞行模式下已购用户不能突然长出水印
        assertThat(PurchaseResolver.resolve(cached = true, outcome = QueryOutcome.Unavailable)).isTrue()
    }

    @Test fun `an unavailable query does not grant pro to a free user`() {
        assertThat(PurchaseResolver.resolve(cached = false, outcome = QueryOutcome.Unavailable)).isFalse()
    }
}
```

`app/src/test/java/com/dama/app/billing/PurchaseStoreTest.kt`：

```kotlin
package com.dama.app.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PurchaseStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun clean() { File(context.filesDir, "datastore").deleteRecursively() }

    @Test fun `a fresh install is not pro`() = runTest {
        assertThat(PurchaseStore(context).isPro.first()).isFalse()
    }

    @Test fun `pro state survives a write and read`() = runTest {
        val store = PurchaseStore(context)
        store.setPro(true)
        assertThat(store.isPro.first()).isTrue()
    }

    @Test fun `pro state can be revoked`() = runTest {
        val store = PurchaseStore(context)
        store.setPro(true)
        store.setPro(false)
        assertThat(store.isPro.first()).isFalse()
    }
}
```

- [ ] **Step 2: 加依赖并实现**

```kotlin
    implementation(libs.billing.ktx)
```

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

**这一步的预期与之前所有依赖都不同**：断言现在应该**打印出 `com.android.vending.BILLING`** 并通过（它在允许列表里）。若出现任何**别的**权限，构建会失败——那正是 spec §15.4 要验的东西。

把断言的输出记进 spec §15 第 4 条：合并后 manifest 里到底有哪几条 `uses-permission`。

`app/src/main/java/com/dama/app/billing/PurchaseStore.kt`：

```kotlin
package com.dama.app.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.billingDataStore: DataStore<Preferences> by preferencesDataStore(name = "purchase")

/**
 * 购买态的本地缓存（spec §10）。
 *
 * queryPurchasesAsync 首次校验需要网络；结果缓存在这里，此后飞行模式下也认。
 * 已购用户在无网环境导出不会突然出现水印——这对一个主打离线的 app 是必须的。
 *
 * **缓存对 root 设备是可篡改的，这一点是接受的。** 它保护的是一个水印，不是一道安全边界；
 * 为它引入服务端校验会同时破坏零网络与无账号两个前提。不要「顺手加个校验接口」。
 */
class PurchaseStore(private val context: Context) {

    val isPro: Flow<Boolean> = context.billingDataStore.data.map { it[KEY_PRO] ?: false }

    suspend fun setPro(value: Boolean) {
        context.billingDataStore.edit { it[KEY_PRO] = value }
    }

    private companion object { val KEY_PRO = booleanPreferencesKey("pro") }
}
```

`app/src/main/java/com/dama/app/billing/PurchaseResolver.kt`：

```kotlin
package com.dama.app.billing

sealed interface QueryOutcome {
    /** 查询成功，Play 明确回答了「有没有」。 */
    data class Ok(val owned: Boolean) : QueryOutcome
    /** 查不到：无网络、服务未连上、Play 服务不可用。 */
    data object Unavailable : QueryOutcome
}

/**
 * 「缓存 + 查询结果 → 是否已购」的全部规则，抽成纯函数以便测试。
 *
 * 查得到就以查询结果为准（覆盖退款/撤单）；查不到就沿用缓存（覆盖飞行模式）。
 */
object PurchaseResolver {
    fun resolve(cached: Boolean, outcome: QueryOutcome): Boolean = when (outcome) {
        is QueryOutcome.Ok -> outcome.owned
        QueryOutcome.Unavailable -> cached
    }
}
```

`app/src/main/java/com/dama/app/billing/BillingRepository.kt`：

```kotlin
package com.dama.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val PRODUCT_REMOVE_WATERMARK = "dama_remove_watermark"

/**
 * Play Billing 接入（spec §10）：一次性买断，非订阅，无服务端，无账号。
 * 付费解锁的**唯一**内容是去除品牌水印。
 *
 * 购买流程是全应用**唯一**会发起网络请求的地方，且必须由用户主动触发
 * （§13 的「网络请求 = 0」指标明确排除了这条路径）。
 */
class BillingRepository(
    context: Context,
    private val store: PurchaseStore,
    private val scope: CoroutineScope,
) {

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { applyPurchases(purchases) }
        }
    }

    private val client = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases()
        .build()

    /** 启动时调用：先用缓存点亮 UI，再尝试联网校验。 */
    fun start() {
        scope.launch { _isPro.value = store.isPro.first() }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch { sync() }
                }
            }
            override fun onBillingServiceDisconnected() {
                // 断连不改变购买态：沿用缓存（PurchaseResolver 的 Unavailable 分支）
            }
        })
    }

    fun stop() = client.endConnection()

    private suspend fun sync() {
        val cached = store.isPro.first()
        val outcome = queryOwned()
        val resolved = PurchaseResolver.resolve(cached, outcome)
        if (resolved != cached) store.setPro(resolved)
        _isPro.value = resolved
    }

    private suspend fun queryOwned(): QueryOutcome = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                cont.resume(QueryOutcome.Unavailable)
            } else {
                val owned = purchases.any {
                    it.products.contains(PRODUCT_REMOVE_WATERMARK) &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                scope.launch { purchases.forEach { acknowledgeIfNeeded(it) } }
                cont.resume(QueryOutcome.Ok(owned))
            }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        ) { /* 确认失败下次启动会重试 */ }
    }

    private suspend fun applyPurchases(purchases: List<Purchase>) {
        val owned = purchases.any {
            it.products.contains(PRODUCT_REMOVE_WATERMARK) &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (owned) {
            purchases.forEach { acknowledgeIfNeeded(it) }
            store.setPro(true)
            _isPro.value = true
        }
    }

    suspend fun launchPurchase(activity: Activity): Result<Unit> = runCatching {
        val details = queryProductDetails() ?: error("商品信息暂不可用")
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) error("无法启动购买流程")
    }

    private suspend fun queryProductDetails() = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_REMOVE_WATERMARK)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { _, details ->
            cont.resume(details.firstOrNull())
        }
    }
}
```

> **API 漂移提示**：Play Billing 每年都会变签名（`queryProductDetailsAsync` 的回调类型、`enablePendingPurchases` 的参数在不同大版本里都改过）。若编译不过，以当前版本的官方文档为准调整调用方式——**但不要改变上面的行为契约**：缓存优先、断连不降级、只认 `PRODUCT_REMOVE_WATERMARK`、购买后必须 acknowledge。

- [ ] **Step 3: 运行单测**

```bash
./gradlew :app:testDebugUnitTest --tests '*Purchase*'
```

预期：7 个测试 PASS。

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts app/src/main/java/com/dama/app/billing app/src/test/java/com/dama/app/billing docs/superpowers/specs
git commit -m "feat: add one-time purchase with offline-first cached entitlement"
```

---

### Task 45: 去水印接线与购买入口

**Files:**
- Create: `app/src/main/java/com/dama/app/ui/components/PaywallDialog.kt`
- Modify: `app/src/main/java/com/dama/app/ui/EditorViewModel.kt`、`EditorUiState.kt`、`EditorScreen.kt`、`EditorActivity.kt`
- Test: `app/src/test/java/com/dama/app/ui/EditorViewModelWatermarkTest.kt`

**Interfaces:**
- Consumes: `BillingRepository.isPro`
- Produces:
  - `EditorUiState` 增加 `val isPro: Boolean = false`、`val paywallVisible: Boolean = false`
  - `EditorViewModel`：`fun observePro(flow: StateFlow<Boolean>)`、`fun showPaywall()`、`fun dismissPaywall()`
  - 全部 `requestExport` / `exportBatch` 调用点不再硬编码 `applyWatermark = true`，改成 `applyWatermark = !state.isPro`

到这一步为止，计划 02–06 里所有 `applyWatermark = true` 的硬编码都要清掉。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/ui/EditorViewModelWatermarkTest.kt`：

```kotlin
package com.dama.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.Candidate
import com.dama.app.core.model.TextLine
import com.dama.app.data.SettingsStore
import com.dama.app.engine.CandidateMerger
import com.dama.app.engine.RedactionEngine
import com.dama.app.engine.SensitivityClassifier
import com.dama.app.engine.TextRecognizer
import com.dama.app.export.Exporter
import com.dama.app.export.ImageSink
import com.dama.app.export.WatermarkDrawer
import com.dama.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
class EditorViewModelWatermarkTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class CapturingSink : ImageSink {
        var last: Bitmap? = null
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { last = bitmap.copy(Bitmap.Config.ARGB_8888, false); return Uri.parse("content://fake/1") }
    }

    private lateinit var sink: CapturingSink

    private fun vm(): EditorViewModel {
        sink = CapturingSink()
        val engine = RedactionEngine(
            object : TextRecognizer {
                override val id = "none"
                override suspend fun recognize(image: SourceImage) = emptyList<TextLine>()
            },
            emptyList(),
            listOf(object : SensitivityClassifier {
                override val id = "none"
                override suspend fun isAvailable() = true
                override suspend fun classify(lines: List<TextLine>) = emptyList<Candidate>()
            }),
            CandidateMerger(),
        )
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engine = engine,
            settings = SettingsStore(context),
            ioDispatcher = dispatcher,
        )
    }

    private fun whiteUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.toUri()
    }

    /** 水印落在右下角。这块区域有没有非白像素，就是有没有水印。 */
    private fun cornerMarked(): Boolean {
        val bmp = sink.last!!
        for (x in 500 until 590 step 3) for (y in 500 until 590 step 3) {
            if (bmp.getPixel(x, y) != Color.WHITE) return true
        }
        return false
    }

    @Test
    fun `a free user gets the brand watermark`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(whiteUri("free.png")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.requestExport(); advanceUntilIdle()
        assertThat(cornerMarked()).isTrue()
    }

    @Test
    fun `a pro user gets no watermark`() = runTest(dispatcher) {
        val vm = vm()
        val pro = MutableStateFlow(true)
        vm.observePro(pro); advanceUntilIdle()

        vm.onImageChosen(whiteUri("pro.png")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.requestExport(); advanceUntilIdle()
        assertThat(cornerMarked()).isFalse()
    }

    @Test
    fun `pro state flowing in flips the ui state`() = runTest(dispatcher) {
        val vm = vm()
        val pro = MutableStateFlow(false)
        vm.observePro(pro); advanceUntilIdle()
        assertThat(vm.state.value.isPro).isFalse()

        pro.value = true; advanceUntilIdle()
        assertThat(vm.state.value.isPro).isTrue()
    }
}
```

- [ ] **Step 2: 实现**

`EditorUiState` 加两个字段。`EditorViewModel` 加：

```kotlin
    /** Activity 在 onCreate 里把 BillingRepository.isPro 接进来。 */
    fun observePro(flow: StateFlow<Boolean>) {
        viewModelScope.launch {
            flow.collect { pro -> _state.value = _state.value.copy(isPro = pro) }
        }
    }

    fun showPaywall() { _state.value = _state.value.copy(paywallVisible = true) }
    fun dismissPaywall() { _state.value = _state.value.copy(paywallVisible = false) }
```

把 `requestExport` / `confirmMaskAllAndExport` / `confirmExportAnyway` / `exportBatch` 的 `applyWatermark: Boolean` 参数**全部去掉**，内部统一用 `!_state.value.isPro`。

> **这次是删参数，波及面比前两次大。** 一次改完，不要留两套重载：
> - `EditorScreen.kt`：底栏导出、拦截对话框的两个按钮，共 3 处
> - `EditorViewModelTest`（计划 02）：`requestExport(applyWatermark = true)` 等 4 处
> - `EditorViewModelBatchTest`（计划 07 Task 43）：`exportBatch(applyWatermark = true)` 1 处
>
> 改完 `./gradlew :app:testDebugUnitTest` 必须全绿——**免费用户有水印、付费用户没有**这条行为由本任务新加的测试守，旧测试只需要能编译并保持原有断言。

`app/src/main/java/com/dama/app/ui/components/PaywallDialog.kt`：

```kotlin
package com.dama.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 去水印的购买入口（spec §10）。
 *
 * 措辞要说清楚「付费买的纯粹是外观」——免费版一项隐私能力都不缺。
 * 这是避免商店评论区出现「保护隐私还要收费」的唯一办法。
 */
@Composable
fun PaywallDialog(onBuy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("去除水印") },
        text = {
            Column {
                Text("一次性买断，不是订阅。")
                Text("免费版的隐私能力一项都不缺：完整识别、三态编辑、六种打码样式、原图分辨率导出、元数据清除。付费去掉的只是导出图右下角的 DAMA 标记。")
            }
        },
        confirmButton = { TextButton(onClick = onBuy) { Text("购买") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("以后再说") } },
    )
}
```

`EditorScreen` 里加对话框与入口（顶栏加一个按钮，`state.isPro` 为 true 时隐藏）：

```kotlin
            if (state.paywallVisible) {
                PaywallDialog(onBuy = onBuyClicked, onDismiss = vm::dismissPaywall)
            }
```

`EditorScreen` 签名加 `onBuyClicked: () -> Unit`。

`EditorActivity`：

```kotlin
    private val billing by lazy { BillingRepository(applicationContext, PurchaseStore(applicationContext), lifecycleScope) }

    // onCreate 里：
        billing.start()
        vm.observePro(billing.isPro)
        // setContent 里把 onBuyClicked 传成：
        //   { lifecycleScope.launch { billing.launchPurchase(this@EditorActivity) } }

    override fun onDestroy() {
        billing.stop()
        super.onDestroy()
    }
```

- [ ] **Step 3: 运行全部单测**

```bash
./gradlew :app:testDebugUnitTest
```

预期：全绿。若计划 02/04 的测试因为去掉 `applyWatermark` 参数而编译失败，一并改完——**不要保留两套签名**。

- [ ] **Step 4: 在 Play Console 配置商品并实测购买**

- [ ] Play Console → 应用内商品 → 新建**一次性商品**，商品 ID 填 `dama_remove_watermark`
- [ ] 上传一个 internal testing 轨道的构建
- [ ] 用测试账号完成一次购买
- [ ] 购买后导出：水印消失
- [ ] **杀掉进程 → 开飞行模式 → 重新打开 → 导出：水印仍然不出现**（这是离线购买态的关键验证）
- [ ] 清除应用数据 → 联网打开 → 购买态被 `queryPurchasesAsync` 恢复，水印消失

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/dama/app app/src/test/java/com/dama/app/ui
git commit -m "feat: remove the brand watermark for purchasers, with offline entitlement"
```

---

### Task 46: §13 全量验收

**Files:**
- Create: `docs/release-checklist.md`
- Modify: `docs/superpowers/specs/2026-09-02-dama-android-design.md`（§15 剩余条目的实测结论）

**Interfaces:**
- Consumes: 全部功能
- Produces: 一份逐条对照 spec §13 的验收记录

- [ ] **Step 1: 跑全量自动化**

```bash
./gradlew clean
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assertReleaseNoRuntimePermissions :app:bundleRelease
```

- [ ] **Step 2: 逐条对照 §13**

把结果写进 `docs/release-checklist.md`，每一条都要有**实测数值**，不能只打勾：

| 维度 | 指标 | 阈值 | 实测 | 怎么测的 |
|---|---|---|---|---|
| 圈出率 | 标注实体被 MASKED 或 OUTLINED 覆盖 | ≥ 0.95 | | `EvaluationTest`，真实样本集 |
| 默认打码召回率 | 默认打码类型被 MASKED 覆盖 | ≥ 0.95 | | 同上 |
| 精确率 | MASKED 区域确实覆盖敏感内容 | ≥ 0.70 | | 同上 |
| 识别延迟 | 1080×2400，中端机，选中到候选出现 | < 800 ms | | `EvaluationTest` 的延迟用例 |
| 冷启动 | 点图标到 Photo Picker 可交互 | < 1.2 s | | `adb shell am start -W` 的 TotalTime |
| 下发体积 | arm64-v8a split | ≤ 25 MB | | `bundletool get-size total --dimensions=ABI` |
| 运行期敏感权限 | 除 BILLING 外的 uses-permission 条数 | 0 | | `assertReleaseNoRuntimePermissions` |
| 网络请求 | 全流程（不含购买）运行期请求数 | 0 | | 抓包 |
| 导出拦截 | pendingCount > 0 时对话框触发率 | 100% | | 手动 + `EditorViewModelTest` |
| 水印避让 | 水印与遮罩相交的比例 | 0% | | `WatermarkDrawerTest` + 手动 |
| 元数据 | 导出图读到 GPS/型号/时间戳的条数 | 0 | | `ExporterTest` + `MediaStoreSinkTest` |
| 不可还原 | 像素化与实色块导出图 | 去码工具验证不可读 | | `IrreversibilityTest` + Depix 实测 |

冷启动测量：

```bash
adb shell am force-stop com.dama.app
adb shell am start -W -n com.dama.app/.ui.EditorActivity | grep TotalTime
```

体积测量：

```bash
bundletool get-size total --bundle=app/build/outputs/bundle/release/app-release.aab --dimensions=ABI
```

- [ ] **Step 3: 抓包验证零网络**

用 mitmproxy 或 Charles 代理设备流量，走完整流程（不点购买）：

```bash
# 简化替代：断网跑一遍，行为必须与联网时完全一致
adb shell svc wifi disable && adb shell svc data disable
# ……走一遍选图 → 识别 → 编辑 → 导出……
adb shell svc wifi enable
```

- [ ] 运行期请求数 = 0
- [ ] 断网下识别结果与联网时完全一致

- [ ] **Step 4: 收尾 spec §15 的剩余条目**

逐条确认并把结论写进 spec：

- [ ] §15.1 ML Kit bundled 不需要网络 —— 计划 03 已验
- [ ] §15.2 零权限完整闭环 + content URI 重建后可读性 —— 计划 02/04 已验；用「不保留活动」再确认一次私有副本方案的必要性
- [ ] §15.3 冷启动直接拉 Picker 的体验 —— 计划 02 已验
- [ ] §15.4 Play Billing 合并后的权限清单 —— 计划 07 Task 44 已验
- [ ] §15.5 词级映射的实际粒度 —— 计划 03 已验
- [ ] §15.6 像素化块尺寸下限 —— 计划 06 已验
- [ ] §15.7 大图导出的内存上限 —— **本任务补验**：在一台低端机上导出一张 32 MP 的图，确认不 OOM。若 OOM，下调 `SourceImageLoader.EXPORT_MAX_PIXELS` 并同步改 spec §9.2 的数值。
- [ ] §15.8 水印避让在四角全被占时的表现 —— **本任务补验**：造一张整屏聊天记录全打码的图，看加描边压在右下的效果是否可接受。
- [ ] §15.9 各 artifact 的当前版本与体积 —— 把 `libs.versions.toml` 里的实际版本与 `bundletool` 报的实际体积写回 spec §11 的包体预算表。

- [ ] **Step 5: 上架前检查**

- [ ] 商店描述**首屏**写明：本应用不申请任何运行期敏感权限；`com.android.vending.BILLING` 是 Play Billing 库并入的，不授予任何数据访问能力（spec §0 的立场）
- [ ] 隐私政策写明：不收集、不上传任何数据；无账号；无分析 SDK
- [ ] Data safety 表单：全部选「不收集」
- [ ] 免费版功能描述里明确「隐私能力一项不缺，付费只去水印」

- [ ] **Step 6: 提交并打标签**

```bash
git add docs
git commit -m "docs: record §13 acceptance results and close out the §15 verification list"
git tag m5-release-ready
```

---

## 本计划出口

> **M5 出口（spec §12）**：达到 §13 的全部指标。

- [ ] `docs/release-checklist.md` 每一行都有实测数值
- [ ] spec §15 的九条待验证项全部有实测结论
- [ ] 运行期敏感权限 = 0，由 CI 断言守着
- [ ] 运行期网络请求 = 0
- [ ] 已购用户在飞行模式下导出无水印
- [ ] 批量流走得通，拦截按全部图片汇总判定

到这里首版可以上架。

---

## 执行记录（2026-09-03）

Task 41–46 全部执行。环境：模拟器 `Medium_Phone` AVD / android-37.1 / arm64-v8a / playstore，
JDK 21，AGP 8.11.1。最终自动化：**单元测试 316 条全绿；instrumented 59 条，58 通过 1 跳过**
（跳过的是正脸召回，`androidTest/assets/faces/` 为空，M3 遗留）。

### 1. `EditorViewModel` 的构造器多一个参数（Task 41、43、45 全部相关）

计划里所有 `EditorViewModel(...)` 片段都没有 `savedState: SavedStateHandle`——
那是计划 04 为「Activity 重建后恢复」加的，不能去掉。`settings` 是**新增**参数，
不是替换。最终签名：`intake, exporter, engine, settings, savedState, ioDispatcher`。
四个测试类（`EditorViewModelTest` / `AnalysisTest` / `RestoreTest` / 新增的
`BatchTest`、`WatermarkTest`）的工厂都补了这一项。

### 2. DataStore 的单例委托与 Robolectric 打架，测试走内部构造器

计划的 `SettingsStoreTest` / `PurchaseStoreTest` 每个用例 `new` 一个 store 再靠
`File(filesDir, "datastore").deleteRecursively()` 清场。这在 Robolectric 下不成立：
`preferencesDataStore` 是**文件级属性委托**，`INSTANCE` 在整个进程里只创建一次，
而同一个测试类的各个用例共用类加载器——第一个用例建的 DataStore 会带着数据
（和一个已被删掉的目录）活到后面的用例，「默认值是 SOLID」「全新安装不是 pro」
这类断言随执行顺序时好时坏。

处置：`SettingsStore` / `PurchaseStore` 各加一个 `internal constructor(DataStore<Preferences>)`，
公开的 `(Context)` 构造器仍在（生产路径不变，仍是单例，否则同一文件两个 DataStore 会崩）。
测试用 `data.isolatedSettingsStore()` / `billing.isolatedPurchaseStore()` 各拿一个独立文件。

### 3. 批量载入不能逐张 `intake.clear()`（Task 43）

`onImageChosen` 会在换图前清空 intake 目录。批量若照抄这个路径，
`exportBatch` 到时前面几张的私有副本已经被删掉，导出只剩最后一张。
改成**只在 `onImagesChosen` 开会话时清一次**。

批量会话本身不进 `SavedStateHandle`：进程被杀后回来退化成单张（`restore()` 那条路）。
这是有意的降级，理由写在 `loadBatchCurrent` 的注释里。

### 4. 实机清单改成了自动化测试（Task 43 Step 7）

计划里的「相册选 3 张分享进来」手工清单换成 `BatchFlowTest`（androidTest）：
素材经 `MediaStoreSink` 写入，是**本应用自己创建的**媒体文件，读回来不需要任何权限，
因此这条测试本身也顺带守着零权限承诺。`am start` 合成的 `SEND_MULTIPLE` intent
无法真正授予 content URI 权限（spec §15.2 早有记载），手工路线在 adb 上走不通。

### 5. 新测试逮到一个真 bug（Task 45）

`onImageChosen` / `onImagesChosen` / `restore` 都是整只重建 `EditorUiState`，
把 `isPro` 一起清成 `false`——已购用户**换一张图水印就回来了**。
`StateFlow` 不会重发相同值，所以 `observePro` 的收集器也救不回来。三处都改成显式带上购买态。

### 6. Play Billing 8.0.0 与计划片段的 API 漂移（Task 44）

行为契约（缓存优先 / 断连不降级 / 只认 `PRODUCT_REMOVE_WATERMARK` / 购买后必须 acknowledge）
一条没变，调用方式改了两处：

- `enablePendingPurchases()` 自 6.2 起要传 `PendingPurchasesParams`；
- `queryProductDetailsAsync` 的回调第二参在 8.0 是 `QueryProductDetailsResult`，不再是 `List<ProductDetails>`。

另加了 `enableAutoServiceReconnection()`——8.0 新增，断连后不至于永久查不到。

### 7. `EXPORT_MAX_PIXELS` 的边界值容易写错（Task 46 / spec §15.7）

`LargeImageExportTest` 第一版用 5657×5657，测试失败：5657² = 32,001,649 px
已经**超过** 32,000,000 的上限，会被降采样。上限是像素数不是边长。
改成 5656×5656（31,990,336 px）后通过，导出 5656×5656 原分辨率不 OOM。
真机（堆比模拟器紧）上的复跑仍欠。

### 8. §13 的「水印避让 0%」有一个口径例外（spec §15.8）

四角全被占时按 §9.3 的设计回退到右下并压在遮罩上，此时相交比例不是 0。
这不是缺陷也不是泄漏（水印画在所有遮罩**之后**，只会盖住黑块的一部分），
但指标的措辞需要带上「四角还有空位时」这个前提，已写进 spec §15.8 与
`docs/release-checklist.md`。

### 9. 本机做不了的：Play Console 真机购买（Task 45 Step 4）

没有可用的 Play Console 应用与测试账号。四条（建商品 / 测试账号购买 /
飞行模式复验 / 清数据后 `queryPurchasesAsync` 恢复）原样留在
`docs/release-checklist.md` 的「未达成 / 未验的项」里。
代码侧的规则由 `PurchaseResolverTest`、`PurchaseStoreTest`、
`EditorViewModelWatermarkTest` 守着。

### 10. 仍未达成的出口指标（都卡在素材，不是代码）

- §13 前三行（圈出率 / 默认打码召回率 / 精确率）：`androidTest/assets/samples/` 为空，
  表里的 1.00 全部来自合成集，**不构成验收**。
- 正脸召回 ≥ 0.98：`androidTest/assets/faces/` 为空，用例 SKIPPED（M3 遗留）。
- 全部实测在模拟器上，中端真机未上手。

因此 **M5 的「达到 §13 全部指标」这条出口没有完全达成**：可自动验证的部分全部达标，
依赖真实素材与 Play Console 的部分逐条记在 `docs/release-checklist.md`。
