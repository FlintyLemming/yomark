# claim / release / open-image / adb 共用。emulator.exe 只是个壳，真正占端口的是它拉起的 qemu 子进程，
# 杀掉壳 qemu 还会活着，所以按命令行里的 -port 认进程，不记 pid。

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$lockRoot = "$env:TEMP\yomark-emu"
$logDir = Join-Path $lockRoot 'logs'
$owner = (git rev-parse --show-toplevel).Trim()

function Get-EmuProcesses([int]$port) {
    Get-CimInstance Win32_Process -Filter "Name='emulator.exe' or Name like 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-port\s+$port(\s|$)" }
}

function Get-Locks {
    if (Test-Path $lockRoot) { Get-ChildItem $lockRoot -Directory | Where-Object Name -match '^\d+$' }
}

# 当前 worktree 领着的那台的端口，没领返回 $null
function Get-OwnPort {
    foreach ($dir in Get-Locks) {
        $ownerFile = Join-Path $dir.FullName 'owner'
        if ((Test-Path $ownerFile) -and (Get-Content $ownerFile) -eq $owner) { return [int]$dir.Name }
    }
}

function Test-LockAlive($dir) {
    if (Get-EmuProcesses ([int]$dir.Name)) { return $true }
    # 刚建锁、模拟器还没拉起来的窗口期
    return ((Get-Date) - $dir.CreationTime).TotalSeconds -lt 120
}

function Stop-Emu([int]$port) {
    & $adb -s "emulator-$port" emu kill 2>$null | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-EmuProcesses $port) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
    Get-EmuProcesses $port | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# 每台模拟器配一个私有 adb 服务，端口是模拟器端口 + 1000（emulator-5560 → 6560），不用共用的 5037：
# Android Studio 把 5037 弄卡死（adb.log 里刷 Too many open files）时，所有窗口的 adb 都跟着卡。
function Get-AdbPort([int]$port) { $port + 1000 }

# 把这个进程的 adb 指向 $port 那台的私有服务。之后在这个进程里跑的 adb、起的模拟器都继承这些变量，
# 服务没在跑时 adb 也照它们拉起：扫描上限低于 5555 就不扫模拟器端口，只认注册过来的那台
# （ADB_EMU=0 也不扫，但模拟器掉线后不重连）；不碰 USB 和 mDNS，不跟 5037 抢手机；日志不进共用的 adb.log。
# 模拟器自己也会调 adb（GMS 的 dumpsys），它拉起的服务要是没带这些，就会连上所有窗口的模拟器
function Use-Adb([int]$port) {
    $env:ANDROID_ADB_SERVER_PORT = Get-AdbPort $port
    $env:ADB_LOCAL_TRANSPORT_MAX_PORT = 5000
    $env:ADB_USB = 0
    $env:ADB_MDNS = 0
    $env:ANDROID_ADB_LOG_PATH = Join-Path $logDir "adb-$(Get-AdbPort $port).log"
}

function Clear-Adb {
    'ANDROID_ADB_SERVER_PORT', 'ADB_LOCAL_TRANSPORT_MAX_PORT', 'ADB_USB', 'ADB_MDNS', 'ANDROID_ADB_LOG_PATH' |
        ForEach-Object { Remove-Item "env:$_" -ErrorAction SilentlyContinue }
}

# $port 上有没有进程在听。Get-NetTCPConnection 要一秒多，这个几毫秒
function Test-Listening([int]$port) {
    [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains $port
}

# 在 $port 上监听的进程
function Get-Listener([int]$port) {
    if (-not (Test-Listening $port)) { return }
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object OwningProcess | Sort-Object -Unique |
        ForEach-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }
}

# 起 Use-Adb 指的那个私有服务，已经在跑就什么也不做。
# 工作目录放日志目录：服务比这次调用活得久，别让它占着 worktree 目录（删 worktree 时会删不掉）
function Start-AdbServer {
    if (Test-Listening $env:ANDROID_ADB_SERVER_PORT) { return }
    New-Item -ItemType Directory -Force $logDir | Out-Null
    Push-Location $logDir
    try { $out = & $adb start-server 2>&1 } finally { Pop-Location }
    if ($LASTEXITCODE) { throw "adb 服务（端口 $env:ANDROID_ADB_SERVER_PORT）起不来：$out" }
}

# 让 $adbPort 上的 adb 服务连上 $port 那台模拟器。adb 服务只在启动时扫一遍模拟器端口，之后只认
# host:emulator:<模拟器的 adb 端口> 这条注册（adb 协议：4 位十六进制长度 + 正文，服务不回话），重复注册没关系。
# 直连发完就走：对面没起时连接被拒，卡死时连不上，最多等 1 秒
function Register-Emu([int]$port, [int]$adbPort) {
    $msg = "host:emulator:$($port + 1)"
    $bytes = [Text.Encoding]::ASCII.GetBytes(('{0:x4}{1}' -f $msg.Length, $msg))
    $client = [Net.Sockets.TcpClient]::new()
    try {
        if ($client.ConnectAsync('127.0.0.1', $adbPort).Wait(1000)) { $client.GetStream().Write($bytes, 0, $bytes.Length) }
    } catch { } finally { $client.Dispose() }
}

# 关 $port 那台的私有服务。直接杀进程：adb kill-server 碰上卡死的服务会一直等它回话。
# 先关模拟器再调这个，不然模拟器下次调 adb 又会把服务拉起来
function Stop-AdbServer([int]$port) {
    Get-Listener (Get-AdbPort $port) | Where-Object ProcessName -eq 'adb' | ForEach-Object {
        Stop-Process -InputObject $_ -Force -ErrorAction SilentlyContinue
        $_.WaitForExit(5000) | Out-Null
    }
}
