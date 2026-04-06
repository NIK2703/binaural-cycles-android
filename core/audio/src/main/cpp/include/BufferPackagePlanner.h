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
 * Поддерживает два режима:
 * - INTERVAL: фиксированный интервал между перестановками
 * - TENDENCY: перестановка в точках экстремума графика частот
 *
 * Ключевой принцип: неполный буфер в конце пакета переносится в начало следующего.
 *
 * Пример для 2 минут и интервале 30 сек:
 * Пакет 1: [solid 30s] [fade-out 1s] [fade-in 1s] [solid 30s] [fade-out 1s] [fade-in 1s] [solid 26s]
 * Пакет 2: [solid 4s] [fade-out 1s] [fade-in 1s] [solid 30s] ...
 */

// Логирование только в DEBUG сборках
#ifdef AUDIO_TEST_BUILD
// При тестировании отключаем логирование
#define LOGD_PLANNER(...) ((void)0)
#elif defined(ANDROID)
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
     * @param currentTimeMs Текущее время в мс от начала суток (для режима TENDENCY)
     * @return План пакета с последовательностью сегментов
     */
    PackagePlan planPackage(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state,
        int64_t currentTimeMs = 0
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
     * @param config Конфигурация для инициализации режима TENDENCY
     */
    void resetState(GeneratorState& state, const BinauralConfig& config);
    
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
    
    /**
     * Найти следующую точку экстремума для режима TENDENCY
     * @param currentTimeMs Текущее время в мс от начала суток
     * @param curve Кривая частот с точками экстремума
     * @param state Состояние генератора
     * @return Время следующего экстремума в мс, или -1 если не найден
     */
    int64_t findNextSwapPoint(
        int64_t currentTimeMs,
        const FrequencyCurve& curve,
        GeneratorState& state
    ) const;
    
    /**
     * Планирование для режима INTERVAL
     */
    PackagePlan planPackageInterval(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state
    );
    
    /**
     * Планирование для режима TENDENCY
     */
    PackagePlan planPackageTendency(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state,
        int64_t currentTimeMs
    );
};

// ============================================================================
// INLINE РЕАЛИЗАЦИЯ ДЛЯ ПРОИЗВОДИТЕЛЬНОСТИ
// ============================================================================

inline PackagePlan BufferPackagePlanner::planPackage(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state,
    int64_t currentTimeMs
) {
    // Без swap: один сплошной буфер на весь пакет
    if (!config.channelSwapEnabled) {
        PackagePlan plan;
        plan.totalDurationMs = packageDurationMs;
        plan.endsMidCycle = false;
        
        BufferSegment segment;
        segment.type = BufferType::SOLID;
        segment.durationMs = packageDurationMs;
        segment.swapAfterSegment = false;
        plan.segments.push_back(segment);
        return plan;
    }
    
    // Выбор режима планирования
    switch (config.channelSwapMode) {
        case SwapMode::TENDENCY:
            return planPackageTendency(packageDurationMs, config, state, currentTimeMs);
        case SwapMode::INTERVAL:
        default:
            return planPackageInterval(packageDurationMs, config, state);
    }
}

inline PackagePlan BufferPackagePlanner::planPackageInterval(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state
) {
    PackagePlan plan;
    plan.totalDurationMs = 0;
    plan.endsMidCycle = false;
    
    LOGD_PLANNER("planPackageInterval: duration=%lldms, fadeEnabled=%d, fadeDuration=%lldms",
                 (long long)packageDurationMs,
                 config.channelSwapFadeEnabled ? 1 : 0,
                 (long long)config.channelSwapFadeDurationMs);
    
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
    
    // Константа для разбиения SOLID на мелкие сегменты (1 сек)
    constexpr int64_t SOLID_SUBSEGMENT_MS = 1000;
    
    int segmentIndex = 0;
    while (remainingTime > 0) {
        // Пропускаем фазы с нулевой длительностью
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = phaseDuration(currentPhase, config);
            LOGD_PLANNER("  skip to next phase: phase=%d, duration=%lldms",
                         static_cast<int>(currentPhase), (long long)phaseTimeRemaining);
            continue;
        }
        
        int64_t segmentDuration = std::min(remainingTime, phaseTimeRemaining);
        
        // Для SOLID фазы разбиваем на подсегменты по 1 сек
        if (currentPhase == SwapPhase::SOLID && segmentDuration > SOLID_SUBSEGMENT_MS) {
            int64_t solidRemaining = segmentDuration;
            while (solidRemaining > 0 && remainingTime > 0) {
                int64_t subSegmentDuration = std::min({solidRemaining, remainingTime, SOLID_SUBSEGMENT_MS});
                
                BufferSegment subSegment;
                subSegment.type = BufferType::SOLID;
                subSegment.durationMs = subSegmentDuration;
                subSegment.swapAfterSegment = false;
                
                plan.segments.push_back(subSegment);
                plan.totalDurationMs += subSegmentDuration;
                solidRemaining -= subSegmentDuration;
                remainingTime -= subSegmentDuration;
                phaseTimeRemaining -= subSegmentDuration;
                
                LOGD_PLANNER("  segment[%d]: type=SOLID_SUB, duration=%lldms, swapAfter=0",
                             segmentIndex, (long long)subSegment.durationMs);
                segmentIndex++;
            }
        } else {
            BufferSegment segment;
            segment.type = toBufferType(currentPhase);
            segment.durationMs = segmentDuration;
            
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
        }
        
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = phaseDuration(currentPhase, config);
        }
    }
    
    // Если фаза полностью завершена, переходим к следующей
    if (phaseTimeRemaining == 0) {
        currentPhase = nextPhase(currentPhase);
    }
    
    state.swapPhase = currentPhase;
    state.phaseRemainingMs = phaseTimeRemaining;
    plan.endsMidCycle = (phaseTimeRemaining > 0);
    
    LOGD_PLANNER("  final state: phase=%d, phaseRemaining=%lldms, segments=%zu",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 plan.segments.size());
    
    return plan;
}

inline PackagePlan BufferPackagePlanner::planPackageTendency(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state,
    int64_t currentTimeMs
) {
    PackagePlan plan;
    plan.totalDurationMs = 0;
    plan.endsMidCycle = false;
    
    const auto& curve = config.curve;
    
    // Если нет точек экстремума, работаем как SOLID
    if (curve.swapPointsCount == 0) {
        BufferSegment segment;
        segment.type = BufferType::SOLID;
        segment.durationMs = packageDurationMs;
        segment.swapAfterSegment = false;
        plan.segments.push_back(segment);
        plan.totalDurationMs = packageDurationMs;
        return plan;
    }
    
    LOGD_PLANNER("planPackageTendency: duration=%lldms, currentTime=%lldms, swapPoints=%d",
                 (long long)packageDurationMs, (long long)currentTimeMs, curve.swapPointsCount);
    
    int64_t remainingTime = packageDurationMs;
    SwapPhase currentPhase = state.swapPhase;
    int64_t phaseTimeRemaining = state.phaseRemainingMs;
    
    // Вычисляем время до следующей точки swap
    int64_t nextSwapPoint = state.nextSwapPointMs;
    
    // Если nextSwapPoint == 0, нужно найти следующую точку
    if (nextSwapPoint == 0 || state.currentSwapPointIndex == 0) {
        nextSwapPoint = findNextSwapPoint(currentTimeMs, curve, state);
    }
    
    LOGD_PLANNER("  initial: phase=%d, phaseRemaining=%lldms, nextSwap=%lldms",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 (long long)nextSwapPoint);
    
    // Если phaseRemainingMs == 0, начинаем новую фазу
    if (phaseTimeRemaining == 0) {
        if (currentPhase == SwapPhase::SOLID) {
            // В режиме TENDENCY длительность SOLID = время до следующего экстремума
            constexpr int64_t DAY_MS = 86400LL * 1000LL;
            int64_t timeToSwap = nextSwapPoint - currentTimeMs;
            if (timeToSwap < 0) {
                timeToSwap += DAY_MS;
            }
            phaseTimeRemaining = timeToSwap;
            LOGD_PLANNER("  TENDENCY: SOLID duration = %lldms (time to next extremum)", (long long)phaseTimeRemaining);
        } else {
            phaseTimeRemaining = phaseDuration(currentPhase, config);
        }
    }
    
    constexpr int64_t SOLID_SUBSEGMENT_MS = 1000;
    int segmentIndex = 0;
    
    while (remainingTime > 0) {
        // Пропускаем фазы с нулевой длительностью
        if (phaseTimeRemaining == 0) {
            SwapPhase prevPhase = currentPhase;
            currentPhase = nextPhase(currentPhase);
            
            // Для SOLID в режиме TENDENCY вычисляем время до следующего экстремума
            if (currentPhase == SwapPhase::SOLID) {
                // После FADE_IN находим следующую точку экстремума
                if (prevPhase == SwapPhase::FADE_IN) {
                    nextSwapPoint = findNextSwapPoint(currentTimeMs, curve, state);
                }
                // Длительность SOLID = время до следующего экстремума
                constexpr int64_t DAY_MS = 86400LL * 1000LL;
                int64_t timeToSwap = nextSwapPoint - currentTimeMs;
                if (timeToSwap < 0) {
                    timeToSwap += DAY_MS;
                }
                phaseTimeRemaining = timeToSwap;
                LOGD_PLANNER("  TENDENCY(skip): SOLID duration = %lldms", (long long)phaseTimeRemaining);
            } else {
                phaseTimeRemaining = phaseDuration(currentPhase, config);
            }
            continue;
        }
        
        int64_t segmentDuration = std::min(remainingTime, phaseTimeRemaining);
        
        // Для SOLID фазы проверяем, не достигнем ли точки экстремума
        if (currentPhase == SwapPhase::SOLID) {
            // Время до следующего экстремума
            int64_t timeToSwap = nextSwapPoint - currentTimeMs;
            
            // Нормализация времени (переход через полночь)
            constexpr int64_t DAY_MS = 86400LL * 1000LL;
            if (timeToSwap < 0) {
                timeToSwap += DAY_MS;
            }
            
            // Если экстремум ближе чем длительность сегмента
            if (timeToSwap > 0 && timeToSwap < segmentDuration) {
                // Обрезаем сегмент до точки экстремума
                segmentDuration = timeToSwap;
            }
            
            // Разбиваем SOLID на подсегменты по 1 сек
            if (segmentDuration > SOLID_SUBSEGMENT_MS) {
                int64_t solidRemaining = segmentDuration;
                while (solidRemaining > 0 && remainingTime > 0) {
                    int64_t subSegmentDuration = std::min({solidRemaining, remainingTime, SOLID_SUBSEGMENT_MS});
                    
                    BufferSegment subSegment;
                    subSegment.type = BufferType::SOLID;
                    subSegment.durationMs = subSegmentDuration;
                    subSegment.swapAfterSegment = false;
                    
                    plan.segments.push_back(subSegment);
                    plan.totalDurationMs += subSegmentDuration;
                    solidRemaining -= subSegmentDuration;
                    remainingTime -= subSegmentDuration;
                    phaseTimeRemaining -= subSegmentDuration;
                    currentTimeMs = (currentTimeMs + subSegmentDuration) % (DAY_MS);
                    segmentIndex++;
                }
            } else {
                BufferSegment segment;
                segment.type = BufferType::SOLID;
                segment.durationMs = segmentDuration;
                segment.swapAfterSegment = false;
                
                plan.segments.push_back(segment);
                plan.totalDurationMs += segmentDuration;
                remainingTime -= segmentDuration;
                phaseTimeRemaining -= segmentDuration;
                currentTimeMs = (currentTimeMs + segmentDuration) % (86400LL * 1000LL);
                segmentIndex++;
            }
            
            // Если достигли или прошли точку экстремума, переходим к FADE_OUT
            if (phaseTimeRemaining > 0 && currentTimeMs >= nextSwapPoint) {
                // Завершаем SOLID и начинаем fade
                currentPhase = SwapPhase::FADE_OUT;
                phaseTimeRemaining = phaseDuration(SwapPhase::FADE_OUT, config);
                LOGD_PLANNER("  TENDENCY: reached swap point, starting FADE_OUT duration=%lldms", 
                             (long long)phaseTimeRemaining);
            }
        } else {
            // FADE_OUT, PAUSE, FADE_IN
            BufferSegment segment;
            segment.type = toBufferType(currentPhase);
            segment.durationMs = segmentDuration;
            
            segment.swapAfterSegment = (currentPhase == SwapPhase::FADE_OUT &&
                                        segmentDuration == phaseTimeRemaining);
            
            plan.segments.push_back(segment);
            plan.totalDurationMs += segmentDuration;
            remainingTime -= segmentDuration;
            phaseTimeRemaining -= segmentDuration;
            currentTimeMs = (currentTimeMs + segmentDuration) % (86400LL * 1000LL);
            
            LOGD_PLANNER("  segment[%d]: type=%d, duration=%lldms, swapAfter=%d",
                         segmentIndex, static_cast<int>(segment.type),
                         (long long)segment.durationMs, segment.swapAfterSegment ? 1 : 0);
            segmentIndex++;
        }
        
        // Переход к следующей фазе
        if (phaseTimeRemaining == 0) {
            SwapPhase prevPhase = currentPhase;
            currentPhase = nextPhase(currentPhase);
            
            // Для SOLID в режиме TENDENCY вычисляем время до следующего экстремума
            if (currentPhase == SwapPhase::SOLID) {
                // После FADE_IN находим следующую точку экстремума
                if (prevPhase == SwapPhase::FADE_IN) {
                    nextSwapPoint = findNextSwapPoint(currentTimeMs, curve, state);
                }
                // Длительность SOLID = время до следующего экстремума
                constexpr int64_t DAY_MS = 86400LL * 1000LL;
                int64_t timeToSwap = nextSwapPoint - currentTimeMs;
                if (timeToSwap < 0) {
                    timeToSwap += DAY_MS;
                }
                phaseTimeRemaining = timeToSwap;
                LOGD_PLANNER("  TENDENCY: next SOLID duration = %lldms", (long long)phaseTimeRemaining);
            } else {
                phaseTimeRemaining = phaseDuration(currentPhase, config);
            }
        }
    }
    
    // Если фаза полностью завершена, переходим к следующей
    if (phaseTimeRemaining == 0) {
        currentPhase = nextPhase(currentPhase);
    }
    
    state.swapPhase = currentPhase;
    state.phaseRemainingMs = phaseTimeRemaining;
    state.nextSwapPointMs = nextSwapPoint;
    plan.endsMidCycle = (phaseTimeRemaining > 0);
    
    LOGD_PLANNER("  final state: phase=%d, phaseRemaining=%lldms, segments=%zu",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 plan.segments.size());
    
    return plan;
}

inline int64_t BufferPackagePlanner::findNextSwapPoint(
    int64_t currentTimeMs,
    const FrequencyCurve& curve,
    GeneratorState& state
) const {
    if (curve.swapPointsCount == 0) {
        return -1;
    }
    
    constexpr int64_t DAY_MS = 86400LL * 1000LL;
    
    // Бинарный поиск ближайшей точки >= currentTimeMs
    int left = 0;
    int right = curve.swapPointsCount - 1;
    int result = -1;
    
    while (left <= right) {
        int mid = left + (right - left) / 2;
        if (curve.swapPointsMs[mid] >= currentTimeMs) {
            result = mid;
            right = mid - 1;
        } else {
            left = mid + 1;
        }
    }
    
    // Если не нашли точку >= currentTimeMs, берём первую (переход через полночь)
    if (result == -1) {
        result = 0;
    }
    
    state.currentSwapPointIndex = result;
    return curve.swapPointsMs[result];
}

inline int64_t BufferPackagePlanner::calculateCycleDuration(const BinauralConfig& config) const {
    if (!config.channelSwapEnabled) {
        return 0;
    }
    
    return config.channelSwapIntervalSec * 1000LL +
           config.channelSwapFadeDurationMs +
           config.channelSwapFadeDurationMs;
}

inline void BufferPackagePlanner::resetState(GeneratorState& state, const BinauralConfig& config) {
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    state.cyclePositionMs = 0;
    state.channelsSwapped = false;
    
    // Для режима TENDENCY инициализируем индекс точки экстремума
    if (config.channelSwapMode == SwapMode::TENDENCY) {
        state.currentSwapPointIndex = 0;
        state.nextSwapPointMs = 0;
        state.currentTendency = config.curve.initialTendency;
    }
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
        case SwapPhase::SOLID:
            // В режиме TENDENCY длительность SOLID определяется до следующего экстремума
            // В режиме INTERVAL используем фиксированный интервал
            return config.channelSwapIntervalSec * 1000LL;
        case SwapPhase::FADE_OUT:
            return config.channelSwapFadeEnabled ? config.channelSwapFadeDurationMs : 0;
        case SwapPhase::PAUSE:
            return config.channelSwapPauseDurationMs;
        case SwapPhase::FADE_IN:
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