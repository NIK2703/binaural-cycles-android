#include "BinauralEngine.h"
#include "BufferPackagePlanner.h"
#include <chrono>
#include <algorithm>
#include <cmath>
#include <atomic>
#include <shared_mutex>
#include <mutex>

#ifdef AUDIO_TEST_BUILD
#include "../tests/android_stub.h"
#elif defined(ANDROID)
#include <android/log.h>
#else
#include "../tests/android_stub.h"
#endif

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

// Нормализация времени суток в [0, 86400). double — для точности при больших total*scale.
namespace {
constexpr double kSecondsPerDayD = 86400.0;
inline float normalizeTimeOfDay(double t) {
    double r = std::fmod(t, kSecondsPerDayD);
    if (r < 0.0) r += kSecondsPerDayD;
    return static_cast<float>(r);
}
} // namespace

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
    float timeSeconds = computePlaybackTimeSeconds();

    // Множитель скорости виртуального времени. В debug-режиме (VirtualClock
    // включён) частота-кривая должна обходиться быстрее реального времени.
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float timeScale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
    const float timeScale = 1.0f;
#endif

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
        elapsedMs,
        timeScale
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#endif
    
    // Обновляем время используя РЕАЛЬНОЕ количество сгенерированных сэмплов.
    // double — для точности при долгих сессиях (total*scale может быть большим).
    const double batchDurationSeconds =
        static_cast<double>(result.samplesGenerated) / static_cast<double>(sampleRate);
    m_totalBufferTimeSeconds.store(
        m_totalBufferTimeSeconds.load(std::memory_order_relaxed) + batchDurationSeconds,
        std::memory_order_relaxed
    );
    
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
        BufferPackagePlanner planner;
        planner.resetState(m_state);
        m_state.lastSwapElapsedMs = 0;
        m_elapsedSeconds.store(0, std::memory_order_relaxed);

#ifdef ENABLE_DEBUG_TIME_CONTROL
        if (m_virtualClock.isEnabled()) {
            // ВАЖНО: НЕ сбрасываем m_totalBufferTimeSeconds.
            // И fresh-play, и resume в Kotlin идут через setPlaying(true);
            // сброс total откатил бы виртуальное время и вернул стык.
            // Таймлайн устанавливается в enable/scrub/setScale/reset.
            LOGD("setPlaying(true): virtual timeline preserved (base=%.2f, total=%.3f)",
                 m_virtualBaseTimeSeconds.load(std::memory_order_relaxed),
                 m_totalBufferTimeSeconds.load(std::memory_order_relaxed));
        } else {
            m_baseTimeSeconds = getCurrentTimeSeconds();
            m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
            LOGD("setPlaying(true): baseTime=%d", m_baseTimeSeconds);
        }
#else
        m_baseTimeSeconds = getCurrentTimeSeconds();
        m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
        LOGD("setPlaying(true): baseTime=%d", m_baseTimeSeconds);
#endif
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
#ifdef ENABLE_DEBUG_TIME_CONTROL
    if (m_virtualClock.isEnabled()) {
        // Та же sample-driven ось времени, что и у генерации,
        // чтобы UI-индикатор и отображаемые частоты совпадали со звуком.
        return static_cast<int32_t>(computePlaybackTimeSeconds());
    }
#endif
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

std::pair<float, float> BinauralEngine::getFrequenciesAtCurrentTime() {
    // Получаем текущее время суток в секундах — тот же таймлайн, что и генерация.
    const float currentSeconds = computePlaybackTimeSeconds();
    
    // Читаем конфигурацию с shared_lock
    std::shared_lock<std::shared_mutex> lock(m_configMutex);
    
    const auto& curve = m_config.curve;
    
    // Проверяем что lookup table построена
    if (curve.lowerFreqTable.empty() || curve.upperFreqTable.empty()) {
        return {0.0f, 0.0f};
    }
    
    // O(1) доступ к предвычисленной таблице
    // Индекс: время в мс / шаг таблицы (100 мс)
    const float timeMs = currentSeconds * 1000.0f;
    const float indexFloat = timeMs / FREQUENCY_TABLE_INTERVAL_MS;
    const int index = static_cast<int>(indexFloat);
    
    // Безопасное получение значений с проверкой границ
    const int clampedIndex = std::clamp(index, 0, static_cast<int>(curve.lowerFreqTable.size()) - 1);
    
    const float lowerFreq = curve.lowerFreqTable[clampedIndex];
    const float upperFreq = curve.upperFreqTable[clampedIndex];
    
    // Вычисляем beat и carrier частоты
    const float beatFreq = upperFreq - lowerFreq;
    const float carrierFreq = (lowerFreq + upperFreq) / 2.0f;
    
    return {beatFreq, carrierFreq};
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
    float timeSeconds = computePlaybackTimeSeconds();

    // Множитель скорости виртуального времени (см. generateBatch).
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float timeScale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
    const float timeScale = 1.0f;
#endif

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
        elapsedMs,
        timeScale
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
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
    
    // Обновляем время используя РЕАЛЬНОЕ количество сгенерированных сэмплов.
    // double — для точности при долгих сессиях (total*scale может быть большим).
    const double actualDurationSeconds =
        static_cast<double>(result.samplesGenerated) / static_cast<double>(sampleRate);
    m_totalBufferTimeSeconds.store(
        m_totalBufferTimeSeconds.load(std::memory_order_relaxed) + actualDurationSeconds,
        std::memory_order_relaxed
    );
    
    return true;
}

// ============ Debug virtual time ============

int32_t BinauralEngine::getCurrentTimeOfDaySeconds() const {
    return getCurrentTimeSeconds();
}

float BinauralEngine::computePlaybackTimeSeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    if (m_virtualClock.isEnabled()) {
        // SAMPLE-DRIVEN таймлайн (как в реальном режиме), но с масштабом:
        //   audioTime = normalize(base + totalSamples * scale)
        // Это устраняет скачок при включении/выключении virtual clock и при
        // смене scale/scrub — время всегда монотонно и непрерывно.
        const double total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const double scale = m_virtualClock.getTimeScale();
        const double base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        return normalizeTimeOfDay(base + total * scale);
    }
#endif
    // Существующая логика (real-time): base + накопленное реальное аудио
    const double total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
    return normalizeTimeOfDay(static_cast<double>(m_baseTimeSeconds) + total);
}

void BinauralEngine::setVirtualTimeEnabled(bool enabled) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // Сначала заякорить VirtualClock на реальное время, затем прочитать его.
    m_virtualClock.setEnabled(enabled);
    if (enabled) {
        // Seed sample-driven таймлайна текущим реальным временем суток.
        // setEnabled() только что заякорил VirtualClock на реальное время,
        // поэтому getTimeOfDaySeconds() == реальное время суток.
        m_virtualBaseTimeSeconds.store(m_virtualClock.getTimeOfDaySeconds(),
                                       std::memory_order_relaxed);
        m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
    } else {
        // Перед выключением синхронизируем реальный базис с текущим виртуальным
        // временем генерации, чтобы реальный режим продолжился без скачка.
        const double total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const double scale = m_virtualClock.getTimeScale();
        const double base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        m_baseTimeSeconds = static_cast<int32_t>(normalizeTimeOfDay(base + total * scale));
        m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
        m_virtualClock.setEnabled(false);
    }
    LOGD("setVirtualTimeEnabled(%d)", enabled ? 1 : 0);
#else
    (void)enabled;
#endif
}

void BinauralEngine::scrubVirtualTime(float timeOfDaySeconds) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // Сдвигаем базис таймлайна, сбрасываем накопленное аудио → без скачка скорости.
    m_virtualBaseTimeSeconds.store(std::fmod(timeOfDaySeconds, 86400.0f),
                                   std::memory_order_relaxed);
    m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
#else
    (void)timeOfDaySeconds;
#endif
}

void BinauralEngine::setVirtualTimeScale(float scale) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float clamped = std::clamp(scale, 1.0f, 60.0f);
    // Если уже бежит — сохраняем текущее аудио-время при смене масштаба
    // (переносим base, сбрасываем накопленное), чтобы не было скачка.
    if (m_virtualClock.isEnabled() && m_virtualClock.isRunning()) {
        const double total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const double oldScale = m_virtualClock.getTimeScale();
        const double base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        const double current = normalizeTimeOfDay(base + total * oldScale);
        m_virtualBaseTimeSeconds.store(static_cast<float>(current),
                                       std::memory_order_relaxed);
        m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
    }
    m_virtualClock.setTimeScale(clamped);
    LOGD("setVirtualTimeScale(%.2f)", clamped);
#else
    (void)scale;
#endif
}

void BinauralEngine::setVirtualTimeRunning(bool running) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    m_virtualClock.setRunning(running);
    // Ничего не меняем в таймлайне: при паузе генерация останавливается
    // (write блокируется о паузу AudioTrack) и m_totalBufferTimeSeconds замирает сам.
#else
    (void)running;
#endif
}

void BinauralEngine::resetVirtualTimeToReal() {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    m_virtualClock.resetToRealTime();
    // Пересадка на реальное время суток (после resetToRealTime getTimeOfDaySeconds()
    // возвращает реальное время, а не sample-driven ось UI).
    m_virtualBaseTimeSeconds.store(m_virtualClock.getTimeOfDaySeconds(),
                                    std::memory_order_relaxed);
    m_totalBufferTimeSeconds.store(0.0, std::memory_order_relaxed);
#endif
}

float BinauralEngine::getVirtualTimeOfDaySeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // Отдаём то же самое время, что генерируется (sample-driven), для консистентности UI.
    if (m_virtualClock.isEnabled()) {
        return computePlaybackTimeSeconds();
    }
    return static_cast<float>(getCurrentTimeSeconds());
#else
    return static_cast<float>(getCurrentTimeSeconds());
#endif
}

bool BinauralEngine::isVirtualTimeEnabled() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    return m_virtualClock.isEnabled();
#else
    return false;
#endif
}

float BinauralEngine::getVirtualTimeScale() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    return m_virtualClock.getTimeScale();
#else
    return 1.0f;
#endif
}

} // namespace binaural