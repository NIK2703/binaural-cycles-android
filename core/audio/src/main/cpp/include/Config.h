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
 * Точка на графике частот
 */
struct FrequencyPoint {
    int32_t timeSeconds;      // Секунды с начала суток (0-86399)
    double carrierFrequency;  // Несущая частота (Гц)
    double beatFrequency;     // Частота биений (Гц)
};

/**
 * Кривая зависимости частот от времени
 */
struct FrequencyCurve {
    std::vector<FrequencyPoint> points;
    InterpolationType interpolationType = InterpolationType::LINEAR;
    float splineTension = 0.0f;  // 0.0 = Catmull-Rom, 1.0 = почти линейный
    
    // Кэш для оптимизации
    double minLowerFreq = 0.0;
    double maxLowerFreq = 0.0;
    double minUpperFreq = 0.0;
    double maxUpperFreq = 0.0;
    int32_t cachedHash = -1;
    
    // Lookup table для O(1) доступа к частотам
    // Размер определяется интервалом обновления частот из настроек
    // При интервале 1 сек: 86400 значений
    // При интервале 10 сек: 8640 значений
    // При интервале 60 сек: 1440 значений
    std::vector<double> lowerFreqTable;  // Нижняя частота канала (carrier - beat/2)
    std::vector<double> upperFreqTable;  // Верхняя частота канала (carrier + beat/2)
    int32_t tableIntervalMs = 10000;     // Интервал в мс, для которого построена таблица
    
    /**
     * Получить частоты каналов для заданного времени через lookup table
     * СЛОЖНОСТЬ: O(1) - один индекс + линейная интерполяция между двумя значениями
     * @param timeSeconds секунды с начала суток (0-86399)
     * @return (нижняя частота, верхняя частота)
     */
    std::pair<double, double> getChannelFrequenciesAt(int32_t timeSeconds) const;
    
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
    
    // Настройки нормализации
    NormalizationType normalizationType = NormalizationType::TEMPORAL;
    float volumeNormalizationStrength = 0.5f;  // 0.0 - 2.0
};

/**
 * Состояние генератора
 */
struct GeneratorState {
    double leftPhase = 0.0;
    double rightPhase = 0.0;
    bool channelsSwapped = false;
    int64_t lastSwapElapsedMs = 0;
    int64_t totalSamplesGenerated = 0;
    
    // Fade состояние
    enum class FadeOperation : int8_t {
        NONE = 0,
        CHANNEL_SWAP = 1,
        PRESET_SWITCH = 2
    };
    
    FadeOperation currentFadeOperation = FadeOperation::NONE;
    bool isFadingOut = true;
    int64_t fadeStartSample = 0;
};

} // namespace binaural