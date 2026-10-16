#!/usr/bin/env bash
# 云端会话（Claude Code on the web）里起一台模拟器，等开机完成后输出 serial（emulator-5560）。已经开好就直接输出。
#
# 云端容器是 Firecracker 虚拟机，没有 /dev/kvm，模拟器只能用 QEMU 软件模拟（-accel off），比本机慢几十倍：
# - 1 个 vCPU。软件模拟本来就只用一个线程跑所有 vCPU，核多了只会互相等锁。
#   -qemu -accel tcg,thread=multi（多线程软件模拟）在这版 QEMU（2.12）上 x86 没适配好，实测更慢，别开。
# - 系统镜像用完整的 AOSP（有状态栏、导航栏，截图跟真机一样）。精简的 ATD 镜像开机快一些，
#   但没有 SystemUI，截图是黑的。
# - 慢到 system_server 的看门狗（主线程卡 60 秒）会把整个框架杀掉重来，首次开机能被连杀好几次；
#   应用也动不动 ANR（输入 5 秒、广播 10 秒）。所以开机过程中尽早：
#   - 在设置里把看门狗放宽（system_server_watchdog_timeout_ms）。设置服务一起来就能写，不用 root，
#     看门狗当场改用新值；写进设置库，这台 AVD 以后开机都带着。
#   - root 下设 ro.hw_timeout_multiplier，ANR 和看门狗的超时都乘上它。框架是在 zygote 预加载时读这个值的，
#     adbd 比 zygote 晚起一点，多半赶得上；没赶上就开机后重启一次框架。
# - 最后切回非 root 的 adb：root 发不出 URI 授权，open-image 打开的图应用读不到。
# - 第一次开机（新建的 AVD）最慢：要解压 APEX、扫包、编译。
#
# 用法（新建的 AVD 开机要二十分钟左右，可以先放后台，同时编译）：
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
MULTIPLIER=20
WATCHDOG_MS=$((60000 * MULTIPLIER))
adb=$SDK/platform-tools/adb
mkdir -p "$LOG_DIR"
log=$LOG_DIR/$AVD.log
t0=$(date +%s)

a() { timeout 300 "$adb" -s "$SERIAL" "$@"; }
# 开机中途用的查询：设备不在或卡住都只是返回空，不算失败
q() { timeout 30 "$adb" -s "$SERIAL" "$@" 2>/dev/null | tr -d '\r' || true; }
running() { pgrep -f "qemu-system-x86_64.* -port $PORT( |$)" >/dev/null; }
check_alive() {
  running || { echo "模拟器进程退出了，日志在 $log" >&2; exit 1; }
  if [ $(( $(date +%s) - t0 )) -gt "$BOOT_TIMEOUT" ]; then
    echo "$SERIAL 在 $BOOT_TIMEOUT 秒内没准备好，日志在 $log" >&2
    exit 1
  fi
}
watchdog_set=
set_watchdog() {
  [ -n "$watchdog_set" ] && return
  [ "$(q shell "settings put global system_server_watchdog_timeout_ms $WATCHDOG_MS >/dev/null 2>&1;
    settings get global system_server_watchdog_timeout_ms")" = "$WATCHDOG_MS" ] && watchdog_set=1 || true
}
wait_adb() {
  until [ "$(q get-state)" = device ]; do check_alive; sleep 2; done
}
wait_boot() {
  until [ "$(q shell getprop sys.boot_completed)" = 1 ]; do
    check_alive
    set_watchdog
    sleep 10
  done
  set_watchdog
}
# adbd 换成 root（uid 0）或 shell（uid 2000）身份，换的时候它会重启一下
adbd_as() {
  until [ "$(q shell id -u)" = "$1" ]; do
    check_alive
    q "$2" >/dev/null
    sleep 3
    wait_adb
  done
}
# 存储比 sys.boot_completed 晚挂上，挂上前往 /sdcard 推图会报 Transport endpoint is not connected。
# 光看目录能不能列不够：媒体库起得慢，第一次挂常常超时失败，卸掉过一两分钟再挂，
# 中间那一阵目录也能列。要等 StorageManager 报 mounted（sm 是 Java 命令，一次要十几秒）
wait_storage() {
  until [ "$(timeout 120 "$adb" -s "$SERIAL" shell 'sm list-volumes 2>/dev/null | grep -q "emulated;0 mounted" &&
      ls /storage/emulated/0/ >/dev/null 2>&1 && echo ok' 2>/dev/null | tr -d '\r' || true)" = ok ]; do
    check_alive
    sleep 10
  done
}
# ro 属性只能设一次，镜像本身没设
set_multiplier() {
  local v
  until v=$(q shell getprop ro.hw_timeout_multiplier); [ "$v" = "$MULTIPLIER" ]; do
    [ -z "$v" ] || { echo "ro.hw_timeout_multiplier 已经是 $v，改不了" >&2; return; }
    check_alive
    adbd_as 0 root
    q shell setprop ro.hw_timeout_multiplier "$MULTIPLIER" >/dev/null
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
  # 没有，或者是拿别的系统镜像建的（改过上面的 IMAGE），都重新建
  if ! grep -qsxF "image.sysdir.1=${IMAGE//;//}/" "$avd_dir/config.ini"; then
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
  # 启动器过一两秒才换成 qemu 进程
  launcher=$!
  until running; do
    kill -0 $launcher 2>/dev/null || { echo "模拟器没起来，日志在 $log" >&2; exit 1; }
    sleep 1
  done
fi

wait_adb
# 这次开机已经准备好了（属性重启模拟器才清掉）
if [ "$(q shell getprop debug.yomark.ready)" = 1 ]; then
  wait_boot
  wait_storage
  echo "$SERIAL"
  exit 0
fi

set_multiplier
wait_boot

# 框架里的 Java 代码拿到倍数没有：前台广播超时默认 10 秒，乘上了就不是这个数。
# 刚开完机很忙，这段要输出半分钟以上，dumpsys 默认 10 秒就掐断，所以加 -t
bcast=$(a shell 'dumpsys -t 120 activity broadcasts | grep -m1 bcast_timeout=' 2>/dev/null | tr -d '\r' || true)
[[ $bcast == *bcast_timeout=+* ]] || echo "没查到广播超时（$bcast），当作倍数已生效" >&2
if [[ $bcast == *bcast_timeout=+10s0ms* ]]; then
  echo "ro.hw_timeout_multiplier 设晚了，重启一次框架让它生效" >&2
  adbd_as 0 root
  a shell 'setprop sys.boot_completed 0 && stop && start'
  wait_boot
fi

adbd_as 2000 unroot

wait_storage

# 屏幕常亮、动画全关，记下这次开机已经准备好
a shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; svc power stayon true;
  settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
  settings put global animator_duration_scale 0; setprop debug.yomark.ready 1' >/dev/null 2>&1 || true
echo "$SERIAL"
