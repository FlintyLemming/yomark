<#
给当前 worktree 领一台独占的模拟器，最后一行输出它的 serial（如 emulator-5560）。

多个 Claude Code 窗口并行时，各自用 -read-only 起一个同 AVD 的实例，互不覆盖 APK、不抢前台。
端口靠 $env:TEMP\yomark-emu\<port> 目录加锁（New-Item 建目录是原子的）。

冷启动（-no-snapshot）：从 default_boot 快照恢复的只读实例，写 /metadata（encryptionkey.img）会一直挂着，
am instrument 之类碰到它的命令跟着卡死、杀不掉。冷启动多花一两分钟，没有这个问题；也不往共用的 AVD 存快照。
同一个 worktree 重复调用会直接返回已领到的 serial，所以每次需要 serial 时再跑一遍即可。
AVD 被 Android Studio 之类不带 -read-only 的实例锁着时，自动换用它的克隆 <AVD>_yomark（见 Get-Clone）。

用法：
  $s = .\tools\emu\claim.ps1            # 默认 Pixel_10_Pro
  adb -s $s install -r app\build\outputs\apk\debug\app-x86_64-debug.apk
  .\tools\emu\release.ps1               # 用完释放
#>
param(
    [string]$Avd = 'Pixel_10_Pro',
    [int]$BootTimeoutSec = 300
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

# 5554/5556 留给手动开的或不加 -port 起的模拟器
$ports = 5560..5574 | Where-Object { $_ % 2 -eq 0 }

$avdLocked = 'AVD 被不带 -read-only 的实例锁着'

function Wait-Boot($serial, $proc, $log) {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc -and $proc.HasExited) {
            if (Select-String -Quiet -Path $log -Pattern 'run all emulators with -read-only') { throw $avdLocked }
            throw "模拟器进程提前退出，日志在 $log"
        }
        $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ("$booted".Trim() -eq '1') { return }
        Start-Sleep -Seconds 3
    }
    throw "$serial 在 $BootTimeoutSec 秒内没启动完"
}

# 只要有一个不带 -read-only 的实例在跑（Android Studio 开的就是），同一个 AVD 就起不了只读实例。
# 这时换用一份克隆：只抄 config.ini，数据盘第一次冷启动时从系统镜像现做，不碰原 AVD。
# 先在临时目录里建好再改名过去，两个窗口同时克隆也不会读到半份
function Get-Clone($name) {
    $root = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "$env:USERPROFILE\.android\avd" }
    $clone = "${name}_yomark"
    $ini = Join-Path $root "$clone.ini"
    if (Test-Path $ini) { return $clone }

    $srcIni = Get-Content (Join-Path $root "$name.ini")
    $srcDir = ($srcIni | Where-Object { $_ -like 'path=*' }).Substring(5)
    $avdDir = Join-Path $root "$clone.avd"
    if (-not (Test-Path $avdDir)) {
        $tmp = "$avdDir.tmp-$PID"
        New-Item -ItemType Directory -Force $tmp | Out-Null
        Get-Content (Join-Path $srcDir 'config.ini') | ForEach-Object {
            if ($_ -like 'AvdId=*') { "AvdId=$clone" }
            elseif ($_ -like 'avd.ini.displayname=*') { "$_ (yomark)" }
            else { $_ }
        } | Set-Content (Join-Path $tmp 'config.ini')
        try { Move-Item $tmp $avdDir -ErrorAction Stop } catch { Remove-Item -Recurse -Force $tmp }
    }
    $tmpIni = "$ini.tmp-$PID"
    @($srcIni | Where-Object { $_ -notlike 'path=*' -and $_ -notlike 'path.rel=*' }) +
        "path=$avdDir" + "path.rel=avd\$clone.avd" | Set-Content $tmpIni
    try { Move-Item $tmpIni $ini -ErrorAction Stop } catch { Remove-Item -Force $tmpIni }
    return $clone
}

New-Item -ItemType Directory -Force $lockRoot | Out-Null

# 先回收崩掉的窗口留下的锁
foreach ($dir in Get-Locks) {
    if (-not (Test-LockAlive $dir)) {
        Write-Host "回收失效的锁 $($dir.Name)"
        Remove-Item -Recurse -Force $dir.FullName
    }
}

# 自己已经领过就直接返回
foreach ($dir in Get-Locks) {
    $ownerFile = Join-Path $dir.FullName 'owner'
    if ((Test-Path $ownerFile) -and (Get-Content $ownerFile) -eq $owner) {
        $serial = "emulator-$($dir.Name)"
        Wait-Boot $serial $null $null
        Write-Output $serial
        exit 0
    }
}

$port = $null
foreach ($p in $ports) {
    if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { continue }
    try {
        New-Item -ItemType Directory (Join-Path $lockRoot $p) -ErrorAction Stop | Out-Null
        $port = $p
        break
    } catch { }
}
if (-not $port) { throw "没有空闲端口了（$($ports -join ',')），先 release 掉不用的实例" }

$dir = Join-Path $lockRoot $port
$serial = "emulator-$port"
Set-Content (Join-Path $dir 'owner') $owner
Set-Content (Join-Path $dir 'avd') $Avd

# 日志放锁目录外面、每次一个新文件：模拟器派生的 watchdog / crashpad 会继承句柄，
# 关机后还要占一会儿，放锁目录里会删不掉锁
$logDir = Join-Path $lockRoot 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
Get-ChildItem $logDir -File | Where-Object LastWriteTime -lt (Get-Date).AddDays(-3) |
    Remove-Item -Force -ErrorAction SilentlyContinue
$log = Join-Path $logDir "$port-$(Get-Date -Format yyyyMMdd-HHmmss).log"

function Start-Avd($name) {
    $log = Join-Path $logDir "$port-$(Get-Date -Format yyyyMMdd-HHmmss).log"
    Set-Content (Join-Path $dir 'avd') $name
    Write-Host "启动 $name -> $serial（$owner）"
    $proc = Start-Process $emulator -PassThru `
        -ArgumentList '-avd', $name, '-read-only', '-port', $port, '-no-boot-anim', '-no-snapshot' `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    try {
        Wait-Boot $serial $proc $log
        return $true
    } catch {
        Stop-Emu $port
        if ("$_" -eq $avdLocked) { return $false }
        Remove-Item -Recurse -Force $dir
        throw
    }
}

if (-not (Start-Avd $Avd)) {
    $clone = Get-Clone $Avd
    Write-Host "$Avd 被一个不带 -read-only 的实例锁着（多半是 Android Studio 开的），换用 $clone"
    if (-not (Start-Avd $clone)) {
        Remove-Item -Recurse -Force $dir
        throw "$clone 也被一个不带 -read-only 的实例锁着，关掉那台（adb -s <serial> emu kill）再领"
    }
}
Write-Output $serial
