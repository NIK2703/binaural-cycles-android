#pragma once

#include <vector>
#include <cstdint>

namespace binaural {

/**
 * Количество секунд в сутках
 */
constexpr int SECONDS_PER_DAY = 86400;

/**
 * Тип интерполяции между точками
 */
enum class InterpolationType : int8_t {
    LINEAR = 0,     // Линейная интерполяция
    CARDINAL = 1,   // Кардинальный сплайн
    MONOTONE = 2,   // Монотонный сплайн
    STEP = 3        // Ступенчатая интерполяция
};

/**
 * Тип нормализации громкости
 */
enum class NormalizationType : int8_t {
    NONE = 0,       // Без нормализации
    CHANNEL = 1,    // Канальная нормализация
    TEMPORAL = 2    // Временная нормализация
};

/**
 * Результат запроса частот из lookup table
 * Содержит интерполированные частоты для конкретного момента времени
 */
struct FrequencyTableResult {
    float lowerFreq;   // Нижняя частота (левый канал)
    float upperFreq;   // Верхняя частота (правый канал)
};

/**
 * Точка на графике частот
 */
struct FrequencyPoint {
    int32_t timeSeconds;      // Секунды с начала суток (0-86399)
    float carrierFrequency;   // Несущая частота (Гц)
    float beatFrequency;      // Частота биений (Гц)
};

/**
 * Кривая зависимости частот от времени
 */
struct FrequencyCurve {
    std::vector<FrequencyPoint> points;
    InterpolationType interpolationType = InterpolationType::LINEAR;
    float splineTension = 0.0f;  // 0.0 = Catmull-Rom, 1.0 = почти линейный
    
    // Кэш для оптимизации
    float minLowerFreq = 0.0;
    float maxLowerFreq = 0.0;
    float minUpperFreq = 0.0;
    float maxUpperFreq = 0.0;
    int32_t cachedHash = -1;
    
    // Lookup table для O(1) доступа к частотам
    // Размер определяется интервалом обновления частот из настроек
    // При интервале 1 сек: 86400 значений
    // При интервале 10 сек: 8640 значений
    // При интервале 60 сек: 1440 значений
    std::vector<float> lowerFreqTable;  // Нижняя частота канала (carrier - beat/2)
    std::vector<float> upperFreqTable;  // Верхняя частота канала (carrier + beat/2)
    int32_t tableIntervalMs = 10000;     // Интервал в мс, для которого построена таблица
    
    /**
     * Получить частоты каналов для заданного времени через lookup table
     * Возвращает интерполированные частоты для конкретного момента времени
     * @param timeSeconds секунды с начала суток (0-86399.999...), поддерживает дробные значения
     * @return структура с частотами для обоих каналов
     */
    FrequencyTableResult getChannelFrequenciesAt(float timeSeconds) const;
    
    /**
     * Обновить кэш min/max частот
     * Вызывается при изменении точек графика
     */
    void updateCache();
    
    /**
     * Построить lookup table для заданного интервала обновления частот
     * @param intervalMs интервал обновления частот в миллисекундах
     */
    void buildLookupTable(int intervalMs);

private:
    /**
     * Внутренняя реализация построения таблицы
     */
    void buildLookupTableInternal(int intervalSeconds);
};

/**
 * Конфигурация бинаурального ритма
 */
struct BinauralConfig {
    FrequencyCurve curve;
    float volume = 0.7f;
    
    // Настройки перестановки каналов
    bool channelSwapEnabled = false;
    int32_t channelSwapIntervalSec = 300;  // 5 минут
    bool channelSwapFadeEnabled = true;
    int64_t channelSwapFadeDurationMs = 1000;
    int64_t channelSwapPauseDurationMs = 0;  // Пауза между fade-out и fade-in (0 = без паузы)
    
    // Настройки нормализации
    NormalizationType normalizationType = NormalizationType::TEMPORAL;
    float volumeNormalizationStrength = 0.5f;  // 0.0 - 2.0
};

/**
 * Фаза цикла перестановки каналов
 *
 * Цикл: [SOLID N сек] → [FADE_OUT M сек] → [FADE_IN M сек] → [SOLID N сек] → ...
 *                                           ↑
 *                               swap каналов здесь (в конце FADE_IN)
 */
enum class SwapPhase : int8_t {
    SOLID = 0,      // Сплошной буфер без fade (до swap)
    FADE_OUT = 1,   // Затухание (перед swap)
    FADE_IN = 2     // Возрастание (после swap)
};

/**
 * Тип сегмента буфера для генерации пакета
 */
enum class BufferType : int8_t {
    SOLID = 0,     // Сплошной буфер без fade
    FADE_OUT = 1,  // Затухание
    FADE_IN = 2    // Возрастание
};

/**
 * Описание сегмента буфера для генерации пакета
 */
struct BufferSegment {
    BufferType type;           // Тип буфера
    int64_t durationMs;        // Длительность в мс
    bool swapAfterSegment;     // Выполнить swap после этого сегмента
};

/**
 * Результат планирования пакета
 */
struct PackagePlan {
    std::vector<BufferSegment> segments;  // Последовательность сегментов
    int64_t totalDurationMs = 0;          // Общая длительность пакета в мс
    bool endsMidCycle = false;            // Пакет заканчивается в середине цикла
};

/**
 * Состояние генератора
 * Используем float для фаз - совместимость с NEON SIMD и достаточная точность
 */
struct GeneratorState {
    float leftPhase = 0.0f;
    float rightPhase = 0.0f;
    bool channelsSwapped = false;
    int64_t lastSwapElapsedMs = 0;
    int64_t totalSamplesGenerated = 0;
    
    // ================================================================
    // НОВАЯ STATE MACHINE ДЛЯ SWAP-ЦИКЛА
    // ================================================================
    
    // Текущая фаза swap-цикла
    SwapPhase swapPhase = SwapPhase::SOLID;
    
    // Оставшееся время в текущей фазе (в мс)
    // Когда достигает 0, переходим к следующей фазе
    int64_t phaseRemainingMs = 0;
    
    // Позиция внутри цикла для переноса между пакетами
    int64_t cyclePositionMs = 0;
    
    // ================================================================
    // LEGACY ПОЛЯ (для обратной совместимости)
    // Будут удалены после полного перехода на новую архитектуру
    // ================================================================
    
    // Позиция внутри текущей фазы (в сэмплах)
    int64_t phaseSamplePosition = 0;
    
    // Время начала текущего SOLID периода (для вычисления времени до swap)
    int64_t solidStartMs = 0;
    
    // Legacy fade состояние
    enum class FadeOperation : int8_t {
        NONE = 0,
        CHANNEL_SWAP = 1,
        PRESET_SWITCH = 2,
        PAUSE = 3
    };
    
    FadeOperation currentFadeOperation = FadeOperation::NONE;
    bool isFadingOut = true;  // Для CHANNEL_SWAP: true = fade-out, false = fade-in
    int64_t fadeStartSample = 0;
    int64_t pauseStartSample = 0;
};

} // namespace binaural