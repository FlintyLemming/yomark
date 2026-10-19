<#
把一张本地图片放进当前 worktree 那台模拟器的媒体库，在编辑器里打开，最后一行输出它的 content URI。
返回栈跟从首页选图进来一样：首页垫在编辑器下面。

URI 必须放在 -d 里。--eu android.intent.extra.STREAM 不行：--grant-read-uri-permission 只给 data 和 ClipData
里的 URI 授权。真实应用分享时，startActivity() 会先把 EXTRA_STREAM 挪进 ClipData，am start 没有这一步，
app 拿不到读权限，编辑器空白，只弹「打不开这张图片」。
am 也带不了多个 URI（没有 ClipData 参数，也没有 Uri 数组 extra），批量只能走 Photo Picker。

用法：
  .\tools\emu\open-image.ps1 app\src\test\resources\ppocr\logistics-page.png
#>
param(
    [Parameter(Mandatory)][string]$Path
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$file = Get-Item -LiteralPath $Path
# claim.ps1 顺带把这个进程的 adb 指向了那台的私有服务，下面的 & $adb 都走它
$serial = & "$PSScriptRoot\claim.ps1"

# 文件名要拼进设备上的 shell 命令，只留安全字符
$dir = '/storage/emulated/0/Pictures/yomark-test'
$remote = "$dir/$($file.Name -replace '[^A-Za-z0-9._-]', '_')"

& $adb -s $serial shell mkdir -p $dir
$pushed = & $adb -s $serial push $file.FullName $remote 2>&1
if ($LASTEXITCODE) { throw "push 失败：$pushed" }
& $adb -s $serial shell content call --uri content://media/external/file --method scan_file --arg $remote | Out-Null

$id = $null
foreach ($row in & $adb -s $serial shell content query --uri content://media/external/images/media --projection _id:_data) {
    if ($row -match '_id=(\d+), _data=(.+)$' -and $Matches[2].Trim() -eq $remote) { $id = $Matches[1] }
}
if (-not $id) { throw "媒体库里没找到 $remote，确认它是图片" }
$uri = "content://media/external/images/media/$id"

& $adb -s $serial shell am force-stop moe.flinty.yomark
& $adb -s $serial shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER `
    -n moe.flinty.yomark/.ui.home.HomeActivity | Out-Null
$out = & $adb -s $serial shell am start -W -n moe.flinty.yomark/.ui.EditorActivity -d $uri --grant-read-uri-permission 2>&1
if ($LASTEXITCODE -or ($out -match 'Error|Exception')) { throw ($out -join "`n") }
Write-Output $uri
