#!/bin/bash
# Временной ряд кучи под заданным потолком пакета.
#
# Зачем: одиночный замер `mem` в конце прогона бесполезен — он попадает в
# случайную фазу GC и показывает то 68 МБ, то 217 МБ. Нужен РЯД: baseline
# до старта -> стартовый пакет -> доращивание -> шторм -> усадка после него.
#
# Печатается только java heap (занято/выделено/макс) и PSS, чтобы ряд читался.
#
# Использование:
#   bash tools/dbgheaptrace.sh 64
#   DIV=1 bash tools/dbgheaptrace.sh 80
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND

CAP="${1:-64}"
DIV="${DIV:-1}"

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$1'" 2>&1 |
        tr '\n' '~' | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tr '~' '\n'
}

# Короткая сводка: java heap + pss + сколько пакет реально занимает.
snap() {
    local label="$1"
    local heap pss pk
    heap=$(cmd "mem" | grep 'java heap' | grep -o '[0-9]*MB занято / [0-9]*MB выделено / [0-9]*MB максимум')
    pss=$(cmd "mem" | grep -o 'pss: total=[0-9]*MB dalvik=[0-9]*MB')
    pk=$(cmd "pkstat" | grep -o 'budget=[0-9]*МБ' | head -1)
    printf '  %-26s heap=%-42s %-38s пакет=%s\n' "$label" "$heap" "$pss" "$pk"
}

echo "########## Потолок ${CAP} МБ, делитель кучи ${DIV} ##########"
cmd "packetdiv $DIV" >/dev/null
cmd "packetmax $CAP" >/dev/null
cmd "stop" >/dev/null
sleep 6
cmd "gc" >/dev/null
sleep 2
snap "0. тишина (базис)"

cmd "play" >/dev/null
sleep 3
snap "1. старт (пакет 2 с)"
sleep 14
snap "2. дорос до цели"

cmd "switch 40 250" >/dev/null
sleep 5
snap "3. шторм, 5 с"
sleep 8
snap "4. шторм, 13 с"
sleep 8
snap "5. шторм, 21 с"

sleep 20
snap "6. через 20 с после шторма"
cmd "gc" >/dev/null
sleep 3
snap "7. после System.gc()"
echo
