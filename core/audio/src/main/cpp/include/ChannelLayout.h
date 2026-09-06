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
//
// ПРОЦЕДУРА СМЕНЫ (как она звучит): знак — СТУПЕНЬКА s(t) ∈ {−1, +1}, а не
// непрерывная рампа. Плавность даёт не частота, а ОГИБАЮЩАЯ ГРОМКОСТИ g(t):
//
//     [затухание F] → [тишина P] → [нарастание F],  центр — в T*,
//
// ровно тот ритуал FADE_OUT→PAUSE→FADE_IN, что был до миграции на знаковый
// beat (см. docs/design_signed_beat_channel_layout.md §3.4). Рампа « beat
// проходит через ноль » слушалась как быстрый перепад частот каналов на
// противоположные и была отброшена: частота обязана следовать графику, а
// переход между раскладками — это тишина, а не глиссандо.
//
// Огибающая — тоже чистая функция (конфиг, t), поэтому процедура переживает
// рестарт и смену конфига ровно как сама раскладка. В момент переворота знака
// g(T*) = 0, поэтому ступенька частот не даёт щелчка. Генератор дополнительно
// режет подсегмент по T*, чтобы ступенька попала на границу сэмпла.
// ============================================================================

#include "Config.h"
#include "Interpolation.h"

#include <algorithm>
#include <cmath>
#include <limits>
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
//
// Позиция — float (ось суток 0..86400 с). Внутренняя арифметика здесь тоже
// float: для наших диапазонов (pos < 86400, interval ≤ 3600) точности float
// хватает с запасом. Валидность процедуры смены (nearestSwapProcedure, ред. 5)
// определяется КОНСТРУКТИВНО по самому расписанию, без соседних float /
// nextafter / окрестностных зондов.
inline bool channelSwapStateAt(const BinauralConfig& cfg, float curvePosSec) {
    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    float pos = std::fmod(curvePosSec, dayF);
    if (pos < 0.0f) pos += dayF;

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
            if (selected && c.timeSec < pos) ++count;
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
        pos / static_cast<float>(cfg.channelSwapIntervalSec));  // floor, pos ≥ 0
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
    const float interval = static_cast<float>(intervalSec);
    const float pos = std::fmod(curvePosSec,
                                static_cast<float>(SECONDS_PER_DAY));
    const float distSec = interval - std::fmod(pos, interval); // ∈ (0, I]
    const int64_t dtMs = static_cast<int64_t>(distSec * 1000.0f / ts);
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
    constexpr float dayF2 = static_cast<float>(SECONDS_PER_DAY);
    const float ts = (timeScale > 0.0f) ? timeScale : 1.0f;

    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &curve.trendCrossings;
    if (!curve.trendCrossingsValid) {
        computeTrendCrossings(curve, localCrossings);
        crossings = &localCrossings;
    }

    const bool wantPeaks = (points == ChannelSwapTrendPoints::PEAKS);
    const float pos = std::fmod(curvePosSec, dayF2);
    float bestRel = -1.0f;
    (void)currentlySwapped;

    for (const TrendCrossing& c : *crossings) {
        const bool isSelected = (points == ChannelSwapTrendPoints::BOTH)
            ? true
            : (c.toSwapped == wantPeaks);
        if (!isSelected) continue;
        float rel = c.timeSec - pos;
        if (rel <= 0.0f) rel += dayF2; // wrap через полночь
        if (bestRel < 0.0f || rel < bestRel) bestRel = rel;
    }
    if (bestRel < 0.0f) {
        return kTrendMaxSolidMs; // подходящих переходов за сутки нет
    }

    const int64_t dtMs = static_cast<int64_t>(bestRel * 1000.0f / ts);
    const int64_t raw = dtMs - leadMs - swapOffsetMs;
    if (points == ChannelSwapTrendPoints::BOTH) {
        // Без верхнего клампа: редкий BOTH-тренд (пилообразный и т.п.) не должен
        // принудительно разрывать SOLID каждые kTrendMaxSolidMs.
        return std::max(int64_t{0}, raw);
    }
    return std::clamp(raw, int64_t{0}, kTrendMaxSolidMs);
}

// ============================================================================
// РАСКЛАДКА s(t) И ОГИБАЮЩАЯ ПРОЦЕДУРЫ g(t)
// ============================================================================

/**
 * Ближайший запланированный момент смены раскладки T* (сек суток ∈ [0, day)).
 *
 *   TIMER — арифметика по узлам сетки k·intervalSec;
 *   TREND — ближайшее выбранное пересечение trendCrossings (по кругу суток).
 *
 * На T* центрируется процедура (затухание → тишина → нарастание) и ровно в T*
 * знак раскладки переворачивается. Если пересечений нет (TREND без
 * экстремумов) — возвращает 0; процедуры там тоже нет (см. nearestSwapProcedure:
 * знак не меняется ⇒ затухать незачем).
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
 * Круговое смещение от t0 к t на оси суток: результат в [−day/2, day/2].
 */
inline float circularDeltaSec(float t, float t0) {
    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    float d = t - t0;
    if (d > dayF * 0.5f) d -= dayF;
    else if (d < -dayF * 0.5f) d += dayF;
    return d;
}

/**
 * Процедура смены раскладки: чем именно сопровождается переворот знака.
 *
 * valid = false — переворота нет (swap выключен либо расписание в этой точке
 *                знак не меняет): огибающая тождественно 1, резать нечего;
 * fadeSec = 0   — переворот есть, но затухание выключено (ступенька частот
 *                на полной громкости — осознанный выбор «без плавности»).
 */
struct SwapProcedure {
    bool  valid    = false;
    float tStarSec = 0.0f;  // мгновение переворота знака, центр процедуры
    float fadeSec  = 0.0f;  // F — длительность затухания и нарастания
    float pauseSec = 0.0f;  // P — тишина между ними
};

/**
 * Минимальный зазор между СОСЕДНИМИ сменами раскладки (сек).
 *
 * Процедура не может быть длиннее зазора: иначе окна соседних смен
 * перекрываются, огибающая не успевает вернуться к 1, а в пределе (P больше
 * зазора) звук замолкает навсегда. TIMER: зазор = интервал сетки. TREND:
 * минимальное круговое расстояние между соседними выбранными экстремумами;
 * если выбранных пересечений меньше двух — ограничения нет (возвращаем сутки).
 */
inline float swapGapSec(const BinauralConfig& cfg) {
    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    if (cfg.channelSwapMode == ChannelSwapMode::TIMER) {
        return (cfg.channelSwapIntervalSec > 0)
            ? static_cast<float>(cfg.channelSwapIntervalSec)
            : dayF;
    }
    std::vector<TrendCrossing> localCrossings;
    const std::vector<TrendCrossing>* crossings = &cfg.curve.trendCrossings;
    if (!cfg.curve.trendCrossingsValid) {
        computeTrendCrossings(cfg.curve, localCrossings);
        crossings = &localCrossings;
    }
    const bool wantPeaks = (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::PEAKS);
    std::vector<float> times;
    for (const TrendCrossing& c : *crossings) {
        const bool selected =
            (cfg.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH)
                ? true
                : (c.toSwapped == wantPeaks);
        if (selected) times.push_back(c.timeSec);
    }
    if (times.size() < 2) return dayF;
    std::sort(times.begin(), times.end());
    float gap = dayF;
    for (size_t i = 0; i + 1 < times.size(); ++i) {
        gap = std::min(gap, times[i + 1] - times[i]);
    }
    // Смычка через полночь: последний узел суток и первый следующего дня.
    gap = std::min(gap, dayF - (times.back() - times.front()));
    return (gap > 0.0f) ? gap : dayF;
}

/**
 * Число ВЫБРАННЫХ переходов раскладки на кривой (TREND-режим).
 *
 * Используется в nearestSwapProcedure (ред. 5) для КОНСТРУКТИВНОЙ валидности:
 * ступенька в TREND есть ⇔ выбран хотя бы один переход. Это тот же самый
 * отбор selected, что и в channelSwapStateAt/nearestSwapTimeSec — единая точка
 * истины отбора, без дублирования логики.
 */
inline int64_t numSelectedCrossings(const BinauralConfig& cfg) {
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
        if (selected) ++count;
    }
    return count;
}

/**
 * Процедура смены раскладки, ближайшая к моменту t (ЧИСТАЯ ФУНКЦИЯ конфига).
 *
 * T* берётся из nearestSwapTimeSec, а ВАЛИДНОСТЬ определяется КОНСТРУКТИВНО —
 * без окрестностных зондов (nextafter/nextafterf/±inf), которые под -ffast-math
 * на устройстве (arm64 NDK clang) схлопываются и гасят процедуру на все сутки
 * (ред. 4, см. docs/analysis_swap_crossfade_missing.md).
 *
 *   TIMER — valid ⇔ channelSwapIntervalSec > 0.
 *           Чётность n = floor(pos / I) меняется на каждом узле сетки k·I по
 *           построению: при переходе pos через k·I значение n скачком меняется
 *           на 1, значит channelSwapStateAt меняет знак в каждом узле. Поэтому
 *           ступенька есть ровно там, где интервал положителен. Без ULP/зондов.
 *
 *   TREND — valid ⇔ число выбранных переходов ≥ 1 (numSelectedCrossings).
 *           Счётчик инкрементируется на каждом выбранном экстремуме, значит
 *           channelSwapStateAt меняет знак в каждом выбранном переходе. Если
 *           выбранных переходов нет (пологая кривая / не тот фильтр) — знак
 *           не меняется ни в одной точке суток, и затухать незачем (без
 *           окрестностного зонда это раньше ломалось на полуночном нуле).
 *
 * Это ЭКВИВАЛЕНТНО старой проверке «знак меняется в окрестности T*»
 * (channelSwapStateAt(T*−ε) ≠ channelSwapStateAt(T*+ε)), но ВЫВЕДЕНО из самой
 * КОНСТРУКЦИИ расписания, а не из квантирования float. Чистый float, без
 * nextafter/inf/double/ULP — доказуемо на бумаге и не зависит от флагов
 * оптимизации.
 *
 * Длительности: F = channelSwapFadeDurationMs, P = channelSwapPauseDurationMs
 * (по отдельности, а не свёрнутые в W = 2F+P: это разные фазы). При
 * fadeEnabled=false процедура вырождается в голую ступеньку (F = P = 0).
 * Если 2F + P превышает зазор между сменами — обе длительности пропорционально
 * сжимаются, чтобы огибающая успевала вернуться к 1 (см. swapGapSec).
 */
inline SwapProcedure nearestSwapProcedure(const BinauralConfig& cfg, float t) {
    SwapProcedure p;
    if (!cfg.channelSwapEnabled) return p;

    const float Tstar = nearestSwapTimeSec(cfg, t);

    // КОНСТРУКТИВНАЯ ВАЛИДНОСТЬ (ред. 5): ступенька есть ⇔ расписание по
    // построению меняет знак в этой точке. Без соседних float / nextafter / inf.
    bool hasFlip = false;
    if (cfg.channelSwapMode == ChannelSwapMode::TIMER) {
        hasFlip = (cfg.channelSwapIntervalSec > 0);
    } else {  // TREND (и BOTH — тот же трендовый путь отбора)
        hasFlip = (numSelectedCrossings(cfg) >= 1);
    }
    if (!hasFlip) return p;  // valid=false, Tstar=0

    p.valid = true;
    p.tStarSec = Tstar;

    if (!cfg.channelSwapFadeEnabled) return p; // голая ступенька

    float F = static_cast<float>(cfg.channelSwapFadeDurationMs) / 1000.0f;
    float P = static_cast<float>(cfg.channelSwapPauseDurationMs) / 1000.0f;
    if (F < 0.0f) F = 0.0f;
    if (P < 0.0f) P = 0.0f;
    const float W = 2.0f * F + P;
    if (W > 0.0f) {
        const float gap = swapGapSec(cfg);
        if (W > gap) {
            const float k = gap / W;
            F *= k;
            P *= k;
        }
    }
    p.fadeSec = F;
    p.pauseSec = P;
    return p;
}

/**
 * Множитель раскладки ушей в момент t (сек суток).
 *
 *   s = +1 — прямое расположение (левое ухо = lower, правое = upper);
 *   s = −1 — обратное.
 *
 * СТУПЕНЬКА, а не рампа: частота каждого уха обязана следовать графику, и
 * «плавность» перехода обеспечивает НЕ частота, а огибающая громкости
 * layoutGainAt() — затухание → тишина → нарастание вокруг T*. В T* огибающая
 * равна нулю, поэтому ступенька не слышна как щелчок.
 *
 * Исторически здесь была непрерывная рампа s(t) = s_до·cos(π·u) («биения
 * проходят через ноль»). На слух это быстрый перепад частот каналов на
 * противоположные (частоты скользят навстречу и расходятся), к тому же
 * расходясь с графиком на всём окне W. Рампа удалена, ритуал вернулся.
 */
inline float layoutSignAt(const BinauralConfig& cfg, float t) {
    if (!cfg.channelSwapEnabled) return 1.0f;
    return channelSwapStateAt(cfg, t) ? -1.0f : 1.0f;
}

/**
 * Огибающая громкости процедуры смены раскладки (множитель к амплитуде).
 *
 * Центр — в T* (мгновение переворота знака), окно симметрично:
 *
 *   |t − T*| ≥ P/2 + F           → 1.0  (процедуры нет)
 *   T* − P/2 − F … T* − P/2      → затухание 0.5·(1 + cos(π·u)), u ∈ [0,1] → 1…0
 *   |t − T*| ≤ P/2               → 0.0  (тишина; знак перевёрнут, фазе нечего рвать)
 *   T* + P/2 … T* + P/2 + F      → нарастание 0.5·(1 − cos(π·u)), u ∈ [0,1] → 0…1
 *
 * Приподнятый косинус (а не линейная рампа) — та же кривая, что была у
 * FADE_OUT/FADE_IN до миграции на знаковый beat; на концах производная равна
 * нулю, поэтому стык с полной громкостью не слышен.
 *
 * Функция ЧЁТНАЯ относительно T*: g(T*−d) = g(T*+d). Отсюда непрерывность в
 * точке смены «ближайшего узла» — ровно посередине между соседними сменами
 * обе ветки дают одно и то же значение, скачка нет даже при перекрытии окон.
 *
 * Стоимость: при выключенном swap (по умолчанию) — один if, ноль сканов.
 */
inline float layoutGainAt(const BinauralConfig& cfg, float t) {
    const SwapProcedure p = nearestSwapProcedure(cfg, t);
    if (!p.valid || p.fadeSec <= 0.0f) return 1.0f;

    const float d = circularDeltaSec(t, p.tStarSec);
    const float halfP = 0.5f * p.pauseSec;
    const float F = p.fadeSec;

    if (d <= -halfP - F || d >= halfP + F) return 1.0f; // вне окна
    if (d <= -halfP) {                                  // затухание
        const float u = (d + halfP + F) / F;            // 0 … 1
        constexpr float kPi = 3.14159265358979323846f;
        return 0.5f * (1.0f + std::cos(kPi * u));
    }
    if (d <= halfP) return 0.0f;                        // тишина
    constexpr float kPi = 3.14159265358979323846f;
    const float u = (d - halfP) / F;                    // 0 … 1
    return 0.5f * (1.0f - std::cos(kPi * u));
}

/**
 * Мгновение переворота знака раскладки (сек суток ∈ [0, day)) или −1, если
 * знак не меняется. Нужно генератору: подсегмент режется по T*, чтобы
 * ступенька частот попала на границу сэмпла, где огибающая равна нулю.
 */
inline float swapFlipTimeSec(const BinauralConfig& cfg, float t) {
    const SwapProcedure p = nearestSwapProcedure(cfg, t);
    return p.valid ? p.tStarSec : -1.0f;
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
