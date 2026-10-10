# 有码 · 英文人名、地址识别调研（第一轮 2026-10-09，第二轮 2026-10-10）

## 起因

英文截图上的地址、人名基本认不出来。要决定的是：再找一个像 PP-OCR 那样的模型，还是换别的办法。

动手前先拆开看：漏认可能出在两层，**OCR 没把字读对**，或者**字读对了、规则不认得它是人名地址**。
两层的解法完全不同，所以先分别量。第一轮在 15 张合成页上量了个大概；第二轮按「效果优先」把样本扩到 72 张 OCR 压力页、
70 页 501 处的名址基准和 57 页 WebPII 网页截图，把 en_PP-OCRv5 按页分流、十几个 NER / PII 模型都跑了一遍。

## 结论

1. **OCR：换 PP-OCRv6 small，再改一处空格逻辑；不需要 en_PP-OCRv5。** 72 张压力页上，英文的关键片段（人名、地址、邮箱、电话、编号
   一字不差地读出来）出厂 v5 是 91.8%，v6 small 94.2%，再把拉丁字母之间按像素补空格的门槛从 0.3 倍字高提到 0.6 倍、挨着 ASCII 标点不补（「混合空格」，识别模型自己的空格照旧），97.6%；
   中文 96.2% → 99.7%。en_PP-OCRv5 按页分流（一张图不会中英混排，判定 157 页全对）最好也只到 97.0%，逐行择优 97.6% 持平，
   两个模型错在同样的地方，多一个 7.85 MB 的模型换不来提升。网页截图上 v6 small 的提升更明显：规则认出的人名 30 → 40（共 55）。
2. **规则还是主体，但人名的天花板很低。** 按第二轮调研的公开资料补的规则（v3：SSA 名单、模糊名、品牌否决、USPS 街道后缀、
   英加澳爱新的邮编）在 70 页基准上直接打码的一档人名 30%、地址 71%、只有 4 处误报；连「只圈出」的猜测算上，人名也只到 47%。
   漏的大头是名单外的名字：有词不在美国名单里的 53 个人名（尼日利亚、印度、越南、毛利、爱尔兰盖尔语……）规则只认出 5 个。
3. **要把人名做上去只能靠模型，用 GLiNER-PII。** 规则 + 模型之后人名 85–95%、地址 82–97%，三套样本上都成立。两个可选的：
   - **edge（Ettin-32m 底座），fp16，91 MB：召回最稳。** 70 页基准上人名 93%、地址 95%，真实 OCR 91% / 96%，没见过的 WebPII 网页 93% / 94%，
     三套都在最前面，也是最小的。代价是误报：70 页 74 处，多是品牌、店名被当成人名（Ralph Lauren、Wendy's、Jumia、Lalamove）。
     官方 fp16 文件加载不了、官方 uint8 人名掉得厉害（规则 + 模型 93% → 72%），fp16 要用 `fp16_onnx.py` 自己转（结果与 fp32 一致）；
   - **base（DeBERTa-v3-small 底座）加「机构、品牌、商店、商品」几个对手标签：误报最少。** 70 页误报 18，多是名人和以人名命名的品牌、店
     （LeBron、Taylor Swift、Frida Kahlo、Peter Jones 百货），另有几个公开地名，其中 4 处还是规则自己的；人名 89%、地址 92%；但网页上地址只有 82%，文件也大：fp16 333 MB（同样要自己转），
     官方 uint8 197 MB 在网页上地址再掉到 70%；
   - Ettin PII、Rampart、Horizon、gravitee 这些 token 分类模型都不如 GLiNER：要么误报多（品牌、词的碎片），要么地址弱。
4. **模型的结果放在「圈出」一档，不自动打码。** 这与 app「敏感信息由规则决定、AI 复查只圈出」的原则一致，也让 edge 的误报只是多一个虚线框
   （点一下就能切换，导出前会提醒）。规则那部分照旧打码。另加两道便宜的过滤：品牌表否决，以及模型的地址必须「像地址」
   （有字母词又有数字、两个词以上，或者整段是邮编；否则要紧挨着像地址的一行）：edge 的状态栏「5G 82%」「9:41」这类多认因此从 158 处降到 46 处，召回只掉 2 处。
5. **体积是要拍板的地方。** 现在包里 PP-OCRv5 两个模型 21 MB；换 v6 small 是 31 MB；再加 GLiNER-PII edge fp16 是 +91 MB
   （base 是 +333 MB）。`docs/release-checklist.md` 里 25 MB 的下发预算是放 PP-OCR 之前定的，早已不适用，需要重新定。
6. **几处小修，与上面无关、马上能做：** `SemanticPrompt.MAX_NAME = 8` 把英文全名整条丢掉；中文系统上 `PhoneRule` 认不出不带国家码的美国号码；
   出厂的地址字段名规则在英文网页上把「Address Book」「Address line 1:」里字段名后面的字当成地址（WebPII 57 页上多认 45 处，几乎全是这种）。

## 现状：英文进来之后各条规则做了什么

| 认法 | 位置 | 对英文 |
|---|---|---|
| 人名·字段名 | `DefaultRuleSet.NAME_LABELS` + `LabeledField` | 英文字段名只有 `Name`、`Recipient`，区分大小写：「Guest name」「Full name」认不出；值必须和字段名在同一行，「字段名在上、值在下」的版式接不住 |
| 人名·电话前 | `NameBeforePhone` | 只往前收汉字 |
| 人名·从字面猜 | `PersonNameRecognizer`（HanLP） | 只认中文人名，音译名（nrf）还特意排除了 |
| 人名的旁证 | `DefaultRuleSet.nameAnchors` | 称呼、字段名、票种全是中文；英文地址认不出，也就当不了旁证 |
| 地址·字段名 | `ADDRESS` 的字段名表 | 只有 `Address`、`Ship to`、`Deliver to`，区分大小写（「Shipping address」小写的 a 就不行），值要在同一行，多行地址只能取到第一行。反过来，字段名后面是什么都收：「Address Book」收「Book」，「Address line 1:」收「line 1:」，「Ship to this address」收「this address」 |
| 地址·形状 | `AddressShape` | 只认中文门牌（路 + 号、栋、单元、室），整个跑在汉字上 |
| 电话 | `PhoneRule(defaultRegion = 系统地区)` | 中文系统上是 CN：不带 +1 的美国号码（`(614) 293-7781`）认不出。英国的 `07700 900123` 倒是认出了，但那是被当成了广西的座机号，碰巧 |
| AI 复查 | `SemanticPrompt.MAX_NAME = 8` | 超过 8 个字符的人名整条丢掉，「Jennifer Walsh」是 14 个 |
| OCR 的空格 | `WordGaps` | 识别模型输出的空格之外，再按像素间隙补（≥ 0.3 倍字高就断词，两个数字之间 0.45 倍）。英文上补多了：字距宽的全大写字被拆开（网页截图上 `P L A C E OR D E R`） |

## 第一轮实测

### 样本

全是合成页，1080×2340，Inter 字体（接近 Roboto / SF），运单用等宽体。按设备口径降采样到 540×1170 再识别。

- **练习页 10 张、42 处**：美国电商订单、iMessage 聊天、邮件（签名档带地址）、英国酒店订单（住客是拼音名）、英国快递、
  美国运单（全大写）、拼音地址（国际站收货地址）、账户资料页（字段名在上）、收件箱和打车收据（12sp 小字、浅灰）；
- **留出页 5 张、19 处**：通讯录联系人、德国地址的结账表单、群聊、机票（`ZHANG/WEI MR`）、外卖。
  **原型规则写完之后才画的，没照着它们调过**；
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
- en_PP-OCRv5（英文专用）不配合分流时中文全坏，按页分流的结果见第二轮；
- v6 tiny 快 2.4 倍，但字典只有 6904 字（v5 和 v6 small 是一万八千多），生僻字人名有风险；
- PaddlePaddle 自己的数字方向一致：PP-OCRv6 论文的英文榜（表 11）上 v6 small 86.3%、en_PP-OCRv5 86.0%、PP-OCRv5 mobile 78.3%。

换 v6 small 时 `CtcDecoder` 的约定（输出 = 空白 + 字典 + 空格）照样成立，不用改代码。要注意：v6 small 的字典比 v5 少了 35 个字符，
大多是西里尔字母和国旗表情，汉字只少了一个兼容表意字 U+2F80F（字形同「兔」，常用的「兔」U+5154 仍在）。

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
- 留出页上没认出的：聊天句子里的「Rob Fletcher」「Sophie Lambert joined…」、外卖页的「Your Dasher, Marcus」（单个名、没有旁证），
  联系人页大字号的「Daniel Ortiz」（电话在下面三行开外），结账表单左右两栏里的「Nina」和一个孤零零的德国邮编「10119」。

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

第二轮的 v3 在这之上按调研资料补了一批（见下），名单都在 `tools/model-eval/en-lists/`。

## 第二轮实测

### OCR：压力测试页

第一轮的页面太干净，几个模型都在 99% 以上，分不出高下。第二轮另画了 72 页（英文 48、中文 24，`make_ocr_stress.py`），
每页随机组合：7 种英文字体（Roboto、Inter、Open Sans、Lato、Arial 类、Source Serif、Roboto Mono）和 2 种中文字体，
10–18sp 的字号，浅色、深色、深灰、分组卡片四种配色，聊天气泡和彩色顶栏，6 种屏幕分辨率，六成过一遍 JPEG（质量 55–92），
一成五再缩放一次。文字是 Faker 造的人名、地址、邮箱、电话和故意混进 O/0、I/l/1 的订单号。按设备口径降采样后识别。

「关键片段」是人名、地址、邮箱、电话、编号在 OCR 输出里一字不差（含空格）地出现，这是规则能认出它的前提，比字符错误率更贴近要解决的问题。

| 配置 | 英文 CER | 英文行全对 | 英文关键片段 | 中文 CER | 中文关键片段 |
|---|---|---|---|---|---|
| 出厂 PP-OCRv5 mobile | 0.25% | 1650 / 1804 | 730 / 795（91.8%） | 1.88% | 280 / 291（96.2%） |
| PP-OCRv6 tiny | 0.24% | 1668 / 1804 | 728 / 795（91.6%） | 0.73% | 279 / 291（95.9%） |
| PP-OCRv6 small，app 的空格 | 0.08% | 1714 / 1804 | 749 / 795（94.2%） | 0.29% | 288 / 291（99.0%） |
| **PP-OCRv6 small，混合空格** | **0.08%** | **1778 / 1804** | **776 / 795（97.6%）** | **0.29%** | **290 / 291（99.7%）** |
| PP-OCRv6 small，只用模型的空格 | 0.08% | 1755 / 1804 | 777 / 795（97.7%） | 0.29% | 291 / 291 |
| PP-OCRv6 small，v6 官方 DB 阈值 | 0.08% | 1715 / 1804 | 746 / 795（93.8%） | 0.31% | 288 / 291 |
| v6 small 检测 + v6 medium 识别 | 0.14% | 1705 / 1804 | 751 / 795（94.5%） | 0.26% | 288 / 291 |
| 按页分流：英文页换 en_PP-OCRv5 识别 | 0.15% | 1704 / 1804 | 743 / 795（93.5%） | 0.29% | 288 / 291 |
| 按页分流 + 模型的空格 | 0.15% | 1763 / 1804 | 771 / 795（97.0%） | 0.29% | 291 / 291 |
| 逐行择优：v6 small 混合空格 / en_PP-OCRv5，取置信度高的 | 0.10% | 1776 / 1804 | 776 / 795（97.6%） | 0.29% | 290 / 291 |

- **空格是英文这边最大的一笔账。** app 保留识别模型自己输出的空格（CTC 的最后一类），再由 `WordGaps` 按像素间隙补：
  间隙 ≥ 0.3 倍字高就断词，两个数字之间要 0.45 倍。中文页上这样是对的；英文上补多了，字距宽的字体、全大写的按钮字、
  打码的号码被拆开（网页截图上 v5 读成 `P L A C E OR D E R`，压力页上 `187* * * * 9157`）。**混合空格**（`PpOcrEngineX` 的 `OCR_SPACES=latin`）
  只收紧拉丁字符之间按像素补的空格，模型自己的空格照旧保留：
  - 两个字里有一个不是 ASCII（汉字、全角标点、中点）：照 `WordGaps`，与现在一样；
  - 两个数字之间：照 `WordGaps`（「2026-09-30 14:22:05」的日期和时间要分开）；
  - 挨着 ASCII 标点：不按像素补；
  - 两个拉丁字母、字母和数字之间：像素间隙要到 0.6 倍字高才补。
  英文行全对 1714 → 1778、关键片段 94.2% → 97.6%；中文字符错误率不变（0.29%），汉字两侧的空格没动，746 行里只变了 4 行，
  都在 ASCII 标点旁（打码的手机号「187****9157」不再被拆开），中文关键片段 288 → 290。网页上的 `P L A C E OR D E R` 变成 `PLAC E ORDER`，好了大半。
  只用模型的空格（完全不按像素补）英文关键片段差不多（97.7%），但行全对低 23 行：模型在「Parker Group · Operative」「Tue, Oct 28 · 12:41 PM」
  这种中点两边不出空格，混合空格对非 ASCII 字符照 `WordGaps`，补上了；中文行全对它反而更高，因为 `WordGaps` 会在中文标点两边补空格、
  真值里没有——但中文规则是照着 `WordGaps` 的输出写的，那边要不要动是另一件事，混合空格刻意不动汉字两侧；
  反过来的粘连（网页上出厂 v5 读出的 `8529Graham`、`TateMeadow`）是识别模型没出空格、像素间隙又不到门槛，改空格逻辑救不了，
  换 v6 small 就好了（它自己出这个空格）；
- **v6 small 几乎在每一种条件下都比 v5 好**（例外是英文 1080×2400 一档，0.10% 对 0.08%）：英文差得最多的是 Open Sans（v5 0.48%、v6 small 0.07%）和 Roboto Mono（0.48% 对 0.14%），配色里是深色模式（0.35% 对 0.07%）；
  中文 v5 在各条件下都在 1.3–2.5%，v6 small 除了 1080×2340 一档（1.39%）都在 1% 以下；
- **v6 官方的 DB 阈值（0.2 / 0.45 / 1.4）不比 app 现在的（0.3 / 0.6 / 1.5）好**，英文关键片段 93.8% 对 94.2%，沿用 app 的；
- **v6 medium 识别不值**：76.6 MB，整页 6–9 秒（small 不到 1 秒），这批页上英文反而比 small 差一点（0.14% 对 0.08%）；
- **v6 tiny** 中文比 v5 好，英文与 v5 持平，字典只有 6904 字，不考虑。

#### en_PP-OCRv5：按页分流也用不上

按「一张图不会中英混排」的约定试了按页分流：先用中文模型识别，汉字 / (汉字 + 拉丁字母) 低于 0.1 的判为英文页，同一批检测框换
en_PP-OCRv5（7.85 MB）再识别一遍（`route_ocr.py`）。判定本身毫无问题：三套页面 157 页一页没判错，英文页的汉字占比都是 0，
中文页最低 0.92。问题是 en_PP-OCRv5 不比 v6 small 强：

- 用 app 的空格，分流后英文关键片段 93.5%，比 v6 small 不分流（94.2%）还低；配模型的空格或混合空格 96.7–97.0%，仍低于 v6 small 混合空格的 97.6%；
  字符错误率 0.15% 对 0.08%；
- 再退一步，逐行比两个模型的置信度、取高的（`ensemble_ocr.py`）：97.6%，与 v6 small 单独一样，字符错误率还略高（0.10%）。
  两个模型大多错在同样的地方（订单号里的 O/0、I/l）：1804 行里 en_PP-OCRv5 对、v6 small 错的只有 7 行，反过来是 26 行，两边都错 19 行。
  按置信度能把那 7 行里的 5 行换对，却同时把 v6 small 本来对的 7 行换成 en_PP-OCRv5 的错读，净少 2 行；
- 第一轮的干净页上分不出：v5 检测 + en_PP-OCRv5 识别 146/150 行全对，v6 small（检测 + 识别）147/150；同用 v5 检测时 en_PP-OCRv5 识别 146、v6 small 识别 144，都在噪声以内。

所以 en_PP-OCRv5 不加：多 7.85 MB、英文页多一遍识别，换不来任何提升。PaddleOCR 论文的英文榜（v6 small 86.3、en_PP-OCRv5 86.0）也是这个意思。

### 人名地址：两套新基准

第一轮的留出页只有 19 处，撑不起「规则还是模型」这种判断。第二轮做了两套：

- **代理出题的 70 页、501 处**（`make_ner_bench.py`）：五组 app 类别（购物物流、聊天、邮件办公、出行金融、表单政务医疗）
  各 14 页，由几个互不知情的代理分头写页面（美、英、加、澳、新、爱、印、尼日利亚、新加坡、德国、中国拼音），
  另一个代理逐页核对真值。出题的人没看过原型规则。真值分三类：find（人名 210、地址 145、证件编号 70、电话 50、邮箱 26），
  avoid（不该遮的品牌、店名、名人、公开地名，259 处），neutral（遮不遮都行的，比如单独的城市名）。
  同一批页面出两份输入：「完美 OCR」（原文 + 框，只比认人名地址的本事）和 v6 small 混合空格的真实 OCR；
- **WebPII 测试集抽的 57 页**（`convert_webpii.py`，Apache-2.0）：真实电商网页模板、1280 宽的桌面截图，
  值是 Faker 造的（「Lake Cassieton」「Changfurt, MI 61434」）。人名 55、地址 66、电话 17、邮箱 4（筛过之后，见下）。
  这一套原型规则和模型都没见过，也没对着它调过。它有两处要处理：表单的 empty、partial 变体里输入框在截图上是空的，
  标注却有值，按 OCR 里找不找得到把这 19 处挪进了 neutral（`webpii_filled.py`）；地址常只标邮编一段，
  而规则、模型遮的是整行「South Brenda, PA 76715-7608」，打分改成多出来的部分都是同页别的真值或 neutral 时也算命中。

打分口径同第一轮，另外两条：不分大小写（网页用 CSS 转大写）；「误报」只算 avoid，
「另外」是 find、avoid、neutral 都不沾的多认（状态栏时间、按钮字、单独的「London」……），分开列，因为里面混着出题人漏标的真隐私。

**训练数据污染**：Rampart 用 OpenPII 训练，Horizon 用了 OpenPII、Nemotron-PII、Gretel，Ettin 用 Nemotron-PII。
上面两套都不是从这些数据集来的，没有污染；反过来说，不能拿这些公开集给这几个模型打分。

### 规则 v3

v2（第一轮的原型）在基准出题之前冻结；v3 只用第二轮调研找到的公开资料补，没有照着基准页调，但写 v3 时看过 v2 在这批页上的分数，
所以 v3 的数字可能偏乐观一点，WebPII 那套才是完全没见过的。v3 加的：

- 名：普查局的 5163 个之外加 SSA 前 2 万个（1950 年以来出生的美国人）；Will、Grant、Park 这种既是名又是普通词的（WordNet），
  名和姓两头都占就不算，单个的模糊名后面要跟一个姓；整段是品牌名（OSM name-suggestion-index，16419 个）的不算猜出的人名；
- 机票的「LEUNG/CHI HO KELVIN MR」，邮件头的「To: / From:」，更多人名、地址字段名；
- 地址：USPS Pub 28 的全部街道后缀和房号用词、「4/18」这种门牌、方向词；美国州的全名；英国（严格式）、加拿大、
  澳大利亚（州 + 四位邮编）、爱尔兰 Eircode、新加坡的邮编。

| 70 页基准，完美 OCR | 人名（210） | 地址（145） | 电话（50） | 误报 |
|---|---|---|---|---|
| 出厂规则 | 0 | 0 | 28 | 0 |
| v2，打码一档 | 43（20%） | 80（55%） | 39 | 7 |
| v2，打码 + 圈出 | 62（30%） | 80（55%） | 39 | 33 |
| **v3，打码一档** | **62（30%）** | **103（71%）** | 39 | **4** |
| v3，打码 + 圈出 | 99（47%） | 103（71%） | 39 | 15 |

换成真实 OCR（v6 small 混合空格），v3 打码一档人名 56、地址 102、误报 4；用出厂 v5 的 OCR 是 59、100：干净的手机截图上两种 OCR 差不多，
差在检测框的位置让「字段名在上、值在下」的配对时有时无。**网页截图上差得多**（1280 宽、字高约 14px，不降采样）：

| WebPII 57 页，规则 v3 | 人名（55） | 地址（66） | 电话（17） | 另外 |
|---|---|---|---|---|
| 出厂 v5 OCR，打码一档 | 30（55%） | 36（55%） | 16 | 65 |
| **v6 small 混合空格，打码一档** | **40（73%）** | **39（59%）** | 16 | 61 |
| v6 small 混合空格，打码 + 圈出 | 43（78%） | 40（61%） | 16 | 72 |

v5 把「8529 Graham Isle」读成「8529Graham Isle」、「051 Tate Meadow」读成「051 TateMeadow」，街道后缀就对不上了；
人名、地址整段读对（不分大小写）的 v5 112 / 121、v6 small 116 / 121。

规则还漏的，基本是名单和形状够不着的：

- **名单外的名字**：基准里有 53 个人名至少有一个词（称谓、单个字母的缩写不算）不在美国名单（普查局名、姓 + SSA 前两万）里，
  规则只认出 5 个（漏的如 Adeyemi-Clarke、Raghunathan、Aroha Ngata、Gráinne Ní Mhurchú、Seo-yeon Choi……）；
  带重音、连字符、撇号的也常漏（Schäfer-Brandt、O'Driscoll 整个没认出），认出的有时只截到一半（「Rosa Delgado-Mu」「Ms. Eze」）；
- **姓在前**：「NGUYEN, THU HA」「HUYNH, KHANH LINH」「Oyelaran, B.」；
- **没有旁证的单个名**：礼品留言、送货备注里的「Kemi」，账单问候语里的「Tunde」，聊天里的「Jess」，邮件开头称呼的「Rhys」；
- **地址**：单独一行的城市（「Ballincollig」），印度式地址（「B-604, Sea Breeze CHS」），
  网页顶栏的「Deliver to Angela · Port Emily 24847」；
- **WebPII 特有的**：「Hello, William」这类问候、表单里小写的名字（「megan newman」）、搜索框里的邮编。

### 模型

都只跑没有汉字的页（`run_ner.py`，整页的行用换行连起来送进去），阈值 0.3（另注明的除外）。模型认出的电话、邮箱、证件号不计（交给规则），
只比人名、地址两类。两道过滤对所有模型一样：整段是品牌名的不要（NSI 品牌表，多词的品牌也认不带「's」的写法）；
地址要「像地址」（`summarize_bench.py --addr-gate`：有字母词又有数字、两个词以上，或者整段是邮编；不像的要紧挨着像地址的一行，
留住地址块里单独一行的城市）。「规则 + 模型」是规则的打码一档加上模型的人名、地址。

**70 页基准，完美 OCR：**

| 模型 | 文件 | 许可 | 规则 + 模型：人名 | 地址 | 误报 | 另外 | 模型单独：人名 | 地址 | 误报 |
|---|---|---|---|---|---|---|---|---|---|
| 只用规则 v3（打码一档） | — | — | 62（30%） | 103（71%） | 4 | 19 | | | |
| GLiNER-PII base + 对手标签 | fp32 665 MB / fp16 333 MB | Apache-2.0 | **186（89%）** | 133（92%） | **18** | 26 | 178（85%） | 114 | 14 |
| 同上，阈值 0.2 | 同上 | Apache-2.0 | 193（92%） | 138（95%） | 21 | 41 | 186（89%） | 134 | 17 |
| 同上，官方 uint8 | 197 MB | Apache-2.0 | 181（86%） | 131（90%） | 19 | 28 | 173（82%） | 108 | 15 |
| 同上，官方 uint8，阈值 0.2 | 197 MB | Apache-2.0 | 190（90%） | 138（95%） | 21 | 42 | 185（88%） | 131 | 17 |
| GLiNER-PII base | fp32 665 MB | Apache-2.0 | 187（89%） | 129（89%） | 22 | 29 | 179（85%） | 107 | 18 |
| GLiNER-PII base，官方 uint8 | 197 MB | Apache-2.0 | 181（86%） | 128（88%） | 20 | 26 | 172（82%） | 98 | 16 |
| **GLiNER-PII edge，fp16（自己转）** | 91 MB | Apache-2.0 | **196（93%）** | **138（95%）** | 74 | 63 | 192（91%） | 130 | 71 |
| GLiNER-PII edge + 对手标签 | 91 MB | Apache-2.0 | 178（85%） | 138（95%） | 35 | 49 | 168（80%） | 123 | 31 |
| 同上，阈值 0.2 | 91 MB | Apache-2.0 | 181（86%） | 140（97%） | 39 | 94 | 171（81%） | 131 | 35 |
| GLiNER-PII edge，官方 uint8 | 46 MB | Apache-2.0 | 151（72%） | 135（93%） | 44 | 57 | 126（60%） | 113 | 41 |
| GLiNER-PII small | fp32 327 MB | Apache-2.0 | 189（90%） | 139（96%） | 64 | 53 | 184（88%） | 130 | 60 |
| GLiNER-PII large | uint8 648 MB / fp32 1.76 GB | Apache-2.0 | 182（87%） | 127（88%） | 13 | 22 | 168（80%） | 86 | 9 |
| Ettin-32m Nemotron PII | bf16 64 MB（fp32 ONNX 128 MB） | MIT | 180（86%） | 136（94%） | 51 | 29 | 173（82%） | 115 | 47 |
| Ettin-68m Nemotron PII | bf16 137 MB（fp32 ONNX 274 MB） | MIT | 177（84%） | 135（93%） | 40 | 26 | 167（80%） | 126 | 36 |
| Horizon pii-redactor-small | fp32 563 MB（另有 int8 268 MB，没测） | Apache-2.0 | 195（93%） | 118（81%） | 29 | 28 | 191（91%） | 79 | 25 |
| Rampart | q4 15 MB | CC BY 4.0 | 169（80%） | 121（83%） | 53 | 32 | 162（77%） | 63 | 50 |
| gravitee bert-small | int8 29 MB | Apache-2.0 | 190（90%） | 103（71%） | 46 | 43 | 182（87%） | 0（没有地址类） | 42 |

**真实 OCR（v6 small 混合空格）**上 GLiNER 各型号的人名掉 2–5 个百分点，Ettin、Rampart 只掉 0–1 个点，地址基本不变；
前几名（edge、small、base）的排序不变：规则 + base 对手标签 人名 85%、地址 91%、误报 16，
阈值 0.2 时 88%、97%、误报 19（官方 uint8：0.3 时 83%、90%、14，0.2 时 88%、95%、21）；规则 + edge 91%、96%、误报 74；
规则 + edge 对手标签 81%、95%、误报 34。

**WebPII 57 页**（真实 OCR；这一套的 avoid 只有 9 个商品品牌，「误报」几乎量不出来，看「另外」）：

| WebPII 57 页 | 规则 + 模型：人名（55） | 地址（66） | 另外 | 模型单独：人名 | 地址 | 另外 |
|---|---|---|---|---|---|---|
| 只用规则 v3（打码一档） | 40（73%） | 39（59%） | 61 | | | |
| GLiNER-PII base + 对手标签 | 52（95%） | 54（82%） | 147 | 49 | 54 | 86 |
| 同上，阈值 0.2 | 52（95%） | 56（85%） | 333 | 49 | 56 | 272 |
| 同上，官方 uint8 | 52（95%） | 46（70%） | 91 | 48 | 36 | 30 |
| 同上，官方 uint8，阈值 0.2 | 53（96%） | 51（77%） | 235 | 50 | 44 | 174 |
| GLiNER-PII base | 49（89%） | 53（80%） | 106 | 39 | 53 | 45 |
| GLiNER-PII base，官方 uint8 | 48（87%） | 46（70%） | 69 | 36 | 38 | 8 |
| **GLiNER-PII edge** | **51（93%）** | **62（94%）** | 172 | 49 | 61 | 112 |
| GLiNER-PII edge + 对手标签 | 49（89%） | 61（92%） | 188 | 43 | 59 | 128 |
| GLiNER-PII edge，官方 uint8 | 44（80%） | 56（85%） | 119 | 23 | 40 | 58 |
| GLiNER-PII small | 51（93%） | 55（83%） | 299 | 47 | 52 | 240 |
| Ettin-68m | 53（96%） | 52（79%） | 113 | 52 | 49 | 52 |
| Ettin-32m | 48（87%） | 41（62%） | 236 | 47 | 33 | 175 |
| Rampart | 52（95%） | 40（61%） | 107 | 49 | 27 | 46 |

「另外」里规则自己就占 61（大半是出厂地址字段名规则的「Book」「line 1:」），模型多出来的是表单的提示字（「Street address, P.O. box」「claim code」）、
州名列表（「Colorado, Oklahoma, South Dakota」）、商品名，以及真值只标了一部分的 Faker 地址的其余片段。这一套上 Ettin-68m 人名最好，
但它在 70 页基准上误报是 base 的两倍多；几套合起来看，GLiNER-PII base 和 edge 仍是两头。base 放到 0.2，网页上多出来的 186 个框，
大多（净增约 134 个）是自提柜页面地图上的街名和地名碎片（「Mission Street」「Fulton Street」「383, East」），其余是表单字段名
（「billing address」「Address line 1」）、「you」和几处「United States」；召回只多 2 处地址。

- **名单外的名字是模型的主要收益**：上面那 53 个有词不在美国名单里的人名，GLiNER-PII base + 对手标签单独认出 42 个，规则（打码 + 圈出）5 个；
  名单里的 157 个，模型 135、规则 94；
- **对手标签**：给 GLiNER 多几个「organization、brand、store、product」标签，品牌和店名被这些标签认走，就不会落到 person 上。
  edge 的误报 71 → 31，代价是人名 91% → 80%；base 本来就不太混，误报 18 → 14，人名不变；
- **剩下的误报**：base + 对手标签单独 14 处，人名类 7 处几乎都是名人和以人名命名的店（LeBron、Taylor Swift、Frida Kahlo、Peter Jones 百货、
  R.M. Williams、Sir John Soane），地址类 7 处是公开的地名、店名（Melbourne、Sydney、Hong Kong、Tan Tock Seng 医院）；Dan Murphy's、Trader Joe's
  模型也认成了人名，被品牌表挡掉了。edge 还会把 Ralph Lauren、Wendy's、Jumia、Lalamove 这类品牌当人名，品牌表只挡住一部分；
- **「另外」**：edge 最多的是状态栏（「5G 82%」「9:41」）和单个词（「Town」「Mobile」），地址门槛之前 158 处，之后 46 处，召回只掉了 2 处；
- **token 分类模型**（Ettin、Rampart、Horizon、gravitee）：BPE / WordPiece 的子词会被切成几截报出来（「Hil」「ield」「ong」），
  品牌、店的地址（「Walgreens, 1554 N Milwaukee Ave」）也算进去；Rampart 和 Horizon 的地址类只认到街道，城市、邮编常常漏；
- **阈值**：GLiNER 的分数很不均匀，edge 在 0.5 时人名从 91% 掉到 61%，0.7 时几乎什么都不认。0.3 加上面的过滤比抬阈值划算。
  base 不一样：阈值往下放更好。**base + 对手标签在 0.2 时，规则 + 模型人名 92%、地址 95%、误报 21**，召回追上 edge 0.3、误报不到它的三分之一；
  base（不加对手标签）0.15 时人名 96%、地址 97%，误报 38。edge 降到 0.4 是 89%、92%、误报 49，加对手标签降到 0.2 是 86%、97%、误报 39，都不如 base 0.2。
  但 0.2 在网页上框多得多（见上表）；用 base 的话默认 0.3、把 0.2 挂在 app 已有的「宽松」上。

### 量化与体积

- **GLiNER-PII 官方的 fp16 文件在 ONNX Runtime 1.31 上都加载失败**：edge（90.8 MB）是 rotary 位置编码里一个 Cast 输出 float16、下游要 float，
  base（333 MB）是 embeddings 里的 Cast，Ettin 的 fp16 也一样。`fp16_onnx.py` 把 rotary 那段和所有「Cast 到 float」的节点留在 fp32、其余转 fp16，
  再删掉转换器插重的 Cast：edge 91 MB，70 页上与 fp32 的结果一样（66 页逐字相同，召回、误报一个不差）；base 333 MB，70 页逐字相同；
- **官方 uint8**：edge 掉得厉害（人名 91% → 60%）；base 是 DeBERTa，70 页上量化友好得多（加对手标签，0.3 时人名 85% → 82%，0.2 时 89% → 88%），
  但 WebPII 网页上地址从 82% 掉到 70%；
  Ettin 只把词表 int8 也掉十几个点；
- **速度**（x86 服务器 4 vCPU 的容器，Python 版 ONNX Runtime，单页中位数，CPU 有争用，只作相对比较）：edge uint8 约 80 ms、fp16 约 100 ms，base uint8 约 130–170 ms、
  fp16 约 190 ms，Rampart 20 ms。手机上大概慢 2–4 倍，与 OCR 的一秒左右相比不是瓶颈；fp16 在 ARM 上走不走 fp16 的算子要真机看。

## 建议

**2026-10-10 的决定**：不加 GLiNER 这类人名地址模型。海外机型上 Gemini Nano 覆盖较多，英文人名交给 AI 复查（前提是先修
`SemanticPrompt.MAX_NAME`，见第 1 条）；OCR 换成 PP-OCRv6 small，只换了模型，空格逻辑没动（见 `docs/ppocr-models.md`）。
下面第 4、5、6 条因此暂不做，第 2 条的混合空格、第 3 条的英文规则仍是可选的后续。

**同日后续**：第 1 条的 `MAX_NAME` 和第 3 条的英文规则已经做了。`SemanticPrompt` 的人名上限改成「有汉字的 8 个字，拉丁字母的 4 个词、40 个字符」；
原型 v3 搬进了 `rules/`：`EnglishAddressShape`（地址）、`EnglishNameCues`（不要旁证的写法）、`EnglishNameGuess`（名单猜测，要旁证），
名单在 `app/src/main/resources/moe/flinty/yomark/rules/en/`（约 630 KB），旁证表加了英文字段名一行。没搬的：字段名单独一行、值在下面的
（`pageFinds`，app 里还没有这种机制）、`PhoneRule` 的 US/GB 再试、混合空格。出厂规则在 v6 small 混合空格的真实 OCR 上（`en-rules … US`）：

| | 人名 | 地址 | 误报 |
|---|---|---|---|
| 70 页基准，改前 | 0/210 | 0/145 | 0 |
| 70 页基准，改后 | 50/210 | 84/145 | 1（聊天里的「Ralph Lauren」） |
| 原型 v3 打码一档（含 `pageFinds` 和外国电话） | 56/210 | 102/145 | 4 |
| WebPII 57 页，改前 | 10/55 | 4/66 | 0 |
| WebPII 57 页，改后 | 42/55 | 38/66 | 0 |

中文系统（`CN`）上人名 51/210、地址不变。第一轮的 15 张页上，中文页的结果与改前逐条相同。漏掉的人名大多不在美国普查名单里
（尼日利亚、印度、毛利、爱尔兰语的名字）或者在字段名下一行，这部分靠 AI 复查。

按先后：

1. **小修**（各一天以内，与英文模型无关）：
   - `SemanticPrompt`：人名长度上限按文种算，拉丁字母的名按词数（比如不超过 4 个词）而不是 8 个字符；
   - `PhoneRule`：没有汉字的行再按 US、GB 试一遍（或者设置里加一个「常用地区」），配单测确认订单号、确认号不会被当成电话；
   - 出厂的地址、人名字段名规则：值不能以冒号结尾、不能是另一个字段名或「Book」「Details」这类界面词，字段名后面要有分隔（冒号、换行、两个以上空格）；
2. **OCR 换 PP-OCRv6 small + 混合空格**：换三个文件（`docs/ppocr-models.md` 跟着改），`WordGaps.isBreak` 里拉丁字母、数字之间的判断改成
   `PpOcrEngineX` 的 latin 口径（两个拉丁字母之间以模型的空格为准、像素门槛 0.6 倍字高；挨着 ASCII 标点不补；两个数字、凡是有非 ASCII 字符的照旧）。
   DB 阈值不动。上线前：真机量速度和内存（只在 x86 上量过）、跑 `PaddleTextRecognizerTest`（字典少的 35 个字符里没有常用汉字）；
3. **英文规则**：把原型 v3 搬进 `rules/`，照现有的写法一条认法一个类、一组单测（第一轮建议里的四项，加上 v3 的名单和邮编格式）。
   名单约 650 KB 文本（`en-lists/` 约 390 KB，加上 `download_en_eval.sh` 生成的普查局名、姓约 260 KB），可以只留常用的再压缩。**只跑没有汉字的行**，不靠「这一页是英文页」的判断：中文页里夹的英文地址照样能认；
4. **英文人名地址模型，放在「圈出」一档**：
   - 首选 **GLiNER-PII edge fp16 + 品牌表 + 地址门槛**（91 MB，阈值 0.3）：三套样本上召回都在最前面，体积最小；多出来的误报是虚线框；
   - 如果试用下来嫌框多，换 **GLiNER-PII base fp16 + 对手标签**（333 MB，阈值 0.3，「宽松」0.2）：手机截图上误报只有 edge 的四分之一，
     网页上地址弱一些。官方 uint8（197 MB）在网页上地址再掉 12 个点，不建议为省体积用它；
   - 要做的事：ModernBERT 的 BPE 分词（edge）或 DeBERTa 的 SentencePiece（base）用 Kotlin 写，GLiNER 的输入拼装和输出解码
     （标签提示 + 词级的打分，几百行），真机上量 fp16 的速度和内存；设置里像 AI 复查一样给一个开关；
   - 与 app「由规则决定、不交给模型」的原则：模型只圈出、不打码，与 AI 复查同一个定位。用户可以在设置里把这一类改成直接打码；
5. **体积先拍板**：现在 21 MB 的模型；v6 small 后 31 MB；再加 edge fp16 是 122 MB（base fp16 是 364 MB）。要不要拆成「英文增强」的单独安装包
   （GitHub 的 APK 多一个变体；Play 上用按需分发要先确认不需要网络权限）是产品上的决定；
6. **再往上走**：拿 Ettin-32m 在这类截图文字上自己微调（人名、地址两类加对手类），有机会兼得 edge 的召回和 base 的精度、体积压到 64 MB（fp16）。
   需要一批不来自基准的训练数据（可以用 `make_ner_bench.py` 的出题方式另外生成），评测仍用这里的两套。

不建议的：

| 方案 | 为什么不 |
|---|---|
| en_PP-OCRv5，单独用或按页分流 | 中文全坏；按页分流后英文仍不如 v6 small 混合空格，逐行择优也没有提升，两个模型错在同样的地方 |
| PP-OCRv6 medium 识别 | 76.6 MB，整页 6–9 秒，这批页上不比 small 好 |
| GLiNER-PII 的官方 uint8 | edge 人名召回掉三分之一；base 的手机截图上掉得少，但网页地址掉 12 个点 |
| Rampart（15 MB） | 小，但人名 80%、地址类只认到街道，误报与 edge 相当 |
| Horizon、Ettin、gravitee | Horizon、Ettin 体积与 GLiNER 相当或更大，效果不如它；gravitee 虽小（int8 29 MB），但没有地址类，误报也比 base 多 |
| GLiNER-PII large | 最准但最慢（单页十几秒），uint8 也有 648 MB |
| Desert Ant redact、Piiranha | 许可不行（遥测与设备数上限；CC BY-NC-ND） |
| ML Kit 实体抽取 | 不认人名；语言模型要在运行时下载，与「没有网络权限」冲突 |
| Android 系统的 TextClassifier | 不认人名；地址只在部分语言和机型上有，国产 ROM 上的实现各不相同 |
| 随包大模型 | 上一次调研已经否了（`docs/on-device-model-research.md`）：体积、内存、速度都撑不起 |

## 局限

- **还没有一张真实的手机截图**。三套样本都是合成或仿制的：代理写的页面、Faker 的值、WebPII 的电商模板。真实截图里有头像、图片底、
  奇怪的字体，OCR 和模型都只会更差；最该做的验证仍是拿真实漏认的英文截图（不入库）跑一遍；
- 代理出题的基准由同一类模型写成，句式、人名的分布可能与真实聊天不同；GLiNER-PII 的训练数据没有公开，无法排除它见过类似的合成文本；
- 规则 v3 写的时候看过 v2 在基准上的分数（见上）；地址门槛、品牌表的所有格处理、base 的阈值 0.2 都是看着 70 页基准定的，
  WebPII 是这些之后才跑的（0.2 在那里多出很多框，正是这个偏差的例子）；
- 速度只在 x86 服务器 CPU 上量过；fp16 在 ARM 上的表现、内存占用都没量；
- WebPII 的 avoid 只有商品品牌，误报这一项在那套上量不出来。

## 怎么复现

脚本在 `tools/model-eval/`，与 app 的构建无关。Python 3.12 的虚拟环境装 `onnxruntime onnx gliner transformers torch faker pyyaml pillow segno`（CPU 版即可）；
另需系统字体 Inter、Liberation Sans、DejaVu Sans Mono、文泉驿正黑（Debian：`fonts-inter fonts-liberation fonts-dejavu-core fonts-wqy-zenhei`）。

```bash
cd tools/model-eval
PY=python3.12 ./download_en_eval.sh                       # OCR 候选模型、名单（含 en-lists/）、字体 → models-en/、names/、fonts/
MODEL_EVAL_OUT=out-en python3 make_en_samples.py         # 第一轮：英文合成页 + 真值（含留出页 en-h-*）
MODEL_EVAL_OUT=out python3 make_samples.py               # 第一轮：中文页
MODEL_EVAL_OUT=out-stress python3 make_ocr_stress.py     # 第二轮：OCR 压力页
MODEL_EVAL_OUT=out-ner python3 make_ner_bench.py ner-bench-pages.json   # 第二轮：名址基准 70 页（samples、truth、oracle）

# OCR：同一套 PpOcrEngine 换检测、识别、字典；第五个参数 device 按设备口径降采样，full 不降；可以给目录。
# OCR_SPACES=latin OCR_LATIN_BREAK=0.6 是混合空格（PpOcrEngineX）。gradle run 的工作目录是 ocr-dump/，路径写绝对的
E=$PWD; M=$E/models-en
OCR_SPACES=latin OCR_LATIN_BREAK=0.6 ../../gradlew -p ocr-dump -q run -Pmain=moe.flinty.yomark.OcrBenchKt \
  --args="$E/out-ocr/stress-v6s $M/PP-OCRv6_small_det.onnx $M/PP-OCRv6_small_rec.onnx $M/PP-OCRv6_small_rec.dict.txt device $E/out-stress/samples"
python3 score_ocr_stress.py out-stress/truth v6s-hybrid=out-ocr/stress-v6s
python3 route_ocr.py <中文模型ocr目录> <英文模型ocr目录> <输出目录>        # 按页分流；ensemble_ocr.py 是逐行择优

# 规则：出厂 + 原型；第三个参数是系统地区，第四个是名单目录，第五个 v3
../../gradlew -p ocr-dump -q run -Pmain=moe.flinty.yomark.EnRulesKt --args="$E/out-ner/oracle $E/rules-oracle.json CN $E/names v3"

# 模型（模型写法见 run_ner.py 开头）；fp16 用 fp16_onnx.py 从官方 fp32 ONNX 转
python3 run_ner.py out-ner/oracle ner-edge.json gliner:knowledgator/gliner-pii-edge-v1.0 0.3
python3 summarize_bench.py out-ner/truth rules-oracle.json --veto-brands=names/brands_nsi.txt --addr-gate=out-ner/oracle ner-edge.json

# WebPII：fetch_webpii.py 取样（文中的 57 页），convert_webpii.py 转格式（高的页切条），full 口径识别，merge_tiles.py 拼回，
# webpii_filled.py 筛掉截图上空着的输入框
```

参考：[PP-OCRv6 论文](https://arxiv.org/abs/2606.13108)、[PaddleOCR 3.7.0 发布说明](https://github.com/PaddlePaddle/PaddleOCR/releases)、
[en_PP-OCRv5_mobile_rec](https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec)、
[GLiNER-PII edge](https://huggingface.co/knowledgator/gliner-pii-edge-v1.0)、[GLiNER-PII base](https://huggingface.co/knowledgator/gliner-pii-base-v1.0)、
[Ettin Nemotron PII](https://huggingface.co/kalyan-ks/ettin-32m-nemotron-pii)、[Rampart](https://huggingface.co/nationaldesignstudio/rampart)、
[WebPII](https://huggingface.co/datasets/WebPII/webpii)、[name-suggestion-index](https://github.com/osmlab/name-suggestion-index)、
[USPS Publication 28](https://pe.usps.com/text/pub28/welcome.htm)、
[美国人口普查局姓氏数据](https://www.census.gov/topics/population/genealogy/data/2010_surnames.html)、
[SSA 新生儿名字数据](https://www.ssa.gov/oact/babynames/limits.html)。
