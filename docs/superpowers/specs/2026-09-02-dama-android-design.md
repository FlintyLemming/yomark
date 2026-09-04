# DAMA 安卓版实现方案

技术方案书 · v1.1 · 2026-09-02

一个图片隐私打码工具的完整实现规格。方案已确定，本文不列备选——所有可能的替换点都以接口形式写在 §4 和 §14 里。

| | |
|---|---|
| 目标 | Google Play 海外 |
| 范围 | 首版 MVP |
| 栈 | Kotlin · Compose · ML Kit bundled |
| 运行期敏感权限 | 0 项 |

### 相对 v1.0 的变更

v1.1 落定了五个 v1.0 留白或自相矛盾的决定。每一条都在正文里展开：

1. **接内购**，权限口径从「0 条 uses-permission」改为「0 条运行期敏感权限」（§11、§13）。
2. **冷启动直接拉起系统 Photo Picker**，不自绘相册（§7.1）。
3. **编辑器三态模型**：已打码 / 圈出未打码 / 未圈出，取代 v1.0 的二态勾选（§7）。
4. **候选默认状态按误报率分层**，`enabledByDefault` 从摆设升格为规则表的一列（§6）。
5. **免费导出带品牌水印，付费去除**（§9.3、§10）。

---

## §0 · 方案陈述

**一个不申请任何敏感权限、不在识别与编辑流程中发起任何网络请求的打码工具。**

识别全部用 ML Kit 的 bundled 变体——文字识别、人脸检测、条码扫描三个模型编译进 APK，装完即用，飞行模式照常工作。敏感信息的判定以正则加校验位为地基，模型只负责给出文字和坐标，不负责判断什么是隐私。

代价是 APK 多约 13 MB；换来的是运行期零网络请求、以及权限列表上除计费外的空白。对一个隐私工具来说，这个空白就是最有力的产品说明。

这个决定连带定死了三件事：

- 首版不用 ML Kit Entity Extraction——它只有动态下载一种形态，会引入 `INTERNET` 权限与网络依赖。
- `minSdk` 定在 29。Android 10 的作用域存储让 app 读写自己创建的媒体文件免权限，低于这个版本就必须申请存储权限。
- 不自绘相册。自绘相册网格必须读设备媒体库，需要 `READ_MEDIA_IMAGES`（13+）或 `READ_EXTERNAL_STORAGE`（≤12）；Android 14 的部分访问 `READ_MEDIA_VISUAL_USER_SELECTED` 同样是 manifest 里实打实的一条声明。系统 Photo Picker 由系统进程绘制，app 只拿回被选中那一张的 URI，是唯一不破坏前提的入口。

### 关于计费权限的例外

Play Billing Library 的 manifest 会通过 manifest merger 并入 `com.android.vending.BILLING`，代码里写不写都会有。这是接受内购的必然代价，不可规避。

本方案的立场是：**该条声明不授予任何数据访问能力**——它不读取照片、不读取存储、不读取位置、不读取通讯录、不开启摄像头。商店描述首屏主动写明这一点。§13 的验收指标相应改为「运行期敏感权限 = 0」。

---

## §1 · 范围与非目标

### 首版要做的

- 冷启动直接拉起系统 Photo Picker 选图；或从别的 app 分享进来（`ACTION_SEND`）
- 自动识别并框出：支付卡号、IBAN、电话、邮箱、SSN、护照 MRZ、IP/MAC、URL、API key、快递单号、人脸、二维码与条形码
- 三态编辑：高置信类型进编辑器即已打码，低置信类型仅圈出待确认，点击互相切换
- 手动框选任意区域
- 打码样式：实色块（默认）、像素化、模糊、马克笔、Emoji、抹除
- 导出：在原图分辨率上重绘、重新编码写入相册，元数据一律不携带；免费版带品牌水印

### 首版明确不做的

- 人名 / 地名 / 机构名识别——需要 NER 模型，接口已留（§14），首版留空
- 非拉丁脚本 OCR——中日韩各需一个额外脚本包，每个每架构约 4 MB，等市场数据说话
- 视频打码、云端同步、账号体系
- 无人值守的批量处理（见 §7.6）
- 用途水印（在证件照上叠「仅供办理 XX 使用」）——排在 M4，不阻塞首版发布

---

## §2 · 技术选型

每一行都是一个已经做完的决定。备选方案不在这里，在 §14 的接口位。

| 层 | 选定 | 为什么是它 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose | 编辑器界面是大量自定义手势与 Canvas 绘制，Compose 的 `pointerInput` 比 View 体系省事 |
| SDK 区间 | minSdk 29 / target 36 | Android 10 起读写自己的媒体文件免权限。这是零权限方案的硬前提 |
| 选图入口 | `ActivityResultContracts.PickVisualMedia` | 系统进程绘制，免权限，是不自绘相册的唯一替代 |
| 文字识别 | ML Kit Text Recognition v2（bundled，拉丁） | 返回 `cornerPoints` 四边形与旋转信息，斜向文字直接可用；离线、免费 |
| 人脸检测 | ML Kit Face Detection（bundled） | 只需要边界框，FAST 模式够用 |
| 条码 | ML Kit Barcode Scanning（bundled） | 13 种码制，支持静态图输入 |
| 敏感判定 | 自研规则引擎 + libphonenumber | 结构化实体用正则加校验位可以做到接近 100%，且可解释、可单测 |
| 图像解码 | ImageDecoder + ExifInterface | 需要精确控制采样率和方向校正，不交给图片库 |
| 异步 | Coroutines + Flow | 识别管线天然是并行 + 汇聚 |
| 依赖注入 | 不引框架，手工装配 | 整个应用只有一处需要装配（§4.4），引 Hilt 是负收益 |
| 设置持久化 | DataStore Preferences | 只存上次用的打码样式、购买态等几个值，不需要数据库 |
| 计费 | Google Play Billing Library | 一次性买断，无服务端。权限代价见 §0 |

---

## §3 · 架构与数据流

五个阶段，前三个是纯函数式的数据变换，第四个是 UI 状态，只有最后一个写磁盘：

```
SourceImage                        → 解码 + EXIF 方向校正 + 长边降采样到 2048
   │
   ├─ TextRecognizer   → List<TextLine> → SensitivityClassifier[] → Candidate[]
   └─ RegionDetector[] → Candidate[]（人脸 / 条码）
   │
CandidateMerger                    → IoU 去重、同类相邻合并、按面积排序
   │
MaskPlan（三态；用户可增删改）       → UI 状态，撤销/重做栈在这一层
   │
Exporter                           → 原图分辨率重绘 → 品牌水印 → 重编码 → MediaStore
```

**一个容易做错的地方**：识别在降采样图上跑，打码必须在原图上画。识别用 2048 长边的缩略图（省时省内存），得到的四边形按 `scale` 反算回原图坐标，导出时在原始分辨率上重绘。如果直接把降采样图导出，用户会拿到一张画质变差的图，这是差评的主要来源之一。

---

## §4 · 核心接口

这一节是整份方案里最需要照着实现的部分。三个接口把「用谁的模型」这件事和其余所有代码隔离开，全应用只有 §4.4 那一个函数知道用的是 ML Kit。

### 4.1 数据模型

```kotlin
// ---------- 几何 ----------
/** 图像上的任意四边形。OCR 的文本框会是倾斜的，一律用四点表示，不退化成 Rect。 */
data class Quad(val p0: PointF, val p1: PointF, val p2: PointF, val p3: PointF) {
    fun bounds(): RectF
    fun expand(px: Float): Quad          // 外扩，用于抹除时采样周边像素
    fun iou(other: Quad): Float          // 合并候选时判重
    fun scaled(factor: Float): Quad      // 降采样坐标 ↔ 原图坐标
    fun contains(p: PointF): Boolean     // 命中测试
}

// ---------- 语义 ----------
enum class SensitiveKind {
    PHONE, EMAIL, PAYMENT_CARD, IBAN, SSN, PASSPORT,
    TRACKING_NO, IP_ADDR, MAC_ADDR, URL, API_KEY,
    POSTAL_ADDRESS, PERSON_NAME, ORG_NAME,   // v2：需要 NER，首版不产出
    FACE, BARCODE, MANUAL
}

/** 候选是谁提出来的。UI 上区分「规则命中」和「模型猜的」，后者标为实验性。 */
enum class DetectorSource { RULE, ENTITY_MODEL, LLM, FACE, BARCODE, MANUAL }

// ---------- OCR 输出 ----------
data class TextElement(val quad: Quad, val text: String, val range: IntRange)

data class TextLine(
    val quad: Quad,
    val text: String,                    // elements 按阅读顺序拼接
    val confidence: Float,
    val elements: List<TextElement>      // range 是该词在 text 中的字符区间
) {
    /** 把 text 上的匹配区间映射回像素四边形。见 §5.3 —— 只能到词粒度。 */
    fun quadForRange(range: IntRange): Quad
}

// ---------- 引擎输出 ----------
data class Candidate(
    val id: String,
    val quad: Quad,
    val kind: SensitiveKind,
    val source: DetectorSource,
    val confidence: Float,
    /**
     * 进编辑器时的初始状态。true → MASKED，false → OUTLINED。
     * 由规则表逐条指定（§6），不是由 confidence 阈值算出来的。
     */
    val enabledByDefault: Boolean = true
)

data class AnalysisResult(val lines: List<TextLine>, val candidates: List<Candidate>)
```

### 4.2 编辑状态模型

v1.0 只用一句话带过 MaskPlan。三态模型把它变成了核心状态，展开如下：

```kotlin
/** 未圈出的区域根本不在 plan 里，因此只有两个枚举值。 */
enum class MaskState { MASKED, OUTLINED }

data class MaskItem(
    val candidateId: String,
    val quad: Quad,                      // 降采样坐标系；导出时反算
    val kind: SensitiveKind,
    val source: DetectorSource,
    val state: MaskState
)

data class MaskPlan(
    val items: List<MaskItem>,
    val style: MaskStyle,                // 全局，非逐项。见 §7.5
    val options: MaskOptions
) {
    /** 已识别但尚未打码的数量。导出拦截（§7.4）的触发条件。 */
    val pendingCount: Int get() = items.count { it.state == MaskState.OUTLINED }
}
```

**撤销栈用快照，不用命令模式。** MaskPlan 撑死几十个 item，整个存下来也就几 KB。一个 `ArrayDeque<MaskPlan>`（上限 50）加一个重做栈，比实现 do/undo 命令对省一大半代码，且不可能出现命令不对称的 bug。这是全方案里唯一一处主动选择「笨但正确」的实现。换图时两个栈都清空。

**一条不对称规则**：规则命中的候选点击只在 `MASKED ⇄ OUTLINED` 之间切换，**永不从画面消失**；只有 `kind == MANUAL` 的手动框可以被彻底删除。理由是候选一旦消失用户就再也找不回来，而漏检是事故。

### 4.3 四个可替换接口

```kotlin
/**
 * 定位层。唯一职责：把一张图变成带坐标的文本行。
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
 * 首版只有 RuleClassifier。Gemini Nano、ML Kit Entity Extraction、本地 NER
 * 各自是一个实现，按优先级排进 List —— 规则永远在第一位且永不缺席。
 */
interface SensitivityClassifier {
    val id: String
    /** 运行时探测。模型没就绪、设备不支持、配额耗尽都返回 false，引擎跳过它。 */
    suspend fun isAvailable(): Boolean
    suspend fun classify(lines: List<TextLine>): List<Candidate>
}

/** 渲染层。每种打码样式一个实现，样式与识别完全解耦。 */
interface MaskRenderer {
    val style: MaskStyle
    fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions)
}
```

### 4.4 引擎与装配

```kotlin
class RedactionEngine(
    private val recognizer: TextRecognizer,
    private val regionDetectors: List<RegionDetector>,
    private val classifiers: List<SensitivityClassifier>,
    private val merger: CandidateMerger = CandidateMerger()
) {
    suspend fun analyze(image: SourceImage): AnalysisResult = coroutineScope {
        // 文字链路和区域检测互不依赖，并行跑
        val linesJob = async { recognizer.recognize(image) }
        val regionJobs = regionDetectors.map { d ->
            async { runCatching { d.detect(image) }.getOrElse { emptyList() } }
        }
        val lines = runCatching { linesJob.await() }.getOrElse { emptyList() }
        val fromText = classifiers
            .filter { it.isAvailable() }                       // 不可用的直接跳过
            .flatMap { c -> runCatching { c.classify(lines) }.getOrElse { emptyList() } }
        AnalysisResult(
            lines = lines,
            candidates = merger.merge(fromText + regionJobs.awaitAll().flatten())
        )
    }
}

// ---------- 首版装配：整个应用只有这一处知道用的是哪家的模型 ----------
fun buildEngine(context: Context) = RedactionEngine(
    recognizer = MlKitTextRecognizer(),
    regionDetectors = listOf(MlKitFaceDetector(), MlKitBarcodeDetector()),
    classifiers = listOf(RuleClassifier(DefaultRuleSet))
)

// v2 想加 Gemini Nano 语义判定：classifiers 里追加一项，其余不动
//     classifiers = listOf(RuleClassifier(DefaultRuleSet), GeminiNanoClassifier(context))
//
// v2 想换成自带 OCR 模型：换掉 recognizer 这一行，其余不动
//     recognizer = PaddleOcrOnnxRecognizer(context)
```

注意 `classifiers` 是一个 List 而不是单个实例，并且每个实现都要回答 `isAvailable()`。这两点合起来就是全部的扩展机制：加一个判定器不需要改引擎，某个判定器在当前设备上不可用时引擎自动跳过，规则层始终兜底。

---

## §5 · 识别管线

### 5.1 预处理

读 EXIF 的 `ORIENTATION` 并把 Bitmap 转正——ML Kit 虽然接受 rotation 参数，但后续所有坐标运算都在「已转正」的空间里做，统一坐标系比到处传旋转角省事得多。长边超过 2048 就按 2 的幂降采样，记下 `scale` 供反算。

### 5.2 并行取证

OCR、人脸、条码三条链路互不依赖，用 `async` 并行。任何一条抛异常都降级为空结果而不是让整次识别失败——用户宁可少几个候选，也不愿意看到「识别失败」。OCR 链路失败时 `lines` 为空列表，规则层自然产出零候选，编辑器仍可用于手动打码。

### 5.3 把匹配区间映射回像素

这是最容易写错的一步。规则跑在 `TextLine.text` 这个字符串上，得到的是字符区间；但 ML Kit 给的坐标最细只到 **element（词）** 级别。做法是：

1. 拼接 `text` 时记录每个 element 占据的字符区间 `range`；
2. 匹配区间与哪些 element 的 `range` 相交，就取这些 element；
3. 对它们的四边形求**最小外接四边形**（不是外接矩形——倾斜文字的外接矩形会盖住相邻内容）。

结果是词粒度：命中 `4111111111111111` 时如果整个卡号是一个 element，就精确覆盖；如果 element 是 `Card: 4111...`，会连 `Card:` 一起遮掉。这符合「宁可多遮」的取舍，不做优化。

### 5.4 候选合并

`CandidateMerger` 做三件事：

- IoU > 0.6 的候选按 source 优先级去重（`RULE > ENTITY_MODEL > LLM`）；
- 同一 kind 且水平间距小于行高 0.5 倍的相邻候选合并成一个；合并后的 `enabledByDefault` 取**或**（任一为 true 则为 true），保证合并不会把默认打码的降级成仅圈出；
- 按面积降序输出，让 UI 先渲染大块。

---

## §6 · 敏感规则表

首版的全部规则。每条都有校验步骤——只有正则没有校验的规则误报率高到不可用。

**「默认」一列按误报率划线，不按危害划线。** 理由：高误报类型自动打码会让用户一直在跟 app 对着干，每张图都要点掉七八个误报，比少遮一个 URL 伤害大得多。有校验位兜底的类型误报接近零，自动打码不会打扰任何人。

| 类型 | 匹配 | 校验 | 默认 | 示例（已按本 app 规则遮住） |
|---|---|---|---|---|
| PAYMENT_CARD | 13–19 位数字，容忍空格与连字符分组 | Luhn 校验和 | **打码** | `4111 ████ ████ ████` |
| IBAN | 2 位国家码 + 2 位校验位 + BBAN，长度查国家表 | 重排后 mod-97 == 1 | **打码** | `DE89 ████████████████` |
| PHONE | 交给 libphonenumber 的 `findNumbers` | `isValidNumber`，排除明显的订单号 | **打码** | `+1 415 ███ ████` |
| EMAIL | RFC 5322 简化式 | 域名必须有已知 TLD | **打码** | `██████@example.com` |
| SSN | `\d{3}-\d{2}-\d{4}` | 排除 000 / 666 / 9xx 开头等无效段 | **打码** | `███-██-████` |
| PASSPORT | 优先识别 MRZ 两行（各 44 字符） | MRZ 自带校验位 | **打码** | `P<USA███████████` |
| MAC_ADDR | 六组两位十六进制 | 分隔符一致性 | **打码** | `██:██:██:██:██:██` |
| API_KEY | 已知前缀（`sk-`、`ghp_`、`AKIA`、JWT 的 `eyJ`） | Shannon 熵 > 3.5 | **打码** | `sk-████████████████` |
| URL | 含协议头，或常见 TLD 结尾 | 只遮 query 与 path，域名保留 | 仅圈出 | `app.com/r/████████` |
| IP_ADDR | IPv4 / IPv6 正则 | 各段数值范围；排除版本号误报 | 仅圈出 | `192.168.█.██` |
| TRACKING_NO | 主流承运商格式表 | 部分承运商有校验位，无校验位的降低置信度 | 仅圈出 | `1Z██████████████` |

以上 11 条构成 `DefaultRuleSet`，全部跑在 `RuleClassifier` 里，属于 M2。`FACE` 与 `BARCODE` 不是规则——它们由 `RegionDetector` 直接产出（M3），默认状态恒为**打码**，不经过这张表。

**规则集是数据不是代码**：`DefaultRuleSet` 是一个 `List<Rule>`，每条 `Rule` 携带 `pattern`、`validator`、`kind` 和 `enabledByDefault`。加一条规则不改任何逻辑。

**取舍写死在代码里**：漏检是事故，误报只是麻烦。默认打码的类型置信度阈值往低了设，宁可多框；默认仅圈出的类型靠 §7.4 的导出拦截兜底，保证不会因为分层而静默漏出。这条原则在评审任何一次识别改动时都优先于「精确率」。

---

## §7 · 编辑器交互规格

v1.0 没有这一节。它是 M1 的主体。

### 7.1 入口

**冷启动直接拉起系统 Photo Picker。** 用户点图标 → 立刻是一屏照片网格 → 选中即进编辑器，且**候选已经按 §6 的默认状态打好码**，不是「等你逐个确认」。

- 全应用只有一个 `EditorActivity`，它同时是启动器入口和分享目标。`onCreate` 中若 intent 未携带图片 URI，立即 `launch(PickVisualMedia)`，不绘制自己的首屏（首次安装除外，见 M5 的引导页）。
- 用户在 Picker 里按返回 = 退出 app。这是正确行为，不是 bug：这个 app 没有「主页」这个概念。
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 进来的直接跳过 Picker。

**URI 生命周期**：`ACTION_SEND` 携带的 `content://` URI 权限在 Activity 重建后可能失效。入口处**立即把原图复制到应用私有目录**（`context.cacheDir`），此后全流程只读私有副本。这同时解决了「用户在后台删掉了原图」的情况。私有副本在导出成功或 Activity 销毁时删除。

### 7.2 三态

| 状态 | 外观 | 单指点击 |
|---|---|---|
| **已打码** `MASKED` | 实心遮罩（当前样式） | → 取消，退回「圈出未打码」 |
| **圈出未打码** `OUTLINED` | 2dp 虚线框（琥珀色）+ 类型小标签 | → 打码 |
| **未圈出** | 无 | 见 §7.3 |

类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片。

### 7.3 手势

采用绘图类 app 的通行分工——**一指画、两指导航**。不设模式切换按钮，底栏保持干净，也避免了「长按起手」方案里想平移时手指停顿一下就误画框的问题。

| 手势 | 行为 |
|---|---|
| 单指点击候选/遮罩 | 切换 `MASKED ⇄ OUTLINED` |
| 单指点击空白 | 取消当前选中态 |
| 单指拖动（空白起手） | 画新框，落笔即 `kind = MANUAL, state = MASKED` |
| 双指捏合 | 缩放 |
| 双指拖动 | 平移 |
| 双击 | 在「适配屏幕」与 200% 之间切换 |
| 长按手动框 | 进入选中态：出现四角手柄与删除按钮 |
| 选中态下拖框体 / 拖手柄 | 移动 / 调整大小 |

**点击与拖动的区分**：位移超过 `ViewConfiguration.touchSlop`（约 8dp）判为拖动。拖出的框短边小于 16dp 则丢弃，防误触产生 1px 框。

**顶栏**：撤销、重做、关闭。**底栏**：样式选择器、导出。

### 7.4 导出拦截

三态模型开了一个 v1.0 没有的风险：有候选会以 `OUTLINED` 状态出厂，用户没注意就导出 = 泄露。这与 §0「漏检是事故」的立场直接冲突，因此拦截**不是打磨项，是这套模型成立的前提**。

- **触发条件**：`plan.pendingCount > 0`，点击导出时触发，无例外。
- **文案**：「还有 N 处已识别的内容未打码」，下方按类型列出（如「2 个网址、1 个 IP 地址」）。
- **按钮**：「全部打码」（主要）/「仍然导出」（次要）。
- **不提供「不再提示」**。首版刻意不给关。这是分层默认的唯一安全网，关掉它等于把 URL/IP/快递单号三类彻底变成静默漏检。有留存数据后再评估。

### 7.5 样式是全局的

样式选择器作用于**整张图的所有遮罩**，`MaskPlan.style` 是单值而非逐项。理由是逐项样式要求先选中再改，把「点一下取消」这个最高频操作变成两步，而混合样式的实际需求极低。逐项样式在 §14 留了位置。

### 7.6 批量（M5）

**不做无人值守批量。** `ACTION_SEND_MULTIPLE` 进来后逐张进入同一个编辑器，底栏「下一张」，走完最后一张统一导出。

理由：三态模型的全部价值在于人眼过一遍那些仅圈出的候选。无人值守批量只能二选一——要么把 `OUTLINED` 全部打码（等于取消分层，回到 v1.0 的过度遮挡），要么全部不打码（静默漏检）。两个都不可接受，所以这个功能形态本身不成立。

---

## §8 · 打码渲染规格

六种样式，全部实现 `MaskRenderer`。每种的绘制路径都以 `Quad` 为裁剪区，不是外接矩形。

| 样式 | 实现 | 安全性 |
|---|---|---|
| **实色块**（默认） | 沿 Quad 描路径，`drawPath` 填充不透明色 | 不可还原 |
| 像素化 | 取 Quad 外接框区域 → 缩到 1/N → 最近邻放大 → 以 Quad 裁剪绘回 | 块边长下限 = `max(区域短边/8, 12px)`（原图口径，预览按 `renderScale` 折算）。**实测可还原，已降级为「外观优先，非安全」，UI 明确标注**，见 §15.6 |
| 模糊 | 同上流程，改用三次缩放逼近高斯（不用 `RenderEffect`：它只能作用于硬件加速 Canvas，而导出画在软件 Bitmap 上，分两条路径会让预览与导出不一致） | UI 明确标注「外观优先，非安全」 |
| 马克笔 | 半透明色叠加，路径带轻微手绘抖动 | 不遮蔽，仅标记；UI 标注 |
| Emoji | 按 Quad 短边定字号，居中绘制文本 | 不可还原 |
| 抹除 | 采样 Quad 外扩 4px 的环形区域，取中位色填充 | 不可还原；见下 |

**抹除的降级策略**：环形采样区的颜色方差超过阈值时（说明背景不是纯色，中位色填充会留下明显色块），自动降级为实色块并在 UI 上说明原因。截图场景绝大多数是纯色或简单渐变背景，这条路径的命中率会很高；照片类复杂背景交给降级，不引 inpainting 模型。

---

## §9 · 导出规格

### 9.1 管线

```
1. 原图全分辨率解码（不降采样，但受 §9.2 上限约束）+ EXIF 方向校正
2. 逐项渲染 state == MASKED 的 MaskItem，quad 按 1/scale 反算回原图坐标
3. 免费用户绘制品牌水印（§9.3）
4. 编码：源为 PNG 则输出 PNG，其余一律 JPEG q=95
5. MediaStore.Images 写入，全程 IS_PENDING 事务
```

### 9.2 四条硬性要求

1. **在原图分辨率上重绘**，不是把预览图放大。
2. **重新编码输出**，绝不保留任何可分离的图层或 alpha 通道。
3. **不携带任何 EXIF**。`Bitmap.compress` 本身不写 EXIF，所以这条由重编码天然保证；但导出后必须断言 `ExifInterface` 读不到 GPS、设备型号、时间戳——把它做成一条自动化测试，而不是靠「我们没写所以肯定没有」。方向信息已在解码时烘进像素（§5.1），不写 orientation tag 是正确的。
4. **写入用 `IS_PENDING` 事务**，避免半成品被相册扫到。

**内存上限**：原图全分辨率解码是本方案唯一的 OOM 风险点。超过 **32 MP** 的源图按 2 的幂降采样到 32 MP 以内再导出，并在导出完成提示里说明「原图过大，已压缩至 X×Y」。`application` 声明 `android:largeHeap="true"`。分块渲染能彻底解决这个问题，但复杂度不值当首版付，留在 §14。

### 9.3 品牌水印

免费版导出带品牌水印，付费一次性买断后不带。这是唯一的付费点（§10）。

| 项 | 规格 |
|---|---|
| 内容 | app 图标 + 「DAMA」文字 |
| 位置 | 默认右下角，安全边距 = 短边的 3% |
| 尺寸 | 高度 = 短边的 4%，下限 24px |
| 颜色 | 半透明白，落点区域偏亮时自动切半透明黑 |
| 绘制时机 | 所有遮罩绘制完成之后，编码之前 |

**硬约束：水印不得与任何 `MASKED` 区域相交。** 半透明水印压在实色块上，会让人以为那个遮罩本身也是半透明的、底下的东西还在——这直接损害产品的核心承诺。避让规则：右下角起，与任何遮罩相交就顺时针换角（右下 → 左下 → 左上 → 右上）；四角全被占则回到右下并加不透明描边保证可读。

水印是营销手段，不是安全边界。用户裁掉它是预期内的，不做任何对抗。

---

## §10 · 商业模式

- **形态**：一次性买断，非订阅。无服务端，无账号。
- **免费版**：完整识别、完整三态编辑、全部六种打码样式、原图分辨率导出、元数据清除。**一项隐私能力都不缺。**
- **付费解锁**：去除品牌水印。仅此一项。

这个切法的理由：DAMA 的输出天然要发出去给别人看——发进工单、发进群、发进 bug report。对一个输出必然被公开传播的工具来说，水印不是税，是分发渠道本身。同时它避免了按功能收费的最大风险——商店评论区出现「保护隐私还要收费」。付费买的纯粹是外观。

**离线购买态**：Play Billing 的 `queryPurchasesAsync` 首次校验需要网络。购买态缓存在 DataStore，此后飞行模式下也认。已购用户在无网环境导出不会突然出现水印——这对一个主打离线的 app 是必须的。

缓存的购买态对 root 设备是可篡改的。**接受**：它保护的是一个水印，不是一道安全边界，为它引入服务端校验会同时破坏零网络与无账号两个前提。

---

## §11 · 依赖 · 权限 · 包体

### app/build.gradle.kts

```kotlin
android {
    compileSdk = 36
    defaultConfig {
        minSdk = 29        // Android 10：作用域存储，读写自己的媒体文件免权限
        targetSdk = 36
    }
}

dependencies {
    // 感知层 —— 全部 bundled：模型编译进 APK，装完即用，永不联网
    implementation("com.google.mlkit:text-recognition")     // 拉丁脚本
    implementation("com.google.mlkit:face-detection")
    implementation("com.google.mlkit:barcode-scanning")

    // 规则层 —— 跨国电话号码解析与有效性校验，正则做不了这件事
    implementation("com.googlecode.libphonenumber:libphonenumber")

    // 平台
    implementation("androidx.activity:activity-compose")    // Photo Picker，免权限选图
    implementation("androidx.exifinterface:exifinterface")  // 读方向 / 导出后断言无元数据
    implementation("androidx.datastore:datastore-preferences")

    // 计费 —— 唯一引入 manifest 权限声明的依赖，见 §0
    implementation("com.android.billingclient:billing-ktx")
}
```

版本号一律以官方 release notes 为准。ML Kit 官方示例工程当前用的是 `text-recognition:16.0.1`、`face-detection:16.1.7`、`barcode-scanning:17.3.0`，可作下限参考。Play Billing 取当前 Google Play 仍接受的最低版本以上的最新稳定版——Google 每年会淘汰旧版本，这个依赖必须定期升。

### AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!--
      本文件不声明任何权限。
      构建产物中唯一的 uses-permission 是 com.android.vending.BILLING，
      由 Play Billing Library 的 manifest merger 并入，不可规避。
      它不授予任何数据访问能力。见 §0。
      合并结果必须在 CI 中断言，见 §13。
    -->
    <application
        android:allowBackup="false"
        android:largeHeap="true">
        <!-- 单 Activity。冷启动无 URI 时自行拉起 Photo Picker，见 §7.1。 -->
        <activity android:name=".ui.EditorActivity" android:exported="true">
            <intent-filter>                          <!-- 启动器入口 -->
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>                          <!-- 从别的 app 分享进来 -->
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <intent-filter>                          <!-- 批量 -->
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### 包体预算

| 组成 | arm64-v8a 下发体积 |
|---|---|
| ML Kit 文字识别（拉丁，bundled） | ≈4.0 MB |
| ML Kit 人脸检测（bundled） | ≈6.9 MB |
| ML Kit 条码扫描（bundled） | ≈2.4 MB |
| libphonenumber（含元数据） | ≈1.5 MB |
| Compose 与 AndroidX | ≈4 MB |
| Play Billing | ≈0.5 MB |
| 应用代码与资源 | ≈2 MB |
| **合计** | **≈21.3 MB** |

以 App Bundle 按 ABI 分发，用户实际只下载 arm64 那一份。作为参照，iOS 版 DAMA 是 42.2 MB。

**实测（2026-09-03，计划 07 Task 46，release bundle，`bundletool get-size total --dimensions=ABI`）**：

| ABI | 下发体积 |
|---|---|
| **arm64-v8a** | **16,995,342 B = 17.0 MB** ✅ |
| armeabi-v7a | 15,230,577 B = 15.2 MB |
| x86_64 | 17,790,241 B = 17.8 MB |
| x86 | 17,993,245 B = 18.0 MB |

比预算的 21.3 MB 还小 4 MB，离 §13 的 25 MB 阈值有 8 MB 余量。
逐 artifact 的实测明细见 `docs/release-checklist.md`。

---

## §12 · 开发里程碑

每个里程碑都有可发布的形态。M1 结束时应用已经能作为纯手动打码工具上架。

### M1 — 骨架与手动打码

冷启动直接拉 Photo Picker、`ACTION_SEND` 入口与私有副本、解码与 EXIF 方向校正、大图降采样、画布手势（一指画框 / 二指导航 / 双击）、MaskPlan 与撤销重做栈、实色块渲染、品牌水印、导出到 MediaStore。

此阶段没有识别，因此 `MaskState` 实际只会出现 `MASKED`，导出拦截无从触发——它属于 M2。数据结构在 M1 就按三态建好。

> **出口**：能当一个可用的手动打码工具发出去，全程零权限弹窗。

### M2 — OCR 与规则层

`MlKitTextRecognizer` 接入、词级偏移映射（§5.3）、`RuleClassifier` 与全部 11 条文本规则（含 `enabledByDefault` 分层）、`CandidateMerger`、`OUTLINED` 态与三态切换 UI、导出拦截对话框。

> **出口**：200 张自建样本集上，默认打码类型召回率 ≥ 0.95；全类型圈出率 ≥ 0.95。

### M3 — 人脸与条码

两个 `RegionDetector` 实现接入并行管线，异常降级路径打通。

> **出口**：正脸召回 ≥ 0.98，二维码 100%。

### M4 — 样式全集与抹除

像素化（含块尺寸下限）、模糊、马克笔、Emoji、抹除与方差降级，用途水印（在证件照上叠「仅供办理 XX 使用」，与 §9.3 的品牌水印是两回事）。

> **出口**：导出图通过去码工具验证不可还原。

### M5 — 批量与商业化

`ACTION_SEND_MULTIPLE` 逐张流、记住上次样式、首次安装引导页、Play Billing 接入与去水印解锁、离线购买态缓存。

> **出口**：达到 §13 的全部指标。

---

## §13 · 验收标准

**样本集需要自建**：200 张真实截图，覆盖聊天记录、订单页、证件照、快递单、银行 app、邮件列表六类，人工标注全部敏感实体的位置。这份样本集是后续每次改动的回归基准，比任何公开数据集都重要。

| 维度 | 指标 | 阈值 |
|---|---|---|
| 圈出率 | 标注的敏感实体被 `MASKED` 或 `OUTLINED` 覆盖的比例 | ≥ 0.95 |
| 默认打码召回率 | 默认打码类型的标注实体被 `MASKED` 覆盖的比例 | ≥ 0.95 |
| 精确率 | `MASKED` 区域中确实覆盖敏感内容的比例 | ≥ 0.70 |
| 识别延迟 | 1080×2400 截图，中端机，选中照片到候选出现 | < 800 ms |
| 冷启动 | 点图标到 Photo Picker 可交互 | < 1.2 s |
| 下发体积 | arm64-v8a split | ≤ 25 MB |
| 运行期敏感权限 | 合并后 manifest 中除 `com.android.vending.BILLING` 外的 uses-permission 条数 | 0 |
| 网络请求 | 选图 → 识别 → 编辑 → 导出全流程抓包（不含用户主动发起的购买流程），运行期发出的请求数 | 0 |
| 导出拦截 | `pendingCount > 0` 时对话框触发率 | 100% |
| 水印避让 | 免费导出的水印与任一遮罩区域相交的比例 | 0% |
| 元数据 | 导出图 `ExifInterface` 读到 GPS / 型号 / 时间戳的条数 | 0 |
| 不可还原 | 像素化与实色块导出图 | 去码工具验证不可读 |

**权限指标做成 CI 断言**：解析 `:app:processReleaseManifest` 的合并输出，除 BILLING 外出现任何 `uses-permission` 即构建失败。这是本方案的核心承诺，不能靠人工检查守。

**精确率阈值刻意设得低**：多遮一块只是麻烦，漏遮一块是事故。这个不对称是产品定位决定的，不是妥协。分层默认（§6）看似削弱了这条原则，但它靠导出拦截（§7.4）兜底——分层只改变默认状态，不改变「用户导出前必然被告知」这个保证。

---

## §14 · 预留的接口位

下面每一项都不需要改引擎、UI 或数据模型。这是 §4 那几个接口存在的全部理由。

| 想加的能力 | 接哪里 | 改动量 | 连带代价 |
|---|---|---|---|
| 换成自带 OCR 模型<br>PP-OCRv5 + ONNX Runtime | `TextRecognizer` | 新增一个实现 + §4.4 换一行 | APK +37 MB；需自己实现 DB 后处理 |
| 人名 / 地名 / 机构 NER | `SensitivityClassifier` | 新增实现 + List 加一项 | 模型体积；UI 需标注实验性 |
| Gemini Nano 语义判定 | `SensitivityClassifier` | 同上，`isAvailable()` 里做设备探测 | 仅前台可用；有推理与电池配额 |
| ML Kit Entity Extraction | `SensitivityClassifier` | 同上 | 会引入 `INTERNET` 权限，破坏零权限定位 |
| 车牌检测 | `RegionDetector` | 新增实现 + List 加一项 | 模型体积 |
| NPU 加速 | 自带 Recognizer 内部 | 换 execution provider，不触及接口 | 仅 arm64；需多目标编译 |
| 抹除改用 inpainting 模型 | `MaskRenderer` | ERASE 分支换实现 | 模型体积；延迟上升 |
| 中日韩 OCR | `TextRecognizer` | 加脚本包，无代码改动 | 每脚本每架构 ≈4 MB |
| 逐项样式 | `MaskItem` 加 `style: MaskStyle?` | 数据模型加一个可空字段，渲染时 `?: plan.style` | 交互变两步，见 §7.5 |
| 大图分块导出 | `Exporter` 内部 | 不触及接口 | 实现复杂度；解除 §9.2 的 32 MP 上限 |
| 出国行版（无 GMS） | `TextRecognizer` / `RegionDetector` | 整体换成自带模型的实现 | 见第一行；这是接口分层的最大回报 |

---

## §15 · 待验证项

这些是方案里基于文档推断、需要实机确认的地方。建议在 M1 期间就全部验掉，因为其中任何一条不成立都会影响架构。

1. **ML Kit bundled 是否真的完全不需要网络。** 官方文档说 bundled 模型编译进 app、安装即用、无需联网。零权限方案完全建立在这条上——拿一台断网设备跑完整流程，抓包确认零请求。

   **实测（2026-09-03，模拟器 Medium_Phone / android-37，ML Kit text-recognition 16.0.1）**：**成立**。
   飞行模式 + wifi/data 全关（`Active default network: none`）下，`MlKitTextRecognizerTest` 5 条全绿，
   识别结果与联网时一致。APK 里能看到 `libmlkit_google_ocr_pipeline.so`，模型确实随包发行。

   **但发现一个计划没预料到的权限泄漏，已修**：bundled 的 `com.google.mlkit:text-recognition`
   传递依赖到 `com.google.android.datatransport:transport-backend-cct`（GMS 遥测上报后端），
   它把 `INTERNET` 与 `ACCESS_NETWORK_STATE` 合并进了本应用 manifest，`assertDebugNoRuntimePermissions` 当场阻断。
   泄漏的不是模型下载，是使用统计回传。处理办法是在 `AndroidManifest.xml` 里用
   `tools:node="remove"` 摘掉这两条——摘权限比 exclude 掉 artifact 更强：类还在（ML Kit 的日志代码
   直接引用它们，exclude 会 `NoClassDefFoundError`），但系统层面不给这个进程开网络，
   任何回传都拿不到 socket。摘除后识别功能不受任何影响，见上面的断网实测。

   **复验（2026-09-03，计划 04 出口）**：接上编辑器之后再验一遍，结论不变。
   飞行模式 + wifi/data 全关（`Active default network: none`）下跑完整
   instrumented 套件 25 条全绿，评测指标与联网时逐位一致
   （圈出率 / 召回率 / 精确率均 1.0，延迟 39ms）。
   已安装包的 `dumpsys package` 显示 `requested permissions` 只有
   `com.dama.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 一条，
   `runtime permissions` 为空——设备侧也确认了零权限，不只是合并 manifest 上的断言。

2. **零权限下的完整闭环。** Photo Picker 选图 → 编辑 → 写回相册，全程不弹任何权限框；再验一遍 `ACTION_SEND` 进来的 content URI 在 Activity 重建后是否仍可读（§7.1 的私有副本方案就是为此，但需实测确认必要性与时机）。

   **实测（2026-09-03，模拟器 Medium_Phone / android-37）**：闭环成立——Photo Picker 选图 → 画框 → 导出到
   `Pictures/DAMA/`，全程零权限对话框；导出件 600×900（原图分辨率）、无 EXIF 段、水印在右下且不压遮罩。
   从系统分享面板 `ACTION_SEND` 进来同样直接进编辑器（注意：`adb am start` 显式指定组件的合成 intent
   **不会**真正授予 URI 权限，会报 `SecurityException`，必须走真实分享面板才测得准）。
   **但 Activity 重建这条不成立**：开「不保留活动」后离开再回来，图片丢失、退回 Photo Picker。
   原因不是 URI 授权失效，而是重建路径根本没走到读 URI 那一步——私有副本的路径没有存进
   `onSaveInstanceState`，且 `EditorViewModel.onCleared()` 会清空整个 intake 目录。
   即：Task 6 的私有副本目前只防「原图被删/授权在同一实例内失效」，不防 Activity 重建。
   要覆盖后者，需把副本路径与 MaskPlan 一起持久化，并把 intake 目录的清理时机从 `onCleared` 挪到
   明确的「换图/导出成功」时点。**留到计划 04（三态编辑）一并处理，届时状态持久化本来就要做一遍。**

   **已修并实测（2026-09-03，计划 04）**：`Quad` / `MaskItem` / `MaskOptions` / `MaskPlan` 变成
   `Parcelable`，私有副本路径 + mimeType + 整棵 plan 走 `SavedStateHandle` 持久化。
   实测方式比「不保留活动」更狠：直接 `am kill com.dama.app`（pid 23296 → 进程消失 → 23556 新进程），
   重新启动后图片与整棵 plan 原样回来，**不重跑识别**。撤销栈刻意不持久化，
   重建后 undo 按钮如期是禁用态；重建后第一次点击才成为第一个可撤销动作。

   一处与 §15.2 原文的取舍不同：intake 目录的清理**只**挪到「换图」，没有加「导出成功」那一档——
   导出成功就删私有副本会让用户改个样式再导出一次直接失败。代价是会话结束后副本留在 `cacheDir`
   到下次换图为止，由系统按缓存回收，换来的是重建可恢复。

   恢复失败（副本确实被清掉了）时静默退回 Photo Picker：新增 `EditorUiState.restoring`，
   `EditorActivity` 在重建路径上等它落定再决定要不要拉相册，避免在图正要回来的那一瞬间弹出 Picker。

   **复验（2026-09-03，计划 07 Task 46，用「不保留活动」这条原文要求的方式）**：
   `settings put global always_finish_activities 1` 开启后走完整路径——
   引导页 → Picker 选一张含二维码的图 → 编辑器（二维码已自动成为黑块）→ 按 Home →
   回到 app：**图与整棵 plan 原样回来**，二维码仍是黑块，没有退回 Picker。
   这条与计划 04 的 `am kill` 复验结论一致，私有副本 + SavedStateHandle 的方案两种销毁方式都覆盖得住。

3. **冷启动直接拉 Photo Picker 的实际体验。** 会不会出现闪白、双层 Activity、或返回时留下空壳 Activity。若体验不佳，退路是加一个极简首屏（一个大按钮），但那会牺牲「打开就是相册」的即时感。

   **实测（2026-09-03，模拟器 Medium_Phone / android-37）**：体验可接受，**维持不加首屏**。
   点图标到 Picker 出现约 0.5s（`am start -W` TotalTime 536ms），中间是自家 Surface 的主题背景色，
   没有闪白；Picker 以底部大半屏 sheet 的形态压在自家窗口上，同一个 task 内两个 Activity，
   不是「双层 app」的观感。Picker 里按返回 / 点 ✕ → `uri == null` → `finish()`，
   任务栈里不留空壳，直接回到桌面。
   一处与预期不同：android-37 的 Photo Picker 单选也要点一次「Done」才回传，不是点中即回。
   这是系统 Picker 的行为，不在本应用控制范围内。

4. **Play Billing 合并后的权限清单。** 确认 manifest merger 只并入 `com.android.vending.BILLING` 一条，没有别的。这条直接决定 §13 的 CI 断言怎么写。

   **实测（2026-09-03，计划 07 Task 44，`com.android.billingclient:billing-ktx:8.0.0`）**：**成立**。
   加进依赖后 `assertDebugNoRuntimePermissions` 的输出是：

   ```
   权限断言通过：合并后 manifest 的 uses-permission =
     com.android.vending.BILLING, com.dama.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
   ```

   合并后 manifest 里的 `uses-permission` 就这两条，Billing 只并入了 `BILLING` 一条，
   没有夹带 `INTERNET`（购买流程的网络是 Play 商店进程发起的，不是本进程）。
   两条都不是运行期权限：`BILLING` 不弹授权框、不授予任何数据访问能力；
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 是 androidx.core 给本应用自己定义的
   `signature` 级权限。断言的允许名单逐条写死，不做前缀匹配，新冒出任何一条都会阻断构建。

5. **词级映射的实际粒度。** 用真实截图看 ML Kit 的 element 切分——如果卡号被拆成多个 element，或者标签和值被合成一个，§5.3 的遮罩范围会和预期不同。这个必须看真实数据，不能靠推断。

   **实测（2026-09-03，真实淘宝订单详情页截图 1206×2622，降采样到 640×1428 后识别）**：

   - **长数字串是一整个 element，不会被拆。** 19 位订单号 `5127402746064028833`（conf 0.95）与
     28 位支付宝交易号 `2026090323001114571431156787`（conf 0.92）各自独占一个 element，
     也各自独占一行。§5.3 的遮罩范围因此符合预期：命中整串就遮整串，不会出现只遮一半。
   - **标签和值不会粘成一个 element。** 视觉上左标签右值的行，ML Kit 分成不同的 line；
     即使同一 line 内（如 `2026-09-03 12:08:06`），日期与时间也是两个独立 element。
     所以「遮住值、保留标签」这件事在 element 粒度上是可做的。
   - **⚠️ bundled 拉丁识别器对中文完全无能，这是首版的硬边界。** 截图里全部中文标签
     （「订单信息」「支付宝交易号」「实付款」）都被识别成乱码
     （`KN9F22]N#ShAiA`、`7RfRBER`、`HË;M`），置信度 0.25–0.4。两个直接后果：
     1. `PhoneRule` 的中文提示词（订单/单号/流水/发票）在真实中文页面上**永远不会命中**，
        排除订单号误报这件事在中文场景下只能靠数字长度本身，不能靠上下文。
     2. 低置信度乱码串会照常喂进规则层，是误报的一个来源。`RuleClassifier` 目前不看
        `line.confidence`——**建议在计划 04 加一个置信度下限（如 < 0.5 的行不参与规则判定）**，
        本计划不改，因为那会牵动已经定稿的规则测试。
   - **⚠️ 实测到一例真实漏检：28 位支付宝交易号不被任何规则命中。**
     `card` 规则限 13–19 位、`tracking` 限 12/15/20/22 位，28 位落在所有规则之外；
     19 位订单号则因 Luhn 不通过被 `card` 正确排除（已验算）。
     这是「漏检是事故」原则下需要正视的缺口，但补法不属于本计划——
     记在这里，留给计划 04 的评测 harness 量化后再决定要不要加一条「长数字串」兜底规则。

   **两条建议都已在计划 04 落地（2026-09-03）**：

   - `RuleClassifier` 加了行置信度下限（`MIN_LINE_CONFIDENCE = 0.5`，可注入）。
     低于它的行不参与规则判定。阈值取得保守：ML Kit 对清晰拉丁文本给 0.8–0.99，
     0.5 离正常区间足够远；识别器不给置信度时 `MlKitTextRecognizer` 已回落到 0.9，
     不会被这条下限静默吃掉。
   - 补了 `longnum` 兜底规则 + 新枚举 `SensitiveKind.LONG_NUMBER`（显示名「长数字串」）。
     三条边界避免与已有规则抢同一串数字（合并只在同 kind 之间做，抢起来会在同一处叠出两个候选）：
     下限 16 位（E.164 电话最长 15 位，与 `PhoneRule` 完全不重叠）、排除 20/22 位
     （`tracking` 的 USPS 窗口）、排除 13–19 位里 Luhn 通过的（`card` 的地盘）。
     没有校验位，误报天然多，按 §6「默认按误报率划线」**默认仅圈出**，靠 §7.4 的导出拦截兜底。
     规则表因此从 11 条变成 12 条（8 打码 / 4 圈出）。
     实机验证：1080×1920 截图上 28 位交易号被圈成琥珀色虚线框并标注「长数字串」，
     导出拦截如实报「1 个网址、1 个长数字串、1 个 IP 地址」。

6. **像素化块尺寸下限是否足够。** 「短边/8 且不小于 12px」是一个工程估计值，需要用公开的去码工具实测校准，必要时上调。

   **实测（2026-09-03，计划 06 Task 40）：不够，而且不存在够用的值。像素化已降级为「外观优先，非安全」。**

   **方法一 · 模板泄漏探针（决定性）。** 用生产的 `PixelateRenderer` 渲染一行 16 位卡号，
   固定区域几何与块网格，只改其中一位数字，比较像素化后各块中心的灰度向量。
   若 10 个数字两两可区分，说明马赛克里还留着这一位的身份，
   知道字体字号的攻击者（Depix 的威胁模型，对已知 UI 的截图完全成立）可以逐位模板比对还原。

   | 参数 | 每字高块数 | 10 个数字两两可区分 | 最大块间灰度差 |
   |---|---|---|---|
   | **当前默认** divisor=8 / floor=12px，字号 40 / 60 / 96px | 3.3 / 5.0 / 7.7 | **45 / 45** | 245 |
   | 当前默认，字号 32px | 2.7 | 42 / 45 | 245 |
   | 当前默认，字号 24px | 2.0 | 39 / 45 | 239 |
   | floor=24px，字号 32px | 1.3 | 0 / 45 | 0 |
   | divisor=2 / floor=12px，字号 24 / 32 / 60px | 1.1 / 1.1 / 1.3 | 0 / 45 | ≤ 7 |

   **唯一不泄漏的区间是「每字高约 1 块」——那时马赛克在视觉上就是一个实色块。**
   把块调大到安全，像素化作为一种独立样式的意义就没了；保留马赛克的观感，它就必然泄漏。
   计划 06 Task 40 Step 3 原本写的补救（「上调 `MIN_BLOCK_PX` 直到还原失败」）不存在可用解。

   **方法二 · Depix 实跑（辅证）。** `github.com/spipm/Depix`，搜索图用同字体同字号同底色渲染的
   数字全 bigram 序列（攻击者知道这是卡号，只搜数字是更强的攻击）。
   40px 字号（12px 下限生效，每字高 3.3 块）：**未能还原**——5 个候选块里 4 个直接匹配，
   输出图无可读字符。这与探针一致：Depix 的成功还依赖块网格与整行上下文对齐，
   比「信息是否泄漏」这个下界要求更高，跑不出来不等于信息没漏。

   **处置**：`MaskStyleInfo.safety(PIXELATE)` 从 `IRREVERSIBLE` 改为 `COSMETIC`，
   `StyleBar` 下方显示「像素化是外观优先，非安全手段——已知字体的截图可被逐位还原」。
   块尺寸参数不动（改了也不安全，只会更难看）。
   `IrreversibilityTest` 里像素化那条保留，但语义改成「至少没盖漏」，不再是安全断言。

   **顺带修掉的一个相关缺口**：12px 下限与抹除的 4px 采样环是绝对像素常数，
   而预览画在降采样到 2048 的分析图上、导出画在原图上。长边超过 2048 的图上，
   预览的块占区域短边 24%、导出只有 12.5%——**预览比导出更糊，用户看到的比实际拿到的安全**。
   `MaskOptions` 增加 `renderScale`，两个调用方各自填入，渲染器按它折算这两个常数。

7. **大图导出的内存上限。** §9.2 的 32 MP 是估算值（32 MP × 4 byte ≈ 128 MB）。需在低端机上实测 OOM 边界，必要时下调。

   **实测（2026-09-03，计划 07 Task 46，模拟器 Medium_Phone / android-37.1 / arm64-v8a）**：
   **32 MP 这个上限站得住，不下调。** `LargeImageExportTest` 造一张 5656×5656
   （31,990,336 px，刚好压在 `EXPORT_MAX_PIXELS` 之下、不触发降采样）的图走完整导出：
   `downscaled = false`，输出 5656×5656 原分辨率，不 OOM。
   `android:largeHeap="true"` 是必须的——导出路径上源位图（128 MB）与输出位图同时活着。

   **边界值上有个容易踩的坑，测试里记一笔**：5657×5657 = 32,001,649 px 已经**超过**
   上限，会被降采样成 2828×2828。上限是像素数不是边长，写测试时别按边长凑整。

   **仍未验的是「低端机」这一半**：本次全部在模拟器上跑。真机堆上限比模拟器紧，
   上架前应在一台中端机上复跑这条测试。若真机 OOM，下调 `SourceImageLoader.EXPORT_MAX_PIXELS`
   并同步改 §9.2 的数值。

8. **水印避让在四角全被占时的表现。** 密集截图（如整屏聊天记录全打码）下四角可能都被遮罩占据，需确认加描边压在右下的效果是否可接受。

   **实测（2026-09-03，计划 07 Task 46）：可接受，维持现在的回退。**
   造一张 1080×2400 的整屏全打码聊天记录（顶部与底部各一条通栏黑块，四角因此全被占，
   中间 10 条左右交替的气泡全打成黑块），跑 `WatermarkDrawer.draw`：
   四档避让全部落空后回到右下，墨色按背景亮度自动翻成浅色，压在黑块上仍然清晰可读，
   观感上像一个正常的角标，不像画错了。

   **一个必须写清楚的口径问题**：§13 的「水印与遮罩相交比例 = 0%」这条指标，
   严格说只在**四角还有空位时**成立。四角全被占是设计上明确允许的例外——
   §9.3 的避让顺序最后一档就是「回右下并加不透明描边」。
   这不构成信息泄漏：水印画在所有遮罩**之后**，压上去只会盖住黑块的一部分，
   不会露出底下的像素。`WatermarkDrawerTest` 的
   `all four corners occupied falls back to bottom right with an outline` 与
   `drawing never touches a masked region` 两条分别守这两种情形。

9. **各 artifact 的当前版本与体积。** 本文引用的体积来自 ML Kit 官方文档，版本来自官方示例工程，两者都会随版本变化，以 release notes 为准。

   **实测（2026-09-03，计划 07 Task 46，release bundle）**：见 §11 包体预算表下方的
   「实测」一列，以及 `docs/release-checklist.md` 的下发体积明细。
   结论：**arm64-v8a 下发 16,995,342 B = 17.0 MB，低于 §11 的 ≈21.3 MB 预算，
   离 25 MB 阈值有 8 MB 余量。**

   未压缩的原生库比预算行大得多（OCR 11.06 MB / 人脸 8.52 MB / 条码 4.95 MB，
   预算写的是 4.0 / 6.9 / 2.4），但下发计的是压缩后体积，合计反而更小。
   `libs.versions.toml` 里的实际版本：`text-recognition 16.0.1`、`face-detection 16.1.7`、
   `barcode-scanning 17.3.0`、`libphonenumber 8.13.52`、`billing-ktx 8.0.0`、
   `datastore-preferences 1.1.7`、Compose BOM `2025.06.01`。

---

## 参考

- ML Kit Text Recognition v2 on Android
- ML Kit Face Detection on Android
- ML Kit Barcode Scanning on Android
- ML Kit model installation paths
- ML Kit release notes
- ML Kit 官方示例的依赖清单
- Access media files from shared storage
- Android photo picker
- Google Play Billing Library
