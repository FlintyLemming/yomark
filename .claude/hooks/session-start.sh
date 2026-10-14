#!/bin/bash
# Claude Code 云端会话的启动钩子：装好 Android SDK，让云端能跑 assembleDebug 和单元测试。
# 版本跟 .github/workflows/android-apk.yml 保持一致。本机（非云端）直接跳过。
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"
PACKAGES=("platform-tools" "platforms;android-36" "build-tools;35.0.0")

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "安装 Android cmdline-tools 到 $SDK"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
missing=()
for p in "${PACKAGES[@]}"; do
  [ -d "$SDK/${p//;//}" ] || missing+=("$p")
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "安装 SDK 包：${missing[*]}"
  yes | "$sdkmanager" --sdk_root="$SDK" --licenses > /dev/null || true
  "$sdkmanager" --sdk_root="$SDK" "${missing[@]}" > /dev/null
fi

cd "$CLAUDE_PROJECT_DIR"
# local.properties 已被 .gitignore 忽略；写上 sdk.dir，Gradle 不依赖环境变量也能找到 SDK
echo "sdk.dir=$SDK" > local.properties

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$SDK\""
    echo "export ANDROID_SDK_ROOT=\"$SDK\""
    echo "export PATH=\"$SDK/platform-tools:$SDK/cmdline-tools/latest/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

# 云端出口 IP 访问 Maven Central（repo.maven.apache.org）常被限流成 429，
# 用 init 脚本把它（含 Robolectric 运行时的下载）换成 Google 托管的 Maven Central 镜像。只放在本容器的 ~/.gradle，不影响本机和 CI。
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def useMirror = { RepositoryHandler repos ->
  repos.withType(MavenArtifactRepository).configureEach { repo ->
    if (repo.url.host == 'repo.maven.apache.org') repo.url = mirror
  }
}
beforeSettings { settings ->
  useMirror(settings.pluginManagement.repositories)
  useMirror(settings.dependencyResolutionManagement.repositories)
}
allprojects {
  useMirror(buildscript.repositories); useMirror(repositories)
  // Robolectric 在测试运行时自己去 Maven Central 下 android-all，同样走镜像
  tasks.withType(Test).configureEach { systemProperty 'robolectric.dependency.repo.url', mirror }
}
GRADLE

# 预热：下载 Gradle 发行版和依赖，容器快照会缓存它们
./gradlew --no-daemon -q :app:dependencies --configuration debugRuntimeClasspath > /dev/null
echo "Android 构建环境就绪：SDK=$SDK"
