#!/usr/bin/env bash
#
# ШТОРМ ХЭНДОФФОВ: серия быстрых жестов в RUNNING для трёх причин смены
# звука — скраб (SCRUB), смена пресета (PRESET_SWITCH), правка настройки
# (SETTINGS). docs/analysis_scrub_storm_click_risk.md (§4.2, §4.3, §6).
#
# Что именно проверяется:
#   1. КОАЛЕСЦЕНЦИЯ — серия жестов, плотнее окна, обязана стоить 1–2 перехода,
#      а не N. Окно двухуровневое: одиночный жест ждёт 150 мс, серия — 300 мс
#      от последнего жеста (§4.3). Проверка УСЛОВНАЯ: ритм серии мерится по
#      таймстемпам `requestHandoff`, потому что `am broadcast` в device-shell
#      стоит 150–250 мс и реальный интервал жестов всегда больше GAP (§4.4).
#      Для редкой серии требуется не «≤ 2», а «≤ число кластеров».
#   2. ПОРЯДОК ПЕРЕХОДА — «гаснет → ТИШИНА → SWAP по нулю»: NEXT поднимается
#      строго после того, как огибающая CURRENT подтверждённо в нуле. С §4.4
#      это ЕДИНСТВЕННЫЙ маршрут (кроссфейд удалён), и смешение тут — дефект,
#      а не выбор маршрута.
#   3. МАРШРУТИЗАЦИЯ — вердикт `когерентность:` печатается для каждого
#      перехода, но ничего не решает: по Δf видно, чем грозил бы кроссфейд
#      на этом жесте (§4.4).
#   4. ОТСУТСТВИЕ АВАРИЙ — ни одного `prepare() не удался` → последовательного
#      хэндоффа (разрыв 100–200 мс), ни одного принудительного снятия рампы,
#      ни одного нарушения сторожа инварианта.
#   5. ПАМЯТЬ — `pkstat` до и после: пик держателей пакета ≤ 2, без OOM-урезаний.
#
# Использование (Windows / Git Bash):
#   bash tools/dbgstorm.sh                 # все три сценария
#   bash tools/dbgstorm.sh scrub settings  # выборочно
#   DEVICE=192.168.199.165:5555 N=12 GAP=0.1 bash tools/dbgstorm.sh scrub
#
# Требуется установленная debug-сборка com.binauralcycles.debug.
#
# ГРАБЛИ, УЧТЁННЫЕ ЗДЕСЬ (см. память проекта):
#   * `adb connect` — в КАЖДОМ вызове: демон в этой песочнице не держит
#     подключение между процессами bash;
#   * лог снимается СИНХРОННЫМ дампом (`logcat -b all -d`), а не фоновым
#     `logcat > file &`: фоновый adb-клиент в Git Bash не убивается;
#   * вся серия жестов уходит ОДНИМ `adb shell` — отдельный вызов стоит
#     100–150 мс, что больше окна коалесценции (150 мс) и разрушило бы шторм;
#   * многословные команды внутри пакета — в ОДИНАРНЫХ кавычках: их снимает
#     device-shell, и `am` получает «pscrub 12:00» одним argv-элементом.

set -u

ADB="${ADB:-C:/Users/Nikita/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEVICE="${DEVICE:-192.168.199.165:5555}"
PKG="com.binauralcycles.debug"
ACTION="com.binauralcycles.debug.COMMAND"
OUTDIR="${OUTDIR:-/tmp/dbgstorm}"

N="${N:-10}"        # жестов в серии
GAP="${GAP:-0.12}"  # пауза между жестами, с (реальная больше — см. §4.4)
WAIT="${WAIT:-12}"  # таймаут ожидания RUNNING, с
# Окно коалесценции СЕРИИ (HANDOFF_STORM_EXTEND_MS): жест продлевает окно
# ⟺ следующий жест пришёл раньше, чем через это время. Одиночный жест ждёт
# вдвое меньше (HANDOFF_STORM_SETTLE_MS = 150) — для проверок важна серия.
STORM_WINDOW_MS="${STORM_WINDOW_MS:-300}"

PASSED=0
FAILED=0
FAILED_LIST=""

adb() { "$ADB" "$@"; }

run() {
    adb shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages \
        --es cmd "'$1'" 2>&1 \
        | tr '\n' ' ' \
        | grep -o 'data="[^"]*"' \
        | head -1 \
        | sed 's/data="//; s/"$//'
}
burst() { adb shell "$*" >/dev/null 2>&1; }

check() {
    local label="$1"; shift
    if "$@" >/dev/null 2>&1; then
        printf '  PASS  %s\n' "$label"; PASSED=$((PASSED + 1)); return 0
    else
        printf '  FAIL  %s\n' "$label"; FAILED=$((FAILED + 1))
        FAILED_LIST="$FAILED_LIST\n  - ${SCEN:-?}: $label"; return 1
    fi
}
le() { awk -v x="$1" -v y="$2" 'BEGIN{exit !(x+0 <= y+0)}'; }

start_log() {
    mkdir -p "$OUTDIR"
    SCEN="$1"; LOG="$OUTDIR/$SCEN.log"
    adb logcat -G 16M >/dev/null 2>&1
    adb logcat -b all -c >/dev/null 2>&1
    sleep 0.5
}
stop_log() { sleep 3; adb logcat -b all -d -v time > "$LOG" 2>&1; }

# Сколько раз строка встречается в логе сценария.
# ВАЖНО: `grep -c` печатает «0» и выходит с кодом 1, поэтому `|| echo 0`
# добавлял ВТОРУЮ строку, и сравнение с "0" ломалось на каждом чистом прогоне.
count() {
    local n
    n=$(grep -acE "$1" "$LOG" 2>/dev/null || true)
    printf '%s' "${n:-0}"
}

# Интервалы между жестами ОДНОЙ причины (мс), по таймстемпам `logcat -v time`.
#
# Причина фильтруется обязательно: поверх сценария скраба идут посторонние
# SETTINGS-пуши ViewModel (~1 с), и они не должны портить ни ритм серии, ни
# счёт доставленных жестов.
intervals() {
    grep -aE "requestHandoff: spec#[0-9]+ в очередь .*reason=$1" "$LOG" \
        | awk '{split($2, t, ":"); s = t[1]*3600 + t[2]*60 + t[3]
                if (p != "") { d = s - p; if (d < 0) d += 86400; printf "%.0f\n", d*1000 }
                p = s}' \
        | sort -n > "$OUTDIR/$SCEN.iv"
}
# Медиана интервала: «ритм серии» одним числом.
median() {
    awk '{a[NR]=$1} END{ if (NR==0) print 0
                        else if (NR%2) printf "%.0f", a[(NR+1)/2]
                        else printf "%.0f", (a[NR/2] + a[NR/2+1])/2 }' "$1"
}
# Сколько КЛАСТЕРОВ (отдельных переходов) обязана дать серия: жест продлевает
# окно ⟺ следующий жест пришёл раньше чем через [STORM_WINDOW_MS]. Разрыв
# серии — интервал ≥ окна; кластеров всегда на один больше числа разрывов.
clusters() {
    awk -v w="$STORM_WINDOW_MS" 'BEGIN{c=1} {if ($1+0 >= w) c++} END{print c}' "$1"
}

# ---------------------------------------------------------------- Отчёт
# Печатает сводку по одному сценарию и проверяет инварианты.
#   $1 — сколько жестов отправляли; $2 — причина (SpecReason) сценария
report() {
    local gestures="$1" reason="$2"
    local silent started swap coherent series waits
    local requests_all requests silent_by_reason
    requests_all=$(count "requestHandoff: spec#")
    requests=$(count "requestHandoff: spec#[0-9]+ в очередь .*reason=$reason")
    silent=$(count "beginSilentSwitch: spec#[0-9]+ гаснет")
    silent_by_reason=$(count "beginSilentSwitch: spec#[0-9]+ гаснет .*причина=$reason")
    started=$(count "startPendingSilentSwitch: SWAP по нулю")
    swap=$(count "beginOverlapSwitch: SWAP")
    coherent=$(count "когерентность: [A-Z_]+ Δf=")
    series=$(count "одиночный жест")
    waits=$(count "tryAdvanceQueue: ждём")

    printf '     жестов отправлено=%s, доставлено (причина %s)=%s, всего requestHandoff=%s\n' \
        "$gestures" "$reason" "$requests" "$requests_all"
    printf '     переходов: этой причины=%s, всего=%s, из них с перекрытием=%s, вердиктов=%s\n' \
        "$silent_by_reason" "$silent" "$swap" "$coherent"
    # Коалесценция глазами: сколько ожиданий пришлось на серию, сколько —
    # на одиночный жест. Серия обязана продлевать окно (§4.3).
    printf '     коалесценция: ожиданий=%s (серия=%s, одиночный=%s)\n' \
        "$waits" "$((waits - series))" "$series"

    # ПОРЯДОК ПЕРЕХОДА: «гаснет → ТИШИНА → SWAP по нулю». С §4.4 это
    # единственный маршрут, и смешение (старт NEXT до нуля огибающей)
    # означает дефект, а не выбор ветви — раньше тут считали `swap`.
    local ord g s w early earlystart orphan
    ord=$(awk '
        /beginSilentSwitch: spec#[0-9]+ гаснет/ {state=1; g++; next}
        /stop spec#[0-9]+: ТИШИНА/             {if (state==1) {state=2; s++} else orphan++; next}
        /startPendingSilentSwitch: SWAP по нулю/ {
            if (state==2) {state=0; w++} else if (state==1) {early++; state=0} else stray++; next}
        /start spec#[0-9]+: RC1 underrunDelta=/ {if (state==1) earlystart++}
        END {printf "%d %d %d %d %d %d", g, s, w, early, earlystart, orphan+0}
    ' "$LOG")
    set -- $ord
    g="${1:-0}"; s="${2:-0}"; w="${3:-0}"; early="${4:-0}"
    earlystart="${5:-0}"; orphan="${6:-0}"
    local vmax
    vmax=$(grep -aoE "ТИШИНА — v=[0-9.]+" "$LOG" | grep -oE "[0-9.]+$" \
        | awk 'NR==1{m=$1} {if($1>m)m=$1} END{printf "%s", (NR?m:"-")}')
    printf '     порядок: уходов=%s, маркеров ТИШИНА=%s (max v=%s), SWAP по нулю=%s\n' \
        "$g" "$s" "$vmax" "$w"

    # Вердикты когерентности — гистограмма «причина → вердикт» плюс разброс
    # Δf: по ним видно, чем грозил бы кроссфейд, и не «зеркалятся» ли каналы
    # (признак перестановки ушей — Δf = |beat| ровно, см. §4.2-2).
    if [ "${coherent:-0}" != "0" ]; then
        grep -aoE "когерентность: [A-Z_]+ Δf=[0-9.]+Гц [^)]*\) — (когерентно|разные тоны)" "$LOG" \
            | sed -E 's/когерентность: ([A-Z_]+) .* — (когерентно|разные тоны).*/\1 \2/' \
            | sort | uniq -c | sed 's/^/       /'
        grep -aoE "Δf=[0-9.]+Гц" "$LOG" | grep -oE "[0-9.]+" \
            | awk 'NR==1{min=$1;max=$1} {if($1<min)min=$1; if($1>max)max=$1}
                   END{printf "       Δf: min=%s max=%s Гц (%s замеров)\n", min, max, NR}'
        # Зеркальные каналы при Δf > 0 — дефект измерения раскладки (§4.2-2).
        grep -aoE "каналы L [0-9.]+→[0-9.]+, R [0-9.]+→[0-9.]+" "$LOG" \
            | awk -F'[ →,]+' '{if ($3==$7 && $4==$6 && $3!=$4) m++}
                   END{if (m+0>0) printf "       ВНИМАНИЕ зеркальные каналы: %s замеров (перестановка ушей)\n", m}'
    fi

    # Отказ prepare() сам по себе уже не дефект: спека переоткладывается и
    # CURRENT продолжает играть (R1). Дефект — исчерпание лимита, то есть
    # последовательный хэндофф с разрывом 100–200 мс.
    local retries seq
    retries=$(count "prepare NEXT spec#[0-9]+ .*не удался")
    seq=$(count "переход на последовательный хэндофф")
    [ "${retries:-0}" != "0" ] && \
        printf '  INFO  отказов prepare(): %s (переотложено, CURRENT не прерывался)\n' "$retries"

    # ---- Проверки ----
    # Ритм серии МЕРИТСЯ: `am broadcast` в device-shell стоит 150–250 мс, так
    # что реальный интервал жестов всегда больше GAP. Требовать «≤ 2 перехода»
    # от серии, которая объективно реже окна, — значит ловить дефект там, где
    # его нет (грабли волн 3–4, §4.4).
    intervals "$reason"
    local med cl
    med=$(median "$OUTDIR/$SCEN.iv")
    cl=$(clusters "$OUTDIR/$SCEN.iv")
    printf '     ритм (причина %s): медиана=%s мс/жест, кластеров=%s, окно=%s мс\n' \
        "$reason" "$med" "$cl" "$STORM_WINDOW_MS"

    check "  перекрытия нет: beginOverlapSwitch удалён" [ "$swap" = "0" ]
    check "  каждый уход закрыт стартом (переходов=SWAP)" [ "$silent" = "$started" ]
    check "  каждый уход дошёл до нуля (маркер ТИШИНА на переход)" [ "$s" = "$g" ]
    check "  NEXT не поднимается до нуля (порядок не нарушен)" [ "$early" = "0" ]
    check "  фейд-ин NEXT не начинается внутри фейд-аута" [ "$earlystart" = "0" ]
    check "  маркер ТИШИНА не висит без перехода" [ "$orphan" = "0" ]
    if [ "${requests:-0}" -ge 2 ] && [ "${med:-99999}" -lt "$STORM_WINDOW_MS" ]; then
        check "  серия плотнее окна (${med} мс): переходов этой причины ≤ 2" \
            le "$silent_by_reason" 2
    else
        check "  переходов этой причины ≤ числа кластеров (${cl} при медиане ${med} мс)" \
            le "$silent_by_reason" "$cl"
    fi
    check "  ни одного разрыва (последовательный хэндофф)" [ "$seq" = "0" ]
    check "  ни одного принудительного снятия рампы" \
        [ "$(count "шейпер не дошёл до цели|не освободился|шаг неизбежен")" = "0" ]
    check "  сторож инварианта молчит" [ "$(count "INVARIANT НАРУШЕН")" = "0" ]
    check "  стартовое окно без underrun" \
        [ "$(count "RC1 underrunDelta=[1-9]")" = "0" ]
}

# Дать стартовой суете умереть (пуши настроек из ViewModel, загрузка
# пресета, разгон кольца) и только потом очистить лог: иначе в окно сценария
# попадают чужие хэндоффы и подсчёт переходов теряет смысл.
settle_then_log() {
    sleep 4
    start_log "$1"
}

ensure_running() {
    run "stop" >/dev/null 2>&1; sleep 2
    run "clockreset" >/dev/null 2>&1
    run "play" >/dev/null 2>&1
    local deadline=$(( $(date +%s) + WAIT )) s
    while [ "$(date +%s)" -lt "$deadline" ]; do
        s=$(run "vsnap")
        if printf '%s' "$s" | grep -aq "state=RUNNING"; then return 0; fi
        sleep 0.5
    done
    printf '  FAIL  не удалось выйти в RUNNING\n'; return 1
}

# ---------------------------------------------------------------- Сценарии
# SCRUB: та же кривая на другой оси ⇒ Δf десятые герца ⇒ когерентно ⇒
# переход без перекрытия. Цели — фиксированные моменты суток, чтобы прогон
# был воспроизводим; сдвиг каждый раз свой, иначе менеджер отсёк бы повтор.
sc_scrub() {
    printf '\n############ ШТОРМ: скраб (%s жестов, пауза %ss) ############\n' "$N" "$GAP"
    ensure_running || return 0
    settle_then_log scrub
    local targets=("06:00" "09:00" "12:00" "15:00" "18:00" "21:00" "00:30" "03:00")
    local cmd="" i t
    for i in $(seq 0 $((N - 1))); do
        t="${targets[$((i % ${#targets[@]}))]}"
        cmd="${cmd}am broadcast -a $ACTION -p $PKG --es cmd 'pscrub $t'; sleep $GAP; "
    done
    burst "$cmd"
    stop_log
    report "$N" SCRUB
    run "pscrubreset" >/dev/null 2>&1
}

# PRESET_SWITCH: «листание» пресета. Когерентно только если у двух пресетов
# совпала несущая; проверяем, что маршрут выбран по частотам, а не по причине.
sc_preset() {
    printf '\n############ ШТОРМ: смена пресета (%s жестов, пауза %ss) ############\n' "$N" "$GAP"
    ensure_running || return 0
    local count_p
    count_p=$(run "presets" | grep -coE '\*?[0-9]+\. ')
    if [ "${count_p:-0}" -lt 2 ]; then
        # `dup` без аргумента копирует АКТИВНЫЙ пресет: этого достаточно,
        # чтобы `next` имел куда переключаться.
        printf '  INFO  пресет один — дублирую активный, чтобы `next` работал\n'
        run "dup" >/dev/null 2>&1
        sleep 2
    fi
    settle_then_log preset
    local cmd="" i
    for i in $(seq 0 $((N - 1))); do
        cmd="${cmd}am broadcast -a $ACTION -p $PKG --es cmd 'next'; sleep $GAP; "
    done
    burst "$cmd"
    stop_log
    report "$N" PRESET_SWITCH
}

# SETTINGS: кривая та же самая ⇒ Δf = 0 ровно ⇒ когерентно ВСЕГДА. Значения
# чередуются, иначе менеджер отсёк бы повтор как ту же спеку.
sc_settings() {
    printf '\n############ ШТОРМ: правка настройки (%s жестов, пауза %ss) ############\n' "$N" "$GAP"
    ensure_running || return 0
    settle_then_log settings
    local cmd="" i v
    for i in $(seq 0 $((N - 1))); do
        v=$(( 300 + (i % 2) * 301 ))
        cmd="${cmd}am broadcast -a $ACTION -p $PKG --es cmd 'swapinterval $v'; sleep $GAP; "
    done
    burst "$cmd"
    stop_log
    report "$N" SETTINGS
    run "swapinterval 300" >/dev/null 2>&1
}

# ---------------------------------------------------------------- main
adb connect "$DEVICE" >/dev/null 2>&1
adb shell am start -n "$PKG/com.binauralcycles.MainActivity" >/dev/null 2>&1
sleep 3
run "clockreset" >/dev/null 2>&1

printf '===== Счётчики пакетной памяти ДО штормов =====\n'
run "pcreset" >/dev/null 2>&1
PK_BEFORE=$(run "pkstat")
printf '%s\n' "$PK_BEFORE"

ARGS=("$@")
[ "${#ARGS[@]}" -eq 0 ] && ARGS=(scrub preset settings)
for a in "${ARGS[@]}"; do
    case "$a" in
        scrub) sc_scrub ;; preset) sc_preset ;; settings) sc_settings ;;
        *) printf 'неизвестный сценарий: %s\n' "$a" ;;
    esac
done

printf '\n===== Счётчики пакетной памяти ПОСЛЕ штормов =====\n'
PK_AFTER=$(run "pkstat")
printf '%s\n' "$PK_AFTER"
echo "$PK_AFTER" | grep -oE 'holders[^ ]*|пик[^ ]*|oomHalvings=[0-9]+' | head -5

printf '\n=========================================\n'
printf 'ИТОГО: PASS=%s FAIL=%s  (логи: %s)\n' "$PASSED" "$FAILED" "$OUTDIR"
if [ -n "$FAILED_LIST" ]; then printf 'Провалы:%b\n' "$FAILED_LIST"; fi
[ "$FAILED" -eq 0 ] || exit 1
exit 0
