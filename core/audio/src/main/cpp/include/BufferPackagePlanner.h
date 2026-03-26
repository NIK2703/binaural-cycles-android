#pragma once

#include "Config.h"
#include <vector>
#include <algorithm>

namespace binaural {

/**
 * Планировщик пакетов буферов
 *
 * Разбивает время пакета на последовательность целых буферов согласно циклу:
 * [SOLID N сек] → [FADE_OUT M сек] → [FADE_IN M сек] → [SOLID N сек] → ...
 *
 * Ключевой принцип: неполный буфер в конце пакета переносится в начало следующего.
 *
 * Пример для 2 минут и интервале 30 сек:
 * Пакет 1: [solid 30s] [fade-out 1s] [fade-in 1s] [solid 30s] [fade-out 1s] [fade-in 1s] [solid 26s]
 * Пакет 2: [solid 4s] [fade-out 1s] [fade-in 1s] [solid 30s] ...
 */

// Логирование только в DEBUG сборках
#ifdef AUDIO_DEBUG
#include <android/log.h>
#define LOG_TAG "BufferPackagePlanner"
#define LOGD_PLANNER(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD_PLANNER(...) ((void)0)
#endif
class BufferPackagePlanner {
public:
    /**
     * Спланировать пакет буферов
     * 
     * @param packageDurationMs Длительность пакета в мс
     * @param config Конфигурация с параметрами swap
     * @param state Текущее состояние (изменяется для продолжения с места остановки)
     * @return План пакета с последовательностью сегментов
     */
    PackagePlan planPackage(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state
    );
    
    /**
     * Вычислить длительность полного swap-цикла
     * Цикл = SOLID + FADE_OUT + FADE_IN
     * 
     * @param config Конфигурация с параметрами swap
     * @return Длительность цикла в мс, или 0 если swap отключён
     */
    int64_t calculateCycleDuration(const BinauralConfig& config) const;
    
    /**
     * Сбросить состояние планировщика в начальное
     * Вызывается при начале воспроизведения
     * 
     * @param state Состояние для сброса
     */
    void resetState(GeneratorState& state);
    
private:
    /**
     * Определить следующую фазу после текущей
     */
    SwapPhase nextPhase(SwapPhase current) const;
    
    /**
     * Вычислить длительность фазы в мс
     */
    int64_t phaseDuration(SwapPhase phase, const BinauralConfig& config) const;
    
    /**
     * Преобразовать SwapPhase в BufferType
     */
    BufferType toBufferType(SwapPhase phase) const;
};

// ============================================================================
// INLINE РЕАЛИЗАЦИЯ ДЛЯ ПРОИЗВОДИТЕЛЬНОСТИ
// ============================================================================

inline PackagePlan BufferPackagePlanner::planPackage(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state
) {
    PackagePlan plan;
    plan.totalDurationMs = 0;
    plan.endsMidCycle = false;
    
    LOGD_PLANNER("planPackage: duration=%lldms, swapEnabled=%d, fadeEnabled=%d, fadeDuration=%lldms",
                 (long long)packageDurationMs,
                 config.channelSwapEnabled ? 1 : 0,
                 config.channelSwapFadeEnabled ? 1 : 0,
                 (long long)config.channelSwapFadeDurationMs);
    
    // Без swap: один сплошной буфер на весь пакет
    if (!config.channelSwapEnabled) {
        BufferSegment segment;
        segment.type = BufferType::SOLID;
        segment.durationMs = packageDurationMs;
        segment.swapAfterSegment = false;
        plan.segments.push_back(segment);
        plan.totalDurationMs = packageDurationMs;
        return plan;
    }
    
    int64_t remainingTime = packageDurationMs;
    SwapPhase currentPhase = state.swapPhase;
    int64_t phaseTimeRemaining = state.phaseRemainingMs;
    
    LOGD_PLANNER("  initial state: phase=%d, phaseRemaining=%lldms, channelsSwapped=%d",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 state.channelsSwapped ? 1 : 0);
    
    // Если phaseRemainingMs == 0, начинаем новую фазу
    if (phaseTimeRemaining == 0) {
        phaseTimeRemaining = phaseDuration(currentPhase, config);
        LOGD_PLANNER("  starting new phase: phase=%d, duration=%lldms",
                     static_cast<int>(currentPhase), (long long)phaseTimeRemaining);
    }
    
    int segmentIndex = 0;
    while (remainingTime > 0) {
        // Пропускаем фазы с нулевой длительностью (например, если fade отключён)
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = phaseDuration(currentPhase, config);
            LOGD_PLANNER("  skip to next phase: phase=%d, duration=%lldms",
                         static_cast<int>(currentPhase), (long long)phaseTimeRemaining);
            continue;
        }
        
        // Определяем длительность текущего сегмента
        int64_t segmentDuration = std::min(remainingTime, phaseTimeRemaining);
        
        // Создаём сегмент
        BufferSegment segment;
        segment.type = toBufferType(currentPhase);
        segment.durationMs = segmentDuration;
        
        // Swap происходит после полного FADE_OUT (перед PAUSE)
        // Это обеспечивает: SOLID → FADE_OUT → swap → PAUSE → FADE_IN → SOLID
        // Если паузы нет, swap происходит в конце FADE_OUT перед FADE_IN
        segment.swapAfterSegment = (currentPhase == SwapPhase::FADE_OUT &&
                                    segmentDuration == phaseTimeRemaining);
        
        plan.segments.push_back(segment);
        plan.totalDurationMs += segmentDuration;
        remainingTime -= segmentDuration;
        phaseTimeRemaining -= segmentDuration;
        
        LOGD_PLANNER("  segment[%d]: type=%d, duration=%lldms, swapAfter=%d",
                     segmentIndex, static_cast<int>(segment.type),
                     (long long)segment.durationMs, segment.swapAfterSegment ? 1 : 0);
        segmentIndex++;
        
        // Переход к следующей фазе
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = phaseDuration(currentPhase, config);
        }
    }
    
    // Сохраняем состояние для следующего пакета
    state.swapPhase = currentPhase;
    state.phaseRemainingMs = phaseTimeRemaining;
    plan.endsMidCycle = (phaseTimeRemaining > 0);
    
    LOGD_PLANNER("  final state: phase=%d, phaseRemaining=%lldms, segments=%zu",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 plan.segments.size());
    
    return plan;
}

inline int64_t BufferPackagePlanner::calculateCycleDuration(const BinauralConfig& config) const {
    if (!config.channelSwapEnabled) {
        return 0;  // Нет цикла без swap
    }
    
    return config.channelSwapIntervalSec * 1000LL +  // SOLID
           config.channelSwapFadeDurationMs +        // FADE_OUT
           config.channelSwapFadeDurationMs;         // FADE_IN
}

inline void BufferPackagePlanner::resetState(GeneratorState& state) {
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    state.cyclePositionMs = 0;
    state.channelsSwapped = false;
}

inline SwapPhase BufferPackagePlanner::nextPhase(SwapPhase current) const {
    switch (current) {
        case SwapPhase::SOLID:    return SwapPhase::FADE_OUT;
        case SwapPhase::FADE_OUT: return SwapPhase::PAUSE;
        case SwapPhase::PAUSE:    return SwapPhase::FADE_IN;
        case SwapPhase::FADE_IN:  return SwapPhase::SOLID;
    }
    return SwapPhase::SOLID;
}

inline int64_t BufferPackagePlanner::phaseDuration(SwapPhase phase, const BinauralConfig& config) const {
    switch (phase) {
        case SwapPhase::SOLID:    return config.channelSwapIntervalSec * 1000LL;
        case SwapPhase::FADE_OUT:
            // Если fade отключён, пропускаем фазы затухания/возрастания
            return config.channelSwapFadeEnabled ? config.channelSwapFadeDurationMs : 0;
        case SwapPhase::PAUSE:
            // Пауза между fade-out и fade-in
            return config.channelSwapPauseDurationMs;
        case SwapPhase::FADE_IN:
            // Если fade отключён, пропускаем фазы затухания/возрастания
            return config.channelSwapFadeEnabled ? config.channelSwapFadeDurationMs : 0;
    }
    return 0;
}

inline BufferType BufferPackagePlanner::toBufferType(SwapPhase phase) const {
    switch (phase) {
        case SwapPhase::SOLID:    return BufferType::SOLID;
        case SwapPhase::FADE_OUT: return BufferType::FADE_OUT;
        case SwapPhase::PAUSE:    return BufferType::PAUSE;
        case SwapPhase::FADE_IN:  return BufferType::FADE_IN;
    }
    return BufferType::SOLID;
}

} // namespace binaural
