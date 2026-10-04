<p align="center">
  <img src="docs/readme/hero.png" alt="有码：截图里的敏感信息，发出去之前先遮住" width="100%">
</p>

# 有码

有码是一个 Android 上的图片打码工具。选一张截图，它会在手机上把手机号、地址、取件码、人名、人脸和二维码这类信息找出来。有把握的直接遮住，没把握的圈出来等你确认，最后导出一张新图。

应用本身没有联网权限。识别、编辑、导出全在手机上完成，图片不会离开设备。

## 用起来是这样的

1. 在首页选一张图，或者在相册里把图分享给「有码」。一次可以分享多张，逐张过一遍，最后一起导出。
2. 识别时有一道光从图上扫过，PP-OCR 处理一张图大约两秒。这段时间里画布可以照常操作。
3. 识别完画面上有两种标记。天蓝色块是已经遮住的；琥珀色虚线框带类型标签，是识别到了但还没遮的。点一下就在两种状态之间切换。
4. 漏掉的地方用一根手指拖出一个框补上，两根手指缩放和平移。长按手动框可以调整大小或删掉。
5. 点导出时，如果还有圈出来没遮的，会先弹窗提示「还有 3 处没打码」，并列出分别是什么。

识别出来的框只能在「遮住」和「圈出」之间切换，不能从画面上删掉。框一消失就再也找不回来，而漏遮一处就是一次事故。

## 认得出什么

| 默认直接遮住 | 默认只圈出，等你确认 |
|---|---|
| 电话、邮箱、人名、地址、取件码 | 快递单号、长数字串（订单号、交易号） |
| 银行卡号、IBAN、护照、社保号 | 日期时间 |
| 密钥（API key）、MAC 地址 | 网址、IP 地址 |
| 人脸、能解出内容的二维码和条形码 | 解不出内容的疑似条码 |

每条规则都可以在设置里单独设成「关」「只圈出」或「直接遮住」。

## 六种遮法，三种是真的遮住了

<p align="center">
  <img src="docs/readme/styles.png" alt="六种遮法的对比：色块、表情、抹除不可还原；马赛克、模糊、马克笔看起来遮了" width="100%">
</p>

色块、表情和抹除导出后无法还原。色块默认是天蓝色，不透明；抹除会用周围的颜色把那块填平，看上去就像那里从来没有字。

马赛克和模糊只是看起来遮住了。截图上的文字字体固定、背景干净，这两种都能被还原，马赛克甚至能逐字还原（实测过程见 [release-checklist](docs/release-checklist.md)）。马克笔只做标记，不会盖住内容。选中这三种时，样式栏下方会一直显示一行提醒。

## 导出

- 导出时在原图分辨率上重新绘制、重新编码。EXIF 里的位置、机型、拍摄时间一律不带出去。
- 用途水印：在整张图上平铺一行字，比如「仅供快递取件使用」，颜色、角度、透明度、疏密都可以调。证件照发出去之前加一行，以后被挪作他用时一眼就能认出来。
- 免费版导出的图片右下角有一个「有码 Youma」小水印，会自动避开打码区域。一次性买断可以去掉。付费只去掉这个水印，免费版的其他功能都不受限。

## 权限与网络

不申请任何运行期权限。选图走系统的 Photo Picker，应用拿到的只有你选中的那一张，读不到相册里的其他图片。

ML Kit 会通过依赖把 `INTERNET` 和 `ACCESS_NETWORK_STATE` 带进 manifest，构建时会把这两条摘掉。这不是一句"我们保证不联网"的承诺：系统根本不给这个进程开网络。

合并后 manifest 里剩下的三条声明都不授予数据访问能力：

- `com.android.vending.BILLING`：Play 内购。购买在 Play 商店的进程里进行。
- `com.google.android.apps.aicore.service.BIND_SERVICE`：「AI 复查」绑定系统的 AICore，模型在本机运行。
- `com.youma.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`：androidx 自动生成的 signature 级权限，其他应用拿不到。

构建时 `assertDebugNoRuntimePermissions` 会对照这份名单检查，多出任何一条，构建就会失败。

## 识别是怎么做的

- **文字**：默认用 PP-OCRv5 mobile（ONNX Runtime，模型打包在安装包里）。中文截图上，打了星号的号码、生僻字人名它都认得出来，还能给出单个字的框。设置里可以切换到 ML Kit 的拉丁或中文模型，也可以让两者同时运行。
- **判断**：哪些字算敏感信息由规则决定，不交给模型判断。规则是正则加校验位（银行卡 Luhn、IBAN mod 97、UPS 单号等），电话用 libphonenumber 解析，人名用 HanLP 的离线词典。规则命中的结果可以解释原因，也可以写单元测试。
- **人脸与条码**：用 ML Kit 的 bundled 版本，模型同样随包分发。
- **AI 复查**：在支持 AICore 的机型上，编辑器里会出现这个按钮。点了才会让 Gemini Nano 在本机把整页文字再读一遍；新发现的内容只圈出，不会自动遮住。

## 构建

需要 JDK 21 和 Android SDK 36。最低支持 Android 10（minSdk 29）：从这一版开始，应用读写自己创建的图片不需要存储权限，这是零权限的前提。

```sh
./gradlew :app:assembleDebug              # APK 按 ABI 拆分：真机装 arm64-v8a，模拟器装 x86_64
./gradlew :app:testDebugUnitTest          # Robolectric；PP-OCR 用桌面版 ONNX Runtime 跑真模型
./gradlew :app:connectedDebugAndroidTest  # 需要连着设备或模拟器
```

每次推送后，GitHub Actions 都会打一份 debug APK，可以在对应运行的 Artifacts 里下载。

## 目录

```
app/src/main/java/com/youma/app/
  core/      几何、图片读取、打码方案（MaskPlan）的数据模型
  engine/    OCR、人脸、条码的封装，候选去重合并
  rules/     敏感信息规则与校验位
  render/    六种遮法的渲染器，预览和导出共用一套
  export/    原图重绘、品牌水印、用途水印、写入相册
  ui/        首页、编辑器、识别设置（Jetpack Compose）
  billing/   去水印的一次性购买
docs/        设计文档、模型调研、上架验收记录
tools/       模型评测和测试语料的生成脚本
```

想了解某个决定为什么这样做，先看 [总体方案](docs/superpowers/specs/2026-09-02-youma-android-design.md)，再看 [上架验收记录](docs/release-checklist.md)。验收记录里每一项都写着实测数值，没达到的项也照实记录。

<sub>README 里的截图由 `ReadmeScreenshots` 在 Robolectric 上拍摄，用的是真实的识别流程。样图里的人名、地址、号码都是编的。重拍和合成的方法见 [docs/readme/src/render.cjs](docs/readme/src/render.cjs)。</sub>
