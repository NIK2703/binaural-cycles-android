#pragma once

#include <chrono>
#include <cmath>
#include <cstdint>
#include <ctime>
#include <mutex>

#include "DebugWallClock.h"

namespace binaural {

constexpr int32_t VC_SECONDS_PER_DAY = 86400;

/**
 * Виртуальные часы для DEBUG-сборки.
 *
 * Единая точка истины для:
 *   - lookup частот (getFrequenciesAtCurrentTime)
 *   - генерации аудио (computePlaybackTimeSeconds)
 *   - UI-индикатора времени
 *
 * Модель: якорь на steady_clock. Текущее виртуальное время суток:
 *   anchorVirtual + timeScale * (nowSteady - anchorSteady)
 * Re-anchor при scrub / смене scale / pause предотвращает скачки.
 *
 * ВАЖНО: используется только при определённом ENABLE_DEBUG_TIME_CONTROL.
 */
class VirtualClock {
public:
    bool isEnabled() const {
        std::lock_guard<std::mutex> lk(m_mutex);
        return m_enabled;
    }

    bool isRunning() const {
        std::lock_guard<std::mutex> lk(m_mutex);
        return m_running;
    }

    float getTimeScale() const {
        std::lock_guard<std::mutex> lk(m_mutex);
        return m_timeScale;
    }

    void setEnabled(bool enabled) {
        std::lock_guard<std::mutex> lk(m_mutex);
        if (enabled && !m_enabled) {
            // При включении стартуем с реального времени суток, scale=1, running.
            m_anchorVirtualSeconds = realTimeOfDaySeconds();
            m_anchorSteadyNs = steadyNowNs();
            m_running = true;
            m_timeScale = 1.0f;
        }
        m_enabled = enabled;
    }

    // Установить абсолютное время суток (scrub).
    void scrubTo(float timeOfDaySeconds) {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_anchorVirtualSeconds = normalize(timeOfDaySeconds);
        m_anchorSteadyNs = steadyNowNs();
    }

    // Сменить множитель без скачка: re-anchor в текущей виртуальной точке.
    void setTimeScale(float scale) {
        std::lock_guard<std::mutex> lk(m_mutex);
        const float current = getTimeOfDaySecondsUnlocked();
        m_timeScale = scale;
        m_anchorVirtualSeconds = current;
        m_anchorSteadyNs = steadyNowNs();
    }

    // Пауза/ход виртуального времени.
    void setRunning(bool running) {
        std::lock_guard<std::mutex> lk(m_mutex);
        if (m_running == running) return;
        if (running) {
            // возобновление: якорь уже хранит замороженное время
            m_anchorSteadyNs = steadyNowNs();
        } else {
            // пауза: фиксируем текущее бегущее значение
            m_anchorVirtualSeconds = getTimeOfDaySecondsUnlocked();
        }
        m_running = running;
    }

    // Сброс к реальному времени (scale=1, running).
    void resetToRealTime() {
        std::lock_guard<std::mutex> lk(m_mutex);
        m_anchorVirtualSeconds = realTimeOfDaySeconds();
        m_anchorSteadyNs = steadyNowNs();
        m_timeScale = 1.0f;
        m_running = true;
    }

    float getTimeOfDaySeconds() const {
        std::lock_guard<std::mutex> lk(m_mutex);
        return getTimeOfDaySecondsUnlocked();
    }

private:
    float getTimeOfDaySecondsUnlocked() const {
        if (!m_running) {
            return normalize(m_anchorVirtualSeconds);
        }
        const int64_t elapsedNs = steadyNowNs() - m_anchorSteadyNs;
        const float elapsedRealSeconds = static_cast<float>(elapsedNs) * 1e-9f;
        const float virtualSeconds =
            m_anchorVirtualSeconds + m_timeScale * elapsedRealSeconds;
        return normalize(virtualSeconds);
    }

    static float normalize(float s) {
        float r = std::fmod(s, static_cast<float>(VC_SECONDS_PER_DAY));
        if (r < 0.0f) r += static_cast<float>(VC_SECONDS_PER_DAY);
        return r;
    }

    static int64_t steadyNowNs() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    // Реальное локальное время суток с миллисекундной дробью.
    // Целая и дробная части из ОДНОГО снимка часов (согласовано с
    // BinauralEngine::realTimeOfDaySeconds).
    //
    // Сдвиг виртуальных настенных часов (binaural::debug) прибавляется здесь же:
    // VirtualClock — ИСТОЧНИК посева для sample-driven оси движка, и если он
    // останется на реальных часах, то `vtime on` после перемотки настенных часов
    // посадит кривую на реальное время суток и вся верификация паузы поедет.
    static float realTimeOfDaySeconds() {
        const int64_t nowMs = binaural::debug::nowWallMs();
        const std::time_t t = static_cast<std::time_t>(nowMs / 1000);
        std::tm tmInfo{};
#if defined(_WIN32)
        localtime_s(&tmInfo, &t);
#else
        localtime_r(&t, &tmInfo);
#endif
        const float whole = static_cast<float>(
            tmInfo.tm_hour * 3600 + tmInfo.tm_min * 60 + tmInfo.tm_sec);
        return whole + static_cast<float>(nowMs % 1000) / 1000.0f;
    }

    mutable std::mutex m_mutex;
    bool m_enabled = false;
    bool m_running = true;
    float m_timeScale = 1.0f;
    float m_anchorVirtualSeconds = 0.0f;
    int64_t m_anchorSteadyNs = 0;
};

} // namespace binaural