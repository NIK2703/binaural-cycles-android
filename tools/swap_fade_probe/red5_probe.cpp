// Зонд ред. 5 — КОНСТРУКТИВНАЯ ВАЛИДНОСТЬ процедуры смены раскладки.
//
// Проверяет ровно то, что внедрено в ChannelLayout.h (nearestSwapProcedure):
//   TIMER — valid ⇔ channelSwapIntervalSec > 0;
//   TREND — valid ⇔ numSelectedCrossings(cfg) >= 1.
// БЕЗ nextafter / nextafterf / ±inf — чистая float-конструкция.
//
// Цель: убедиться, что на хосте (x86 g++) процедура даёт valid=1 и огибающая
// проваливается в 0 в T*, а в точках без переворота — valid=0.
#include <cstdio>
#include <cmath>
#include <cstdint>
#include <vector>

#include "Config.h"
#include "ChannelLayout.h"

using namespace binaural;

static TrendCrossing mk(float t, bool toSwapped) {
    TrendCrossing c;
    c.timeSec = t;
    c.toSwapped = toSwapped;
    return c;
}

// ---- TIMER ----
static void probeTimer(const char* label, float t, int intervalSec, bool fade) {
    BinauralConfig cfg;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = intervalSec;
    cfg.channelSwapFadeEnabled = fade;
    cfg.channelSwapFadeDurationMs = 2000;
    cfg.channelSwapPauseDurationMs = 0;

    const SwapProcedure p = nearestSwapProcedure(cfg, t);
    const float gain = layoutGainAt(cfg, t);
    printf("[TIMER %s] t=%.3f int=%d fade=%d => valid=%d Tstar=%.3f F=%.3f gain=%.5f\n",
           label, static_cast<double>(t), intervalSec, fade ? 1 : 0,
           p.valid ? 1 : 0, static_cast<double>(p.tStarSec),
           static_cast<double>(p.fadeSec), static_cast<double>(gain));
}

// ---- TREND ----
static void probeTrend(const char* label, float t,
                       ChannelSwapTrendPoints pts, bool fade) {
    BinauralConfig cfg;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TREND;
    cfg.channelSwapTrendPoints = pts;
    cfg.channelSwapFadeEnabled = fade;
    cfg.channelSwapFadeDurationMs = 1500;
    cfg.channelSwapPauseDurationMs = 500;
    cfg.curve.trendCrossingsValid = true;
    // Несколько выбранных переходов в течение суток.
    cfg.curve.trendCrossings = {
        mk(1000.0f, true),    // peaks
        mk(20000.0f, false),  // valleys
        mk(40000.0f, true),   // peaks
        mk(70000.0f, false),  // valleys
    };

    const int64_t n = numSelectedCrossings(cfg);
    const SwapProcedure p = nearestSwapProcedure(cfg, t);
    const float gain = layoutGainAt(cfg, t);
    printf("[TREND %s] t=%.3f pts=%d fade=%d nSel=%lld => valid=%d Tstar=%.3f gain=%.5f\n",
           label, static_cast<double>(t), (int)pts, fade ? 1 : 0,
           (long long)n, p.valid ? 1 : 0,
           static_cast<double>(p.tStarSec), static_cast<double>(gain));
}

// ---- TREND БЕЗ переходов (пологая кривая) ----
static void probeTrendEmpty(const char* label, float t) {
    BinauralConfig cfg;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TREND;
    cfg.channelSwapTrendPoints = ChannelSwapTrendPoints::PEAKS;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1500;
    cfg.channelSwapPauseDurationMs = 500;
    cfg.curve.trendCrossingsValid = true;
    cfg.curve.trendCrossings.clear();  // нет ни одного перехода

    const int64_t n = numSelectedCrossings(cfg);
    const SwapProcedure p = nearestSwapProcedure(cfg, t);
    const float gain = layoutGainAt(cfg, t);
    printf("[TREND-EMPTY %s] t=%.3f nSel=%lld => valid=%d gain=%.5f\n",
           label, static_cast<double>(t), (long long)n,
           p.valid ? 1 : 0, static_cast<double>(gain));
}

int main() {
    printf("=== РЕД. 5: конструктивная валидность (хост) ===\n\n");

    // TIMER: интервал > 0 → valid=1 даже вечером (где раньше ε-баг гасил).
    probeTimer("утро", 27.0f, 30, true);
    probeTimer("из лога 41430", 41430.164f, 30, true);
    probeTimer("вечер 85797", 85797.0f, 30, true);
    // TIMER: интервал = 0 → valid=0 (нет сетки).
    probeTimer("interval=0", 41430.164f, 0, true);

    printf("\n");
    // TREND: есть переходы → valid=1 в любой точке суток.
    probeTrend("утро BOTH", 100.0f, ChannelSwapTrendPoints::BOTH, true);
    probeTrend("середина PEAKS", 15000.0f, ChannelSwapTrendPoints::PEAKS, true);
    probeTrend("вечер TROUGHS", 50000.0f, ChannelSwapTrendPoints::TROUGHS, true);
    // TREND без переходов → valid=0.
    probeTrendEmpty("пологая", 41430.164f);

    printf("\n");
    // Проверка, что огибающая действительно проваливается в 0 ровно в T* (TIMER int=30).
    {
        BinauralConfig cfg;
        cfg.channelSwapEnabled = true;
        cfg.channelSwapMode = ChannelSwapMode::TIMER;
        cfg.channelSwapIntervalSec = 30;
        cfg.channelSwapFadeEnabled = true;
        cfg.channelSwapFadeDurationMs = 2000;
        cfg.channelSwapPauseDurationMs = 0;
        const SwapProcedure p = nearestSwapProcedure(cfg, 41430.164f);
        const float gAtStar = layoutGainAt(cfg, p.tStarSec);
        printf("[T* check] Tstar=%.3f gain(T*)=%.6f (должен быть ~0)\n",
               static_cast<double>(p.tStarSec), static_cast<double>(gAtStar));
    }

    return 0;
}
