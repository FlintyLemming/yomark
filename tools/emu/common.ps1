# claim.ps1 / release.ps1 共用。emulator.exe 只是个壳，真正占端口的是它拉起的 qemu 子进程，
# 杀掉壳 qemu 还会活着，所以按命令行里的 -port 认进程，不记 pid。

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
$lockRoot = "$env:TEMP\yomark-emu"
$owner = (git rev-parse --show-toplevel).Trim()

function Get-EmuProcesses([int]$port) {
    Get-CimInstance Win32_Process -Filter "Name='emulator.exe' or Name like 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-port\s+$port(\s|$)" }
}

function Get-Locks {
    if (Test-Path $lockRoot) { Get-ChildItem $lockRoot -Directory | Where-Object Name -match '^\d+$' }
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
