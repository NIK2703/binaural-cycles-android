#include "BinauralEngine.h"
#include "BufferPackagePlanner.h"
#include <chrono>
#include <algorithm>
#include <cmath>
#include <android/log.h>
#include <atomic>
#include <shared_mutex>

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#ifdef USE_SSE
#include <immintrin.h>
#endif

// Логирование только в DEBUG сборках
#ifdef AUDIO_DEBUG
#define LOG_TAG "BinauralEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

namespace binaural {

BinauralEngine::BinauralEngine() {
    // Инициализация конфигурации по умолчанию
    m_config.curve.updateCache();
    
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
    newConfig.curve.buildLookupTable();
    
    // Эксклюзивная блокировка для записи
    std::unique_lock<std::shared_mutex> lock(m_configMutex);
    m_config = std::move(newConfig);
}

void BinauralEngine::setSampleRate(int sampleRate) {
    m_generator.setSampleRate(sampleRate);
}

void BinauralEngine::setBatchDurationMinutes(int durationMinutes) {
    m_batchDurationMinutes = durationMinutes;
    LOGD("Batch duration set to %d minutes", durationMinutes);
}

int BinauralEngine::generateBatch(float* buffer, int maxSamplesPerChannel) {
    if (!m_isPlaying.load(std::memory_order_acquire) || m_batchDurationMinutes <= 0) {
        return 0;
    }
    
    const int sampleRate = m_generator.getSampleRate();
    const int64_t packageDurationMs = static_cast<int64_t>(m_batchDurationMinutes) * 60 * 1000LL;
    const int maxSamples = m_batchDurationMinutes * 60 * sampleRate;
    const int samplesToGenerate = std::min(maxSamples, maxSamplesPerChannel);
    
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // Планируем пакет буферов
    BufferPackagePlanner planner;
    PackagePlan plan = planner.planPackage(packageDurationMs, config, m_state);
    
    // Точное время для начала буфера (float для сохранения дробной части)
    // КРИТИЧНО: используем float вместо int32_t для бесшовных переходов между пакетами
    float timeSeconds = m_baseTimeSeconds + m_totalBufferTimeSeconds;
    
    // Нормализация в пределах суток с сохранением дробной части
    constexpr float SECONDS_PER_DAY_F = 86400.0f;
    timeSeconds = std::fmod(timeSeconds, SECONDS_PER_DAY_F);
    if (timeSeconds < 0.0f) {
        timeSeconds += SECONDS_PER_DAY_F;
    }
    
    const int64_t elapsedMs = static_cast<int64_t>(
        m_elapsedSeconds.load(std::memory_order_relaxed)
    ) * 1000;
    
    // Генерируем пакет буферов по плану
#if defined(USE_NEON)
    GenerateResult result = m_generator.generatePackageNeon(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
#endif
    
    // Обновляем время
    const float batchDurationSeconds = static_cast<float>(samplesToGenerate) / sampleRate;
    m_totalBufferTimeSeconds += batchDurationSeconds;
    
    // Обновляем атомарные значения для Java
    const float prevBeatFreq = m_currentBeatFreq.exchange(result.currentBeatFreq, std::memory_order_relaxed);
    m_currentCarrierFreq.store(result.currentCarrierFreq, std::memory_order_relaxed);
    
    // Callback при значительном изменении частоты (> 0.1 Hz)
    if (std::abs(result.currentBeatFreq - prevBeatFreq) > 0.1f) {
        if (m_callbacks.onFrequencyChanged) {
            m_callbacks.onFrequencyChanged(result.currentBeatFreq, result.currentCarrierFreq);
        }
    }
    
    // Уведомляем о перестановке каналов
    if (result.channelsSwapped && m_callbacks.onChannelsSwapped) {
        LOGD("ChannelSwap: elapsedMs=%lld, channelsSwapped=%d",
             (long long)elapsedMs, m_state.channelsSwapped ? 1 : 0);
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    return samplesToGenerate;
}

void BinauralEngine::setPlaying(bool playing) {
    m_isPlaying.store(playing, std::memory_order_release);
    
    if (playing) {
        // Сброс состояния при начале воспроизведения
        BufferPackagePlanner planner;
        planner.resetState(m_state);
        m_state.lastSwapElapsedMs = 0;
        m_elapsedSeconds.store(0, std::memory_order_relaxed);
        m_baseTimeSeconds = getCurrentTimeSeconds();
        m_totalBufferTimeSeconds = 0.0;
        
        LOGD("setPlaying(true): baseTime=%d", m_baseTimeSeconds);
    }
}

void BinauralEngine::resetState() {
    m_generator.resetState(m_state);
    
    // Сброс состояния планировщика
    BufferPackagePlanner planner;
    planner.resetState(m_state);
    
    m_elapsedSeconds.store(0, std::memory_order_relaxed);
    m_currentBeatFreq.store(0.0f, std::memory_order_relaxed);
    m_currentCarrierFreq.store(0.0f, std::memory_order_relaxed);
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

bool BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel) {
    // Быстрая проверка без блокировки
    if (!m_isPlaying.load(std::memory_order_acquire)) {
        return false;
    }
    
    // Вычисляем точное время для интерполяции
    const int sampleRate = m_generator.getSampleRate();
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / sampleRate;
    const int64_t bufferDurationMs = static_cast<int64_t>(samplesPerChannel) * 1000 / sampleRate;
    
    // Точное время для начала буфера (float для сохранения дробной части)
    // КРИТИЧНО: используем float вместо int32_t для бесшовных переходов между пакетами
    float timeSeconds = m_baseTimeSeconds + m_totalBufferTimeSeconds;
    
    // Нормализация в пределах суток с сохранением дробной части
    constexpr float SECONDS_PER_DAY_F = 86400.0f;
    timeSeconds = std::fmod(timeSeconds, SECONDS_PER_DAY_F);
    if (timeSeconds < 0.0f) {
        timeSeconds += SECONDS_PER_DAY_F;
    }
    
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
    
    // НОВАЯ АРХИТЕКТУРА: Используем планировщик пакетов
    // Планируем пакет буферов на основе текущего состояния
    BufferPackagePlanner planner;
    PackagePlan plan = planner.planPackage(bufferDurationMs, config, m_state);
    
    // Используем SIMD-оптимизированную версию если доступна
#if defined(USE_NEON)
    GenerateResult result = m_generator.generatePackageNeon(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs
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
        LOGD("ChannelSwap: elapsedMs=%lld, channelsSwapped=%d",
             (long long)elapsedMs, m_state.channelsSwapped ? 1 : 0);
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    return true;
}

} // namespace binaural