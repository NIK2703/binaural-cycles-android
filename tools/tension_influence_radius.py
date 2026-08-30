#!/usr/bin/env python3
"""
Радиус влияния динамического натяжения кардинального сплайна.

ВОПРОС
------
Когда канальная кривая (CARDINAL) выскакивает за вертикальные границы пресета
`carrierRange`, регуляция `CardinalTension` укорачивает касательные. Какие
участки кривой при этом меняют форму — только заскакивающий сегмент или и
соседние?

ОТВЕТ (подтверждён этим скриптом)
---------------------------------
Не только заскакивающий. Сегмент [i, i+1] меняется тогда и только тогда, когда
заскакивает i-1, i или i+1 (циклически): вес гасится в УЗЛЕ, а одна касательная
узла обслуживает ДВА смежных сегмента.

  проход 1: k[i] = min по каналам max_scale(интервал i..i+1)
  проход 2: w[i] = min(k[i-1], k[i])     <- цена сохранения C1
  => один плохой интервал i гасит w[i] и w[i+1]
  => сегменты [i-1,i] (берёт w[i]) и [i+1,i+2] (берёт w[i+1]) тоже меняют форму

Это точный порт Kotlin-реализации
`core/audio/src/main/java/com/binaural/core/audio/model/CardinalTension.kt`
(`computeSharedWeights`). В продакшене float32, здесь float64 — на выводы не
влияет (запас до порогов 1e-3 Гц — порядки).

Запуск:  python3 tools/tension_influence_radius.py   (~40 с)
"""

import math
import random

TOL = 0.1          # CardinalTension.TOLERANCE_HZ
EPS = 1e-12        # CardinalTension.EPS
SLACK = 1e-4       # CardinalTension.SLACK
BISECTION = 24     # CardinalTension.BISECTION_STEPS
MAX_SWEEPS = 8     # CardinalTension.MAX_SWEEPS
MIN_TONE = 20.0    # FrequencyMath.MIN_TONE_FREQUENCY
MAX_TONE = 2000.0  # FrequencyMath.MAX_TONE_FREQUENCY


# --------------------------------------------------------------------------
# Базис кубического Эрмита (Interpolation::cardinal)
# --------------------------------------------------------------------------
def hermite(p1, p2, m1, m2, t):
    t2, t3 = t * t, t * t * t
    return ((2*t3 - 3*t2 + 1)*p1 + (t3 - 2*t2 + t)*m1
            + (-2*t3 + 3*t2)*p2 + (t3 - t2)*m2)


def cubic_range(p1, p2, m1, m2):
    """Точный min/max кубики на [0,1] — из корней производной, не сэмплирование."""
    lo, hi = min(p1, p2), max(p1, p2)
    d = p2 - p1
    a = 3*(m1 + m2 - 2*d)
    b = 2*(3*d - 2*m1 - m2)
    c = m1
    ts = []
    if abs(a) < EPS:
        if abs(b) > EPS:
            t = -c/b
            if 0 < t < 1:
                ts.append(t)
    else:
        disc = b*b - 4*a*c
        if disc > 0:
            sq = math.sqrt(disc)
            for t in ((-b-sq)/(2*a), (-b+sq)/(2*a)):
                if 0 < t < 1:
                    ts.append(t)
    for t in ts:
        v = hermite(p1, p2, m1, m2, t)
        if v < lo:
            lo = v
        elif v > hi:
            hi = v
    return lo, hi


def feasible(p1, p2, m1, m2, lo, hi):
    """Эффективные границы: узел вне [lo,hi] неисправим, требуем лишь, чтобы
    касательные не добавили нарушения сверх заданного узлами."""
    lo_eff, hi_eff = min(lo, p1, p2), max(hi, p1, p2)
    a, b = cubic_range(p1, p2, m1, m2)
    return a >= lo_eff - TOL - SLACK and b <= hi_eff + TOL + SLACK


def max_scale(p1, p2, m1, m2, lo, hi):
    """Наибольший k in [0,1] с допустимыми касательными (k*m1, k*m2)."""
    if feasible(p1, p2, m1, m2, lo, hi):
        return 1.0
    if not feasible(p1, p2, 0.0, 0.0, lo, hi):
        return 0.0
    good, bad = 0.0, 1.0
    for _ in range(BISECTION):
        mid = (good + bad)*0.5
        if feasible(p1, p2, m1*mid, m2*mid, lo, hi):
            good = mid
        else:
            bad = mid
    return good


def compute_shared_weights(lower, upper, tension, lo, hi):
    """Порт CardinalTension.computeSharedWeights -> (w, k, sweeps)."""
    n = len(lower)
    w = [1.0]*n
    if n < 2 or not (hi > lo):
        return w, [1.0]*n, 0
    s = (1.0 - tension)/2.0
    ml = [(lower[(i+1) % n] - lower[(i-1) % n])*s for i in range(n)]
    mu = [(upper[(i+1) % n] - upper[(i-1) % n])*s for i in range(n)]

    k = [1.0]*n
    for i in range(n):
        j = (i+1) % n
        k[i] = min(max_scale(lower[i], lower[j], ml[i], ml[j], lo, hi),
                   max_scale(upper[i], upper[j], mu[i], mu[j], lo, hi))

    for i in range(n):
        w[i] = min(k[(i-1) % n], k[i])

    sweeps = 0
    for _ in range(MAX_SWEEPS):
        changed = False
        for i in range(n):
            j = (i+1) % n
            if (feasible(lower[i], lower[j], ml[i]*w[i], ml[j]*w[j], lo, hi) and
                    feasible(upper[i], upper[j], mu[i]*w[i], mu[j]*w[j], lo, hi)):
                continue
            need = min(
                max_scale(lower[i], lower[j], ml[i]*w[i], ml[j]*w[j], lo, hi),
                max_scale(upper[i], upper[j], mu[i]*w[i], mu[j]*w[j], lo, hi))
            w[i] *= need
            w[j] *= need
            changed = True
        if not changed:
            break
        sweeps += 1
    return w, k, sweeps


# --------------------------------------------------------------------------
# Вспомогательное
# --------------------------------------------------------------------------
def valid_beat(carrier, beat, lo, hi):
    """Повторяет FrequencyMath.clampBeat."""
    cap = 2.0*min(carrier - lo, hi - carrier)
    cap = max(0.0, min(cap, 2.0*(carrier - MIN_TONE), 2.0*(MAX_TONE - carrier)))
    return math.copysign(min(abs(beat), cap), beat)


def channels(pts):
    return ([c - b/2 for (_, c, b) in pts],
            [c + b/2 for (_, c, b) in pts])


def tangents(y, tension):
    s = (1.0 - tension)/2.0
    n = len(y)
    return [(y[(i+1) % n] - y[(i-1) % n])*s for i in range(n)]


def cyclic_dist(a, b, n):
    d = abs(a - b) % n
    return min(d, n - d)


def random_curve(rng):
    n = rng.randint(3, 14)
    lo = rng.choice([20.0, 60.0, 100.0, 200.0])
    hi = lo + rng.choice([150.0, 500.0, 1500.0])
    pts = []
    for i in range(n):
        c = rng.uniform(lo, hi)
        pts.append((i*(86400//n), c, valid_beat(c, rng.uniform(-60, 60), lo, hi)))
    return pts, rng.choice([0.0, 0.0, 0.25, 0.5]), max(lo, MIN_TONE), min(hi, MAX_TONE)


def scan_intervals(pts, tension, lo, hi, per=120):
    """По каждому сегменту: заскакивал ли номинально, на сколько Гц изменился."""
    n = len(pts)
    L, R = channels(pts)
    ML, MR = tangents(L, tension), tangents(R, tension)
    w, k, sweeps = compute_shared_weights(L, R, tension, lo, hi)
    rows = []
    for i in range(n):
        j = (i+1) % n
        bad = not (feasible(L[i], L[j], ML[i], ML[j], lo, hi) and
                   feasible(R[i], R[j], MR[i], MR[j], lo, hi))
        still_bad = not (feasible(L[i], L[j], w[i]*ML[i], w[j]*ML[j], lo, hi) and
                         feasible(R[i], R[j], w[i]*MR[i], w[j]*MR[j], lo, hi))
        d = 0.0
        for q in range(per+1):
            t = q/per
            d = max(d,
                    abs(hermite(L[i], L[j], w[i]*ML[i], w[j]*ML[j], t)
                        - hermite(L[i], L[j], ML[i], ML[j], t)),
                    abs(hermite(R[i], R[j], w[i]*MR[i], w[j]*MR[j], t)
                        - hermite(R[i], R[j], MR[i], MR[j], t)))
        rows.append(dict(i=i, k=k[i], w1=w[i], w2=w[j], bad=bad,
                         still_bad=still_bad, dev=d))
    return rows, w, sweeps


# --------------------------------------------------------------------------
# Раздел 1. Показательные пресетs
# --------------------------------------------------------------------------
def section_presets():
    print("="*78)
    print(" 1. ПОКАЗАТЕЛЬНЫЕ ПРЕСЕТЫ — какой сегмент и на сколько меняется")
    print("="*78)
    cases = [
        ("пик у верхней границы (8 узлов)",
         [(0, 174, 3), (10800, 210, 6), (21600, 220, 8), (32400, 560, 20),
          (43200, 575, 25), (54000, 560, 18), (64800, 250, 12), (75600, 240, 10)]),
        ("провал у нижней границы (4 узла)",
         [(0, 500, 4), (21600, 105, 6), (43200, 110, 8), (64800, 520, 10)]),
        ("два смежных заскока (4 узла)",
         [(0, 590, 18), (21600, 101, 4), (43200, 595, 8), (64800, 300, 14)]),
    ]
    for name, raw in cases:
        pts = [(t, c, valid_beat(c, b, 100.0, 600.0)) for (t, c, b) in raw]
        rows, w, sweeps = scan_intervals(pts, 0.0, 100.0, 600.0, per=400)
        n = len(pts)
        print(f"\n{name}  bounds=[100,600] tension=0")
        print(f"  w = [{', '.join(f'{x:.3f}' for x in w)}]  sweeps={sweeps}")
        print("  сегмент   k[i]   w[i]  w[i+1]  заскок   Δ, Гц")
        for r in rows:
            print(f"  [{r['i']:2d}→{(r['i']+1) % n:2d}]   {r['k']:5.3f} {r['w1']:5.3f}"
                  f" {r['w2']:6.3f}   {'ДА ' if r['bad'] else 'нет'}  {r['dev']:7.3f}")


# --------------------------------------------------------------------------
# Раздел 2. Стресс: радиус влияния и цена для соседей
# --------------------------------------------------------------------------
def section_stress(trials=6000):
    print("\n" + "="*78)
    print(f" 2. СТРЕСС ({trials} случайных кривых)")
    print("="*78)
    rng = random.Random(4242)
    radius_hist, sweep_hist = {}, {}
    ratios, wider = [], 0
    new_violation = 0
    curves = 0
    for _ in range(trials):
        pts, tension, lo, hi = random_curve(rng)
        rows, w, sweeps = scan_intervals(pts, tension, lo, hi, per=60)
        n = len(pts)
        sweep_hist[sweeps] = sweep_hist.get(sweeps, 0) + 1
        badset = [r["i"] for r in rows if r["bad"]]
        if not badset:
            continue
        curves += 1
        touched = [r["i"] for r in rows if r["dev"] > 1e-3]
        rad = max(min(cyclic_dist(t, b, n) for b in badset) for t in touched)
        radius_hist[rad] = radius_hist.get(rad, 0) + 1
        # теоретическая зона = плохие +- 1 интервал
        theo = set()
        for b in badset:
            theo.update(((b-1) % n, b, (b+1) % n))
        if set(touched) - theo:
            wider += 1
        if any(r["still_bad"] and not r["bad"] for r in rows):
            new_violation += 1
        d_bad = max(r["dev"] for r in rows if r["bad"])
        nb = [r["dev"] for r in rows if not r["bad"] and r["dev"] > 1e-3]
        if nb and d_bad > 1e-3:
            ratios.append(max(nb)/d_bad)

    ratios.sort()
    print(f"  кривых с заскоком                       : {curves} из {trials}")
    print(f"  радиус влияния (интервалов)             : "
          f"{dict(sorted(radius_hist.items()))}")
    print(f"  зона шире теоретической (плохие +-1)    : {wider}")
    print(f"  корректирующие свипы (все кривые)       : "
          f"{dict(sorted(sweep_hist.items()))}")
    print(f"  чистый сегмент стал нарушенным ПОСЛЕ    : {new_violation}")
    print(f"  (макс. Δ соседа)/(макс. Δ виновника)    : "
          f"медиана {ratios[len(ratios)//2]:.3f}, "
          f"90-й проц. {ratios[int(len(ratios)*0.9)]:.3f}, макс {ratios[-1]:.3f}")
    print(f"  сосед меняется сильнее виновника        : "
          f"{sum(1 for r in ratios if r >= 1.0)/len(ratios)*100:.1f} % кривых")
    print(f"  сосед меняется более чем на 25 %        : "
          f"{sum(1 for r in ratios if r >= 0.25)/len(ratios)*100:.1f} % кривых")


# --------------------------------------------------------------------------
# Раздел 3. Граница искажения и перекрёстное влияние каналов
# --------------------------------------------------------------------------
def section_channels(trials=4000):
    print("\n" + "="*78)
    print(f" 3. ГРАНИЦА ИСКАЖЕНИЯ И «НЕВИНОВНЫЙ» КАНАЛ ({trials} кривых)")
    print("="*78)
    H = 4.0/27.0   # max|h10| = max|h11| на [0,1]
    rng = random.Random(2024)
    worst_bound = 0.0
    one_channel, both = 0, 0
    cross, dev_beat_bad, dev_beat_nb = [], [], []
    for _ in range(trials):
        pts, tension, lo, hi = random_curve(rng)
        n = len(pts)
        L, R = channels(pts)
        B = [b for (_, _, b) in pts]
        ML, MR, MB = tangents(L, tension), tangents(R, tension), tangents(B, tension)
        w, k, _ = compute_shared_weights(L, R, tension, lo, hi)
        if all(x >= 1.0 - 1e-12 for x in w):
            continue
        for i in range(n):
            j = (i+1) % n
            bad_l = not feasible(L[i], L[j], ML[i], ML[j], lo, hi)
            bad_r = not feasible(R[i], R[j], MR[i], MR[j], lo, hi)
            if bad_l or bad_r:
                one_channel += 1 if bad_l != bad_r else 0
                both += 1 if (bad_l and bad_r) else 0
            d = {}
            for tag, (y, M) in (("L", (L, ML)), ("R", (R, MR))):
                dev = max(abs(hermite(y[i], y[j], w[i]*M[i], w[j]*M[j], q/80)
                              - hermite(y[i], y[j], M[i], M[j], q/80))
                          for q in range(80))
                d[tag] = dev
                bound = H*(abs(1-w[i])*abs(M[i]) + abs(1-w[j])*abs(M[j]))
                if bound > 1e-9:
                    worst_bound = max(worst_bound, dev/bound)
            if max(d["L"], d["R"]) > 1e-3:
                cross.append(min(d["L"], d["R"])/max(d["L"], d["R"]))
            dbeat = max(abs(hermite(B[i], B[j], w[i]*MB[i], w[j]*MB[j], q/80)
                            - hermite(B[i], B[j], MB[i], MB[j], q/80))
                        for q in range(80))
            (dev_beat_bad if (bad_l or bad_r) else dev_beat_nb).append(dbeat)

    cross.sort()
    total = one_channel + both
    print(f"  заскочивших сегментов: один канал {one_channel}, оба {both}"
          f"  => только один канал в {100.0*one_channel/total:.1f} % случаев")
    print(f"  факт / граница 4/27·(|1-w_i||M_i|+|1-w_j||M_j|) <= "
          f"{worst_bound:.3f}  (ожидается <= 1.000)")
    print(f"  Δ «невиновного» канала / Δ виновного   : медиана "
          f"{cross[len(cross)//2]:.3f}, 10-й проц. {cross[len(cross)//10]:.3f}")
    print(f"  «невиновный» канал меняется >80 %      : "
          f"{sum(1 for r in cross if r >= 0.8)/len(cross)*100:.1f} % сегментов")
    print(f"  макс. Δ кривой БИЕНИЙ: на заскочивших {max(dev_beat_bad):.2f} Гц, "
          f"на чистых соседях {max(dev_beat_nb):.2f} Гц")


if __name__ == "__main__":
    section_presets()
    section_stress()
    section_channels()
