# 有码 · 可切换识别方案设计

对 `docs/superpowers/specs/2026-09-02-youma-android-design.md` 的增补。
本文只改识别层的**装配方式**与新增一个设置界面，不改 §0 的零权限承诺、
不改 §4 的四个接口、不改编辑与导出的任何行为。

## 起因

真机（Android，淘宝订单详情页截图）跑出来三个问题，根因各不相同：

| 现象 | 根因 | 归属 |
|---|---|---|
| 商品图被框成「条码」并**默认打码** | `enableAllPotentialBarcodes()` 把解不出内容的疑似条码也报出来，印花、格纹、布料纹理都会撞上 | 条码策略 |
| 日期时间没被框出来 | 规则表 12 条里没有日期时间这一条。OCR 认得出，是判定层没人管 | 规则表 |
| 中文标签全是乱码（`支付宝交易号` → `7RfRBER`） | bundled 拉丁识别器对中文无能（原 spec §15.5 已实测记录） | OCR 后端 |

三个根因分属三层，**任何单一参数都调不掉**。所以不调参数，改成把每一层
的可选项都内嵌进包，交给用户在设置里逐项切换。

## 目标

1. OCR 后端、条码策略、人脸策略、规则表逐条状态，全部在 app 内可切；
2. 切换后当前这张图立即重跑，**保留用户已画的手动框**，且整次重跑可一键撤销；
3. 全过程零网络、零新增权限，arm64-v8a 下发体积仍 ≤ 25 MB。

## 非目标

- 不引入任何需要下载的模型（ML Kit Entity Extraction、unbundled 变体一律排除）；
- 不做方案预设/命名档位——逐项开关的意义就在于变量隔离，预设会把变量重新绑在一起；
- 不做识别结果的 A/B 并排对比视图。切换即重跑加撤销栈，已经够用。

---

## §1 · RecognitionConfig

四根轴 + 一张规则覆盖表，一个 data class 装完：

```kotlin
enum class TextEngineOption { LATIN, CHINESE, BOTH }
enum class BarcodeOption    { LOOSE, STRICT, OFF }
enum class FaceOption       { FAST, ACCURATE, OFF }
enum class RuleState        { OFF, OUTLINED, MASKED }

data class RecognitionConfig(
    val textEngine: TextEngineOption = TextEngineOption.BOTH,
    val barcode:    BarcodeOption    = BarcodeOption.LOOSE,
    val face:       FaceOption       = FaceOption.FAST,
    /** 只存与出厂默认不同的项。空表 = 全用 RuleCatalog 的出厂态。 */
    val ruleOverrides: Map<String, RuleState> = emptyMap(),
)
```

`ruleOverrides` 只存差异，不存全表。全表会让「出厂默认改了之后老安装读到旧值」
变成一个需要迁移的问题；只存差异则新规则天然继承新默认。

### 出厂默认为什么是这几个值

**`textEngine = BOTH`**：两个识别器并行跑，结果并集交给已有的 `CandidateMerger`
去重。这一档是**唯一不可能比现状更差**的默认——中文识别器万一在真机上表现异常，
拉丁那条 lane 仍然产出今天这个版本产出的一切。代价是多一次推理，但两条 lane 是
并行的，延迟取 `max` 而不是 `sum`（现状 143 ms，预期仍远低于 §13 的 800 ms 阈值）。

**`barcode = LOOSE`，但语义改了**：`enableAllPotentialBarcodes()` 保留，因为
「漏检是事故」——倾斜的收款码丢掉是真事故（自造 36 张评测图里默认配置丢 6 张）。
改的是解不出内容的那部分的**默认状态**：

| 条码 | 旧行为 | 新行为 |
|---|---|---|
| 解得出内容 | MASKED | MASKED |
| 解不出内容（potential） | MASKED | **OUTLINED** |

误报因此从「商品图被涂黑」降级成「商品图上有个琥珀色虚线框」，而导出拦截（§7.4）
保证用户在导出前必然被告知。既不漏，也不毁图——这正是三态设计存在的理由。

**`face = FAST`**、**规则出厂态 = 现状的 8 打码 / 4 圈出 + 新增 datetime 仅圈出**：
维持现状，改动面越小越容易定位问题。

---

## §2 · 装配点

`buildEngine` 从无参变成吃 config，**全应用仍然只有这一处知道用的是哪家模型**：

```kotlin
fun buildEngine(context: Context, config: RecognitionConfig): RedactionEngine
```

`EditorViewModel` 的构造参数从 `engine: RedactionEngine` 换成
`engineProvider: (RecognitionConfig) -> RedactionEngine`。config 变了就丢掉旧
engine 建新的——ML Kit 的 client 全是 `by lazy`，没被选中的后端不会初始化，
所以「中文模型在包里」不等于「中文模型在内存里」。

不选常驻多引擎的理由：这个应用已经 `largeHeap="true"` 在跟 32 MP 大图抢内存，
而低端机的导出 OOM 边界（原 spec §15.7）**至今没在真机上量过**。在没摸清的边界上
再压一份模型，是拿一个已知风险换一个不痛不痒的切换延迟。

### 各轴怎么落

| 轴 | 选项 | 实现 |
|---|---|---|
| OCR | LATIN / CHINESE / BOTH | `MlKitTextRecognizer(script)`；BOTH 用 `CompositeTextRecognizer` 并行跑两个再拼行 |
| 条码 | LOOSE / STRICT / OFF | `MlKitBarcodeDetector(strategy)`；OFF 时不进 `regionDetectors` 列表 |
| 人脸 | FAST / ACCURATE / OFF | `MlKitFaceDetector(mode)`；OFF 同上 |
| 规则 | 逐条 OFF / OUTLINED / MASKED | `RuleCatalog.rulesFor(config)`：OFF 的规则不进列表，其余包一层把 `enabledByDefault` 覆写成对应值 |

`CompositeTextRecognizer` 只做一件事：并行跑 N 个 recognizer，把行拼起来。
它不去重——去重是 `CandidateMerger` 的职责，两个识别器在同一块文字上各出一个
候选时，IoU 去重本来就会合并掉，不需要在 OCR 层重复实现一遍。

---

## §3 · 新规则 datetime

```
id = "datetime"    kind = SensitiveKind.DATETIME    出厂 = OUTLINED
```

覆盖三类写法：`2026-09-03 12:08:06`、`2026/9/3`、`2026年9月3日`，以及独立的
`12:08` / `12:08:06`。**出厂仅圈出**，因为时间在截图里无处不在（状态栏时钟、
每一条聊天记录），默认打码会让用户一直在跟 app 对着干——这正是 §6「按误报率
划线，不按危害划线」的原话。

需要同时新增 `SensitiveKind.DATETIME` 与它的显示名「日期时间」。

---

## §4 · 持久化

`SettingsStore` 加四个键：三个枚举名（字符串），一个规则覆盖表。
覆盖表序列化成 `id:STATE` 的分号分隔串——只有十几条、只存差异，
为它引 JSON 序列化不划算。

读回来时**逐项容错**：认不出的枚举名退回该项的出厂默认，认不出的规则 id
直接丢弃。整份设置读不出来时退回 `RecognitionConfig()`，绝不在启动路径上抛。
理由与既有的 `lastStyle` 一致：枚举名是持久化格式的一部分，删一个选项不该让
老安装打不开。

---

## §5 · 设置界面

顶栏右上角加一个齿轮（在「用途水印」左边、购买入口右边），点开是**全屏页**，
不是对话框——十几个规则开关塞不进对话框。

页面结构：

```
识别设置                                    [返回]

文字识别      ( ) 拉丁   ( ) 中文   (•) 两者并跑
条码          (•) 宽松   ( ) 严格   ( ) 关闭
人脸          (•) 快速   ( ) 精确   ( ) 关闭

规则                                    [全部恢复默认]
  银行卡号     关 | 仅圈出 | ●打码
  ...（13 条，每条一个三段选择器）
```

每根轴下面一行灰字说明它的代价（例如条码「宽松：解不出内容的条码也框出来，
但只圈不打码」）。不写"推荐"，因为哪个好正是用户要自己看的。

**改动即时生效**：不设"应用"按钮。每次改动写 DataStore，`EditorViewModel`
观察 config flow，变化时重跑。

---

## §6 · 切换后的重跑语义

```
config 变化
  → undoStack.push(当前 plan)          整次重跑压一个快照，可一键撤回对比
  → 重建 engine
  → 用新 engine 重跑 analyze(当前图)
  → plan.items = 新候选 + 原 plan 里 kind == MANUAL 的项
```

丢掉的是用户对**规则候选**做过的三态切换。这是有意的：新方案产出的候选和旧的
不是同一批，把旧的切换态往新候选上贴没有正确答案。撤销栈兜住了这一点——
不满意按一下撤销就回到切换前。

没有图片时（还在 Picker 阶段）改设置只写 DataStore，不触发任何重跑。

---

## §7 · 对 §13 验收指标的影响

| 指标 | 阈值 | 改动后 | 说明 |
|---|---|---|---|
| 下发体积 arm64-v8a | ≤ 25 MB | **17.65 MB**（实测） | 中文包只 +657 KB：它与拉丁共用同一套 OCR pipeline 与检测器，只多一个 `Hani_ctc/lstm_model.fb` |
| 运行期敏感权限 | 0 | **0**（实测，断言已过） | 中文包不引入任何新权限 |
| 网络请求 | 0 | 0 | bundled 变体，模型随包发行 |
| 识别延迟 | < 800 ms | 待实测 | BOTH 档两条 lane 并行，预期取 max 而非 sum |

前两行是加完依赖后现跑的实测值，不是估算。延迟需要真机复核。

---

## §8 · 测试

| 层 | 测什么 |
|---|---|
| 单测 | config 的序列化往返与容错；`RuleCatalog` 的三态覆盖（OFF 不进表、OUTLINED/MASKED 正确映射到 `enabledByDefault`、未知 id 被丢弃）；datetime 规则的正例与反例（不能撞上价格 `¥156`、版本号 `v1.2.3`）；条码候选的 decodable → MASKED / potential → OUTLINED 映射 |
| 单测（ViewModel） | 切 config 触发重跑；MANUAL 项活下来；重跑前压了快照且撤销能回到切换前；无图时不重跑 |
| instrumented | 中文识别器在真机上确实产出中文行（这一条是 BOTH 默认档的成立前提，**必须上设备**） |

条码那条映射逻辑要从 `MlKitBarcodeDetector` 里抽成纯函数才测得了——ML Kit 的
`Barcode` 对象造不出来，但「给定 quad 与 decodable 标志，产出什么状态的候选」
这件事本身不需要 ML Kit。

---

## §9 · 明确不做

- **不改条码检测器的任何阈值参数**。LOOSE/STRICT 是 ML Kit 给的两种模式，
  中间没有可调的旋钮，也不打算自己造一个。
- **不加人名/地址 NER**。那需要模型，属于原 spec §14 的接口位，不在本次范围。
- **不把设置暴露给批量流的每一张**。config 是全局的，跟 `MaskPlan.style`
  一样是全局单值。
