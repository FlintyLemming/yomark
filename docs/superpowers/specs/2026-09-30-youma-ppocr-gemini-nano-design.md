# 有码 · PP-OCR 与 Gemini Nano 增补设计

对 `2026-09-02-youma-android-design.md` 的增补，落地 §14 预留接口表里的两行：
「换成自带 OCR 模型 PP-OCRv5 + ONNX Runtime」与「Gemini Nano 语义判定」。

## 起因

菜鸟快递详情页上姓名、打了星的电话、地址、取件码整页漏检（见 `LogisticsPageRegressionTest`）。
规则补上之后，用户追问判定是规则还是模型，并决定：OCR 换成 PP-OCR，加一层端侧小模型判定，
**不再限制包体**。

## 决定

| 项 | 决定 | 理由 |
|---|---|---|
| 包体上限 | 取消原 §13 的「arm64-v8a ≤ 25 MB」 | 用户决定。直接装的 APK 按 ABI 拆，arm64 debug 包约 150 MB |
| 出厂 OCR | `TextEngineOption.PADDLE`（PP-OCRv5 mobile） | 真机截图上中文识别明显好于 ML Kit；能给出字符级的框 |
| ML Kit 三档 | 保留在设置里 | 逐项开关的意义就在于能切回去对比 |
| 设置键 | `recognition_text_engine` → `_v2` | 老安装动过设置就存着 BOTH，旧键会让它们永远停在 ML Kit |
| 语义判定 | Gemini Nano，经 ML Kit GenAI Prompt API（AICore） | §14 已列；推理在系统服务里跑，本应用不联网 |
| 模型结果的默认态 | 仅圈出，标「AI」 | 模型会猜错，按 §6「按误报率划线」；导出拦截兜底 |
| 模型能否否决规则 | 不能，只加不减 | 漏检是事故，让模型否决规则等于让它制造漏检 |
| 何时跑 | 规则结果上屏之后另跑一遍（`RedactionEngine.refine`） | 一次推理几秒，挡在前面会让「选中即打码」变成「选中后干等」 |
| 新增权限 | `com.google.android.apps.aicore.service.BIND_SERVICE` | 只用于绑定 AICore；非运行期权限，不触及用户数据。已加入构建断言白名单 |

运行期敏感权限仍为 0，合并后 manifest 里仍然没有 `INTERNET`。

## PP-OCR 的实现要点

见 `engine/ppocr`。核心不碰 `android.graphics`，单测用桌面版 onnxruntime 在 JVM 上跑真模型。

- 检测：长边缩到 1536；DB 后处理手写（连通分量 → 凸包 → 最小外接矩形 → unclip），不引 OpenCV；
- 识别：高 48，CTC 贪心解码；
- 字符级框：由每个字的 CTC 时间步切出，`TextLine.element` 是单个字；
- 空格：模型几乎不输出空格，按识别条上的像素间隙补回（`WordGaps`）；
- 同一行被拆开的几段拼回一条（`RowGrouper`），规则层「同一行」的前提不变。

模型来源与校验和见 `docs/ppocr-models.md`。

## Gemini Nano 的实现要点

见 `engine/genai`。

- 可用性：`checkStatus()`；`DOWNLOADABLE` 时请 AICore 去下，这一次先跳过；机型不支持就整层不跑；
- 提示词：整页文字编行号，只问人名 / 地址 / 电话 / 号码四类，回答用「行号|类型|原文」一行一条；
- 信任边界：原文必须能在那一行里找到（忽略空格），找不到就丢；与规则结果大面积重叠的丢；
- 设置：新轴「语义判定（实验）」，Gemini Nano / 关闭。

## 未验证

- PP-OCR 在中端机上的识别延迟。原 §13 的 800 ms 指标很可能超出，`EvaluationTest` 的延迟断言需要上设备重新量；
- Gemini Nano 的召回与误报：只能在支持 AICore 的真机上评估，单测只覆盖提示词与解析。
