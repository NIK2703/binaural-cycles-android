#!/usr/bin/env bash
# Воспроизведение бага «при возврате в свёрнутое приложение воспроизведение
# перезапускается».
#
# Сценарий:
#   1. открываем MainActivity;
#   2. стартуем воспроизведение так же, как это делает UI: `play`, затем
#      `preset 1` (applyPreset применяет РЕАЛЬНЫЕ настройки из DataStore —
#      без этого поток поднялся бы на дефолтах движка и первая же синхронизация
#      настроек выглядела бы как «перезапуск»);
#   3. HOME — приложение сворачивается, сервис играет в фоне;
#   4. возвращаемся launcher-интентом;
#   5. смотрим лог: если после возврата пошли beginHandoff/launchSpec —
#      воспроизведение перезапустилось, баг есть.
#
# Переменные окружения:
#   FINISH=1 — «не сохранять операции»: активити гарантированно уничтожается
#              при сворачивании (самый жёсткий вариант возврата).
#   NOHOME=1 — не уходить домой (активити остаётся на экране).
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
PKG=com.binauralcycles.debug
MAIN_ACT=com.binauralcycles.MainActivity
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages \
        --es cmd "'$1'" 2>&1 |
        sed -n '/Broadcast completed/,$p' | sed 's/^Broadcast completed: result=0, data="//; s/"$//'
}
logsize() { "$ADB" shell stat -c '%s' "$LOG" 2>/dev/null | tr -d '\r'; }
activity_state() {
    "$ADB" shell dumpsys activity activities 2>/dev/null | tr -d '\r' |
        grep -E "mResumedActivity|topResumedActivity|Hist #" | grep -i binaural | head -3
}

if [ "${FINISH:-0}" = "1" ]; then
    "$ADB" shell settings put global always_finish_activities 1 >/dev/null
    echo "always_finish_activities = 1 (активити уничтожается при сворачивании)"
else
    "$ADB" shell settings put global always_finish_activities 0 >/dev/null
    echo "always_finish_activities = 0 (система решает сама)"
fi

"$ADB" shell input keyevent 224 >/dev/null 2>&1   # wakeup
sleep 1
"$ADB" shell input keyevent 82  >/dev/null 2>&1   # разблокировка (если без PIN)
sleep 1

echo "=== холодный старт ==="
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
"$ADB" shell am start -n "$PKG/$MAIN_ACT" 2>&1 | grep -i error
sleep 6
echo "PID=$("$ADB" shell pidof "$PKG" | tr -d '\r')"
activity_state
cmd "status" | head -4

echo
echo "=== старт воспроизведения (путь UI: play, затем реальные настройки) ==="
cmd "play" | head -1
sleep 2
cmd "preset 1" | head -1
sleep 6
OFFSET=$(logsize)
echo "лог с позиции $OFFSET"
cmd "status" | head -2

if [ "${NOHOME:-0}" != "1" ]; then
    echo
    echo "=== сворачиваем (HOME) ==="
    "$ADB" shell input keyevent 3 >/dev/null
    sleep 3
    echo "активити после HOME:"; activity_state
    cmd "status" | head -2
fi

echo
echo "=== возвращаемся (launcher intent) ==="
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -n "$PKG/$MAIN_ACT" 2>&1 | grep -i error
sleep 6
cmd "status" | head -4

echo
echo "=== лог после возврата (маркеры перезапуска) ==="
"$ADB" shell "tail -c +$((OFFSET + 1)) $LOG" 2>/dev/null | tr -d '\r' | head -60

echo
echo "=== PID после возврата: $("$ADB" shell pidof "$PKG" | tr -d '\r') ==="
