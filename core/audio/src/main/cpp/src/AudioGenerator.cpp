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
    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
    state.isFadingOut = false;  // Исправлено: согласованное начальное состояние
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
    
    // Проверка необходимости начать fade для перестановки каналов
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                state.isFadingOut = true;
                state.fadeStartSample = state.totalSamplesGenerated;
                // Записываем время НАЧАЛА перестановки (fade-out)
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                state.channelsSwapped = !state.channelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    // Определяем, какой тип генерации нужен
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE) {
        // Самый быстрый путь: сплошной буфер без fade
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
    
    if (swapActive) {
        for (; i < neonEnd; i += 4) {
            float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
            float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
            
            float32x4_t vLeftPhases = vaddq_f32(vdupq_n_f32(leftPhaseBase), vmulq_f32(vOmegaL, vOffsets));
            float32x4_t vRightPhases = vaddq_f32(vdupq_n_f32(rightPhaseBase), vmulq_f32(vOmegaR, vOffsets));
            
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
            
            // Swap: R L вместо L R
            float32x4x2_t vInterleaved = {vRightSamples, vLeftSamples};
            vst2q_f32(buffer + i * 2, vInterleaved);
        }
    } else {
        for (; i < neonEnd; i += 4) {
            float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
            float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
            
            float32x4_t vLeftPhases = vaddq_f32(vdupq_n_f32(leftPhaseBase), vmulq_f32(vOmegaL, vOffsets));
            float32x4_t vRightPhases = vaddq_f32(vdupq_n_f32(rightPhaseBase), vmulq_f32(vOmegaR, vOffsets));
            
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
        float32x4_t vOmegaL = vdupq_n_f32(leftOmega);
        float32x4_t vOmegaR = vdupq_n_f32(rightOmega);
        
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
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                state.isFadingOut = true;
                state.fadeStartSample = state.totalSamplesGenerated;
                // Записываем время НАЧАЛА перестановки (fade-out)
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
    
    if (state.currentFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                state.currentFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                state.isFadingOut = true;
                state.fadeStartSample = state.totalSamplesGenerated;
                // Записываем время НАЧАЛА перестановки (fade-out)
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

} // namespace binaural