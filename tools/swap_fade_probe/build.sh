#!/usr/bin/env bash
# Сборка host-зонда процедуры смены каналов.
#
#   ./build.sh            — собрать новую реализацию (текущее дерево)
#   ./build.sh old        — собрать реализацию из HEAD (для A/B)
#
# Зонд компилируется и против новой, и против старой реализации: он не
# вызывает layoutGainAt/layoutSignAt напрямую, а мерит только звук.
set -u
cd "$(dirname "$0")"

ROOT="$(cd ../.. && pwd)"
# MinGW-g++ не понимает пути Git Bash (/e/...): нужен вид E:/...
ROOT="$(cygpath -m "$ROOT")"
CPP="$ROOT/core/audio/src/main/cpp"
OUT="$(cygpath -m "$PWD")/out"
mkdir -p "$OUT"

COMMON="-std=c++17 -O2 -D_USE_MATH_DEFINES -DANDROID -DAUDIO_TEST_BUILD -I$CPP/include -I$CPP -mssse3 -DUSE_SSE -include host_shim.h"

if [ "${1:-new}" = "old" ]; then
    # Реализация из HEAD: своя копия include/ и src/, чтобы не трогать дерево.
    TREE="$OUT/tree_old"
    rm -rf "$TREE"; mkdir -p "$TREE/include" "$TREE/src"
    (cd "$ROOT" && git show HEAD:core/audio/src/main/cpp/include/ChannelLayout.h) \
        > "$TREE/include/ChannelLayout.h"
    for h in Config.h Interpolation.h BufferPackagePlanner.h AudioGenerator.h Wavetable.h; do
        cp "$CPP/include/$h" "$TREE/include/$h"
    done
    cp "$CPP/src/AudioGenerator.cpp" "$TREE/src/AudioGenerator.cpp" 2>/dev/null || true
    (cd "$ROOT" && git show HEAD:core/audio/src/main/cpp/src/AudioGenerator.cpp) \
        > "$TREE/src/AudioGenerator.cpp"
    cp "$CPP/src/Wavetable.cpp" "$TREE/src/Wavetable.cpp"
    EXE="$OUT/probe_old.exe"
    g++ $COMMON -I"$TREE/include" -I"$TREE" probe.cpp \
        "$TREE/src/AudioGenerator.cpp" "$TREE/src/Wavetable.cpp" -o "$EXE"
else
    EXE="$OUT/probe_new.exe"
    g++ $COMMON probe.cpp \
        "$CPP/src/AudioGenerator.cpp" "$CPP/src/Wavetable.cpp" -o "$EXE"
fi

echo "собрано: $EXE"
