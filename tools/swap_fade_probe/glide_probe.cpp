// ЗОНД ГЛАЙДА (регрессионный тест).
//
// Проверяет, что на смене раскладки частота каналов НЕ проскальзывает
// (не глиссандо «beat через ноль»), а ступенчато обрывается в тишине
// огибающей процедуры.
//
// МЕТРИКА (устойчивая): для каждого окна СТРОГО внутри левого/правого
// кусочка (вне узла T*) измеряем мгновенную частоту канала L по интервалам
// между восходящими нулями пересечения. ЧИСТАЯ СТУПЕНЬКА => частота внутри
// кусочка КОНСТАНТНА => дробная дисперсия периода ≈ 0. ГЛИССАНДО => частота
// рампит pre→post => дисперсия периода заметно > 0. Плюс проверяем, что знак
// beat (какой канал выше) перевернулся между левым и правым кусочком.
//
// БАГ: в generatePackage* конец кусочка брался на границе t1 = currentTime +
// pieceEnd*secPerSample, где pieceEnd — индекс СРЕЗА по T*. Последний РЕАЛЬНЫЙ
// сэмпл кусочка — pieceEnd-1, его время = currentTime+(pieceEnd-1)*secPerSample.
// Брать pieceEnd означает на 1 сэмпл «в будущее», где channelSwapStateAt уже
// вернул ПЕРЕВЁРНУТЫЙ знак beat => левый кусочек рампит pre→post через ноль
// (слышимое глиссандо «быстрая плавная смена каналов»). Фикс: t1 на
// (pieceEnd-1).
//
// Сборка (скалярный путь, как на хосте; баг и там):
//   g++ -std=c++17 -O2 -D_USE_MATH_DEFINES -DANDROID -DAUDIO_TEST_BUILD \
//       -I$CPP/include -I$CPP -mssse3 -DUSE_SSE -include host_shim.h \
//       glide_probe.cpp $CPP/src/AudioGenerator.cpp $CPP/src/Wavetable.cpp -o out/glide_probe.exe
//
// Ожидание: до фикса left/right sweep > tol (FAIL — глиссандо);
//          после фикса left/right sweep < tol И знак перевернут (PASS).

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>

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

// Средний период (с) между восходящими нулями канала ch в окне + дробная
// дисперсия периода (fracStd). Чистый тон => fracStd ≈ 0; глиссандо => >0.
float channelPeriod(const std::vector<float>& sig, int startSample, int n, int ch, float& fracStd) {
    fracStd = 0.0f;
    if (n < 8) return 0.0f;
    std::vector<int> zc;
    float prev = sig[static_cast<size_t>(startSample) * 2 + ch];
    for (int i = 1; i < n; ++i) {
        const float v = sig[static_cast<size_t>(startSample + i) * 2 + ch];
        if (prev < 0.0f && v >= 0.0f) zc.push_back(startSample + i);
        prev = v;
    }
    if (zc.size() < 3) return 0.0f;
    std::vector<float> per;
    for (size_t j = 1; j < zc.size(); ++j)
        per.push_back((zc[j] - zc[j - 1]) / static_cast<float>(SR));
    float mean = 0.0f;
    for (float p : per) mean += p;
    mean /= static_cast<float>(per.size());
    float var = 0.0f;
    for (float p : per) var += (p - mean) * (p - mean);
    var /= static_cast<float>(per.size());
    fracStd = std::sqrt(var) / mean;
    return mean;
}

int main(int argc, char** argv) {
    const int fadeMs = (argc > 1) ? std::atoi(argv[1]) : 2000;
    const int pauseMs = (argc > 2) ? std::atoi(argv[2]) : 0;
    const int intervalSec = (argc > 3) ? std::atoi(argv[3]) : 30;
    // startSec=12.35 => узел 30.0 попадает на 50 мс внутрь 100 мс сегмента
    // [29.95,30.05]; кусочки: левый [29.95,30.0], правый [30.0,30.05].
    const float startSec = (argc > 4) ? static_cast<float>(std::atof(argv[4])) : 12.35f;
    const float durSec = (argc > 5) ? static_cast<float>(std::atof(argv[5])) : 40.0f;

    BinauralConfig cfg = makeConfig(fadeMs, pauseMs, intervalSec);
    const std::vector<float> sig = emit(cfg, startSec, durSec);
    const int total = static_cast<int>(sig.size() / 2);
    printf("config: swap=1 interval=%ds F=%dms P=%dms SR=%d\n", intervalSec, fadeMs, pauseMs, SR);
    printf("start=%.3f dur=%.3f total=%d samples\n", startSec, durSec, total);

    const float T = static_cast<float>(intervalSec); // первый узел k=1 => 30.0

    // Окна (абс. сэмплы). ref — вне узла; left/right — СТРОГО внутри
    // кусочков, на 2 мс отодвинуты от T* (чтобы не задеть огибающую = 0).
    const int refStart   = static_cast<int>((T - 0.600f - startSec) * SR);
    const int refN       = static_cast<int>(0.200f * SR);
    const int leftStart  = static_cast<int>((T - 0.048f - startSec) * SR);
    const int leftN      = static_cast<int>(0.046f * SR);
    const int rightStart = static_cast<int>((T + 0.002f - startSec) * SR);
    const int rightN     = static_cast<int>(0.046f * SR);

    float sLref, sLleft, sLright, sRref, sRleft, sRright;
    const float pLref   = channelPeriod(sig, refStart,   refN,   0, sLref);
    const float pLleft  = channelPeriod(sig, leftStart,  leftN,  0, sLleft);
    const float pLright = channelPeriod(sig, rightStart, rightN, 0, sLright);
    const float pRref   = channelPeriod(sig, refStart,   refN,   1, sRref);
    const float pRleft  = channelPeriod(sig, leftStart,  leftN,  1, sRleft);
    const float pRright = channelPeriod(sig, rightStart, rightN, 1, sRright);

    auto hz = [](float period) -> float { return period > 0 ? 1.0f / period : 0.0f; };
    auto sign = [](float pL, float pR) -> int { return (pL < pR) ? +1 : -1; };

    printf("\n");
    printf("REF   : fL=%.2f fR=%.2f  sweepL=%.4f sweepR=%.4f  sign=%+d\n",
           hz(pLref), hz(pRref), sLref, sRref, sign(pLref, pRref));
    printf("LEFT  : fL=%.2f fR=%.2f  sweepL=%.4f sweepR=%.4f  sign=%+d\n",
           hz(pLleft), hz(pRleft), sLleft, sRleft, sign(pLleft, pRleft));
    printf("RIGHT : fL=%.2f fR=%.2f  sweepL=%.4f sweepR=%.4f  sign=%+d\n",
           hz(pLright), hz(pRright), sLleft, sRright, sign(pLright, pRright));

    // ЧИСТАЯ СТУПЕНЬКА:
    //   * sweep внутри обоих кусочков мал (частота константна => нет глиссандо);
    //   * знак beat перевернулся (левый pre, правый post — смена каналов была);
    //   * ref и left — одна сторона (до и сразу после начала левого кусочка
    //     знак ещё не перевернут).
    const float tol = 0.005f; // 0.5% дробной дисперсии периода (чистый тон ≈0.0014)
    const bool noGlide   = (sLleft  < tol) && (sLright < tol);
    const bool swapped   = (sign(pLleft, pRleft) != sign(pLright, pRright));
    const bool refOk     = (sign(pLref, pRref) == sign(pLleft, pRleft));
    const bool ok = noGlide && swapped && refOk;

    printf("\nпорог sweep = %.3f  noGlide=%d swapped=%d refOk=%d\n",
           tol, noGlide, swapped, refOk);
    printf("%s\n", ok ? "GLIDE_TEST: PASS (ступенька без глиссандо)"
                       : "GLIDE_TEST: FAIL (глиссандо или нет смены каналов!)");
    return ok ? 0 : 1;
}
