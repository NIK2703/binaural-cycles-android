#include "AudioGenerator.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <utility>

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

// Логирование для отладки стыков буферов.
// ВАЖНО: отключено по умолчанию и включается ТОЛЬКО при
//   adb shell setprop debug.binaural.segment_log 1
// иначе в обычной/debug сборке эти строки (по одной на КАЖДЫЙ сегмент!)
// засоряют logcat сотнями тысяч строк в секунду (файл в сотни МБ).
#ifdef AUDIO_TEST_BUILD
#define LOG_SEG(...) ((void)0)
#elif defined(ANDROID)
#include <android/log.h>
#include <sys/system_properties.h>
inline bool segmentDebugLogEnabled() {
    static const bool enabled = []() {
        char v[PROP_VALUE_MAX] = {0};
        return __system_property_get("debug.binaural.segment_log", v) > 0 && v[0] == '1';
    }();
    return enabled;
}
#define LOG_SEG(...) do { if (segmentDebugLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, "SEGMENT_DEBUG", __VA_ARGS__); } while (0)
#else
#include "../tests/android_stub.h"
#define LOG_SEG(...) __android_log_print(ANDROID_LOG_DEBUG, "SEGMENT_DEBUG", __VA_ARGS__)
#endif

namespace binaural {

// Предвычисленные константы для оптимизации
static constexpr float ONE_OVER_TWO_PI = 1.0f / AudioGenerator::TWO_PI;

// ===== Бисекция: debug.binaural.constant_freq=1 → постоянная частота мимо кривой.
// Если щелчки на границах пакетов исчезают — виновата кривая/таймлайн/нормализация;
// если остаются — свап/амплитуда/AudioTrack/запись.
#if defined(ANDROID) && !defined(AUDIO_TEST_BUILD)
#include <sys/system_properties.h>
#endif

namespace {
inline bool bisectionConstantFreq() {
#if defined(ANDROID) && !defined(AUDIO_TEST_BUILD)
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.binaural.constant_freq", v) > 0 && v[0] == '1') return true;
#endif
    return false;
}
} // namespace

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

        // G2: progress==1.0 даёт index==TABLE_SIZE — чтение values[index+1]
        // вышло бы за границу массива
        if (index >= TABLE_SIZE) {
            return values[TABLE_SIZE];
        }

        const float y0 = values[index];
        const float y1 = values[index + 1];
        return y0 + fraction * (y1 - y0);
    }
};

static const FadeCurveTable s_fadeCurveTable;

// Границы (в сэмплах от начала сегмента) контрольных точек STEP-кривой внутри сегмента
static std::vector<int> collectStepBoundaries(
    const FrequencyCurve& curve,
    double startTimeSeconds,
    double secPerSample,
    int samples
) {
    std::vector<int> bounds;
    if (samples <= 1 || !(secPerSample > 0.0)) {
        return bounds;
    }
    const double endTime = startTimeSeconds + secPerSample * samples;
    const double day = static_cast<double>(SECONDS_PER_DAY);
    for (const auto& p : curve.points) {
        const double pt = static_cast<double>(p.timeSeconds);
        const int64_t kFirst = static_cast<int64_t>(std::floor((startTimeSeconds - pt) / day)) - 1;
        const int64_t kLast = static_cast<int64_t>(std::ceil((endTime - pt) / day)) + 1;
        for (int64_t k = kFirst; k <= kLast; ++k) {
            const double occurrence = pt + static_cast<double>(k) * day;
            if (occurrence <= startTimeSeconds || occurrence >= endTime) {
                continue;
            }
            const int n = static_cast<int>(
                std::ceil((occurrence - startTimeSeconds) / secPerSample));
            if (n > 0 && n < samples) {
                bounds.push_back(n);
            }
        }
    }
    std::sort(bounds.begin(), bounds.end());
    bounds.erase(std::unique(bounds.begin(), bounds.end()), bounds.end());
    return bounds;
}

// Смещение (в сэмплах от начала окна) БЛИЖАЙШЕЙ границы ступени STEP-кривой
// внутри окна [startTimeSeconds, startTimeSeconds + secPerSample*maxSamples).
// 0 — границ внутри окна нет. Без выделений (вызывается на аудио-потоке).
static int stepBoundaryOffset(
    const FrequencyCurve& curve,
    double startTimeSeconds,
    double secPerSample,
    int maxSamples
) {
    if (maxSamples <= 1 || !(secPerSample > 0.0)) {
        return 0;
    }
    const double endTime = startTimeSeconds + secPerSample * maxSamples;
    const double day = static_cast<double>(SECONDS_PER_DAY);
    int best = 0;
    for (const auto& p : curve.points) {
        const double pt = static_cast<double>(p.timeSeconds);
        const int64_t kFirst = static_cast<int64_t>(std::floor((startTimeSeconds - pt) / day)) - 1;
        const int64_t kLast  = static_cast<int64_t>(std::ceil((endTime - pt) / day)) + 1;
        for (int64_t k = kFirst; k <= kLast; ++k) {
            const double occurrence = pt + static_cast<double>(k) * day;
            if (occurrence <= startTimeSeconds || occurrence >= endTime) {
                continue;
            }
            const int n = static_cast<int>(
                std::ceil((occurrence - startTimeSeconds) / secPerSample));
            if (n > 0 && n < maxSamples && (best == 0 || n < best)) {
                best = n;
            }
        }
    }
    return best;
}

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
    // samples <= 1: omegaStep = 0 — константная частота старта, деления на ноль нет
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
            if (state.leftPhase < 0.0f) {
                state.leftPhase += TWO_PI;
            }

            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
            if (state.rightPhase < 0.0f) {
                state.rightPhase += TWO_PI;
            }

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
            if (state.leftPhase < 0.0f) {
                state.leftPhase += TWO_PI;
            }

            state.rightPhase += rightOmega;
            state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
            if (state.rightPhase < 0.0f) {
                state.rightPhase += TWO_PI;
            }

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
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }

        state.rightPhase += rightOmega;
        state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }

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
    float startLeftOmega,
    float startRightOmega,
    float endLeftOmega,
    float endRightOmega,
    GeneratorState& state
) {
    // Фикс (Qwen, P3): линейная рампа omega вместо константы — та же модель
    // фазового накопления, что в SOLID-сегментах (omega(samples-1) == endOmega).
    // Иначе после PAUSE фаза не совпадает с непрерывной кривой частот.
    const float leftOmegaStep = (samples > 1) ? (endLeftOmega - startLeftOmega) / (samples - 1) : 0.0f;
    const float rightOmegaStep = (samples > 1) ? (endRightOmega - startRightOmega) / (samples - 1) : 0.0f;

    float leftOmega = startLeftOmega;
    float rightOmega = startRightOmega;

    for (int i = 0; i < samples; ++i) {
        state.leftPhase += leftOmega;
        state.leftPhase -= TWO_PI * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }

        state.rightPhase += rightOmega;
        state.rightPhase -= TWO_PI * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }

        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
    }
}

void AudioGenerator::updatePhasesOverCurve(
    const BinauralConfig& config,
    int samples,
    double currentTime,
    float timeScale,
    bool constantFreq,
    GeneratorState& state
) {
    if (samples <= 0) {
        return;
    }
    // Пауза: звука нет, но фазы должны идти по кривой. Режем на кусочки
    // <=100 мс — та же модель, что в FADE_OUT/FADE_IN и в подсегментах SOLID.
    const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
    const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
    const float twoPiOverSampleRate = TWO_PI / static_cast<float>(m_sampleRate);
    const bool stepMode = !constantFreq &&
        config.curve.interpolationType == InterpolationType::STEP &&
        config.curve.points.size() > 1;

    int gen = 0;
    while (gen < samples) {
        int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
        // STEP: кусочек не должен перешагивать границу ступени, иначе частота
        // внутри паузы рампилась бы вместо мгновенного скачка.
        if (stepMode) {
            const int cut = stepBoundaryOffset(
                config.curve,
                currentTime + static_cast<double>(gen) * secPerSample,
                secPerSample,
                ps);
            if (cut > 0 && cut < ps) {
                ps = cut;
            }
        }
        const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
        const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
        auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
        auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
        if (constantFreq) {
            // Бисекция: игнорируем кривую, частота постоянна.
            f0.lowerFreq = f1.lowerFreq = 200.0f;
            f0.upperFreq = f1.upperFreq = 206.0f;
        }
        if (stepMode) {
            // STEP: hold the step value inside the piece (see the fade loops).
            f1.lowerFreq = f0.lowerFreq;
            f1.upperFreq = f0.upperFreq;
        }
        updatePhasesOnly(
            ps,
            twoPiOverSampleRate * f0.lowerFreq,
            twoPiOverSampleRate * f0.upperFreq,
            twoPiOverSampleRate * f1.lowerFreq,
            twoPiOverSampleRate * f1.upperFreq,
            state
        );
        gen += ps;
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
            if (leftPhaseBase < 0.0f) {
                leftPhaseBase += static_cast<float>(TWO_PI);
            }
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            if (rightPhaseBase < 0.0f) {
                rightPhaseBase += static_cast<float>(TWO_PI);
            }
            
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
            if (leftPhaseBase < 0.0f) {
                leftPhaseBase += static_cast<float>(TWO_PI);
            }
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            if (rightPhaseBase < 0.0f) {
                rightPhaseBase += static_cast<float>(TWO_PI);
            }
            
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
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }
        
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
        const float firstCosProgress = s_fadeCurveTable.get(firstProgress);
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
                fadeCompleted = true;
            } else if (fadeProgress >= 0) {
                const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
                const float cosProgress = s_fadeCurveTable.get(progress);
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
        if (leftPhaseBase < 0.0f) {
            leftPhaseBase += static_cast<float>(TWO_PI);
        }
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        if (rightPhaseBase < 0.0f) {
            rightPhaseBase += static_cast<float>(TWO_PI);
        }
        
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
            const float cosProgress = s_fadeCurveTable.get(progress);
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }
        
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
            if (leftPhaseBase < 0.0f) {
                leftPhaseBase += static_cast<float>(TWO_PI);
            }
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            if (rightPhaseBase < 0.0f) {
                rightPhaseBase += static_cast<float>(TWO_PI);
            }
            
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
            if (leftPhaseBase < 0.0f) {
                leftPhaseBase += static_cast<float>(TWO_PI);
            }
            rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
            rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
            if (rightPhaseBase < 0.0f) {
                rightPhaseBase += static_cast<float>(TWO_PI);
            }
            
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
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }
        
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
                fadeCompleted = true;
            } else if (fadeProgress >= 0) {
                const float progress = static_cast<float>(fadeProgress) * invFadeDuration;
                const float cosProgress = s_fadeCurveTable.get(progress);
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
        if (leftPhaseBase < 0.0f) {
            leftPhaseBase += static_cast<float>(TWO_PI);
        }
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        rightPhaseBase -= static_cast<float>(TWO_PI) * static_cast<int>(rightPhaseBase * ONE_OVER_TWO_PI);
        if (rightPhaseBase < 0.0f) {
            rightPhaseBase += static_cast<float>(TWO_PI);
        }
        
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
            const float cosProgress = s_fadeCurveTable.get(progress);
            fadeMultiplier = fadingOut ? (1.0f - cosProgress) : cosProgress;
        }
        
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        state.leftPhase += leftOmega;
        state.leftPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.leftPhase * ONE_OVER_TWO_PI);
        if (state.leftPhase < 0.0f) {
            state.leftPhase += TWO_PI;
        }
        
        state.rightPhase += rightOmega;
        state.rightPhase -= static_cast<float>(TWO_PI) * static_cast<int>(state.rightPhase * ONE_OVER_TWO_PI);
        if (state.rightPhase < 0.0f) {
            state.rightPhase += TWO_PI;
        }
        
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
    int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();
    
    const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);
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
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(
            config.curve, static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve,
            static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;

        if (constantFreq) {
            // Бисекция: игнорируем кривую. Частота/амплитуда постоянны во всех пакетах.
            startLeftFreq = endLeftFreq = 200.0f;
            startRightFreq = endRightFreq = 206.0f;   // beat 6 Гц
        }

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
                // STEP: ступенька должна быть мгновенной — режем сегмент по границам
                // контрольных точек, в каждом под-кусочке частота константна (Δω=0)
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int> stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = getChannelFrequenciesAt(
                                config.curve,
                                static_cast<float>(currentTime + pieceStart * stepSecPerSample)
                            );
                            const float pieceLeftFreq = pieceFreq.lowerFreq;
                            const float pieceRightFreq = pieceFreq.upperFreq;
                            auto [pieceLeftAmp, pieceRightAmp] = calculateNormalizedAmplitudes(
                                pieceLeftFreq, pieceRightFreq, config, config.curve
                            );
                            const float pieceLeftOmega = twoPiOverSampleRate * pieceLeftFreq;
                            const float pieceRightOmega = twoPiOverSampleRate * pieceRightFreq;
                            generateSolidBuffer(
                                buffer + (currentSample + pieceStart) * 2,
                                pieceEnd - pieceStart,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftAmp, pieceRightAmp,
                                pieceLeftAmp, pieceRightAmp,
                                state.channelsSwapped,
                                state
                            );
                            pieceStart = pieceEnd;
                        }
                        break;
                    }
                    // G1: граница ступени совпала с концом сегмента — держим
                    // частоту старта (Δω=0), а не портаменто к постступенчатому значению
                    generateSolidBuffer(
                        buffer + currentSample * 2,
                        samples,
                        startLeftOmega, startRightOmega,
                        startLeftOmega, startRightOmega,
                        startLeftAmp, startRightAmp,
                        endLeftAmp, endRightAmp,
                        state.channelsSwapped,
                        state
                    );
                    break;
                }
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
                
            case BufferType::FADE_OUT: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBuffer(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        true,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }

            case BufferType::PAUSE:
                // Пауза: тишина, но фазы продолжают обновляться
                // Это обеспечивает бесшовное продолжение после паузы
                updatePhasesOverCurve(
                    config,
                    samples,
                    currentTime,
                    timeScale,
                    constantFreq,
                    state
                );
                // Заполняем буфер тишиной
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
                break;
                
            case BufferType::FADE_IN: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBuffer(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        false,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }
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
        currentTime += static_cast<double>(durationSec) * timeScale;
        // Ось расписания из фактических сэмплов — без дрейфа против аудио
        currentElapsedMs = elapsedMs + (static_cast<int64_t>(currentSample) * 1000) / m_sampleRate;
        
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
    int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);
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
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(
            config.curve, static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve,
            static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;

        if (constantFreq) {
            // Бисекция: игнорируем кривую. Частота/амплитуда постоянны во всех пакетах.
            startLeftFreq = endLeftFreq = 200.0f;
            startRightFreq = endRightFreq = 206.0f;   // beat 6 Гц
        }

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
                // STEP: режем сегмент по границам контрольных точек, Δω=0 внутри кусочка
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int> stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = getChannelFrequenciesAt(
                                config.curve,
                                static_cast<float>(currentTime + pieceStart * stepSecPerSample)
                            );
                            const float pieceLeftFreq = pieceFreq.lowerFreq;
                            const float pieceRightFreq = pieceFreq.upperFreq;
                            auto [pieceLeftAmp, pieceRightAmp] = calculateNormalizedAmplitudes(
                                pieceLeftFreq, pieceRightFreq, config, config.curve
                            );
                            const float pieceLeftOmega = twoPiOverSampleRate * pieceLeftFreq;
                            const float pieceRightOmega = twoPiOverSampleRate * pieceRightFreq;
                            generateSolidBufferNeon(
                                buffer + (currentSample + pieceStart) * 2,
                                pieceEnd - pieceStart,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftAmp, pieceRightAmp,
                                pieceLeftAmp, pieceRightAmp,
                                state.channelsSwapped,
                                state
                            );
                            pieceStart = pieceEnd;
                        }
                        break;
                    }
                    // G1: см. скалярный путь — держим частоту старта
                    generateSolidBufferNeon(
                        buffer + currentSample * 2,
                        samples,
                        startLeftOmega, startRightOmega,
                        startLeftOmega, startRightOmega,
                        startLeftAmp, startRightAmp,
                        endLeftAmp, endRightAmp,
                        state.channelsSwapped,
                        state
                    );
                    break;
                }
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
                
            case BufferType::FADE_OUT: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBufferNeon(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        true,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }
                
            case BufferType::PAUSE:
                updatePhasesOverCurve(
                    config,
                    samples,
                    currentTime,
                    timeScale,
                    constantFreq,
                    state
                );
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
                break;

            case BufferType::FADE_IN: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBufferNeon(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        false,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }
        }
        
        // Логируем первый и последний сэмплы сегмента
        float firstLeftSample = buffer[currentSample * 2];
        float firstRightSample = buffer[currentSample * 2 + 1];
        float lastLeftSample = buffer[(currentSample + samples - 1) * 2];
        float lastRightSample = buffer[(currentSample + samples - 1) * 2 + 1];
        
        // Вычисляем ожидаемый первый сэмпл СЛЕДУЮЩЕГО сегмента через фазу.
        // Фикс (Qwen, P2): учитываем амплитуду (baseVolumeFactor × endAmp),
        // иначе сравнение с фактическим first вводит в заблуждение:
        // сырой sin(phase) больше реального сэмпла в 1/(0.5·amp) раз.
        constexpr float baseVolumeFactor = 0.5f;
        float expectedFirstLeft = Wavetable::fastSin(state.leftPhase) * baseVolumeFactor * endLeftAmp;
        float expectedFirstRight = Wavetable::fastSin(state.rightPhase) * baseVolumeFactor * endRightAmp;

        // При активном свапе каналы в буфере меняются местами
        if (state.channelsSwapped) {
            std::swap(expectedFirstLeft, expectedFirstRight);
        }
        
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
        currentTime += static_cast<double>(durationSec) * timeScale;
        // Ось расписания из фактических сэмплов — без дрейфа против аудио
        currentElapsedMs = elapsedMs + (static_cast<int64_t>(currentSample) * 1000) / m_sampleRate;
        
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
    int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);
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
        FrequencyTableResult startFreqResult = getChannelFrequenciesAt(
            config.curve, static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = getChannelFrequenciesAt(
            config.curve,
            static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
        );
        float endLeftFreq = endFreqResult.lowerFreq;
        float endRightFreq = endFreqResult.upperFreq;

        if (constantFreq) {
            // Бисекция: игнорируем кривую. Частота/амплитуда постоянны во всех пакетах.
            startLeftFreq = endLeftFreq = 200.0f;
            startRightFreq = endRightFreq = 206.0f;   // beat 6 Гц
        }

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
                // STEP: режем сегмент по границам контрольных точек, Δω=0 внутри кусочка
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int> stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = getChannelFrequenciesAt(
                                config.curve,
                                static_cast<float>(currentTime + pieceStart * stepSecPerSample)
                            );
                            const float pieceLeftFreq = pieceFreq.lowerFreq;
                            const float pieceRightFreq = pieceFreq.upperFreq;
                            auto [pieceLeftAmp, pieceRightAmp] = calculateNormalizedAmplitudes(
                                pieceLeftFreq, pieceRightFreq, config, config.curve
                            );
                            const float pieceLeftOmega = twoPiOverSampleRate * pieceLeftFreq;
                            const float pieceRightOmega = twoPiOverSampleRate * pieceRightFreq;
                            generateSolidBufferSse(
                                buffer + (currentSample + pieceStart) * 2,
                                pieceEnd - pieceStart,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftOmega, pieceRightOmega,
                                pieceLeftAmp, pieceRightAmp,
                                pieceLeftAmp, pieceRightAmp,
                                state.channelsSwapped,
                                state
                            );
                            pieceStart = pieceEnd;
                        }
                        break;
                    }
                    // G1: см. скалярный путь — держим частоту старта
                    generateSolidBufferSse(
                        buffer + currentSample * 2,
                        samples,
                        startLeftOmega, startRightOmega,
                        startLeftOmega, startRightOmega,
                        startLeftAmp, startRightAmp,
                        endLeftAmp, endRightAmp,
                        state.channelsSwapped,
                        state
                    );
                    break;
                }
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
                
            case BufferType::FADE_OUT: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBufferSse(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        true,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }
                
            case BufferType::PAUSE:
                updatePhasesOverCurve(
                    config,
                    samples,
                    currentTime,
                    timeScale,
                    constantFreq,
                    state
                );
                std::memset(buffer + currentSample * 2, 0, samples * 2 * sizeof(float));
                break;

            case BufferType::FADE_IN: {
                // Ручные планы могут не задавать fadeTotalMs/fadeOffsetMs (==0) —
                // тогда огибающая вырождается и фейд нем (тишина). Считаем
                // весь сегмент полным фейдом.
                int fadeOffsetMs = segment.fadeOffsetMs;
                int fadeTotalMs = segment.fadeTotalMs;
                if (fadeTotalMs == 0) {
                    fadeTotalMs = segment.durationMs;
                    fadeOffsetMs = 0;
                }
                const int fadeOffsetSamples = static_cast<int>(
                    (fadeOffsetMs * m_sampleRate + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>(
                    (fadeTotalMs * m_sampleRate + 500) / 1000);

                // Режем фейд на подсегменты <=100 мс и рампим частоту между
                // значениями кривой на границах каждого (точность = SOLID).
                // fadeProgress = fadeOffsetSamples + gen + i — поточечно тот же,
                // что у одного длинного фейда, поэтому щелчков нет.
                const int pieceSamples = (100 * m_sampleRate + 500) / 1000;
                const double secPerSample = static_cast<double>(timeScale) / m_sampleRate;
                // STEP: ступенька должна быть мгновенной — кусочек не перешагивает
                // границу контрольной точки, иначе частота внутри фейда рампилась
                // бы вместо скачка (SOLID это умеет через collectStepBoundaries,
                // из-за чего фейд расходился с SOLID в 4 раза на ступеньке).
                const bool stepMode = !constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1;
                int gen = 0;
                while (gen < samples) {
                    int ps = (pieceSamples < samples - gen) ? pieceSamples : (samples - gen);
                    if (stepMode) {
                        const int cut = stepBoundaryOffset(config.curve,
                            currentTime + static_cast<double>(gen) * secPerSample,
                            secPerSample, ps);
                        if (cut > 0 && cut < ps) {
                            ps = cut;
                        }
                    }
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    auto f0 = getChannelFrequenciesAt(config.curve, static_cast<float>(t0));
                    auto f1 = getChannelFrequenciesAt(config.curve, static_cast<float>(t1));
                    if (constantFreq) {
                        f0.lowerFreq = f1.lowerFreq = 200.0f;
                        f0.upperFreq = f1.upperFreq = 206.0f;
                    }
                    if (stepMode) {
                        // STEP: inside a step the frequency is CONSTANT (delta omega = 0),
                        // exactly as in SOLID. A chord f0->f1 would smear the
                        // instantaneous jump into a ramp across the whole piece:
                        // when a step boundary lands on the piece END (stepBoundaryOffset
                        // does not cut there, it is not strictly inside), the pre-step
                        // value would already ramp toward the post-step one.
                        f1.lowerFreq = f0.lowerFreq;
                        f1.upperFreq = f0.upperFreq;
                    }
                    const float l0 = twoPiOverSampleRate * f0.lowerFreq;
                    const float r0 = twoPiOverSampleRate * f0.upperFreq;
                    const float l1 = twoPiOverSampleRate * f1.lowerFreq;
                    const float r1 = twoPiOverSampleRate * f1.upperFreq;
                    auto [a0l, a0r] = calculateNormalizedAmplitudes(f0.lowerFreq, f0.upperFreq, config, config.curve);
                    auto [a1l, a1r] = calculateNormalizedAmplitudes(f1.lowerFreq, f1.upperFreq, config, config.curve);
                    if (generateFadeBufferSse(
                        buffer + (currentSample + gen) * 2,
                        ps,
                        l0, r0, l1, r1,
                        a0l, a0r, a1l, a1r,
                        fadeOffsetSamples + gen,
                        fadeTotalSamples,
                        false,
                        state.channelsSwapped,
                        state
                    )) {
                        result.fadePhaseCompleted = true;
                    }
                    gen += ps;
                }
                break;
            }
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
        currentTime += static_cast<double>(durationSec) * timeScale;
        // Ось расписания из фактических сэмплов — без дрейфа против аудио
        currentElapsedMs = elapsedMs + (static_cast<int64_t>(currentSample) * 1000) / m_sampleRate;
        
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
