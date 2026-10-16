#!/usr/bin/env bash
# 云端会话（Claude Code on the web）里起一台模拟器，等开机完成后输出 serial（emulator-5560）。已经开好就直接输出。
#
# 云端容器是 Firecracker 虚拟机，没有 /dev/kvm，模拟器只能用 QEMU 软件模拟（-accel off），比本机慢几十倍：
# - 1 个 vCPU。软件模拟本来就只用一个线程跑所有 vCPU，核多了只会互相等锁。
#   -qemu -accel tcg,thread=multi（多线程软件模拟）在这版 QEMU（2.12）上 x86 没适配好，实测更慢，别开。
# - 系统镜像用完整的 AOSP（有状态栏、导航栏，截图跟真机一样）。精简的 ATD 镜像开机快一些，
#   但没有 SystemUI，截图是黑的。
# - 慢到 system_server 自己的看门狗（60 秒）会把系统杀掉重启，应用也动不动 ANR（5 秒）。
#   开机后用 root 设 ro.hw_timeout_multiplier=20 把这些超时放大，重启一次框架；
#   同时写进 /data/local.prop，同一个 AVD 以后开机自动带上。
# - 最后切回非 root 的 adb：root 发不出 URI 授权，open-image 打开的图应用读不到。
# - 第一次开机（新建的 AVD）最慢：要解压 APEX、扫包、编译。
#
# 用法（开机要十几分钟，可以先放后台，同时编译）：
#   s=$(tools/emu/cloud-claim.sh)
#   adb -s $s install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
#   tools/emu/cloud-release.sh           # 用完关掉
set -euo pipefail

SDK=${ANDROID_HOME:-/opt/android-sdk}
PORT=5560
SERIAL=emulator-$PORT
AVD=yomark_cloud
IMAGE="system-images;android-36;default;x86_64"
LOG_DIR=${TMPDIR:-/tmp}/yomark-emu
BOOT_TIMEOUT=${YOMARK_EMU_BOOT_TIMEOUT:-3600}
TIMEOUT_MULTIPLIER=20
adb=$SDK/platform-tools/adb
mkdir -p "$LOG_DIR"
log=$LOG_DIR/$AVD.log

a() { timeout 300 "$adb" -s "$SERIAL" "$@"; }
running() { pgrep -f "qemu-system-x86_64.* -port $PORT( |$)" >/dev/null; }
booted() { [ "$(timeout 20 "$adb" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }
wait_boot() {
  local start
  start=$(date +%s)
  until booted; do
    running || { echo "模拟器进程退出了，日志在 $log" >&2; exit 1; }
    if [ $(( $(date +%s) - start )) -gt "$BOOT_TIMEOUT" ]; then
      echo "$SERIAL 在 $BOOT_TIMEOUT 秒内没启动完，日志在 $log" >&2
      exit 1
    fi
    sleep 10
  done
}

"$adb" start-server >/dev/null 2>&1

if ! running; then
  [ -e /dev/kvm ] && echo "这台机器有 /dev/kvm，这个脚本按没有 KVM 的云端容器写，照样用软件模拟" >&2

  # 模拟器和系统镜像不放进 SessionStart 钩子：每个会话都装要多等半分钟，多数会话用不着
  missing=()
  [ -x "$SDK/emulator/emulator" ] || missing+=("emulator")
  [ -d "$SDK/${IMAGE//;//}" ] || missing+=("$IMAGE")
  if [ ${#missing[@]} -gt 0 ]; then
    echo "安装 ${missing[*]}" >&2
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "${missing[@]}" >/dev/null
  fi

  avd_dir=${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD.avd
  if [ ! -f "$avd_dir/config.ini" ]; then
    echo "新建 AVD $AVD（Pixel 9 Pro 的屏幕，1280x2856，480dpi）" >&2
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_9_pro --force >/dev/null
    # 数据盘放大，装 140MB 的 debug 包还有余地；不要音频和摄像头
    sed -i -e 's/^hw.cpu.ncore=.*/hw.cpu.ncore=1/' -e 's/^hw.ramSize=.*/hw.ramSize=4096/' \
      -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' -e 's/^hw.keyboard=.*/hw.keyboard=yes/' \
      -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' -e 's/^hw.gpu.mode=.*/hw.gpu.mode=swiftshader_indirect/' \
      -e 's/^hw.audioInput=.*/hw.audioInput=no/' -e 's/^hw.audioOutput=.*/hw.audioOutput=no/' \
      -e 's/^hw.camera.back=.*/hw.camera.back=none/' "$avd_dir/config.ini"
  fi
  rm -f "$avd_dir"/*.lock

  echo "冷启动 $AVD（软件模拟，日志在 $log）" >&2
  # -no-snapshot：软件模拟下存取快照都慢，容器也不跨会话
  setsid nohup "$SDK/emulator/emulator" -avd "$AVD" -port $PORT -no-window -no-audio -no-boot-anim -no-metrics \
    -gpu swiftshader_indirect -accel off -no-snapshot >"$log" 2>&1 </dev/null &
fi

wait_boot

if [ -z "$(a shell getprop ro.hw_timeout_multiplier | tr -d '\r')" ]; then
  echo "放大系统超时（ro.hw_timeout_multiplier=$TIMEOUT_MULTIPLIER），重启框架" >&2
  a root >/dev/null
  sleep 5
  a wait-for-device
  a shell "setprop ro.hw_timeout_multiplier $TIMEOUT_MULTIPLIER &&
    echo ro.hw_timeout_multiplier=$TIMEOUT_MULTIPLIER > /data/local.prop && chmod 644 /data/local.prop &&
    setprop sys.boot_completed 0 && stop && start"
  wait_boot
fi

if [ "$(a shell id -u | tr -d '\r')" = "0" ]; then
  a unroot >/dev/null
  sleep 5
  a wait-for-device
fi

# 存储比 sys.boot_completed 晚挂上，挂上前往 /sdcard 推图会报 Transport endpoint is not connected
start=$(date +%s)
until a shell sm list-volumes 2>/dev/null | grep -q 'emulated;0 mounted'; do
  if [ $(( $(date +%s) - start )) -gt 900 ]; then
    echo "$SERIAL 的存储 15 分钟还没挂上" >&2
    exit 1
  fi
  sleep 10
done

# 屏幕常亮、动画全关
a shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; svc power stayon true;
  settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
  settings put global animator_duration_scale 0' >/dev/null 2>&1 || true
echo "$SERIAL"
