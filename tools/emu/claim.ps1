<#
给当前 worktree 领一台独占的模拟器，最后一行输出它的 serial（如 emulator-5560）。

多个 Claude Code 窗口并行时，各自用 -read-only 起一个同 AVD 的实例，互不覆盖 APK、不抢前台。
端口靠 $env:TEMP\yomark-emu\<port> 目录加锁（New-Item 建目录是原子的）。
同一个 worktree 重复调用会直接返回已领到的 serial，所以每次需要 serial 时再跑一遍即可。

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

function Wait-Boot($serial, $proc, $log) {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc -and $proc.HasExited) {
            if (Select-String -Quiet -Path $log -Pattern 'run all emulators with -read-only') {
                throw "$Avd 已经有一个不带 -read-only 的实例在跑，它锁住了 AVD。要关掉那台（adb -s <serial> emu kill），以后统一用 claim.ps1 起"
            }
            throw "模拟器进程提前退出，日志在 $log"
        }
        $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ("$booted".Trim() -eq '1') { return }
        Start-Sleep -Seconds 3
    }
    throw "$serial 在 $BootTimeoutSec 秒内没启动完"
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

Write-Host "启动 $Avd -> $serial（$owner）"
$proc = Start-Process $emulator -PassThru `
    -ArgumentList '-avd', $Avd, '-read-only', '-port', $port, '-no-boot-anim' `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"

try {
    Wait-Boot $serial $proc $log
} catch {
    Stop-Emu $port
    Remove-Item -Recurse -Force $dir
    throw
}
Write-Output $serial
