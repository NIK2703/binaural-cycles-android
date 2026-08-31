# Анализ: стоп/продолжение и позиция по времени суток

## Ключевое требование (исправлено понимание, 2026-08-31)

**Суть приложения:** оно воспроизводит бинауральный ритм по пресету **для ТЕКУЩЕГО
момента времени суток**. График частот — это суточная кривая (`FrequencyCurve` по
`LocalTime`); в любой момент звук обязан соответствовать тому, который предписан
пресетом на **сейчас** (real-time-of-day), а не «та позиция, где мы остановились».

Следствие для паузы/продолжения:
- **Пауза** = просто заморозить вывод (тишина), НЕ «сохранить место в песне».
- **Возобновление** = продолжить игру **с текущего момента времени суток** по пресету.
  Если паузу поставили в 14:32 и возобновили в 14:35 — звучит ритм для 14:35, а не для 14:32.
- **Стоп** (полный) = то же самое: следующий старт начинается с текущего времени суток.

То есть «продолжение с запомненной отметки» (как у музыкального плеера) здесь
**НЕПРАВИЛЬНО** — это была ошибка в предыдущем фиксе (см. ниже). Правильное
поведение: при любом возобновлении движок стартует с `realTimeOfDaySeconds()`
(свежий `engine.play()` с `resumeCurveTimeSeconds = -1`), а не с `pausedTimeOfDay`.

## Исходный симптом (первый вопрос пользователя)
Пользователь нажимал стоп/продолжить несколько раз подряд и видел, что
воспроизведение **часто** начиналось с 0:00 (с начала суточной кривой), а не с
текущего момента. То есть баг был в том, что позиция сбрасывалась в 0:00 вместо
того, чтобы быть **текущим временем суток**.

### Почему сбрасывалось в 0:00 (корень, не изменился)
`BinauralViewModel.togglePlayback()` трактовал toggle как жёсткий стоп
(`stopWithFade()`), который утилизирует поток и сбрасывает `pausedTimeOfDay = 0`.
Следующий запуск создавал новый поток с `resumeCurveTimeSeconds = -1`, и в
`prepare()` это ветка `engine.play()` — **свежий старт**, который якорит кривую к
`realTimeOfDaySeconds()` (см. `BinauralEngine::setPlaying(true)` без
`preserveTimeline`). То есть по замыслу он ДОЛЖЕН был стартовать с текущего времени
суток — но `m_curveTimeSeconds` после `resetState()` и свежего `play()` мог
оказаться 0, если wall-clock-якорь не успел примениться, либо гонка
`FADE_OUT_STOP` пересоздавала поток в момент, когда нативный таймлайн ещё
заморожен на 0.

Две независимые оси времени в движке:
- **Фронтир генерации** = `m_curveTimeSeconds` (продвигается при генерации пакета).
- **Слышимое время** = `playbackHeadPosition` минус недописанный хвост
  (`computeAudibleTimeSeconds`).

Мост: `BinauralStreamImpl.audibleCurveSeconds()` → `getAudibleTimeOfDaySeconds()`.

## ОШИБКА предыдущего фикса (помечено, 2026-08-31)

Был применён фикс, который закрепил «продолжение с запомненной позиции»:
1. `togglePlayback()` → `pauseWithFade()` (мягкая пауза, сохраняет `pausedTimeOfDay`).
2. `onStop` → `capturePauseMetrics()` до фейда.
3. `onStreamFullyStopped(STOP)` → прерванный стоп→play стартует с
   `resumeCurveTimeSeconds = pausedTimeOfDay`.
4. Debug-CLI `audible`.

Это **противоречит сути приложения**: теперь пауза «перематывает» на запомненную
точку, а должна играть ритм для текущего времени суток. Предыдущий фикс отменяется
— см. план работы.

## Уточнение семантики паузы/возобновления (2026-08-31, после разбора)

Решающее уточнение к «сути приложения»: возобновление обязано играть ритм для
**текущего** момента суток — но замороженный пакет переиспользуется, когда он ещё
актуален. Пакет не «устаревает после любой паузы», а только когда текущий момент
выходит за окно уже сгенерированного аудио.

### Где лежит окно актуальности пакета
Писатель генерирует пакет на `bufferIntervalMs` (до ~600 с) вперёд и пишет его в
`AudioTrack` чанками. К моменту паузы у живого потока есть три координаты кривой
(секунды суток, нормализованные в `[0, 86400)`):
- `A0` — **слышимая** позиция (`getAudibleTimeOfDaySeconds()`), где остановился
  звук. Это «голова воспроизведения» минус недописанный хвост.
- `F0` — **фронтир генерации** (`getCurrentCurveTimeSeconds()` = `m_curveTimeSeconds`),
  конец уже сгенерированного аудио. Между `A0` и `F0` лежит недоигранный буфер
  `U0 = F0 − A0` (сгенерирован, но ещё не прозвучал — часть в кольце трека `R`,
  часть не дописана в пакет).
- `now` — реальное время суток на момент возобновления.

Пакет **не устарел**, пока `normalize(now − A0) ≤ normalize(F0 − A0)`, т.е.
`now` ещё внутри сгенерированного окна `[A0, F0]`. Звук для `now` уже
присутствует в пакете (со смещением `Δ = normalize(now − A0)` от головы) — его
надо лишь «дописать»: пропустить устаревший кусок. Пакет **устарел** (`now > F0`)
— сгенерированного аудио на текущий момент уже нет → пересобрать поток с якорем
на `now`.

### Что делает возобновление
- **Не устарел** → мягкое возобновление того же потока + **пропуск `Δ*rate`
  кадров** из пакета: писатель сдвигает свой `offset` вперёд на `Δ*rate*frameBytes`
  (перенося остаток на следующий пакет, если `Δ` выходит за конец текущего — см.
  Шаг 3). Результат: после того как кольцо трека (≈ `R`, 3–10 с) доигрывает
  старый хвост, звук идёт ровно для `now`. Указатель UI переякоривается на `now`
  (`resumeUiTimelineFrom(now)`), чтобы индикатор графика показывал текущий момент.
  Первые `R` секунд слышимый звук отстаёт от `now` на `Δ` — это переходный
  участок (на 24-часовой кривой дрейф частоты за `R` пренебрежим), затем синхрон.
- **Устарел** → пересборка потока (`resumeFromPaused`): свежий движок,
  `resumeCurveTimeSeconds = -1` ⇒ `prepare()` якорит кривую на `now`
  (`engine.setCurveTime(now)`). Часы сессии продолжаются (`resumeElapsedMs =
  accumulatedMs`), пауза в `elapsed` не идёт.

### Что такое «устаревание» точно (формула)
Пакет сгенерирован в момент, когда недоигранный буфер исчерпался (тогда
`U ≈ bufferIntervalMs`), и покрывает интервал `[T_gen, T_gen + bufferIntervalMs]`
по кривой. Он действителен для воспроизведения в реальном времени, пока стенное
время `≤ T_gen + bufferIntervalMs`, что равносильно `now ≤ F0` (фронтир ещё не
прошёл текущий момент). Ночной переход через полночь корректно ловится
нормализацией (`normalize(x) = ((x % 86400) + 86400) % 86400`).

## План работы (отмена ошибочного фикса + правильное поведение)

### Шаг 1. `togglePlayback()` — пауза = тишина, возобновление = ритм для `now`
- Играющая ветка `BinauralViewModel.togglePlayback()` → `pauseWithFade()`
  (мягкая пауза, состояние `PAUSED`, часы сессии сохраняются). Непрекращающая
  ветка → `resumeWithFade()` (возобновление из `PAUSED` либо свежий старт из
  `IDLE`).
- Жёсткий `stopWithFade()` (утилизация → `IDLE`, часы сессии в 0) остаётся за
  уведомлением/разрывом гарнитуры/сменой пресета. Обе ветки (пауза и стоп)
  заканчиваются одним: звук соответствует текущему моменту суток.
- (Решение по Шагу 4 прежнего плана: мягкую паузу оставляем — она мгновенна и
  безвредна; при возобновлении НЕ переякориваем на `pausedTimeOfDay`, а
  переиспользуем пакет с пропуском устаревшего либо пересобираем, см. Шаг 3.)

### Шаг 2. Свежий старт = текущее время суток, а не 0:00
В `BinauralStreamImpl.prepare()` унифицировать якорь кривой:
```
// СУТЬ ПРИЛОЖЕНИЯ: звук для ТЕКУЩЕГО момента суток. Явная позиция
// (resumeCurveTimeSeconds >= 0) задаётся ТОЛЬКО сквозным хэндоффом
// (смена пресета/SR/настроек), где разрыв недопустим. Пауза — не повод
// сохранять позицию: возобновление играет ритм для now.
val curveTod = if (spec.resumeCurveTimeSeconds >= 0)
                   spec.resumeCurveTimeSeconds
               else engine.getCurrentTimeOfDay()   // реальные (или virtual) часы
engine.setCurveTime(curveTod)
```
- `engine.getCurrentTimeOfDay()` на свежем движке (до `play()`) отдаёт
  `getCurrentTimeSeconds()` (реальное локальное время) либо virtual-base — то же,
  что потом возьмёт `setPlaying(true)` без `preserveTimeline`. Явный `setCurveTime`
  убирает гонку «новый поток родился, пока старый ещё frozen» (исходный баг
  «старт с 0:00»).
- `setCurveTime` ДО `play()`: для ветки `resumeCurveTimeSeconds >= 0` это уже
  есть; для свежего/возобновлённого старта он теперь тоже задаётся явно и
  переживает `resetState()` + `setPlaying(true)` (см. `BinauralEngine.cpp:
  setCurveTimeSeconds`/`setPlaying`, где без `preserveTimeline` фронтир =
  `realTimeOfDaySeconds()` — дублируется безвредно).

### Шаг 3. Возобновление: переиспользовать пакет или пересобрать
В `BinauralStreamManager`:
- `onPausedFully`/`capturePauseMetrics`: кроме `pausedTimeOfDay` (= `A0`) снимать
  `pausedFrontierTimeOfDay = it.getCurrentCurveTimeSeconds()` (= `F0`). Оба
  заморожены, пока поток на паузе.
- `onResumeFromPaused()`:
  - если `pausedSpecDirty` → `resumeFromPaused()` (настройки сменились на паузе).
  - иначе вычислить на актёре в момент возобновления:
    ```
    val now = realTimeOfDaySeconds()          // Kotlin: LocalTime.now().toSecondOfDay() + nano
    val a0  = pausedTimeOfDay                  // A0, int, заморожен
    val f0  = pausedFrontierTimeOfDay          // F0, float, заморожен
    val dA  = normalize(now - a0)
    val dF  = normalize(f0 - a0)
    if (dA <= dF) resumePausedStream(skipSeconds = dA)   // не устарел
    else           resumeFromPaused()                      // устарел → пересборка
    ```
    (`normalize(x) = ((x % 86400) + 86400) % 86400` — ловит переход через полночь.)
- `resumePausedStream(skipSeconds)` (мягкое продолжение):
  - передать пропуск в поток: `s.resume(skipSeconds)` (см. `BinauralStreamImpl`).
  - UI-якорь НЕ на `audibleCurveSeconds()` (старое), а на `now`:
    `nativeEngine?.resumeUiTimelineFrom(now)` — индикатор показывает текущий момент.
- `resumeFromPaused()` (пересборка, устарел/настройки):
  - **убрать** строку `resumeCurveTimeSeconds = pausedTimeOfDay`. Оставить
    `resumeCurveTimeSeconds = -1` ⇒ `prepare()` якорит на `now` (Шаг 2).
  - сохранить `resumeAnchorMs = now − accumulatedMs`, `resumeElapsedMs =
    accumulatedMs` (часы сессии продолжаются, пауза в них не идёт).
- `onStreamFullyStopped(STOP)` (прерванный стоп→play): убрать подстановку
  `resumeCurveTimeSeconds = pausedTimeOfDay` — `resetSession()` уже занулил
  `pausedTimeOfDay`, старт и так свежий от `now`.

В `BinauralStreamImpl`:
- Добавить `@Volatile var pendingSkipFrames = 0L` (пишет нить управления в
  `resume()`, читает писатель) и `@Volatile var skippedFrames = 0L` (накопленные
  выброшенные кадры — участвуют в расчёте слышимой позиции).
- `resume(skipSeconds: Float)`:
  - `pendingSkipFrames = (skipSeconds * spec.sampleRate.value).toLong()`.
  - UI-якорь на `now`: `nativeEngine?.resumeUiTimelineFrom(realTimeOfDaySeconds())`
    вместо `audibleCurveSeconds()`.
- `writerLoop()`: сразу после `if (paused) { parkWriter(); continue }` вставить
  применение пропуска (до блока генерации пакета, чтобы остаток переносился на
  следующий пакет):
  ```
  if (pendingSkipFrames > 0) {
      val frame = frameBytes.toLong()            // байт на кадр (stereo float = 8)
      val avail = ((packetBytes - offset) / frame).toLong()
      val take = minOf(avail, pendingSkipFrames)
      offset += (take * frame).toInt()
      skippedFrames += take
      pendingSkipFrames -= take
      if (pendingSkipFrames > 0) continue        // добрать из следующего пакета
  }
  ```
  - Защита от зацикливания: если `pendingSkipFrames` всё ещё > 0 после генерации
    ≥ 2 пакетов — сбросить в 0 и залогировать ошибку (на практике не случится:
    условие `dA <= dF` ограничивает пропуск окном одного пакета).
- `audibleCurveSeconds()`: `eng.getAudibleTimeSeconds(head + skippedFrames,
  generatedFrames)` — недоигранный буфер уменьшается на выброшенные кадры, слышимая
  позиция совпадает с продвинутым фронтиром.
- `getAudibleTimeOfDaySeconds()`/`getFrequenciesAtCurrentTime()` не меняются
  (читают состояние движка; фронтир `m_curveTimeSeconds` пропуск не трогает).

### Шаг 4. Убрать лишнее сохранение позиции в hard-stop
- `BinauralStreamManager.onStop()` для `RUNNING/FADE_IN`: `capturePauseMetrics()`
  здесь больше не нужен — hard-stop не возобновляется из `PAUSED`. Оставить только
  в `onPause()` (мягкая пауза, нужна для `F0`/`A0` при возобновлении).
- `onStop()` остальных веток — без изменений.
- `resetSession()` уже зануляет `pausedTimeOfDay`/`pausedFrontierTimeOfDay`.

### Шаг 5. Проверка на устройстве
- `dbgverify_resume.sh` (переписать), два сценария:
  - **Короткая пауза** (внутри окна пакета, `Δ` меньше недоигранного буфера):
    play → pause → подождать `Δ` (напр. 10 с) → resume → `audible` должен
    показать `now` (± кольцо трека `R`), `pausedTimeOfDay` в логе НЕ используется
    как якорь. Маркер: `audible_after_resume ≈ real_time_now`.
  - **Длинная пауза** (пауза дольше окна пакета, `Δ` > `bufferIntervalMs`):
    play → pause → подождать `Δ` (напр. 15 мин) → resume → `audible` = `now`
    (пересборка потока). Маркер: `audible_after_resume ≈ real_time_now`.
  - **Переход через полночь** (опц.): pause перед полночью, resume после →
    `audible` = `now` (нормализация).
- `dbgverify_stop_race.sh`: play → stop + сразу play во время фейда → `audible`
  = `now`, не 0:00 и не запомненная точка.
- Сравнить `audible` сразу после старта со `status` time (на экране) — совпадение
  с точностью до секунд.

### Шаг 6. Документация
- Обновить `docs/design_signed_beat_channel_layout.md` и комментарии в
  `BinauralStreamManager`/`BinauralStreamImpl`: позиция возобновления = текущий
  момент суток; пауза переиспользует замороженный пакет, если `now ≤ F0`, иначе
  пересобирает поток.
- Уточнить в `MEMORY.md` инвариант: «возобновление = ритм для текущего момента
  суток; пакет не устарел, пока `now ≤ F0` (фронтир генерации)».

## Статус — РЕАЛИЗОВАНО (2026-08-31)

- Предыдущий фикс (продолжение с запомненной позиции `pausedTimeOfDay`) —
  **ошибочен по сути**, отменён (Шаг 3: `resumeFromPaused` больше НЕ подставляет
  `pausedTimeOfDay`, возобновление якорится на `now`).
- Исходный баг (старт с 0:00 вместо текущего времени суток) закрыт Шагом 2:
  `prepare()` теперь ЯВНО якорит `m_curveTimeSeconds` на `now` через
  `engine.setCurveTime(...)` во всех ветках (свежий старт / пересборка).
  Добавлен нативный геттер фронтира `getCurveTimeSeconds()` (JNI
  `nativeGetCurveTimeSeconds`), чтобы решатель возобновления мог сравнить
  `now` с `F0` без полагания на UI-время.
- Корректная семантика реализована (см. «Уточнение семантики»):
  - `onResumeFromPaused()` решает `Δ = normalize(now − A0)` против
    `window = normalize(F0 − A0)`;
    `Δ ≤ window` → `resumePausedStream(Δ)` (мягкое продолжение того же потока
    с пропуском `Δ·rate` кадров из пакета), иначе `resumeFromPaused()`
    (пересборка потока, свежий старт от `now`).
  - `BinauralStreamImpl.resume(skipSeconds)` ставит `pendingSkipFrames`;
    `writerLoop` выбрасывает голову пакета, `skippedFrames` учитывается в
    `audibleCurveSeconds()`; UI-якорь ставится на `now` (`reanchorUiTimeline`).
- Шаг 4 выполнен: `onStop()` для `RUNNING/FADE_IN` больше не делает
  `capturePauseMetrics()` (hard-stop не возобновляется из паузы); снимок
  A0/F0 остался только в мягкой паузе (`onPause`/`finalizePause`).
- Debug-CLI `audible` теперь печатает `audible` / `frontier` / `now` — видно
  сходимость звука с `now` после возобновления. `dbgverify_resume.sh`
  переписан под два сценария плюс прерванный стоп→play; на устройстве НЕ
  прогнано (нет под рукой запущенного сеанса), нужен полный прогон по
  `dbgverify_resume.sh` + стресс памяти.

### Что ещё проверить на устройстве (обязательно перед релизом)
- `bash tools/dbgverify_resume.sh` — оба сценария + прерванный стоп→play:
  маркер `audible ≈ now` (Δ < 5 с) и `audible != 0`.
- `bash tools/dbgxfade.sh` (все 10 фаз) + стресс после правок — смотреть
  ошибки в СЫРОМ логе устройства.
- Переход через полночь (опц.): pause перед полночью, resume после →
  `audible = now` (нормализация в `normalizeTimeOfDay` ловит переход).
