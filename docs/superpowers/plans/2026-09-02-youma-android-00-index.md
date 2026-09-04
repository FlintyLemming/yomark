# 有码安卓版实现计划 · 索引

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement these plans task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `docs/superpowers/specs/2026-09-02-youma-android-design.md` 落成一个可上架的安卓图片打码应用。

**Spec:** `docs/superpowers/specs/2026-09-02-youma-android-design.md`

**为什么拆成多份**：设计文档的 §12 定义了五个各自可发布的里程碑。每份计划对应一个（或半个）里程碑，独立可执行、独立可验收、结束时产物可运行。按顺序执行，不要跳。

---

## 计划清单

| # | 文件 | 里程碑 | 任务 | 出口 |
|---|---|---|---|---|
| 01 | `2026-09-02-youma-android-01-foundation.md` | M1 上半 | 1–7 | 工程可编译、权限断言进 CI、几何与数据模型齐备、图片能解码转正 |
| 02 | `2026-09-02-youma-android-02-editor-export.md` | M1 下半 | 8–15 | **可发布**：纯手动打码工具，零权限弹窗，导出进相册 |
| 03 | `2026-09-02-youma-android-03-recognition.md` | M2 上半 | 16–25 | 引擎 + OCR + 11 条规则 + 合并，全部单测通过 |
| 04 | `2026-09-02-youma-android-04-tri-state.md` | M2 下半 | 26–30 | **可发布**：三态编辑 + 导出拦截 + 评测 harness |
| 05 | `2026-09-02-youma-android-05-face-barcode.md` | M3 | 31–33 | 人脸与条码进并行管线，降级路径打通 |
| 06 | `2026-09-02-youma-android-06-styles.md` | M4 | 34–40 | 六种打码样式全集 + 抹除降级 + 用途水印 |
| 07 | `2026-09-02-youma-android-07-batch-billing.md` | M5 | 41–46 | **可发布**：批量流、引导页、Play Billing 去水印 |

共 46 个任务，编号跨计划连续。三个「可发布」节点分别对应 spec §12 的 M1、M2、M5 出口。

---

## Global Constraints

以下是全项目约束，**每个任务的验收条件都隐含包含本节**。数值一律照抄自设计文档，不得自行调整。

### 平台与构建
- `compileSdk = 36`，`minSdk = 29`，`targetSdk = 36`
- `application` 声明 `android:largeHeap="true"`、`android:allowBackup="false"`
- 全应用只有一个 Activity：`com.youma.app.ui.EditorActivity`，同时是 LAUNCHER 入口与 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 目标
- 以 App Bundle 按 ABI 分发，arm64-v8a split 下发体积 ≤ 25 MB

### 权限与网络（本方案的核心承诺）
- `AndroidManifest.xml` **不声明任何权限**
- 合并后 manifest 中除 `com.android.vending.BILLING` 外的 `uses-permission` 条数 = **0**，由 Gradle 任务断言，构建失败即阻断
- 运行期网络请求数 = **0**（不含用户主动发起的购买流程）
- ML Kit 一律用 **bundled** 变体（`text-recognition` / `face-detection` / `barcode-scanning`），不得引入任何 unbundled / 动态下载变体
- **禁止引入 ML Kit Entity Extraction**——它只有动态下载形态，会带进 `INTERNET` 权限
- 不自绘相册，选图一律走系统 Photo Picker（`ActivityResultContracts.PickVisualMedia`）

### 图像管线
- 识别跑在**长边降采样到 2048** 的图上，记录 `scale`
- 导出必须在**原图分辨率**上重绘，不得放大预览图
- 导出解码上限 **32 MP**，超出按 2 的幂降采样，并在完成提示里说明「原图过大，已压缩至 X×Y」
- 导出**不携带任何 EXIF**；方向信息在解码时烘进像素，不写 orientation tag
- 导出重新编码：源为 PNG 输出 PNG，其余一律 JPEG q=95
- MediaStore 写入全程 `IS_PENDING` 事务

### 交互与状态
- `MaskPlan.style` 是**全局单值**，不是逐项
- 撤销/重做用 **MaskPlan 快照**，`ArrayDeque` 上限 **50**，换图时两栈清空
- **不对称删除规则**：规则命中的候选点击只在 `MASKED ⇄ OUTLINED` 间切换，永不从画面消失；只有 `kind == MANUAL` 的手动框可以删除
- `plan.pendingCount > 0` 时点击导出**必然**弹拦截对话框，**不提供「不再提示」**
- 拖动判定阈值 = `ViewConfiguration.touchSlop`（约 8dp）；拖出的框短边 < 16dp 丢弃
- 类型小标签只在缩放比 ≥ 0.5 时绘制

### 水印
- 免费导出带品牌水印，付费一次性买断后不带
- **水印不得与任何 `MASKED` 区域相交**；避让顺序：右下 → 左下 → 左上 → 右上；四角全被占则回右下并加不透明描边
- 位置边距 = 短边 3%，高度 = 短边 4%（下限 24px），绘制时机在所有遮罩之后、编码之前

### 取舍原则（评审任何识别改动时优先于「精确率」）
> **漏检是事故，误报只是麻烦。**
> 默认打码类型的置信度阈值往低了设，宁可多框；默认仅圈出的类型靠导出拦截兜底。

---

## 共享技术约定

### 包结构

```
app/src/main/java/com/youma/app/
  core/geometry/   Quad
  core/model/      SensitiveKind, DetectorSource, Candidate, TextLine, AnalysisResult,
                   MaskState, MaskItem, MaskPlan, MaskStyle, MaskOptions
  core/image/      SourceImage, ImageIntake, SourceImageLoader
  engine/          TextRecognizer, RegionDetector, SensitivityClassifier,
                   RedactionEngine, CandidateMerger, EngineFactory
  engine/mlkit/    MlKitTextRecognizer, MlKitFaceDetector, MlKitBarcodeDetector
  rules/           Rule, RegexRule, PhoneRule, RuleClassifier, DefaultRuleSet
  rules/validator/ Luhn, Iban, Ssn, Mrz, Entropy
  render/          MaskRenderer, SolidRenderer, PixelateRenderer, BlurRenderer,
                   MarkerRenderer, EmojiRenderer, EraseRenderer, RendererRegistry
  export/          Exporter, WatermarkDrawer, MediaStoreWriter
  billing/         PurchaseStore, BillingRepository
  ui/              EditorActivity, EditorViewModel, EditorUiState, UndoStack
  ui/canvas/       Viewport, GestureReducer, ImageCanvas, Overlays
  ui/components/   TopBar, StyleBar, PendingExportDialog
```

一个文件一个职责。会一起改的东西放在一起（规则和它的校验器、渲染器和它的选项）。

### 测试分层

| 层 | 位置 | 工具 |
|---|---|---|
| 纯逻辑（几何、规则、合并、撤销栈、水印避让） | `app/src/test/` | JUnit4 + Truth + Robolectric |
| 需要真实像素的（渲染器、导出、EXIF 断言） | `app/src/test/` | Robolectric `@GraphicsMode(NATIVE)` |
| ML Kit、MediaStore、Photo Picker、Activity 闭环 | `app/src/androidTest/` | AndroidJUnit4 + 模拟器 |
| 权限断言 | Gradle 任务 | `assertNoRuntimePermissions` |

Robolectric 的 `NATIVE` 图形模式会真实执行 `Canvas` 绘制并产出可读像素，渲染器与水印避让因此不必上模拟器。**每个涉及 `Canvas` / `Bitmap` 的单测类都必须标 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`**，否则 `getPixel` 全返回 0，测试会假通过。

### 提交规范

每个任务的最后一步是提交。用 Conventional Commits：`feat:` / `test:` / `fix:` / `chore:` / `docs:`。一个任务一个提交，除非任务里明确写了多个提交点。

---

## 版本锁定

版本号在 **计划 01 的 Task 1** 一次性写进 `gradle/libs.versions.toml`，此后所有任务一律引用 version catalog 的别名，不在 `build.gradle.kts` 里写裸版本号。

若某个版本解析失败（artifact 已下架或版本号已过期），取该 artifact 的**最新稳定版**，把新版本写回 `libs.versions.toml`，并在提交信息里说明改了什么。不要为了绕过解析失败而降级到 unbundled 变体或改动上面的 Global Constraints。
