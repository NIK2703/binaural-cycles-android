#!/usr/bin/env bash
# Замер таймингов кроссфейда при смене пресетов.
#
# НИКОГДА не будит устройство и не запускает активити: все команды уходят
# через adb-интерфейс, который работает и при погашенном экране.
# Ориентиры времени берутся с хоста, чтобы потом сопоставить их с логом.
#
# Использование:
#   bash tools/dbgxfade.sh              полный прогон (все фазы)
#   bash tools/dbgxfade.sh "a b"        только фазы A и B
#   PHASES="a b" bash tools/dbgxfade.sh
#
# Путь до adb и адрес устройства задаются снаружи — в Windows и в Termux они
# разные, плюс adb-демон в песочнице поднимается заново:
#   DEVICE=192.168.61.212:5555 ADB=/path/to/adb bash tools/dbgxfade.sh
# DEVICE пуст — работаем по единственному подключённому устройству.
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
DEVICE=${DEVICE:-}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

# Демон может быть мёртв (или только что поднят) — подключаемся сами. Без этого
# команды молча уходят в никуда: sed в cmd() вырезает сообщение об ошибке, и
# прогон выглядит успешным, а в логе устройства нет ни одной строки.
ADB_ARGS=()
if [ -n "$DEVICE" ]; then
  "$ADB" connect "$DEVICE" >/dev/null 2>&1
  ADB_ARGS=(-s "$DEVICE")
fi
adb() { "$ADB" "${ADB_ARGS[@]}" "$@"; }

if ! adb shell true >/dev/null 2>&1; then
  echo "НЕТ УСТРОЙСТВА: adb не видит цель (DEVICE='${DEVICE}')." >&2
  echo "Сначала: adb connect <ip:5555> — иначе прогон пройдёт впустую." >&2
  exit 1
fi

cmd() {
    # --include-stopped-packages: поднять процесс после установки или
    # force-stop, когда пакет числится «остановленным» и система исключает его
    # из broadcast'ов. Активити при этом не запускается.
    adb shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages \
        --es cmd "'$1'" 2>&1 |
        sed -n '/Broadcast completed/,$p' | sed 's/^Broadcast completed: result=0, data="//; s/"$//'
}
logsize() { adb shell stat -c '%s' "$LOG" 2>/dev/null | tr -d '\r'; }

# Все маркеры, по которым потом считаются тайминги
MARKERS='beginHandoff|requestHandoff|fadeOutCurrent|fade-out\(|fade-in|start spec|prepare |releaseInternal|writerLoop exit|onStreamReleased|onStreamFullyStopped|createAudioTrack|VolumeShaper|RC1|growPacketBuffer|launchSpec|launchStream|discardPausedCurrent|resumeFromPaused|onResumeFromPaused|switch #'

# Холодный старт процесса: иначе прогон наследует runtime-override'ы от
# прошлых экспериментов (packetdiv/packetgdiv/packetmax/buffer живут в
# companion-объекте до перезапуска) и проверяет не дефолтную конфигурацию,
# а случайную. --include-stopped-packages поднимает процесс без активити.
adb shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1
for _ in 1 2 3 4 5; do
    [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] && break
    cmd "status" >/dev/null
    sleep 2
done

# force-stop убивает и воспроизведение, а фазы A–C первым же шагом шлют
# `preset N`. Без запущенного сервиса они отвечают «Сервис не запущен» и
# проходят впустую (в логе потом ноль `switch #`) — прогон выглядит зелёным,
# но стресс-серию не проверяет. Поэтому стартуем playback до фаз и ждём
# выхода в RUNNING; OFFSET снимаем ПОСЛЕ, чтобы стартовый шум не попал в лог
# эксперимента.
cmd "play" >/dev/null
for _ in $(seq 1 20); do
    cmd "state" | grep -q "state=RUNNING" && break
    sleep 1
done
if ! cmd "state" | grep -q "state=RUNNING"; then
    echo "НЕ УДАЛОСЬ ЗАПУСТИТЬ ВОСПРОИЗВЕДЕНИЕ: фазы A–C пройдут впустую." >&2
    echo "Открой приложение один раз (или пошли \`ui\`) и повтори." >&2
    exit 1
fi

echo "=== состояние до эксперимента (PID=$(adb shell pidof "$PKG" | tr -d '\r')) ==="
cmd "status" | head -3
echo

OFFSET=$(logsize)
echo "лог: $(basename "$LOG"), стартовая позиция $OFFSET байт"
echo

step() {
    local label="$1" command="$2" pause="$3"
    echo "--- $(date +%H:%M:%S.%3N) | $label | adb: $command ---"
    cmd "$command" | head -2
    sleep "$pause"
}

run_phase_a() {
    echo "############ ФАЗА A: одиночные смены, интервал 9 с (чисто) ############"
    step "A1" "preset 1" 9
    step "A2" "preset 2" 9
    step "A3" "preset 3" 9
}

run_phase_b() {
    echo "############ ФАЗА B: смены с интервалом 1 с ############"
    step "B1" "preset 1" 1
    step "B2" "preset 2" 1
    step "B3" "preset 3" 12
}

run_phase_c() {
    echo "############ ФАЗА C: серия 6 смен через 400 мс (стресс) ############"
    step "C" "switch 6 400" 16
}

run_phase_d() {
    echo "############ ФАЗА D: полная остановка посреди кроссфейда ############"
    step "D0" "preset 1" 4
    step "D1" "preset 2" 0.12        # через 120 мс — в середине фейд-аута
    step "D2" "stop" 6
    step "D3" "play" 5
}

run_phase_e() {
    echo "############ ФАЗА E: мягкая пауза посреди кроссфейда + возобновление ############"
    step "E0" "preset 2" 4
    step "E1" "preset 3" 0.12        # в середине фейд-аута
    step "E2" "pause" 5
    step "E3" "state" 1
    step "E4" "resume" 5
    step "E5" "state" 1
}

run_phase_f() {
    echo "############ ФАЗА F: смена пресета на мягкой паузе, потом возобновление ############"
    step "F0" "pause" 4
    step "F1" "state" 1
    step "F2" "preset 1" 3
    step "F3" "state" 1
    step "F4" "resume" 6
    step "F5" "state" 1
}

run_phase_g() {
    echo "############ ФАЗА G: смена частоты дискретизации (кроссфейд с другим SR) ############"
    step "G0" "samplerate 48000" 6
    step "G1" "samplerate 44100" 6
    step "G2" "samplerate 22050" 6
    step "G3" "samplerate 44100" 6
}

run_phase_h() {
    echo "############ ФАЗА H: громкость и нормализация во время кроссфейда ############"
    step "H0" "preset 2" 4
    step "H1" "volume 0.9" 0.15
    step "H2" "preset 3" 0.10
    step "H3" "volume 0.3" 6
    step "H4" "norm off" 5
    step "H5" "norm on" 5
    step "H6" "volume 0.45" 3
}

run_phase_i() {
    echo "############ ФАЗА I: смена пресета сразу после старта (фейд-ин не кончился) ############"
    step "I0" "stop" 5
    step "I1" "play" 0.08            # через 80 мс — идёт fade-in
    step "I2" "preset 2" 8
}

run_phase_j() {
    echo "############ ФАЗА J: двойная смена подряд (0 мс), затем серия ############"
    step "J0" "preset 1" 5
    step "J1" "preset 2" 0
    step "J2" "preset 3" 10
}

# ФАЗА K — инвариант «загружен не более одного потока» на пути
# пауза -> смена пресета -> возобновление. Именно здесь замороженный поток
# ещё держит пакет, а автомат уже хочет создать новый; до правки launchSpec
# логировал тут «загруженныхБуферов=1».
# Проверка после прогона: ни одного launchSpec с загруженныхБуферов!=0.
run_phase_k() {
    echo "############ ФАЗА K: шторм пауза -> смена пресета -> resume ############"
    step "K0" "preset 1" 4
    echo "--- K1..K4: пауза успевает дойти до PAUSED, затем смена и resume ---"
    for i in 1 2 3 4; do
        step "K$i.a pause"  "pause" 0.5
        step "K$i.b preset" "preset $(( (i % 3) + 1 ))" 0.15
        step "K$i.c resume" "resume" 0.8
    done
    echo "--- K5..K8: resume прилетает ПОСРЕДИ фейда паузы (FADE_OUT_PAUSE) ---"
    for i in 5 6 7 8; do
        step "K$i.a pause"  "pause" 0.12
        step "K$i.b preset" "preset $(( (i % 3) + 1 ))" 0
        step "K$i.c resume" "resume" 1.2
    done
    step "K9"  "state" 1
}

PHASES=${PHASES:-${1:-"a b c d e f g h i j k"}}

for p in $PHASES; do
    case "$p" in
        a|A) run_phase_a ;;
        b|B) run_phase_b ;;
        c|C) run_phase_c ;;
        d|D) run_phase_d ;;
        e|E) run_phase_e ;;
        f|F) run_phase_f ;;
        g|G) run_phase_g ;;
        h|H) run_phase_h ;;
        i|I) run_phase_i ;;
        j|J) run_phase_j ;;
        k|K) run_phase_k ;;
        *) echo "нет такой фазы: $p" ;;
    esac
    echo
done

echo "############ ЛОГ ЭКСПЕРИМЕНТА ############"
adb shell "tail -c +$((OFFSET + 1)) '$LOG'" 2>&1 |
    grep -E "$MARKERS"
