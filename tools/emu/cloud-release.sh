#!/usr/bin/env bash
# 关掉 cloud-claim.sh 起的模拟器。AVD 留着，同一个会话里再领不用重新扫包。
set -euo pipefail
SDK=${ANDROID_HOME:-/opt/android-sdk}
PORT=5560
timeout 20 "$SDK/platform-tools/adb" -s emulator-$PORT emu kill >/dev/null 2>&1 || true
for _ in $(seq 20); do
  pgrep -f "qemu-system-x86_64.* -port $PORT( |$)" >/dev/null || exit 0
  sleep 1
done
pkill -9 -f "qemu-system-x86_64.* -port $PORT( |$)" || true
