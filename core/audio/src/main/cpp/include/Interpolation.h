#pragma once

#include "Config.h"
#include <cmath>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace binaural {

/**
 * Алгоритмы интерполяции для расчёта частот между точками
 * 
 * ТИПЫ ИНТЕРПОЛЯЦИИ:
 * - LINEAR: простая линейная интерполяция, без overshoot
 * - CARDINAL: кубический сплайн с параметром tension (0=Catmull-Rom плавный, 1=почти линейный)
 * - MONOTONE: сохраняет форму данных, гарантированно без overshoot
 * - STEP: ступенчатая интерполяция, значение остаётся постоянным до следующей точки
 */
namespace Interpolation {

/**
 * Линейная интерполяция
 */
inline float linear(float y1, float y2, float t) {
    return y1 + t * (y2 - y1);
}

/**
 * Кардинальный сплайн (с параметром tension)
 * 
 * ОСОБЕННОСТИ:
 * - tension = 0.0 -> Catmull-Rom (плавная кривая, возможен overshoot)
 * - tension = 1.0 -> почти линейная интерполяция
 * - tension > 0 -> более "тугая" кривая, меньше overshoot
 * - Проходит через все контрольные точки
 *
 * w1, w2 — веса касательных в узлах p1 и p2 (см. CardinalTension.kt):
 *   1.0 — номинальная касательная (как раньше), 0.0 — нулевая.
 * Это и есть регулировка overshoot: на участках, где кривая вылетает за
 * вертикальные границы, касательные укорачиваются настолько, чтобы кривая
 * границу КАСАЛАСЬ, а не перескакивала.
 *
 * Вес ОБЩИЙ для обоих каналов. Сплайн линеен по касательным, поэтому
 *   right(t) − left(t) = spline(beat; w·M^beat):
 * каналы НЕ схлопываются в точке касания, частота биений остаётся точной
 * интерполяцией своих узлов. Свой на канал вес это тождество разрушил бы,
 * каналы почти слиплись бы, а overshoot просто переехал бы в другое место.
 */
inline float cardinal(float p0, float p1, float p2, float p3, float t,
                      float tension = 0.0, float w1 = 1.0f, float w2 = 1.0f) {
    const float t2 = t * t;
    const float t3 = t2 * t;

    // Вычисляем касательные с учётом натяжения
    const float s = (1.0 - tension) / 2.0;
    const float m1 = (p2 - p0) * s * w1;
    const float m2 = (p3 - p1) * s * w2;

    const float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const float h10 = t3 - 2.0 * t2 + t;
    const float h01 = -2.0 * t3 + 3.0 * t2;
    const float h11 = t3 - t2;
    
    return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
}

/**
 * Вычисляет наклон для монотонного сплайна
 * Использует алгоритм Fritsch-Carlson для сохранения монотонности
 */
inline float computeMonotoneSlope(float d1, float d2) {
    // Если наклоны имеют разные знаки или один из них нулевой - касательная = 0
    if (d1 * d2 <= 0) return 0.0;
    
    // Гармоническое среднее двух наклонов - это ключ к монотонности
    return 2.0 * d1 * d2 / (d1 + d2);
}

/**
 * Монотонный кубический сплайн (PCHIP - Piecewise Cubic Hermite Interpolating Polynomial)
 * 
 * ОСОБЕННОСТИ:
 * - Гарантирует ОТСУТСТВИЕ OVERSHOOT - значения всегда в пределах [min(p1,p2), max(p1,p2)]
 * - Сохраняет монотонность данных - если p1 < p2, то кривая монотонно возрастает
 * - Проходит через все контрольные точки
 */
inline float monotone(float p0, float p1, float p2, float p3, float t) {
    // Вычисляем наклоны (разности) между соседними точками
    const float d0 = p1 - p0;  // наклон слева от p1
    const float d1 = p2 - p1;  // наклон между p1 и p2 (основной интервал)
    const float d2 = p3 - p2;  // наклон справа от p2
    
    // Вычисляем касательные в точках p1 и p2 используя гармоническое среднее
    const float m1 = computeMonotoneSlope(d0, d1);
    const float m2 = computeMonotoneSlope(d1, d2);
    
    // Кубическая интерполяция Эрмита
    const float t2 = t * t;
    const float t3 = t2 * t;
    
    const float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const float h10 = t3 - 2.0 * t2 + t;
    const float h01 = -2.0 * t3 + 3.0 * t2;
    const float h11 = t3 - t2;
    
    const float result = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    
    // Гарантируем отсутствие overshoot
    const float minVal = std::min(p1, p2);
    const float maxVal = std::max(p1, p2);
    return std::clamp(result, minVal, maxVal);
}

/**
 * Ступенчатая интерполяция
 * Значение остаётся постоянным (равным левой точке) до следующей точки
 */
inline float step(float p1) {
    return p1;
}

/**
 * Выполняет интерполяцию указанным методом
 * @param type тип интерполяции
 * @param p0 точка до левой границы
 * @param p1 левая граница интервала
 * @param p2 правая граница интервала
 * @param p3 точка после правой границы
 * @param t нормализованная позиция в интервале [0, 1]
 * @param tension параметр натяжения для CARDINAL (0.0=Catmull-Rom, 1.0=почти линейный)
 * @param allowNegative разрешить отрицательный результат.
 *        false (по умолчанию) — результат клампится к [0, +inf): так
 *        интерполируются ФИЗИЧЕСКИЕ частоты каналов, которые не бывают
 *        отрицательными.
 *        true — знак сохраняется: обязательно для ЧАСТОТЫ БИЕНИЙ, которая
 *        величина знаковая (beat = right − left; знак кодирует раскладку
 *        каналов, |beat| — слышимую пульсацию). См. FrequencyMath.kt.
 * @param w1 вес касательной в узле p1 (только CARDINAL, см. cardinal())
 * @param w2 вес касательной в узле p2 (только CARDINAL)
 * @return интерполированное значение
 */
inline float interpolate(
    InterpolationType type,
    float p0, float p1, float p2, float p3,
    float t,
    float tension = 0.0f,
    bool allowNegative = false,
    float w1 = 1.0f,
    float w2 = 1.0f
) {
    float result;
    switch (type) {
        case InterpolationType::LINEAR:
            result = linear(p1, p2, t);
            break;
        case InterpolationType::CARDINAL:
            result = cardinal(p0, p1, p2, p3, t, tension, w1, w2);
            break;
        case InterpolationType::MONOTONE:
            result = monotone(p0, p1, p2, p3, t);
            break;
        case InterpolationType::STEP:
            result = step(p1);
            break;
        default:
            result = linear(p1, p2, t);
    }
    return allowNegative ? result : std::max(0.0f, result);
}

} // namespace Interpolation

/**
 * Реализация методов FrequencyCurve
 */

/**
 * Залить обе таблицы константой (вырожденные кривые: 0 или 1 точка).
 */
inline void FrequencyCurve::fillTablesConstant(float lowerFreq, float upperFreq,
                                               int entries) {
    // КРИТИЧНО: создаём НОВЫЕ векторы, а не перезаписываем старые — прежние
    // таблицы может ещё держать другой поток через shared_ptr (писатель берёт
    // копию конфига на время генерации пакета).
    lowerFreqTable = std::make_shared<std::vector<float>>(
        static_cast<size_t>(entries), lowerFreq);
    upperFreqTable = std::make_shared<std::vector<float>>(
        static_cast<size_t>(entries), upperFreq);
}

/**
 * Внутренняя реализация построения таблицы
 * Использует АДАПТИВНЫЙ шаг (см. FREQUENCY_TABLE_MIN_INTERVAL_MS в Config.h)
 *
 * ОПТИМИЗАЦИЯ: Использует итеративный поиск O(n) вместо бинарного O(n log n)
 * т.к. время монотонно возрастает при построении таблицы.
 */
inline void FrequencyCurve::buildLookupTableInternal() {
    if (points.empty()) {
        // Нет точек - используем значения по умолчанию
        tableIntervalMs = FREQUENCY_TABLE_MAX_INTERVAL_MS;
        fillTablesConstant(200.0f, 210.0f, FREQUENCY_TABLE_MIN_SIZE);
        return;
    }
    
    if (points.size() == 1) {
        // Одна точка - вычисляем частоты из неё.
        // Кламп >= 0 — физический: сам тон не может иметь отрицательную частоту.
        const auto& p = points[0];
        const float lowerFreq = std::max(0.0f, p.carrierFrequency - p.beatFrequency / 2.0f);
        const float upperFreq = std::max(0.0f, p.carrierFrequency + p.beatFrequency / 2.0f);
        // Кривая константна — любая ячейка даёт то же значение, берём крупный шаг.
        tableIntervalMs = FREQUENCY_TABLE_MAX_INTERVAL_MS;
        fillTablesConstant(lowerFreq, upperFreq, FREQUENCY_TABLE_MIN_SIZE);
        return;
    }
    
    // Сортируем копию стабильно по времени; при равных временах оставляем последнюю точку
    std::vector<FrequencyPoint> sortedPoints = points;
    std::stable_sort(sortedPoints.begin(), sortedPoints.end(),
        [](const FrequencyPoint& a, const FrequencyPoint& b) {
            return a.timeSeconds < b.timeSeconds;
        });
    {
        size_t outIndex = 0;
        for (size_t i = 0; i < sortedPoints.size(); ++i) {
            if (i + 1 < sortedPoints.size() &&
                sortedPoints[i].timeSeconds == sortedPoints[i + 1].timeSeconds) {
                continue;
            }
            sortedPoints[outIndex++] = sortedPoints[i];
        }
        sortedPoints.resize(outIndex);
    }
    const int numPoints = static_cast<int>(sortedPoints.size());

    // ---------------- Веса касательных (регуляция overshoot) ----------------
    // Веса относились к узлам в том порядке, в котором точки прислал Kotlin, и
    // привязаны к отсортированным и схлопнутым по времени точкам — ровно к тем,
    // что в sortedPoints. При ЛЮБОМ расхождении размера регулировка
    // отключается: безопаснее построить таблицу с номинальными касательными,
    // чем применить веса к чужим узлам.
    const float* tw = nullptr;
    if (tensionWeights.size() == static_cast<size_t>(numPoints)) {
        tw = tensionWeights.data();
    }

    // ---------------- Адаптивный шаг таблицы ----------------
    // Шаг привязан к МИНИМАЛЬНОМУ зазору между соседними точками (с учётом
    // wrap-зазора через полночь): чем чаще точки, тем мельче шаг.
    //
    // ПОЛ шага = FREQUENCY_TABLE_MIN_INTERVAL_MS (100 мс) — историческое
    // разрешение таблицы. Тем самым ошибка адаптивной таблицы НИКОГДА не
    // превышает ошибку прежней фиксированной: кривая с часто расставленными
    // точками просто упирается в пол и ведёт себя по-старому.
    //
    // Выгода — на типовых пресетах: у кривой по умолчанию точки разнесены на
    // 3 часа, шаг упирается в потолок 1 с, и таблица становится в 10 раз
    // меньше (86 400 записей вместо 864 000): 0.69 МБ вместо 6.6 МБ и
    // updateCache ~4 мс вместо ~37 мс (MONOTONE).
    {
        int minGap = SECONDS_PER_DAY;
        for (int i = 0; i < numPoints; ++i) {
            const int a = sortedPoints[i].timeSeconds;
            const int b = sortedPoints[(i + 1) % numPoints].timeSeconds;
            int gap = b - a;
            if (gap <= 0) gap += SECONDS_PER_DAY;   // wrap через полночь
            minGap = std::min(minGap, gap);
        }
        if (minGap < 1) minGap = 1;
        tableIntervalMs = std::clamp(
            static_cast<int>(static_cast<float>(minGap) * 1000.0f /
                             static_cast<float>(FREQUENCY_TABLE_CELLS_PER_GAP)),
            FREQUENCY_TABLE_MIN_INTERVAL_MS,
            FREQUENCY_TABLE_MAX_INTERVAL_MS);
    }

    const int tableSize = SECONDS_PER_DAY * 1000 / tableIntervalMs;

    // Выделяем СВЕЖИЕ таблицы: старые могут быть ещё видны писателю, который
    // держит копию конфига на время генерации пакета. Пишем в локальные
    // ссылки, наружу публикуем только готовые — иначе читатель мог бы увидеть
    // наполовину построенную таблицу.
    auto newLower = std::make_shared<std::vector<float>>(static_cast<size_t>(tableSize));
    auto newUpper = std::make_shared<std::vector<float>>(static_cast<size_t>(tableSize));
    std::vector<float>& lowerFreqTable = *newLower;
    std::vector<float>& upperFreqTable = *newUpper;
    
    // Селекторы для частот каналов.
    //
    // ВАЖНО (знаковая частота биений): beat = right − left, поэтому
    //   left  = carrier − beat/2   (исторически называется "lower")
    //   right = carrier + beat/2   (исторически называется "upper")
    // При ОТРИЦАТЕЛЬНОМ beat имена меняются местами лишь по звучанию:
    // «нижняя» таблица (левый канал) реально держит более высокий тон.
    // Формулы от этого не меняются, и знак beat сохраняется автоматически —
    // ниже beatFreq считается как upper − lower и остаётся знаковым.
    // Кламп >= 0 применяется только к готовому каналу (физический предел тона),
    // но НЕ к beat: интерполяция каналов идёт с allowNegative=true.
    auto getLowerFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency - p.beatFrequency / 2.0;
    };
    auto getUpperFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency + p.beatFrequency / 2.0;
    };
    
    // Предвычисляем частоты для каждого интервала.
    // Итеративный поиск O(n) амортизировано: leftIndex только увеличивается.
    //
    // РОБАСТНОСТЬ: диапазон [0, points[0].time) принадлежит wrap-интервалу
    // [последняя точка -> первая точка + 86400], а НЕ интервалу [points[0], points[1]].
    // Это критично, когда первая точка НЕ в 0:00 (иначе ratio < 0 -> кламп к 0
    // и значение "застревает" на первой точке).
    const int firstPointTime = sortedPoints[0].timeSeconds;
    int leftIndex = 0;

    for (int tableIndex = 0; tableIndex < tableSize; ++tableIndex) {
        // Конвертируем индекс таблицы в секунды суток.
        // ФИКС: было `tableIndex * FREQUENCY_TABLE_INTERVAL_MS / 1000` —
        // ЦЕЛОЧИСЛЕННОЕ деление отбрасывало доли секунды, и таблица строилась
        // с шагом 1 секунда (10 одинаковых ячеек подряд) вместо заявленных
        // 100 мс. Из-за этого частоты в UI стояли на месте до секунды,
        // а при крутых кривых «ступеньки» были слышны и в отчётах частот.
        // Сейчас шаг адаптивный (tableIntervalMs) и дробный.
        const float timeSecondsF =
            static_cast<float>(tableIndex) *
            (static_cast<float>(tableIntervalMs) / 1000.0f);

        // Выбираем левый индекс интервала для данной записи.
        int effectiveLeftIndex;
        if (timeSecondsF < static_cast<float>(firstPointTime)) {
            // Начальный wrap-участок [0, firstPointTime): интервал
            // [points[last], points[0] + 86400].
            effectiveLeftIndex = numPoints - 1;
        } else {
            // Обычный монотонный поиск (leftIndex только растёт).
            // ФИКС: было (numPoints - 2) — wrap-интервал [последняя точка -> первая + 86400]
            // не строился, и для времени после последней точки происходила экстраполяция
            // с ratio > 1.0. Теперь leftIndex достигает numPoints - 1 (wrap-интервал).
            // Short-circuit && гарантирует отсутствие доступа к sortedPoints[numPoints].
            while (leftIndex < numPoints - 1 &&
                   static_cast<float>(sortedPoints[leftIndex + 1].timeSeconds) <= timeSecondsF) {
                ++leftIndex;
            }
            effectiveLeftIndex = leftIndex;
        }

        const int rightIndex = (effectiveLeftIndex + 1) % numPoints;

        const auto& leftPoint = sortedPoints[effectiveLeftIndex];
        const auto& rightPoint = sortedPoints[rightIndex];

        // Вычисляем нормализованную позицию t в интервале [0, 1]
        const float t1 = static_cast<float>(leftPoint.timeSeconds);
        float t2 = static_cast<float>(rightPoint.timeSeconds);

        // Обработка перехода через полночь
        const bool isWrapping = (effectiveLeftIndex == numPoints - 1);
        if (isWrapping) {
            t2 += static_cast<float>(SECONDS_PER_DAY);
        }

        float t = timeSecondsF;
        if (isWrapping && t < t1) {
            t += static_cast<float>(SECONDS_PER_DAY);
        }

        float ratio = 0.0f;
        if (t2 != t1) {
            ratio = (t - t1) / (t2 - t1);
        }
        // Защитный clamp — согласованность с jni.cpp (nativeGenerateInterpolatedCurve)
        ratio = std::clamp(ratio, 0.0f, 1.0f);

        // Получаем 4 точки для сплайна (циклические соседи)
        const int prevIndex = (effectiveLeftIndex - 1 + numPoints) % numPoints;
        const int nextNextIndex = (rightIndex + 1) % numPoints;
        
        // Веса касательных ОБЩИЕ для обоих каналов — иначе частота биений
        // перестала бы быть интерполяцией своих узлов и каналы слиплись бы
        // в точке касания с границей.
        const float w1 = tw ? tw[effectiveLeftIndex] : 1.0f;
        const float w2 = tw ? tw[rightIndex]         : 1.0f;

        // Интерполируем левую (исторически «нижнюю») частоту.
        // allowNegative=true: внутри сплайна канал может уйти в минус между
        // узлами, но по физике тон не бывает отрицательным — внешний
        // std::max(0.0f, ...) и есть единственный физический кламп.
        float lowerP0 = getLowerFreq(sortedPoints[prevIndex]);
        float lowerP1 = getLowerFreq(leftPoint);
        float lowerP2 = getLowerFreq(rightPoint);
        float lowerP3 = getLowerFreq(sortedPoints[nextNextIndex]);
        lowerFreqTable[tableIndex] = std::max(0.0f, Interpolation::interpolate(
            interpolationType, lowerP0, lowerP1, lowerP2, lowerP3, ratio, splineTension,
            /*allowNegative=*/true, w1, w2
        ));

        // Интерполируем правую (исторически «верхнюю») частоту
        float upperP0 = getUpperFreq(sortedPoints[prevIndex]);
        float upperP1 = getUpperFreq(leftPoint);
        float upperP2 = getUpperFreq(rightPoint);
        float upperP3 = getUpperFreq(sortedPoints[nextNextIndex]);
        upperFreqTable[tableIndex] = std::max(0.0f, Interpolation::interpolate(
            interpolationType, upperP0, upperP1, upperP2, upperP3, ratio, splineTension,
            /*allowNegative=*/true, w1, w2
        ));
    }

    // Публикуем готовые таблицы одной парой присваиваний: до этой точки
    // наружу видны предыдущие (или пустые) таблицы целиком.
    this->lowerFreqTable = std::move(newLower);
    this->upperFreqTable = std::move(newUpper);
}

/**
 * Построить lookup table с адаптивным шагом tableIntervalMs
 */
inline void FrequencyCurve::buildLookupTable() {
    buildLookupTableInternal();
}

// ============================================================================
// ПРЕДВЫЧИСЛЕНИЕ НУЛЕЙ ТРЕНДОВОЙ ПРОИЗВОДНОЙ (режим ChannelSwapMode::TREND)
//
// Выполняется ОДИН РАЗ при финализации кривой (updateCache → вызов из
// BinauralEngine::setConfig, т.е. при сохранении профиля / смене кривой),
// дальше планировщик только ищет по готовому списку.
//
// Δbeat(t) = beat(t+h) − beat(t−h), beat = upper − lower (частота биений),
// h = TREND_HALF_WINDOW_SEC — непрерывная периодическая функция по суткам;
// ноль со сменой знака = локальный экстремум ЧАСТОТЫ БИЕНИЙ = момент смены
// тенденции. Тренд (и его экстремумы) определяются частотой биений, а не
// несущей — см. обоснование в BufferPackagePlanner.h.
// ============================================================================

/**
 * Единая точка истины тренда: знакопеременный прирост ЧАСТОТЫ БИЕНИЙ на окне
 * 2*h. beat = upper − lower, поэтому прирост = разность приростов каналов:
 *   (upper(t+h) − lower(t+h)) − (upper(t−h) − lower(t−h)).
 * Ноль со сменой знака = локальный экстремум beat-кривой.
 * Множитель 1/2 (в отличие от carrier = (upper+lower)/2) здесь НЕ нужен: beat
 * берётся без усреднения. Постоянный множитель не влияет ни на нули, ни на
 * знак — величина везде используется только по знаку.
 */
inline float trendBeatDeltaAt(const FrequencyCurve& curve, float tSec) {
    const FrequencyTableResult plus =
        curve.getChannelFrequenciesAt(tSec + TREND_HALF_WINDOW_SEC);
    const FrequencyTableResult minus =
        curve.getChannelFrequenciesAt(tSec - TREND_HALF_WINDOW_SEC);
    return (plus.upperFreq - plus.lowerFreq) - (minus.upperFreq - minus.lowerFreq);
}

namespace TrendScanDetail {

inline float trendDeltaSample(const FrequencyCurve& curve, float tSec) {
    // Делегируем единой точке истины тренда: тренд считается по частоте биений.
    return trendBeatDeltaAt(curve, tSec);
}

/**
 * Уточнить ноль на бракете [lo, hi] с разными знаками Δbeat на концах.
 * Бисекция: Δbeat кусочно-линейна с плотными узлами (таблица 100 мс), монотонность
 * бракета не гарантирована, но смена знака есть — бисекция сходится всегда.
 * Точность ~1e-4 c (0.1 мс оси кривой).
 */
inline float refineZero(const FrequencyCurve& curve, float lo, float hi) {
    const float loSign = trendDeltaSample(curve, lo);
    for (int it = 0; it < 40 && (hi - lo) > 1e-4f; ++it) {
        const float mid = 0.5f * (lo + hi);
        if ((trendDeltaSample(curve, mid) > 0.0f) == (loSign > 0.0f)) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return 0.5f * (lo + hi);
}

} // namespace TrendScanDetail

/**
 * Найти все нули Δbeat за сутки методом «грубая сетка → уточнение».
 * Свободная функция над const-кривой: используется и кэширующим членом
 * buildTrendCrossings(), и fallback-путём планировщика без копии таблиц.
 *
 * Шаг сетки адаптивный: четверть минимального зазора между контрольными точками
 * (включая wrap-зазор через полночь), кламп [0.25; 5] c — любой изгиб кривой
 * масштаба контрольных точек разрешается сеткой, а влияние особенности частоты
 * биений на Δbeat разливается минимум на ширину окна ±60 c и не может
 * «спрятаться» между узлами. Плато (Δbeat == 0) переходов не создаёт; касание
 * нуля без смены знака — тоже. Результат отсортирован по времени суток,
 * направления чередуются.
 */
inline void computeTrendCrossings(const FrequencyCurve& curve,
                                  std::vector<TrendCrossing>& out) {
    out.clear();

    if (!curve.hasFreqTables() || curve.points.size() < 2) {
        return; // таблица/точки не заданы — переходов нет
    }

    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);

    // Минимальный зазор между соседними точками (по циклу, включая wrap-участок)
    std::vector<float> times;
    times.reserve(curve.points.size());
    for (const auto& p : curve.points) times.push_back(static_cast<float>(p.timeSeconds));
    std::sort(times.begin(), times.end());
    times.erase(std::unique(times.begin(), times.end()), times.end());
    float minGap = dayF - times.back() + times.front(); // wrap-зазор
    for (size_t i = 1; i < times.size(); ++i) {
        minGap = std::min(minGap, times[i] - times[i - 1]);
    }
    minGap = std::max(minGap, 1.0f); // защита от вырождения (дубликаты уже слиты)

    const float coarseStep = std::clamp(minGap * 0.25f, 0.25f, 5.0f);
    const int gridN = static_cast<int>(std::ceil(dayF / coarseStep));
    const float step = dayF / static_cast<float>(gridN);

    // Обход узлов 0..gridN (узел gridN ≡ узел 0 через полночь). Нулевые узлы
    // не участвуют в сравнении знаков напрямую: экстремум может попасть РОВНО
    // на узел (симметричные кривые), и тогда соседние пары дают «+→0»/«0→−»
    // без строгого переворота. Поэтому последний НЕнулевой знак переносится
    // через нулевой участок, а бракетом для уточнения служит пара разноимённых
    // ненулевых узлов. Плато (длинный нулевой участок) переходов не создаёт,
    // если знак по обе стороны одинаков.
    auto timeAt = [&](int i) -> float {
        return (i >= gridN) ? dayF : static_cast<float>(i) * step;
    };
    auto sampleAt = [&](int i) -> float {
        return TrendScanDetail::trendDeltaSample(curve, (i >= gridN) ? 0.0f
                                              : static_cast<float>(i) * step);
    };

    out.reserve(16);
    int lastSignedIdx = -1;
    float lastSign = 0.0f;
    int firstSignedIdx = -1;
    float firstSign = 0.0f;
    auto pushCrossing = [&](int loIdx, int hiIdx, float dHi) {
        TrendCrossing crossing;
        // hiIdx может быть gridN+dayWrap-расширенным: бракет через полночь
        crossing.timeSec = std::fmod(
            TrendScanDetail::refineZero(curve, timeAt(loIdx), timeAt(hiIdx)), dayF);
        if (crossing.timeSec < 0.0f) crossing.timeSec += dayF;
        crossing.toSwapped = (dHi < 0.0f); // после пика тренд убывает
        out.push_back(crossing);
    };
    for (int i = 0; i <= gridN; ++i) {
        const float d = sampleAt(i);
        if (d == 0.0f) continue; // нулевой узел: знак не обновляется
        if (lastSignedIdx >= 0 && ((d > 0.0f) != (lastSign > 0.0f))) {
            pushCrossing(lastSignedIdx, i, d);
        }
        if (firstSignedIdx < 0) { firstSignedIdx = i; firstSign = d; }
        lastSignedIdx = i;
        lastSign = d;
    }
    // Циклическая смычка через полночь: экстремум на полуночи даёт нулевые узлы
    // на ОБОИХ концах развёртки — знак последнего ненулевого узла сравнивается
    // с первым (бракет продлевается за 86400).
    if (firstSignedIdx >= 0 && lastSignedIdx >= 0 &&
        firstSignedIdx != lastSignedIdx &&
        ((firstSign > 0.0f) != (lastSign > 0.0f))) {
        const float loT = timeAt(lastSignedIdx);
        const float hiT = dayF + timeAt(firstSignedIdx);

        // Длинный горизонтальный участок, пересекающий полночь, — НЕ экстремум.
        // На нём Δbeat == 0 (частота биений не меняется на окне ±60 с), и если
        // по обе стороны от него знаки Δbeat противоположны — например, это
        // спад и подъём одного и того же бампа, — смычка видит «смену знака
        // − -> +» и объявляет минимум там, где кривая просто плоская. Это
        // лишняя перестановка каналов посреди неизменного звука.
        //
        // Настоящий экстремум у полуночи локален: ненулевые узлы стоят по
        // обе стороны от неё в нескольких шагах сетки, и бракет короток.
        // Поэтому бракет длиннее полусуток — признак плато, а не экстремума.
        if (hiT - loT <= dayF * 0.5f) {
            TrendCrossing crossing;
            crossing.timeSec = std::fmod(
                TrendScanDetail::refineZero(curve, loT, hiT), dayF);
            if (crossing.timeSec < 0.0f) crossing.timeSec += dayF;
            crossing.toSwapped = (firstSign < 0.0f); // знак начала суток после перехода
            out.push_back(crossing);
        }
    }
}

inline void FrequencyCurve::buildTrendCrossings() {
    trendCrossingsValid = false;

    if (!hasFreqTables()) {
        trendCrossings.clear();
        return; // таблица не построена — тренд не определён, кэш невалиден
    }

    computeTrendCrossings(*this, trendCrossings);
    trendCrossingsValid = true; // валиден даже при пустом списке (плоская кривая)
}

/**
 * Обновить кэш min/max частот и перестроить lookup table
 *
 * ВАЖНО: min/max для lower/upper частот вычисляются по lookup-таблице,
 * т.к. при интерполяции CARDINAL возможен overshoot и реальные значения
 * могут отличаться от значений в контрольных точках.
 *
 * Для CARDINAL overshoot к моменту построения таблицы уже укрощён весами
 * касательных (FrequencyCurve::tensionWeights, см. cardinal()) — таблица
 * считается с ними, поэтому min/max по ней и есть реальные границы звучания.
 *
 * minChannelFreq вычисляется по КОНТРОЛЬНЫМ ТОЧКАМ (не по lookup-таблице!),
 * используя формулу: carrier - |beat|/2
 * Это даёт истинную минимальную частоту тона для каждого из каналов:
 * - При beat > 0: min = carrier - beat/2 (нижний канал)
 * - При beat < 0: min = carrier - |beat|/2 (более низкий из двух каналов)
 *
 * Вычисление по контрольным точкам необходимо, т.к. при CARDINAL интерполяции
 * возможен overshoot, и интерполированные значения в lookup-таблице могут
 * стать отрицательными (и быть обрезаны до 0), что приведёт к неправильному
 * вычислению minChannelFreq = 0 и потере звука при временной нормализации.
 */
/**
 * ОТПЕЧАТОК КРИВОЙ: ровно всё, от чего зависит содержимое lookup-таблицы.
 *
 * Таблица — функция только от (точки, тип интерполяции, натяжение, веса
 * касательных). Частота дискретизации, громкость, перестановка каналов и
 * нормализация в неё НЕ входят и в отпечаток не идут: иначе смена громкости
 * выглядела бы сменой кривой и выбивала бы кэш на каждом шаге слайдера.
 */
inline uint64_t curveFingerprint(const FrequencyCurve& c) {
    // FNV-1a, 64 бит.
    uint64_t h = 1469598103934665603ull;
    auto mix = [&h](const void* p, std::size_t n) {
        const auto* b = static_cast<const unsigned char*>(p);
        for (std::size_t i = 0; i < n; ++i) {
            h ^= static_cast<uint64_t>(b[i]);
            h *= 1099511628211ull;
        }
    };
    // Поля смешиваются ПО ОДНОМУ, а не целой структурой: в FrequencyPoint
    // есть выравнивающие пропуски, и их содержимое не определено — смешивание
    // структуры целиком делало бы отпечаток нестабильным.
    for (const auto& p : c.points) {
        mix(&p.timeSeconds, sizeof(p.timeSeconds));
        mix(&p.carrierFrequency, sizeof(p.carrierFrequency));
        mix(&p.beatFrequency, sizeof(p.beatFrequency));
    }
    const int itype = static_cast<int>(c.interpolationType);
    mix(&itype, sizeof(itype));
    mix(&c.splineTension, sizeof(c.splineTension));
    if (!c.tensionWeights.empty()) {
        mix(c.tensionWeights.data(), c.tensionWeights.size() * sizeof(float));
    }
    // Размеры — последними и отдельно: без них «4 точки (1,2)+(3,4)» и «8
    // точек» с одинаковым байтовым потоком выглядели бы одной кривой.
    const uint64_t n = static_cast<uint64_t>(c.points.size());
    const uint64_t w = static_cast<uint64_t>(c.tensionWeights.size());
    mix(&n, sizeof(n));
    mix(&w, sizeof(w));
    return h;
}

/**
 * КЭШ ТАБЛИЦ КРИВОЙ: один слот на процесс, таблицы по СЛАБЫМ ссылкам.
 *
 * ЗАЧЕМ. Каждый скраб в редакторе — это полный хэндофф, то есть новый
 * нативный движок и полная пересборка lookup-таблицы (0.69 МБ и ~4 мс в
 * типичном случае, до 6.6 МБ и ~37 мс при шаге 100 мс) плюс сканирование
 * сетки ≥17 280 узлов на нули тренда. И всё это НА НИТИ АКТЁРА ради кривой,
 * которая НЕ ИЗМЕНИЛАСЬ. docs/analysis_scrub_storm_click_risk.md (R3).
 *
 * ПОЧЕМУ СЛАБЫЕ ССЫЛКИ. Держать таблицы сильно — значит навсегда
 * зарезервировать до 6.6 МБ нативной памяти, а её дефицит здесь не
 * гипотеза, а documented причина отказа createTrack_l (-12). Слабая ссылка
 * даёт ровно то, что нужно: в момент скраба УХОДЯЩИЙ поток ещё жив (он
 * гаснет 250 мс), его таблицы ещё в куче, и свежий движок забирает их
 * БЕСПЛАТНО. Когда ни одного потока нет — кэш пуст и память свободна.
 *
 * ПОЧЕМУ ОДИН СЛОТ. Рабочий сценарий — «предыдущая кривая → та же кривая».
 * Набор пресетов с несколькими живыми кривыми кэшу не выгоден, а один слот
 * делает промах детерминированным.
 *
 * Таблицы неизменяемы (shared_ptr<const ...>), поэтому отдавать их нескольким
 * движкам одновременно безопасно: писатель, держащий копию конфига, видит
 * тот же снимок, что и до пересборки.
 */
class CurveTableCache {
public:
    struct Entry {
        uint64_t fingerprint = 0;
        std::size_t points = 0;
        std::size_t weights = 0;
        int32_t tableIntervalMs = 0;
        std::weak_ptr<const std::vector<float>> lower;
        std::weak_ptr<const std::vector<float>> upper;
        std::vector<TrendCrossing> crossings;
        float minLower = 0.0f, maxLower = 0.0f;
        float minUpper = 0.0f, maxUpper = 0.0f;
        float minChannel = 0.0f;
    };

    static CurveTableCache& instance() {
        static CurveTableCache c;
        return c;
    }

    /**
     * @param outLower/outUpper сильные ссылки на таблицы (lock внутри):
     *        пока вызывающий их держит, таблицы гарантированно живы.
     * @param out метаданные (шаг таблицы, экстремумы, min/max).
     * @return true — попали: данные сложены в аргументы.
     *         false — держателя больше нет или кривая другая.
     */
    bool lookup(uint64_t fp, std::size_t points, std::size_t weights,
                std::shared_ptr<const std::vector<float>>& outLower,
                std::shared_ptr<const std::vector<float>>& outUpper,
                Entry& out) {
        std::lock_guard<std::mutex> g(mtx);
        if (slot.fingerprint != fp || slot.points != points ||
            slot.weights != weights) return false;
        outLower = slot.lower.lock();   // nullptr — все держатели умерли
        outUpper = slot.upper.lock();
        if (!outLower || !outUpper) return false;
        out.fingerprint = fp;
        out.points = points;
        out.weights = weights;
        out.tableIntervalMs = slot.tableIntervalMs;
        out.crossings = slot.crossings;
        out.minLower = slot.minLower;
        out.maxLower = slot.maxLower;
        out.minUpper = slot.minUpper;
        out.maxUpper = slot.maxUpper;
        out.minChannel = slot.minChannel;
        return true;
    }

    void publish(const Entry& e) {
        // Список экстремумов — единственное, что хранится ПО ЗНАЧЕНИЮ и потому
        // переживает смерть всех держателей. На типовых кривых их единицы;
        // предел страхует от патологической кривой с тысячами экстремумов.
        if (e.crossings.size() > kMaxCachedCrossings) return;
        std::lock_guard<std::mutex> g(mtx);
        slot = e;
    }

private:
    CurveTableCache() = default;
    static constexpr std::size_t kMaxCachedCrossings = 4096;
    std::mutex mtx;
    Entry slot;
};

inline void FrequencyCurve::updateCache() {
    if (points.empty()) return;

    // БЫСТРЫЙ ПУТЬ: кривая та же — переиспользуем готовые таблицы.
    //
    // Экономит 0.69…6.6 МБ аллокаций и ~4…37 мс на нити актёра на КАЖДОМ
    // скрабе, а заодно снимает давление на кучу — ту самую, из-за которой
    // второй трек иногда не создаётся (-12) и скраб уходит в разрыв (R1).
    // Проверка дешевле сборки на порядки: отпечаток — один проход по точкам,
    // а сборка — это таблица плюс скан сетки.
    const uint64_t fp = curveFingerprint(*this);
    cachedHash = static_cast<int32_t>(fp & 0xFFFFFFFFu);
    CurveTableCache::Entry hit;
    // Сильные ссылки живут в lookup() ровно до присваивания в поля — присваиваем
    // сразу, без промежуточного хранения: двойной lock() не нужен.
    if (CurveTableCache::instance().lookup(
            fp, points.size(), tensionWeights.size(),
            lowerFreqTable, upperFreqTable, hit)) {
        tableIntervalMs = hit.tableIntervalMs;
        trendCrossings = std::move(hit.crossings);
        trendCrossingsValid = true;
        minLowerFreq = hit.minLower;
        maxLowerFreq = hit.maxLower;
        minUpperFreq = hit.minUpper;
        maxUpperFreq = hit.maxUpper;
        minChannelFreq = hit.minChannel;
        return;
    }

    // Сначала строим lookup table
    buildLookupTable();

    // Предвычисляем нули трендовой производной (экстремумы ЧАСТОТЫ БИЕНИЙ).
    // Один раз на финализацию кривой (сохранение профиля / смена кривой),
    // планировщик TREND дальше только переиспользует список.
    buildTrendCrossings();
    
    // Вычисляем min/max по lookup-таблице (учитывает интерполяцию)
    minLowerFreq = std::numeric_limits<float>::max();
    maxLowerFreq = std::numeric_limits<float>::lowest();
    minUpperFreq = std::numeric_limits<float>::max();
    maxUpperFreq = std::numeric_limits<float>::lowest();
    
    // Ссылки и размер вынесены из цикла: иначе компилятор обязан перезагружать
    // size() и разыменовывать shared_ptr на каждой итерации (не может доказать
    // отсутствие алиасинга с min*/max*, которые тоже доступны по this).
    if (hasFreqTables()) {
        const std::vector<float>& lo = *lowerFreqTable;
        const std::vector<float>& up = *upperFreqTable;
        const size_t n = lo.size();
        for (size_t i = 0; i < n; ++i) {
            minLowerFreq = std::min(minLowerFreq, lo[i]);
            maxLowerFreq = std::max(maxLowerFreq, lo[i]);
            minUpperFreq = std::min(minUpperFreq, up[i]);
            maxUpperFreq = std::max(maxUpperFreq, up[i]);
        }
    }
    
    // minChannelFreq вычисляем по КОНТРОЛЬНЫМ ТОЧКАМ
    // Используем формулу: carrier - |beat|/2
    // Это даёт истинную минимальную частоту тона независимо от знака beatFrequency
    minChannelFreq = std::numeric_limits<float>::max();
    for (const auto& p : points) {
        const float minFreqAtPoint = p.carrierFrequency - std::abs(p.beatFrequency) / 2.0f;
        minChannelFreq = std::min(minChannelFreq, minFreqAtPoint);
    }

    // Публикуем результат: следующий движок с той же кривой возьмёт его
    // бесплатно — если этот к тому моменту ещё жив (отсюда слабые ссылки).
    CurveTableCache::Entry e;
    e.fingerprint = fp;
    e.points = points.size();
    e.weights = tensionWeights.size();
    e.tableIntervalMs = tableIntervalMs;
    e.lower = lowerFreqTable;
    e.upper = upperFreqTable;
    e.crossings = trendCrossings;
    e.minLower = minLowerFreq;
    e.maxLower = maxLowerFreq;
    e.minUpper = minUpperFreq;
    e.maxUpper = maxUpperFreq;
    e.minChannel = minChannelFreq;
    CurveTableCache::instance().publish(e);
}

/**
 * Получить частоты каналов для заданного времени через lookup table
 * Возвращает интерполированные частоты для конкретного момента времени
 *
 * СЛОЖНОСТЬ: O(1) - прямой доступ по индексу + линейная интерполяция
 *
 * ИНТЕРПОЛЯЦИЯ ВНУТРИ ТАБЛИЦЫ:
 * Использует линейную интерполяцию между соседними значениями таблицы,
 * что обеспечивает плавные переходы при любом разрешении таблицы.
 *
 * ДРОБНОЕ ВРЕМЯ:
 * Поддерживает дробные секунды для корректной интерполяции внутри буфера.
 * Например: 0.186 сек позволяет вычислить частоты в середине буфера.
 *
 * ОПТИМИЗАЦИЯ:
 * Использует __builtin_prefetch для предзагрузки следующего значения в кэш.
 */
inline FrequencyTableResult FrequencyCurve::getChannelFrequenciesAt(float timeSeconds) const {
    FrequencyTableResult result = {200.0, 210.0};
    
    // Если lookup table не построена, возвращаем значения по умолчанию
    if (!hasFreqTables()) {
        return result;
    }
    
    // Нормализуем время в пределах суток
    // Используем fmod для корректной работы с отрицательными дробными значениями
    timeSeconds = std::fmod(timeSeconds, static_cast<float>(SECONDS_PER_DAY));
    if (timeSeconds < 0.0f) {
        timeSeconds += static_cast<float>(SECONDS_PER_DAY);
    }
    
    // STEP: таблица линейно интерполирует между ячейками 100 мс, из-за чего
    // ступенька размазывается в глиссандо на 100 мс — и в SOLID, и в фейдах
    // (SOLID это частично компенсирует collectStepBoundaries, фейд — нет,
    // отсюда расхождение SOLID/фейд в 4 раза на ступеньке). Сама ступенчатая
    // функция задана ТОЧНО контрольными точками, поэтому для STEP таблица не
    // нужна: берём значение напрямую (удержание левой точки интервала, как в
    // Interpolation::step). Скачок становится мгновенным и одинаковым во всех
    // фазах, а место скачка — ровно timeSeconds контрольной точки.
    if (interpolationType == InterpolationType::STEP && points.size() >= 2) {
        const FrequencyPoint* held = &points[0];
        bool found = false;
        float bestTime = 0.0f;
        // Точки могут прийти неотсортированными — ищем последнюю по времени,
        // не наступающую на timeSeconds (STEP = удержание ЛЕВОЙ точки).
        for (const auto& p : points) {
            const float pt = static_cast<float>(p.timeSeconds);
            if (pt <= timeSeconds && (!found || pt > bestTime)) {
                bestTime = pt;
                found = true;
                held = &p;
            }
        }
        if (!found) {
            // Wrap: время раньше первой точки суток — держим последнюю точку
            // (то же соглашение, что при построении таблицы).
            float maxTime = static_cast<float>(points[0].timeSeconds);
            held = &points[0];
            for (const auto& p : points) {
                const float pt = static_cast<float>(p.timeSeconds);
                if (pt > maxTime) {
                    maxTime = pt;
                    held = &p;
                }
            }
        }
        result.lowerFreq = std::max(0.0f, held->carrierFrequency - held->beatFrequency * 0.5f);
        result.upperFreq = std::max(0.0f, held->carrierFrequency + held->beatFrequency * 0.5f);
        return result;
    }

    // Шаг таблицы АДАПТИВНЫЙ и лежит в себе же (100…1000 мс). Читать его
    // обязательно из кривой: у разных кривых шаг разный, а постоянная
    // FREQUENCY_TABLE_INTERVAL_MS больше не существует.
    const float intervalSeconds = static_cast<float>(tableIntervalMs) / 1000.0f;
    const std::vector<float>& lo = *lowerFreqTable;
    const std::vector<float>& up = *upperFreqTable;
    const int tableSize = static_cast<int>(lo.size());
    
    // Вычисляем непрерывную позицию в таблице (дробная)
    const float continuousIndex = timeSeconds / intervalSeconds;
    
    // Индекс текущей точки
    const int currentIndex = static_cast<int>(continuousIndex);
    
    // Позиция внутри текущего интервала [0, 1)
    const float t = continuousIndex - static_cast<float>(currentIndex);
    
    // Индекс следующей точки (с циклическим переходом через полночь)
    const int nextIndex = (currentIndex + 1) % tableSize;
    
    // Безопасные индексы (clamping)
    const int safeCurrentIndex = std::min(currentIndex, tableSize - 1);
    const int safeNextIndex = std::min(nextIndex, tableSize - 1);
    
    // ОПТИМИЗАЦИЯ: Prefetch следующего значения для лучшего cache hit
    // Предзагружаем значение, которое потребуется в следующем вызове
    #ifdef __GNUC__
    if (safeNextIndex + 1 < tableSize) {
        __builtin_prefetch(&lo[safeNextIndex + 1], 0, 0);
        __builtin_prefetch(&up[safeNextIndex + 1], 0, 0);
    }
    #endif
    
    // Линейная интерполяция между соседними значениями таблицы
    result.lowerFreq = Interpolation::linear(
        lo[safeCurrentIndex],
        lo[safeNextIndex],
        t
    );
    result.upperFreq = Interpolation::linear(
        up[safeCurrentIndex],
        up[safeNextIndex],
        t
    );
    
    return result;
}

} // namespace binaural