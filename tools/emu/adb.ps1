<#
在当前 worktree 领的那台模拟器上跑一条 adb 命令，参数原样转给 adb：走那台的私有 adb 服务，自动带 -s。
还没领就先领（见 claim.ps1）。直接跑 SDK 里的 adb.exe 连的是共用的 5037，那边不一定认得这台。

用法：
  .\tools\emu\adb.ps1 install -r app\build\outputs\apk\debug\app-x86_64-debug.apk
  .\tools\emu\adb.ps1 shell screencap -p /sdcard/x.png
  .\tools\emu\adb.ps1 pull /sdcard/x.png <本地目录>
#>
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$serial = & "$PSScriptRoot\claim.ps1"
& $adb -s $serial @args
exit $LASTEXITCODE
