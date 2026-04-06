#!/bin/bash
#
# Скрипт сборки проекта BinauralCycles
# Использует инструменты из ~/tools
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Настройка окружения
export JAVA_HOME="/home/nikita/tools/jdk-17.0.2"
export ANDROID_HOME="/home/nikita/tools/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Проверка JAVA_HOME
if [ ! -d "$JAVA_HOME" ]; then
    echo "❌ Ошибка: JDK не найден в $JAVA_HOME"
    exit 1
fi

# Проверка ANDROID_HOME
if [ ! -d "$ANDROID_HOME" ]; then
    echo "❌ Ошибка: Android SDK не найден в $ANDROID_HOME"
    exit 1
fi

echo "=============================================="
echo "🔧 BinauralCycles Build Script"
echo "=============================================="
echo "☕ JAVA_HOME: $JAVA_HOME"
echo "📱 ANDROID_HOME: $ANDROID_HOME"
echo "📂 Директория проекта: $SCRIPT_DIR"
echo "=============================================="
echo ""

# Проверка Java версии
echo "☕ Проверка Java версии..."
java -version

echo ""
echo "🔨 Запуск сборки..."
echo ""

cd "$SCRIPT_DIR"

# Проверка наличия gradlew
if [ ! -f "./gradlew" ]; then
    echo "⚠️  gradlew не найден, генерируем wrapper..."
    /home/nikita/tools/gradle-8.4/bin/gradle wrapper --gradle-version 8.4
fi

# Запуск Gradle wrapper
./gradlew assembleDebug --no-daemon "$@"
