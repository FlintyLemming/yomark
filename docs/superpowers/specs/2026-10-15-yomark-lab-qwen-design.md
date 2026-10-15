# 有码 · 实验室：本地导入的 Qwen3.5 判断打码区域

新增的实验功能，挂在设置的「实验室」页（2026-10-15 新建，眼下只有「聊天记录」）。
相关：`2026-09-30-yomark-ppocr-gemini-nano-design.md`（AI 增强识别）、`docs/on-device-model-research.md`（Qwen3.5 的桌面实测，以及「不随包大模型」的决定）。

## 起因

规则认的是形状，认不出语义。AI 增强识别只在有 Gemini Nano 的机型上有。
10-03 的桌面实测里，Qwen3.5-0.8B 看截图认出了 22 处里的 14 处，0 误报，当时因为体积大没有随包。

用户决定加一个实验功能，**完全由 Qwen3.5-0.8B 判断打码区域**：文字，以及人脸、条码、聊天头像，都由模型决定。
模型不随包，由用户从本地导入。

## 决定

| 项 | 决定 | 理由 |
|---|---|---|
| 入口 | 设置 → 实验室，在「聊天记录」下面加一组 | 实验室页就是放出厂关着的功能的 |
| 模型来源 | 用户导入两个 GGUF 文件（模型 + mmproj）；不随包，应用也不下载 | 应用没有网络权限；延续 10-03「不随包大模型」 |
| 推理框架 | llama.cpp（libllama + libmtmd），自写 JNI，固定提交，只用 CPU | 见下文「推理框架」 |
| 判断范围 | 开着时**规则、人脸检测、条码检测、聊天版面分析都不跑**，遮哪里全由模型定 | 用户决定。也是变量隔离：看到的就是模型自己的本事 |
| 文字怎么落到像素上 | 模型抄出原文（`类型\|原文`），app 在文字识别的行里找到它，用 OCR 的字框（PP-OCR 精确到字，ML Kit 精确到词） | 0.8B 读字强（OCRBench 79.1），同时框多个目标弱（ODInW13 33.2）；找不到出处的原文自动丢掉 |
| 人脸、条码、聊天头像 | 模型输出 `bbox_2d`（0–1000 的相对坐标），换算回图上，再外扩一圈 | 用户决定。Qwen3-VL 一脉的框就是这个格式 |
| 文字识别 | 照旧跑，只当尺子；先于模型跑完 | 模型给不出字级的框；转了直角的页要靠它先认出方向 |
| 问哪几类 | 只问设置里开着的：「文字」里开着的类型；人脸、条码各看自己的开关；「聊天记录」开着才问头像、昵称和聊天标题 | 和 AI 增强识别同一条：不能绕过用户的设置 |
| 这时不起作用的设置 | 人脸、条码的识别模式，「没有旁证的人名」，「AI 增强识别」：灰掉，改了也不重跑 | 它们管的是规则和 ML Kit，这时都不跑 |
| AI 增强识别 | 实验室开着时不出现 | 两个模型的结果叠在一起，分不清是谁认出来的 |
| 何时跑 | 打开图片就跑，识别动效一直放到模型答完 | 它就是这张图的识别本身，不是第二遍 |
| 没跑完 | 按两句分别记；没答完的那一句常驻提示、可以重试；导出前先问 | 同 `refine()` 的理由：不能让用户以为这一页是干净的 |
| 进程 | 推理放进 `:lab` 进程里的绑定服务 | 要占 1 GB 以上的原生内存（桌面实测峰值 2.2 GB，16k 上下文）；崩溃、内存不够时不带走编辑器；取消就是结束这个进程 |
| 设备 | arm64-v8a（要有 dotprod 和 fp16），x86_64（要有 AVX2、FMA、F16C、BMI2）；总内存门槛见「原生库与构建」；其余设备不显示这一组 | 32 位放不下；指令集是编译基线，不满足的机器会崩 |
| 新增权限 | 无 | 导入走系统文件选择器；不导出的服务放在独立进程里不需要权限；只用 CPU，不需要 `uses-native-library` |

运行期敏感权限仍为 0，合并后的 manifest 里仍然没有 `INTERNET`。

## 推理框架

2026-10-15 对照源码、发布包和模型仓库核对过。要求有四条：能让 0.8B 看清一张长截图，能加载用户导入的文件，能在 x86_64 模拟器上跑，不联网。

| 框架 | 看截图 | 接进 yomark | 结论 |
|---|---|---|---|
| **llama.cpp** | 2026-02 起主线支持 Qwen3.5 的文字和看图（PR #19468）；保留交错的多轴旋转位置编码；按比例缩放，图像 token 上限可调 | 没有现成带看图的 Android 库，要自写 JNI；arm64 + x86_64；按同样配方的 `com.arm:ai-chat` 实测每个 ABI 约 12 MB；libllama、ggml、mtmd 里没有联网代码 | **选它**。10-03 桌面实测用的就是它，导入的也是同样两个文件 |
| MNN | 支持，阿里官方转好的 7 个文件（542 MB） | 没有 Maven 包；没有 x86_64 构建，上游也不测；不能中途取消；导出工具和运行库版本对不上时结果错但不报错；连续请求的崩溃 10-13 才修进 master | 备选。速度不够时拿来比 |
| 现有 ORT 上自写生成循环 | onnx-community 有导出好的模型，算子在 CPU 上都有 | 不加新的 .so，JVM 上能端到端测；但缩图切块、分词、多轴位置编码、递归状态传递约 1000 行 Kotlin 全得自己写，写错了不报错 | 备选 |
| LiteRT-LM | 只有社区做的看图包：**拉伸成 512×512**，位置编码压成一维 | 需要 Kotlin 2.3 以上（现在是 2.2.21）；0.18.0 遇到「￥」会崩，修复没进这一版；Tensor G4 的 CPU 上慢（Qwen3-0.6B 预填 96 tok/s，llama.cpp 241） | 不选 |
| ONNX Runtime GenAI | 运行时支持 | AAR 不在 Maven；自带 28.6 MB 的遥测库 libmat.so，按 4 KB 对齐，2027-02-01 起过不了 Play 的 16 KB 检查；会合并进 INTERNET | 不选 |

其余几家也排除了：ExecuTorch 的 Qwen3.5 只能读文字；MLC 把截图拉伸成 448×448，只能用 GPU，模拟器跑不了；ncnn_llm 是社区项目，没有 Android 构建；Cactus 默认上报遥测，商用许可受限；GenieX 只支持骁龙，Qwen3.5 只能读文字。

GPU 先不做：
- llama.cpp 的 Vulkan 在子群大小为 16 的 GPU（Mali）上矩阵乘结果有错，与模型无关；PR 作者在 Pixel 9 上用 test-backend-ops 复现了（ggml-org/llama.cpp#28637，修复 PR #30106 未合）。有人在 Pixel 9 Pro 的 Vulkan 上跑 Qwen3.5-0.8B，输出就是乱码（leehack/llamadart#948）；
- OpenCL 只认 Adreno；
- 在 Tensor G4 上，llama.cpp 的 GPU 本来就比 CPU 慢（Qwen3-0.6B 预填 76 vs 241 tok/s）。

## 识别流程

1. `buildEngine` 先问 `labActive(config, modelPresent, deviceSupported)`（internal 纯函数）：开关开着、模型文件在、设备支持，三样都满足才造实验室的引擎，否则造普通引擎。
   实验室的引擎：文字识别照旧（设置里选的引擎，出厂 PP-OCR）；`classifiers`、`regionDetectors`、`pageAnalyzers`、`refiners` 都是空的；`RedactionEngine` 新增构造参数 `judge: LabJudge? = null`，放 `QwenJudge`。
2. 先跑文字识别（一两秒），再跑模型：
   - 由文字行算出 `ReadingFrame`。`quarterTurns ≠ 0`（竖着拍的证件照这类）时，先把图转正再送模型，框用 `Upright.toSource` 映射回来。`Upright` 从 `MlKitFaceDetector.kt` 挪到 `core/geometry`；
   - `judge.judge(image, lines, frame)` 返回 `LabVerdict(candidates, outcome)`。它自己的失败不走「降级为空」，而是写进 outcome；
   - `AnalysisResult` 加一项 `lab: LabOutcome? = null`，普通引擎为 null。
3. 缩图：app 照 mtmd 的 `calc_size_preserved_ratio` 算目标宽高。两边各按 32 四舍五入；乘积超过 1024 个 token 时，按 `beta = √(h·w / (1024·32²))` 缩小，再向下取整到 32 的倍数。然后直接拉伸到这个尺寸，不留边也不裁。
   - 这样 mtmd 收到的尺寸就是它自己会算出的尺寸，原样拷贝，不会补黑边，0–1000 坐标两轴各乘原图宽高就是准的。照抄的理由：mtmd 处理 QWEN3VL 时默认按比例缩放后居中补黑边，模型的坐标相对的是补了边的画布；
   - 例：1080×2340 → 672×1504，987 个 token。mtmd 的 `image_max_tokens` 也设 1024 兜底；
   - 小图不放大。上游说 Qwen-VL 低于 1024 个图像 token 时框不准（ggml-org/llama.cpp#16842），放不放大由评测定。
4. 一张图只编码一次，问两句：先问文字，再问区域。
   - 第一句之前用 `llama_state_seq_get_size` / `llama_state_seq_get_data` 存下整条序列（KV + 递归状态），第二句前用 `llama_state_seq_set_data` 恢复；
   - 不要用 `llama_memory_seq_rm` 回退：递归部分不支持部分删除；
   - 哪一类都没开的那一句不问。
5. 两段回答分别解析（见下文），造出 `Candidate`，`source = DetectorSource.LLM`，`confidence = 0.5`：
   - 文字照 `GeminiNanoClassifier` 的写法：`quad = lines[i].quadForRange(range)`，`readingDegrees = RuleClassifier.readingDegrees(lines)[i]`；
   - 区域：`readingDegrees = null`；
   - 交给 `CandidateMerger`，帧用 `ReadingFrame.of(lines)`。`CandidateMerger.canJoin` 改成也按 kind 排除：FACE、BARCODE、CHAT_AVATAR 不论来源都不参与相邻合并。现在只按 source 排除，模型的人脸并排时会拼成一块。
6. 结局按句记：`LabOutcome(text, regions)`，每一句是 `Done(found)`、`Unfinished(reason)` 或 `NotAsked`。超时、进程没了、写满输出上限、复读截停都算没答完。答完的那一句照常打码。

## 提示词与解析

### 公共部分

```
<|im_start|>user
{图}{问题}<|im_end|>
<|im_start|>assistant
<think>

</think>

```

- 图在前、问题在后，和桌面评测一样，图像前缀才能给两句共用。图的位置用 mtmd 的标记（`mtmd_default_marker()`）；
- 空的 think 块用来关掉思考模式。0.8B 本来默认不思考，但我们不跑模板引擎，得自己写上；
- 贪心解码（温度 0），停在 `<|im_end|>` 或 `<|endoftext|>`，每句最多 512 token；
- 同一行连着出现三次就停：0.8B 会复读；
- 不用 GBNF 语法约束。按 ggml-org/llama.cpp#29715，accept 了语法不允许的 token（比如自己截断后补一个 EOG）会让进程 abort 或 terminate，上游不修。我们要自己判停、截断，不冒这个险，格式靠解析时宽容。

### 文字

- 从评测验证过的短提示出发：`tools/model-eval/run_eval.py` 的 `IMAGE_INSTRUCTIONS`，人名 / 地址 / 电话 / 号码四类；
- 提示词里的类型名按开着的类型取，最多九个：人名、昵称、聊天标题、地址、电话、邮箱、取件码、验证码、号码。
  - 取件码、验证码靠语义，规则按形状认不出，所以单列；
  - 其余开着的号码类（证件号、银行卡、护照、车牌、车架号、生日……）都归「号码」；
  - 给 Nano 用的 23 类长提示在 0.8B 上没测过，它的指令遵循分（IFEval）只有 52；
- 「号码」分回细类：在原文落到的那一整行 OCR 文字上（字段名在上一行的，连上一行一起）跑设置里开着的各条规则（`rule.findIn`），命中区间和原文相交就取那一条的类型，字段名锚点还在。
  都认不出时，「长数字串」开着才归它，否则丢掉，并在调试信息里记一笔；
- 解析复用 `SemanticPrompt` 的 `locateAll`、`plausible`、`collapse` 和短信签名的排除，类型表做成参数。关掉的类型的否决（`GeminiNanoClassifier` 里 private 的 `vetoed`）挪进 `SemanticPrompt`，两边共用。

### 区域

问法初稿（只列开着的那几类）：

```
找出图中所有的人脸、二维码和条形码、聊天头像。
用 JSON 输出：[{"bbox_2d": [x1, y1, x2, y2], "label": "人脸"}]
label 只用：人脸、条码、头像。一个也没有就输出：[]
```

- 坐标按 0–1000 的相对坐标解读，相对于模型看到的那张图。来源是 Qwen3-VL 的说明和 ms-swift 的 Qwen3.5 文档，Qwen3.5 没有官方示例；
- 保险：任何一个坐标大于 1000，就按缩图上的像素坐标换算（Qwen2.5-VL 就是这样），并在调试信息里标出；
- 不整段按 JSON 解析：逐个找 `"bbox_2d": [a, b, c, d]` 和挨着它的 `"label"`，坏一个只丢一个（同 `SemanticPrompt` 不用 JSON 的理由）。label 认中英别名（face、qr code、barcode、avatar）；
- 换算：`x × W / 1000`、`y × H / 1000`，两轴各自换算，W、H 是送去的那张图（转正前的）在 `SourceImage` 坐标系里的宽高。缩图时的拉伸不影响结果。转正过的，再用 `Upright.toSource` 映射回原方向；
- 外扩：人脸每边 15%（连头发和下巴），头像、条码每边 6%（初值，评测再调），截到图内；
- 丢掉：坐标颠倒或面积为 0 的；按外扩前的框算，面积超过整张图 90% 的（0.8B 有时把整张图框成一个）；同类 IoU > 0.5 的只留大的；每类最多 40 个（防复读）；
- 人脸框和头像框 IoU 过半时只留一个：「聊天记录」开着留头像，否则留人脸。不然叠出两个码，点一下拿不掉；
- 不拿像素再核一遍（比如用 ML Kit 再认一遍条码）：用户要的是模型自己判断。代价是框错了、框多了没人兜，只能靠用户点掉。

## 模型导入

- 实验室页「导入」→ `ActivityResultContracts.OpenMultipleDocuments`（`*/*`），一次选两个文件；
- 纯 Kotlin 读 GGUF 文件头认身份，读到要的几项就停，不碰张量：
  - 模型：`general.architecture = qwen35`；记下 `qwen35.block_count`、`qwen35.embedding_length`、`general.file_type`（量化方式）；
  - mmproj：`clip.projector_type = qwen3vl_merger`，`clip.vision.projection_dim` 要等于模型的 `embedding_length`；
  - 2B、4B 也能导入，但提示词只在 0.8B 上调过；
- 出错各提示一句：不是 Qwen3.5 的模型文件；只选了一个文件；模型和 mmproj 对不上；空间不够；
- 开始拷之前先结束 `:lab`（见「推理进程」），删除时也一样；
- 拷进 `noBackupFilesDir/lab/`，不放 cacheDir（会被系统清掉）：
  - 先查剩余空间（文件大小 + 200 MB）；
  - 文件名带导入序号（`model-<序号>.gguf`、`mmproj-<序号>.gguf`），写完再改名。不在原地覆盖可能正被内存映射的文件；新的拷好后删掉旧序号的文件；
  - 显示进度，可以取消；
- 存放：
  - 两个文件的原名、大小、量化方式、模型尺寸，以及导入序号，存在单独的键里。只由导入、删除这条路径写，`setRecognitionConfig` 不写，`resetSettings` 不删。序号只增不减；
  - `RecognitionConfig` 加 `qwen: Boolean`（出厂 false）和 `qwenModel: Int`。读的时候 `qwenModel = if (qwen) 导入序号 else 0`，所以开关关着时导入、删除都不会让编辑器重跑；
- 「删除模型」删文件和这几项，同时关掉开关。「恢复默认设置」只关开关，不删模型。

## 设置页

实验室页在「聊天记录」下面加一组：

```
实验室
├ 聊天记录（开关）                       已有
│ 页脚（已有；Qwen 开着时换成：问模型时带上头像、昵称和聊天标题。）
├ Qwen3.5（开关）                        没导入模型时灰着
├ 模型   Qwen3.5-0.8B · Q4_K_M · 533 MB  没导入时写「未导入」，点了导入；导入中显示百分比
├ 视觉   mmproj · F16 · 205 MB
├ 删除模型
│ 页脚：开着时由模型判断要遮的文字、人脸和条码（聊天记录开着时还有头像、昵称、标题），规则和其他识别不跑。需要模型和 mmproj 两个 .gguf 文件。下载
```

- 「下载」链到 `https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF`，用系统浏览器打开（和打赏入口一样，应用自己不发请求）；
- 一级页「实验室」那一行的小字：设备支持时用新的 `settings_labs_summary_qwen`（聊天记录、Qwen3.5 / Chat screenshots, Qwen3.5），不支持时仍是原来那条；
- 设备不支持时这一组不显示，判断方法见「原生库与构建」；
- Qwen 开着时灰掉，页脚一句「Qwen 开着时不用」：
  - 人脸、条码页的「识别模式」一组；
  - 人名页的「没有旁证的人名」；
  - 文字页的「AI 增强识别」；
- 新增的每条文案在 `values/` 和 `values-zh/` 各一份，行对齐：页脚两条、未导入、导入进度、删除模型、下载、四种导入错误、「Qwen 没跑完」、重试、导出前的询问。照极简的规矩写。

## 编辑器

- 识别中照旧放扫光或磨砂动效，直到模型答完；
- 底栏不出现「AI 增强识别」；
- 结果都是 `DetectorSource.LLM`，宽屏列表上标「AI」；点掉、撤销照旧，点没打码的字打整行（`LINE`）也照旧；
- **没答完**：画布上方一条常驻提示「Qwen 没跑完」，右侧「重试」，手机和宽屏都显示。样子照 `ColorPickHint`（`AiReviewResultPill` 会自己收起、只在手机上画、不接触摸，不合用）。
  - 重试只问没答完的那一句，结果追加进来，不动已经打上的码，先压一个撤销快照。文字行已经没有了（比如从重建里恢复的）就整张重跑；
  - `EditorUiState` 和 `BatchScan` 都存一份 `labOutcome`，批量里切回这张图时照原样显示；
- **导出**：实验室开着时，导出和「完成」遇到这张图还在识别，就先等结果（和批量的 `awaitBatchScans` 一样，按钮显示「导出中…」）。
  有没答完的图时先问一句「N 张 Qwen 没跑完」，选重试或照样导出。批量里在胶片条上标出没答完的那几张；
- **进程被杀后恢复**：识别还在跑或没答完时，往 `SavedStateHandle` 写一项（如 `KEY_LAB_PENDING`）。恢复时读到它，就照第一次打开那样重跑（保留手动框），不能静默显示一张干净的图；
- **改了不起作用的设置不重跑**：编辑器判断要不要重跑时，比较「生效的配置」。Qwen 开着时，先把 `face`、`barcode`、`semantic`、`maskUnanchoredNames` 归一成固定值再比；
- **作废的识别**：单张模式也留着识别的 Job 和序号（照批量的 `scanSerial`），作废的结果不落地。改设置重跑、换图、`onNewIntent` 进新图、结束批量时，取消这张图原来的 `:lab` 请求：排着的从队里拿掉，跑着的中止；
- **排队**：`:lab` 一次只跑一张，画布上正看着的那张排最前。批量里换到别的图时，已经在跑的那张不取消（结果仍然有效）；
- **超时**：从 `:lab` 开始处理这张算起，不含排队，初值 180 秒（真机量完再定）。

## 推理进程

- 进程级的 `object LabClient`（照 `NanoClient`、`PpOcrModels` 的写法）管连接、队列和模型状态，`QwenJudge` 只调它。
  理由：改一次设置就重建一个 `RedactionEngine`，连接不能跟着引擎走。
- `LabService`（`android:process=":lab"`，不导出）。`LabClient` 用 `bindService` 绑定，用 `Messenger` 收发。服务在连上时报自己的 pid；
- 生命周期：
  - 编辑器实例按引用计数：`EditorViewModel` 新增 `onCleared()`，在里面调 `LabClient.release()`。可能同时有两个编辑器（分享入口和 `EditorForCaller`）；
  - 最后一个编辑器 `onCleared` 时：清掉排队的请求，`unbindService`，再 `Process.killProcess`（`:lab` 的 pid）。「离开编辑器」就是指这个，不是 `onStop`，从编辑器进设置不会打断识别；
  - 导入、删除模型前也这样结束 `:lab`；
- 请求：
  - 轮到这张时，主进程才把缩好的图写成 `cacheDir/lab/` 下的一个 RGB 文件（672×1504×3 约 3 MB，超过 Binder 的 1 MB 上限，不走 Parcel）。排着的请求只记着是哪张图；
  - 只传路径、宽高、两句问题、输出上限和导入序号。回复：两段原样回答，加上各段耗时和 token 数；
  - RGB 文件由 `:lab` 读完就删；请求取消或 `:lab` 崩溃后由主进程删；打开编辑器时清空 `cacheDir/lab/`；
- 模型：
  - 第一次请求时加载，参数是 `n_ctx` 2048（图 ≤ 1024 + 模板 + 问题 + 512 输出）、`n_batch` 512、线程数初值 4（真机上和全部核数比一下）；
  - 加载时间包括 mtmd 默认的 warmup（编码一张假图）；
  - 请求里的导入序号变了，就先 `llama_free`、`mtmd_free`，再按新文件加载；
- 线程：推理跑在自己的线程上，`Messenger` 的 Handler 只负责收发，取消消息才送得进来；
- 取消：
  - 先设中止标志。abort 回调（`llama_set_abort_callback`）在每次 `llama_decode` 的计算节点之间检查，问题的预填、送图像 embedding 的那次 decode、逐 token 生成都停得下来。中止后先 `llama_memory_clear`，再接下一张；
  - 停不下来的只有 clip 的图像编码（以及加载时的 warmup）。过 2 秒还没停，就先 `unbindService` 再 `killProcess`：绑着的时候杀，系统会带退避自动重启这个服务。下一次请求重新 `bindService`；
  - `onServiceDisconnected` 里把正在跑的那张记为没答完；
  - `onDestroy` 里不调 `llama_free`：unbind 之后原生线程可能还在跑，进程反正马上就被杀；
- `:lab` 进程里 `YomarkApplication.onCreate` 不预读 HanLP 词典（按 `Application.getProcessName()` 判断），省约 30 MB。

## 原生库与构建

- 不另开模块，放在 `:app`：`src/main/cpp/` 里一个 JNI 源文件和 `CMakeLists.txt`。llama.cpp 作为 git submodule 放在 `third_party/llama.cpp`，固定提交（选哪个见「未验证」）；
- **submodule 是可选的**：`app/build.gradle.kts` 只在 `third_party/llama.cpp/CMakeLists.txt` 存在时才配 `externalNativeBuild`，否则打印一句提示，照常构建、只是没有 `libyomark_lab.so`，实验室里的 Qwen 一组随之不显示。
  Kotlin 代码不依赖 .so，照常编译。这样别的窗口的 worktree 不拉 submodule 也不受影响；
- 只给 64 位编：`defaultConfig.externalNativeBuild.cmake.abiFilters("arm64-v8a", "x86_64")`。它只限定 CMake 编哪几个 ABI，不动打包；app 的 `splits` 不动，32 位包照常出，只是里面没有这个 .so；
- JNI 只有五个调用：
  - 加载（模型路径、mmproj 路径、`n_ctx`、`n_batch`、线程数、图像 token 上限）；
  - 编码图像；
  - 提问（问题、上限，逐段回调）；
  - 取消；
  - 释放；
  - C++ 异常在 JNI 边界全部接住，转成错误码；
- CMake 开关：
  - 公共：`-DLLAMA_BUILD_MTMD=ON -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DGGML_NATIVE=OFF -DBUILD_SHARED_LIBS=OFF -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`；
  - 作为子目录引入时，llama-common（里面有 HTTP 下载和 jinja）、tools、server、测试默认就不编；
  - 静态库全部链进 `libyomark_lab.so`；
  - 不用 `GGML_BACKEND_DL`：yomark 打包时不解压 .so（合并后 `extractNativeLibs="false"`），按目录挑 CPU 变体的加载方式找不到文件；`useLegacyPackaging` 又会把 ORT 的 33 MB 一起解压出来；
  - arm64-v8a：`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`。`GGML_CPU_KLEIDIAI=ON` 只在 `ANDROID_ABI STREQUAL "arm64-v8a"` 时设，在 x86_64 上会 FATAL_ERROR，所以写在 `CMakeLists.txt` 里按 ABI 分开。
    KleidiAI 按运行时的 CPU 挑 i8mm、SME 内核，和 `GGML_BACKEND_DL` 无关，只覆盖部分矩阵乘（Q4_0、Q4_K、Q8_0、F32）；
  - x86_64（模拟器、Chromebook）：`-DGGML_SSE42=ON -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON -DGGML_BMI2=ON`。交叉编译时这些默认全关；
- 支不支持在主进程判断，主进程不加载这个 .so：
  - `BaseDexClassLoader.findLibrary("yomark_lab")` 找得到；
  - `/proc/cpuinfo`：arm64 要同时有 `asimddp` 和 `asimdhp`；x86_64 要同时有 `avx2`、`fma`、`f16c`、`bmi2`。和编译基线一一对应，不然会在 `:lab` 里 SIGILL；
  - 内存：`ActivityManager.MemoryInfo.totalMem ≥ 5.5 GiB`（API 34 起改用 `advertisedMem ≥ 6 GB`）。
    标称 6 GB 的手机读出来不到 6 GiB，阈值要拿一台 6 GB 真机核对读数后再定。debug 构建跳过内存这一条：项目的模拟器只有约 3.8 GiB；
- 16 KB 对齐：构建后检查 .so 的对齐。Play 2027-02-01 起要求；项目的模拟器也用 16 KB 页的镜像；
- 构建环境：
  - 两个工作流（`android-apk.yml`、`play-bundle.yml`）的 checkout 加 `with: submodules: true`；
  - `.claude/hooks/session-start.sh` 加 `git submodule update --init --depth 1`，PACKAGES 加 `ndk;27.3.13750724` 和 `cmake;3.31.6`；
  - CLAUDE.md 加一句：要编实验室的 worktree，先 `git submodule update --init --depth 1`；
- KleidiAI 的源码在 CMake 配置时从 GitHub Releases 下载（ggml 里写死了版本 v1.31.0 和 MD5）。离线时把源码解压进仓库（如 `third_party/kleidiai`），传 `-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=<该路径>` 跳过下载；
- APK 的构建时间会多出几分钟（没量过）；
- R8：保留 JNI 回调类，理由同 `proguard-rules.pro` 里 `ai.onnxruntime.**` 那条；
- 体积：每个 ABI 的 APK 和安装体积约多 10–12 MB（.so 不压缩存放），以构建后实测为准；
- 许可：llama.cpp、ggml 是 MIT，KleidiAI 是 Apache-2.0，和现有依赖一样处理许可声明。

## 调试信息

`QwenJudge` 照 `GeminiNanoClassifier.recordExchange` 的写法记：

- 「Qwen · 本机情况」：模型尺寸、量化方式、文件大小，`n_ctx`、线程数，CPU 特性；
- 「Qwen · 文字」「Qwen · 区域」：
  - 送去的图的尺寸、图像 token 数，有没有转正；
  - 提示词，原样回答；
  - 解析出的每一处：文字写第几行和原文；区域写类型和换算后的框，按像素坐标换算的标出来；
  - 「号码」分不回细类而丢掉的；
  - 写满上限或复读截停的；
- 各段耗时：加载（含 warmup）、图像编码、预填、生成，以及生成了多少 token、存下的状态有多大；
- 没答完时写原因。图本身不进调试信息。

`EditorTrace.inputSection` 写明这一次走的是实验室。

## 要改的文档

- README：「功能」「实现」里「敏感信息判断由规则决定，不交给模型」那几处，加一句实验室例外；
- 隐私权政策（`site/privacy/index.html`，中英两版）第 3、4 节和生效日期：实验室的模型由用户导入、存在本机、只在本机跑、不联网；推理用的临时图像文件读完即删；
- CLAUDE.md：submodule 那一句（见上文）。

## 测试

推理函数照 `GeminiNanoClassifier` 的 `ask` 那样注入，JVM 单测不加载 .so。

| 层 | 测什么 |
|---|---|
| 单测 | GGUF 文件头：认出模型和 mmproj、维度不配、不是 GGUF、文件截断（用手搓的小 GGUF 字节） |
| 单测 | 缩图：宽高都是 32 的倍数、和 mtmd 的算法结果一致、token ≤ 1024；坐标两轴各自换算回原图；转正后的框映射回原方向 |
| 单测 | `labActive`：开关、模型文件、设备支持各缺一样时都不走实验室 |
| 单测 | 提示词：按开着的类型增减；一类都没开的那一句不问 |
| 单测 | 文字解析：沿用 `SemanticPromptTest` 的情形，换成实验室的类型表；「号码\|552817」在有「验证码」的行里；出厂设置下形状认不出的号码被丢掉 |
| 单测 | 区域解析：正常的 JSON、少括号多逗号、label 的中英别名、坐标颠倒、整张图的框、大于 1000 的坐标、外扩后截到图内、一张大头近景不被丢、人脸和头像重叠只留一个 |
| 单测 | `CandidateMerger`：两张并排的 LLM 人脸不拼成一块 |
| 单测 | 结局：文字答完、区域超时时，文字照常打码，提示只说区域没跑完；重试只问区域 |
| 单测（DataStore） | 新几项的往返；开关关着时导入不改 `qwenModel`；恢复默认关开关、不删模型、不动导入序号 |
| 单测（Compose，Robolectric） | 实验室页：没导入时开关灰着；导入进度和四种错误；删除后开关关掉；设备不支持时整组不显示、一级页小字是旧的那条；Qwen 开着时人脸、条码识别模式等几项灰掉 |
| 单测 | 编辑器：改灰掉的那几项不重跑；识别中点导出会等；有没答完的图时导出先问；进程被杀后恢复时重跑 |
| 模拟器（x86_64，debug 构建） | 把两个 GGUF 推进 Download，从选择器导入；打开开关，打开测试图，截图看码；离开编辑器后 `pidof moe.flinty.yomark:lab` 为空；跑到一半杀掉 `:lab`，编辑器提示没跑完、不崩（模拟器内存紧，`:lab` 被系统杀掉也是这个情形）；识别中 `am kill` 主进程再回来；连划 10 张再导出 |
| 真机（Pixel 9 Pro） | 加载、图像编码、预填、生成各自的耗时；`:lab` 的峰值内存（`dumpsys meminfo`）；连跑十张后的温度；一张竖着拍的身份证 |
| 评测 | `tools/model-eval`：先在桌面 `llama-server` 上调两句提示词。扩展 `run_eval.py`：图在本地先缩成和 app 一样的尺寸再发；加区域的真值框和 IoU 打分；加一组「小图放大到接近 1024 token」的对照；单独统计「号码」分回细类后还剩多少命中。然后照 `nano_eval.py` 写一个真机探针，跑 app 里的整条路 |

## 未验证

- **手机上要多久**：没有公开数据。按 Tensor G4 上 llama.cpp 跑 Qwen3-0.6B 的 CPU 速度（预填 241 tok/s、生成 46 tok/s）估，一张截图十几到几十秒；
- llama.cpp 的看图编码在安卓 CPU 上有过一次 2.7 倍的变慢（ggml-org/llama.cpp#27649，没修就关了）。定提交之前要在 Pixel 9 Pro 上比几个版本；
- 0.8B 一次框出多个人脸、头像、条码准不准：公开的只有 ODInW13 33.2。10-03 的桌面评测里，它连「图里有没有条码」都只答对 9 题里的 6 题，条码框的风险更大；
- 低于 1024 个图像 token 时框不准（上游的说法，ggml-org/llama.cpp#16842）；1024 个 token 够不够看清小头像、小二维码；长截图（如 1080×5148）缩完字太小，第一版不分块；
- Qwen3.5 的框是 0–1000 的相对坐标还是缩图上的像素：先在桌面评测里确认；
- 峰值内存：桌面实测 2.2 GB（16k 上下文，视觉部分常驻）；真机按 `n_ctx` 2048 量，量完再定内存门槛；
- 粗类分回细类的效果，以及提示词里放几类最好。

## 以后

- GPU：等 Mali 上 Vulkan 的错修好（ggml-org/llama.cpp#28637），再加一个开关试；
- 长截图分块；
- 拿 Qwen3.5-0.8B-Base 做截图打码的微调（ms-swift 支持 Qwen3.5 的框训练）。
