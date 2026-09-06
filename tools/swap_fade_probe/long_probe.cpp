// Расширенный зонд: НЕСКОЛЬКО смен каналов подряд (как на устройстве,
// interval=30 с), старт со смещением долей секунды (не по сетке 100 мс),
// чтобы проверить, что провал громкости (пауза процедуры) возникает
// ВОКРУГ КАЖДОГО узла смены, а не только одного.
//
// Сборка: g++ аналогично build.sh, но с long_probe.cpp вместо probe.cpp.
//   g++ -std=c++17 -O2 -D_USE_MATH_DEFINES -DANDROID -DAUDIO_TEST_BUILD \
//       -I$CPP/include -I$CPP -mssse3 -DUSE_SSE -include host_shim.h \
//       long_probe.cpp $CPP/src/AudioGenerator.cpp $CPP/src/Wavetable.cpp -o out/long_probe.exe

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

#include "Config.h"
#include "AudioGenerator.h"
#include "BufferPackagePlanner.h"
#include "ChannelLayout.h"

using namespace binaural;

constexpr int SR = 48000;

BinauralConfig makeConfig(int fadeMs, int pauseMs, int intervalSec) {
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

std::vector<float> emit(BinauralConfig& cfg, float startSec, float durationSec) {
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
        std::vector<float> buf(static_cast<size_t>(samples) * 2);
        GenerateResult r = gen.generatePackage(buf.data(), plan, cfg, state, curvePos, 0, 1.0f);
        const int n = r.samplesGenerated > 0 ? r.samplesGenerated : samples;
        out.insert(out.end(), buf.begin(), buf.begin() + static_cast<size_t>(n) * 2);
        const float adv = static_cast<float>(plan.totalDurationMs) / 1000.0f;
        curvePos += adv;
        acc += adv;
    }
    return out;
}

float rmsAt(const std::vector<float>& sig, int startSample, int n, int ch) {
    double s = 0.0;
    for (int i = 0; i < n; ++i) {
        const double v = sig[static_cast<size_t>(startSample + i) * 2 + ch];
        s += v * v;
    }
    return static_cast<float>(std::sqrt(s / n));
}

int main(int argc, char** argv) {
    const int fadeMs = (argc > 1) ? std::atoi(argv[1]) : 1000;
    const int pauseMs = (argc > 2) ? std::atoi(argv[2]) : 0;
    const int intervalSec = (argc > 3) ? std::atoi(argv[3]) : 30;
    const float startSec = (argc > 4) ? static_cast<float>(std::atof(argv[4])) : 12.37f;
    const float durSec = (argc > 5) ? static_cast<float>(std::atof(argv[5])) : 95.0f;

    BinauralConfig cfg = makeConfig(fadeMs, pauseMs, intervalSec);
    const std::vector<float> sig = emit(cfg, startSec, durSec);
    const int total = static_cast<int>(sig.size() / 2);
    printf("config: swap=1 interval=%ds F=%dms P=%dms SR=%d\n", intervalSec, fadeMs, pauseMs, SR);
    printf("start=%.3f dur=%.3f total=%d samples\n", startSec, durSec, total);

    // Огибающая RMS окнами 20 мс.
    const int win = 20 * SR / 1000;
    const int nWin = total / win;
    std::vector<float> prof(nWin);
    for (int w = 0; w < nWin; ++w) prof[w] = rmsAt(sig, w * win, win, 0);

    // Полная громкость вне окон процедур (±(F+P/2+0.3) вокруг каждого узла).
    auto isNearNode = [&](float t) {
        for (int k = 0; k * intervalSec <= durSec + startSec + intervalSec; ++k) {
            const float T = static_cast<float>(k) * intervalSec;
            if (std::fabs(t - T) < (fadeMs / 1000.0f + pauseMs / 1000.0f * 0.5f + 0.3f))
                return true;
        }
        return false;
    };
    float full = 0.0f;
    for (int w = 0; w < nWin; ++w) {
        const float t = startSec + static_cast<float>(w * win) / SR;
        if (!isNearNode(t)) full = std::max(full, prof[w]);
    }
    printf("full RMS (вне процедур) = %.5f\n\n", full);

    // Проверяем каждый узел в окне: провал < 5% полной и ширина провала ~ F+P.
    int nodesChecked = 0, nodesOk = 0;
    for (int k = 1; k * intervalSec < startSec + durSec; ++k) {
        const float T = static_cast<float>(k) * intervalSec;
        if (T < startSec + 0.5f) continue;
        if (T > startSec + durSec - 0.5f) continue;
        const int wc = static_cast<int>((T - startSec) * SR / win);
        const float rmsAtNode = (wc >= 0 && wc < nWin) ? prof[wc] : 1e9f;
        // ширина провала (<50%)
        int below = 0;
        for (int w = 0; w < nWin; ++w) {
            const float t = startSec + static_cast<float>(w * win) / SR;
            if (t < T - (fadeMs/1000.0f + pauseMs/1000.0f*0.5f + 0.05f)) continue;
            if (t > T + (fadeMs/1000.0f + pauseMs/1000.0f*0.5f + 0.05f)) continue;
            if (prof[w] < 0.5f * full) ++below;
        }
        const float widthSec = static_cast<float>(below * win) / SR;
        const bool dipOk = rmsAtNode < 0.05f * full;
        const bool widthOk = std::fabs(widthSec - (fadeMs/1000.0f + pauseMs/1000.0f)) < 0.15f;
        if (dipOk) ++nodesOk;
        ++nodesChecked;
        printf("  node t=%.1f  rms@node=%.6f (%.3f%% full)  width=%.3fs expect=%.3fs  %s\n",
               T, rmsAtNode, 100.0f * rmsAtNode / full, widthSec,
               fadeMs/1000.0f + pauseMs/1000.0f,
               (dipOk && widthOk) ? "OK" : "BAD");
    }
    printf("\nитог узлов: %d проверено, %d с провалом OK\n", nodesChecked, nodesOk);
    return (nodesChecked > 0 && nodesOk == nodesChecked) ? 0 : 1;
}
