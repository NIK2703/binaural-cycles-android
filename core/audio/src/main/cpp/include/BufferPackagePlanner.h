#pragma once

#include "Config.h"
#include "Interpolation.h"
// Расписание смен раскладки и сам множитель раскладки живут здесь: раскладка
// ушей — ЧИСТАЯ ФУНКЦИЯ (конфиг, t), а не состояние планировщика.
#include "ChannelLayout.h"
#include <vector>

#include <algorithm>
#include <cmath>


namespace binaural {

// Расписание смен раскладки (channelSwapStateAt, trendDesiredSwapped,
// timerSolidDurationMs, trendSolidDurationMs) и множитель s(t) = layoutSignAt
// перенесены в ChannelLayout.h. Они по-прежнему доступны отсюда: этот
// заголовок включается везде, где включался BufferPackagePlanner.h.

/**
 * [SOLID N сек] → [FADE_OUT M сек] → [FADE_IN M сек] → [SOLID N сек] → ...
 *
 * Ключевой принцип: неполный буфер в конце пакета переносится в начало следующего.
 *
 * Пример для 2 минут и интервале 30 сек:
 * Пакет 1: [solid 30s] [fade-out 1s] [fade-in 1s] [solid 30s] [fade-out 1s] [fade-in 1s] [solid 26s]
 * Пакет 2: [solid 4s] [fade-out 1s] [fade-in 1s] [solid 30s] ...
 */

// Логирование планировщика пакетов. ОТКЛЮЧЕНО по умолчанию — включается
// только при debug.binaural.segment_log=1 (как SEGMENT_DEBUG/PKG_BOUNDARY),
// чтобы не засорять logcat в обычной сборке.
#ifdef AUDIO_TEST_BUILD
// При тестировании отключаем логирование
#define LOGD_PLANNER(...) ((void)0)
#elif defined(ANDROID)
#include <android/log.h>
#include <sys/system_properties.h>
inline bool plannerDebugLogEnabled() {
    static const bool enabled = []() {
        char v[PROP_VALUE_MAX] = {0};
        return __system_property_get("debug.binaural.segment_log", v) > 0 && v[0] == '1';
    }();
    return enabled;
}
#define LOG_TAG "BufferPackagePlanner"
#define LOGD_PLANNER(...) do { if (plannerDebugLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
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
     * @param curveStartSeconds Позиция кривой (сек суток) в начале пакета — нужна
     *        для TREND-режима; < 0 → TREND откатывается к TIMER-поведению
     * @param timeScale Множитель скорости кривой относительно аудио-времени
     *        (debug virtual time; в release всегда 1.0)
     * @return План пакета с последовательностью сегментов
     */
    PackagePlan planPackage(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state,
        float curveStartSeconds = -1.0f,
        float timeScale = 1.0f
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
    /**
     * Инициализировать состояние по расписанию для момента свежего старта.
     * Каналы выставляются ровно по channelSwapStateAt(pos) — без немедленного
     * свапа. Фаза = SOLID, phaseRemainingMs = длительность SOLID для позиции.
     */
    void initStateForStart(
        const BinauralConfig& config,
        GeneratorState& state,
        float curveStartSeconds,
        float timeScale = 1.0f
    );

    
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
inline void BufferPackagePlanner::initStateForStart(
    const BinauralConfig& config,
    GeneratorState& state,
    float curveStartSeconds,
    float timeScale) {
    state.channelsSwapped = channelSwapStateAt(config, curveStartSeconds);
    state.swapPhase = SwapPhase::SOLID;
    // Anchor the internal drift-free curve position to the requested start.
    const double dayD0 = static_cast<double>(SECONDS_PER_DAY);
    double p = std::fmod(static_cast<double>(curveStartSeconds), dayD0);
    if (p < 0.0) p += dayD0;
    state.lastNormInput = p;   // avoids spurious seek-detect on first planPackage
    const float ts = (timeScale > 0.0f) ? timeScale : 1.0f;
    if (config.channelSwapMode == ChannelSwapMode::TREND && curveStartSeconds >= 0.0f) {
        const int64_t leadMs = config.channelSwapFadeDurationMs;
        const int64_t swapOffsetMs = config.channelSwapPauseDurationMs / 2;
        state.phaseRemainingMs = trendSolidDurationMs(
            config.curve, curveStartSeconds, state.channelsSwapped,
            ts, leadMs, config.channelSwapTrendPoints, swapOffsetMs);
    } else {
        state.phaseRemainingMs = timerSolidDurationMs(
            curveStartSeconds, config.channelSwapIntervalSec, ts);
    }
}


// ============================================================================
// INLINE РЕАЛИЗАЦИЯ ДЛЯ ПРОИЗВОДИТЕЛЬНОСТИ
// ============================================================================

inline PackagePlan BufferPackagePlanner::planPackage(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state,
    float curveStartSeconds,
    float timeScale
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
    // Без свапа: дробим на 100-мс подсегменты ВСЕГДА (раньше был ОДИН
    // SOLID на весь пакет, затем 1 с). Это: (а) кривая частот следует с шагом
    // 100 мс вместо кусочно-линейных рампов по 10 мин; (б) рантайм идёт тем
    // же путём, что покрыт standalone-тестами генератора.
    if (!config.channelSwapEnabled) {
        constexpr int64_t SOLID_SUBSEGMENT_MS = 100;
        int64_t remaining = packageDurationMs;
        // Точная оценка: план состоит только из подсегментов длительностью <= T
        plan.segments.reserve(static_cast<size_t>(
            (remaining + SOLID_SUBSEGMENT_MS - 1) / SOLID_SUBSEGMENT_MS));
        while (remaining > 0) {
            const int64_t segDur = std::min(remaining, SOLID_SUBSEGMENT_MS);
            BufferSegment segment;
            segment.type = BufferType::SOLID;
            segment.durationMs = segDur;
            segment.swapAfterSegment = false;
            plan.segments.push_back(segment);
            plan.totalDurationMs += segDur;
            remaining -= segDur;
        }
        return plan;
    }

    // Контекст режимов, привязанных к оси кривой (позиция 0 = начало суток).
    // curveStartSeconds < 0 (не передан вызывающим) → откат к базовому
    // поведению (TIMER от старта воспроизведения) для совместимости.
    const bool trendMode = config.channelSwapMode == ChannelSwapMode::TREND &&
                           curveStartSeconds >= 0.0f;
    // TIMER с привязкой к суточной сетке: расписание смен — узлы
    // k*channelSwapIntervalSec от начала суток, а не от момента Play.
    const bool timerGridMode = config.channelSwapMode == ChannelSwapMode::TIMER &&
                               curveStartSeconds >= 0.0f;

    const double dayD = static_cast<double>(SECONDS_PER_DAY);
    // Internal drift-free curve position. The caller passes a float curveStartSeconds
    // (engine m_curveTimeSeconds / test accumulator) that accumulates float rounding
    // drift over long playbacks; maintaining our own double position prevents that
    // drift from making us re-target an already-served crossing (spurious 2nd swap).
    double trendCurvePosSec = state.trendCurvePosSec;
    bool projectedSwapped = state.channelsSwapped; // проекция состояния после запланированных swap
    if (trendMode || timerGridMode) {
        // Normalize caller position. We keep an internal drift-free position and only
        // resync to the caller on a genuine discontinuity (seek): a real seek makes the
        // caller jump by far more than the audio advanced this package, whereas the
        // caller's own float accumulator drifts only gradually (≈ audio length/call).
        // The 2s tolerance absorbs normal per-package timing jitter.
        const double inputPos = std::fmod(static_cast<double>(curveStartSeconds), dayD);
        const double normInput = inputPos < 0.0 ? inputPos + dayD : inputPos;
        const double audioAdvance = static_cast<double>(packageDurationMs) / 1000.0 * timeScale;
        double callerAdvance = normInput - state.lastNormInput;
        if (callerAdvance > dayD * 0.5) callerAdvance -= dayD;
        else if (callerAdvance < -dayD * 0.5) callerAdvance += dayD;
        const bool seek = std::abs(callerAdvance - audioAdvance) > 2.0;
        if (state.justStarted || seek) {
            trendCurvePosSec = normInput;
        }
        state.lastNormInput = normInput;
    }

    // ОДНОРАЗОВАЯ коррекция рассогласованного входа (TREND/BOTH): свежий/сброшенный
    // вход (фаза SOLID ещё не начата, phaseRemainingMs == 0) может нести channelsSwapped,
    // не совпадающий с авторитетным расписанием channelSwapStateAt (легаси-состояние,
    // смена конфига без рестарта, тестовый вход без initStateForStart). Форсируем
    // нулевой SOLID → процедура начнётся с FADE_OUT. Середина потока паритет НЕ
    // перепроверяется — перепроверка каскадно порождает двойные смены каналов.
    bool forceImmediateTrendSwap = false;
    if (trendMode &&
        state.justStarted && state.swapPhase == SwapPhase::SOLID &&
        config.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH &&
        channelSwapStateAt(config, trendCurvePosSec) != projectedSwapped) {
        forceImmediateTrendSwap = true;
    }
    // one-shot: only the first planPackage after reset/init may force a swap
    state.justStarted = false;


    // Длительность СТАРТУЮЩЕЙ фазы: для SOLID в TREND — динамическая (до смены
    // тренда минус lead фейда и половина паузы — центровка прерывания/паузы на
    // момент смены), для остальных и TIMER — константа из конфига.
    auto startPhaseDuration = [&](SwapPhase phase) -> int64_t {
        if (phase == SwapPhase::SOLID) {
            if (trendMode) {
                if (forceImmediateTrendSwap) {   // одноразово: только на входе planPackage
                    forceImmediateTrendSwap = false;
                    return 0;
                }
                const int64_t leadMs = phaseDuration(SwapPhase::FADE_OUT, config);
                const int64_t pauseHalfMs = config.channelSwapPauseDurationMs / 2;
                int64_t solidDur = trendSolidDurationMs(config.curve, trendCurvePosSec, projectedSwapped,
                                            timeScale, leadMs, config.channelSwapTrendPoints,
                                            pauseHalfMs);
                return solidDur;
            }
            if (timerGridMode) {
                // Следующая смена = ближайший узел суточной сетки. Каждый новый
                // SOLID пересчитывается от продвинутой позиции, поэтому сетка
                // остаётся привязанной к началу суток на всём горизонте.
                return timerSolidDurationMs(trendCurvePosSec,
                                            config.channelSwapIntervalSec,
                                            timeScale);
            }
        }
        return phaseDuration(phase, config);
    };

    // Продвижение TREND-контекста вдоль запланированного аудио
    auto advanceTrendContext = [&](const BufferSegment& segment) {
        if (segment.swapAfterSegment) {
            projectedSwapped = !projectedSwapped;
        }
        if (trendMode || timerGridMode) {
            trendCurvePosSec = std::fmod(
                trendCurvePosSec +
                static_cast<double>(segment.durationMs) * 0.001 * static_cast<double>(timeScale), dayD);
            if (trendCurvePosSec < 0.0) trendCurvePosSec += dayD;
        }
    };

    int64_t remainingTime = packageDurationMs;
    SwapPhase currentPhase = state.swapPhase;
    int64_t phaseTimeRemaining = state.phaseRemainingMs;

    LOGD_PLANNER("  initial state: phase=%d, phaseRemaining=%lldms, channelsSwapped=%d",
                 static_cast<int>(currentPhase), (long long)phaseTimeRemaining,
                 state.channelsSwapped ? 1 : 0);

    // Если phaseRemainingMs == 0, начинаем новую фазу
    if (phaseTimeRemaining == 0) {
        phaseTimeRemaining = startPhaseDuration(currentPhase);
        LOGD_PLANNER("  starting new phase: phase=%d, duration=%lldms",
                     static_cast<int>(currentPhase), (long long)phaseTimeRemaining);
    }
    
    // Константа для разбиения SOLID на мелкие подсегменты (T=100 мс).
    // Мгновенная частота внутри кусочка аппроксимируется хордой
    // lookup(концов): ошибка ε ≈ |f″|·T²/8 квадратична по T — при T=1000 мс
    // крутые S-кривые уплощаются до единиц–десятков Гц. T=100 мс снижает ε
    // в 100 раз; стоимость ~10× O(1)-lookup в секунду аудио ничтожна.
    constexpr int64_t SOLID_SUBSEGMENT_MS = 100;

    // Грубая оценка ёмкости: ~T-кусочки SOLID + запас под фазовые сегменты
    // (FADE_OUT/PAUSE/FADE_IN и их разрезания границей пакета)
    plan.segments.reserve(
        static_cast<size_t>(remainingTime / SOLID_SUBSEGMENT_MS + 4));

    int segmentIndex = 0;
    while (remainingTime > 0) {
        // Пропускаем фазы с нулевой длительностью (например, если fade отключён)
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = startPhaseDuration(currentPhase);
            LOGD_PLANNER("  skip to next phase: phase=%d, duration=%lldms",
                         static_cast<int>(currentPhase), (long long)phaseTimeRemaining);
            continue;
        }
        
        // Определяем длительность текущего сегмента
        int64_t segmentDuration = std::min(remainingTime, phaseTimeRemaining);
        
        // Для SOLID фазы разбиваем на подсегменты по SOLID_SUBSEGMENT_MS
        // для точного вычисления частот из таблицы
        if (currentPhase == SwapPhase::SOLID && segmentDuration > SOLID_SUBSEGMENT_MS) {
            // Создаём подсегменты по SOLID_SUBSEGMENT_MS
            int64_t solidRemaining = segmentDuration;
            while (solidRemaining > 0 && remainingTime > 0) {
                int64_t subSegmentDuration = std::min({solidRemaining, remainingTime, SOLID_SUBSEGMENT_MS});

                BufferSegment subSegment;
                subSegment.type = BufferType::SOLID;
                subSegment.durationMs = subSegmentDuration;
                subSegment.swapAfterSegment = false;  // КРИТИЧНО: явно инициализируем false

                plan.segments.push_back(subSegment);
                plan.totalDurationMs += subSegmentDuration;
                advanceTrendContext(subSegment);
                solidRemaining -= subSegmentDuration;
                remainingTime -= subSegmentDuration;
                phaseTimeRemaining -= subSegmentDuration;

                LOGD_PLANNER("  segment[%d]: type=SOLID_SUB, duration=%lldms, swapAfter=0",
                             segmentIndex, (long long)subSegment.durationMs);
                segmentIndex++;
            }
        } else {
            // Для FADE_OUT, PAUSE, FADE_IN создаём один сегмент
            BufferSegment segment;
            segment.type = toBufferType(currentPhase);
            segment.durationMs = segmentDuration;

            // Swap происходит после полного FADE_OUT (перед PAUSE)
            // Это обеспечивает: SOLID → FADE_OUT → swap → PAUSE → FADE_IN → SOLID
            // Если паузы нет, swap происходит в конце FADE_OUT перед FADE_IN
            segment.swapAfterSegment = (currentPhase == SwapPhase::FADE_OUT &&
                                        segmentDuration == phaseTimeRemaining);
            if (currentPhase == SwapPhase::FADE_OUT || currentPhase == SwapPhase::FADE_IN) {
                const int64_t fullPhaseDur = phaseDuration(currentPhase, config);
                segment.fadeTotalMs  = fullPhaseDur;
                segment.fadeOffsetMs = fullPhaseDur - phaseTimeRemaining; // уже сгенерировано до этого сегмента
            }

            plan.segments.push_back(segment);
            plan.totalDurationMs += segmentDuration;
            advanceTrendContext(segment);
            remainingTime -= segmentDuration;
            phaseTimeRemaining -= segmentDuration;

            LOGD_PLANNER("  segment[%d]: type=%d, duration=%lldms, swapAfter=%d",
                         segmentIndex, static_cast<int>(segment.type),
                         (long long)segment.durationMs, segment.swapAfterSegment ? 1 : 0);
            segmentIndex++;
        }

        // Переход к следующей фазе
        if (phaseTimeRemaining == 0) {
            currentPhase = nextPhase(currentPhase);
            phaseTimeRemaining = startPhaseDuration(currentPhase);
        }
    }
    
    // Сохраняем состояние для следующего пакета
    state.swapPhase = currentPhase;
    state.phaseRemainingMs = phaseTimeRemaining;
    state.trendCurvePosSec = trendCurvePosSec;   // persist drift-free position
    plan.endsMidCycle = (phaseTimeRemaining > 0);
    
    
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
    state.justStarted = true;
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
        case SwapPhase::FADE_IN: {
            // Гарантированный рамп от щелчка: при выключенном fade фаза не должна
            // «исчезать» (иначе FADE_OUT сегмент не создаётся и swap не происходит
            // вовсе, а переходы SOLID→PAUSE→SOLID режутся жёстко).
            constexpr int64_t MIN_SWAP_FADE_MS = 15;
            if (!config.channelSwapFadeEnabled) {
                return MIN_SWAP_FADE_MS;
            }
            return std::max(config.channelSwapFadeDurationMs, MIN_SWAP_FADE_MS);
        }
        case SwapPhase::PAUSE:
            // Пауза между fade-out и fade-in
            return config.channelSwapPauseDurationMs;
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
