#!/bin/bash
# Длинный стресс кроссфейда с контролем кучи.
#
# Вопрос: утечка ли ~1 МБ на смену пресета (тогда процесс рано или поздно
# падает по OOM/watchdog) или это «шарик» кучи, который выходит на плато.
#
# Экран НЕ будим: всё через debug-приёмник.
set -u
ADB=/home/nikita/tools/android-sdk/platform-tools/adb
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$1'" 2>&1 |
        tr '\n' ' ' | sed -n 's/.*data="\([^"]*\)".*/\1/p'
}

# Ключевая цифра из `mem` — занято/выделено; pss вторичен.
heap() {
    cmd "gc" >/dev/null
    sleep 2
    cmd "mem" | grep -o 'java heap: [^"]*' | cut -d' ' -f1-9
}

pid() { "$ADB" shell pidof "$PKG" | tr -d '\r'; }

echo "=== PID до стресса: $(pid) ==="
echo "=== старт (5 c на прогрев) ==="
cmd "play" >/dev/null
sleep 8
echo "  0 смен: $(heap)"

for round in 1 2 3 4; do
    cmd "switch 50 500" >/dev/null
    # 50 смен по 500 мс = 25 c + хвост кроссфейдов и релиз осиротевших потоков.
    sleep 40
    echo "$((round * 50)) смен: $(heap)"
    echo "           PID=$(pid) состояние=$(cmd 'state')"
done

echo "=== свободно по growPacketBuffer (последние 5) ==="
"$ADB" shell "grep 'growPacketBuffer' $LOG | tail -n 5"

echo "=== признаки гибели ==="
"$ADB" logcat -d -t 6000 | grep -aiE \
    'OutOfMemory|Failed to allocate|memory leaks occurred|has died|FATAL|AndroidRuntime|createTrack_l' |
    tail -n 15
echo "(пусто = чисто)"
echo "=== PID после стресса: $(pid) ==="
