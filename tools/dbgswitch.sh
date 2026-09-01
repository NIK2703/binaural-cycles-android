#!/bin/bash
# Прогон стресс-теста быстрой смены пресетов с записью logcat.
# Использование: bash tools/dbgswitch.sh <количество> <задержкаМс>
set -u
ADB=/home/nikita/tools/android-sdk/platform-tools/adb
COUNT="${1:-12}"
DELAY="${2:-300}"
LOG=/tmp/binaural_switch_log.txt

rm -f "$LOG"
"$ADB" logcat -v time -b all > "$LOG" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null' EXIT

sleep 2
echo "=== baseline ==="
"$ADB" shell am broadcast -a com.binauralcycles.debug.COMMAND -p com.binauralcycles.debug \
    --es cmd "'mem'" 2>&1 | sed -n '/Broadcast completed/,$p'
echo "pid до: $("$ADB" shell pidof com.binauralcycles.debug)"

echo "=== switch $COUNT $DELAY ==="
"$ADB" shell am broadcast -a com.binauralcycles.debug.COMMAND -p com.binauralcycles.debug \
    --es cmd "'switch $COUNT $DELAY'" 2>&1 | sed -n '/Broadcast completed/,$p'

sleep $((COUNT * DELAY / 1000 + 10))

echo "=== после ==="
echo "pid после: $("$ADB" shell pidof com.binauralcycles.debug)"
"$ADB" shell am broadcast -a com.binauralcycles.debug.COMMAND -p com.binauralcycles.debug \
    --es cmd "'status'" 2>&1 | sed -n '/Broadcast completed/,$p'

kill "$LOGCAT_PID" 2>/dev/null
sleep 1
echo "=== logcat: ключевые события ==="
grep -iE "kill process|memory leaks|am_proc_died|am_proc_start|lowmemorykiller|Clamp target|FATAL|AndroidRuntime|binaural.*died" "$LOG" | tail -25
echo "=== logcat: switch + prepare/grow ==="
grep -iE "BinauralDebug|growPacketBuffer|allocateDirect|handoff|Handoff" "$LOG" | tail -60
