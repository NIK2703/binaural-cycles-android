#include <jni.h>
#include <android/log.h>
#include "BinauralEngine.h"
#include "Interpolation.h"
#include <memory>
#include <vector>
#include <algorithm>
#include <atomic>
#include <new>

#define LOG_TAG "NativeAudioEngine"

// Логирование только в DEBUG сборках
#ifdef AUDIO_DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// PER-INSTANCE ENGINES (фикс краша "destroyed mutex" / SIGABRT).
//
// Раньше здесь жил глобальный std::unique_ptr<BinauralEngine> g_engine:
// повторный тап по пресету переиспользовал/пересоздавал его, и старый
// поток-писатель оставался внутри generateAudioBuffer с общим m_configMutex'ом,
// который разрушал чужой вызов нативного релиза.
//
// Теперь каждый Kotlin-объект NativeAudioEngine владеет СОБСТВЕННЫМ
// C++-движком через непрозрачный jlong-дескриптор:
//   nativeInitialize() -> new BinauralEngine, возвращается как handle;
//   все методы принимают handle первым аргументом;
//   нативный релиз — delete; идемпотентность (ровно одна деструкция)
//   обеспечивается атомарным getAndSet(0) на стороне Kotlin.
// ============================================================================

static inline binaural::BinauralEngine* engineFromHandle(jlong h) {
    return reinterpret_cast<binaural::BinauralEngine*>(h);
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad: Native library loaded (per-instance, pull-model)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload: Native library unloaded (per-instance ownership)");
}

/**
 * Инициализация движка (per-instance).
 * Возвращает непрозрачный jlong-хэндл, который Kotlin хранит у себя.
 */
JNIEXPORT jlong JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeInitialize(
    JNIEnv* env,
    jobject thiz
) {
    auto* engine = new (std::nothrow) binaural::BinauralEngine();
    LOGD("nativeInitialize (per-instance) engine=%p", (void*)engine);
    return reinterpret_cast<jlong>(engine);
}

/**
 * Освобождение ресурсов (потокобезопасно: идемпотентно при параллельном вызове).
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeRelease(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    if (!handle) return;
    auto* engine = engineFromHandle(handle);
    LOGD("nativeRelease engine=%p", (void*)engine);
    delete engine;
}

/**
 * Установка конфигурации из Kotlin
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetConfig(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jintArray timePoints,
    jfloatArray carrierFreqs,
    jfloatArray beatFreqs,
    jint interpolationType,
    jfloat splineTension,
    jfloat volume,
    jboolean channelSwapEnabled,
    jint channelSwapIntervalSec,
    jint channelSwapMode,
    jboolean channelSwapFadeEnabled,
    jlong channelSwapFadeDurationMs,
    jlong channelSwapPauseDurationMs,
    jint channelSwapTrendPoints,
    jint normalizationType,
    jfloat volumeNormalizationStrength
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return;

    binaural::BinauralConfig config;

    // Получаем массивы точек
    jint numPoints = env->GetArrayLength(timePoints);
    const jint numCarriers = env->GetArrayLength(carrierFreqs);
    const jint numBeats = env->GetArrayLength(beatFreqs);
    if (numCarriers != numPoints || numBeats != numPoints || numPoints > 100000) {
        LOGE("nativeSetConfig: array length mismatch (times=%d carriers=%d beats=%d)",
             (int)numPoints, (int)numCarriers, (int)numBeats);
        return;
    }
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* carriers = env->GetFloatArrayElements(carrierFreqs, nullptr);
    jfloat* beats = env->GetFloatArrayElements(beatFreqs, nullptr);
    if (!times || !carriers || !beats) {
        if (times) env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        if (carriers) env->ReleaseFloatArrayElements(carrierFreqs, carriers, JNI_ABORT);
        if (beats) env->ReleaseFloatArrayElements(beatFreqs, beats, JNI_ABORT);
        return;
    }

    config.curve.points.reserve(numPoints);
    for (int i = 0; i < numPoints; ++i) {
        config.curve.points.push_back({
            times[i],
            carriers[i],
            beats[i]
        });
    }

    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(carrierFreqs, carriers, JNI_ABORT);
    env->ReleaseFloatArrayElements(beatFreqs, beats, JNI_ABORT);

    // Устанавливаем параметры
    config.curve.interpolationType = static_cast<binaural::InterpolationType>(interpolationType);
    config.curve.splineTension = splineTension;
    config.volume = volume;
    config.channelSwapEnabled = channelSwapEnabled;
    config.channelSwapIntervalSec = channelSwapIntervalSec;
    config.channelSwapMode = (channelSwapMode == 1)
        ? binaural::ChannelSwapMode::TREND
        : binaural::ChannelSwapMode::TIMER;
    config.channelSwapFadeEnabled = channelSwapFadeEnabled;
    config.channelSwapFadeDurationMs = channelSwapFadeDurationMs;
    config.channelSwapPauseDurationMs = channelSwapPauseDurationMs;
    config.channelSwapTrendPoints = (channelSwapTrendPoints >= 0 && channelSwapTrendPoints <= 2)
        ? static_cast<binaural::ChannelSwapTrendPoints>(channelSwapTrendPoints)
        : binaural::ChannelSwapTrendPoints::BOTH;
    config.normalizationType = static_cast<binaural::NormalizationType>(normalizationType);
    config.volumeNormalizationStrength = volumeNormalizationStrength;

    // Lookup-таблица строится внутри BinauralEngine::setConfig (единственная сборка)
    engine->setConfig(config);
}

/**
 * Установка частоты дискретизации
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetSampleRate(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint sampleRate
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setSampleRate(sampleRate);
    }
}

/**
 * Сброс состояния
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeResetState(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->resetState();
    }
}

/**
 * Установка состояния проигрывания
 * preserveTimeline: true = RESUME (продолжить с того же места кривой без сброса)
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetPlaying(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jboolean playing,
    jboolean preserveTimeline
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setPlaying(playing, preserveTimeline == JNI_TRUE);
    }
}

/**
 * Установка времени начала воспроизведения
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetPlaybackStartTime(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong startTimeMs
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setPlaybackStartTime(startTimeMs);
    }
}

/**
 * Явная установка позиции кривой (секунды суток) для продолжения таймлайна
 * в свежем движке (resume/handoff).
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetCurveTime(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint timeSeconds
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setCurveTimeSeconds(static_cast<float>(timeSeconds));
    }
}

/**
 * Слышимая позиция кривой: фронтир генерации минус ещё не проигранный остаток.
 * Единственная точка, где ось AudioTrack (кадры) встречается с осью кривой
 * (секунды суток) — по ней пауза фиксирует место возобновления.
 */
JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetAudibleTimeSeconds(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jlong playedFrames,
    jlong generatedFrames
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return 0.0f;
    return static_cast<jfloat>(
        engine->computeAudibleTimeSeconds(playedFrames, generatedFrames));
}

/**
 * Заморозить UI-указатель графика на слышимой позиции (пауза).
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeFreezeUiTimelineAt(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloat seconds
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->freezeUiTimelineAt(static_cast<float>(seconds));
    }
}

/**
 * Снять заморозку UI-указателя (возобновление): продолжение с той же позиции.
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeResumeUiTimelineFrom(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloat seconds
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->resumeUiTimelineFrom(static_cast<float>(seconds));
    }
}

/**
 * Генерация буфера аудио (FloatArray версия - с копированием)
 * @deprecated Используйте nativeGenerateBufferDirect для zero-copy
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBuffer(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloatArray buffer,
    jint samplesPerChannel
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return JNI_FALSE;

    jfloat* bufferPtr = env->GetFloatArrayElements(buffer, nullptr);
    if (!bufferPtr) return JNI_FALSE;

    int generated = engine->generateAudioBuffer(bufferPtr, samplesPerChannel);

    env->ReleaseFloatArrayElements(buffer, bufferPtr, 0);

    return generated > 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Zero-copy генерация буфера через DirectByteBuffer
 * @return РЕАЛЬНО сгенерированное количество сэмплов на канал (0 = не активно).
 *         Вызывающая сторона обязана записать в AudioTrack ровно это значение.
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBufferDirect(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobject directBuffer,
    jint samplesPerChannel
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return 0;

    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferPtr) {
        LOGE("nativeGenerateBufferDirect: Failed to get direct buffer address");
        return 0;
    }

    jlong bufferCapacity = env->GetDirectBufferCapacity(directBuffer);
    jlong requiredSize = static_cast<jlong>(samplesPerChannel) * 2 * sizeof(float);
    if (bufferCapacity < requiredSize) {
        LOGE("nativeGenerateBufferDirect: Buffer too small. Required: %ld, Got: %ld",
             (long)requiredSize, (long)bufferCapacity);
        return 0;
    }

    int generated = engine->generateAudioBuffer(bufferPtr, samplesPerChannel);

    return generated;
}
/**
 * Получение текущей фазы несущих каналов (для бесшовного кроссфейда).
 * Возвращает FloatArray[2] = { leftPhase, rightPhase }.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentPhases(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    jfloatArray out = env->NewFloatArray(2);
    if (!engine) return out;
    const auto p = engine->getCurrentPhases();
    const float arr[2] = { p.first, p.second };
    env->SetFloatArrayRegion(out, 0, 2, arr);
    return out;
}

/**
 * Установка фазы несущих каналов (продолжение кроссфейда).
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetPhases(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jfloat leftPhase,
    jfloat rightPhase
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setPhases(leftPhase, rightPhase);
    }
}

/**
 * Получение текущей частоты биений (из атомарного поля движка)
 */
JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentBeatFrequency(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    return engine ? engine->getCurrentBeatFrequency() : 0.0f;
}

/**
 * Получение частот для текущего времени из lookup table (O(1) операция)
 * Использует предвычисленную таблицу, не требует интерполяции на лету.
 * @return float[2] {beatFrequency, carrierFrequency} или null если конфиг не установлен
 */
JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetFrequenciesAtCurrentTime(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return nullptr;

    // null ТОЛЬКО когда кривая не сконфигурирована; 0/0 — легитимная точка графика
    if (!engine->isCurveConfigured()) {
        return nullptr;
    }

    auto result = engine->getFrequenciesAtCurrentTime();

    jfloatArray resultArray = env->NewFloatArray(2);
    if (resultArray) {
        const jfloat data[2] = { result.first, result.second };
        env->SetFloatArrayRegion(resultArray, 0, 2, data);
    }
    return resultArray;
}

/**
 * Получение текущей несущей частоты (из атомарного поля движка)
 */
JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentCarrierFrequency(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    return engine ? engine->getCurrentCarrierFrequency() : 0.0f;
}

/**
 * Получение прошедшего времени (из атомарного поля движка)
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetElapsedSeconds(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    return engine ? engine->getElapsedSeconds() : 0;
}

/**
 * Получение состояния перестановки каналов (из движка)
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeIsChannelsSwapped(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    return (engine && engine->isChannelsSwapped()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Обновление прошедшего времени
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeUpdateElapsedTime(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->updateElapsedTime();
    }
}

/**
 * Установка длительности батча для оптимизации энергопотребления
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetBatchDurationMinutes(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jint durationMinutes
) {
    auto* engine = engineFromHandle(handle);
    if (engine) {
        engine->setBatchDurationMinutes(durationMinutes);
        LOGD("Batch duration set to %d minutes", durationMinutes);
    }
}

/**
 * Получение длительности батча
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetBatchDurationMinutes(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    auto* engine = engineFromHandle(handle);
    return engine ? engine->getBatchDurationMinutes() : 0;
}

/**
 * Генерация батча аудио (оптимизация энергопотребления)
 * Генерирует большой буфер на заданное время за один вызов
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBatch(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jobject directBuffer,
    jint maxSamplesPerChannel
) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return 0;

    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferPtr) {
        LOGE("nativeGenerateBatch: Failed to get direct buffer address");
        return 0;
    }

    int samplesGenerated = engine->generateBatch(bufferPtr, maxSamplesPerChannel);

    return samplesGenerated;
}

// ============================================================================
// JNI методы для интерполяции (используются в UI для графика)
// ============================================================================

JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeInterpolate(
    JNIEnv* env,
    jobject thiz,
    jfloat p0,
    jfloat p1,
    jfloat p2,
    jfloat p3,
    jfloat t,
    jint interpolationType,
    jfloat tension,
    jboolean allowNegative
) {
    // allowNegative=true — для знаковых величин (частота биений):
    // beat = right − left, знак кодирует раскладку каналов.
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension, (allowNegative == JNI_TRUE)
    );
}

JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateInterpolatedCurve(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jfloatArray values,
    jint numOutputPoints,
    jint interpolationType,
    jfloat tension,
    jboolean allowNegative
) {
    if (!timePoints || !values || numOutputPoints <= 0) {
        return nullptr;
    }

    const jint numInputPoints = env->GetArrayLength(timePoints);
    const jint numValues = env->GetArrayLength(values);
    if (numInputPoints < 2 || numValues != numInputPoints) {
        return nullptr;
    }

    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    if (!times || !vals) {
        if (times) env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        if (vals) env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(numOutputPoints);
    if (!result) {
        env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }

    std::vector<jfloat> outputValues(numOutputPoints);
    constexpr int SECONDS_PER_DAY = 86400;

    for (int i = 0; i < numOutputPoints; ++i) {
        const float t = static_cast<float>(i) / (numOutputPoints - 1);
        const int targetSeconds = static_cast<int>(t * SECONDS_PER_DAY);

        int leftIndex = -1;
        for (int j = 0; j < numInputPoints - 1; ++j) {
            if (times[j] <= targetSeconds && targetSeconds < times[j + 1]) {
                leftIndex = j;
                break;
            }
        }

        if (leftIndex < 0) {
            if (targetSeconds >= times[numInputPoints - 1] || targetSeconds < times[0]) {
                leftIndex = numInputPoints - 1;
            } else {
                outputValues[i] = vals[0];
                continue;
            }
        }

        const int rightIndex = (leftIndex + 1) % numInputPoints;
        int t1 = times[leftIndex];
        int t2 = times[rightIndex];

        bool isWrapping = (leftIndex == numInputPoints - 1);
        if (isWrapping) {
            t2 += SECONDS_PER_DAY;
            if (targetSeconds < t1) {
                if (t2 <= t1) { outputValues[i] = vals[leftIndex]; continue; }
                const float ratio = static_cast<float>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);

                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;

                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension,
                    (allowNegative == JNI_TRUE)
                );
                continue;
            }
        }

        if (t2 <= t1) { outputValues[i] = vals[leftIndex]; continue; }
        const float ratio = static_cast<float>(targetSeconds - t1) / (t2 - t1);
        const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);

        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;

        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension,
            (allowNegative == JNI_TRUE)
        );
    }

    env->SetFloatArrayRegion(result, 0, numOutputPoints, outputValues.data());
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetChannelFrequencies(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jfloatArray carrierFreqs,
    jfloatArray beatFreqs,
    jint targetTimeSeconds,
    jint interpolationType,
    jfloat tension
) {
    if (!timePoints || !carrierFreqs || !beatFreqs) {
        return nullptr;
    }

    const jint numPoints = env->GetArrayLength(timePoints);
    const jint numCarriers = env->GetArrayLength(carrierFreqs);
    const jint numBeats = env->GetArrayLength(beatFreqs);
    if (numPoints < 2 || numCarriers != numPoints || numBeats != numPoints) {
        return nullptr;
    }

    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* carriers = env->GetFloatArrayElements(carrierFreqs, nullptr);
    jfloat* beats = env->GetFloatArrayElements(beatFreqs, nullptr);
    if (!times || !carriers || !beats) {
        if (times) env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        if (carriers) env->ReleaseFloatArrayElements(carrierFreqs, carriers, JNI_ABORT);
        if (beats) env->ReleaseFloatArrayElements(beatFreqs, beats, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> lowerFreqs(numPoints);
    std::vector<float> upperFreqs(numPoints);

    for (int i = 0; i < numPoints; ++i) {
        lowerFreqs[i] = static_cast<float>(carriers[i] - beats[i] / 2.0);
        upperFreqs[i] = static_cast<float>(carriers[i] + beats[i] / 2.0);
    }

    constexpr int SECONDS_PER_DAY = 86400;
    int leftIndex = -1;

    for (int j = 0; j < numPoints - 1; ++j) {
        if (times[j] <= targetTimeSeconds && targetTimeSeconds < times[j + 1]) {
            leftIndex = j;
            break;
        }
    }

    if (leftIndex < 0) {
        if (targetTimeSeconds >= times[numPoints - 1] || targetTimeSeconds < times[0]) {
            leftIndex = numPoints - 1;
        } else {
            leftIndex = 0;
        }
    }

    const int rightIndex = (leftIndex + 1) % numPoints;
    int t1 = times[leftIndex];
    int t2 = times[rightIndex];

    float ratio = 0.0f;
    bool isWrapping = (leftIndex == numPoints - 1);

    if (isWrapping) {
        t2 += SECONDS_PER_DAY;
    }
    if (t2 > t1) {
        if (isWrapping && targetTimeSeconds < t1) {
            ratio = static_cast<float>(targetTimeSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
        } else {
            ratio = static_cast<float>(targetTimeSeconds - t1) / (t2 - t1);
        }
    }

    ratio = std::clamp(ratio, 0.0f, 1.0f);

    const int prevIndex = (leftIndex - 1 + numPoints) % numPoints;
    const int nextNextIndex = (rightIndex + 1) % numPoints;

    float lowerFreq = binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        lowerFreqs[prevIndex], lowerFreqs[leftIndex], lowerFreqs[rightIndex], lowerFreqs[nextNextIndex],
        ratio, tension
    );

    float upperFreq = binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        upperFreqs[prevIndex], upperFreqs[leftIndex], upperFreqs[rightIndex], upperFreqs[nextNextIndex],
        ratio, tension
    );

    lowerFreq = std::max(0.0f, lowerFreq);
    upperFreq = std::max(0.0f, upperFreq);

    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(carrierFreqs, carriers, JNI_ABORT);
    env->ReleaseFloatArrayElements(beatFreqs, beats, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(2);
    if (result) {
        const jfloat resultData[2] = { lowerFreq, upperFreq };
        env->SetFloatArrayRegion(result, 0, 2, resultData);
    }

    return result;
}

// ============================================================================
// Статические JNI методы для NativeInterpolation (используются в UI)
// ============================================================================

JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeInterpolation_nativeInterpolate(
    JNIEnv* env,
    jobject thiz,
    jfloat p0,
    jfloat p1,
    jfloat p2,
    jfloat p3,
    jfloat t,
    jint interpolationType,
    jfloat tension,
    jboolean allowNegative
) {
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension, (allowNegative == JNI_TRUE)
    );
}

JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeInterpolation_nativeGenerateInterpolatedCurve(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jfloatArray values,
    jint numOutputPoints,
    jint interpolationType,
    jfloat tension,
    jboolean allowNegative
) {
    if (!timePoints || !values || numOutputPoints <= 0) {
        return nullptr;
    }

    const jint numInputPoints = env->GetArrayLength(timePoints);
    const jint numValues = env->GetArrayLength(values);
    if (numInputPoints < 2 || numValues != numInputPoints) {
        return nullptr;
    }

    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    if (!times || !vals) {
        if (times) env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        if (vals) env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(numOutputPoints);
    if (!result) {
        env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }

    std::vector<jfloat> outputValues(numOutputPoints);
    constexpr int SECONDS_PER_DAY = 86400;

    for (int i = 0; i < numOutputPoints; ++i) {
        const float t = static_cast<float>(i) / (numOutputPoints - 1);
        const int targetSeconds = static_cast<int>(t * SECONDS_PER_DAY);

        int leftIndex = -1;
        for (int j = 0; j < numInputPoints - 1; ++j) {
            if (times[j] <= targetSeconds && targetSeconds < times[j + 1]) {
                leftIndex = j;
                break;
            }
        }

        if (leftIndex < 0) {
            if (targetSeconds >= times[numInputPoints - 1] || targetSeconds < times[0]) {
                leftIndex = numInputPoints - 1;
            } else {
                outputValues[i] = vals[0];
                continue;
            }
        }

        const int rightIndex = (leftIndex + 1) % numInputPoints;
        int t1 = times[leftIndex];
        int t2 = times[rightIndex];

        bool isWrapping = (leftIndex == numInputPoints - 1);
        if (isWrapping) {
            t2 += SECONDS_PER_DAY;
            if (targetSeconds < t1) {
                if (t2 <= t1) { outputValues[i] = vals[leftIndex]; continue; }
                const float ratio = static_cast<float>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);

                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;

                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension,
                    (allowNegative == JNI_TRUE)
                );
                continue;
            }
        }

        if (t2 <= t1) { outputValues[i] = vals[leftIndex]; continue; }
        const float ratio = static_cast<float>(targetSeconds - t1) / (t2 - t1);
        const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);

        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;

        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension,
            (allowNegative == JNI_TRUE)
        );
    }

    env->SetFloatArrayRegion(result, 0, numOutputPoints, outputValues.data());
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);

    return result;
}

// ============================================================================
// Debug virtual time (compile-time gated). В release — no-op заглушки.
// ============================================================================

JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugSetVirtualTimeEnabled(
    JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (engine) engine->setVirtualTimeEnabled(enabled == JNI_TRUE);
#else
    (void)handle; (void)enabled;
#endif
}

JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugScrub(
    JNIEnv* env, jobject thiz, jlong handle, jint timeSeconds) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (engine) engine->scrubVirtualTime(static_cast<float>(timeSeconds));
#else
    (void)handle; (void)timeSeconds;
#endif
}

JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugSetTimeScale(
    JNIEnv* env, jobject thiz, jlong handle, jfloat scale) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (engine) engine->setVirtualTimeScale(scale);
#else
    (void)handle; (void)scale;
#endif
}

JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugSetRunning(
    JNIEnv* env, jobject thiz, jlong handle, jboolean running) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (engine) engine->setVirtualTimeRunning(running == JNI_TRUE);
#else
    (void)handle; (void)running;
#endif
}

JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugReset(
    JNIEnv* env, jobject thiz, jlong handle) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (engine) engine->resetVirtualTimeToReal();
#else
    (void)handle;
#endif
}

JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugGetVirtualTime(
    JNIEnv* env, jobject thiz, jlong handle) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (!engine) return 0;
    return static_cast<jint>(engine->getVirtualTimeOfDaySeconds());
#else
    (void)handle;
    return 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugIsEnabled(
    JNIEnv* env, jobject thiz, jlong handle) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    return (engine && engine->isVirtualTimeEnabled()) ? JNI_TRUE : JNI_FALSE;
#else
    (void)handle;
    return JNI_FALSE;
#endif
}

JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeDebugGetTimeScale(
    JNIEnv* env, jobject thiz, jlong handle) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    auto* engine = engineFromHandle(handle);
    if (!engine) return 1.0f;
    return engine->getVirtualTimeScale();
#else
    (void)handle;
    return 1.0f;
#endif
}

// Всегда доступно: текущее время суток (учитывает virtual-режим).
// В release это просто реальные часы.
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentTimeOfDay(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = engineFromHandle(handle);
    if (!engine) return 0;
    return static_cast<jint>(engine->getCurrentTimeOfDaySeconds());
}

} // extern "C"
