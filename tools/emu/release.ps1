<#
关掉 claim.ps1 给当前 worktree 起的模拟器并释放端口锁。
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
    Write-Host "释放 emulator-$($dir.Name)"
    Stop-Emu ([int]$dir.Name)
    Remove-Item -Recurse -Force $dir.FullName
}
