#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生成真实的 Gradle Wrapper（gradlew / gradlew.bat / gradle/wrapper/gradle-wrapper.jar）
#
# 为什么需要这个脚本：开发这套代码的环境没有网络，无法获取 gradle-wrapper.jar
# （它是二进制文件，不能凭文本伪造，也不应伪造）。在任意有网络 + JDK 17 的机器上
# 执行本脚本一次，即可得到与 gradle-wrapper.properties 中版本一致的官方 Wrapper。
#
# 使用方式（在 android/ 目录下）：
#   bash tools/generate-wrapper.sh
#
# 前置条件（任选其一）：
#   A. 本机已安装 Gradle 8.9+（任意版本都能生成 wrapper，生成的是 properties 里指定的版本）
#        gradle wrapper --gradle-version 8.9
#   B. 没有 Gradle：先下载官方发行包再生成
#        curl -fL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
#        unzip -q /tmp/gradle.zip -d /tmp
#        /tmp/gradle-8.9/bin/gradle wrapper --gradle-version 8.9
#
# 生成后目录应包含：
#   gradlew                 （Linux/macOS 启动脚本）
#   gradlew.bat             （Windows 启动脚本）
#   gradle/wrapper/gradle-wrapper.jar
#   gradle/wrapper/gradle-wrapper.properties   ← 已存在，版本 8.9
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE_VERSION="$(grep -oP 'gradle-\K[0-9.]+(?=-bin\.zip)' gradle/wrapper/gradle-wrapper.properties || echo 8.9)"
echo "目标 Gradle 版本: ${GRADLE_VERSION}"

if command -v gradle >/dev/null 2>&1; then
  echo "使用已安装的 gradle: $(gradle --version | grep -m1 Gradle)"
  gradle wrapper --gradle-version "${GRADLE_VERSION}" --distribution-type bin
else
  echo "未找到 gradle 命令。请按脚本头部注释的 B 方案先获取发行包，或手动执行：" >&2
  echo "  gradle wrapper --gradle-version ${GRADLE_VERSION} --distribution-type bin" >&2
  exit 1
fi

echo
echo "生成结果："
ls -l gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar 2>/dev/null || true
echo
echo "接下来："
echo "  ./gradlew :core-schedule:test      # 纯 JVM 单元测试"
echo "  ./gradlew :app:assembleDebug       # 编译 Debug APK"
