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
// ЦЕНТРОВКА ПРОЦЕДУРЫ ПЕРЕСТАНОВКИ НА ЭКСТРЕМУМ T* (момент смены тенденции):
// SOLID длится до T* − leadMs − P/2, где leadMs — длительность грядущего
// FADE_OUT, P — длительность паузы (channelSwapPauseDurationMs). Фейд-аут
// занимает [T*−P/2−lead, T*−P/2] и завершается ровно на T*−P/2, там же
// происходит прерывание потока (перестановка каналов). Пауза идёт сразу
// после: [T*−P/2, T*+P/2], то есть её СЕРЕДИНА приходится ровно на T*
// (центр экстремума). Фейд-ин идёт после паузы: [T*+P/2, T*+P/2+lead].
// При P = 0 пауза вырождается, и прерывание потока ложится ровно на T*
// (базовое поведение «момент смены = экстремум»).
// ============================================================================

// Полуокно оценки производной: Δf = carrier(t+h) − carrier(t−h) (см. Config.h)
constexpr float kTrendHalfWindowSec = TREND_HALF_WINDOW_SEC;
// Если переходов нет — предельная длина SOLID до переоценки (30 минут)
constexpr int64_t kTrendMaxSolidMs = 1800000LL;

/**
 * ЕДИНАЯ ТОЧКА ИСТИНЫ ТРЕНДА — прирост ЧАСТОТЫ БИЕНИЙ (см. trendBeatDeltaAt
 * в Interpolation.h). Все решения о смене каналов (список экстремумов,
 * поправка чётности в channelSwapStateAt, рестарт в BinauralEngine::setPlaying)
 * обязаны приниматься по этой величине.
 *
 * Раньше здесь жила trendCarrierDeltaAt() — прирост НЕСУЩЕЙ
 * (((up+lo) − (up+lo))/2). Она удалена: знаки трендов carrier и beat могут
 * расходиться, и любое решение по несущей рано или поздно инвертирует чётность
 * каналов относительно списка экстремумов, построенного по биениям.
 */

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
 * Состояние перестановки каналов (true = swapped) для позиции времени суток
 * curvePosSec согласно расписанию смен, привязанному к оси суток
 * (позиция 0 = начало суток), а не к моменту нажатия Play.
 *
 * Результат = чётность событий смены, уже прошедших в полуинтервале
 * [0, curvePosSec):
 *   TREND — выбранные экстремумы (фильтр channelSwapTrendPoints) из
 *           curve.trendCrossings с timeSec < curvePosSec;
 *   TIMER — узлы сетки k*channelSwapIntervalSec: floor(pos / interval).
 * База в начале суток — normal (false), с поправкой на знак тренда в начале
 * суток для BOTH (см. обоснование ниже).
 *
 * Для TREND/BOTH наивная чётность равна знаковому правилу
 * (swapped ⇔ производная < 0) только при восходящем тренде в начале суток.
 * Если в начале суток тренд нисходящий (Δ(0) < 0), наивная чётность ошибается
 * на 1, и планировщик при старте немедленно форсировал бы свап (щелчок сразу
 * после Play). Поправка midnightPhase = (Δ(0) < 0) ровно восстанавливает
 * эквивалентность знаковому правилу и гарантирует нулевую регрессию для BOTH.
 */
inline bool channelSwapStateAt(const BinauralConfig& cfg, float curvePosSec) {
    constexpr double dayD = static_cast<double>(SECONDS_PER_DAY);
    double pos = std::fmod(static_cast<double>(curvePosSec), dayD);
    if (pos < 0.0) pos += dayD;

    if (cfg.channelSwapMode == ChannelSwapMode::TREND) {
        std::vector<TrendCrossing> localCrossings;
        const std::vector<TrendCrossing>* crossings = &cfg.curve.trendCrossings;
        if (!cfg.curve.trendCrossingsValid) {
            computeTrendCrossings(cfg.curve, localCrossings);
            crossings = &localCrossings;
        }
        const bool wantPeaks = (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::PEAKS);
        int64_t count = 0;
        for (const TrendCrossing& c : *crossings) {
            const bool selected =
                (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH)
                    ? true
                    : (c.toSwapped == wantPeaks);
            if (selected && static_cast<double>(c.timeSec) < pos) ++count;
        }
        bool swapped = (count & 1) != 0;

        // Поправка фазы в начале суток (только BOTH).
        //
        // ВАЖНО: дельта берётся по частоте БИЕНИЙ — той же величине, по которой
        // построен список экстремумов (computeTrendCrossings -> trendBeatDeltaAt)
        // и по которой работает BinauralEngine::setPlaying. Раньше здесь
        // считалась дельта НЕСУЩЕЙ (бывшая trendCarrierDeltaAt, удалена): пока
        // знаки трендов carrier и beat в начале суток совпадают, разницы нет,
        // но как только они расходятся — чётность инвертирована на все сутки:
        // планировщик стартует в переставленном состоянии и форсирует лишний
        // свап сразу после Play.
        if (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH) {
            const float delta0 = trendBeatDeltaAt(cfg.curve, 0.0f);
            if (delta0 < 0.0f) swapped = !swapped;
        }
        return swapped;
    }

    // TIMER: узлы сетки k*channelSwapIntervalSec от начала суток.
    if (cfg.channelSwapIntervalSec <= 0) return false;
    const int64_t n = static_cast<int64_t>(
        pos / static_cast<double>(cfg.channelSwapIntervalSec));  // floor, pos ≥ 0
    return (n & 1) != 0;
}

/**
 * Длительность SOLID в TIMER-режиме с привязкой к суточной сетке:
 * время (аудио-мс) до ближайшего узла k*channelSwapIntervalSec от curvePosSec.
 * Позиция делится на timeScale (кривая обгоняет аудио при virtual time).
 * При старте ровно на узле (fmod == 0) — следующий узел через полный интервал.
 * Каждый последующий SOLID пересчитывается от продвинутой позиции кривой,
 * поэтому сетка остаётся привязанной к началу суток на всём горизонте
 * воспроизведения (поглощается длительность fade/pause между узлами).
 */
inline int64_t timerSolidDurationMs(float curvePosSec,
                                    int32_t intervalSec,
                                    float timeScale = 1.0f) {
    if (intervalSec <= 0) return 0;
    const float ts = (timeScale > 0.0f) ? timeScale : 1.0f;
    const double interval = static_cast<double>(intervalSec);
    const double pos = std::fmod(static_cast<double>(curvePosSec),
                                 static_cast<double>(SECONDS_PER_DAY));
    const double distSec = interval - std::fmod(pos, interval); // ∈ (0, I]
    const int64_t dtMs = static_cast<int64_t>(distSec * 1000.0 / ts);
    return std::max<int64_t>(dtMs, 0);
}

/**
 * Длительность SOLID-фазы в TREND-режиме: время до ближайшей смены тренда
 * минус lead и минус swapOffset (центровка процедуры перестановки на T*).
 *
 * @param leadMs Длительность FADE_OUT из конфига: SOLID заканчивается на
 *        T* − leadMs − swapOffset, чтобы прерывание потока (конец fade-out)
 *        пришлось на T* − swapOffset.
 * @param swapOffsetMs Доп. смещение до прерывания относительно T* (АУДИО-мс).
 *        Для центровки ПАУЗЫ на экстремуме передаётся P/2 (половина
 *        channelSwapPauseDurationMs): тогда пауза [T*−P/2, T*+P/2] имеет
 *        середину ровно T*. При P = 0 (swapOffset = 0) прерывание потока
 *        ложится ровно на T* (базовое поведение). Если T* ближе
 *        (leadMs + swapOffsetMs) — SOLID клампится в 0 (процедура целиком
 *        уходит вправо; длительности фейдов не адаптируются).
 *
 * Оси времени: нули живут на ОСИ КРИВОЙ (сек суток), а длительности фаз —
 * на АУДИО-ОСИ (мс). При timeScale>1 (debug virtual time) кривая обгоняет
 * аудио, поэтому найденное смещение dtMs делится на масштаб. swapOffsetMs —
 * это уже аудио-длительность (реальная пауза воспроизведения), делить на
 * timeScale его НЕ нужно.
 */
inline int64_t trendSolidDurationMs(
    const FrequencyCurve& curve,
    float curvePosSec,
    bool currentlySwapped,
    float timeScale = 1.0f,
    int64_t leadMs = 0,
    ChannelSwapTrendPoints points = ChannelSwapTrendPoints::BOTH,
    int64_t swapOffsetMs = 0
) {
    constexpr double dayD = static_cast<double>(SECONDS_PER_DAY);
    const float ts = (timeScale > 0.0f) ? timeScale : 1.0f;

    // Паритет каналов выставляется единожды на входе planPackage (см. одноразовую
    // коррекцию рассогласованного свежего/сброшенного состояния) и поддерживается
    // плановыми свапами (по одному на выбранное пересечение). Здесь parity-логики
    // нет: функция только считает длительность SOLID до ближайшего пересечения.
    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &curve.trendCrossings;
    if (!curve.trendCrossingsValid) {
        computeTrendCrossings(curve, localCrossings);
        crossings = &localCrossings;
    }

    // Ближайший ВЫБРАННЫЙ переход: BOTH — любой экстремум (прежнее поведение,
    // смена на каждом пике и впадине), PEAKS — только пик (toSwapped==true),
    // TROUGHS — только впадина (toSwapped==false).
    const bool wantPeaks = (points == ChannelSwapTrendPoints::PEAKS);
    const double pos = std::fmod(static_cast<double>(curvePosSec), dayD);
    // Паритет не перепроверяется: его сверка на каждом реарме SOLID гоняется с
    // собственной процедурой свапа (счётчик пересечений переворачивается в T*, а
    // фактический свап — в конце FADE_OUT, T*−P/2) и каскадом порождает двойные
    // смены каналов. Сверка вынесена в planPackage (одноразово, на входе).
    double bestRel = -1.0;
    (void)currentlySwapped;

    for (const TrendCrossing& c : *crossings) {

        const bool isSelected = (points == ChannelSwapTrendPoints::BOTH)
            ? true
            : (c.toSwapped == wantPeaks);
        if (!isSelected) continue;
        double rel = static_cast<double>(c.timeSec) - pos;
        if (rel <= 0.0) rel += dayD; // wrap через полночь
        if (bestRel < 0.0 || rel < bestRel) bestRel = rel;
    }
    if (bestRel < 0.0) {
        return kTrendMaxSolidMs; // подходящих переходов за сутки нет — переоценка позже
    }

    const int64_t dtMs = static_cast<int64_t>(bestRel * 1000.0 / ts);
    // SOLID заканчивается на T* − leadMs − swapOffsetMs: прерывание потока
    // (конец fade-out) приходится на T* − swapOffsetMs, а при swapOffsetMs = P/2
    // середина последующей паузы ложится ровно на T* (центр экстремума).
    const int64_t raw = dtMs - leadMs - swapOffsetMs;
    if (points == ChannelSwapTrendPoints::BOTH) {
        // Без верхнего клампа: редкий BOTH-тренд (пилообразный и т.п.) не должен
        // принудительно разрывать SOLID каждые kTrendMaxSolidMs и форсировать
        // периодические свапы там, где реальных пересечений нет.
        return std::max(int64_t{0}, raw);
    }
    return std::clamp(raw, int64_t{0}, kTrendMaxSolidMs);
}

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
