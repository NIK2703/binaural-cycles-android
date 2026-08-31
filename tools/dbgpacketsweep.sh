#!/bin/bash
# Перебор потолка пакетного буфера (PACKET_MAX_BYTES) под штормом смен пресетов.
#
# Вопрос: при каком потолке ОДНОГО пакета шторм смен пресетов проходит без
# OOM — то есть без уполовинивания allocateDirect, без createTrack_l(-12) и
# без смерти процесса.
#
# Критерий «предел найден» (все четыре пункта):
#   1) oomHalvings == 0              — allocateDirect ни разу не делил запрос пополам;
#   2) holders peak <= 1             — инвариант «не больше ОДНОГО потока с пакетом»;
#   3) PID до прогона == PID после   — процесс не убил ни LMK, ни сторож Xiaomi;
#   4) в сыром логе нет OutOfMemory / createTrack_l / "memory leaks occurred".
#
# ВАЖНО ПРО ВЕРХНЮЮ ГРАНИЦУ: реальный размер пакета — это
# min(packetmax, heap / PACKET_HEAP_DIVISOR). На куче 256 МБ делитель 4 даёт
# 64 МБ, поэтому потолки выше 64 МБ на этом устройстве ничего не меняют —
# в pkstat это видно как perStreamCap, который перестаёт расти.
#
# Потолок меняется НА ХОДУ (debug-команда `packetmax`), поэтому весь перебор
# делается за одну установку — пересборка не нужна.
#
# Использование:
#   bash tools/dbgpacketsweep.sh                       # 32 48 64 80 96 112 128, 60 смен по 300 мс
#   bash tools/dbgpacketsweep.sh "64 96" 40 250
#
set -u
ADB=/home/nikita/tools/android-sdk/platform-tools/adb
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

CAPS="${1:-32 48 64 80 96 112 128}"
COUNT="${2:-60}"
DELAY="${3:-300}"

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$1'" 2>&1 |
        tr '\n' '~' | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tr '~' '\n'
}
pid() { "$ADB" shell pidof "$PKG" | tr -d '\r'; }

# Ключевые цифры pkstat: holders/peak и oomHalvings.
stat() {
    cmd "pkstat" | tr '\n' ' ' | sed 's/  */ /g'
}

echo "=== Старт: PID=$(pid) ==="
cmd "status" | grep -E "^(playing|volume|preset)" | head -3

# Убеждаемся, что воспроизведение идёт: команды звука требуют живой сервис.
if ! cmd "status" | grep -q "playing=true"; then
    echo "=== запуск воспроизведения ==="
    cmd "play" | head -2
    sleep 8
fi

for cap in $CAPS; do
    echo ""
    echo "############ Пакетный потолок: ${cap} МБ ############"
    cmd "packetmax $cap" | head -1
    # Даём потолку примениться: prepare() NEXT считает целевую ёмкость по нём.
    sleep 1
    cmd "pcreset" >/dev/null
    PID_BEFORE=$(pid)
    # Лог-маркер, по которому потом вырезаем хвост прогона из сырого лога.
    "$ADB" logcat -c >/dev/null 2>&1

    cmd "switch $COUNT $DELAY" | head -1
    # Серия + хвост кроссфейдов и релиз осиротевших потоков.
    sleep $(( COUNT * DELAY / 1000 + 12 ))

    PID_AFTER=$(pid)
    echo "  pkstat: $(stat)"
    echo "  heap:   $(cmd 'mem' | grep 'java heap' | cut -d' ' -f1-9)"
    echo "  PID:    $PID_BEFORE -> $PID_AFTER"
    echo "  state:  $(cmd 'state')"
    echo "  --- growPacketBuffer (последние 4) ---"
    "$ADB" shell "grep 'growPacketBuffer' $LOG | tail -n 4" | sed 's/^/    /'
    echo "  --- урезание allocateDirect (последние 4) ---"
    "$ADB" shell "grep 'allocateDirect урезал' $LOG | tail -n 4" | sed 's/^/    /'
    echo "  --- признаки гибели ---"
    "$ADB" logcat -d -t 8000 | grep -aiE \
        'OutOfMemory|Failed to allocate|memory leaks occurred|has died|FATAL|AndroidRuntime|createTrack_l' |
        tail -n 8 | sed 's/^/    /'
    echo "  (пусто = чисто)"

    if [ "$PID_BEFORE" != "$PID_AFTER" ]; then
        echo "  !!! ПРОЦЕСС УМЕР на потолке ${cap} МБ — предел ПРЕВЫШЕН"
        break
    fi
done

echo ""
echo "=== Итог: подходит последний потолок с oomHalvings=0 и holders peak<=1 ==="
echo "=== Финал: PID=$(pid) ==="
