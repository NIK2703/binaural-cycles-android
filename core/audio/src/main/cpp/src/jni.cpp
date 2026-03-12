#include <jni.h>
#include <android/log.h>
#include "BinauralEngine.h"
#include <memory>

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
 * Генерация буфера аудио
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
    
    // Коммитим изменения в Java массив
    env->ReleaseFloatArrayElements(buffer, bufferPtr, 0);
    
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

} // extern "C"