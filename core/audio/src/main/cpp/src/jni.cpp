#include <jni.h>
#include <android/log.h>
#include "BinauralEngine.h"
#include "Interpolation.h"
#include <memory>
#include <vector>
#include <algorithm>

#define LOG_TAG "NativeAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::unique_ptr<binaural::BinauralEngine> g_engine;
    JavaVM* g_javaVM = nullptr;
    jobject g_callbackObj = nullptr;
    jmethodID g_onFrequencyChangedMethod = nullptr;
    jmethodID g_onChannelsSwappedMethod = nullptr;
    jmethodID g_onElapsedChangedMethod = nullptr;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_javaVM = vm;
    LOGD("JNI_OnLoad: Native library loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    g_engine.reset();
    if (g_callbackObj) {
        JNIEnv* env;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(g_callbackObj);
        }
        g_callbackObj = nullptr;
    }
    LOGD("JNI_OnUnload: Native library unloaded");
}

/**
 * Инициализация движка
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeInitialize(
    JNIEnv* env,
    jobject thiz,
    jobject callback
) {
    LOGD("nativeInitialize");
    
    if (!g_engine) {
        g_engine = std::make_unique<binaural::BinauralEngine>();
    }
    
    // Сохраняем callback объект
    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
    }
    g_callbackObj = env->NewGlobalRef(callback);
    
    // Получаем method ID для callback'ов
    jclass callbackClass = env->GetObjectClass(callback);
    g_onFrequencyChangedMethod = env->GetMethodID(callbackClass, "onFrequencyChanged", "(DD)V");
    g_onChannelsSwappedMethod = env->GetMethodID(callbackClass, "onChannelsSwapped", "(Z)V");
    g_onElapsedChangedMethod = env->GetMethodID(callbackClass, "onElapsedChanged", "(I)V");
    
    // Устанавливаем callbacks
    binaural::EngineCallbacks callbacks;
    callbacks.onFrequencyChanged = [](double beatFreq, double carrierFreq) {
        if (g_javaVM && g_callbackObj && g_onFrequencyChangedMethod) {
            JNIEnv* env = nullptr;
            bool needsDetach = false;
            
            JavaVMAttachArgs args = {JNI_VERSION_1_6, "NativeCallback", nullptr};
            jint result = g_javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            
            if (result == JNI_EDETACHED) {
                g_javaVM->AttachCurrentThread(&env, &args);
                needsDetach = true;
            }
            
            if (env) {
                env->CallVoidMethod(g_callbackObj, g_onFrequencyChangedMethod, 
                                   static_cast<jdouble>(beatFreq), 
                                   static_cast<jdouble>(carrierFreq));
            }
            
            if (needsDetach) {
                g_javaVM->DetachCurrentThread();
            }
        }
    };
    
    callbacks.onChannelsSwapped = [](bool channelsSwapped) {
        if (g_javaVM && g_callbackObj && g_onChannelsSwappedMethod) {
            JNIEnv* env = nullptr;
            bool needsDetach = false;
            
            JavaVMAttachArgs args = {JNI_VERSION_1_6, "NativeCallback", nullptr};
            jint result = g_javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            
            if (result == JNI_EDETACHED) {
                g_javaVM->AttachCurrentThread(&env, &args);
                needsDetach = true;
            }
            
            if (env) {
                env->CallVoidMethod(g_callbackObj, g_onChannelsSwappedMethod, channelsSwapped);
            }
            
            if (needsDetach) {
                g_javaVM->DetachCurrentThread();
            }
        }
    };
    
    callbacks.onElapsedChanged = [](int elapsedSeconds) {
        if (g_javaVM && g_callbackObj && g_onElapsedChangedMethod) {
            JNIEnv* env = nullptr;
            bool needsDetach = false;
            
            JavaVMAttachArgs args = {JNI_VERSION_1_6, "NativeCallback", nullptr};
            jint result = g_javaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            
            if (result == JNI_EDETACHED) {
                g_javaVM->AttachCurrentThread(&env, &args);
                needsDetach = true;
            }
            
            if (env) {
                env->CallVoidMethod(g_callbackObj, g_onElapsedChangedMethod, elapsedSeconds);
            }
            
            if (needsDetach) {
                g_javaVM->DetachCurrentThread();
            }
        }
    };
    
    g_engine->setCallbacks(std::move(callbacks));
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
    
    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
        g_callbackObj = nullptr;
    }
}

/**
 * Установка конфигурации из Kotlin
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetConfig(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jdoubleArray carrierFreqs,
    jdoubleArray beatFreqs,
    jint interpolationType,
    jfloat splineTension,
    jfloat volume,
    jboolean channelSwapEnabled,
    jint channelSwapIntervalSec,
    jboolean channelSwapFadeEnabled,
    jlong channelSwapFadeDurationMs,
    jint normalizationType,
    jfloat volumeNormalizationStrength
) {
    if (!g_engine) return;
    
    binaural::BinauralConfig config;
    
    // Получаем массивы точек
    jint numPoints = env->GetArrayLength(timePoints);
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jdouble* carriers = env->GetDoubleArrayElements(carrierFreqs, nullptr);
    jdouble* beats = env->GetDoubleArrayElements(beatFreqs, nullptr);
    
    config.curve.points.reserve(numPoints);
    for (int i = 0; i < numPoints; ++i) {
        config.curve.points.push_back({
            times[i],
            carriers[i],
            beats[i]
        });
    }
    
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseDoubleArrayElements(carrierFreqs, carriers, JNI_ABORT);
    env->ReleaseDoubleArrayElements(beatFreqs, beats, JNI_ABORT);
    
    // Устанавливаем параметры
    config.curve.interpolationType = static_cast<binaural::InterpolationType>(interpolationType);
    config.curve.splineTension = splineTension;
    config.volume = volume;
    config.channelSwapEnabled = channelSwapEnabled;
    config.channelSwapIntervalSec = channelSwapIntervalSec;
    config.channelSwapFadeEnabled = channelSwapFadeEnabled;
    config.channelSwapFadeDurationMs = channelSwapFadeDurationMs;
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
 * Установка интервала обновления частот
 * Этот параметр определяет размер порции генерации буфера
 * Больший интервал = меньше прерываний = лучше энергоэффективность
 */
JNIEXPORT void JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeSetFrequencyUpdateInterval(
    JNIEnv* env,
    jobject thiz,
    jint intervalMs
) {
    if (g_engine) {
        g_engine->setFrequencyUpdateInterval(intervalMs);
        LOGD("Frequency update interval set to %d ms", intervalMs);
    }
}

/**
 * Получение интервала обновления частот
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetFrequencyUpdateInterval(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getFrequencyUpdateInterval() : 10000;
}

/**
 * Получение рекомендуемого размера буфера в сэмплах на канал
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetRecommendedBufferSize(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getRecommendedBufferSize() : 0;
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
 * @param frequencyUpdateIntervalMs интервал обновления частот в мс (для интерполяции)
 * @deprecated Используйте nativeGenerateBufferDirect для zero-copy
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBuffer(
    JNIEnv* env,
    jobject thiz,
    jfloatArray buffer,
    jint samplesPerChannel,
    jint frequencyUpdateIntervalMs
) {
    if (!g_engine) return JNI_FALSE;
    
    jfloat* bufferPtr = env->GetFloatArrayElements(buffer, nullptr);
    if (!bufferPtr) return JNI_FALSE;
    
    // Передаём интервал обновления частот для точной интерполяции
    bool result = g_engine->generateAudioBuffer(bufferPtr, samplesPerChannel, frequencyUpdateIntervalMs);
    
    // Коммитим изменения в Java массив
    env->ReleaseFloatArrayElements(buffer, bufferPtr, 0);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Zero-copy генерация буфера через DirectByteBuffer
 * Избегает копирования данных между Java и C++
 * 
 * @param directBuffer прямой буфер из Java (ByteBuffer.allocateDirect())
 * @param samplesPerChannel количество сэмплов на канал
 * @param frequencyUpdateIntervalMs интервал обновления частот в мс
 * @return true если генерация успешна
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateBufferDirect(
    JNIEnv* env,
    jobject thiz,
    jobject directBuffer,
    jint samplesPerChannel,
    jint frequencyUpdateIntervalMs
) {
    if (!g_engine) return JNI_FALSE;
    
    // Получаем прямой указатель на буфер без копирования
    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!bufferPtr) {
        LOGE("nativeGenerateBufferDirect: Failed to get direct buffer address");
        return JNI_FALSE;
    }
    
    // Проверяем размер буфера
    jlong bufferCapacity = env->GetDirectBufferCapacity(directBuffer);
    jlong requiredSize = samplesPerChannel * 2 * sizeof(float);
    if (bufferCapacity < requiredSize) {
        LOGE("nativeGenerateBufferDirect: Buffer too small. Required: %ld, Got: %ld", 
             (long)requiredSize, (long)bufferCapacity);
        return JNI_FALSE;
    }
    
    // Генерируем напрямую в буфер без копирования
    bool result = g_engine->generateAudioBuffer(bufferPtr, samplesPerChannel, frequencyUpdateIntervalMs);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Получение текущей частоты биений
 */
JNIEXPORT jdouble JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentBeatFrequency(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getCurrentBeatFrequency() : 0.0;
}

/**
 * Получение текущей несущей частоты
 */
JNIEXPORT jdouble JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetCurrentCarrierFrequency(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getCurrentCarrierFrequency() : 0.0;
}

/**
 * Получение прошедшего времени
 */
JNIEXPORT jint JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetElapsedSeconds(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? g_engine->getElapsedSeconds() : 0;
}

/**
 * Получение состояния перестановки каналов
 */
JNIEXPORT jboolean JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeIsChannelsSwapped(
    JNIEnv* env,
    jobject thiz
) {
    return g_engine ? (g_engine->isChannelsSwapped() ? JNI_TRUE : JNI_FALSE) : JNI_FALSE;
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
    }
}

// ============================================================================
// JNI методы для интерполяции (используются в UI для графика)
// ============================================================================

/**
 * Интерполяция одного значения
 * @param p0 точка до левой границы
 * @param p1 левая граница интервала
 * @param p2 правая граница интервала
 * @param p3 точка после правой границы
 * @param t нормализованная позиция [0, 1]
 * @param interpolationType тип интерполяции (0=LINEAR, 1=CARDINAL, 2=MONOTONE, 3=STEP)
 * @param tension параметр натяжения для CARDINAL
 * @return интерполированное значение
 */
JNIEXPORT jdouble JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeInterpolate(
    JNIEnv* env,
    jobject thiz,
    jdouble p0,
    jdouble p1,
    jdouble p2,
    jdouble p3,
    jdouble t,
    jint interpolationType,
    jfloat tension
) {
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension
    );
}

/**
 * Генерация массива интерполированных значений для графика
 * @param timePoints массив временных точек (секунды с начала суток)
 * @param values массив значений в этих точках
 * @param numOutputPoints количество выходных точек
 * @param interpolationType тип интерполяции
 * @param tension параметр натяжения
 * @return массив интерполированных значений
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGenerateInterpolatedCurve(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jdoubleArray values,
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
    
    // Получаем входные данные
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jdouble* vals = env->GetDoubleArrayElements(values, nullptr);
    
    // Создаём выходной массив
    jdoubleArray result = env->NewDoubleArray(numOutputPoints);
    if (!result) {
        env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        env->ReleaseDoubleArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }
    
    std::vector<double> outputValues(numOutputPoints);
    
    // Константы для времени суток
    constexpr int SECONDS_PER_DAY = 86400;
    
    // Генерируем интерполированные значения
    for (int i = 0; i < numOutputPoints; ++i) {
        // Равномерно распределяем точки по суткам
        const double t = static_cast<double>(i) / (numOutputPoints - 1);
        const int targetSeconds = static_cast<int>(t * SECONDS_PER_DAY);
        
        // Находим интервал (бинарный поиск)
        int leftIndex = -1;
        for (int j = 0; j < numInputPoints - 1; ++j) {
            if (times[j] <= targetSeconds && targetSeconds < times[j + 1]) {
                leftIndex = j;
                break;
            }
        }
        
        // Если не нашли - это переход через полночь или выход за границы
        if (leftIndex < 0) {
            if (targetSeconds >= times[numInputPoints - 1] || targetSeconds < times[0]) {
                // Переход через полночь: между последней и первой точкой
                leftIndex = numInputPoints - 1;
            } else {
                // Fallback
                outputValues[i] = vals[0];
                continue;
            }
        }
        
        const int rightIndex = (leftIndex + 1) % numInputPoints;
        
        // Вычисляем нормализованную позицию t в интервале
        int t1 = times[leftIndex];
        int t2 = times[rightIndex];
        
        // Обработка перехода через полночь
        bool isWrapping = (leftIndex == numInputPoints - 1);
        if (isWrapping) {
            t2 += SECONDS_PER_DAY;
            if (targetSeconds < t1) {
                // targetSeconds после полуночи
                const double ratio = static_cast<double>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const double clampedRatio = std::clamp(ratio, 0.0, 1.0);
                
                // Получаем 4 точки для сплайна
                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextIndex = rightIndex;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;
                
                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension
                );
                continue;
            }
        }
        
        const double ratio = static_cast<double>(targetSeconds - t1) / (t2 - t1);
        const double clampedRatio = std::clamp(ratio, 0.0, 1.0);
        
        // Получаем 4 точки для сплайна
        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;
        
        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension
        );
    }
    
    // Копируем результат
    env->SetDoubleArrayRegion(result, 0, numOutputPoints, outputValues.data());
    
    // Освобождаем ресурсы
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseDoubleArrayElements(values, vals, JNI_ABORT);
    
    return result;
}

/**
 * Получение частот каналов для заданного времени (для UI)
 * @param timePoints массив временных точек (секунды с начала суток)
 * @param carrierFreqs массив несущих частот
 * @param beatFreqs массив частот биений
 * @param targetTimeSeconds целевое время в секундах с начала суток
 * @param interpolationType тип интерполяции
 * @param tension параметр натяжения
 * @return double[2]: [0] = нижняя частота канала, [1] = верхняя частота канала
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_binaural_core_audio_engine_NativeAudioEngine_nativeGetChannelFrequencies(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jdoubleArray carrierFreqs,
    jdoubleArray beatFreqs,
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
    
    // Получаем входные данные
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jdouble* carriers = env->GetDoubleArrayElements(carrierFreqs, nullptr);
    jdouble* beats = env->GetDoubleArrayElements(beatFreqs, nullptr);
    
    // Вычисляем частоты каналов для каждой точки
    std::vector<double> lowerFreqs(numPoints);
    std::vector<double> upperFreqs(numPoints);
    
    for (int i = 0; i < numPoints; ++i) {
        lowerFreqs[i] = carriers[i] - beats[i] / 2.0;
        upperFreqs[i] = carriers[i] + beats[i] / 2.0;
    }
    
    // Находим интервал
    constexpr int SECONDS_PER_DAY = 86400;
    int leftIndex = -1;
    
    for (int j = 0; j < numPoints - 1; ++j) {
        if (times[j] <= targetTimeSeconds && targetTimeSeconds < times[j + 1]) {
            leftIndex = j;
            break;
        }
    }
    
    // Если не нашли - переход через полночь
    if (leftIndex < 0) {
        if (targetTimeSeconds >= times[numPoints - 1] || targetTimeSeconds < times[0]) {
            leftIndex = numPoints - 1;
        } else {
            leftIndex = 0;
        }
    }
    
    const int rightIndex = (leftIndex + 1) % numPoints;
    
    // Вычисляем нормализованную позицию
    int t1 = times[leftIndex];
    int t2 = times[rightIndex];
    
    double ratio;
    bool isWrapping = (leftIndex == numPoints - 1);
    
    if (isWrapping) {
        t2 += SECONDS_PER_DAY;
        if (targetTimeSeconds < t1) {
            ratio = static_cast<double>(targetTimeSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
        } else {
            ratio = static_cast<double>(targetTimeSeconds - t1) / (t2 - t1);
        }
    } else {
        ratio = static_cast<double>(targetTimeSeconds - t1) / (t2 - t1);
    }
    
    ratio = std::clamp(ratio, 0.0, 1.0);
    
    // Получаем 4 точки для сплайна
    const int prevIndex = (leftIndex - 1 + numPoints) % numPoints;
    const int nextNextIndex = (rightIndex + 1) % numPoints;
    
    double lowerFreq = binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        lowerFreqs[prevIndex], lowerFreqs[leftIndex], lowerFreqs[rightIndex], lowerFreqs[nextNextIndex],
        ratio, tension
    );
    
    double upperFreq = binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        upperFreqs[prevIndex], upperFreqs[leftIndex], upperFreqs[rightIndex], upperFreqs[nextNextIndex],
        ratio, tension
    );
    
    // Гарантируем неотрицательные частоты
    lowerFreq = std::max(0.0, lowerFreq);
    upperFreq = std::max(0.0, upperFreq);
    
    // Освобождаем ресурсы
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseDoubleArrayElements(carrierFreqs, carriers, JNI_ABORT);
    env->ReleaseDoubleArrayElements(beatFreqs, beats, JNI_ABORT);
    
    // Возвращаем результат
    jdoubleArray result = env->NewDoubleArray(2);
    if (result) {
        const double resultData[2] = { lowerFreq, upperFreq };
        env->SetDoubleArrayRegion(result, 0, 2, resultData);
    }
    
    return result;
}

// ============================================================================
// Статические JNI методы для NativeInterpolation (используются в UI)
// ============================================================================

/**
 * Статическая интерполяция одного значения (не требует экземпляра движка)
 */
JNIEXPORT jdouble JNICALL
Java_com_binaural_core_audio_engine_NativeInterpolation_nativeInterpolate(
    JNIEnv* env,
    jobject thiz,
    jdouble p0,
    jdouble p1,
    jdouble p2,
    jdouble p3,
    jdouble t,
    jint interpolationType,
    jfloat tension
) {
    return binaural::Interpolation::interpolate(
        static_cast<binaural::InterpolationType>(interpolationType),
        p0, p1, p2, p3, t, tension
    );
}

/**
 * Статическая генерация интерполированной кривой (не требует экземпляра движка)
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_binaural_core_audio_engine_NativeInterpolation_nativeGenerateInterpolatedCurve(
    JNIEnv* env,
    jobject thiz,
    jintArray timePoints,
    jdoubleArray values,
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
    
    // Получаем входные данные
    jint* times = env->GetIntArrayElements(timePoints, nullptr);
    jdouble* vals = env->GetDoubleArrayElements(values, nullptr);
    
    // Создаём выходной массив
    jdoubleArray result = env->NewDoubleArray(numOutputPoints);
    if (!result) {
        env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
        env->ReleaseDoubleArrayElements(values, vals, JNI_ABORT);
        return nullptr;
    }
    
    std::vector<double> outputValues(numOutputPoints);
    
    // Константы для времени суток
    constexpr int SECONDS_PER_DAY = 86400;
    
    // Генерируем интерполированные значения
    for (int i = 0; i < numOutputPoints; ++i) {
        // Равномерно распределяем точки по суткам
        const double t = static_cast<double>(i) / (numOutputPoints - 1);
        const int targetSeconds = static_cast<int>(t * SECONDS_PER_DAY);
        
        // Находим интервал (бинарный поиск)
        int leftIndex = -1;
        for (int j = 0; j < numInputPoints - 1; ++j) {
            if (times[j] <= targetSeconds && targetSeconds < times[j + 1]) {
                leftIndex = j;
                break;
            }
        }
        
        // Если не нашли - это переход через полночь или выход за границы
        if (leftIndex < 0) {
            if (targetSeconds >= times[numInputPoints - 1] || targetSeconds < times[0]) {
                // Переход через полночь: между последней и первой точкой
                leftIndex = numInputPoints - 1;
            } else {
                // Fallback
                outputValues[i] = vals[0];
                continue;
            }
        }
        
        const int rightIndex = (leftIndex + 1) % numInputPoints;
        
        // Вычисляем нормализованную позицию t в интервале
        int t1 = times[leftIndex];
        int t2 = times[rightIndex];
        
        // Обработка перехода через полночь
        bool isWrapping = (leftIndex == numInputPoints - 1);
        if (isWrapping) {
            t2 += SECONDS_PER_DAY;
            if (targetSeconds < t1) {
                // targetSeconds после полуночи
                const double ratio = static_cast<double>(targetSeconds + SECONDS_PER_DAY - t1) / (t2 - t1);
                const double clampedRatio = std::clamp(ratio, 0.0, 1.0);
                
                // Получаем 4 точки для сплайна
                const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
                const int nextIndex = rightIndex;
                const int nextNextIndex = (rightIndex + 1) % numInputPoints;
                
                outputValues[i] = binaural::Interpolation::interpolate(
                    static_cast<binaural::InterpolationType>(interpolationType),
                    vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
                    clampedRatio, tension
                );
                continue;
            }
        }
        
        const double ratio = static_cast<double>(targetSeconds - t1) / (t2 - t1);
        const double clampedRatio = std::clamp(ratio, 0.0, 1.0);
        
        // Получаем 4 точки для сплайна
        const int prevIndex = (leftIndex - 1 + numInputPoints) % numInputPoints;
        const int nextNextIndex = (rightIndex + 1) % numInputPoints;
        
        outputValues[i] = binaural::Interpolation::interpolate(
            static_cast<binaural::InterpolationType>(interpolationType),
            vals[prevIndex], vals[leftIndex], vals[rightIndex], vals[nextNextIndex],
            clampedRatio, tension
        );
    }
    
    // Копируем результат
    env->SetDoubleArrayRegion(result, 0, numOutputPoints, outputValues.data());
    
    // Освобождаем ресурсы
    env->ReleaseIntArrayElements(timePoints, times, JNI_ABORT);
    env->ReleaseDoubleArrayElements(values, vals, JNI_ABORT);
    
    return result;
}

} // extern "C"
