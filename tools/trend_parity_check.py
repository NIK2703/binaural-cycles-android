#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка согласованности TREND-режима перестановки каналов.

После коммита c53a6ca («key trend channel-swap on beat frequency») тренд
переключили на частоту БИЕНИЙ:
  - computeTrendCrossings / trendBeatDeltaAt  -> экстремумы BEAT   (Interpolation.h:331)
  - BinauralEngine::setPlaying(resume)        -> trendBeatDeltaAt  (BinauralEngine.cpp:319)

Но ОДНО место осталось на НЕСУЩЕЙ частоте:
  - channelSwapStateAt() поправка «midnightPhase» считает CARRIER-дельту
    (BufferPackagePlanner.h:106-115), а должна — BEAT-дельту,
    потому что чётность считается по BEAT-пересечениям.

Следствия:
  1) если в начале суток знак тренда carrier и beat расходятся —
     состояние каналов инвертировано ВСЕ СУТКИ;
  2) свежий старт (channelSwapStateAt, carrier) и resume
     (trendDesiredSwapped + trendBeatDeltaAt, beat) дают РАЗНЫЙ ответ ->
     лишний корректирующий свап (forceImmediateTrendSwap) сразу после resume.

Плюс: trendCarrierDeltaAt() объявлен, но нигде не вызывается (dead code),
а комментарий в BufferPackagePlanner.h:13-24 всё ещё описывает несущую.
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fade_tracking_analysis import (Curve, Cfg, MONOTONE, TREND, TIMER, BOTH,
                                    TREND_HALF_WINDOW_SEC, DAY,
                                    trend_solid_duration_ms, SOLID, FADE_OUT)


def carrier_delta_at(curve, t):
    lo_p, up_p = curve.lookup_s(t + TREND_HALF_WINDOW_SEC)
    lo_m, up_m = curve.lookup_s(t - TREND_HALF_WINDOW_SEC)
    return ((up_p + lo_p) - (up_m + lo_m)) * 0.5


def beat_delta_at(curve, t):
    return curve.trend_beat_delta(t)


def state_at_carrier(curve, pos):
    """Как в channelSwapStateAt(): чётность BEAT-пересечений + поправка по CARRIER в 0:00."""
    cnt = sum(1 for tc, _ in curve.trend_crossings if tc < pos)
    swapped = (cnt & 1) != 0
    if carrier_delta_at(curve, 0.0) < 0.0:      # <-- НЕСУЩАЯ
        swapped = not swapped
    return swapped


def state_at_beat(curve, pos):
    """Корректная версия: та же чётность, но поправка по BEAT в 0:00 (как в resume)."""
    cnt = sum(1 for tc, _ in curve.trend_crossings if tc < pos)
    swapped = (cnt & 1) != 0
    if beat_delta_at(curve, 0.0) < 0.0:         # <-- БИЕНИЯ
        swapped = not swapped
    return swapped


def sign_rule(curve, pos):
    """Эталон: swapped <=> тренд BEAT убывает в точке pos (плато сохраняет)."""
    d = beat_delta_at(curve, pos)
    if d > 0:
        return False
    if d < 0:
        return True
    return None      # плато


def sweep(curve, label):
    print(f"\n### {label}")
    print(f"  экстремумов BEAT: {len(curve.trend_crossings)} -> "
          + ", ".join(f"{t/3600:.2f}h" for t, _ in curve.trend_crossings))
    print(f"  Δ carrier(0:00) = {carrier_delta_at(curve, 0.0):+.4f} Гц   "
          f"Δ beat(0:00) = {beat_delta_at(curve, 0.0):+.4f} Гц")
    disagree = 0
    mismatch_c = mismatch_b = 0
    total = 0
    for k in range(0, 86400, 60):
        pos = float(k)
        want = sign_rule(curve, pos)
        if want is None:
            continue
        total += 1
        if state_at_carrier(curve, pos) != want:
            mismatch_c += 1
        if state_at_beat(curve, pos) != want:
            mismatch_b += 1
    print(f"  расхождение с эталоном (знаковое правило по beat):")
    print(f"    текущий код  (поправка по CARRIER): "
          f"{mismatch_c}/{total} точек = {mismatch_c/max(total,1):.1%}")
    print(f"    исправленный (поправка по BEAT):   "
          f"{mismatch_b}/{total} точек = {mismatch_b/max(total,1):.1%}")
    return mismatch_c, mismatch_b, total


def squeeze_check(curve, label, fade_ms, pause_ms):
    """SOLID вырождается в 0, если до экстремума меньше lead+pause/2."""
    print(f"\n### {label}: фейд {fade_ms} мс, пауза {pause_ms} мс")
    times = [tc for tc, _ in curve.trend_crossings]
    if len(times) < 2:
        print("  экстремумов < 2 — проверка неприменима")
        return
    lead = max(fade_ms, 15)
    half = pause_ms // 2
    gaps = []
    for i in range(len(times)):
        a = times[i]
        b = times[(i + 1) % len(times)]
        g = (b - a) % DAY
        if g > 0:
            gaps.append((a, g))
    worst = min(gaps, key=lambda x: x[1])
    print(f"  минимальное расстояние между экстремумами: {worst[1]/60:.1f} мин "
          f"(около {worst[0]/3600:.2f} ч)")
    print(f"  требуется на процедуру: lead {lead} мс + pause/2 {half} мс = {lead+half} мс")
    if worst[1] * 1000 <= lead + half:
        print(f"  -> SOLID клампится в 0: FADE_OUT/PAUSE/FADE_IN идут подряд, "
              f"SOLID-звука между ними нет")
    else:
        print(f"  -> запас есть: SOLID = {worst[1]*1000 - lead - half:.0f} мс")


def main():
    print("=" * 92)
    print("ПРОВЕРКА 1. Согласованность поправки чётности в channelSwapStateAt()")
    print("=" * 92)

    # Пресет «Циркадный ритм»: carrier и beat в 0:00 растут ОБА -> расхождения нет
    circ = [(0, 174.0, 3.0), (10800, 210.0, 6.0), (21600, 220.0, 8.0), (32400, 440.0, 20.0),
            (43200, 440.0, 25.0), (54000, 440.0, 18.0), (64800, 250.0, 12.0), (75600, 240.0, 10.0)]
    m1 = sweep(Curve(circ, MONOTONE, 0.0), "Пресет «Циркадный ритм» (carrier и beat растут в 0:00)")

    # Контрпример: в 0:00 carrier РАСТЁТ, а beat ПАДАЕТ
    conflict = [(0, 300.0, 30.0), (10800, 400.0, 10.0), (21600, 500.0, 5.0),
                (43200, 500.0, 5.0), (64800, 400.0, 10.0), (82800, 320.0, 20.0)]
    m2 = sweep(Curve(conflict, MONOTONE, 0.0),
               "Контрпример: в 0:00 carrier растёт (+), beat падает (-)")

    print("\n" + "=" * 92)
    print("ПРОВЕРКА 2. Вырождение SOLID в 0 при тесных экстремумах (TREND)")
    print("=" * 92)
    squeeze_check(Curve(circ, MONOTONE, 0.0), "Циркадный ритм", 1000, 0)
    squeeze_check(Curve(circ, MONOTONE, 0.0), "Циркадный ритм", 10000, 4000)
    # Пилообразная кривая: много тесных экстремумов beat
    saw = [(h * 1800, 300.0, 10.0 if (h % 2 == 0) else 40.0) for h in range(48)]
    squeeze_check(Curve(saw, MONOTONE, 0.0), "Пилообразная beat (экстремум каждые 30 мин)",
                  1000, 0)
    squeeze_check(Curve(saw, MONOTONE, 0.0), "Пилообразная beat (экстремум каждые 30 мин)",
                  5000, 2000)

    print("\n" + "=" * 92)
    print("ИТОГ")
    print("=" * 92)
    if m2[0] > 0 and m2[1] == 0:
        print("Контрпример подтверждает: текущая (carrier) поправка даёт "
              f"{m2[0]/m2[2]:.0%} расхождений, поправка по beat — 0.")
        print("=> channelSwapStateAt() нужно перевести на trendBeatDeltaAt(),")
        print("   как уже сделано в BinauralEngine::setPlaying(resume).")
    else:
        print("Расхождение не воспроизвелось — нужна ручная проверка.")


if __name__ == "__main__":
    main()
