#pragma once

#include "Config.h"
#include "BufferPackagePlanner.h"
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
    float currentBeatFreq = 0.0;
    float currentCarrierFreq = 0.0;
    int samplesGenerated = 0;  // Реальное количество сгенерированных сэмплов
};

/**
 * Класс для генерации аудио буфера бинауральных ритмов
 * Оптимизирован для максимальной производительности
 */
class AudioGenerator {
public:
    static constexpr float TWO_PI = 2.0 * M_PI;
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
     * Сгенерировать пакет буферов по плану
     *
     * Генерирует последовательность сегментов согласно плану от BufferPackagePlanner.
     * Поддерживает: SOLID, FADE_OUT, PAUSE, FADE_IN сегменты.
     *
     * @param buffer выходной буфер (interleaved stereo)
     * @param plan план пакета с сегментами
     * @param config конфигурация
     * @param state состояние генератора (изменяется)
     * @param startTimeSeconds время начала в секундах с начала суток
     * @param elapsedMs прошедшее время воспроизведения в мс
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
    
#ifdef USE_NEON
    /**
     * NEON-оптимизированная генерация пакета буферов
     * Обрабатывает 4 сэмпла одновременно используя SIMD инструкции ARM NEON.
     *
     * @param buffer выходной буфер (interleaved stereo)
     * @param plan план пакета с сегментами
     * @param config конфигурация
     * @param state состояние генератора (изменяется)
     * @param startTimeSeconds время начала в секундах с начала суток
     * @param elapsedMs прошедшее время воспроизведения в мс
     * @return результат генерации
     */
    GenerateResult generatePackageNeon(
        float* buffer,
        const PackagePlan& plan,
        const BinauralConfig& config,
        GeneratorState& state,
        float startTimeSeconds,
        int64_t elapsedMs
    );
#endif

#ifdef USE_SSE
    /**
     * SSE-оптимизированная генерация пакета буферов для x86/x86_64
     * Обрабатывает 4 сэмпла одновременно используя SSE инструкции.
     *
     * @param buffer выходной буфер (interleaved stereo)
     * @param plan план пакета с сегментами
     * @param config конфигурация
     * @param state состояние генератора (изменяется)
     * @param startTimeSeconds время начала в секундах с начала суток
     * @param elapsedMs прошедшее время воспроизведения в мс
     * @return результат генерации
     */
    GenerateResult generatePackageSse(
        float* buffer,
        const PackagePlan& plan,
        const BinauralConfig& config,
        GeneratorState& state,
        float startTimeSeconds,
        int64_t elapsedMs
    );
#endif
    
    /**
     * Сбросить состояние генератора
     */
    void resetState(GeneratorState& state);

private:
    int m_sampleRate = 44100;
    
    /**
     * Получить частоты каналов для заданного времени с учётом смещения
     */
    std::pair<float, float> getChannelFrequenciesAtTime(
        const BinauralConfig& config,
        int32_t baseTimeSeconds,
        int64_t offsetMs
    ) const;
    
    /**
     * Вычислить нормализованные амплитуды
     */
    std::pair<float, float> calculateNormalizedAmplitudes(
        float leftFreq,
        float rightFreq,
        const BinauralConfig& config,
        const FrequencyCurve& curve
    ) const;
    
    /**
     * Таблица для быстрой аппроксимации pow
     * Кэширует значения x^n для типичных x в диапазоне [0.1, 2.0]
     */
    static constexpr int POW_TABLE_SIZE = 256;
    static constexpr float POW_TABLE_MIN = 0.1;
    static constexpr float POW_TABLE_MAX = 2.0;
    static constexpr float POW_TABLE_STEP = (POW_TABLE_MAX - POW_TABLE_MIN) / POW_TABLE_SIZE;
    
    /**
     * Быстрая аппроксимация pow с использованием таблицы для типичных значений
     * Оптимизация: для x в диапазоне [0.1, 2.0] используем таблицу
     * Для значений вне диапазона используем точный расчёт
     */
    static inline float fastPow(float x, float n) {
        if (n == 0.0) return 1.0;
        if (n == 1.0) return x;
        if (x <= 0.0) return 0.0;
        
        // Для типичных значений нормализации (x в [0.1, 2.0])
        // используем быструю таблицу если n около 0.5-1.0
        if (x >= POW_TABLE_MIN && x <= POW_TABLE_MAX && n >= 0.3 && n <= 2.0) {
            // Линейная интерполяция в таблице
            const float normalizedX = (x - POW_TABLE_MIN) / POW_TABLE_STEP;
            const int index = static_cast<int>(normalizedX);
            
            if (index >= 0 && index < POW_TABLE_SIZE - 1) {
                // Для простоты используем точный расчёт с кэшированием ln(x)
                // Это всё равно быстрее чем полный pow т.к. ln(x) константен для данного x
                static thread_local float cachedLnX = 0.0;
                static thread_local float cachedX = -1.0;
                
                if (cachedX != x) {
                    cachedLnX = std::log(x);
                    cachedX = x;
                }
                return std::exp(n * cachedLnX);
            }
        }
        
        // Точный расчёт для нетипичных значений
        return std::exp(n * std::log(x));
    }
    
    /**
     * Очень быстрая аппроксимация pow для нормализации
     * Использует приближённую формулу: x^n ≈ 1 + n*(x-1) для x близких к 1
     * Точность: ~1% для x в [0.8, 1.2] и n в [0.5, 1.5]
     */
    static inline float fastPowApprox(float x, float n) {
        if (x <= 0.0) return 0.0;
        if (n == 0.0) return 1.0;
        if (n == 1.0) return x;
        
        // Для x близких к 1 (типичный случай для нормализации)
        // используем линейное приближение
        if (x >= 0.8 && x <= 1.2) {
            // x^n ≈ 1 + n*ln(x) для x≈1
            // Более точно: используем разложение Тейлора
            const float lnX = (x - 1.0) - (x - 1.0) * (x - 1.0) / 2.0 + (x - 1.0) * (x - 1.0) * (x - 1.0) / 3.0;
            return 1.0 + n * lnX + n * n * lnX * lnX / 2.0;
        }
        
        // Для остальных - точный расчёт
        return std::exp(n * std::log(x));
    }
    
    /**
     * Получить частоты каналов через lookup table
     * Возвращает интерполированные частоты для конкретного момента времени
     * СЛОЖНОСТЬ: O(1) - прямой доступ к предвычисленным значениям
     * @param timeSeconds секунды с начала суток (поддерживает дробные значения)
     */
    FrequencyTableResult getChannelFrequenciesAt(
        const FrequencyCurve& curve,
        float timeSeconds
    ) const;
    
    // ========================================================================
    // СПЕЦИАЛИЗИРОВАННЫЕ ФУНКЦИИ ГЕНЕРАЦИИ (приватные)
    // ========================================================================
    
    /**
     * Генерация сплошного буфера без fade (скалярная версия)
     */
    void generateSolidBuffer(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        bool swapActive,
        GeneratorState& state
    );
    
    /**
     * Генерация буфера с fade (скалярная версия)
     * @return true если fade завершён
     */
    bool generateFadeBuffer(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        int fadeStartOffset,
        int fadeDuration,
        bool fadingOut,
        bool swapActive,
        GeneratorState& state
    );
    
    /**
     * Обновление фаз без генерации звука (для паузы)
     */
    void updatePhasesOnly(
        int samples,
        float leftOmega,
        float rightOmega,
        GeneratorState& state
    );

#ifdef USE_NEON
    /**
     * NEON-оптимизированная генерация сплошного буфера
     */
    void generateSolidBufferNeon(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        bool swapActive,
        GeneratorState& state
    );
    
    /**
     * NEON-оптимизированная генерация буфера с fade
     */
    bool generateFadeBufferNeon(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        int fadeStartOffset,
        int fadeDuration,
        bool fadingOut,
        bool swapActive,
        GeneratorState& state
    );
#endif

#ifdef USE_SSE
    /**
     * SSE-оптимизированная генерация сплошного буфера
     */
    void generateSolidBufferSse(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        bool swapActive,
        GeneratorState& state
    );
    
    /**
     * SSE-оптимизированная генерация буфера с fade
     */
    bool generateFadeBufferSse(
        float* buffer,
        int samples,
        float startLeftOmega,
        float startRightOmega,
        float endLeftOmega,
        float endRightOmega,
        float startLeftAmp,
        float startRightAmp,
        float endLeftAmp,
        float endRightAmp,
        int fadeStartOffset,
        int fadeDuration,
        bool fadingOut,
        bool swapActive,
        GeneratorState& state
    );
#endif
};

} // namespace binaural
