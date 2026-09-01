package com.binaural.core.audio.debug

import com.binaural.core.audio.engine.NativeAudioEngine

/**
 * Мост к нативной копии виртуальных настенных часов
 * (`binaural::debug::wallOffsetMs()` в `DebugWallClock.h`).
 *
 * Сдвиг живёт в двух местах — здесь и в [DebugClock] — и это ОДИН и тот же
 * виртуальный момент. Расхождение недопустимо: тогда `now` решателя
 * возобновления (Kotlin) и якорь свежего потока в `prepare()` →
 * `engine.getCurrentTimeOfDay()` (C++) уедут в разные стороны, и верификация
 * начнёт проверять саму себя, а не код. Команда `clock` сверяет обе стороны.
 *
 * Движок здесь — «пустышка»: конструктор `BinauralEngine` дешёв (никаких
 * буферов, только конфиг по умолчанию), а сдвиг в C++ хранится в глобальном
 * атомике и не принадлежит конкретному движку, поэтому handle 0 допустим.
 * Так мост работает и до первого `play`, и после `stop`.
 */
object DebugNativeClock {

    private val engine: NativeAudioEngine? = try {
        NativeAudioEngine()
    } catch (t: Throwable) {
        null
    }

    /** true, если нативная библиотека загружена и мост работоспособен. */
    val available: Boolean get() = engine != null

    /** Задать сдвиг в нативной копии часов, мс. */
    fun setOffsetMs(valueMs: Long) {
        engine?.debugSetWallOffsetMs(valueMs)
    }

    /** Сдвинуть нативную копию часов, мс. */
    fun addOffsetMs(deltaMs: Long) {
        engine?.debugAddWallOffsetMs(deltaMs)
    }

    /** Прочитать сдвиг из нативной копии, мс. */
    fun getOffsetMs(): Long = engine?.debugGetWallOffsetMs() ?: 0L

    /** Нативное «сейчас» в мс эпохи. */
    fun getNowMs(): Long = engine?.debugGetWallNowMs() ?: 0L

    /** Нативное время суток, секунды (с миллисекундной точностью). */
    fun getTimeOfDaySeconds(): Float = engine?.debugGetWallTimeOfDaySeconds() ?: 0f
}
