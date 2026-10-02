#!/usr/bin/env bash
# 不用 gradle.bat 启动 Gradle：部分企业安全策略会拦截 cmd.exe，
# 所以改成用 java 直接调 GradleMain。
#
# ⚠ 本文件**不要写本机路径**（开源时会暴露你的环境）。按下面顺序解析：
#   1) 环境变量 JAVA_HOME / ANDROID_HOME（或 ANDROID_SDK_ROOT）/ GRADLE_LAUNCHER
#   2) 同目录下的 gradle.local.sh —— 个人配置，已在 .gitignore 里，格式见 .example
#   3) 自动探测：JAVA_HOME 从 PATH 上的 java 推断；Android SDK 读 local.properties 的 sdk.dir
#   4) PATH 上若有 gradle 命令，直接用它
set -euo pipefail

cd "$(dirname "$0")"

# ── 个人配置（可选）──
# shellcheck disable=SC1091
[ -f ./gradle.local.sh ] && . ./gradle.local.sh

# ── JAVA_HOME ──
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 \
               | sed -n 's/^ *java\.home = //p' | head -1 | tr '\\' '/')"
fi
export JAVA_HOME
if [ -z "${JAVA_HOME:-}" ]; then
  echo "找不到 JDK。请设置环境变量 JAVA_HOME，或写进 gradle.local.sh。" >&2
  exit 1
fi

# ── ANDROID_HOME：优先环境变量，其次 local.properties 的 sdk.dir ──
#    (Java properties 会把 sdk.dir 的盘符与反斜杠转义，所以要还原成普通路径)
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -f local.properties ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1 \
                  | tr '\\' '/' | sed -e 's|/:|:|' -e 's|//*|/|g')"
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "$ANDROID_HOME" ]; then
  echo "找不到 Android SDK。请设置 ANDROID_HOME，或在 local.properties 里写 sdk.dir=..." >&2
  exit 1
fi

# ── 启动 Gradle ──
if [ -z "${GRADLE_LAUNCHER:-}" ] && command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
if [ -z "${GRADLE_LAUNCHER:-}" ]; then
  cat >&2 <<'MSG'
找不到 Gradle，二选一：
  · 把 gradle 加进 PATH；或
  · 建一个 gradle.local.sh（已在 .gitignore 里）写入：
        GRADLE_LAUNCHER="<gradle 发行包>/lib/gradle-launcher-<版本>.jar"
MSG
  exit 1
fi

exec "$JAVA_HOME/bin/java" \
  -Xmx2048m \
  -Dfile.encoding=UTF-8 \
  -Dorg.gradle.appname=gradle \
  -classpath "$GRADLE_LAUNCHER" \
  org.gradle.launcher.GradleMain "$@"
