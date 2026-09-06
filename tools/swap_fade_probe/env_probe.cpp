// ЗОНД ОГИБАЮЩЕЙ (поиск «нет затухания при смене каналов»).
//
// Отличие от glide_probe.cpp: тот доказывал лишь, что частоты НЕ глиссандо.
// Этот мерит ГРОМКОСТЬ — есть ли реальный провал (затухание → тишина →
// нарастание) вокруг узла T*. Именно его отсутствие и слышит пользователь:
// чистая ступенька частот БЕЗ провала звучит как «быстрая смена каналов».
//
// Печатает:
//   1) поля процедуры nearestSwapProcedure (valid / fadeSec / pauseSec / T*)
//      — если valid=false или fadeSec=0, layoutGainAt() вернёт ровно 1;
//   2) layoutGainAt() в нескольких контрольных точках;
//   3) реальную огибающую звука (peak в окне 10 мс) вокруг T*.
//
// Прогон: утро (t ≈ 30 с) и вечер (t ≈ 85800 с) — код исторически чувствителен
// к величине t из-за ULP float на оси суток.
//
// Сборка (скалярный путь; ОТНОСИТЕЛЬНЫЕ пути выхода — MSYS2 g++ не пишет на E:/):
//   cd tools/swap_fade_probe
//   g++ -std=c++17 -O2 -D_USE_MATH_DEFINES -DANDROID -DAUDIO_TEST_BUILD \
//       -I$CPP/include -I$CPP -mssse3 -DUSE_SSE -include host_shim.h \
//       -c env_probe.cpp -o env_probe.o
//   ... аналогично AudioGenerator.cpp, Wavetable.cpp
//   g++ env_probe.o ag.o wt.o -o env_probe.exe

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>
#include <algorithm>

#include "Config.h"
#include "AudioGenerator.h"
#include "BufferPackagePlanner.h"
#include "ChannelLayout.h"

using namespace binaural;

constexpr int SR = 48000;

static BinauralConfig makeConfig(int fadeMs, int pauseMs, int intervalSec) {
    BinauralConfig cfg;
    FrequencyPoint p;
    p.timeSeconds = 0;
    p.carrierFrequency = 200.0f;
    p.beatFrequency = 8.0f;
    cfg.curve.points.push_back(p);
    cfg.curve.interpolationType = InterpolationType::LINEAR;
    cfg.curve.updateCache();
    cfg.volume = 0.7f;
    cfg.normalizationType = NormalizationType::NONE;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = intervalSec;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = fadeMs;
    cfg.channelSwapPauseDurationMs = pauseMs;
    return cfg;
}

static std::vector<float> emit(BinauralConfig& cfg, float startSec, float durationSec) {
    AudioGenerator gen;
    gen.setSampleRate(SR);
    BufferPackagePlanner planner;
    GeneratorState state;
    planner.resetState(state);
    gen.resetState(state);
    std::vector<float> out;
    float curvePos = startSec;
    float acc = 0.0f;
    while (acc < durationSec - 1e-3f) {
        const int64_t ms = static_cast<int64_t>(
            std::min<float>((durationSec - acc) * 1000.0f, 1000.0f));
        PackagePlan plan = planner.planPackage(ms, cfg, state, curvePos, 1.0f);
        if (plan.segments.empty()) break;
        const int samples = static_cast<int>((plan.totalDurationMs * SR) / 1000);
        std::vector<float> buf(static_cast<size_t>(samples) * 2, 0.0f);
        GenerateResult r = gen.generatePackage(buf.data(), plan, cfg, state, curvePos, 0, 1.0f);
        const int n = r.samplesGenerated > 0 ? r.samplesGenerated : samples;
        out.insert(out.end(), buf.begin(), buf.begin() + static_cast<size_t>(n) * 2);
        const float adv = static_cast<float>(plan.totalDurationMs) / 1000.0f;
        curvePos += adv;
        acc += adv;
    }
    return out;
}

// Пик |амплитуды| в окне [SampleFrom, +win). Огибающая звука.
static float peakIn(const std::vector<float>& sig, int from, int win) {
    float m = 0.0f;
    const int total = static_cast<int>(sig.size() / 2);
    const int end = std::min(from + win, total);
    for (int i = from; i < end; ++i) {
        const float a = std::fabs(sig[static_cast<size_t>(i) * 2]);
        const float b = std::fabs(sig[static_cast<size_t>(i) * 2 + 1]);
        m = std::max(m, std::max(a, b));
    }
    return m;
}

int main(int argc, char** argv) {
    const int fadeMs = (argc > 1) ? std::atoi(argv[1]) : 1000;
    const int pauseMs = (argc > 2) ? std::atoi(argv[2]) : 0;
    const int intervalSec = (argc > 3) ? std::atoi(argv[3]) : 30;
    const float startSec = (argc > 4) ? static_cast<float>(std::atof(argv[4])) : 27.0f;
    const float durSec = (argc > 5) ? static_cast<float>(std::atof(argv[5])) : 8.0f;

    BinauralConfig cfg = makeConfig(fadeMs, pauseMs, intervalSec);
    const std::vector<float> sig = emit(cfg, startSec, durSec);
    const int total = static_cast<int>(sig.size() / 2);

    printf("config: swap=1 TIMER interval=%ds F=%dms P=%dms SR=%d\n",
           intervalSec, fadeMs, pauseMs, SR);
    printf("start=%.3f dur=%.3f -> %d samples\n\n", startSec, durSec, total);

    // ---- 1) процедура в контрольных точках -------------------------------
    const float Tstar = nearestSwapTimeSec(cfg, startSec + 0.5f * durSec);
    printf("nearestSwapTimeSec(mid) = %.4f\n", Tstar);
    printf("%-12s %-6s %-9s %-9s %-11s %-9s\n",
           "t", "valid", "fadeSec", "pauseSec", "tStarSec", "gain");
    const float probes[] = {Tstar - 2.0f, Tstar - 1.0f, Tstar - 0.5f, Tstar - 0.05f,
                            Tstar, Tstar + 0.05f, Tstar + 0.5f, Tstar + 1.0f, Tstar + 2.0f};
    for (float tp : probes) {
        const SwapProcedure p = nearestSwapProcedure(cfg, tp);
        printf("%-12.4f %-6d %-9.4f %-9.4f %-11.4f %-9.4f\n",
               tp, p.valid ? 1 : 0, p.fadeSec, p.pauseSec, p.tStarSec,
               layoutGainAt(cfg, tp));
    }

    // ---- 2) реальная огибающая звука -------------------------------------
    printf("\nогибающая звука (peak в окне 10 мс), окно T*±%.2f с:\n",
           1.6f * (fadeMs / 1000.0f) + 0.2f);
    const int win = static_cast<int>(0.010f * SR); // 10 мс
    const float from = startSec;
    const float to = startSec + durSec;
    float gMax = 0.0f;
    float gMin = 1e9f;
    float gMinT = 0.0f;
    printf("%-12s %-9s %s\n", "t-T*", "peak", "шкала");
    for (float t = from; t < to - 0.01f; t += 0.050f) {
        const int i0 = static_cast<int>((t - startSec) * SR);
        if (i0 < 0 || i0 + win > total) continue;
        const float pk = peakIn(sig, i0, win);
        // Нормируем на «полную громкость» = максимум вдали от T*.
        if (std::fabs(t - Tstar) > 1.5f * (fadeMs / 1000.0f) + 0.5f) gMax = std::max(gMax, pk);
        if (std::fabs(t - Tstar) < 1.5f * (fadeMs / 1000.0f) + 0.3f && pk < gMin) {
            gMin = pk;
            gMinT = t;
        }
        const int bar = static_cast<int>(pk * 50.0f / 0.35f);
        char line[64];
        int n = 0;
        for (int k = 0; k < std::min(bar, 50); ++k) line[n++] = '#';
        line[n] = 0;
        printf("%-12.3f %-9.5f %s\n", t - Tstar, pk, line);
    }

    printf("\n");
    if (gMax <= 0.0f) {
        printf("НЕТ ЗВУКА ВООБЩЕ (gMax=0)\n");
        return 1;
    }
    const float rel = gMin / gMax;
    printf("gMax(вне окна)=%.5f  gMin(в окне)=%.5f на t-T*=%.3f  отношение=%.4f\n",
           gMax, gMin, gMinT - Tstar, rel);
    // Провал считается настоящим, если громкость падает ниже 10 % от полной.
    const bool dip = rel < 0.10f;
    printf("%s (порог отношения 0.10)\n",
           dip ? "ENV_TEST: PASS — затухание ЕСТЬ" : "ENV_TEST: FAIL — затухания НЕТ");
    return dip ? 0 : 1;
}
