#!/usr/bin/env bash
# Замер ямы ретюна по лог-маркерам. Требует подключённое устройство и
# установленный debug-вариант. Команды — через существующий debug-CLI.
set -u
PKG=${PKG:-com.binauralcycles.debug}
N=${N:-8}                       # число переключений
cmd() { adb shell am broadcast -a "$PKG.COMMAND" -p "$PKG" --es cmd "$1" >/dev/null; }

adb logcat -c
cmd "play"; sleep 4
for i in $(seq 1 "$N"); do cmd "next"; sleep 2; done
sleep 1
adb logcat -d | grep -E "retune|pre-gen|VALLEY_MS|PREGEN_MS|COMMIT_MS|INVARIANT|recreateTrack" \
    > retune_valley_raw.log

echo "=== сырые маркеры ==="; cat retune_valley_raw.log; echo

extract() { grep -o "$1=[0-9]*" retune_valley_raw.log | cut -d= -f2 | sort -n; }
stats()   { awk '{a[NR]=$1} END{ if(NR==0){print "  нет данных"; exit 1}
              printf "  n=%d min=%d med=%d max=%d\n", NR, a[1], a[int((NR+1)/2)], a[NR] }'; }

echo "предгенерация, мс:";   extract PREGEN_MS | stats
echo "коммит писателя, мс:"; extract COMMIT_MS | stats
echo "ЯМА, мс:";             extract VALLEY_MS | stats

FAIL=0
V=$(extract VALLEY_MS | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
[ -n "$V" ] && [ "$V" -le 60 ] || { echo "FAIL: медиана ямы ${V:-н/д} > 60 мс"; FAIL=1; }
grep -q "INVARIANT НАРУШЕН" retune_valley_raw.log && { echo "FAIL: нарушение инварианта"; FAIL=1; }
grep -q "recreateTrack"     retune_valley_raw.log && { echo "FAIL: был пересоздан трек"; FAIL=1; }
grep -q "preGen=false"      retune_valley_raw.log && echo "WARN: были фолбэки без предгенерации"
[ "$FAIL" -eq 0 ] && echo "PASS" || exit 1
