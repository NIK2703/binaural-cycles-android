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
#ifdef AUDIO_TEST_BUILD
#define LOGD(...) ((void)0)
#elif defined(AUDIO_DEBUG) && defined(ANDROID)
#include <android/log.h>
#define LOG_TAG "AudioGenerator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

// Логирование для отладки стыков буферов
#ifdef AUDIO_TEST_BUILD
#define LOG_SEG(...) ((void)0)
#elif defined(ANDROID)
#include <android/log.h>
#define LOG_SEG(...) __android_log_print(ANDROID_LOG_DEBUG, "SEGMENT_DEBUG", __VA_ARGS__)
#else
#include "../tests/android_stub.h"
#define LOG_SEG(...) __android_log_print(ANDROID_LOG_DEBUG, "SEGMENT_DEBUG", __VA_ARGS__)
#endif

namespace binaural {

// Предвычисленные константы для оптимизации
static constexpr float ONE_OVER_TWO_PI = 1.0f / AudioGenerator::TWO_PI;

// ========================================================================
// ПРЕДВЫЧИСЛЕННАЯ ТАБЛИЦА ДЛЯ FADE КРИВОЙ (косинусная интерполяция)
// ========================================================================
struct FadeCurveTable {
    static constexpr int TABLE_SIZE = 2048;
    static constexpr int TABLE_SIZE_F = TABLE_SIZE;
    float values[TABLE_SIZE + 1];
    
    FadeCurveTable() {
        for (int i = 0; i <= TABLE_SIZE; ++i) {
            const float t = static_cast<float>(i) / TABLE_SIZE;
            values[i] = 0.5f * (1.0f - std::cos(t * static_cast<float>(M_PI)));
        }
    }
    
    inline float get(float progress) const {
        const float clampedProgress = std::clamp(progress, 0.0f, 1.0f);
        const float scaledIndex = clampedProgress * TABLE_SIZE;
        const int index = static_cast<int>(scaledIndex);
        const float fraction = scaledIndex - index;
        
        const float y0 = values[index];
        const float y1 = values[index + 1];
        return y0 + fraction * (y1 - y0);
    }
};

static const FadeCurveTable s_fadeCurveTable;

AudioGenerator::AudioGenerator() {
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
    
    // State machine для swap-цикла
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    state.cyclePositionMs = 0;
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
            // Используем глобальную минимальную частоту среди всех каналов
            // minChannelFreq = min(lower, upper) в каждой точке по всему графику
            // Это корректно учитывает случай, когда beat < 0 (каналы поменялись местами)
            const float globalMinFreq = curve.minChannelFreq;
            const float leftNormalized = leftFreq > 0 ? globalMinFreq / leftFreq : 1.0;
            const float rightNormalized = rightFreq > 0 ? globalMinFreq / rightFreq : 1.0;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
    }
    
    return {leftAmplitude, rightAmplitude};
}

// ========================================================================
// СПЕЦИАЛИЗИРОВАННЫЕ ФУНКЦИИ ГЕНЕРАЦИИ (приватные)
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
    constexpr float baseVolumeFactor = 0.5f;

    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;

    float leftNormAmp = startLeftAmp;
    float rightNormAmp = startRightAmp;

    // Для правильной генерации рампирующей частоты используем линейное изменение omega:
    // omega(n) = startOmega + omegaStep * n, где omega(samples-1) = endOmega
    // omegaStep = (endOmega - startOmega) / (samples - 1)
    // phase(n) = sum(omega(i)) для i=0..n-1 = startOmega * n + omegaStep * n*(n-1)/2
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;

    if (swapActive) {
        for (int i = 0; i < samples; ++i) {
            // Вычисляем omega для текущего сэмпла
            const float leftOmega = startLeftOmega + leftOmegaStep * i;
            const float rightOmega = startRightOmega + rightOmegaStep * i;

            const float leftSample = Wavetable::fastSin(state.leftPhase);
            const float rightSample = Wavetable::fastSin(state.rightPhase);

            state.leftPhase += leftOmega;
            state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);

            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);

            buffer[i * 2] = rightSample * (baseVolumeFactor * rightNormAmp);
            buffer[i * 2 + 1] = leftSample * (baseVolumeFactor * leftNormAmp);

            leftNormAmp += ampStepLeft;
            rightNormAmp += ampStepRight;
        }
    } else {
        for (int i = 0; i < samples; ++i) {
            // Вычисляем omega для текущего сэмпла
            const float leftOmega = startLeftOmega + leftOmegaStep * i;
            const float rightOmega = startRightOmega + rightOmegaStep * i;

            const float leftSample = Wavetable::fastSin(state.leftPhase);
            const float rightSample = Wavetable::fastSin(state.rightPhase);

            state.leftPhase += leftOmega;
            state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);

            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);

            buffer[i * 2] = leftSample * (baseVolumeFactor * leftNormAmp);
            buffer[i * 2 + 1] = rightSample * (baseVolumeFactor * rightNormAmp);

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

    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;

    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;

    bool fadeCompleted = false;

    // Для правильной генерации рампирующей частоты используем линейное изменение omega
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;

    for (int i = 0; i < samples; ++i) {
        const int fadeProgress = fadeStartOffset + i;
        float fadeMultiplier = 1.0f;

        if (fadeProgress >= fadeDuration) {
            fadeMultiplier = fadingOut ? 0.0f : 1.0f;
            fadeCompleted = true;
        } else if (fadeProgress >= 0) {
            const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
            const float cosProgress = s_fadeCurveTable.get(progress);
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }

        // Вычисляем omega для текущего сэмпла
        const float leftOmega = startLeftOmega + leftOmegaStep * i;
        const float rightOmega = startRightOmega + rightOmegaStep * i;

        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);

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
    for (int i = 0; i < samples; ++i) {
        state.leftPhase += leftOmega;
        state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);

        state.rightPhase += rightOmega;
        state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
    }
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
    
    // КРИТИЧНО: omegaStep вычисляется с делением на (samples - 1), чтобы
    // при i = samples - 1 получить точно endOmega: omega(samples-1) = start + step*(samples-1) = end
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const float32x4_t vScaleFactor = vdupq_n_f32(scaleFactor);
    const float32x4_t vBaseVol = vdupq_n_f32(baseVolumeFactor);
    // Индексы для расчёта фаз: phase[i] = phaseBase + i*omega + omegaStep * i*(i-1)/2
    const float32x4_t vIndices = {0.0f, 1.0f, 2.0f, 3.0f};
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const float32x4_t vPhaseAccum = {0.0f, 0.0f, 1.0f, 3.0f};
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int neonEnd = samples - 3;
    
    if (swapActive) {
        for (; i < neonEnd; i += 4) {
            // Правильный расчёт фаз с накоплением:
            // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
            float32x4_t vLeftPhases = vaddq_f32(
                vdupq_n_f32(leftPhaseBase),
                vaddq_f32(
                    vmulq_f32(vdupq_n_f32(leftOmega), vIndices),
                    vmulq_f32(vdupq_n_f32(leftOmegaStep), vPhaseAccum)
                )
            );
            float32x4_t vRightPhases = vaddq_f32(
                vdupq_n_f32(rightPhaseBase),
                vaddq_f32(
                    vmulq_f32(vdupq_n_f32(rightOmega), vIndices),
                    vmulq_f32(vdupq_n_f32(rightOmegaStep), vPhaseAccum)
                )
            );
            
            float32x4_t vAmpL = {leftAmplitude, leftAmplitude + ampStepLeft,
                                 leftAmplitude + 2*ampStepLeft, leftAmplitude + 3*ampStepLeft};
            float32x4_t vAmpR = {rightAmplitude, rightAmplitude + ampStepRight,
                                 rightAmplitude + 2*ampStepRight, rightAmplitude + 3*ampStepRight};
            
            float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
            float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
            
            float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
            float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
            
            float32x4_t vLeftAmps = vmulq_f32(vBaseVol, vAmpL);
            float32x4_t vRightAmps = vmulq_f32(vBaseVol, vAmpR);
            
            #ifdef __ARM_FEATURE_FMA
                vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
            #else
                vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
            #endif
            
            // Сначала обновляем фазу с текущими значениями omega
            leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            // Затем обновляем omega для следующей итерации
            leftOmega += leftOmegaStep * 4;
            rightOmega += rightOmegaStep * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            float32x4x2_t vInterleaved = {vRightSamples, vLeftSamples};
            vst2q_f32(buffer + i * 2, vInterleaved);
        }
    } else {
        for (; i < neonEnd; i += 4) {
            // Правильный расчёт фаз с накоплением:
            // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
            float32x4_t vLeftPhases = vaddq_f32(
                vdupq_n_f32(leftPhaseBase),
                vaddq_f32(
                    vmulq_f32(vdupq_n_f32(leftOmega), vIndices),
                    vmulq_f32(vdupq_n_f32(leftOmegaStep), vPhaseAccum)
                )
            );
            float32x4_t vRightPhases = vaddq_f32(
                vdupq_n_f32(rightPhaseBase),
                vaddq_f32(
                    vmulq_f32(vdupq_n_f32(rightOmega), vIndices),
                    vmulq_f32(vdupq_n_f32(rightOmegaStep), vPhaseAccum)
                )
            );
            
            float32x4_t vAmpL = {leftAmplitude, leftAmplitude + ampStepLeft,
                                 leftAmplitude + 2*ampStepLeft, leftAmplitude + 3*ampStepLeft};
            float32x4_t vAmpR = {rightAmplitude, rightAmplitude + ampStepRight,
                                 rightAmplitude + 2*ampStepRight, rightAmplitude + 3*ampStepRight};
            
            float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
            float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
            
            float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
            float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
            
            float32x4_t vLeftAmps = vmulq_f32(vBaseVol, vAmpL);
            float32x4_t vRightAmps = vmulq_f32(vBaseVol, vAmpR);
            
            #ifdef __ARM_FEATURE_FMA
                vLeftSamples = vfmaq_f32(vdupq_n_f32(0.0f), vLeftSamples, vLeftAmps);
                vRightSamples = vfmaq_f32(vdupq_n_f32(0.0f), vRightSamples, vRightAmps);
            #else
                vLeftSamples = vmulq_f32(vLeftSamples, vLeftAmps);
                vRightSamples = vmulq_f32(vRightSamples, vRightAmps);
            #endif
            
            leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += leftOmegaStep * 4;
            rightOmega += rightOmegaStep * 4;
            leftAmplitude += ampStepLeft * 4;
            rightAmplitude += ampStepRight * 4;
            
            float32x4x2_t vInterleaved = {vLeftSamples, vRightSamples};
            vst2q_f32(buffer + i * 2, vInterleaved);
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
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
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
    
    // КРИТИЧНО: omegaStep вычисляется с делением на (samples - 1), чтобы
    // при i = samples - 1 получить точно endOmega
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const float32x4_t vScaleFactor = vdupq_n_f32(scaleFactor);
    const float32x4_t vBaseVol = vdupq_n_f32(baseVolumeFactor);
    // Индексы для расчёта фаз: phase[i] = phaseBase + i*omega + omegaStep * i*(i-1)/2
    const float32x4_t vIndices = {0.0f, 1.0f, 2.0f, 3.0f};
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const float32x4_t vPhaseAccum = {0.0f, 0.0f, 1.0f, 3.0f};
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    bool fadeCompleted = false;
    int i = 0;
    const int neonEnd = samples - 3;
    
    // Логируем первый fadeMultiplier для отладки стыков
    if (fadeStartOffset == 0) {
        const float firstProgress = 0.0f;
        const float firstCosProgress = 0.5f * (1.0f - std::cos(firstProgress * static_cast<float>(M_PI)));
        const float firstFadeMult = fadingOut ? (1.0f - firstCosProgress) : firstCosProgress;
        LOG_SEG("FADE_FIRST_MULT: fadingOut=%d, fadeMult=%.6f, amp=[%.4f, %.4f], phase=[%.4f, %.4f]",
             fadingOut ? 1 : 0, firstFadeMult,
             leftAmplitude, rightAmplitude,
             leftPhaseBase, rightPhaseBase);
    }
    
    for (; i < neonEnd; i += 4) {
        // Правильный расчёт фаз с накоплением:
        // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
        float32x4_t vLeftPhases = vaddq_f32(
            vdupq_n_f32(leftPhaseBase),
            vaddq_f32(
                vmulq_f32(vdupq_n_f32(leftOmega), vIndices),
                vmulq_f32(vdupq_n_f32(leftOmegaStep), vPhaseAccum)
            )
        );
        float32x4_t vRightPhases = vaddq_f32(
            vdupq_n_f32(rightPhaseBase),
            vaddq_f32(
                vmulq_f32(vdupq_n_f32(rightOmega), vIndices),
                vmulq_f32(vdupq_n_f32(rightOmegaStep), vPhaseAccum)
            )
        );
        
        float32x4_t vLeftPhasesScaled = vmulq_f32(vLeftPhases, vScaleFactor);
        float32x4_t vRightPhasesScaled = vmulq_f32(vRightPhases, vScaleFactor);
        
        float32x4_t vLeftSamples = Wavetable::fastSinNeon(vLeftPhasesScaled);
        float32x4_t vRightSamples = Wavetable::fastSinNeon(vRightPhasesScaled);
        
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
        
        leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
        leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        
        leftOmega += leftOmegaStep * 4;
        rightOmega += rightOmegaStep * 4;
        leftAmplitude += ampStepLeft * 4;
        rightAmplitude += ampStepRight * 4;
        
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
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
    
    return fadeCompleted;
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
    
    // КРИТИЧНО: omegaStep вычисляется с делением на (samples - 1), чтобы
    // при i = samples - 1 получить точно endOmega: omega(samples-1) = start + step*(samples-1) = end
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const __m128 vScaleFactor = _mm_set1_ps(scaleFactor);
    const __m128 vBaseVol = _mm_set1_ps(baseVolumeFactor);
    // Индексы для расчёта фаз: phase[i] = phaseBase + i*omega + omegaStep * i*(i-1)/2
    const __m128 vIndices = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const __m128 vPhaseAccum = _mm_set_ps(3.0f, 1.0f, 0.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int sseEnd = samples - 3;
    
    if (swapActive) {
        for (; i < sseEnd; i += 4) {
            // Правильный расчёт фаз с накоплением:
            // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
            __m128 vLeftPhases = _mm_add_ps(
                _mm_set1_ps(leftPhaseBase),
                _mm_add_ps(
                    _mm_mul_ps(_mm_set1_ps(leftOmega), vIndices),
                    _mm_mul_ps(_mm_set1_ps(leftOmegaStep), vPhaseAccum)
                )
            );
            __m128 vRightPhases = _mm_add_ps(
                _mm_set1_ps(rightPhaseBase),
                _mm_add_ps(
                    _mm_mul_ps(_mm_set1_ps(rightOmega), vIndices),
                    _mm_mul_ps(_mm_set1_ps(rightOmegaStep), vPhaseAccum)
                )
            );
            
            __m128 vAmpL = _mm_set_ps(leftAmplitude + 3*ampStepLeft, leftAmplitude + 2*ampStepLeft,
                                       leftAmplitude + ampStepLeft, leftAmplitude);
            __m128 vAmpR = _mm_set_ps(rightAmplitude + 3*ampStepRight, rightAmplitude + 2*ampStepRight,
                                       rightAmplitude + ampStepRight, rightAmplitude);
            
            __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
            __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
            
            __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
            __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
            
            __m128 vLeftAmps = _mm_mul_ps(vBaseVol, vAmpL);
            __m128 vRightAmps = _mm_mul_ps(vBaseVol, vAmpR);
            
            vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
            vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
            
            leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += leftOmegaStep * 4;
            rightOmega += rightOmegaStep * 4;
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
            // Правильный расчёт фаз с накоплением:
            // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
            __m128 vLeftPhases = _mm_add_ps(
                _mm_set1_ps(leftPhaseBase),
                _mm_add_ps(
                    _mm_mul_ps(_mm_set1_ps(leftOmega), vIndices),
                    _mm_mul_ps(_mm_set1_ps(leftOmegaStep), vPhaseAccum)
                )
            );
            __m128 vRightPhases = _mm_add_ps(
                _mm_set1_ps(rightPhaseBase),
                _mm_add_ps(
                    _mm_mul_ps(_mm_set1_ps(rightOmega), vIndices),
                    _mm_mul_ps(_mm_set1_ps(rightOmegaStep), vPhaseAccum)
                )
            );
            
            __m128 vAmpL = _mm_set_ps(leftAmplitude + 3*ampStepLeft, leftAmplitude + 2*ampStepLeft,
                                       leftAmplitude + ampStepLeft, leftAmplitude);
            __m128 vAmpR = _mm_set_ps(rightAmplitude + 3*ampStepRight, rightAmplitude + 2*ampStepRight,
                                       rightAmplitude + ampStepRight, rightAmplitude);
            
            __m128 vLeftPhasesScaled = _mm_mul_ps(vLeftPhases, vScaleFactor);
            __m128 vRightPhasesScaled = _mm_mul_ps(vRightPhases, vScaleFactor);
            
            __m128 vLeftSamples = Wavetable::fastSinSse(vLeftPhasesScaled);
            __m128 vRightSamples = Wavetable::fastSinSse(vRightPhasesScaled);
            
            __m128 vLeftAmps = _mm_mul_ps(vBaseVol, vAmpL);
            __m128 vRightAmps = _mm_mul_ps(vBaseVol, vAmpR);
            
            vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
            vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
            
            leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
            leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            
            leftOmega += leftOmegaStep * 4;
            rightOmega += rightOmegaStep * 4;
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
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
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
    
    // КРИТИЧНО: omegaStep вычисляется с делением на (samples - 1), чтобы
    // при i = samples - 1 получить точно endOmega
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;
    const float ampStepLeft = (endLeftAmp - startLeftAmp) / samples;
    const float ampStepRight = (endRightAmp - startRightAmp) / samples;
    
    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;
    float leftAmplitude = startLeftAmp;
    float rightAmplitude = startRightAmp;
    
    const __m128 vScaleFactor = _mm_set1_ps(scaleFactor);
    const __m128 vBaseVol = _mm_set1_ps(baseVolumeFactor);
    // Индексы для расчёта фаз: phase[i] = phaseBase + i*omega + omegaStep * i*(i-1)/2
    const __m128 vIndices = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const __m128 vPhaseAccum = _mm_set_ps(3.0f, 1.0f, 0.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    bool fadeCompleted = false;
    int i = 0;
    const int sseEnd = samples - 3;
    
    for (; i < sseEnd; i += 4) {
        // Правильный расчёт фаз с накоплением:
        // phase[i] = phaseBase + i*startOmega + omegaStep * i*(i-1)/2
        __m128 vLeftPhases = _mm_add_ps(
            _mm_set1_ps(leftPhaseBase),
            _mm_add_ps(
                _mm_mul_ps(_mm_set1_ps(leftOmega), vIndices),
                _mm_mul_ps(_mm_set1_ps(leftOmegaStep), vPhaseAccum)
            )
        );
        __m128 vRightPhases = _mm_add_ps(
            _mm_set1_ps(rightPhaseBase),
            _mm_add_ps(
                _mm_mul_ps(_mm_set1_ps(rightOmega), vIndices),
                _mm_mul_ps(_mm_set1_ps(rightOmegaStep), vPhaseAccum)
            )
        );
        
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
            leftAmps[j] = baseVolumeFactor * fadeMultipliers[j] * (leftAmplitude + j * ampStepLeft);
            rightAmps[j] = baseVolumeFactor * fadeMultipliers[j] * (rightAmplitude + j * ampStepRight);
        }
        
        __m128 vLeftAmps = _mm_load_ps(leftAmps);
        __m128 vRightAmps = _mm_load_ps(rightAmps);
        
        vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
        vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);
        
        leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
        leftPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(leftPhaseBase * ONE_OVER_TWO_PI);
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        
        leftOmega += leftOmegaStep * 4;
        rightOmega += rightOmegaStep * 4;
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
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
    
    return fadeCompleted;
}
#endif // USE_SSE

// ========================================================================
// ГЕНЕРАЦИЯ ПАКЕТОВ БУФЕРОВ
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
    
    int currentSample = 0;
    float currentTime = startTimeSeconds;
    int64_t currentElapsedMs = elapsedMs;
    
    float lastLeftFreq = 0.0f;
    float lastRightFreq = 0.0f;
    
    for (const auto& segment : plan.segments) {
        // Вычисляем количество сэмплов и реальную длительность в секундах
        // КРИТИЧНО: durationSec вычисляем из сэмплов для согласованности времени
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(samples) / m_sampleRate;
        
        if (samples <= 0) continue;
        
        // Сохраняем последние сэмплы предыдущего сегмента для отладки
        float lastLeftSample = 0.0f;
        float lastRightSample = 0.0f;
        if (currentSample > 0) {
            lastLeftSample = buffer[(currentSample - 1) * 2];
            lastRightSample = buffer[(currentSample - 1) * 2 + 1];
        }
        
        // Логируем фазу ДО генерации сегмента
        LOG_SEG("SEG_START: type=%d, samples=%d, leftPhase=%.4f, rightPhase=%.4f, prevSample=[%.4f, %.4f]",
             static_cast<int>(segment.type), samples,
             state.leftPhase, state.rightPhase,
             lastLeftSample, lastRightSample);
        
        // Начальные и конечные частоты ВСЕГДА вычисляем из таблицы по времени
        // Это гарантирует точное соответствие графику без скачков частот
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, currentTime);
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;
        
        LOG_SEG("SEGMENT_FREQS: time=%.3f, start=[%.2f, %.2f], end=[%.2f, %.2f], type=%d",
             currentTime,
             startLeftFreq, startRightFreq,
             endLeftFreq, endRightFreq,
             static_cast<int>(segment.type));
        
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
                    0,
                    samples,
                    true,
                    state.channelsSwapped,
                    state
                );
                break;
                
            case BufferType::PAUSE:
                // Пауза: тишина, но фазы продолжают обновляться
                // Это обеспечивает бесшовное продолжение после паузы
                updatePhasesOnly(
                    samples,
                    startLeftOmega,
                    startRightOmega,
                    state
                );
                // Заполняем буфер тишиной
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
                break;
                
            case BufferType::FADE_IN:
                generateFadeBuffer(
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
        
        // Логируем последние сэмплы текущего сегмента
        float endLeftSample = buffer[(currentSample + samples - 1) * 2];
        float endRightSample = buffer[(currentSample + samples - 1) * 2 + 1];
        
        // Логируем фазу ПОСЛЕ генерации сегмента
        LOG_SEG("SEG_END: type=%d, leftPhase=%.4f, rightPhase=%.4f, lastSample=[%.4f, %.4f]",
             static_cast<int>(segment.type),
             state.leftPhase, state.rightPhase,
             endLeftSample, endRightSample);
        
        if (segment.swapAfterSegment) {
            state.channelsSwapped = !state.channelsSwapped;
            result.channelsSwapped = true;
            
            LOGD("PackageGen: swap at elapsedMs=%lld, channelsSwapped=%d",
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
    result.samplesGenerated = currentSample;  // Возвращаем реальное количество сэмплов
    
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
        // Вычисляем количество сэмплов и реальную длительность в секундах
        // КРИТИЧНО: durationSec вычисляем из сэмплов для согласованности времени
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(samples) / m_sampleRate;
        
        if (samples <= 0) continue;
        
        // Логируем фазу ДО генерации сегмента
        LOG_SEG("SEG_START_NEON: type=%d, samples=%d, leftPhase=%.4f, rightPhase=%.4f",
             static_cast<int>(segment.type), samples,
             state.leftPhase, state.rightPhase);
        
        // Начальные и конечные частоты ВСЕГДА вычисляем из таблицы по времени
        // Это гарантирует точное соответствие графику без скачков частот
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, currentTime);
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;
        
        LOG_SEG("SEGMENT_FREQS_NEON: time=%.3f, start=[%.2f, %.2f], end=[%.2f, %.2f], type=%d",
             currentTime,
             startLeftFreq, startRightFreq,
             endLeftFreq, endRightFreq,
             static_cast<int>(segment.type));
        
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
        
        // Логируем omega и амплитуды для отладки
        LOG_SEG("PARAMS_NEON: type=%d, omega=[%.6f, %.6f]->[%.6f, %.6f], amp=[%.4f, %.4f]->[%.4f, %.4f]",
             static_cast<int>(segment.type),
             startLeftOmega, startRightOmega,
             endLeftOmega, endRightOmega,
             startLeftAmp, startRightAmp,
             endLeftAmp, endRightAmp);
        
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
                
            case BufferType::PAUSE:
                updatePhasesOnly(
                    samples,
                    startLeftOmega,
                    startRightOmega,
                    state
                );
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
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
        
        // Логируем первый и последний сэмплы сегмента
        float firstLeftSample = buffer[currentSample * 2];
        float firstRightSample = buffer[currentSample * 2 + 1];
        float lastLeftSample = buffer[(currentSample + samples - 1) * 2];
        float lastRightSample = buffer[(currentSample + samples - 1) * 2 + 1];
        
        // Вычисляем ожидаемый первый сэмпл через фазу
        float expectedFirstLeft = Wavetable::fastSin(state.leftPhase);
        float expectedFirstRight = Wavetable::fastSin(state.rightPhase);
        
        // Логируем фазу ПОСЛЕ генерации сегмента
        LOG_SEG("SEG_END_NEON: type=%d, leftPhase=%.4f, rightPhase=%.4f, first=[%.4f, %.4f], last=[%.4f, %.4f], expectedFirst=[%.4f, %.4f]",
             static_cast<int>(segment.type),
             state.leftPhase, state.rightPhase,
             firstLeftSample, firstRightSample,
             lastLeftSample, lastRightSample,
             expectedFirstLeft, expectedFirstRight);
        
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
    result.samplesGenerated = currentSample;  // Возвращаем реальное количество сэмплов
    
    return result;
}
#endif // USE_NEON

#ifdef USE_SSE
GenerateResult AudioGenerator::generatePackageSse(
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
        // Вычисляем количество сэмплов и реальную длительность в секундах
        // КРИТИЧНО: durationSec вычисляем из сэмплов для согласованности времени
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(samples) / m_sampleRate;
        
        if (samples <= 0) continue;
        
        // Логируем фазу ДО генерации сегмента
        LOG_SEG("SEG_START_SSE: type=%d, samples=%d, leftPhase=%.4f, rightPhase=%.4f",
             static_cast<int>(segment.type), samples,
             state.leftPhase, state.rightPhase);
        
        // Начальные и конечные частоты ВСЕГДА вычисляем из таблицы по времени
        // Это гарантирует точное соответствие графику без скачков частот
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(config.curve, currentTime);
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve, currentTime + durationSec
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;
        
        LOG_SEG("SEGMENT_FREQS_SSE: time=%.3f, start=[%.2f, %.2f], end=[%.2f, %.2f], type=%d",
             currentTime,
             startLeftFreq, startRightFreq,
             endLeftFreq, endRightFreq,
             static_cast<int>(segment.type));
        
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
                generateSolidBufferSse(
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
                generateFadeBufferSse(
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
                
            case BufferType::PAUSE:
                updatePhasesOnly(
                    samples,
                    startLeftOmega,
                    startRightOmega,
                    state
                );
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
                break;
                
            case BufferType::FADE_IN:
                generateFadeBufferSse(
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
        
        // Логируем фазу ПОСЛЕ генерации сегмента
        LOG_SEG("SEG_END_SSE: type=%d, leftPhase=%.4f, rightPhase=%.4f",
             static_cast<int>(segment.type),
             state.leftPhase, state.rightPhase);
        
        if (segment.swapAfterSegment) {
            state.channelsSwapped = !state.channelsSwapped;
            result.channelsSwapped = true;
            
            LOGD("PackageGenSse: swap at elapsedMs=%lld, channelsSwapped=%d",
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
    result.samplesGenerated = currentSample;  // Возвращаем реальное количество сэмплов
    
    return result;
}
#endif // USE_SSE

} // namespace binaural
