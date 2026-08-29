// -*- mode: c++ -*-
// Стенд для измерения точности следования частоты графику в фазах перестановки
// каналов (SOLID / FADE_OUT / PAUSE / FADE_IN).
//
// Использует НАСТОЯЩИЙ код движка:
//   * BufferPackagePlanner::planPackage   — реальная раскладка сегментов;
//   * FrequencyCurve::getChannelFrequenciesAt — реальный float32 lookup;
// Модель частоты внутри сегмента повторяет AudioGenerator::generatePackage
// (хорда lookup-значений на границах кусочка, <=100 мс для фейдов).
//
// Эталон («график») — точный сплайн по контрольным точкам в double: то, что
// рисует UI (jni.cpp nativeGenerateInterpolatedCurve строит кривую теми же
// функциями Interpolation, но без 100-мс таблицы).
//
// Ничего не компилируется из Android: собирается обычным g++ на хосте.
#include "AudioGenerator.h"
#include "BufferPackagePlanner.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace binaural;

// ===========================================================================
// Эталон: сплайн по контрольным точкам в double (порт Interpolation.h)
// ===========================================================================
static double interpD(InterpolationType type,
                      double p0, double p1, double p2, double p3,
                      double t, double tension) {
    double r;
    switch (type) {
        case InterpolationType::LINEAR:
            r = p1 + t * (p2 - p1);
            break;
        case InterpolationType::STEP:
            r = p1;
            break;
        case InterpolationType::CARDINAL: {
            const double t2 = t * t, t3 = t2 * t;
            const double s = (1.0 - tension) / 2.0;
            const double m1 = (p2 - p0) * s, m2 = (p3 - p1) * s;
            const double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t;
            const double h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
            r = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
            break;
        }
        default: {  // MONOTONE (Fritsch-Carlson + clamp)
            const double d0 = p1 - p0, d1 = p2 - p1, d2 = p3 - p2;
            auto slope = [](double a, double b) {
                if (a * b <= 0.0) return 0.0;
                return 2.0 * a * b / (a + b);
            };
            const double m1 = slope(d0, d1), m2 = slope(d1, d2);
            const double t2 = t * t, t3 = t2 * t;
            const double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t;
            const double h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
            r = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
            r = std::min(std::max(r, std::min(p1, p2)), std::max(p1, p2));
            break;
        }
    }
    return std::max(0.0, r);
}

struct Ideal {
    std::vector<double> times, lo, up;
    InterpolationType itype = InterpolationType::LINEAR;
    double tension = 0.0;
    int n = 0;

    explicit Ideal(const FrequencyCurve& c) {
        std::vector<FrequencyPoint> pts = c.points;
        std::stable_sort(pts.begin(), pts.end(),
                         [](const FrequencyPoint& a, const FrequencyPoint& b) {
                             return a.timeSeconds < b.timeSeconds;
                         });
        {
            size_t out = 0;
            for (size_t i = 0; i < pts.size(); ++i) {
                if (i + 1 < pts.size() && pts[i].timeSeconds == pts[i + 1].timeSeconds) continue;
                pts[out++] = pts[i];
            }
            pts.resize(out);
        }
        n = static_cast<int>(pts.size());
        itype = c.interpolationType;
        tension = c.splineTension;
        for (const auto& p : pts) {
            times.push_back(static_cast<double>(p.timeSeconds));
            lo.push_back(p.carrierFrequency - p.beatFrequency / 2.0);
            up.push_back(p.carrierFrequency + p.beatFrequency / 2.0);
        }
    }

    // Возвращает (lower, upper) точного сплайна в момент t (сек суток).
    std::pair<double, double> at(double t) const {
        constexpr double DAY = 86400.0;
        t = std::fmod(t, DAY);
        if (t < 0.0) t += DAY;
        int left;
        if (n == 1) return {lo[0], up[0]};
        if (t < times[0]) {
            left = n - 1;                       // wrap-интервал [последняя -> первая + сутки]
        } else {
            left = static_cast<int>(std::upper_bound(times.begin(), times.end(), t) - times.begin()) - 1;
            if (left >= n - 1) left = n - 1;
            if (left < 0) left = 0;
        }
        const int right = (left + 1) % n;
        const bool wrap = (left == n - 1);
        const double t1 = times[left];
        const double t2 = times[right] + (wrap ? DAY : 0.0);
        const double tt = (wrap && t < t1) ? t + DAY : t;
        double ratio = (t2 != t1) ? (tt - t1) / (t2 - t1) : 0.0;
        ratio = std::min(std::max(ratio, 0.0), 1.0);
        const int prev = (left - 1 + n) % n;
        const int nxt = (right + 1) % n;
        return {interpD(itype, lo[prev], lo[left], lo[right], lo[nxt], ratio, tension),
                interpD(itype, up[prev], up[left], up[right], up[nxt], ratio, tension)};
    }
};

// ===========================================================================
// Огибающая фейда — точная копия FadeCurveTable из AudioGenerator.cpp
// ===========================================================================
struct FadeCurve {
    static constexpr int N = 2048;
    double v[N + 1];
    FadeCurve() {
        for (int i = 0; i <= N; ++i) {
            const double t = static_cast<double>(i) / N;
            v[i] = 0.5 * (1.0 - std::cos(t * M_PI));
        }
    }
    double get(double p) const {
        const double c = std::min(std::max(p, 0.0), 1.0);
        const double si = c * N;
        const int i = static_cast<int>(si);
        if (i >= N) return v[N];
        const double f = si - i;
        return v[i] + f * (v[i + 1] - v[i]);
    }
};

// ===========================================================================
// Аккумуляторы по фазам
// ===========================================================================
struct PhaseStats {
    long long n = 0;
    double cAbsSum = 0.0, cAbsMax = 0.0, cSqSum = 0.0;
    double bAbsSum = 0.0, bAbsMax = 0.0, bSqSum = 0.0;
    double bEnvAbsSum = 0.0, envSum = 0.0;
    double cSignedSum = 0.0, bSignedSum = 0.0;
};

static const char* phaseName(int p) {
    switch (p) {
        case 0: return "SOLID";
        case 1: return "FADE_OUT";
        case 2: return "PAUSE";
        case 3: return "FADE_IN";
    }
    return "?";
}

// ===========================================================================
// Утилиты
// ===========================================================================
static double normalizeTod(double t) {
    constexpr double DAY = 86400.0;
    t = std::fmod(t, DAY);
    if (t < 0.0) t += DAY;
    return t;
}

static std::vector<int> stepBounds(const FrequencyCurve& curve, double startTimeSeconds,
                                   double secPerSample, int samples) {
    std::vector<int> bounds;
    if (samples <= 1 || !(secPerSample > 0.0)) return bounds;
    const double endTime = startTimeSeconds + secPerSample * samples;
    constexpr double DAY = 86400.0;
    for (const auto& p : curve.points) {
        const double pt = static_cast<double>(p.timeSeconds);
        const long long kFirst = static_cast<long long>(std::floor((startTimeSeconds - pt) / DAY)) - 1;
        const long long kLast = static_cast<long long>(std::ceil((endTime - pt) / DAY)) + 1;
        for (long long k = kFirst; k <= kLast; ++k) {
            const double occ = pt + static_cast<double>(k) * DAY;
            if (occ <= startTimeSeconds || occ >= endTime) continue;
            const int n = static_cast<int>(std::ceil((occ - startTimeSeconds) / secPerSample));
            if (n > 0 && n < samples) bounds.push_back(n);
        }
    }
    std::sort(bounds.begin(), bounds.end());
    bounds.erase(std::unique(bounds.begin(), bounds.end()), bounds.end());
    return bounds;
}

// ===========================================================================
// main
// ===========================================================================
static void usage(const char* argv0) {
    std::fprintf(stderr,
        "usage: %s --sr N --pkg MS --start SEC --dur SEC --out PREFIX\n"
        "       [--curve circadian|fast|step|gamma|daily|file:PATH]\n"
        "       [--itype linear|cardinal|monotone|step] [--tension F]\n"
        "       [--swap 0|1] [--mode timer|trend] [--interval SEC] [--fade MS]\n"
        "       [--pause MS] [--points both|peaks|troughs] [--fade-enabled 0|1]\n"
        "       [--timescale F] [--stride N] [--wintrace 0|1]\n",
        argv0);
}

int main(int argc, char** argv) {
    int sr = 48000;
    long long pkgMs = 500;
    double start = 6.0 * 3600.0;
    double dur = 1800.0;
    std::string out = "/tmp/probe";
    std::string curveName = "circadian";
    InterpolationType itype = InterpolationType::MONOTONE;
    float tension = 0.0f;
    bool swap = true;
    ChannelSwapMode mode = ChannelSwapMode::TIMER;
    int intervalSec = 300;
    long long fadeMs = 1000, pauseMs = 0;
    ChannelSwapTrendPoints points = ChannelSwapTrendPoints::BOTH;
    bool fadeEnabled = true;
    float timeScale = 1.0f;
    long long stride = 2400;
    bool wintrace = true;

    auto arg = [&](const char* name) -> std::string {
        for (int i = 1; i + 1 < argc; ++i)
            if (std::strcmp(argv[i], name) == 0) return argv[i + 1];
        return "";
    };
    if (argc < 2) { usage(argv[0]); return 1; }
    if (!arg("--sr").empty()) sr = std::atoi(arg("--sr").c_str());
    if (!arg("--pkg").empty()) pkgMs = std::atoll(arg("--pkg").c_str());
    if (!arg("--start").empty()) start = std::atof(arg("--start").c_str());
    if (!arg("--dur").empty()) dur = std::atof(arg("--dur").c_str());
    if (!arg("--out").empty()) out = arg("--out");
    if (!arg("--curve").empty()) curveName = arg("--curve");
    if (!arg("--itype").empty()) {
        std::string s = arg("--itype");
        itype = (s == "linear") ? InterpolationType::LINEAR
              : (s == "cardinal") ? InterpolationType::CARDINAL
              : (s == "step") ? InterpolationType::STEP
              : InterpolationType::MONOTONE;
    }
    if (!arg("--tension").empty()) tension = static_cast<float>(std::atof(arg("--tension").c_str()));
    if (!arg("--swap").empty()) swap = std::atoi(arg("--swap").c_str()) != 0;
    if (!arg("--mode").empty()) mode = (arg("--mode") == "trend") ? ChannelSwapMode::TREND : ChannelSwapMode::TIMER;
    if (!arg("--interval").empty()) intervalSec = std::atoi(arg("--interval").c_str());
    if (!arg("--fade").empty()) fadeMs = std::atoll(arg("--fade").c_str());
    if (!arg("--pause").empty()) pauseMs = std::atoll(arg("--pause").c_str());
    if (!arg("--points").empty()) {
        std::string s = arg("--points");
        points = (s == "peaks") ? ChannelSwapTrendPoints::PEAKS
               : (s == "troughs") ? ChannelSwapTrendPoints::TROUGHS
               : ChannelSwapTrendPoints::BOTH;
    }
    if (!arg("--fade-enabled").empty()) fadeEnabled = std::atoi(arg("--fade-enabled").c_str()) != 0;
    if (!arg("--timescale").empty()) timeScale = static_cast<float>(std::atof(arg("--timescale").c_str()));
    if (!arg("--stride").empty()) stride = std::atoll(arg("--stride").c_str());
    if (!arg("--wintrace").empty()) wintrace = std::atoi(arg("--wintrace").c_str()) != 0;

    // ---------------- кривая ----------------
    BinauralConfig config;
    auto& curve = config.curve;
    auto addP = [&](int t, double c, double b) {
        FrequencyPoint p;
        p.timeSeconds = t;
        p.carrierFrequency = static_cast<float>(c);
        p.beatFrequency = static_cast<float>(b);
        curve.points.push_back(p);
    };
    if (curveName == "circadian") {
        addP(0, 174, 3); addP(10800, 210, 6); addP(21600, 220, 8); addP(32400, 440, 20);
        addP(43200, 440, 25); addP(54000, 440, 18); addP(64800, 250, 12); addP(75600, 240, 10);
    } else if (curveName == "gamma") {
        addP(0, 220, 1.5); addP(10800, 250, 5); addP(21600, 340, 9); addP(32400, 400, 18);
        addP(43200, 380, 14); addP(54000, 440, 40); addP(64800, 300, 7.5); addP(75600, 240, 4);
    } else if (curveName == "daily") {
        addP(0, 200, 2); addP(10800, 200, 3); addP(21600, 300, 10); addP(32400, 400, 18);
        addP(43200, 300, 6); addP(54000, 400, 25); addP(64800, 300, 9); addP(75600, 250, 5);
    } else if (curveName == "fast") {
        addP(0, 100, 2); addP(3600, 900, 40); addP(86399, 100, 2);
    } else if (curveName == "step") {
        addP(0, 200, 4); addP(21899, 200, 4); addP(21900, 500, 30);
        addP(30000, 500, 30); addP(86399, 200, 4);
    } else if (curveName.rfind("file:", 0) == 0) {
        std::FILE* f = std::fopen(curveName.c_str() + 5, "r");
        if (!f) { std::fprintf(stderr, "cannot open curve file\n"); return 2; }
        int t; double c, b;
        while (std::fscanf(f, "%d %lf %lf", &t, &c, &b) == 3) addP(t, c, b);
        std::fclose(f);
    } else {
        std::fprintf(stderr, "unknown curve %s\n", curveName.c_str());
        return 2;
    }
    curve.interpolationType = itype;
    curve.splineTension = tension;
    curve.updateCache();

    config.channelSwapEnabled = swap;
    config.channelSwapMode = mode;
    config.channelSwapIntervalSec = intervalSec;
    config.channelSwapTrendPoints = points;
    config.channelSwapFadeEnabled = fadeEnabled;
    config.channelSwapFadeDurationMs = fadeMs;
    config.channelSwapPauseDurationMs = pauseMs;
    config.normalizationType = NormalizationType::NONE;  // на частоту не влияет

    const Ideal ideal(curve);
    const FadeCurve fadeCurve;

    // ---------------- состояние движка ----------------
    BufferPackagePlanner planner;
    GeneratorState state;
    planner.resetState(state);
    planner.initStateForStart(config, state, static_cast<float>(start), timeScale);

    const int samplesPerChannel = static_cast<int>(static_cast<long long>(sr) * pkgMs / 1000);
    const int64_t bufferDurationMs = static_cast<int64_t>(samplesPerChannel) * 1000 / sr;
    const long long targetSamples = static_cast<long long>(dur * sr);

    PhaseStats st[4];
    long long produced = 0;
    float curveTime = static_cast<float>(normalizeTod(start));

    std::FILE* fPlan = std::fopen((out + ".plan.csv").c_str(), "w");
    std::FILE* fTrace = std::fopen((out + ".trace.csv").c_str(), "w");
    std::FILE* fEvents = std::fopen((out + ".events.csv").c_str(), "w");
    std::fprintf(fPlan, "pkg,idx,type,durMs,fadeOffsetMs,fadeTotalMs,swapAfter\n");
    std::fprintf(fTrace, "sample,phase,t_curve,model_lo,model_up,ideal_lo,ideal_up,env\n");
    std::fprintf(fEvents, "sample,t_curve,event,swapped\n");

    // Окно высокого разрешения вокруг первого фейда
    long long winStart = -1, winEnd = -1;
    const long long winHalf = static_cast<long long>((fadeMs + pauseMs + 500)) * sr / 1000;
    bool winArmed = wintrace && swap;

    // Максимальный скачок мгновенной частоты между соседними сэмплами
    double maxJumpC = 0.0, maxJumpB = 0.0;
    long long maxJumpAt = -1;
    int maxJumpFrom = -1, maxJumpTo = -1;
    bool havePrev = false;
    double prevMc = 0.0, prevMb = 0.0;
    int prevPhase = -1;

    // Лямбда: записать ошибку одного сэмпла
    auto record = [&](long long gidx, int phase, double tCurve,
                      double mLo, double mUp, double env) {
        const auto [iLo, iUp] = ideal.at(tCurve);
        const double mc = (mLo + mUp) * 0.5, ic = (iLo + iUp) * 0.5;
        const double mb = (mUp - mLo), ib = (iUp - iLo);
        const double dc = std::fabs(mc - ic), db = std::fabs(mb - ib);
        PhaseStats& s = st[phase];
        s.n++;
        s.cAbsSum += dc; s.cSqSum += dc * dc; if (dc > s.cAbsMax) s.cAbsMax = dc;
        s.bAbsSum += db; s.bSqSum += db * db; if (db > s.bAbsMax) s.bAbsMax = db;
        s.bEnvAbsSum += db * env; s.envSum += env;
        s.cSignedSum += (mc - ic); s.bSignedSum += (mb - ib);

        if (havePrev && phase != 2 && prevPhase != 2) {
            const double jc = std::fabs(mc - prevMc), jb = std::fabs(mb - prevMb);
            if (jc > maxJumpC) { maxJumpC = jc; maxJumpAt = gidx; maxJumpFrom = prevPhase; maxJumpTo = phase; }
            if (jb > maxJumpB) maxJumpB = jb;
        }
        prevMc = mc; prevMb = mb; prevPhase = phase; havePrev = true;

        const bool inWin = (winStart >= 0 && gidx >= winStart && gidx <= winEnd);
        if (gidx % stride == 0 || inWin) {
            std::fprintf(fTrace, "%lld,%d,%.6f,%.9f,%.9f,%.9f,%.9f,%.6f\n",
                         gidx, phase, tCurve, mLo, mUp, iLo, iUp, env);
        }
    };

    int pkgIndex = 0;
    while (produced < targetSamples) {
        const float timeSeconds = curveTime;
        PackagePlan plan = planner.planPackage(bufferDurationMs, config, state,
                                               timeSeconds, timeScale);
        int segIdx = 0;
        long long pkgSamples = 0;
        double currentTime = static_cast<double>(timeSeconds);
        const double secPerSample = static_cast<double>(timeScale) / static_cast<double>(sr);
        const int fadePieceSamples = (100 * sr + 500) / 1000;

        for (const auto& seg : plan.segments) {
            const int samples = static_cast<int>((seg.durationMs * sr) / 1000);
            std::fprintf(fPlan, "%d,%d,%d,%lld,%lld,%lld,%d\n", pkgIndex, segIdx,
                         static_cast<int>(seg.type),
                         static_cast<long long>(seg.durationMs),
                         static_cast<long long>(seg.fadeOffsetMs),
                         static_cast<long long>(seg.fadeTotalMs),
                         seg.swapAfterSegment ? 1 : 0);
            segIdx++;
            if (samples <= 0) continue;   // генератор такие сегменты пропускает
            if (produced + pkgSamples + samples > targetSamples) break;

            const int phase = static_cast<int>(seg.type);

            if (phase == 1 && winArmed) {  // FADE_OUT — центр окна трассировки
                winStart = produced + pkgSamples - winHalf;
                winEnd = produced + pkgSamples + samples + winHalf;
                if (winStart < 0) winStart = 0;
                winArmed = false;
            }

            if (phase == 0) {  // SOLID
                const bool stepMode = (itype == InterpolationType::STEP) && curve.points.size() > 1;
                if (stepMode) {
                    const std::vector<int> bounds = stepBounds(curve, currentTime, secPerSample, samples);
                    if (!bounds.empty()) {
                        int pieceStart = 0;
                        for (size_t k = 0; k <= bounds.size(); ++k) {
                            const int pieceEnd = (k < bounds.size()) ? bounds[k] : samples;
                            const auto f = curve.getChannelFrequenciesAt(
                                static_cast<float>(currentTime + pieceStart * secPerSample));
                            for (int i = pieceStart; i < pieceEnd; ++i) {
                                record(produced + pkgSamples + i, phase,
                                       currentTime + i * secPerSample, f.lowerFreq, f.upperFreq, 1.0);
                            }
                            pieceStart = pieceEnd;
                        }
                    } else {
                        const auto f = curve.getChannelFrequenciesAt(static_cast<float>(currentTime));
                        for (int i = 0; i < samples; ++i)
                            record(produced + pkgSamples + i, phase,
                                   currentTime + i * secPerSample, f.lowerFreq, f.upperFreq, 1.0);
                    }
                } else {
                    const auto f0 = curve.getChannelFrequenciesAt(static_cast<float>(currentTime));
                    const auto f1 = curve.getChannelFrequenciesAt(
                        static_cast<float>(currentTime + samples * secPerSample));
                    const double dl = (static_cast<double>(f1.lowerFreq) - f0.lowerFreq) / (samples - 1);
                    const double du = (static_cast<double>(f1.upperFreq) - f0.upperFreq) / (samples - 1);
                    for (int i = 0; i < samples; ++i)
                        record(produced + pkgSamples + i, phase, currentTime + i * secPerSample,
                               f0.lowerFreq + dl * i, f0.upperFreq + du * i, 1.0);
                }
            } else if (phase == 2) {  // PAUSE — тишина, ошибка не накапливается
                for (int i = 0; i < samples; ++i) {
                    const auto [iLo, iUp] = ideal.at(currentTime + i * secPerSample);
                    if ((produced + pkgSamples + i) % stride == 0)
                        std::fprintf(fTrace, "%lld,2,%.6f,%.9f,%.9f,%.9f,%.9f,%.6f\n",
                                     produced + pkgSamples + i, currentTime + i * secPerSample,
                                     0.0, 0.0, iLo, iUp, 0.0);
                }
            } else {  // FADE_OUT / FADE_IN
                int64_t fadeOffsetMs = seg.fadeOffsetMs;
                int64_t fadeTotalMs = seg.fadeTotalMs;
                if (fadeTotalMs == 0) { fadeTotalMs = seg.durationMs; fadeOffsetMs = 0; }
                const int fadeOffsetSamples = static_cast<int>((fadeOffsetMs * sr + 500) / 1000);
                const int fadeTotalSamples = static_cast<int>((fadeTotalMs * sr + 500) / 1000);
                const bool fadingOut = (phase == 1);
                int gen = 0;
                while (gen < samples) {
                    const int ps = std::min(fadePieceSamples, samples - gen);
                    const double t0 = currentTime + static_cast<double>(gen) * secPerSample;
                    const double t1 = currentTime + static_cast<double>(gen + ps) * secPerSample;
                    const auto f0 = curve.getChannelFrequenciesAt(static_cast<float>(t0));
                    const auto f1 = curve.getChannelFrequenciesAt(static_cast<float>(t1));
                    const double dl = (ps > 1) ? (static_cast<double>(f1.lowerFreq) - f0.lowerFreq) / (ps - 1) : 0.0;
                    const double du = (ps > 1) ? (static_cast<double>(f1.upperFreq) - f0.upperFreq) / (ps - 1) : 0.0;
                    for (int i = 0; i < ps; ++i) {
                        const int fadeProgress = fadeOffsetSamples + gen + i;
                        double env = 1.0;
                        if (fadeProgress >= fadeTotalSamples) env = fadingOut ? 0.0 : 1.0;
                        else if (fadeProgress >= 0) {
                            const double cs = fadeCurve.get(static_cast<double>(fadeProgress) /
                                                            static_cast<double>(fadeTotalSamples));
                            env = fadingOut ? (1.0 - cs) : cs;
                        }
                        record(produced + pkgSamples + gen + i, phase,
                               t0 + i * secPerSample,
                               f0.lowerFreq + dl * i, f0.upperFreq + du * i, env);
                    }
                    gen += ps;
                }
            }

            if (seg.swapAfterSegment) {
                std::fprintf(fEvents, "%lld,%.6f,swap,%d\n", produced + pkgSamples + samples,
                             normalizeTod(currentTime + samples * secPerSample),
                             state.channelsSwapped ? 0 : 1);
                state.channelsSwapped = !state.channelsSwapped;
            }

            const float durationSec = static_cast<float>(samples) / static_cast<float>(sr);
            currentTime += static_cast<double>(durationSec) * timeScale;
            pkgSamples += samples;
        }

        if (pkgSamples <= 0) break;
        // Фикс 2: единый носитель времени — продвигаем на фактическое число сэмплов
        const float actualDurationSeconds = static_cast<float>(pkgSamples) / static_cast<float>(sr);
        curveTime = static_cast<float>(normalizeTod(curveTime + actualDurationSeconds * timeScale));
        // Фикс 3: коррекция дрейфа swap-цикла
        {
            const float plannedMs = static_cast<float>(bufferDurationMs);
            const float generatedMs = 1000.0f * static_cast<float>(pkgSamples) / static_cast<float>(sr);
            const float deltaMs = plannedMs - generatedMs;
            if (deltaMs > 0.0f && state.phaseRemainingMs > 0) {
                state.phaseRemainingMs -= static_cast<int64_t>(deltaMs + 0.5f);
                if (state.phaseRemainingMs < 0) state.phaseRemainingMs = 0;
            }
        }
        produced += pkgSamples;
        pkgIndex++;
    }

    std::fclose(fPlan);
    std::fclose(fTrace);
    std::fclose(fEvents);

    // ---------------- отчёт ----------------
    std::FILE* fStats = std::fopen((out + ".stats.csv").c_str(), "w");
    std::fprintf(fStats, "phase,n,time_s,c_abs_mean,c_abs_max,c_rms,"
                         "b_abs_mean,b_abs_max,b_rms,b_env_mean,c_signed_mean,b_signed_mean\n");
    for (int p = 0; p < 4; ++p) {
        const PhaseStats& s = st[p];
        if (s.n == 0) continue;
        const double n = static_cast<double>(s.n);
        std::fprintf(fStats, "%s,%lld,%.4f,%.6e,%.6e,%.6e,%.6e,%.6e,%.6e,%.6e,%.6e,%.6e\n",
                     phaseName(p), s.n, n / sr,
                     s.cAbsSum / n, s.cAbsMax, std::sqrt(s.cSqSum / n),
                     s.bAbsSum / n, s.bAbsMax, std::sqrt(s.bSqSum / n),
                     s.bEnvAbsSum / n, s.cSignedSum / n, s.bSignedSum / n);
    }
    std::fclose(fStats);

    std::FILE* fMeta = std::fopen((out + ".meta.txt").c_str(), "w");
    std::fprintf(fMeta, "sr=%d\n", sr);
    std::fprintf(fMeta, "pkg_ms=%lld\n", static_cast<long long>(pkgMs));
    std::fprintf(fMeta, "buffer_duration_ms=%lld\n", static_cast<long long>(bufferDurationMs));
    std::fprintf(fMeta, "samples_per_channel=%d\n", samplesPerChannel);
    std::fprintf(fMeta, "start=%.6f\n", start);
    std::fprintf(fMeta, "dur=%.6f\n", dur);
    std::fprintf(fMeta, "curve=%s\n", curveName.c_str());
    std::fprintf(fMeta, "itype=%d\n", static_cast<int>(itype));
    std::fprintf(fMeta, "tension=%.3f\n", tension);
    std::fprintf(fMeta, "swap=%d\n", swap ? 1 : 0);
    std::fprintf(fMeta, "mode=%s\n", (mode == ChannelSwapMode::TREND) ? "trend" : "timer");
    std::fprintf(fMeta, "interval_sec=%d\n", intervalSec);
    std::fprintf(fMeta, "fade_ms=%lld\n", static_cast<long long>(fadeMs));
    std::fprintf(fMeta, "pause_ms=%lld\n", static_cast<long long>(pauseMs));
    std::fprintf(fMeta, "points=%d\n", static_cast<int>(points));
    std::fprintf(fMeta, "fade_enabled=%d\n", fadeEnabled ? 1 : 0);
    std::fprintf(fMeta, "timescale=%.3f\n", timeScale);
    std::fprintf(fMeta, "produced_samples=%lld\n", produced);
    std::fprintf(fMeta, "produced_seconds=%.4f\n", static_cast<double>(produced) / sr);
    std::fprintf(fMeta, "packages=%d\n", pkgIndex);
    std::fprintf(fMeta, "trend_crossings=%zu\n", curve.trendCrossings.size());
    for (size_t i = 0; i < curve.trendCrossings.size(); ++i)
        std::fprintf(fMeta, "trend_crossing_%.3zu=%.4f,%d\n", i,
                     curve.trendCrossings[i].timeSec, curve.trendCrossings[i].toSwapped ? 1 : 0);
    std::fprintf(fMeta, "max_jump_carrier_hz=%.6e\n", maxJumpC);
    std::fprintf(fMeta, "max_jump_beat_hz=%.6e\n", maxJumpB);
    std::fprintf(fMeta, "max_jump_at_sample=%lld\n", maxJumpAt);
    std::fprintf(fMeta, "max_jump_from=%s\n", maxJumpFrom >= 0 ? phaseName(maxJumpFrom) : "-");
    std::fprintf(fMeta, "max_jump_to=%s\n", maxJumpTo >= 0 ? phaseName(maxJumpTo) : "-");
    std::fclose(fMeta);

    std::printf("produced %.2f s in %d packages; crossings=%zu; maxJump(carrier)=%.3e Hz\n",
                static_cast<double>(produced) / sr, pkgIndex, curve.trendCrossings.size(), maxJumpC);
    return 0;
}
