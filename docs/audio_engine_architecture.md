# Архитектура аудиодвижка бинауральных ритмов

## Содержание

1. [Обзор архитектуры](#обзор-архитектуры)
2. [Слои архитектуры](#слои-архитектуры)
3. [Нативный C++ слой](#нативный-c-слой)
4. [Kotlin JNI слой](#kotlin-jni-слой)
5. [Модели данных](#модели-данных)
6. [Генерация аудио](#генерация-аудио)
7. [Режим расслабления](#режим-расслабления)
8. [Управление воспроизведением](#управление-воспроизведением)
9. [Оптимизации производительности](#оптимизации-производительности)
10. [Поток данных](#поток-данных)
11. [Диаграммы](#диаграммы)
12. [Константы](#константы)

---

## Обзор архитектуры

Аудиодвижок построен по многослойной архитектуре с разделением ответственности между нативным C++ кодом (высокопроизводительная генерация аудио) и Kotlin/Java слоем (управление воспроизведением и UI).

### Ключевые принципы

1. **Zero-copy генерация** — использование DirectByteBuffer для избежания копирования данных между JNI границами
2. **Pull-модель** — Kotlin опрашивает C++ состояние через глобальные атомарные переменные вместо push-callbacks
3. **SIMD-оптимизация** — использование NEON (ARM) и SSE (x86) для параллельной генерации сэмплов
4. **Lookup-таблицы** — предвычисленные таблицы для O(1) доступа к частотам и fade-кривым

### Аппаратные архитектуры

| Архитектура | SIMD | Особенности |
|-------------|------|-------------|
| ARMv7 (armeabi-v7a) | NEON | Базовый, без vrndmq_f32, без FMA intrinsics |
| ARMv8 (arm64-v8a) | NEON Advanced | Полный SIMD с FMA (vfmaq_f32) и rounding (vrndmq_f32) |
| x86/x86_64 | SSE | SSSE3 для векторизованной интерполяции |

---

## Слои архитектуры

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI Layer (Compose)                        │
│  PresetEditScreen, PresetListScreen, SettingsScreen             │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      ViewModel Layer                             │
│  BinauralViewModel                                               │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Service Layer                               │
│  BinauralPlaybackService (Foreground Service)                    │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Audio Engine Layer                            │
│  BinauralAudioEngine (Kotlin) ←→ NativeAudioEngine (JNI)        │
│  ├── VolumeShaper для fade-in/fade-out                          │
│  ├── WakeLock для предотвращения засыпания                      │
│  └── HandlerThread с приоритетом AUDIO                          │
└─────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Native C++ Layer                              │
│  BinauralEngine → AudioGenerator → Wavetable                    │
│                  ↓                                               │
│         BufferPackagePlanner                                     │
│                  ↓                                               │
│         Interpolation (LINEAR/CARDINAL/MONOTONE/STEP)           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Нативный C++ слой

### Структура файлов

```
core/audio/src/main/cpp/
├── include/
│   ├── Config.h              # Конфигурация и структуры данных
│   ├── Wavetable.h           # Таблица синусов для генерации
│   ├── Interpolation.h       # Алгоритмы интерполяции
│   ├── AudioGenerator.h      # Генератор аудио-буферов
│   ├── BinauralEngine.h      # Главный класс движка
│   └── BufferPackagePlanner.h # Планировщик пакетов буферов
└── src/
    ├── AudioGenerator.cpp    # Реализация генератора
    ├── BinauralEngine.cpp    # Реализация движка
    ├── Wavetable.cpp         # Инициализация таблицы
    └── jni.cpp               # JNI-мосты
```

### Основные классы

#### BinauralEngine

Главный класс C++ движка. Управляет состоянием и координирует генерацию аудио.

**Ответственности:**
- Хранение конфигурации (BinauralConfig)
- Управление состоянием генератора (GeneratorState)
- Координация между AudioGenerator и BufferPackagePlanner
- Thread-safe доступ через std::shared_mutex (reader-writer lock)
- Генерация батчей для оптимизации энергопотребления

**Ключевые методы:**
- `setConfig()` — установка конфигурации с перестройкой lookup-таблиц
- `generateAudioBuffer()` — генерация одного буфера
- `generateBatch()` — генерация большого буфера для оптимизации
- `setPlaying()` — управление состоянием воспроизведения
- `resetState()` — сброс состояния при остановке

**Callbacks (опционально):**
- `onPlayingChanged` — изменение состояния воспроизведения
- `onFrequencyChanged` — изменение частоты (срабатывает при изменении > 0.1 Гц)
- `onChannelsSwapped` — перестановка каналов
- `onElapsedChanged` — изменение прошедшего времени

**Атомарные переменные для Pull-модели:**
- `m_currentBeatFreq` — текущая частота биений
- `m_currentCarrierFreq` — текущая несущая частота
- `m_elapsedSeconds` — прошедшее время
- `m_isPlaying` — состояние воспроизведения

#### AudioGenerator

Генератор аудио-буферов. Содержит SIMD-оптимизированные методы генерации.

**Ответственности:**
- Генерация сэмплов синусоид через Wavetable
- Интерполяция частот внутри буфера
- Обработка fade-in/fade-out для перестановки каналов
- Нормализация громкости
- Генерация пакетов буферов по плану

**Варианты генерации:**
- Скалярная версия (fallback)
- NEON-оптимизированная (ARM)
- SSE-оптимизированная (x86)

**Специализированные методы генерации:**
- `generatePackage()` — генерация пакета буферов по плану (скалярная версия)
- `generatePackageNeon()` — NEON-оптимизированная генерация пакета
- `generatePackageSse()` — SSE-оптимизированная генерация пакета
- `updatePhasesOnly()` — обновление фаз без генерации звука (для паузы в swap-цикле)

**Предвычисленные таблицы:**
- **Wavetable** — 2048 синусов с линейной интерполяцией (статический класс)
- **FadeCurveTable** — локальная структура в AudioGenerator.cpp для косинусной интерполяции

#### FadeCurveTable

Локальная структура в [`AudioGenerator.cpp`](core/audio/src/main/cpp/src/AudioGenerator.cpp:37) для предвычисленной fade-кривой (косинусная интерполяция). Не является отдельным классом — определяется как статическая переменная в файле.

**Характеристики:**
- Размер: 2048 + 1 значений (TABLE_SIZE + 1 для безопасной интерполяции)
- Формула: 0.5 × (1 - cos(t × π))
- Выравнивание: 32 байта для SIMD
- Инициализация: при первом запуске через статический экземпляр `s_fadeCurveTable`

**Методы:**
- `get(progress)` — получение значения с линейной интерполяцией между соседними элементами таблицы. Автоматически clamp прогресса в диапазон [0.0, 1.0]

**Использование:** замена дорогого std::cos на table lookup в горячем цикле генерации аудио.

**Отладка стыков сегментов:**
- `LOG_SEG` — макрос для логирования переходов между сегментами (всегда включён для отладки стыков буферов)
- Формат: `SEGMENT_TRANS: prevEnd=[freq1, freq2], curStart=[freq3, freq4], diff=[delta1, delta2], type=bufferType`

**Оптимизации нормализации:**
- `fastPow()` — быстрая аппроксимация pow с кэшированием ln(x) в thread_local переменных
- `fastPowApprox()` — очень быстрая аппроксимация через разложение Тейлора для x≈1

**Константы для оптимизации pow:**
- `POW_TABLE_SIZE` = 256 — размер таблицы для аппроксимации
- `POW_TABLE_MIN` = 0.1 — минимальное значение x
- `POW_TABLE_MAX` = 2.0 — максимальное значение x

**Важно:** PowTable не является предвычисленной таблицей — это константы для определения диапазона, в котором применяется кэширование ln(x). Реальное кэширование происходит в thread_local переменных внутри fastPow().

#### Wavetable

Таблица предвычисленных синусов для быстрой генерации.

**Характеристики:**
- Размер: 2048 элементов (выравнивание 32 байта для SIMD)
- Линейная интерполяция между значениями
- Запас 4 элемента для безопасной интерполяции

**Оптимизации:**
- NEON: 4 сэмпла одновременно
- SSE: 4 сэмпла одновременно
- FMA (ARMv8): fused multiply-add для интерполяции

**Различия архитектур ARM:**
- ARMv8: использует vrndmq_f32 для floor и vfmaq_f32 для FMA
- ARMv7: совместимая реализация без расширенных intrinsics, через vcvtq_f32_s32

**Методы:**
- `fastSin()` — быстрый синус с линейной интерполяцией
- `fastSinNoInterp()` — быстрый синус без интерполяции
- `fastSinNeon()` — NEON-оптимизированная генерация 4 синусов
- `fastSinNeon8()` — NEON-оптимизированная генерация 8 сэмплов (2 регистра)
- `fastSinSse()` — SSE-оптимизированная генерация 4 синусов

#### BufferPackagePlanner

Планировщик пакетов буферов для цикла перестановки каналов. Определяет последовательность сегментов буферов для генерации.

**Цикл перестановки:**
```
[SOLID N сек] → [FADE_OUT M сек] → [PAUSE P сек] → [FADE_IN M сек] → [SOLID N сек] → ...
```

**Ключевой принцип:** неполный буфер в конце пакета переносится в начало следующего.

**Пример для 2 минут и интервале 30 сек:**
```
Пакет 1: [solid 30s] [fade-out 1s] [pause 0.5s] [fade-in 1s] [solid 30s] ... [solid 26s]
Пакет 2: [solid 4s] [fade-out 1s] [pause 0.5s] [fade-in 1s] [solid 30s] ...
```

**Момент swap:** перестановка каналов происходит после полного FADE_OUT, перед PAUSE (если пауза включена) или перед FADE_IN (если пауза отключена). Swap выполняется только если сегмент FADE_OUT завершён полностью (`segmentDuration == phaseTimeRemaining`).

**Реализация PAUSE:**
- PAUSE является отдельной фазой в `SwapPhase` enum (`PAUSE = 2`)
- Пауза реализуется через параметр `channelSwapPauseDurationMs` в конфигурации
- Во время паузы вызывается `updatePhasesOnly()` — фазы обновляются без генерации звука, буфер заполняется нулями
- Если `channelSwapPauseDurationMs = 0`, фаза PAUSE пропускается планировщиком
- Пауза происходит ПОСЛЕ завершения FADE_OUT и ПЕРЕД началом FADE_IN

**SwapPhase enum:** `SOLID = 0`, `FADE_OUT = 1`, `PAUSE = 2`, `FADE_IN = 3`. Каждая фаза явно представлена в enum, что упрощает логику планировщика.

**Порядок фаз с паузой:**
```
SOLID → FADE_OUT → (swap каналов) → PAUSE → FADE_IN → SOLID → ...
```

**Порядок фаз без паузы:**
```
SOLID → FADE_OUT → (swap каналов) → FADE_IN → SOLID → ...
```

**Методы:**
- `planPackage(packageDurationMs, config, state)` — планирование пакета с сегментами, возвращает `PackagePlan`
- `calculateCycleDuration(config)` — вычисление длительности полного цикла
- `resetState(state)` — сброс состояния в начальное
- `nextPhase(current)` — определение следующей фазы (inline)
- `phaseDuration(phase, config)` — длительность фазы в мс (inline)

**Возвращаемое значение planPackage():**
Структура `PackagePlan` содержит:
- `segments: vector<BufferSegment>` — последовательность сегментов для генерации
- `totalDurationMs: int64_t` — общая длительность пакета
- `endsMidCycle: bool` — признак того, что пакет заканчивается в середине цикла

**Интеграция с AudioGenerator:**
Метод [`generatePackage()`](core/audio/src/main/cpp/src/AudioGenerator.cpp:1924) принимает `PackagePlan` и генерирует все сегменты за один вызов:
1. Итерация по сегментам плана
2. Для каждого сегмента вызывается соответствующий метод (`generateSolidBuffer`, `generateFadeBuffer`)
3. После сегмента с `swapAfterSegment=true` выполняется перестановка каналов
4. Результат содержит финальные частоты и состояние swap

**Inline реализация:** Методы `planPackage()`, `calculateCycleDuration()`, `resetState()`, `nextPhase()`, `phaseDuration()` реализованы inline в заголовочном файле для максимальной производительности.

#### Interpolation

Алгоритмы интерполяции для расчёта частот между точками.

**Поддерживаемые типы:**
| Тип | Описание | Overshoot |
|-----|----------|----------|
| `LINEAR` | Линейная интерполяция | Нет |
| `CARDINAL` | Кубический сплайн с параметром tension | Возможен |
| `MONOTONE` | PCHIP сплайн (Fritsch-Carlson) | Гарантированно нет |
| `STEP` | Ступенчатая | Нет |

**Монотонный сплайн (PCHIP):**
- Использует гармоническое среднее наклонов
- Гарантирует отсутствие overshoot
- Значения всегда в пределах [min(p1,p2), max(p1,p2)]

---

## Kotlin JNI слой

### NativeAudioEngine

JNI-обёртка для C++ движка.

**Архитектура Pull-модели:**
```
┌─────────────────┐                    ┌─────────────────┐
│    Kotlin       │   nativeGetXxx()   │     C++         │
│                 │ ──────────────────>│                 │
│  (polling)      │                    │  g_currentXxx   │
│                 │<────────────────── │  (std::atomic)  │
└─────────────────┘                    └─────────────────┘
```

**Глобальные атомарные переменные (jni.cpp):**
- `g_currentBeatFreq` (std::atomic<float>) — текущая частота биений
- `g_currentCarrierFreq` (std::atomic<float>) — текущая несущая частота
- `g_elapsedSeconds` (std::atomic<int>) — прошедшее время
- `g_channelsSwapped` (std::atomic<bool>) — состояние перестановки каналов

**Преимущества Pull-модели:**
- Нет overhead JNI callbacks
- Нет context switching
- Kotlin читает данные только когда нужно
- Используется memory_order_relaxed для минимальных барьеров

**Группы JNI методов:**
- Генерация буферов (DirectByteBuffer для zero-copy)
- Pull-модель геттеры (частоты, время, состояние каналов)
- Батчевая генерация (оптимизация энергопотребления)
- Интерполяция для UI (графики)

**Методы генерации виртуальных точек (Relaxation Mode):**
- `generateStepVirtualPoints()` — генерация точек для STEP режима
- `generateSmoothVirtualPoints()` — генерация точек для SMOOTH режима
- `interpolateCarrierAtTime()` — интерполяция несущей частоты
- `interpolateBeatAtTime()` — интерполяция частоты биений

### BinauralAudioEngine

Высокоуровневый движок для управления воспроизведением.

**Ответственности:**
- Создание и управление AudioTrack
- VolumeShaper для плавных переходов
- WakeLock для предотвращения засыпания
- HandlerThread с приоритетом AUDIO для генерации
- Адаптация к режиму энергосбережения

**Ограничения памяти:**
- Максимум 10 минут буфера (MAX_BUFFER_MINUTES)
- ~500 МБ максимальный размер буфера (MAX_BUFFER_BYTES)

**Приоритет потока:** THREAD_PRIORITY_AUDIO (-16)

**Поток воспроизведения:**
1. play() → startNewPlayback() → startPlayback()
2. createAudioTrack() + VolumeShaper (fade-in)
3. generateAudioLoop() — основной цикл
4. cleanupPlayback() при завершении

**Внутренние методы воспроизведения:**
- `startNewPlayback()` — инициализация новой сессии воспроизведения, установка флагов, сброс состояния
- `startPlayback()` — создание AudioTrack, DirectByteBuffer, VolumeShaper, запуск generateAudioLoop()
- `generateAudioLoop()` — основной цикл генерации аудио в HandlerThread
- `stopPlayback()` — остановка и освобождение AudioTrack, VolumeShaper
- `cleanupPlayback()` — очистка ресурсов при завершении цикла

**Внутренние методы управления громкостью:**
- `createVolumeShaper(durationMs, targetVolume)` — создание VolumeShaper для fade перехода
- `startVolumeShaper()` — запуск выполнения VolumeShaper
- `getVolumeFromShaper()` — получение текущей громкости с учётом прогресса fade
- `startFadeOut(durationMs, callback)` — запуск fade-out с callback по завершении
- `startFadeOutImmediate(durationMs, callback)` — немедленный запуск fade-out без ожидания цикла

**Внутренние методы обработки запросов:**
- `applyPendingSettings()` — применение отложенных настроек (sampleRate, interval) в начале цикла
- `checkFadeRequests()` — проверка запросов на stop/pause/switch preset с fade
- `executePause()` — выполнение паузы с сохранением накопленного времени

**Внутренние методы управления ресурсами:**
- `createAudioTrack()` — создание AudioTrack с нужными параметрами
- `acquireWakeLock()` — захват WakeLock для предотвращения засыпания
- `releaseWakeLock()` — освобождение WakeLock
- `resetState()` — сброс состояния (elapsedSeconds, accumulatedElapsedMs)

**StateFlows для UI:**
- `isPlaying` — состояние воспроизведения
- `currentConfig` — текущая конфигурация
- `currentBeatFrequency` — текущая частота биений
- `currentCarrierFrequency` — текущая несущая частота
- `elapsedSeconds` — прошедшее время
- `isChannelsSwapped` — перестановка каналов

**Отложенные настройки (применяются в следующем цикле):**
- `pendingSampleRate` — новая частота дискретизации
- `pendingFrequencyUpdateIntervalMs` — новый интервал генерации
- `stopWithFadeRequested` — запрос на остановку с fade
- `pauseWithFadeRequested` — запрос на паузу с fade
- `presetSwitchRequested` — запрос на переключение пресета

**Управление громкостью:**
- `userVolume` — громкость пользователя (0.0-1.0), сохраняется между сессиями
- `currentVolume` — текущая громкость с учётом fade
- `MIN_VOLUME` = 0.001f — минимальная громкость
- `PLAYBACK_FADE_DURATION_MS` = 250мс — длительность fade

**Логика setVolume():**
- Прямая установка AudioTrack.setVolume() для мгновенного отклика
- VolumeShaper НЕ используется для слайдера громкости (причины: асинхронность, множество созданных шейперов при быстрых движениях)
- userVolume сохраняется для восстановления при следующем воспроизведении

**Обработка play() при активном fade-out:**
- Если isActive=true и isPlaying=false (идёт fade-out), происходит прерывание fade
- Удаляются все callback из Handler
- Сбрасываются флаги stopWithFadeRequested и pauseWithFadeRequested
- Сохраняется текущая громкость (currentVolume)
- Закрывается VolumeShaper, устанавливается сохранённая громкость
- После паузы 100мс запускается startNewPlayback()

**Методы для UI без генерации:**
- `getFrequenciesAtCurrentTime()` — получение текущих частот для отображения без генерации буфера

### NativeInterpolation

Статический объект для доступа к C++ интерполяции из UI без создания экземпляра NativeAudioEngine.

**Методы:**
- `interpolate(p0, p1, p2, p3, t, type, tension)` — интерполяция одного значения
- `generateInterpolatedCurve(timePoints, values, numOutputPoints, type, tension)` — генерация массива интерполированных значений для графика

**Оптимизация:** один JNI вызов для генерации всего массива интерполированных значений вместо сотен отдельных вызовов. Используется для отрисовки графиков в UI.

**Нативные методы (jni.cpp):**
- `nativeInterpolate()` — делегирует в `Interpolation::interpolate()`
- `nativeGenerateInterpolatedCurve()` — генерирует массив значений для всего графика

### Interpolation (Kotlin)

Объект с алгоритмами интерполяции для использования в Kotlin коде (без JNI вызовов).

**Методы:**
- `linear(y1, y2, t)` — линейная интерполяция
- `cardinal(p0, p1, p2, p3, t, tension)` — кардинальный сплайн с параметром tension
- `monotone(p0, p1, p2, p3, t)` — монотонный сплайн PCHIP (Fritsch-Carlson)
- `step(p1)` — ступенчатая интерполяция (возвращает значение левой точки)
- `interpolate(type, p0, p1, p2, p3, t, tension)` — универсальный метод с выбором типа

**Алгоритм монотонного сплайна:**
- Вычисление наклонов между соседними точками
- Гармоническое среднее наклонов: 2 × d1 × d2 / (d1 + d2)
- Если наклоны имеют разные знаки — касательная = 0
- Гарантирует отсутствие overshoot

**Использование:** применяется в FrequencyCurve для интерполяции частот между точками графика.

### JNI интерфейс (jni.cpp)

**Глобальные атомарные переменные для Pull-модели:**
- `g_currentBeatFreq` (std::atomic<float>) — текущая частота биений
- `g_currentCarrierFreq` (std::atomic<float>) — текущая несущая частота
- `g_elapsedSeconds` (std::atomic<int>) — прошедшее время
- `g_channelsSwapped` (std::atomic<bool>) — состояние перестановки каналов

**Группы JNI методов:**

**Инициализация и управление:**
- `nativeInitialize()` — создание экземпляра BinauralEngine
- `nativeRelease()` — освобождение ресурсов
- `nativeSetConfig()` — установка конфигурации с массивами точек
- `nativeSetSampleRate()` — установка частоты дискретизации
- `nativeResetState()` — сброс состояния и атомарных переменных
- `nativeSetPlaying()` — управление состоянием воспроизведения
- `nativeSetPlaybackStartTime()` — установка времени начала для расчёта elapsed

**Генерация буферов:**
- `nativeGenerateBuffer()` — генерация в FloatArray (с копированием, deprecated)
- `nativeGenerateBufferDirect()` — zero-copy генерация через DirectByteBuffer
- `nativeGenerateBatch()` — генерация большого буфера для оптимизации энергопотребления

**Pull-модель геттеры:**
- `nativeGetCurrentBeatFrequency()` — чтение из g_currentBeatFreq
- `nativeGetCurrentCarrierFrequency()` — чтение из g_currentCarrierFreq
- `nativeGetElapsedSeconds()` — чтение из g_elapsedSeconds
- `nativeIsChannelsSwapped()` — чтение из g_channelsSwapped
- `nativeUpdateElapsedTime()` — обновление прошедшего времени

**Батчевая генерация:**
- `nativeSetBatchDurationMinutes()` — установка длительности батча
- `nativeGetBatchDurationMinutes()` — получение длительности батча

**Интерполяция для UI:**
- `nativeInterpolate()` — интерполяция одного значения
- `nativeGenerateInterpolatedCurve()` — генерация массива для графика
- `nativeGetChannelFrequencies()` — получение частот каналов для заданного времени

---

## Модели данных

### Kotlin модели

#### FrequencyPoint
- `time: LocalTime` — время суток
- `carrierFrequency: Float` — несущая частота (Гц)
- `beatFrequency: Float` — частота биений (Гц)

#### FrequencyCurve
- `points: List<FrequencyPoint>` — точки кривой
- `carrierRange: FrequencyRange` — диапазон несущей частоты
- `beatRange: FrequencyRange` — диапазон частоты биений
- `interpolationType: InterpolationType` — тип интерполяции
- `splineTension: Float` — параметр натяжения для CARDINAL

**Оптимизации поиска:**
- `sortedPoints` — предварительно отсортированные точки
- `pointSeconds` — массив секунд для бинарного поиска O(log n)

**Методы интерполяции каналов:**
- `getUpperChannelFrequencyAt()` — carrier + beat/2
- `getLowerChannelFrequencyAt()` — carrier - beat/2

#### FrequencyRange
- `min: Float` — минимальная частота
- `max: Float` — максимальная частота
- `contains()` — проверка принадлежности
- `clamp()` — ограничение значения

**Константы по умолчанию:**
- `DEFAULT_CARRIER` = (50.0, 500.0) Гц
- `DEFAULT_BEAT` = (0.0, 1000.0) Гц

#### BinauralConfig
- `frequencyCurve: FrequencyCurve` — кривая частот
- `volume: Float` — громкость
- `channelSwapEnabled: Boolean` — включена ли перестановка каналов
- `channelSwapIntervalSeconds: Int` — интервал перестановки
- `channelSwapFadeEnabled: Boolean` — затухание при перестановке
- `channelSwapFadeDurationMs: Long` — длительность затухания
- `channelSwapPauseDurationMs: Long` — пауза между fade-out и fade-in (0 = без паузы)
- `normalizationType: NormalizationType` — тип нормализации
- `volumeNormalizationStrength: Float` — сила нормализации

#### ChannelSwapSettings
- `enabled: Boolean` — включена ли перестановка
- `intervalSeconds: Int` — интервал в секундах (по умолчанию 300)
- `fadeEnabled: Boolean` — затухание при перестановке
- `fadeDurationMs: Long` — длительность затухания
- `pauseDurationMs: Long` — пауза между fade-out и fade-in

#### RelaxationModeSettings
- `enabled: Boolean` — включён ли режим
- `mode: RelaxationMode` — STEP или SMOOTH
- `carrierReductionPercent: Int` — снижение несущей (0-50%)
- `beatReductionPercent: Int` — снижение биений (0-100%)
- **STEP параметры:** gapBetweenRelaxationMinutes (0-120), transitionPeriodMinutes (1-10), relaxationDurationMinutes (10-60)
- **SMOOTH параметры:** smoothIntervalMinutes (10-120)

**Ограничения валидации:**
- carrierReductionPercent: 0-50%
- beatReductionPercent: 0-100%
- gapBetweenRelaxationMinutes: 0-120 минут
- transitionPeriodMinutes: 1-10 минут
- relaxationDurationMinutes: 10-60 минут
- smoothIntervalMinutes: 10-120 минут (интервал между точками в SMOOTH режиме)

**Генерация виртуальных точек:**
Виртуальные точки генерируются в Kotlin слое (NativeAudioEngine) и передаются в C++ при обновлении конфигурации. Это позволяет использовать один и тот же C++ код для генерации аудио без изменений.

- `generateStepVirtualPoints()` — генерация 4 точек на каждый период расслабления (трапеция)
- `generateSmoothVirtualPoints()` — генерация чередующихся точек (базовая → снижающая)
- `interpolateCarrierAtTime()` — интерполяция несущей частоты для заданного времени
- `interpolateBeatAtTime()` — интерполяция частоты биений для заданного времени

**Важно:** При включённом режиме расслабления интерполяция производится ТОЛЬКО по виртуальным точкам, реальные точки используются только для расчёта базовой кривой.

#### VolumeNormalizationSettings
- `type: NormalizationType` — тип нормализации
- `strength: Float` — сила нормализации (0-2.0)

#### BinauralPreset
- `id: String` — уникальный идентификатор
- `name: String` — название пресета
- `frequencyCurve: FrequencyCurve` — кривая частот
- `relaxationModeSettings: RelaxationModeSettings` — настройки расслабления
- `createdAt: Long` — время создания
- `updatedAt: Long` — время обновления

**Ленивое свойство:**
- `curveWithRelaxation` — кривая с виртуальными точками расслабления

**Стандартные пресеты:**
- `defaultPreset()` — "Циркадный ритм"
- `gammaPreset()` — "Гамма-продуктивность"
- `dailyCyclePreset()` — "Суточный цикл"

#### PlaybackState
- `isPlaying: Boolean` — состояние воспроизведения
- `config: BinauralConfig` — конфигурация
- `elapsedSeconds: Int` — прошедшее время
- `volume: Float` — громкость

#### SampleRate (enum)
- `ULTRA_LOW` = 8000 Гц
- `VERY_LOW` = 16000 Гц
- `LOW` = 22050 Гц
- `MEDIUM` = 44100 Гц (по умолчанию)
- `HIGH` = 48000 Гц

### C++ структуры

#### FrequencyPoint
- `timeSeconds: int32_t` — секунды с начала суток (0-86399)
- `carrierFrequency: float` — несущая частота
- `beatFrequency: float` — частота биений

#### FrequencyCurve
- `points: vector<FrequencyPoint>` — точки кривой
- `interpolationType: InterpolationType` — тип интерполяции
- `splineTension: float` — параметр натяжения
- **Кэш:** minLowerFreq, maxLowerFreq, minUpperFreq, maxUpperFreq, cachedHash
- **Lookup table:** lowerFreqTable, upperFreqTable (864000 значений каждая)

#### FrequencyTableResult
- `lowerFreq: float` — нижняя частота канала (левый канал)
- `upperFreq: float` — верхняя частота канала (правый канал)

**Использование:** возвращается из lookup-таблицы для O(1) доступа к частотам.

#### GenerateResult
- `fadePhaseCompleted: bool` — завершена ли фаза fade
- `channelsSwapped: bool` — были ли переставлены каналы
- `currentBeatFreq: float` — текущая частота биений
- `currentCarrierFreq: float` — текущая несущая частота

**Использование:** возвращается из методов генерации буферов.

#### GeneratorState
- `leftPhase, rightPhase: float` — текущие фазы
- `channelsSwapped: bool` — переставлены ли каналы
- `lastSwapElapsedMs: int64_t` — время последней перестановки
- `totalSamplesGenerated: int64_t` — всего сгенерировано сэмплов
- **State machine:** swapPhase, phaseRemainingMs, cyclePositionMs

**Legacy поля (для обратной совместимости):**
- `phaseSamplePosition` — позиция внутри текущей фазы (в сэмплах)
- `solidStartMs` — время начала текущего SOLID периода
- `currentFadeOperation` — тип текущей операции fade
- `isFadingOut` — направление fade для CHANNEL_SWAP
- `fadeStartSample` — начало fade в сэмплах
- `pauseStartSample` — начало паузы в сэмплах

**Примечание:** Эти поля используются в legacy коде генерации и будут удалены после полного перехода на архитектуру с BufferPackagePlanner.

#### FadeOperation (enum в GeneratorState)
- `NONE = 0` — нет операции fade
- `CHANNEL_SWAP = 1` — fade для перестановки каналов
- `PRESET_SWITCH = 2` — fade для переключения пресета

**Примечание:** FadeOperation используется для отслеживания текущей операции fade в state machine генератора.

#### BinauralConfig
- `curve: FrequencyCurve` — кривая частот
- `volume: float` — громкость
- `channelSwapEnabled: bool` — перестановка каналов
- `channelSwapIntervalSec: int32_t` — интервал перестановки
- `channelSwapFadeEnabled: bool` — затухание при перестановке
- `channelSwapFadeDurationMs: int64_t` — длительность затухания
- `channelSwapPauseDurationMs: int64_t` — пауза между fade-out и fade-in
- `normalizationType: NormalizationType` — тип нормализации
- `volumeNormalizationStrength: float` — сила нормализации

#### SwapPhase (enum)
- `SOLID = 0` — сплошной буфер без fade
- `FADE_OUT = 1` — затухание перед swap
- `PAUSE = 2` — пауза между fade-out и fade-in (тишина, фазы обновляются)
- `FADE_IN = 3` — возрастание после swap

#### BufferType (enum)
- `SOLID = 0` — сплошной буфер
- `FADE_OUT = 1` — затухание
- `PAUSE = 2` — пауза (тишина)
- `FADE_IN = 3` — возрастание

#### BufferSegment
- `type: BufferType` — тип буфера
- `durationMs: int64_t` — длительность в мс
- `swapAfterSegment: bool` — выполнить swap после сегмента

#### PackagePlan
- `segments: vector<BufferSegment>` — последовательность сегментов
- `totalDurationMs: int64_t` — общая длительность
- `endsMidCycle: bool` — пакет заканчивается в середине цикла

---

## Генерация аудио

### Lookup Table для частот

**Размер таблицы:** 864000 значений (86400 секунд × 10 значений в секунду)

**Интервал дискретизации:** 100 мс (FREQUENCY_TABLE_INTERVAL_MS)

**Доступ:** O(1) — прямой индекс + линейная интерполяция между соседними значениями таблицы

**Построение таблицы:**
- Итеративный поиск O(n) вместо бинарного O(n log n) — время монотонно возрастает
- Prefetch следующего значения через __builtin_prefetch

**Интерполяция внутри таблицы:**
- Линейная интерполяция между соседними значениями таблицы
- Поддержка дробного времени (например, 0.186 сек) для корректной интерполяции внутри буфера
- Нормализация времени через fmod для корректной работы с отрицательными значениями

### Формула бинаурального ритма

**Левый канал:** carrier - beat/2 Гц
**Правый канал:** carrier + beat/2 Гц

**Пример:** carrier=200 Гц, beat=10 Гц
- Левый: 195 Гц
- Правый: 205 Гц
- Воспринимаемая частота биений: 10 Гц

### Генерация сэмпла

1. Вычисление фазового инкремента: omega = 2π × frequency / sampleRate
2. Накопление фазы с wrap-around (branchless)
3. Получение сэмпла через Wavetable::fastSin()

### Нормализация громкости

| Тип | Описание |
|-----|----------|
| `NONE` | Без нормализации |
| `CHANNEL` | Уравнивание между левым и правым каналом |
| `TEMPORAL` | Уравнивание по минимальной частоте во всей кривой |

**Формула TEMPORAL:** amplitude = (minFreq / currentFreq)^strength

### FadeCurveTable

Предвычисленная таблица для fade-кривой (косинусная интерполяция).

**Размер:** 2048 + 1 значений (TABLE_SIZE + 1 для безопасной интерполяции)

**Формула:** 0.5 × (1 - cos(t × π))

**Использование:** замена дорогого std::cos на table lookup в горячем цикле

**Метод доступа:**
- Линейная интерполяция между соседними значениями таблицы
- Автоматический clamp прогресса в диапазон [0.0, 1.0]

---

## Режим расслабления

### Обзор

Режим расслабления позволяет автоматически снижать частоты по расписанию для периодов отдыха. Генерируются виртуальные точки, по которым строится итоговая кривая.

### STEP режим (ступенчатый)

Создаёт трапецеидальные впадины по расписанию:

```
     ┌─────────┐                   ┌─────────┐
    /           \                 /           \
───┘             └───────────────┘             └───
   │← переход →│← длительность →│← переход →│
   │            │   расслабления  │            │
   │←─────────── полный период ──────────────→│
   │                            │← пауза →│
```

**Генерация виртуальных точек:**
1. Точка 1: на базовой кривой (начало периода)
2. Точка 2: сниженные частоты (после перехода)
3. Точка 3: сниженные частоты (конец расслабления)
4. Точка 4: на базовой кривой (после выхода)

**Параметры:**
- `gapBetweenRelaxationMinutes` — интервал между периодами (0-120 мин)
- `transitionPeriodMinutes` — период перехода (1-10 мин)
- `relaxationDurationMinutes` — длительность расслабления (10-60 мин)

### SMOOTH режим (плавный)

Чередующиеся точки (базовая → снижающая → базовая → снижающая):

```
     ┌───┐     ┌───┐     ┌───┐     ┌───┐
    /     \   /     \   /     \   /     \
───┘       └─┘       └─┘       └─┘       └───
  базовая  снижающая  базовая  снижающая
```

**Параметры:**
- `smoothIntervalMinutes` — интервал между точками (10-120 мин)
- `carrierReductionPercent` — снижение несущей частоты (0-50%)
- `beatReductionPercent` — снижение частоты биений (0-100%)

---

## Управление воспроизведением

### VolumeShaper

Используется для плавных переходов громкости при старте/остановке воспроизведения.

**Сценарии использования:**
- Fade-in при старте воспроизведения (250 мс)
- Fade-out при остановке/паузе (250 мс)

**Параметры:**
- `MIN_VOLUME` = 0.001f — минимальная громкость
- `PLAYBACK_FADE_DURATION_MS` = 250 мс — длительность fade

**Трекинг состояния fade:**
- `isFadeInProgress` — идёт ли fade
- `fadeStartTime` — время начала fade
- `fadeDurationMs` — длительность fade
- `fadeStartVolume` — начальная громкость
- `fadeTargetVolume` — целевая громкость

**Важно:** VolumeShaper НЕ используется для слайдера громкости — только для автоматических переходов. Для слайдера используется прямая установка AudioTrack.setVolume().

### WakeLock

Предотвращает засыпание устройства во время воспроизведения.

**Тип:** PARTIAL_WAKE_LOCK

**Тег:** "BinauralBeats:PlaybackWakeLock"

**Максимум:** 10 минут (перевыдаётся в цикле)

### Pause/Resume с сохранением состояния

- `accumulatedElapsedMs` — накопленное время между сессиями
- При pause: сохраняется текущее время сессии
- При resume: накопленное время сохраняется

### Адаптация к режиму энергосбережения

- Проверка `PowerManager.isPowerSaveMode`
- Увеличение интервала генерации в 3 раза (POWER_SAVE_INTERVAL_MULTIPLIER) при Battery Saver
- Максимум интервала: 60 секунд

### Переключение пресета с fade

- `switchPresetWithFade()` — переключение с плавным переходом
- `presetSwitchRequested` — отложенная конфигурация
- Применяется в следующем цикле generateAudioLoop()

---

## Оптимизации производительности

### SIMD-оптимизации

**NEON (ARM):**
- Генерация 4 сэмплов одновременно
- FMA для интерполяции (ARMv8): vfmaq_f32
- Rounding для floor (ARMv8): vrndmq_f32
- ARMv7: совместимая реализация через vcvtq_f32_s32

**SSE (x86):**
- Генерация 4 сэмплов одновременно
- SSSE3 для векторизованной интерполяции

### Предвычисленные таблицы

| Таблица | Размер | Назначение |
|---------|--------|------------|
| Wavetable | 2048 + 4 | Синусы для генерации |
| FadeCurveTable | 2048 + 1 | Косинусная интерполяция для fade |
| FrequencyTable | 864000 × 2 | Частоты каналов на сутки |

### Оптимизация функции pow

Для нормализации громкости используется оптимизированная функция [`fastPow()`](core/audio/src/main/cpp/include/AudioGenerator.h:195):

**Метод:** Кэширование ln(x) в thread_local переменных
- Диапазон: x ∈ [0.1, 2.0], n ∈ [0.3, 2.0]
- Для значений вне диапазона — точный расчёт через exp(n*ln(x))
- Позволяет избежать дорогого вызова std::log() для типичных значений

**Аппроксимация [`fastPowApprox()`](core/audio/src/main/cpp/include/AudioGenerator.h:220):**
- Разложение Тейлора для x ≈ 1: x^n ≈ 1 + n*ln(x) + n²*ln²(x)/2
- Точность: ~1% для x ∈ [0.8, 1.2] и n ∈ [0.5, 1.5]
- Используется для быстрой аппроксимации когда высокая точность не критична

**Константы для оптимизации:**
- `POW_TABLE_SIZE` = 256 — количество сегментов для кэширования
- `POW_TABLE_MIN` = 0.1 — минимальное значение x для кэша
- `POW_TABLE_MAX` = 2.0 — максимальное значение x для кэша

### Branchless оптимизации

- Phase wrap без ветвления (через умножение и приведение типов)
- Bitwise mask вместо modulo

### Cache-friendly доступ

- Prefetch следующего значения через __builtin_prefetch
- Итеративный поиск O(n) вместо бинарного O(n log n) при построении таблицы (т.к. время монотонно возрастает)

### Батчевая генерация

Генерация большого буфера за один вызов для оптимизации энергопотребления:
- Уменьшение количества JNI вызовов
- Возможность использования больших буферов (до 10 минут)
- `setBatchDurationMinutes()` — установка длительности батча
- `generateBatch()` — генерация батча

---

## Поток данных

### При воспроизведении

1. UI → BinauralViewModel.play()
2. BinauralPlaybackService.onStartCommand()
3. BinauralAudioEngine.play()
4. HandlerThread → startPlayback()
5. createAudioTrack() + VolumeShaper (fade-in)
6. generateAudioLoop():
   - applyPendingSettings()
   - checkFadeRequests()
   - nativeEngine.generateBufferDirect()
   - audioTrack.write()
   - update StateFlows (pull-model)
7. UI собирает StateFlow

### При изменении конфигурации

1. UI → BinauralViewModel.updateConfig()
2. BinauralAudioEngine.updateConfig()
3. NativeAudioEngine.updateConfig()
   - Генерация виртуальных точек (если RelaxationMode включён)
   - nativeSetConfig()
4. C++: BinauralEngine::setConfig()
   - BinauralConfig копирование
   - curve.buildLookupTable()
   - std::unique_lock для записи

### При переключении пресета с fade

1. UI → BinauralViewModel.switchPresetWithFade()
2. BinauralAudioEngine.switchPresetWithFade()
3. presetSwitchRequested.set(config)
4. В следующем цикле generateAudioLoop():
   - checkFadeRequests() обнаруживает presetSwitchRequested
   - nativeEngine.updateConfig(newConfig)

---

## Диаграммы

### Диаграмма классов C++

```
┌──────────────────────┐
│   BinauralEngine     │
├──────────────────────┤
│ - m_config           │
│ - m_generator        │
│ - m_state            │
│ - m_callbacks        │
│ - m_batchDuration    │
│ - m_configMutex      │ (shared_mutex)
│ - m_currentBeatFreq  │ (atomic)
│ - m_currentCarrierFreq│ (atomic)
│ - m_elapsedSeconds   │ (atomic)
├──────────────────────┤
│ + setConfig()        │
│ + generateAudioBuffer()│
│ + generateBatch()    │
│ + setPlaying()       │
│ + setCallbacks()     │
│ + getCurrentBeatFrequency()│
│ + getCurrentCarrierFrequency()│
│ + getElapsedSeconds()│
│ + isChannelsSwapped()│
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐     ┌──────────────────────┐
│   AudioGenerator     │     │ BufferPackagePlanner │
├──────────────────────┤     ├──────────────────────┤
│ - m_sampleRate       │     │ + planPackage()      │
├──────────────────────┤     │ + calculateCycleDuration()│
│ + generatePackage()  │     │ + resetState()       │
│ + generatePackageNeon()│    │ + nextPhase()        │
│ + generatePackageSse()│     │ + phaseDuration()    │
│ + updatePhasesOnly() │     └──────────────────────┘
│ + fastPow()          │
│ + fastPowApprox()    │     (создаётся как локальный
└──────────┬───────────┘      объект в BinauralEngine)
           │
           ▼
┌──────────────────────┐     ┌──────────────────────┐
│     Wavetable        │     │   Interpolation      │
├──────────────────────┤     ├──────────────────────┤
│ - s_sineTable[]      │     │ + linear()           │
│ - s_tableSize        │     │ + cardinal()         │
│ - s_tableSizeMask    │     │ + monotone()         │
│ - s_scaleFactor      │     │ + step()             │
├──────────────────────┤     │ + interpolate()      │
│ + fastSin()          │     │ + computeMonotoneSlope()│
│ + fastSinNoInterp()  │     └──────────────────────┘
│ + fastSinNeon()      │
│ + fastSinNeon8()     │
│ + fastSinSse()       │
│ + initialize()       │
└──────────────────────┘
```

### Диаграмма состояний Swap-цикла

```
          ┌──────────────────────────────────────┐
          │                                      │
          ▼                                      │
    ┌───────────┐                                │
    │   SOLID   │ duration = intervalSec * 1000  │
    └─────┬─────┘                                │
          │ timeRemaining == 0                   │
          ▼                                      │
    ┌───────────┐                                │
    │ FADE_OUT  │ duration = fadeDurationMs      │
    └─────┬─────┘                                │
          │ fade completed                       │
          │ swap channels here                   │
          ▼                                      │
    ┌───────────┐                                │
    │   PAUSE   │ duration = pauseDurationMs     │
    └─────┬─────┘ (опционально, может быть 0)    │
          │ pause completed                      │
          ▼                                      │
    ┌───────────┐                                │
    │  FADE_IN  │ duration = fadeDurationMs      │
    └─────┬─────┘                                │
          │ fade completed                       │
          └──────────────────────────────────────┘
```

### Диаграмма состояний воспроизведения

```
          ┌───────────────────────────────────────────────────────────────┐
          │                                                               │
          ▼                                                               │
    ┌───────────┐      play()       ┌───────────┐                        │
    │   IDLE    │──────────────────>│  PLAYING  │                        │
    └───────────┘                   └─────┬─────┘                        │
          ▲                               │                              │
          │                               │ pauseWithFade()              │
          │                               ▼                              │
          │                         ┌───────────┐                        │
          │                         │  PAUSED   │                        │
          │                         └─────┬─────┘                        │
          │                               │                              │
          │                               │ resumeWithFade()             │
          │                               │                              │
          │                               └──────────────────────────────┤
          │                                                              │
          │   stop()                                                     │
          │   stopWithFade()                                             │
          └──────────────────────────────────────────────────────────────┘
```

**Переходы между состояниями:**

| Из состояния | В состояние | Метод | Описание |
|--------------|-------------|-------|----------|
| IDLE | PLAYING | play() | Начало воспроизведения с fade-in |
| PLAYING | PAUSED | pauseWithFade() | Пауза с fade-out, сохранение accumulatedElapsedMs |
| PAUSED | PLAYING | resumeWithFade() | Возобновление с fade-in |
| PLAYING | IDLE | stop() | Немедленная остановка |
| PLAYING | IDLE | stopWithFade() | Остановка с fade-out |
| PAUSED | IDLE | stop() | Немедленная остановка из паузы |
| PAUSED | IDLE | stopWithFade() | Остановка с fade-out из паузы |

**Важно:** При вызове play() во время fade-out (isActive=true, isPlaying=false) происходит прерывание fade и немедленный запуск нового воспроизведения.

### Диаграмма последовательности генерации буфера

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   Kotlin    │  │    JNI      │  │ BinauralEng │  │AudioGenerator│
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │                │
       │ generateBufferDirect()          │                │
       │───────────────>│                │                │
       │                │ generateAudioBuffer()           │
       │                │───────────────>│                │
       │                │                │ planPackage()  │
       │                │                │───────────────>│
       │                │                │<───────────────│
       │                │                │                │
       │                │                │ generatePackageNeon()
       │                │                │───────────────>│
       │                │                │                │ getChannelFrequenciesAt() O(1)
       │                │                │                │ generateSolidBufferNeon() SIMD
       │                │                │<───────────────│
       │                │<───────────────│                │
       │<───────────────│                │                │
       │                │                │                │
       │ getCurrentBeatFrequency()       │                │
       │───────────────>│                │                │
       │<───────────────│ atomic load    │                │
       │                │ g_currentBeatFreq               │
       │                │                │                │
```

### Диаграмма классов Kotlin

```
┌──────────────────────────────────────────────────────────────────┐
│                      BinauralAudioEngine                         │
├──────────────────────────────────────────────────────────────────┤
│ - nativeEngine: NativeAudioEngine                                │
│ - audioTrack: AudioTrack                                         │
│ - volumeShaper: VolumeShaper                                     │
│ - wakeLock: WakeLock                                             │
│ - audioThread: HandlerThread                                     │
│ - configRef: AtomicReference<BinauralConfig>                     │
│ - isActive: AtomicBoolean                                        │
│ - pendingSampleRate, pendingFrequencyUpdateIntervalMs            │
│ - stopWithFadeRequested, pauseWithFadeRequested                  │
│ - presetSwitchRequested                                          │
│ - accumulatedElapsedMs: Long                                     │
│ - userVolume, currentVolume: Float                               │
│ - isFadeInProgress, fadeStartTime, fadeDurationMs                │
├──────────────────────────────────────────────────────────────────┤
│ + initialize(), release()                                        │
│ + play(), stop(), stopWithFade()                                 │
│ + pauseWithFade(), resumeWithFade()                              │
│ + updateConfig(config, relaxationSettings)                       │
│ + updateRelaxationModeSettings(settings)                         │
│ + updateFrequencyCurve(curve)                                    │
│ + setVolume(volume)                                              │
│ + setSampleRate(rate), getSampleRate()                           │
│ + setFrequencyUpdateInterval(intervalMs)                         │
│ + getFrequencyUpdateInterval()                                   │
│ + switchPresetWithFade(config)                                   │
│ + getFrequenciesAtCurrentTime()                                  │
│ + isPowerSaveMode()                                              │
│ + getAdaptiveFrequencyUpdateInterval()                           │
│ + applyPowerSaveMode()                                           │
├──────────────────────────────────────────────────────────────────┤
│ StateFlows:                                                      │
│ - isPlaying, currentConfig                                       │
│ - currentBeatFrequency, currentCarrierFrequency                  │
│ - elapsedSeconds, isChannelsSwapped                              │
└──────────────────────────────┬───────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                      NativeAudioEngine                           │
├──────────────────────────────────────────────────────────────────┤
│ - currentConfig: BinauralConfig?                                 │
│ - relaxationModeSettings: RelaxationModeSettings                 │
│ - isInitialized: Boolean                                         │
├──────────────────────────────────────────────────────────────────┤
│ + initialize(), release()                                        │
│ + updateConfig(config, relaxationSettings)                       │
│ + updateRelaxationModeSettings(settings)                         │
│ + setSampleRate(sampleRate)                                      │
│ + resetState()                                                   │
│ + play(), stop()                                                 │
│ + generateBuffer(buffer, samplesPerChannel)                      │
│ + generateBufferDirect(directBuffer, samplesPerChannel)          │
│ + getCurrentBeatFrequency(), getCurrentCarrierFrequency()        │
│ + getElapsedSeconds(), isChannelsSwapped()                       │
│ + setBatchDurationMinutes(), getBatchDurationMinutes()           │
│ + generateBatch(directBuffer, maxSamplesPerChannel)             │
│ + interpolate(p0, p1, p2, p3, t, type, tension)                  │
│ + generateInterpolatedCurve(timePoints, values, num, type, tens) │
│ + getChannelFrequenciesAt(timePoints, carrier, beat, time, ...)  │
│ - generateStepVirtualPoints(points, settings)                    │
│ - generateSmoothVirtualPoints(points, settings)                  │
│ - interpolateCarrierAtTime(points, time)                         │
│ - interpolateBeatAtTime(points, time)                            │
└──────────────────────────────────────────────────────────────────┘
```

---

## Константы

### AudioConstants (Kotlin)

| Константа | Значение | Описание |
|-----------|----------|----------|
| `MIN_AUDIBLE_FREQUENCY` | 20.0 Гц | Минимальная слышимая человеком частота |
| `MAX_AUDIBLE_FREQUENCY` | 20000.0 Гц | Максимальная слышимая человеком частота |
| `DEFAULT_MAX_CARRIER_FREQUENCY` | 500.0 Гц | Максимальная несущая частота по умолчанию |
| `DEFAULT_MIN_BEAT_FREQUENCY` | 0.0 Гц | Минимальная частота биений по умолчанию |
| `DEFAULT_MAX_BEAT_FREQUENCY` | 1000.0 Гц | Максимальная частота биений по умолчанию |

### Config.h (C++)

| Константа | Значение | Описание |
|-----------|----------|----------|
| `SECONDS_PER_DAY` | 86400 | Количество секунд в сутках |
| `FREQUENCY_TABLE_INTERVAL_MS` | 100 | Интервал дискретизации таблицы частот (мс) |
| `FREQUENCY_TABLE_SIZE` | 864000 | Размер таблицы частот (значений на канал) |

### BinauralAudioEngine (Kotlin)

| Константа | Значение | Описание |
|-----------|----------|----------|
| `BUFFER_SIZE_MS` | 1000 | Размер буфера AudioTrack в мс |
| `MAX_BUFFER_MINUTES` | 10 | Максимум минут в буфере |
| `MAX_BUFFER_BYTES` | ~500 МБ | Максимум байт в буфере |
| `MIN_VOLUME` | 0.001f | Минимальная громкость |
| `PLAYBACK_FADE_DURATION_MS` | 250 | Длительность fade при старте/остановке |
| `POWER_SAVE_INTERVAL_MULTIPLIER` | 3 | Множитель интервала при Battery Saver |

---

## Заключение

Аудиодвижок построен с акцентом на максимальную производительность:

1. **Нативный C++ код** для критичных по времени операций
2. **SIMD-инструкции** для параллельной генерации сэмплов (NEON для ARM, SSE для x86)
3. **Lookup-таблицы** для O(1) доступа к частотам и fade-кривым
4. **Zero-copy JNI** через DirectByteBuffer
5. **Pull-модель** для устранения overhead callbacks через глобальные атомарные переменные
6. **Отдельный поток** с приоритетом AUDIO для генерации
7. **Режим расслабления** для автоматического снижения частот по расписанию
8. **VolumeShaper** для плавных переходов при старте/остановке
9. **Адаптация к энергосбережению** для увеличения времени работы
10. **Пауза в swap-цикле** для дополнительной гибкости

Архитектура обеспечивает плавное воспроизведение бинауральных ритмов с минимальным влиянием на UI и энергопотребление.
