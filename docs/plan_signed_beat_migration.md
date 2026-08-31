# План работ: миграция раскладки ушей на знаковую beat (шаги 2–5)

Исполнительный план по `design_signed_beat_channel_layout.md` (далее —
дизайн-док): карта правок каждого шага, модель звука, стратегия тестов,
критерии приёмки, порядок коммитов и откаты. Нормативные определения (почему
знак beat — единственный механизм, теорема о границах, алгебра знаков) — в
дизайн-доке; здесь только «что, где и чем проверяем».

- Дата: 2026-08-31
- Базовая линия: `buffer_package_tests` 115/115 PASS; шаги 0–1 закоммичены
  (`37ec30b`)

## Статус

| Шаг | Содержание | Статус |
|-----|------------|--------|
| 0 | Property-якорь `EarLayoutProperty` | готов |
| 1 | `ChannelLayout.h` + роутинг частот через `channelsAt()` | готов (115/115, `37ec30b`) |
| 2 | `s(t)` = расписание; удалить перестановку буфера | **готов**, 115/115 PASS |
| 3 | Непрерывная рампа; выбросить ритуал планировщика | **готов**, 23/23 PASS |
| 4 | Телеметрия `right − left`; удалить цепочку `isChannelsSwapped` | ожидает |
| 5 | Верификация на устройстве (POCO) | ожидает |

---

## Шаг 2. Ступенчатая раскладка, удалить перестановку буфера

### Что меняется

`layoutSignAt()` наполняется расписанием (до этого — константа `+1` шага 1):

```cpp
inline float layoutSignAt(const BinauralConfig& cfg, float t) {
    return cfg.channelSwapEnabled && channelSwapStateAt(cfg, t) ? -1.0f : 1.0f;
}
```

Из генераторов удаляется бывший механизм: параметр `swapActive`, 10 ветвлений
`if (swapActive)`, чтение `state.channelsSwapped` в 15 вызовах приватных
генераторов, 3 flip-блока `if (segment.swapAfterSegment)`. Выход буфера
становится безусловным: `left = i*2, right = i*2+1`.

### Модель звука после шага 2 (проверено аудитом планировщика)

- Планировщик ВСЕГДА режет SOLID на подсегменты <= 100 мс (обе ветки:
  swap-enabled и swap-disabled). Последний подсегмент перед `T*` берёт хорду
  `earFreqsAt(T*-100мс) -> earFreqsAt(T*)`: частоты линейно проходят унисон
  ровно к `T*`. Фаза непрерывна, амплитуда постоянна — щелчка нет по
  построению; скачок остаётся только у производной частоты (устраняется
  рампой шага 3).
- Смена раскладки — точно в `T*` (узел сетки TIMER / выбранный экстремум
  TREND), а не в конце FADE_OUT.
- Ритуал FADE_OUT/PAUSE/FADE_IN пока ОСТАЁТСЯ (умирает на шаге 3). В сеточном
  режиме он начинается ровно в `T*`; в свободном (тестовые харнессы без
  привязки кривой к оси суток) живёт по собственной ms-оси и с узлами сетки
  постепенно расходится.
- Телеметрия `isChannelsSwapped()` становится производной: знак
  `m_currentBeatFreq` (= right − left после `channelsAt()`). Отдельного
  состояния раскладки больше не существует.

### Карта правок

| Файл | Правка |
|------|--------|
| `include/ChannelLayout.h` | `layoutSignAt` — ступенчатая функция + комментарий «ШАГ 2 МИГРАЦИИ» |
| `include/AudioGenerator.h` | удалить параметр `swapActive` из 6 деклараций |
| `src/AudioGenerator.cpp` | удалить 6 определений параметра; свернуть 10 ветвлений (дублированные циклы scalar/NEON/SSE solid -> один безусловный; 5 хвостовых скалярных и 2 fade-стора SIMD -> безусловные); удалить 15 аргументов `state.channelsSwapped`; удалить 3 flip-блока; удалить SEG_LOG-swap в `generatePackageNeon`; `elapsedMs` -> `[[maybe_unused]]` (единственное использование было в LOGD flip-блоков; параметр уходит при реструктуризации шага 3) |
| `src/BinauralEngine.cpp` | `isChannelsSwapped()` -> `m_currentBeatFreq.load(relaxed) < 0.0f` (атомарно, без lock; §3.6 дизайн-дока) |

Инертное до шага 3/4 (НЕ трогать на шаге 2): поле
`GeneratorState::channelsSwapped`, `resetState` генератора, `initStateForStart`,
resume-релайн `setPlaying`, ритуалы `planPackage`, `result.channelsSwapped`
(всегда false), ветки колбэка `onChannelsSwapped`, `forceImmediateTrendSwap`
(ещё планирует startup-ритуал, но на раскладку звука больше не влияет).

### Тесты: ожидаемые падения и фиксы

Семантика: окно измерения, накрывающее glide через унисон, видит схлопнутый
разброс пары {fL, fR}; пара «до/после» через `T*` не обязана совпадать
поканально — это непрерывный переход, а не скачок на стыке.

Аудит всех 115 тестов выполнен. Ожидаемые падения и фиксы:

| Тест | Причина | Фикс |
|------|---------|------|
| харнесс `runPackageSequenceScenario` | окна, накрывающие glide | пропуск пары при схлопнутом разбросе: `spread < 0.6 × max(spreadB, spreadA)` |
| `MultiSegmentBufferConsistency` | локальная модель `channelsSwapped` со старой семантикой | сбор отсортированных пар {lo, hi} по сегментам + исключение по разбросу |
| `TransitionTest::FullSwapCycleContinuity` | поканальное сравнение через `T*` | сравнение отсортированных пар, допуск 3 Гц |
| `FrequencyJumpDiagnosticTest::FullSwapCycle_DetailedDiagnostic` | 100-мс окна = весь glide | пропуск по разбросу |
| `SolidFadeTransitionTest::checkBoundaryContinuity` | 50-мс окна, допуск 0.05 Гц | пропуск по разбросу |
| `DiagnosticTest::DetailedSegmentBoundaryAnalysis` | доаудит при правке | пропуск по разбросу, если проверяет границы в узлах сетки |

Якорь `EarLayoutProperty` обязан остаться PASS — это проверка главного
утверждения дизайн-дока (паритет смен == расписанию, множество частот ==
{lower, upper} вне окна перехода). Если якорь падает — стоп, утверждение
опровергнуто.

### Что получилось по факту (выполнено)

Прогноз оправдался с двумя дополнениями. Упало **7** тестов (не 6): к
ожидаемым добавились `MultiPackageTest.EngineLevel_AlignedPackages_*` и
`MultiPackageTest.MixedLengthPackages_*` — один и тот же харнесс
`runPackageSequenceScenario` (пары там уже сортировались, не хватало только
пропуска glide) и `ExtremeFrequencyTest.SolidToFadeOutTransitionWithExtremeFreqs`
(граница SOLID→FADE_OUT стоит ровно в `T*`, поэтому окно «конец SOLID» —
это весь glide целиком: разброс 0.06 Гц против 5.30 Гц после).

Замеренная модель звука подтвердилась: на примере
`TransitionTest.FullSwapCycleContinuity` (`beat=10`, `T*=1.0 с`) окна по
500 мс вокруг границ дают ровно ту картину, что предсказана в §«Модель
звука» — последний 100-мс подсегмент перед `T*` линеен от `lower` к `upper`,
унисон ровно в `T*`, дальше постоянная обратная раскладка (включая FADE_OUT).

Семантика правок тестов, хелперы и обоснование порога 0.6 — в дизайн-доке,
§0.1.3. Ключевое: правило пропуска не маскирует дефектов, потому что
настоящий дефект (скачок несущей, разрыв фазы, щелчок) разброс пары
СОХРАНЯЕТ, а не схлопывает. Детектор щелчков в
`runPackageSequenceScenario` — 0 на всех сценариях, в том числе на
«aligned pkg=30000 swap=30000», где граница пакета попадает ровно в `T*`.

### Критерии приёмки

1. `cmake -S core/audio/src/main/cpp/tests -B /tmp/bptest_build && cmake
   --build /tmp/bptest_build -j8 && /tmp/bptest_build/buffer_package_tests` —
   все PASS, включая `EarLayoutProperty`. **115/115 PASS.**
2. Grep-инвариант: в `AudioGenerator.h/.cpp` не осталось `swapActive`.
3. Коммит отдельный. Звук на устройстве до шага 5 не проверяется: ритуал ещё
   жив, провал громкости в `T*` исчезнет только на шаге 3.

---

## Шаг 3. Непрерывная рампа, выбросить ритуал

### Правки

- `layoutSignAt()` -> рампа: `s(t) = s_before·cos(π·u)`,
  `u = (t − (T*−W/2))/W`, `W = 2F + P`; `fadeEnabled=false` -> `W=0` ->
  ступенька. Окно центрируется на ближайшем `T*`; хелпер «ближайшее
  пересечение» (TIMER — арифметика, TREND — бинпоиск по `trendCrossings`).
- `planPackage` -> только нарезка <= 100 мс SOLID (текущая ветка «swap
  disabled» становится безусловной для всех).
- Удалить: `SwapPhase`, `phaseRemainingMs`, `swapAfterSegment`, `justStarted`,
  `trendCurvePosSec`, `lastNormInput`, seek-детектор, `forceImmediateTrendSwap`,
  `initStateForStart`, `resetState` планировщика; `BufferType::FADE_OUT /
  PAUSE / FADE_IN`; `generateFadeBuffer*`; `updatePhasesOverCurve`; 8 полей
  свапа из `GeneratorState` (вкл. `channelsSwapped`); коррекции дрейфа ms-оси
  и resume-релайн в `BinauralEngine.cpp`.
- Тесты: переписать `TrendSwapTest` на чистую функцию; новый ramp-тест
  (в `T*` унисон, IPD непрерывна); удалить/переписать тесты ритуала.

### Критерии

- Все тесты PASS; ramp-тест PASS.
- Слух (шаг 5): пульсация замедляется, встаёт в унисон и возрождается в
  обратную сторону БЕЗ провала громкости.

### Что получилось по факту (выполнено)

Рампа `s(t)` встала в `ChannelLayout.h` ровно как в дизайн-доке:
`s(t) = s_до·cos(π·u)`, окно `W` центрируется на ближайшем `T*`
(TIMER — арифметика, TREND — бинарный поиск по `trendCrossings`). **Важный
нюанс:** `layoutSignAt` берёт `W` ТОЛЬКО из `channelSwapFadeDurationMs` /
`channelSwapPauseDurationMs`; поле `channelSwapFadeEnabled` в расчёте `W` не
участвует. Поэтому чистая ступенька достигается `fadeDurationMs = 0`, а не
`fadeEnabled = false` — тесты это учитывают (`cfg.channelSwapFadeDurationMs = 0`).

Планировщик `planPackage` вырожден в безусловную нарезку ≤100 мс SOLID
(бывшая ветка «swap disabled» теперь единственная). Из `AudioGenerator`
удалены `generateFadeBuffer*` (scalar/NEON/SSE), `updatePhasesOnly`,
`updatePhasesOverCurve`, `struct FadeCurveTable` + `s_fadeCurveTable`; три
фазовых `case`-машины в диспетчерах скаляр/NEON/SSE свёрнуты в `break;`
(метки `BufferType::FADE_OUT/PAUSE/FADE_IN` оставлены, чтобы не ловить
`-Wswitch` — см. ниже про инертные заготовки). Из `BinauralEngine.cpp`
удалены коррекции дрейфа ms-оси и resume-релайн `setPlaying` (якорь кривой
на текущее время суток теперь единственный).

Тесты (`BufferPackageTest.cpp`) переписаны под Шаг 3: ритуальные
`MultiSegment/MultiPackage/Transition/FrequencyJump/SolidFadeTransition/
Diagnostic` выброшены; `TrendSwapTest` заменён чистыми функциональными
`TimerParity` / `TrendParity` / `TrendBeatDeltaSign` / `NearestSwapTime_TIMER`;
добавлены `RampTest.LayoutSignAt_UnisonAtTStar`, `RampTest.LayoutSignAt_CosineShape`,
`RampTest.Audio_UnisonAndNoVolumeDipAtTStar` (в `T*` частоты сходятся в унисон,
IPD непрерывна, несущая — постоянна, провала громкости нет). Якорь
`EarLayoutProperty` остался PASS.

**Отклонение от карты правок (инертные заготовки, сознательно оставлены
до Шага 4 ради зелёной сборки):**
- `BufferType::FADE_OUT / PAUSE / FADE_IN` — enum-значения и `break;` в
  диспетчерах оставлены; планировщик их больше не эмитит.
- 8 полей свапа `GeneratorState` (вкл. `channelsSwapped`) и `resetState`
  генератора — оставлены: на них ещё ссылается планировщик/движок; уходят
  вместе с цепочкой `isChannelsSwapped` на Шаге 4.
- `result.channelsSwapped` / `result.fadePhaseCompleted` в `GenerateResult` —
  оставлены как неиспользуемые поля (не `-Werror`); уходят на Шаге 4 вместе
  с телеметрией.

Слуховая проверка — за Шагом 5 (устройство).

---

## Шаг 4. Телеметрия и Kotlin-плумбинг

- `result.currentBeatFreq` = right − left после `channelsAt()` (после шага 2
  уже фактически так); удалить `result.channelsSwapped`,
  `EngineCallbacks::onChannelsSwapped`, мёртвые ветки колбэка.
- Удалить цепочку `isChannelsSwapped`: `jni.cpp` -> `NativeAudioEngine.kt` ->
  `BinauralStreamImpl.kt` -> `BinauralStream.kt` -> `BinauralStreamManager.kt`
  -> `BinauralPlaybackService.kt` -> `BinauralViewModel.kt`; поле `swapped=`
  в debug-статусе `DebugCommandExecutor.kt`. Индикатор раскладки (если UI
  нужен) — из знака `currentBeatFrequency`.
- Критерии: компиляция `:core:audio` и `:app`; нативные тесты PASS.

---

## Шаг 5. Верификация на устройстве

- `:app:assembleDebug` (arm64-v8a), установка на POCO (192.168.110.98).
- Полный `bash tools/dbgxfade.sh` (все фазы A–K), `dbgsmoke.sh`,
  `dbgmemstress.sh`; ошибки — по сырому логу устройства (устройство опережает
  хост ~120 мс).
- Слух: TIMER `interval=5 с`, `F=1000 мс`, `P=0` — пульсация замедляется,
  встаёт в унисон и возрождается в обратную сторону, без провала громкости.
- Обновить: `tools/trend_parity_check.py`, `tools/fade_tracking_*.py`,
  `docs/analysis_swap_fade_tracking.md` (фейд-анализ больше неприменим),
  таблицу статуса в дизайн-доке.

---

## Порядок коммитов и откаты

- Один шаг — один коммит. Откат = revert коммита шага.
- Шаг 2 изолирован от шага 3: сначала «раскладка вошла в частоты» (бит-в-бит
  тот же звук при выключенном swap), затем «удаление ритуала». Падение якоря
  на шаге 2 — стоп и разбор (см. дизайн-док, Шаг 2).
- Перед коммитом: нативные тесты; перед `git add -A` —
  `git ls-files | grep -i keystore`.

## Открытые вопросы

- `DiagnosticTest::DetailedSegmentBoundaryAnalysis` (interval=1 с, swap=true):
  при правке проверить, не измеряет ли он границы в узлах сетки — тогда
  применить правило разброса.
- `[[maybe_unused]] elapsedMs` — временное, уходит при реструктуризации
  шага 3 (возможно, вместе с параметром).
