package com.binaural.core.audio.debug

import java.util.concurrent.atomic.AtomicLong

/**
 * ВИРТУАЛЬНЫЕ НАСТЕННЫЕ ЧАСЫ: общий сдвиг «сейчас» для всего процесса.
 *
 * ПОЧЕМУ ЭТО НЕ VirtualClock.
 * VirtualClock (см. `core/audio/src/main/cpp/VirtualClock.h`) ускоряет время
 * движка, но носитель этого времени — сгенерированные сэмплы: ось идёт как
 * `base + сгенерированные_секунды * scale`. На паузе генерация стоит, значит
 * и «now» по VirtualClock стоит. Сколько ни жди — Δ паузы останется нулевой, и
 * решатель возобновления (SOFT против REBUILD) не будет испытан вообще.
 *
 * Сдвиг настенных часов воспроизводит ровно то, что происходит при реальной
 * паузе: «сейчас» уезжает вперёд мгновенно, а пакет, голова трека и фронтир
 * генерации остаются замороженными на месте.
 *
 * ПОЧЕМУ СДВИГ ДУБЛИРУЕТСЯ В C++.
 * Сдвиг живёт в двух местах: здесь (Kotlin) и в `binaural::debug::wallOffsetMs()`
 * (`core/audio/src/main/cpp/DebugWallClock.h`). Это один и тот же «виртуальный
 * момент», и обе стороны обязаны его видеть, иначе разойдутся
 *   • `now` решателя возобновления (читается здесь, Kotlin) и
 *   • якорь свежего потока в `prepare()` → `engine.getCurrentTimeOfDay()`
 *     (читается там, C++).
 * Проверка, что они не разъехались, — команда `clockchk`.
 *
 * В release сдвиг всегда 0: единственный путь его установить — debug-команды,
 * существующие только под `BuildConfig.DEBUG`.
 */
object DebugClock {

    private const val MS_PER_DAY = 86_400_000L

    private val offsetMs = AtomicLong(0L)

    /** Текущий сдвиг относительно реальных часов, мс. */
    @JvmStatic
    fun offsetMs(): Long = offsetMs.get()

    /** Задать сдвиг, мс. Синхронизирует нативную копию часов. */
    @JvmStatic
    fun setOffsetMs(valueMs: Long) {
        offsetMs.set(valueMs)
        DebugNativeClock.setOffsetMs(valueMs)
    }

    /**
     * Сдвинуть часы на дельту, мс.
     *
     * Именно так моделируется пауза: `warp +10000` == «прошло 10 секунд» без
     * единой секунды реального ожидания.
     */
    fun addOffsetMs(deltaMs: Long): Long {
        val result = offsetMs.addAndGet(deltaMs)
        DebugNativeClock.setOffsetMs(result)
        return result
    }

    /**
     * Прокрутить время на [deltaMs] — ОДНА СТОРОНА ВИРТУАЛЬНОГО ВРЕМЕНИ.
     *
     * Заменитель `sleep` в сценариях верификации: часы уезжают мгновенно,
     * а звуковой пакет, голова трека и фронтир генерации остаются на месте.
     */
    @JvmStatic
    fun warp(deltaMs: Long): Long = addOffsetMs(deltaMs)

    /** Прокрутить время на [deltaSeconds] (дробно) — то же, что [warp]. */
    @JvmStatic
    fun warpSeconds(deltaSeconds: Double): Long =
        addOffsetMs((deltaSeconds * 1000.0).toLong())

    /** Вернуться к реальному времени — обе стороны. */
    @JvmStatic
    fun reset() {
        offsetMs.set(0L)
        DebugNativeClock.setOffsetMs(0L)
    }

    /** Виртуальное «сейчас» в мс эпохи. */
    @JvmStatic
    fun nowWallMs(): Long = System.currentTimeMillis() + offsetMs.get()

    /**
     * Виртуальное локальное время суток, секунды с дробной долей.
     *
     * Решатель возобновления (BinauralStreamManager) обязан читать «now»
     * отсюда — иначе он сравнит виртуальное «сейчас» с реальным, и проверка
     * начнёт врать сама себе.
     */
    @JvmStatic
    fun realTimeOfDaySeconds(): Float {
        val nowMs = nowWallMs()
        val localMs = nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)
        // Остаток может быть отрицательным: сдвиг часов бывает и назад
        // (тест перехода через полночь уезжает «вчера»).
        val inDay = localMs % MS_PER_DAY
        val positive = if (inDay < 0L) inDay + MS_PER_DAY else inDay
        return positive / 1000f
    }

    /**
     * Передвинуть часы так, чтобы «сейчас» было ровно [timeOfDaySeconds].
     *
     * Берётся БЛИЖАЙШИЙ переход (вперёд или назад в пределах текущих суток),
     * поэтому `totime 86395` аккуратно ставит нас в 5 секундах до полуночи —
     * ровно то, что нужно для граничного случая «пауза через полночь».
     */
    @JvmStatic
    fun setTimeOfDay(timeOfDaySeconds: Double) {
        val target = ((timeOfDaySeconds % 86_400.0) + 86_400.0) % 86_400.0
        val current = realTimeOfDaySeconds().toDouble()
        var delta = target - current
        // Ближайший переход: не дальше полусуток в любую сторону.
        if (delta > 43_200.0) delta -= 86_400.0
        if (delta < -43_200.0) delta += 86_400.0
        addOffsetMs((delta * 1000.0).toLong())
    }

    /** «Человеческое» HH:MM:SS.mmm текущего виртуального момента (для отчётов). */
    @JvmStatic
    fun formatTimeOfDay(seconds: Float): String {
        val total = ((seconds % 86_400f) + 86_400f) % 86_400f
        val whole = total.toLong()
        val ms = ((total - whole) * 1000f).toLong()
        val h = whole / 3600
        val m = (whole % 3600) / 60
        val s = whole % 60
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms)
    }
}
