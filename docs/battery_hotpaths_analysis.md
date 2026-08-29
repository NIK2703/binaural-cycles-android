# Анализ горячих путей исполнения и оптимизация энергопотребления

Дата: 2026-08-29
Охват: `app`, `core/audio` (Kotlin + C++), `data/preferences`

---

## 1. Главный вывод (неочевидный)

**Аудиотракт уже оптимизирован и не является источником расхода батареи.**
Батчевая архитектура (один пакет на 10 минут аудио, нативный SIMD-генератор,
wavetable) даёт коэффициент заполнения CPU на уровне долей процента.

Вся утечка энергии сосредоточена в **слое опроса/уведомлений/UI**, который
работает с частотой 1–10 Гц **непрерывно**, независимо от того, нужен ли
результат:

| Путь | Частота | Когда работает |
|---|---|---|
| Пересборка + публикация notification | **1 Гц** | передний план + воспроизведение |
| Полная рекомпозиция дерева Compose | **3–4 Гц** | передний план |
| Перерисовка графика (≥1000 интерполяций) | **3–4 Гц** | экран редактирования |
| Пересборка notification в фоне | **0.1 Гц** | весь срок жизни сервиса |
| Пробуждение писателя (`track.write`) | **2 Гц** | всё время воспроизведения |

Три верхние строки — это практически весь измеряемый расход.

---

## 2. Карта горячих путей

```
[Аудио, фон]                          [UI, главный поток]
BinauralWriter-N                      BinauralPlaybackService.serviceScope
  generateBufferDirect(26.5M сэмплов)   ├─ startUiFrequencyUpdateJob  (1 Гц)
  └─ track.write(chunk 0.5 s) ──┐        │    ├─ updateCurrentFrequencies()  ──┐
     ↑ будит поток 2×/с         │        │    ├─ updateMediaMetadata()         │
                                │        │    └─ updateNotificationSilently()  │
[Актор] HandlerThread           │        │         └─ createNotification()     │
  BinauralStreamManager         │        │              + notify() → IPC →     │
  WakeLock TTL 12 мин           │        │                SystemUI             │
                                │        └─ startNotificationUpdateJob (0.1 Гц)│
                                │                                             │
                                └───────────────> BinauralViewModel           │
                                                   4 коллектора → 4 ×         │
                                                   _uiState.update() ─────────┘
                                                        │
                                                        ▼
                                    Navigation / PresetListScreen /
                                    PresetEditScreen / SettingsScreen
                                    (collectAsState монолитного uiState)
                                                        │
                                                        ▼
                                    FrequencyGraph.drawBehind
                                      └─ drawBeatArea: ~1000 ×
                                         interpolateChannels()
                                         (каждый вызов сортирует список)
```

---

## 3. Находки по приоритету

### P0-1. Notification пересобирается и публикуется каждую секунду

`BinauralPlaybackService.kt:654-676` — `startUiFrequencyUpdateJob()`:

```kotlin
while (true) {
    delay(1000)
    audioEngine?.updateCurrentFrequencies()
    ...
    if (_isPlaying.value || _debugTimeEnabled.value) {
        ...
        if (_currentBeatFrequency.value > 0) {
            updateMediaMetadata()
            updateNotificationSilently()      // ← КАЖДУЮ СЕКУНДУ
        }
    }
}
```

`updateNotificationSilently()` → `createNotification()` (`:300-377`), который
каждый раз создаёт:

- 3 `PendingIntent` (`getService` / `getActivity` с `FLAG_UPDATE_CURRENT`);
- `NotificationCompat.Builder` с двумя `addAction`;
- `MediaStyle` с токеном `MediaSession`;

затем `notificationManager.notify()` — байnder-транзакция в `system_server`,
ранжирование уведомления, рассылка слушателям и перерисовка в SystemUI.
MediaStyle-уведомления дороже обычных: SystemUI дополнительно обрабатывает
media session token и элементы управления.

**Итого: 3600 пересборок уведомления в час**, каждая — межпроцессное
взаимодействие, будящее `system_server` и SystemUI. Это самая дорогая
операция в приложении, и она не даёт пользователю ничего, что менялось бы
раз в секунду.

Фоновый джоб (`:625-643`) делает то же каждые 10 с **в течение всего срока
жизни сервиса** — ещё 360 раз в час при выключенном экране.

**Решение** — публиковать уведомление только при реальном изменении текста:

```kotlin
private var lastNotifTitle: String? = null
private var lastNotifContent: String? = null

private fun buildNotificationContent(): String { /* существующая логика */ }

private fun updateNotificationIfChanged() {
    val title = _currentPresetName.value ?: getString(R.string.notification_playing)
    val content = buildNotificationContent()
    if (title == lastNotifTitle && content == lastNotifContent) return
    lastNotifTitle = title
    lastNotifContent = content
    updateMediaMetadata()
    updateNotificationSilently()
}
```

Дополнительно — округлять частоты до целых Гц в строке уведомления: текст
будет меняться раз в несколько секунд/минут вместо каждого тика.
Заменить оба вызова (`startUiFrequencyUpdateJob`, `startNotificationUpdateJob`)
на `updateNotificationIfChanged()`.

---

### P0-2. Монолитный `uiState` рекомпозирует всё дерево 3–4 раза в секунду

`BinauralViewModel.kt:40` — `BinauralUiState` содержит ~50 полей (пресеты,
кривые, настройки, *и* высокочастотные `currentBeatFrequency`,
`currentCarrierFrequency`, `currentTime`).

`BinauralViewModel.kt:285-315` — четыре независимых коллектора пишут в один
`MutableStateFlow`:

```kotlin
launch { ...currentBeatFrequency.collect  { _uiState.update { it.copy(currentBeatFrequency = it) } } }
launch { ...currentCarrierFrequency.collect { _uiState.update { it.copy(...) } } }
launch { ...currentTimeOfDaySeconds.collect { _uiState.update { it.copy(currentTime = ...) } } }
launch { ...isChannelsSwapped.collect     { _uiState.update { it.copy(...) } } }
```

Каждый `copy()` создаёт новый экземпляр data-класса → `StateFlow` видит
неравенство → **испускает**. За один тик 1 Гц приходит 3–4 различных
эмиссии (частоты и время меняются, флаг swap — реже).

Кто читает `uiState` через `collectAsState()`:

- `Navigation.kt:45` — **корень навигации**;
- `PresetListScreen.kt:48`, `PresetEditScreen.kt:35`, `SettingsScreen.kt:27`,
  `DebugTimeControlPanel.kt:47`.

Итог: **3–4 полные рекомпозиции графа Compose в секунду**, включая
`ChannelSettingsCard` (1030 строк со слайдерами) и список пресетов, — ради
обновления двух чисел и указателя времени.

**Решение (минимальное, с сохранением API)** — объединить потоки и
проредить их:

```kotlin
private data class Telemetry(
    val beat: Float, val carrier: Float,
    val timeOfDay: Int, val swapped: Boolean
)

private val _telemetry = MutableStateFlow(Telemetry(0f, 0f, 0, false))
val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

// в observePlaybackState():
viewModelScope.launch {
    combine(
        BinauralPlaybackService.currentBeatFrequency,
        BinauralPlaybackService.currentCarrierFrequency,
        BinauralPlaybackService.currentTimeOfDaySeconds,
        BinauralPlaybackService.isChannelsSwapped
    ) { b, c, t, s -> Telemetry(b, c, t.coerceIn(0, 86399), s) }
        .sample(300)                 // ≤ ~3 Гц вместо 4 независимых эмиссий
        .distinctUntilChanged()
        .collect { _telemetry.value = it }
}
```

Затем вынести `currentBeatFrequency`, `currentCarrierFrequency`, `currentTime`
из `BinauralUiState`, оставив в нём только «медленные» данные. Экраны,
которым нужна телеметрия (`CurrentFrequenciesCard`, индикатор на графике),
читают `telemetry`; `Navigation` перестаёт коллектить `uiState` вообще;
`PresetEditScreen` получает только статические поля.

---

### P1-1. График перерисовывается целиком 3–4 раза в секунду

`FrequencyGraph.kt:220-239` — вся статическая кривая рисуется внутри
`drawBehind`, лямбда которого захватывает `currentLocalTime`,
`currentCarrierFrequency`, `currentBeatFrequency`:

```kotlin
.drawBehind {
    drawGraphContent(
        sortedPoints = allPoints,
        currentLocalTime = currentLocalTime,   // ← меняется 1 Гц
        ...
    )
}
```

Изменение захваченного значения инвалидирует узел рисования → **полная
перерисовка**, хотя от времени зависит только вертикальный указатель.

**Что перерисовывается** (`drawBeatArea`, `:484-591`):

```kotlin
val numSamples = (sortedPoints.size * 4).coerceAtLeast(500)
for (i in 1..numSamples) {                    // проход 1: ≥500 вызовов
    val (lowerFreq, upperFreq) = Interpolation.interpolateChannels(...)
}
for (i in numSamples downTo 0) {              // проход 2: ещё ≥501 вызов
    val (lowerFreq, _) = Interpolation.interpolateChannels(...)   // lower уже посчитан!
}
```

- **~1000 вызовов `interpolateChannels` на перерисовку** (плюс ≥300 в
  `drawDashedBaseCurve` при включённом режиме расслабления);
- **проход 2 заново вычисляет `lowerFreq`, уже полученный в проходе 1** —
  чистые 50 % потерь;
- 3 объекта `Path` по ≥500 сегментов + растеризация (1 заливка + 2 обводки)
  Skia;
- при 3–4 Гц это **~3000–5200 вызовов интерполяции в секунду** на главном
  потоке.

**Решение — три правки, по возрастанию отдачи:**

1. **Убрать сортировку из цикла.** `Interpolation.kt:161`:

   ```kotlin
   val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }   // каждый вызов!
   ```

   Это единственная самая расточительная строка в приложении: ~1000 аллокаций
   списка + сортировок на перерисовку. Добавить перегрузку без сортировки
   (вызывающие уже передают отсортированный список):

   ```kotlin
   fun interpolateChannelsSorted(
       sortedPoints: List<FrequencyPoint>,
       time: LocalTime, type: InterpolationType, tension: Float = 0f
   ): Pair<Float, Float> { /* тело без sortedBy */ }

   fun interpolateChannels(points: List<FrequencyPoint>, ...) =
       interpolateChannelsSorted(points.sortedBy { it.time.toSecondOfDay() }, ...)
   ```

   `drawGraphContent` уже получает `allPoints`, отсортированный в
   `remember(...)`, — достаточно переключить вызов.

2. **Один проход вместо двух** — сохранить `lowerY` в `FloatArray` в проходе 1
   и пройтись по нему в обратном порядке для замыкания пути. Минус 50 %
   интерполяций, память — один массив на 500 элементов.

3. **Разделить статику и динамику на два слоя.** Статическая кривая
   кэшируется (в проекте уже есть готовый паттерн — `MiniGraphCache`/
   `CachedGraphGeometry`) и перерисовывается только при смене кривой,
   диапазона или размера:

   ```kotlin
   Box(Modifier.matchParentSize()) {
       Canvas(Modifier.matchParentSize()) {          // статический слой
           drawCachedCurve(geometry)                 // Path из кэша
       }
       Canvas(Modifier.matchParentSize()) {          // динамический слой
           drawTimeIndicator(currentLocalTime)       // одна линия
       }
   }
   ```

   После этого тик 1 Гц стоит одну линию вместо ~1000 интерполяций.

**Побочно:** `FrequencyGraph.kt:166`

```kotlin
val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
```

не используется нигде ниже (в `drawBehind` передаётся `allPoints`, строка 225).
Мёртвая аллокация на каждой рекомпозиции — удалить.

---

### P1-2. `applyPowerSaveMode` инвертирует логику (баг)

`BinauralStreamManager.kt:174-181`:

```kotlin
bufferIntervalMs = if (pm.isPowerSaveMode)
    (lastUserIntervalMs * POWER_SAVE_MULTIPLIER).coerceAtMost(60_000)
else lastUserIntervalMs
```

`coerceAtMost` — это `min`. При дефолте 600 000 мс (10 мин):
`min(1 800 000, 60 000) = 60 000` → **1 минута**.

При включённом режиме энергосбережения Android приложение начинает
генерировать аудио **в 10 раз чаще** (60 раз в час вместо 6) — ровно
наоборот задуманному. Опечатка: имелся в виду лимит 60 *минут*
(`3_600_000`), а не 60 секунд.

```kotlin
bufferIntervalMs = if (pm.isPowerSaveMode)
    (lastUserIntervalMs * POWER_SAVE_MULTIPLIER)
        .coerceIn(lastUserIntervalMs, 60 * 60 * 1000)   // не короче настроек, кап 60 мин
else lastUserIntervalMs
```

---

### P1-3. Настройка «1…60 мин» молча клампится до ~12,7 мин

`BinauralStreamImpl.kt:138-144`:

```kotlin
val maxSamplesByMinutes = rate.toLong() * 60 * MAX_BUFFER_MINUTES   // 60 мин
val maxSamplesByBytes   = MAX_BUFFER_BYTES / 8L                     // 256 МБ / 8
samplesPerChannel = minOf(rate * bufferIntervalMs / 1000, maxSamplesByMinutes, maxSamplesByBytes)
```

`MAX_BUFFER_BYTES = 256 МБ` ограничивает длину пакета:

| Частота | Мин/пакет при капе 256 МБ |
|---|---|
| 8 000 | 69,9 мин |
| 16 000 | 35,0 мин |
| 22 050 | 25,4 мин |
| 44 100 | **12,7 мин** |
| 48 000 | **11,7 мин** |

При 44,1 кГц (дефолт) любое значение настройки выше ~13 минут не имеет
эффекта. Ползунок обещает до 60 — это вводит в заблуждение.
Либо показывать реальный предел для выбранной частоты, либо поднять
`MAX_BUFFER_BYTES` на устройствах с большим объёмом памяти
(`ActivityManager.isLargeRam()`).

---

### P2-1. Писатель просыпается 2 раза в секунду

`BinauralStreamImpl.kt:39, 596-609`:

```kotlin
private const val WRITE_CHUNK_MS = 500   // гранулярность записи/реакции
...
val chunk = minOf(packetBytes - offset, audioTrackBufferSize,
                  spec.sampleRate.value * 2 * 4 * WRITE_CHUNK_MS / 1000)
val written = track.write(buf, chunk, AudioTrack.WRITE_BLOCKING)
```

Запись идёт порциями по 0,5 с при внутреннем буфере трека 3 с, то есть
**2 пробуждения в секунду на протяжении всего сеанса**. Отзывчивость на
`stop()` от этого не зависит (писателя разблокируют `track.pause()/stop()/
release()` в `releaseInternal`, `:542-544`).

Поднять до 2000–3000 мс → 0,33–0,5 пробуждения в секунду, **в 4–6 раз меньше**.

---

### P2-2. Фоновый джоб уведомления живёт вечно

`startNotificationUpdateJob()` запускается в `onCreate()` и не отменяется до
`onDestroy()`. При паузе тело джоба делает ранний `return`, но сам
`delay(10_000)` продолжает тикать — лишние таймерные прерывания в пустом
сервисе.

Запускать по `play()`, отменять по `pause()/stop()`, интервал поднять до
30–60 с и пропускать обновление при выключенном экране:

```kotlin
val pm = getSystemService(PowerManager::class.java)
if (!pm.isInteractive) return@launch   // экран погашен — уведомление всё равно не видно
```

---

### P2-3. 202 МБ direct-буфера на поток (404 МБ при кроссфейде)

При дефолтах (44 100 Гц, 10 мин):

```
samplesPerChannel = 44100 × 600 = 26 460 000
directBuffer      = 26 460 000 × 2 канала × 4 байта = 211,7 МБ
```

Во время handoff одновременно существуют `current` и `next`, каждый со своим
движком и буфером → **~423 МБ**. Это давление на память (риск LMK), а
`allocateDirect` при нехватке памяти деградирует к половинному буферу
(`:194-205`), что молча ломает заданную длительность пакета.

Смягчения: снизить частоту дискретизации (см. §4), снизить дефолтный
интервал до 5 мин, либо аллоцировать буфер `next` лениво — после старта
кроссфейда.

---

### P2-4. WakeLock: TTL не продлевается

`BinauralStreamManager.kt:904`:

```kotlin
val ttlMs = maxOf(10 * 60 * 1000L, bufferIntervalMs.toLong() + 120_000L)
...
if (wakeLock?.isHeld != true) { wakeLock?.acquire(ttlMs) }
```

Лок приобретается один раз на всё состояние `RUNNING`. Если сеанс длиннее
TTL (12 мин при дефолте, 62 мин при 60-мин буфере), он просто истекает и
никем не возобновляется — заявленный в комментарии инвариант («TTL обязан
покрыть запись пакета целиком») фактически не соблюдается.

Практически это безвредно (во время воспроизведения AudioTrack HAL держит
свой wake lock), но код стоит привести в соответствие с намерением:
пересчитывать TTL при каждой генерации пакета.

---

## 4. Настройки без потери качества

### 4.1 Частота дискретизации — главный рычаг

Сигнал бинауральных ритмов — это два синуса: несущая (обычно 100–500 Гц,
максимум 2000 Гц по `MAX_FREQUENCY`) и биение 0,5–30 Гц.

| Частота | Найквист | Память / 10 мин | Вердикт |
|---|---|---|---|
| 48 000 | 24 кГц | 230 МБ | избыточно |
| **44 100** | 22 кГц | **212 МБ** | текущий дефолт, избыточно |
| **22 050** | 11 кГц | **106 МБ** | **рекомендуемый дефолт** |
| 16 000 | 8 кГц | 77 МБ | «ультра-экономия», допустимо |
| 8 000 | 4 кГц | 38 МБ | не рекомендуется (4 отсчёта на период при 2 кГц) |

При 22 050 Гц несущая 2 кГц даёт 11 отсчётов на период — для синусоиды
разница неразличима. Выигрыш: **вдвое меньше памяти и вдвое короче
вычислительный всплеск**, без сколько-нибудь заметной потери качества.

### 4.2 Новый переключатель «Энергосбережение UI»

Добавить в `SettingsScreen` и связать с уже существующими механизмами:

| Параметр | Обычный | Энергосбережение |
|---|---|---|
| Опрос частот | 1 Гц | 0,25 Гц |
| Обновление notification | по изменению текста | по изменению текста, но не чаще 1/мин |
| Индикатор времени на графике | 1 Гц | статичный (или 1/5 Гц) |
| Интервал генерации | настройка пользователя | ×2 (но не меньше настройки) |

### 4.3 Исправить режим энергосбережения Android

См. P1-2 — сейчас он ускоряет генерацию в 10 раз.

---

## 5. Порядок внедрения

| Шаг | Правка | Риск | Ожидаемый эффект |
|---|---|---|---|
| 1 | P1-2: `coerceAtMost` → `coerceIn` (1 строка) | нет | −90 % probуждений в power-save |
| 2 | P0-1: notification только по изменению текста | низкий | −3600 IPC/час |
| 3 | P1-1(1): `interpolateChannelsSorted` | низкий | −~4000 сортировок/с |
| 4 | P0-2: `telemetry` + `sample(300)` | средний | −3 рекомпозиции/с |
| 5 | P1-1(2): один проход в `drawBeatArea` | низкий | −50 % интерполяций |
| 6 | P2-1: `WRITE_CHUNK_MS` 500 → 2000 | низкий | −75 % пробуждений писателя |
| 7 | P1-1(3): кэш `Path` + разделение слоёв | средний | перерисовка 1 Гц ≈ одна линия |
| 8 | P2-2: жизненный цикл джоба уведомления | низкий | −360 IPC/час в фоне |
| 9 | §4.1: дефолт 22 050 Гц | низкий | −50 % памяти и DSP |

Шаги 1, 3, 5, 6 — почти безрисковые и дают основную часть выигрыша.

---

## 6. План валидации

1. **Бейзлайн.** Perfetto / System Trace, 10 мин воспроизведения, экран
   включён: зафиксировать CPU-time потоков `BinauralWriter-*`, main,
   `system_server`.
2. **Счётчики рекомпозиций.** Layout Inspector → Recomposition Counts на
   `PresetEditScreen`: должно быть ~0/с в покое (сейчас 3–4/с).
3. **Аллокации.** Allocation Profiler, 30 с на экране редактирования:
   подтвердить исчезновение ~4000 `ArrayList`+`LocalTime` в секунду.
4. **Батарея.** `adb shell dumpsys batterystats --reset`, 1 ч воспроизведения
   с выключенным экраном, затем Battery Historian — сравнить mA·ч до/после,
   замерить число вызовов `notify()`.
5. **Регрессия качества.** Сравнить спектр выходного сигнала при 44 100 и
   22 050 Гц (FFT): убедиться в отсутствии паразитных составляющих выше
   порога слышимости.
