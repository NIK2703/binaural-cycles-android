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
 * Множитель раскладки ушей в момент t (сек суток).
 *
 *   s = +1 — прямое расположение (левое ухо = lower, правое = upper);
 *   s = −1 — обратное;
 *   |s| < 1 — ПРОХОД ПУЛЬСАЦИИ ЧЕРЕЗ НОЛЬ: оба уха сходятся в унисон и
 *             расходятся в противоположном направлении.
 *
 * ШАГ 2 МИГРАЦИИ: СТУПЕНЧАТАЯ функция от расписания смен. Бывший второй
 * механизм (флаг GeneratorState::channelsSwapped + перестановка выходного
 * буфера ПОСЛЕ осцилляторов) удалён из генераторов: раскладка целиком вошла
 * в частоты через channelsAt(), смена происходит точно в T* — узле сетки
 * TIMER или выбранном экстремуме TREND, а не в конце ритуала FADE_OUT.
 *
 * Непрерывность фазы в ухе — по построению: планировщик режет SOLID на
 * подсегменты ≤ 100 мс, и последний подсегмент перед T* рампит частоты от
 * «до» к «после» (хорда earFreqsAt) — ухо слышит плавный проход биений через
 * унисон, щелчка нет; несущая и громкость не меняются. Следующий шаг (3)
 * заменит ступеньку на непрерывную рампу s(t) = s_before·cos(π·u) в окне
 * W = 2F+P вокруг T* — сгладит и производную.
 */
inline float layoutSignAt(const BinauralConfig& cfg, float t) {
    return cfg.channelSwapEnabled && channelSwapStateAt(cfg, t) ? -1.0f : 1.0f;
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
