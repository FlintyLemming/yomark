# PP-OCR 模型来源

`app/src/main/assets/ppocr/` 下的三个文件随包发行，运行期不联网。

| 文件 | 原名 | 大小 | SHA-256 |
|---|---|---|---|
| `det.onnx` | `PP-OCRv6_small_det_onnx` 的 `inference.onnx` | 9,880,512 B | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| `rec.onnx` | `PP-OCRv6_small_rec_onnx` 的 `inference.onnx` | 21,159,378 B | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| `dict.txt` | `PP-OCRv6_small_rec_onnx` 的 `inference.yml` 里的 `character_dict` | 74,947 B | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |

- 模型：PaddlePaddle/PaddleOCR 的 PP-OCRv6 small（2026-06，PaddleOCR 3.7.0），Apache License 2.0。
- ONNX 是 PaddlePaddle 在 Hugging Face 上发的官方导出：
  `https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx`（提交 `28fe5895c24fd108c19eb3e8479f4ab385fbfc62`）、
  `https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx`（提交 `b8f84f0b80c529de40b4fbb3544b84fa7233a513`），
  两个 `inference.onnx` 的 SHA-256 与仓库 LFS 记录的一致。
- 字典没有单独的文件，从识别模型的 `inference.yml`（`PostProcess.character_dict`）按顺序一行一个写出来，UTF-8，末尾一个换行。
  `tools/model-eval/download_en_eval.sh` 里有同样的抽法。
- 识别模型输出 18710 类 = blank + 字典 18708 字 + 空格，`CtcDecoder` 启动时校验这个对应关系。
- 预处理与 v5 相同，`PpOcrEngine` 不用改：检测按 ImageNet 均值方差、BGR 通道序；识别高 48、`(x/255 − 0.5)/0.5`。
  DB 后处理沿用 app 原来的阈值（0.3 / 0.6 / 1.5）；v6 配置里的 0.2 / 0.45 / 1.4 在评测里没有更好。

换模型时同时换三个文件并更新上表；`PaddleTextRecognizerTest` 会在 JVM 上用真模型读一张合成页，
识别质量明显退化时它会先失败。

## 2026-10-10：PP-OCRv5 mobile → PP-OCRv6 small

之前随包的是 PP-OCRv5 mobile（RapidAI/RapidOCR 转的 ONNX，检测 4.8 MB + 识别 16.6 MB）。换成 v6 small 的依据见
`docs/english-recognition-research.md`：72 张压力测试页（多字体、深色模式、JPEG 压缩、6 种分辨率）上，
人名、地址、电话、编号一字不差读出来的比例英文 91.8% → 94.2%、中文 96.2% → 99.0%，字符错误率英文 0.25% → 0.08%、
中文 1.88% → 0.29%；第一轮的运单页上，v5 把全大写里的 O 读成 0（`0AK AVE`、`L0S ANGELES`），v6 small 读对了。代价：

- 包里两个模型 21 MB → 31 MB；
- x86 服务器上（JVM 版 ONNX Runtime，4 vCPU）整页耗时与 v5 相当（955 ms 对 894 ms），**真机的速度和内存还没量**；
- 字典 18383 → 18708 字：多了 360 个，其中 317 个是拉丁字母（大多带变音符号：À、Ñ、Ā……），另有圈号字符、单个的区域指示符号和一些符号；
  少了 35 个：西里尔字母 11 个、国旗表情 10 个（v6 改成了单个的区域指示符号）、数学斜体 7 个、韩文 2 个，
  以及 ˂、天城体数字 ०、康熙部首 ⼁、私用区 U+F8FF 和兼容表意字 U+2F80F。没有少常用汉字（「兔」U+5154 两边都有）。
