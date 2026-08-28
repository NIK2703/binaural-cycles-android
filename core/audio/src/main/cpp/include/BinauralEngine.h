#pragma once

#include "Config.h"
#include "AudioGenerator.h"

#ifdef ENABLE_DEBUG_TIME_CONTROL
#include "VirtualClock.h"
#endif

#include <memory>
#include <functional>
#include <atomic>
#include <shared_mutex>
#include <utility>   // std::pair (getFrequenciesAtCurrentTime)

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
     * Установить частоту дискретизации
     */
    void setSampleRate(int sampleRate);
    
    /**
     * Получить частоту дискретизации
     */
    int getSampleRate() const { return m_generator.getSampleRate(); }
    
    /**
     * Установить длительность батча для оптимизации энергопотребления
     * @param durationMinutes длительность в минутах (0 = отключено)
     */
    void setBatchDurationMinutes(int durationMinutes);
    
    /**
     * Получить длительность батча в минутах
     */
    int getBatchDurationMinutes() const { return m_batchDurationMinutes; }
    
    /**
     * Сгенерировать батч аудио на заданное время
     * Оптимизация: генерирует один большой буфер вместо множества маленьких
     */
    int generateBatch(float* buffer, int maxSamplesPerChannel);
    
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
    /**
     * Сгенерировать буфер аудио
     * Вызывается из AudioTrack в Java
     *
     * @param buffer выходной буфер (float*, interleaved stereo)
     * @param samplesPerChannel количество сэмплов на канал (запрошено)
     * @return РЕАЛЬНО сгенерированное количество сэмплов на канал.
     *         Из-за целочисленного округления длительностей сегментов оно может
     *         отличаться от samplesPerChannel. Вызывающая сторона ОБЯЗАНА
     *         записать в AudioTrack ровно это значение, иначе на стыке пакетов
     *         прозвучит мусорный "хвост" (щелчок + резкая смена частот).
     *         0 — воспроизведение не активно.
     */
    int generateAudioBuffer(float* buffer, int samplesPerChannel);
    
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
     * Расширенная версия: preserveTimeline=true (RESUME) продолжает с того же
     * места кривой и той же фазой — НЕ сбрасывает m_baseTimeSeconds /
     * m_totalBufferTimeSeconds / фазы генератора. Иначе pause→resume даёт
     * скачок кривой к wall-clock и фазовый щелчок.
     */
    void setPlaying(bool playing, bool preserveTimeline);
    
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
     * Явно задать позицию кривой (секунды суток) для продолжения таймлайна
     * в свежем движке (resume/handoff). Вызывать до setPlaying(true, true).
     */
    void setCurveTimeSeconds(float timeSeconds);
    
    /**
     * Обновить прошедшее время
     */
    void updateElapsedTime();
    
    /**
     * Получить состояние перестановки каналов
     */
    bool isChannelsSwapped() const;
    
    /**
     * Получить частоты каналов для текущего времени из lookup table.
     * O(1) операция - использует предвычисленную таблицу.
     * @return Pair(beatFrequency, carrierFrequency) или (0, 0) если конфиг не установлен
     */
    std::pair<float, float> getFrequenciesAtCurrentTime();
    /**
     * Получить текущую фазу несущих каналов (для бесшовного кроссфейда).
     * Чтение m_state.leftPhase/rightPhase со стороны актёра (OLD ещё играет) —
     * best-effort: на ARM выровненное 32-битное чтение атомарно, расхождение
     * с пишущим аудио-потоком — доли микросекунд (пренебрежимо для фазы).
     */
    std::pair<float, float> getCurrentPhases() const;

    /**
     * Установить фазу несущих каналов (продолжение кроссфейда).
     * Вызывать СТРОГО до запуска писателя (в prepare()), иначе гонка с
     * аудио-потоком.
     */
    void setPhases(float leftPhase, float rightPhase);

    // Кривая сконфигурирована (lookup-таблица построена). Позволяет JNI-слою
    // отличать «конфиг не установлен» от легитимной точки 0/0 Гц.
    bool isCurveConfigured() const;

    // ===== UI-таймлайн кривой (плавное следование частот за графиком) =====
    // Генерация остаётся sample-driven (m_curveTimeSeconds продвигается только
    // при генерации пакета), поэтому прямое использование computePlaybackTimeSeconds()
    // в UI даёт «ступеньку» раз в интервал генерации: частоты на экране стоят
    // на месте до минуты, пока указатель времени ползёт по графику.
    // UI-время экстраполируется от якоря последнего пакета по wall-clock
    // и ограничивается сгенерированным диапазоном [start, end].
    void anchorUiTimeline(float startSec, float endSec);
    float computeUiTimeSeconds();

    // ====== НОВОЕ: публичный доступ к текущему времени суток ======
    // Учитывает виртуальный режим (в release всегда возвращает реальное время).
    int32_t getCurrentTimeOfDaySeconds() const;

    // ====== НОВОЕ: Debug virtual time (в release — no-op) ======
    void setVirtualTimeEnabled(bool enabled);
    void scrubVirtualTime(float timeOfDaySeconds);
    void setVirtualTimeScale(float scale);       // clamp 1..60 внутри
    void setVirtualTimeRunning(bool running);
    void resetVirtualTimeToReal();
    float getVirtualTimeOfDaySeconds() const;
    bool isVirtualTimeEnabled() const;
    float getVirtualTimeScale() const;

private:
    // ========================================================================
    // ИНВАРИАНТ ПОТОКОВ (важно не сломать!):
    //  - ТОЛЬКО АУДИО-ПОТОК (писатель) вызывает методы, читающие/мутирующие
    //    m_generator и m_state: generateAudioBuffer / generateBatch.
    //  - resetState / setPlaying / setCurveTimeSeconds / setConfig вызываются
    //    нитью-владельцем СТРОГО до запуска писателя либо после его
    //    гарантированного выхода (см. BinauralStreamImpl.releaseInternal).
    //  - m_configMutex защищает ТОЛЬКО m_config (чтение копии под
    //    shared_lock в аудио-потоке и подмена под unique_lock в setConfig).
    //    Если когда-либо понадобится «живая» переконфигурация во время
    //    воспроизведения — потребуется расширить синхронизацию на весь
    //    цикл генерации (или перезапускать стрим), иначе гонка на
    //    векторах кривой / состоянии генератора.
    //  - m_state.channelsSwapped пишется под unique_lock(m_configMutex)
    //    (setPlaying) и читается под shared_lock (isChannelsSwapped).
    // ========================================================================
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
    
    // Длительность батча в минутах (0 = отключено)
    int m_batchDurationMinutes = 0;
    
    // Точная интерполяция времени между буферами
    // atomic: пишется из binder-потока (fresh-play/disable), читается аудио-потоком в PKG_BOUNDARY
    std::atomic<int32_t> m_baseTimeSeconds{0};   // Время начала воспроизведения (legacy/диагностика)

    // Накопленная РЕАЛЬНАЯ длительность сгенерированного аудио (сек).
    // float — единая ось времени с генератором (без конверсий double<->float).
    // atomic — читается UI-потоком (getCurrentTimeSeconds) и пишется аудио-потоком.
    std::atomic<float> m_totalBufferTimeSeconds{0.0f};

    // ЕДИНЫЙ НОСИТЕЛЬ времени кривой (аналог curTime в тестовом харнессе):
    // старт пакета N+1 — ТА ЖЕ САМАЯ float-величина, что и конец пакета N,
    // без пересчёта base+total (исключает любое расхождение на стыке).
    std::atomic<float> m_curveTimeSeconds{0.0f};

    /**
     * Получить текущее время суток в секундах
     */
    int32_t getCurrentTimeSeconds() const;

    /**
     * Дробное локальное время суток в секундах (E1: якорь свежего play без
     * потери доли секунды; чистая функция от часов — потокобезопасна)
     */
    static float realTimeOfDaySeconds();

    /**
     * Вычислить время суток для генерации буфера
     * (virtual clock, если включён; иначе старая формула на основе baseTime + накопленного аудио)
     */
    float computePlaybackTimeSeconds() const;

    std::atomic<float> m_uiAnchorStartSec{0.0f};   // кривая-время старта последнего пакета
    std::atomic<float> m_uiAnchorEndSec{0.0f};     // кривая-время конца сгенерированного аудио
    std::atomic<int64_t> m_uiAnchorWallMs{0};      // wall-clock якоря (0 = ещё не якорили)
    std::atomic<float> m_uiLastUiTimeSec{0.0f};    // последнее показанное UI-время (стicky на паузе)

#ifdef ENABLE_DEBUG_TIME_CONTROL
    VirtualClock m_virtualClock;   // присутствует только в debug-сборке

    // База sample-driven виртуального таймлайна (время суток в момент старта/scrub).
    // Аудио-время = normalize(m_virtualBaseTimeSeconds + m_totalBufferTimeSeconds * scale).
    std::atomic<float> m_virtualBaseTimeSeconds{0.0f};
#endif
};

} // namespace binaural