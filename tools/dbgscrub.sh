#!/usr/bin/env bash
#
# Проверка скраба предпросмотра — ручки ◀|▶ на указателе текущего момента
# (docs/plan_playback_scrub_handle.md, §12 «Верификация»).
#
# ЧТО ПРОВЕРЯЕТСЯ. Скраб — это не «позиция в треке», а сдвиг ВСЕЙ оси времени
# суток: ось(t) = normalize(t + Δ). Главный инвариант приложения остаётся
# прежним («звук == текущий момент суток»), но «текущий момент» для слушателя
# теперь сдвинут. Поэтому проверка одна на все сценарии и состоит из ДВУХ
# независимых частей:
#
#     1) audible ≈ now + scrub     (|Δ| < TOL по кругу суток)
#     2) сам сдвиг ≈ ожидаемому    (|Δ| < SCRUB_TOL)
#
# Первое без второго проходит и при скрабе «не туда» (оси согласованы, но
# слушаем не то время); второе без первого — это просто значение в StateFlow,
# которое не доехало до звука. Обе проверки нужны всегда.
#
# Сценарии:
#   V1 базовый сдвиг на +6 ч: ось сошлась, настенные часы не поехали;
#   V2 сдвиг переживает хэндофф настроек (тот же путь, что правка точки кривой);
#   V3 pscrubreset возвращает ось на реальное «сейчас»;
#   V4 полный стоп → play стирает сдвиг;
#   V5 скраб через полночь (цель 23:58 при now 00:03 — обёртка, Δ = −5 мин);
#   V6 два скраба подряд внутри одного кроссфейда: итог = вторая цель;
#   V7 сторож инварианта не срабатывает на легальный предпросмотр;
#   V8 скраб → пауза → возобновление: ось выжила, решатель отработал;
#   V9 смена пресета стирает сдвиг.
#
# Использование (Windows / Git Bash):
#   bash tools/dbgscrub.sh                 # все сценарии
#   bash tools/dbgscrub.sh v1 v5           # выборочно
#   DEVICE=192.168.61.212:5555 TOL=8 bash tools/dbgscrub.sh v7
#
# Требуется установленная debug-сборка com.binauralcycles.debug.
#
# ПОБОЧНЫЙ ЭФФЕКТ: V2 на время меняет интервал перестановки каналов
# (`swapinterval 601`) и возвращает 300 — это единственная доступная из CLI
# правка конфига, а именно она поднимает SETTINGS-хэндофф.
#
# ГРАБЛИ, УЧТЁННЫЕ ЗДЕСЬ (см. память проекта):
#   * `adb connect` — в КАЖДОМ вызове: демон в этой песочнице не держит
#     подключение между процессами bash, иначе «no devices/emulators found»;
#   * лог снимается СИНХРОННЫМ дампом (`logcat -b all -d`), а не фоновым
#     `logcat > file &`: фоновый adb-клиент в Git Bash не убивается через
#     kill и писал бы в файл следующего сценария, давая ложные PASS;
#   * дамп делается ДО утверждений, а не после: иначе все expect_log/reject_log
#     читали бы пустой или прошлый файл;
#   * несколько команд — ОДНИМ `adb shell` (отдельный вызов ~100-150 мс, что
#     больше окна коалесценции кроссфейда). Многословная команда внутри
#     пакета пишется в ОДИНАРНЫХ кавычках: их снимает device-shell, и `am`
#     получает «pscrub 12345» одним argv-элементом. Без кавычек `--es cmd
#     pscrub 12345` разваливается на два аргумента;
#   * поле `now` снимка нельзя выдирать regexp'ом напрямую: `window=`
#     содержит `now=` как подстроку. Снимок разбирается по пробелам;
#   * величину сдвига нельзя проверять точным regexp'ом: сдвиг считается на
#     нити актёра, поэтому он отличается от «цель − now_в_момент_чтения»
#     на время доставки команды и почти всегда на 1-2 с МЕНЬШЕ круглого
#     числа. Все сравнения числовые, по кругу суток.

set -u

ADB="${ADB:-C:/Users/Nikita/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEVICE="${DEVICE:-192.168.61.212:5555}"
PKG="com.binauralcycles.debug"
ACTION="com.binauralcycles.debug.COMMAND"
OUTDIR="${OUTDIR:-/tmp/dbgscrub}"

# Допуск «слышимое == now + scrub», сек. Тот же, что в dbgverify_resume.sh:
# зазор здесь только от квантования кадра и времени на предзаполнение кольца.
TOL="${TOL:-5}"
# Допуск на ВЕЛИЧИНУ сдвига, сек: сдвиг считается в момент применения, а
# ожидание — от now, прочитанного на ~0.2-1 с раньше.
SCRUB_TOL="${SCRUB_TOL:-8}"
# Таймаут ожидания сходимости оси, сек (кроссфейд ~1 с + генерация пакета).
WAIT="${WAIT:-12}"

PASSED=0
FAILED=0
FAILED_LIST=""
SCEN="common"
LOG="$OUTDIR/common.log"
# Ожидаемый сдвиг текущего сценария — его использует тест t_axis.
WANT_SCRUB=0

adb() { "$ADB" "$@"; }

# ---------------------------------------------------------------- Математика
circ_add() { # (a + b) mod 86400
    awk -v a="$1" -v b="$2" 'BEGIN{v=(a+b)%86400; if(v<0)v+=86400; printf "%.2f", v}'
}
norm_delta() { # круговая |a − b| (0..43200)
    awk -v a="$1" -v b="$2" 'BEGIN{d=a-b; d=d-86400*int(d/86400); if(d<0)d+=86400; if(d>43200)d=86400-d; printf "%.2f", d}'
}
lt() { awk -v x="$1" -v y="$2" 'BEGIN{exit !(x+0 < y+0)}'; }
hhmm() {
    awk -v s="$1" 'BEGIN{v=int(s)%86400; if(v<0)v+=86400; printf "%02d:%02d", int(v/3600), int((v%3600)/60)}'
}

# ---------------------------------------------------------------- Условия
equals()   { [ "$1" = "$2" ]; }
contains() { printf '%s' "$1" | grep -aqE "$2"; }

# ---------------------------------------------------------------- Команды
# Отправка команды и вытаскивание resultData (ответ дублируется в logcat, но
# разбирать проще именно resultData: `am broadcast` печатает его одной строкой
# после `tr '\n' ' '`).
run() {
    adb shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages \
        --es cmd "'$1'" 2>&1 \
        | tr '\n' ' ' \
        | grep -o 'data="[^"]*"' \
        | head -1 \
        | sed 's/data="//; s/"$//'
}

# Несколько команд в ОДНОМ shell-вызове. Многословные команды — в одинарных
# кавычках (их снимает device-shell, см. шапку). "$*" позволяет передавать
# пакет частями: burst "cmd1; sleep 0.15; " "cmd2".
burst() { adb shell "$*" >/dev/null 2>&1; }

# ---------------------------------------------------------------- Снимок
snap() { run "vsnap"; }
# Разбор по пробелам, а не regexp'ом: `window=` содержит `now=` как подстроку.
field() { printf '%s' "$1" | tr ' ' '\n' | grep -E "^$2=" | head -1 | sed "s/^$2=//"; }
s_state()   { field "$1" state; }
s_playing() { field "$1" playing; }
s_now()     { field "$1" now; }
s_aud()     { field "$1" audible; }
s_raw()     { field "$1" audibleraw; }
s_scrub()   { field "$1" scrub; }

LAST_SNAP=""

t_running() { [ "$(s_state "$1")" = "RUNNING" ] && [ "$(s_playing "$1")" = "1" ]; }
t_paused()  { [ "$(s_state "$1")" = "PAUSED" ]; }
# Ось сошлась: и сдвиг равен ожидаемому, и слышимое == now + scrub.
t_axis() {
    lt "$(norm_delta "$(s_scrub "$1")" "$WANT_SCRUB")" "$SCRUB_TOL" &&
    lt "$(norm_delta "$(s_aud "$1")" "$(circ_add "$(s_now "$1")" "$(s_scrub "$1")")")" "$TOL"
}

wait_until() { # $1=таймаут, сек  $2=тест-функция(снимок)
    local deadline=$(( $(date +%s) + $1 )) s
    while [ "$(date +%s)" -lt "$deadline" ]; do
        s=$(snap)
        if [ -n "$s" ] && eval "$2 \"\$s\""; then LAST_SNAP="$s"; return 0; fi
        sleep 0.4
    done
    LAST_SNAP=$(snap)
    return 1
}

# ---------------------------------------------------------------- Утверждения
check() { # $1=метка, дальше — команда-условие (exit 0 == PASS)
    local label="$1"; shift
    if "$@" >/dev/null 2>&1; then
        printf '  PASS  %s\n' "$label"; PASSED=$((PASSED + 1)); return 0
    else
        printf '  FAIL  %s\n' "$label"; FAILED=$((FAILED + 1))
        FAILED_LIST="$FAILED_LIST\n  - $SCEN: $label"; return 1
    fi
}

expect_log() { # строка ДОЛЖНА присутствовать в логе сценария
    if grep -aqE "$2" "$LOG"; then
        printf '  PASS  %s\n' "$1"; PASSED=$((PASSED + 1))
    else
        printf '  FAIL  %s  (нет строки /%s/)\n' "$1" "$2"; FAILED=$((FAILED + 1))
        FAILED_LIST="$FAILED_LIST\n  - $SCEN: $1"
    fi
}

reject_log() { # строки НЕ должно быть
    if grep -aqE "$2" "$LOG"; then
        printf '  FAIL  %s\n     %s\n' "$1" "$(grep -aE "$2" "$LOG" | head -1)"
        FAILED=$((FAILED + 1)); FAILED_LIST="$FAILED_LIST\n  - $SCEN: $1"
    else
        printf '  PASS  %s\n' "$1"; PASSED=$((PASSED + 1))
    fi
}

# ПОРЯДОК ПЕРЕХОДА (§4.4 документа): «гаснет → ТИШИНА → SWAP по нулю».
# С четвёртой волны кроссфейд удалён вообще, поэтому «перехода с перекрытием
# не было» проверять нечем — его просто не существует. Остаётся проверить
# главное: NEXT поднимается ТОЛЬКО после того, как огибающая CURRENT
# подтверждённо дошла до нуля (маркер `ТИШИНА` в хуке `stopWithSilentHook`).
# Провал означает смешение фейд-ина с фейд-аутом — ровно то, от чего
# отказались, — или потерянный хук (переход не закрыт стартом).
expect_transition_order() {
    local label="$1" ord g s w early earlystart
    ord=$(awk '
        /beginSilentSwitch: spec#[0-9]+ гаснет/  {state=1; g++; next}
        /stop spec#[0-9]+: ТИШИНА/              {if (state==1) {state=2; s++} next}
        /startPendingSilentSwitch: SWAP по нулю/ {
            if (state==2) {state=0; w++} else if (state==1) {early++; state=0} next}
        /start spec#[0-9]+: RC1 underrunDelta=/ {if (state==1) earlystart++}
        END {printf "%d %d %d %d %d", g+0, s+0, w+0, early+0, earlystart+0}
    ' "$LOG")
    set -- $ord
    g="${1:-0}"; s="${2:-0}"; w="${3:-0}"; early="${4:-0}"; earlystart="${5:-0}"
    if [ "$g" -gt 0 ] && [ "$s" -eq "$g" ] && [ "$w" -eq "$g" ] \
       && [ "$early" -eq 0 ] && [ "$earlystart" -eq 0 ]; then
        printf '  PASS  %s (уходов=%s, ТИШИНА=%s, SWAP по нулю=%s)\n' \
            "$label" "$g" "$s" "$w"; PASSED=$((PASSED + 1))
    else
        printf '  FAIL  %s (уходов=%s, ТИШИНА=%s, SWAP=%s, стартов до нуля=%s, фейд-ин внутри фейд-аута=%s)\n' \
            "$label" "$g" "$s" "$w" "$early" "$earlystart"
        FAILED=$((FAILED + 1)); FAILED_LIST="$FAILED_LIST\n  - $SCEN: $label"
    fi
}

# ---------------------------------------------------------------- Лог
start_log() {
    mkdir -p "$OUTDIR"
    SCEN="$1"; LOG="$OUTDIR/$SCEN.log"
    adb logcat -G 16M >/dev/null 2>&1   # запас: иначе буфер обернётся
    adb logcat -b all -c >/dev/null 2>&1
    sleep 1
}
# Сбросить буфер ВНУТРИ сценария: после этого в логе остаётся только то, что
# нужно именно этой проверке (иначе «сдвиг в prepare()» нашёлся бы в строке
# предыдущего, уже проверенного хэндоффа).
mark_log() { adb logcat -b all -c >/dev/null 2>&1; sleep 0.5; }
stop_log() { sleep 1; adb logcat -b all -d -v time > "$LOG" 2>&1; }

# ---------------------------------------------------------------- Служебное
banner() { printf '\n############ %s ############\n' "$*"; }

dump_axis() {
    printf '     now=%s  audible=%s  scrub=%s (ждали %s)  state=%s\n' \
        "$(hhmm "$(s_now "$LAST_SNAP")")" \
        "$(hhmm "$(s_aud "$LAST_SNAP")")" \
        "$(s_scrub "$LAST_SNAP")" \
        "$WANT_SCRUB" \
        "$(s_state "$LAST_SNAP")"
}

# Сброс в известное состояние: стоп → play. Заодно стирает сдвиг
# (onStop → clearScrubState).
restart_playback() {
    run "stop" >/dev/null 2>&1; sleep 2
    run "play" >/dev/null 2>&1
    wait_until "$WAIT" t_running >/dev/null 2>&1
    sleep 1
}

# Скраб на <now + смещение> и ожидание сходимости оси.
scrub_by() { # $1=смещение, сек
    local now target
    now=$(s_now "$(snap)")
    [ -n "$now" ] || { printf '  FAIL  не удалось прочитать now\n'; return 1; }
    target=$(circ_add "$now" "$1" | awk '{printf "%d", $1}')
    WANT_SCRUB="$1"
    run "pscrub $target" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
}

# Снять сдвиг и дождаться возврата оси на реальное «сейчас».
scrub_off() {
    WANT_SCRUB=0
    run "pscrubreset" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
}

# Сдвиг из человекочитаемого `clock`: «scrub=21599с — ось звука сдвинута…».
clk_scrub() { printf '%s' "$1" | grep -oE 'scrub=[0-9]+с' | head -1 | sed 's/scrub=//; s/с$//'; }
# Пара «сдвиг спеки / сдвиг менеджера» из `invcheck`: «scrub=7200/7200».
inv_pair()  { printf '%s' "$1" | grep -oE 'scrub=[0-9]+/[0-9]+' | head -1 | sed 's/scrub=//'; }
inv_spec()  { printf '%s' "$(inv_pair "$1")" | sed 's#/.*##'; }
inv_mgr()   { printf '%s' "$(inv_pair "$1")" | sed 's#^.*/##'; }

# ---------------------------------------------------------------- V1
v1() {
    banner "V1: базовый сдвиг на +6 ч"
    start_log v1
    restart_playback
    local n0 n1 clk
    n0=$(s_now "$(snap)")

    scrub_by 21600
    dump_axis
    n1=$(s_now "$LAST_SNAP")
    clk=$(run "clock")

    stop_log
    check "ось сошлась: audible == now + scrub" t_axis "$LAST_SNAP"
    check "сдвиг именно +6 ч" lt "$(norm_delta "$(s_scrub "$LAST_SNAP")" 21600)" "$SCRUB_TOL"
    check "настенные часы не поехали" lt "$(norm_delta "$n1" "$n0")" 60
    # Имена команд — в «гусиных лапках»: обратные кавычки внутри двойных
    # кавычек bash превращает в подстановку команды, и метка ломается.
    check "«clock» сообщает о сдвинутой оси" contains "$clk" "ось звука сдвинута"
    check "«clock»: величина сдвига ≈ +6 ч" \
        lt "$(norm_delta "$(clk_scrub "$clk")" 21600)" "$SCRUB_TOL"
    expect_log "менеджер принял скраб" "scrubTo [0-9:]+ \(сдвиг=21[0-9]+ с"
    expect_log "новый поток построен со сдвигом" "сдвиг скраба=[1-9][0-9]*"
    # Скраб КОГЕРЕНТЕН (та же кривая, Δf десятые герца) ⇒ кроссфейда быть не
    # должно: переход идёт «приседанием» через ноль (§4.1, §4.2 документа).
    # Раньше здесь ждали `beginCrossfade: SWAP` — это умерло вместе со
    # схемой «скраб кроссфейдится».
    expect_log "вердикт когерентности напечатан" "когерентность: SCRUB Δf=[0-9.]+Гц"
    expect_log "переход без перекрытия начат" "beginSilentSwitch: spec#[0-9]+ гаснет"
    expect_transition_order "скраб не смешивается: гаснет → ТИШИНА → SWAP по нулю"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"

    scrub_off >/dev/null 2>&1
}

# ---------------------------------------------------------------- V2
v2() {
    banner "V2: сдвиг переживает хэндофф настроек"
    start_log v2
    restart_playback
    scrub_by 10800
    dump_axis
    check "ось сошлась до правки" t_axis "$LAST_SNAP"

    # Правка точки кривой из CLI недоступна (такой команды нет), но путь ровно
    # тот же: `updateConfig()` → `onSpecChanged(SETTINGS)` → хэндофф с новой
    # спекой. Интервал перестановки чередуем, чтобы значение гарантированно
    # отличалось от текущего (повтор того же значения менеджер отсекает).
    mark_log
    run "swapinterval 601" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
    dump_axis

    stop_log
    check "ось осталась сдвинутой после хэндоффа" t_axis "$LAST_SNAP"
    check "сдвиг не обнулился (+3 ч)" \
        lt "$(norm_delta "$(s_scrub "$LAST_SNAP")" 10800)" "$SCRUB_TOL"
    # Правка настройки НЕ меняет кривую ⇒ Δf = 0 ⇒ когерентно ⇒ тоже нулевое
    # перекрытие (§4.2 документа). Это даже важнее скраба: при нулевой
    # расстройке разность фаз не дрейфует, и провал держался бы ВСЁ
    # перекрытие, а не его середину.
    expect_log "вердикт когерентности напечатан" "когерентность: SETTINGS Δf=[0-9.]+Гц"
    expect_log "переход без перекрытия начат" "beginSilentSwitch: spec#[0-9]+ гаснет"
    expect_transition_order "правка настройки не смешивается: гаснет → ТИШИНА → SWAP по нулю"
    expect_log "пересборка унаследовала сдвиг" "сдвиг скраба=[1-9][0-9]*"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"

    run "swapinterval 300" >/dev/null 2>&1
    scrub_off >/dev/null 2>&1
}

# ---------------------------------------------------------------- V3
v3() {
    banner "V3: pscrubreset возвращает ось на реальное «сейчас»"
    start_log v3
    restart_playback
    scrub_by 7200
    check "ось сошлась на +2 ч" t_axis "$LAST_SNAP"

    mark_log
    scrub_off
    dump_axis
    local inv
    inv=$(run "invcheck")
    printf '     invcheck: %s\n' "$inv"

    stop_log
    check "ось вернулась: audible == now" t_axis "$LAST_SNAP"
    check "сдвиг обнулён" equals "$(s_scrub "$LAST_SNAP")" 0
    check "invcheck: нарушения нет" contains "$inv" "нарушение=нет"
    # Точная формулировка лога: «scrubReset (сдвиг=7198 с…)». Слово «прежний»
    # в сообщении никогда не было — проверка ждала его с первого дня и потому
    # честно падала на прогонах, где поведение было верным.
    expect_log "менеджер снял сдвиг" "scrubReset \(сдвиг=[1-9][0-9]* с"
    expect_log "пересборка пошла без сдвига" "сдвиг скраба=0"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"
}

# ---------------------------------------------------------------- V4
v4() {
    banner "V4: полный стоп → play стирает сдвиг"
    start_log v4
    restart_playback
    scrub_by 21600
    check "ось сошлась на +6 ч" t_axis "$LAST_SNAP"

    mark_log
    run "stop" >/dev/null 2>&1; sleep 2
    WANT_SCRUB=0
    run "play" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
    dump_axis

    stop_log
    check "старт с реального now" t_axis "$LAST_SNAP"
    check "сдвиг обнулён" equals "$(s_scrub "$LAST_SNAP")" 0
    expect_log "новый поток построен без сдвига" "сдвиг скраба=0"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"
}

# ---------------------------------------------------------------- V5
v5() {
    banner "V5: скраб через полночь (цель 23:58 при now 00:03)"
    start_log v5
    restart_playback

    # Часы и скраб уходят ОДНИМ пакетом. Между ними нельзя вставлять
    # отдельный adb-вызов: настенные часы прыгнут почти на сутки, и если
    # скраб не догонит их за WATCHDOG_SUSTAIN_MS (3 с), сторож запишет
    # «INVARIANT НАРУШЕН» — уже не про скраб, а про разрыв, который мы сами
    # создали. (Дополнительно страхует grace сторожа: он стартует вместе с
    # запуском NEXT и равен тем же 3 с.)
    burst "am broadcast -a $ACTION -p $PKG --es cmd 'totime 00:03'; sleep 0.15; " \
          "am broadcast -a $ACTION -p $PKG --es cmd 'pscrub 23:58'"
    # Цель 23:58 = 86280 с при now 00:03 = 180 с ⇒ сдвиг −5 мин = 86100 с.
    WANT_SCRUB=86100
    wait_until "$WAIT" t_axis
    dump_axis

    stop_log
    check "ось обернулась на 23:58" t_axis "$LAST_SNAP"
    check "сдвиг = −5 мин (86100 с)" \
        lt "$(norm_delta "$(s_scrub "$LAST_SNAP")" 86100)" "$SCRUB_TOL"
    expect_log "сдвиг посчитан через полночь" "scrubTo 23:58 \(сдвиг=86[01][0-9]+ с"
    expect_log "новый поток построен со сдвигом" "сдвиг скраба=[1-9][0-9]*"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"

    # ПОРЯДОК ОБЯЗАТЕЛЕН: сначала часы, потом снятие сдвига, и лучше одним
    # пакетом. `clockreset` двигает только настенные часы — ось живого потока
    # остаётся там, где её оставил `totime`, то есть на 23 часа в стороне.
    # Сама по себе эта «вилка» легальна (ровно её и ловит сторож), но если
    # снять сдвиг ДО возврата часов, ось вообще не переякорится: `pscrubreset`
    # с already-нулевым сдвигом — это та же спека, быстрый путь `audioEquals`
    # её отсечёт, хэндоффа не будет, и расхождение в сутки уедет в СЛЕДУЮЩИЙ
    # сценарий, где сторож честно заорет «INVARIANT НАРУШЕН» — на чужой ошибке.
    # Сначала часы (ось уезжает на сутки), тут же `pscrubreset` (хэндофф,
    # переякоривание на restored now). Разрыв живёт ~1 с и накрывается грейсом
    # сторожа, который стартует вместе с запуском NEXT.
    burst "am broadcast -a $ACTION -p $PKG --es cmd 'clockreset'; sleep 0.15; " \
          "am broadcast -a $ACTION -p $PKG --es cmd 'pscrubreset'"
    WANT_SCRUB=0
    wait_until "$WAIT" t_axis
    dump_axis
    check "после сценария ось вернулась на реальное now" t_axis "$LAST_SNAP"
}

# ---------------------------------------------------------------- V6
v6() {
    banner "V6: два скраба подряд внутри одного кроссфейда"
    start_log v6
    restart_playback
    local now a b n
    now=$(s_now "$(snap)")
    a=$(circ_add "$now" 3600  | awk '{printf "%d", $1}')
    b=$(circ_add "$now" 14400 | awk '{printf "%d", $1}')

    # Обе команды — в одном shell-вызове с паузой 0.15 с: второй скраб обязан
    # прилететь, пока уходящий поток ещё в слоте `outgoing`.
    burst "am broadcast -a $ACTION -p $PKG --es cmd 'pscrub $a'; sleep 0.15; " \
          "am broadcast -a $ACTION -p $PKG --es cmd 'pscrub $b'"
    WANT_SCRUB=14400
    wait_until "$WAIT" t_axis
    dump_axis

    stop_log
    n=$(grep -ac "scrubTo " "$LOG")
    printf '  INFO  scrubTo в логе: %s (ожидается 2)\n' "$n"
    check "итог = вторая цель (+4 ч)" t_axis "$LAST_SNAP"
    check "ось не уехала на сумму сдвигов" \
        lt "$(norm_delta "$(s_scrub "$LAST_SNAP")" 14400)" "$SCRUB_TOL"
    check "оба скраба дошли до менеджера" equals "$n" 2
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"

    scrub_off >/dev/null 2>&1
}

# ---------------------------------------------------------------- V7
v7() {
    banner "V7: сторож инварианта при активном скрабе"
    start_log v7
    restart_playback
    scrub_by 18000
    check "ось сошлась на +5 ч" t_axis "$LAST_SNAP"

    mark_log
    # Сторож тикает каждые 500 мс и пишет ERROR, только если расхождение
    # держится дольше WATCHDOG_SUSTAIN_MS (3 с). Пять секунд — с запасом:
    # легальный предпросмотр обязан продержаться сколько угодно.
    sleep 5
    local inv snap2
    inv=$(run "invcheck")
    printf '     invcheck: %s\n' "$inv"
    snap2=$(snap)

    stop_log
    check "invcheck: нарушения нет" contains "$inv" "нарушение=нет"
    check "invcheck: сдвиг виден в спеке потока" \
        lt "$(norm_delta "$(inv_spec "$inv")" 18000)" "$SCRUB_TOL"
    check "invcheck: сдвиг виден в менеджере" \
        lt "$(norm_delta "$(inv_mgr "$inv")" 18000)" "$SCRUB_TOL"
    check "ось держится все 5 с" t_axis "$snap2"
    reject_log "сторож молчит на легальном предпросмотре" "INVARIANT НАРУШЕН"

    scrub_off >/dev/null 2>&1
}

# ---------------------------------------------------------------- V8
v8() {
    banner "V8: скраб → пауза → возобновление"
    start_log v8
    restart_playback
    scrub_by 7200
    check "ось сошлась на +2 ч" t_axis "$LAST_SNAP"

    run "pause" >/dev/null 2>&1
    wait_until "$WAIT" t_paused >/dev/null 2>&1
    sleep 1
    mark_log
    run "resume" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
    dump_axis
    local res
    res=$(run "resumesnap" | grep -o 'resolution=[A-Za-z_]*' | head -1 | sed 's/resolution=//')
    printf '     resumesnap: resolution=%s\n' "${res:-?}"

    stop_log
    check "ось выжила паузу: audible == now + scrub" t_axis "$LAST_SNAP"
    check "сдвиг сохранился (+2 ч)" \
        lt "$(norm_delta "$(s_scrub "$LAST_SNAP")" 7200)" "$SCRUB_TOL"
    expect_log "возобновление состоялось" \
        "resumeFromPaused: spec#[0-9]+|resumePausedStream: spec#[0-9]+"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"
    case "$res" in
        SOFT|REBUILD_*) check "путь возобновления известен (${res})" true ;;
        *)              check "путь возобновления известен (получен «${res:-?}»)" false ;;
    esac

    scrub_off >/dev/null 2>&1
}

# ---------------------------------------------------------------- V9
v9() {
    banner "V9: смена пресета стирает сдвиг"
    start_log v9
    restart_playback
    # Нужен хотя бы один ДРУГОЙ пресет: `resetScrub()` лишь снимает состояние,
    # а на реальную ось звук вернёт только хэндофф. С одним пресетом `next`
    # применил бы тот же конфиг, менеджер отсёк бы его как повтор — и ось
    # осталась бы сдвинутой при нулевом сдвиге в UI.
    local presets count
    presets=$(run "presets")
    count=$(printf '%s' "$presets" | grep -oE '\*?[0-9]+\. ' | wc -l | tr -d ' ')
    if [ "${count:-0}" -lt 2 ]; then
        printf '  SKIP  нужно минимум два пресета, найдено %s\n' "${count:-0}"
        stop_log
        return 0
    fi
    scrub_by 21600
    check "ось сошлась на +6 ч" t_axis "$LAST_SNAP"

    mark_log
    WANT_SCRUB=0
    run "next" >/dev/null 2>&1
    wait_until "$WAIT" t_axis
    dump_axis

    stop_log
    check "ось вернулась на реальное now" t_axis "$LAST_SNAP"
    check "сдвиг обнулён" equals "$(s_scrub "$LAST_SNAP")" 0
    expect_log "новый пресет построен без сдвига" "сдвиг скраба=0"
    reject_log "сторож не сработал" "INVARIANT НАРУШЕН"
}

# ---------------------------------------------------------------- main
adb connect "$DEVICE" >/dev/null 2>&1
adb shell am start -n "$PKG/com.binauralcycles.MainActivity" >/dev/null 2>&1
sleep 3
# Виртуальные часы предыдущих прогонов обязаны быть сняты: иначе now снимка и
# база сдвига уедут вместе, и все допуски поедут следом.
run "clockreset" >/dev/null 2>&1

ARGS=("$@")
[ "${#ARGS[@]}" -eq 0 ] && ARGS=(v1 v2 v3 v4 v5 v6 v7 v8 v9)

for a in "${ARGS[@]}"; do
    case "$a" in
        v1) v1 ;; v2) v2 ;; v3) v3 ;; v4) v4 ;; v5) v5 ;;
        v6) v6 ;; v7) v7 ;; v8) v8 ;; v9) v9 ;;
        *) printf 'неизвестный сценарий: %s\n' "$a" ;;
    esac
done

printf '\n=========================================\n'
printf 'ИТОГО: PASS=%s FAIL=%s  (логи: %s)\n' "$PASSED" "$FAILED" "$OUTDIR"
if [ -n "$FAILED_LIST" ]; then
    printf 'Провалы:%b\n' "$FAILED_LIST"
fi
[ "$FAILED" -eq 0 ] || exit 1
exit 0
