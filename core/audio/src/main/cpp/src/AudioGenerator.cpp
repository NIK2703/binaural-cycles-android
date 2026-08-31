#include "AudioGenerator.h"
#include "ChannelLayout.h"
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
#define LOGD_ENABLED() false
#elif defined(AUDIO_DEBUG) && defined(ANDROID)
#include <android/log.h>
#define LOG_TAG "AudioGenerator"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGD_ENABLED() true
#else
#define LOGD(...) ((void)0)
#define LOGD_ENABLED() false
#endif

// Логирование для отладки стыков буферов.
// ВАЖНО: отключено по умолчанию и включается ТОЛЬКО при
//   adb shell setprop debug.binaural.segment_log 1
// иначе в обычной/debug сборке эти строки (по одной на КАЖДЫЙ сегмент!)
// засоряют logcat сотнями тысяч строк в секунду (файл в сотни МБ).
#ifdef AUDIO_TEST_BUILD
#define LOG_SEG(...) ((void)0)
#define SEG_LOG_ENABLED() false
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
// Гард для ВЫЧИСЛЕНИЙ, которые нужны только этой диагностике. Без него они
// выполнялись на каждом сегменте (два чтения из буфера, fastSin, swap) даже
// при выключенном логе — ради строки, которую никто не напечатает. Проверка
// дешёвая: segmentDebugLogEnabled() читает один раз инициализированный static.
#define SEG_LOG_ENABLED() segmentDebugLogEnabled()
#else
#include "../tests/android_stub.h"
#define LOG_SEG(...) __android_log_print(ANDROID_LOG_DEBUG, "SEGMENT_DEBUG", __VA_ARGS__)
#define SEG_LOG_ENABLED() true
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

// Границы (в сэмплах от начала сегмента) контрольных точек STEP-кривой внутри сегмента.
//
// ВОЗВРАЩАЕТ ССЫЛКУ НА thread_local-буфер, а не новый vector: вызывается на
// аудио-потоке для КАЖДОГО STEP-сегмента (сегмент ≤100 мс ⇒ десятки раз в
// секунду аудио), и раньше каждый вызов аллокировал. Буфер переиспользуется,
// поэтому вызывающий обязан потребить результат до следующего вызова — в
// generatePackage* так и есть (один проход по кусочкам, без вложенных вызовов).
static const std::vector<int>& collectStepBoundaries(
    const FrequencyCurve& curve,
    double startTimeSeconds,
    double secPerSample,
    int samples
) {
    thread_local std::vector<int> bounds;
    bounds.clear();
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

    // ШАГ 2 МИГРАЦИИ: раскладка вошла в сами частоты (channelsAt()), поэтому
    // перестановки выходного буфера больше не существует — left всегда i*2,
    // right всегда i*2+1, безусловно.
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
    const float32x4_t vIndices = {0.0f, 1.0f, 2.0f, 3.0f};
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const float32x4_t vPhaseAccum = {0.0f, 0.0f, 1.0f, 3.0f};
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int neonEnd = samples - 3;
    
    // ШАГ 2 МИГРАЦИИ: раскладка вошла в сами частоты (channelsAt()) —
    // интерлив безусловен: left всегда первый регистр, right всегда второй.
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

        // Дешёвая обмотка фазы: вместо floor через приведение к int
        // (mul + cvt f2i + cvt i2f + mul + sub + compare) — два условных
        // вычитания. Достаточно, т.к. прирост за 4 сэмпла < 4π на худшем
        // сочетании SR/частоты движка (SR 8000, тон 2000 Гц => ровно 2π).
        // Выход побитово совпадает с прежней формулой.
        leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
        if (leftPhaseBase >= static_cast<float>(TWO_PI)) leftPhaseBase -= static_cast<float>(TWO_PI);
        if (leftPhaseBase >= static_cast<float>(TWO_PI)) leftPhaseBase -= static_cast<float>(TWO_PI);
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        if (rightPhaseBase >= static_cast<float>(TWO_PI)) rightPhaseBase -= static_cast<float>(TWO_PI);
        if (rightPhaseBase >= static_cast<float>(TWO_PI)) rightPhaseBase -= static_cast<float>(TWO_PI);

        leftOmega += leftOmegaStep * 4;
        rightOmega += rightOmegaStep * 4;
        leftAmplitude += ampStepLeft * 4;
        rightAmplitude += ampStepRight * 4;

        float32x4x2_t vInterleaved = {vLeftSamples, vRightSamples};
        vst2q_f32(buffer + i * 2, vInterleaved);
    }
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    for (; i < samples; ++i) {
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        // Та же дешёвая обмотка (см. основной SSE-цикл): прирост за один
        // сэмпл <= pi/2 (SR 8000, тон 2000 Гц), одного вычитания хватает.
        state.leftPhase += leftOmega;
        if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;
        if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;

        state.rightPhase += rightOmega;
        if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
        if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
        
        const float leftAmp = baseVolumeFactor * leftAmplitude;
        const float rightAmp = baseVolumeFactor * rightAmplitude;
        
        buffer[i * 2] = leftSample * leftAmp;
        buffer[i * 2 + 1] = rightSample * rightAmp;
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
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
    const __m128 vIndices = _mm_set_ps(3.0f, 2.0f, 1.0f, 0.0f);
    // i*(i-1)/2 для i=0,1,2,3 = 0,0,1,3
    const __m128 vPhaseAccum = _mm_set_ps(3.0f, 1.0f, 0.0f, 0.0f);
    
    float leftPhaseBase = state.leftPhase;
    float rightPhaseBase = state.rightPhase;
    
    int i = 0;
    const int sseEnd = samples - 3;
    
    // ШАГ 2 МИГРАЦИИ: раскладка вошла в сами частоты (channelsAt()) —
    // store безусловен: left = (i+j)*2, right = (i+j)*2+1.
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

        __m128 vLeftSamples = Wavetable::fastSinSseNonNeg(vLeftPhasesScaled);
        __m128 vRightSamples = Wavetable::fastSinSseNonNeg(vRightPhasesScaled);

        __m128 vLeftAmps = _mm_mul_ps(vBaseVol, vAmpL);
        __m128 vRightAmps = _mm_mul_ps(vBaseVol, vAmpR);

        vLeftSamples = _mm_mul_ps(vLeftSamples, vLeftAmps);
        vRightSamples = _mm_mul_ps(vRightSamples, vRightAmps);

        // Дешёвая обмотка фазы: вместо floor через приведение к int
        // (mul + cvt f2i + cvt i2f + mul + sub + compare) — два условных
        // вычитания. Достаточно, т.к. прирост за 4 сэмпла < 4π на худшем
        // сочетании SR/частоты движка (SR 8000, тон 2000 Гц => ровно 2π).
        // Выход побитово совпадает с прежней формулой.
        leftPhaseBase += leftOmega * 4 + leftOmegaStep * 6;
        if (leftPhaseBase >= static_cast<float>(TWO_PI)) leftPhaseBase -= static_cast<float>(TWO_PI);
        if (leftPhaseBase >= static_cast<float>(TWO_PI)) leftPhaseBase -= static_cast<float>(TWO_PI);
        rightPhaseBase += rightOmega * 4 + rightOmegaStep * 6;
        if (rightPhaseBase >= static_cast<float>(TWO_PI)) rightPhaseBase -= static_cast<float>(TWO_PI);
        if (rightPhaseBase >= static_cast<float>(TWO_PI)) rightPhaseBase -= static_cast<float>(TWO_PI);

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
    
    state.leftPhase = leftPhaseBase;
    state.rightPhase = rightPhaseBase;
    
    for (; i < samples; ++i) {
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        // Та же дешёвая обмотка (см. основной SSE-цикл): прирост за один
        // сэмпл <= pi/2 (SR 8000, тон 2000 Гц), одного вычитания хватает.
        state.leftPhase += leftOmega;
        if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;
        if (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;

        state.rightPhase += rightOmega;
        if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
        if (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
        
        const float leftAmp = baseVolumeFactor * leftAmplitude;
        const float rightAmp = baseVolumeFactor * rightAmplitude;
        
        buffer[i * 2] = leftSample * leftAmp;
        buffer[i * 2 + 1] = rightSample * rightAmp;
        
        leftOmega += leftOmegaStep;
        rightOmega += rightOmegaStep;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
    }
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
    // elapsedMs после удаления flip-блоков не используется: единственный
    // потребитель был LOGD в swapAfterSegment. Параметр остаётся в API до
    // реструктуризации шага 3.
    [[maybe_unused]] int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();

    // ЕДИНАЯ точка истины частот ушей: раскладка (знак beat) применяется
    // ДО осцилляторов, поэтому звук, фазы паузы и телеметрия берут частоты
    // из одного места и разойтись не могут.
    auto earFreqsAt = [&](double t) -> FrequencyTableResult {
        return constantFreq
            // debug-бисекция: кривая игнорируется, раскладка сохраняется.
            ? channelsAtConstant(config, static_cast<float>(t), 203.0f, 6.0f)
            : channelsAt(config, static_cast<float>(t));
    };
    
    const float twoPiOverSampleRate = TWO_PI / m_sampleRate;
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);

    float lastLeftFreq = 0.0f;
    float lastRightFreq = 0.0f;
    
    for (const auto& segment : plan.segments) {
        // Вычисляем количество сэмплов и реальную длительность в секундах
        // КРИТИЧНО: durationSec вычисляем из сэмплов для согласованности времени
        const int samples = static_cast<int>((segment.durationMs * m_sampleRate) / 1000);
        const float durationSec = static_cast<float>(samples) / m_sampleRate;
        
        if (samples <= 0) continue;
        
        // prevSample и фаза ДО генерации нужны только SEGMENT_DEBUG: вне
        // логирования два чтения из буфера делать незачем (см. SEG_LOG_ENABLED).
        if (SEG_LOG_ENABLED()) {
            float lastLeftSample = 0.0f;
            float lastRightSample = 0.0f;
            if (currentSample > 0) {
                lastLeftSample = buffer[(currentSample - 1) * 2];
                lastRightSample = buffer[(currentSample - 1) * 2 + 1];
            }
            LOG_SEG("SEG_START: type=%d, samples=%d, leftPhase=%.4f, rightPhase=%.4f, prevSample=[%.4f, %.4f]",
                 static_cast<int>(segment.type), samples,
                 state.leftPhase, state.rightPhase,
                 lastLeftSample, lastRightSample);
        }
        
        // Начальные и конечные частоты ВСЕГДА вычисляем из таблицы по времени
        // Это гарантирует точное соответствие графику без скачков частот
        FrequencyTableResult startFreqResult = earFreqsAt(static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = earFreqsAt(static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
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
                // STEP: ступенька должна быть мгновенной — режем сегмент по границам
                // контрольных точек, в каждом под-кусочке частота константна (Δω=0)
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int>& stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = earFreqsAt(static_cast<float>(currentTime + pieceStart * stepSecPerSample)
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
                    state
                );
                break;
                
            case BufferType::FADE_OUT: break;
            case BufferType::PAUSE: break;
            case BufferType::FADE_IN: break;
        }
        
        // lastSample и фаза ПОСЛЕ генерации — только для SEGMENT_DEBUG.
        if (SEG_LOG_ENABLED()) {
            const float endLeftSample = buffer[(currentSample + samples - 1) * 2];
            const float endRightSample = buffer[(currentSample + samples - 1) * 2 + 1];
            LOG_SEG("SEG_END: type=%d, leftPhase=%.4f, rightPhase=%.4f, lastSample=[%.4f, %.4f]",
                 static_cast<int>(segment.type),
                 state.leftPhase, state.rightPhase,
                 endLeftSample, endRightSample);
        }
        
        currentSample += samples;
        currentTime += static_cast<double>(durationSec) * timeScale;
        
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
    // elapsedMs после удаления flip-блоков не используется: единственный
    // потребитель был LOGD в swapAfterSegment. Параметр остаётся в API до
    // реструктуризации шага 3.
    [[maybe_unused]] int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();

    // ЕДИНАЯ точка истины частот ушей: раскладка (знак beat) применяется
    // ДО осцилляторов, поэтому звук, фазы паузы и телеметрия берут частоты
    // из одного места и разойтись не могут.
    auto earFreqsAt = [&](double t) -> FrequencyTableResult {
        return constantFreq
            // debug-бисекция: кривая игнорируется, раскладка сохраняется.
            ? channelsAtConstant(config, static_cast<float>(t), 203.0f, 6.0f)
            : channelsAt(config, static_cast<float>(t));
    };
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);

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
        FrequencyTableResult startFreqResult = earFreqsAt(static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = earFreqsAt(static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
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
                // STEP: режем сегмент по границам контрольных точек, Δω=0 внутри кусочка
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int>& stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = earFreqsAt(static_cast<float>(currentTime + pieceStart * stepSecPerSample)
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
                    state
                );
                break;
                
            case BufferType::FADE_OUT: break;
            case BufferType::PAUSE: break;
            case BufferType::FADE_IN: break;
        }
        
        // Всё это — только для SEGMENT_DEBUG: четыре чтения из буфера, два
        // fastSin и swap на каждом сегменте ради одной строки. Вне логирования
        // делать это незачем (см. SEG_LOG_ENABLED).
        if (SEG_LOG_ENABLED()) {
            // Логируем первый и последний сэмплы сегмента
            const float firstLeftSample = buffer[currentSample * 2];
            const float firstRightSample = buffer[currentSample * 2 + 1];
            const float lastLeftSample = buffer[(currentSample + samples - 1) * 2];
            const float lastRightSample = buffer[(currentSample + samples - 1) * 2 + 1];

            // Вычисляем ожидаемый первый сэмпл СЛЕДУЮЩЕГО сегмента через фазу.
            // Фикс (Qwen, P2): учитываем амплитуду (baseVolumeFactor × endAmp),
            // иначе сравнение с фактическим first вводит в заблуждение:
            // сырой sin(phase) больше реального сэмпла в 1/(0.5·amp) раз.
            constexpr float baseVolumeFactor = 0.5f;
            float expectedFirstLeft = Wavetable::fastSin(state.leftPhase) * baseVolumeFactor * endLeftAmp;
            float expectedFirstRight = Wavetable::fastSin(state.rightPhase) * baseVolumeFactor * endRightAmp;

            // Логируем фазу ПОСЛЕ генерации сегмента
            LOG_SEG("SEG_END_NEON: type=%d, leftPhase=%.4f, rightPhase=%.4f, first=[%.4f, %.4f], last=[%.4f, %.4f], expectedFirst=[%.4f, %.4f]",
                 static_cast<int>(segment.type),
                 state.leftPhase, state.rightPhase,
                 firstLeftSample, firstRightSample,
                 lastLeftSample, lastRightSample,
                 expectedFirstLeft, expectedFirstRight);
        }
        
        currentSample += samples;
        currentTime += static_cast<double>(durationSec) * timeScale;
        
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
    // elapsedMs после удаления flip-блоков не используется: единственный
    // потребитель был LOGD в swapAfterSegment. Параметр остаётся в API до
    // реструктуризации шага 3.
    [[maybe_unused]] int64_t elapsedMs,
    float timeScale
) {
    GenerateResult result;
    
    if (plan.segments.empty()) {
        return result;
    }
    
    const bool constantFreq = bisectionConstantFreq();

    // ЕДИНАЯ точка истины частот ушей: раскладка (знак beat) применяется
    // ДО осцилляторов, поэтому звук, фазы паузы и телеметрия берут частоты
    // из одного места и разойтись не могут.
    auto earFreqsAt = [&](double t) -> FrequencyTableResult {
        return constantFreq
            // debug-бисекция: кривая игнорируется, раскладка сохраняется.
            ? channelsAtConstant(config, static_cast<float>(t), 203.0f, 6.0f)
            : channelsAt(config, static_cast<float>(t));
    };
    
    const float twoPiOverSampleRate = static_cast<float>(TWO_PI / m_sampleRate);
    
    int currentSample = 0;
    double currentTime = static_cast<double>(startTimeSeconds);

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
        FrequencyTableResult startFreqResult = earFreqsAt(static_cast<float>(currentTime));
        float startLeftFreq = startFreqResult.lowerFreq;
        float startRightFreq = startFreqResult.upperFreq;
        
        FrequencyTableResult endFreqResult = earFreqsAt(static_cast<float>(currentTime + static_cast<double>(durationSec) * timeScale)
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
                // STEP: режем сегмент по границам контрольных точек, Δω=0 внутри кусочка
                if (!constantFreq &&
                    config.curve.interpolationType == InterpolationType::STEP &&
                    config.curve.points.size() > 1) {
                    const double stepSecPerSample = static_cast<double>(timeScale) / m_sampleRate;
                    const std::vector<int>& stepBounds = collectStepBoundaries(
                        config.curve, currentTime, stepSecPerSample, samples);
                    if (!stepBounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= stepBounds.size(); ++k) {
                            const int pieceEnd = (k < stepBounds.size()) ? stepBounds[k] : samples;
                            const FrequencyTableResult pieceFreq = earFreqsAt(static_cast<float>(currentTime + pieceStart * stepSecPerSample)
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
                    state
                );
                break;
                
            case BufferType::FADE_OUT: break;
            case BufferType::PAUSE: break;
            case BufferType::FADE_IN: break;
        }
        
        // Логируем фазу ПОСЛЕ генерации сегмента
        LOG_SEG("SEG_END_SSE: type=%d, leftPhase=%.4f, rightPhase=%.4f",
             static_cast<int>(segment.type),
             state.leftPhase, state.rightPhase);
        
        currentSample += samples;
        currentTime += static_cast<double>(durationSec) * timeScale;
        
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
