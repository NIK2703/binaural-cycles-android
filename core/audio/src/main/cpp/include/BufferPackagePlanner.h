#pragma once

#include "Config.h"
#include "Interpolation.h"
#include <vector>
#include <algorithm>
#include <cmath>

namespace binaural {

// ============================================================================
// РЕЖИМ ПЕРЕСТАНОВКИ КАНАЛОВ ПО ТЕНДЕНЦИИ ГРАФИКА (ChannelSwapMode::TREND)
//
// Желаемое состояние в момент t определяется знаком производной НЕСУЩЕЙ
// частоты кривой:
//   рост   (Δf > 0) → обычное расположение каналов (swapped = false)
//   спад   (Δf < 0) → обратное расположение           (swapped = true)
//   плато  (Δf == 0) → состояние не меняется
// Мёртвой зоны нет: переход = точная смена знака Δf, т.е. локальный экстремум
// несущей. Все экстремумы предвычисляются один раз при финализации кривой
// (FrequencyCurve::updateCache → buildTrendCrossings) и здесь только
// переиспользуются.
//
// ЦЕНТРОВКА ПРОЦЕДУЫ ПЕРЕСТАНОВКИ: SOLID длится до T* − leadMs, где T* —
// момент смены тенденции, leadMs — длительность грядущего FADE_OUT. Поэтому
// вся процедура [fade-out | прерывание | fade-in] ложится серединой на T*:
// фейд-аут завершается ровно на T*, в T* происходит прерывание потока
// (перестановка каналов), фейд-ин идёт после T*.
// ============================================================================

// Полуокно оценки производной: Δf = carrier(t+h) − carrier(t−h) (см. Config.h)
constexpr float kTrendHalfWindowSec = TREND_HALF_WINDOW_SEC;
// Если переходов нет — предельная длина SOLID до переоценки (30 минут)
constexpr int64_t kTrendMaxSolidMs = 1800000LL;

/**
 * Знакопеременный прирост несущей частоты в точке t (Гц за окно 2*h).
 */
inline float trendCarrierDeltaAt(const FrequencyCurve& curve, float tSec) {
    const FrequencyTableResult plus = curve.getChannelFrequenciesAt(tSec + kTrendHalfWindowSec);
    const FrequencyTableResult minus = curve.getChannelFrequenciesAt(tSec - kTrendHalfWindowSec);
    // carrier = (upper + lower)/2 → разность носителей = половина разности сумм
    return ((plus.upperFreq + plus.lowerFreq) - (minus.upperFreq + minus.lowerFreq)) * 0.5f;
}

/**
 * Желаемое состояние перестановки по знаку производной.
 * Точный ноль (плато) состояние сохраняет — защита от дребезга на пологих
 * участках без сдвига момента переключения.
 */
inline bool trendDesiredSwapped(bool currentlySwapped, float carrierDeltaHz) {
    if (carrierDeltaHz > 0.0f) return false; // рост → прямое расположение
    if (carrierDeltaHz < 0.0f) return true;  // убывание → обратное
    return currentlySwapped;                 // плато → без изменений
}

/**
 * Длительность SOLID-фазы в TREND-режиме: время до ближайшей смены тренда
 * минус lead (центровка процедуры перестановки на момент смены).
 *
 * @param leadMs Длительность FADE_OUT из конфига: SOLID заканчивается на
 *        T* − leadMs, чтобы прерывание потока (конец fade-out) пришлось
 *        ровно на T*. Если T* ближе leadMs — SOLID клампится в 0 (процедура
 *        целиком уходит вправо; длительности фейдов не адаптируются).
 *
 * Оси времени: нули живут на ОСИ КРИВОЙ (сек суток), а длительности фаз —
 * на АУДИО-ОСИ (мс). При timeScale>1 (debug virtual time) кривая обгоняет
 * аудио, поэтому найденное смещение делится на масштаб.
 */
inline int64_t trendSolidDurationMs(
    const FrequencyCurve& curve,
    float curvePosSec,
    bool currentlySwapped,
    float timeScale = 1.0f,
    int64_t leadMs = 0
) {
    constexpr double dayD = static_cast<double>(SECONDS_PER_DAY);
    const float ts = (timeScale > 0.0f) ? timeScale : 1.0f;

    // Рассогласование уже сейчас → немедленный свап (SOLID=0, без выдержек)
    if (trendDesiredSwapped(currentlySwapped,
                            trendCarrierDeltaAt(curve, curvePosSec)) != currentlySwapped) {
        return 0;
    }

    // Кэш нулей; для кривых вне production-пути (тесты строят конфиг вручную
    // и не звали updateCache) — локальный расчёт без мутации curve и без
    // копирования lookup-таблиц.
    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &curve.trendCrossings;
    if (!curve.trendCrossingsValid) {
        computeTrendCrossings(curve, localCrossings);
        crossings = &localCrossings;
    }

    // Нужен ближайший переход, переводящий состояние в !currentlySwapped:
    // пик (toSwapped=true) при swapped=false, впадина (false) при swapped=true.
    const bool needToSwapped = !currentlySwapped;
    const double pos = std::fmod(static_cast<double>(curvePosSec), dayD);
    double bestRel = -1.0;
    for (const TrendCrossing& c : *crossings) {
        if (c.toSwapped != needToSwapped) continue;
        double rel = static_cast<double>(c.timeSec) - pos;
        if (rel <= 0.0) rel += dayD; // wrap через полночь
        if (bestRel < 0.0 || rel < bestRel) bestRel = rel;
    }
    if (bestRel < 0.0) {
        return kTrendMaxSolidMs; // подходящих переходов за сутки нет — переоценка позже
    }

    const int64_t dtMs = static_cast<int64_t>(bestRel * 1000.0 / ts);
    return std::clamp(dtMs - leadMs, int64_t{0}, kTrendMaxSolidMs);
}

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

    // Контекст TREND-режима. curveStartSeconds < 0 (не передан вызывающим)
    // → откат к TIMER-поведению через флаг trendMode.
    const bool trendMode = config.channelSwapMode == ChannelSwapMode::TREND &&
                           curveStartSeconds >= 0.0f;
    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    float trendCurvePosSec = 0.0f;   // позиция кривой внутри плана (сек суток)
    bool projectedSwapped = state.channelsSwapped; // проекция состояния после запланированных swap
    if (trendMode) {
        // Стартовая позиция кривой от движка (иначе скан пойдёт от полуночи)
        trendCurvePosSec = std::fmod(curveStartSeconds, dayF);
        if (trendCurvePosSec < 0.0f) trendCurvePosSec += dayF;
    }

    // Длительность СТАРТУЮЩЕЙ фазы: для SOLID в TREND — динамическая (до смены тренда
    // минус lead фейда — центровка прерывания на момент смены), для остальных и
    // TIMER — константа из конфига.
    auto startPhaseDuration = [&](SwapPhase phase) -> int64_t {
        if (trendMode && phase == SwapPhase::SOLID) {
            // Lead = длительность грядущего FADE_OUT: SOLID завершается на T* − lead,
            // fade-out укладывается ровно перед T*, прерывание потока — на T*.
            const int64_t leadMs = phaseDuration(SwapPhase::FADE_OUT, config);
            return trendSolidDurationMs(config.curve, trendCurvePosSec, projectedSwapped,
                                        timeScale, leadMs);
        }
        return phaseDuration(phase, config);
    };

    // Продвижение TREND-контекста вдоль запланированного аудио
    auto advanceTrendContext = [&](const BufferSegment& segment) {
        if (segment.swapAfterSegment) {
            projectedSwapped = !projectedSwapped;
        }
        if (trendMode) {
            trendCurvePosSec = std::fmod(
                trendCurvePosSec +
                static_cast<float>(segment.durationMs) * 0.001f * timeScale, dayF);
            if (trendCurvePosSec < 0.0f) trendCurvePosSec += dayF;
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

            // Позиция внутри ПОЛНОГО фейда: если фейд разрезан границей пакета,
            // генератор продолжит кривую затухания/нарастания с правильного места
            // вместо рестарта с offset=0 (щелчок + скачок частот).
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
    state.fadeElapsedSamples = 0;
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
