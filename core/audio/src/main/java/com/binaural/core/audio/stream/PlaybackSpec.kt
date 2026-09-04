package com.binaural.core.audio.stream

import com.binaural.core.audio.debug.DebugClock
import com.binaural.core.audio.model.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.RelaxationModeSettings
import java.util.concurrent.atomic.AtomicReference

enum class SpecReason {
    PLAY, SETTINGS, PRESET_SWITCH, SAMPLE_RATE, RESUME, DEBUG,
    /**
     * Предпросмотр другого времени суток из редактора пресета (см.
     * docs/plan_playback_scrub_handle.md). По звуку спек может быть полностью
     * равен живому — сравнение обязано идти не через [PlaybackSpec.audioEquals].
     */
    SCRUB
}

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
    /**
     * Якорь кривой: позиция И её происхождение ([CurveAnchor]).
     *
     * Раньше здесь был голый `Int resumeCurveTimeSeconds` с сентинелом `-1`, и
     * проверка `>= 0` не отличала легальную ПОЛНОЧЬ (0) от «якоря нет». Из-за
     * этого литеральный 0 из протухшего захвата беспрепятственно становился
     * якорем и защёлкивался (0 → 0 → 0). Теперь сентинел — только
     * [CurveAnchor.NONE], а 0 — обычное время суток.
     *
     * Хэндофф (SETTINGS/PRESET_SWITCH/SAMPLE_RATE) якорь НЕ несёт: новый поток
     * встаёт на «сейчас» в `prepare()`, от уходящего наследуются только фазы
     * несущих и часы сессии (см. docs/handoff_anchor_zero_analysis_plan.md, P1).
     */
    val resumeAnchor: CurveAnchor = CurveAnchor.NONE,
    /** ФИКС RC-2: фаза несущих для бесшовного кроссфейда (null = свежий старт, фаза 0). */
    val resumeLeftPhase: Float? = null,
    val resumeRightPhase: Float? = null,
    /**
     * СКРАБ: сдвиг ОСИ времени суток в секундах, [0, 86400).
     *
     * Звучит не «позиция трека», а «какое сейчас время суток»: ось =
     * `normalize(реальное_сейчас + scrubOffsetSec)`. Кривая при этом
     * продолжает эволюционировать под прослушиванием, а все производные от
     * времени (знаковая раскладка каналов, relaxation, beat scatter) остаются
     * консистентны — в отличие от замороженной позиции.
     *
     * Сдвиг — СКАЛЯР, а не захваченный [CurveAnchor]: якорь устаревает за
     * время фейд-аута и релиза старого потока, скаляр применяется к «сейчас»
     * уже внутри `prepare()` и устареть не может.
     *
     * 0 = звук следует за реальным моментом суток (обычный режим).
     *
     * ВАЖНО: [audioEquals] это поле сознательно НЕ учитывает — иначе
     * предпросмотр невозможно было бы отличить от живого спека. Сравнение
     * делается на вызывающей стороне (см. [SpecReason.SCRUB]).
     */
    val scrubOffsetSec: Int = 0
) {
    /** Звучат ли два спека одинаково (нужен ли вообще handoff). */
    fun audioEquals(other: PlaybackSpec): Boolean =
        config == other.config &&
        relaxation == other.relaxation &&
        sampleRate == other.sampleRate
}

/** Секунд в сутках — знаменатель нормализации времени суток. */
internal const val SECONDS_PER_DAY = 86_400f

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
 *
 * ВИРТУАЛЬНОЕ ВРЕМЯ: часы читаются через [DebugClock], а не напрямую из
 * `System.currentTimeMillis()`. Сдвиг [DebugClock] в release всегда 0, поэтому
 * боевое поведение не меняется ни на йоту, зато в debug-сборке верификация
 * возобновления может мгновенно «прокрутить» паузу: настенные часы уезжают
 * вперёд, а фронтир генерации остаётся замороженным — ровно как при реальной
 * паузе, но без ожидания. Нативная сторона обязана видеть тот же сдвиг
 * (см. `DebugWallClock.h`), иначе `now` решателя и якорь `prepare()` разойдутся.
 */
internal fun realTimeOfDaySeconds(): Float = DebugClock.realTimeOfDaySeconds()

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
