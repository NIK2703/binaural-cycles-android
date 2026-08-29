#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ГЛУБОКИЙ анализ следования частоты графику в фейдах смены каналов
(TIMER-сетка и TREND-экстремумы) по сравнению с обычным временем без фейдов.

Надстройка над точным портом C++-пайплайна (fade_tracking_analysis.py).

Блоки:
  E1  базовая сводка: SOLID vs FADE_OUT/FADE_IN (текущий код / до фикса 4384bd1 /
      длинный фейд). Эталон — точный сплайн по контрольным точкам (то, что видно
      на графике в UI), БЕЗ lookup-таблицы.
  E2  разложение ошибки на источники:
        stretch — растяжение хорды на 1 сэмпл (деление на samples-1);
        таблица — квантование графика lookup-таблицей 100 мс;
        |Δнес|  — суммарно против точного сплайна.
      Показывает, ОДИНАКОВЫ ли источники в SOLID и в фейдах.
  E3  смещение самого события перестановки каналов относительно узла TIMER-сетки
      или экстремума T*, и «проскальзывание» графика за время фейда.
  E4  фаза, накопленная за PAUSE: одна хорда (текущий код) против кусочков 100 мс.
  E5  интерполяция STEP: ступенька внутри фейда «размазывается» в глиссандо;
      проверка предлагаемого фикса (резка фейда по границам ступеней).
  E6  пропуск экстремума, если два экстремума ближе, чем F + P/2.
  E7  остаточная амплитуда в конце FADE_OUT (уровень щелчка в момент свапа).
"""
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fade_tracking_analysis as fta  # noqa: E402
from fade_tracking_analysis import (  # noqa: E402
    Curve, Cfg, plan_package, _step_bounds,
    DAY, MONOTONE, STEP,
    TIMER, TREND, BOTH,
    SOLID, FADE_OUT, PAUSE, FADE_IN, PHASE_NAME, PROBES,
)

SR = 48000
PKG_MS = 200


# ============================================================================
# Кривые
# ============================================================================
def circadian():
    """Пресет «циркадный ритм», MONOTONE — типовой профиль."""
    pts = [(0, 174.0, 3.0), (10800, 210.0, 6.0), (21600, 220.0, 8.0),
           (32400, 440.0, 20.0), (43200, 440.0, 25.0), (54000, 440.0, 18.0),
           (64800, 250.0, 12.0), (75600, 240.0, 10.0)]
    return pts


def relaxation_zigzag(base_pts, minutes=5, reduction=0.30):
    """
    Эмуляция режима расслабления SMOOTH: виртуальные точки каждые `minutes`
    минут, чередование «на графике» / «снижено на reduction». Интерполяция
    MONOTONE — та же, что уходит в нативный код. Даёт кривую с наибольшей
    реалистичной кривизной (зигзаг), на которой хорда видна лучше всего.
    """
    base = Curve(base_pts, MONOTONE, 0.0, build_trend=False)
    step = minutes * 60
    pts = []
    for i, t in enumerate(range(0, DAY, step)):
        lo, up = base.ideal(np.array([float(t)]))
        carrier = 0.5 * (float(lo[0]) + float(up[0]))
        beat = float(up[0]) - float(lo[0])
        if i % 2:
            carrier *= (1.0 - reduction)
            beat *= (1.0 - reduction)
        pts.append((t, carrier, beat))
    return pts


# ============================================================================
# E1/E2: детальная симуляция с разложением ошибки
# ============================================================================
def _new_rec():
    return {p: {"n": 0, "c_sum": 0.0, "c_max": 0.0, "b_sum": 0.0, "b_max": 0.0,
                "st_sum": 0.0, "st_max": 0.0, "tb_sum": 0.0, "tb_max": 0.0,
                "bw_sum": 0.0, "env_sum": 0.0}
            for p in (SOLID, FADE_OUT, PAUSE, FADE_IN)}


def _weights(n):
    if n == 1:
        return np.array([1.0])
    w = np.full(n, 1.0 / (n - 1))
    w[0] = w[-1] = 0.5 / (n - 1)
    return w


def _piece_samples(sr):
    return (100 * sr + 500) // 1000


def _chord_freq(curve, t0, samples, spp, I, fix_stretch):
    """Одна хорда на весь сегмент: lookup(t0) -> lookup(t0 + N*dt)."""
    n_end = (samples - 1) if fix_stretch else samples
    l0, u0 = curve.lookup(np.array([t0 % DAY]))
    l1, u1 = curve.lookup(np.array([(t0 + n_end * spp) % DAY]))
    u = I / max(samples - 1, 1)
    return l0[0] + (l1[0] - l0[0]) * u, u0[0] + (u1[0] - u0[0]) * u


def _solid_freq(curve, t0, samples, spp, I, fix_stretch, step_hold=False):
    if curve.itype == STEP and curve.n > 1:
        lo_a = np.empty(len(I))
        up_a = np.empty(len(I))
        bounds = _step_bounds(curve, t0, spp, samples)
        for a, b in zip([0] + bounds, bounds + [samples]):
            if b <= a:
                continue
            tt = (t0 + a * spp) % DAY
            if step_hold:
                # Правка на уровне lookup: для STEP брать ЗНАЧЕНИЕ СТУПЕНИ
                # (удержание левой контрольной точки), а не линейную
                # интерполяцию между ячейками 100 мс. См. getChannelFrequenciesAt.
                flo, fup = curve.ideal(np.array([tt]))
            else:
                flo, fup = curve.lookup(np.array([tt]))
            m = (I >= a - 1e-9) & (I <= b - 1 + 1e-9)
            lo_a[m] = flo[0]
            up_a[m] = fup[0]
        return lo_a, up_a
    return _chord_freq(curve, t0, samples, spp, I, fix_stretch)


def _fade_freq(curve, t0, samples, spp, sr, I, fix_stretch, step_cut_fade,
               step_hold=False):
    """Фейд, разрезанный на кусочки <=100 мс (текущий код после фикса 4384bd1)."""
    piece = _piece_samples(sr)
    lo_a = np.zeros(len(I))
    up_a = np.zeros(len(I))
    step_mode = step_cut_fade and curve.itype == STEP and curve.n > 1
    gen = 0
    while gen < samples:
        ps = min(piece, samples - gen)
        ta = t0 + gen * spp
        if step_mode:
            cuts = [0] + _step_bounds(curve, ta, spp, ps) + [ps]
        else:
            cuts = [0, ps]
            tend = t0 + (gen + ps - 1 if fix_stretch else gen + ps) * spp
            fl1, fu1 = curve.lookup(np.array([tend % DAY]))
        for a, b in zip(cuts[:-1], cuts[1:]):
            if b <= a:
                continue
            if step_hold and curve.itype == STEP:
                # Правка на уровне lookup: для STEP брать ЗНАЧЕНИЕ СТУПЕНИ
                # (удержание), а не линейную интерполяцию между ячейками 100 мс.
                flo, fup = curve.ideal(np.array([(ta + a * spp) % DAY]))
            else:
                flo, fup = curve.lookup(np.array([(ta + a * spp) % DAY]))
            # Кусочек покрывает целые сэмплы [gen+a, gen+b-1]. Сетка проб I —
            # дробная, поэтому проба относится к ближайшему сэмплу: границы
            # сдвинуты на полсэмпла, иначе дробные пробы проваливаются в щель
            # между кусочками и остаются нулями.
            m = (I >= gen + a - 0.5) & (I < gen + b - 0.5)
            if step_mode:
                lo_a[m] = flo[0]
                up_a[m] = fup[0]
            else:
                uu = np.clip((I[m] - gen - a) / max(b - 1 - a, 1), 0.0, 1.0)
                lo_a[m] = flo[0] + (fl1[0] - flo[0]) * uu
                up_a[m] = fup[0] + (fu1[0] - fup[0]) * uu
        gen += ps
    return lo_a, up_a


def _fade_env(foff_ms, ftot_ms, sr, samples, I, stype):
    piece = _piece_samples(sr)
    foff = (foff_ms * sr + 500) // 1000
    ftot = max((ftot_ms * sr + 500) // 1000, 1)
    env = np.zeros(len(I))
    gen = 0
    while gen < samples:
        ps = min(piece, samples - gen)
        m = (I >= gen - 0.5) & (I < gen + ps - 0.5)   # см. _fade_freq: полсэмпла
        prog = np.clip((foff + I[m] - gen) / ftot, 0.0, 1.0)
        cs = 0.5 * (1.0 - np.cos(prog * math.pi))
        env[m] = (1.0 - cs) if stype == FADE_OUT else cs
        gen += ps
    return env


def simulate_detail(cfg, sr, start_t, total_seconds, package_ms=PKG_MS,
                    time_scale=1.0, legacy_fade=False, fix_stretch=False,
                    step_cut_fade=False, step_hold=False, windows=None):
    """
    legacy_fade=True    — поведение ДО фикса 4384bd1: одна хорда на весь фейд.
    fix_stretch=True    — правка: конечная частота берётся в момент последнего
                          сэмпла (t0+(N-1)dt), а не начала следующего сегмента.
    step_cut_fade=True  — правка: фейд режется по границам STEP-ступеней.
    step_hold=True      — правка уровня lookup: для STEP значение ступени
                          удерживается (не интерполируется между ячейками).
    windows=[(t0,t1)]   — копить ошибку только внутри этих интервалов
                          АБСОЛЮТНОГО времени (отсчёт от start_t). Нужно для
                          честного сравнения: «что было бы в эти же мгновения,
                          если бы смены каналов не было».
    """
    curve = cfg.curve
    state = {"phase": SOLID, "phase_remaining": 0}
    curve_pos = start_t % DAY
    spp = time_scale / sr
    rec = _new_rec()

    t_cur = float(start_t % DAY)
    t_abs = 0.0
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

            if stype == PAUSE:
                r["n"] += samples
                produced += samples
                t_cur = (t_cur + (samples / sr) * time_scale) % DAY
                t_abs += (samples / sr) * time_scale
                continue

            npr = min(PROBES, max(samples, 2))
            I = np.linspace(0.0, samples - 1.0, npr)
            w = _weights(npr)
            t_probe = (t_cur + I * spp) % DAY

            # Маска «какие пробы попадают в окна сравнения».
            if windows is None:
                sel = np.ones(npr, dtype=bool)
                wsel = w
                wsum = 1.0
            else:
                tp = t_abs + I * spp
                sel = np.zeros(npr, dtype=bool)
                for (a, b) in windows:
                    sel |= (tp >= a) & (tp <= b)
                wsel = w * sel
                wsum = float(wsel.sum())
                if wsum <= 0.0:
                    produced += samples
                    t_cur = (t_cur + (samples / sr) * time_scale) % DAY
                    t_abs += (samples / sr) * time_scale
                    continue

            if stype == SOLID:
                lo_a, up_a = _solid_freq(curve, t_cur, samples, spp, I,
                                        fix_stretch, step_hold)
                env = np.ones(npr)
            else:
                if legacy_fade:
                    lo_a, up_a = _chord_freq(curve, t_cur, samples, spp, I,
                                             fix_stretch)
                else:
                    lo_a, up_a = _fade_freq(curve, t_cur, samples, spp, sr, I,
                                            fix_stretch, step_cut_fade,
                                            step_hold)
                if ftot_ms == 0:
                    ftot_ms, foff_ms = dur_ms, 0
                env = _fade_env(foff_ms, ftot_ms, sr, samples, I, stype)

            lk_lo, lk_up = curve.lookup(t_probe)    # график глазами lookup-таблицы
            id_lo, id_up = curve.ideal(t_probe)     # точный сплайн (эталон UI)

            dc = np.abs((lo_a + up_a) / 2.0 - (id_lo + id_up) / 2.0)
            db = np.abs((up_a - lo_a) - (id_up - id_lo))
            dst = np.abs((lo_a + up_a) / 2.0 - (lk_lo + lk_up) / 2.0)
            dtb = np.abs((lk_lo + lk_up) / 2.0 - (id_lo + id_up) / 2.0)

            r["c_sum"] += float(np.dot(dc, wsel)) * samples
            r["c_max"] = max(r["c_max"], float(dc[sel].max()))
            r["b_sum"] += float(np.dot(db, wsel)) * samples
            r["b_max"] = max(r["b_max"], float(db[sel].max()))
            r["st_sum"] += float(np.dot(dst, wsel)) * samples
            r["st_max"] = max(r["st_max"], float(dst[sel].max()))
            r["tb_sum"] += float(np.dot(dtb, wsel)) * samples
            r["tb_max"] = max(r["tb_max"], float(dtb[sel].max()))
            r["bw_sum"] += float(np.dot(db * env, wsel)) * samples
            r["env_sum"] += float(np.dot(env, wsel)) * samples
            r["n"] += int(round(samples * wsum))

            produced += samples
            t_cur = (t_cur + (samples / sr) * time_scale) % DAY
            t_abs += (samples / sr) * time_scale
    return rec


def report_detail(title, rec, sr):
    print(f"\n### {title}")
    print(f"{'фаза':<9}{'доля':>7}{'время,с':>8}"
          f"{'|Δнес|ср':>11}{'|Δнес|макс':>11}"
          f"{'stretch ср':>12}{'stretch макс':>13}"
          f"{'таблица ср':>12}{'таблица макс':>13}"
          f"{'|Δбиен|макс':>12}")
    tot = sum(v["n"] for v in rec.values()) or 1
    for ph in (SOLID, FADE_OUT, PAUSE, FADE_IN):
        v = rec[ph]
        if v["n"] == 0:
            continue
        if ph == PAUSE:
            print(f"{PHASE_NAME[ph]:<9}{v['n']/tot:>6.1%}{v['n']/sr:>8.1f}"
                  f"{'':>61}   тишина (фазы идут)")
            continue
        n = float(v["n"])
        print(f"{PHASE_NAME[ph]:<9}{v['n']/tot:>6.1%}{v['n']/sr:>8.1f}"
              f"{v['c_sum']/n:>11.3e}{v['c_max']:>11.3e}"
              f"{v['st_sum']/n:>12.3e}{v['st_max']:>13.3e}"
              f"{v['tb_sum']/n:>12.3e}{v['tb_max']:>13.3e}"
              f"{v['b_max']:>12.3e}")


# ============================================================================
# E2c: честное сравнение — фейд против SOLID В ТЕ ЖЕ САМЫЕ МГНОВЕНИЯ
# ============================================================================
def fade_windows(cfg, start_t, total_seconds, package_ms=PKG_MS, sr=SR,
                 time_scale=1.0):
    """Интервалы абсолютного времени (от start_t), занятые FADE_OUT/FADE_IN."""
    segs = segment_stream(cfg, start_t, total_seconds, package_ms, sr, time_scale)
    t = 0.0
    wins = []
    run = None
    for s in segs:
        t0, t1 = t, t + (s[1] / sr) * time_scale
        t = t1
        if s[0] in (FADE_OUT, FADE_IN):
            if run is None:
                run = [t0, t1]
            else:
                run[1] = t1
        elif run is not None:
            wins.append((run[0], run[1]))
            run = None
    if run is not None:
        wins.append((run[0], run[1]))
    return wins


def e2c_same_instants(label, curve, cfg_on, start_t, total_seconds, sr=SR,
                      kw_on=None, kw_solid=None):
    """
    Единственно корректный ответ на вопрос «следуют ли фейды графику так же
    точно, как обычное время»: сравниваем фейд с SOLID, измеренным ровно в те
    же мгновения (те же участки кривой), а не с SOLID «в среднем за окно».
    """
    kw_on = kw_on or {}
    kw_solid = kw_solid or {}
    wins = fade_windows(cfg_on, start_t, total_seconds, sr=sr)
    if not wins:
        print(f"\n### E2c. {label}\n    фейдов в окне нет")
        return
    dur = sum(b - a for a, b in wins)
    rec_f = simulate_detail(cfg_on, sr, start_t, total_seconds, **kw_on)
    rec_s = simulate_detail(Cfg(curve, False), sr, start_t, total_seconds,
                            windows=wins, **kw_solid)

    print(f"\n### E2c. {label}")
    print(f"    фейд-окна: {len(wins)} шт., суммарно {dur*1000:.0f} мс "
          f"({dur/total_seconds:.2%} окна)")
    print(f"{'величина':<22}{'фейд':>14}{'SOLID те же мгновения':>24}"
          f"{'фейд/SOLID':>13}")
    for ph in (FADE_OUT, FADE_IN):
        f, s = rec_f[ph], rec_s[SOLID]
        if f["n"] == 0 or s["n"] == 0:
            continue
        print(f"  -- {PHASE_NAME[ph]} ({f['n']/sr*1000:.0f} мс) --")
        rows = [("|Δнес| сред, Гц", f["c_sum"] / f["n"], s["c_sum"] / s["n"]),
                ("|Δнес| макс, Гц", f["c_max"], s["c_max"]),
                ("stretch сред, Гц", f["st_sum"] / f["n"], s["st_sum"] / s["n"]),
                ("таблица сред, Гц", f["tb_sum"] / f["n"], s["tb_sum"] / s["n"]),
                ("|Δбиен| макс, Гц", f["b_max"], s["b_max"])]
        for name, a, b in rows:
            ratio = (a / b) if b > 0 else float("nan")
            print(f"{name:<22}{a:>14.4e}{b:>24.4e}{ratio:>13.3f}")


# ============================================================================
# E3: смещение события перестановки каналов
# ============================================================================
def segment_stream(cfg, start_t, total_seconds, package_ms, sr, time_scale=1.0):
    state = {"phase": SOLID, "phase_remaining": 0}
    curve_pos = start_t % DAY
    out = []
    produced = 0
    target = int(total_seconds * sr)
    while produced < target:
        segs, curve_pos = plan_package(cfg, state, package_ms, curve_pos, time_scale)
        for (stype, dur, foff, ftot) in segs:
            samples = (dur * sr) // 1000
            if samples <= 0:
                continue
            samples = min(samples, target - produced)
            if samples <= 0:
                break
            out.append([stype, samples, foff, ftot])
            produced += samples
    return out


def swap_events(cfg, start_t, total_seconds, package_ms=PKG_MS, sr=SR,
                time_scale=1.0):
    """Моменты фактической перестановки каналов (конец FADE_OUT)."""
    segs = segment_stream(cfg, start_t, total_seconds, package_ms, sr, time_scale)
    t = float(start_t % DAY)
    for s in segs:
        s.append(t)                      # s[4] — время старта сегмента
        t = (t + (s[1] / sr) * time_scale) % DAY
    ev = []
    for i, s in enumerate(segs):
        if s[0] != FADE_OUT:
            continue
        nxt = segs[i + 1][0] if i + 1 < len(segs) else None
        if nxt == FADE_OUT:              # фейд разрезан границей пакета
            continue
        t_swap = (s[4] + (s[1] / sr) * time_scale) % DAY
        j = i - 1
        while j >= 0 and segs[j][0] != SOLID:
            j -= 1
        t_solid_end = ((segs[j][4] + (segs[j][1] / sr) * time_scale) % DAY
                       if j >= 0 else None)
        ev.append((t_swap, t_solid_end))
    return ev, cfg.phase_duration(FADE_OUT)


def e3_alignment(curve, label, cfg, start_t, total_seconds):
    ev, fade_ms = swap_events(cfg, start_t, total_seconds)
    print(f"\n### E3. {label}")
    print(f"    фейд={fade_ms} мс, пауза={cfg.pause_ms} мс, "
          f"событий в окне: {len(ev)}")
    if not ev:
        print("    событий нет")
        return
    print(f"{'№':>3}{'цель, с':>13}{'свап, с':>13}{'смещение, с':>13}"
          f"{'Δнес, Гц':>12}{'Δбиен, Гц':>12}")
    for k, (t_swap, t_solid_end) in enumerate(ev[:5]):
        if cfg.mode == TIMER:
            t_target = t_solid_end                       # узел сетки
        else:
            t_target = (t_solid_end +
                        (fade_ms + cfg.pause_ms / 2.0) / 1000.0) % DAY
        off = t_swap - t_target
        if off > DAY / 2:
            off -= DAY
        lo1, up1 = curve.lookup(np.array([t_target]))
        lo2, up2 = curve.lookup(np.array([t_swap]))
        c1 = 0.5 * (float(lo1[0]) + float(up1[0]))
        c2 = 0.5 * (float(lo2[0]) + float(up2[0]))
        b1 = float(up1[0]) - float(lo1[0])
        b2 = float(up2[0]) - float(lo2[0])
        print(f"{k+1:>3}{t_target:>13.3f}{t_swap:>13.3f}{off:>+13.3f}"
              f"{c2-c1:>+12.4f}{b2-b1:>+12.4f}")


# ============================================================================
# E4: фаза, накопленная за PAUSE
# ============================================================================
def e4_pause_phase(curve, sr, t0, pause_ms_list):
    print("\n### E4. Фаза, накопленная за PAUSE: одна хорда (текущий код) "
          "vs кусочки 100 мс")
    print("    (расхождение с точным интегралом lookup-кривой, в циклах)")
    print(f"{'пауза, мс':>10}{'одна хорда L':>15}{'одна хорда R':>15}"
          f"{'кусочки 100мс L':>17}{'кусочки 100мс R':>17}")
    spp = 1.0 / sr
    for pms in pause_ms_list:
        n = int(pms * sr / 1000)
        if n <= 0:
            print(f"{pms:>10}{0.0:>15.3e}{0.0:>15.3e}{0.0:>17.3e}{0.0:>17.3e}")
            continue
        t = (np.arange(n) * spp + t0) % DAY
        ex_lo, ex_up = curve.lookup(t)
        ph_l = float(ex_lo.sum()) / sr
        ph_r = float(ex_up.sum()) / sr

        l0, u0 = curve.lookup(np.array([t0]))
        l1, u1 = curve.lookup(np.array([(t0 + n * spp) % DAY]))
        u = np.arange(n) / max(n - 1, 1)
        one_l = float((l0[0] + (l1[0] - l0[0]) * u).sum()) / sr
        one_r = float((u0[0] + (u1[0] - u0[0]) * u).sum()) / sr

        piece = 100 * sr // 1000
        ch_l = ch_r = 0.0
        gen = 0
        while gen < n:
            ps = min(piece, n - gen)
            ta, tb = t0 + gen * spp, t0 + (gen + ps) * spp
            a0, b0 = curve.lookup(np.array([ta]))
            a1, b1 = curve.lookup(np.array([tb]))
            uu = np.arange(ps) / max(ps - 1, 1)
            ch_l += float((a0[0] + (a1[0] - a0[0]) * uu).sum())
            ch_r += float((b0[0] + (b1[0] - b0[0]) * uu).sum())
            gen += ps
        ch_l /= sr
        ch_r /= sr
        print(f"{pms:>10}{one_l-ph_l:>15.3e}{one_r-ph_r:>15.3e}"
              f"{ch_l-ph_l:>17.3e}{ch_r-ph_r:>17.3e}")


def e5b_step_smear(curve, t_step, sr=SR):
    """
    Проверка: сама lookup-таблица линейно интерполирует между ячейками 100 мс,
    поэтому при STEP-интерполяции ступенька ВСЕГДА размазывается на ~100 мс —
    и в SOLID, и в фейде. Здесь это видно напрямую, без генератора.
    """
    print("\n### E5b. STEP: что даёт сама lookup-таблица вокруг ступеньки "
          f"t={t_step:.1f} с (без участия генератора)")
    print(f"{'t - t_ступ, мс':>15}{'lookup, Гц':>13}{'эталон(сплайн), Гц':>20}"
          f"{'|lookup-эталон|, Гц':>21}")
    for dms in (-150, -100, -75, -50, -25, -5, -0.001, 0.0, 5, 25, 50, 100, 150):
        t = t_step + dms / 1000.0
        lo, up = curve.lookup(np.array([t % DAY]))
        ilo, iup = curve.ideal(np.array([t % DAY]))
        c = 0.5 * (float(lo[0]) + float(up[0]))
        ic = 0.5 * (float(ilo[0]) + float(iup[0]))
        print(f"{dms:>15.3f}{c:>13.3f}{ic:>20.3f}{abs(c-ic):>21.3f}")


# ============================================================================
# E6: пропуск экстремума
# ============================================================================
class _Stub:
    def __init__(self, crossings):
        self.trend_crossings = crossings


def e6_crossing_skip():
    print("\n### E6. Пропуск экстремума: два экстремума ближе, чем F + P/2")
    print("    (SOLID после обслуживания T1; «ожид.» — если бы T2 обслужился)")
    print(f"{'F,мс':>7}{'P,мс':>7}{'зазор T2-T1,с':>15}"
          f"{'SOLID факт,с':>15}{'SOLID ожид.,с':>16}{'':>14}")
    for fade_ms, pause_ms in ((1000, 0), (2000, 0), (1000, 4000), (5000, 2000)):
        for gap in (0.5, 2.0, 10.0):
            T1 = 36000.0
            stub = _Stub([(T1, True), (T1 + gap, False), (T1 + 40000.0, True)])
            lead = (fade_ms + pause_ms / 2.0) / 1000.0
            pos = T1 + lead
            d = fta.trend_solid_duration_ms(stub, pos, 1.0, fade_ms, BOTH,
                                            pause_ms // 2)
            want = max(0.0, (gap - lead) * 1000.0 - fade_ms - pause_ms / 2.0)
            flag = "  <-- ПРОПУСК (T2 проигнорирован)" if d > want + 1000 else ""
            print(f"{fade_ms:>7}{pause_ms:>7}{gap:>15.1f}"
                  f"{d/1000.0:>15.1f}{want/1000.0:>16.1f}{flag}")


# ============================================================================
# E7: остаточная амплитуда в конце FADE_OUT
# ============================================================================
def e7_residual():
    print("\n### E7. Остаточная амплитуда на последнем сэмпле FADE_OUT "
          "(уровень щелчка в момент свапа)")
    srs = (8000, 44100, 48000, 96000)
    print(f"{'фейд, мс':>10}" + "".join(f"{('sr=%d' % sr):>14}" for sr in srs))
    for fms in (15, 50, 100, 250, 500, 1000, 2000, 5000, 10000):
        row = f"{fms:>10}"
        for sr in srs:
            tot = (fms * sr + 500) // 1000
            n = (fms * sr) // 1000
            if n <= 0 or tot <= 0:
                row += f"{'—':>14}"
                continue
            prog = (n - 1) / tot
            resid = 1.0 - 0.5 * (1.0 - math.cos(prog * math.pi))
            amp = 0.5 * resid
            db = 20.0 * math.log10(amp) if amp > 0 else float("-inf")
            row += f"{db:>13.1f}дБ"
        print(row)


# ============================================================================
# main
# ============================================================================
def main():
    np.seterr(all="ignore")
    print("=" * 116)
    print("СЛЕДОВАНИЕ ЧАСТОТЫ ГРАФИКУ: ФЕЙДЫ СМЕНЫ КАНАЛОВ vs ОБЫЧНОЕ ВРЕМЯ")
    print(f"sr={SR}, пакет {PKG_MS} мс; эталон = точный сплайн по контрольным "
          f"точкам (lookup-таблица в эталоне не участвует)")
    print("=" * 116)

    base_pts = circadian()
    curve = Curve(base_pts, MONOTONE, 0.0)
    print(f"\nКривая «циркадный ритм», MONOTONE, точек: {curve.n}; "
          f"экстремумов beat за сутки: {len(curve.trend_crossings)}")
    zig = Curve(relaxation_zigzag(base_pts), MONOTONE, 0.0)
    print(f"Кривая «расслабление SMOOTH», зигзаг 5 мин / -30%, точек: {zig.n}")

    print("\n" + "=" * 116)
    print("E1/E2. Точность следования и разложение ошибки "
          "(окно 20 мин от 06:00 — самый крутой участок)")
    print("=" * 116)
    cases = [
        ("A1. Без смены каналов — обычное время без фейдов (чистый SOLID)",
         Cfg(curve, False), {}),
        ("A2. TIMER 300 с / фейд 1000 мс / пауза 0  [ТЕКУЩИЙ КОД]",
         Cfg(curve, True, TIMER, 300, 1000, 0), {}),
        ("A3. То же, ДО фикса 4384bd1 (одна хорда на весь фейд)",
         Cfg(curve, True, TIMER, 300, 1000, 0), {"legacy_fade": True}),
        ("A4. TIMER 300 с / фейд 10000 мс  [ТЕКУЩИЙ КОД]",
         Cfg(curve, True, TIMER, 300, 10000, 0), {}),
        ("A5. TIMER 300 с / фейд 10000 мс  [ДО ФИКСА]",
         Cfg(curve, True, TIMER, 300, 10000, 0), {"legacy_fade": True}),
        ("A6. TREND/BOTH, фейд 1000 мс, пауза 0  [ТЕКУЩИЙ КОД]",
         Cfg(curve, True, TREND, 300, 1000, 0, BOTH), {}),
        ("A7. TREND/BOTH-стресс: фейд 5000 мс, пауза 2000 мс",
         Cfg(curve, True, TREND, 300, 5000, 2000, BOTH), {}),
    ]
    for title, cfg, kw in cases:
        # TREND-кейсы (A6/A7) считаем в окне 42800..43700 с: там лежит
        # экстремум beat T*=43194.9 с, иначе фейдов в окне просто не будет.
        t0, dur = (42800.0, 900.0) if cfg.mode == TREND else (6 * 3600.0, 1200.0)
        report_detail(title, simulate_detail(cfg, SR, t0, dur, **kw), SR)

    print("\n" + "=" * 116)
    print("E2b. Худшая реалистичная кривизна — зигзаг режима расслабления")
    print("=" * 116)
    for title, cfg, kw in [
        ("B1. Зигзаг, SOLID (без смены каналов)", Cfg(zig, False), {}),
        ("B2. Зигзаг, TIMER 300 с / фейд 1000 мс  [ТЕКУЩИЙ КОД]",
         Cfg(zig, True, TIMER, 300, 1000, 0), {}),
        ("B3. Зигзаг, то же + ПРАВКА stretch (t_end на последнем сэмпле)",
         Cfg(zig, True, TIMER, 300, 1000, 0), {"fix_stretch": True}),
        ("B4. Зигзаг, TIMER 300 с / фейд 1000 мс  [ДО ФИКСА]",
         Cfg(zig, True, TIMER, 300, 1000, 0), {"legacy_fade": True}),
    ]:
        report_detail(title, simulate_detail(cfg, SR, 6 * 3600.0, 600.0, **kw), SR)

    print("\n" + "=" * 116)
    print("E2c. Фейд против SOLID В ТЕ ЖЕ МГНОВЕНИЯ (честный бейзлайн)")
    print("=" * 116)
    e2c_same_instants("D1. Циркадная, TIMER 300 с / фейд 1000 мс  [ТЕКУЩИЙ КОД]",
                      curve, Cfg(curve, True, TIMER, 300, 1000, 0),
                      6 * 3600.0, 1200.0)
    e2c_same_instants("D2. Циркадная, то же  [ДО ФИКСА 4384bd1]",
                      curve, Cfg(curve, True, TIMER, 300, 1000, 0),
                      6 * 3600.0, 1200.0, kw_on={"legacy_fade": True})
    e2c_same_instants("D3. Циркадная, TIMER 300 с / фейд 10000 мс  [ТЕКУЩИЙ]",
                      curve, Cfg(curve, True, TIMER, 300, 10000, 0),
                      6 * 3600.0, 1200.0)
    e2c_same_instants("D4. Зигзаг, TIMER 300 с / фейд 1000 мс "
                      "(узлы СОВПАДАЮТ с изломами зигзага)  [ТЕКУЩИЙ]",
                      zig, Cfg(zig, True, TIMER, 300, 1000, 0),
                      6 * 3600.0, 600.0)
    e2c_same_instants("D5. Зигзаг, TIMER 370 с / фейд 1000 мс "
                      "(узлы НЕ совпадают с изломами)  [ТЕКУЩИЙ]",
                      zig, Cfg(zig, True, TIMER, 370, 1000, 0),
                      6 * 3600.0, 740.0)
    e2c_same_instants("D6. Зигзаг, TIMER 370 с / фейд 1000 мс  [ДО ФИКСА]",
                      zig, Cfg(zig, True, TIMER, 370, 1000, 0),
                      6 * 3600.0, 740.0, kw_on={"legacy_fade": True})
    e2c_same_instants("D7. Зигзаг, то же + ПРАВКА stretch  [ТЕКУЩИЙ+ФИКС]",
                      zig, Cfg(zig, True, TIMER, 370, 1000, 0),
                      6 * 3600.0, 740.0, kw_on={"fix_stretch": True},
                      kw_solid={"fix_stretch": True})
    e2c_same_instants("D8. Циркадная, TREND/BOTH, фейд 1000 мс, пауза 0 "
                      "(окно вокруг экстремума T*=43194.9 с)  [ТЕКУЩИЙ]",
                      curve, Cfg(curve, True, TREND, 300, 1000, 0, BOTH),
                      42800.0, 900.0)
    e2c_same_instants("D9. Циркадная, TREND/BOTH, фейд 5000 мс, пауза 2000 мс "
                      " [ТЕКУЩИЙ]",
                      curve, Cfg(curve, True, TREND, 300, 5000, 2000, BOTH),
                      42800.0, 900.0)
    e2c_same_instants("D10. Циркадная, TREND/BOTH, фейд 5000 мс, пауза 2000 мс "
                      " [ДО ФИКСА]",
                      curve, Cfg(curve, True, TREND, 300, 5000, 2000, BOTH),
                      42800.0, 900.0, kw_on={"legacy_fade": True})

    print("\n" + "=" * 116)
    print("E3. Смещение момента перестановки каналов относительно «цели»")
    print("=" * 116)
    e3_alignment(curve, "TIMER, интервал 300 с, фейд 1000 мс",
                 Cfg(curve, True, TIMER, 300, 1000, 0), 6 * 3600.0, 1200.0)
    e3_alignment(curve, "TIMER, интервал 60 с, фейд 5000 мс, пауза 2000 мс",
                 Cfg(curve, True, TIMER, 60, 5000, 2000), 6 * 3600.0, 600.0)
    e3_alignment(curve, "TREND/BOTH, фейд 1000 мс, пауза 0",
                 Cfg(curve, True, TREND, 300, 1000, 0, BOTH), 11.5 * 3600.0, 3600.0)
    e3_alignment(curve, "TREND/BOTH, фейд 5000 мс, пауза 2000 мс",
                 Cfg(curve, True, TREND, 300, 5000, 2000, BOTH), 11.5 * 3600.0, 3600.0)

    print("\n" + "=" * 116)
    print("E4/E5/E6/E7. Краевые случаи")
    print("=" * 116)
    e4_pause_phase(curve, SR, 6 * 3600.0, [0, 100, 500, 2000, 10000, 60000])

    print("\n### E5. Интерполяция STEP: ступенька внутри фейда")
    step_pts = [(0, 200.0, 4.0), (21899, 200.0, 4.0), (21900, 500.0, 30.0),
                (30000, 500.0, 30.0), (86399, 200.0, 4.0)]
    cstep = Curve(step_pts, STEP, 0.0, build_trend=False)
    for title, cfg, kw in [
        ("C1. STEP, SOLID — ступенька режется по границе (эталон)",
         Cfg(cstep, False), {}),
        ("C2. STEP, TIMER 300 с / фейд 1000 мс  [ТЕКУЩИЙ КОД]",
         Cfg(cstep, True, TIMER, 300, 1000, 0), {}),
        ("C3. STEP, то же + ПРАВКА (резка фейда по границам ступеней)",
         Cfg(cstep, True, TIMER, 300, 1000, 0), {"step_cut_fade": True}),
    ]:
        report_detail(title, simulate_detail(cfg, SR, 21897.0, 12.0, **kw), SR)
    e5b_step_smear(cstep, 21900.0)
    e2c_same_instants("C4. STEP: фейд vs SOLID в те же мгновения  [ТЕКУЩИЙ]",
                      cstep, Cfg(cstep, True, TIMER, 300, 1000, 0),
                      21897.0, 12.0)
    e2c_same_instants("C5. STEP: + ПРАВКА (резка по ступеням + удержание "
                      "значения ступени вместо интерполяции таблицы)",
                      cstep, Cfg(cstep, True, TIMER, 300, 1000, 0),
                      21897.0, 12.0,
                      kw_on={"step_cut_fade": True, "step_hold": True})

    e6_crossing_skip()
    e7_residual()

    print("\n" + "=" * 116)
    print("Готово.")
    print("=" * 116)


if __name__ == "__main__":
    main()
