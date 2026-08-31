#pragma once

// ============================================================================
// РАСКЛАДКА КАНАЛОВ (какое ухо слышит более высокий тон)
//
// Единственный механизм раскладки — ЗНАК ЧАСТОТЫ БИЕНИЙ, дошедшей до
// генератора:
//
//     left  = carrier − s(t)·beat/2
//     right = carrier + s(t)·beat/2
//
// где s(t) ∈ [−1, +1] — множитель раскладки. Исторически здесь жил второй,
// параллельный механизм — булев флаг GeneratorState::channelsSwapped и
// перестановка выходного буфера после осцилляторов. Он физически тождественен
// «умножить beat на −1», но реализован ПОСЛЕ осцилляторов, из-за чего рвал
// фазу в ухе (щелчок до полной шкалы), требовал ритуала «затухание → тишина
// → нарастание» и не переживал ни рестарт, ни смену пресета. Подробный разбор
// — docs/design_signed_beat_channel_layout.md.
//
// ТРИ ИСТОЧНИКА ЗНАКА складываются явно, как числовые множители к beat:
//   * знак beat в точках пресета   — данные (FrequencyPoint::beatFrequency);
//   * релакс с beatReduction > 100 % — данные (reduceFrequencies);
//   * расписание TIMER/TREND       — множитель s(t) из layoutSignAt().
// Алгебра тотальна и коммутативна: beat_eff(t) = s(t) · beat_кривой(t).
// Никакого XOR булевых флагов.
//
// ГЛАВНОЕ СВОЙСТВО: раскладка — ЧИСТАЯ ФУНКЦИЯ (конфиг, t). Она переживает
// рестарт воспроизведения, смену пресета, смерть сервиса и смену конфига,
// потому что выводится из данных, а не из состояния времени исполнения.
// ============================================================================

#include "Config.h"
#include "Interpolation.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace binaural {

// ============================================================================
// РАСПИСАНИЕ СМЕН РАСКЛАДКИ (привязано к оси суток: позиция 0 = начало суток)
// ============================================================================

// Полуокно оценки производной: Δf = beat(t+h) − beat(t−h) (см. Config.h)
constexpr float kTrendHalfWindowSec = TREND_HALF_WINDOW_SEC;
// Если переходов нет — предельная длина SOLID до переоценки (30 минут)
constexpr int64_t kTrendMaxSolidMs = 1800000LL;

/**
 * ЕДИНАЯ ТОЧКА ИСТИНЫ ТРЕНДА — прирост ЧАСТОТЫ БИЕНИЙ (см. trendBeatDeltaAt
 * в Interpolation.h). Все решения о смене раскладки (список экстремумов,
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
 * Состояние перестановки каналов (true = обратное расположение) для позиции
 * времени суток curvePosSec согласно расписанию смен, привязанному к оси
 * суток, а не к моменту нажатия Play.
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
 * эквивалентность знаковому правилу.
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
        // построен список экстремумов (computeTrendCrossings -> trendBeatDeltaAt).
        // Раньше здесь считалась дельта НЕСУЩЕЙ (бывшая trendCarrierDeltaAt,
        // удалена): пока знаки трендов carrier и beat в начале суток совпадают,
        // разницы нет, но как только они расходятся — чётность инвертирована на
        // все сутки.
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

    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &curve.trendCrossings;
    if (!curve.trendCrossingsValid) {
        computeTrendCrossings(curve, localCrossings);
        crossings = &localCrossings;
    }

    const bool wantPeaks = (points == ChannelSwapTrendPoints::PEAKS);
    const double pos = std::fmod(static_cast<double>(curvePosSec), dayD);
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
        return kTrendMaxSolidMs; // подходящих переходов за сутки нет
    }

    const int64_t dtMs = static_cast<int64_t>(bestRel * 1000.0 / ts);
    const int64_t raw = dtMs - leadMs - swapOffsetMs;
    if (points == ChannelSwapTrendPoints::BOTH) {
        // Без верхнего клампа: редкий BOTH-тренд (пилообразный и т.п.) не должен
        // принудительно разрывать SOLID каждые kTrendMaxSolidMs.
        return std::max(int64_t{0}, raw);
    }
    return std::clamp(raw, int64_t{0}, kTrendMaxSolidMs);
}

// ============================================================================
// МНОЖИТЕЛЬ РАСКЛАДКИ s(t)
// ============================================================================

/**
 * Ближайший запланированный момент смены раскладки T* (сек суток ∈ [0, day)).
 *
 *   TIMER — арифметика по узлам сетки k·intervalSec;
 *   TREND — ближайшее выбранное пересечение trendCrossings (по кругу суток).
 *
 * Нужен рампе layoutSignAt: окно прохода биений через ноль центрируется на T*.
 * Если пересечений нет (TREND без экстремумов) — возвращает 0 (рампа к началу
 * суток; на практике channelSwapStateAt тогда тождественно false и рампа
 * вырождается в ступеньку +1).
 */
inline float nearestSwapTimeSec(const BinauralConfig& cfg, float t) {
    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    const float tt = std::fmod(t, dayF);
    const float pos = (tt < 0.0f) ? tt + dayF : tt;

    if (cfg.channelSwapMode == ChannelSwapMode::TIMER) {
        if (cfg.channelSwapIntervalSec <= 0) return 0.0f;
        const float interval = static_cast<float>(cfg.channelSwapIntervalSec);
        const float k = std::round(pos / interval);
        float T = std::fmod(k * interval, dayF);
        if (T < 0.0f) T += dayF;
        return T;
    }

    // TREND: ближайшее выбранное пересечение по кругу суток.
    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &cfg.curve.trendCrossings;
    if (!cfg.curve.trendCrossingsValid) {
        computeTrendCrossings(cfg.curve, localCrossings);
        crossings = &localCrossings;
    }
    const bool wantPeaks = (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::PEAKS);
    float best = 0.0f;
    float bestD = dayF; // больше любого возможного кругового расстояния
    for (const TrendCrossing& c : *crossings) {
        const bool selected =
            (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH)
                ? true
                : (c.toSwapped == wantPeaks);
        if (!selected) continue;
        float d = c.timeSec - pos;
        if (d > dayF * 0.5f) d -= dayF;
        else if (d < -dayF * 0.5f) d += dayF;
        const float ad = std::abs(d);
        if (ad < bestD) { bestD = ad; best = c.timeSec; }
    }
    return best;
}

/**
 * Множитель раскладки ушей в момент t (сек суток).
 *
 *   s = +1 — прямое расположение (левое ухо = lower, правое = upper);
 *   s = −1 — обратное;
 *   |s| < 1 — ПРОХОД ПУЛЬСАЦИИ ЧЕРЕЗ НОЛЬ: оба уха сходятся в унисон и
 *             расходятся в противоположном направлении.
 *
 * ШАГ 3 МИГРАЦИИ: НЕПРЕРЫВНАЯ рампа вокруг ближайшего T*:
 *
 *     s(t) = s_before · cos(π·u),  u = (t − (T* − W/2)) / W ∈ [0, 1],
 *     W = 2·F + P,  F = channelSwapFadeDurationMs,  P = channelSwapPauseDurationMs.
 *
 * — вне окна |t − T*| ≥ W/2 рампа вырождается в ступеньку channelSwapStateAt;
 * — при fadeEnabled=false (W = 0) рампа исчезает, остаётся чистая ступенька
 *   (поведение шага 2 — бит-в-бит тот же звук при выключенном swap);
 * — в центре окна (t = T*) s = 0: несущая не меняется, громкость постоянна,
 *   фаза непрерывна — пульсация плавно замирает в унисоне и возрождается в
 *   противоположном направлении. Ни щелчка, ни провала громкости.
 *
 * Бывший ритуал FADE_OUT→PAUSE→FADE_IN (и вся фазовая машина планировщика)
 * удалён: раскладка — чистая функция (конфиг, t), а не состояние времени
 * исполнения, поэтому «смена каналов» = beat проходит через ноль.
 *
 * s_before = ступенчатый знак СЛЕВА от T* = channelSwapStateAt(T* − ε).
 * Справа от T* чётность расписания инвертируется (channelSwapStateAt(T* + ε)
 * = −s_before), и cos(π·1) = −1 даёт в точности −s_before — рампа непрерывна
 * со ступенькой с обеих сторон.
 */
inline float layoutSignAt(const BinauralConfig& cfg, float t) {
    if (!cfg.channelSwapEnabled) return 1.0f;
    const float sStep = channelSwapStateAt(cfg, t) ? -1.0f : 1.0f;

    constexpr float kPi = 3.14159265358979323846f;
    const float W = 2.0f * static_cast<float>(cfg.channelSwapFadeDurationMs) / 1000.0f
                 + static_cast<float>(cfg.channelSwapPauseDurationMs) / 1000.0f;
    if (W <= 0.0f) return sStep; // без рампы — ступенька

    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    const float Tstar = nearestSwapTimeSec(cfg, t);
    const float halfW = 0.5f * W;
    float d = t - Tstar;
    if (d > dayF * 0.5f) d -= dayF;
    else if (d < -dayF * 0.5f) d += dayF;
    if (std::abs(d) >= halfW) return sStep; // вне окна — ступенька

    const float u = (d + halfW) / W; // ∈ [0, 1]
    const float sBefore = channelSwapStateAt(cfg, Tstar - 1e-3f) ? -1.0f : 1.0f;
    return sBefore * std::cos(kPi * u);
}

/**
 * Эффективная (слышимая) частота биений: знаковая beat кривой, умноженная на
 * раскладку. Знак результата — и есть фактическая раскладка ушей.
 */
inline float beatEffectiveAt(const BinauralConfig& cfg, float t) {
    const FrequencyTableResult f = cfg.curve.getChannelFrequenciesAt(t);
    return layoutSignAt(cfg, t) * (f.upperFreq - f.lowerFreq);
}

/**
 * ЕДИНАЯ ТОЧКА ИСТИНЫ ЧАСТОТ КАНАЛОВ.
 *
 * Возвращает частоты УШЕЙ (lowerFreq = левое, upperFreq = правое) — с уже
 * применённой раскладкой s(t). Вызывается и аудио-путём, и UI, поэтому звук и
 * индикатор согласованы по построению.
 *
 * Теорема (границы): при |s| ≤ 1 значения c ∓ s·b/2 лежат в отрезке
 * [min(lower,upper), max(lower,upper)]. Доказательство: c − s·b/2 линейно по
 * s, значит на s ∈ [−1,1] пробегает ровно отрезок от c − b/2 = lower до
 * c + b/2 = upper. ∎
 *
 * Следствие: физические пределы (20…2000 Гц, вертикальные границы графика)
 * УЖЕ обеспечены таблицей частот. Рампа знака не может вынести канал за
 * пределы — она только двигает его внутри проверенного коридора. Новых
 * клампов, проверок и тестов на границы не нужно.
 */
inline FrequencyTableResult channelsAt(const BinauralConfig& cfg, float t) {
    const float s = layoutSignAt(cfg, t);
    const FrequencyTableResult f = cfg.curve.getChannelFrequenciesAt(t);
    // s == +1 — раскладка не меняет ничего: возвращаем частоты кривой БЕЗ
    // арифметики. Иначе c ∓ b/2 даёт до 1 ULP расхождения на парах вроде
    // lower=196.00001/upper=203.99997, и «ничего не делающий» множитель
    // тихо портил бы битовую идентичность там, где свап выключен.
    if (s == 1.0f) return f;
    const float c = 0.5f * (f.lowerFreq + f.upperFreq);
    const float b = f.upperFreq - f.lowerFreq;
    return FrequencyTableResult{c - 0.5f * s * b, c + 0.5f * s * b};
}

/**
 * То же, но с заданными несущей и beat — для debug-режима «постоянная
 * частота» (бисекция), где кривая игнорируется, а раскладка сохраняется.
 */
inline FrequencyTableResult channelsAtConstant(const BinauralConfig& cfg, float t,
                                               float carrier, float beat) {
    const float s = layoutSignAt(cfg, t);
    return FrequencyTableResult{carrier - 0.5f * s * beat, carrier + 0.5f * s * beat};
}

} // namespace binaural
