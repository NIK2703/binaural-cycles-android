# План доработки архитектуры пакетов буферов

## Целевая архитектура

### Типы буферов

```
1. SOLID    — без fade, полная громкость
2. FADE-OUT — от полной до нуля
3. FADE-IN  — от нуля до полной
```

### Цикл swap

```
[SOLID N сек] → [FADE-OUT M сек] → swap → [FADE-IN M сек] → [SOLID N сек] → ...
     ↑                                                              ↓
     └──────────────── ← swap каналов ←─────────────────────────────┘
```

### Параметры

- `N = channelSwapIntervalSec` (интервал перестановки, например 30 сек)
- `M = channelSwapFadeDurationMs` (время затухания/возрастания, например 1 сек)

### Составление пакета

Пакет = последовательность **целых буферов**, пока не достигнем лимита времени.

Пример для 2 минут и интервале 30 сек:

```
Пакет 1: [solid 30s] [fade-out 1s] [fade-in 1s] [solid 30s] [fade-out 1s] [fade-in 1s] [solid 26s]
Пакет 2: [solid 4s] [fade-out 1s] [fade-in 1s] [solid 30s] [fade-out 1s] [fade-in 1s] [solid 26s]
Пакет 3: [solid 4s] ...
```

**Ключевой принцип**: неполный буфер в конце пакета переносится в начало следующего.

---

## Этап 1: Новая структура данных буфера

### 1.1 Добавить структуру BufferSegment в Config.h

```cpp
/**
 * Тип сегмента буфера
 */
enum class BufferType : int8_t {
    SOLID = 0,     // Сплошной буфер без fade
    FADE_OUT = 1,  // Затухание
    FADE_IN = 2    // Возрастание
};

/**
 * Описание сегмента буфера для генерации
 */
struct BufferSegment {
    BufferType type;           // Тип буфера
    int64_t durationMs;        // Длительность в мс
    bool swapAfterSegment;     // Выполнить swap после этого сегмента
    
    // Вспомогательные поля для генерации
    int startSample;           // Начальная позиция в выходном буфере
    int samplesCount;          // Количество сэмплов
};
```

### 1.2 Обновить GeneratorState

```cpp
struct GeneratorState {
    // ... существующие поля ...
    
    // Новая state machine для swap-цикла
    SwapPhase swapPhase = SwapPhase::SOLID;
    int64_t phaseRemainingMs = 0;  // Оставшееся время в текущей фазе
    
    // Позиция в цикле для переноса между пакетами
    int64_t cyclePositionMs = 0;   // Позиция внутри swap-цикла
};
```

---

## Этап 2: Планировщик пакетов

### 2.1 Новый класс BufferPackagePlanner

Создать файл `core/audio/src/main/cpp/include/BufferPackagePlanner.h`:

```cpp
#pragma once

#include "Config.h"
#include <vector>

namespace binaural {

/**
 * Результат планирования пакета
 */
struct PackagePlan {
    std::vector<BufferSegment> segments;
    int64_t totalDurationMs;
    bool endsMidCycle;  // Пакет заканчивается в середине цикла
};

/**
 * Планировщик пакетов буферов
 * Разбивает время пакета на последовательность целых буферов
 */
class BufferPackagePlanner {
public:
    /**
     * Спланировать пакет буферов
     * 
     * @param packageDurationMs Длительность пакета в мс
     * @param config Конфигурация с параметрами swap
     * @param state Текущее состояние (для продолжения с места остановки)
     * @return План пакета с последовательностью сегментов
     */
    PackagePlan planPackage(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state
    );
    
    /**
     * Вычислить длительность полного swap-цикла
     * Цикл = SOLID + FADE_OUT + FADE_IN
     */
    int64_t calculateCycleDuration(const BinauralConfig& config) const;
    
private:
    /**
     * Определить следующую фазу после текущей
     */
    SwapPhase nextPhase(SwapPhase current) const;
    
    /**
     * Вычислить длительность фазы
     */
    int64_t phaseDuration(SwapPhase phase, const BinauralConfig& config) const;
};

} // namespace binaural
```

### 2.2 Реализация BufferPackagePlanner

Создать файл `core/audio/src/main/cpp/src/BufferPackagePlanner.cpp`:

```cpp
#include "BufferPackagePlanner.h"
#include <algorithm>

namespace binaural {

PackagePlan BufferPackagePlanner::planPackage(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state
) {
    PackagePlan plan;
    plan.totalDurationMs = 0;
    plan.endsMidCycle = false;
    
    if (!config.channelSwapEnabled) {
        // Без swap: один сплошной буфер
        BufferSegment segment;
        segment.type = BufferType::SOLID;
        segment.durationMs = packageDurationMs;
        segment.swapAfterSegment = false;
        plan.segments.push_back(segment);
        plan.totalDurationMs = packageDurationMs;
        return plan;
    }
    
    int64_t remainingTime = packageDurationMs;
    SwapPhase currentPhase = state.swapPhase;
    int64_t phaseTimeRemaining = state.phaseRemainingMs;
    
    // Если phaseRemainingMs == 0, начинаем новую фазу
    if (phaseTimeRemaining == 0) {
        phaseTimeRemaining = phaseDuration(currentPhase, config);
    }
    
    while (remainingTime > 0) {
        // Определяем длительность текущего сегмента
        int64_t segmentDuration = std::min(remainingTime, phaseTimeRemaining);
        
        // Создаём сегмент
        BufferSegment segment;
        segment.type = static_cast<BufferType>(currentPhase);
        segment.durationMs = segmentDuration;
        
        // Swap происходит после FADE_IN (в конце цикла)
        segment.swapAfterSegment = (currentPhase == SwapPhase::FADE_IN && 
                                    segmentDuration == phaseTimeRemaining);
        
        plan.segments.push_back(segment);
        plan.totalDurationMs += segmentDuration;
        remainingTime -= segmentDuration;
        phaseTimeRemaining -= segmentDuration;
        
        // Переход к следующей фазе
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = phaseDuration(currentPhase, config);
        }
    }
    
    // Сохраняем состояние для следующего пакета
    state.swapPhase = currentPhase;
    state.phaseRemainingMs = phaseTimeRemaining;
    plan.endsMidCycle = (phaseTimeRemaining > 0);
    
    return plan;
}

int64_t BufferPackagePlanner::calculateCycleDuration(const BinauralConfig& config) const {
    if (!config.channelSwapEnabled) {
        return 0;  // Нет цикла без swap
    }
    
    return config.channelSwapIntervalSec * 1000LL +  // SOLID
           config.channelSwapFadeDurationMs +        // FADE_OUT
           config.channelSwapFadeDurationMs;         // FADE_IN
}

SwapPhase BufferPackagePlanner::nextPhase(SwapPhase current) const {
    switch (current) {
        case SwapPhase::SOLID:    return SwapPhase::FADE_OUT;
        case SwapPhase::FADE_OUT: return SwapPhase::FADE_IN;
        case SwapPhase::FADE_IN:  return SwapPhase::SOLID;
    }
    return SwapPhase::SOLID;
}

int64_t BufferPackagePlanner::phaseDuration(SwapPhase phase, const BinauralConfig& config) const {
    switch (phase) {
        case SwapPhase::SOLID:    return config.channelSwapIntervalSec * 1000LL;
        case SwapPhase::FADE_OUT: return config.channelSwapFadeDurationMs;
        case SwapPhase::FADE_IN:  return config.channelSwapFadeDurationMs;
    }
    return 0;
}

} // namespace binaural
```

---

## Этап 3: Модификация AudioGenerator

### 3.1 Новый метод generatePackage

Добавить в [`AudioGenerator.h`](core/audio/src/main/cpp/include/AudioGenerator.h):

```cpp
/**
 * Сгенерировать пакет буферов по плану
 * 
 * @param buffer выходной буфер
 * @param plan план пакета с сегментами
 * @param config конфигурация
 * @param state состояние генератора
 * @param startTimeSeconds время начала в секундах
 * @param elapsedMs прошедшее время воспроизведения
 * @return результат генерации
 */
GenerateResult generatePackage(
    float* buffer,
    const PackagePlan& plan,
    const BinauralConfig& config,
    GeneratorState& state,
    float startTimeSeconds,
    int64_t elapsedMs
);
```

### 3.2 Реализация generatePackage

```cpp
GenerateResult AudioGenerator::generatePackage(
    float* buffer,
    const PackagePlan& plan,
    const BinauralConfig& config,
    GeneratorState& state,
    float startTimeSeconds,
    int64_t elapsedMs
) {
    GenerateResult result;
    int currentSample = 0;
    float currentTime = startTimeSeconds;
    int64_t currentElapsedMs = elapsedMs;
    
    for (const auto& segment : plan.segments) {
        // Вычисляем параметры для сегмента
        const int samples = (segment.durationMs * m_sampleRate) / 1000;
        const float durationSec = static_cast<float>(segment.durationMs) / 1000.0f;
        
        // Получаем частоты для начала и конца сегмента
        auto [startLeftFreq, startRightFreq] = getChannelFrequenciesAt(
            config.curve, currentTime
        );
        auto [endLeftFreq, endRightFreq] = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        
        // Вычисляем амплитуды
        auto [startLeftAmp, startRightAmp] = calculateNormalizedAmplitudes(
            startLeftFreq, startRightFreq, config, config.curve
        );
        auto [endLeftAmp, endRightAmp] = calculateNormalizedAmplitudes(
            endLeftFreq, endRightFreq, config, config.curve
        );
        
        // Омеги
        const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
        const float startLeftOmega = twoPiOverSampleRate * startLeftFreq;
        const float startRightOmega = twoPiOverSampleRate * startRightFreq;
        const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
        const float endRightOmega = twoPiOverSampleRate * endRightFreq;
        
        // Генерируем сегмент в зависимости от типа
        switch (segment.type) {
            case BufferType::SOLID:
                generateSolidBuffer(
                    buffer + currentSample * 2,
                    samples,
                    startLeftOmega, startRightOmega,
                    endLeftOmega, endRightOmega,
                    startLeftAmp, startRightAmp,
                    endLeftAmp, endRightAmp,
                    state.channelsSwapped,
                    state
                );
                break;
                
            case BufferType::FADE_OUT:
                generateFadeBuffer(
                    buffer + currentSample * 2,
                    samples,
                    startLeftOmega, startRightOmega,
                    endLeftOmega, endRightOmega,
                    startLeftAmp, startRightAmp,
                    endLeftAmp, endRightAmp,
                    0,  // fadeStartOffset
                    samples,  // fadeDuration = длительность сегмента
                    true,  // fadingOut
                    state.channelsSwapped,
                    state
                );
                break;
                
            case BufferType::FADE_IN:
                generateFadeBuffer(
                    buffer + currentSample * 2,
                    samples,
                    startLeftOmega, startRightOmega,
                    endLeftOmega, endRightOmega,
                    startLeftAmp, startRightAmp,
                    endLeftAmp, endRightAmp,
                    0,  // fadeStartOffset
                    samples,  // fadeDuration = длительность сегмента
                    false,  // fadingOut = fade-in
                    state.channelsSwapped,
                    state
                );
                break;
        }
        
        // Swap после сегмента если нужно
        if (segment.swapAfterSegment) {
            state.channelsSwapped = !state.channelsSwapped;
            result.channelsSwapped = true;
        }
        
        // Обновляем позицию
        currentSample += samples;
        currentTime += durationSec;
        currentElapsedMs += segment.durationMs;
    }
    
    state.totalSamplesGenerated += currentSample;
    
    return result;
}
```

---

## Этап 4: Интеграция в BinauralEngine

### 4.1 Модификация generateBatch

Обновить [`BinauralEngine::generateBatch()`](core/audio/src/main/cpp/src/BinauralEngine.cpp:78):

```cpp
int BinauralEngine::generateBatch(float* buffer, int maxSamplesPerChannel) {
    if (!m_isPlaying.load(std::memory_order_acquire) || m_batchDurationMinutes <= 0) {
        return 0;
    }
    
    const int sampleRate = m_generator.getSampleRate();
    const int64_t packageDurationMs = m_batchDurationMinutes * 60 * 1000LL;
    const int maxSamples = m_batchDurationMinutes * 60 * sampleRate;
    const int samplesToGenerate = std::min(maxSamples, maxSamplesPerChannel);
    
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // Планируем пакет
    BufferPackagePlanner planner;
    PackagePlan plan = planner.planPackage(packageDurationMs, config, m_state);
    
    // Генерируем пакет
    int32_t timeSeconds = static_cast<int32_t>(m_baseTimeSeconds + m_totalBufferTimeSeconds);
    constexpr int32_t SECONDS_PER_DAY = 86400;
    timeSeconds = ((timeSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    const int64_t elapsedMs = static_cast<int64_t>(
        m_elapsedSeconds.load(std::memory_order_relaxed)
    ) * 1000;
    
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        static_cast<float>(timeSeconds),
        elapsedMs
    );
    
    // Обновляем время
    const float batchDurationSeconds = static_cast<float>(samplesToGenerate) / sampleRate;
    m_totalBufferTimeSeconds += batchDurationSeconds;
    
    return samplesToGenerate;
}
```

---

## Этап 5: NEON-оптимизация

### 5.1 NEON-версия generatePackage

Добавить NEON-оптимизированную версию для каждого типа буфера:

```cpp
#ifdef USE_NEON
GenerateResult AudioGenerator::generatePackageNeon(
    float* buffer,
    const PackagePlan& plan,
    const BinauralConfig& config,
    GeneratorState& state,
    float startTimeSeconds,
    int64_t elapsedMs
) {
    // Аналогично скалярной версии, но с вызовом NEON-функций
    // generateSolidBufferNeon, generateFadeBufferNeon
}
#endif
```

---

## Этап 6: Тестирование

### 6.1 Unit-тесты для BufferPackagePlanner

```cpp
// test/BufferPackagePlannerTest.cpp

TEST(BufferPackagePlannerTest, SingleCyclePackage) {
    BufferPackagePlanner planner;
    BinauralConfig config;
    config.channelSwapEnabled = true;
    config.channelSwapIntervalSec = 30;
    config.channelSwapFadeDurationMs = 1000;
    
    GeneratorState state;
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    
    // Пакет 62 секунды = 1 полный цикл (30+1+1) + начало следующего (30)
    PackagePlan plan = planner.planPackage(62000, config, state);
    
    EXPECT_EQ(plan.segments.size(), 4);
    EXPECT_EQ(plan.segments[0].type, BufferType::SOLID);
    EXPECT_EQ(plan.segments[0].durationMs, 30000);
    EXPECT_EQ(plan.segments[1].type, BufferType::FADE_OUT);
    EXPECT_EQ(plan.segments[1].durationMs, 1000);
    EXPECT_EQ(plan.segments[2].type, BufferType::FADE_IN);
    EXPECT_EQ(plan.segments[2].durationMs, 1000);
    EXPECT_TRUE(plan.segments[2].swapAfterSegment);
    EXPECT_EQ(plan.segments[3].type, BufferType::SOLID);
    EXPECT_EQ(plan.segments[3].durationMs, 30000);
}

TEST(BufferPackagePlannerTest, ContinuationAcrossPackages) {
    BufferPackagePlanner planner;
    BinauralConfig config;
    config.channelSwapEnabled = true;
    config.channelSwapIntervalSec = 30;
    config.channelSwapFadeDurationMs = 1000;
    
    GeneratorState state;
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    
    // Первый пакет: 50 секунд
    PackagePlan plan1 = planner.planPackage(50000, config, state);
    
    // Проверяем, что состояние сохранено для продолжения
    EXPECT_EQ(state.swapPhase, SwapPhase::SOLID);
    EXPECT_EQ(state.phaseRemainingMs, 12000);  // 30 - (50 - 30 - 2) = 12
    
    // Второй пакет: продолжение
    PackagePlan plan2 = planner.planPackage(10000, config, state);
    
    EXPECT_EQ(plan2.segments[0].type, BufferType::SOLID);
    EXPECT_EQ(plan2.segments[0].durationMs, 10000);
}
```

---

## Порядок реализации

1. **Этап 1** — Добавить новые структуры данных (Config.h)
2. **Этап 2** — Реализовать BufferPackagePlanner
3. **Этап 3** — Добавить generatePackage в AudioGenerator
4. **Этап 4** — Интегрировать в BinauralEngine
5. **Этап 5** — NEON-оптимизация
6. **Этап 6** — Тестирование

## Оценка времени

| Этап | Время |
|------|-------|
| Этап 1 | 30 мин |
| Этап 2 | 2 часа |
| Этап 3 | 3 часа |
| Этап 4 | 1 час |
| Этап 5 | 2 часа |
| Этап 6 | 2 часа |
| **Итого** | ~10.5 часов |

## Риски

1. **Обратная совместимость** — старый код может полагаться на текущую структуру
2. **Производительность** — дополнительный слой абстракции может добавить накладные расходы
3. **Точность времени** — необходимо аккуратно работать с миллисекундами и сэмплами

## Метрики успеха

1. Пакеты формируются как последовательность целых буферов
2. Неполный буфер корректно переносится в следующий пакет
3. Swap происходит точно в конце FADE_IN
4. Производительность не ухудшается более чем на 5%
