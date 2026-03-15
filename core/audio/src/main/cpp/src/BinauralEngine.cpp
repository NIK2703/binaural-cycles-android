#include "BinauralEngine.h"
#include <chrono>
#include <algorithm>
#include <android/log.h>

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#define LOG_TAG "BinauralEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace binaural {

BinauralEngine::BinauralEngine() {
    // Инициализация конфигурации по умолчанию
    m_config.curve.updateCache();
    // Интервал обновления частот по умолчанию - 10 секунд
    // Это обеспечивает оптимальный баланс между отзывчивостью UI
    // и энергоэффективностью (меньше прерываний)
    m_frequencyUpdateIntervalMs = 10000;
    
#ifdef USE_NEON
    LOGD("BinauralEngine initialized with NEON SIMD optimization");
#else
    LOGD("BinauralEngine initialized (scalar mode)");
#endif
}

BinauralEngine::~BinauralEngine() = default;

void BinauralEngine::setCallbacks(EngineCallbacks callbacks) {
    m_callbacks = std::move(callbacks);
}

void BinauralEngine::setConfig(const BinauralConfig& config) {
    std::lock_guard<std::mutex> lock(m_configMutex);
    m_config = config;
    // Строим lookup table с текущим интервалом обновления частот
    m_config.curve.buildLookupTable(m_frequencyUpdateIntervalMs);
}

void BinauralEngine::setSampleRate(int sampleRate) {
    m_generator.setSampleRate(sampleRate);
}

void BinauralEngine::setFrequencyUpdateInterval(int intervalMs) {
    m_frequencyUpdateIntervalMs = intervalMs;
    
    // Перестраиваем lookup table с новым интервалом
    std::lock_guard<std::mutex> lock(m_configMutex);
    m_config.curve.buildLookupTable(intervalMs);
    
    LOGD("Frequency update interval set to %d ms, lookup table rebuilt", intervalMs);
}

void BinauralEngine::setPlaying(bool playing) {
    m_isPlaying.store(playing);
    
    // СБРОС состояния при начале воспроизведения
    // Это гарантирует корректный отсчёт времени до следующей перестановки каналов
    if (playing) {
        m_state.lastSwapElapsedMs = 0;
        m_state.channelsSwapped = false;
        m_elapsedSeconds.store(0);
        // Инициализируем базовое время для точной интерполяции
        m_baseTimeSeconds = getCurrentTimeSeconds();
        m_totalBufferTimeSeconds = 0.0;
        __android_log_print(ANDROID_LOG_DEBUG, "BinauralEngine", 
            "setPlaying(true): reset lastSwapElapsedMs to 0, channelsSwapped to false, baseTime=%d", 
            m_baseTimeSeconds);
    }
}

void BinauralEngine::resetState() {
    m_generator.resetState(m_state);
    m_elapsedSeconds.store(0);
    m_currentBeatFreq.store(0.0);
    m_currentCarrierFreq.store(0.0);
}

int BinauralEngine::getRecommendedBufferSize() const {
    // Вычисляем размер буфера на основе интервала обновления частот
    // samplesPerChannel = sampleRate * intervalMs / 1000
    int sampleRate = m_generator.getSampleRate();
    return (sampleRate * m_frequencyUpdateIntervalMs) / 1000;
}

int32_t BinauralEngine::getCurrentTimeSeconds() const {
    // Thread-safe получение текущего времени суток
    // Используем chrono вместо localtime для избежания mutex contention
    auto now = std::chrono::system_clock::now();
    auto duration = now.time_since_epoch();
    
    // Получаем секунды с начала эпохи Unix
    auto totalSeconds = std::chrono::duration_cast<std::chrono::seconds>(duration).count();
    
    // Конвертируем в секунды с начала суток (86400 секунд в сутках)
    // Используем арифметику вместо localtime для thread-safety
    constexpr int32_t SECONDS_PER_DAY = 86400;
    int32_t timeOfDay = static_cast<int32_t>(totalSeconds % SECONDS_PER_DAY);
    
    // Учитываем временную зону (UTC+6 для Omsk, Asia/Omsk)
    // Для production нужно получать timezone из системы, но это требует thread-safe подхода
    // Здесь используем упрощённый вариант - localtime_r если доступен
#ifdef __ANDROID__
    // На Android используем localtime_r (thread-safe версия)
    time_t time = std::chrono::system_clock::to_time_t(now);
    struct tm tm_info;
    localtime_r(&time, &tm_info);
    return tm_info.tm_hour * 3600 + tm_info.tm_min * 60 + tm_info.tm_sec;
#else
    // Fallback: используем UTC (для тестирования)
    return timeOfDay;
#endif
}

void BinauralEngine::updateElapsedTime() {
    if (m_playbackStartTimeMs.load() > 0) {
        auto now = std::chrono::system_clock::now();
        auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()
        ).count();
        int elapsed = static_cast<int>((nowMs - m_playbackStartTimeMs.load()) / 1000);
        m_elapsedSeconds.store(elapsed);
        
        if (m_callbacks.onElapsedChanged) {
            m_callbacks.onElapsedChanged(elapsed);
        }
    }
}

bool BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel, int frequencyUpdateIntervalMs) {
    if (!m_isPlaying.load()) {
        return false;
    }
    
    // Вычисляем ТОЧНОЕ время для интерполяции
    // Используем накопленное время буферов вместо системного времени (избегаем jitter)
    int sampleRate = m_generator.getSampleRate();
    float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / sampleRate;
    
    // Точное время для начала буфера
    int32_t timeSeconds = static_cast<int32_t>(m_baseTimeSeconds + m_totalBufferTimeSeconds);
    
    // Нормализация в пределах суток
    constexpr int32_t SECONDS_PER_DAY = 86400;
    timeSeconds = ((timeSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    // Накапливаем время для следующего буфера
    m_totalBufferTimeSeconds += bufferDurationSeconds;
    
    int64_t elapsedMs = static_cast<int64_t>(m_elapsedSeconds.load()) * 1000;
    
    // Обновляем прошедшее время
    updateElapsedTime();
    
    // Генерируем буфер
    BinauralConfig config;
    {
        std::lock_guard<std::mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // Используем NEON-оптимизированную версию если доступна
#ifdef USE_NEON
    GenerateResult result = m_generator.generateBufferNeon(
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
    
    // Обновляем атомарные значения для Java
    // ОПТИМИЗАЦИЯ: callback вызываем только при реальном изменении
    const float prevBeatFreq = m_currentBeatFreq.exchange(result.currentBeatFreq);
    m_currentCarrierFreq.store(result.currentCarrierFreq);
    
    // Callback только при значительном изменении частоты (> 0.1 Hz)
    if (std::abs(result.currentBeatFreq - prevBeatFreq) > 0.1) {
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