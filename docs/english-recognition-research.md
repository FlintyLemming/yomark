# 有码 · 英文人名、地址识别调研（2026-10-09）

## 起因

英文截图上的地址、人名基本认不出来。要决定的是：再找一个像 PP-OCR 那样的模型，还是换别的办法。

动手前先拆开看：漏认可能出在两层，**OCR 没把字读对**，或者**字读对了、规则不认得它是人名地址**。
两层的解法完全不同，所以先分别量。

## 结论

1. **问题主要在规则层，不在 OCR。** 出厂规则在 10 张英文合成页上认出人名 0/15、地址 0/18，在 5 张留出页上人名 0/8、地址 0/7。
   同一批页面上，随包的 PP-OCRv5 把英文读得相当准：字符错误率 0.39%，150 行里 140 行一字不差（含空格）。
   原因在代码里（见下一节）：人名的三种认法里两种只认汉字，地址的形状认法只认中文门牌，英文字段名只有五个且区分大小写，
   中文系统上不带国家码的美国号码也认不出。
2. **所以不用为英文再找一个 OCR 模型。** 换 OCR 救不了规则层的空白。先在规则层补英文：原型在练习页上 38/42、零误报，
   在**留出页**（写完原型之后才画的）上 13/19、零误报，中文页的结果一处不变。地址补得好（留出页 6/7），人名有限（3/8）。
3. **人名想再往上走，才需要模型。** 试了 GLiNER PII edge（小型 NER，uint8 ONNX 46 MB，ONNX Runtime 已在包里）：
   与规则合用，留出页 18/19；但它会把品牌、店名、街区名当成人名地址（Starbucks、Chipotle、UberX、Shoreditch），
   15 页上 6 处误报，另有十来处圈了订单号、日期这类不该圈的。只适合像 HanLP 那样做「只圈不打码」的补充。
4. **OCR 可以顺手升到 PP-OCRv6 small**（2026-06 发布，同一家族的新版）。现有 `PpOcrEngine` 不改一行就能跑：
   英文字符错误率 0.39% → 0.09%，中文 0.96% → 0%（订单页的「刘洋」不再读成「文洋」，运单上的「OAK」不再读成「0AK」），
   速度与 v5 相当，包体多 10 MB。它和英文是两件事，可以单独做。
5. **两处小修，马上见效：** AI 复查把超过 8 个字符的人名整条丢掉（`SemanticPrompt.MAX_NAME = 8`），英文全名几乎都超；
   中文系统上 `PhoneRule` 按 CN 解析，`(614) 293-7781` 这种美国号码认不出，而电话又是人名的旁证，于是连锁漏掉。

## 现状：英文进来之后各条规则做了什么

| 认法 | 位置 | 对英文 |
|---|---|---|
| 人名·字段名 | `DefaultRuleSet.NAME_LABELS` + `LabeledField` | 英文字段名只有 `Name`、`Recipient`，区分大小写：「Guest name」「Full name」认不出；值必须和字段名在同一行，「字段名在上、值在下」的版式接不住 |
| 人名·电话前 | `NameBeforePhone` | 只往前收汉字 |
| 人名·从字面猜 | `PersonNameRecognizer`（HanLP） | 只认中文人名，音译名（nrf）还特意排除了 |
| 人名的旁证 | `DefaultRuleSet.nameAnchors` | 称呼、字段名、票种全是中文；英文地址认不出，也就当不了旁证 |
| 地址·字段名 | `ADDRESS` 的字段名表 | 只有 `Address`、`Ship to`、`Deliver to`，区分大小写（「Shipping address」小写的 a 就不行），值要在同一行，多行地址只能取到第一行 |
| 地址·形状 | `AddressShape` | 只认中文门牌（路 + 号、栋、单元、室），整个跑在汉字上 |
| 电话 | `PhoneRule(defaultRegion = 系统地区)` | 中文系统上是 CN：不带 +1 的美国号码（`(614) 293-7781`）认不出。英国的 `07700 900123` 倒是认出了，但那是被当成了广西的座机号，碰巧 |
| AI 复查 | `SemanticPrompt.MAX_NAME = 8` | 超过 8 个字符的人名整条丢掉，「Jennifer Walsh」是 14 个 |

## 实测

### 样本

全是合成页，1080×2340，Inter 字体（接近 Roboto / SF），运单用等宽体。按设备口径降采样到 540×1170 再识别。

- **练习页 10 张、42 处**：美国电商订单、iMessage 聊天、邮件（签名档带地址）、英国酒店订单（住客是拼音名）、英国快递、
  美国运单（全大写）、拼音地址（国际站收货地址）、账户资料页（字段名在上）、收件箱和打车收据（12sp 小字、浅灰）；
- **留出页 5 张、19 处**：通讯录联系人、德国地址的结账表单、群聊、机票（`ZHANG/WEI MR`）、外卖。
  **原型规则写完之后才画的，没照着它们调过**，是这份调研里唯一不偏的数字；
- **中文页 5 张**：上一次调研的同一批（`make_samples.py`），只用来看换 OCR、加英文规则会不会伤到中文。

多行的英文地址按行各算一处：规则逐行跑，每一行都要遮到。命中口径与上一次相同，另外几段合起来盖住真值 80% 以上也算
（英文地址常被分成街道、城市、邮编几段认出来）。

### OCR

练习页 150 行英文、中文页 55 行。「全对」是一字不差含空格；中文看「去空格全对」，因为 `WordGaps` 在中文标点两边补的空格各配置都一样。
耗时是 3 张英文 + 2 张中文页的中位数，检测加识别，桌面 JVM 上的 ONNX Runtime，4 vCPU Xeon。

| 配置 | 文件（检测 + 识别） | 英文全对 | 英文字符错误率 | 中文去空格全对 | 中文字符错误率 | 耗时 |
|---|---|---|---|---|---|---|
| **出厂**：PP-OCRv5 mobile | 4.8 + 16.6 MB | 140 / 150 | 0.39% | 50 / 55 | 0.96% | 894 ms |
| 出厂，不降采样 | 同上 | 139 / 150 | 0.47% | 51 / 55 | 0.77% | — |
| v5 检测 + en_PP-OCRv5 识别 | 4.8 + 7.9 MB | 146 / 150 | 0.26% | 14 / 55 | 53.6% | — |
| v5 检测 + v6 small 识别 | 4.8 + 21.2 MB | 144 / 150 | 0.26% | 55 / 55 | 0% | — |
| **PP-OCRv6 small** | 9.9 + 21.2 MB | **147 / 150** | **0.09%** | **55 / 55** | **0%** | 955 ms |
| PP-OCRv6 tiny | 1.8 + 4.5 MB | 144 / 150 | 0.30% | 53 / 55 | 0.38% | 372 ms |
| v5 检测 + v6 medium 识别 | 4.8 + 76.6 MB | 146 / 150 | 0.26% | 55 / 55 | 0% | 6–9 s |

- 出厂 v5 读英文的错：全大写里 O 读成 0（`0AK AVE`、`L0S ANGELES`，正好砸在运单地址上）、`ASOS` → `ASOs`、`LinkedIn` → `Linkedln`。
  v6 small 这几处都对；
- 不降采样没有帮助，这批页面上字够大；
- en_PP-OCRv5（英文专用）英文好一点，但中文全坏。要用就得按行判断文种再分流，多一个模型多一遍识别，不如直接换 v6；
- v6 tiny 快 2.4 倍，但字典只有 6904 字（v5 和 v6 small 是一万八千多），生僻字人名有风险；
- PaddlePaddle 自己的数字方向一致：PP-OCRv6 论文的英文榜（表 11）上 v6 small 86.3%、en_PP-OCRv5 86.0%、PP-OCRv5 mobile 78.3%。

换 v6 small 时 `CtcDecoder` 的约定（输出 = 空白 + 字典 + 空格）照样成立，不用改代码。要注意：v6 small 的字典比 v5 少了 35 个字符，
大多是西里尔字母和国旗表情，但也少了一个「兔」；DB 后处理的阈值这次沿用了 v5 的（0.3 / 0.6 / 1.5），v6 官方配置是 0.2 / 0.45 / 1.4，没调过。

### 规则与模型

OCR 用 v6 small，系统地区按中文系统（CN）。误报只算落在「不该圈」清单上的（品牌、店名、商品名、价格）。

| | 练习页 42 处 | 误报 | 留出页 19 处 | 误报 |
|---|---|---|---|---|
| 出厂规则 | 6（人名 0/15、地址 0/18） | 0 | 4（人名 0/8、地址 0/7） | 0 |
| **出厂 + 英文规则原型** | **38**（人名 12/15、地址 18/18） | 0 | **13**（人名 3/8、地址 6/7） | 0 |
| GLiNER PII edge，uint8（46 MB），单独 | 31 | 5 | 13 | 1 |
| GLiNER PII edge，fp32（181 MB），单独 | 35 | 7 | 17 | 1 |
| 原型 + GLiNER uint8 | 41 | 5 | 18 | 1 |
| 原型 + GLiNER fp32 | 42 | 7 | 19 | 1 |

- 中文页上，加不加英文原型结果完全一样（17/20，一处不多一处不少）；
- 换回出厂的 v5 OCR，原型是 38/42、12/19：差的那处就是运单上被读成 `0AK` 的地址；
- GLiNER 的误报：Starbucks、Hoxton、Shoreditch、UberX、Chipotle、商品名里的「737」、全大写的 SUPPLY。另外还圈了订单号、日期、
  「City」「home」「Dasher」这类字（uint8 共 14 处，fp32 共 15 处），自动打码会让用户一直在跟 app 对着干；
- GLiNER 量化之后召回掉得明显（留出页 17 → 13），与社区里对这类模型量化的反馈一致；
- GLiNER 速度：uint8 每页 36–46 ms（Python 版 ONNX Runtime，同一台机器），不是瓶颈。

### 原型做了什么

代码在 `tools/model-eval/ocr-dump/src/main/kotlin/EnProto.kt`，没有进 app。全是形状、字段名、名单和旁证，与出厂规则一个路子：

1. **英文地址的形状**：门牌号 + 街名 + 街道后缀（St、Ave、Rd、Blvd、Dr、Ln、Way、Road……），前后可带 Apt、Unit、Suite、Flat、Room、#；
   「城市, 州缩写 ZIP」；英国「城市 邮编」；拼音的「Xuanwu District, Nanjing, Jiangsu 210018」；PO Box。同一行相邻几段连成一段；
2. **字段名单独一行、值在下面**：「Shipping Address」「Delivering to」「Home address」「Pickup」下面行距均匀、左对齐的 1–4 行算地址，
   「Full name」「Passenger」下面一行算人名。行距突然变大就停。**这是 app 里没有的一种机制**：现在的规则逐行跑，只有要旁证的猜测会看邻行；
3. **英文人名字段名**，不区分大小写：Full name、Guest name、Recipient、Passenger、Cardholder、Bill to……；
4. **名单 + 旁证**：相邻两个大写词，前一个在美国人口普查局的常见名里（5163 个，公有领域），后一个在常见姓里（前 3 万个）；
   或者是拼音名（常见姓的拼音 + 拼音音节）。与 HanLP 认出的中文名一样只算猜测（`NeedsAnchor`），附近要有电话、邮箱、地址、字段名；
   旁证表里加了英文地址和外国电话；
5. **不要旁证的**：Mr / Ms / Dr + 名；邮件里的「Sarah Johnson <sarah@…>」；「Hi David,」「Thanks for riding, Kevin」这类称呼；
   顶栏「< Emily Carter」（要过名单，「< Order Details」不算）；
6. **电话**：没有汉字的行，再按 US、GB 各认一遍。

留出页上没认出的：聊天句子里的「Rob Fletcher」「Sophie Lambert joined…」、外卖页的「Your Dasher, Marcus」（单个名、没有旁证），
联系人页大字号的「Daniel Ortiz」（电话在下面三行开外，超出了旁证的距离），结账表单左右两栏里的「Nina」和一个孤零零的德国邮编「10119」。
这几类与中文那边一样，是规则的边界：句子里没带称呼、附近没有旁证的人名，规则认不出。

## 建议

按投入产出排：

1. **两处小修**（一天以内）：
   - `SemanticPrompt`：人名长度上限按文种算，拉丁字母的名按词数（比如不超过 4 个词）而不是 8 个字符；
   - `PhoneRule`：没有汉字的行再按 US、GB 试一遍（或者设置里加一个「常用地区」）。要配单测确认订单号、确认号不会被当成电话；
2. **英文规则**（主体工作）：把原型的几种认法搬进 `rules/`，照现有的写法一条认法一个类、一组单测：
   - `DefaultRuleSet`：英文字段名进字段名表，`LabeledField` 加不区分大小写的选项；
   - 新的 `EnglishAddressShape`，与 `AddressShape` 并列放进地址规则；
   - `RuleClassifier` 加「字段名在上、值在下」：中文界面的个人资料页、表单其实也是这个版式，这次顺带受益；
   - 名单（普查局数据，约 260 KB 文本，可以只留前若干千个再压缩）+ `NeedsAnchor`；`nameAnchors` 加英文地址、外国电话、英文字段名；
3. **OCR 升到 PP-OCRv6 small**（独立的一件事）：换三个文件，更新 `docs/ppocr-models.md`。上线前要做：真机上量速度和内存
   （这次只在 x86 上量过）、跑 `PaddleTextRecognizerTest`、在真实样本集上对比、看一眼字典里少掉的「兔」、试一下 v6 官方的 DB 阈值；
4. **可选：GLiNER 做英文人名的实验开关**，只圈不打码，只跑没有汉字的行，与 HanLP 在中文这边的定位一样。
   代价是包体 +46 MB，还要在 Android 上自己实现分词（`tokenizer.json`，BPE）和输出解码。先做完 1–3，用真实截图看还剩多少漏认再决定。

不建议的：

| 方案 | 为什么不 |
|---|---|
| en_PP-OCRv5 专用英文模型 | 中文全坏（字符错误率 54%），要按行分流；v6 small 一个模型中英都更好 |
| ML Kit 实体抽取（Entity Extraction） | 不认人名；语言模型要在运行时下载，与「没有网络权限」冲突 |
| Android 系统的 TextClassifier | 不认人名；地址只在部分语言和机型上有，国产 ROM 上的实现各不相同。没有实测 |
| 随包大模型 | 上一次调研已经否了（`docs/on-device-model-research.md`）：体积、内存、速度都撑不起 |

## 局限

- **全是合成页**：干净背景、标准字体。真实截图有浅灰小字、图片底、深色模式，OCR 只会比这里差，表里的错误率是下限；
- **原型是看着练习页写的**，练习页的 38/42 偏乐观，留出页 5 张 19 处是唯一不偏的数字，样本很小；
- 速度只在 x86 服务器 CPU 上量过；
- 最该做的验证是**拿真实漏认的英文截图跑一遍**（不入库）：如果漏认的那几处 OCR 本身就读错了，结论要改成先修 OCR。

## 怎么复现

脚本在 `tools/model-eval/`，与 app 的构建无关。

```bash
cd tools/model-eval
MODEL_EVAL_OUT=out-en python3 make_en_samples.py         # 英文合成页 + 真值（含留出页 en-h-*）
MODEL_EVAL_OUT=out python3 make_samples.py               # 中文页（上一次调研的同一批）
PY=python3.12 ./download_en_eval.sh                       # OCR 候选模型（转 ONNX、抽字典）和人名名单 → models-en/、names/

# OCR：同一套 PpOcrEngine，换检测、识别、字典三个文件；第五个参数 device 按设备口径降采样，full 不降。
# gradle run 的工作目录是 ocr-dump/，路径一律写绝对的
E=$PWD; A=$E/../../app/src/main/assets/ppocr; M=$E/models-en
PAGES=$(ls $E/out-en/samples/*.png $E/out/samples/*.png | tr '\n' ' ')
../../gradlew -p ocr-dump -q run -Pmain=moe.flinty.yomark.OcrBenchKt \
  --args="$E/out-ocr/v5 $A/det.onnx $A/rec.onnx $A/dict.txt device $PAGES"
../../gradlew -p ocr-dump -q run -Pmain=moe.flinty.yomark.OcrBenchKt \
  --args="$E/out-ocr/v6s $M/PP-OCRv6_small_det.onnx $M/PP-OCRv6_small_rec.onnx $M/PP-OCRv6_small_rec.dict.txt device $PAGES"
python3 score_ocr.py out-ocr/v6s out-en/truth out/truth

# 规则：出厂规则 + 英文原型（第四个参数给了名单目录才跑原型）；第三个参数是系统地区
../../gradlew -p ocr-dump -q run -Pmain=moe.flinty.yomark.EnRulesKt --args="$E/out-ocr/v6s $E/out-rules-v6s.json CN $E/names"
python3 score_en_rules.py out-en/truth out-rules-v6s.json

# GLiNER（pip install gliner；CPU 版 torch 即可），可以和规则的结果合起来打分
python3 run_gliner.py out-ocr/v6s out-gliner-v6s.json model_quint8 0.3
python3 score_en_rules.py out-en/truth out-rules-v6s.json out-gliner-v6s.json
```

参考：[PP-OCRv6 论文](https://arxiv.org/abs/2606.13108)、[PaddleOCR 3.7.0 发布说明](https://github.com/PaddlePaddle/PaddleOCR/releases)、
[en_PP-OCRv5_mobile_rec](https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec)、
[GLiNER PII edge](https://huggingface.co/knowledgator/gliner-pii-edge-v1.0)、
[ML Kit 实体抽取](https://developers.google.com/ml-kit/language/entity-extraction/android)、
[美国人口普查局姓氏数据](https://www.census.gov/topics/population/genealogy/data/2010_surnames.html)。
