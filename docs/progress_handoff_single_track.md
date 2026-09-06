# Прогресс и план: `retune()` на одном треке

Статус на **2026-09-06, ~21:30 — §5.4.5 и §5.4.6 ЗАКРЫТЫ, дерево
КОМПИЛИРУЕТСЯ; §5.5 автоматическая верификация ЗАКРЫТА (dbgscrub 56/0,
dbgstorm 33/0, APK пересобран и установлен). Субъективный прослух —
отдельный ручной шаг (§5.5-б).**

Дальше по порядку: §5.5 — автоматическая верификация ЗАКРЫТА (см. §5.5-а);
ручной субъективный прослух остаётся (§5.5-б).

Краткая история: §5.0–§5.3 закрыты, менеджерская обвязка §5.4.1–§5.4.2
сделана, ветка `retune` — ЖИВОЙ основной путь. §5.4.4 (удаление второго
трека) начат 2026-09-06 ~20:15, прерывался приказом «остановись», возобновлён
и доведён до конца к ~21:00: менеджер, `BinauralStreamImpl`, интерфейс
`BinauralStream` и `PlaybackSpec` очищены от механизма двух треков. §5.4.5
(снятие байтового потолка кольца) и §5.4.6 (удаление JNI-фаз) закрыты к
~21:20.

Базовые документы: `docs/plan_handoff_single_track.md` (дизайн) и
`docs/plan_handoff_single_track_impl.md` (детализация, этапы 1–7).

---

## 1. Компилируемость

**ЗЕЛЁНО** (проверено 2026-09-06 ~21:18, после закрытия §5.4.5 и §5.4.6):
`:core:audio:compileDebugKotlin` — `BUILD SUCCESSFUL`,
`:app:compileDebugKotlin` — `BUILD SUCCESSFUL`,
`:core:audio:externalNativeBuildDebug` (`-PabiFilter=arm64-v8a`) —
`BUILD SUCCESSFUL` (нативный код собирается, warnings — прежние).

Прогон выявил один побочный дефект прошлой сессии: вместе с блоком констант
§5.4.4-а было стёрто поле `private var state = ManagerState.IDLE` (24 ошибки
`Unresolved reference 'state'`). Поле восстановлено рядом с `_managerState`,
с комментарием «пишется и читается только на нити актёра».

Уверенность «зелёный» на момент написания §1 относилась ТОЛЬКО к компиляции;
с тех пор сценарии проиграны на устройстве и закрыли два реальных дефекта
(см. §5.5-а).

---

## 2. Что сделано (по файлам)

### 2.1 `core/audio/.../stream/BinauralStreamImpl.kt`

**Этап 2 (опрос тишины) — ЗАВЕРШЁН.** Мониторинг удалён полностью:

* удалены `FADE_POLL_MS`, `FADE_SETTLE_CEILING_MS`, `FADE_STALL_GRACE_MS`,
  потолок эскалации и вся ветка опроса живого множителя в completion;
* `scheduleFadeCompletion(rampMs, toZero, completion)` теперь — ОДИН
  `postDelayed(rampMs + RAMP_SETTLE_MARGIN_MS)`; `RAMP_SETTLE_MARGIN_MS = 80`
  — ГАРАНТИЯ (`рампа + маржа`), а не измерение.

**`applyShaper` переработан** (этап 1, частично): одно чтение живого
множителя — только для стартовой ординаты; `shaper.replace(cfg, PLAY,
join = true)`; обобщённая equal-power кривая `v(p) = f·cos(θ) + t·sin(θ)`.

**`spec` стал мутабельным**: `@Volatile specState` + `override val spec get()`.

**Этапы 3–5 (сам `retune`) — НАПИСАНЫ ЦЕЛИКОМ:**
`retune()` / `finishRetune()` / `onRetuneReady()` / `abortRetune()` /
`cancelRetuneTimers()` / `doRetuneOnWriter()` / `writeOneChunk()`, константы
`RETUNE_RAMP_MS = 125`, `RETUNE_PACKET_SECONDS = 30`, `RETUNE_DEADLINE_MS =
1000`, интеграция с `pause()` / `stop()` / `resume()`.

**§5.1 — `writerLoop` переподключён на поля (КРИТИЧНО, сделано):**

* локальные `packetBytes` / `offset` / `prefillUntilPlay` / `prefillLatch`
  заменены на `writerPacketBytes` / `writerOffset` / `writerPrefillUntilPlay` /
  `writerPrefillLatch`; в начале цикла они инициализируются из
  `preparedPacketBytes` / `preparedPrefilled`;
* `writerMaxChunkBytes = maxChunkBytes` ставится при входе в цикл — его видит
  и `writeOneChunk()`, который пишет вне цикла;
* в начало цикла, ДО `if (paused)`, добавлены обе ветки перенастройки:
  `paused && pendingRetune != null` (применение на парковке, провала нет) и
  `retuning && retuneGo` (применение в провале);
* в ветке ошибки записи, ПОСЛЕ проверки утилизации и ДО `if (paused)`,
  добавлено `if (retuning) continue` — пауза из `finishRetune` прерывает
  `write()` планово;
* **цель предзаполнения ограничена достижимым**: `prefillGoal =
  minOf(prefillFrames, maxChunkBytes / frameBytes)`. Раньше `prefillFrames`
  (`кольцо − 1 с`) была физически недостижима одной записью (`кольцо −
  UNDERRUN_HEADROOM_MS` = `кольцо − 2 с`), и латч стабильно выгорал по
  таймауту 150 мс. Это закрывает открытый вопрос №3 из прежней версии
  документа — причём для ВСЕХ перемоток, не только для retune.

**§5.2 — баг якоря в `resume()` исправлен:** при `forcedZero` якорь UI-оси
теперь `a0`, а не `normalizeTimeOfDay(a0 + skipSeconds)`. Обоснование: после
перенастройки на паузе пакет начинается ровно с «сейчас», то есть `a0`
(`audibleCurveSeconds()`) уже равен якорю retune, и прибавление Δ унесло бы
указатель графика вперёд на длительность паузы.

**§5.3 — уборка в `releaseInternal()`:** снимаются `pendingRetune`, таймеры
провала и `retuneCallback` (с `false` — менеджер пересоберёт поток, если
утилизация была принудительной, `releaseNow()`). Штатный `stop()` теряет
задание и колбэк сам, поэтому в норме блок молчит.

### 2.2 `core/audio/.../stream/BinauralStream.kt`

В интерфейс добавлен `retune(newSpec, onApplied)` с полным контрактом.

### 2.3 `core/audio/.../stream/BinauralStreamManager.kt` — ПОДКЛЮЧЕНО (§5.4)

**§5.4.1 — `beginTransition()`:** при совпадении частоты дискретизации
сначала `old.retune(spec) { ok -> if (!ok) beginHandoffSequential(spec) }`.
Принято → `sessionSpec = spec` и выход (второй трек не создаётся). Отказ
`retune()` (не PLAYING, смена SR) → прежний `beginSilentSwitch()`.

**§5.4.2 — PAUSED-ветка `onSpecChanged()`:** сначала `cur.retune(spec)` на
месте; при отказе — прежний `pausedSpecDirty = true`. Если ничего значимого не
изменилось (`!needsHandoff`) — писателя не будим вовсе.

**Новый метод `recapturePausedWindow(s)`** — пересъём A0/F0 после
перенастройки на паузе. Без него решение на возобновлении (SOFT / REBUILD)
опиралось бы на окно ВЫБРОШЕННОГО пакета. Состав полей тот же, что в
`capturePauseMetrics()`; если позиция нечитаема — честно поднимаем
`pausedSpecDirty`.

**Предчек №2 (зависимость UI от `ManagerState.HANDOFF`) — ВЫПОЛНЕН, зависимо
нет.** `managerState` читается только `DebugCommandExecutor` (печать имени
состояния в debug-CLI); `listener` нигде не присваивается — колбэк мёртв.
Значит удаление `HANDOFF` (§5.4.4) безопасно для UI.

**§5.4.4 — УДАЛЕНИЕ ВТОРОГО ТРЕКА — ЗАВЕРШЕНО (2026-09-06 ~21:00).** Что
сделано поверх пунктов §5.4.4-а (см. ниже):

* `tryAdvanceQueue()` — сторож `state == RECREATING` первым делом (пока
  CURRENT гаснет, разыгрывать очередь нельзя: спеку подберёт
  `onStreamFullyStopped` из ветки SWITCH). Оба сторожа (`outgoing`,
  `pendingSilentSwitch`), мёртвый блок `wait > 0 … settleRunnable` и блок
  `pendingHandoff → accumulatedMs` удалены; KDoc переписан.
* `beginTransition()` — вызов `logCoherenceVerdict` удалён; фолбэк вместо
  `beginSilentSwitch(old, spec)` → `recreateTrack(spec)`; KDoc переписан под
  retune (основной путь — один трек, второй остался только для смены SR и
  отказа `retune`).
* `beginSilentSwitch()` и `startPendingSilentSwitch()` удалены целиком вместе
  с KDoc.
* `beginHandoffSequential()` переименован в **`recreateTrack(spec)`** с новым
  телом: `accumulatedMs += now − segmentStartWallMs`, затем
  `queue.offer(spec.copy(resumeElapsedMs = accumulatedMs))` и
  `fadeOutCurrent(FadeTarget.SWITCH)`. Часы сессии переносятся ЗДЕСЬ, поэтому
  из `onStreamFullyStopped` (ветка SWITCH) блок переноса удалён.
* `onOutgoingReleased`, `afterOutgoingReleased`, `outgoingReaper`,
  `scheduleOutgoingReaper` удалены; guard в `resumeFromPaused()` на них —
  тоже убран.
* `fadeOutCurrent()` — форма всегда `LINEAR` (перекрытия нет, equal-power
  нечего делить); `onStreamReleased()` — без ветки `outgoing`.
* `retargetFade()` — `FadeTarget.SWITCH -> ManagerState.RECREATING`.
* `onStop()`/`onPause()` — ветки `HANDOFF` переписаны под `RECREATING` и
  упрощены (без `pendingHandoff`/`resetContinuity`); комментарий в `onPlay()`
  поправлен.
* `handleRuntimeError()` — ветка «сбой уходящего потока» удалена.
* Диагностика непрерывности удалена целиком (217 строк):
  `continuityCaptureAllowed`, `logCoherenceVerdict`, `earPair`, `fmt`,
  `axisSecondsFor`, `localTimeOfDay`, `captureContinuity`,
  `enrichForContinuity`, `resetContinuity`. Вместе с ней ушли импорты
  `java.util.Locale`, `kotlin.math.abs`, `FrequencyMath`, `LocalTime`.
* `launchSpec()` — без сброса `pendingHandoff`/`resetContinuity`;
  `launchStream()` — без параметров `fadeInShape`/`fadeInMsOverride` и без
  блока непрерывности; `resetSession()` — без `pendingHandoff`/`resetContinuity`.
* Восстановлено случайно стёртое поле `private var state` (см. §1).

### 2.4 Остальные файлы (§5.4.4-в) — СДЕЛАНО

* **`BinauralStreamImpl.kt`**
  - `stop()` — тело `stopWithSilentHook` инлайнуто обратно; сам метод и весь
    механизм `silent`/once-хука (маркер «ТИШИНА — поднимаем NEXT») удалены.
    Из четырёх мест вызова `silent?.invoke()` осталось ноль.
  - Удалены `isFadedToSilent()` (проверка нужна была только сторожу
    уходящего потока), `setPacketGrowthAllowed()`, поле `packetGrowthAllowed`
    и его проверка в `maybeGrowPacketBuffer()`.
  - Удалён `releaseNow()` — после снятия `outgoingReaper` вызывающих не
    осталось.
  - `prepare()` — убрано восстановление фаз (`engine.setPhases(rl, rr)`) и
    ветка `else if (spec.resumeLeftPhase != null …)`; на их месте —
    комментарий, почему фазы больше не переносятся (перекрытия нет).
  - `getPhases()` (≈2631) оставлен — убирается вместе с JNI в §5.4.6.
* **`BinauralStream.kt`** — удалены объявления `stopWithSilentHook()` и
  `isFadedToSilent()`; KDoc `start()`/`stop()` переписаны (парные плечи
  рампы, без ссылок на `stopWithSilentHook`/`beginSilentSwitch`).
* **`PlaybackSpec.kt`** — удалены поля `resumeLeftPhase`/`resumeRightPhase`;
  KDoc `resumeAnchor` уточнён (от уходящего наследуются только часы сессии).
* Контрольный grep по всему репозиторию на
  `stopWithSilentHook|isFadedToSilent|setPacketGrowthAllowed|releaseNow|
  resumeLeftPhase|resumeRightPhase|HANDOFF` — пуст (кроме
  `WRITER_HANDOFF_GRACE_MS`, не имеющего отношения к хэндоффу, и комментария
  в `tools/dbgscrub.sh`).

### 2.5 §5.4.5 — снятие `MAX_TRACK_BUFFER_BYTES` (Этап 7) — СДЕЛАНО

**Код (всё в `BinauralStreamImpl.kt`):**

* константа `MAX_TRACK_BUFFER_BYTES = 2 МиБ` удалена; в `createAudioTrack()`
  осталось `val size = requested.toInt()` (нижнюю границу `minBuffer` и так
  держит сам `requested`);
* KDoc `TRACK_BUFFER_MS` переписан: почему потолок существовал (два трека в
  одной куче клиента AudioFlinger, замер `-12 NO_MEMORY` на POCO), почему его
  можно снять (трек теперь всегда один) и какой остаётся риск;
* KDoc `WRITE_CHUNK_MS`, `UNDERRUN_HEADROOM_MS`, `MIN_WRITE_MARGIN_MS` и
  `RETUNE_PACKET_SECONDS` переписаны под полное кольцо; `MIN_WRITE_MARGIN_MS`
  честно назван тем, чем он остался — отступом в формуле цели
  предзаполнения ([SEEK_PREFILL_MARGIN_MS]), а не запасом до underrun;
* из `writerLoop()` удалён мёртвый локальный `marginBytes` (в расчёте чанка
  после введения `UNDERRUN_HEADROOM_MS` он не участвовал);
* комментарии в `finalizeStop()` («писатель провисает в write() до ~4.5 с») и
  в блоке снятия трека перед ожиданием писателя пересчитаны на новое кольцо
  и на единственный оставшийся путь со вторым треком (`recreateTrack`).

**Пересчёт пробуждений писателя** (48 кГц, стерео float, 8 Б/кадр;
`чанк = min(8 с, кольцо − 2 с)`, `пробуждений = 3_600_000 / чанк`):

| SR | кольцо было | чанк был | было, /час | кольцо стало | чанк стал | стало, /час |
|---|---|---|---|---|---|---|
| 48 000 | 5.46 с (2 МиБ) | 3.46 с | 1040 | 10 с (3.84 МиБ) | 8 с | **450** |
| 44 100 | 5.94 с (2 МиБ) | 3.94 с | 913 | 10 с (3.53 МиБ) | 8 с | **450** |
| 22 050 | 10 с (1.76 МиБ) | 8 с | 450 | без изменений | 8 с | 450 |

Запас до underrun — 2 с на всех SR (был 2 с на младших и 1 с на старших).
Цель предзаполнения при перемотке: `min(кольцо − 1 с, чанк)` = 8 с — то есть
одной записью достигается (открытый вопрос №1 закрыт и для нового кольца).

Критерий Этапа 7 «пробуждений/час не больше нынешних» выполнен с запасом:
−57 % на 48 кГц, −51 % на 44.1 кГц, без изменений ниже.

### 2.6 §5.4.6 — JNI-фазы — СДЕЛАНО

Удалено целиком (проверка вызывающих — grep по всему `src/` пуст):

* `BinauralStreamImpl.getPhases()` — вызывающих не было с §5.4.4;
* `NativeAudioEngine.getCurrentPhases()` / `setPhases()` и
  `external nativeGetCurrentPhases` / `nativeSetPhases`;
* `jni.cpp` — `…_nativeGetCurrentPhases` и `…_nativeSetPhases` (привязка по
  имени, таблицы `RegisterNatives` нет);
* `BinauralEngine.h` / `.cpp` — объявления и тела `getCurrentPhases()` /
  `setPhases()`. `m_state.leftPhase/rightPhase` остались: это рабочее
  состояние самого генератора.

Проверки перед удалением: нативные тесты (`core/audio/src/main/cpp/tests`)
этих методов не касаются (grep пуст); `tools/` содержит лишь сгенерированную
копию дерева (`tools/swap_fade_probe/out/…`), основным билдом не собираемую.
Нативная сборка после правки — зелёная.

### 2.7 НЕ ИЗМЕНЕНО

Мёртвые параметры рампы `fadeInMsOverride` / `fadeOutMsOverride` (см. §6,
п. 6) — вне обязательного плана, оставлены.

---

## 3. Отклонения от исходного плана (сознательные)

| Пункт плана | Решение | Почему |
|---|---|---|
| §3.2: `track.pause()` в t0, чтобы прервать write | **pause перенесена в t0+F+M** | VolumeShaper движется КАДРАМИ микшера: пауза на середине рампы заморозила бы шейпер на ненулевом множителе → щелчок. Побочный выигрыш: пауза в нижней точке сама прерывает заблокированный `write()` — отдельный механизм пробуждения писателя не нужен |
| §3.2: писатель просыпается по `retuneGoLatch` | **Флаг `retuneGo`, без латча** | Писатель обязан ПРОДОЛЖАТЬ питать трек во время спада (иначе underrun). Он просто продолжает обычный цикл, а после `pause()` отрабатывает ветку ошибки записи → `if (retuning) continue` → `doRetuneOnWriter()` |
| Этап 1: «долгоживущий шейпер, never close, база 1.0» | **ОТКАЗАНО, оставлен идиом «база 0 + closeShaper»** | У VolumeShaper нет колбэка завершения, и часть реализаций может авто-закрываться. Пока шейпер — единственный держатель нуля, его тихая смерть означала бы вспышку полноамплитудного PCM из кольца. Существующий идиом обкатан на устройстве |
| §3.2: «наполнить кольцо» после регенерации | **`writeOneChunk()` — ровно одна запись до play()** | Кольцо пусто после flush; немедленный play() = underrun |
| `getCurrentPhases`/`setPhases` | **Удалены в §5.4.6** | Вызывающих не осталось: перекрытия нет, фазы переносить некуда — аккумуляторы одни и те же |
| Менеджер: «сначала retune, иначе `pausedSpecDirty`» | **Добавлен `!needsHandoff` — ранний выход** | Пока `retune` не подключён к фабрике спек, лишние пробуждения писателя на каждом SETTINGS-пуше были бы чистой потерей |

---

## 4. Краевые случаи, закрытые в коде

* пауза во время провала → провал отменяется, задание живёт, применяется на
  парковке (`pause()` + `retuneStartAtZero`);
* стоп во время провала → задание теряется, обычная утилизация (`stop()`);
* шторм → перезапись цели без новых таймеров (`retune()`);
* писатель не отозвался → `RETUNE_DEADLINE_MS` → `abortRetune(false)` →
  менеджер идёт в `recreateTrack()`;
* flush/regenerate упал → `abortRetune(false)` (конфиг к этому моменту ещё
  НЕ применён: flush идёт первым) → тот же аварийный путь;
* перенастройка на паузе → без провала вообще, `resume()` встаёт в кадр 0
  свежего пакета, UI-якорь не сдвигается на Δ (§5.2);
* принудительная утилизация во время провала (`abort()`/повторный `stop()`) →
  колбэк `false` из `releaseInternal()`;
* смена частоты дискретизации → `retune()` сразу `false` → пересоздание трека.

---

## 5. Что осталось (порядок строгий)

### 5.4.4. УДАЛЕНИЕ ВТОРОГО ТРЕКА — **ЗАКРЫТО 2026-09-06 ~21:00**

Менеджер, `BinauralStreamImpl`, интерфейс `BinauralStream` и `PlaybackSpec`
очищены. `:core:audio:compileDebugKotlin` и `:app:compileDebugKotlin` —
зелёные. Подробная опись сделанного — в §2.3 (менеджер) и §2.4 (остальные
файлы); ниже — только исторические пометки, которые ещё имеют смысл.

#### 5.4.4-а. Что было сделано в менеджере ДО остановки (сохранено)

1. **`ManagerState.HANDOFF` → `ManagerState.RECREATING`** (с KDoc: состояние
   описывает РАЗРЫВ при пересоздании трека, а не перекрытие двух треков;
   штатная смена настроек сюда не приходит — она в RUNNING через `retune`).
2. **Удалён весь блок констант** `OUTGOING_*`, `HANDOFF_STORM_*`,
   `HANDOFF_RETRY_*`, `COHERENT_DETUNE_HZ`, `TRANSITION_FADE_MS`,
   `ZERO_OVERLAP_LEG_MS`.
3. **Удалены поля** `outgoing`, `pendingAfterOutgoing`, `outgoingStartWallMs`,
   `settleAtMs`, `settleScheduled`, `stormDetected`, `settleRunnable`,
   `handoffRetryScheduled/Attempts/Runnable`, `scheduleHandoffRetry()`,
   `pendingSilentSwitch`; KDoc слота `current` переписан под инвариант
   ОДНОГО потока.
4. **Удалены поля непрерывности** `switchElapsedMs`, `switchLeftPhase`,
   `switchRightPhase`, `pendingHandoff`, `handoffStartWallMs`.
5. **`release()`** упрощён: убрана утилизация `outgoing`/`pendingSilentSwitch`
   и снятие их колбэков; осталось `queue.clear(); current?.stop(...)`.
6. **`isActiveState()`** переписан: `RUNNING || FADE_IN || RECREATING`.
7. **`requestHandoff()`** упрощён: быстрый путь `setVolume` через
   `isActiveState()`; упоминание `outgoing` в логе убрано.

#### 5.4.4-г. Решения, принятые по ходу (не заглядывая в план)

* `HANDOFF` не удалён, а ПЕРЕИМЕНОВАН в `RECREATING`: аварийный путь
  `recreateTrack` всё равно нуждается в собственном состоянии автомата, но
  семантика теперь «разрыв при пересоздании», а не «два трека в полёте».
* `FadeTarget.SWITCH` сохранён (имя по-прежнему точное).
* Штормовая коалесценция (`settle*`/`stormDetected`) удалена вместе с
  `HANDOFF_STORM_*` — она была мёртвой с волны 5 (`settleAtMs=0`).
* `accumulatedMs` в `recreateTrack` переносится через
  `spec.copy(resumeElapsedMs = …)` — это же подхватывает `prepare()`
  (`setPlaybackStartTime(now − resumeElapsedMs)`, `preserveTimeline=true`).
* `recreateTrack()` — итоговое тело (перенос часов + пересоздание из очереди):
  ```kotlin
  private fun recreateTrack(spec: PlaybackSpec) {
      // Часы сессии: новый движок стартует с elapsed=0 — переносим накопленное
      accumulatedMs += System.currentTimeMillis() - segmentStartWallMs
      queue.offer(spec.copy(resumeElapsedMs = accumulatedMs))
      StreamLogger.d(TAG, "recreateTrack spec#${spec.serial}: фейд-аут CURRENT, " +
          "загрузка после полного релиза (разрыв звука ожидаем)")
      fadeOutCurrent(FadeTarget.SWITCH)
  }
  ```
  (`fadeOutCurrent` сам зовёт `retargetFade(SWITCH)` → RECREATING; подъём —
  из `onStreamFullyStopped` ветки SWITCH через `queue.poll()`.)


### 5.4.5. Этап 7
Снять `MAX_TRACK_BUFFER_BYTES` (2 МиБ потолок кольца был обходом ДВУХ треков в
одной куче AudioFlinger) → вернуть кольцо 10 с; пересчитать ожидаемую частоту
пробуждений писателя.

### 5.4.6. JNI
`getCurrentPhases` / `setPhases` — удалить, если вне хэндоффа не нужны
(проверить `tools/` и тесты).

### 5.5. Верификация (по этапам плана) — **АВТОМАТИЧЕСКАЯ ЧАСТЬ ЗАКРЫТА 2026-09-06 ~21:30**

* сборка `:app:assembleDebug -PabiFilter=arm64-v8a`, установка на POCO — **СДЕЛАНО**
  (два пересбора после двух правок, см. §5.5-а);
* `tools/dbgscrub.sh` V1–V9 и `tools/dbgstorm.sh` — **СДЕЛАНО**, см. §5.5-а
  (dbgscrub 56/0, dbgstorm 33/0);
* субъективный прослух — **РУЧНОЙ ШАГ, НЕ ВЫПОЛНЕН**, см. §5.5-б;
* маркеры в логе: `retune spec#N -> spec#M: приседание` →
  `doRetuneOnWriter: пакет перестроен` → `retune spec#N: ПОДНЯТ`;
  для паузы — `onSpecChanged: PAUSED retune … принят` →
  `recapturePausedWindow: A0=… F0=…` — **ПОДТВЕРЖДЕНЫ** на устройстве (см. §5.5-а).

#### 5.5-а. Автоматическая верификация — результаты

APK пересобран **дважды** по ходу §5.5 (после каждой из двух правок) и
переустановлен на POCO (`192.168.199.165:5555`, `com.binauralcycles.debug`).
Дерево с двумя правками — то же, что сейчас на устройстве.

**Инструменты (оба обновлены под retune-путь):**
* `tools/dbgscrub.sh` V1–V9 — **ИТОГО: PASS=56 FAIL=0**;
* `tools/dbgstorm.sh` (штормы скраб/пресет/настройки, по 10 жестов @0.12 с) —
  **ИТОГО: PASS=33 FAIL=0**;
  гарантии корректности зелёные: `recreateTrack=0` (второго трека нет),
  `beginOverlapSwitch удалён=0`, последовательного хэндоффа нет, сторож
  инварианта молчит, underrun в стартовом окне нет, счётчики памяти пакета
  стабильны (`holders=1 peak=1 oomHalvings=0`).

**Два реальных дефекта, найденных и закрытых ВО ВРЕМЯ верификации:**

1. **V5/V9: `INVARIANT НАРУШЕН` на полном сдвиге оси.**
   Причина: `doRetuneOnWriter()` брал базу якоря из `engine.getCurrentTimeOfDay()`,
   который считает от `System.currentTimeMillis()` и **игнорирует debug-часы
   `totime`**. Под виртуальными часами нативный и Kotlin-клоки расходились на
   величину сдвига оси → нарушение инварианта менеджера.
   Исправление: база якоря = `realTimeOfDaySeconds()` (Kotlin, debug-часы-осведомлён),
   как у инварианта `normalizeTimeOfDay(realTimeOfDaySeconds()+scrub)`;
   `oldScrub` теперь только для лога (вычитать не нужно).
   Файл: `core/audio/.../stream/BinauralStreamImpl.kt` (`doRetuneOnWriter`,
   около строк 1563–1613).
   Проверено: V5 PASS (`ось обернулась на 23:58`, `после сценария ось вернулась
   на реальное now`), V9 PASS.

2. **V9: смена пресета стирала сдвиг, retune не происходил вовсе.**
   Причина: `resetScrub()` — «тихий»: очищал состояние скраба, но реальную
   перенастройку оставлял на «следующее изменение настроек». Если целевой
   пресет аудио-идентичен (`updateConfig` дедуплицирует через
   `needsHandoff`/`audioEquals`), реального `retune` не случалось — движок
   оставался на старой (+6 ч) оси навсегда, инвариант сыпал.
   Исправление: `resetScrub()` сам вызывает `onSpecChanged(SpecReason.SETTINGS)`,
   когда `scrubNeedsRealignment` (выставлен по оси ЖИВОГО потока).
   Файл: `core/audio/.../stream/BinauralStreamManager.kt` (`resetScrub`,
   около строк 460–473).
   Проверено: V9 PASS (смена пресета возвращает ось на реальное now).

Маркеры лога из плана подтверждены живьём на POCO: последовательность
`приседание → пакет перестроен → ПОДНЯТ` (один трек, «второго трека не было»);
на паузе — `onSpecChanged: PAUSED retune … принят` →
`recapturePausedWindow: A0=… F0=…`.

#### 5.5-б. Субъективный прослух — РУЧНОЙ ШАГ (НЕ ВЫПОЛНЕН)

Требует ушей человека; автоматикой не покрывается. Процедура (debug-CLI через
`am broadcast -a com.binauralcycles.debug.COMMAND -p com.binauralcycles.debug --es cmd '…'`):

1. **Смена пресета в игре** — `presets`, `next`; ожидать провал ≈ 230–310 мс
   **без щелчка**.
2. **Смена пресета на паузе** — `next` на паузе; ожидать мгновенную смену
   **без провала**.
3. **Пауза/стоп внутри провала** — запустить смену, внутри провала
   `next`→пауза; ожидать чистого прерывания (провал отменяется, задание живёт).
4. **Скраб-драг** — `pscrub <HH:MM>` / `pscrubreset`; ожидать плавного сдвига
   оси без разрывов и щелчков.

После прослуха — закрыть этот пункт и снять пометку «РУЧНОЙ ШАГ» выше.

---

## 6. Открытые вопросы / риски

1. ~~`prefillFrames` недостижима одной записью~~ — **ЗАКРЫТО** в §5.1
   (`prefillGoal`). Надо подтвердить на устройстве, что предупреждение
   «перемотка не уложилась в 150мс» ушло.
2. ~~`engine.getCurrentTimeOfDay()` как база якоря в `doRetuneOnWriter`~~ —
   **ЗАКРЫТО** в §5.5-а (дефект №1): база заменена на
   `realTimeOfDaySeconds()` (debug-часы-осведомлён), совпадает с базисом
   инварианта менеджера.
3. Поведение `track.flush()` на POCO/Android 13 (предчек №5 плана, не снят).
   Отказ уже обрабатывается (abort → пересборка), но частота отказа неизвестна.
4. Поведение VolumeShaper после завершения кривой (держит последний множитель
   или авто-закрывается) — вопрос остался открытым, поэтому выбран безопасный
   идиом §3. Если когда-нибудь захочется долгоживущий шейпер — сначала замер
   `getVolume()` через 1 с после конца рампы на устройстве.
5. Гонка колбэков при шторме НА ПАУЗЕ: второй `retune` перезаписывает
   `retuneCallback`, а пост первого `doRetuneOnWriter` берёт поле
   `retuneCallback` (то есть новейший). Итог — один вызов, но не обязательно
   тот, который заказывал первый жест. Безвредно (оба колбэка делают одно и
   то же), но если понадобится различать — привязывать колбэк к спеке.
6. **Мёртвые параметры рампы** (после §5.4.4): `fadeInMsOverride` у
   `start()` и `fadeOutMsOverride` у `stop()` больше НИКТО не передаёт —
   все вызовы идут с дефолтами. Кандидаты на удаление в §5.4.5/§5.4.6.
   `FadeShape.EQUAL_POWER` ещё жив: им пользуются рамки самого `retune`
   (спад/подъём в `BinauralStreamImpl`), это НЕ кроссфейдный остаток.
7. **Урок §5.4.4-а**: удаление «блока констант» по диапазону строк стёрло
   поле `private var state`, стоявшее рядом (24 ошибки компиляции). При
   массовых удалениях удалять поимённо, не диапазоном.
8. ~~`tools/dbgscrub.sh` ссылается в комментарии на `stopWithSilentHook`~~ —
   **ЗАКРЫТО**: инструмент переписан под retune-путь в §5.5; grep по
   `stopWithSilentHook` в `tools/dbgscrub.sh` пуст.

---

## 7. Сводка состояний автомата (после §5.4.4)

* `RUNNING`/`FADE_IN` — штатная игра; смена настроек через `retune()`
  БЕЗ смены состояния.
* `RECREATING` (бывш. `HANDOFF`) — только аварийный путь `recreateTrack`:
  смена SR или отказ `retune`. CURRENT гасится, очередь разыгрывается в
  `onStreamFullyStopped` (SWITCH). Стоп в этом состоянии гасит всё;
  пауза — мягко замораживает CURRENT.
* `PAUSED`/`FADE_OUT_*` — без изменений; `onSpecChanged` в PAUSED сначала
  пробует `retune` на замороженном потоке, при отказе `pausedSpecDirty`.
