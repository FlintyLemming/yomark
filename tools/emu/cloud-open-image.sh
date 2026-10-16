#!/usr/bin/env bash
# open-image.ps1 的云端版：把一张本地图片放进 cloud-claim.sh 那台模拟器的媒体库，在编辑器里打开，输出它的 content URI。
# 为什么 URI 放在 -d 里、为什么先垫一个首页，见 open-image.ps1。
#
# 用法：
#   tools/emu/cloud-open-image.sh app/src/test/resources/chat/chat-003-wechat-light-group.png
set -euo pipefail
file=$1
[ -f "$file" ] || { echo "没有这个文件：$file" >&2; exit 1; }
serial=$("$(dirname "$0")/cloud-claim.sh")
a() { timeout 120 "${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb" -s "$serial" "$@"; }

# 文件名要拼进设备上的 shell 命令，只留安全字符
dir=/storage/emulated/0/Pictures/yomark-test
remote=$dir/$(basename "$file" | sed 's/[^A-Za-z0-9._-]/_/g')
a shell mkdir -p "$dir"
a push "$file" "$remote" >/dev/null
a shell content call --uri content://media/external/file --method scan_file --arg "$remote" >/dev/null

id=$(a shell content query --uri content://media/external/images/media --projection _id:_data | tr -d '\r' \
  | sed -n "s|.*_id=\([0-9]*\), _data=$remote\$|\1|p" | tail -1)
[ -n "$id" ] || { echo "媒体库里没找到 $remote，确认它是图片" >&2; exit 1; }
uri=content://media/external/images/media/$id

a shell am force-stop moe.flinty.yomark
a shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n moe.flinty.yomark/.ui.home.HomeActivity >/dev/null
out=$(a shell am start -W -n moe.flinty.yomark/.ui.EditorActivity -d "$uri" --grant-read-uri-permission 2>&1)
if grep -qE 'Error|Exception' <<<"$out"; then echo "$out" >&2; exit 1; fi
echo "$uri"
