# PP-OCR 模型来源

`app/src/main/assets/ppocr/` 下的三个文件随包发行，运行期不联网。

| 文件 | 原名 | SHA-256 |
|---|---|---|
| `det.onnx` | `ch_PP-OCRv5_det_mobile.onnx` | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` |
| `rec.onnx` | `ch_PP-OCRv5_rec_mobile.onnx` | `5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5` |
| `dict.txt` | `ppocrv5_dict.txt` | `d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b` |

- 模型：PaddlePaddle/PaddleOCR 的 PP-OCRv5 mobile，Apache License 2.0。
- ONNX 转换版取自 RapidAI/RapidOCR（ModelScope）：
  `https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/{det,rec}/…`，
  字典取自同仓库 `paddle/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile/ppocrv5_dict.txt`。
- 识别模型输出 18385 类 = blank + 字典 18383 字 + 空格，`CtcDecoder` 启动时校验这个对应关系。

换模型时同时换三个文件并更新上表；`PaddleTextRecognizerTest` 会在 JVM 上用真模型读一张合成页，
识别质量明显退化时它会先失败。
