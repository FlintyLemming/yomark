# 有码上架前验收记录

对照 `docs/superpowers/specs/2026-09-02-youma-android-design.md` §13 逐条实测。
每一行都要有**实测数值**，不是打勾。达不到的写「未达成」并说明卡在哪里——
这份文档的用处全在于它敢记不好看的数。

**测试环境**：模拟器 `Medium_Phone` AVD / android-37.1 / arm64-v8a / playstore 镜像，
debug 变体（体积与权限两项用 release bundle）。JDK 21，AGP 8.11.1。
日期：2026-09-03。

**自动化总览**：单元测试 360 条全绿；instrumented 62 条（含识别设置页新增的 4 条），
**其中 5 条在改完可切换识别方案后尚未上设备复跑**。

> **2026-09-04 更新**：加入可切换识别方案（见
> `docs/superpowers/specs/2026-09-04-youma-recognition-profiles-design.md`）。
> 体积与权限两行已按新依赖复测；延迟、圈出率、召回率三行的旧数值是在
> 「拉丁 OCR + 条码全部默认打码」下测的，**新出厂默认（两者并跑 + 疑似条码仅圈出）
> 下的数值尚未测**。

---

## §13 指标

| 维度 | 指标 | 阈值 | 实测 | 怎么测的 |
|---|---|---|---|---|
| 圈出率 | 标注实体被 MASKED 或 OUTLINED 覆盖 | ≥ 0.95 | **1.00（合成集）**；真实样本集**未采集** | `EvaluationTest`，`YOUMA-EVAL synthetic/screenshot` |
| 默认打码召回率 | 默认打码类型被 MASKED 覆盖 | ≥ 0.95 | **1.00（合成集）**；真实样本集**未采集** | 同上 |
| 精确率 | MASKED 区域确实覆盖敏感内容 | ≥ 0.70 | **1.00（合成集）**；真实样本集**未采集** | 同上 |
| 识别延迟 | 1080×2400，选中到候选出现 | < 800 ms | **143 ms**（断网）／**191 ms**（联网）✅ | `EvaluationTest` 的延迟用例 |
| 冷启动 | 点图标到首页可交互 | < 1.2 s | **待重测**：启动器入口已从直接弹 Photo Picker 改为首页（旧流程首帧 392–445 ms，Picker 可交互 544–712 ms） | `am start -W` TotalTime ×5 |
| 下发体积 | arm64-v8a split | ≤ 25 MB | **17,652,832 B = 16.8 MiB / 17.65 MB** ✅（含中文 OCR 包） | `bundletool get-size total --dimensions=ABI` |
| 运行期敏感权限 | 除 BILLING 外的 uses-permission 条数 | 0 | **0** ✅（见下方清单） | `assertReleaseNoRuntimePermissions` + 设备侧 `dumpsys package` |
| 网络请求 | 全流程（不含购买）运行期请求数 | 0 | **0** ✅ | 无 `INTERNET` 权限 + 断网复跑全套 |
| 导出拦截 | pendingCount > 0 时对话框触发率 | 100% | **100%** ✅ | `EditorViewModelTest` / `EditorViewModelBatchTest` + 实机走查 |
| 水印避让 | 水印与遮罩相交的比例 | 0% | **0%（四角有空位时）**；四角全占时按设计压在右下，见下 | `WatermarkDrawerTest` 12 条 + 目视 |
| 元数据 | 导出图读到 GPS/型号/时间戳的条数 | 0 | **0** ✅ | `ExporterTest` + `MediaStoreSinkTest` |
| 不可还原 | 像素化与实色块导出图 | 去码工具验证不可读 | 实色块/Emoji/抹除 **不可还原**；**像素化不满足**，已降级为 COSMETIC | `IrreversibilityTest` + M4 的模板泄漏探针与 Depix 实跑 |

### 下发体积明细（arm64-v8a）

| 组成 | 实测 | spec §11 预算 |
|---|---|---|
| `libmlkit_google_ocr_pipeline.so` | 11.06 MB | ≈4.0 MB |
| `libface_detector_v2_jni.so` | 8.52 MB | ≈6.9 MB |
| `libbarhopper_v3.so` | 4.95 MB | ≈2.4 MB |
| `classes.dex` | 3.98 MB | ≈6 MB（代码 + Compose/AndroidX + Billing） |
| bundled 模型资产（tflite / emd / fb） | ≈5.0 MB | 含在上面各行 |
| **arm64-v8a 下发合计** | **17.0 MB** | ≈21.3 MB |

未压缩的 so 体积远超预算行，但**下发是压缩后的**，合计反而低于预算。
25 MB 的阈值有 8 MB 余量。

### 合并后 manifest 的 uses-permission

```
com.android.vending.BILLING
com.youma.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

两条都不是运行期权限。设备侧 `dumpsys package com.youma.app` 复核：
`requested permissions` 就这两条，两条 `install permissions` 均 `granted=true`，
**`runtime permissions` 一节为空**。

### 零网络

- 本应用**没有 `INTERNET` 权限**（ML Kit 传递依赖带进来的 `INTERNET` /
  `ACCESS_NETWORK_STATE` 在 manifest 里被 `tools:node="remove"` 摘掉，见 spec §15.1）。
  没有这个权限，进程层面就拿不到 socket——这比抓包看到 0 条请求更强。
- 复核方式：`svc wifi disable && svc data disable`（`Active default network: none`）下
  跑完整 instrumented 套件，59 条结果与联网时一致；评测指标逐位相同
  （圈出率/召回率/精确率均 1.00）。
- 购买流程是唯一的例外，且网络发生在 Play 商店进程里，不在本进程（spec §10 / §13 已排除）。

---

## 未达成 / 未验的项

### 1. 真实样本集未采集（M2 出口，阻塞 §13 前三行）

`app/src/androidTest/assets/samples/` 为空，`EvaluationTest` 的真实样本用例打印
提示后跳过。上表前三行的 1.00 全部来自 `SyntheticSamples`——合成图没有压缩噪点、
深色模式、异体字号、重叠 UI，**也没有中文**，而 bundled 拉丁识别器对中文完全无能
（spec §15.5 实测）。

**这三行在真实样本集跑出来之前不构成验收。** 采集格式与隐私要求见 `docs/eval-sample-set.md`。

### 2. 正脸召回未量（M3 出口的一半）

`app/src/androidTest/assets/faces/` 为空，`RegionEvaluationTest` 的对应用例 SKIPPED。
需要 30+ 张含正脸的照片。二维码那半边已达成：36/36 = 1.00（自造语料，
`tools/gen_qr_corpus.py`，种子写死可复现）。

### 3. Play Billing 真机购买未验

本机没有可用的 Play Console 应用与测试账号，以下三条留给上架前人工验：

- [ ] Play Console 建一次性商品，ID 填 `youma_remove_watermark`
- [ ] internal testing 轨道用测试账号买一次 → 导出无水印
- [ ] **杀进程 → 飞行模式 → 重开 → 导出：水印仍不出现**（离线购买态的关键验证）
- [ ] 清除应用数据 → 联网打开 → `queryPurchasesAsync` 恢复购买态，水印消失

代码侧已由 `PurchaseResolverTest`（4 条）与 `PurchaseStoreTest`（3 条）守住
「缓存优先 / 断连不降级 / 查得到以查询为准」这三条规则，
`EditorViewModelWatermarkTest` 守住「免费有水印、付费没有」。

### 4. 中端真机未上手

全部实测在模拟器上。冷启动、延迟、32 MP 导出三项在真机上的数会不同，
上架前应至少在一台中端机复跑一遍。

---

## 实机走查（同一环境）

- **首次安装**：引导页出现，**不**直接拉 Picker；点「选择图片」→ Picker →
  选图 → 进编辑器。杀进程重启：直接是 Picker，引导页不再出现。
  从别的入口 `ACTION_SEND` 进来：跳过引导页直接进编辑器。
- **识别**：选一张含二维码的图，进编辑器时二维码**已经是黑块**，无需任何操作。
- **「不保留活动」复验**（spec §15.2）：开启 `always_finish_activities` 后按 Home 再回来，
  图与整棵 plan 原样回来，二维码仍是黑块，没有退回 Picker。
- **批量**：`ACTION_SEND_MULTIPLE` 三张进来 → 底栏「1 / 3」+「下一张」，
  逐张推进编辑内容不丢，最后一张主按钮变回「导出」（`BatchFlowTest` 自动化守着）。
- **购买入口**：顶栏出现去水印按钮（已购时隐藏），点开是买断说明弹窗，
  首句就是「一次性买断，不是订阅」，正文明确「免费版的隐私能力一项都不缺」。
- **四角全占的水印回退**（spec §15.8）：整屏全打码时水印回到右下压在黑块上，
  墨色自动翻浅，清晰可读；水印画在遮罩之后，不会露出底下像素。

---

## 上架前检查

- [ ] 商店描述**首屏**写明：本应用不申请任何运行期敏感权限；`com.android.vending.BILLING`
      是 Play Billing 库并入的，不授予任何数据访问能力（spec §0 的立场）
- [ ] 隐私政策写明：不收集、不上传任何数据；无账号；无分析 SDK
- [ ] Data safety 表单：全部选「不收集」
- [ ] 免费版功能描述里明确「隐私能力一项不缺，付费只去水印」
- [ ] 商店描述里**不要**把像素化写成安全手段——它是外观优先（spec §15.6）

---

## 复跑命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew clean
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest \
          :app:assertReleaseNoRuntimePermissions :app:bundleRelease

# 评测指标
adb logcat -c && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.youma.app.eval
adb logcat -d -s System.out:I | grep YOUMA-EVAL

# 冷启动
adb shell am force-stop com.youma.app
adb shell am start -W -n com.youma.app/.ui.home.HomeActivity | grep TotalTime

# 下发体积
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/youma.apks
bundletool get-size total --apks=/tmp/youma.apks --dimensions=ABI

# 零网络复核
adb shell svc wifi disable && adb shell svc data disable
./gradlew :app:connectedDebugAndroidTest
adb shell svc wifi enable && adb shell svc data enable
```
