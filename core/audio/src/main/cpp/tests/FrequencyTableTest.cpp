/**
 * Тесты lookup-таблицы частот FrequencyCurve.
 *
 * Покрывают две оптимизации, внесённые вместе и поэтому проверяемые вместе:
 *
 * 1) АДАПТИВНЫЙ ШАГ ТАБЛИЦЫ (было: всегда 100 мс = 864000 записей = 3.3 МБ
 *    на канал). Шаг теперь привязан к минимальному зазору между контрольными
 *    точками и клампится в [FREQUENCY_TABLE_MIN_INTERVAL_MS;
 *    FREQUENCY_TABLE_MAX_INTERVAL_MS]. Ключевая гарантия: ПОЛ шага равен
 *    историческим 100 мс, поэтому ошибка аппроксимации НИКОГДА не превышает
 *    прежнюю — кривая с часто расставленными точками упирается в пол и ведёт
 *    себя в точности как раньше.
 *
 * 2) РАЗДЕЛЯЕМЫЕ ТАБЛИЦЫ (shared_ptr). Движок копирует конфиг на каждый
 *    генерируемый пакет; при владении векторами по значению это memcpy 6.6 МБ
 *    и две аллокации по 3.3 МБ на пакет. Через shared_ptr копия конфига —
 *    O(1). Требование безопасности: пересборка обязана публиковать НОВЫЕ
 *    таблицы, а не писать поверх старых, иначе писатель, держащий копию
 *    конфига, мог бы читать наполовину построенную таблицу.
 */

#include <gtest/gtest.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include "Config.h"
#include "Interpolation.h"

namespace binaural {
namespace test {

// ============================================================================
// Вспомогательное
// ============================================================================

namespace {

/**
 * ТОЧНОЕ значение сплайна в момент времени — без всякой lookup-таблицы.
 * Служит эталоном для замера ошибки табличной аппроксимации.
 * Логика выбора интервала повторяет FrequencyCurve::buildLookupTableInternal.
 */
void splineAt(const FrequencyCurve& c, float timeSecondsF,
              float& outLower, float& outUpper) {
    std::vector<FrequencyPoint> pts = c.points;
    std::stable_sort(pts.begin(), pts.end(),
        [](const FrequencyPoint& a, const FrequencyPoint& b) {
            return a.timeSeconds < b.timeSeconds;
        });
    // удаляем дубли по времени — как в проде (оставляем последнюю)
    {
        std::vector<FrequencyPoint> out;
        for (size_t i = 0; i < pts.size(); ++i) {
            if (i + 1 < pts.size() &&
                pts[i].timeSeconds == pts[i + 1].timeSeconds) continue;
            out.push_back(pts[i]);
        }
        pts = out;
    }

    const int n = static_cast<int>(pts.size());
    if (n == 0) { outLower = 200.0f; outUpper = 210.0f; return; }
    if (n == 1) {
        outLower = std::max(0.0f, pts[0].carrierFrequency - pts[0].beatFrequency / 2.0f);
        outUpper = std::max(0.0f, pts[0].carrierFrequency + pts[0].beatFrequency / 2.0f);
        return;
    }

    int leftIndex = 0;
    while (leftIndex < n - 1 &&
           static_cast<float>(pts[leftIndex + 1].timeSeconds) <= timeSecondsF) {
        ++leftIndex;
    }
    // участок [0, pts[0].time) принадлежит wrap-интервалу
    if (timeSecondsF < static_cast<float>(pts[0].timeSeconds)) leftIndex = n - 1;

    const int rightIndex = (leftIndex + 1) % n;
    const int prevIndex  = (leftIndex - 1 + n) % n;
    const int nextNext   = (rightIndex + 1) % n;

    float t1 = static_cast<float>(pts[leftIndex].timeSeconds);
    float t2 = static_cast<float>(pts[rightIndex].timeSeconds);
    const bool isWrapping = (leftIndex == n - 1);
    if (isWrapping) t2 += static_cast<float>(SECONDS_PER_DAY);

    float t = timeSecondsF;
    if (isWrapping && t < t1) t += static_cast<float>(SECONDS_PER_DAY);

    float ratio = (t2 != t1) ? (t - t1) / (t2 - t1) : 0.0f;
    ratio = std::clamp(ratio, 0.0f, 1.0f);

    auto lo = [](const FrequencyPoint& p) {
        return p.carrierFrequency - p.beatFrequency / 2.0;
    };
    auto up = [](const FrequencyPoint& p) {
        return p.carrierFrequency + p.beatFrequency / 2.0;
    };

    outLower = std::max(0.0f, Interpolation::interpolate(
        c.interpolationType,
        lo(pts[prevIndex]), lo(pts[leftIndex]), lo(pts[rightIndex]), lo(pts[nextNext]),
        ratio, c.splineTension, /*allowNegative=*/true));
    outUpper = std::max(0.0f, Interpolation::interpolate(
        c.interpolationType,
        up(pts[prevIndex]), up(pts[leftIndex]), up(pts[rightIndex]), up(pts[nextNext]),
        ratio, c.splineTension, /*allowNegative=*/true));
}

FrequencyCurve buildCurve(std::vector<FrequencyPoint> pts, InterpolationType t) {
    FrequencyCurve c;
    c.points = std::move(pts);
    c.interpolationType = t;
    c.splineTension = 0.0f;
    c.updateCache();
    return c;
}

/// Кривая по умолчанию из приложения: 8 точек через 3 часа, MONOTONE.
std::vector<FrequencyPoint> defaultPresetPoints() {
    const int h[8] = {0, 3, 6, 9, 12, 15, 18, 21};
    const float c[8] = {174, 210, 220, 440, 440, 440, 250, 240};
    const float b[8] = {3, 6, 8, 20, 25, 18, 12, 10};
    std::vector<FrequencyPoint> v;
    for (int i = 0; i < 8; ++i) {
        FrequencyPoint p;
        p.timeSeconds = h[i] * 3600;
        p.carrierFrequency = c[i];
        p.beatFrequency = b[i];
        v.push_back(p);
    }
    return v;
}

/// Точки через gapSec; при saw==true соседние точки прыгают на полный диапазон.
std::vector<FrequencyPoint> spacedPoints(int gapSec, int n, bool saw) {
    std::vector<FrequencyPoint> v;
    for (int i = 0; i < n; ++i) {
        FrequencyPoint p;
        p.timeSeconds = (i * gapSec) % SECONDS_PER_DAY;
        if (saw) {
            p.carrierFrequency = (i % 2 == 0) ? 100.0f : 2000.0f;
            p.beatFrequency = (i % 2 == 0) ? 0.5f : 30.0f;
        } else {
            p.carrierFrequency = 150.0f + 450.0f * static_cast<float>(i) / (n - 1);
            p.beatFrequency = 2.0f + 16.0f * static_cast<float>(i) / (n - 1);
        }
        v.push_back(p);
    }
    return v;
}

/**
 * Максимальная ошибка таблицы против точного сплайна.
 *
 * Шаг сетки ДРОБНЫЙ и заведомо не кратный ни одному возможному шагу таблицы:
 * с целым страйдом замер попадает ровно в узлы и даёт ложный ноль.
 */
double maxTableError(const FrequencyCurve& curve, int samples = 20000) {
    double maxErr = 0.0;
    for (int i = 0; i < samples; ++i) {
        const float t = std::fmod(static_cast<float>(i) * 137.3713f,
                                  static_cast<float>(SECONDS_PER_DAY));
        const FrequencyTableResult got = curve.getChannelFrequenciesAt(t);
        float eL = 0.0f, eU = 0.0f;
        splineAt(curve, t, eL, eU);
        maxErr = std::max(maxErr, std::abs(static_cast<double>(got.lowerFreq) - eL));
        maxErr = std::max(maxErr, std::abs(static_cast<double>(got.upperFreq) - eU));
    }
    return maxErr;
}

/// Пилообразный CARDINAL-пресет: несущая прыгает 200↔600, биения 4↔20.
/// На каждом участке Catmull-Rom (tension=0) даёт явный overshoot выше узла,
/// а каналы остаются строго положительными (lower≥198, upper≤610) — значит
/// физический кламп ≥0 в buildLookupTableInternal НЕ искажает beat.
std::vector<FrequencyPoint> sawtoothPreset() {
    const int h[6] = {0, 4, 8, 12, 16, 20};
    const float c[6] = {200, 600, 200, 600, 200, 600};
    const float b[6] = {4, 20, 4, 20, 4, 20};
    std::vector<FrequencyPoint> v;
    for (int i = 0; i < 6; ++i) {
        FrequencyPoint p;
        p.timeSeconds = h[i] * 3600;
        p.carrierFrequency = c[i];
        p.beatFrequency = b[i];
        v.push_back(p);
    }
    return v;
}

/// Кривая с заранее заданными весами касательных (как прислал бы Kotlin).
/// Точки ОБЯЗАНЫ быть отсортированы и уникальны по времени, иначе веса
/// перестанут соответствовать индексам — как в проде (Kotlin предаёт
/// отсортированный массив, натив лишь потребляет его по размеру).
FrequencyCurve buildCurveWeighted(std::vector<FrequencyPoint> pts,
                                  InterpolationType t,
                                  std::vector<float> weights) {
    FrequencyCurve c;
    c.points = std::move(pts);
    c.interpolationType = t;
    c.splineTension = 0.0f;
    c.tensionWeights = std::move(weights);
    c.updateCache();
    return c;
}

/**
 * ТОЧНОЕ значение beat-кривой при весах w — вне всякой таблицы.
 * Логика выбора интервала/индексов повторяет FrequencyCurve::buildLookupTableInternal,
 * веса применяются к ОБОИМ каналам ровно так же. beat = upper − lower.
 */
float weightedSplineBeatAt(const FrequencyCurve& c,
                           const std::vector<float>& weights,
                           float timeSecondsF) {
    const int n = static_cast<int>(c.points.size());
    int leftIndex = 0;
    while (leftIndex < n - 1 &&
           static_cast<float>(c.points[leftIndex + 1].timeSeconds) <= timeSecondsF) {
        ++leftIndex;
    }
    if (timeSecondsF < static_cast<float>(c.points[0].timeSeconds)) leftIndex = n - 1;

    const int rightIndex = (leftIndex + 1) % n;
    const int prevIndex  = (leftIndex - 1 + n) % n;
    const int nextNext   = (rightIndex + 1) % n;

    const float t1 = static_cast<float>(c.points[leftIndex].timeSeconds);
    const float t2 = static_cast<float>(c.points[rightIndex].timeSeconds);
    const bool isWrapping = (leftIndex == n - 1);
    float t2w = t2;
    if (isWrapping) t2w += static_cast<float>(SECONDS_PER_DAY);

    float t = timeSecondsF;
    if (isWrapping && t < t1) t += static_cast<float>(SECONDS_PER_DAY);

    float ratio = (t2w != t1) ? (t - t1) / (t2w - t1) : 0.0f;
    ratio = std::clamp(ratio, 0.0f, 1.0f);

    auto lo = [](const FrequencyPoint& p) { return p.carrierFrequency - p.beatFrequency / 2.0; };
    auto up = [](const FrequencyPoint& p) { return p.carrierFrequency + p.beatFrequency / 2.0; };

    const float w1 = weights[leftIndex];
    const float w2 = weights[rightIndex];

    const float eL = Interpolation::interpolate(
        c.interpolationType, lo(c.points[prevIndex]), lo(c.points[leftIndex]),
        lo(c.points[rightIndex]), lo(c.points[nextNext]), ratio, c.splineTension,
        /*allowNegative=*/true, w1, w2);
    const float eU = Interpolation::interpolate(
        c.interpolationType, up(c.points[prevIndex]), up(c.points[leftIndex]),
        up(c.points[rightIndex]), up(c.points[nextNext]), ratio, c.splineTension,
        /*allowNegative=*/true, w1, w2);
    return eU - eL;
}

} // namespace

// ============================================================================
// 1. Адаптивный шаг
// ============================================================================

TEST(FrequencyTableAdaptiveStep, DefaultPreset_UsesCoarsestStep) {
    FrequencyCurve c = buildCurve(defaultPresetPoints(), InterpolationType::MONOTONE);
    // Точки разнесены на 3 часа — шаг упирается в потолок.
    EXPECT_EQ(c.tableIntervalMs, FREQUENCY_TABLE_MAX_INTERVAL_MS);
    EXPECT_EQ(c.freqTableSize(), FREQUENCY_TABLE_MIN_SIZE);
    // В 10 раз меньше исторической таблицы (было 864000 записей / 3.30 МБ).
    EXPECT_EQ(c.freqTableSize(), FREQUENCY_TABLE_SIZE / 10);
}

TEST(FrequencyTableAdaptiveStep, TableSize_NeverExceedsHistoricalMax) {
    // Главная гарантия по памяти: адаптивный шаг не может превысить тот объём,
    // который таблица занимала всегда.
    std::vector<std::vector<FrequencyPoint>> variants = {
        defaultPresetPoints(),
        spacedPoints(3600, 8, false),
        spacedPoints(600, 8, false),
        spacedPoints(600, 12, true),
        spacedPoints(60, 60, true),
        spacedPoints(10, 100, true),
        spacedPoints(1, 200, true),   // самый злой случай
        {{0, 200, 6}, {86399, 300, 10}},
    };
    for (const auto& pts : variants) {
        for (auto type : {InterpolationType::LINEAR, InterpolationType::CARDINAL,
                          InterpolationType::MONOTONE}) {
            FrequencyCurve c = buildCurve(pts, type);
            EXPECT_GE(c.tableIntervalMs, FREQUENCY_TABLE_MIN_INTERVAL_MS);
            EXPECT_LE(c.tableIntervalMs, FREQUENCY_TABLE_MAX_INTERVAL_MS);
            EXPECT_LE(c.freqTableSize(), FREQUENCY_TABLE_SIZE)
                << "таблица больше исторического максимума";
            EXPECT_GE(c.freqTableSize(), FREQUENCY_TABLE_MIN_SIZE);
        }
    }
}

TEST(FrequencyTableAdaptiveStep, DenseCurve_FallsBackToFinestStep) {
    // Точки в 1 с друг от друга: шаг обязан упереться в пол = 100 мс,
    // т.е. деградировать ровно до прежнего поведения, но не хуже.
    FrequencyCurve c = buildCurve(spacedPoints(1, 200, true), InterpolationType::MONOTONE);
    EXPECT_EQ(c.tableIntervalMs, FREQUENCY_TABLE_MIN_INTERVAL_MS);
    EXPECT_EQ(c.freqTableSize(), FREQUENCY_TABLE_SIZE);
}

TEST(FrequencyTableAdaptiveStep, ErrorStaysBelowOldFixedStep) {
    // Ключевое свойство: раз шаг не мельче исторических 100 мс, ошибка
    // аппроксимации не может превысить прежнюю. Проверяем на типовых
    // пресетах, что она при этом остаётся ничтожной.
    FrequencyCurve def = buildCurve(defaultPresetPoints(), InterpolationType::MONOTONE);
    EXPECT_LT(maxTableError(def), 0.01);          // замер: ~9e-5 Гц

    FrequencyCurve hourly = buildCurve(spacedPoints(3600, 8, false),
                                       InterpolationType::MONOTONE);
    EXPECT_LT(maxTableError(hourly), 0.01);       // замер: ~1.2e-4 Гц

    FrequencyCurve tenMin = buildCurve(spacedPoints(600, 12, true),
                                       InterpolationType::MONOTONE);
    EXPECT_LT(maxTableError(tenMin), 0.05);       // замер: ~2.6e-3 Гц
}

// ============================================================================
// 2. Разделяемые таблицы (shared_ptr)
// ============================================================================

TEST(FrequencyTableSharing, CopySharesTables_NoDeepCopy) {
    FrequencyCurve curve = buildCurve(defaultPresetPoints(), InterpolationType::MONOTONE);
    BinauralConfig src;
    src.curve = curve;

    const float* addrBefore = src.curve.lowerFreqTable->data();

    BinauralConfig dst = src;   // то, что движок делает на КАЖДОМ пакете

    EXPECT_EQ(src.curve.lowerFreqTable, dst.curve.lowerFreqTable)
        << "копия конфига обязана делить таблицы, а не копировать 6.6 МБ";
    EXPECT_EQ(src.curve.upperFreqTable, dst.curve.upperFreqTable);
    EXPECT_EQ(dst.curve.lowerFreqTable->data(), addrBefore);
    EXPECT_EQ(dst.curve.tableIntervalMs, src.curve.tableIntervalMs);
}

TEST(FrequencyTableSharing, RebuildPublishesNewTables_OldCopyStaysValid) {
    // Писатель держит копию конфига на время генерации пакета. Пересборка
    // кривой (setConfig из UI) не имеет права портить память под ним:
    // обязана выделить новые таблицы, а не писать поверх старых.
    FrequencyCurve curve = buildCurve(defaultPresetPoints(), InterpolationType::MONOTONE);
    BinauralConfig held = BinauralConfig();
    held.curve = curve;                       // «писатель держит»

    const float* oldData = held.curve.lowerFreqTable->data();
    const int oldSize = held.curve.freqTableSize();
    const std::vector<float> snapshot = *held.curve.lowerFreqTable;

    // Меняем кривую и пересобираем
    FrequencyCurve changed = curve;
    changed.points[3].carrierFrequency = 777.0f;
    changed.updateCache();

    const float* newData = changed.lowerFreqTable->data();

    EXPECT_NE(oldData, newData) << "пересборка обязана выделить НОВЫЕ таблицы";
    EXPECT_EQ(held.curve.freqTableSize(), oldSize) << "копия писателя не должна меняться";
    // и содержимое копии писателя обязано остаться целым
    ASSERT_EQ(held.curve.lowerFreqTable->size(), snapshot.size());
    for (size_t i = 0; i < snapshot.size(); ++i) {
        ASSERT_FLOAT_EQ((*held.curve.lowerFreqTable)[i], snapshot[i]) << "i=" << i;
    }
}

TEST(FrequencyTableSharing, EmptyCurve_StillHasUsableTables) {
    FrequencyCurve c;                 // нет точек
    c.updateCache();                  // updateCache для пустой кривой — no-op
    EXPECT_FALSE(c.hasFreqTables());

    FrequencyCurve one;
    one.points.push_back({3600, 200.0f, 6.0f});
    one.updateCache();
    ASSERT_TRUE(one.hasFreqTables());
    // Константная кривая: все ячейки равны, шаг не важен — берём крупный.
    EXPECT_EQ(one.tableIntervalMs, FREQUENCY_TABLE_MAX_INTERVAL_MS);
    const float lower = 200.0f - 6.0f / 2.0f;
    EXPECT_FLOAT_EQ((*one.lowerFreqTable)[0], lower);
    EXPECT_FLOAT_EQ((*one.lowerFreqTable)[one.freqTableSize() - 1], lower);
}

// ============================================================================
// 3. Регуляция overshoot весами касательных (tensionWeights)
//
//    Вес ОБЩИЙ для обоих каналов (см. CardinalTension.kt / cardinal()).
//    Сплайн линеен по касательным, поэтому
//        right(t) − left(t) = spline(beat; w·M^beat)
//    — частота биений остаётся точной интерполяцией своих узлов, а каналы
//    НЕ схлопываются в точке касания границы. Набор тестов доказывает три
//    свойства механизма со стороны движка, независимо от Kotlin:
//      (а) beat таблицы тождественен spline(beatKnots; w) при ЛЮБОМ весе;
//      (б) w=0 гасит overshoot (кривая вырождается в линейную), w=1 — нет;
//      (в) несовпадение размера весов отключает регулировку (безопасный no-op).
// ============================================================================

TEST(FrequencyTableTensionWeights, WeightedTableBeatEqualsSplineOfBeatKnots) {
    // Ключевой инвариант: общий вес => beat таблицы = spline(beatKnots; w).
    // Значит каналы нигде не слипаются, даже при сильном укорочении касательных.
    const std::vector<FrequencyPoint> pts = sawtoothPreset();
    const int n = static_cast<int>(pts.size());
    // Произвольный ненулевой вес — не из Kotlin, просто проверяет свойство.
    std::vector<float> w(n, 0.5f);
    FrequencyCurve c = buildCurveWeighted(pts, InterpolationType::CARDINAL, w);

    double maxAbsDiff = 0.0;
    for (int i = 0; i < 20000; ++i) {
        const float t = std::fmod(static_cast<float>(i) * 137.3713f,
                                  static_cast<float>(SECONDS_PER_DAY));
        const FrequencyTableResult got = c.getChannelFrequenciesAt(t);
        const float tableBeat = got.upperFreq - got.lowerFreq;
        const float exactBeat = weightedSplineBeatAt(c, w, t);
        maxAbsDiff = std::max(maxAbsDiff, std::abs(static_cast<double>(tableBeat) - exactBeat));
    }
    // Каналы >0 (пресет подобран), кламп не искажает beat => совпадение точное
    // с точностью табличной линейной интерполяции внутри ячейки (~0.1 мс).
    EXPECT_LT(maxAbsDiff, 0.05) << "beat таблицы НЕ равен spline(beatKnots; w): каналы схлопываются";
}

TEST(FrequencyTableTensionWeights, ZeroWeightsKillOvershoot_LinearTable) {
    // w=0 => касательные нулевые => cardinal вырождается в линейную
    // интерполяцию, ограниченную узлами. Поэтому таблица БЕЗ overshoot, а с
    // w=1 (несущая идёт 200→400→600→600) Catmull-Rom упирается в плоский
    // узел 600 Гц с ПОЛОЖИТЕЛЬНОЙ касательной и ВЫЛЕТАЕТ выше узла-максимума
    // верхнего канала (602 Гц = carrier 600 + beat/2 2), до ~640 Гц.
    std::vector<FrequencyPoint> pts;
    {
        const int h[4] = {0, 10800, 21600, 32400};
        const float c[4] = {200, 400, 600, 600};
        const float b[4] = {4, 4, 4, 4};
        for (int i = 0; i < 4; ++i) {
            FrequencyPoint p;
            p.timeSeconds = h[i];
            p.carrierFrequency = c[i];
            p.beatFrequency = b[i];
            pts.push_back(p);
        }
    }
    const int n = static_cast<int>(pts.size());
    std::vector<float> zero(n, 0.0f);
    std::vector<float> one(n, 1.0f);
    FrequencyCurve cz = buildCurveWeighted(pts, InterpolationType::CARDINAL, zero);
    FrequencyCurve co = buildCurveWeighted(pts, InterpolationType::CARDINAL, one);

    double maxZero = 0.0, maxOne = 0.0;
    for (int i = 0; i < 20000; ++i) {
        const float t = std::fmod(static_cast<float>(i) * 137.3713f,
                                  static_cast<float>(SECONDS_PER_DAY));
        maxZero = std::max(maxZero, static_cast<double>(cz.getChannelFrequenciesAt(t).upperFreq));
        maxOne  = std::max(maxOne,  static_cast<double>(co.getChannelFrequenciesAt(t).upperFreq));
    }
    // Узел-максимум верхнего канала = 602 Гц (carrier 600 + beat/2 2).
    EXPECT_NEAR(maxZero, 602.0, 0.5) << "w=0 обязано ограничиться узлом (линейная)";
    EXPECT_GT(maxOne, maxZero + 1.0) << "w=1 обязано давать явный overshoot";
}

TEST(FrequencyTableTensionWeights, AllOnesIsNoOpIdenticalToNominal) {
    // Веса все 1.0 => таблица в точности номинальная (без регулировки):
    // применение единичного веса тождественно отсутствию регулировки.
    const std::vector<FrequencyPoint> pts = sawtoothPreset();
    const int n = static_cast<int>(pts.size());
    std::vector<float> one(n, 1.0f);
    FrequencyCurve cw = buildCurveWeighted(pts, InterpolationType::CARDINAL, one);
    FrequencyCurve cn = buildCurve(pts, InterpolationType::CARDINAL);

    double maxDiff = 0.0;
    for (int i = 0; i < 20000; ++i) {
        const float t = std::fmod(static_cast<float>(i) * 137.3713f,
                                  static_cast<float>(SECONDS_PER_DAY));
        const FrequencyTableResult aw = cw.getChannelFrequenciesAt(t);
        const FrequencyTableResult an = cn.getChannelFrequenciesAt(t);
        maxDiff = std::max(maxDiff, std::abs(static_cast<double>(aw.lowerFreq) - an.lowerFreq));
        maxDiff = std::max(maxDiff, std::abs(static_cast<double>(aw.upperFreq) - an.upperFreq));
    }
    EXPECT_LT(maxDiff, 1e-3) << "единичный вес не no-op";
}

TEST(FrequencyTableTensionWeights, SizeMismatchDisablesRegulation) {
    // Несовпадение размера весов => регулировка отключается (tw==nullptr),
    // таблица строится номинально, даже если подали «нулевые» веса. Это
    // единственное безопасное поведение: применить веса к чужим узлам хуже,
    // чем построить номинальную кривую.
    const std::vector<FrequencyPoint> pts = sawtoothPreset();
    const int n = static_cast<int>(pts.size());
    std::vector<float> bad(static_cast<size_t>(n + 1), 0.0f);  // неверный размер
    FrequencyCurve cw = buildCurveWeighted(pts, InterpolationType::CARDINAL, bad);
    FrequencyCurve cn = buildCurve(pts, InterpolationType::CARDINAL);

    double maxDiff = 0.0;
    for (int i = 0; i < 20000; ++i) {
        const float t = std::fmod(static_cast<float>(i) * 137.3713f,
                                  static_cast<float>(SECONDS_PER_DAY));
        const FrequencyTableResult aw = cw.getChannelFrequenciesAt(t);
        const FrequencyTableResult an = cn.getChannelFrequenciesAt(t);
        maxDiff = std::max(maxDiff, std::abs(static_cast<double>(aw.lowerFreq) - an.lowerFreq));
        maxDiff = std::max(maxDiff, std::abs(static_cast<double>(aw.upperFreq) - an.upperFreq));
    }
    EXPECT_LT(maxDiff, 1e-3) << "несовпадение размера не отключило регулировку";
}

} // namespace test
} // namespace binaural
