#pragma once

#include "Config.h"
#include "Wavetable.h"
#include "Interpolation.h"
#include <cmath>
#include <vector>
#include <functional>

namespace binaural {

/**
 * Результат генерации буфера
 */
struct GenerateResult {
    bool fadePhaseCompleted = false;
    bool channelsSwapped = false;
    double currentBeatFreq = 0.0;
    double currentCarrierFreq = 0.0;
};

/**
 * Класс для генерации аудио буфера бинауральных ритмов
 * Оптимизирован для максимальной производительности
 */
class AudioGenerator {
public:
    static constexpr double TWO_PI = 2.0 * M_PI;
    static constexpr int SECONDS_PER_DAY = 86400;
    
    AudioGenerator();
    ~AudioGenerator() = default;
    
    /**
     * Установить частоту дискретизации
     */
    void setSampleRate(int sampleRate);
    
    /**
     * Получить частоту дискретизации
     */
    int getSampleRate() const { return m_sampleRate; }
    
    /**
     * Сгенерировать буфер аудио
     * 
     * @param buffer выходной буфер (interleaved stereo, размер = samplesPerChannel * 2)
     * @param samplesPerChannel количество сэмплов на канал
     * @param config конфигурация
     * @param state состояние генератора (изменяется)
     * @param timeSeconds текущее время в секундах с начала суток
     * @param elapsedMs прошедшее время воспроизведения в мс
     * @return результат генерации
     */
    GenerateResult generateBuffer(
        float* buffer,
        int samplesPerChannel,
        const BinauralConfig& config,
        GeneratorState& state,
        int32_t timeSeconds,
        int64_t elapsedMs
    );
    
    /**
     * Сбросить состояние генератора
     */
    void resetState(GeneratorState& state);

private:
    int m_sampleRate = 44100;
    
    /**
     * Получить частоты каналов для заданного времени с учётом смещения
     */
    std::pair<double, double> getChannelFrequenciesAtTime(
        const BinauralConfig& config,
        int32_t baseTimeSeconds,
        int64_t offsetMs
    ) const;
    
    /**
     * Вычислить нормализованные амплитуды
     */
    std::pair<double, double> calculateNormalizedAmplitudes(
        double leftFreq,
        double rightFreq,
        const BinauralConfig& config,
        const FrequencyCurve& curve
    ) const;
    
    /**
     * Быстрая аппроксимация pow для нормализации
     */
    static inline double fastPow(double x, double n) {
        if (n == 0.0) return 1.0;
        if (n == 1.0) return x;
        if (x <= 0.0) return 0.0;
        // x^n = exp(n * ln(x))
        return std::exp(n * std::log(x));
    }
    
    /**
     * Интерполяция частот каналов напрямую
     */
    void interpolateChannelFrequencies(
        const FrequencyCurve& curve,
        int32_t timeSeconds,
        double& lowerFreq,
        double& upperFreq
    ) const;
    
    /**
     * Найти индекс интервала для времени (бинарный поиск)
     */
    int findIntervalIndex(const std::vector<FrequencyPoint>& sortedPoints, int32_t targetSeconds) const;
};

} // namespace binaural