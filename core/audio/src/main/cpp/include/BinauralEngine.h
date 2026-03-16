#pragma once

#include "Config.h"
#include "AudioGenerator.h"
#include <memory>
#include <functional>
#include <atomic>
#include <shared_mutex>

namespace binaural {

/**
 * Callback для уведомлений о изменении состояния
 */
struct EngineCallbacks {
    std::function<void(bool isPlaying)> onPlayingChanged;
    std::function<void(float beatFreq, float carrierFreq)> onFrequencyChanged;
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
     * Этот параметр определяет размер порции генерации буфера
     * Больший интервал = меньше прерываний = лучше энергоэффективность
     * Также перестраивает lookup table для оптимального размера
     */
    void setFrequencyUpdateInterval(int intervalMs);
    
    /**
     * Получить интервал обновления частот
     */
    int getFrequencyUpdateInterval() const { return m_frequencyUpdateIntervalMs; }
    
    /**
     * Получить рекомендуемый размер буфера в сэмплах на канал
     * на основе интервала обновления частот
     */
    int getRecommendedBufferSize() const;
    
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
     * @param frequencyUpdateIntervalMs интервал обновления частот в мс (для интерполяции)
     * @return true если генерация успешна
     */
    bool generateAudioBuffer(float* buffer, int samplesPerChannel, int frequencyUpdateIntervalMs);
    
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
    float getCurrentBeatFrequency() const { return m_currentBeatFreq.load(); }
    
    /**
     * Получить текущую несущую частоту
     */
    float getCurrentCarrierFrequency() const { return m_currentCarrierFreq.load(); }
    
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
    
    mutable std::shared_mutex m_configMutex;  // Reader-writer lock для оптимизации
    std::atomic<bool> m_isPlaying{false};
    std::atomic<float> m_currentBeatFreq{0.0};
    std::atomic<float> m_currentCarrierFreq{0.0};
    std::atomic<int> m_elapsedSeconds{0};
    std::atomic<int64_t> m_playbackStartTimeMs{0};
    
    // Начальное значение до получения из настроек через JNI
    // Должно соответствовать значению по умолчанию в BinauralPreferencesRepository
    int m_frequencyUpdateIntervalMs = 10000;
    
    // Точная интерполяция времени между буферами
    int32_t m_baseTimeSeconds = 0;          // Время начала воспроизведения
    float m_totalBufferTimeSeconds = 0.0;  // Накопленное время буферов
    
    /**
     * Получить текущее время суток в секундах
     */
    int32_t getCurrentTimeSeconds() const;
};

} // namespace binaural