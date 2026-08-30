# Анализ: щелчок и обрыв воспроизведения при возврате из долгого фона

**Симптом:** приложение долго работает в фоне, затем пользователь открывает
его — воспроизведение «резко с щелчком прерывается».

**Область анализа:** Kotlin-слой жизненного цикла
(`MainActivity` → `BinauralViewModel` → `BinauralPlaybackService` →
`BinauralStreamManager` → `BinauralStreamImpl`) и нативная ось времени
(`BinauralEngine`, `AudioGenerator`, `BufferPackagePlanner`).
**Не** входят: качество самого PCM-рендера и сплайн-интерполяция кривой.

**Метод:** статический анализ кода + трассировка единственного актора
(`HandlerThread("BinauralStreamActor")`). Результат — три независимых
механизма, которые накладываются друг на друга. Ранжированы по вкладу.

---

## 1. Главный результат

Симптом не один баг, а конъюнкция трёх. Все три включаются именно
«долгим фоном», но по разным причинам:

| # | Механизм | Что даёт «долгий фон» | Вклад в симптом |
|---|---|---|---|
| **A** | Необновляемый WakeLock | TTL истекает через 12 мин и больше не продлевается | **обрыв** (undrrun → тишина) |
| **B** | Шторм реконфигурации при рестарте Activity | OS уничтожает Activity в фоне → пересоздание | **щелчок** (3–4 кроссфейда подряд) |
| **C** | Wall-clock ось расписания перестановки каналов | расхождение часов с аудио накапливается при простое | **щелчок** на стыке пакетов |

**A** объясняет, почему звук *прерывается*; **B** и **C** — почему это
сопровождается *щелчком* и почему заметно именно в момент открытия
приложения.

---

## 2. Механизм A. WakeLock с TTL, который никто не продлевает

`BinauralStreamManager.kt:906`

```kotlin
private fun acquireWakeLock() = synchronized(wakeLockLock) {
    ...
    // TTL обязан покрыть запись пакета целиком (до 60 мин)
    val ttlMs = maxOf(10 * 60 * 1000L, bufferIntervalMs.toLong() + 120_000L)
    if (wakeLock?.isHeld != true) {
        wakeLock?.acquire(ttlMs)          // <-- acquire(timeout), не acquire()
    }
}
```

Два факта, которые вместе образуют баг:

1. `WakeLock.acquire(timeout)` — **самораспускающийся** лок. По истечении
   TTL система его снимает.
2. `updateWakeLock()` (`:906`) вызывается **только на переходах состояния**
   (`:263, 479, 599, 666, 804, 826, 839, 896`). Пока менеджер стоит в
   `RUNNING` — вызовов нет. А `acquireWakeLock()` повторно не захватывает
   лок: `if (wakeLock?.isHeld != true)`.

**Итог:** при дефолтном интервале 10 мин TTL = 12 мин. Через 12 минут
после старта воспроизведения лок снят и **никогда не берётся снова**.
Дальше всё зависит от того, удерживает ли систему сам аудиовывод:

- На «чистом» Android AudioFlinger/FastMixer косвенно не даёт CPU уснуть,
  и последствия могут не проявиться часами.
- При **Doze / экономии заряда / агрессивном OEM-тюнинге** вейклоки
  приложений игнорируются — CPU засыпает между пробуждениями писателя.

Писатель будит систему раз в `WRITE_CHUNK_MS = 2000` мс
(`BinauralStreamImpl.kt:51`), внутренний буфер трека — `TRACK_BUFFER_MS =
3000` мс (`:38`). Запас по времени — ~1 с. Любая заминка CPU длиннее секунды
→ **underrun**: PCM-поток обрывается на произвольном значении отсчёта.
Именно так физически звучит «щелчок»: разрыв непрерывности waveform.

Дальше — развилка:

- Если трек выжил: при следующем `write()` звук возобновляется с другого
  значения отсчёта → щелчок и заметный провал. Пользователь открывает
  приложение, CPU просыпается — и **в этот момент** он слышит последствия.
- Если сработёл `onRuntimeError` (`write failed` / `generate failed`,
  `BinauralStreamImpl.kt:627/612`) → `handleRuntimeError()`
  (`BinauralStreamManager.kt:871`) гасит поток и уходит в `IDLE`.
  Комментарий обещает «retryable», но **повторного запуска нет нигде** —
  воспроизведение встало окончательно.

> Отдельно: `PARTIAL_WAKE_LOCK` без `ON_AFTER_RELEASE` и без продления —
> единственный в коде механизм удержания CPU. Больше его не держит никто.

---

## 3. Механизм B. Шторм реконфигурации при пересоздании Activity

### 3.1 Почему это происходит именно «после долгого фона»

Долгий фон → система выгружает Activity (а часто и весь процесс, кроме
foreground service). Пользователь тапает по иконке → `MainActivity.onCreate`
→ новая ViewModel → `init { bindToService(); loadPreferences(); ... }`
(`BinauralViewModel.kt:196`).

`BinauralUiState` стартует с **дефолтами, а не с сохранёнными значениями**
(`:72-90`):

```kotlin
val volume: Float = 0.7f,
val sampleRate: SampleRate = SampleRate.LOW,     // 22050
val bufferGenerationMinutes: Int = 10,
val channelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
val activePreset: BinauralPreset? = null,
```

`bindService()` на уже живущем сервисе доставляет `onServiceConnected`
**до** того, как `getPresets()` / `getSampleRate()` / `getVolume()` из
DataStore успевают отдать первое значение (чтение с диска — suspend). Код это
признаёт прямо:

```kotlin
// BinauralViewModel.kt:169
// Всегда вызываем updateAudioConfig - это обновит конфиг даже если activePreset ещё не загружен
// (в этом случае будет использован дефолтный конфиг, который потом заменится при загрузке пресета)
```

### 3.2 Что реально улетает в живой движок

`onServiceConnected` (`:151-196`), всё подряд, без дедупликации:

| Вызов | Значение в этот момент | Эффект на акторе |
|---|---|---|
| `updateAudioConfig()` | **дефолтный** конфиг, `activePreset == null` | `requestHandoff` → полный кроссфейд |
| `setFrequencyUpdateInterval(10 * 60 * 1000)` | дефолт 10 мин | только поле |
| `setVolume(0.7f)` | **дефолт**, а не сохранённая громкость | live-смена базы |
| `setSampleRate(SampleRate.LOW)` | **дефолт 22050** | `requestHandoff` → кроссфейд со сменой SR |
| `tryAutoResumeOnAppStart()` | см. 3.4 | — |

Дальше доезжают коллекторы из `loadPreferences()` — и **снова** то же самое,
но уже с настоящими значениями:

- `getPresets()` → `updateAudioConfig()` (`:236`) → ещё один `requestHandoff`
- `getSampleRate()` → `setSampleRate(real)` (`:257`) → ещё один, если SR ≠ LOW
- `getBufferGenerationMinutes()` → `setFrequencyUpdateInterval(real)` (`:275`)
- `getVolume()` → **только UI** (`:287`), в сервис не уходит — поэтому
  громкость остаётся той, что «прилетела» дефолтной в `onServiceConnected`

### 3.3 Цепочка на акторе

`updateConfig` (`:121`) не дедуплицирует: `onSpecChanged` → `requestHandoff`
(`:292`, `:300`). Быстрый путь есть только для `RUNNING`:

```kotlin
if (state == ManagerState.RUNNING && cur != null && cur.spec.audioEquals(spec)) {
    cur.setVolume(spec.volume); return
}
```

После первого же `beginHandoff()` состояние становится `HANDOFF` — и быстрый
путь перестаёт работать для всех последующих спк. Дальше:

```
updateConfig(default)   → RUNNING  → beginHandoff()          [handoff #1]
                                     captureContinuity()
                                     prepare()  ← 212 МБ direct buffer, на нити актора
                                     start() + fadeOutCurrent(SWITCH)
setSampleRate(LOW)      → HANDOFF  → rearmNextIfStale()
                                     n.lifecycle == PLAYING
                                     → promoteNextToCurrent()   ← NEXT повышен НА ПОЛУФЕЙДЕ
                                     → хвост очереди новее → beginHandoff()  [handoff #2]
presets приехали        → HANDOFF  → rearmNextIfStale() → promote + beginHandoff  [#3]
getSampleRate() приехал → HANDOFF  → rearmNextIfStale() → promote + beginHandoff  [#4]
```

Четыре `prepare()` за ~1 с. Каждый `prepare()` (`BinauralStreamImpl.kt:98`)
синхронно на нити актора делает:

```kotlin
// :146
samplesPerChannel = minOf(
    rate.toLong() * bufferIntervalMs / 1000L,   // 44100 * 600 = 26 460 000
    maxSamplesByMinutes,
    maxSamplesByBytes                            // 1 ГБ / 8
).toInt()
directBuffer = allocateDirect(samplesPerChannel * 2 * 4, rate)   // 211 680 000 байт
```

**212 МБ** `ByteBuffer.allocateDirect` на каждый поток. Три-четыре потока
живут одновременно (старый гаснет 250 мс + ожидание писателя до
`WRITER_EXIT_WAIT_MS = 3500` мс) → пик нативной памяти под 700 МБ.

### 3.4 Почему это щелкает

1. **Три одновременно звучащих AudioTrack.** EQUAL_POWER-кроссфейд
   (`sin²+cos²=1`) математически корректен ровно для **двух** потоков.
   На трёх сумма огибающих превышает 1 → клиппинг на сведении.
2. **`promoteNextToCurrent()` (`:462`) повышает NEXT, не дожидаясь конца его
   fade-in.** В `rearmNextIfStale()` (`:378`) это штатный путь:
   `if (n.lifecycle == PLAYING) { promoteNextToCurrent(); return }`.
   Повышенный поток продолжает свой fade-in, но тут же `beginHandoff()`
   вызывает на нём `fadeOutCurrent(SWITCH)` → `stop()` с
   `cur = currentMultiplier()` ≈ 0.2–0.4 → `applyShaper` идёт по ветке
   «замена активного шейпера» (ФИКС 1.3, `BinauralStreamImpl.kt:433-462`):
   ```kotlin
   val live = old.volume
   val base = userVolume * fromC
   audioTrack?.setVolume(base)     // база снижена
   try { old.close() } catch (...) {}   // возврат к base
   ```
   Между `setVolume(base)` и `createVolumeShaper().apply(PLAY)` мгновенная
   эффективная громкость = `base · oldShaperVolume = userVolume · fromC²`.
   Для `fromC = 0.3` это провал в 3.3 раза. Успеет ли AudioFlinger
   отрендерить этот микрокадр — вопрос гонки, но это ровно тот тип
   разрыва, который слышен как щелчок.
3. **`prepare()` блокирует нить актора на сотни мс** (аллокация 212 МБ +
   `generateBufferDirect` первого пакета). Все таймеры фейдов — это
   `controlHandler.postDelayed(..., dur + FADE_GUARD_MS)` на той же нити.
   Поэтому `FADE_GUARD_MS = 60` мс и `DEFAULT_FADE_MS = 250` мс перестают
   быть гарантией: `fadeCompletion` уже идущего фейда не исполнится вовремя,
   окно перекрытия кроссфейда размывается.
4. **`setVolume(0.7f)` из `onServiceConnected` применяется live** —
   скачок базовой громкости поверх идущего кроссфейда.

### 3.5 Побочный дефект в `allocateDirect`

```kotlin
// BinauralStreamImpl.kt:245
private fun allocateDirect(sizeBytes: Int, rateHz: Int): ByteBuffer? {
    val minSize = maxOf(audioTrackBufferSize, rateHz * 2 * 4)
```

`allocateDirect` вызывается на шаге 3 `prepare()`, а `audioTrackBufferSize`
заполняется на шаге 4 в `createAudioTrack()` (`:224`). В момент вызова он
**ещё 0**, поэтому пол при OOM-уполовинивании = `rateHz * 2 * 4` (1 с аудио)
вместо `audioTrackBufferSize` (3 с). На устройстве с нехваткой памяти буфер
схлопывается до 1 с при 3-секундном буфере трека — прямой путь к underrun.

---

## 4. Механизм C. Ось расписания перестановки каналов — wall-clock, а не sample-driven

### 4.1 Две оси времени, которые обязаны совпадать, но не обязаны

В движке **две** независимые оси:

| Ось | Источник | Где живёт |
|---|---|---|
| Время кривой | **sample-driven** | `m_curveTimeSeconds += actualDurationSeconds * timeScale` |
| Расписание swap | **wall-clock** | `m_elapsedSeconds = (system_clock::now() - m_playbackStartTimeMs) / 1000` |

```cpp
// BinauralEngine.cpp:539
void BinauralEngine::updateElapsedTime() {
    const int64_t startTime = m_playbackStartTimeMs.load(std::memory_order_relaxed);
    if (startTime > 0) {
        auto nowMs = ...system_clock::now()...;
        int elapsed = static_cast<int>((nowMs - startTime) / 1000);
        m_elapsedSeconds.store(elapsed, std::memory_order_relaxed);
    }
}
```

Дальше она уходит в генератор как база оси расписания:

```cpp
// BinauralEngine.cpp:579 — читается ДО updateElapsedTime() (вызов на :585)
const int64_t elapsedMs = static_cast<int64_t>(m_elapsedSeconds.load(...)) * 1000;
...
// AudioGenerator.cpp:2062
currentElapsedMs = elapsedMs + (static_cast<int64_t>(currentSample) * 1000) / m_sampleRate;
```

По `currentElapsedMs` планировщик решает, где ставить
`swapAfterSegment` (`AudioGenerator.cpp:2052-2057`).

### 4.2 Следствия

1. **Отставание на один пакет.** `elapsedMs` читается до
   `updateElapsedTime()`, то есть берётся значение, записанное при генерации
   *предыдущего* пакета. При интервале 10 мин ось расписания постоянно
   сдвинута на 10 мин относительно звучащего момента. Пока сдвиг
   постоянный — слышимой проблемы нет, но любое возмущение становится
   скачком.
2. **Неустранимое расхождение при простое.** Если CPU спал Δ секунд,
   `nowMs - startTime` ушёл вперёд на Δ, а реально отрендерено столько же
   сэмплов. Следующий пакет получает `elapsedMs` на Δ больше → ось
   расписания **прыгает вперёд**, и запланированный swap либо пропускается,
   либо срабатывает немедленно на стыке пакетов.
3. **TREND-режим усугубляет.** `BufferPackagePlanner::planPackage`
   сравнивает `callerAdvance` и `audioAdvance` и при
   `|Δ| > 2.0` считает это seek'ом, ресинхронизируя внутреннюю позицию
   (`trendCurvePosSec`). Плюс `forceImmediateTrendSwap` — одноразовая
   коррекция чётности на `justStarted`. После прыжка wall-clock оба
   механизма срабатывают не там, где ожидалось.

Прыжок swap-состояния на стыке пакетов = мгновенная перестановка каналов
без фейда (если фейд выключен) или с фейдом, начатым не в той фазе —
**щелчок**.

### 4.3 Тот же корень у целочисленного якоря непрерывности

```kotlin
// BinauralStreamImpl.kt:663
fun getCurrentCurveTimeSeconds(): Float = nativeEngine?.getCurrentTimeOfDay()?.toFloat() ?: 0f
```

`nativeGetCurrentTimeOfDay` в JNI возвращает `jint`, а
`PlaybackSpec.resumeCurveTimeSeconds` — `Int` (`:32`). На каждом
`captureContinuity()` (`:745`) → `enrichForContinuity()` (`:759`) дробная
секунда позиции по кривой отбрасывается. На спокойной кривой это десятые
герца; при `timeScale` (debug) до 60× — уже слышимый шаг. Отдельно
`getElapsedMs()` = `getElapsedSeconds() * 1000L` — тоже целые секунды.

---

## 5. Что НЕ является причиной (проверено и снято)

- **`stop()` / `finalizeStop()`** (`BinauralStreamImpl.kt:292`, `:337`) —
  фейд идемпотентен, ФИКС 1.2 гасит базу в момент, когда рампа уже в нуле.
  Хлопка **не** даёт.
- **`AUDIOFOCUS_LOSS`** → `audioEngine?.stop()` без фейда. Теоретически
  источник хард-стопа, но потери фокуса «на возврате из фона» сценарий не
  подразумевает; оставляем только как гипотезу второго порядка.
- **Гонка `updateCurrentFrequencies()`** с нитью писателя.
  `getFrequenciesAtCurrentTime()` берёт `shared_lock(m_configMutex)`;
  `getCurrentTimeOfDaySeconds()` — атомарные `m_uiAnchorWallMs` /
  `m_uiLastUiTimeSec`. Исключение — `getCurrentPhases()`
  (`BinauralEngine.cpp:436`) без лока, но это best-effort чтение двух
  выровненных `float`, на ARM атомарно.
- **Переполнение `Int` в расчёте `samplesPerChannel`** — нет: стоит
  `rate.toLong() * bufferIntervalMs / 1000L`.
- **`startForegroundService()` из `onCreate` без action** — `onStartCommand`
  для `null`-action ничего не делает, `START_STICKY` возвращается.
- **`tryAutoResumeOnAppStart()`** — защищён `_telemetry.value.isPlaying`,
  который из статического `BinauralPlaybackService.isPlaying` заполняется
  синхронно в `init`. Ложного `resume` не происходит.

---

## 6. Диагностика: чем подтвердить на устройстве

```bash
adb shell setprop debug.binaural.segment_log 1     # PKG_BOUNDARY / PKG_SEAM
adb install -r app/build/outputs/apk/debug/...apk  # debug-сборка
```

Смотреть `/sdcard/Download/binaural_stream.log` и `adb logcat`:

| Что искать | Что это докажет |
|---|---|
| `beginHandoff spec#` 3+ раза за секунду с разными `serial` | механизм **B** |
| `rearmNextIfStale: NEXT ... играем — повышаем` | B, повышение на полуфейде |
| `prepare FAILED` / `direct buffer unavailable` | B, нехватка нативной памяти |
| `PKGE_SEAM ... dL/dR > 0.05` | **C**, разрыв данных на стыке пакетов |
| `writerLoop: write failed` / `generate failed` | **A**, underrun дошёл до ошибки |
| `handleRuntimeError` → `playback error ... (retryable)` | **A**, окончательный обрыв |
| Время между `launchStream ... успешно` и первым `beginHandoff` | > 12 мин при живом воспроизведении ⇒ A |

Отдельно — проверить сам вейклок:

```bash
adb shell dumpsys power | grep -i "BinauralBeats"
```

Если через 12 минут после старта воспроизведения лока в списке нет —
механизм **A** подтверждён напрямую.

---

## 7. Рекомендации по исправлению

Порядок соответствует вкладу в симптом.

### A. WakeLock

1. `acquireWakeLock()` — либо `acquire()` без TTL (с Release в
   `releaseWakeLock()`), либо периодическое продление с интервалом
   меньше TTL. TTL должен быть **меньше** любого разумного TTL Doze.
2. Вызывать `updateWakeLock()` не только на переходах состояния, но и по
   таймеру на акторе (например, раз в 5 мин), пока `isActiveState()`.
3. `write()` возвращает `< 0` / `generate <= 0` — добавить реальный
   «retryable»: один-два повтора с пересозданием потока, иначе
   `(retryable)` в сообщении об ошибке — ложь.

### B. Реконфигурация

1. **`updateConfig` дедуплицировать**: `if (config == this.config &&
   relaxation == this.relaxation) return@post`. Это одна строчка и она
   убирает бóльшую часть шторма.
2. **Не пушить дефолты в живой движок.** `onServiceConnected` обязан
   применять конфиг только когда `_uiState` действительно загружен
   (например, флаг `preferencesLoaded`), либо `updateAudioConfig()`
   должна пропускаться при `activePreset == null` **если** уже играем.
3. **`setSampleRate` / `setFrequencyUpdateInterval` / `setVolume`** из
   `onServiceConnected` вызывать только по фактическому расхождению; для
   SR — сравнивать с `playbackService?.getSampleRate()`, а не надеяться на
   внутренний guard (он есть, но `_uiState.sampleRate` всё равно дефолтный).
4. **Коалесценция хэндоффов.** Пока идёт `HANDOFF`, новую спеку не
   материализовать сразу, а дождаться `promoteNextToCurrent()` — там уже
   есть разбор хвоста очереди. Сейчас `rearmNextIfStale` форсит
   promote на полуфейде ради «последняя команда побеждает», и это стоит
   щелчка.
5. **`prepare()` убрать с нити актора** либо хотя бы вынести
   `allocateDirect` + первый пакет. 212 МБ на нити, которая крутит таймеры
   фейдов с точностью 60 мс, — архитектурный риск.
6. **`MAX_BUFFER_BYTES = 1 ГБ` пересмотреть.** 212 МБ direct buffer на
   поток при 10-мин интервале и 44.1 кГц — запредельно. Разумный потолок
   ~32–64 МБ (≈3–6 мин стерео float), остальное не даёт выигрыша по
   энергопотреблению, но кратно повышает риск OOM и стоимость кроссфейда.
7. **`allocateDirect` вызывать после `createAudioTrack`**, иначе пол
   OOM-уполовинивания считается от нулевого `audioTrackBufferSize`.

### C. Ось времени

1. `m_elapsedSeconds` перевести на **sample-driven** источник — тот же
   принцип, что у `m_curveTimeSeconds`. Wall-clock допустим только как
   начальный якорь.
2. `elapsedMs` читать **после** `updateElapsedTime()`, а не до.
3. `nativeGetCurrentTimeOfDay` и `PlaybackSpec.resumeCurveTimeSeconds`
   перевести на миллисекунды (`jint` мс или `jlong` мкс) — иначе каждый
   кроссфейд теряет до 0.999 с позиции по кривой.
4. В `BufferPackagePlanner` заменить wall-clock-сравнение
   (`|callerAdvance - audioAdvance| > 2.0`) на sample-driven счётчик,
   чтобы «seek» не срабатывал от заминки CPU.

---

## 8. Сводка артефактов

| Файл | Строки | Что там |
|---|---|---|
| `core/.../BinauralStreamManager.kt` | 906–937 | WakeLock с TTL, без продления |
| | 121–125 | `updateConfig` без дедупликации |
| | 292–300 | `onSpecChanged` / `requestHandoff` |
| | 328–376 | `beginHandoff` |
| | 378–414 | `rearmNextIfStale` → promote на полуфейде |
| | 462–487 | `promoteNextToCurrent` |
| | 871–893 | `handleRuntimeError` — «retryable» без retry |
| `core/.../BinauralStreamImpl.kt` | 38–62 | `TRACK_BUFFER_MS=3000`, `WRITE_CHUNK_MS=2000`, `MAX_BUFFER_BYTES=1 ГБ` |
| | 146–158 | расчёт `samplesPerChannel` + 212 МБ direct buffer |
| | 224–257 | `createAudioTrack` / `allocateDirect` (порядок) |
| | 426–481 | `applyShaper`, ветка замены активного шейпера |
| | 592–650 | `writerLoop` |
| | 662–664 | `getCurrentCurveTimeSeconds` — целые секунды |
| `core/.../PlaybackSpec.kt` | 32–35 | `audioEquals` (SR/config, без volume отдельно) |
| `core/.../cpp/src/BinauralEngine.cpp` | 539–553 | `updateElapsedTime` — wall clock |
| | 579–585 | `elapsedMs` читается до обновления |
| `core/.../cpp/src/AudioGenerator.cpp` | 2052–2062 | swap по `currentElapsedMs` |
| `app/.../viewmodel/BinauralViewModel.kt` | 72–90 | дефолты `BinauralUiState` |
| | 151–196 | `onServiceConnected` — шторм |
| | 236, 257, 275 | повторные пуши из коллекторов |
| `app/.../service/BinauralPlaybackService.kt` | 196–277 | `onCreate`, запуск UI-джобы |
| | 751–769 | `startUiFrequencyUpdateJob` — 1 Гц |
| `app/.../MainActivity.kt` | 32–87 | `onCreate`/`onResume`/`onPause` |
