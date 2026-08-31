package com.binaural.core.audio.stream

import com.binaural.core.audio.model.SampleRate
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
    val resumeCurveTimeSeconds: Int = -1,
    /** ФИКС RC-2: фаза несущих для бесшовного кроссфейда (null = свежий старт, фаза 0). */
    val resumeLeftPhase: Float? = null,
    val resumeRightPhase: Float? = null
) {
    /** Звучат ли два спека одинаково (нужен ли вообще handoff). */
    fun audioEquals(other: PlaybackSpec): Boolean =
        config == other.config &&
        relaxation == other.relaxation &&
        sampleRate == other.sampleRate
}

/** Секунд в сутках — знаменатель нормализации времени суток. */
private const val SECONDS_PER_DAY = 86_400f

/**
 * РЕАЛЬНОЕ локальное время суток в секундах, с дробной долей.
 *
 * СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ моменту суток, а не
 * «позиции, где остановились». Единственный источник этой величины в
 * Kotlin-слое — здесь; нативная сторона считает то же самое в
 * `BinauralEngine::realTimeOfDaySeconds()`.
 *
 * Дробь обязательна: целые секунды округляют якорь вниз и дают ошибку до 1 с.
 * Сдвиг часового пояса берётся на текущий момент (`getOffset(now)`), поэтому
 * переход на летнее/зимнее время не требует пересчёта.
 *
 * `java.time.LocalTime` сознательно не используется: он требует API 26 и
 * дешугаринга, а арифметика выше одна и та же на всех версиях.
 */
internal fun realTimeOfDaySeconds(): Float {
    val nowMs = System.currentTimeMillis()
    val localMs = nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)
    return (localMs % 86_400_000L) / 1000f
}

/**
 * Нормализация разницы времён суток в [0, 86400).
 *
 * Обязательна для паузы ЧЕРЕЗ ПОЛНОЧЬ: без неё `now − A0` даёт отрицательное
 * число (например −86390 вместо +10), и замороженный пакет молча сочли бы
 * свежим либо, наоборот, безнадёжно устаревшим.
 */
internal fun normalizeTimeOfDay(seconds: Float): Float {
    val v = seconds % SECONDS_PER_DAY
    return if (v < 0f) v + SECONDS_PER_DAY else v
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
