#!/bin/bash
# ПОИСК МАКСИМАЛЬНОГО РАЗМЕРА ПАКЕТА БЕЗ OOM.
#
# Вопрос: до какого потолка ОДНОГО пакетного буфера приложение проходит весь
# набор типичных ситуаций без OOM — то есть без уполовинивания
# allocateDirect, без потери инварианта одного потока и без смерти процесса.
#
# Почему «весь набор», а не только шторм смен: после перехода на инвариант
# «загружен не более одного потока» шторм перестал быть худшим случаем по
# ПИКУ (одновременно живёт один пакет), но остался худшим по ОБОРОТУ
# аллокаций — за 18 с шторма создаётся и освобождается ~30 буферов полного
# размера. Худший случай по пику — одиночное долгое проигрывание с
# доращенным пакетом. Проверять надо оба, поэтому ситуаций несколько.
#
# Ситуации на каждом потолке:
#   S1 установившийся звук      — пакет доращён до целевого и висит в куче;
#   S2 шторм смен пресетов      — оборот аллокаций, latest-wins;
#   S3 шторм пауза -> смена -> resume (путь пересоздания из заморозки);
#   S4 смена частоты дискретизации — пересчёт пакета под другой SR;
#   S5 рестарт воспроизведения  — повторный рост после полной утилизации.
#
# Критерий «потолок подходит» (все четыре пункта):
#   1) oomHalvings == 0    — allocateDirect ни разу не делил запрос пополам;
#   2) holders peak <= 1   — инвариант «не больше одного потока с пакетом»;
#   3) PID до == PID после — процесс не убил ни LMK, ни сторож Xiaomi;
#   4) в logcat нет OutOfMemory / createTrack_l / "memory leaks occurred".
#
# ВАЖНО ПРО ВЕРХНЮЮ ГРАНИЦУ: реальный размер пакета — это
# min(packetmax, heap × packetpct%, общий бюджет heap × packetgpct%).
# На куче 256 МБ предел по умолчанию — heap × 86% = 220 МБ, поэтому потолки
# выше 220 МБ (с учётом packetmax) молча урезаются и прогон проверяет не то,
# что написано. Чтобы искать предел выше 220 МБ, поднимите PCT (и GPECT) до
# 95: тогда потолок определяет сам packetmax. В pkstat это видно как
# perStreamCap и limit: как только они перестают расти, предел упирается не
# в packetmax.
#
# Использование:
#   bash tools/dbgpacketlimit.sh                    # 32 48 64 80
#   bash tools/dbgpacketlimit.sh "64 96 128"        # только эти потолки
#   PCT=95 GPCT=95 bash tools/dbgpacketlimit.sh "128 160 192"   # честный перебор
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND

CAPS="${1:-32 48 64 80}"
# Доля кучи под ОДИН пакет, %. 86 — значение из кода (heap × 86% = 220 МБ на
# 256 МБ). Чтобы проверять потолки выше 220 МБ, поднимите до 95: тогда предел
# определяет сам packetmax, а не доля кучи.
PCT="${PCT:-86}"
# Доля кучи под СУММУ пакетов, %. Совпадает с PCT, пока работает инвариант
# «один поток» (сумма = одному пакету). Поднимите синхронно с PCT для честного
# перебора потолков выше 220 МБ.
GPCT="${GPCT:-86}"
# Перезапускать процесс перед каждым потолком (по умолчанию да).
#
# Зачем: крупные массивы на ART не возвращаются по System.gc() (проверено —
# обычный byte[] ведёт себя так же), поэтому куча процесса растёт монотонно и
# «свободное место» в конце длинного прогона уже не характеризует потолок.
# Без перезапуска результат зависит от того, сколько мусора накопилось до
# прогона, и предел получается разным от запуска к запуску.
FRESH="${FRESH:-1}"
STORM_COUNT="${STORM_COUNT:-40}"
STORM_DELAY="${STORM_DELAY:-250}"
# Запрашиваемый интервал генерации в МИНУТАХ. Должен быть ЗАВЕДОМО больше
# потолка: поток просит min(интервал, 60 мин, пределы), и если интервал короче
# потолка, пакет упрется в интервал, а не в потолок — перебор перестанет что-либо
# проверять. При 44.1 кГц стерео float это 211 МБ, то есть 10 минут перекрывают
# все разумные потолки.
BUFFER="${BUFFER:-10}"

cmd() {
    # --include-stopped-packages нужен после force-stop и после установки:
    # иначе система исключает «остановленный» пакет из broadcast'ов и процесс
    # не поднимается. Активити при этом НЕ запускается — только Application.
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages \
        --es cmd "'$1'" 2>&1 |
        tr '\n' '~' | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tr '~' '\n'
}

# Холодный старт процесса: убить и разбудить broadcast'ом. Активити не трогаем.
cold_start() {
    "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
    sleep 1
    local i
    for i in 1 2 3 4 5; do
        if [ -n "$(pid)" ]; then return 0; fi
        cmd "status" >/dev/null
        sleep 2
    done
    [ -n "$(pid)" ]
}
pid() { "$ADB" shell pidof "$PKG" | tr -d '\r'; }
stat1() { cmd "pkstat" | tr '\n' ' ' | sed 's/  */ /g'; }
heap1() { cmd "mem" | grep 'java heap' | cut -d' ' -f1-9; }

# Гарантировать живое воспроизведение: после stop сервис может остановиться.
ensure_playing() {
    local i
    for i in 1 2 3; do
        if cmd "status" | grep -q "playing=true"; then return 0; fi
        cmd "play" >/dev/null
        sleep 6
    done
    cmd "status" | grep -q "playing=true"
}

echo "=== Старт: PID=$(pid), PCT=$PCT GPCT=$GPCT FRESH=$FRESH ==="
if [ "$FRESH" = "1" ]; then
    cold_start || { echo "!!! процесс не поднимается — прервано"; exit 1; }
    echo "=== холодный старт: PID=$(pid) ==="
fi
ensure_playing || { echo "!!! воспроизведение не поднимается — прервано"; exit 1; }
echo

for cap in $CAPS; do
    echo "############################################################"
    echo "############ Пакетный потолок: ${cap} МБ ############"
    echo "############################################################"
    if [ "$FRESH" = "1" ] && [ "$cap" != "$(echo $CAPS | cut -d' ' -f1)" ]; then
        cold_start || { echo "!!! процесс не поднимается"; exit 1; }
        ensure_playing || { echo "!!! воспроизведение не поднимается"; exit 1; }
    fi
    cmd "buffer $BUFFER" >/dev/null
    cmd "packetpct $PCT" >/dev/null
    cmd "packetgpct $GPCT" >/dev/null
    cmd "packetmax $cap" | head -1
    cmd "pkstat" | sed -n '2p' | sed 's/^/    perStreamCap: /'
    sleep 1
    cmd "pcreset" >/dev/null
    PID_BEFORE=$(pid)
    "$ADB" logcat -c >/dev/null 2>&1

    # --- S1: установившийся звук, пакет доращён до целевого ---
    echo "  [S1] установившийся звук (пакет доращён)"
    cmd "stop" >/dev/null; sleep 4
    cmd "play" >/dev/null; sleep 14
    echo "       $(stat1)"

    # --- S2: шторм смен пресетов ---
    echo "  [S2] шторм смен: ${STORM_COUNT} смен через ${STORM_DELAY} мс"
    cmd "switch $STORM_COUNT $STORM_DELAY" | head -1
    sleep $(( STORM_COUNT * STORM_DELAY / 1000 + 12 ))
    echo "       $(stat1)"

    # --- S3: шторм пауза -> смена пресета -> resume ---
    echo "  [S3] шторм пауза -> смена -> resume (6 циклов)"
    i=1
    while [ $i -le 6 ]; do
        cmd "pause"  >/dev/null; sleep 0.5
        cmd "preset $(( (i % 3) + 1 ))" >/dev/null; sleep 0.15
        cmd "resume" >/dev/null; sleep 0.8
        i=$((i + 1))
    done
    echo "       $(stat1)"

    # --- S4: смена частоты дискретизации (пересчёт пакета под другой SR) ---
    echo "  [S4] смена частоты дискретизации"
    for sr in 48000 22050 44100; do
        cmd "samplerate $sr" >/dev/null; sleep 6
    done
    echo "       $(stat1)"

    # --- S5: рестарт воспроизведения, повторный рост пакета ---
    echo "  [S5] рестарт: stop -> play"
    cmd "stop" >/dev/null; sleep 5
    cmd "play" >/dev/null; sleep 12
    echo "       $(stat1)"

    PID_AFTER=$(pid)
    echo "  ----------------------------------------------------------"
    echo "  ИТОГ потолка ${cap} МБ:"
    echo "    pkstat:  $(stat1)"
    echo "    heap:    $(heap1)"
    echo "    PID:     $PID_BEFORE -> $PID_AFTER"
    echo "    state:   $(cmd 'state' | head -1)"
    # Лог берём из logcat (он очищен в начале потолка), а НЕ из файлового
    # binaural_stream.log: тот живёт между прогонами, и tail по нему
    # подтягивал бы строки прошлых экспериментов.
    LOGC=$("$ADB" logcat -d -t 20000)
    echo "  --- урезание allocateDirect (последние 5) ---"
    echo "$LOGC" | grep 'allocateDirect урезал' | tail -n 5 | sed 's/^/      /'
    echo "  --- отложенный рост (последние 5) ---"
    echo "$LOGC" | grep 'growPacketBuffer.*отложено' | tail -n 5 | sed 's/^/      /'
    echo "  --- признаки гибели ---"
    # createTrack_l НЕ фильтруем целиком: AudioFlinger штатно печатает им же
    # строки «AUDIO_OUTPUT_FLAG_FAST successful» и «mismatch between requested
    # flags» при каждом создании трека. Гибель — это только ошибки создания,
    # поэтому ловим createTrack_l в одном контексте с кодом ошибки/NO_MEMORY.
    echo "$LOGC" | grep -aiE \
        'OutOfMemory|Failed to allocate|memory leaks occurred|has died|FATAL|AndroidRuntime' |
        tail -n 10 | sed 's/^/      /'
    echo "$LOGC" | grep -aiE 'createTrack_l' | grep -aiE '(-[0-9]+)|NO_MEMORY|failed|error' |
        tail -n 10 | sed 's/^/      /'
    echo "      (пусто = чисто)"

    # Главное отличие «предел найден» от «предел не проверен»: пакет обязан
    # РЕАЛЬНО дорасти до цели. Если рост постоянно откладывается, поток всю
    # жизнь сидит на стартовом пакете (2 с) — OOM действительно нет, но и
    # пакета нет: писатель просыпается в 47 раз чаще, батарейная оптимизация
    # мертва. Такой потолок не считается рабочим.
    LIVE=$(cmd "pkstat" | grep -o 'budget=[0-9]*МБ' | head -1 | grep -o '[0-9]*')
    if [ -n "$LIVE" ] && [ "$LIVE" -lt $(( cap * 3 / 4 )) ]; then
        echo "  !!! ПАКЕТ НЕ ДОРОС: занято ${LIVE} МБ из ${cap} МБ — рост откладывается,"
        echo "      потолок нерабочий (поток сидит на стартовом пакете 2 с)"
    fi
    echo

    if [ "$PID_BEFORE" != "$PID_AFTER" ]; then
        echo "  !!! ПРОЦЕСС УМЕР на потолке ${cap} МБ — предел ПРЕВЫШЕН"
        break
    fi
done

echo "=== Финал: PID=$(pid) ==="
echo "=== Подходит последний потолок с oomHalvings=0 и holders peak<=1 ==="
