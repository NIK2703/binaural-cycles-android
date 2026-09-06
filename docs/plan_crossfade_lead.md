# Кроссфейд с опережением (crossfade with lead)

Источники: `docs/analysis_handoff_crossfade_click_risk_vs_samplerate.md` (таксономия щелчков C1–C9),
реализация — `BinauralStreamManager.kt` / `BinauralStreamImpl.kt`.

## 1. Суть схемы

**Прежний (последовательный) хэндофф:** старый фейд-аут → полный релиз → `prepare()` нового → фейд-ин.
Между «утих» и «зазвучал» лежит разрыв ≈ 100–200 мс (стража шейпера + выход писателя + prepare).
Это не сумма-в-единицу, а ДЫРА тишины.

**Новый (основной) путь:** NEXT готовится и стартует ДО того, как CURRENT начал гаснуть:

```
prepare(NEXT)  →  start(NEXT) [sin-рампа вверх]  →  SWAP current=NEXT
              →  stop(OLD)  [cos-рампа вниз]     →  release(OLD) → onOutgoingReleased
```

Перекрытие суммируется по энергии: sin² + cos² = 1 (EQUAL_POWER становится осмысленной — раньше
она лишь делала хвост круче в 1.57 раза). Выход AudioFlinger не простаивает никогда → HAL не
ре-конфигурируется между треками (C2).

## 2. Правила (инварианты)

1. **Не больше двух AudioTrack одновременно.** Слот `outgoing` в менеджере; пока он не `null`,
   `beginCrossfade` не поднимает следующий NEXT. Шторм A→B→C→D материализуется максимум в два
   потока (очередь — один слот latest-wins).
2. **Не больше одного «большого» пакета.** `setPacketGrowthAllowed(false)` на NEXT до релиза
   `outgoing` (стартовый пакет 750 КБ против до 230 МБ у уходящего). Разрешение раздаёт только
   `onOutgoingReleased`. `launchStream` только СУЖАЕТ (не отменяет явный запрет из `beginCrossfade`).
3. **Кольцо 2 МиБ на поток** — два трека влезают в кучу клиента AudioFlinger (~7 МиБ); при 3 МиБ
   второй трек не создаётся вовсе (`createTrack_l -12`, замер на устройстве).
4. **Провал NEXT не трогает CURRENT.** Ошибка `prepare()`/`start()` → откат владения, старый
   продолжает звучать, спека возвращается в очередь; при провале `prepare()` — уход в
   `beginHandoffSequential` (прежний путь с разрывом, сохранён как аварийный).
5. **Релиз уходящего — по факту шейпера, не по таймеру.** Комплешн висит на опросе живого
   множителя (`FADE_POLL_MS = 20`, порог `FADE_ZERO_EPSILON = 0.002` = −54 dBFS), жёсткий потолок
   `FADE_SETTLE_CEILING_MS = 400` с WARN.
6. **Сторож застрявшего релиза** `outgoingReaper`: после `OUTGOING_RELEASE_TIMEOUT_MS = 1200` —
   принудительный `releaseNow()` (поток к тому моменту в нуле, неслышим).
7. **`stop()` идемпотентен** по `fadeMode == OUT` — повторный вызов не перезапускает рампу с 1.0.

## 3. Покрытие таксономии щелчков

| Клик | Механизм | Закрытие |
|---|---|---|
| C1 | underrun | явный `UNDERRUN_HEADROOM_MS = 2000` → запас 2 с на любой SR; чанк = min(WRITE_CHUNK, кольцо − headroom), при вырождении — кольцо/2 |
| C2 | HAL re-config на простое | выход не простаивает: NEXT стартует до fade-out OLD |
| C3 | `prepare()` на слуху | prepare идёт под звучащий CURRENT |
| C4 | лаг VolumeShaper > стражи | опрос живого множителя вместо константы 60 мс (остаток ≤ 0.002 против до 0.126) |
| C5 | шаг базовой громкости | `setVolume(0)` ДО `closeShaper()` (сохранено) |
| C6 | частичная запись | без изменений (чанк уже аккуратен) |
| C7–C9 | якорь/фаза/шов пакета | fade-in NEXT стартует строго с 0; фазы несущих наследуются (`captureContinuity`/`enrichForContinuity`); якоря кривой НЕТ — NEXT встаёт на «сейчас» в `prepare()` |

## 4. Что сделано (статус на 2026-09-03, проверено на устройстве — см. §5)

### `BinauralStreamImpl.kt` — ГОТОВО
- `FADE_GUARD_MS` (60 мс) удалён → `FADE_POLL_MS`/`FADE_ZERO_EPSILON`/`FADE_SETTLE_CEILING_MS`,
  `scheduleFadeCompletion()` + `liveShaperVolume()`; все 5 точек `postDelayed(... + GUARD)`
  переведены на опрос (start/stop/reverseFade/pause/resume).
- `UNDERRUN_HEADROOM_MS = 2000`, `MIN_WRITE_CHUNK_MS = 500`; чанк писателя выводится из явного
  запаса + диагностический лог кольцо/чанк/запас.
- `packetGrowthAllowed` (@Volatile) + хуки `setPacketGrowthAllowed()` / `releaseNow()`.
- `packetStats()`: инвариант держателей пакета ослаблен до `<= 2`.

### `BinauralStreamManager.kt` — ГОТОВО (компилируется)
- Поля `outgoing` / `pendingAfterOutgoing` / `outgoingStartWallMs`; константы
  `OUTGOING_RELEASE_TIMEOUT_MS = 1200`, `OUTGOING_REAPER_PERIOD_MS = 200`.
- `requestHandoff()` → очередь + `tryAdvanceQueue()` (единственная точка решения «создавать поток»;
  refuses при `outgoing != null`; запуски при `current == null` корректно закрывают якорь).
- `beginCrossfade()`: continuity → prepare NEXT (провал → sequential fallback, CURRENT не тронут)
  → `setPacketGrowthAllowed(false)` → `launchStream` (провал → ОТКАТ владения current/состояния,
  старый продолжает звучать) → SWAP → `stop(OLD, EQUAL_POWER)`.
- `beginHandoffSequential()` — прежний путь как аварийная ветвь; `onStreamFullyStopped` SWITCH
  обслуживает только её.
- `onOutgoingReleased()` / `afterOutgoingReleased()` / `outgoingReaper` / `scheduleOutgoingReaper()`.
- `onStreamReleased()`: маршрутизация `s === outgoing` → `onOutgoingReleased`.
- `launchStream(): Boolean` (возвращает успех; неудача — откат менеджером).
- `launchStream` сужает packet-growth при живом `outgoing` (правило 2 — для ВСЕХ путей запуска).
- `onStop` в HANDOFF: `fadeOutCurrent(STOP)` явно (HANDOFF теперь означает «NEXT играет», одного
  retargetFade недостаточно — иначе stop во время кроссфейда оставлял NEXT играть вечно).
- `release()`: снятие `outgoingReaper`, `outgoing?.releaseNow()` до quitSafely актёра.
- `handleRuntimeError`: сбой УХОДЯЩЕГО — только закрытие перекрытия, автомат не трогается.
- `resumeFromPaused()`: отложен до `onOutgoingReleased`, если пауза застала незакрытый кроссфейд.

### `BinauralStream.kt`
- KDoc `FadeShape.EQUAL_POWER` переписан (F8): осмысленна ТОЛЬКО при перекрытии.

### Сборка
- `:core:audio:compileDebugKotlin` — OK; `:app:compileDebugKotlin` — OK (51 c / 1 м 13 с).
- `:app:assembleDebug -PabiFilter=arm64-v8a` — OK (1 м 30 с), установлено на устройство.

### Стенд
- `tools/dbgxlead.sh` — 6 сценариев проверки кроссфейда с опережением (см. §5).

## 5. Проверка на устройстве — ВЫПОЛНЕНО (2026-09-03)

**Стенд:** POCO 23049PCD8G (lineage_marble), Android 13, adb `192.168.61.212:5555`, debug-варианта
`com.binauralcycles.debug` (pid 4826, не перезапускался за весь прогон).
Сборка: `gradle :app:assembleDebug -PabiFilter=arm64-v8a --no-daemon` (1 м 30 с — только Kotlin,
нативная часть не пересобиралась).

**Сценарии:** новый `tools/dbgxlead.sh` (S1 смена в покое · S2 пауза в кроссфейде · S3 стоп в
кроссфейде · S4 возобновление из такой паузы · S5 шторм 48 кГц · S6 шторм 8 кГц).
**Итог: 41 PASS / 0 FAIL / 1 SKIP.**

### Замеры

| Величина | Результат |
|---|---|
| Окно перекрытия (SWAP → релиз уходящего) | 326–407 мс, медиана ~360 мс |
| Держатели пакета (`pkstat`) | `holders=1 peak=2` — инвариант ≤ 2 держится на 48 кГц и на 8 кГц |
| `oomHalvings` | 0 на обоих частотах |
| `underrunDelta` | 0 во всех прогонах; кольцо 2 МиБ (5461 мс @48 кГц), чанк 3461 мс, запас 2000 мс |
| `pal_stream_start` ПОСЛЕ SWAP | 0 — HAL не переконфигурировался, **C2 закрыт объективно** |
| WARN «шейпер не дошёл до цели» | 0 — completion всегда по факту, **C4 закрыт** |
| Шторм: 40 смен через 120 мс | 13–14 материализованных SWAP, столько же релизов, 0 потерь |
| «отказов роста» пакета в шторме | 13–14 — ожидаемо: NEXT живёт на стартовом пакете до релиза `outgoing` |

Поведение автомата подтверждено: пауза и стоп ВНУТРИ кроссфейда доходят до `PAUSED` / `IDLE`,
NEXT не остаётся играть вечно; возобновление после такой паузы поднимает звук
(`playing=true`, `пакетодержателей=1`).

### Что осталось непроверенным

Защитная ветвь `resumeFromPaused` «`outgoing != null` → отложить до релиза» (SKIP). Она достижима
только если `resume` прилетает в окно между `onPausedFully` (pause + ~275 мс) и релизом уходящего
(SWAP + ~360 мс); при паузе на +107 мс это ~25 мс. Гонка не сошлась — но это и значит, что ветвь
не понадобилась: откладывать было нечего.

### Три грабли стенда (для будущих правок скриптов)

1. **Фоновый `adb logcat > файл` в Git Bash не убивается через `kill`.** Процесс первого сценария
   писал в свой файл до конца всего прогона, поэтому проверки S2…S6 находили чужие строки и
   проходили ложно. Переведено на синхронный дамп: `logcat -G 16M` → пауза → `logcat -b all -d`.
2. **Значение `--es cmd` внутри `adb shell "…"` нельзя брать в двойные кавычки.** Вариант
   `--es cmd \"'next'\"` доходит до `sh -c` как `'next'` с литеральными апострофами →
   «Неизвестная команда». Пишем слово без кавычек.
3. **`samplerate low` не существует** — только число (8000/16000/22050/44100/48000). С неверным
   аргументом шторм молча шёл на 48 кГц, и «проверка на 8 кГц» была бы липой. В S5/S6 добавлена
   явная проверка `@8000Гц` / `@48000Гц` по логу `createAudioTrack`.

### Старый сценарий `tools/dbgxfade.sh`

Не потерял смысла: фазы D (стоп посреди кроссфейда), E (мягкая пауза посреди кроссфейда +
возобновление) и K (шторм пауза → смена пресета → resume) проходят. Инвариант фазы K держится —
`launchSpec reason=RESUME загруженныхБуферов=0`. Список `MARKERS` дополнен новыми событиями
(`beginCrossfade`, `onOutgoingReleased`, `outgoingReaper`, `afterOutgoingReleased`, `onPause`,
`onPausedFully`, `resumePausedStream`) — иначе выгрузка была бы слепой к новой схеме.

## 6. Дальнейший план

1. **Прослушивание release-сборки** (по открытому списку: c=90…130, b=20…60,
   контроль 120/200/300; слайдер ≥ 40 %) — переходы между пресетами без щелчка и без дыры.
   Контрольные точки: смена пресета в покое, смена во время звучания, смена на паузе.
   Release `app-arm64-v8a-release.apk` (5 м 25 с, R8) собран и установлен в `com.binauralcycles`
   2026-09-03 — остаётся только слушать.
2. **Коммит** по рабочему рецепту
   (`GIT_CONFIG_NOSYSTEM=1 git -c credential.helper=wincred push origin <SHA>:refs/heads/<branch>`).
3. Снять SKIP с защитной ветви `resumeFromPaused`, если подвернётся детерминированный способ
   задержать релиз уходящего (сейчас это только гонка на ~25 мс; искусственной задержки в
   debug-командах нет).

## 7. Известные компромиссы

- Частота пробуждений писателя на 48 кГц растёт (~1040/час против ~800): цена генерации не зависит
  от длины пакета, underrun — полноамплитудный ступень, запас важнее.
- Кроссфейд живёт на стартовом пакете NEXT (~2 с запас): если уходящий застрянет на полный
  `OUTGOING_RELEASE_TIMEOUT_MS`, NEXT обязан жить без доращивания до 1.2 с — стартового пакета
  хватает с запасом.
- `onStreamFullyStopped` SWITCH (sequential fallback) оставлен как есть — тестируется только при
  искусственном провале prepare (OOM-инъекция не запланирована).
