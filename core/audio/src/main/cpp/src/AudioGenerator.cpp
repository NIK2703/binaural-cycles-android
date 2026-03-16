#include "AudioGenerator.h"
#include <algorithm>
#include <cmath>

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
    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
    state.isFadingOut = true;
    state.fadeStartSample = 0;
}

/**
 * Получить частоты каналов через lookup table
 * СЛОЖНОСТЬ: O(1) - прямой доступ к предвычисленным значениям
 * Возвращает текущие и следующие значения для интерполяции внутри буфера
 */
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
    // Вычисляем время с учётом смещения
    float offsetSeconds = offsetMs / 1000.0;
    int32_t totalSeconds = static_cast<int32_t>(baseTimeSeconds + offsetSeconds);
    
    // Нормализуем в пределах суток
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
            // Без нормализации
            break;
            
        case NormalizationType::CHANNEL: {
            // Канальная нормализация (уравнивание между левым и правым каналом)
            const float minFreq = std::min(leftFreq, rightFreq);
            const float leftNormalized = minFreq / leftFreq;
            const float rightNormalized = minFreq / rightFreq;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
        
        case NormalizationType::TEMPORAL: {
            // Временная нормализация
            const float leftNormalized = leftFreq > 0 ? curve.minLowerFreq / leftFreq : 1.0;
            const float rightNormalized = rightFreq > 0 ? curve.minUpperFreq / rightFreq : 1.0;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
    }
    
    return {leftAmplitude, rightAmplitude};
}

GenerateResult AudioGenerator::generateBuffer(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    int32_t timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs; // Не используется в скалярной версии
    GenerateResult result;
    
    // Длительность буфера в секундах (для вычисления конечных частот)
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    
    // Получаем частоты в начале буфера
    FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, timeSeconds);
    float startLeftFreq = startFreqResult.lowerFreq;
    float startRightFreq = startFreqResult.upperFreq;
    
    // Получаем частоты в конце буфера (для плавной интерполяции внутри буфера)
    FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
        config.curve,
        static_cast<float>(timeSeconds + bufferDurationSeconds)
    );
    float endLeftFreq = endFreqResult.lowerFreq;
    float endRightFreq = endFreqResult.upperFreq;
    
    // Начальные амплитуды
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    
    // Конечные амплитуды
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    // Предвычисление констант
    const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
    const float baseVolumeFactor = 0.5f * config.volume;
    
    // Фазовые инкременты
    float leftOmega = twoPiOverSampleRate * startLeftFreq;
    float rightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    // Шаги изменения фазовых инкрементов
    const float omegaStepLeft = (endLeftOmega - leftOmega) / samplesPerChannel;
    const float omegaStepRight = (endRightOmega - rightOmega) / samplesPerChannel;
    
    // Текущие амплитуды
    float leftAmplitude = startLeftAmplitude;
    float rightAmplitude = startRightAmplitude;
    
    // Шаги изменения амплитуд
    const float ampStepLeft = (endLeftAmplitude - startLeftAmplitude) / samplesPerChannel;
    const float ampStepRight = (endRightAmplitude - startRightAmplitude) / samplesPerChannel;
    
    // Параметры fade
    const int channelSwapFadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    // Локальные переменные для состояния
    bool localIsFadingOut = state.isFadingOut;
    int64_t localFadeStartSample = state.fadeStartSample;
    bool localChannelsSwapped = state.channelsSwapped;
    GeneratorState::FadeOperation localFadeOperation = state.currentFadeOperation;
    int64_t swapExecutedAtSample = -1;
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    // Проверка перестановки каналов
    if (localFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                localFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                localIsFadingOut = true;
                localFadeStartSample = state.totalSamplesGenerated;
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                localChannelsSwapped = !localChannelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    // ========================================================================
    // ОПТИМИЗИРОВАННЫЙ ГОРЯЧИЙ ПУТЬ: без fade - наиболее частый случай
    // ========================================================================
    
    const bool hasFade = localFadeOperation != GeneratorState::FadeOperation::NONE;
    
    // Предвычисление констант для оптимизации горячего пути
    const float invSamples = 1.0f / samplesPerChannel;
    const float leftAmpIncrement = (endLeftAmplitude - startLeftAmplitude) * invSamples;
    const float rightAmpIncrement = (endRightAmplitude - startRightAmplitude) * invSamples;
    
    // Предвычисляем начальные амплитуды с volume factor (избегаем умножения в цикле)
    float currentLeftAmp = baseVolumeFactor * leftAmplitude;
    float currentRightAmp = baseVolumeFactor * rightAmplitude;
    const float ampIncrementLeft = baseVolumeFactor * leftAmpIncrement;
    const float ampIncrementRight = baseVolumeFactor * rightAmpIncrement;
    
    if (!hasFade) {
        // Быстрый путь: нет fade, нет перестановки в середине буфера
        const bool swapActive = channelSwapEnabled && channelsSwappedAtStart;
        
        if (!swapActive) {
            // Самый быстрый путь: нет fade, нет swap
            for (int i = 0; i < samplesPerChannel; ++i) {
                // Генерация синусов через wavetable
                const float leftSample = Wavetable::fastSin(state.leftPhase);
                const float rightSample = Wavetable::fastSin(state.rightPhase);
                
                // Обновление фаз (оптимизированный wrap)
                state.leftPhase += leftOmega;
                if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;
                
                state.rightPhase += rightOmega;
                if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
                
                // Запись в буфер (interleaved stereo) - амплитуды предвычислены
                buffer[i * 2] = leftSample * currentLeftAmp;
                buffer[i * 2 + 1] = rightSample * currentRightAmp;
                
                // Обновление инкрементов
                leftOmega += omegaStepLeft;
                rightOmega += omegaStepRight;
                currentLeftAmp += ampIncrementLeft;
                currentRightAmp += ampIncrementRight;
            }
        } else {
            // Быстрый путь: нет fade, но swap активен
            for (int i = 0; i < samplesPerChannel; ++i) {
                const float leftSample = Wavetable::fastSin(state.leftPhase);
                const float rightSample = Wavetable::fastSin(state.rightPhase);
                
                state.leftPhase += leftOmega;
                if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;
                
                state.rightPhase += rightOmega;
                if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
                
                // Swap: левый канал -> правый, правый -> левый
                buffer[i * 2] = rightSample * currentRightAmp;
                buffer[i * 2 + 1] = leftSample * currentLeftAmp;
                
                leftOmega += omegaStepLeft;
                rightOmega += omegaStepRight;
                currentLeftAmp += ampIncrementLeft;
                currentRightAmp += ampIncrementRight;
            }
        }
    } else {
        // Медленный путь: есть fade (редкое событие)
        const float invFadeDuration = 1.0f / channelSwapFadeDurationSamples;
        
        for (int i = 0; i < samplesPerChannel; ++i) {
            const int64_t currentSample = state.totalSamplesGenerated + i;
            float fadeMultiplier = 1.0;
            
            // Обработка fade
            const int64_t elapsedSamples = currentSample - localFadeStartSample;
            const float progress = elapsedSamples * invFadeDuration;
            
            if (localIsFadingOut) {
                if (progress >= 1.0) {
                    fadeMultiplier = 0.0;
                    if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                        swapExecutedAtSample = currentSample;
                        localChannelsSwapped = !localChannelsSwapped;
                        result.channelsSwapped = true;
                        localIsFadingOut = false;
                        localFadeStartSample = currentSample;
                    }
                } else if (progress >= 0.0) {
                    fadeMultiplier = 1.0 - progress;
                }
            } else {
                if (progress >= 1.0) {
                    fadeMultiplier = 1.0;
                    result.fadePhaseCompleted = true;
                    localFadeOperation = GeneratorState::FadeOperation::NONE;
                } else if (progress >= 0.0) {
                    fadeMultiplier = progress;
                }
            }
            
            // Генерация синусов через wavetable
            const float leftSample = Wavetable::fastSin(state.leftPhase);
            const float rightSample = Wavetable::fastSin(state.rightPhase);
            
            // Обновление фаз (branchless)
            state.leftPhase += leftOmega;
            state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
            
            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
            
            // Вычисление амплитуд
            const float baseAmplitude = baseVolumeFactor * fadeMultiplier;
            const float leftAmp = baseAmplitude * leftAmplitude;
            const float rightAmp = baseAmplitude * rightAmplitude;
            
            // Обновление инкрементов
            leftOmega += omegaStepLeft;
            rightOmega += omegaStepRight;
            leftAmplitude += ampStepLeft;
            rightAmplitude += ampStepRight;
            
            // Определение перестановки каналов для текущего сэмпла
            const bool swapForSample = channelSwapEnabled && 
                ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
                 localChannelsSwapped : channelsSwappedAtStart);
            
            // Запись в буфер (interleaved stereo)
            if (swapForSample) {
                buffer[i * 2] = rightSample * rightAmp;
                buffer[i * 2 + 1] = leftSample * leftAmp;
            } else {
                buffer[i * 2] = leftSample * leftAmp;
                buffer[i * 2 + 1] = rightSample * rightAmp;
            }
        }
    }
    
    // Обновление состояния
    state.channelsSwapped = localChannelsSwapped;
    state.isFadingOut = localIsFadingOut;
    state.fadeStartSample = localFadeStartSample;
    state.currentFadeOperation = localFadeOperation;
    state.totalSamplesGenerated += samplesPerChannel;
    
    // Текущие частоты для UI
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}

#ifdef USE_NEON
/**
 * NEON-оптимизированная генерация буфера
 * Полностью векторизованная версия с использованием FMA и vst2 для interleaved output
 */
GenerateResult AudioGenerator::generateBufferNeon(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    int32_t timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs; // Не используется в NEON версии
    GenerateResult result;
    
    // Длительность буфера в секундах
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    
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
    
    // Амплитуды
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    // Константы для NEON
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    const float baseVolumeFactor = 0.5f * config.volume;
    
    // Фазовые инкременты
    float leftOmega = twoPiOverSampleRate * startLeftFreq;
    float rightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    // Шаги изменения
    const float omegaStepLeft = (endLeftOmega - leftOmega) / samplesPerChannel;
    const float omegaStepRight = (endRightOmega - rightOmega) / samplesPerChannel;
    const float ampStepLeft = static_cast<float>((endLeftAmplitude - startLeftAmplitude) / samplesPerChannel);
    const float ampStepRight = static_cast<float>((endRightAmplitude - startRightAmplitude) / samplesPerChannel);
    
    float leftAmplitude = static_cast<float>(startLeftAmplitude);
    float rightAmplitude = static_cast<float>(startRightAmplitude);
    
    // Параметры fade
    const int channelSwapFadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    // Локальное состояние
    bool localIsFadingOut = state.isFadingOut;
    int64_t localFadeStartSample = state.fadeStartSample;
    bool localChannelsSwapped = state.channelsSwapped;
    GeneratorState::FadeOperation localFadeOperation = state.currentFadeOperation;
    int64_t swapExecutedAtSample = -1;
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    // Проверка перестановки каналов
    if (localFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                localFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                localIsFadingOut = true;
                localFadeStartSample = state.totalSamplesGenerated;
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                localChannelsSwapped = !localChannelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    // ========================================================================
    // ОПТИМИЗИРОВАННЫЙ NEON ПУТЬ
    // ========================================================================
    
    const bool hasFade = localFadeOperation != GeneratorState::FadeOperation::NONE;
    const bool swapActive = channelSwapEnabled && channelsSwappedAtStart;
    
    // Предвычисленные NEON константы
    const float32x4_t vScaleFactor = vdupq_n_f32(scaleFactor);
    const float32x4_t vBaseVol = vdupq_n_f32(baseVolumeFactor);
    const float32x4_t vOffsets = {0.0f, 1.0f, 2.0f, 3.0f};
    
    // Фазы
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int neonEnd = samplesPerChannel - 3;
    
    // БЫСТРЫЙ ПУТЬ: нет fade (наиболее частый случай)
    if (!hasFade) {
        if (!swapActive) {
            // Самый быстрый путь: нет fade, нет swap
            for (; i < neonEnd; i += 4) {
                // ВЕКТОРИЗОВАННОЕ вычисление фаз для 4 сэмплов
                // phases = basePhase + omega * offsets
                float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
                float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
                
                // phases = base + omega * [0,1,2,3]
                float32x4_t vLeftPhases = vaddq_f32(
                    vdupq_n_f32(leftPhaseBase),
                    vmulq_f32(vOmegaL, vOffsets)
                );
                float32x4_t vRightPhases = vaddq_f32(
                    vdupq_n_f32(rightPhaseBase),
                    vmulq_f32(vOmegaR, vOffsets)
                );
                
                // Масштабируем фазы для wavetable
                float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
                float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
                
                // Получаем синусы через NEON wavetable
                float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
                float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
                
                // Векторные амплитуды
                float32x4_t vLeftAmps = vmulq_n_f32(vBaseVol, leftAmplitude);
                float32x4_t vRightAmps = vmulq_n_f32(vBaseVol, rightAmplitude);
                
                // Применяем амплитуды с FMA если доступно
                #ifdef __ARM_FEATURE_FMA
                    vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                    vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
                #else
                    vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                    vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
                #endif
                
                // Обновляем фазы (branchless)
                leftPhaseBase += leftOmega * 4;
                leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
                rightPhaseBase += rightOmega * 4;
                rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
                
                // Обновляем omega и амплитуды
                leftOmega += omegaStepLeft * 4;
                rightOmega += omegaStepRight * 4;
                leftAmplitude += ampStepLeft * 4;
                rightAmplitude += ampStepRight * 4;
                
                // Записываем в буфер (interleaved stereo)
                // Используем vst2 для interleaved записи: L R L R L R L R
                float32x4x2_t vInterleaved = {vLeftSamples, vRightSamples};
                vst2q_f32(buffer + i * 2, vInterleaved);
            }
        } else {
            // Быстрый путь: нет fade, но swap активен
            for (; i < neonEnd; i += 4) {
                float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
                float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
                
                float32x4_t vLeftPhases = vaddq_f32(
                    vdupq_n_f32(leftPhaseBase),
                    vmulq_f32(vOmegaL, vOffsets)
                );
                float32x4_t vRightPhases = vaddq_f32(
                    vdupq_n_f32(rightPhaseBase),
                    vmulq_f32(vOmegaR, vOffsets)
                );
                
                float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
                float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
                
                float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
                float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
                
                float32x4_t vLeftAmps = vmulq_n_f32(vBaseVol, leftAmplitude);
                float32x4_t vRightAmps = vmulq_n_f32(vBaseVol, rightAmplitude);
                
                #ifdef __ARM_FEATURE_FMA
                    vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                    vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
                #else
                    vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                    vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
                #endif
                
                leftPhaseBase += leftOmega * 4;
                leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
                rightPhaseBase += rightOmega * 4;
                rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
                
                leftOmega += omegaStepLeft * 4;
                rightOmega += omegaStepRight * 4;
                leftAmplitude += ampStepLeft * 4;
                rightAmplitude += ampStepRight * 4;
                
                // Записываем в буфер с swap (R L вместо L R)
                float32x4x2_t vInterleaved = {vRightSamples, vLeftSamples};
                vst2q_f32(buffer + i * 2, vInterleaved);
            }
        }
    } else {
        // Медленный путь: есть fade
        const float invFadeDuration = 1.0f / channelSwapFadeDurationSamples;
        
        for (; i < neonEnd; i += 4) {
            const int64_t currentSampleBase = state.totalSamplesGenerated + i;
            
            // Векторизованное вычисление фаз
            float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
            float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
            
            float32x4_t vLeftPhases = vaddq_f32(
                vdupq_n_f32(leftPhaseBase),
                vmulq_f32(vOmegaL, vOffsets)
            );
            float32x4_t vRightPhases = vaddq_f32(
                vdupq_n_f32(rightPhaseBase),
                vmulq_f32(vOmegaR, vOffsets)
            );
            
            float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
            float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
            
            float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
            float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
            
            // Обработка fade для 4 сэмплов
            float fadeMultipliers[4] __attribute__((aligned(16)));
            bool swapFlags[4] = {false, false, false, false};
            
            for (int j = 0; j < 4; ++j) {
                const int64_t currentSample = currentSampleBase + j;
                float fadeMultiplier = 1.0;
                
                const int64_t elapsedSamples = currentSample - localFadeStartSample;
                const float progress = elapsedSamples * invFadeDuration;
                
                if (localIsFadingOut) {
                    if (progress >= 1.0) {
                        fadeMultiplier = 0.0;
                        if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                            swapExecutedAtSample = currentSample;
                            localChannelsSwapped = !localChannelsSwapped;
                            result.channelsSwapped = true;
                            localIsFadingOut = false;
                            localFadeStartSample = currentSample;
                        }
                    } else if (progress >= 0.0) {
                        fadeMultiplier = 1.0 - progress;
                    }
                } else {
                    if (progress >= 1.0) {
                        fadeMultiplier = 1.0;
                        result.fadePhaseCompleted = true;
                        localFadeOperation = GeneratorState::FadeOperation::NONE;
                    } else if (progress >= 0.0) {
                        fadeMultiplier = progress;
                    }
                }
                fadeMultipliers[j] = fadeMultiplier;
                
                // Определяем swap для каждого сэмпла
                const bool swapForSample = channelSwapEnabled && 
                    ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
                     localChannelsSwapped : channelsSwappedAtStart);
                swapFlags[j] = swapForSample;
            }
            
            // Амплитуды для 4 сэмплов
            float leftAmps[4] __attribute__((aligned(16)));
            float rightAmps[4] __attribute__((aligned(16)));
            for (int j = 0; j < 4; ++j) {
                leftAmps[j] = baseVolumeFactor * fadeMultipliers[j] * leftAmplitude;
                rightAmps[j] = baseVolumeFactor * fadeMultipliers[j] * rightAmplitude;
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
            
            leftPhaseBase += leftOmega * 4;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += omegaStepLeft * 4;
            rightOmega += omegaStepRight * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            // Записываем в буфер с учётом swap
            float leftResult[4] __attribute__((aligned(16)));
            float rightResult[4] __attribute__((aligned(16)));
            vst1q_f32(leftResult, vLeftSamples);
            vst1q_f32(rightResult, vRightSamples);
            
            for (int j = 0; j < 4; ++j) {
                const int idx = (i + j) * 2;
                if (swapFlags[j]) {
                    buffer[idx] = rightResult[j];
                    buffer[idx + 1] = leftResult[j];
                } else {
                    buffer[idx] = leftResult[j];
                    buffer[idx + 1] = rightResult[j];
                }
            }
        }
    }
    
    // Обновляем фазы в состоянии
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    // Обрабатываем оставшиеся сэмплы (0-3) скалярным способом
    for (; i < samplesPerChannel; ++i) {
        const int64_t currentSample = state.totalSamplesGenerated + i;
        float fadeMultiplier = 1.0;
        
        if (localFadeOperation != GeneratorState::FadeOperation::NONE) {
            const int64_t elapsedSamples = currentSample - localFadeStartSample;
            const float progress = static_cast<float>(elapsedSamples) / channelSwapFadeDurationSamples;
            
            if (localIsFadingOut) {
                if (progress >= 1.0) {
                    fadeMultiplier = 0.0;
                    if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                        swapExecutedAtSample = currentSample;
                        localChannelsSwapped = !localChannelsSwapped;
                        result.channelsSwapped = true;
                        localIsFadingOut = false;
                        localFadeStartSample = currentSample;
                    }
                } else if (progress >= 0.0) {
                    fadeMultiplier = 1.0 - progress;
                }
            } else {
                if (progress >= 1.0) {
                    fadeMultiplier = 1.0;
                    result.fadePhaseCompleted = true;
                    localFadeOperation = GeneratorState::FadeOperation::NONE;
                } else if (progress >= 0.0) {
                    fadeMultiplier = progress;
                }
            }
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float baseAmplitude = baseVolumeFactor * fadeMultiplier;
        const float leftAmp = baseAmplitude * leftAmplitude;
        const float rightAmp = baseAmplitude * rightAmplitude;
        
        const bool swapForSample = channelSwapEnabled && 
            ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
             localChannelsSwapped : channelsSwappedAtStart);
        
        if (swapForSample) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
    }
    
    // Обновление состояния
    state.channelsSwapped = localChannelsSwapped;
    state.isFadingOut = localIsFadingOut;
    state.fadeStartSample = localFadeStartSample;
    state.currentFadeOperation = localFadeOperation;
    state.totalSamplesGenerated += samplesPerChannel;
    
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}
#endif

#ifdef USE_SSE
/**
 * SSE-оптимизированная генерация буфера для x86/x86_64
 */
GenerateResult AudioGenerator::generateBufferSse(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    int32_t timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    (void)frequencyUpdateIntervalMs;
    GenerateResult result;
    
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / m_sampleRate;
    
    FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, timeSeconds);
    float startLeftFreq = startFreqResult.lowerFreq;
    float startRightFreq = startFreqResult.upperFreq;
    
    FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
        config.curve,
        static_cast<float>(timeSeconds + bufferDurationSeconds)
    );
    float endLeftFreq = endFreqResult.lowerFreq;
    float endRightFreq = endFreqResult.upperFreq;
    
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    const float scaleFactor = static_cast<float>(Wavetable::getScaleFactor());
    const float baseVolumeFactor = 0.5f * config.volume;
    
    float leftOmega = twoPiOverSampleRate * startLeftFreq;
    float rightOmega = twoPiOverSampleRate * startRightFreq;
    const float endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const float endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    const float omegaStepLeft = (endLeftOmega - leftOmega) / samplesPerChannel;
    const float omegaStepRight = (endRightOmega - rightOmega) / samplesPerChannel;
    const float ampStepLeft = static_cast<float>((endLeftAmplitude - startLeftAmplitude) / samplesPerChannel);
    const float ampStepRight = static_cast<float>((endRightAmplitude - startRightAmplitude) / samplesPerChannel);
    
    float leftAmplitude = static_cast<float>(startLeftAmplitude);
    float rightAmplitude = static_cast<float>(startRightAmplitude);
    
    const int channelSwapFadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    bool localIsFadingOut = state.isFadingOut;
    int64_t localFadeStartSample = state.fadeStartSample;
    bool localChannelsSwapped = state.channelsSwapped;
    GeneratorState::FadeOperation localFadeOperation = state.currentFadeOperation;
    int64_t swapExecutedAtSample = -1;
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    if (localFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                localFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                localIsFadingOut = true;
                localFadeStartSample = state.totalSamplesGenerated;
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                localChannelsSwapped = !localChannelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    const bool hasFade = localFadeOperation != GeneratorState::FadeOperation::NONE;
    const bool swapActive = channelSwapEnabled && channelsSwappedAtStart;
    
    // SSE константы
    const __m128 vScaleFactor = _mm_set1_ps(scaleFactor);
    const __m128 vBaseVol = _mm_set1_ps(baseVolumeFactor);
    const __m128 vOffsets = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int sseEnd = samplesPerChannel - 3;
    
    if (!hasFade) {
        if (!swapActive) {
            for (; i < sseEnd; i += 4) {
                __m128 vOmegaL = _mm_set1_ps(leftOmega);
                __m128 vOmegaR = _mm_set1_ps(rightOmega);
                
                __m128 vLeftPhases = _mm_add_ps(
                    _mm_set1_ps(leftPhaseBase),
                    _mm_mul_ps(vOmegaL, vOffsets)
                );
                __m128 vRightPhases = _mm_add_ps(
                    _mm_set1_ps(rightPhaseBase),
                    _mm_mul_ps(vOmegaR, vOffsets)
                );
                
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
                
                // Interleaved store: L R L R L R L R
                float leftResult[4] __attribute__((aligned(16)));
                float rightResult[4] __attribute__((aligned(16)));
                _mm_store_ps(leftResult, vLeftSamples);
                _mm_store_ps(rightResult, vRightSamples);
                
                for (int j = 0; j < 4; ++j) {
                    buffer[(i + j) * 2] = leftResult[j];
                    buffer[(i + j) * 2 + 1] = rightResult[j];
                }
            }
        } else {
            for (; i < sseEnd; i += 4) {
                __m128 vOmegaL = _mm_set1_ps(leftOmega);
                __m128 vOmegaR = _mm_set1_ps(rightOmega);
                
                __m128 vLeftPhases = _mm_add_ps(
                    _mm_set1_ps(leftPhaseBase),
                    _mm_mul_ps(vOmegaL, vOffsets)
                );
                __m128 vRightPhases = _mm_add_ps(
                    _mm_set1_ps(rightPhaseBase),
                    _mm_mul_ps(vOmegaR, vOffsets)
                );
                
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
                
                // Swap: R L вместо L R
                float leftResult[4] __attribute__((aligned(16)));
                float rightResult[4] __attribute__((aligned(16)));
                _mm_store_ps(leftResult, vLeftSamples);
                _mm_store_ps(rightResult, vRightSamples);
                
                for (int j = 0; j < 4; ++j) {
                    buffer[(i + j) * 2] = rightResult[j];
                    buffer[(i + j) * 2 + 1] = leftResult[j];
                }
            }
        }
    } else {
        const float invFadeDuration = 1.0f / channelSwapFadeDurationSamples;
        
        for (; i < sseEnd; i += 4) {
            const int64_t currentSampleBase = state.totalSamplesGenerated + i;
            
            __m128 vOmegaL = _mm_set1_ps(leftOmega);
            __m128 vOmegaR = _mm_set1_ps(rightOmega);
            
            __m128 vLeftPhases = _mm_add_ps(
                _mm_set1_ps(leftPhaseBase),
                _mm_mul_ps(vOmegaL, vOffsets)
            );
            __m128 vRightPhases = _mm_add_ps(
                _mm_set1_ps(rightPhaseBase),
                _mm_mul_ps(vOmegaR, vOffsets)
            );
            
            __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
            __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
            
            __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
            __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
            
            float fadeMultipliers[4] __attribute__((aligned(16)));
            bool swapFlags[4] = {false, false, false, false};
            
            for (int j = 0; j < 4; ++j) {
                const int64_t currentSample = currentSampleBase + j;
                float fadeMultiplier = 1.0;
                
                const int64_t elapsedSamples = currentSample - localFadeStartSample;
                const float progress = elapsedSamples * invFadeDuration;
                
                if (localIsFadingOut) {
                    if (progress >= 1.0) {
                        fadeMultiplier = 0.0;
                        if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                            swapExecutedAtSample = currentSample;
                            localChannelsSwapped = !localChannelsSwapped;
                            result.channelsSwapped = true;
                            localIsFadingOut = false;
                            localFadeStartSample = currentSample;
                        }
                    } else if (progress >= 0.0) {
                        fadeMultiplier = 1.0 - progress;
                    }
                } else {
                    if (progress >= 1.0) {
                        fadeMultiplier = 1.0;
                        result.fadePhaseCompleted = true;
                        localFadeOperation = GeneratorState::FadeOperation::NONE;
                    } else if (progress >= 0.0) {
                        fadeMultiplier = progress;
                    }
                }
                fadeMultipliers[j] = fadeMultiplier;
                
                const bool swapForSample = channelSwapEnabled && 
                    ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
                     localChannelsSwapped : channelsSwappedAtStart);
                swapFlags[j] = swapForSample;
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
                if (swapFlags[j]) {
                    buffer[idx] = rightResult[j];
                    buffer[idx + 1] = leftResult[j];
                } else {
                    buffer[idx] = leftResult[j];
                    buffer[idx + 1] = rightResult[j];
                }
            }
        }
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    for (; i < samplesPerChannel; ++i) {
        const int64_t currentSample = state.totalSamplesGenerated + i;
        float fadeMultiplier = 1.0;
        
        if (localFadeOperation != GeneratorState::FadeOperation::NONE) {
            const int64_t elapsedSamples = currentSample - localFadeStartSample;
            const float progress = static_cast<float>(elapsedSamples) / channelSwapFadeDurationSamples;
            
            if (localIsFadingOut) {
                if (progress >= 1.0) {
                    fadeMultiplier = 0.0;
                    if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                        swapExecutedAtSample = currentSample;
                        localChannelsSwapped = !localChannelsSwapped;
                        result.channelsSwapped = true;
                        localIsFadingOut = false;
                        localFadeStartSample = currentSample;
                    }
                } else if (progress >= 0.0) {
                    fadeMultiplier = 1.0 - progress;
                }
            } else {
                if (progress >= 1.0) {
                    fadeMultiplier = 1.0;
                    result.fadePhaseCompleted = true;
                    localFadeOperation = GeneratorState::FadeOperation::NONE;
                } else if (progress >= 0.0) {
                    fadeMultiplier = progress;
                }
            }
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        
        const float baseAmplitude = baseVolumeFactor * fadeMultiplier;
        const float leftAmp = baseAmplitude * leftAmplitude;
        const float rightAmp = baseAmplitude * rightAmplitude;
        
        const bool swapForSample = channelSwapEnabled && 
            ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
             localChannelsSwapped : channelsSwappedAtStart);
        
        if (swapForSample) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
    }
    
    state.channelsSwapped = localChannelsSwapped;
    state.isFadingOut = localIsFadingOut;
    state.fadeStartSample = localFadeStartSample;
    state.currentFadeOperation = localFadeOperation;
    state.totalSamplesGenerated += samplesPerChannel;
    
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}
#endif

} // namespace binaural