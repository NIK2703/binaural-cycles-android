#!/usr/bin/env python3
"""Поиск полноамплитудных ступеней в float32-стерео дампе (анализ стыков PCM).

Для бинауральных несущих 100–400 Гц легитимный |Δ| между соседними
сэмплами ≤ 0.15 даже на полной амплитуде; порог 0.5 безопасен с запасом.
Стыки пакетов/ретюна должны давать 0 ступеней (шов |Δ| < 0.02 — критерий
PKG_SEAM). Микшерные фейды (VolumeShaper) в дампе писателя НЕ видны.

Использование: analyze_pcm_seams.py dump.raw [sampleRate]
Выход: 0 если ступеней нет, 1 если найдены.
"""
import numpy as np
import sys

sr = int(sys.argv[2]) if len(sys.argv) > 2 else 48000
raw = np.fromfile(sys.argv[1], dtype=np.float32)
if raw.size % 2:
    raw = raw[:-1]
mono = raw.reshape(-1, 2).mean(axis=1)
d = np.abs(np.diff(mono))
hits = np.where(d > 0.5)[0]

for h in hits:
    pre = mono[max(0, h - int(sr * 0.01)):h]
    post = mono[h + 1:h + int(sr * 0.01) + 1]
    rp = np.sqrt(np.mean(pre ** 2)) if pre.size else 0.0
    rq = np.sqrt(np.mean(post ** 2)) if post.size else 0.0
    print(f"СТУПЕНЬ sample={h} t={h / sr:.3f}s |Δ|={d[h]:.3f} "
          f"RMS_до={rp:.3f} RMS_после={rq:.3f} "
          f"ΔRMS={20 * np.log10(rq / max(rp, 1e-9)):+.1f} дБ")
print(f"итого ступеней: {len(hits)}; сэмплов: {len(mono)}")
sys.exit(1 if len(hits) else 0)
