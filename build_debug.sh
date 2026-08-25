#!/data/data/com.termux/files/usr/bin/bash
#
# Однокомандная сборка DEBUG APK (всё локально, без скачивания toolchains).
# Использует уже установленные в Termux инструменты:
#   - Gradle 9.2.1 (граббер из gradle-home)
#   - Android SDK / NDK r29 / CMake (локальные пути)
#   - aapt2/zipalign  для aarch64 (Termux android-tools)
#
# Использование:
#   ./build_debug.sh                               # собрать debug (4 ABI)
#   ./build_debug.sh --arm64                       # debug, только arm64-v8a + копия в ~/storage/downloads
#   ./build_debug.sh --abi arm64 :app:assembleRelease   # release, только arm64-v8a + копия в загрузки
#   ./build_debug.sh --abi arm64,x86_64 :app:assembleDebug
#
# --abi SPEC: arm64 | armv7 | x86 | x86_64 (или полные имена ABI), можно несколько через запятую.
#   Передаётся в Gradle как -PabiFilter=... — нативный код собирается ТОЛЬКО для выбранных ABI.
#   Собранные APK выбранных ABI автоматически копируются в ~/storage/downloads.
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
JAVA_HOME_="${JAVA_HOME:-$PREFIX_/lib/jvm/java-21-openjdk}"

map_abi() { # короткое имя -> полное имя ABI
    case "$1" in
        arm64|arm64-v8a)  echo "arm64-v8a" ;;
        armv7|arm32|arm|armeabi-v7a) echo "armeabi-v7a" ;;
        x86)              echo "x86" ;;
        x64|x86_64)       echo "x86_64" ;;
        *)                echo "" ;;
    esac
}

# По умолчанию собираем debug APK; любые не-флаговые аргументы — это Gradle-задачи.
TASKS=()
COPY_ARM64=0
ABI_SPEC=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --arm64) COPY_ARM64=1; ABI_SPEC="${ABI_SPEC:+$ABI_SPEC,}arm64" ;;
        --abi) shift; ABI_SPEC="${ABI_SPEC:+$ABI_SPEC,}$1" ;;
        --abi=*) ABI_SPEC="${ABI_SPEC:+$ABI_SPEC,}${1#--abi=}" ;;
        *) TASKS+=("$1") ;;
    esac
    shift
done

GRADLE_PROPS=()
COPY_APKS=0
if [ -n "$ABI_SPEC" ]; then
    ABIS=""
    IFS=',' read -ra PARTS <<< "$ABI_SPEC"
    for p in "${PARTS[@]}"; do
        FULL=$(map_abi "$(echo "$p" | tr -d '[:space:]')")
        if [ -z "$FULL" ]; then
            echo "❌ Неизвестная архитектура: '$p' (доступны: arm64, armv7, x86, x86_64)"
            exit 1
        fi
        ABIS="${ABIS:+$ABIS,}$FULL"
    done
    GRADLE_PROPS+=("-PabiFilter=$ABIS")
    COPY_APKS=1
fi
[ "${#TASKS[@]}" -eq 0 ] && TASKS=(":app:assembleDebug")

# GRADLE_BIN можно задать вручную; иначе ищем самую новую версию Gradle в dists
if [ -n "${GRADLE_BIN:-}" ] && [ -x "$GRADLE_BIN" ]; then
    : # используем внешний путь
else
    GRADLE_BIN=$(find "$GRADLE_HOME/wrapper/dists" -maxdepth 6 -type f -path '*/gradle-9.*/bin/gradle' 2>/dev/null | sort -V | tail -1)
fi

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

# zipalign: AGP использует его из build-tools (для x86_64) -> заменяем на aarch64 во ВСЕХ версиях build-tools.
ZIPALIGN="$PREFIX_/bin/zipalign"
for BT_DIR in "$SDK"/build-tools/*/; do
    BT_ZIPALIGN="$BT_DIR/zipalign"
    if [ -f "$ZIPALIGN" ] && [ -f "$BT_ZIPALIGN" ] && [ "$(ELF_MACHINE "$BT_ZIPALIGN")" = "62" ]; then
        cp "$BT_ZIPALIGN" "$BT_ZIPALIGN.x64-orig"
        cp "$ZIPALIGN" "$BT_ZIPALIGN"
        chmod +x "$BT_ZIPALIGN"
        echo "🔧 zipalign в $(basename "$BT_DIR") заменён на aarch64"
    fi
done

# === СБОРКА ===
echo "=============================================="
echo "🔨 BinauralBeats Build"
echo "=============================================="
echo "📦 Gradle: $GRADLE_BIN"
echo "🏗️  Tasks: ${TASKS[*]}"
[ "${#GRADLE_PROPS[@]}" -gt 0 ] && echo "🎯 ABI: ${GRADLE_PROPS[0]#-PabiFilter=}"
echo "=============================================="

# Определяем варианты сборки (debug/release) ТОЛЬКО из явно заданных Gradle-задач,
# чтобы не листать и не копировать СТАРЫЕ APK другого варианта из выходной папки.
BUILT_VARIANTS=""
for _t in "${TASKS[@]}"; do
    case "$_t" in
        *Release*) BUILT_VARIANTS="${BUILT_VARIANTS:+$BUILT_VARIANTS }release" ;;
        *Debug*)   BUILT_VARIANTS="${BUILT_VARIANTS:+$BUILT_VARIANTS }debug" ;;
    esac
done
[ -z "$BUILT_VARIANTS" ] && BUILT_VARIANTS="debug"

cd "$SCRIPT_DIR"
"$GRADLE_BIN" "${GRADLE_PROPS[@]}" "${TASKS[@]}" --no-daemon --console=plain 2>&1 | tail -40
EXIT="${PIPESTATUS[0]}"

if [ "$EXIT" -ne 0 ]; then
    echo "❌ Сборка упала с кодом $EXIT"
    exit "$EXIT"
fi

echo ""
echo "✅ Сборка завершена. APK (только собранный вариант: $BUILT_VARIANTS):"
for _v in $BUILT_VARIANTS; do
    find "$SCRIPT_DIR/app/build/outputs/apk/$_v" -name "app-*-$_v.apk" 2>/dev/null
done | while read -r apk; do
    echo "   📦 $apk  ($(du -h "$apk" | cut -f1))"
done

if [ "$COPY_APKS" -eq 1 ]; then
    DEST="$TERMUX_HOME/storage/downloads"
    mkdir -p "$DEST"
    IFS=',' read -ra COPY_ABIS <<< "$ABIS"
    for abi in "${COPY_ABIS[@]}"; do
        for variant in $BUILT_VARIANTS; do
            APK="$SCRIPT_DIR/app/build/outputs/apk/$variant/app-$abi-$variant.apk"
            if [ -f "$APK" ]; then
                cp "$APK" "$DEST/"
                echo "   🗂️ Скопировано в $DEST: app-$abi-$variant.apk ($(du -h "$APK" | cut -f1))"
            fi
        done
    done
fi
exit "$EXIT"