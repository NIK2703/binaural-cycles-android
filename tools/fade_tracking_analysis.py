#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Количественный анализ следования частоты графику в фазах FADE_OUT/PAUSE/FADE_IN
(перестановка каналов) по сравнению с SOLID (обычное время без фейдов).

Точный порт пайплайна C++:
  Interpolation.h       -> monotone/linear/cardinal/step + buildLookupTableInternal
  Interpolation.h       -> FrequencyCurve::getChannelFrequenciesAt (таблица 100 мс)
  BufferPackagePlanner  -> planPackage (TIMER-grid / TREND)
  AudioGenerator        -> generatePackage (хорда частоты на сегменте / кусочке <=100 мс)

Эталон («график») = точный сплайн по контрольным точкам БЕЗ lookup-таблицы:
то, что рисует UI и что пользователь считает графиком.

Ошибка внутри сегмента — гладкая функция позиции, поэтому берётся
аналитически на равномерной сетке проб (PROBES точек), а не посепмплово:
это даёт те же max/mean при в ~50 раз меньшей стоимости.
"""
import math
import numpy as np

DAY = 86400
TABLE_INTERVAL_MS = 100
TABLE_SIZE = DAY * 1000 // TABLE_INTERVAL_MS      # 864000
INTERVAL_S = TABLE_INTERVAL_MS / 1000.0           # 0.1
TREND_HALF_WINDOW_SEC = 60.0
PROBES = 257

LINEAR, CARDINAL, MONOTONE, STEP = 0, 1, 2, 3
TIMER, TREND = 0, 1
BOTH, PEAKS, TROUGHS = 0, 1, 2
SOLID, FADE_OUT, PAUSE, FADE_IN = 0, 1, 2, 3
PHASE_NAME = {SOLID: "SOLID", FADE_OUT: "FADE_OUT", PAUSE: "PAUSE", FADE_IN: "FADE_IN"}
SOLID_SUBSEGMENT_MS = 100


# ============================================================================
# Интерполяция (порт Interpolation.h)
# ============================================================================
def _cardinal_arr(p0, p1, p2, p3, t, tension):
    t2, t3 = t * t, t * t * t
    s = (1.0 - tension) / 2.0
    m1, m2 = (p2 - p0) * s, (p3 - p1) * s
    h00, h10 = 2 * t3 - 3 * t2 + 1, t3 - 2 * t2 + t
    h01, h11 = -2 * t3 + 3 * t2, t3 - t2
    return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2


def _monotone_arr(p0, p1, p2, p3, t):
    d0, d1, d2 = p1 - p0, p2 - p1, p3 - p2
    m1 = np.where(d0 * d1 > 0, 2 * d0 * d1 / np.where(d0 + d1 == 0, 1.0, d0 + d1), 0.0)
    m2 = np.where(d1 * d2 > 0, 2 * d1 * d2 / np.where(d1 + d2 == 0, 1.0, d1 + d2), 0.0)
    t2, t3 = t * t, t * t * t
    h00, h10 = 2 * t3 - 3 * t2 + 1, t3 - 2 * t2 + t
    h01, h11 = -2 * t3 + 3 * t2, t3 - t2
    r = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2
    return np.clip(r, np.minimum(p1, p2), np.maximum(p1, p2))


def _eval(itype, p0, p1, p2, p3, ratio, tension):
    if itype == LINEAR:
        r = p1 + ratio * (p2 - p1)
    elif itype == STEP:
        r = np.broadcast_to(p1, np.shape(ratio)).astype(float)
    elif itype == CARDINAL:
        r = _cardinal_arr(p0, p1, p2, p3, ratio, tension)
    else:
        r = _monotone_arr(p0, p1, p2, p3, ratio)
    return np.maximum(r, 0.0)


class Curve:
    """points: список (t_sec, carrier, beat); itype, tension — как в конфиге."""

    def __init__(self, points, itype=MONOTONE, tension=0.0, build_trend=True):
        pts = sorted(points, key=lambda p: p[0])
        dedup = []
        for i, p in enumerate(pts):
            if i + 1 < len(pts) and pts[i + 1][0] == p[0]:
                continue
            dedup.append(p)
        self.points = dedup
        self.itype = itype
        self.tension = tension
        self.times = np.array([p[0] for p in dedup], dtype=np.float64)
        self.lower_pt = np.array([p[1] - p[2] / 2.0 for p in dedup], dtype=np.float64)
        self.upper_pt = np.array([p[1] + p[2] / 2.0 for p in dedup], dtype=np.float64)
        self.n = len(dedup)
        self.build_lookup()
        self.trend_crossings = []
        if build_trend:
            self.build_trend_crossings()

    # ---------- эталон: точный сплайн по контрольным точкам ----------
    def ideal(self, t):
        t = np.asarray(t, dtype=np.float64) % DAY
        n = self.n
        idx = np.searchsorted(self.times, t, side="right") - 1
        idx = np.where((t < self.times[0]) | (t >= self.times[-1]), n - 1, idx)
        idx = np.clip(idx, 0, n - 1)
        right = (idx + 1) % n
        t1 = self.times[idx]
        t2 = self.times[right].copy()
        wrap = idx == n - 1
        t2 = np.where(wrap, t2 + DAY, t2)
        tt = np.where(wrap & (t < t1), t + DAY, t)
        denom = t2 - t1
        ratio = np.where(denom != 0, (tt - t1) / np.where(denom == 0, 1.0, denom), 0.0)
        ratio = np.clip(ratio, 0.0, 1.0)
        prev = (idx - 1 + n) % n
        nxt = (right + 1) % n
        lo = _eval(self.itype, self.lower_pt[prev], self.lower_pt[idx],
                   self.lower_pt[right], self.lower_pt[nxt], ratio, self.tension)
        up = _eval(self.itype, self.upper_pt[prev], self.upper_pt[idx],
                   self.upper_pt[right], self.upper_pt[nxt], ratio, self.tension)
        return lo, up

    # ---------- lookup-таблица (порт buildLookupTableInternal) ----------
    def build_lookup(self):
        n = self.n
        ti = np.arange(TABLE_SIZE, dtype=np.float64) * INTERVAL_S
        left = np.clip(np.searchsorted(self.times, ti, side="right") - 1, 0, n - 1)
        left = np.where(ti < self.times[0], n - 1, left)
        right = (left + 1) % n
        t1 = self.times[left]
        t2 = self.times[right].copy()
        wrap = left == n - 1
        t2 = np.where(wrap, t2 + DAY, t2)
        tt = np.where(wrap & (ti < t1), ti + DAY, ti)
        denom = t2 - t1
        ratio = np.where(denom != 0, (tt - t1) / np.where(denom == 0, 1.0, denom), 0.0)
        ratio = np.clip(ratio, 0.0, 1.0)
        prev = (left - 1 + n) % n
        nxt = (right + 1) % n
        self.lower_tbl = _eval(self.itype, self.lower_pt[prev], self.lower_pt[left],
                               self.lower_pt[right], self.lower_pt[nxt], ratio, self.tension)
        self.upper_tbl = _eval(self.itype, self.upper_pt[prev], self.upper_pt[left],
                               self.upper_pt[right], self.upper_pt[nxt], ratio, self.tension)

    def lookup(self, t):
        t = np.asarray(t, dtype=np.float64) % DAY
        ci = t / INTERVAL_S
        i0 = np.clip(np.floor(ci).astype(np.int64), 0, TABLE_SIZE - 1)
        frac = ci - i0
        i1 = np.minimum((i0 + 1) % TABLE_SIZE, TABLE_SIZE - 1)
        lo = self.lower_tbl[i0] + frac * (self.lower_tbl[i1] - self.lower_tbl[i0])
        up = self.upper_tbl[i0] + frac * (self.upper_tbl[i1] - self.upper_tbl[i0])
        return lo, up

    def lookup_s(self, t):
        lo, up = self.lookup(np.array([float(t)]))
        return float(lo[0]), float(up[0])

    # ---------- нули тренда частоты биений (порт computeTrendCrossings) ----------
    def trend_beat_delta(self, t):
        lo_p, up_p = self.lookup_s(t + TREND_HALF_WINDOW_SEC)
        lo_m, up_m = self.lookup_s(t - TREND_HALF_WINDOW_SEC)
        return (up_p - lo_p) - (up_m - lo_m)

    def build_trend_crossings(self):
        times = sorted(set(float(p[0]) for p in self.points))
        min_gap = DAY - times[-1] + times[0]
        for i in range(1, len(times)):
            min_gap = min(min_gap, times[i] - times[i - 1])
        min_gap = max(min_gap, 1.0)
        grid_n = int(math.ceil(DAY / min(max(min_gap * 0.25, 0.25), 5.0)))
        step = DAY / grid_n

        cache = {}

        def sample_sec(i):
            return 0.0 if i >= grid_n else i * step

        def delta(i):
            if i not in cache:
                cache[i] = self.trend_beat_delta(sample_sec(i))
            return cache[i]

        def refine(lo, hi):
            lo_sign = self.trend_beat_delta(lo)
            for _ in range(40):
                if hi - lo <= 1e-4:
                    break
                mid = 0.5 * (lo + hi)
                if (self.trend_beat_delta(mid) > 0) == (lo_sign > 0):
                    lo = mid
                else:
                    hi = mid
            return 0.5 * (lo + hi)

        out = []
        last_i = last_s = None
        first_i = first_s = None
        for i in range(grid_n + 1):
            d = delta(i)
            if d == 0.0:
                continue
            if last_i is not None and (d > 0) != (last_s > 0):
                out.append((refine(last_i * step, i * step) % DAY, d < 0))
            if first_i is None:
                first_i, first_s = i, d
            last_i, last_s = i, d
        if (first_i is not None and last_i is not None and first_i != last_i
                and (first_s > 0) != (last_s > 0)):
            out.append((refine(last_i * step, DAY + first_i * step) % DAY, first_s < 0))
        self.trend_crossings = sorted(out, key=lambda c: c[0])


# ============================================================================
# Планировщик (порт BufferPackagePlanner::planPackage)
# ============================================================================
class Cfg:
    def __init__(self, curve, swap_enabled, mode=TIMER, interval_sec=300,
                 fade_ms=1000, pause_ms=0, trend_points=BOTH, fade_enabled=True):
        self.curve = curve
        self.swap_enabled = swap_enabled
        self.mode = mode
        self.interval_sec = interval_sec
        self.fade_ms = fade_ms
        self.pause_ms = pause_ms
        self.trend_points = trend_points
        self.fade_enabled = fade_enabled

    def phase_duration(self, phase):
        if phase == SOLID:
            return self.interval_sec * 1000
        if phase in (FADE_OUT, FADE_IN):
            return max(self.fade_ms, 15) if self.fade_enabled else 15
        return self.pause_ms


def timer_solid_duration_ms(pos, interval_sec, time_scale=1.0):
    if interval_sec <= 0:
        return 0
    pos %= DAY
    return max(int((interval_sec - (pos % interval_sec)) * 1000.0 / time_scale), 0)


def trend_solid_duration_ms(curve, pos, time_scale, lead_ms, points, swap_offset_ms):
    best = None
    for tc, to_swapped in curve.trend_crossings:
        if points != BOTH and (to_swapped != (points == PEAKS)):
            continue
        rel = tc - (pos % DAY)
        if rel <= 0:
            rel += DAY
        if best is None or rel < best:
            best = rel
    if best is None:
        return 1800000
    raw = int(best * 1000.0 / time_scale) - lead_ms - swap_offset_ms
    if points == BOTH:
        return max(raw, 0)
    return min(max(raw, 0), 1800000)


def plan_package(cfg, state, package_ms, curve_pos, time_scale=1.0):
    segs = []
    if not cfg.swap_enabled:
        rem = package_ms
        while rem > 0:
            d = min(rem, SOLID_SUBSEGMENT_MS)
            segs.append((SOLID, d, 0, 0))
            rem -= d
        return segs, curve_pos

    trend_mode = cfg.mode == TREND
    grid_mode = cfg.mode == TIMER
    pos = curve_pos % DAY

    def start_phase_duration(phase):
        if phase == SOLID:
            if trend_mode:
                return trend_solid_duration_ms(cfg.curve, pos, time_scale,
                                               cfg.phase_duration(FADE_OUT),
                                               cfg.trend_points, cfg.pause_ms // 2)
            if grid_mode:
                return timer_solid_duration_ms(pos, cfg.interval_sec, time_scale)
        return cfg.phase_duration(phase)

    phase = state["phase"]
    rem_phase = state["phase_remaining"]
    if rem_phase == 0:
        rem_phase = start_phase_duration(phase)
    rem = package_ms
    guard = 0
    while rem > 0 and guard < 100000:
        guard += 1
        if rem_phase == 0:
            phase = (phase + 1) % 4
            rem_phase = start_phase_duration(phase)
            continue
        seg = min(rem, rem_phase)
        if phase == SOLID and seg > SOLID_SUBSEGMENT_MS:
            sr = seg
            while sr > 0 and rem > 0:
                d = min(sr, rem, SOLID_SUBSEGMENT_MS)
                segs.append((SOLID, d, 0, 0))
                pos = (pos + d * 0.001 * time_scale) % DAY
                sr -= d
                rem -= d
                rem_phase -= d
        else:
            foff = ftot = 0
            if phase in (FADE_OUT, FADE_IN):
                ftot = cfg.phase_duration(phase)
                foff = ftot - rem_phase
            segs.append((phase, seg, foff, ftot))
            pos = (pos + seg * 0.001 * time_scale) % DAY
            rem -= seg
            rem_phase -= seg
        if rem_phase == 0:
            phase = (phase + 1) % 4
            rem_phase = start_phase_duration(phase)
    state["phase"] = phase
    state["phase_remaining"] = rem_phase
    return segs, pos


def _step_bounds(curve, start_t, sec_per_sample, samples):
    end_t = start_t + sec_per_sample * samples
    bounds = set()
    for pt in curve.points:
        p = float(pt[0])
        for k in range(int(math.floor((start_t - p) / DAY)) - 1,
                       int(math.ceil((end_t - p) / DAY)) + 2):
            occ = p + k * DAY
            if occ <= start_t or occ >= end_t:
                continue
            n = int(math.ceil((occ - start_t) / sec_per_sample))
            if 0 < n < samples:
                bounds.add(n)
    return sorted(bounds)


# ============================================================================
# Генератор: фактическая мгновенная частота vs эталон
# ============================================================================
def _weights(n):
    """Трапециевидные веса на равномерной сетке индексов сэмплов [0, n-1]."""
    if n == 1:
        return np.array([1.0])
    w = np.full(n, 1.0 / (n - 1))
    w[0] = w[-1] = 0.5 / (n - 1)
    return w


def simulate(cfg, sr, start_t, total_seconds, package_ms, time_scale=1.0,
             legacy_fade=False, progress=False):
    """legacy_fade=True — поведение ДО фикса 4384bd1: одна хорда на весь фейд."""
    curve = cfg.curve
    state = {"phase": SOLID, "phase_remaining": 0}
    curve_pos = start_t % DAY
    sec_per_sample = time_scale / sr

    rec = {p: {"n": 0, "c_sum": 0.0, "c_max": 0.0, "b_sum": 0.0, "b_max": 0.0,
               "bw_sum": 0.0, "env_sum": 0.0}
           for p in (SOLID, FADE_OUT, PAUSE, FADE_IN)}

    t_cur = float(start_t % DAY)
    produced = 0
    target = int(total_seconds * sr)

    while produced < target:
        segs, curve_pos = plan_package(cfg, state, package_ms, curve_pos, time_scale)
        for (stype, dur_ms, foff_ms, ftot_ms) in segs:
            samples = (dur_ms * sr) // 1000
            if samples <= 0:
                continue
            samples = min(samples, target - produced)
            if samples <= 0:
                break
            r = rec[stype]
            r["n"] += samples

            if stype == PAUSE:
                produced += samples
                t_cur = (t_cur + (samples / sr) * time_scale) % DAY
                continue

            # сетка проб — глобальные индексы сэмплов внутри сегмента
            npr = min(PROBES, max(samples, 2))
            I = np.linspace(0.0, samples - 1.0, npr)
            w = _weights(npr)
            t_probe = (t_cur + I * sec_per_sample) % DAY

            if stype == SOLID:
                if curve.itype == STEP and curve.n > 1:
                    lo_a = np.empty(npr)
                    up_a = np.empty(npr)
                    bounds = _step_bounds(curve, t_cur, sec_per_sample, samples)
                    starts = [0] + bounds
                    ends = bounds + [samples]
                    for a, b in zip(starts, ends):
                        if b <= a:
                            continue
                        flo, fup = curve.lookup(np.array([(t_cur + a * sec_per_sample) % DAY]))
                        m = (I >= a) & (I < b) if b < samples else (I >= a)
                        lo_a[m] = flo[0]
                        up_a[m] = fup[0]
                else:
                    l0, u0 = curve.lookup(np.array([t_cur]))
                    l1, u1 = curve.lookup(np.array([t_cur + samples * sec_per_sample]))
                    u = I / max(samples - 1, 1)
                    lo_a = l0[0] + (l1[0] - l0[0]) * u
                    up_a = u0[0] + (u1[0] - u0[0]) * u
                env = np.ones(npr)
            elif legacy_fade:
                # ДО фикса: одна хорда на весь фейд-сегмент
                l0, u0 = curve.lookup(np.array([t_cur]))
                l1, u1 = curve.lookup(np.array([t_cur + samples * sec_per_sample]))
                u = I / max(samples - 1, 1)
                lo_a = l0[0] + (l1[0] - l0[0]) * u
                up_a = u0[0] + (u1[0] - u0[0]) * u
                foff = (foff_ms * sr + 500) // 1000
                ftot = (ftot_ms * sr + 500) // 1000
                prog = np.clip((foff + I) / ftot, 0.0, 1.0)
                cs = 0.5 * (1.0 - np.cos(prog * math.pi))
                env = (1.0 - cs) if stype == FADE_OUT else cs
            else:
                piece = (100 * sr + 500) // 1000
                foff = (foff_ms * sr + 500) // 1000
                ftot = (ftot_ms * sr + 500) // 1000
                lo_a = np.empty(npr)
                up_a = np.empty(npr)
                env = np.empty(npr)
                gen = 0
                while gen < samples:
                    ps = min(piece, samples - gen)
                    t0 = t_cur + gen * sec_per_sample
                    t1 = t_cur + (gen + ps) * sec_per_sample
                    l0, u0 = curve.lookup(np.array([t0]))
                    l1, u1 = curve.lookup(np.array([t1]))
                    m = (I >= gen) & (I < gen + ps) if gen + ps < samples else (I >= gen)
                    uu = np.clip((I[m] - gen) / max(ps - 1, 1), 0.0, 1.0)
                    lo_a[m] = l0[0] + (l1[0] - l0[0]) * uu
                    up_a[m] = u0[0] + (u1[0] - u0[0]) * uu
                    prog = np.clip((foff + gen + uu * max(ps - 1, 1)) / ftot, 0.0, 1.0)
                    cs = 0.5 * (1.0 - np.cos(prog * math.pi))
                    env[m] = (1.0 - cs) if stype == FADE_OUT else cs
                    gen += ps

            ilo, iup = curve.ideal(t_probe)
            dc = np.abs((lo_a + up_a) / 2.0 - (ilo + iup) / 2.0)
            db = np.abs((up_a - lo_a) - (iup - ilo))
            r["c_sum"] += float(np.dot(dc, w)) * samples
            r["c_max"] = max(r["c_max"], float(dc.max()))
            r["b_sum"] += float(np.dot(db, w)) * samples
            r["b_max"] = max(r["b_max"], float(db.max()))
            r["bw_sum"] += float(np.dot(db * env, w)) * samples
            r["env_sum"] += float(np.dot(env, w)) * samples

            produced += samples
            t_cur = (t_cur + (samples / sr) * time_scale) % DAY
        if progress:
            print(f"    {produced/sr:7.1f} / {total_seconds:.0f} s", flush=True)
    return rec


def report(title, rec, sr):
    print(f"\n### {title}")
    print(f"{'фаза':<9}{'доля':>7}{'время,с':>9}"
          f"{'|Δнес|ср':>12}{'|Δнес|макс':>12}"
          f"{'|Δбиен|ср':>12}{'|Δбиен|макс':>12}"
          f"{'|Δбиен|ср·огиб':>15}")
    tot = sum(v["n"] for v in rec.values()) or 1
    for ph in (SOLID, FADE_OUT, PAUSE, FADE_IN):
        v = rec[ph]
        if v["n"] == 0:
            continue
        if ph == PAUSE:
            print(f"{PHASE_NAME[ph]:<9}{v['n']/tot:>6.1%}{v['n']/sr:>9.1f}"
                  f"{'—':>12}{'—':>12}{'—':>12}{'—':>12}{'—':>15}   тишина")
            continue
        n = float(v["n"])
        print(f"{PHASE_NAME[ph]:<9}{v['n']/tot:>6.1%}{v['n']/sr:>9.1f}"
              f"{v['c_sum']/n:>12.3e}{v['c_max']:>12.3e}"
              f"{v['b_sum']/n:>12.3e}{v['b_max']:>12.3e}"
              f"{v['bw_sum']/n:>15.3e}")


def main():
    sr = 48000
    pts = [(0, 174.0, 3.0), (10800, 210.0, 6.0), (21600, 220.0, 8.0), (32400, 440.0, 20.0),
           (43200, 440.0, 25.0), (54000, 440.0, 18.0), (64800, 250.0, 12.0), (75600, 240.0, 10.0)]
    curve = Curve(pts, MONOTONE, 0.0)
    pkg = 200
    print(f"Кривая «Циркадный ритм», MONOTONE, точек: {curve.n}", flush=True)
    print(f"Экстремумов beat за сутки: {len(curve.trend_crossings)} -> "
          + ", ".join(f"{t/3600:.2f}h" for t, _ in curve.trend_crossings), flush=True)
    print(f"sr={sr}, пакет {pkg} мс", flush=True)

    # --- TIMER: окно 06:00 (самый крутой участок) ---
    start = 6 * 3600.0
    print("\n" + "=" * 96)
    print("БЛОК 1. TIMER (смена по таймеру), окно 30 мин от 06:00 — самый крутой участок кривой")
    print("=" * 96, flush=True)
    for title, cfg, legacy in [
        ("A. Без перестановки каналов — «обычное время без фейдов» (чистый SOLID)",
         Cfg(curve, False), False),
        ("B. TIMER: интервал 300 с, фейд 1000 мс, пауза 0  [ТЕКУЩИЙ КОД]",
         Cfg(curve, True, TIMER, 300, 1000, 0), False),
        ("B'. То же, но ДО фикса 4384bd1 (одна хорда на весь фейд)",
         Cfg(curve, True, TIMER, 300, 1000, 0), True),
        ("C. TIMER-стресс: интервал 60 с, фейд 3000 мс, пауза 1000 мс",
         Cfg(curve, True, TIMER, 60, 3000, 1000), False),
        ("C'. То же, ДО фикса",
         Cfg(curve, True, TIMER, 60, 3000, 1000), True),
    ]:
        report(title, simulate(cfg, sr, start, 1800.0, pkg, legacy_fade=legacy), sr)

    # --- TREND: окно вокруг экстремума 12:00 ---
    start = 11.5 * 3600.0        # 11:30
    print("\n" + "=" * 96)
    print("БЛОК 2. TREND (смена в экстремумах beat), окно 30 мин от 11:30 — захватывает пик 12:00")
    print("=" * 96, flush=True)
    for title, cfg, legacy in [
        ("D. TREND/BOTH: фейд 1000 мс, пауза 0  [ТЕКУЩИЙ КОД]",
         Cfg(curve, True, TREND, 300, 1000, 0, BOTH), False),
        ("D'. То же, ДО фикса",
         Cfg(curve, True, TREND, 300, 1000, 0, BOTH), True),
        ("E. TREND/BOTH-стресс: фейд 5000 мс, пауза 2000 мс",
         Cfg(curve, True, TREND, 300, 5000, 2000, BOTH), False),
        ("E'. То же, ДО фикса",
         Cfg(curve, True, TREND, 300, 5000, 2000, BOTH), True),
    ]:
        report(title, simulate(cfg, sr, start, 1800.0, pkg, legacy_fade=legacy), sr)

    # --- Крутая кривая: быстрая развёртка, чтобы хорда стала видна ---
    print("\n" + "=" * 96)
    print("БЛОК 3. Стресс-кривая: 100 -> 900 Гц за 1 час (MONOTONE) — хорда перестаёт быть пренебрежимой")
    print("=" * 96, flush=True)
    fast = [(0, 100.0, 2.0), (3600, 900.0, 40.0), (86399, 100.0, 2.0)]
    cfast = Curve(fast, MONOTONE, 0.0, build_trend=False)
    for title, cfg, legacy in [
        ("F. SOLID (без свапа)", Cfg(cfast, False), False),
        ("G. TIMER 300 с / фейд 1000 мс  [ТЕКУЩИЙ КОД]",
         Cfg(cfast, True, TIMER, 300, 1000, 0), False),
        ("G'. То же, ДО фикса", Cfg(cfast, True, TIMER, 300, 1000, 0), True),
        ("H. TIMER 300 с / фейд 10000 мс  [ТЕКУЩИЙ КОД]",
         Cfg(cfast, True, TIMER, 300, 10000, 0), False),
        ("H'. То же, ДО фикса", Cfg(cfast, True, TIMER, 300, 10000, 0), True),
    ]:
        report(title, simulate(cfg, sr, 300.0, 1800.0, pkg, legacy_fade=legacy), sr)

    # --- STEP: ступенька попадает точно в фейд ---
    print("\n" + "=" * 96)
    print("БЛОК 4. Интерполяция STEP: контрольная точка ровно внутри фейд-окна")
    print("=" * 96, flush=True)
    cstep = Curve(pts, STEP, 0.0, build_trend=False)
    # TIMER-сетка 300 с: узел 21900 с (06:05). Фейд-аут идёт [21899, 21900).
    # Окно начинаем в 21897 с — и фейд, и лежащая рядом контрольная точка (06:00 уже позади),
    # поэтому берём кривую со ступенькой прямо посередине фейда.
    step_pts = [(0, 200.0, 4.0), (21899, 200.0, 4.0), (21900, 500.0, 30.0),
                (30000, 500.0, 30.0), (86399, 200.0, 4.0)]
    cstep2 = Curve(step_pts, STEP, 0.0, build_trend=False)
    for title, cfg in [
        ("I. STEP, SOLID (ступинка в SOLID-сегменте)", Cfg(cstep2, False)),
        ("J. STEP, TIMER 300 с / фейд 1000 мс (ступинка ВНУТРИ фейда)",
         Cfg(cstep2, True, TIMER, 300, 1000, 0)),
    ]:
        report(title, simulate(cfg, sr, 21897.0, 20.0, pkg), sr)
    del cstep


if __name__ == "__main__":
    main()
