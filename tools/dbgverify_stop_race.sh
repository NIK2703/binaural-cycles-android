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
now() { run "audible" | grep -o 'now=[0-9]*' | head -1 | sed 's/now=//'; }
# Круговое расстояние по 24-часовой оси: ловит переход через полночь.
norm_delta() {
  local d=$(( ($1 - $2) % 86400 ))
  [ "$d" -lt 0 ] && d=$((d + 86400))
  echo "$d"
}

echo "== PLAY =="
run "play"; sleep 4
A1=$(audible); echo "AUDIBLE playing = $A1"
echo "== STOP (hard fade) + сразу PLAY (прерываем стоп) =="
run "stop"
sleep 0.3
run "play"
sleep 2
A2=$(audible); N2=$(now); echo "AUDIBLE after interrupted stop->play = $A2 (now=$N2)"
echo "== STATE =="
run "state"; echo
echo "=== ИТОГ ==="
echo "A1=$A1  A2=$A2  now=$N2"
D=$(norm_delta "$A2" "$N2")
# Критерий другой, чем был: прерванный стоп — это тот же СВЕЖИЙ СТАРТ, а не
# «продолжение с позиции». Звук обязан соответствовать текущему моменту суток,
# а не отметке A1. Проверка A2 >= A1 была бы проверкой ошибочного поведения.
if [ -n "$A2" ] && [ "$A2" != "0" ] && [ "$D" -lt 5 ]; then
  echo "PASS: прерванный стоп->play стартует с текущего момента суток (Δ=$D с, не 0:00)"
else
  echo "FAIL: audible=$A2 не совпадает с now=$N2 (Δ=$D с) либо сброшен в 0:00"
fi
