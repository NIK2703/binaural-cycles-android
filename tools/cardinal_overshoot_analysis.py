#!/usr/bin/env python3
"""
Анализ заскока канальных кривых за границы carrierRange при CARDINAL
интерполяции и проверка решения — локальной регуляции натяжения
ОБЩИМ весом w[i] на обоих каналах.

Запуск:  python3 tools/cardinal_overshoot_analysis.py   (~40 с)

Три раздела:
  1. Показательные пресеты (до/после, shared против independent).
  2. Стресс: 5000 случайных кривых — остаточные нарушения, сохранность биений.
  3. Верификация инвариантов: границы, C1-непрерывность, тождество
     beat == spline(beat knots) с теми же весами.

Соответствие продакшену:
  C++   core/audio/src/main/cpp/include/Interpolation.h :: Interpolation::cardinal
  Kotlin core/audio/src/main/java/com/binaural/core/audio/model/Interpolation.kt
  Границы: FrequencyMath.MIN_TONE_FREQUENCY / MAX_TONE_FREQUENCY,
           FrequencyCurve.carrierRange
"""

import math
import random

MIN_TONE = 20.0      # FrequencyMath.MIN_TONE_FREQUENCY
MAX_TONE = 2000.0    # FrequencyMath.MAX_TONE_FREQUENCY
TOL = 0.1            # Гц, допуск на касание границы (BOUND_TOLERANCE_HZ)


# --------------------------------------------------------------------------
# Базис кубического Эрмита (точная копия Interpolation::cardinal)
# --------------------------------------------------------------------------
def hermite_basis(t):
    t2, t3 = t * t, t * t * t
    return (2 * t3 - 3 * t2 + 1, t3 - 2 * t2 + t, -2 * t3 + 3 * t2, t3 - t2)


def hermite(p1, p2, m1, m2, t):
    h00, h10, h01, h11 = hermite_basis(t)
    return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2


def d_hermite(p1, p2, m1, m2, t):
    """Производная по t — нужна для проверки C1."""
    t2 = t * t
    return ((6 * t2 - 6 * t) * p1 + (3 * t2 - 4 * t + 1) * m1
            + (-6 * t2 + 6 * t) * p2 + (3 * t2 - 2 * t) * m2)


def cardinal(p0, p1, p2, p3, t, tension):
    """Дословный порт Interpolation::cardinal — без клампа, отсюда заскок."""
    s = (1.0 - tension) / 2.0
    return hermite(p1, p2, s * (p2 - p0), s * (p3 - p1), t)


def nominal_tangents(y, tension):
    """Циклические (24 ч) касательные Катмулла-Рома: M_i = s*(y[i+1]-y[i-1])."""
    s = (1.0 - tension) / 2.0
    n = len(y)
    return [s * (y[(i + 1) % n] - y[(i - 1) % n]) for i in range(n)]


# --------------------------------------------------------------------------
# Точный экстремум кубики на [0,1] — решается производная, не сэмплирование
# --------------------------------------------------------------------------
def cubic_range(p1, p2, m1, m2):
    d = p2 - p1
    A = 3 * (m1 + m2 - 2 * d)
    B = 2 * (3 * d - 2 * m1 - m2)
    C = m1
    cands = [0.0, 1.0]
    if abs(A) < 1e-12:
        if abs(B) > 1e-12:
            t = -C / B
            if 0.0 < t < 1.0:
                cands.append(t)
    else:
        disc = B * B - 4 * A * C
        if disc > 0.0:
            sq = math.sqrt(disc)
            for t in ((-B + sq) / (2 * A), (-B - sq) / (2 * A)):
                if 0.0 < t < 1.0:
                    cands.append(t)
    vals = [hermite(p1, p2, m1, m2, t) for t in cands]
    return min(vals), max(vals)


def feasible(p1, p2, m1, m2, lo, hi, tol=TOL):
    """Эффективные границы: узел вне [lo,hi] неисправим — кривая обязана
    через него пройти, поэтому требуем лишь «касательные не добавляют
    нарушения». Это гарантирует, что k=0 всегда допустим."""
    mn, mx = cubic_range(p1, p2, m1, m2)
    return mn >= min(lo, p1, p2) - tol and mx <= max(hi, p1, p2) + tol


def max_scale(p1, p2, M1, M2, lo, hi, iters=24, tol=TOL):
    """Наибольший k in [0,1]: касательные (k*M1, k*M2) держат кубику в
    границах. Допустимость монотонна по k (u-компонента базиса Эрмита
    растёт по k), k=0 допустим всегда => бисекция корректна."""
    if feasible(p1, p2, M1, M2, lo, hi, tol):
        return 1.0
    a, b = 0.0, 1.0
    for _ in range(iters):
        mid = 0.5 * (a + b)
        if feasible(p1, p2, mid * M1, mid * M2, lo, hi, tol):
            a = mid
        else:
            b = mid
    return a


# --------------------------------------------------------------------------
# Ядро решения: ОБЩИЙ вес натяжения на оба канала
# --------------------------------------------------------------------------
def compute_weights(ch_ys, tension, lo, hi, n, max_sweeps=8, shared=True):
    """Возвращает списки весов w[канал][узел] в [0,1].

    Проход 1: по каждому интервалу i..i+1 берём min по каналам от max_scale.
    Проход 2: вес узла w[i] = min(k[i-1], k[i]) — сохраняет C1, потому что
              оба соседних сегмента умножают ОДНУ и ту же касательную.
    Проход 3: verifying-свип — w[i], w[j] *= need, пока всё не сойдётся.
              На 5000 стресс-кривых сходился за <= 1 дополнительный проход.
    """
    if shared:
        k = [1.0] * n
        for i in range(n):
            j = (i + 1) % n
            kk = 1.0
            for y in ch_ys:
                M = nominal_tangents(y, tension)
                kk = min(kk, max_scale(y[i], y[j], M[i], M[j], lo, hi))
            k[i] = kk
        w = [[min(k[(i - 1) % n], k[i]) for i in range(n)] for _ in ch_ys]
    else:
        w = []
        for y in ch_ys:
            M = nominal_tangents(y, tension)
            k = [max_scale(y[i], y[(i + 1) % n], M[i], M[(i + 1) % n], lo, hi)
                 for i in range(n)]
            w.append([min(k[(i - 1) % n], k[i]) for i in range(n)])

    sweeps = 0
    for sweep in range(max_sweeps):
        bad = False
        for i in range(n):
            j = (i + 1) % n
            need = 1.0
            for ci, y in enumerate(ch_ys):
                M = nominal_tangents(y, tension)
                m1, m2 = w[ci][i] * M[i], w[ci][j] * M[j]
                if not feasible(y[i], y[j], m1, m2, lo, hi):
                    need = min(need, max_scale(y[i], y[j], m1, m2, lo, hi))
            if need < 1.0:
                for ci in range(len(ch_ys)):
                    w[ci][i] *= need
                    w[ci][j] *= need
                bad = True
        if not bad:
            break
        sweeps = sweep + 1

    residual = any(
        not feasible(y[i], y[(i + 1) % n],
                     w[ci][i] * nominal_tangents(y, tension)[i],
                     w[ci][(i + 1) % n] * nominal_tangents(y, tension)[(i + 1) % n],
                     lo, hi)
        for ci, y in enumerate(ch_ys) for i in range(n))
    return w, sweeps, residual


# --------------------------------------------------------------------------
# Утилиты пресетов
# --------------------------------------------------------------------------
def valid_beat(carrier, beat, lo, hi):
    """Повторяет FrequencyMath.clampBeat / beatBounds."""
    cap = 2.0 * min(carrier - lo, hi - carrier)
    cap = max(0.0, min(cap, 2.0 * (carrier - MIN_TONE), 2.0 * (MAX_TONE - carrier)))
    return math.copysign(min(abs(beat), cap), beat)


def sample_channel(y, T, per=400):
    n = len(y)
    return [hermite(y[i], y[(i + 1) % n], T[i], T[(i + 1) % n], j / per)
            for i in range(n) for j in range(per + 1)]


def analyse(name, pts, tension, lo, hi, shared=True, verbose=True):
    pts = sorted(pts, key=lambda p: p[0])
    n = len(pts)
    left = [c - b / 2.0 for (_, c, b) in pts]
    right = [c + b / 2.0 for (_, c, b) in pts]
    lo_b, hi_b = max(lo, MIN_TONE), min(hi, MAX_TONE)

    Tl, Tr = nominal_tangents(left, tension), nominal_tangents(right, tension)
    raw_l, raw_r = sample_channel(left, Tl), sample_channel(right, Tr)
    raw_beat = [r - l for l, r in zip(raw_l, raw_r)]

    w, sweeps, residual = compute_weights([left, right], tension, lo_b, hi_b, n,
                                          shared=shared)
    wl, wr = w[0], w[1]
    new_l = sample_channel(left, [wl[i] * Tl[i] for i in range(n)])
    new_r = sample_channel(right, [wr[i] * Tr[i] for i in range(n)])
    new_beat = [r - l for l, r in zip(new_l, new_r)]

    # Нарушением считается выход ЗА пределы допуска TOL: сэмплы внутри
    # допуска — это и есть «касание границы», а не заскок.
    viol = lambda vals: sum(1 for v in vals
                            if v < lo_b - TOL - 1e-9 or v > hi_b + TOL + 1e-9)
    if verbose:
        print(f"\n=== {name}  tension={tension} bounds=[{lo_b:g},{hi_b:g}] "
              f"shared={shared}")
        print(f"  БЫЛО  L[{min(raw_l):8.2f},{max(raw_l):8.2f}] "
              f"R[{min(raw_r):8.2f},{max(raw_r):8.2f}] "
              f"beat[{min(raw_beat):7.2f},{max(raw_beat):7.2f}]  "
              f"выходов>допуска={viol(raw_l) + viol(raw_r)}")
        print(f"  СТАЛО L[{min(new_l):8.2f},{max(new_l):8.2f}] "
              f"R[{min(new_r):8.2f},{max(new_r):8.2f}] "
              f"beat[{min(new_beat):7.2f},{max(new_beat):7.2f}]  "
              f"выходов>допуска={viol(new_l) + viol(new_r)}")
        print(f"  w_L={[round(x, 4) for x in wl]}")
        print(f"  w_R={[round(x, 4) for x in wr]}  sweeps={sweeps} "
              f"residual={residual}")
    return dict(sweeps=sweeps, residual=residual, over=viol(new_l) + viol(new_r),
                beat_min=min(new_beat), beat_max=max(new_beat),
                raw_beat_min=min(raw_beat), raw_beat_max=max(raw_beat), wl=wl)


# --------------------------------------------------------------------------
# Раздел 1. Пресеты
# --------------------------------------------------------------------------
def section_presets():
    print("#" * 72)
    print("# 1. ПОКАЗАТЕЛЬНЫЕ ПРЕСЕТЫ (carrierRange = [100, 600] Гц)")
    print("#" * 72)
    LO, HI = 100.0, 600.0
    cases = [
        ("1. Пик у верхней границы",
         [(0, 174, 3), (10800, 210, 6), (21600, 220, 8), (32400, 560, 20),
          (43200, 575, 25), (54000, 560, 18), (64800, 250, 12),
          (75600, 240, 10)]),
        ("2. Провал у нижней границы",
         [(0, 500, 4), (21600, 105, 6), (43200, 110, 8), (64800, 520, 10)]),
        ("3. Зигзаг (узел ровно на границе)",
         [(0, 560, 30), (14400, 120, 30), (28800, 570, 30), (43200, 115, 30),
          (57600, 565, 30), (72000, 125, 30)]),
        ("4. Резкие броски к обеим границам",
         [(0, 590, 18), (21600, 101, 4), (43200, 595, 8), (64800, 300, 14)]),
        ("5. Отрицательные биения, заскок вверх",
         [(0, 138.2, 5.27), (21600, 179.1, 27.5), (43200, 582.6, -20.94),
          (64800, 557.6, -36.99)]),
    ]
    for name, raw in cases:
        pts = [(t, c, valid_beat(c, b, LO, HI)) for (t, c, b) in raw]
        analyse(name, pts, 0.0, LO, HI, shared=True)

    print("\n--- Почему нужен ОБЩИЙ вес: preset 2, независимая регуляция ---")
    pts = [(t, c, valid_beat(c, b, LO, HI)) for (t, c, b) in
           [(0, 500, 4), (21600, 105, 6), (43200, 110, 8), (64800, 520, 10)]]
    a = analyse("2a. общий вес", pts, 0.0, LO, HI, shared=True, verbose=False)
    b = analyse("2b. независимые веса", pts, 0.0, LO, HI, shared=False, verbose=False)
    print(f"  общий вес       : beat [{a['beat_min']:.2f}, {a['beat_max']:.2f}] "
          f"w={[round(x, 4) for x in a['wl']]}")
    print(f"  независимо      : beat [{b['beat_min']:.2f}, {b['beat_max']:.2f}] "
          f"w_L={[round(x, 4) for x in b['wl']]}")
    print("  -> независимо: биения проходят через ноль (каналы меняются местами)")


# --------------------------------------------------------------------------
# Раздел 2. Стресс
# --------------------------------------------------------------------------
def section_stress(trials=5000):
    print("\n" + "#" * 72)
    print(f"# 2. СТРЕСС ({trials} случайных кривых, общий вес)")
    print("#" * 72)
    random.seed(11)
    worst_sweeps = residuals = overs = beat_loss = needed = 0
    worst_ratio = (1.0, None)
    for _ in range(trials):
        n = random.randint(2, 14)
        lo = random.choice([20.0, 60.0, 100.0, 200.0])
        hi = lo + random.choice([150.0, 500.0, 1500.0])
        pts = []
        for i in range(n):
            t = i * (86400 // n)
            c = random.uniform(lo, hi)
            pts.append((t, c, valid_beat(c, random.uniform(-60, 60), lo, hi)))
        tension = random.choice([0.0, 0.0, 0.25, 0.5])
        r = analyse("", pts, tension, lo, hi, verbose=False)
        worst_sweeps = max(worst_sweeps, r["sweeps"])
        residuals += 1 if r["residual"] else 0
        overs += r["over"]
        if min(r["wl"]) < 0.999:
            needed += 1
        mag_raw = max(abs(r["raw_beat_min"]), abs(r["raw_beat_max"]))
        mag_new = max(abs(r["beat_min"]), abs(r["beat_max"]))
        if mag_raw > 1e-6:
            ratio = mag_new / mag_raw
            if ratio < worst_ratio[0]:
                worst_ratio = (ratio, (pts, tension, lo, hi))
        if mag_new < mag_raw * 0.5:
            beat_loss += 1
    print(f"  кривых, где коррекция потребовалась : {needed} / {trials}")
    print(f"  остаточных нарушений границ         : {residuals}")
    print(f"  сэмплов ВНЕ границ + допуск {TOL} Гц  : {overs}")
    print(f"  макс. число проходов verifying-свипа: {worst_sweeps}")
    print(f"  кривых с потерей >50% модуля биений : {beat_loss}")
    print(f"  худшее |beat|_new / |beat|_raw      : {worst_ratio[0]:.4f}")


# --------------------------------------------------------------------------
# Раздел 3. Верификация инвариантов
# --------------------------------------------------------------------------
def section_verify(trials=4000):
    print("\n" + "#" * 72)
    print(f"# 3. ВЕРИФИКАЦИЯ ИНВАРИАНТОВ ({trials} кривых, общий вес)")
    print("#" * 72)
    random.seed(1234)
    max_violation = max_c1_break = max_beat_dev = 0.0
    tot = 0
    for _ in range(trials):
        n = random.randint(2, 14)
        lo = random.choice([20.0, 100.0])
        hi = lo + random.choice([150.0, 500.0, 1500.0])
        pts = []
        for i in range(n):
            t = i * (86400 // n)
            c = random.uniform(lo, hi)
            pts.append((t, c, valid_beat(c, random.uniform(-60, 60), lo, hi)))
        tension = random.choice([0.0, 0.3])
        L = [c - b / 2 for (_, c, b) in pts]
        R = [c + b / 2 for (_, c, b) in pts]
        B = [b for (_, _, b) in pts]
        lo_b, hi_b = max(lo, MIN_TONE), min(hi, MAX_TONE)
        # при shared=True оба канала получают один и тот же список весов
        w = compute_weights([L, R], tension, lo_b, hi_b, n)[0][0]
        ML, MR, MB = (nominal_tangents(L, tension), nominal_tangents(R, tension),
                      nominal_tangents(B, tension))
        per = 120
        for i in range(n):
            j = (i + 1) % n
            for s in range(per + 1):
                t = s / per
                lv = hermite(L[i], L[j], w[i] * ML[i], w[j] * ML[j], t)
                rv = hermite(R[i], R[j], w[i] * MR[i], w[j] * MR[j], t)
                for v in (lv, rv):
                    max_violation = max(max_violation, lo_b - v, v - hi_b)
                # ИНВАРИАНТ: rv - lv == сплайн узлов биений с ТЕМИ ЖЕ весами
                bv = hermite(B[i], B[j], w[i] * MB[i], w[j] * MB[j], t)
                max_beat_dev = max(max_beat_dev, abs((rv - lv) - bv))
                tot += 1
        for i in range(n):                       # C1 в каждом узле
            p, j = (i - 1) % n, (i + 1) % n
            for y, M in ((L, ML), (R, MR)):
                dl = d_hermite(y[p], y[i], w[p] * M[p], w[i] * M[i], 1.0)
                dr = d_hermite(y[i], y[j], w[i] * M[i], w[j] * M[j], 0.0)
                max_c1_break = max(max_c1_break, abs(dl - dr))
    print(f"  сэмплов проверено                        : {tot}")
    print(f"  макс. выход за границу (Гц)              : {max_violation:.6f} "
          f"  (допуск {TOL})")
    print(f"  макс. разрыв C1 (Гц/интервал)            : {max_c1_break:.9f}")
    print(f"  макс. |(R-L) - spline(beat)| (Гц)        : {max_beat_dev:.9f}")
    ok = (max_violation <= TOL + 1e-9 and max_c1_break < 1e-9
          and max_beat_dev < 1e-9)
    print(f"\n  ИТОГ: {'ВСЕ ИНВАРИАНТЫ ВЫПОЛНЕНЫ' if ok else 'НАРУШЕНИЕ'}")


if __name__ == "__main__":
    section_presets()
    section_stress()
    section_verify()
