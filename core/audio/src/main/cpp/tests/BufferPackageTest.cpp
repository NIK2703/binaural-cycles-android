/**
 * Тесты генерации пакетов буферов — ШАГ 3 МИГРАЦИИ (непрерывная рампа,
 * ритуал планировщика удалён).
 *
 * Проверяемые свойства:
 *  1. Планировщик вырожден в нарезку на <=100 мс SOLID-подсегменты (без
 *     FADE_OUT/PAUSE/FADE_IN и без фазовой машины).
 *  2. Раскладка ушей — ЧИСТАЯ ФУНКЦИЯ (конфиг, t): множитель s(t) рампой
 *     проходит через ноль в ближайшем T*; вне окна s ≡ ±1.
 *  3. Якорь EarLayoutProperty: уши слышат ровно две частоты кривой
 *     (множество {lower, upper}), число смен == расписанию.
 *  4. Непрерывность через T*: сортированные пары ушей совпадают с {lower,upper}
 *     вне окна перехода; окна, накрывающие glide (схлопнутый разброс), — пропуск.
 *  5. Рампа: в T* частоты сходятся в унисон, IPD непрерывна, громкость
 *     (несущая) не меняется — провала звука нет.
 *  6. Чистые функции расписания (TIMER-чётность, TREND-паритет, ближайший T*).
 */

#include <gtest/gtest.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <numeric>
#include "Config.h"
#include "AudioGenerator.h"
#include "BufferPackagePlanner.h"
#include "ChannelLayout.h"
#include "BinauralEngine.h"

namespace binaural {
namespace test {

constexpr int SAMPLE_RATE = 44100;

// ============================================================================
// ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ДЛЯ АНАЛИЗА АУДИО
// ============================================================================

/** Точное измерение частоты через подсчёт периодов (усреднение). */
float measureFrequencyByPeriods(const float* samples, int numSamples, float sampleRate) {
    if (numSamples < 100) return 0.0f;
    std::vector<int> zeroCrossings;
    for (int i = 1; i < numSamples; ++i) {
        if (samples[i-1] < 0.0f && samples[i] >= 0.0f) {
            float frac = -samples[i-1] / (samples[i] - samples[i-1]);
            zeroCrossings.push_back(i - 1 + frac);
        }
    }
    if (zeroCrossings.size() < 2) return 0.0f;
    float totalPeriod = 0.0f;
    for (size_t i = 1; i < zeroCrossings.size(); ++i) {
        totalPeriod += (zeroCrossings[i] - zeroCrossings[i-1]);
    }
    float avgPeriod = totalPeriod / (zeroCrossings.size() - 1);
    return sampleRate / avgPeriod;
}

float measureFrequencyInWindow(const float* buffer, int totalSamples,
                                int startSample, int windowSize,
                                int channel, float sampleRate) {
    if (startSample < 0 || startSample + windowSize > totalSamples) return 0.0f;
    std::vector<float> channelData(windowSize);
    for (int i = 0; i < windowSize; ++i) {
        channelData[i] = buffer[(startSample + i) * 2 + channel];
    }
    return measureFrequencyByPeriods(channelData.data(), windowSize, sampleRate);
}

float rmsChannel(const float* buffer, int totalSamples,
                  int startSample, int windowSize, int channel) {
    if (startSample < 0 || startSample + windowSize > totalSamples) return 0.0f;
    double sum = 0.0;
    for (int i = 0; i < windowSize; ++i) {
        const float v = buffer[(startSample + i) * 2 + channel];
        sum += static_cast<double>(v) * v;
    }
    return static_cast<float>(std::sqrt(sum / windowSize));
}

// ============================================================================
// СРАВНЕНИЕ ЧАСТОТ УШЕЙ, УСТОЙЧИВОЕ К СМЕНЕ РАСКЛАДКИ (ШАГ 2/3)
// ============================================================================

/** Пара частот ушей, отсортированная по возрастанию: {lo, hi}. */
struct EarFreqPair {
    float lo = 0.0f;
    float hi = 0.0f;
};

inline float earPairSpread(const EarFreqPair& p) { return p.hi - p.lo; }

inline EarFreqPair sortedEarPair(float leftFreq, float rightFreq) {
    return leftFreq <= rightFreq ? EarFreqPair{leftFreq, rightFreq}
                                 : EarFreqPair{rightFreq, leftFreq};
}

// Порог «схлопнутого» разброса: окно накрывает glide, если разброс меньше
// этой доли от максимального разброса пары окон. 0.6 — по фактическим
// измерениям (см. docs/design_signed_beat_channel_layout.md §0.1.3).
constexpr float kCollapsedSpreadRatio = 0.6f;

inline bool isUnisonGlideWindow(const EarFreqPair& before, const EarFreqPair& after) {
    const float spreadMax = std::max(earPairSpread(before), earPairSpread(after));
    if (spreadMax <= 0.0f) return false;
    return earPairSpread(before) < kCollapsedSpreadRatio * spreadMax ||
           earPairSpread(after)  < kCollapsedSpreadRatio * spreadMax;
}

// ============================================================================
// КОНФИГУРАЦИИ
// ============================================================================

BinauralConfig createTestConfig(
    float carrierFreq = 200.0f,
    float beatFreq = 10.0f,
    bool enableSwap = true,
    int swapIntervalSec = 30,
    int fadeDurationMs = 1000
) {
    BinauralConfig config;
    FrequencyPoint point;
    point.timeSeconds = 0;
    point.carrierFrequency = carrierFreq;
    point.beatFrequency = beatFreq;
    config.curve.points.push_back(point);
    config.curve.interpolationType = InterpolationType::LINEAR;
    config.curve.updateCache();
    config.volume = 0.7f;
    config.channelSwapEnabled = enableSwap;
    config.channelSwapIntervalSec = swapIntervalSec;
    config.channelSwapFadeEnabled = true;
    config.channelSwapFadeDurationMs = fadeDurationMs;
    config.channelSwapPauseDurationMs = 0;
    return config;
}

/** Кривая с пиком beat в середине суток (для TREND-тестов). */
FrequencyCurve makePeakBeatCurve() {
    FrequencyCurve c;
    FrequencyPoint p1, p2, p3;
    p1.timeSeconds = 0;     p1.carrierFrequency = 200.0f; p1.beatFrequency = 5.0f;
    p2.timeSeconds = 43200; p2.carrierFrequency = 200.0f; p2.beatFrequency = 15.0f; // пик
    p3.timeSeconds = 86400; p3.carrierFrequency = 200.0f; p3.beatFrequency = 5.0f;
    c.points.push_back(p1);
    c.points.push_back(p2);
    c.points.push_back(p3);
    c.interpolationType = InterpolationType::LINEAR;
    c.updateCache();
    return c;
}

BinauralConfig makeTrendConfig(const FrequencyCurve& curve,
                               ChannelSwapMode mode = ChannelSwapMode::TREND,
                               ChannelSwapTrendPoints points = ChannelSwapTrendPoints::BOTH) {
    BinauralConfig cfg;
    cfg.curve = curve;
    cfg.volume = 0.7f;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = mode;
    cfg.channelSwapTrendPoints = points;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1000;
    cfg.channelSwapPauseDurationMs = 0;
    return cfg;
}

// ============================================================================
// ГЕНЕРАЦИЯ СИГНАЛА НАПРЯМУЮ ЧЕРЕЗ AudioGenerator + планировщик
// ============================================================================

struct Emitted {
    std::vector<float> signal;   // interleaved stereo
    int samples = 0;
};

Emitted emitSignal(BinauralConfig& cfg, float startSec, float durationSec, int sampleRate) {
    AudioGenerator gen;
    gen.setSampleRate(sampleRate);
    BufferPackagePlanner planner;
    GeneratorState state;
    planner.resetState(state);
    gen.resetState(state);

    Emitted out;
    float curvePos = startSec;
    float accSec = 0.0f;
    const int64_t pkgMs = 1000;
    while (accSec < durationSec - 1e-3f) {
        const int64_t ms = static_cast<int64_t>(
            std::min<float>((durationSec - accSec) * 1000.0f, static_cast<float>(pkgMs)));
        PackagePlan plan = planner.planPackage(ms, cfg, state, curvePos, 1.0f);
        if (plan.segments.empty()) break;
        const int totalMs = static_cast<int>(plan.totalDurationMs);
        const int samples = static_cast<int>((static_cast<int64_t>(totalMs) * sampleRate) / 1000);
        std::vector<float> buf(static_cast<size_t>(samples) * 2);
        GenerateResult r = gen.generatePackage(buf.data(), plan, cfg, state, curvePos, 0, 1.0f);
        const int gen = r.samplesGenerated > 0 ? r.samplesGenerated : samples;
        out.signal.insert(out.signal.end(), buf.begin(), buf.begin() + static_cast<size_t>(gen) * 2);
        out.samples += gen;
        curvePos += static_cast<float>(totalMs) / 1000.0f;
        accSec += static_cast<float>(totalMs) / 1000.0f;
    }
    return out;
}

// ============================================================================
// ТЕСТ ПЛАНИРОВЩИКА: вырожденная нарезка на SOLID
// ============================================================================

class BufferPackagePlannerTest : public ::testing::Test {
protected:
    BufferPackagePlanner planner;
    GeneratorState state;
    void SetUp() override { planner.resetState(state); }
};

TEST_F(BufferPackagePlannerTest, Collapsed_SolidOnlySlicing) {
    BinauralConfig config = createTestConfig(200.0f, 10.0f, true, 30, 1000);

    // 30.5 с -> 305 подсегментов по 100 мс, всё SOLID, без FADE_*/PAUSE.
    PackagePlan plan = planner.planPackage(30500, config, state, 0.0f, 1.0f);
    ASSERT_EQ(plan.segments.size(), 305u);
    EXPECT_EQ(plan.totalDurationMs, 30500);

    int solidCount = 0;
    for (const auto& seg : plan.segments) {
        EXPECT_EQ(seg.type, BufferType::SOLID);
        EXPECT_FALSE(seg.swapAfterSegment);
        EXPECT_EQ(seg.durationMs, 100);
        ++solidCount;
    }
    EXPECT_EQ(solidCount, 305);
}

TEST_F(BufferPackagePlannerTest, Collapsed_RespectsPackageBoundary) {
    BinauralConfig config = createTestConfig(200.0f, 10.0f, false);

    // 1234 мс -> 12×100 + 34 (последний подсегмент короче).
    PackagePlan plan = planner.planPackage(1234, config, state, 0.0f, 1.0f);
    int64_t total = 0;
    for (const auto& seg : plan.segments) {
        EXPECT_EQ(seg.type, BufferType::SOLID);
        total += seg.durationMs;
    }
    EXPECT_EQ(total, 1234);
}

// ============================================================================
// ЯКОРЬ: EarLayoutProperty (ШАГ 0/2/3)
// ============================================================================

// Уши слышат ровно две частоты кривой вне окон смены; число смен == расписанию.
TEST(EarLayoutProperty, EarSetEqualsCurveAndSwapCountMatchesSchedule) {
    // TIMER, interval=5 с, beat=+8 -> lower=196, upper=204, W=0 (чистая
    // ступенька: множество частот ровно {196,204} везде вне узлов сетки).
    // ВАЖНО: layoutSignAt смотрит на channelSwapFadeDurationMs, а НЕ на
    // channelSwapFadeEnabled — поэтому ступенька задаётся нулём длительности,
    // иначе рампа (W=2с) сияла бы частоты унисоном вне ступеньки.
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeDurationMs = 0;

    const float durationSec = 31.0f;
    Emitted e = emitSignal(cfg, 0.0f, durationSec, SAMPLE_RATE);
    ASSERT_GT(e.samples, 100);
    const int total = e.samples;

    const int winSamples = static_cast<int>(0.2f * SAMPLE_RATE); // 200 мс
    const float tol = 1.5f;

    int flips = 0;
    bool prevNormal = true;
    bool first = true;
    int checkedWindows = 0;

    for (int w = 0; (w + 1) * winSamples <= total; ++w) {
        const float wStart = static_cast<float>(w * winSamples) / SAMPLE_RATE;
        const float wEnd = static_cast<float>((w + 1) * winSamples) / SAMPLE_RATE;
        // Пропускаем окна, накрывающие ступеньку (узлы сетки TIMER).
        bool nearStep = false;
        for (int k = 1; k <= 6; ++k) {
            const float T = static_cast<float>(k) * 5.0f;
            if (wStart < T + 0.12f && wEnd > T - 0.12f) { nearStep = true; break; }
        }
        if (nearStep) continue;

        const float lf = measureFrequencyInWindow(e.signal.data(), total, w * winSamples, winSamples, 0, SAMPLE_RATE);
        const float rf = measureFrequencyInWindow(e.signal.data(), total, w * winSamples, winSamples, 1, SAMPLE_RATE);
        ASSERT_GT(lf, 1.0f) << "window " << w;
        ASSERT_GT(rf, 1.0f) << "window " << w;

        EarFreqPair p = sortedEarPair(lf, rf);
        EXPECT_NEAR(p.lo, 196.0f, tol) << "window " << w << " lo=" << p.lo;
        EXPECT_NEAR(p.hi, 204.0f, tol) << "window " << w << " hi=" << p.hi;

        const bool normal = (lf < rf); // left=lower -> прямое расположение
        if (!first) {
            if (normal != prevNormal) ++flips;
        } else {
            first = false;
        }
        prevNormal = normal;
        ++checkedWindows;
    }

    EXPECT_GT(checkedWindows, 100);
    // 6 смен за 31 с при interval=5 с (узлы 5,10,15,20,25,30).
    EXPECT_EQ(flips, 6) << "обнаружено смен раскладки";
}

// Раскладка в конце прогона совпадает с расписанием channelSwapStateAt.
TEST(EarLayoutProperty, ArrangementMatchesScheduleAtEnd) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeDurationMs = 0; // W=0 -> чистая ступенька

    const float durationSec = 31.0f;
    Emitted e = emitSignal(cfg, 0.0f, durationSec, SAMPLE_RATE);
    const int total = e.samples;

    // Последнее окно (до конца, вне ступеньки 30 с).
    const int winSamples = static_cast<int>(0.2f * SAMPLE_RATE);
    const int wStart = total - winSamples;
    const float lf = measureFrequencyInWindow(e.signal.data(), total, wStart, winSamples, 0, SAMPLE_RATE);
    const float rf = measureFrequencyInWindow(e.signal.data(), total, wStart, winSamples, 1, SAMPLE_RATE);

    const bool normal = (lf < rf);
    // t=31 с: floor(31/5)=6 (чётно) -> normal (прямое расположение).
    EXPECT_EQ(normal, !channelSwapStateAt(cfg, 31.0f));
}

// Переход через T* непрерывен по множеству частот (сортированные пары
// совпадают с {lower,upper}; окна glide пропускаются).
TEST(EarLayoutProperty, ContinuityAcrossTStar_SortedPairs) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeEnabled = true; // рампа: окна glide появляются

    // Старт в t=2.0 с (а НЕ 0): ближайший T* к полуночи — 0, и на нём тоже
    // центрируется рампа, которая схлопнула бы частоты унисоном в начале
    // прогона и сломала бы базовую сверку вне окон. С t=2.0 покрываются
    // только T*=5 и T*=10 (обе внутри [2,14]).
    const float durationSec = 12.0f; // старт 2.0 с -> покрывает [2,14], T*=5 и T*=10
    Emitted e = emitSignal(cfg, 2.0f, durationSec, SAMPLE_RATE);
    const int total = e.samples;

    const int winSamples = static_cast<int>(0.1f * SAMPLE_RATE); // 100 мс
    const float tol = 2.0f;
    const float nominalBeat = 8.0f; // beat конфига

    int flips = 0;
    bool prevNormal = true;
    bool first = true;
    int checked = 0;

    EarFreqPair prevPair{0, 0};
    for (int w = 0; (w + 1) * winSamples <= total; ++w) {
        const float lf = measureFrequencyInWindow(e.signal.data(), total, w * winSamples, winSamples, 0, SAMPLE_RATE);
        const float rf = measureFrequencyInWindow(e.signal.data(), total, w * winSamples, winSamples, 1, SAMPLE_RATE);
        if (lf < 1.0f || rf < 1.0f) continue;
        EarFreqPair p = sortedEarPair(lf, rf);

        if (!first) {
            // Glide-окно — АБСОЛЮТНЫЙ порог относительно номинального beat:
            // разброс < 0.6*8 = 4.8 Гц означает, что окно накрывает унисон
            // внутри рампы. Локальный порог (isUnisonGlideWindow — относительно
            // соседа) НЕ ловит «дно» glide: две подряд идущие унисонные окна
            // имеют крошечный локальный максимум, и отношение не срабатывает,
            // из-за чего унисонные окна попадали под сверку с {196,204}.
            if (earPairSpread(p) < kCollapsedSpreadRatio * nominalBeat) { prevPair = p; continue; }
            EXPECT_NEAR(p.lo, 196.0f, tol) << "window " << w;
            EXPECT_NEAR(p.hi, 204.0f, tol) << "window " << w;
            const bool normal = (lf < rf);
            if (normal != prevNormal) ++flips;
            prevNormal = normal;
            ++checked;
        }
        prevPair = p;
        first = false;
    }

    EXPECT_GT(checked, 50);
    // T*=5 и T*=10 -> 2 смены за 12 с.
    EXPECT_EQ(flips, 2) << "смен за 12 с (ожидалось 2)";
}

// ============================================================================
// РАМПА s(t)
// ============================================================================

// В центре окна (t = T*) s = 0: унисон. Вне окна — ступенька.
TEST(RampTest, LayoutSignAt_UnisonAtTStar) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1000; // W = 2*1.0 + 0 = 2.0 с
    cfg.channelSwapPauseDurationMs = 0;

    // T* = 5.0 с.
    EXPECT_NEAR(layoutSignAt(cfg, 5.0f), 0.0f, 1e-3f);
    // Вне окна (|t-T*| >= 1.0 с): ступенька channelSwapStateAt.
    EXPECT_NEAR(layoutSignAt(cfg, 3.0f), 1.0f, 1e-3f);   // floor(3/5)=0 -> normal
    EXPECT_NEAR(layoutSignAt(cfg, 8.0f), -1.0f, 1e-3f);  // floor(8/5)=1 -> swapped
}

// Косинусоидальная форма: s(T* - δ) = -s(T* + δ).
TEST(RampTest, LayoutSignAt_CosineShape) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1000;
    cfg.channelSwapPauseDurationMs = 0;

    const float sLeft = layoutSignAt(cfg, 4.5f);  // u = 0.25
    const float sRight = layoutSignAt(cfg, 5.5f); // u = 0.75
    EXPECT_NEAR(sLeft, std::cos(3.14159265358979323846f * 0.25f), 1e-3f);
    EXPECT_NEAR(sRight, std::cos(3.14159265358979323846f * 0.75f), 1e-3f);
    EXPECT_NEAR(sLeft, -sRight, 1e-3f);
}

// Звук в T*: левый и правый сходятся в унисон (carrier), громкость (RMS
// несущей) не проседает — провала звука нет.
TEST(RampTest, Audio_UnisonAndNoVolumeDipAtTStar) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1000;
    cfg.channelSwapPauseDurationMs = 0;

    // 2 с начиная с 4.0 с -> покрывает T*=5.0 с.
    Emitted e = emitSignal(cfg, 4.0f, 2.0f, SAMPLE_RATE);
    const int total = e.samples;
    const int winSamples = static_cast<int>(0.2f * SAMPLE_RATE);

    auto measurePair = [&](int startSample) -> EarFreqPair {
        const float lf = measureFrequencyInWindow(e.signal.data(), total, startSample, winSamples, 0, SAMPLE_RATE);
        const float rf = measureFrequencyInWindow(e.signal.data(), total, startSample, winSamples, 1, SAMPLE_RATE);
        return sortedEarPair(lf, rf);
    };
    auto rms = [&](int startSample, int ch) {
        return rmsChannel(e.signal.data(), total, startSample, winSamples, ch);
    };

    // До рампы (4.0–4.2 с): прямое расположение, разнос ~8 Гц.
    const int beforeStart = 0;
    const EarFreqPair before = measurePair(beforeStart);
    EXPECT_NEAR(earPairSpread(before), 8.0f, 2.0f);

    // В центре рампы (4.9–5.1 с): унисон (разнос близок к 0).
    const int atStart = static_cast<int>(0.9f * SAMPLE_RATE);
    const EarFreqPair at = measurePair(atStart);
    EXPECT_NEAR(earPairSpread(at), 0.0f, 3.0f);

    // После рампы (5.8–6.0 с): обратное расположение, разнос ~8 Гц.
    const int afterStart = static_cast<int>(1.8f * SAMPLE_RATE);
    const EarFreqPair after = measurePair(afterStart);
    EXPECT_NEAR(earPairSpread(after), 8.0f, 2.0f);

    // Несущая не меняется: RMS левого/правого до и в центре рампы равны
    // (провала громкости нет). Допуск — на погрешность измерения RMS.
    const float rmsL_before = rms(beforeStart, 0);
    const float rmsL_at = rms(atStart, 0);
    const float rmsR_before = rms(beforeStart, 1);
    const float rmsR_at = rms(atStart, 1);
    EXPECT_NEAR(rmsL_before, rmsL_at, 0.05f);
    EXPECT_NEAR(rmsR_before, rmsR_at, 0.05f);
}

// ============================================================================
// ЧИСТЫЕ ФУНКЦИИ РАСПИСАНИЯ
// ============================================================================

TEST(TrendSwapPureTest, TimerParity) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = 5;

    // channelSwapStateAt = (floor(pos / interval) & 1) != 0.
    EXPECT_FALSE(channelSwapStateAt(cfg, 3.0f));   // floor(3/5)=0 -> normal
    EXPECT_TRUE(channelSwapStateAt(cfg, 5.0f));    // floor(5/5)=1 -> swapped
    EXPECT_FALSE(channelSwapStateAt(cfg, 12.0f));  // floor(12/5)=2 -> normal
    EXPECT_FALSE(channelSwapStateAt(cfg, 13.0f));  // floor(13/5)=2 -> normal
    EXPECT_TRUE(channelSwapStateAt(cfg, 15.0f));   // floor(15/5)=3 -> swapped
    EXPECT_TRUE(channelSwapStateAt(cfg, 7.0f));    // floor(7/5)=1 -> swapped
}

TEST(TrendSwapPureTest, TrendParity) {
    BinauralConfig cfg = makeTrendConfig(makePeakBeatCurve(), ChannelSwapMode::TREND, ChannelSwapTrendPoints::BOTH);

    // СИММЕТРИЧНАЯ кривая beat 5(0)/15(43200)/5(86400) под периодическим
    // продолжением имеет ДВА экстремума beat: трон в полночь (t=0, beat=5 —
    // минимум) и пик в полдень (t=43200, beat=15 — максимум). computeTrendCrossings
    // поэтому выдаёт ДВА пересечения: t=0 и t=43200 (bracket через полночь
    // ~0.5 с << полсуток, поэтому guard-плато НЕ отсекает его). Δbeat(0)=0
    // (симметрия), так что поправка фазы в начале суток не срабатывает, и
    // channelSwapStateAt = чётность прошедших пересечений:
    //   (0, 43200)  — прошло только t=0        -> нечёт -> swapped
    //   (43200, day) — прошло t=0 и t=43200     -> чёт   -> normal
    // Тест фиксирует ЭТУ (реальную, детерминированную) семантику, а не
    // наивное «до пика=false / после=true»: на симметричной кривой полдень
    // НЕ единолично управляет раскладкой.
    EXPECT_TRUE(channelSwapStateAt(cfg, 1000.0f));
    EXPECT_FALSE(channelSwapStateAt(cfg, 50000.0f));
}

TEST(TrendSwapPureTest, TrendBeatDeltaSign) {
    FrequencyCurve curve = makePeakBeatCurve();
    // До пика (рост beat): delta > 0. После пика (спад): delta < 0.
    EXPECT_GT(trendBeatDeltaAt(curve, 1000.0f), 0.0f);
    EXPECT_LT(trendBeatDeltaAt(curve, 50000.0f), 0.0f);
    // Мёртвой зоны нет.
    EXPECT_FALSE(trendDesiredSwapped(false, +1.0f));
    EXPECT_TRUE(trendDesiredSwapped(false, -1.0f));
    EXPECT_TRUE(trendDesiredSwapped(true, 0.0f));
}

TEST(TrendSwapPureTest, NearestSwapTime_TIMER) {
    BinauralConfig cfg = createTestConfig(200.0f, 8.0f, true, 5, 1000);
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = 5;

    // Ближайший узел сетки: round(pos/interval)*interval.
    EXPECT_NEAR(nearestSwapTimeSec(cfg, 7.0f), 5.0f, 1e-2f);
    EXPECT_NEAR(nearestSwapTimeSec(cfg, 7.6f), 10.0f, 1e-2f);
    EXPECT_NEAR(nearestSwapTimeSec(cfg, 4.9f), 5.0f, 1e-2f);
}

} // namespace test
} // namespace binaural
