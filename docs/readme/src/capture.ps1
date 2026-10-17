<#
拍 README 的截图和动图：在模拟器上跑真的应用，打开 app\src\test\resources 里的样图（人名、地址、号码都是编的）。
成品写到 docs\readme\，录屏原片和整张截图留在 docs\readme\shots\（不进仓库）。

  .\gradlew.bat :app:assembleDebug
  .\docs\readme\src\capture.ps1                    # 手机上的全拍一遍
  .\docs\readme\src\capture.ps1 scan bigbang       # 只拍其中几项，名字见下面的 $scenes
  .\docs\readme\src\capture.ps1 -Tablet            # 平板那张，用 Pixel_Tablet
  .\tools\emu\release.ps1

claim.ps1 一个 worktree 只领一台：拍完手机先 release，再拍平板。
手机是 Pixel_10_Pro（1280×2856）：图上的点按样图在画布里的位置算，换机型要重算；按钮从 uiautomator 里按文字找。
模拟器上识别要四五秒（真机一两秒），scan.gif 把等识别的磨砂剪到两秒。
要 ffmpeg（录屏转 GIF、缩图）和 JDK（编 SetClip.java，往模拟器剪贴板里放中文：adb shell input text 打不了中文）。
#>
param(
    [Parameter(ValueFromRemainingArguments)][string[]]$Only,
    [switch]$Tablet,
    [switch]$SkipInstall,
    # 调某一项时用：不重装、不清数据，接着上一次的状态拍
    [switch]$Quick
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\..\..\tools\emu\common.ps1"    # $sdk、$adb；$owner 是仓库根目录

$out = Join-Path $owner 'docs\readme'
$work = Join-Path $out 'shots'
$res = Join-Path $owner 'app\src\test\resources'
$pkg = 'moe.flinty.yomark'
# 截图、界面树都放这儿。放 /sdcard 会被媒体库扫进去，出现在 Photo Picker 里
$tmp = '/data/local/tmp'
New-Item -ItemType Directory -Force $work | Out-Null
$inv = [cultureinfo]::InvariantCulture

$serial = if ($Tablet) { & "$owner\tools\emu\claim.ps1" -Avd Pixel_Tablet } else { & "$owner\tools\emu\claim.ps1" }
if ($Tablet -and -not ((& $adb -s $serial shell getprop ro.product.model) -match 'Tablet')) {
    throw "$serial 不是平板。这个 worktree 已经领了一台手机，先 .\tools\emu\release.ps1"
}

function Sh([string]$command) { & $adb -s $serial shell $command }
function Num([double]$x) { $x.ToString('0.###', $inv) }
$screen = [int[]]((Sh 'wm size' | Select-Object -First 1) -replace '.*:\s*' -split 'x')

# 动画没停的时候 uiautomator dump 偶尔失败（null root node），先删掉旧文件，免得读到上一次的
function Get-Ui {
    for ($try = 0; $try -lt 5; $try++) {
        Sh "rm -f $tmp/ui.xml; uiautomator dump $tmp/ui.xml" | Out-Null
        & $adb -s $serial pull "$tmp/ui.xml" "$work\ui.xml" 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return [xml](Get-Content -Raw -Encoding utf8 "$work\ui.xml") }
        Start-Sleep -Milliseconds 500
    }
    throw 'uiautomator dump 一直失败'
}

# 按文字或无障碍描述找控件，返回中心点
function Find-Node([string]$label, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        $node = (Get-Ui).SelectNodes('//node') |
            Where-Object { $_.text -eq $label -or $_.'content-desc' -eq $label } | Select-Object -First 1
        if ($node) {
            $b = [int[]]($node.bounds -split '\D+' | Where-Object { $_ })
            return [int](($b[0] + $b[2]) / 2), [int](($b[1] + $b[3]) / 2)
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    throw "界面上没找到「$label」"
}

function Tap-Node([string]$label, [int]$pauseMs = 1200) {
    $x, $y = Find-Node $label
    Sh "input tap $x $y"
    Start-Sleep -Milliseconds $pauseMs
}

# 等编辑器识别完：手机上识别中「按住对比」是灰的；平板上它一直能按，码的列表那儿写着「识别中…」
function Wait-Scan([int]$timeoutSec = 60) {
    Start-Sleep -Milliseconds 1500
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        $nodes = (Get-Ui).SelectNodes('//node')
        $compare = $nodes | Where-Object { $_.'content-desc' -eq '按住对比' }
        $busy = ($nodes | Where-Object { $_.text -like '识别中*' }) -or ($compare | Where-Object { $_.enabled -ne 'true' })
        if ($compare -and -not $busy) { Start-Sleep -Milliseconds 1500; return }  # 再等揭开的动画走完
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "$timeoutSec 秒还没识别完"
}

function Find-Sample([string]$remote) {
    foreach ($row in Sh 'content query --uri content://media/external/images/media --projection _id:_data') {
        if ($row -match '_id=(\d+), _data=(.+)$' -and $Matches[2].Trim() -eq $remote) { $id = $Matches[1] }
    }
    if ($id) { "content://media/external/images/media/$id" }
}

# 把样图放进媒体库，返回 content URI（同 tools\emu\open-image.ps1）。
# 文件时间改成现在：Photo Picker 按时间倒排，后放的排前面
function Add-Sample([string]$path) {
    $file = Get-Item -LiteralPath $path
    $remote = "/storage/emulated/0/Pictures/yomark-readme/$($file.Name)"
    if ($Quick -and ($uri = Find-Sample $remote)) { return $uri }
    Sh 'mkdir -p /storage/emulated/0/Pictures/yomark-readme'
    & $adb -s $serial push $file.FullName $remote 2>$null | Out-Null
    Sh "touch $remote"
    Sh "content call --uri content://media/external/file --method scan_file --arg $remote" | Out-Null
    Start-Sleep -Seconds 1
    $uri = Find-Sample $remote
    if (-not $uri) { throw "媒体库里没找到 $remote" }
    $uri
}

function Start-Home { Sh "am start -W -n $pkg/.ui.home.HomeActivity -f 0x10008000" | Out-Null; Start-Sleep -Milliseconds 800 }
function Open-Editor([string]$uri) { "am start -n $pkg/.ui.EditorActivity -d $uri --grant-read-uri-permission" }

# 首页清空任务栈，再打开编辑器等它识别完。进程不杀，模型留在内存里
function Open-Sample([string]$uri) {
    Start-Home
    Sh (Open-Editor $uri) | Out-Null
    Wait-Scan
}

function Set-DeviceClipboard([string]$text) {
    $hex = ([Text.Encoding]::UTF8.GetBytes($text) | ForEach-Object { $_.ToString('x2') }) -join ''
    Sh "CLASSPATH=$tmp/setclip.dex app_process / SetClip $hex"
}

# 选中输入框里的字，换成 $text
function Replace-Field([string]$current, [string]$text) {
    Set-DeviceClipboard $text
    Tap-Node $current 800
    Sh 'input keycombination KEYCODE_CTRL_LEFT KEYCODE_A'
    Sh 'input keyevent KEYCODE_PASTE'
    Start-Sleep -Milliseconds 500
    Sh 'input keyevent KEYCODE_BACK'    # 收起键盘
    Start-Sleep -Milliseconds 800
}

function Save-Shot([string]$name, [int]$width = 720) {
    Sh "screencap -p $tmp/shot.png"
    & $adb -s $serial pull "$tmp/shot.png" "$work\$name.png" 2>$null | Out-Null
    ffmpeg -hide_banner -loglevel error -y -i "$work\$name.png" -vf "scale=${width}:-2:flags=lanczos,format=rgb24" -pred mixed "$out\$name.png"
    Write-Host "$name.png"
}

# 录屏时的手势走模拟器控制台：它经过虚拟触摸屏，「显示点按」的圆点才画得出来；
# adb shell input 是直接注入的，录出来看不到手指点在哪。坐标要换成触摸屏的 0..32767
function Send-Touch([string[]]$events) { & $adb -s $serial emu event send @events | Out-Null }
function Touch-Position([int]$x, [int]$y) {
    "EV_ABS:ABS_MT_POSITION_X:$([int]($x * 32767 / $screen[0]))", "EV_ABS:ABS_MT_POSITION_Y:$([int]($y * 32767 / $screen[1]))"
}
function Touch-Down([int]$x, [int]$y) {
    Send-Touch (@('EV_ABS:ABS_MT_SLOT:0', 'EV_ABS:ABS_MT_TRACKING_ID:1', 'EV_ABS:ABS_MT_TOUCH_MAJOR:8',
            'EV_ABS:ABS_MT_PRESSURE:512') + (Touch-Position $x $y) + 'EV_SYN:0:0')
}
function Touch-Move([int]$x, [int]$y) { Send-Touch ((Touch-Position $x $y) + 'EV_SYN:0:0') }
function Touch-Up { Send-Touch 'EV_ABS:ABS_MT_TRACKING_ID:-1', 'EV_SYN:0:0' }
function Touch-Tap([int]$x, [int]$y) { Touch-Down $x $y; Start-Sleep -Milliseconds 120; Touch-Up }
# 每发一次控制台命令本身要五十毫秒上下，$ms 只是大概
function Touch-Drag([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 500, [int]$steps = 8) {
    Touch-Down $x1 $y1
    for ($i = 1; $i -le $steps; $i++) {
        Start-Sleep -Milliseconds ([math]::Max(0, $ms / $steps - 50))
        Touch-Move ($x1 + ($x2 - $x1) * $i / $steps) ($y1 + ($y2 - $y1) * $i / $steps)
    }
    Touch-Up
}

# 边录屏边做 $steps 里的操作，原片拉到 shots\$name.mp4。原生分辨率编码器吃不消，录一半大小
function Record([string]$name, [int]$seconds, [scriptblock]$steps) {
    Sh "rm -f $tmp/rec.mp4"
    $recorder = Start-Process $adb -PassThru -NoNewWindow -ArgumentList '-s', $serial, 'shell', 'screenrecord',
        '--size', '640x1424', '--bit-rate', '16000000', '--time-limit', $seconds, "$tmp/rec.mp4"
    Start-Sleep -Milliseconds 800
    & $steps
    $recorder.WaitForExit()
    & $adb -s $serial pull "$tmp/rec.mp4" "$work\$name.mp4" 2>$null | Out-Null
}

# 录屏转 GIF。$keep 是留下的时间段（秒），多段时交叉淡化 0.2 秒接上。
# screenrecord 只在画面变了的时候出帧，停住以后就没有帧了：先补成定帧率，再把最后一帧续上几秒
function Save-Gif([string]$name, [double[][]]$keep, [int]$width = 360, [int]$fps = 15) {
    $labels = (0..($keep.Count - 1) | ForEach-Object { "[c$_]" }) -join ''
    $graph = @("[0:v]fps=$fps,tpad=stop_mode=clone:stop_duration=6,scale=${width}:-2:flags=lanczos,format=rgb24," +
        "split=$($keep.Count)$labels")
    for ($i = 0; $i -lt $keep.Count; $i++) {
        $graph += "[c$i]trim=start=$(Num $keep[$i][0]):end=$(Num $keep[$i][1]),setpts=PTS-STARTPTS[s$i]"
    }
    $last = 's0'
    $length = $keep[0][1] - $keep[0][0]
    for ($i = 1; $i -lt $keep.Count; $i++) {
        $length -= 0.2
        $graph += "[$last][s$i]xfade=transition=fade:duration=0.2:offset=$(Num $length)[x$i]"
        $last = "x$i"
        $length += $keep[$i][1] - $keep[$i][0]
    }
    $graph += "[$last]split[a][b]", '[a]palettegen=max_colors=128:stats_mode=diff[p]',
        '[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle'
    ffmpeg -hide_banner -loglevel error -y -i "$work\$name.mp4" -filter_complex ($graph -join ';') -loop 0 "$out\$name.gif"
    Write-Host ('{0}.gif  {1:N0} KB' -f $name, ((Get-Item "$out\$name.gif").Length / 1KB))
}

# 录屏里「识别中…」那颗胶囊出现、消失的时间：胶囊是深色的，在顶栏下方正中
function Find-ScanSpan([string]$name) {
    $lines = ffmpeg -hide_banner -loglevel error -i "$work\$name.mp4" `
        -vf 'crop=90:16:275:211,signalstats,metadata=mode=print:key=lavfi.signalstats.YAVG:file=-' -f null - 2>&1
    $shown = $null
    $t = 0
    foreach ($line in $lines) {
        if ("$line" -match 'pts_time:([\d.]+)') { $t = [double]::Parse($Matches[1], $inv) }
        elseif ("$line" -match 'YAVG=([\d.]+)') {
            $y = [double]::Parse($Matches[1], $inv)
            if (-not $shown -and $y -lt 140) { $shown = $t }
            elseif ($shown -and $y -gt 170) { return $shown, $t }
        }
    }
    throw "$name.mp4 里没找到识别从开始到结束的一段"
}

# 系统这边：动画正常速度、显示点按位置、状态栏用演示模式
function Initialize-Device {
    Sh 'settings put global animator_duration_scale 1'
    Sh 'settings put global transition_animation_scale 1'
    Sh 'settings put global window_animation_scale 1'
    Sh 'settings put system show_touches 1'    # 录屏里看得见手指点在哪
    # 状态栏：固定时间、满格、不显示通知
    Sh 'settings put global sysui_demo_allowed 1'
    foreach ($demo in 'enter', 'clock -e hhmm 0930', 'battery -e level 100 -e plugged false',
        'network -e wifi show -e level 4 -e fully true -e mobile hide', 'notifications -e visible false',
        'status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e mute hide') {
        Sh "am broadcast -a com.android.systemui.demo -e command $demo" | Out-Null
    }
    # 演示模式藏不掉的系统通知图标（没设锁屏的安全提醒之类）：推迟一天
    foreach ($line in Sh 'dumpsys notification --noredact') {
        if ($line -match '\skey=(\S+):\s') { Sh "cmd notification snooze --for 86400000 '$($Matches[1])'" | Out-Null }
    }
}

# 应用回到出厂设置：首次打开先是引导，跳过后把主题色换成蓝色（跟 Play 截图一样）
function Reset-App {
    if (-not $SkipInstall) {
        Write-Host '安装 APK'
        & $adb -s $serial install -r "$owner\app\build\outputs\apk\debug\app-x86_64-debug.apk" | Out-Null
    }
    Sh "pm clear $pkg" | Out-Null
    Sh "cmd locale set-app-locales $pkg --locales zh-CN"
    Sh "am start -W -n $pkg/.ui.home.HomeActivity" | Out-Null
    Tap-Node '跳过'
    Tap-Node '设置'
    Tap-Node '外观'
    Tap-Node '蓝色'
    Sh 'input keyevent KEYCODE_BACK'
    Start-Sleep -Milliseconds 800
}

function Initialize-Phone {
    Initialize-Device
    if (-not $Quick) { Reset-App }

    # 编一个往剪贴板放中文的小工具
    $dex = "$work\setclip\classes.dex"
    if (-not (Test-Path $dex)) {
        $jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "$env:USERPROFILE\.jdks\jbr-21.0.11" }
        $platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1
        $buildTools = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1
        & "$jdk\bin\javac.exe" --release 11 -cp "$($platform.FullName)\android.jar" -d "$work\setclip" "$PSScriptRoot\SetClip.java"
        $env:JAVA_HOME = $jdk
        & "$($buildTools.FullName)\d8.bat" --min-api 29 --output "$work\setclip" "$work\setclip\SetClip.class"
    }
    & $adb -s $serial push $dex "$tmp/setclip.dex" 2>$null | Out-Null

    # 批量那张从 Photo Picker 里选头三张：订单、聊天、证件照，最后放的排最前
    $script:uris = @{}
    foreach ($sample in @(
            @('idcard', "$res\ppocr\id-card-photo.jpg"),
            @('chat', "$res\play\chat-page.png"),
            @('order', "$res\readme\order-page.png"))) {
        $script:uris[$sample[0]] = Add-Sample $sample[1]
        Start-Sleep -Seconds 1
    }
    # 冷启动第一次识别要加载模型，先跑一遍
    Open-Sample $uris.order
}

# ---- 各个场景。坐标是 Pixel_10_Pro 上的像素；样图 1080×1680，在画布里放大 1.185 倍、顶边在 y=467 ----

$scenes = [ordered]@{
    # 打开图片：磨砂、识别、从中心揭开，认出来的已经打上码
    scan = {
        Start-Home
        Record scan 13 { Sh (Open-Editor $uris.order) | Out-Null; Start-Sleep -Seconds 11 }
        # 磨砂刚盖上来的那几帧还看得见底下没打码的字，从胶囊出来半秒以后开始
        $shown, $done = Find-ScanSpan scan
        Save-Gif scan @(@(($shown + 0.5), ($shown + 2.5)), @(($done - 0.3), ($done + 2.8)))
    }
    # 点一下链接，整段打上码；点一下手机号的码拿掉，再点一下打回去
    tap = {
        Open-Sample $uris.chat
        Record tap 7 {
            Touch-Tap 521 1934; Start-Sleep -Milliseconds 1600
            Touch-Tap 374 1486; Start-Sleep -Milliseconds 1500
            Touch-Tap 374 1486; Start-Sleep -Milliseconds 1800
        }
        Save-Gif tap @(, @(0.3, 6.6))
    }
    # 大爆炸：长按链接末尾的提取码，划过去，松手出字块，点「完成」
    bigbang = {
        Open-Sample $uris.chat
        Record bigbang 9 {
            Touch-Down 700 1934; Start-Sleep -Milliseconds 1000
            foreach ($x in 730, 760, 790, 815, 835) { Touch-Move $x 1934; Start-Sleep -Milliseconds 60 }
            Start-Sleep -Milliseconds 400; Touch-Up; Start-Sleep -Milliseconds 2200
            Touch-Tap 1133 1650; Start-Sleep -Milliseconds 1800
        }
        Save-Gif bigbang @(, @(0.3, 7.6))
    }
    # 换样式：「打码」展开，色块 → 马赛克 → 模糊 → 色块，收起。
    # 选中的样式变宽，别的往两边让，所以每一步点的位置都不一样
    style = {
        Open-Sample $uris.order
        Record style 10 {
            foreach ($step in @(327, 1300), @(827, 1500), @(1127, 1500), @(407, 1300), @(120, 1600)) {
                Touch-Tap $step[0] 2682; Start-Sleep -Milliseconds $step[1]
            }
        }
        Save-Gif style @(, @(0.3, 9.4))
    }
    # 补框：快递单号默认不打码，拖个框遮住一半，再拖右下角拉宽
    box = {
        Open-Sample $uris.order
        Record box 7 {
            Touch-Drag 300 1330 560 1400 700; Start-Sleep -Milliseconds 1200
            Touch-Drag 560 1400 735 1400 600; Start-Sleep -Milliseconds 1200
            Touch-Tap 640 2400; Start-Sleep -Milliseconds 1400
        }
        Save-Gif box @(, @(0.3, 6.4))
    }
    # 首次引导：欢迎页的形状慢慢转，翻页时样图不动、只有字滑过去；第三页点一下框出的链接
    tour = {
        Start-Home
        Tap-Node '设置'
        Tap-Node '重新观看引导' 2000
        Record tour 10 {
            Start-Sleep -Milliseconds 1200
            Touch-Drag 1050 1500 230 1500 250 5; Start-Sleep -Milliseconds 2200
            Touch-Drag 1050 1500 230 1500 250 5; Start-Sleep -Milliseconds 1400
            Touch-Tap 538 1685; Start-Sleep -Milliseconds 2200
        }
        Save-Gif tour @(, @(0.2, 8.9))
    }
    # 用途水印：写上用途，铺满整张图
    watermark = {
        Open-Sample $uris.order
        Tap-Node '编辑'
        Tap-Node '水印'
        Replace-Field '仅供办理 XX 使用' '仅供快递理赔使用'
        $null, $y = Find-Node '应用'
        Sh "input tap 1090 $y"    # 「应用」右边的开关
        Start-Sleep -Milliseconds 1200
        Save-Shot watermark
    }
    # 证件照：拍歪了也先转正再认，姓名、出生、住址、号码遮住
    idcard = {
        Open-Sample $uris.idcard
        Save-Shot idcard
    }
    # 批量：Photo Picker 里选三张，底部胶片条
    batch = {
        Start-Home
        Tap-Node '选择图片' 2500
        $tiles = (Get-Ui).SelectNodes('//node') | Where-Object { $_.'content-desc' -like 'Photo taken on*' } | Select-Object -First 3
        foreach ($tile in $tiles) {
            $b = [int[]]($tile.bounds -split '\D+' | Where-Object { $_ })
            Sh "input tap $([int](($b[0] + $b[2]) / 2)) $([int](($b[1] + $b[3]) / 2))"
            Start-Sleep -Milliseconds 500
        }
        Tap-Node 'Done' 1000
        Wait-Scan
        Save-Shot batch
    }
    # 设置里的文字规则：哪类打码、哪类默认关闭
    rules = {
        Start-Home
        Tap-Node '设置'
        Tap-Node '文字'
        Save-Shot rules
    }
}

if ($Tablet) {
    # 平板横着：画布左边，右边是码的列表和平铺成方块的功能
    Initialize-Device
    if (-not $Quick) { Reset-App }
    Open-Sample (Add-Sample "$res\readme\order-page.png")
    Save-Shot tablet 1280
    Sh 'settings put system show_touches 0'
    return
}

Initialize-Phone
foreach ($name in $scenes.Keys) {
    if ($Only -and $name -notin $Only) { continue }
    Write-Host "== $name"
    & $scenes[$name]
}
Sh 'settings put system show_touches 0'
