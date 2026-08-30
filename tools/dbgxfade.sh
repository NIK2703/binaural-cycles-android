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
set -u

ADB=${ADB:-/home/nikita/tools/android-sdk/platform-tools/adb}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
LOG=/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log

cmd() {
    "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$1'" 2>&1 |
        sed -n '/Broadcast completed/,$p' | sed 's/^Broadcast completed: result=0, data="//; s/"$//'
}
logsize() { "$ADB" shell stat -c '%s' "$LOG" 2>/dev/null | tr -d '\r'; }

# Все маркеры, по которым потом считаются тайминги
MARKERS='beginHandoff|fadeOutCurrent|fade-out\(|fade-in|promoteNextToCurrent|start spec|prepare |releaseInternal|writerLoop exit|onStreamReleased|onStreamFullyStopped|createAudioTrack|VolumeShaper|RC1|growPacketBuffer|handoffBlocked|finalizePause|discardNext|rearmNextIfStale|launchSpec|launchStream|switch #'

echo "=== состояние до эксперимента ==="
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

PHASES=${PHASES:-${1:-"a b c d e f g h i j"}}

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
        *) echo "нет такой фазы: $p" ;;
    esac
    echo
done

echo "############ ЛОГ ЭКСПЕРИМЕНТА ############"
"$ADB" shell "tail -c +$((OFFSET + 1)) '$LOG'" 2>&1 |
    grep -E "$MARKERS"
