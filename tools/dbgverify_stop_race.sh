#!/bin/bash
# Проверка вторичной гонки: прерванный стоп (stop -> play во время фейда)
# НЕ должен сбрасывать позицию в 0:00.
ADB=/home/nikita/tools/android-sdk/platform-tools/adb
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
run() {
  "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages --es cmd "'$1'" 2>&1 \
    | tr '\n' ' ' | grep -o 'data="[^"]*"' | head -1 | sed 's/data="//; s/"$//'
}
audible() { run "audible" | grep -o 'audible=[0-9]*' | head -1 | sed 's/audible=//'; }

echo "== PLAY =="
run "play"; sleep 4
A1=$(audible); echo "AUDIBLE playing = $A1"
echo "== STOP (hard fade) + сразу PLAY (прерываем стоп) =="
run "stop"
sleep 0.3
run "play"
sleep 2
A2=$(audible); echo "AUDIBLE after interrupted stop->play = $A2"
echo "== STATE =="
run "state"; echo
echo "=== ИТОГ ==="
echo "A1=$A1  A2=$A2"
if [ -n "$A2" ] && [ "$A2" != "0" ] && [ "$A2" -ge "$A1" ]; then
  echo "PASS: прерванный стоп->play продолжает с позиции (не 0:00)"
else
  echo "FAIL: позиция сброшена в 0:00 (A2=$A2)"
fi
