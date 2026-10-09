# 有码 · 设置页改版（目录式一级页、人脸与条码的处理方式）

对 `2026-09-04-yomark-recognition-profiles-design.md` §5「设置界面」的改写，起因是一轮真机反馈：

1. 层级对不齐：「规则」收在二级页里，文字识别、条码、人脸、AI 复查、识别动效的选项却直接铺在一级页上；
2. 人脸只能选识别模式（快速 / 精确 / 关闭），没法设「认出来之后打不打码」；条码同理；
3. 想要 Android 16 系统设置的样子：标题前有图标，带点颜色。

## 一级页是目录

一级页上不摆任何选项，每一项点进去是一页，下面一行小字写它眼下的状态：

| 组 | 项 | 图标（圆底色） | 小字 |
|---|---|---|---|
| 识别到了怎么办 | 文字 | TextFields（蓝） | 打码 11 类 · 圈出 5 类（有关掉的再加「关闭 n 类」） |
| | 人脸 | Face（橙） | 打码 · 快速识别；关掉时只写「关闭」 |
| | 条码 | QrCode2（绿） | 打码 · 严格识别 |
| 怎么识别、导出、样子 | 文字识别 | DocumentScanner（紫） | PP-OCR · AI 复查开启 |
| | AI | AutoAwesome（黄） | Gemini Nano 状态（2026-10-09 增补，见下文「AI 页」） |
| | 导出 | IosShare（青） | 导出前提醒已开启 |
| | 外观 | Palette（粉） | 主题色、识别动效 |
| | 恢复默认设置 | SettingsBackupRestore（灰） | 除外观外，全部恢复出厂值 |

二级页：

- **文字**：原来的「规则」页。16 类每类一行，行首一个单色图标，行尾「关 / 圈出 / 打码」三选一；右上角「恢复默认」只管这一页。
- **人脸**、**条码**：上面一组「识别到人脸（条码）时」：打码 / 仅圈出 / 关闭；下面一组「识别模式」：快速 / 精确、严格 / 宽松。关闭时识别模式灰掉。
- **文字识别**：识别引擎（PP-OCR / ML Kit 英文 / 中文 / 中英）与 AI 复查开关。两项都是「怎么认字」；认出来怎么处理在「文字」那页。
- **AI**（2026-10-09 增补）：眼下没有可设的项，只显示本机的 Gemini Nano，见下文「AI 页」。
- **导出**：导出前提醒。它原先放在规则页顶上，理由是它兜的正是「圈出」这一态；现在人脸、条码也有圈出，它就不只属于文字了。
- **外观**：主题色（跟随系统 + 七个预设色块）与识别动效（扫光 / 磨砂）。

每页仍然是一个 Activity（二级页共用 `SettingsPageActivity`，按参数决定打开哪一页），不拦系统返回，预见式返回照旧是跨 Activity 的动画。

## 人脸、条码的处理方式

`RecognitionConfig` 加两项，和文字规则同样的三态（`RuleState`）：

```kotlin
val barcodeState: RuleState = RuleState.MASKED
val faceState: RuleState = RuleState.MASKED
```

- OFF：检测器不进 `regionDetectors`，根本不跑（原来 `FaceOption.OFF` / `BarcodeOption.OFF` 的效果）；
- MASKED / OUTLINED：决定候选的 `enabledByDefault`。条码的 MASKED 只管解得出内容的那些，解不出的疑似条码（宽松模式才有）无论如何只圈出，§1 的那条不变；
- 出厂都是 MASKED，出厂行为不变。

识别模式里的 OFF 随之删掉：`FaceOption { FAST, ACCURATE }`、`BarcodeOption { STRICT, LOOSE }`。「关」只在一个地方。

人脸候选的构造抽成了 `FaceCandidates.from`（与 `BarcodeCandidates` 同理：ML Kit 的 Face 造不出来），好单测。

### 持久化与迁移

新键 `recognition_face_state`、`recognition_barcode_state`，存枚举名。老安装里识别模式存着 `OFF`、又还没有处理方式这一项的，读成处理方式 OFF、识别模式退回出厂值——不迁的话，关掉过人脸的用户升级后会突然又被打码。存过处理方式之后以它为准。

## AI 页（2026-10-09 增补）

先放一页「AI」，里面暂时不放设置项，只回答一件事：本机有没有 Gemini Nano，有的话是哪一版。

一行「Gemini Nano」，小字先写版本再写模型在不在本机：

| 情况 | 小字 |
|---|---|
| 还在问 AICore | 正在检测… |
| 机型不支持、没装 AICore、AICore 报错或没应答 | 本机没有（报了错的，下面的说明里附上错误码） |
| 支持，模型还没下 / 正在下 / 已下好 | nano-v3 · 未下载 / 下载中 / 已就绪；版本问不到写「版本未知」 |

- 版本取 ML Kit 的 `getBaseModelName()`，就是官方设备列表里的 nano-v2、nano-v3、nano-v4 那一级（v4 会细到 nano-v4-fast / nano-v4-full）。不同版本对同一段提示词的回答可能不一样，排查 AI 复查的效果时先看这个；
- 先 `checkStatus()`，不是 UNAVAILABLE 才问版本：不支持的机型上 ML Kit 问版本只会抛异常。两步各限时 10 秒，AICore 的服务连接挂住时页面不能一直停在「正在检测」；
- 只看不下：不像编辑页探测时的 `NanoClient.ready()` 那样顺手请 AICore 去下载，打开设置看一眼不该引来一次模型下载。未下载时说明里写清：开着 AI 复查打开一张图，编辑页会请系统去下；
- 进页时问一次（`produceState`），不轮询；
- 一级页「AI」那一行的小字固定写「Gemini Nano 状态」，不写实时结果：那得每次打开设置都去绑一次 AICore 的服务，点进去再看就够了；
- 图标用 AutoAwesome，与「文字识别」页里 AI 复查那一行同一个；圆底取黄色（种子色 #FBBC04），夹在紫（文字识别）与青（导出）之间分得开，也和橙（人脸）拉开了色相。

## 恢复默认设置

范围按页面划：文字、人脸、条码、文字识别、导出恢复出厂值，外观（主题色、识别动效）不动。原先「全部恢复默认」会把识别动效也拨回扫光，现在识别动效和主题色在同一页，一个动一个不动说不通。

做法是删键，不是写一份当下的出厂值：缺省就是出厂值，和新装一样，出厂值以后再改也不会被冻住（与 `ruleOverrides` 只存差异同一条理由）。

它在一级页上就是普通的一行，点了先弹对话框确认，误点不会把设置清掉。

## 样式（照 Android 16 的系统设置）

- 页面底色 `surfaceContainer`，行是 `surfaceBright` 的卡片。同一组的行之间留 2 dp 的缝，组的外角 20 dp、组内相邻处 4 dp，看上去是一整块被切开的卡片；
- 大标题栏（`LargeTopAppBar`），列表往上滚时收起；
- 一级页每项一个 40 dp 的彩色圆底图标。颜色由 `tools/theme/gen_settings_icon_colors.mjs` 生成：每项一个固定色相，圆底取 Material Color Utilities 色调 90、彩度不超过 24，图标取色调 30。色相不随主题色变，认图标靠的就是「文字是蓝的、人脸是橙的」；明度和 primaryContainer 一档，换哪个主题色都不跳；
- 二级页：分组标题用主题色的小字；单选是行首圆点、整行可点；开关开着时滑块里画对勾（对勾用 primary：预设主题色里 onPrimaryContainer 是白的，画在白滑块上看不见）；
- 文字规则的三态是一排连在一起的按钮（Material 3 Expressive 的 connected button group）：两端半圆、相邻处小圆角，选中的那个变成整颗胶囊、填主题色。Compose Material 3 1.3 里还没有这个组件，自己画的；
- 一级页不画右箭头，Android 的设置首页也不画。

## 测试

| 层 | 测什么 |
|---|---|
| 单测 | 出厂人脸、条码都打码；`BarcodeCandidates` 的「仅圈出」与「疑似条码永远只圈出」；`FaceCandidates` 的处理方式与外扩 |
| 单测（DataStore） | 新两项的往返；老安装 OFF 的迁移；存过处理方式后不再迁；恢复默认清掉识别方案与导出提醒、不动外观、连老 OFF 一起清 |
| 单测 | Gemini Nano 状态的探测（`NanoStatusTest`）：各状态与版本、不支持时不问版本、报错带错误码、不应答时放弃、版本问不到时状态照报 |
| 单测（Compose，Robolectric） | 一级页只有目录、小字跟着状态走、点哪项开哪页、恢复默认先问；AI 页各状态的小字与说明；各二级页点选发出正确的新值；人脸关闭时识别模式点不动；系统栏避让与不拦返回 |

原先在 androidTest 里的设置页测试挪进了 Robolectric 单测：它不需要真机，放在 androidTest 里 CI 根本不跑。
