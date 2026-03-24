#include "AudioGenerator.h"
#include <algorithm>
#include <cmath>
#include <cstring>

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#ifdef USE_SSE
#include <immintrin.h>
#endif

// Логирование только в DEBUG сборках
#ifdef AUDIO_DEBUG
#include <android/log.h>
#define LOG_TAG "AudioGenerator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

namespace binaural {

// Предвычисленные константы для оптимизации
static constexpr float ONE_OVER_TWO_PI = 1.0f / AudioGenerator::TWO_PI;

// ========================================================================
// ПРЕДВЫЧИСЛЕННАЯ ТАБЛИЦА ДЛЯ FADE КРИВОЙ (косинусная интерполяция)
// Заменяет дорогой std::cos на table lookup в горячем цикле
// Размер 256 значений даёт точность ~0.4%, достаточную для восприятия
// ========================================================================
struct FadeCurveTable {
    static constexpr int TABLE_SIZE = 2048;  // Соответствует размеру Wavetable для максимальной точности
    static constexpr int TABLE_SIZE_F = TABLE_SIZE;
    float values[TABLE_SIZE + 1];  // +1 для безопасной интерполяции
    
    FadeCurveTable() {
        // Косинусная интерполяция: 0.5 * (1 - cos(t * PI))
        // Обеспечивает плавный S-образный переход
        for (int i = 0; i <= TABLE_SIZE; ++i) {
            const float t = static_cast<float>(i) / TABLE_SIZE;
            values[i] = 0.5f * (1.0f - std::cos(t * static_cast<float>(M_PI)));
        }
    }
    
    // Получить значение fade multiplier (0.0 - 1.0)
    // progress: нормализованная позиция [0.0, 1.0]
    inline float get(float progress) const {
        const float clampedProgress = std::clamp(progress, 0.0f, 1.0f);
        const float scaledIndex = clampedProgress * TABLE_SIZE;
        const int index = static_cast<int>(scaledIndex);
        const float fraction = scaledIndex - index;
        
        // Линейная интерполяция между соседними значениями
        const float y0 = values[index];
        const float y1 = values[index + 1];
        return y0 + fraction * (y1 - y0);
    }
};

// Статический экземпляр - инициализируется один раз при загрузке
static const FadeCurveTable s_fadeCurveTable;

AudioGenerator::AudioGenerator() {
    // Инициализация wavetable при создании генератора
    Wavetable::initialize();
}

void AudioGenerator::setSampleRate(int sampleRate) {
    m_sampleRate = sampleRate;
}

void AudioGenerator::resetState(GeneratorState& state) {
    state.leftPhase = 0.0f;
    state.rightPhase = 0.0f;
    state.channelsSwapped = false;
    state.lastSwapElapsedMs = 0;
    state.totalSamplesGenerated = 0;
    
    // Новая state machine для swap-цикла
    state.swapPhase = SwapPhase::SOLID;
    state.phaseSamplePosition = 0;
    state.solidStartMs = 0;
    
    // Legacy поля (для обратной совместимости)
    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
    state.isFadingOut = true;
    state.fadeStartSample = 0;
    state.pauseStartSample = 0;
}

FrequencyTableResult AudioGenerator::getChannelFrequenciesAt(
    const FrequencyCurve& curve,
    float timeSeconds
) const {
    return curve.getChannelFrequenciesAt(timeSeconds);
}

std::pair<float, float> AudioGenerator::getChannelFrequenciesAtTime(
    const BinauralConfig& config,
    int32_t baseTimeSeconds,
    int64_t offsetMs
) const {
    float offsetSeconds = offsetMs / 1000.0;
    int32_t totalSeconds = static_cast<int32_t>(baseTimeSeconds + offsetSeconds);
    
    totalSeconds = ((totalSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    FrequencyTableResult freqResult = getChannelFrequenciesAt(config.curve, totalSeconds);
    
    return {freqResult.lowerFreq, freqResult.upperFreq};
}

std::pair<float, float> AudioGenerator::calculateNormalizedAmplitudes(
    float leftFreq,
    float rightFreq,
    const BinauralConfig& config,
    const FrequencyCurve& curve
) const {
    float leftAmplitude = 1.0;
    float rightAmplitude = 1.0;
    
    const float strength = std::clamp(config.volumeNormalizationStrength, 0.0f, 2.0f);
    
    switch (config.normalizationType) {
        case NormalizationType::NONE:
            break;
            
        case NormalizationType::CHANNEL: {
            const float minFreq = std::min(leftFreq, rightFreq);
            const float leftNormalized = minFreq / leftFreq;
            const float rightNormalized = minFreq / rightFreq;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
        
        case NormalizationType::TEMPORAL: {
            const float leftNormalized = leftFreq > 0 ? curve.minLowerFreq / leftFreq : 1.0;
            const float rightNormalized = rightFreq > 0 ? curve.minUpperFreq / rightFreq : 1.0;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
    }
    
    return {leftAmplitude, rightAmplitude};
}

// ========================================================================
// СПЕЦИАЛИЗИРОВАННЫЕ ФУНКЦИИ ГЕНЕРАЦИИ
// ========================================================================

void AudioGenerator::generateSolidBuffer(
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
) {
    // Предвычисление констант
    constexpr float baseVolumeFactor = 0.5f;
    
    // Шаги изменения
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    // Текущие значения (нормализованные амплитуды)
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftNormAmp = startLeftAmp;
    float rightNormAmp = startRightAmp;
    
    // ОПТИМИЗАЦИЯ: Branchless phase wrap для лучшей предсказуемости
    if (swapActive) {
        // Swap активен: левый канал -> правый, правый -> левый
        for (int i = 0; i < samples; ++i) {
            const float leftSample = Wavetable::fastSin(state.leftPhase);
            const float rightSample = Wavetable::fastSin(state.rightPhase);
            
            state.leftPhase += leftOmega;
            state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
            
            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
            
            // Swap: правый сэмпл в левый канал, левый в правый
            buffer[i * 2] = rightSample * (baseVolumeFactor * rightNormAmp);
            buffer[i * 2 + 1] = leftSample * (baseVolumeFactor * leftNormAmp);
            
            leftOmega += omegaStepLeft;
            rightOmega += omegaStepRight;
            leftNormAmp += ampStepLeft;
            rightNormAmp += ampStepRight;
        }
    } else {
        // Нормальный режим
        for (int i = 0; i < samples; ++i) {
            const float leftSample = Wavetable::fastSin(state.leftPhase);
            const float rightSample = Wavetable::fastSin(state.rightPhase);
            
            state.leftPhase += leftOmega;
            state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
            
            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
            
            buffer[i * 2] = leftSample * (baseVolumeFactor * leftNormAmp);
            buffer[i * 2 + 1] = rightSample * (baseVolumeFactor * rightNormAmp);
            
            leftOmega += omegaStepLeft;
            rightOmega += omegaStepRight;
            leftNormAmp += ampStepLeft;
            rightNormAmp += ampStepRight;
        }
    }
}

bool AudioGenerator::generateFadeBuffer(
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
) {
    constexpr float baseVolumeFactor = 0.5f;
    const float invFadeDuration = 1.0f / fadeDuration;
    
    // Шаги изменения
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    bool fadeCompleted = false;
    
    // ОПТИМИЗАЦИЯ: Используем предвычисленную таблицу для fade кривой
    // вместо дорогого std::cos на каждом сэмпле
    for (int i = 0; i < samples; ++i) {
        const int fadeProgress = fadeStartOffset + i;
        float fadeMultiplier = 1.0f;
        
        if (fadeProgress >= fadeDuration) {
            // Fade завершён
            fadeMultiplier = fadingOut ? 0.0f : 1.0f;
            fadeCompleted = true;
        } else if (fadeProgress >= 0) {
            // В процессе fade - используем таблицу вместо std::cos
            const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
            const float cosProgress = s_fadeCurveTable.get(progress);
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }
        // Если fadeProgress < 0: fadeMultiplier = 1.0 (ещё не начался или уже завершился)
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        // ОПТИМИЗАЦИЯ: Branchless phase wrap
        state.leftPhase += leftOmega;
        state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float baseAmp = baseVolumeFactor * fadeMultiplier;
        const float leftAmp = baseAmp * leftAmplitude;
        const float rightAmp = baseAmp * rightAmplitude;
        
        if (swapActive) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
        
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
    
    return fadeCompleted;
}

void AudioGenerator::updatePhasesOnly(
    int samples,
    float leftOmega,
    float rightOmega,
    GeneratorState& state
) {
    // Обновляем фазы без генерации звука (для паузы)
    for (int i = 0; i < samples; ++i) {
        state.leftPhase += leftOmega;
        state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
    }
}

// ========================================================================
// ОСНОВНАЯ ФУНКЦИЯ ГЕНЕРАЦИИ (КООРДИНАТОР)
// ========================================================================

GenerateResult AudioGenerator::generateBuffer(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    float timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs;
    GenerateResult result;
    
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
    constexpr float baseVolumeFactor = 0.5f;
    
    // Получаем частоты в начале и конце буфера
    FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, timeSeconds);
    float startLeftFreq = startFreqResult.lowerFreq;
    float startRightFreq = startFreqResult.upperFreq;
    
    FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
        config.curve,
        static_cast<float>(timeSeconds + bufferDurationSeconds)
    );
    float endLeftFreq = endFreqResult.lowerFreq;
    float endRightFreq = endFreqResult.upperFreq;
    
    // Логирование частот начала и конца буфера (только в DEBUG)
    LOGD("BufferGen: time=%.3fs, dur=%.3fs, startFreq=[%.2f, %.2f], endFreq=[%.2f, %.2f], swapped=%d, fadeOp=%d",
         timeSeconds, bufferDurationSeconds,
         startLeftFreq, startRightFreq,
         endLeftFreq, endRightFreq,
         state.channelsSwapped ? 1 : 0,
         static_cast<int>(state.currentFadeOperation));
    
    // Амплитуды
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    // Фазовые инкременты
    const float startLeftOmega = twoPiOverSampleRate * startLeftFreq;
    const float startRightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    // Параметры fade
    const int fadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    // Вычисляем размер буфера в мс
    const int bufferDurationMs = (static_cast<int64_t>(samplesPerChannel) * 1000) / m_sampleRate;
    
    // Проверка необходимости начать fade для перестановки каналов
    // ИСПРАВЛЕНО: swap должен происходить в ТОЧНЫЙ момент времени внутри буфера
    int swapOffsetInBuffer = -1;  // Позиция swap внутри буфера (-1 = нет swap в этом буфере)
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        const int64_t timeUntilSwap = swapIntervalMs - (elapsedMs - state.lastSwapElapsedMs);
        
        // Swap попадает внутрь этого буфера?
        if (timeUntilSwap < bufferDurationMs && timeUntilSwap >= 0) {
            // Вычисляем точную позицию swap в сэмплах от начала буфера
            // timeUntilSwap = 0 → swap в начале буфера (offset = 0)
            // timeUntilSwap = bufferDurationMs → swap в конце буфера
            swapOffsetInBuffer = static_cast<int>((timeUntilSwap * m_sampleRate) / 1000);
            swapOffsetInBuffer = std::clamp(swapOffsetInBuffer, 0, samplesPerChannel - 1);
            
            LOGD("Swap scheduled at offset %d samples (%lld ms) in buffer of %d samples (%d ms)",
                 swapOffsetInBuffer, (long long)timeUntilSwap, samplesPerChannel, bufferDurationMs);
        } else if (timeUntilSwap < 0) {
            // Swap уже просрочен - делаем немедленно в начале буфера
            swapOffsetInBuffer = 0;
            LOGD("Swap overdue by %lld ms, starting at buffer start", -(long long)timeUntilSwap);
        }
    }
    
    // Определяем, какой тип генерации нужен
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && swapOffsetInBuffer < 0) {
        // Самый быстрый путь: сплошной буфер без fade и без swap
        const bool swapActive = channelSwapEnabled && state.channelsSwapped;
        
        generateSolidBuffer(
            buffer,
            samplesPerChannel,
            startLeftOmega,
            startRightOmega,
            endLeftOmega,
            endRightOmega,
            startLeftAmplitude,
            startRightAmplitude,
            endLeftAmplitude,
            endRightAmplitude,
            swapActive,
            state
        );
    } else if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && swapOffsetInBuffer >= 0) {
        // ================================================================
        // НОВОЕ: Swap в точной позиции внутри буфера
        // Генерируем составной буфер: [часть до swap] + [fade] + [часть после swap]
        // ================================================================
        
        const bool currentSwapState = state.channelsSwapped;
        
        // ИСПРАВЛЕНО: Интерполируем омеги для каждой части буфера
        // Часть 1: до swap - омеги от начала до позиции swap
        if (swapOffsetInBuffer > 0) {
            const float ratio1 = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
            const float endLeftOmega1 = startLeftOmega + (endLeftOmega - startLeftOmega) * ratio1;
            const float endRightOmega1 = startRightOmega + (endRightOmega - startRightOmega) * ratio1;
            const float endLeftAmp1 = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratio1;
            const float endRightAmp1 = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratio1;
            
            generateSolidBuffer(
                buffer,
                swapOffsetInBuffer,
                startLeftOmega,
                startRightOmega,
                endLeftOmega1,
                endRightOmega1,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmp1,
                endRightAmp1,
                currentSwapState,
                state
            );
        }
        
        // Выполняем swap
        if (config.channelSwapFadeEnabled) {
            // С fade: начинаем fade-out в точной позиции
            state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
            state.isFadingOut = true;
            state.fadeStartSample = state.totalSamplesGenerated + swapOffsetInBuffer;
            
            // Записываем точное время swap (elapsedMs + время до swap в буфере)
            const int64_t swapTimeInBufferMs = (static_cast<int64_t>(swapOffsetInBuffer) * 1000) / m_sampleRate;
            state.lastSwapElapsedMs = elapsedMs + swapTimeInBufferMs;
            
            // Часть 2: fade-out от позиции swap
            const int samplesAfterSwap = samplesPerChannel - swapOffsetInBuffer;
            const int fadeOutSamples = std::min(fadeDurationSamples, samplesAfterSwap);
            
            // Омеги для fade-out части
            const float ratioFadeStart = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
            const float ratioFadeEnd = static_cast<float>(swapOffsetInBuffer + fadeOutSamples) / samplesPerChannel;
            const float fadeStartLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeStart;
            const float fadeStartRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeStart;
            const float fadeEndLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeEnd;
            const float fadeEndRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeEnd;
            const float fadeStartLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeStart;
            const float fadeStartRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeStart;
            const float fadeEndLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeEnd;
            const float fadeEndRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeEnd;
            
            generateFadeBuffer(
                buffer + swapOffsetInBuffer * 2,
                fadeOutSamples,
                fadeStartLeftOmega,
                fadeStartRightOmega,
                fadeEndLeftOmega,
                fadeEndRightOmega,
                fadeStartLeftAmp,
                fadeStartRightAmp,
                fadeEndLeftAmp,
                fadeEndRightAmp,
                0,
                fadeDurationSamples,
                true,
                currentSwapState,
                state
            );
            
            // Если fade-out завершён и есть место для fade-in
            if (fadeOutSamples >= fadeDurationSamples && samplesAfterSwap > fadeOutSamples) {
                // Переключаем каналы
                state.channelsSwapped = !currentSwapState;
                state.isFadingOut = false;
                state.fadeStartSample = state.totalSamplesGenerated + swapOffsetInBuffer + fadeOutSamples;
                
                // Часть 3: fade-in
                const int fadeInSamples = samplesAfterSwap - fadeOutSamples;
                
                // Омеги для fade-in части
                const float ratioFadeInStart = static_cast<float>(swapOffsetInBuffer + fadeOutSamples) / samplesPerChannel;
                const float ratioFadeInEnd = static_cast<float>(swapOffsetInBuffer + fadeOutSamples + fadeInSamples) / samplesPerChannel;
                const float fadeInStartLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeInStart;
                const float fadeInStartRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeInStart;
                const float fadeInEndLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeInEnd;
                const float fadeInEndRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeInEnd;
                const float fadeInStartLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeInStart;
                const float fadeInStartRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeInStart;
                const float fadeInEndLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeInEnd;
                const float fadeInEndRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeInEnd;
                
                generateFadeBuffer(
                    buffer + (swapOffsetInBuffer + fadeOutSamples) * 2,
                    fadeInSamples,
                    fadeInStartLeftOmega,
                    fadeInStartRightOmega,
                    fadeInEndLeftOmega,
                    fadeInEndRightOmega,
                    fadeInStartLeftAmp,
                    fadeInStartRightAmp,
                    fadeInEndLeftAmp,
                    fadeInEndRightAmp,
                    0,
                    fadeDurationSamples,
                    false,
                    state.channelsSwapped,
                    state
                );
                
                if (fadeInSamples >= fadeDurationSamples) {
                    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                }
            }
            
            result.channelsSwapped = true;
            result.fadePhaseCompleted = true;
        } else {
            // Без fade: мгновенный swap
            state.channelsSwapped = !currentSwapState;
            state.lastSwapElapsedMs = elapsedMs + (static_cast<int64_t>(swapOffsetInBuffer) * 1000) / m_sampleRate;
            result.channelsSwapped = true;
            
            // Часть 2: после swap (новое состояние каналов)
            const int samplesAfterSwap = samplesPerChannel - swapOffsetInBuffer;
            if (samplesAfterSwap > 0) {
                // Омеги для части после swap
                const float ratio2 = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
                const float startLeftOmega2 = startLeftOmega + (endLeftOmega - startLeftOmega) * ratio2;
                const float startRightOmega2 = startRightOmega + (endRightOmega - startRightOmega) * ratio2;
                const float startLeftAmp2 = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratio2;
                const float startRightAmp2 = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratio2;
                
                generateSolidBuffer(
                    buffer + swapOffsetInBuffer * 2,
                    samplesAfterSwap,
                    startLeftOmega2,
                    startRightOmega2,
                    endLeftOmega,
                    endRightOmega,
                    startLeftAmp2,
                    startRightAmp2,
                    endLeftAmplitude,
                    endRightAmplitude,
                    state.channelsSwapped,
                    state
                );
            }
        }
    } else {
        // Путь с fade
        const int64_t currentFadeOffset = state.totalSamplesGenerated - state.fadeStartSample;
        
        // Вычисляем, сколько сэмплов до завершения текущей фазы fade
        const int samplesUntilFadeEnd = fadeDurationSamples - static_cast<int>(currentFadeOffset);
        
        // Проверяем, нужно ли разделить буфер на fade-out + fade-in
        if (state.isFadingOut && samplesUntilFadeEnd < samplesPerChannel && 
            samplesUntilFadeEnd > 0 && config.channelSwapPauseDurationMs == 0) {
            // ================================================================
            // ОПТИМИЗАЦИЯ: fade-out завершается в этом буфере, нет паузы
            // Генерируем обе части: fade-out + fade-in в одном буфере
            // ================================================================
            
            // Часть 1: fade-out до завершения
            generateFadeBuffer(
                buffer,
                samplesUntilFadeEnd,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                static_cast<int>(currentFadeOffset),
                fadeDurationSamples,
                true,  // fadingOut
                state.channelsSwapped,
                state
            );
            
            // Переключаем состояние
            state.channelsSwapped = !state.channelsSwapped;
            state.isFadingOut = false;
            state.fadeStartSample = state.totalSamplesGenerated + samplesUntilFadeEnd;
            
            // Часть 2: fade-in для остатка буфера
            const int remainingSamples = samplesPerChannel - samplesUntilFadeEnd;
            generateFadeBuffer(
                buffer + samplesUntilFadeEnd * 2,  // Сдвиг буфера
                remainingSamples,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                0,  // fade-in начинается с offset=0
                fadeDurationSamples,
                false,  // fadingOut = fade-in
                state.channelsSwapped,
                state
            );
            
            result.fadePhaseCompleted = true;
            result.channelsSwapped = true;
            
            // Если fade-in тоже завершился в этом буфере
            if (remainingSamples >= fadeDurationSamples) {
                state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                // lastSwapElapsedMs уже записан в начале fade-out
            }
        } else {
            // Обычный случай: весь буфер в одной фазе fade
            const bool fadeCompleted = generateFadeBuffer(
                buffer,
                samplesPerChannel,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                static_cast<int>(currentFadeOffset),
                fadeDurationSamples,
                state.isFadingOut,
                state.channelsSwapped,
                state
            );
            
            // Обработка завершения fade-out (переход к паузе или fade-in)
            if (fadeCompleted && state.isFadingOut) {
                result.fadePhaseCompleted = true;
                result.channelsSwapped = true; // Сигнал для BinauralEngine
                
                if (config.channelSwapPauseDurationMs > 0) {
                    // Есть пауза: переходим в состояние PAUSE
                    state.currentFadeOperation = GeneratorState::FadeOperation::PAUSE;
                    state.pauseStartSample = state.totalSamplesGenerated + samplesPerChannel;
                } else {
                    // Без паузы: меняем каналы для следующего буфера
                    state.channelsSwapped = !state.channelsSwapped;
                    state.isFadingOut = false;
                    state.fadeStartSample = state.totalSamplesGenerated + samplesPerChannel;
                }
            } else if (fadeCompleted && !state.isFadingOut) {
                // Fade-in завершён
                state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                // lastSwapElapsedMs уже записан в начале fade-out
                result.fadePhaseCompleted = true;
            }
        }
    }
    
    state.totalSamplesGenerated += samplesPerChannel;
    
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}

// ========================================================================
// NEON-ОПТИМИЗИРОВАННЫЕ ВЕРСИИ
// ========================================================================

#ifdef USE_NEON
void AudioGenerator::generateSolidBufferNeon(
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
) {
    constexpr float baseVolumeFactor = 0.5f;
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    
    // Шаги изменения
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    // NEON константы
    const float32x4_t vScaleFactor = vdupq_n_f32(scaleFactor);
    const float32x4_t vBaseVol = vdupq_n_f32(baseVolumeFactor);
    const float32x4_t vOffsets = {0.0f, 1.0f, 2.0f, 3.0f};
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int neonEnd = samples - 3;
    
    // ИСПРАВЛЕНО: Правильная интерполяция omega внутри NEON-блока
    // omega[i] = baseOmega + step * i для каждого сэмпла в блоке из 4
    // Переменные для амплитуд (используются в обоих ветках if/else)
    float32x4_t vLeftAmps, vRightAmps;
    
    if (swapActive) {
        for (; i < neonEnd; i += 4) {
            // Интерполированные omega для каждого из 4 сэмплов
            float32x4_t vOmegaL = {leftOmega, leftOmega + omegaStepLeft, 
                                   leftOmega + 2*omegaStepLeft, leftOmega + 3*omegaStepLeft};
            float32x4_t vOmegaR = {rightOmega, rightOmega + omegaStepRight,
                                   rightOmega + 2*omegaStepRight, rightOmega + 3*omegaStepRight};
            
            // Интерполированные амплитуды
            float32x4_t vAmpL = {leftAmplitude, leftAmplitude + ampStepLeft,
                                 leftAmplitude + 2*ampStepLeft, leftAmplitude + 3*ampStepLeft};
            float32x4_t vAmpR = {rightAmplitude, rightAmplitude + ampStepRight,
                                 rightAmplitude + 2*ampStepRight, rightAmplitude + 3*ampStepRight};
            
            // Фазы: phase[i] = basePhase + omega[0]*0 + omega[1]*1 + ... = накапливающая сумма
            // Используем накопленную фазу: phase[i] = basePhase + i*omegaBase + step*i*(i-1)/2
            // Для простоты: phase[i] ≈ basePhase + (omega + i*step/2) * i
            float32x4_t vLeftPhases = vaddq_f32(vdupq_n_f32(leftPhaseBase), vmulq_f32(vOmegaL, vOffsets));
            float32x4_t vRightPhases = vaddq_f32(vdupq_n_f32(rightPhaseBase), vmulq_f32(vOmegaR, vOffsets));
            
            float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
            float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
            
            float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
            float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
            
            vLeftAmps = vmulq_f32(vBaseVol, vAmpL);
            vRightAmps = vmulq_f32(vBaseVol, vAmpR);
            
            #ifdef __ARM_FEATURE_FMA
                vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
            #else
                vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
            #endif
            
            // Обновляем базовую фазу с учётом интерполяции
            // Новая фаза = старая + sum(omega[i]) = старая + 4*omegaBase + 6*step
            leftPhaseBase += leftOmega * 4 + omegaStepLeft * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + omegaStepRight * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += omegaStepLeft * 4;
            rightOmega += omegaStepRight * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            // Swap: R L вместо L R
            float32x4x2_t vInterleaved = {vRightSamples, vLeftSamples};
            vst2q_f32(buffer + i * 2, vInterleaved);
        }
    } else {
        for (; i < neonEnd; i += 4) {
            // Интерполированные omega для каждого из 4 сэмплов
            float32x4_t vOmegaL = {leftOmega, leftOmega + omegaStepLeft, 
                                   leftOmega + 2*omegaStepLeft, leftOmega + 3*omegaStepLeft};
            float32x4_t vOmegaR = {rightOmega, rightOmega + omegaStepRight,
                                   rightOmega + 2*omegaStepRight, rightOmega + 3*omegaStepRight};
            
            // Интерполированные амплитуды
            float32x4_t vAmpL = {leftAmplitude, leftAmplitude + ampStepLeft,
                                 leftAmplitude + 2*ampStepLeft, leftAmplitude + 3*ampStepLeft};
            float32x4_t vAmpR = {rightAmplitude, rightAmplitude + ampStepRight,
                                 rightAmplitude + 2*ampStepRight, rightAmplitude + 3*ampStepRight};
            
            float32x4_t vLeftPhases = vaddq_f32(vdupq_n_f32(leftPhaseBase), vmulq_f32(vOmegaL, vOffsets));
            float32x4_t vRightPhases = vaddq_f32(vdupq_n_f32(rightPhaseBase), vmulq_f32(vOmegaR, vOffsets));
            
            float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
            float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
            
            float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
            float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
            
            vLeftAmps = vmulq_f32(vBaseVol, vAmpL);
            vRightAmps = vmulq_f32(vBaseVol, vAmpR);
            
            #ifdef __ARM_FEATURE_FMA
                vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
            #else
                vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
            #endif
            
            // Обновляем базовую фазу с учётом интерполяции
            leftPhaseBase += leftOmega * 4 + omegaStepLeft * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + omegaStepRight * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += omegaStepLeft * 4;
            rightOmega += omegaStepRight * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            float32x4x2_t vInterleaved = {vLeftSamples, vRightSamples};
            vst2q_f32(buffer + i * 2, vInterleaved);
        }
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    // Хвост: скалярная обработка оставшихся 0-3 сэмплов
    for (; i < samples; ++i) {
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float leftAmp = baseVolumeFactor * leftAmplitude;
        const float rightAmp = baseVolumeFactor * rightAmplitude;
        
        if (swapActive) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
        
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
}

bool AudioGenerator::generateFadeBufferNeon(
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
) {
    constexpr float baseVolumeFactor = 0.5f;
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    const float invFadeDuration = 1.0f / fadeDuration;
    
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const float32x4_t vScaleFactor = vdupq_n_f32(scaleFactor);
    const float32x4_t vBaseVol = vdupq_n_f32(baseVolumeFactor);
    const float32x4_t vOffsets = {0.0f, 1.0f, 2.0f, 3.0f};
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    bool fadeCompleted = false;
    int i = 0;
    
    // NEON-обработка блоками по 4
    const int neonEnd = samples - 3;
    
    for (; i < neonEnd; i += 4) {
        // ИСПРАВЛЕНО: Интерполированные omega для каждого из 4 сэмплов
        float32x4_t vOmegaL = {leftOmega, leftOmega + omegaStepLeft, 
                               leftOmega + 2*omegaStepLeft, leftOmega + 3*omegaStepLeft};
        float32x4_t vOmegaR = {rightOmega, rightOmega + omegaStepRight,
                               rightOmega + 2*omegaStepRight, rightOmega + 3*omegaStepRight};
        
        float32x4_t vLeftPhases = vaddq_f32(vdupq_n_f32(leftPhaseBase), vmulq_f32(vOmegaL, vOffsets));
        float32x4_t vRightPhases = vaddq_f32(vdupq_n_f32(rightPhaseBase), vmulq_f32(vOmegaR, vOffsets));
        
        float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
        float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
        
        float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
        float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
        
        // Вычисление fade-множителей для 4 сэмплов
        float fadeMultipliers[4] __attribute__((aligned(16)));
        for (int j = 0; j < 4; ++j) {
            const int fadeProgress = fadeStartOffset + i + j;
            if (fadeProgress >= fadeDuration) {
                fadeMultipliers[j] = fadingOut ? 0.0f : 1.0f;
                if (j == 0) fadeCompleted = true;
            } else if (fadeProgress >= 0) {
                const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
                const float cosProgress = 0.5f * (1.0f - std::cos(progress * static_cast<float>(M_PI)));
                fadeMultipliers[j] = fadingOut ? (1.0f - cosProgress) : cosProgress;
            } else {
                fadeMultipliers[j] = 1.0f;
            }
        }
        
        // Интерполированные амплитуды
        float32x4_t vAmpL = {leftAmplitude, leftAmplitude + ampStepLeft,
                             leftAmplitude + 2*ampStepLeft, leftAmplitude + 3*ampStepLeft};
        float32x4_t vAmpR = {rightAmplitude, rightAmplitude + ampStepRight,
                             rightAmplitude + 2*ampStepRight, rightAmplitude + 3*ampStepRight};
        
        float leftAmps[4] __attribute__((aligned(16)));
        float rightAmps[4] __attribute__((aligned(16)));
        for (int j = 0; j < 4; ++j) {
            leftAmps[j] = baseVolumeFactor * fadeMultipliers[j] * (leftAmplitude + j * ampStepLeft);
            rightAmps[j] = baseVolumeFactor * fadeMultipliers[j] * (rightAmplitude + j * ampStepRight);
        }
        
        float32x4_t vLeftAmps = vld1q_f32(leftAmps);
        float32x4_t vRightAmps = vld1q_f32(rightAmps);
        
        #ifdef __ARM_FEATURE_FMA
            vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
            vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
        #else
            vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
            vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
        #endif
        
        // ИСПРАВЛЕНО: Обновляем фазу с учётом интерполяции omega
        leftPhaseBase += leftOmega * 4 + omegaStepLeft * 6;
        leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
        rightPhaseBase += rightOmega * 4 + omegaStepRight * 6;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        
        leftOmega += omegaStepLeft * 4;
        rightOmega += omegaStepRight * 4;
        leftAmplitude += ampStepLeft * 4;
        rightAmplitude += ampStepRight * 4;
        
        // Запись в буфер
        float leftResult[4] __attribute__((aligned(16)));
        float rightResult[4] __attribute__((aligned(16)));
        vst1q_f32(leftResult, vLeftSamples);
        vst1q_f32(rightResult, vRightSamples);
        
        for (int j = 0; j < 4; ++j) {
            const int idx = (i + j) * 2;
            if (swapActive) {
                buffer[idx] = rightResult[j];
                buffer[idx + 1] = leftResult[j];
            } else {
                buffer[idx] = leftResult[j];
                buffer[idx + 1] = rightResult[j];
            }
        }
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    // Хвост
    for (; i < samples; ++i) {
        const int fadeProgress = fadeStartOffset + i;
        float fadeMultiplier = 1.0f;
        
        if (fadeProgress >= fadeDuration) {
            fadeMultiplier = fadingOut ? 0.0f : 1.0f;
            fadeCompleted = true;
        } else if (fadeProgress >= 0) {
            const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
            const float cosProgress = 0.5f * (1.0f - std::cos(progress * static_cast<float>(M_PI)));
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float baseAmp = baseVolumeFactor * fadeMultiplier;
        const float leftAmp = baseAmp * leftAmplitude;
        const float rightAmp = baseAmp * rightAmplitude;
        
        if (swapActive) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
        
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
    
    return fadeCompleted;
}

/**
 * NEON-оптимизированная версия основной функции
 */
GenerateResult AudioGenerator::generateBufferNeon(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    float timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs;
    GenerateResult result;
    
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, timeSeconds);
    float startLeftFreq = startFreqResult.lowerFreq;
    float startRightFreq = startFreqResult.upperFreq;
    
    FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
        config.curve,
        static_cast<float>(timeSeconds + bufferDurationSeconds)
    );
    float endLeftFreq = endFreqResult.lowerFreq;
    float endRightFreq = endFreqResult.upperFreq;
    
    // Логирование частот начала и конца буфера (только в DEBUG)
    LOGD("BufferGenNeon: time=%.3fs, dur=%.3fs, startFreq=[%.2f, %.2f], endFreq=[%.2f, %.2f], swapped=%d, fadeOp=%d",
         timeSeconds, bufferDurationSeconds,
         startLeftFreq, startRightFreq,
         endLeftFreq, endRightFreq,
         state.channelsSwapped ? 1 : 0,
         static_cast<int>(state.currentFadeOperation));
    
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    const float startLeftOmega = twoPiOverSampleRate * startLeftFreq;
    const float startRightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    const int fadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    // Вычисляем размер буфера в мс
    const int bufferDurationMs = (static_cast<int64_t>(samplesPerChannel) * 1000) / m_sampleRate;
    
    // ИСПРАВЛЕНО: swap должен происходить в ТОЧНЫЙ момент времени внутри буфера
    int swapOffsetInBuffer = -1;  // Позиция swap внутри буфера (-1 = нет swap в этом буфере)
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        const int64_t timeUntilSwap = swapIntervalMs - (elapsedMs - state.lastSwapElapsedMs);
        
        // Swap попадает внутрь этого буфера?
        if (timeUntilSwap < bufferDurationMs && timeUntilSwap >= 0) {
            swapOffsetInBuffer = static_cast<int>((timeUntilSwap * m_sampleRate) / 1000);
            swapOffsetInBuffer = std::clamp(swapOffsetInBuffer, 0, samplesPerChannel - 1);
            
            LOGD("NEON Swap scheduled at offset %d samples (%lld ms) in buffer of %d samples (%d ms)",
                 swapOffsetInBuffer, (long long)timeUntilSwap, samplesPerChannel, bufferDurationMs);
        } else if (timeUntilSwap < 0) {
            swapOffsetInBuffer = 0;
            LOGD("NEON Swap overdue by %lld ms, starting at buffer start", -(long long)timeUntilSwap);
        }
    }
    
    // Коэффициент для интерполяции параметров внутри буфера
    const auto interpolateOmega = [](float start, float end, int offset, int total) -> std::pair<float, float> {
        if (total <= 0) return {start, end};
        const float ratio = static_cast<float>(offset) / total;
        const float omegaAtOffset = start + (end - start) * ratio;
        const float omegaAtEnd = start + (end - start) * ((offset + total) / static_cast<float>(total));
        return {omegaAtOffset, omegaAtEnd};
    };
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && swapOffsetInBuffer < 0) {
        // Самый быстрый путь: сплошной буфер без fade и без swap
        const bool swapActive = channelSwapEnabled && state.channelsSwapped;
        
        generateSolidBufferNeon(
            buffer,
            samplesPerChannel,
            startLeftOmega,
            startRightOmega,
            endLeftOmega,
            endRightOmega,
            startLeftAmplitude,
            startRightAmplitude,
            endLeftAmplitude,
            endRightAmplitude,
            swapActive,
            state
        );
    } else if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && swapOffsetInBuffer >= 0) {
        // ================================================================
        // NEON: Swap в точной позиции внутри буфера
        // Генерируем составной буфер: [часть до swap] + [fade] + [часть после swap]
        // ================================================================
        
        const bool currentSwapState = state.channelsSwapped;
        
        // ИСПРАВЛЕНО: Интерполируем омеги для каждой части буфера
        // Часть 1: до swap - омеги от начала до позиции swap
        if (swapOffsetInBuffer > 0) {
            const float ratio1 = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
            const float endLeftOmega1 = startLeftOmega + (endLeftOmega - startLeftOmega) * ratio1;
            const float endRightOmega1 = startRightOmega + (endRightOmega - startRightOmega) * ratio1;
            const float endLeftAmp1 = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratio1;
            const float endRightAmp1 = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratio1;
            
            generateSolidBufferNeon(
                buffer,
                swapOffsetInBuffer,
                startLeftOmega,
                startRightOmega,
                endLeftOmega1,
                endRightOmega1,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmp1,
                endRightAmp1,
                currentSwapState,
                state
            );
        }
        
        // Выполняем swap
        if (config.channelSwapFadeEnabled) {
            // С fade: начинаем fade-out в точной позиции
            state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
            state.isFadingOut = true;
            state.fadeStartSample = state.totalSamplesGenerated + swapOffsetInBuffer;
            
            const int64_t swapTimeInBufferMs = (static_cast<int64_t>(swapOffsetInBuffer) * 1000) / m_sampleRate;
            state.lastSwapElapsedMs = elapsedMs + swapTimeInBufferMs;
            
            const int samplesAfterSwap = samplesPerChannel - swapOffsetInBuffer;
            const int fadeOutSamples = std::min(fadeDurationSamples, samplesAfterSwap);
            
            // Омеги для fade-out части
            const float ratioFadeStart = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
            const float ratioFadeEnd = static_cast<float>(swapOffsetInBuffer + fadeOutSamples) / samplesPerChannel;
            const float fadeStartLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeStart;
            const float fadeStartRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeStart;
            const float fadeEndLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeEnd;
            const float fadeEndRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeEnd;
            const float fadeStartLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeStart;
            const float fadeStartRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeStart;
            const float fadeEndLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeEnd;
            const float fadeEndRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeEnd;
            
            generateFadeBufferNeon(
                buffer + swapOffsetInBuffer * 2,
                fadeOutSamples,
                fadeStartLeftOmega,
                fadeStartRightOmega,
                fadeEndLeftOmega,
                fadeEndRightOmega,
                fadeStartLeftAmp,
                fadeStartRightAmp,
                fadeEndLeftAmp,
                fadeEndRightAmp,
                0,
                fadeDurationSamples,
                true,
                currentSwapState,
                state
            );
            
            if (fadeOutSamples >= fadeDurationSamples && samplesAfterSwap > fadeOutSamples) {
                state.channelsSwapped = !currentSwapState;
                state.isFadingOut = false;
                state.fadeStartSample = state.totalSamplesGenerated + swapOffsetInBuffer + fadeOutSamples;
                
                const int fadeInSamples = samplesAfterSwap - fadeOutSamples;
                
                // Омеги для fade-in части
                const float ratioFadeInStart = static_cast<float>(swapOffsetInBuffer + fadeOutSamples) / samplesPerChannel;
                const float ratioFadeInEnd = static_cast<float>(swapOffsetInBuffer + fadeOutSamples + fadeInSamples) / samplesPerChannel;
                const float fadeInStartLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeInStart;
                const float fadeInStartRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeInStart;
                const float fadeInEndLeftOmega = startLeftOmega + (endLeftOmega - startLeftOmega) * ratioFadeInEnd;
                const float fadeInEndRightOmega = startRightOmega + (endRightOmega - startRightOmega) * ratioFadeInEnd;
                const float fadeInStartLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeInStart;
                const float fadeInStartRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeInStart;
                const float fadeInEndLeftAmp = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratioFadeInEnd;
                const float fadeInEndRightAmp = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratioFadeInEnd;
                
                generateFadeBufferNeon(
                    buffer + (swapOffsetInBuffer + fadeOutSamples) * 2,
                    fadeInSamples,
                    fadeInStartLeftOmega,
                    fadeInStartRightOmega,
                    fadeInEndLeftOmega,
                    fadeInEndRightOmega,
                    fadeInStartLeftAmp,
                    fadeInStartRightAmp,
                    fadeInEndLeftAmp,
                    fadeInEndRightAmp,
                    0,
                    fadeDurationSamples,
                    false,
                    state.channelsSwapped,
                    state
                );
                
                if (fadeInSamples >= fadeDurationSamples) {
                    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                    // ИСПРАВЛЕНО: После завершения fade обновляем lastSwapElapsedMs
                    // Это время когда swap БЫЛ ЗАПЛАНИРОВАН (не текущее время)
                    // lastSwapElapsedMs уже правильный - время начала fade
                }
            }
            
            result.channelsSwapped = true;
            result.fadePhaseCompleted = true;
        } else {
            // Без fade: мгновенный swap
            state.channelsSwapped = !currentSwapState;
            state.lastSwapElapsedMs = elapsedMs + (static_cast<int64_t>(swapOffsetInBuffer) * 1000) / m_sampleRate;
            result.channelsSwapped = true;
            
            const int samplesAfterSwap = samplesPerChannel - swapOffsetInBuffer;
            if (samplesAfterSwap > 0) {
                // Омеги для части после swap
                const float ratio2 = static_cast<float>(swapOffsetInBuffer) / samplesPerChannel;
                const float startLeftOmega2 = startLeftOmega + (endLeftOmega - startLeftOmega) * ratio2;
                const float startRightOmega2 = startRightOmega + (endRightOmega - startRightOmega) * ratio2;
                const float startLeftAmp2 = startLeftAmplitude + (endLeftAmplitude - startLeftAmplitude) * ratio2;
                const float startRightAmp2 = startRightAmplitude + (endRightAmplitude - startRightAmplitude) * ratio2;
                
                generateSolidBufferNeon(
                    buffer + swapOffsetInBuffer * 2,
                    samplesAfterSwap,
                    startLeftOmega2,
                    startRightOmega2,
                    endLeftOmega,
                    endRightOmega,
                    startLeftAmp2,
                    startRightAmp2,
                    endLeftAmplitude,
                    endRightAmplitude,
                    state.channelsSwapped,
                    state
                );
            }
        }
    } else {
        const int64_t currentFadeOffset = state.totalSamplesGenerated - state.fadeStartSample;
        const int samplesUntilFadeEnd = fadeDurationSamples - static_cast<int>(currentFadeOffset);
        
        // Проверяем, нужно ли разделить буфер на fade-out + fade-in
        if (state.isFadingOut && samplesUntilFadeEnd < samplesPerChannel && 
            samplesUntilFadeEnd > 0 && config.channelSwapPauseDurationMs == 0) {
            // fade-out завершается в этом буфере, нет паузы
            // Часть 1: fade-out
            generateFadeBufferNeon(
                buffer,
                samplesUntilFadeEnd,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                static_cast<int>(currentFadeOffset),
                fadeDurationSamples,
                true,
                state.channelsSwapped,
                state
            );
            
            // Переключаем состояние
            state.channelsSwapped = !state.channelsSwapped;
            state.isFadingOut = false;
            state.fadeStartSample = state.totalSamplesGenerated + samplesUntilFadeEnd;
            
            // Часть 2: fade-in
            const int remainingSamples = samplesPerChannel - samplesUntilFadeEnd;
            generateFadeBufferNeon(
                buffer + samplesUntilFadeEnd * 2,
                remainingSamples,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                0,
                fadeDurationSamples,
                false,
                state.channelsSwapped,
                state
            );
            
            result.fadePhaseCompleted = true;
            result.channelsSwapped = true;
            
            if (remainingSamples >= fadeDurationSamples) {
                state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                // lastSwapElapsedMs уже записан в начале fade-out
            }
        } else {
            const bool fadeCompleted = generateFadeBufferNeon(
                buffer,
                samplesPerChannel,
                startLeftOmega,
                startRightOmega,
                endLeftOmega,
                endRightOmega,
                startLeftAmplitude,
                startRightAmplitude,
                endLeftAmplitude,
                endRightAmplitude,
                static_cast<int>(currentFadeOffset),
                fadeDurationSamples,
                state.isFadingOut,
                state.channelsSwapped,
                state
            );
            
            if (fadeCompleted && state.isFadingOut) {
                result.fadePhaseCompleted = true;
                result.channelsSwapped = true;
                
                if (config.channelSwapPauseDurationMs > 0) {
                    state.currentFadeOperation = GeneratorState::FadeOperation::PAUSE;
                    state.pauseStartSample = state.totalSamplesGenerated + samplesPerChannel;
                } else {
                    state.channelsSwapped = !state.channelsSwapped;
                    state.isFadingOut = false;
                    state.fadeStartSample = state.totalSamplesGenerated + samplesPerChannel;
                }
            } else if (fadeCompleted && !state.isFadingOut) {
                state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
                // lastSwapElapsedMs уже записан в начале fade-out
                result.fadePhaseCompleted = true;
            }
        }
    }
    
    state.totalSamplesGenerated += samplesPerChannel;
    
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}
#endif // USE_NEON

// ========================================================================
// SSE-ОПТИМИЗИРОВАННЫЕ ВЕРСИИ
// ========================================================================

#ifdef USE_SSE
void AudioGenerator::generateSolidBufferSse(
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
) {
    constexpr float baseVolumeFactor = 0.5f;
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const __m128 vScaleFactor = _mm_set1_ps(scaleFactor);
    const __m128 vBaseVol = _mm_set1_ps(baseVolumeFactor);
    const __m128 vOffsets = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int sseEnd = samples - 3;
    
    if (swapActive) {
        for (; i < sseEnd; i += 4) {
            __m128 vOmegaL = _mm_set1_ps(leftOmega);
            __m128 vOmegaR = _mm_set1_ps(rightOmega);
            
            __m128 vLeftPhases = _mm_add_ps(_mm_set1_ps(leftPhaseBase), _mm_mul_ps(vOmegaL, vOffsets));
            __m128 vRightPhases = _mm_add_ps(_mm_set1_ps(rightPhaseBase), _mm_mul_ps(vOmegaR, vOffsets));
            
            __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
            __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
            
            __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
            __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
            
            __m128 vLeftAmps = _mm_mul_ps(vBaseVol, _mm_set1_ps(leftAmplitude));
            __m128 vRightAmps = _mm_mul_ps(vBaseVol, _mm_set1_ps(rightAmplitude));
            
            vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
            vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
            
            leftPhaseBase += leftOmega * 4;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += omegaStepLeft * 4;
            rightOmega += omegaStepRight * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            float leftResult[4] __attribute__((aligned(16)));
            float rightResult[4] __attribute__((aligned(16)));
            _mm_store_ps(leftResult, vLeftSamples);
            _mm_store_ps(rightResult, vRightSamples);
            
            for (int j = 0; j < 4; ++j) {
                buffer[(i + j) * 2] = rightResult[j];
                buffer[(i + j) * 2 + 1] = leftResult[j];
            }
        }
    } else {
        for (; i < sseEnd; i += 4) {
            __m128 vOmegaL = _mm_set1_ps(leftOmega);
            __m128 vOmegaR = _mm_set1_ps(rightOmega);
            
            __m128 vLeftPhases = _mm_add_ps(_mm_set1_ps(leftPhaseBase), _mm_mul_ps(vOmegaL, vOffsets));
            __m128 vRightPhases = _mm_add_ps(_mm_set1_ps(rightPhaseBase), _mm_mul_ps(vOmegaR, vOffsets));
            
            __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
            __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
            
            __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
            __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
            
            __m128 vLeftAmps = _mm_mul_ps(vBaseVol, _mm_set1_ps(leftAmplitude));
            __m128 vRightAmps = _mm_mul_ps(vBaseVol, _mm_set1_ps(rightAmplitude));
            
            vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
            vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
            
            leftPhaseBase += leftOmega * 4;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += omegaStepLeft * 4;
            rightOmega += omegaStepRight * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            float leftResult[4] __attribute__((aligned(16)));
            float rightResult[4] __attribute__((aligned(16)));
            _mm_store_ps(leftResult, vLeftSamples);
            _mm_store_ps(rightResult, vRightSamples);
            
            for (int j = 0; j < 4; ++j) {
                buffer[(i + j) * 2] = leftResult[j];
                buffer[(i + j) * 2 + 1] = rightResult[j];
            }
        }
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    for (; i < samples; ++i) {
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float leftAmp = baseVolumeFactor * leftAmplitude;
        const float rightAmp = baseVolumeFactor * rightAmplitude;
        
        if (swapActive) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
        
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
}

bool AudioGenerator::generateFadeBufferSse(
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
) {
    constexpr float baseVolumeFactor = 0.5f;
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    const float invFadeDuration = 1.0f / fadeDuration;
    
    const float omegaStepLeft = (endLeftOmega - startLeftOmega) / samples;
    const float omegaStepRight = (endRightOmega - startRightOmega) / samples;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const __m128 vScaleFactor = _mm_set1_ps(scaleFactor);
    const __m128 vBaseVol = _mm_set1_ps(baseVolumeFactor);
    const __m128 vOffsets = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    bool fadeCompleted = false;
    int i = 0;
    const int sseEnd = samples - 3;
    
    for (; i < sseEnd; i += 4) {
        __m128 vOmegaL = _mm_set1_ps(leftOmega);
        __m128 vOmegaR = _mm_set1_ps(rightOmega);
        
        __m128 vLeftPhases = _mm_add_ps(_mm_set1_ps(leftPhaseBase), _mm_mul_ps(vOmegaL, vOffsets));
        __m128 vRightPhases = _mm_add_ps(_mm_set1_ps(rightPhaseBase), _mm_mul_ps(vOmegaR, vOffsets));
        
        __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
        __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
        
        __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
        __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
        
        float fadeMultipliers[4] __attribute__((aligned(16)));
        for (int j = 0; j < 4; ++j) {
            const int fadeProgress = fadeStartOffset + i + j;
            if (fadeProgress >= fadeDuration) {
                fadeMultipliers[j] = fadingOut ? 0.0f : 1.0f;
                if (j == 0) fadeCompleted = true;
            } else if (fadeProgress >= 0) {
                const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
                const float cosProgress = 0.5f * (1.0f - std::cos(progress * static_cast<float>(M_PI)));
                fadeMultipliers[j] = fadingOut ? (1.0f - cosProgress) : cosProgress;
            } else {
                fadeMultipliers[j] = 1.0f;
            }
        }
        
        float leftAmps[4] __attribute__((aligned(16)));
        float rightAmps[4] __attribute__((aligned(16)));
        for (int j = 0; j < 4; ++j) {
            leftAmps[j] = baseVolumeFactor * fadeMultipliers[j] * leftAmplitude;
            rightAmps[j] = baseVolumeFactor * fadeMultipliers[j] * rightAmplitude;
        }
        
        __m128 vLeftAmps = _mm_load_ps(leftAmps);
        __m128 vRightAmps = _mm_load_ps(rightAmps);
        
        vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
        vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
        
        leftPhaseBase += leftOmega * 4;
        leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
        rightPhaseBase += rightOmega * 4;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        
        leftOmega += omegaStepLeft * 4;
        rightOmega += omegaStepRight * 4;
        leftAmplitude += ampStepLeft * 4;
        rightAmplitude += ampStepRight * 4;
        
        float leftResult[4] __attribute__((aligned(16)));
        float rightResult[4] __attribute__((aligned(16)));
        _mm_store_ps(leftResult, vLeftSamples);
        _mm_store_ps(rightResult, vRightSamples);
        
        for (int j = 0; j < 4; ++j) {
            const int idx = (i + j) * 2;
            if (swapActive) {
                buffer[idx] = rightResult[j];
                buffer[idx + 1] = leftResult[j];
            } else {
                buffer[idx] = leftResult[j];
                buffer[idx + 1] = rightResult[j];
            }
        }
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    for (; i < samples; ++i) {
        const int fadeProgress = fadeStartOffset + i;
        float fadeMultiplier = 1.0f;
        
        if (fadeProgress >= fadeDuration) {
            fadeMultiplier = fadingOut ? 0.0f : 1.0f;
            fadeCompleted = true;
        } else if (fadeProgress >= 0) {
            const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
            const float cosProgress = 0.5f * (1.0f - std::cos(progress * static_cast<float>(M_PI)));
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float baseAmp = baseVolumeFactor * fadeMultiplier;
        const float leftAmp = baseAmp * leftAmplitude;
        const float rightAmp = baseAmp * rightAmplitude;
        
        if (swapActive) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
        
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
    
    return fadeCompleted;
}

GenerateResult AudioGenerator::generateBufferSse(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    float timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs;
    GenerateResult result;
    
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, timeSeconds);
    float startLeftFreq = startFreqResult.lowerFreq;
    float startRightFreq = startFreqResult.upperFreq;
    
    FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
        config.curve,
        static_cast<float>(timeSeconds + bufferDurationSeconds)
    );
    float endLeftFreq = endFreqResult.lowerFreq;
    float endRightFreq = endFreqResult.upperFreq;
    
    // Логирование частот начала и конца буфера (только в DEBUG)
    LOGD("BufferGenSse: time=%.3fs, dur=%.3fs, startFreq=[%.2f, %.2f], endFreq=[%.2f, %.2f], swapped=%d, fadeOp=%d",
         timeSeconds, bufferDurationSeconds,
         startLeftFreq, startRightFreq,
         endLeftFreq, endRightFreq,
         state.channelsSwapped ? 1 : 0,
         static_cast<int>(state.currentFadeOperation));
    
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    const float startLeftOmega = twoPiOverSampleRate * startLeftFreq;
    const float startRightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    const int fadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    
    // ОПТИМИЗАЦИЯ КРАЕВЫХ FADE: если swap попадает близко к концу буфера,
    // начинаем его раньше чтобы весь цикл (fade-out + fade-in) поместился в одном буфере
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        const int64_t timeUntilSwap = swapIntervalMs - (elapsedMs - state.lastSwapElapsedMs);
        
        const int bufferDurationMs = (static_cast<int64_t>(samplesPerChannel) * 1000) / m_sampleRate;
        
        if (timeUntilSwap <= bufferDurationMs && timeUntilSwap >= 0) {
            const int64_t totalFadeDurationMs = config.channelSwapFadeEnabled 
                ? (config.channelSwapFadeDurationMs * 2 + config.channelSwapPauseDurationMs)
                : 0;
            
            const int64_t timeAfterSwapStart = bufferDurationMs - timeUntilSwap;
            
            const bool needEarlyStart = config.channelSwapFadeEnabled && 
                (timeAfterSwapStart < totalFadeDurationMs) &&
                (timeUntilSwap > 0);
            
            if (timeUntilSwap == 0 || needEarlyStart) {
                if (config.channelSwapFadeEnabled) {
                    state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                    state.isFadingOut = true;
                    state.fadeStartSample = state.totalSamplesGenerated;
                    state.lastSwapElapsedMs = elapsedMs;
                } else {
                    state.channelsSwapped = !state.channelsSwapped;
                    state.lastSwapElapsedMs = elapsedMs;
                    result.channelsSwapped = true;
                }
            }
        } else if (timeUntilSwap <= 0) {
            if (config.channelSwapFadeEnabled) {
                state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                state.isFadingOut = true;
                state.fadeStartSample = state.totalSamplesGenerated;
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                state.channelsSwapped = !state.channelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE) {
        const bool swapActive = channelSwapEnabled && state.channelsSwapped;
        
        generateSolidBufferSse(
            buffer,
            samplesPerChannel,
            startLeftOmega,
            startRightOmega,
            endLeftOmega,
            endRightOmega,
            startLeftAmplitude,
            startRightAmplitude,
            endLeftAmplitude,
            endRightAmplitude,
            swapActive,
            state
        );
    } else {
        const int64_t currentFadeOffset = static_cast<int>(
            state.totalSamplesGenerated - state.fadeStartSample
        );
        
        const bool fadeCompleted = generateFadeBufferSse(
            buffer,
            samplesPerChannel,
            startLeftOmega,
            startRightOmega,
            endLeftOmega,
            endRightOmega,
            startLeftAmplitude,
            startRightAmplitude,
            endLeftAmplitude,
            endRightAmplitude,
            static_cast<int>(currentFadeOffset),
            fadeDurationSamples,
            state.isFadingOut,
            state.channelsSwapped,
            state
        );
        
        if (fadeCompleted && state.isFadingOut) {
            result.fadePhaseCompleted = true;
            result.channelsSwapped = true;
            
            if (config.channelSwapPauseDurationMs > 0) {
                state.currentFadeOperation = GeneratorState::FadeOperation::PAUSE;
                state.pauseStartSample = state.totalSamplesGenerated + samplesPerChannel;
            } else {
                state.channelsSwapped = !state.channelsSwapped;
                state.isFadingOut = false;
                // fadeStartSample = начало следующего буфера (fade-in с offset=0)
                state.fadeStartSample = state.totalSamplesGenerated + samplesPerChannel;
            }
        } else if (fadeCompleted && !state.isFadingOut) {
            state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
            // lastSwapElapsedMs уже записан в начале fade-out
            result.fadePhaseCompleted = true;
        }
    }
    
    state.totalSamplesGenerated += samplesPerChannel;
    
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}
#endif // USE_SSE

// ========================================================================
// ГЕНЕРАЦИЯ ПАКЕТОВ БУФЕРОВ (НОВАЯ АРХИТЕКТУРА)
// ========================================================================

GenerateResult AudioGenerator::generatePackage(
    float* buffer,
    const PackagePlan& plan,
    const BinauralConfig& config,
    GeneratorState& state,
    float startTimeSeconds,
    int64_t elapsedMs
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
    constexpr float baseVolumeFactor = 0.5f;
    
    int currentSample = 0;
    float currentTime = startTimeSeconds;
    int64_t currentElapsedMs = elapsedMs;
    
    // Инициализируем переменные для частот
    float lastLeftFreq = 0.0f;
    float lastRightFreq = 0.0f;
    
    for (const auto& segment : plan.segments) {
        // Вычисляем параметры для сегмента
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(segment.durationMs) / 1000.0f;
        
        if (samples <= 0) continue;
        
        // Получаем частоты для начала и конца сегмента
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, currentTime);
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;
        
        // Вычисляем амплитуды
        auto [startLeftAmp, startRightAmp] = calculateNormalizedAmplitudes(
            startLeftFreq, startRightFreq, config, config.curve
        );
        auto [endLeftAmp, endRightAmp] = calculateNormalizedAmplitudes(
            endLeftFreq, endRightFreq, config, config.curve
        );
        
        // Омеги
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
            
            LOGD("PackageGen: swap at elapsedMs=%lld, channelsSwapped=%d",
                 (long long)currentElapsedMs, state.channelsSwapped ? 1 : 0);
        }
        
        // Обновляем позицию
        currentSample += samples;
        currentTime += durationSec;
        currentElapsedMs += segment.durationMs;
        
        // Сохраняем последние частоты для результата
        lastLeftFreq = endLeftFreq;
        lastRightFreq = endRightFreq;
    }
    
    state.totalSamplesGenerated += currentSample;
    
    // Вычисляем результат
    result.currentBeatFreq = (lastRightFreq - lastLeftFreq);
    result.currentCarrierFreq = (lastLeftFreq + lastRightFreq) / 2.0f;
    
    return result;
}

#ifdef USE_NEON
GenerateResult AudioGenerator::generatePackageNeon(
    float* buffer,
    const PackagePlan& plan,
    const BinauralConfig& config,
    GeneratorState& state,
    float startTimeSeconds,
    int64_t elapsedMs
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    int currentSample = 0;
    float currentTime = startTimeSeconds;
    int64_t currentElapsedMs = elapsedMs;
    
    float lastLeftFreq = 0.0f;
    float lastRightFreq = 0.0f;
    
    for (const auto& segment : plan.segments) {
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(segment.durationMs) / 1000.0f;
        
        if (samples <= 0) continue;
        
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, currentTime);
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;
        
        auto [startLeftAmp, startRightAmp] = calculateNormalizedAmplitudes(
            startLeftFreq, startRightFreq, config, config.curve
        );
        auto [endLeftAmp, endRightAmp] = calculateNormalizedAmplitudes(
            endLeftFreq, endRightFreq, config, config.curve
        );
        
        const float startLeftOmega = twoPiOverSampleRate * startLeftFreq;
        const float startRightOmega = twoPiOverSampleRate * startRightFreq;
        const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
        const float endRightOmega = twoPiOverSampleRate * endRightFreq;
        
        switch (segment.type) {
            case BufferType::SOLID:
                generateSolidBufferNeon(
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
                generateFadeBufferNeon(
                    buffer + currentSample * 2,
                    samples,
                    startLeftOmega, startRightOmega,
                    endLeftOmega, endRightOmega,
                    startLeftAmp, startRightAmp,
                    endLeftAmp, endRightAmp,
                    0,
                    samples,
                    true,
                    state.channelsSwapped,
                    state
                );
                break;
                
            case BufferType::FADE_IN:
                generateFadeBufferNeon(
                    buffer + currentSample * 2,
                    samples,
                    startLeftOmega, startRightOmega,
                    endLeftOmega, endRightOmega,
                    startLeftAmp, startRightAmp,
                    endLeftAmp, endRightAmp,
                    0,
                    samples,
                    false,
                    state.channelsSwapped,
                    state
                );
                break;
        }
        
        if (segment.swapAfterSegment) {
            state.channelsSwapped = !state.channelsSwapped;
            result.channelsSwapped = true;
            
            LOGD("PackageGenNeon: swap at elapsedMs=%lld, channelsSwapped=%d",
                 (long long)currentElapsedMs, state.channelsSwapped ? 1 : 0);
        }
        
        currentSample += samples;
        currentTime += durationSec;
        currentElapsedMs += segment.durationMs;
        
        lastLeftFreq = endLeftFreq;
        lastRightFreq = endRightFreq;
    }
    
    state.totalSamplesGenerated += currentSample;
    
    result.currentBeatFreq = (lastRightFreq - lastLeftFreq);
    result.currentCarrierFreq = (lastLeftFreq + lastRightFreq) / 2.0f;
    
    return result;
}
#endif // USE_NEON

} // namespace binaural