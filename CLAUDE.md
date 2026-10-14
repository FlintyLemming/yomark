# CLAUDE.md

## 多窗口并行：先进 worktree 再改代码

我经常同时开好几个 Claude Code 窗口，各自处理不同问题，它们共用这一个仓库目录。所以主工作区里的未提交改动、新文件、甚至最近的提交，**很可能是别的窗口在做的事，跟你当前的任务无关**。

### 规则

1. **要改代码，先建 worktree。** 只要任务会修改仓库里的文件（写代码、改配置、改文档、跑会生成文件的脚本），第一次动手改之前先调用 `EnterWorktree`，名字取一个能看出任务内容的短 kebab-case（如 `fix-export-crash`）。之后的读、改、构建、测试都在 worktree 里完成。
   - 纯问答、读代码、排查思路这类不改文件的任务不需要 worktree。
   - 我明确说"直接在主目录改"时，跳过这一步。
2. **别把主工作区的状态当成你的上下文。** 会话开头 gitStatus 里列出的修改、`git diff` 里看到的变更，默认都是别的窗口的半成品。不要基于它们推断"当前在做什么"，不要顺手修它们，也不要在回复里总结它们。
3. **永远不要碰不属于你的改动。** 不要对别人的文件执行 `git checkout -- <file>`、`git restore`、`git stash`、`git reset --hard`、`git clean`，也不要覆盖它们。
4. **提交只提交自己的文件。** 用 `git add <具体路径>`，不用 `git add -A` / `git add .` / `git commit -a`。
5. **完成后的收尾：** 在 worktree 分支上提交，然后告诉我分支名，问我要不要合回 `main`。没有我确认，不要自己 merge 或 rebase 到 `main`，也不要删除 worktree。

### 万一没在 worktree 里

如果已经在主工作区改了东西（比如我让你直接改），改动前后都用 `git status` 对照，只认自己改过的文件；碰到别人也在改的同一个文件，先停下来问我。

## 改完要在本机模拟器上验证

改了代码，除了跑单元测试，还要在本机模拟器上装上跑一遍、截图看过再收尾。都在 worktree 里做。

- **模拟器：每个窗口用自己的一台，不要共用。** 在 worktree 根目录跑 `$s = .\tools\emu\claim.ps1`，它会用 `-read-only` 起一台 `Pixel_10_Pro` 的独立实例（端口 5560–5574），等开机完成后返回 serial（如 `emulator-5560`）。shell 状态不保留，所以每次要用 serial 就再跑一遍 `claim.ps1`，同一个 worktree 拿回的总是同一台。之后**所有 adb 命令都带 `-s $s`**，别的窗口的实例别碰。
  - 不要再手动 `Start-Process` 起 `Pixel_10_Pro`：只要有一台不带 `-read-only` 的实例在跑，它就锁住 AVD，所有窗口的 `claim.ps1` 都会失败（脚本会报出这个原因）。
  - `Galaxy_Z_Fold8` 可能正被别的窗口或 Android Studio 用着，别关它、别往上装。
  - 收尾时跑 `.\tools\emu\release.ps1` 关掉自己那台。忘了也没关系，下次有人领的时候会回收已经没有进程的锁，但还开着的实例会一直占内存。
  - 脚本要用 pwsh 7 跑（PowerShell 工具默认就是），Windows PowerShell 5.1 读中文会乱码。锁和启动日志在 `$env:TEMP\yomark-emu`。
- **JDK：** 直接跑 `.\gradlew.bat :app:assembleDebug`，不用先设 `JAVA_HOME`：`gradle/gradle-daemon-jvm.properties` 让 Gradle 自己在本机挑 JDK 21（`$env:USERPROFILE\.jdks\jbr-21.0.11`），用 Android Studio 自带的 JDK 25 启动也行。要是报找不到 Java 21，再设 `$env:JAVA_HOME="$env:USERPROFILE\.jdks\jbr-21.0.11"`。
- **安装：** APK 按 ABI 拆分，模拟器装 `app\build\outputs\apk\debug\app-x86_64-debug.apk`（没有 `app-debug.apk`）：`adb -s $s install -r <apk>`。
- **启动与检查：** `adb -s $s shell monkey -p moe.flinty.yomark -c android.intent.category.LAUNCHER 1`，然后看 `pidof moe.flinty.yomark` 和 `logcat -d -b crash`。crash 日志里 `android.hardware.uwb-service` 的 SIGABRT 是模拟器自己的问题，不用管。
- **打开测试图：** `.\tools\emu\open-image.ps1 <图片路径>`，把图放进媒体库并在编辑器里打开（现成的测试图在 `app\src\test\resources\`）。别自己用 `am start` 发分享 intent（`--eu android.intent.extra.STREAM`）：extra 里的 URI 拿不到读权限，编辑器是空白的。多张（批量）只能在首页点「选择图片」走 Photo Picker。截图时确认画布上真有图。
- **截图：** `adb -s $s shell screencap -p /sdcard/x.png` 再 `adb pull` 到 scratchpad（别用 PowerShell 的 `exec-out ... >`，二进制会被弄坏），读图确认改动的界面，把图的路径告诉我。

## Claude Code 云端会话

云端容器里没有模拟器，上面那节在云端做不了，也不要用 `claim.ps1`。`.claude/hooks/session-start.sh` 会在开会话时装好 Android SDK（`/opt/android-sdk`）并把 Maven Central 换成 Google 的镜像（云端直连常被限流成 429），所以可以直接跑：

- `./gradlew --no-daemon :app:assembleDebug :app:assertDebugNoRuntimePermissions`
- `./gradlew --no-daemon :app:testDebugUnitTest`（含真 OCR 的端到端单测）

模拟器上的验证留给本机，在回复里说明这一步没做。
