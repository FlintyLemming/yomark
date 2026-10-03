# 有码 · 随包端侧模型调研与实测（2026-10-03）

## 起因

滴滴出票页上，乘车人的名字既没有字段名，后面跟的也不是电话，而是一个打了星的身份证号。
规则层的两种认法（`LabeledField`、`NameBeforePhone`）都接不住，只有「AI 复查」（Gemini Nano）圈了出来。

用户的决定是让 AI 复查成为所有设备都能用的能力：有 Gemini Nano 的设备继续用 Nano，其余设备用一个随包的端侧模型。

动手前先厘清一件事：**现在的 AI 复查只发文字。** `GeminiNanoClassifier` 把 PP-OCR 读出的整页文字作为
`TextPart` 发给 Nano，认出那个名字的是一个文本模型，不是多模态。所以这次要回答两个问题：
随包选哪个模型；它该读 OCR 文字、看截图，还是两样都给。

## 结论

1. **随包的小模型要看图。** 同一个模型，看截图比读 OCR 文字好得多：Qwen3.5-2B 看图 18/22，读文字只有 4/22。
   2B 及以下的模型同时给图和文字反而更差（Qwen3.5-2B 5/22），4B 才能把两样都用好（19/22）。
2. **文字模式差，有一半是 app 自己造成的。** 这两处不管最后用哪个模型都该修，Gemini Nano 也受益：
   - `GeminiNanoClassifier` 只送「至少两个字母」的行。打了星的证件号一个字母都没有，模型根本看不到，
     而它恰恰是「上一行是人名」最强的线索。改成整页都送之后，Qwen3.5-0.8B 和 2B 只读文字就把这一页的名字和证件号都认出来了；
   - `SemanticPrompt.parse` 要求回答里的行号必须正好对上。小模型常常差一行（名字在第 19 行，它答 20），
     整条回答因此被丢掉。行号不对时，在相邻几行里找原文就能救回来。
3. **随包模型首选 Qwen3.5-2B，看图模式。** 6 页 22 处里认出 18 处，零误报，用户那张截图上名字和证件号都认出来了，
   9 道条码判断题全对。许可证 Apache-2.0。文件 1.28 GB，加视觉部分 0.67 GB（F16）。
   Qwen3.5-0.8B（共 0.73 GB，14/22）可以作为低内存机型的降级档。Qwen3.5-4B 最好（20/22），但 3.4 GB、
   峰值内存 7.5 GB，撑不起「所有设备」。
4. **最大的未知是手机上的速度。** 本机是 4 核 x86 CPU，Qwen3.5-2B 看一张截图的中位数是 26 秒。
   Google 公布的 Gemma 4 E2B 在 Galaxy S26 Ultra 上的数据：CPU 预填 557 tok/s，GPU 3808 tok/s。
   本机 llama.cpp 跑同一个模型只有 57 tok/s，旗舰机应该是几秒级，但中低端机没有数据。上真机量是下一步的第一件事。
5. **条码可以交给模型复核。** 1.3B 以上的候选在 9 道条码判断题上全对：酒店图、格纹、条纹衬衫都答「没有」，二维码和 EAN-13 都答「有」。
   ML Kit 报的「疑似条码」（解不出内容的那种）可以裁出来让模型再判一次，也可以直接切严格档。
6. **两个包能做，「只有 Nano 机型能装」做不到严格。** 详见下文「打包与设备白名单」。

## 候选（2026-10）

| 模型 | 参数 | 许可 | Android 上的运行时 | 测了吗 |
|---|---|---|---|---|
| Qwen3.5-0.8B / 2B / 4B | 0.8B / 2B / 4B，原生多模态（早融合） | Apache-2.0 | llama.cpp；LiteRT-LM 有社区转换的 2B | 测了 |
| MiniCPM-V 4.6 | 1.3B（SigLIP2-400M + Qwen3.5-0.8B） | Apache-2.0 | llama.cpp、官方端侧适配 | 测了 |
| Gemma 4 E2B | 有效 2B，带逐层嵌入 | Apache-2.0 | LiteRT-LM（Google 官方，CPU/GPU/NPU）、llama.cpp | 测了（llama.cpp） |
| LFM2.5-VL-1.6B | 1.6B | LFM Open License | llama.cpp | 测了 |
| Gemma 4 E4B、Qwen3.5-9B 等 | 4B 以上 | — | — | 没测，体积与内存超出「所有设备」 |

## 实测

### 方法

- **样本**：6 页、22 处应找出的隐私，另列每页不该圈的字（店名、站名、酒店名、商品名、价格）。
  - 用户的滴滴出票截图（不入库）；
  - PP-OCR 单测用的合成快递页（`app/src/test/resources/ppocr/logistics-page.png`）；
  - 4 张新合成页（全是编的）：火车票（名字没有字段名，证件号打星）、微信聊天（人名、地址在句子里）、
    订单（有字段名，另有一堆店名商品名）、酒店订单（「入住人」不在规则的字段名表里）。
- **输入**：与设备同口径。先按 `SourceImageLoader` 降采样（2 的幂，长边 ≤ 2048），1080×2340 的截图实际是在 540×1170 上识别的；
  OCR 用随包的同一套 PP-OCRv5，在桌面 JVM 上跑仓库里的原代码。
- **四种喂法**，回答都落回 OCR 行再打分：

  | 喂法 | 给什么 | 回答格式 |
  |---|---|---|
  | 文字（app 现状） | `SemanticPrompt` 原样的提示词，以及 `GeminiNanoClassifier` 过滤后的行 | 行号\|类型\|原文 |
  | 文字·全送 | 同上，但纯数字、打星的行也送 | 行号\|类型\|原文 |
  | 看图 | 截图，加同样的四类说明 | 类型\|原文 |
  | 图 + 文字 | 截图，加「文字·全送」的整段提示词 | 行号\|类型\|原文 |

- **条码判断题**：9 张小图，只问「有没有条形码或二维码」。反例是用户截图里的三张酒店缩略图（不入库）、
  合成的格纹和条纹衬衫；正例是三张二维码（含仓库里条码单测用的那张、一张倾斜加模糊的）和一张 EAN-13。
- **打分**：「严格」用 app 现在的解析，行号必须对、原文必须在那一行；「宽松」只要原文在任何一行找得到就算。
  引用比真值长出一倍以上（整行照抄）不算命中。
- **环境**：llama.cpp `9bf55f4`，CPU 推理（4 vCPU Xeon，AVX-512），权重 Q4_K_M（Gemma 用官方 QAT Q4_0），
  视觉部分 F16，温度 0。

### 结果（22 处里认出几处）

| 模型 | 文件 LLM+视觉 | 峰值内存¹ | 文字（严格 / 宽松） | 看图 | 误报（文字 / 看图） | 用户截图：名字、证件号² | 条码 9 题 |
|---|---|---|---|---|---|---|---|
| Qwen3.5-0.8B | 0.53 + 0.20 GB | 2.2 GB | 1 / 7 | **14** | 3 / 0 | 文字 —　看图 名 | 6 / 9 |
| **Qwen3.5-2B** | 1.28 + 0.67 GB | 3.8 GB | 4 / 7 | **18** | 1 / 0 | 文字 名³　看图 名、证 | **9 / 9** |
| Qwen3.5-4B | 2.74 + 0.67 GB | 7.5 GB | 17 / 17 | **20** | 0 / 0 | 文字 名　看图 名、证 | 9 / 9 |
| MiniCPM-V 4.6 | 0.53 + 1.11 GB | 2.9 GB | 5 / 11 | 10 | 4 / 0 | 文字 名　看图 证 | 9 / 9 |
| Gemma 4 E2B | 3.35 + 0.99 GB | 5.6 GB | 10 / 10 | 12 | 1 / 0 | 文字 —　看图 — | 9 / 9 |
| LFM2.5-VL-1.6B | 0.73 + 0.58 GB | 2.3 GB | 8 / 13 | 5 | 8 / 2 | 文字 名³　看图 — | 9 / 9 |

¹ llama.cpp、16k 上下文、视觉部分常驻，比手机上实际部署偏高。Gemma 4 E2B 走 LiteRT-LM 时逐层嵌入是内存映射的，
Google 公布的峰值是 1.7 GB（CPU）。
² 文字模式下证件号那一行根本不会送给模型（见结论 2），最多只能认出名字。
³ 宽松口径才算：答对了名字，但行号不对，严格口径下被丢掉。

作为对照，用户真机上的 Gemini Nano（文字模式，app 现状）认出了名字，没认出证件号——证件号那一行没送给它。

**另两种喂法**（严格 / 宽松）：

| 模型 | 文字·全送 | 图 + 文字 |
|---|---|---|
| Qwen3.5-0.8B | 2 / 4 | 1 / 3 |
| Qwen3.5-2B | 10 / 10 | 5 / 8 |
| Qwen3.5-4B | 13 / 19 | **19 / 20** |
| MiniCPM-V 4.6 | 0 / 9 | 5 / 6 |
| Gemma 4 E2B | 7 / 9 | 7 / 8 |

看图模式的引用，绝大多数能在 OCR 行里逐字找到，可以落到 PP-OCR 的字级框上。找不到的多是 OCR 读错的字：
订单页降采样后 PP-OCR 把「刘洋」读成了「文洋」，模型看图读对了，反而对不上。

### 速度（本机，用户截图）

| 模型 | 文字：预填 / 生成 | 看图：编码 + 预填 / 合计 |
|---|---|---|
| Qwen3.5-0.8B | 475 tok 2.4 s（194 tok/s） | 1073 tok 11.2 s / 11.8 s |
| Qwen3.5-2B | 475 tok 4.5 s（105 tok/s），生成 16 tok/s | 1073 tok 28.0 s / 29.9 s |
| Qwen3.5-4B | 475 tok 10.6 s（45 tok/s），生成 8 tok/s | 1073 tok 41.4 s / 43.8 s |
| MiniCPM-V 4.6 | 475 tok 2.4 s（202 tok/s） | 667 tok 19.8 s / 20.8 s |
| Gemma 4 E2B | 508 tok 8.9 s（57 tok/s），生成 11 tok/s | 574 tok 16.9 s / 17.0 s |

这是 x86 服务器 CPU 上的 llama.cpp，不代表手机。可参照的手机数据只有 Google 的 Gemma 4 E2B（Galaxy S26 Ultra、LiteRT-LM）：
CPU 预填 557 tok/s、生成 47 tok/s，GPU 预填 3808 tok/s、生成 52 tok/s。

## 打包与设备白名单

### 分两个包本身不难

Gradle 加一个 flavor 维度，`EngineFactory` 里 `refiners` 那一行按 flavor 取不同的实现：

```kotlin
flavorDimensions += "ai"
productFlavors {
    create("nano")    { dimension = "ai" }   // 只靠 AICore 上的 Gemini Nano，不带模型
    create("bundled") { dimension = "ai" }   // 自带端侧模型，所有设备都能用
}
dependencies {
    "nanoImplementation"(libs.mlkit.genai.prompt)
    "bundledImplementation"(project(":llm"))  // llama.cpp 的 JNI 封装，或 LiteRT-LM
}
```

AICore 的 `BIND_SERVICE` 权限只会合并进 nano 包，`AssertPermissionsTask` 的白名单要按 flavor 分开。

Gemini Nano 也能看图：ML Kit Prompt API 接受 `ImagePart(bitmap)` 加 `TextPart`，输入上限约 4000 token。
两个包可以共用同一套提示词和解析，只换推理后端。

### 「只允许有 Nano 的设备安装」

| 分发渠道 | 能不能在安装时拦 | 怎么做 |
|---|---|---|
| Google Play | 只能近似 | 设备目录只有**排除**、没有白名单：逐个机型排除，或按 RAM、SoC 规则排除。Nano 的支持列表分 nano-v2/v3/v4 三张，还在不停加机型，排除表得一直跟着维护 |
| 直接装 APK（现在 CI 出的包） | 做不到 | `<uses-feature>` 只有 Play 认，系统安装器不看；也没有一个「有 AICore」的系统特性可以声明。只能在首次启动时 `checkStatus()`，不可用就提示改装另一个包 |

就算装在名单内的机型上，Nano 也可能暂时用不了：模型还没下完（DOWNLOADABLE / DOWNLOADING）、解锁了 bootloader、
AICore 对每个应用有推理配额、国行 ROM 没有 GMS。所以 nano 包里「Nano 不可用」这条路必须保留，白名单只能让它少发生。

Nano 的支持列表也不只有 Pixel：Pixel 9 / 10 / 11、Galaxy S26 和 Z Fold7/8，以及小米、OPPO、vivo、荣耀、一加、iQOO
的一批旗舰都在里面（见 ML Kit GenAI 文档的设备表）。

### 两个包同时上 Play 的风险

Play 的垃圾内容政策把「同一开发者发布多个功能、内容、体验高度相似的应用」列为违规。
同一个应用只差一个 AI 后端，分两个包名上架有被判重复的风险；两个包名的购买记录也不互通，因为 Play Billing 按应用记。

### 替代方案：一个包，运行时选择，模型按机型下发

- 运行时：`checkStatus()` 返回 AVAILABLE 就用 Nano，否则用随包模型；
- 模型放进 Play 的 AI pack（Play for On-device AI，beta）。设备分组可以按机型、RAM、SoC、系统特性定义：
  Nano 机型那一组分到空包，不下载模型；其余设备分到模型。下载由 Play 完成。
  单个 AI pack 最多 1.5 GB，整个应用最多 4 GB；设备分组要求 AGP ≥ 8.10，本项目是 8.11.1；
- 直接装 APK 的渠道（CI、GitHub）照旧出一个把模型放进 assets 的大包。

这样只有一个包名、一份购买记录，Nano 机型也不用白下 1–2 GB 的模型，不需要白名单。

## 未验证

- **手机上的速度与内存**：全文最大的风险。尤其是看图时的视觉编码，在中低端机的 CPU 上可能要几十秒；
- Gemini Nano 看图时，一张截图占多少 token、会不会超过 4000 的上限；
- AI pack 由 Play 下载，按理不需要应用自己申请 INTERNET 权限，需要实际打一个包确认；
- 国行 ROM 上 AICore 的情况：按理没有 GMS 就没有 AICore，没有真机核实；
- 样本只有 6 页 22 处，足够看出量级差别，不够做细的取舍。真实样本集（`docs/eval-sample-set.md`）采集后应该重跑。

## 怎么复现

脚本在 `tools/model-eval/`，与 app 的构建无关：

```bash
cd tools/model-eval
python3 make_samples.py                     # 合成页 + 真值 + 条码探针图 → out/（Python 需要 Pillow、segno）
../../gradlew -p ocr-dump -q run --args="../out $(cd out/samples && ls *.png | sed 's|^|../out/samples/|' | tr '\n' ' ')"
./download_models.sh                        # 约 13 GB → models/
LLAMA_SERVER=/path/to/llama-server python3 run_eval.py qwen35-2b  # 文字、看图、条码题
LLAMA_SERVER=/path/to/llama-server python3 run_hybrid.py qwen35-2b  # 文字·全送、图 + 文字
python3 score_eval.py qwen35-2b
```

`ocr-dump` 直接编译 app 里 PP-OCR 和提示词的原文件，在桌面 JVM 上跑随包的同一套模型。它按设备口径降采样，
写出 OCR 行和看图模式用的同尺寸截图，并把 `SemanticPrompt.INSTRUCTIONS` 原样导出，供脚本逐字复用。
自己的截图放进 `out/samples/`，在 `out/truth/` 里写同名真值即可加入评测。截图含个人信息时不要提交。
