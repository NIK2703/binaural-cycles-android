// Зонд процедуры смены каналов (host, без Android SDK).
//
// Проверяет на реально сгенерированном звуке:
//   * смена раскладки сопровождается ПАУЗОЙ (затухание → тишина → нарастание),
//     а не быстрым перепадом частот каналов (унисоном);
//   * частоты ушей вне паузы — ровно {lower, upper} графика, всегда;
//   * в момент переворота знака нет щелчка (скачок сэмпла в пределах естественной
//     скорости нарастания синуса);
//   * выключенный swap не меняет звук ни на один бит (регресс против HEAD).
//
// Сборка: см. build.sh. Запуск: probe <out.f32> [swap=1] [fadeMs] [pauseMs].
//
// ВАЖНО: зонд намеренно НЕ вызывает layoutGainAt/layoutSignAt напрямую — только
// AudioGenerator. Так один и тот же файл собирается и против новой, и против
// старой (HEAD) реализации, что и даёт честное A/B.

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

namespace {

constexpr int SR = 44100;

int g_fail = 0;
int g_pass = 0;

template <typename... Args>
void check(bool ok, const char* name, const char* fmt, Args... args) {
    char detail[256];
    std::snprintf(detail, sizeof(detail), fmt, args...);
    if (ok) { ++g_pass; printf("PASS  %-46s %s\n", name, detail); }
    else    { ++g_fail; printf("FAIL  %-46s %s\n", name, detail); }
}

BinauralConfig makeConfig(bool swap, int fadeMs, int pauseMs, int intervalSec = 5) {
    BinauralConfig cfg;
    FrequencyPoint p;
    p.timeSeconds = 0;
    p.carrierFrequency = 200.0f;
    p.beatFrequency = 8.0f;   // уши: 196 / 204 Гц
    cfg.curve.points.push_back(p);
    cfg.curve.interpolationType = InterpolationType::LINEAR;
    cfg.curve.updateCache();
    cfg.volume = 0.7f;
    cfg.normalizationType = NormalizationType::NONE;
    cfg.channelSwapEnabled = swap;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = intervalSec;
    cfg.channelSwapFadeEnabled = true;
    cfg.channelSwapFadeDurationMs = fadeMs;
    cfg.channelSwapPauseDurationMs = pauseMs;
    return cfg;
}

// Генерация пакетами по 1 с (как делает движок).
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

// Частота по восходящим переходам через ноль.
float freqAt(const std::vector<float>& sig, int startSample, int n, int ch) {
    std::vector<double> cross;
    for (int i = 1; i < n; ++i) {
        const float a = sig[static_cast<size_t>(startSample + i - 1) * 2 + ch];
        const float b = sig[static_cast<size_t>(startSample + i) * 2 + ch];
        if (a < 0.0f && b >= 0.0f) cross.push_back(i - 1 - a / (b - a));
    }
    if (cross.size() < 2) return 0.0f;
    return static_cast<float>((cross.size() - 1) * SR / (cross.back() - cross.front()));
}

} // namespace

int main(int argc, char** argv) {
    const std::string outPath = (argc > 1) ? argv[1] : "out/probe.f32";
    const bool swap = (argc > 2) ? (std::strcmp(argv[2], "0") != 0) : true;
    const int fadeMs = (argc > 3) ? std::atoi(argv[3]) : 1000;
    const int pauseMs = (argc > 4) ? std::atoi(argv[4]) : 0;
    // Сдвиг по оси суток (кратный интервалу сетки 5 с). РЕГРЕССИЯ: на больших
    // t ULP float-оси суток (до 7.8 мс) БОЛЬШЕ любого абсолютного ε, из-за чего
    // проверка «знак действительно перевернулся» гасила всю процедуру и фейд
    // пропадал вечером/ночью. Прогоны base=0 и base=78000 обязательны оба.
    const float baseSec = (argc > 5) ? static_cast<float>(std::atof(argv[5])) : 0.0f;

    // Старт base+2.0 с: ближайший узел сетки (interval=5) — T* = base+5.0 с,
    // то есть процедура целиком лежит внутри прогона [base+2, base+10].
    const float TSTAR = baseSec + 5.0f;
    const float startSec = baseSec + 2.0f;
    const float durSec = 8.0f;

    BinauralConfig cfg = makeConfig(swap, fadeMs, pauseMs);
    const std::vector<float> sig = emit(cfg, startSec, durSec);
    const int total = static_cast<int>(sig.size() / 2);

    FILE* f = std::fopen(outPath.c_str(), "wb");
    if (f) { std::fwrite(sig.data(), sizeof(float), sig.size(), f); std::fclose(f); }

    printf("конфиг: swap=%d, F=%d мс, P=%d мс, interval=5 с, уши 196/204 Гц\n",
           swap ? 1 : 0, fadeMs, pauseMs);
    printf("сгенерировано %.3f с (%d сэмплов)\n\n", static_cast<float>(total) / SR, total);

    // ---- 1. Профиль огибающей --------------------------------------------
    const int winMs = 20;
    const int win = winMs * SR / 1000;
    const int nWin = total / win;
    std::vector<float> prof(nWin);
    for (int w = 0; w < nWin; ++w) prof[w] = rmsAt(sig, w * win, win, 0);

    float full = 0.0f;
    for (int w = 0; w < nWin; ++w) {
        const float t = startSec + static_cast<float>(w * win) / SR;
        if (std::fabs(t - TSTAR) > static_cast<float>(fadeMs) / 1000.0f + 0.5f) {
            full = std::max(full, prof[w]);
        }
    }
    printf("полная громкость (RMS окна 20 мс) = %.5f\n", full);
    printf("профиль (t, RMS, доля):\n");
    for (int w = 0; w < nWin; ++w) {
        const float t = startSec + static_cast<float>(w * win) / SR;
        if (t >= (baseSec + 3.6f) && t <= (baseSec + 6.6f)) {
            printf("  t=%.3f  rms=%.5f  %.3f\n", t, prof[w], prof[w] / full);
        }
    }
    printf("\n");

    const auto at = [&](float t) {
        const int w = static_cast<int>((t - startSec) * SR / win);
        return (w >= 0 && w < nWin) ? prof[w] : 0.0f;
    };

    if (!swap) {
        // Выключенный swap: громкость ровная, частоты не меняются.
        float mn = full, mx = 0.0f;
        for (int w = 0; w < nWin; ++w) { mn = std::min(mn, prof[w]); mx = std::max(mx, prof[w]); }
        check(mx - mn < 0.01f * full, "swap off: громкость ровная",
              "min=%.5f max=%.5f", mn, mx);
    } else {
        // Полная громкость ДО и ПОСЛЕ процедуры.
        check(std::fabs(at((baseSec + 3.0f)) - full) < 0.02f * full, "до процедуры: полная громкость",
              "rms(до процедуры)=%.5f full=%.5f", at((baseSec + 3.0f)), full);
        check(std::fabs(at((baseSec + 7.0f)) - full) < 0.02f * full, "после процедуры: полная громкость",
              "rms(после процедуры)=%.5f full=%.5f", at((baseSec + 7.0f)), full);

        // Тишина в T* (центр процедуры).
        const int pauseHalf = pauseMs / 2;
        check(at(TSTAR) < 0.005f * full, "в T*: тишина", "rms(T*)=%.6f (%.4f%% полной)",
              at(TSTAR), 100.0f * at(TSTAR) / full);
        if (pauseMs >= 1000) {
            check(at(TSTAR - static_cast<float>(pauseHalf) / 1000.0f + 0.4f) < 0.005f * full &&
                  at(TSTAR + static_cast<float>(pauseHalf) / 1000.0f - 0.4f) < 0.005f * full,
                  "пауза P: тишина во всей паузе",
                  "rms=%.6f / %.6f", at(TSTAR - pauseHalf / 1000.0f + 0.4f),
                  at(TSTAR + pauseHalf / 1000.0f - 0.4f));
        }

        // Середина затухания/нарастания: приподнятый косинус 0.5·(1±cos πu)
        // даёт ровно ПОЛОВИНУ амплитуды при u = 0.5 (эта же кривая была у
        // FADE_OUT/FADE_IN до миграции; равномощная sin/cos дала бы 0.707).
        const float F = static_cast<float>(fadeMs) / 1000.0f;
        const float P = static_cast<float>(pauseMs) / 1000.0f;
        const float tHalfOut = TSTAR - P / 2.0f - F / 2.0f;
        const float tHalfIn = TSTAR + P / 2.0f + F / 2.0f;
        check(std::fabs(at(tHalfOut) - 0.5f * full) < 0.1f * full,
              "середина затухания: половина амплитуды",
              "rms(%.3f)=%.5f ожид.%.5f", tHalfOut, at(tHalfOut), 0.5f * full);
        check(std::fabs(at(tHalfIn) - 0.5f * full) < 0.1f * full,
              "середина нарастания: половина амплитуды",
              "rms(%.3f)=%.5f ожид.%.5f", tHalfIn, at(tHalfIn), 0.5f * full);

        // Ширина провала (RMS < 50 %) = F + P. Считаем ТОЛЬКО вокруг T* = 5 с:
        // в прогон попадает и следующий узел сетки (t = 10 с), его затухание
        // тоже дало бы вклад.
        int below = 0;
        for (int w = 0; w < nWin; ++w) {
            const float t = startSec + static_cast<float>(w * win) / SR;
            if (t < TSTAR - F - P / 2.0f - 0.05f || t > TSTAR + F + P / 2.0f + 0.05f) continue;
            if (prof[w] < 0.5f * full) ++below;
        }
        const float widthSec = static_cast<float>(below * win) / SR;
        const float expect = F + P;
        check(std::fabs(widthSec - expect) < 0.1f, "ширина провала (<50 %%) = F + P",
              "%.3f с, ожидалось %.3f с", widthSec, expect);

        // Монотонность: затухание убывает, нарастание растёт. Допуск 2 % —
        // пульсация RMS окна 20 мс (196 Гц даёт 3.92 периода, а не целое).
        const float ripple = 0.02f * full;
        bool monoOut = true, monoIn = true;
        for (int w = 1; w < nWin; ++w) {
            const float t = startSec + static_cast<float>(w * win) / SR;
            const float tp = startSec + static_cast<float>((w - 1) * win) / SR;
            if (tp >= TSTAR - P / 2.0f - F && t <= TSTAR - P / 2.0f) {
                if (prof[w] > prof[w - 1] + ripple) monoOut = false;
            }
            if (tp >= TSTAR + P / 2.0f && t <= TSTAR + P / 2.0f + F) {
                if (prof[w] < prof[w - 1] - ripple) monoIn = false;
            }
        }
        check(monoOut, "затухание монотонно", "допуск %.4f", ripple);
        check(monoIn, "нарастание монотонно", "допуск %.4f", ripple);
    }

    // ---- 2. Частоты: всегда {196, 204}, без унисона -----------------------
    {
        const int fw = 100 * SR / 1000;
        float minSpread = 1e9f, maxSpread = -1e9f;
        bool beforeNormal = false, afterNormal = false;
        int measured = 0;
        for (int s = 0; s + fw <= total; s += fw) {
            const float rms = rmsAt(sig, s, fw, 0);
            if (rms < 0.25f * full) continue;   // тишину не меряем
            const float lf = freqAt(sig, s, fw, 0);
            const float rf = freqAt(sig, s, fw, 1);
            if (lf < 1.0f || rf < 1.0f) continue;
            const float lo = std::min(lf, rf), hi = std::max(lf, rf);
            minSpread = std::min(minSpread, hi - lo);
            maxSpread = std::max(maxSpread, hi - lo);
            const float t = startSec + static_cast<float>(s) / SR;
            if (t < (baseSec + 4.5f)) beforeNormal = (lf < rf);
            if (t > (baseSec + 5.5f)) afterNormal = (lf < rf);
            ++measured;
        }
        printf("\nокон с измеренной частотой: %d, разнос min=%.2f max=%.2f Гц\n",
               measured, minSpread, maxSpread);
        check(maxSpread < 10.0f, "максимальный разнос ушей ~8 Гц (без разлёта)",
              "max=%.2f Гц", maxSpread);
        if (swap) {
            check(minSpread > 6.0f, "унисона НЕТ: разнос никогда не схлопывается",
                  "min=%.2f Гц (было бы ~0 при проходе beat через ноль)", minSpread);
            check(beforeNormal && !afterNormal, "раскладка перевернулась после T*",
                  "до=%s после=%s", beforeNormal ? "прямая" : "обратная",
                  afterNormal ? "прямая" : "обратная");
        }
    }

    // ---- 3. Щелчок: максимальный скачок между соседними сэмплами ----------
    {
        double maxJump = 0.0;
        int jumpAt = -1;
        for (int i = 1; i < total; ++i) {
            for (int ch = 0; ch < 2; ++ch) {
                const double d = std::fabs(static_cast<double>(sig[i * 2 + ch]) -
                                           static_cast<double>(sig[(i - 1) * 2 + ch]));
                if (d > maxJump) { maxJump = d; jumpAt = i; }
            }
        }
        // Естественная скорость синуса 204 Гц при амплитуде 0.5: A·2πf/SR.
        const double natural = 0.5 * 2.0 * M_PI * 204.0 / SR;
        printf("\nмакс. скачок сэмпла = %.6f (естественный %.6f) на сэмпле %d (t=%.4f)\n",
               maxJump, natural, jumpAt, startSec + static_cast<double>(jumpAt) / SR);
        check(maxJump < 1.5 * natural, "щелчка нет: скачок в пределах скорости синуса",
              "%.6f < %.6f", maxJump, 1.5 * natural);
    }

    printf("\nитог: %d PASS, %d FAIL\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
