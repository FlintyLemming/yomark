# DAMA 安卓版 · 计划 01 · M1 上半：工程骨架与核心数据模型

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建起一个零权限、可编译、CI 会守住权限承诺的 Android 工程，并把几何、语义、编辑三层数据模型和图像解码管线全部落地并测通。

**Architecture:** 单模块 `:app`，Kotlin + Compose。本计划只产出**纯数据与纯函数**加一层图像解码——没有 UI，没有识别。所有几何运算围绕 `Quad`（四点四边形，不退化成 `Rect`），所有编辑状态围绕不可变的 `MaskPlan` 快照。权限承诺不靠人工检查守，靠一个解析合并后 manifest 的 Gradle 任务守。

**Tech Stack:** Kotlin 2.x · AGP 8.x · Jetpack Compose · AndroidX ExifInterface · JUnit4 + Truth + Robolectric（`NATIVE` 图形模式）

**Spec:** `docs/superpowers/specs/2026-09-02-dama-android-design.md`

**索引与全局约束:** `docs/superpowers/plans/2026-09-02-dama-android-00-index.md` —— **先读那份的 Global Constraints，本计划每个任务都隐含包含它。**

---

## File Structure

| 文件 | 职责 |
|---|---|
| `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml` | 工程装配与版本锁定，唯一写版本号的地方 |
| `app/build.gradle.kts` | 模块配置 + 权限断言任务接线 |
| `buildSrc/` 或 `app/build.gradle.kts` 内的 `AssertPermissionsTask` | 解析合并后 manifest，除 BILLING 外出现任何 `uses-permission` 即失败 |
| `app/src/main/AndroidManifest.xml` | 零权限声明；单 Activity 与三个 intent-filter |
| `core/geometry/Quad.kt` | 四边形的全部几何运算（bounds / expand / iou / scaled / contains / 最小外接四边形） |
| `core/model/Sensitivity.kt` | `SensitiveKind`、`DetectorSource` 两个枚举 |
| `core/model/Recognition.kt` | `TextElement`、`TextLine`（含 `quadForRange`）、`Candidate`、`AnalysisResult` |
| `core/model/MaskPlan.kt` | `MaskState`、`MaskItem`、`MaskStyle`、`MaskOptions`、`MaskPlan`（含 `pendingCount`） |
| `ui/UndoStack.kt` | 快照式撤销/重做栈，上限 50 |
| `core/image/SourceImage.kt` | 解码结果的载体，携带 `scale` 与原图尺寸 |
| `core/image/ImageIntake.kt` | 把外来 URI 复制进应用私有目录 |
| `core/image/SourceImageLoader.kt` | EXIF 转正 + 降采样（分析用 2048 长边 / 导出用 32 MP 上限） |

---

## 前置：本机环境

执行 Task 1 之前先确认。本机已装 Android SDK（`~/Library/Android/sdk`，含 build-tools 36.0.0、platform-tools、emulator），但**没有可用的 JDK**（`/usr/libexec/java_home` 报错），也没有 `gradle`。Android Studio 自带的 JBR 是 JDK 25，AGP 8.x 不支持，不要用它。

---

### Task 1: 工程骨架与版本锁定

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/dama/app/ui/EditorActivity.kt`
- Create: `app/src/test/java/com/dama/app/SmokeTest.kt`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `com.dama.app` 命名空间；`libs.versions.toml` 里的全部依赖别名；`EditorActivity` 空壳

- [ ] **Step 1: 安装 JDK 21 与引导用 Gradle**

```bash
brew install --cask temurin@21
brew install gradle
/usr/libexec/java_home -v 21   # 应打印一个路径，记下它
```

- [ ] **Step 2: 让 Gradle 永远用 JDK 21（不污染仓库）**

写进用户级配置，不提交进仓库：

```bash
mkdir -p ~/.gradle
echo "org.gradle.java.home=$(/usr/libexec/java_home -v 21)" >> ~/.gradle/gradle.properties
```

- [ ] **Step 3: 生成 Gradle wrapper**

```bash
cd /Users/lobsterdoh/Projects/youma
gradle wrapper --gradle-version 8.14.3 --distribution-type bin
./gradlew --version   # 应显示 Gradle 8.14.3、JVM 21
```

- [ ] **Step 4: 写版本目录**

`gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.11.1"
kotlin = "2.2.0"
coreKtx = "1.16.0"
lifecycle = "2.9.1"
activityCompose = "1.10.1"
composeBom = "2025.06.01"
exifinterface = "1.4.1"
datastore = "1.1.7"
coroutines = "1.10.2"
mlkitText = "16.0.1"
mlkitFace = "16.1.7"
mlkitBarcode = "17.3.0"
libphonenumber = "8.13.52"
billing = "8.0.0"
junit = "4.13.2"
truth = "1.4.4"
robolectric = "4.14.1"
androidxTestJunit = "1.2.1"
androidxTestRunner = "1.6.2"
androidxTestCore = "1.6.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version.ref = "exifinterface" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
mlkit-text-recognition = { module = "com.google.mlkit:text-recognition", version.ref = "mlkitText" }
mlkit-face-detection = { module = "com.google.mlkit:face-detection", version.ref = "mlkitFace" }
mlkit-barcode-scanning = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkitBarcode" }
libphonenumber = { module = "com.googlecode.libphonenumber:libphonenumber", version.ref = "libphonenumber" }
billing-ktx = { module = "com.android.billingclient:billing-ktx", version.ref = "billing" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

> 若某个版本解析失败，取该 artifact 的最新稳定版写回这里。**不得**为绕过失败改用 ML Kit 的 unbundled 变体。

- [ ] **Step 5: 写工程与模块构建脚本**

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "DAMA"
include(":app")
```

根 `build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dama.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dama.app"
        minSdk = 29                 // Android 10：作用域存储，读写自己创建的媒体文件免权限
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // 按 ABI 分发，验收指标是 arm64-v8a split ≤ 25 MB
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
```

`app/proguard-rules.pro` 留空文件即可（后续任务按需补规则）。

- [ ] **Step 6: 写零权限 manifest**

`app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!--
      本文件不声明任何权限。
      构建产物中唯一的 uses-permission 是 com.android.vending.BILLING，
      由 Play Billing Library 的 manifest merger 并入，不可规避，且不授予任何数据访问能力。
      合并结果由 :app:assertNoRuntimePermissions 断言。
    -->
    <application
        android:name=".DamaApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:supportsRtl="true"
        android:theme="@style/Theme.Dama">

        <activity
            android:name=".ui.EditorActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:theme="@style/Theme.Dama">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: 写最小可运行代码与资源**

`app/src/main/java/com/dama/app/DamaApplication.kt`：

```kotlin
package com.dama.app

import android.app.Application

class DamaApplication : Application()
```

`app/src/main/java/com/dama/app/ui/EditorActivity.kt`：

```kotlin
package com.dama.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("DAMA") }
            }
        }
    }
}
```

`app/src/main/res/values/strings.xml`：

```xml
<resources>
    <string name="app_name">DAMA</string>
</resources>
```

`app/src/main/res/values/themes.xml`：

```xml
<resources>
    <style name="Theme.Dama" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

启动图标：先用 AGP 默认的自适应图标占位——创建 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/res/values/colors.xml`：

```xml
<resources>
    <color name="ic_launcher_background">#101014</color>
</resources>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M30,42h48v24h-48z" />
</vector>
```

- [ ] **Step 8: 写冒烟测试**

`app/src/test/java/com/dama/app/SmokeTest.kt`：

```kotlin
package com.dama.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmokeTest {
    @Test
    fun `test infrastructure runs`() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
```

- [ ] **Step 9: 运行构建与测试**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

预期：BUILD SUCCESSFUL。若报缺 `platforms;android-36`，AGP 会自动下载（`licenses` 目录已存在）；若自动下载被拒，用 Android Studio 的 SDK Manager 装上 Android 36 后重试。

- [ ] **Step 10: 提交**

```bash
cat > .gitignore <<'EOF'
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
local.properties
EOF
git add -A
git commit -m "chore: bootstrap zero-permission Android project skeleton"
```

---

### Task 2: 权限承诺的 CI 断言

**Files:**
- Create: `app/src/main/buildTasks/` —— 不需要；断言任务直接写在 `app/build.gradle.kts` 里
- Modify: `app/build.gradle.kts`（追加 `AssertPermissionsTask` 与接线）

**Interfaces:**
- Consumes: Task 1 的 `app/build.gradle.kts`、零权限 manifest
- Produces: Gradle 任务 `assertDebugNoRuntimePermissions` / `assertReleaseNoRuntimePermissions`，并挂到 `check` 上

这是设计文档 §13 里唯一一条明写「不能靠人工检查守」的指标。它必须在有任何依赖引入之前就位，这样后面每引入一个依赖都会被它检验一次。

- [ ] **Step 1: 写断言任务**

在 `app/build.gradle.kts` **顶部 `plugins {}` 之后**加入：

```kotlin
import com.android.build.api.artifact.SingleArtifact

abstract class AssertPermissionsTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        val found = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSortedSet()
        val offenders = found - ALLOWED
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "合并后 manifest 出现了不允许的权限：${offenders.joinToString()}\n" +
                    "本项目的核心承诺是运行期敏感权限为 0（见 spec §0 / §13）。" +
                    "唯一允许的是 ${ALLOWED.joinToString()}。"
            )
        }
        logger.lifecycle("权限断言通过：合并后 manifest 的 uses-permission = ${found.joinToString().ifEmpty { "（空）" }}")
    }

    companion object {
        val ALLOWED = setOf("com.android.vending.BILLING")
    }
}
```

在 `android { }` 块**之后**加入接线：

```kotlin
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<AssertPermissionsTask>("assert${name}NoRuntimePermissions") {
            group = "verification"
            description = "断言合并后 manifest 除 BILLING 外没有任何 uses-permission"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check").configure { dependsOn(task) }
    }
}
```

- [ ] **Step 2: 运行，确认当前通过**

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

预期：`权限断言通过：合并后 manifest 的 uses-permission = （空）`

- [ ] **Step 3: 反向验证——故意加一条权限，确认构建失败**

临时在 `app/src/main/AndroidManifest.xml` 的 `<manifest>` 下加一行：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

运行：

```bash
./gradlew :app:assertDebugNoRuntimePermissions
```

预期：FAILED，报 `合并后 manifest 出现了不允许的权限：android.permission.INTERNET`。

**看到失败后立刻把这一行删掉**，重新运行确认恢复通过。这一步不能跳——一个从未见过自己失败的断言等于没有断言。

- [ ] **Step 4: 确认 check 会带上它**

```bash
./gradlew :app:check
```

预期：任务列表里出现 `assertDebugNoRuntimePermissions` 与 `assertReleaseNoRuntimePermissions`。

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts
git commit -m "feat: fail the build if any uses-permission other than BILLING is merged in"
```

---

### Task 3: Quad 几何

**Files:**
- Create: `app/src/main/java/com/dama/app/core/geometry/Quad.kt`
- Test: `app/src/test/java/com/dama/app/core/geometry/QuadTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class Quad(p0: PointF, p1: PointF, p2: PointF, p3: PointF)`
  - `Quad.bounds(): RectF`、`expand(px: Float): Quad`、`iou(other: Quad): Float`、`scaled(factor: Float): Quad`、`contains(p: PointF): Boolean`、`toPath(): Path`、`points(): List<PointF>`、`shortEdge(): Float`
  - `Quad.Companion.fromRect(r: RectF): Quad`
  - `Quad.Companion.boundingQuad(quads: List<Quad>): Quad` —— 最小面积**旋转**矩形（§5.3 要求「最小外接四边形」，不是轴对齐外接矩形）

OCR 的文本框会是倾斜的，全程用四点表示。`iou` 用于候选去重（§5.4），`expand` 用于抹除时采样周边像素（§8），`scaled` 用于降采样坐标与原图坐标的互转（§3）。

`boundingQuad` 是全项目最容易写错的一处：倾斜文字的**轴对齐**外接矩形会盖住相邻内容，所以必须求最小面积旋转矩形——凸包（Andrew monotone chain）+ 旋转卡壳。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/core/geometry/QuadTest.kt`：

```kotlin
package com.dama.app.core.geometry

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class QuadTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    @Test
    fun `bounds of an axis-aligned quad is the same rect`() {
        assertThat(rect(10f, 20f, 30f, 40f).bounds()).isEqualTo(RectF(10f, 20f, 30f, 40f))
    }

    @Test
    fun `bounds of a rotated quad covers all four points`() {
        val q = Quad(PointF(10f, 0f), PointF(20f, 10f), PointF(10f, 20f), PointF(0f, 10f))
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 20f, 20f))
    }

    @Test
    fun `expand pushes every edge outward by the given px`() {
        val e = rect(10f, 10f, 20f, 20f).expand(5f)
        assertThat(e.bounds()).isEqualTo(RectF(5f, 5f, 25f, 25f))
    }

    @Test
    fun `scaled multiplies every coordinate`() {
        val s = rect(10f, 20f, 30f, 40f).scaled(2f)
        assertThat(s.bounds()).isEqualTo(RectF(20f, 40f, 60f, 80f))
    }

    @Test
    fun `iou of identical quads is one`() {
        assertThat(rect(0f, 0f, 10f, 10f).iou(rect(0f, 0f, 10f, 10f))).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `iou of disjoint quads is zero`() {
        assertThat(rect(0f, 0f, 10f, 10f).iou(rect(20f, 20f, 30f, 30f))).isEqualTo(0f)
    }

    @Test
    fun `iou of half-overlapping quads is one third`() {
        // 两个 10x10，重叠 5x10=50，并集 150 → 1/3
        val v = rect(0f, 0f, 10f, 10f).iou(rect(5f, 0f, 15f, 10f))
        assertThat(v).isWithin(1e-3f).of(1f / 3f)
    }

    @Test
    fun `contains hits inside and misses outside for a rotated quad`() {
        val diamond = Quad(PointF(10f, 0f), PointF(20f, 10f), PointF(10f, 20f), PointF(0f, 10f))
        assertThat(diamond.contains(PointF(10f, 10f))).isTrue()
        assertThat(diamond.contains(PointF(1f, 1f))).isFalse()   // 在外接框内但在菱形外
    }

    @Test
    fun `shortEdge returns the shorter side length`() {
        assertThat(rect(0f, 0f, 30f, 10f).shortEdge()).isWithin(1e-3f).of(10f)
    }

    @Test
    fun `boundingQuad of a single quad returns an equivalent quad`() {
        val q = rect(5f, 5f, 15f, 25f)
        val b = Quad.boundingQuad(listOf(q))
        assertThat(b.bounds()).isEqualTo(q.bounds())
    }

    @Test
    fun `boundingQuad of two adjacent quads covers both`() {
        val b = Quad.boundingQuad(listOf(rect(0f, 0f, 10f, 10f), rect(20f, 0f, 30f, 10f)))
        assertThat(b.bounds()).isEqualTo(RectF(0f, 0f, 30f, 10f))
    }

    @Test
    fun `boundingQuad of tilted quads is tighter than the axis-aligned bounds`() {
        // 沿 45 度排列的两个小方块：轴对齐外接矩形面积远大于最小旋转矩形
        val a = Quad(PointF(0f, 0f), PointF(10f, 10f), PointF(8f, 12f), PointF(-2f, 2f))
        val b = Quad(PointF(20f, 20f), PointF(30f, 30f), PointF(28f, 32f), PointF(18f, 22f))
        val hull = Quad.boundingQuad(listOf(a, b))
        val axisArea = hull.bounds().width() * hull.bounds().height()
        assertThat(hull.area()).isLessThan(axisArea * 0.6f)
        // 且必须真的包住所有输入点
        (a.points() + b.points()).forEach { p ->
            assertThat(hull.contains(p) || hull.distanceTo(p) < 0.01f).isTrue()
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*QuadTest*'
```

预期：编译失败，`Unresolved reference: Quad`

- [ ] **Step 3: 实现 Quad**

`app/src/main/java/com/dama/app/core/geometry/Quad.kt`：

```kotlin
package com.dama.app.core.geometry

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 图像上的任意四边形。OCR 的文本框会是倾斜的，一律用四点表示，不退化成 Rect。
 * 点按顺时针或逆时针给出（不要求特定绕向，但必须是一个简单多边形）。
 */
data class Quad(val p0: PointF, val p1: PointF, val p2: PointF, val p3: PointF) {

    fun points(): List<PointF> = listOf(p0, p1, p2, p3)

    fun bounds(): RectF {
        val xs = points().map { it.x }
        val ys = points().map { it.y }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** 面积，鞋带公式。绕向不影响结果（取绝对值）。 */
    fun area(): Float {
        val p = points()
        var s = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            s += p[i].x * q.y - q.x * p[i].y
        }
        return abs(s) / 2f
    }

    /** 四条边中最短的一条的长度。像素化块尺寸下限、丢弃小框都用它。 */
    fun shortEdge(): Float {
        val p = points()
        return (0 until 4).minOf { i ->
            val a = p[i]; val b = p[(i + 1) % 4]
            hypot(b.x - a.x, b.y - a.y)
        }
    }

    /** 沿每个顶点背离质心的方向外扩 px。用于抹除时采样周边像素（spec §8）。 */
    fun expand(px: Float): Quad {
        val cx = points().sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = points().sumOf { it.y.toDouble() }.toFloat() / 4f
        fun push(p: PointF): PointF {
            val dx = p.x - cx
            val dy = p.y - cy
            val len = hypot(dx, dy)
            if (len < 1e-4f) return PointF(p.x, p.y)
            return PointF(p.x + dx / len * px, p.y + dy / len * px)
        }
        return Quad(push(p0), push(p1), push(p2), push(p3))
    }

    /** 降采样坐标 ↔ 原图坐标。导出时用 1/scale 反算。 */
    fun scaled(factor: Float): Quad = Quad(
        PointF(p0.x * factor, p0.y * factor),
        PointF(p1.x * factor, p1.y * factor),
        PointF(p2.x * factor, p2.y * factor),
        PointF(p3.x * factor, p3.y * factor),
    )

    /** 射线法命中测试。边界上视为命中。 */
    fun contains(p: PointF): Boolean {
        val v = points()
        var inside = false
        var j = v.size - 1
        for (i in v.indices) {
            val a = v[i]; val b = v[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
            j = i
        }
        return inside || distanceTo(p) < 1e-3f
    }

    /** 点到四边形边界的最短距离。0 表示在边上。 */
    fun distanceTo(p: PointF): Float {
        val v = points()
        return (0 until 4).minOf { i ->
            distanceToSegment(p, v[i], v[(i + 1) % 4])
        }
    }

    fun toPath(): Path = Path().apply {
        moveTo(p0.x, p0.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        close()
    }

    /**
     * 交并比。用凸多边形裁剪（Sutherland–Hodgman）求交集面积——
     * 用外接矩形近似会让倾斜文本行的去重完全失准。
     */
    fun iou(other: Quad): Float {
        val inter = intersectionArea(this, other)
        if (inter <= 0f) return 0f
        val union = area() + other.area() - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        fun fromRect(r: RectF): Quad = Quad(
            PointF(r.left, r.top), PointF(r.right, r.top),
            PointF(r.right, r.bottom), PointF(r.left, r.bottom),
        )

        /**
         * 一组四边形的最小面积**旋转**矩形（spec §5.3 的「最小外接四边形」）。
         * 不用轴对齐外接矩形：倾斜文字的轴对齐框会盖住相邻内容。
         * 做法：所有点求凸包，再沿凸包每条边做一次旋转卡壳，取面积最小的那个方向。
         */
        fun boundingQuad(quads: List<Quad>): Quad {
            require(quads.isNotEmpty()) { "boundingQuad 需要至少一个四边形" }
            if (quads.size == 1) return quads.first()
            val hull = convexHull(quads.flatMap { it.points() })
            if (hull.size < 3) return fromRect(quads.map { it.bounds() }.reduce { a, b -> RectF(a).apply { union(b) } })

            var best: Quad? = null
            var bestArea = Float.MAX_VALUE
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val ang = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()).toFloat()
                val c = cos(-ang); val s = sin(-ang)
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (p in hull) {
                    val x = p.x * c - p.y * s
                    val y = p.x * s + p.y * c
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
                val area = (maxX - minX) * (maxY - minY)
                if (area < bestArea) {
                    bestArea = area
                    val ic = cos(ang); val isn = sin(ang)
                    fun back(x: Float, y: Float) = PointF(x * ic - y * isn, x * isn + y * ic)
                    best = Quad(back(minX, minY), back(maxX, minY), back(maxX, maxY), back(minX, maxY))
                }
            }
            return best!!
        }

        /** Andrew monotone chain，逆时针输出，不含共线冗余点。 */
        private fun convexHull(pts: List<PointF>): List<PointF> {
            val p = pts.distinctBy { it.x to it.y }.sortedWith(compareBy({ it.x }, { it.y }))
            if (p.size < 3) return p
            fun cross(o: PointF, a: PointF, b: PointF) =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = ArrayList<PointF>()
            for (q in p) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1)
                lower.add(q)
            }
            val upper = ArrayList<PointF>()
            for (q in p.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1)
                upper.add(q)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        private fun intersectionArea(a: Quad, b: Quad): Float {
            var poly = a.points()
            val clip = b.points()
            for (i in clip.indices) {
                val c1 = clip[i]
                val c2 = clip[(i + 1) % clip.size]
                poly = clipEdge(poly, c1, c2)
                if (poly.isEmpty()) return 0f
            }
            var s = 0f
            for (i in poly.indices) {
                val q = poly[(i + 1) % poly.size]
                s += poly[i].x * q.y - q.x * poly[i].y
            }
            return abs(s) / 2f
        }

        /** Sutherland–Hodgman 的单边裁剪。inside 判定对两种绕向都成立，取绝对面积。 */
        private fun clipEdge(poly: List<PointF>, c1: PointF, c2: PointF): List<PointF> {
            if (poly.isEmpty()) return poly
            fun side(p: PointF) = (c2.x - c1.x) * (p.y - c1.y) - (c2.y - c1.y) * (p.x - c1.x)
            val ref = poly.map { side(it) }
            // 用裁剪多边形自身的绕向决定 inside 的符号
            val sign = if (ref.sum() >= 0) 1f else -1f
            fun inside(p: PointF) = side(p) * sign >= -1e-5f
            fun intersect(p: PointF, q: PointF): PointF {
                val d1 = side(p); val d2 = side(q)
                val t = d1 / (d1 - d2)
                return PointF(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
            }
            val out = ArrayList<PointF>()
            for (i in poly.indices) {
                val cur = poly[i]
                val prev = poly[(i + poly.size - 1) % poly.size]
                if (inside(cur)) {
                    if (!inside(prev)) out.add(intersect(prev, cur))
                    out.add(cur)
                } else if (inside(prev)) {
                    out.add(intersect(prev, cur))
                }
            }
            return out
        }

        private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
            var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
            t = t.coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
    }
}
```

**`clipEdge` 的绕向问题**：Sutherland–Hodgman 要求裁剪多边形绕向已知。上面用 `ref.sum()` 的符号推断绕向，对凸四边形成立。若某个测试出现交集面积为 0 但目视应有交集，先检查这里。

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*QuadTest*'
```

预期：13 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/dama/app/core/geometry app/src/test/java/com/dama/app/core/geometry
git commit -m "feat: add Quad geometry with rotated minimum bounding quad and polygon IoU"
```

---

### Task 4: 语义与识别数据模型

**Files:**
- Create: `app/src/main/java/com/dama/app/core/model/Sensitivity.kt`
- Create: `app/src/main/java/com/dama/app/core/model/Recognition.kt`
- Test: `app/src/test/java/com/dama/app/core/model/TextLineTest.kt`

**Interfaces:**
- Consumes: `Quad`（Task 3），特别是 `Quad.boundingQuad`
- Produces:
  - `enum class SensitiveKind { PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT, TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY, POSTAL_ADDRESS, PERSON_NAME, ORG_NAME, FACE, BARCODE, MANUAL }`
  - `enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }`
  - `data class TextElement(quad: Quad, text: String, range: IntRange)`
  - `data class TextLine(quad: Quad, text: String, confidence: Float, elements: List<TextElement>)` + `fun quadForRange(range: IntRange): Quad`
  - `data class Candidate(id: String, quad: Quad, kind: SensitiveKind, source: DetectorSource, confidence: Float, enabledByDefault: Boolean = true)`
  - `data class AnalysisResult(lines: List<TextLine>, candidates: List<Candidate>)`

`quadForRange` 就是 §5.3 的落地：规则跑在字符串上得到字符区间，但坐标最细只到 element（词）级别，所以取所有**区间相交**的 element，对它们求最小外接四边形。结果是词粒度——命中卡号时如果 element 是 `Card: 4111...`，会连 `Card:` 一起遮掉。这符合「宁可多遮」，**不做优化**。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/core/model/TextLineTest.kt`：

```kotlin
package com.dama.app.core.model

import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextLineTest {

    /** "Card: 4111 2222" —— 三个 element，字符区间按空格拼接。 */
    private fun sampleLine(): TextLine {
        val e0 = TextElement(Quad.fromRect(RectF(0f, 0f, 40f, 10f)), "Card:", 0..4)
        val e1 = TextElement(Quad.fromRect(RectF(45f, 0f, 80f, 10f)), "4111", 6..9)
        val e2 = TextElement(Quad.fromRect(RectF(85f, 0f, 120f, 10f)), "2222", 11..14)
        return TextLine(
            quad = Quad.fromRect(RectF(0f, 0f, 120f, 10f)),
            text = "Card: 4111 2222",
            confidence = 0.9f,
            elements = listOf(e0, e1, e2),
        )
    }

    @Test
    fun `range inside one element returns that element's quad`() {
        val q = sampleLine().quadForRange(6..9)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 80f, 10f))
    }

    @Test
    fun `range spanning two elements covers both`() {
        val q = sampleLine().quadForRange(6..14)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 120f, 10f))
    }

    @Test
    fun `partial overlap of an element still takes the whole element`() {
        // 只命中 "4111" 中的 "11"，仍然整词覆盖 —— 词粒度是已知取舍
        val q = sampleLine().quadForRange(7..8)
        assertThat(q.bounds()).isEqualTo(RectF(45f, 0f, 80f, 10f))
    }

    @Test
    fun `range hitting the label element drags the label in too`() {
        // 命中 "Card: 4111" 会连标签一起遮掉，这是 spec 5-3 明确接受的行为
        val q = sampleLine().quadForRange(0..9)
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 80f, 10f))
    }

    @Test
    fun `range matching no element falls back to the line quad`() {
        val q = sampleLine().quadForRange(100..110)
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 120f, 10f))
    }

    @Test
    fun `candidate defaults to enabled`() {
        val c = Candidate("a", Quad.fromRect(RectF(0f, 0f, 1f, 1f)), SensitiveKind.EMAIL, DetectorSource.RULE, 0.9f)
        assertThat(c.enabledByDefault).isTrue()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*TextLineTest*'
```

预期：编译失败，`Unresolved reference: TextLine`

- [ ] **Step 3: 实现数据模型**

`app/src/main/java/com/dama/app/core/model/Sensitivity.kt`：

```kotlin
package com.dama.app.core.model

enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,

    // v2：需要 NER，首版不产出。枚举位先占住，接口按 spec §14 已留。
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,

    FACE, BARCODE, MANUAL,
}

/** 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }
```

`app/src/main/java/com/dama/app/core/model/Recognition.kt`：

```kotlin
package com.dama.app.core.model

import com.dama.app.core.geometry.Quad

data class TextElement(val quad: Quad, val text: String, val range: IntRange)

data class TextLine(
    val quad: Quad,
    val text: String,                 // elements 按阅读顺序拼接，见 MlKitTextRecognizer
    val confidence: Float,
    val elements: List<TextElement>,  // range 是该词在 text 中的字符区间
) {
    /**
     * 把 text 上的匹配区间映射回像素四边形（spec §5.3）。
     * 只能到词粒度：取所有与 range 相交的 element，求它们的最小外接四边形。
     * 匹配不到任何 element 时退回整行的 quad——宁可多遮。
     */
    fun quadForRange(range: IntRange): Quad {
        val hit = elements.filter { it.range.first <= range.last && range.first <= it.range.last }
        if (hit.isEmpty()) return quad
        return Quad.boundingQuad(hit.map { it.quad })
    }
}

data class Candidate(
    val id: String,
    val quad: Quad,
    val kind: SensitiveKind,
    val source: DetectorSource,
    val confidence: Float,
    /**
     * 进编辑器时的初始状态。true → MASKED，false → OUTLINED。
     * 由规则表逐条指定（spec §6），不是由 confidence 阈值算出来的。
     */
    val enabledByDefault: Boolean = true,
)

data class AnalysisResult(val lines: List<TextLine>, val candidates: List<Candidate>)
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*TextLineTest*'
```

预期：6 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/dama/app/core/model app/src/test/java/com/dama/app/core/model
git commit -m "feat: add recognition data model with word-granularity range-to-quad mapping"
```

---

### Task 5: 编辑状态模型与撤销栈

**Files:**
- Create: `app/src/main/java/com/dama/app/core/model/MaskPlan.kt`
- Create: `app/src/main/java/com/dama/app/ui/UndoStack.kt`
- Test: `app/src/test/java/com/dama/app/core/model/MaskPlanTest.kt`
- Test: `app/src/test/java/com/dama/app/ui/UndoStackTest.kt`

**Interfaces:**
- Consumes: `Quad`（Task 3）、`SensitiveKind` / `DetectorSource`（Task 4）
- Produces:
  - `enum class MaskState { MASKED, OUTLINED }`
  - `enum class MaskStyle { SOLID, PIXELATE, BLUR, MARKER, EMOJI, ERASE }`
  - `data class MaskOptions(solidColor: Int, pixelBlockDivisor: Int, blurRadiusRatio: Float, markerColor: Int, emoji: String)`
  - `data class MaskItem(candidateId: String, quad: Quad, kind: SensitiveKind, source: DetectorSource, state: MaskState)`
  - `data class MaskPlan(items: List<MaskItem>, style: MaskStyle, options: MaskOptions)` + `val pendingCount: Int`、`fun toggle(id: String): MaskPlan`、`fun remove(id: String): MaskPlan`、`fun add(item: MaskItem): MaskPlan`、`fun maskAll(): MaskPlan`、`fun pendingByKind(): Map<SensitiveKind, Int>`
  - `class UndoStack(limit: Int = 50)` + `push` / `undo` / `redo` / `canUndo` / `canRedo` / `clear`

**两条硬规则在这里落地**（spec §4.2）：
1. **不对称删除**：`remove` 只对 `source == DetectorSource.MANUAL` 生效，对规则命中的候选是 no-op。理由是候选一旦消失用户再也找不回来，而漏检是事故。
2. **快照撤销**：整个 `MaskPlan` 存进 `ArrayDeque`，上限 50。这是全方案唯一一处主动选择「笨但正确」的实现——比命令模式少一半代码，且不可能出现命令不对称的 bug。

- [ ] **Step 1: 写 MaskPlan 的失败测试**

`app/src/test/java/com/dama/app/core/model/MaskPlanTest.kt`：

```kotlin
package com.dama.app.core.model

import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPlanTest {

    private fun item(
        id: String,
        state: MaskState,
        source: DetectorSource = DetectorSource.RULE,
        kind: SensitiveKind = SensitiveKind.URL,
    ) = MaskItem(id, Quad.fromRect(RectF(0f, 0f, 10f, 10f)), kind, source, state)

    private fun plan(vararg items: MaskItem) =
        MaskPlan(items.toList(), MaskStyle.SOLID, MaskOptions())

    @Test
    fun `pendingCount counts only outlined items`() {
        val p = plan(
            item("a", MaskState.MASKED),
            item("b", MaskState.OUTLINED),
            item("c", MaskState.OUTLINED),
        )
        assertThat(p.pendingCount).isEqualTo(2)
    }

    @Test
    fun `toggle flips masked to outlined and back`() {
        val p = plan(item("a", MaskState.MASKED))
        val once = p.toggle("a")
        assertThat(once.items.single().state).isEqualTo(MaskState.OUTLINED)
        assertThat(once.toggle("a").items.single().state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `toggle of an unknown id changes nothing`() {
        val p = plan(item("a", MaskState.MASKED))
        assertThat(p.toggle("zzz")).isEqualTo(p)
    }

    @Test
    fun `remove deletes a manual item`() {
        val p = plan(item("m", MaskState.MASKED, DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(p.remove("m").items).isEmpty()
    }

    @Test
    fun `remove refuses to delete a rule-detected candidate`() {
        // spec 4.2 的不对称规则：规则命中的候选永不从画面消失
        val p = plan(item("r", MaskState.OUTLINED, DetectorSource.RULE))
        assertThat(p.remove("r").items).hasSize(1)
    }

    @Test
    fun `remove refuses to delete face and barcode candidates`() {
        val p = plan(
            item("f", MaskState.MASKED, DetectorSource.FACE, SensitiveKind.FACE),
            item("b", MaskState.MASKED, DetectorSource.BARCODE, SensitiveKind.BARCODE),
        )
        assertThat(p.remove("f").items).hasSize(2)
        assertThat(p.remove("b").items).hasSize(2)
    }

    @Test
    fun `maskAll turns every outlined item masked`() {
        val p = plan(item("a", MaskState.OUTLINED), item("b", MaskState.MASKED))
        val all = p.maskAll()
        assertThat(all.pendingCount).isEqualTo(0)
        assertThat(all.items).hasSize(2)
    }

    @Test
    fun `pendingByKind groups outlined items for the interception dialog`() {
        val p = plan(
            item("a", MaskState.OUTLINED, kind = SensitiveKind.URL),
            item("b", MaskState.OUTLINED, kind = SensitiveKind.URL),
            item("c", MaskState.OUTLINED, kind = SensitiveKind.IP_ADDR),
            item("d", MaskState.MASKED, kind = SensitiveKind.EMAIL),
        )
        assertThat(p.pendingByKind()).containsExactly(
            SensitiveKind.URL, 2,
            SensitiveKind.IP_ADDR, 1,
        )
    }

    @Test
    fun `add appends an item without touching the others`() {
        val p = plan(item("a", MaskState.MASKED))
        val added = p.add(item("b", MaskState.MASKED, DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(added.items.map { it.candidateId }).containsExactly("a", "b").inOrder()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*MaskPlanTest*'
```

预期：编译失败，`Unresolved reference: MaskState`

- [ ] **Step 3: 实现 MaskPlan**

`app/src/main/java/com/dama/app/core/model/MaskPlan.kt`：

```kotlin
package com.dama.app.core.model

import android.graphics.Color
import com.dama.app.core.geometry.Quad

/** 未圈出的区域根本不在 plan 里，因此只有两个枚举值。 */
enum class MaskState { MASKED, OUTLINED }

enum class MaskStyle { SOLID, PIXELATE, BLUR, MARKER, EMOJI, ERASE }

data class MaskOptions(
    val solidColor: Int = Color.BLACK,
    /** 像素块边长 = 区域短边 / divisor，再受 12px 下限约束（spec §8）。 */
    val pixelBlockDivisor: Int = 8,
    val blurRadiusRatio: Float = 0.08f,
    val markerColor: Int = 0x99FFEB3B.toInt(),
    val emoji: String = "🙂",
)

data class MaskItem(
    val candidateId: String,
    val quad: Quad,                   // 降采样坐标系；导出时按 1/scale 反算
    val kind: SensitiveKind,
    val source: DetectorSource,
    val state: MaskState,
)

data class MaskPlan(
    val items: List<MaskItem>,
    val style: MaskStyle,             // 全局，非逐项（spec §7.5）
    val options: MaskOptions,
) {
    /** 已识别但尚未打码的数量。导出拦截（spec §7.4）的触发条件。 */
    val pendingCount: Int get() = items.count { it.state == MaskState.OUTLINED }

    fun toggle(id: String): MaskPlan = copy(
        items = items.map {
            if (it.candidateId != id) it
            else it.copy(state = if (it.state == MaskState.MASKED) MaskState.OUTLINED else MaskState.MASKED)
        }
    )

    /**
     * 只有手动框可以被彻底删除（spec §4.2 的不对称规则）。
     * 规则/人脸/条码命中的候选永不从画面消失——候选一旦消失用户就再也找不回来。
     */
    fun remove(id: String): MaskPlan {
        val target = items.firstOrNull { it.candidateId == id } ?: return this
        if (target.source != DetectorSource.MANUAL) return this
        return copy(items = items.filterNot { it.candidateId == id })
    }

    fun add(item: MaskItem): MaskPlan = copy(items = items + item)

    fun replace(item: MaskItem): MaskPlan = copy(
        items = items.map { if (it.candidateId == item.candidateId) item else it }
    )

    fun maskAll(): MaskPlan = copy(items = items.map { it.copy(state = MaskState.MASKED) })

    /** 拦截对话框的「2 个网址、1 个 IP 地址」就是这个 map 渲染出来的。 */
    fun pendingByKind(): Map<SensitiveKind, Int> =
        items.filter { it.state == MaskState.OUTLINED }
            .groupingBy { it.kind }
            .eachCount()

    fun find(id: String): MaskItem? = items.firstOrNull { it.candidateId == id }

    companion object {
        fun empty(style: MaskStyle = MaskStyle.SOLID) = MaskPlan(emptyList(), style, MaskOptions())
    }
}
```

- [ ] **Step 4: 运行确认 MaskPlan 测试通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*MaskPlanTest*'
```

预期：9 个测试全部 PASS。

- [ ] **Step 5: 写 UndoStack 的失败测试**

`app/src/test/java/com/dama/app/ui/UndoStackTest.kt`：

```kotlin
package com.dama.app.ui

import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UndoStackTest {

    private fun planOf(style: MaskStyle) = MaskPlan(emptyList(), style, MaskOptions())

    @Test
    fun `fresh stack can neither undo nor redo`() {
        val s = UndoStack()
        assertThat(s.canUndo).isFalse()
        assertThat(s.canRedo).isFalse()
    }

    @Test
    fun `undo returns the pushed snapshot`() {
        val s = UndoStack()
        val a = planOf(MaskStyle.SOLID)
        val b = planOf(MaskStyle.BLUR)
        s.push(a)
        assertThat(s.undo(b)).isEqualTo(a)
    }

    @Test
    fun `redo returns the state that was undone`() {
        val s = UndoStack()
        val a = planOf(MaskStyle.SOLID)
        val b = planOf(MaskStyle.BLUR)
        s.push(a)
        s.undo(b)
        assertThat(s.redo(a)).isEqualTo(b)
    }

    @Test
    fun `a new push clears the redo stack`() {
        val s = UndoStack()
        s.push(planOf(MaskStyle.SOLID))
        s.undo(planOf(MaskStyle.BLUR))
        assertThat(s.canRedo).isTrue()
        s.push(planOf(MaskStyle.MARKER))
        assertThat(s.canRedo).isFalse()
    }

    @Test
    fun `undo on an empty stack returns null`() {
        assertThat(UndoStack().undo(planOf(MaskStyle.SOLID))).isNull()
    }

    @Test
    fun `stack drops the oldest snapshot beyond the limit`() {
        val s = UndoStack(limit = 3)
        val plans = List(5) { planOf(MaskStyle.entries[it % MaskStyle.entries.size]) }
        plans.forEach { s.push(it) }
        // 只保留最近 3 个，连续 undo 3 次后就没得撤了
        var cur = planOf(MaskStyle.SOLID)
        repeat(3) { cur = s.undo(cur)!! }
        assertThat(s.canUndo).isFalse()
    }

    @Test
    fun `clear empties both stacks`() {
        val s = UndoStack()
        s.push(planOf(MaskStyle.SOLID))
        s.undo(planOf(MaskStyle.BLUR))
        s.clear()
        assertThat(s.canUndo).isFalse()
        assertThat(s.canRedo).isFalse()
    }
}
```

- [ ] **Step 6: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*UndoStackTest*'
```

预期：编译失败，`Unresolved reference: UndoStack`

- [ ] **Step 7: 实现 UndoStack**

`app/src/main/java/com/dama/app/ui/UndoStack.kt`：

```kotlin
package com.dama.app.ui

import com.dama.app.core.model.MaskPlan

/**
 * 快照式撤销/重做（spec §4.2）。
 * MaskPlan 撑死几十个 item，整个存下来也就几 KB，比命令模式省一半代码，
 * 且不可能出现命令不对称的 bug。换图时调用 clear()。
 */
class UndoStack(private val limit: Int = LIMIT) {

    private val undoStack = ArrayDeque<MaskPlan>()
    private val redoStack = ArrayDeque<MaskPlan>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 在**修改之前**把当前 plan 压栈。 */
    fun push(snapshot: MaskPlan) {
        undoStack.addLast(snapshot)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 传入当前 plan，返回上一个快照；已无历史时返回 null。 */
    fun undo(current: MaskPlan): MaskPlan? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    fun redo(current: MaskPlan): MaskPlan? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object { const val LIMIT = 50 }
}
```

- [ ] **Step 8: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*UndoStackTest*' --tests '*MaskPlanTest*'
```

预期：16 个测试全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/dama/app/core/model/MaskPlan.kt app/src/main/java/com/dama/app/ui/UndoStack.kt app/src/test/java/com/dama/app/core/model/MaskPlanTest.kt app/src/test/java/com/dama/app/ui/UndoStackTest.kt
git commit -m "feat: add tri-state MaskPlan with asymmetric deletion and snapshot undo stack"
```

---

### Task 6: 图片接入与私有副本

**Files:**
- Create: `app/src/main/java/com/dama/app/core/image/ImageIntake.kt`
- Test: `app/src/androidTest/java/com/dama/app/core/image/ImageIntakeTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `class ImageIntake(context: Context)`
  - `suspend fun copyToPrivate(uri: Uri): IntakeResult`
  - `data class IntakeResult(val file: File, val mimeType: String)`
  - `fun clear()` —— 删除私有目录下所有副本

`ACTION_SEND` 携带的 `content://` URI 权限在 Activity 重建后可能失效（spec §7.1）。入口处**立即**把原图复制到 `context.cacheDir/intake/`，此后全流程只读私有副本。这同时解决了「用户在后台删掉了原图」。副本在导出成功或 Activity 销毁时删除。

这个任务用 instrumented test，因为它依赖真实的 `ContentResolver`。

- [ ] **Step 1: 写失败的 instrumented 测试**

`app/src/androidTest/java/com/dama/app/core/image/ImageIntakeTest.kt`：

```kotlin
package com.dama.app.core.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageIntakeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeTempPng(name: String): Uri {
        val f = File(context.cacheDir, name)
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).use { bmp ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return f.toUri()
    }

    private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R = try { block(this) } finally { recycle() }

    @Test
    fun copies_source_into_app_private_cache() = runTest {
        val intake = ImageIntake(context)
        val result = intake.copyToPrivate(writeTempPng("src.png"))

        assertThat(result.file.exists()).isTrue()
        assertThat(result.file.length()).isGreaterThan(0L)
        // 必须落在应用私有目录里
        assertThat(result.file.canonicalPath).startsWith(context.cacheDir.canonicalPath)
        assertThat(result.file.canonicalPath).contains("intake")
    }

    @Test
    fun copy_survives_deletion_of_the_original() = runTest {
        val uri = writeTempPng("gone.png")
        val intake = ImageIntake(context)
        val result = intake.copyToPrivate(uri)

        File(uri.path!!).delete()
        assertThat(result.file.exists()).isTrue()
        assertThat(result.file.length()).isGreaterThan(0L)
    }

    @Test
    fun two_intakes_do_not_collide() = runTest {
        val intake = ImageIntake(context)
        val a = intake.copyToPrivate(writeTempPng("a.png"))
        val b = intake.copyToPrivate(writeTempPng("b.png"))
        assertThat(a.file.canonicalPath).isNotEqualTo(b.file.canonicalPath)
    }

    @Test
    fun clear_removes_every_copy() = runTest {
        val intake = ImageIntake(context)
        val a = intake.copyToPrivate(writeTempPng("c.png"))
        intake.clear()
        assertThat(a.file.exists()).isFalse()
    }

    @Test
    fun unreadable_uri_throws_rather_than_returning_an_empty_file() = runTest {
        val intake = ImageIntake(context)
        try {
            intake.copyToPrivate(Uri.parse("content://com.dama.nonexistent/1"))
            throw AssertionError("应该抛异常")
        } catch (e: Exception) {
            assertThat(e).isNotInstanceOf(AssertionError::class.java)
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

先启动一台模拟器（API 29 以上），然后：

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*ImageIntakeTest*'
```

预期：编译失败，`Unresolved reference: ImageIntake`

- [ ] **Step 3: 实现 ImageIntake**

`app/src/main/java/com/dama/app/core/image/ImageIntake.kt`：

```kotlin
package com.dama.app.core.image

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class IntakeResult(val file: File, val mimeType: String)

/**
 * 把外来 URI 立即复制进应用私有目录（spec §7.1）。
 *
 * 两个理由：
 * 1. ACTION_SEND 给的 content:// 授权在 Activity 重建后可能失效；
 * 2. 用户可能在编辑期间把原图删了。
 *
 * 此后全流程只读私有副本。副本在导出成功或 Activity 销毁时由调用方 clear()。
 */
class ImageIntake(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    suspend fun copyToPrivate(uri: Uri): IntakeResult = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: DEFAULT_MIME
        val ext = if (mime.equals("image/png", ignoreCase = true)) "png" else "jpg"
        val target = File(dir, "src-${System.currentTimeMillis()}-${uri.hashCode()}.$ext")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取来源 URI：$uri")

        if (target.length() == 0L) {
            target.delete()
            throw IOException("来源 URI 读到 0 字节：$uri")
        }
        IntakeResult(target, mime)
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIR = "intake"
        const val DEFAULT_MIME = "image/jpeg"
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*ImageIntakeTest*'
```

预期：5 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/dama/app/core/image app/src/androidTest/java/com/dama/app/core/image
git commit -m "feat: copy incoming image URIs into app-private cache on intake"
```

---

### Task 7: 解码、EXIF 转正与降采样

**Files:**
- Create: `app/src/main/java/com/dama/app/core/image/SourceImage.kt`
- Create: `app/src/main/java/com/dama/app/core/image/SourceImageLoader.kt`
- Test: `app/src/test/java/com/dama/app/core/image/SourceImageLoaderTest.kt`

**Interfaces:**
- Consumes: `IntakeResult`（Task 6）
- Produces:
  - `data class SourceImage(bitmap: Bitmap, scale: Float, originalWidth: Int, originalHeight: Int, mimeType: String)` + `fun toOriginal(quad: Quad): Quad`
  - `object SourceImageLoader`：
    - `const val ANALYSIS_MAX_EDGE = 2048`
    - `const val EXPORT_MAX_PIXELS = 32_000_000`
    - `suspend fun loadForAnalysis(file: File, mimeType: String): SourceImage`
    - `suspend fun loadForExport(file: File): ExportBitmap`
  - `data class ExportBitmap(bitmap: Bitmap, downscaled: Boolean, width: Int, height: Int)`

三件事必须一次做对（spec §5.1、§9.2）：
1. **EXIF 转正**：读 `ORIENTATION` 把 Bitmap 转正。后续所有坐标运算都在「已转正」的空间里做——统一坐标系比到处传旋转角省事得多。
2. **分析降采样**：长边超过 2048 就按 2 的幂降采样，记下 `scale`（= 降采样图边长 / 原图边长，≤ 1）。
3. **导出上限**：原图全分辨率解码是唯一的 OOM 风险点，超过 32 MP 按 2 的幂降到 32 MP 以内。

- [ ] **Step 1: 写失败的测试**

`app/src/test/java/com/dama/app/core/image/SourceImageLoaderTest.kt`：

```kotlin
package com.dama.app.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceImageLoaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 左上角红、其余白的 JPEG，用来验证旋转确实发生在像素上。 */
    private fun writeJpeg(name: String, w: Int, h: Int, orientation: Int? = null): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, w / 4f, h / 4f, Paint().apply { color = Color.RED })
        }
        val f = tmp.newFile(name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        if (orientation != null) {
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return f
    }

    @Test
    fun `small image is not downsampled and scale is one`() = runTest {
        val f = writeJpeg("small.jpg", 800, 600)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(img.bitmap.width).isEqualTo(800)
        assertThat(img.bitmap.height).isEqualTo(600)
        assertThat(img.scale).isWithin(1e-4f).of(1f)
        assertThat(img.originalWidth).isEqualTo(800)
    }

    @Test
    fun `long edge above 2048 is downsampled by a power of two`() = runTest {
        val f = writeJpeg("big.jpg", 4800, 2400)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(max(img.bitmap.width, img.bitmap.height)).isAtMost(SourceImageLoader.ANALYSIS_MAX_EDGE)
        // inSampleSize=4 → 1200x600
        assertThat(img.bitmap.width).isEqualTo(1200)
        assertThat(img.scale).isWithin(1e-3f).of(0.25f)
        assertThat(img.originalWidth).isEqualTo(4800)
        assertThat(img.originalHeight).isEqualTo(2400)
    }

    @Test
    fun `rotate 90 exif swaps width and height`() = runTest {
        val f = writeJpeg("rot.jpg", 400, 200, ExifInterface.ORIENTATION_ROTATE_90)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(img.bitmap.width).isEqualTo(200)
        assertThat(img.bitmap.height).isEqualTo(400)
        // originalWidth/Height 也是转正后的尺寸——全流程只有一个坐标系
        assertThat(img.originalWidth).isEqualTo(200)
        assertThat(img.originalHeight).isEqualTo(400)
    }

    @Test
    fun `rotate 90 moves the red corner from top-left to top-right`() = runTest {
        val f = writeJpeg("rotpixel.jpg", 400, 200, ExifInterface.ORIENTATION_ROTATE_90)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        fun isRed(c: Int) = Color.red(c) > 180 && Color.green(c) < 90
        assertThat(isRed(img.bitmap.getPixel(img.bitmap.width - 5, 5))).isTrue()
        assertThat(isRed(img.bitmap.getPixel(5, 5))).isFalse()
    }

    @Test
    fun `toOriginal maps a downsampled quad back to full resolution`() = runTest {
        val f = writeJpeg("map.jpg", 4800, 2400)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        val q = com.dama.app.core.geometry.Quad.fromRect(android.graphics.RectF(100f, 50f, 200f, 100f))
        assertThat(img.toOriginal(q).bounds())
            .isEqualTo(android.graphics.RectF(400f, 200f, 800f, 400f))
    }

    @Test
    fun `export decode keeps full resolution below the pixel cap`() = runTest {
        val f = writeJpeg("exp.jpg", 3000, 2000)
        val out = SourceImageLoader.loadForExport(f)
        assertThat(out.bitmap.width).isEqualTo(3000)
        assertThat(out.downscaled).isFalse()
    }

    @Test
    fun `export decode downsamples beyond 32 megapixels`() = runTest {
        // 8000x5000 = 40 MP > 32 MP → inSampleSize=2 → 4000x2500
        val f = writeJpeg("huge.jpg", 8000, 5000)
        val out = SourceImageLoader.loadForExport(f)
        assertThat(out.bitmap.width.toLong() * out.bitmap.height).isAtMost(SourceImageLoader.EXPORT_MAX_PIXELS.toLong())
        assertThat(out.downscaled).isTrue()
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew :app:testDebugUnitTest --tests '*SourceImageLoaderTest*'
```

预期：编译失败，`Unresolved reference: SourceImageLoader`

- [ ] **Step 3: 实现 SourceImage 与 SourceImageLoader**

`app/src/main/java/com/dama/app/core/image/SourceImage.kt`：

```kotlin
package com.dama.app.core.image

import android.graphics.Bitmap
import com.dama.app.core.geometry.Quad

/**
 * 识别与预览用的图像：已按 EXIF 转正、已降采样。
 *
 * @param scale 降采样图边长 / 原图边长，取值 (0, 1]。导出时按 1/scale 反算坐标。
 * @param originalWidth/originalHeight **转正之后**的原图尺寸——全流程只有一个坐标系。
 */
data class SourceImage(
    val bitmap: Bitmap,
    val scale: Float,
    val originalWidth: Int,
    val originalHeight: Int,
    val mimeType: String,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** 降采样坐标 → 原图坐标。 */
    fun toOriginal(quad: Quad): Quad = quad.scaled(1f / scale)
}

data class ExportBitmap(val bitmap: Bitmap, val downscaled: Boolean, val width: Int, val height: Int)
```

`app/src/main/java/com/dama/app/core/image/SourceImageLoader.kt`：

```kotlin
package com.dama.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * 解码 + EXIF 转正 + 降采样（spec §5.1、§9.2）。
 *
 * 识别跑在长边 2048 的缩略图上（省时省内存），打码在原图上画。
 * 两者之间靠 SourceImage.scale 换算。
 */
object SourceImageLoader {

    const val ANALYSIS_MAX_EDGE = 2048
    const val EXPORT_MAX_PIXELS = 32_000_000

    suspend fun loadForAnalysis(file: File, mimeType: String): SourceImage = withContext(Dispatchers.IO) {
        val (rawW, rawH) = readSize(file)
        val orientation = readOrientation(file)
        val sample = sampleSizeForEdge(max(rawW, rawH), ANALYSIS_MAX_EDGE)

        val decoded = decode(file, sample)
        val upright = applyOrientation(decoded, orientation)

        // 转正后的原图尺寸：旋转 90/270 时宽高互换
        val swapped = orientation in ROTATED_90_OR_270
        val origW = if (swapped) rawH else rawW
        val origH = if (swapped) rawW else rawH

        SourceImage(
            bitmap = upright,
            scale = upright.width.toFloat() / origW.toFloat(),
            originalWidth = origW,
            originalHeight = origH,
            mimeType = mimeType,
        )
    }

    suspend fun loadForExport(file: File): ExportBitmap = withContext(Dispatchers.IO) {
        val (rawW, rawH) = readSize(file)
        val orientation = readOrientation(file)
        val sample = sampleSizeForPixels(rawW.toLong() * rawH.toLong(), EXPORT_MAX_PIXELS.toLong())

        val decoded = decode(file, sample)
        val upright = applyOrientation(decoded, orientation)
        ExportBitmap(
            bitmap = upright,
            downscaled = sample > 1,
            width = upright.width,
            height = upright.height,
        )
    }

    private fun readSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IOException("无法解码：${file.name}")
        return opts.outWidth to opts.outHeight
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decode(file: File, sampleSize: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IOException("解码返回 null：${file.name}")
    }

    /** 2 的幂降采样，保证降采样后长边 ≤ maxEdge。 */
    internal fun sampleSizeForEdge(longEdge: Int, maxEdge: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        // 上面的循环保证 longEdge/s < maxEdge*2；再收一次确保真的不超
        while (longEdge / s > maxEdge) s *= 2
        return s
    }

    internal fun sampleSizeForPixels(pixels: Long, maxPixels: Long): Int {
        var s = 1
        while (pixels / (s.toLong() * s.toLong()) > maxPixels) s *= 2
        return s
    }

    /**
     * 把方向烘进像素。此后不再有旋转角这个概念——
     * ML Kit 虽然接受 rotation 参数，但统一坐标系比到处传角度省事得多。
     */
    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private val ROTATED_90_OR_270 = setOf(
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
    )
}
```

- [ ] **Step 4: 运行确认通过**

```bash
./gradlew :app:testDebugUnitTest --tests '*SourceImageLoaderTest*'
```

预期：7 个测试全部 PASS。

若 `rotate 90 moves the red corner` 失败，先确认测试类上有 `@GraphicsMode(GraphicsMode.Mode.NATIVE)` —— LEGACY 模式下 `getPixel` 恒返回 0，会让像素断言假通过或假失败。

- [ ] **Step 5: 跑全量测试与权限断言**

```bash
./gradlew :app:testDebugUnitTest :app:assertDebugNoRuntimePermissions
```

预期：全绿，且权限断言打印 `uses-permission = （空）`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/dama/app/core/image app/src/test/java/com/dama/app/core/image
git commit -m "feat: decode with EXIF orientation baked in, power-of-two downsampling for analysis and export"
```

---

## 本计划出口

- [ ] `./gradlew :app:assembleDebug` 成功
- [ ] `./gradlew :app:testDebugUnitTest` 全绿（约 35 个测试）
- [ ] `./gradlew :app:connectedDebugAndroidTest` 全绿（ImageIntake 5 个）
- [ ] `./gradlew :app:assertDebugNoRuntimePermissions` 打印「uses-permission = （空）」
- [ ] 反向验证做过一次：临时加一条 `INTERNET` 权限确实让构建失败

出口不是「可发布」——UI 还没有。可发布形态在计划 02 结束时达成。

## 待验证项（spec §15，本计划期间顺手验掉）

- [ ] **§15.2 后半**：`ACTION_SEND` 进来的 content URI 在 Activity 重建后是否仍可读。Task 6 的私有副本方案就是为此，但需实测确认必要性与时机——在计划 02 的 Task 13 接入 `ACTION_SEND` 后，用「开发者选项 → 不保留活动」实测一次，把结论记在这里。
