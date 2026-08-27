package com.binaural.core.audio.stream

import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.RelaxationModeSettings
import java.util.concurrent.atomic.AtomicReference

enum class SpecReason { PLAY, SETTINGS, PRESET_SWITCH, SAMPLE_RATE, RESUME, DEBUG }

/**
 * Неизменяемый снимок всего, что нужно для построения потока.
 * Любое изменение настроек = новый PlaybackSpec, старый никогда не мутирует.
 */
data class PlaybackSpec(
    val serial: Long,
    val config: BinauralConfig,
    val relaxation: RelaxationModeSettings,
    val sampleRate: SampleRate,
    val volume: Float,
    val reason: SpecReason,
    /** RESUME: wall-clock якорь, чтобы таймлайн продолжился с места паузы. 0 = свежий таймлайн. */
    val resumeAnchorMs: Long = 0L,
    /** RESUME: накопленное чистое время воспроизведения до паузы. */
    val resumeElapsedMs: Long = 0L,
    /** Позиция кривой (секунды суток) для продолжения; -1 = свежий старт от настенных часов. */
    val resumeCurveTimeSeconds: Int = -1
) {
    /** Звучат ли два спека одинаково (нужен ли вообще handoff). */
    fun audioEquals(other: PlaybackSpec): Boolean =
        config == other.config &&
        relaxation == other.relaxation &&
        sampleRate == other.sampleRate
}

/**
 * Очередь воспроизведения: один слот, latest-wins.
 * Шторм A→B→C коалесцируется до C; промежуточные спеки не материализуются в звук.
 */
class PlaybackQueue {
    private val slot = AtomicReference<PlaybackSpec?>(null)
    fun offer(spec: PlaybackSpec) { slot.set(spec) }
    fun poll(): PlaybackSpec? = slot.getAndSet(null)
    fun peek(): PlaybackSpec? = slot.get()
    fun clear() { slot.set(null) }
    /** 0 или 1 (один слот, latest-wins). */
    fun size(): Int = if (slot.get() == null) 0 else 1
}
