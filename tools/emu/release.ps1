<#
关掉 claim.ps1 给当前 worktree 起的模拟器和它的私有 adb 服务，释放端口锁。
不带参数时释放当前 worktree 领的那台；-Serial 指定具体的一台；-All 释放所有锁（只在确认没有别的窗口在用时用）。
#>
param(
    [string]$Serial,
    [switch]$All
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$targets = Get-Locks | Where-Object {
    if ($All) { return $true }
    if ($Serial) { return "emulator-$($_.Name)" -eq $Serial }
    $ownerFile = Join-Path $_.FullName 'owner'
    (Test-Path $ownerFile) -and (Get-Content $ownerFile) -eq $owner
}
if (-not $targets) { Write-Host '没有要释放的模拟器'; exit 0 }

foreach ($dir in $targets) {
    $port = [int]$dir.Name
    Write-Host "释放 emulator-$port"
    # 先关模拟器再关服务：模拟器自己会调 adb，服务先关的话它又会拉起一个
    Stop-Emu $port
    Stop-AdbServer $port
    Remove-Item -Recurse -Force $dir.FullName
    # 这个进程的 adb 别再指着关掉的服务，不然下一条 adb 会在那个端口上拉起一个空的
    if ($env:ANDROID_ADB_SERVER_PORT -eq (Get-AdbPort $port)) { Clear-Adb }
}
