#include "BinauralEngine.h"
#include <chrono>
#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <shared_mutex>

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#ifdef USE_SSE
#include <immintrin.h>
#endif

#define LOG_TAG "BinauralEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace binaural {

BinauralEngine::BinauralEngine() {
    // Инициализация конфигурации по умолчанию
    m_config.curve.updateCache();
    // Интервал обновления частот по умолчанию - 10 секунд
    m_frequencyUpdateIntervalMs = 10000;
    
#ifdef USE_NEON
    LOGD("BinauralEngine initialized with NEON SIMD + FMA optimization");
#elif defined(USE_SSE)
    LOGD("BinauralEngine initialized with SSE SIMD optimization");
#else
    LOGD("BinauralEngine initialized (scalar mode)");
#endif
}

BinauralEngine::~BinauralEngine() = default;

void BinauralEngine::setCallbacks(EngineCallbacks callbacks) {
    m_callbacks = std::move(callbacks);
}

void BinauralEngine::setConfig(const BinauralConfig& config) {
    // Быстрый путь: обновляем конфигурацию с минимальной блокировкой
    // Копируем и строим lookup table вне мьютекса
    BinauralConfig newConfig = config;
    newConfig.curve.buildLookupTable(m_frequencyUpdateIntervalMs);
    
    // Эксклюзивная блокировка для записи
    std::unique_lock<std::shared_mutex> lock(m_configMutex);
    m_config = std::move(newConfig);
}

void BinauralEngine::setSampleRate(int sampleRate) {
    m_generator.setSampleRate(sampleRate);
}

void BinauralEngine::setFrequencyUpdateInterval(int intervalMs) {
    m_frequencyUpdateIntervalMs = intervalMs;
    
    // Перестраиваем lookup table с новым интервалом
    // Эксклюзивная блокировка для записи
    std::unique_lock<std::shared_mutex> lock(m_configMutex);
    m_config.curve.buildLookupTable(intervalMs);
    
    LOGD("Frequency update interval set to %d ms", intervalMs);
}

void BinauralEngine::setPlaying(bool playing) {
    m_isPlaying.store(playing, std::memory_order_release);
    
    if (playing) {
        // Сброс состояния при начале воспроизведения
        m_state.lastSwapElapsedMs = 0;
        m_state.channelsSwapped = false;
        m_elapsedSeconds.store(0, std::memory_order_relaxed);
        m_baseTimeSeconds = getCurrentTimeSeconds();
        m_totalBufferTimeSeconds = 0.0;
        
        LOGD("setPlaying(true): baseTime=%d", m_baseTimeSeconds);
    }
}

void BinauralEngine::resetState() {
    m_generator.resetState(m_state);
    m_elapsedSeconds.store(0, std::memory_order_relaxed);
    m_currentBeatFreq.store(0.0f, std::memory_order_relaxed);
    m_currentCarrierFreq.store(0.0f, std::memory_order_relaxed);
}

int BinauralEngine::getRecommendedBufferSize() const {
    int sampleRate = m_generator.getSampleRate();
    return (sampleRate * m_frequencyUpdateIntervalMs) / 1000;
}

int32_t BinauralEngine::getCurrentTimeSeconds() const {
    // Thread-safe получение текущего времени суток
    auto now = std::chrono::system_clock::now();
    
#ifdef __ANDROID__
    // На Android используем localtime_r (thread-safe версия)
    time_t time = std::chrono::system_clock::to_time_t(now);
    struct tm tm_info;
    localtime_r(&time, &tm_info);
    return tm_info.tm_hour * 3600 + tm_info.tm_min * 60 + tm_info.tm_sec;
#else
    // Fallback: UTC
    auto duration = now.time_since_epoch();
    auto totalSeconds = std::chrono::duration_cast<std::chrono::seconds>(duration).count();
    constexpr int32_t SECONDS_PER_DAY = 86400;
    return static_cast<int32_t>(totalSeconds % SECONDS_PER_DAY);
#endif
}

void BinauralEngine::updateElapsedTime() {
    const int64_t startTime = m_playbackStartTimeMs.load(std::memory_order_relaxed);
    if (startTime > 0) {
        auto now = std::chrono::system_clock::now();
        auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()
        ).count();
        int elapsed = static_cast<int>((nowMs - startTime) / 1000);
        m_elapsedSeconds.store(elapsed, std::memory_order_relaxed);
        
        if (m_callbacks.onElapsedChanged) {
            m_callbacks.onElapsedChanged(elapsed);
        }
    }
}

bool BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel, int frequencyUpdateIntervalMs) {
    // Быстрая проверка без блокировки
    if (!m_isPlaying.load(std::memory_order_acquire)) {
        return false;
    }
    
    // Вычисляем точное время для интерполяции
    const int sampleRate = m_generator.getSampleRate();
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / sampleRate;
    
    // Точное время для начала буфера
    int32_t timeSeconds = static_cast<int32_t>(m_baseTimeSeconds + m_totalBufferTimeSeconds);
    
    // Нормализация в пределах суток (branchless)
    constexpr int32_t SECONDS_PER_DAY = 86400;
    timeSeconds = ((timeSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    // Накапливаем время для следующего буфера
    m_totalBufferTimeSeconds += bufferDurationSeconds;
    
    const int64_t elapsedMs = static_cast<int64_t>(m_elapsedSeconds.load(std::memory_order_relaxed)) * 1000;
    
    // Обновляем прошедшее время асинхронно
    updateElapsedTime();
    
    // ОПТИМИЗАЦИЯ: Используем shared_lock для чтения (множественное чтение)
    // Это позволяет нескольким потокам читать конфигурацию одновременно
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // Используем SIMD-оптимизированную версию если доступна
#if defined(USE_NEON)
    GenerateResult result = m_generator.generateBufferNeon(
        buffer,
        samplesPerChannel,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        frequencyUpdateIntervalMs
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generateBufferSse(
        buffer,
        samplesPerChannel,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        frequencyUpdateIntervalMs
    );
#else
    GenerateResult result = m_generator.generateBuffer(
        buffer,
        samplesPerChannel,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        frequencyUpdateIntervalMs
    );
#endif
    
    // Обновляем атомарные значения для Java (relaxed для производительности)
    const float prevBeatFreq = m_currentBeatFreq.exchange(result.currentBeatFreq, std::memory_order_relaxed);
    m_currentCarrierFreq.store(result.currentCarrierFreq, std::memory_order_relaxed);
    
    // Callback только при значительном изменении частоты (> 0.1 Hz)
    if (std::abs(result.currentBeatFreq - prevBeatFreq) > 0.1f) {
        if (m_callbacks.onFrequencyChanged) {
            m_callbacks.onFrequencyChanged(result.currentBeatFreq, result.currentCarrierFreq);
        }
    }
    
    // Уведомляем о перестановке каналов (редкое событие)
    if (result.channelsSwapped && m_callbacks.onChannelsSwapped) {
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    return true;
}

} // namespace binaural