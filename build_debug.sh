#!/data/data/com.termux/files/usr/bin/bash
#
# Однокомандная сборка DEBUG APK (всё локально, без скачивания toolchains).
# Использует уже установленные в Termux инструменты:
#   - Gradle 9.2.1 (граббер из gradle-home)
#   - Android SDK / NDK r29 / CMake (локальные пути)
#   - aapt2/zipalign  для aarch64 (Termux android-tools)
#
# Использование:
#   ./build_debug.sh                       # собрать debug (4 ABI)
#   ./build_debug.sh --arm64               # собрать debug и скопировать arm64-v8a APK в ~/storage/downloads
#   ./build_debug.sh <gradle-task>...      # выполнить произвольные Gradle-задачи (env настроен)
#   Примеры:
#   ./build_debug.sh :core:audio:externalNativeBuildRelease
#   ./build_debug.sh :core:audio:assembleRelease
#   ./build_debug.sh :app:assembleDebug :core:audio:externalNativeBuildDebug
#
set -u

# === КОНФИГУРАЦИЯ ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
PREFIX_="${PREFIX:-/data/data/com.termux/files/usr}"
TOOLS="$TERMUX_HOME/tools"
SDK="$TOOLS/android-sdk"
NDK="$TOOLS/android-ndk-r29"
GRADLE_HOME="$TOOLS/gradle-home"
JAVA_HOME_="${JAVA_HOME:-$PREFIX_/lib/jvm/java-17-openjdk}"

# По умолчанию собираем debug APK; любые не-флаговые аргументы — это Gradle-задачи.
TASKS=()
COPY_ARM64=0
for arg in "$@"; do
    case "$arg" in
        --arm64) COPY_ARM64=1 ;;
        *) TASKS+=("$arg") ;;
    esac
done
[ "${#TASKS[@]}" -eq 0 ] && TASKS=(":app:assembleDebug")

GRADLE_BIN=$(find "$GRADLE_HOME/wrapper/dists" -maxdepth 5 -type f -path '*/gradle-9.2.1/bin/gradle' 2>/dev/null | head -1)

if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
    echo "❌ Gradle 9.2.1 не найден в $GRADLE_HOME/wrapper/dists"
    exit 1
fi

# === ОКРУЖЕНИЕ ===
export HOME="$TERMUX_HOME"
export JAVA_HOME="$JAVA_HOME_"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$GRADLE_HOME"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$NDK"

# === ПРОВЕРКА ПРЕДУСЛОВИЙ ===
for p in "$SDK" "$NDK" "$GRADLE_BIN"; do
    [ -e "$p" ] || { echo "❌ Отсутствует: $p"; exit 1; }
done

# === ПАТЧ HOST-ИНСТРУМЕНТОВ (идемпотентно, только если внутри x86_64) ===
ELF_MACHINE() { # $1=файл -> 62=x86_64, 183=aarch64
    head -c 20 "$1" 2>/dev/null | dd bs=1 skip=18 count=2 2>/dev/null | od -A n -t u1 | awk '{print $1}'
}

# aapt2: AGP берёт его из своего jar (для GNU/Linux x86_64) -> заменяем на aarch64 для Termux.
AAPT2="$PREFIX_/bin/aapt2"
AAPT2_JAR=$(find "$GRADLE_HOME/caches" -name 'aapt2-*-linux.jar' -type f 2>/dev/null | head -1)
if [ -n "$AAPT2_JAR" ] && [ -f "$AAPT2" ]; then
    TMP="$(mktemp -d)"
    unzip -o -q "$AAPT2_JAR" 'aapt2' -d "$TMP" 2>/dev/null
    if [ "$(ELF_MACHINE "$TMP/aapt2")" = "62" ]; then
        cp "$TMP/aapt2" "$TMP/aapt2.x64-orig"
        cp "$AAPT2" "$TMP/aapt2"
        jar uf "$AAPT2_JAR" -C "$TMP" aapt2 2>/dev/null
        echo "🔧 aapt2 в $AAPT2_JAR заменён на aarch64"
    fi
    rm -rf "$TMP"
fi

# zipalign: AGP использует его из build-tools (для x86_64) -> заменяем на aarch64.
ZIPALIGN="$PREFIX_/bin/zipalign"
BT_ZIPALIGN="$SDK/build-tools/34.0.0/zipalign"
if [ -f "$ZIPALIGN" ] && [ -f "$BT_ZIPALIGN" ] && [ "$(ELF_MACHINE "$BT_ZIPALIGN")" = "62" ]; then
    cp "$BT_ZIPALIGN" "$BT_ZIPALIGN.x64-orig"
    cp "$ZIPALIGN" "$BT_ZIPALIGN"
    chmod +x "$BT_ZIPALIGN"
    echo "🔧 zipalign в build-tools/34.0.0 заменён на aarch64"
fi

# === СБОРКА ===
echo "=============================================="
echo "🔨 BinauralBeats Build"
echo "=============================================="
echo "📦 Gradle: $GRADLE_BIN"
echo "🏗️  Tasks: ${TASKS[*]}"
echo "=============================================="

cd "$SCRIPT_DIR"
"$GRADLE_BIN" "${TASKS[@]}" --no-daemon --console=plain 2>&1 | tail -40
EXIT="${PIPESTATUS[0]}"

if [ "$EXIT" -ne 0 ]; then
    echo "❌ Сборка упала с кодом $EXIT"
    exit "$EXIT"
fi

echo ""
echo "✅ Сборка завершена. APK:"
find "$SCRIPT_DIR/app/build/outputs/apk" -name 'app-*-debug.apk' -o -name 'app-*-release.apk' 2>/dev/null | while read -r apk; do
    echo "   📦 $apk  ($(du -h "$apk" | cut -f1))"
done

if [ "$COPY_ARM64" = "1" ]; then
    APK_ARM64="$SCRIPT_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
    if [ -f "$APK_ARM64" ]; then
        DEST="$TERMUX_HOME/storage/downloads/app-arm64-v8a-debug.apk"
        cp "$APK_ARM64" "$DEST"
        echo "   🗂️ Скопировано в: $DEST"
    fi
fi
exit "$EXIT"