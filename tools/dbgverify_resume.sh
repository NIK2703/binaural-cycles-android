#!/bin/bash
# Проверка семантики возобновления (docs/analysis_resume_from_0_position.md).
#
# СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ моменту суток.
# Возобновление после паузы играет ритм для «сейчас», а не «продолжает с
# запомненной отметки» (как музыкальный плеер): замороженный пакет
# переиспользуется с пропуском устаревшей головы, пока текущий момент
# (now) внутри сгенерированного окна [audible, frontier]; вышел за frontier —
# пакет пересобирается.
#
# Два сценария:
#   КОРОТКАЯ пауза (Δ < окна) — возобновление переиспользует пакет,
#       audible сходится с now (через кольцо трека R).
#   ДЛИННАЯ пауза (Δ > окна) — пакет устарел, пересборка,
#       audible сходится с now.
# ПЛЮС: прерванный стоп→play во время фейда — audible = now, не 0:00 и не
#       запомненная точка.
#
# Критерий PASS: после resume `audible` (через R секунд) ≈ `now` с точностью
# до нескольких секунд, и он НЕ равен 0:00 при старте не в полночь.

ADB=/home/nikita/tools/android-sdk/platform-tools/adb
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND

run() {
  "$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --include-stopped-packages --es cmd "'$1'" 2>&1 \
    | tr '\n' ' ' \
    | grep -o 'data="[^"]*"' \
    | head -1 \
    | sed 's/data="//; s/"$//'
}
# audible -> "audible=NNNs (...) frontier=MMMs now=SS.SSs" -> числа
audible()   { run "audible" | grep -o 'audible=[0-9]*'  | head -1 | sed 's/audible=//'; }
frontier()  { run "audible" | grep -o 'frontier=[0-9]*'| head -1 | sed 's/frontier=//'; }
nowsec()    { run "audible" | grep -o 'now=[0-9.]*'     | head -1 | sed 's/now=//'; }
# audibleraw -> "audibleraw=NNNs (...) frontier=MMMs now=SS.SSs lag(now-raw)=LL.LLs"
rawaud()    { run "audibleraw" | grep -o 'audibleraw=[0-9]*' | head -1 | sed 's/audibleraw=//'; }
rawlag()    { run "audibleraw" | grep -o 'lag(now-raw)=[0-9.]*' | head -1 | sed 's/lag(now-raw)=//'; }
# resumesnap -> многострочный снимок; вытащить окно lead из "window(lead)=X"
snapshot()   { run "resumesnap"; }
windowsnap() { run "resumesnap" | grep -o 'window(lead)=[0-9.]*' | head -1 | sed 's/window(lead)=//'; }
resosnap()  { run "resumesnap" | grep -o 'resolution=[A-Z_]*' | head -1 | sed 's/resolution=//'; }
state()     { run "state"; }

# Нормализованная разница круглых суток (для сравнения across midnight).
norm_delta() {
  # $1 = a, $2 = b ; печатает КРУГОВУЮ нормализованную |a-b| в секундах (0..43200).
  # Без поправки «>43200 → 86400-d» маленькая отрицательная разница (-1с) давала
  # 86399с и ложный FAIL.
  awk -v a="$1" -v b="$2" 'BEGIN{
    d = a - b; d = d - 86400*int(d/86400);
    if (d < 0) d += 86400;
    if (d > 43200) d = 86400 - d;
    print d
  }'
}

PASS=1
scenario() {
  local name="$1"; local pause_s="$2"
  echo
  echo "===== СЦЕНАРИЙ: $name (пауза ${pause_s}s) ====="
  run "play" >/dev/null; sleep 3
  A0=$(audible); echo "AUDIBLE до паузы       = $A0"
  F0=$(frontier); echo "FRONTIER до паузы      = $F0"
  run "pause" >/dev/null; sleep 1
  S1=$(state); echo "STATE после паузы        = $S1"
  AP=$(audible); echo "AUDIBLE сразу после пауз= $AP"
  echo "... ждём ${pause_s}s ..."
  sleep "$pause_s"
  AP2=$(audible); echo "AUDIBLE на паузе        = $AP2 (должен стоять)"
  run "resume" >/dev/null; sleep 1
  S2=$(state); echo "STATE после resume       = $S2"
  # Снимок решателя: какой путь выбран, окно lead, Δ, пропущенные кадры.
  echo "--- resumesnap (решатель возобновления) ---"
  snapshot | sed 's/^/    /'
  local RES; RES=$(resosnap); echo "RESOLUTION               = ${RES:-?}"
  local WIN; WIN=$(windowsnap); echo "WINDOW(lead)=${WIN:-?}s (переходная задержка до сходимости)"

  # ТОЧНОСТЬ: реальная слышимая позиция сразу после resume должна отставать
  # от now ровно на Δ (длительность паузы), пока кольцо трека доигрывает хвост.
  local RAW0; RAW0=$(rawaud); local LAG0; LAG0=$(rawlag)
  echo "AUDIBLERAW сразу после resume = $RAW0 (lag now-raw = ${LAG0}s)"
  echo "AUDIBLE (компенсированный)   = $(audible)  (прыгает на now сразу — прячет задержку)"

  # Ждём сходимости: lead секунд + запас. Если окно не снялось — запас 12с.
  local wait_s; wait_s=$(awk -v w="$WIN" 'BEGIN{ if (w+0 <= 0) w=12; printf "%d", w+3 }')
  echo "... ждём ${wait_s}s до сходимости кольца трека ..."
  sleep "$wait_s"
  local AA; AA=$(audible); local NOW; NOW=$(nowsec)
  local RAW1; RAW1=$(rawaud); local LAG1; LAG1=$(rawlag)
  echo "AUDIBLE после resume     = $AA"
  echo "AUDIBLERAW после resume  = $RAW1 (lag now-raw = ${LAG1}s)"
  echo "NOW                      = $NOW"
  local d; d=$(norm_delta "$AA" "$NOW")
  echo "Δ(audible, now)          = ${d}s"
  # Критерий: Δ < 5с и audible != 0 (при старте не в полночь).
  if awk -v d="$d" 'BEGIN{exit !(d < 5)}'; then
    if [ "$AA" != "0" ]; then
      echo "PASS: $name — звук для текущего момента суток (Δ=${d}s)"
    else
      echo "FAIL: $name — audible=0 (START В 0:00!)"
      PASS=0
    fi
  else
    echo "FAIL: $name — audible не сошёлся с now (Δ=${d}s)"
    PASS=0
  fi
  # ДОП. КРИТЕРИЙ ТОЧНОСТИ (нестареющий путь): реальная слышимая позиция
  # после сходимости обязана догнать now (lag → ~0), а сразу после resume —
  # отставать примерно на Δ (но не больше окна lead).
  if [ "$RES" = "SOFT" ]; then
    if awk -v l="$LAG1" 'BEGIN{exit !(l < 5)}'; then
      echo "PASS: $name — РЕАЛЬНАЯ слышимая позиция сошлась с now (lag=${LAG1}s)"
    else
      echo "FAIL: $name — РЕАЛЬНАЯ слышимая позиция не сошлась (lag=${LAG1}s)"
      PASS=0
    fi
  fi
}

# Короткая пауза (внутри окна пакета, Δ меньше недоигранного буфера) → SOFT-путь.
scenario "КОРОТКАЯ пауза" 10
# Длинная пауза (дольше окна пакета → пересборка потока) → REBUILD-путь.
scenario "ДЛИННАЯ пауза" 60

# Прерванный стоп→play во время фейда.
echo
echo "===== СЦЕНАРИЙ: прерванный стоп→play (фейд) ====="
run "play" >/dev/null; sleep 3
run "stop" >/dev/null; sleep 0.3   # фейд-аут идёт — втыкаем play
run "play" >/dev/null; sleep 3
AS=$(audible); NOW=$(nowsec)
echo "AUDIBLE после прерванного play = $AS"
echo "NOW                          = $NOW"
d=$(norm_delta "$AS" "$NOW")
echo "Δ(audible, now)              = ${d}s"
if awk -v d="$d" 'BEGIN{exit !(d < 5)}' && [ "$AS" != "0" ]; then
  echo "PASS: прерванный стоп→play — старт с текущего момента суток (Δ=${d}s)"
else
  echo "FAIL: прерванный стоп→play — audible=$AS Δ=${d}s"
  PASS=0
fi
run "stop" >/dev/null; sleep 1

echo
echo "=== ИТОГ ==="
if [ "$PASS" -eq 1 ]; then
  echo "ALL PASS: возобновление играет ритм для текущего момента суток"
else
  echo "FAIL: см. выше"
  exit 1
fi
