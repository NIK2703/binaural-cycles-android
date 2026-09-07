#!/usr/bin/env bash
# Шторм смен пресетов + ассерты против клика (верификация стыка
# «конец fade-in → полный звук» по телеметрии лога, без осциллографа).
# Использование: ./dbgclick_verify.sh [N]
set -u
PKG=${PKG:-com.binauralcycles.debug}
N=${1:-20}
cmd() { adb shell "am broadcast -a ${PKG}.COMMAND -p ${PKG} --es cmd '$1'"; }

adb logcat -c
cmd "play"; sleep 3
for i in $(seq 1 "$N"); do cmd "next"; sleep 0.4; done
sleep 2
cmd "pkstat"; sleep 1
LOG=$(adb logcat -d); fail=0

grep -qE 'underrunDelta=[1-9]'        <<<"$LOG" && { echo "FAIL: underrun на старте";  fail=1; }
grep -qE 'write failed|generate failed' <<<"$LOG" && { echo "FAIL: ошибка писателя";    fail=1; }
grep -qE 'INVARIANT НАРУШЕН'           <<<"$LOG" && { echo "FAIL: инвариант времени";  fail=1; }
grep -qE 'fallback отказал|жёсткая установка громкости' <<<"$LOG" && { echo "FAIL: аварийный setVolume"; fail=1; }
grep -qE 'КОЛЬЦО ВЫРОЖДЕНО'            <<<"$LOG" && { echo "WARN: HAL урезал кольцо"; }

# Все завершения фейд-инов должны прийти с live ≈ 1.0
grep -E 'fade-in completion' <<<"$LOG" | \
  awk 'match($0,/live=([0-9.]+)/,m){ if (m[1]+0 < 0.999) print "WARN: низкий settle: " $0 }'

echo "память: $(grep -oE 'holders=[0-9]+ peak=[0-9]+' <<<"$LOG" | tail -1)"
echo "переходов: $(grep -c 'ПОДНЯТ' <<<"$LOG") (ожидаем ~$N)"
[ $fail -eq 0 ] && echo "=== PASS ===" || { echo "=== FAIL ==="; exit 1; }
