#include "BinauralEngine.h"
#include <chrono>
#include <algorithm>
#include <android/log.h>

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
}

BinauralEngine::~BinauralEngine() = default;

void BinauralEngine::setCallbacks(EngineCallbacks callbacks) {
    m_callbacks = std::move(callbacks);
}

void BinauralEngine::setConfig(const BinauralConfig& config) {
    std::lock_guard<std::mutex> lock(m_configMutex);
    m_config = config;
    m_config.curve.updateCache();
}

void BinauralEngine::setSampleRate(int sampleRate) {
    m_generator.setSampleRate(sampleRate);
}

void BinauralEngine::setPlaying(bool playing) {
    m_isPlaying.store(playing);
    
    // СБРОС состояния при начале воспроизведения
    // Это гарантирует корректный отсчёт времени до следующей перестановки каналов
    if (playing) {
        m_state.lastSwapElapsedMs = 0;
        m_state.channelsSwapped = false;
        m_elapsedSeconds.store(0);
        __android_log_print(ANDROID_LOG_DEBUG, "BinauralEngine", 
            "setPlaying(true): reset lastSwapElapsedMs to 0, channelsSwapped to false");
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
    // Получаем текущее время суток
    auto now = std::chrono::system_clock::now();
    auto time_t = std::chrono::system_clock::to_time_t(now);
    struct tm* tm_info = localtime(&time_t);
    return tm_info->tm_hour * 3600 + tm_info->tm_min * 60 + tm_info->tm_sec;
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

bool BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel) {
    if (!m_isPlaying.load()) {
        return false;
    }
    
    // Получаем текущее время
    int32_t timeSeconds = getCurrentTimeSeconds();
    int64_t elapsedMs = static_cast<int64_t>(m_elapsedSeconds.load()) * 1000;
    
    // Обновляем прошедшее время
    updateElapsedTime();
    
    // Генерируем буфер
    BinauralConfig config;
    {
        std::lock_guard<std::mutex> lock(m_configMutex);
        config = m_config;
    }
    
    GenerateResult result = m_generator.generateBuffer(
        buffer,
        samplesPerChannel,
        config,
        m_state,
        timeSeconds,
        elapsedMs
    );
    
    // Обновляем атомарные значения для Java
    if (result.currentBeatFreq != m_currentBeatFreq.load()) {
        m_currentBeatFreq.store(result.currentBeatFreq);
        if (m_callbacks.onFrequencyChanged) {
            m_callbacks.onFrequencyChanged(result.currentBeatFreq, result.currentCarrierFreq);
        }
    }
    m_currentCarrierFreq.store(result.currentCarrierFreq);
    
    // Уведомляем о перестановке каналов
    if (result.channelsSwapped && m_callbacks.onChannelsSwapped) {
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    return true;
}

} // namespace binaural