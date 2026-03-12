#pragma once

#include "Config.h"
#include "AudioGenerator.h"
#include <memory>
#include <functional>
#include <mutex>
#include <atomic>

namespace binaural {

/**
 * Callback для уведомлений о изменении состояния
 */
struct EngineCallbacks {
    std::function<void(bool isPlaying)> onPlayingChanged;
    std::function<void(double beatFreq, double carrierFreq)> onFrequencyChanged;
    std::function<void(bool channelsSwapped)> onChannelsSwapped;
    std::function<void(int elapsedSeconds)> onElapsedChanged;
};

/**
 * Главный класс C++ аудиодвижка
 * Управляет состоянием и генерацией аудио
 */
class BinauralEngine {
public:
    BinauralEngine();
    ~BinauralEngine();
    
    /**
     * Установить callbacks для уведомлений
     */
    void setCallbacks(EngineCallbacks callbacks);
    
    /**
     * Установить конфигурацию
     */
    void setConfig(const BinauralConfig& config);
    
    /**
     * Получить текущую конфигурацию
     */
    const BinauralConfig& getConfig() const { return m_config; }
    
    /**
     * Установить частоту дискретизации
     */
    void setSampleRate(int sampleRate);
    
    /**
     * Получить частоту дискретизации
     */
    int getSampleRate() const { return m_generator.getSampleRate(); }
    
    /**
     * Установить интервал обновления частот в мс
     */
    void setFrequencyUpdateInterval(int intervalMs) { m_frequencyUpdateIntervalMs = intervalMs; }
    
    /**
     * Получить интервал обновления частот
     */
    int getFrequencyUpdateInterval() const { return m_frequencyUpdateIntervalMs; }
    
    /**
     * Сбросить состояние (при остановке)
     */
    void resetState();
    
    /**
     * Сгенерировать буфер аудио
     * Вызывается из AudioTrack в Java
     * 
     * @param buffer выходной буфер (float*, interleaved stereo)
     * @param samplesPerChannel количество сэмплов на канал
     * @return true если генерация успешна
     */
    bool generateAudioBuffer(float* buffer, int samplesPerChannel);
    
    /**
     * Получить текущее состояние проигрывания
     */
    bool isPlaying() const { return m_isPlaying.load(); }
    
    /**
     * Установить состояние проигрывания (для синхронизации с Java)
     * При начале воспроизведения сбрасывает состояние перестановки каналов
     */
    void setPlaying(bool playing);
    
    /**
     * Получить текущую частоту биений
     */
    double getCurrentBeatFrequency() const { return m_currentBeatFreq.load(); }
    
    /**
     * Получить текущую несущую частоту
     */
    double getCurrentCarrierFrequency() const { return m_currentCarrierFreq.load(); }
    
    /**
     * Получить прошедшее время в секундах
     */
    int getElapsedSeconds() const { return m_elapsedSeconds.load(); }
    
    /**
     * Установить время начала воспроизведения (для расчёта elapsed)
     */
    void setPlaybackStartTime(int64_t startTimeMs) { m_playbackStartTimeMs = startTimeMs; }
    
    /**
     * Обновить прошедшее время
     */
    void updateElapsedTime();
    
    /**
     * Получить состояние перестановки каналов
     */
    bool isChannelsSwapped() const { return m_state.channelsSwapped; }

private:
    BinauralConfig m_config;
    AudioGenerator m_generator;
    GeneratorState m_state;
    EngineCallbacks m_callbacks;
    
    std::mutex m_configMutex;
    std::atomic<bool> m_isPlaying{false};
    std::atomic<double> m_currentBeatFreq{0.0};
    std::atomic<double> m_currentCarrierFreq{0.0};
    std::atomic<int> m_elapsedSeconds{0};
    std::atomic<int64_t> m_playbackStartTimeMs{0};
    
    int m_frequencyUpdateIntervalMs = 100;
    
    /**
     * Получить текущее время суток в секундах
     */
    int32_t getCurrentTimeSeconds() const;
};

} // namespace binaural