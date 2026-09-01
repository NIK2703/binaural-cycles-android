#!/usr/bin/env bash
# Проверка семантики возобновления (docs/analysis_resume_from_0_position.md)
# БЕЗ реального ожидания — на виртуальных настенных часах.
#
# СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ моменту суток.
# Возобновление после паузы играет ритм для «сейчас», а не «продолжает с
# запомненной отметки» (как музыкальный плеер): замороженный пакет
# переиспользуется с пропуском устаревшей головы, пока текущий момент (now)
# внутри сгенерированного окна [audible, frontier]; вышел за frontier —
# пакет пересобирается.
#
# ПОЧЕМУ ВИРТУАЛЬНОЕ ВРЕМЯ, А НЕ sleep
#   Реальным ожиданием эти границы не проверить: чтобы дойти до конца окна
#   нужны десятки секунд, а чтобы проверить переход через полночь — часы.
#   И главное: VirtualClock (`vtime`/`scale`) здесь бесполезен. Его носитель
#   времени — сгенерированные сэмплы (`base + секунды_генерации * scale`),
#   поэтому на паузе генерация стоит и «now» стоит тоже: сколько ни жди,
#   Δ паузы останется нулевой и решатель вообще не будет испытан.
#   Нужен сдвиг НАСТЕННЫХ часов (`warp`): «сейчас» уезжает мгновенно, а
#   пакет, голова трека и фронтир генерации остаются замороженными — ровно
#   как при настоящей паузе. Сдвиг видят обе стороны (Kotlin DebugClock и
#   нативный DebugWallClock.h), их расхождение проверяется командой `clock`.
#
# Пути возобновления:
#   SOFT    (Δ ≤ окна)   — пакет переиспользуется, курсор на АБСОЛЮТНЫЙ кадр
#                          T = A0 + Δ·rate ВНУТРИ пакета + AudioTrack.flush();
#                          audible СРАЗУ == now (переходной задержки нет).
#   REBUILD (Δ > окна)   — пакет устарел, поток пересобирается с якорем на now.
#
# Критерий PASS везде один: слышимое == ТО, ЧТО СЕЙЧАС (|audible − now| < 5 с
# по кругу суток), и это не 0:00, если сейчас не полночь.
#
# Использование:
#   bash tools/dbgverify_resume.sh
#   DEVICE=192.168.61.62:5555 bash tools/dbgverify_resume.sh
#   ADB=/path/to/adb DEVICE=... bash tools/dbgverify_resume.sh

set -u

ADB=${ADB:-adb}
DEVICE=${DEVICE:-}
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
# Допуск «слышимое == сейчас», сек. Кольцо трека сбрасывается flush, поэтому
# зазор здесь только от квантования кадра (1/SR) и времени на предзаполнение.
TOL=${TOL:-5}

ADB_ARGS=()
if [ -n "$DEVICE" ]; then
  "$ADB" connect "$DEVICE" >/dev/null 2>&1
  ADB_ARGS=(-s "$DEVICE")
fi

adb() { "$ADB" "${ADB_ARGS[@]}" "$@"; }

# Отправка команды и вытаскивание resultData. Ответ дублируется в logcat,
# но разбирать проще именно resultData: am broadcast печатает его одной строкой.
run() {
  adb shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages --es cmd "'$1'" 2>&1 \
    | tr '\n' ' ' \
    | grep -o 'data="[^"]*"' \
    | head -1 \
    | sed 's/data="//; s/"$//'
}

# --- Разбор однотрочного снимка `vsnap` ---
# state=X playing=0/1 now=.. audible=.. audibleraw=.. frontier=.. lag=.. window=.. warped=..
snap()      { run "vsnap"; }
field()     { printf '%s' "$1" | grep -o "$2=[-0-9.A-Za-z_]*" | head -1 | sed "s/$2=//"; }
s_state()   { field "$1" state; }
s_playing() { field "$1" playing; }
s_now()     { field "$1" now; }
s_aud()     { field "$1" audible; }
s_raw()     { field "$1" audibleraw; }
s_front()   { field "$1" frontier; }
s_lag()     { field "$1" lag; }
s_win()     { field "$1" window; }

# Круговая нормализованная |a − b| по суткам (0..43200).
# Без поправки «>43200 → 86400−d» разница в −1 с давала бы 86399 с и ложный FAIL.
norm_delta() {
  awk -v a="$1" -v b="$2" 'BEGIN{
    d = a - b; d = d - 86400*int(d/86400);
    if (d < 0) d += 86400;
    if (d > 43200) d = 86400 - d;
    printf "%.2f", d
  }'
}
lt() { awk -v x="$1" -v y="$2" 'BEGIN{exit !(x+0 < y+0)}'; }

# Ждать, пока поле снимка не удовлетворит условию. Реальное ожидание здесь
# неизбежно и коротко: это время на запуск потока, генерацию пакета и
# предзаполнение кольца трека — не время паузы.
wait_until() { # $1=сек-таймаут $2=тест-функция(снимок)->0/1
  local deadline=$(( $(date +%s) + $1 ))
  local s
  while [ "$(date +%s)" -lt "$deadline" ]; do
    s=$(snap)
    if [ -n "$s" ] && eval "$2 \"\$s\""; then
      LAST_SNAP="$s"
      return 0
    fi
    sleep 0.4
  done
  LAST_SNAP=$(snap)
  return 1
}
t_running(){ [ "$(s_state "$1")" = "RUNNING" ] && [ "$(s_playing "$1")" = "1" ]; }
t_paused() { [ "$(s_state "$1")" = "PAUSED" ]; }
# Поле lag снимка — это circularDelta(now, raw), т.е. величина в [0, 86400):
# если слышимое на долю секунды ОБОГНАЛО now, lag ≈ 86399 и прямое сравнение
# дало бы ложную «несходимость». Берём круговое расстояние — ровно ту же
# метрику, по которой выносится вердикт в verify().
t_lag()    { lt "$(norm_delta "$(s_raw "$1")" "$(s_now "$1")")" "$EXPECT_LAG"; }

PASS=1
FAILED_SCENARIOS=""
verify() { # $1=метка $2=допуск; использует LAST_SNAP
  local label="$1"; local tol="${2:-$TOL}"
  local now aud raw lag d
  now=$(s_now "$LAST_SNAP"); aud=$(s_aud "$LAST_SNAP")
  raw=$(s_raw "$LAST_SNAP"); lag=$(s_lag "$LAST_SNAP")
  d=$(norm_delta "$aud" "$now")
  printf '    now=%s  audible=%s  audibleraw=%s  lag=%s  Δ=%s\n' \
    "$now" "$aud" "$raw" "$lag" "$d"
  if ! lt "$d" "$tol"; then
    echo "    FAIL: слышимое ≠ текущему моменту суток (Δ=${d}s > ${tol}s)"
    PASS=0; FAILED_SCENARIOS="$FAILED_SCENARIOS\n  - $label: Δ=${d}s"
    return 1
  fi
  echo "    PASS: звук соответствует текущему моменту суток (Δ=${d}s)"
  return 0
}

# Решатель различает ПРИЧИНЫ пересборки: REBUILD_STALE (пакет устарел),
# REBUILD_DIRTY (настройки менялись на паузе), REBUILD_NO_STREAM /
# REBUILD_NO_FRONTIER (нечего переиспользовать). Скрипту важен сам факт
# пересборки, поэтому ожидание задаётся префиксом: REBUILD покрывает все
# REBUILD_*. Точное совпадение тоже принимается.
matches_resolution() { # $1=факт $2=ожидание (any | точное | префикс)
  [ "$2" = "any" ] && return 0
  [ -z "$1" ] && return 1
  [ "$1" = "$2" ] && return 0
  case "$1" in "$2"*) return 0;; esac
  return 1
}

report_resolution() { # $1=метка $2=ожидаемый путь (SOFT|REBUILD*|any)
  local label="$1"; local expected="$2"
  local res; res=$(run "resumesnap" | grep -o 'resolution=[A-Za-z_]*' | head -1 | sed 's/resolution=//')
  echo "    resolution=${res:-?} (ожидалось ${expected})"
  run "resumesnap" | tr ' ' '\n' | grep -E '^(now|A0|Δ|skipFrames|window)' | sed 's/^/      /'
  if matches_resolution "$res" "$expected"; then
    echo "    PASS: путь возобновления = ${res:-?}"
  else
    echo "    FAIL: ожидался путь ${expected}, получен ${res:-?}"
    PASS=0; FAILED_SCENARIOS="$FAILED_SCENARIOS\n  - $label: путь ${res:-?} вместо ${expected}"
  fi
}

# ===========================================================================
# Сценарий «пауза на виртуальных часах»
#   $1 метка, $2 дельта warp (сек; может быть дробной/отрицательной),
#   $3 ожидаемый путь (SOFT | REBUILD* | any)
# ===========================================================================
scenario() {
  local label="$1"; local warp_s="$2"; local expect="${3:-any}"
  echo
  echo "===== $label (виртуальная пауза ${warp_s}s) ====="

  run "clockreset" >/dev/null
  run "stop" >/dev/null; sleep 1
  run "play" >/dev/null
  if wait_until 25 t_running; then
    echo "    воспроизведение запущено (state=$(s_state "$LAST_SNAP"))"
  else
    echo "    FAIL: поток не вышел в RUNNING (state=$(s_state "$LAST_SNAP"))"
    PASS=0; FAILED_SCENARIOS="$FAILED_SCENARIOS\n  - $label: не запустилось"
    return 1
  fi

  local win; win=$(s_win "$LAST_SNAP")
  echo "    окно свежести пакета window(F0−audible)=${win}s"

  run "pause" >/dev/null
  if wait_until 15 t_paused; then
    echo "    пауза (state=PAUSED)"
  else
    echo "    FAIL: не дошли до PAUSED (state=$(s_state "$LAST_SNAP"))"
    PASS=0; FAILED_SCENARIOS="$FAILED_SCENARIOS\n  - $label: нет PAUSED"
    return 1
  fi

  local a0 f0 now0
  a0=$(s_aud "$LAST_SNAP"); f0=$(s_front "$LAST_SNAP"); now0=$(s_now "$LAST_SNAP")
  echo "    заморожено: A0(audible)=${a0}s  F0(frontier)=${f0}s  now=${now0}s"

  # ВОТ ОНО: вместо sleep — мгновенный сдвиг настенных часов. Всё звуковое
  # (пакет, голова трека, фронтир) остаётся на месте, уезжает только «сейчас».
  run "warp ${warp_s}s" >/dev/null
  local now1; now1=$(s_now "$(snap)")
  echo "    после warp: now=${now1}s (было ${now0}s), A0 по-прежнему ${a0}s"

  run "resume" >/dev/null
  EXPECT_LAG="$TOL"
  if wait_until 20 t_lag; then
    echo "    слышимое сошлось с now (lag=$(s_lag "$LAST_SNAP")s ≤ ${TOL}s)"
  else
    echo "    ВНИМАНИЕ: lag не уложился в ${TOL}s за 20 с (lag=$(s_lag "$LAST_SNAP")s)"
  fi

  verify "$label" "$TOL"
  report_resolution "$label" "$expect"

  run "stop" >/dev/null; sleep 1
}

# ============================= ПРЕДУСЛОВИЕ =============================
echo "=============================================================="
echo " Верификация возобновления (виртуальное время, без ожидания)"
echo "=============================================================="
adb shell "getprop ro.product.model; getprop ro.build.version.release" 2>/dev/null | sed 's/^/  /'
echo

echo "--- Проверка виртуальных часов (обе стороны обязаны видеть один сдвиг) ---"
run "warp 1s" | sed 's/^/  /'
run "clock"   | sed 's/^/  /'
run "clockreset" >/dev/null
if run "clock" | grep -q "РАСХОЖДЕНИЕ"; then
  echo "  FAIL: Kotlin- и нативная копия часов расходятся — выводы ненадёжны"
  exit 1
fi
echo "  OK: часы согласованы"

# ============================= СЦЕНАРИИ =============================

# 1. Δ ≈ 0: возобновление в ту же секунду — пакет не успел устареть.
scenario "1. Нулевая пауза (Δ≈0)" "0" "SOFT"

# 2. Пауза внутри окна свежести пакета → мягкое продолжение (SOFT).
#    Берём ПОЛОВИНУ реального окна: граница зависит от интервала генерации.
scenario "2. Короткая пауза (Δ = 2с, внутри окна)" "2" "SOFT"

# 3. Длинная пауза — заведомо дальше окна (пакет ~ десятки секунд) → пересборка.
scenario "3. Длинная пауза (Δ = 1 час)" "3600" "REBUILD"

# 4. Пауза ровно на сутки. По модулю суток now возвращается в ту же точку:
#    Δ = normalize(86400) = 0, и звук обязан остаться корректным. Проверяет
#    круговую нормализацию — без неё здесь получился бы мусор.
scenario "4. Пауза ровно на сутки (Δ ≡ 0 по модулю)" "86400" "any"

# ============================= ПЕРЕХОД ЧЕРЕЗ ПОЛНОЧЬ =============================
echo
echo "===== 5. Пауза ЧЕРЕЗ ПОЛНОЧЬ (критичная граница) ====="
run "clockreset" >/dev/null
run "stop" >/dev/null; sleep 1
run "totime 23:59:50" | sed 's/^/  /'
run "play" >/dev/null
if wait_until 25 t_running; then
  echo "  играем на 23:59:5x (state=$(s_state "$LAST_SNAP"))"
else
  echo "  FAIL: не запустилось (state=$(s_state "$LAST_SNAP"))"; PASS=0
fi
run "pause" >/dev/null; wait_until 15 t_paused >/dev/null
A0=$(s_aud "$(snap)"); echo "  A0 (до полуночи) = ${A0}s"
run "warp 30s" >/dev/null
NOW=$(s_now "$(snap)"); echo "  после warp: now = ${NOW}s (должно быть ≈ 20 — уже за полночью)"
run "resume" >/dev/null
EXPECT_LAG="$TOL"
wait_until 20 t_lag >/dev/null || echo "  ВНИМАНИЕ: lag не сошёлся (lag=$(s_lag "$LAST_SNAP")s)"
verify "5. Полночь" "$TOL"
AUD=$(s_aud "$LAST_SNAP")
# Отдельная проверка: слышимое обязано быть ПОСЛЕ полуночи, а не застрять на 23:59.
if lt "$AUD" "120"; then
  echo "  PASS: слышимое перешагнуло полночь (audible=${AUD}s)"
else
  echo "  FAIL: слышимое застряло до полуночи (audible=${AUD}s)"
  PASS=0; FAILED_SCENARIOS="$FAILED_SCENARIOS\n  - 5. Полночь: audible=${AUD}s не после 0:00"
fi
report_resolution "5. Полночь" "any"
run "stop" >/dev/null; sleep 1
run "clockreset" >/dev/null

# ============================= ЧАСЫ НАЗАД (Δ < 0) =============================
echo
echo "===== 6. Часы НАЗАД во время паузы (Δ отрицательная) ====="
echo "  normalize(−5с) = 86395с — заведомо дальше окна, но звук всё равно"
echo "  обязан соответствовать «сейчас», то есть точке на 5 с РАНЬШЕ A0."
run "stop" >/dev/null; sleep 1
run "play" >/dev/null; wait_until 25 t_running >/dev/null
run "pause" >/dev/null; wait_until 15 t_paused >/dev/null
A0=$(s_aud "$(snap)"); echo "  A0 = ${A0}s"
run "warp -5s" >/dev/null
NOW=$(s_now "$(snap)"); echo "  после warp −5с: now = ${NOW}s"
run "resume" >/dev/null
EXPECT_LAG="$TOL"
wait_until 20 t_lag >/dev/null || echo "  ВНИМАНИЕ: lag не сошёлся (lag=$(s_lag "$LAST_SNAP")s)"
verify "6. Часы назад" "$TOL"
report_resolution "6. Часы назад" "REBUILD"
run "stop" >/dev/null; sleep 1
run "clockreset" >/dev/null

# ============================= СМЕНА НАСТРОЕК НА ПАУЗЕ =============================
echo
echo "===== 7. Смена настроек во время паузы (грязная спека → пересборка) ====="
run "stop" >/dev/null; sleep 1
run "play" >/dev/null; wait_until 25 t_running >/dev/null
run "pause" >/dev/null; wait_until 15 t_paused >/dev/null
run "warp 3600s" >/dev/null
run "samplerate 44100" | sed 's/^/  /'
run "resume" >/dev/null
EXPECT_LAG="$TOL"
wait_until 20 t_lag >/dev/null || echo "  ВНИМАНИЕ: lag не сошёлся (lag=$(s_lag "$LAST_SNAP")s)"
verify "7. Смена настроек на паузе" "$TOL"
report_resolution "7. Смена настроек на паузе" "REBUILD"
run "stop" >/dev/null; sleep 1
run "samplerate 48000" >/dev/null
run "clockreset" >/dev/null

# ============================= ПРЕРВАННЫЙ СТОП → PLAY =============================
echo
echo "===== 8. Прерванный стоп→play (фейд-аут перебит стартом) ====="
run "play" >/dev/null; wait_until 25 t_running >/dev/null
run "stop" >/dev/null; sleep 0.3
run "play" >/dev/null
EXPECT_LAG="$TOL"
wait_until 25 t_lag >/dev/null || echo "  ВНИМАНИЕ: lag не сошёлся (lag=$(s_lag "$LAST_SNAP")s)"
verify "8. Прерванный стоп→play" "$TOL"
run "stop" >/dev/null; sleep 1

# ============================= ДВОЙНАЯ ПАУЗА ПОДРЯД =============================
echo
echo "===== 9. Две паузы подряд (накопление виртуального сдвига) ====="
run "clockreset" >/dev/null
run "play" >/dev/null; wait_until 25 t_running >/dev/null
run "pause" >/dev/null; wait_until 15 t_paused >/dev/null
run "warp 3s" >/dev/null
run "resume" >/dev/null; wait_until 20 t_lag >/dev/null
echo "  первое возобновление:"
verify "9a. Первое возобновление" "$TOL"
run "pause" >/dev/null; wait_until 15 t_paused >/dev/null
run "warp 7200s" >/dev/null
run "resume" >/dev/null
EXPECT_LAG="$TOL"
wait_until 20 t_lag >/dev/null || echo "  ВНИМАНИЕ: lag не сошёлся (lag=$(s_lag "$LAST_SNAP")s)"
echo "  второе возобновление (после суммарного сдвига):"
verify "9b. Второе возобновление" "$TOL"
report_resolution "9b. Второе возобновление" "REBUILD"
run "stop" >/dev/null; sleep 1
run "clockreset" >/dev/null

# ============================= ИТОГ =============================
echo
echo "=============================================================="
run "clock" | sed 's/^/  /'
run "clockreset" >/dev/null
if [ "$PASS" -eq 1 ]; then
  echo "  ALL PASS: воспроизведение всегда соответствует текущему моменту суток"
  echo "=============================================================="
  exit 0
fi
echo "  FAIL:"
echo -e "$FAILED_SCENARIOS"
echo "=============================================================="
exit 1
