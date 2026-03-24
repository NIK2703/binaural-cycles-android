# План переработки архитектуры аудиодвижка

## Анализ текущей архитектуры

### Основные компоненты

1. **[`BinauralEngine`](core/audio/src/main/cpp/include/BinauralEngine.h:26)** - главный класс управления
2. **[`AudioGenerator`](core/audio/src/main/cpp/include/AudioGenerator.h:26)** - генерация аудио буферов
3. **[`FrequencyCurve`](core/audio/src/main/cpp/include/Config.h:53)** - кривая частот с lookup table
4. **[`Wavetable`](core/audio/src/main/cpp/include/Wavetable.h)** - таблица синусов

### Текущее понятие "Интервал обновления частот"

Параметр `frequencyUpdateIntervalMs` используется для:
- Определения размера lookup table: `SECONDS_PER_DAY / intervalSeconds`
- Определения рекомендуемого размера буфера
- Передаётся через все функции генерации (но фактически игнорируется)

**Текущий размер таблицы:**
- При 10000 мс (10 сек): 8640 значений
- При 1000 мс (1 сек): 86400 значений
- При 100 мс: 864000 значений

### Проблемы текущей архитектуры

1. **Смешение концептов**: "Интервал обновления частот" объединяет:
   - Размер lookup table (разрешение)
   - Размер буфера генерации (энергопотребление)
   
2. **Избыточная сложность**: Параметр `frequencyUpdateIntervalMs` передаётся через:
   - [`BinauralEngine::setFrequencyUpdateInterval()`](core/audio/src/main/cpp/src/BinauralEngine.cpp:62)
   - [`generateAudioBuffer()`](core/audio/src/main/cpp/src/BinauralEngine.cpp:135)
   - [`AudioGenerator::generateBuffer()`](core/audio/src/main/cpp/src/AudioGenerator.cpp:324)
   - Все SIMD-версии генерации

3. **Фактически не используется**: В коде генерации параметр помечен как `(void)frequencyUpdateIntervalMs;`

---

## Предлагаемая архитектура

### Ключевые изменения

1. **Удалить понятие "Интервал обновления частот"** из API движка
2. **Фиксированный шаг таблицы: 100 мс** (константа)
3. **Таблица строится автоматически** при изменении кривой
4. **Генерация буфера** работает напрямую с таблицей

### Расчёт памяти

```
Шаг таблицы: 100 мс = 0.1 сек
Количество значений: 86400 сек / 0.1 сек = 864000 значений на канал
Память: 864000 * 2 канала * 4 байта = 6.9 MB
```

**Вердикт**: 6.9 MB приемлемо для современных Android-устройств (минимум 2 GB RAM).

### Альтернатива: адаптивный шаг

Если 6.9 MB слишком много, можно использовать адаптивный подход:
- Шаг 100 мс для кривых с быстрыми изменениями
- Шаг 500 мс для плавных кривых
- Автоматический выбор на основе анализа кривой

---

## Детальный план изменений

### Фаза 1: Константы и конфигурация

#### 1.1. Добавить константы в [`Config.h`](core/audio/src/main/cpp/include/Config.h)

```cpp
// Частота дискретизации таблицы частот (фиксированная)
constexpr int FREQUENCY_TABLE_INTERVAL_MS = 100;  // 100 мс
constexpr int FREQUENCY_TABLE_SIZE = SECONDS_PER_DAY * 1000 / FREQUENCY_TABLE_INTERVAL_MS;  // 864000
```

#### 1.2. Изменить структуру [`FrequencyCurve`](core/audio/src/main/cpp/include/Config.h:53)

```cpp
struct FrequencyCurve {
    std::vector<FrequencyPoint> points;
    InterpolationType interpolationType = InterpolationType::LINEAR;
    float splineTension = 0.0f;
    
    // Кэш
    float minLowerFreq = 0.0;
    float maxLowerFreq = 0.0;
    float minUpperFreq = 0.0;
    float maxUpperFreq = 0.0;
    
    // Lookup table с фиксированным шагом 100 мс
    std::vector<float> lowerFreqTable;  // Размер: FREQUENCY_TABLE_SIZE
    std::vector<float> upperFreqTable;
    
    // УДАЛЕНО: int32_t tableIntervalMs - больше не нужен
    
    void updateCache();  // Строит таблицу автоматически
    FrequencyTableResult getChannelFrequenciesAt(float timeSeconds) const;
};
```

### Фаза 2: Интерполяция

#### 2.1. Упростить [`Interpolation.h`](core/audio/src/main/cpp/include/Interpolation.h)

```cpp
// Удалить параметр intervalMs из buildLookupTable
inline void FrequencyCurve::buildLookupTable() {
    // Фиксированный шаг 100 мс
    constexpr int intervalMs = FREQUENCY_TABLE_INTERVAL_MS;
    constexpr int intervalSeconds = 0;  // 100 мс = 0.1 сек (дробный)
    
    // Размер таблицы фиксирован
    const int tableSize = FREQUENCY_TABLE_SIZE;
    
    // ... построение таблицы
}

// Обновить updateCache
inline void FrequencyCurve::updateCache() {
    // ... расчёт min/max
    buildLookupTable();  // Без параметров
}
```

#### 2.2. Упростить [`getChannelFrequenciesAt()`](core/audio/src/main/cpp/include/Interpolation.h:309)

```cpp
inline FrequencyTableResult FrequencyCurve::getChannelFrequenciesAt(float timeSeconds) const {
    // Нормализация времени
    timeSeconds = std::fmod(timeSeconds, static_cast<float>(SECONDS_PER_DAY));
    if (timeSeconds < 0.0f) timeSeconds += SECONDS_PER_DAY;
    
    // Фиксированный шаг 0.1 сек
    constexpr float intervalSeconds = 0.1f;
    
    // Непрерывный индекс
    const float continuousIndex = timeSeconds / intervalSeconds;
    const int currentIndex = static_cast<int>(continuousIndex);
    const float t = continuousIndex - currentIndex;
    
    // Линейная интерполяция между соседними значениями
    const int nextIndex = (currentIndex + 1) % FREQUENCY_TABLE_SIZE;
    
    return {
        Interpolation::linear(lowerFreqTable[currentIndex], lowerFreqTable[nextIndex], t),
        Interpolation::linear(upperFreqTable[currentIndex], upperFreqTable[nextIndex], t)
    };
}
```

### Фаза 3: BinauralEngine

#### 3.1. Удалить из [`BinauralEngine.h`](core/audio/src/main/cpp/include/BinauralEngine.h)

```cpp
// УДАЛИТЬ:
void setFrequencyUpdateInterval(int intervalMs);
int getFrequencyUpdateInterval() const;
int getRecommendedBufferSize() const;
int m_frequencyUpdateIntervalMs;
```

#### 3.2. Упростить [`generateAudioBuffer()`](core/audio/src/main/cpp/src/BinauralEngine.cpp:135)

```cpp
bool BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel) {
    if (!m_isPlaying.load()) return false;
    
    // Вычисляем время
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_generator.getSampleRate();
    float timeSeconds = static_cast<float>(m_baseTimeSeconds) + m_totalBufferTimeSeconds;
    
    // Нормализация
    timeSeconds = std::fmod(timeSeconds, 86400.0f);
    if (timeSeconds < 0.0f) timeSeconds += 86400.0f;
    
    m_totalBufferTimeSeconds += bufferDurationSeconds;
    
    // Генерация БЕЗ параметра frequencyUpdateIntervalMs
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    GenerateResult result = m_generator.generateBuffer(
        buffer, samplesPerChannel, config, m_state, timeSeconds, elapsedMs
    );
    
    // ... остальная логика
}
```

### Фаза 4: AudioGenerator

#### 4.1. Обновить сигнатуры в [`AudioGenerator.h`](core/audio/src/main/cpp/include/AudioGenerator.h)

```cpp
// Упрощённая сигнатура
GenerateResult generateBuffer(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    float timeSeconds,
    int64_t elapsedMs
    // УДАЛЕНО: int frequencyUpdateIntervalMs
);

// Аналогично для generateBufferNeon и generateBufferSse
```

#### 4.2. Удалить неиспользуемый параметр в реализациях

Все функции генерации больше не принимают `frequencyUpdateIntervalMs`.

### Фаза 5: JNI интерфейс

#### 5.1. Обновить [`jni.cpp`](core/audio/src/main/cpp/src/jni.cpp)

Удалить JNI-методы для работы с `frequencyUpdateInterval`:
- `nativeSetFrequencyUpdateInterval`

Обновить сигнатуру `nativeGenerateBuffer`.

### Фаза 6: Java/Kotlin слой

#### 6.1. Удалить из [`NativeAudioEngine.kt`](core/audio/src/main/java/com/binaural/core/audio/engine/NativeAudioEngine.kt)

```kotlin
// УДАЛИТЬ:
external fun nativeSetFrequencyUpdateInterval(intervalMs: Int)
external fun nativeGetRecommendedBufferSize(): Int
```

#### 6.2. Удалить из настроек [`BinauralPreferencesRepository.kt`](data/preferences/src/main/java/com/binaural/data/preferences/BinauralPreferencesRepository.kt)

Удалить preference для `frequency_update_interval`.

#### 6.3. Обновить UI

Удалить настройку "Интервал обновления частот" из [`SettingsScreen.kt`](app/src/main/java/com/binauralcycles/ui/screens/SettingsScreen.kt).

---

## Преимущества новой архитектуры

1. **Простота**: Убран лишний параметр из API
2. **Предсказуемость**: Фиксированное разрешение таблицы
3. **Качество звука**: Шаг 100 мс обеспечивает плавные переходы
4. **Меньше кода**: Упрощение сигнатур функций

## Риски и митигация

| Риск | Митигация |
|------|-----------|
| 6.9 MB памяти | Приемлемо для современных устройств. Можно добавить опцию для старых устройств |
| Потеря гибкости | Фиксированный шаг 100 мс достаточен для всех практических случаев |

---

## Порядок реализации

1. ✅ Анализ текущей архитектуры
2. ⬜ Добавить константы в Config.h
3. ⬜ Переработать FrequencyCurve и Interpolation.h
4. ⬜ Обновить BinauralEngine
5. ⬜ Обновить AudioGenerator
6. ⬜ Обновить JNI интерфейс
7. ⬜ Обновить Kotlin слой
8. ⬜ Удалить настройки из UI
9. ⬜ Тестирование
10. ⬜ Документация

---

## Оценка трудозатрат

| Этап | Время |
|------|-------|
| C++ изменения | 2-3 часа |
| Kotlin изменения | 1 час |
| Тестирование | 1-2 часа |
| **Итого** | **4-6 часов** |
