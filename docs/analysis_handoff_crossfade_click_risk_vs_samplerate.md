# Click risk of the preset/settings handoff, as a function of sample rate

**Date:** 2026-09-03
**Scope:** what happens between "user changes preset / settings / sample rate" and "new audio is
audible", and where in that window a discontinuity can reach the speaker.
**Method:** source read of the actor layer (`BinauralStreamManager`, `BinauralStreamImpl`,
`PlaybackSpec`, `CurveAnchor`, `PacketMemoryBudget`) and the native layer
(`BinauralEngine.cpp`, `AudioGenerator.cpp`, `Config.h`, `BufferPackagePlanner.h`, `jni.cpp`),
plus the measured figures in `docs/hotpath_battery_memory_audit_2026-08-31.md`.
**No code was changed.**

---

## 0. TL;DR

1. **There is no crossfade.** The manager enforces *at most one loaded stream*, so a preset or
   settings change is **sequential**: old stream fades out → is fully released → new stream is
   prepared → new stream fades in. The two ramps never coexist. The code itself documents a
   resulting **silence gap of ~100–200 ms**. The failure mode of this design is therefore a
   *hole*, not a *sum-to-unity error*.
2. **The click exposure is not in the ramps — it is in the plumbing around them.** Every
   genuine discontinuity source reduces to one of: (a) an AudioTrack underrun, (b) a base-volume
   step when the shaper is closed, (c) a step caused by the VolumeShaper lagging past its guard,
   (d) a HAL reconfiguration when the sink rate changes.
3. **Sample rate changes (a) and (d) — through two independent structural facts:**

   | mechanism | 8 / 16 / 22.05 kHz | 44.1 / 48 kHz |
   |---|---|---|
   | track ring buffer | 10.000 s (as requested) | **5.944 s / 5.461 s** (clipped by the 2 MiB cap) |
   | write chunk | 8.000 s | **4.944 s / 4.461 s** |
   | writer wakeups / hour | 450 | **728 / 807** |
   | **underrun headroom** | **2.000 s** | **1.000 s** |
   | packet RAM at the default 600 s | 38 / 77 / 106 MB | **212 / 230 MB** |
   | blocking packet regeneration | 28 / 57 / 78 ms | **156 / 170 ms** |

   High SR halves the stall tolerance, wakes the writer 1.8× more often, and makes the packet
   buffer 6× larger (→ 6× the GC/LMK pressure in a handoff storm).
4. **A vestigial design decision actively hurts:** the outgoing stream uses `EQUAL_POWER`
   (cosine) while the incoming stream uses `LINEAR`. The equal-power rationale in
   `BinauralStream.kt` ("the summed energy of two streams is constant") describes an overlap that
   no longer exists. Worse, the cosine tail has a **1.57× steeper terminal slope** than linear,
   so it converts VolumeShaper latency jitter into a larger amplitude step at teardown — and the
   guard against exactly that is only 60 ms against an observed ~50 ms lag.
5. **The 2 MiB ring cap is a fossil.** It exists because two AudioTracks once had to coexist in
   the AudioFlinger client heap during a real crossfade. With the sequential handoff, only one
   track is ever alive — yet the cap still costs 44.1/48 kHz half of their underrun margin.

---

## 1. What actually runs when the preset or settings change

### 1.1 The call chain

```
ViewModel/UI  ──► BinauralStreamManager.updateConfig(config, relaxation)   [SpecReason.SETTINGS]
              ──► BinauralStreamManager.setPreset(...)                     [SpecReason.PRESET_SWITCH]
              ──► BinauralStreamManager.setSampleRate(rate)                [SpecReason.SAMPLE_RATE]
                        │
                        ▼  (posted to HandlerThread "BinauralStreamActor")
              onSpecChanged(reason)
                        ├─ if newSpec.audioEquals(oldSpec)  →  no-op          ← volume-only changes
                        └─ else beginHandoff()
                                 ├─ captureContinuity()          // elapsed + L/R carrier phases
                                 ├─ queue.offer(enrichForContinuity(spec))
                                 └─ fadeOutCurrent(FadeTarget.SWITCH)   // EQUAL_POWER, 250 ms
                                          │
                        onStreamFullyStopped ──► launchSpec(spec) ──► prepare() ──► launchStream()
                                                                          │          └─ start()  // LINEAR, 250 ms
                                                                          └─ blocks the actor thread
```

The one-stream invariant is explicit in `BinauralStreamManager`: a new stream is created **only
after** `onStreamReleased` fires for the old one. So `old` and `new` never overlap — not in
AudioTracks, not in native engines, not (normally) in packet buffers.

### 1.2 Timeline of one handoff

| phase | duration | audible? |
|---|---|---|
| fade-out (EQUAL_POWER, 1 → 0) | 250 ms | yes, decaying |
| `FADE_GUARD_MS` | 60 ms | silence (shaper already at 0, base volume not yet zeroed) |
| `finalizeStop`: `setVolume(0)` → `closeShaper()` → `pause()` → poll writer latch | ~0–250 ms | silence |
| `releaseInternal`: `stop()` / `release()` the track, release the native engine | ~1–50 ms | silence |
| `prepare()`: `allocateDirect` + generate the 2 s startup packet + `createAudioTrack` | ~5–100 ms (SR-dependent, see §4.3) | **silence** |
| fade-in (LINEAR, 0 → 1) | 250 ms | yes, rising |

**The gap is not the 250 ms fade.** It is `60 ms + release + prepare + (fade-in time to reach
audibility ≈ 25 ms)`. The code comment's "≈100–200 ms" matches: ~100 ms of fixed cost plus
whatever `prepare()` costs on the device.

### 1.3 What is *not* a click source any more

* **Channel swap** — `BufferPackagePlanner::planPackage` (STEP 3 of the signed-beat migration)
  now slices SOLID sub-segments at `SOLID_SUBSEGMENT_MS = 100` and the layout is a continuous
  `layoutSignAt` ramp; the beat passes through zero. The old SOLID→FADE_OUT→PAUSE→FADE_IN cycle
  and its clicks are structurally gone. `docs/analysis_swap_crossfade_missing.md` is about that
  obsolete path and should not be read as describing the current handoff.
* **Config push storms** — `updateConfig` de-duplicates identical configs (4 pushes/second used to
  mean 4 AudioTracks and an audible click).
* **Start click at 44.1/48 kHz (RC-1)** — fixed by priming the track with the generated packet
  *before* `applyShaper()` and `play()`.
* **End-of-preset pop** — fixed by zeroing the base volume *before* closing the shaper, so the
  up-to-10 s of full-amplitude PCM still sitting in the ring cannot return to `userVolume`.

---

## 2. Click taxonomy

Ranked by whether sample rate can make it more or less likely.

| # | mechanism | discontinuity | SR-sensitive? |
|---|---|---|---|
| C1 | **AudioTrack underrun** — writer stalls past the headroom, mixer inserts zeros | step of up to full scale | **YES — strongly (§4.1, §4.2)** |
| C2 | **HAL / sink reconfiguration** when the new track's rate differs from the active sink rate | hardware pop, outside our control | **YES — rate changes only (§4.5)** |
| C3 | **`prepare()` blocks the actor thread** (large `allocateDirect`, GC) → longer gap, fade timers frozen | a *hole*, not a click | **YES (§4.3)** |
| C4 | **VolumeShaper lag > `FADE_GUARD_MS`** → teardown at non-zero amplitude | step of the residual | indirectly — amplified by the EQUAL_POWER tail (§4.4) |
| C5 | **Base-volume step** if `closeShaper()` precedes `setVolume(0)` | full-scale step | no (guarded by ordering today) |
| C6 | **Partial / wrong-size write** — caller writes ≠ `generateAudioBuffer()` return | garbage tail, click + frequency jump | no (contract enforced) |
| C7 | **Curve anchor truncation to whole seconds** (`setCurveTime(jint)`) | frequency/phase step | no (≤1 s, masked by the fade-in) |
| C8 | **Phase reset on fresh play** — `engine.play()` without `preserveTimeline` calls `resetState()` | phase step | no (masked: fade-in starts at 0) |
| C9 | **Packet seam** within/between packets | — | no (phase accumulator is continuous, `m_curveTimeSeconds` carries the exact end) |

C7–C9 deserve a note: they *are* real discontinuities in the data, but the fade-in multiplier is
exactly 0 on the first sample of the new stream, so they are inaudible. They would become audible
the moment anyone shortens the fade-in or starts the track at non-zero gain.

---

## 3. Per-sample-rate parameter table

Derived from the constants and formulas in `BinauralStreamImpl.kt`
(`TRACK_BUFFER_MS = 10000`, `MAX_TRACK_BUFFER_BYTES = 2 MiB`, `WRITE_CHUNK_MS = 8000`,
`MIN_WRITE_MARGIN_MS = 1000`, `STARTUP_PACKET_SECONDS = 2`, `frameBytes = 8`) and
`PacketMemoryBudget.kt`. Ring figures assume the HAL grants the requested size; the code reads
the actual `bufferSizeInFrames` and recomputes the chunk from it, so a HAL that clips the ring
also shortens the chunk and the relationship below still holds.

### 3.1 Ring, chunk, headroom

```
requested   = rate * 8 B/frame * 10000 ms / 1000          (10 s of audio)
ringBytes   = max(minBuffer, min(requested, 2 MiB))
chunkBytes  = min(rate*8*8,  ringBytes - rate*8*1)         (8 s cap, minus a 1 s margin)
headroom    = ringSeconds - chunkSeconds                   ← the stall tolerance
```

| SR (Hz) | requested ring | granted ring | ring | chunk | wakeups/h | **headroom** |
|---|---|---|---|---|---|---|
| 8 000 | 640 000 B | 640 000 B | **10.000 s** | 8.000 s | 450 | **2.000 s** |
| 16 000 | 1 280 000 B | 1 280 000 B | **10.000 s** | 8.000 s | 450 | **2.000 s** |
| 22 050 | 1 764 000 B | 1 764 000 B | **10.000 s** | 8.000 s | 450 | **2.000 s** |
| 44 100 | 3 528 000 B | 2 097 152 B | **5.944 s** | 4.944 s | 728 | **1.000 s** |
| 48 000 | 3 840 000 B | 2 097 152 B | **5.461 s** | 4.461 s | 807 | **1.000 s** |

Note the mechanism: when `ring − 1 s < 8 s` the chunk collapses to `ring − 1 s`, so the headroom
*pinned to exactly the 1 s margin*. At ≤22.05 kHz the 8 s cap binds instead, and the ring is large
enough to leave 2 s over. **The 2 MiB cap is the entire difference.**

### 3.2 Two packet regimes

The writer grows the packet from the 2 s startup buffer to the user's interval
(`maybeGrowPacketBuffer`), but **only if the global packet budget allows it**. Both regimes matter,
because they have opposite click profiles:

| SR | grown packet: chunk / wakeups / headroom | stuck at 2 s: chunk / wakeups / headroom |
|---|---|---|
| 8 000 | 8.000 s / 450 h⁻¹ / **2.000 s** | 2 s / 1800 h⁻¹ / **8.000 s** |
| 16 000 | 8.000 s / 450 h⁻¹ / **2.000 s** | 2 s / 1800 h⁻¹ / **8.000 s** |
| 22 050 | 8.000 s / 450 h⁻¹ / **2.000 s** | 2 s / 1800 h⁻¹ / **8.000 s** |
| 44 100 | 4.944 s / 728 h⁻¹ / **1.000 s** | 2 s / 1800 h⁻¹ / **3.944 s** |
| 48 000 | 4.461 s / 807 h⁻¹ / **1.000 s** | 2 s / 1800 h⁻¹ / **3.461 s** |

**Counter-intuitive but important: a stream that fails to grow its packet is 2–3.5× *safer*
against underruns**, at the cost of 1800 wakeups/hour (battery). The battery optimization and the
click margin are directly opposed, and the trade is set purely by packet length.

### 3.3 Memory (heap 256 MiB → ceiling 220 MiB, per `PacketMemoryBudget`)

| SR | slider stops (min) | max interval | RAM at 600 s | RAM at max | startup packet |
|---|---|---|---|---|---|
| 8 000 | 1,2,5,10,15,20,30,40,50,60 | 3600 s | 38.4 MB | 230.4 MB | 128 KB |
| 16 000 | 1,2,5,10,15,20,30 | 1800 s | 76.8 MB | 230.4 MB | 256 KB |
| 22 050 | 1,2,5,10,15,20 | 1200 s | 105.8 MB | 211.7 MB | 344 KB |
| 44 100 | 1,2,5,10 | 600 s | 211.7 MB | 211.7 MB | 689 KB |
| 48 000 | 1,2,5,10 | 600 s | **230.4 MB** | 230.4 MB | 750 KB |

The ceiling is **memory-bound by construction**, so at the slider maximum every SR ends up with
the same ~28.8 M frames ≈ 230 MB ≈ the same regeneration cost (~170 ms). The difference shows up
at *intermediate* settings: at the default 600 s, 48 kHz holds 6× the PCM of 8 kHz.

### 3.4 Generation cost

Measured: **1.02 s CPU per hour of audio at 48 kHz** (`hotpath_battery_memory_audit_2026-08-31.md
§3.4`) → ≈ **5.9 ns/frame** on device (host SSE bench: 3.94 ns/frame).

| SR | startup 2 s packet | full packet @600 s | full packet @slider max |
|---|---|---|---|
| 8 000 | 0.09 ms | 28 ms | 170 ms (3600 s) |
| 16 000 | 0.19 ms | 57 ms | 170 ms (1800 s) |
| 22 050 | 0.26 ms | 78 ms | 156 ms (1200 s) |
| 44 100 | 0.52 ms | 156 ms | 156 ms (600 s) |
| 48 000 | **0.57 ms** | **170 ms** | 170 ms (600 s) |

Generation is a **single blocking `generateAudioBuffer()` call on the writer thread**
(`m_batchDurationMinutes` defaults to 0, so there is no incremental pre-generation). 170 ms is
comfortable against a 1 s headroom in isolation — but it is 17 % of the margin consumed in one
uncancellable chunk, and it is *the same absolute number* at every sample rate.

---

## 4. The SR-dependent risks in detail

### 4.1 C1 — Underrun headroom is halved at 44.1/48 kHz

An underrun is the worst artifact available: AudioFlinger substitutes silence for the missing
frames, so the output steps from the last sample (which can be at full scale) to zero. That is a
full-scale click, and it is the dominant residual risk in this design.

The tolerance is `ring − chunk`:

* **≤22.05 kHz: 2.000 s.** The writer may be late by up to two seconds — a GC pause, a binder
  stall, a CPU frequency dip, a background LMK sweep — and nothing is heard.
* **44.1 / 48 kHz: 1.000 s.** The same event that was invisible at 22.05 kHz now produces a click.

Because the writer also wakes **1.62× (44.1 k) to 1.79× (48 k)** more often, the *exposure* —
opportunities per hour × probability of exceeding the tolerance at each opportunity — is roughly
**3–3.6× higher at 48 kHz than at ≤22.05 kHz**, all else being equal.

Contributing factor at high SR: the writer must also push **1.71 MB per write** at 48 kHz versus
576 KB at 8 kHz for the same wall-clock coverage, so the write itself takes longer and there is
more DMA/memcpy traffic competing for bandwidth during a CPU dip.

### 4.2 C1 — Memory pressure is 6× higher at 48 kHz, and it lands inside the handoff

At the same slider position, the packet buffer at 48 kHz is 6× the size at 8 kHz
(230 MB vs 38 MB). Consequences that are directly click-relevant:

* **`maybeGrowPacketBuffer` refuses to grow** when `heldByOthers + needed > globalPacketBudget`.
  During a handoff storm the outgoing stream may still hold its grown packet (see §4.6), so the
  new stream stays on the 2 s startup packet → per §3.2 it actually becomes *safer*, but burns
  battery at 1800 wakeups/h indefinitely.
* **`allocateDirect` fails → OOM halving → adaptive ceiling ratchet.** Every halving is a forced
  GC *on the actor thread, during the crossfade* — the code says so explicitly
  ("каждый провал — это реальные потраченные миллисекунды на актёрской нити во время
  кроссфейда"). A forced GC on the actor thread delays the new stream's `start()`, widening the
  audible hole.
* **Historical precedent:** the comment in `prepare()` records heap 223/256 MB, GC thrash
  (15 collections in 600 ms) and the Xiaomi watchdog killing the process —
  `reason: memory leaks occurred`. At 8 kHz with a 38 MB packet, six overlapping streams would
  fit in the same budget; at 48 kHz, one barely does.

At 8 kHz the equivalent configuration is ~6× further from every one of these cliffs.

### 4.3 C3 — `prepare()` blocks the actor thread, and its cost scales with SR

`launchSpec` → `prepare()` runs **synchronously on the actor thread**. While it runs, no fade
timers fire and the next stream cannot start. Its two costs:

* `ByteBuffer.allocateDirect(startupSamples * 8)` — **750 KB at 48 kHz vs 128 KB at 8 kHz**
  (6×). A large direct allocation under memory pressure can trigger a GC, which is exactly the
  situation a handoff storm creates.
* `generateBufferDirect(buf, 2 s)` — 0.57 ms vs 0.09 ms. Negligible.

So `prepare()` is dominated by allocation, not generation, and the allocation scales with SR.
This is a *gap* risk (C3) rather than a click, but it is the part of the gap the team can most
easily measure — and it is the reason the observed gap is ~100–200 ms rather than a fixed ~85 ms.

### 4.4 C4 — The EQUAL_POWER tail eats the guard margin

`fadeOutCurrent` uses `FadeShape.EQUAL_POWER` for `FadeTarget.SWITCH`; `start()` uses the
interface default `FadeShape.LINEAR`.

The documented rationale in `BinauralStream.kt` — *"суммарная энергия двух потоков в окне
перекрытия постоянна, провала −3 дБ нет"* — **describes an overlap that no longer exists**. With
one stream at a time, the shapes cannot sum to anything. What is left is a pure asymmetry, and it
points the wrong way:

* `EQUAL_POWER` down: `v(p) = cos(p·π/2)` → terminal slope `dv/dp|_{p=1} = −π/2 ≈ −1.571`
* `LINEAR` down: `v(p) = 1 − p` → terminal slope `−1.000`

`FADE_GUARD_MS = 60 ms` exists because VolumeShaper lags the schedule by ~50 ms. If the lag ever
exceeds the guard by `L` ms, the track is torn down at residual amplitude
`≈ 1.571 · (L / 250)` for cosine versus `0` for linear (linear is already clamped at zero at the
end of its ramp).

| shaper lag | residual, LINEAR out | residual, EQUAL_POWER out |
|---|---|---|
| 50 ms (observed) | 0 | 0 |
| 60 ms (= guard) | 0 | 0 |
| 70 ms | 0 | **0.063 (−24 dBFS)** |
| 80 ms | 0 | **0.126 (−18 dBFS)** |

Ten milliseconds of scheduling jitter is not exotic on a loaded device, and −18 dBFS step is
clearly audible. **Switching the switch fade-out to `LINEAR` removes this failure mode at zero
cost** — the ramp length is unchanged, and there is no crossfade whose energy needs conserving.

### 4.5 C2 — Sample-rate changes additionally risk a HAL reconfiguration pop

This one is specific to `SpecReason.SAMPLE_RATE` and applies to no other handoff reason.

`createAudioTrack(rate)` builds a **new AudioTrack at a new rate**. AudioFlinger must place it on
an output thread whose sink rate matches, or insert a resampler:

* If the new rate ≠ the rate currently driving the sink, AudioFlinger may have to **re-open or
  re-parameterise the HAL output stream**. That is a hardware-level event with its own mute/unmute
  sequencing; on some devices it produces an audible pop **regardless of what our ramps do**.
* 48 000 Hz is the most common native sink rate on Android, so 48 kHz is the *least* likely to
  trigger this; 8 000 / 16 000 / 22 050 / 44 100 Hz all go through a resampler and are more
  likely to perturb the sink.

Our own contribution to this is already minimised: the outgoing track is released only after its
shaper has reached 0 *and* the base volume has been zeroed, so nothing of ours is playing when the
HAL changes state. What remains is entirely device/HAL-specific and must be measured, not
reasoned about.

Practical note: the same reconfiguration also makes `createAudioTrack` slower on a rate change
than on a same-rate preset switch, further widening the §1.2 gap.

### 4.6 The one-stream invariant is *mostly* airtight — with a 250 ms hole

`releaseInternal()` awaits the writer for only `WRITER_HANDOFF_GRACE_MS = 250 ms`, then hands
native-engine ownership to the writer (`engineOwnedByWriter`) and proceeds. The outgoing packet
buffer is therefore freed by the writer's `finally`, which can be up to:

* **one blocking `write()`** — 4.46 s at 48 kHz, 8 s at ≤22.05 kHz, *plus*
* **one blocking `generateAudioBuffer()`** — up to 170 ms.

So the old 230 MB buffer can still be alive when the new stream starts. `PacketMemoryBudget`
documents exactly this case as the canonical allocation failure:

> "Самый жизненный случай — хэндофф: старый поток держит дорощенный пакет, новый просит такой же
> и падает."

This is *not* a click by itself (the new stream needs only its 750 KB startup buffer at that
moment), but it is the trigger for the §4.2 ratchet, and it means the "at most one packet holder"
invariant is **statistically true, not structurally guaranteed**. `pkstat`'s `holders peak` is the
instrument that measures it.

---

## 5. Risk matrix

Legend: ● structural / always present · ○ conditional · – not applicable

| scenario | 8 k | 16 k | 22.05 k | 44.1 k | 48 k |
|---|---|---|---|---|---|
| Underrun on a writer stall (C1) | ○ low | ○ low | ○ low | ○ **medium** | ○ **high** |
| Underrun during packet regeneration (C1, ~170 ms) | ○ low | ○ low | ○ low | ○ **medium** | ○ **high** |
| GC / OOM pressure in a handoff storm (C2/C3) | ○ low | ○ low | ○ low | ○ **medium** | ○ **high** |
| `prepare()` gap from large `allocateDirect` (C3) | ○ low | ○ low | ○ low | ○ med | ○ med |
| HAL reconfiguration pop on rate change (C2) | ● **device** | ● device | ● device | ● device | ○ lower |
| Shaper lag > 60 ms guard (C4) | ● all rates, amplified by the EQUAL_POWER tail | | | | |
| Base-volume step (C5) | – guarded by ordering | | | | |
| Packet seam / anchor truncation / phase reset (C6–C9) | – masked by the fade-in | | | | |

**Net:** for a *preset or settings change at constant sample rate*, the ordering is
**8 k ≈ 16 k ≈ 22.05 k < 44.1 k < 48 k** — low SR is strictly more robust, and the reason is the
2 MiB ring cap, not anything about the audio.
For a *sample-rate change specifically*, add a device-dependent HAL pop on top, which is worst
when the new rate is far from the sink's native rate (i.e. **not** 48 kHz on most devices).

---

## 6. Mitigations already in place (do not regress these)

| guard | where | protects against |
|---|---|---|
| Prime the track with the generated packet *before* `applyShaper()`/`play()` | `start()` (RC-1) | start click at 44.1/48 kHz |
| `setVolume(0f)` **before** `closeShaper()` | `finalizeStop()` | full-scale pop from the ring's residual PCM |
| `FADE_GUARD_MS = 60` after every ramp | all fade completions | cutting the track at non-zero amplitude |
| Shaper continuity (`live = old.volume` → new base) + `tryLinearFallback` | `applyShaper()` | step when a shaper is replaced mid-ramp |
| 16 curve points and `.coerceIn(0f,1f)` | `buildCurve()` | `createVolumeShaper` throwing on 17 points or on `cos(π/2) = −4.4e-8` |
| `generateAudioBuffer()` returns the real count; caller writes exactly that | JNI contract | garbage tail / click at packet seams |
| `updateConfig` de-duplication | manager | rebuild storms (was 4 AudioTracks/second) |
| `preserveTimeline = true` on every resume/handoff path | `prepare()` | curve re-anchor → frequency/phase jump (FIX 3) |
| Phase capture/restore (`getPhases` → `setPhases`) | RC-2 | carrier phase discontinuity |
| TTL-free WakeLock, renewed every 5 min | manager | underrun clicks after ~12 min (historic bug) |
| `maybeGrowPacketBuffer` rejects buffers < 10 s of audio | writer | guaranteed underrun from an undersized packet |
| Actual `bufferSizeInFrames` read back from the track | `createAudioTrack()` | chunk computed from a ring the HAL clipped |
| Debug invariant watchdog (|Δ| ≤ 2 s sustained 3 s) | manager | whole class of "sound drifted off wall clock" |

---

## 7. Findings and recommendations

Ordered by (expected click-risk reduction) ÷ (implementation risk).

### F1 — Raise (or make rate-aware) `MAX_TRACK_BUFFER_BYTES`. **Biggest single win.**

The 2 MiB cap exists so that *two* AudioTracks fit in the AudioFlinger client heap. Two tracks
never coexist now. At 48 kHz, lifting the cap from 2 MiB to 3.84 MiB restores the full 10 s ring:

| | today | with the cap lifted |
|---|---|---|
| ring @48 kHz | 5.461 s | 10.000 s |
| chunk @48 kHz | 4.461 s | 8.000 s |
| wakeups/h | 807 | 450 |
| **headroom** | **1.000 s** | **2.000 s** |

That removes ~all of the §4.1 differential between 48 kHz and ≤22.05 kHz, and cuts writer wakeups
by 44 % as a side effect. **Risk:** the AudioFlinger client heap is finite and shared; during the
brief window where the old track is being released while the new one is created (§4.5) both may
momentarily exist. A middle setting (e.g. 3 MiB) plus a fallback that retries smaller on
`-12 NO_MEMORY` would be the conservative form. **Verify** with `dumpsys media.audio_flinger` and
by watching for `createAudioTrack` failures in the log.

### F2 — Switch the SWITCH fade-out from `EQUAL_POWER` to `LINEAR`. **Near-zero risk.**

Removes the C4 failure mode entirely (§4.4) and deletes a misleading comment in
`BinauralStream.kt`. One-line change; no ramp length change; no battery change.

### F3 — Make the guard adaptive rather than a fixed 60 ms.

Instead of `dur + FADE_GUARD_MS`, poll the shaper to actual completion with a hard ceiling
(e.g. `min(dur * 1.5, dur + 250)`). This makes C4 robust on loaded devices regardless of F2, and
lets the handoff finish *sooner* on unloaded ones (the guard is pure added silence today).

### F4 — Bound the blocking generation call.

`generateAudioBuffer()` for a full 600 s / 28.8 M-frame packet is one uncancellable ~170 ms call
on the writer thread. Options, cheapest first:
* cap the single-call size (e.g. 60 s) and let the writer loop refill piecewise — the packet
  generator already emits 100 ms sub-segments, so this is a loop, not a redesign;
* or move generation off the writer thread with a double buffer.
Either converts a 170 ms hard stall into ≤10 ms slices, which matters most at 44.1/48 kHz where
the headroom is 1 s.

### F5 — Do not let the packet-budget ratchet fire during a handoff storm.

`PacketMemoryBudget.noteAllocationFailure` halves the ceiling permanently (until packet memory
drops to zero *and* the failure is classified as contention). In a storm this makes every
subsequent handoff allocate a smaller buffer *and* pay a forced GC on the actor thread. Consider
suppressing the ratchet while `livePacketHolders > 1`, since that is precisely the case where the
failure is transient by construction.

### F6 — Close the 250 ms ownership hole in §4.6.

`WRITER_HANDOFF_GRACE_MS` is a deliberate trade (blocking the actor for `WRITER_EXIT_WAIT_MS =
9.5 s` was worse). A middle option: have the writer check a "release requested" flag *between*
sub-segment generations, so a long generation can be abandoned early. This shrinks the window in
which two grown packets coexist, which is the trigger for F5.

### F7 — Measure the HAL reconfiguration pop (C2) explicitly.

The only SR-specific artifact that is outside the app. Test protocol: play at 48 kHz, switch to
8 kHz, and capture with an external recorder or a loopback; then the reverse; then 8 k → 22.05 k.
If pops appear only on rate changes (and not on same-rate preset switches), they are HAL-level and
the only genuine mitigation is to keep a silent 48 kHz track alive across the change so the sink
rate never moves.

### F8 — Documentation fixes (cheap, prevents future regressions).

* `BinauralStream.kt`: the `EQUAL_POWER` doc describes a crossfade that does not exist.
* `BinauralStreamImpl.kt:729`: the comment justifying the 2 MiB cap says "два живых трека
  (кроссфейд)"; with the sequential handoff that premise is false — which is exactly why F1 is
  worth doing, and why nobody has revisited the number.
* `BinauralStreamManager.kt`: the "разрыв ≈ 100–200 мс" comment should record that the gap is
  `60 ms + release + prepare + ~25 ms`, so that future work has the right decomposition.

---

## 8. How to verify any of this on the device

| what | how |
|---|---|
| ring actually granted (`ringSec`) | log line `createAudioTrack … кольцо запрошено … выделено … @NГц` |
| underruns | `start … RC1 underrunDelta=` and `track.underrunCount`; a non-zero delta **is** a click |
| packet-holder invariant | debug CLI `pkstat` → `holders peak` (must stay 1), `oomHalvings` |
| memory ceilings per SR | `PacketMemoryBudget.report()` → heap / ceiling / ranges / bytes per stop |
| move the ceiling without a rebuild | `packetpct`, `packetmax`, `packetgpct`, `pcreset` |
| audible gap length | `dbgxfade.sh` / handoff log timestamps: `beginHandoff` → `fade-in завершён` |
| curve-position drift across a handoff | `invcheck`, `INVARIANT НАРУШЕН` lines (`WATCHDOG_TOL_SEC = 2 s`) |
| packet seams (data-level discontinuity) | `PKG_SEAM` / `PKG_BOUNDARY` logs (`|dL|,|dR| < 0.02` = clean) |
| forced stress | a 40-switch storm, `pause → switch → resume` storm, and a rate-change storm, each at 8 k and 48 k |

**Suggested A/B for F1/F2/F3:** run the same 40-switch storm at 48 kHz before and after, and
compare `holders peak`, `oomHalvings`, `underrunDelta`, and the measured gap. Then repeat at
8 kHz — the two should converge if F1 worked.

---

## 9. Open questions

1. Does the HAL on POCO 23049PCD8G grant the requested ring at 8/16/22.05 kHz, or does it clip it?
   Everything in §3.1 assumes it grants it; the code adapts, but the table would need revising.
2. Is the fast-mixer path ever taken at 48 kHz? If so, the ring and the shaper's application
   point both change, and §3.1 needs a second row for 48 kHz.
3. Real-world distribution of `allocateDirect` latency at 750 KB vs 128 KB under pressure — F3
   and §4.3 rest on an assumption, not a measurement.
4. `docs/analysis_swap_crossfade_missing.md` still describes the pre-STEP-3 swap crossfade as
   current. It should be marked obsolete to avoid being re-read as a live defect.
