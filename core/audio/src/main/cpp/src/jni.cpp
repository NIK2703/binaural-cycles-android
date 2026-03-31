#include <jni.h>
#include <android/log.h>
#include "BinauralEngine.h"
#include "Interpolation.h"
#include <memory>
#include <vector>
#include <algorithm>
#include <atomic>

#define LOG_TAG "NativeAudioEngine"

// Логирование только в DEBUG сборках
#ifdef AUDIO_DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::unique_ptr<binaural::BinauralEngine> g_engine;
    
    // PULL MODEL: Атомарные переменные для polling из Kotlin
    // Kotlin читает эти значения напрямую через JNI getters без callbacks
    // Это устраняет overhead JNI callbacks и context switching
    std::atomic<float> g_currentBeatFreq{0.0f};
    std::atomic<float> g_currentCarrierFreq{0.0f};
    std::atomic<int> g_elapsedSeconds{0};
    std::atomic<bool> g_channelsSwapped{false};
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad: Native library loaded (pull-model)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_engine.reset();
    LOGD("JNI_OnUnload: Native library unloaded");
}

/**
 * Инициализация движка (упрощённая - без callback объекта)
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeInitialize(
    JNIEnv* env,
    jobject thiz
) {
    LOGD("nativeInitialize (pull-model)");
    
    if (!g_engine) {
        g_engine = std::make_unique<binaural::BinauralEngine>();
    }
    
    // Callbacks больше не нужны - Kotlin polling читает данные напрямую
}

/**
 * Освобождение ресурсов
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeRelease(
    JNIEnv* env,
    jobject thiz
) {
    LOGD("nativeRelease");
    g_engine.reset();
}

/**
 * Установка конфигурации из Kotlin
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetConfig(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jfloatArray carrierFreqs,
    jfloatArray beatFreqs,
    jint interpolationType,
    jfloat splineTension,
    jfloat volume,
    jboolean channelSwapEnabled,
    jint channelSwapIntervalSec,
    jboolean channelSwapFadeEnabled,
    jlong channelSwapFadeDurationMs,
    jlong channelSwapPauseDurationMs,
    jint normalizationType,
    jfloat volumeNormalizationStrength
) {
    if (!g_engine) return;
    
    binaural::BinauralConfig config;
    
    // Получаем массивы точек
    jint numPoints = env->GetArrayLength(timePoints);
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* carriers = env->GetFloatArrayElements(carrierFreqs, nullptr);
    jfloat* beats = env->GetFloatArrayElements(beatFreqs, nullptr);
    
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
    config.channelSwapFadeEnabled = channelSwapFadeEnabled;
    config.channelSwapFadeDurationMs = channelSwapFadeDurationMs;
    config.channelSwapPauseDurationMs = channelSwapPauseDurationMs;
    config.normalizationType = static_cast<binaural::NormalizationType>(normalizationType);
    config.volumeNormalizationStrength = volumeNormalizationStrength;
    
    // Обновляем кэш
    config.curve.updateCache();
    
    g_engine->setConfig(config);
}

/**
 * Установка частоты дискретизации
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetSampleRate(
    JNIEnv* env,
    jobject thiz,
    jint sampleRate
) {
    if (g_engine) {
        g_engine->setSampleRate(sampleRate);
    }
}

/**
 * Сброс состояния
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeResetState(
    JNIEnv* env,
    jobject thiz
) {
    if (g_engine) {
        g_engine->resetState();
        // Сбрасываем атомарные переменные
        g_currentBeatFreq.store(0.0f, std::memory_order_relaxed);
        g_currentCarrierFreq.store(0.0f, std::memory_order_relaxed);
        g_elapsedSeconds.store(0, std::memory_order_relaxed);
        g_channelsSwapped.store(false, std::memory_order_relaxed);
    }
}

/**
 * Установка состояния проигрывания
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetPlaying(
    JNIEnv* env,
    jobject thiz,
    jboolean playing
) {
    if (g_engine) {
        g_engine->setPlaying(playing);
    }
}

/**
 * Установка времени начала воспроизведения
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetPlaybackStartTime(
    JNIEnv* env,
    jobject thiz,
    jlong startTimeMs
) {
    if (g_engine) {
        g_engine->setPlaybackStartTime(startTimeMs);
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
    jfloatArray buffer,
    jint samplesPerChannel
) {
    if (!g_engine) return JNI_FALSE;
    
    jfloat* bufferPtr = env->GetFloatArrayElements(buffer, nullptr);
    if (!bufferPtr) return JNI_FALSE;
    
    bool result = g_engine->generateAudioBuffer(bufferPtr, samplesPerChannel);
    
    // PULL MODEL: Обновляем атомарные переменные после генерации
    if (result) {
        g_currentBeatFreq.store(g_engine->getCurrentBeatFrequency(), std::memory_order_relaxed);
        g_currentCarrierFreq.store(g_engine->getCurrentCarrierFrequency(), std::memory_order_relaxed);
        g_elapsedSeconds.store(g_engine->getElapsedSeconds(), std::memory_order_relaxed);
        g_channelsSwapped.store(g_engine->isChannelsSwapped(), std::memory_order_relaxed);
    }
    
    env->ReleaseFloatArrayElements(buffer, bufferPtr, 0);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Zero-copy генерация буфера через DirectByteBuffer
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBufferDirect(
    JNIEnv* env,
    jobject thiz,
    jobject directBuffer,
    jint samplesPerChannel
) {
    if (!g_engine) return JNI_FALSE;
    
    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferPtr) {
        LOGE("nativeGenerateBufferDirect: Failed to get direct buffer address");
        return JNI_FALSE;
    }
    
    jlong bufferCapacity = env->GetDirectBufferCapacity(directBuffer);
    jlong requiredSize = samplesPerChannel * 2 * sizeof(float);
    if (bufferCapacity < requiredSize) {
        LOGE("nativeGenerateBufferDirect: Buffer too small. Required: %ld, Got: %ld",
             (long)requiredSize, (long)bufferCapacity);
        return JNI_FALSE;
    }
    
    bool result = g_engine->generateAudioBuffer(bufferPtr, samplesPerChannel);
    
    // PULL MODEL: Обновляем атомарные переменные после генерации
    if (result) {
        g_currentBeatFreq.store(g_engine->getCurrentBeatFrequency(), std::memory_order_relaxed);
        g_currentCarrierFreq.store(g_engine->getCurrentCarrierFrequency(), std::memory_order_relaxed);
        g_elapsedSeconds.store(g_engine->getElapsedSeconds(), std::memory_order_relaxed);
        g_channelsSwapped.store(g_engine->isChannelsSwapped(), std::memory_order_relaxed);
    }
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Получение текущей частоты биений (из атомарной переменной)
 */
JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentBeatFrequency(
    JNIEnv* env,
    jobject thiz
) {
    return g_currentBeatFreq.load(std::memory_order_relaxed);
}

/**
 * Получение частот для текущего времени из lookup table (O(1) операция)
 * Использует предвычисленную таблицу, не требует интерполяции на лету.
 * @return float[2] {beatFrequency, carrierFrequency} или null если конфиг не установлен
 */
JNIEXPORT jfloatArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetFrequenciesAtCurrentTime(
    JNIEnv* env,
    jobject thiz
) {
    if (!g_engine) return nullptr;
    
    auto result = g_engine->getFrequenciesAtCurrentTime();
    
    // Проверяем что конфиг установлен (частоты не нулевые)
    if (result.first == 0.0f && result.second == 0.0f) {
        return nullptr;
    }
    
    jfloatArray resultArray = env->NewFloatArray(2);
    if (resultArray) {
        const jfloat data[2] = { result.first, result.second };
        env->SetFloatArrayRegion(resultArray, 0, 2, data);
    }
    return resultArray;
}

/**
 * Получение текущей несущей частоты (из атомарной переменной)
 */
JNIEXPORT jfloat JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentCarrierFrequency(
    JNIEnv* env,
    jobject thiz
) {
    return g_currentCarrierFreq.load(std::memory_order_relaxed);
}

/**
 * Получение прошедшего времени (из атомарной переменной)
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetElapsedSeconds(
    JNIEnv* env,
    jobject thiz
) {
    return g_elapsedSeconds.load(std::memory_order_relaxed);
}

/**
 * Получение состояния перестановки каналов (из атомарной переменной)
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeIsChannelsSwapped(
    JNIEnv* env,
    jobject thiz
) {
    return g_channelsSwapped.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Обновление прошедшего времени
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeUpdateElapsedTime(
    JNIEnv* env,
    jobject thiz
) {
    if (g_engine) {
        g_engine->updateElapsedTime();
        g_elapsedSeconds.store(g_engine->getElapsedSeconds(), std::memory_order_relaxed);
    }
}

/**
 * Установка длительности батча для оптимизации энергопотребления
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetBatchDurationMinutes(
    JNIEnv* env,
    jobject thiz,
    jint durationMinutes
) {
    if (g_engine) {
        g_engine->setBatchDurationMinutes(durationMinutes);
        LOGD("Batch duration set to %d minutes", durationMinutes);
    }
}

/**
 * Получение длительности батча
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetBatchDurationMinutes(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getBatchDurationMinutes() : 0;
}

/**
 * Генерация батча аудио (оптимизация энергопотребления)
 * Генерирует большой буфер на заданное время за один вызов
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBatch(
    JNIEnv* env,
    jobject thiz,
    jobject directBuffer,
    jint maxSamplesPerChannel
) {
    if (!g_engine) return 0;
    
    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferPtr) {
        LOGE("nativeGenerateBatch: Failed to get direct buffer address");
        return 0;
    }
    
    int samplesGenerated = g_engine->generateBatch(bufferPtr, maxSamplesPerChannel);
    
    // Обновляем атомарные переменные
    if (samplesGenerated > 0) {
        g_currentBeatFreq.store(g_engine->getCurrentBeatFrequency(), std::memory_order_relaxed);
        g_currentCarrierFreq.store(g_engine->getCurrentCarrierFrequency(), std::memory_order_relaxed);
        g_elapsedSeconds.store(g_engine->getElapsedSeconds(), std::memory_order_relaxed);
        g_channelsSwapped.store(g_engine->isChannelsSwapped(), std::memory_order_relaxed);
    }
    
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
    jfloat tension
) {
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension
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
    jfloat tension
) {
    if (!timePoints || !values || numOutputPoints <= 0) {
        return nullptr;
    }
    
    const jint numInputPoints = env->GetArrayLength(timePoints);
    if (numInputPoints < 2) {
        return nullptr;
    }
    
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    
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
                const float ratio = static_cast<float>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);
                
                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;
                
                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension
                );
                continue;
            }
        }
        
        const float ratio = static_cast<float>(targetSeconds - t1) / (t2 - t1);
        const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);
        
        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;
        
        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension
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
    if (numPoints < 2) {
        return nullptr;
    }
    
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* carriers = env->GetFloatArrayElements(carrierFreqs, nullptr);
    jfloat* beats = env->GetFloatArrayElements(beatFreqs, nullptr);
    
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
    
    float ratio;
    bool isWrapping = (leftIndex == numPoints - 1);
    
    if (isWrapping) {
        t2 += SECONDS_PER_DAY;
        if (targetTimeSeconds < t1) {
            ratio = static_cast<float>(targetTimeSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
        } else {
            ratio = static_cast<float>(targetTimeSeconds - t1) / (t2 - t1);
        }
    } else {
        ratio = static_cast<float>(targetTimeSeconds - t1) / (t2 - t1);
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
    jfloat tension
) {
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension
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
    jfloat tension
) {
    if (!timePoints || !values || numOutputPoints <= 0) {
        return nullptr;
    }
    
    const jint numInputPoints = env->GetArrayLength(timePoints);
    if (numInputPoints < 2) {
        return nullptr;
    }
    
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    
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
                const float ratio = static_cast<float>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);
                
                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;
                
                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension
                );
                continue;
            }
        }
        
        const float ratio = static_cast<float>(targetSeconds - t1) / (t2 - t1);
        const float clampedRatio = std::clamp(ratio, 0.0f, 1.0f);
        
        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;
        
        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension
        );
    }
    
    env->SetFloatArrayRegion(result, 0, numOutputPoints, outputValues.data());
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
    
    return result;
}

} // extern "C"