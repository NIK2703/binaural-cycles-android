// Зонд: воспроизводит РОВНО ту ситуацию, что видна в логе устройства.
//
//   SWAPDIAG t=41430.164 | en=1 mode=0 int=30 fadeEn=1 fadeMs=2000 pauseMs=0
//            | proc valid=0 Tstar=0.000 F=0.0000 P=0.0000 | gain=1.0000
//
// Считает по шагам nearestSwapTimeSec / channelSwapStateAt / nearestSwapProcedure
// и печатает каждое промежуточное значение, чтобы увидеть, на каком шаге
// процедура объявляется невалидной.
#include <cstdio>
#include <cmath>
#include <limits>
#include <vector>

#include "Config.h"
#include "ChannelLayout.h"

using namespace binaural;

static void probe(const char* label, float t) {
    BinauralConfig cfg;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = 30;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 2000;
    cfg.channelSwapPauseDurationMs = 0;

    constexpr float dayF = static_cast<float>(SECONDS_PER_DAY);
    const float tt = std::fmod(t, dayF);
    const float pos = (tt < 0.0f) ? tt + dayF : tt;
    const float interval = static_cast<float>(cfg.channelSwapIntervalSec);
    const float ratio = pos / interval;
    const float k = std::round(ratio);
    float T = std::fmod(k * interval, dayF);
    if (T < 0.0f) T += dayF;

    const float before = std::nextafterf(T, -std::numeric_limits<float>::infinity());
    const float after = std::nextafterf(T, std::numeric_limits<float>::infinity());

    const SwapProcedure p = nearestSwapProcedure(cfg, t);

    printf("=== %s  t=%.3f ===\n", label, static_cast<double>(t));
    printf("  pos=%.6f interval=%.1f pos/interval=%.6f k=%.1f T=%.6f\n",
           static_cast<double>(pos), static_cast<double>(interval),
           static_cast<double>(ratio), static_cast<double>(k),
           static_cast<double>(T));
    printf("  before=%.6f after=%.6f  (ULP=%.8f)\n",
           static_cast<double>(before), static_cast<double>(after),
           static_cast<double>(after - before));
    printf("  state(before)=%d state(after)=%d  => %s\n",
           channelSwapStateAt(cfg, before) ? 1 : 0,
           channelSwapStateAt(cfg, after) ? 1 : 0,
           channelSwapStateAt(cfg, before) == channelSwapStateAt(cfg, after)
               ? "НЕТ ПЕРЕВОРОТА (valid=0)" : "переворот есть (valid=1)");
    printf("  proc: valid=%d Tstar=%.6f F=%.6f P=%.6f\n",
           p.valid ? 1 : 0, static_cast<double>(p.tStarSec),
           static_cast<double>(p.fadeSec), static_cast<double>(p.pauseSec));
    printf("  gain(t)=%.6f\n", static_cast<double>(layoutGainAt(cfg, t)));

    // Скан окрестности T*: где реально меняется знак.
    printf("  скан вокруг T*:\n");
    const float offs[] = {-2.0f, -1.0f, -0.1f, -0.01f, -0.001f, 0.0f,
                          0.001f, 0.01f, 0.1f, 1.0f, 2.0f};
    for (float o : offs) {
        printf("    T*%+9.4f -> state=%d\n", static_cast<double>(o),
               channelSwapStateAt(cfg, T + o) ? 1 : 0);
    }
    printf("\n");
}

int main() {
    probe("УСТРОЙСТВО (из лога)", 41430.164f);
    probe("УТРО", 27.0f);
    probe("ВЕЧЕР", 85797.0f);
    return 0;
}
