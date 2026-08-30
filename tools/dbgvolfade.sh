#!/usr/bin/env bash
# Проверка: правка громкости ВНУТРИ окна кроссфейда обязана дойти и до
# вооружённого NEXT, а не только до уходящего CURRENT.
#
# Сценарий: выставить 0.9 -> подождать -> сменить пресет и через 100 мс
# (ещё внутри 250-мс фейд-аута) выставить 0.3.
# Ожидание в логе: две строки `setVolume spec#<N> -> 0.3` — для CURRENT и для
# NEXT. Если вторая пропала, новый пресет зазвучит по-старому (0.9).
#
#   bash tools/dbgvolfade.sh
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$1'" 2>&1 |
        sed -n 's/^Broadcast completed: result=0, data="//; s/"$//p'
}

echo "--- volume 0.9 ---";                       cmd "volume 0.9"
sleep 3
echo "--- preset 2, затем через 100 мс volume 0.3 ---"
cmd "preset 2"
sleep 0.1
cmd "volume 0.3"
sleep 6

echo
echo "=== что реально применилось (время устройства) ==="
"$ADB" shell "tail -n 70 '$LOG'" |
    grep -E 'setVolume|beginHandoff|prepare OK|promoteNextToCurrent|ТОЧКА ТИШИНЫ'
