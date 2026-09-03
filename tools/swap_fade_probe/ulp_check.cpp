// Диагностика: valid-ли процедура смены раскладки в зависимости от времени суток.
//
// Гипотеза: nearestSwapProcedure() проверяет переворот знака как
// channelSwapStateAt(T* − 1e-3) != channelSwapStateAt(T* + 1e-3). На оси суток
// T* — это float порядка 10^4…10^5 с, где ULP уже больше 1e-3, поэтому оба
// аргумента округляются в ОДИН и тот же float, проверка всегда даёт «знак не
// меняется» и процедура (затухание → тишина → нарастание) молча выключается.
//
// Сборка: g++ -std=c++17 -O2 -DANDROID -DAUDIO_TEST_BUILD -I<cpp>/include ulp_check.cpp

#include <cmath>
#include <cstdio>
#include <limits>
#include "Config.h"
#include "ChannelLayout.h"

using namespace binaural;

static BinauralConfig cfgFor(int intervalSec) {
    BinauralConfig cfg;
    FrequencyPoint p;
    p.timeSeconds = 0;
    p.carrierFrequency = 200.0f;
    p.beatFrequency = 8.0f;
    cfg.curve.points.push_back(p);
    p.timeSeconds = 43200;
    p.carrierFrequency = 300.0f;
    cfg.curve.points.push_back(p);
    p.timeSeconds = 86399;
    p.carrierFrequency = 200.0f;
    cfg.curve.points.push_back(p);
    cfg.curve.interpolationType = InterpolationType::LINEAR;
    cfg.curve.updateCache();
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = intervalSec;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = 1000;
    cfg.channelSwapPauseDurationMs = 0;
    return cfg;
}

int main() {
    printf("ULP(float) на оси суток:\n");
    const float probes[] = {5.0f, 1000.0f, 21600.0f, 43200.0f, 65536.0f, 78000.0f, 86394.0f};
    for (float t : probes) {
        const float up = std::nextafter(t, 1.0e30f);
        printf("  t=%9.1f  ULP=%.6f  (t+1e-3 == t ? %s)\n",
               t, up - t, (t + 1e-3f == t) ? "ДА" : "нет");
    }

    printf("\nTIMER, интервал 300 с — валидность процедуры по оси суток:\n");
    {
        BinauralConfig cfg = cfgFor(300);
        int bad = 0, total = 0;
        for (double tod = 0.0; tod < 86400.0; tod += 1800.0) {
            const float t = static_cast<float>(tod);
            const float T = nearestSwapTimeSec(cfg, t);
            const SwapProcedure p = nearestSwapProcedure(cfg, t);
            const bool signFlips = channelSwapStateAt(cfg, std::nextafter(T, -1.0e30f)) !=
                                   channelSwapStateAt(cfg, std::nextafter(T, 1.0e30f));
            ++total;
            if (!p.valid) ++bad;
            if (static_cast<int>(tod) % 7200 == 0) {
                printf("  tod=%6.0f T*=%9.3f valid=%d (реальный переворот знака=%d) F=%.3f\n",
                       tod, T, p.valid ? 1 : 0, signFlips ? 1 : 0, p.fadeSec);
            }
        }
        printf("  итого: невалидных %d из %d\n", bad, total);
    }

    printf("\nTIMER, интервал 6 с (как в ручном тесте):\n");
    {
        BinauralConfig cfg = cfgFor(6);
        for (double tod : {5.0, 1000.0, 21600.0, 43200.0, 65536.0, 78000.0, 86394.0}) {
            const float t = static_cast<float>(tod);
            const SwapProcedure p = nearestSwapProcedure(cfg, t);
            printf("  tod=%6.0f T*=%9.3f valid=%d F=%.3f  gain(T*)=%.4f\n",
                   tod, p.tStarSec, p.valid ? 1 : 0, p.fadeSec, layoutGainAt(cfg, p.tStarSec));
        }
    }
    // ---- зеркало нового gtest RampTest.SwapProcedureValidOnWholeDayAxis ----
    printf("\nзеркало gtest SwapProcedureValidOnWholeDayAxis://n");
    {
        BinauralConfig cfg = cfgFor(300);
        cfg.channelSwapPauseDurationMs = 0;
        int fails = 0;
        for (int k = 1; k < 288; ++k) {
            const float T = static_cast<float>(k) * 300.0f;
            const SwapProcedure p = nearestSwapProcedure(cfg, T);
            if (!p.valid || std::fabs(p.tStarSec - T) > 0.01f ||
                std::fabs(p.fadeSec - 1.0f) > 1e-3f ||
                std::fabs(layoutGainAt(cfg, T)) > 1e-3f) {
                if (fails < 5) printf("  FAIL k=%d T=%.1f valid=%d\n", k, T, p.valid ? 1 : 0);
                ++fails;
            }
        }
        printf("  узлов 287, провалов: %d\n", fails);
        printf("  полночь: valid=%d (ожидается 0)\n", nearestSwapProcedure(cfg, 0.0f).valid ? 1 : 0);
        constexpr float kT = 78000.0f;
        printf("  вечер T*=%.0f: gain(T*)=%.4f gain(±0.5)=%.4f/%.4f gain(±1.5)=%.4f/%.4f\n",
               kT, layoutGainAt(cfg, kT), layoutGainAt(cfg, kT - 0.5f),
               layoutGainAt(cfg, kT + 0.5f), layoutGainAt(cfg, kT - 1.5f),
               layoutGainAt(cfg, kT + 1.5f));
    }
    return 0;
}
