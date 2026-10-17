<p align="center">
  <img src="docs/readme/hero.png" alt="有码：截图里的敏感信息，发出去之前先遮住" width="100%">
</p>

<p align="center">
  <a href="https://github.com/FlintyLemming/yomark/releases"><img src="https://img.shields.io/github/v/release/FlintyLemming/yomark?label=Release&color=101014" alt="Release"></a>
  <a href="https://github.com/FlintyLemming/yomark/actions/workflows/android-apk.yml"><img src="https://github.com/FlintyLemming/yomark/actions/workflows/android-apk.yml/badge.svg" alt="Android APK"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=white" alt="Android 10+">
</p>

<p align="center">自动找出截图里的手机号、地址、人名、人脸、二维码并打码<br>本机识别，默认不联网，不申请任何运行期权限</p>

<table>
  <tr>
    <th>自动打码</th>
    <th>点一下增减</th>
    <th>大爆炸</th>
  </tr>
  <tr>
    <td><img src="docs/readme/scan.gif" width="240" alt="打开快递详情截图，磨砂识别完从中心揭开，取件码、地址、收件人和邮箱已经打上码"></td>
    <td><img src="docs/readme/tap.gif" width="240" alt="聊天截图里点一下链接，整行打上码；点一下手机号的码拿掉，再点一下打回去"></td>
    <td><img src="docs/readme/bigbang.gif" width="240" alt="长按链接末尾的提取码再划过去，只遮这几个字；松手后下面出现字块"></td>
  </tr>
  <tr>
    <th>换样式</th>
    <th>拖框补漏</th>
    <th>首次引导</th>
  </tr>
  <tr>
    <td><img src="docs/readme/style.gif" width="240" alt="展开「打码」，色块换成马赛克、模糊再换回来，图上的码一起变"></td>
    <td><img src="docs/readme/box.gif" width="240" alt="快递单号默认不打码，拖一个框遮住，再拖右下角拉宽"></td>
    <td><img src="docs/readme/tour.gif" width="240" alt="首次打开的引导：欢迎页，翻页后样图自动打码，再点一下链接打上码"></td>
  </tr>
</table>

<table>
  <tr>
    <th>证件照</th>
    <th>用途水印</th>
    <th>批量处理</th>
    <th>识别规则</th>
  </tr>
  <tr>
    <td><img src="docs/readme/idcard.png" width="173" alt="拍歪的身份证照片，姓名、出生、住址、号码已打码"></td>
    <td><img src="docs/readme/watermark.png" width="173" alt="快递详情页铺满「仅供快递理赔使用」水印，下方是水印的颜色、角度、不透明度、密度"></td>
    <td><img src="docs/readme/batch.png" width="173" alt="一次选了三张图，底部胶片条排着三张缩略图"></td>
    <td><img src="docs/readme/rules.png" width="173" alt="设置里的文字规则：人名、电话、地址、取件码等打码，长数字串默认关闭"></td>
  </tr>
</table>

<table>
  <tr>
    <th>平板、折叠屏</th>
  </tr>
  <tr>
    <td><img src="docs/readme/tablet.png" width="774" alt="平板横屏：画布旁边列着每一块码的缩略图，功能平铺成方块"></td>
  </tr>
</table>

**[完整功能说明 →](docs/features.md)**

## 下载

[Releases](https://github.com/FlintyLemming/yomark/releases) 里下载 APK：真机装 `arm64-v8a`，模拟器装 `x86_64`。

## 反馈

欢迎反馈：

- **识别问题**：哪种截图漏遮、遮错、该认的没认出来，发邮件到 [yomark@flinty.moe](mailto:yomark@flinty.moe)。附上截图更好查，邮件不公开。
- **使用体验**：闪退、不好用、想要什么功能，提 [GitHub Issues](https://github.com/FlintyLemming/yomark/issues/new/choose)。Issues 所有人都看得到，附截图前先用有码打码。

应用里「设置 → 关于 → 反馈」会带上版本和机型。

## 构建

```sh
./gradlew :app:assembleDebug        # JDK 21、Android SDK 36
./gradlew :app:testDebugUnitTest
```

<sub>截图和动图由 [docs/readme/src/capture.ps1](docs/readme/src/capture.ps1) 在模拟器上拍真实应用，样图里的人名、地址、号码都是编的</sub>
