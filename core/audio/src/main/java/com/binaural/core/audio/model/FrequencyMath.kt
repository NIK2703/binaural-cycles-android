package com.binaural.core.audio.model

import kotlin.math.abs

/**
 * Математика частот бинаурального ритма. Единая точка истины для UI и движка.
 *
 * ЗНАК ЧАСТОТЫ БИЕНИЙ
 * -------------------
 * beat — величина ЗНАКОВАЯ: beat = right − left.
 * - beat > 0: правый канал звучит выше левого (обычное расположение);
 * - beat < 0: каналы поменяны местами — правый ниже левого;
 * - |beat| — то, что слышно как пульсация. Знак задаёт только раскладку каналов
 *   (какое ухо получает более высокий тон).
 *
 * Физические частоты тонов (carrier, левый/правый канал) знаковыми не бывают и
 * всегда клампятся к >= 0 Гц; частота биений клампится только по МОДУЛЮ.
 *
 * ОГРАНИЧЕНИЕ МОДУЛЯ
 * ------------------
 * Боковые частоты должны оставаться в слышимом диапазоне
 * [MIN_TONE_FREQUENCY; MAX_TONE_FREQUENCY]. Условие симметрично по знаку beat,
 * т.к. при смене знака боковые частоты просто меняются местами:
 *   min(carrier ∓ |beat|/2) >= MIN_TONE_FREQUENCY  =>  |beat| <= 2 * (carrier - MIN_TONE_FREQUENCY)
 *   max(carrier ± |beat|/2) <= MAX_TONE_FREQUENCY  =>  |beat| <= 2 * (MAX_TONE_FREQUENCY - carrier)
 * Соблюдение этого ограничения гарантирует, что ни один канал не уйдёт ниже 0 Гц
 * (иначе движок обрезал бы его, исказив и carrier, и beat).
 */
object FrequencyMath {

    /** Минимальная частота тона (Гц) — нижняя граница боковых частот. */
    const val MIN_TONE_FREQUENCY = 20.0f

    /** Максимальная частота тона (Гц) — верхняя граница боковых частот. */
    const val MAX_TONE_FREQUENCY = 2000.0f

    /** Максимальный модуль частоты биений по умолчанию (Гц). */
    const val MAX_BEAT_MAGNITUDE = 1000.0f

    /**
     * Частота ЛЕВОГО канала: carrier − beat/2.
     * При beat < 0 это БОЛЕЕ высокий тон (каналы поменяны местами).
     */
    fun leftChannelFrequency(carrierFrequency: Float, beatFrequency: Float): Float =
        carrierFrequency - beatFrequency / 2.0f

    /**
     * Частота ПРАВОГО канала: carrier + beat/2.
     * При beat < 0 это БОЛЕЕ низкий тон (каналы поменяны местами).
     */
    fun rightChannelFrequency(carrierFrequency: Float, beatFrequency: Float): Float =
        carrierFrequency + beatFrequency / 2.0f

    /** Модуль частоты биений — слышимая пульсация (знак задаёт раскладку каналов). */
    fun beatMagnitude(beatFrequency: Float): Float = abs(beatFrequency)

    /**
     * Несущая частота по частотам каналов: (left + right) / 2.
     */
    fun carrierFromChannels(leftFrequency: Float, rightFrequency: Float): Float =
        (leftFrequency + rightFrequency) / 2.0f

    /**
     * Частота биений по частотам каналов (как её считает движок): right − left.
     */
    fun beatFromChannels(leftFrequency: Float, rightFrequency: Float): Float =
        rightFrequency - leftFrequency

    /**
     * Максимальный МОДУЛЬ частоты биений для данной несущей.
     *
     * @param carrierRange если задан, боковые частоты дополнительно не должны
     *                     выходить за вертикальные границы графика.
     */
    fun maxBeatMagnitude(carrierFrequency: Float, carrierRange: FrequencyRange? = null): Float {
        val byFloor = 2.0f * (carrierFrequency - MIN_TONE_FREQUENCY)
        val byCeiling = 2.0f * (MAX_TONE_FREQUENCY - carrierFrequency)
        val global = minOf(byFloor, byCeiling)
        val byRange = carrierRange?.let {
            minOf(
                2.0f * (carrierFrequency - it.min),
                2.0f * (it.max - carrierFrequency)
            )
        } ?: Float.POSITIVE_INFINITY
        return minOf(global, byRange).coerceAtLeast(0.0f)
    }

    /**
     * Допустимый интервал частоты биений для точки с данной несущей:
     * физика (боковые в слышимом диапазоне) ∩ границы графика ∩ диапазон кривой.
     *
     * Интервал всегда симметричен относительно нуля по модулю, поэтому
     * отрицательные частоты биений разрешены ровно на тех же условиях, что и
     * положительные той же величины.
     */
    fun beatBounds(
        carrierFrequency: Float,
        beatRange: FrequencyRange,
        carrierRange: FrequencyRange? = null
    ): ClosedFloatingPointRange<Float> {
        val maxMagnitude = maxBeatMagnitude(carrierFrequency, carrierRange)
        val lower = maxOf(beatRange.min, -maxMagnitude)
        val upper = minOf(beatRange.max, maxMagnitude)
        // Защита от вырожденного интервала (несущая вне слышимого диапазона)
        return lower.coerceAtMost(upper)..upper
    }

    /**
     * Привести частоту биений к допустимому интервалу для данной несущей.
     * Знак сохраняется: клампится только модуль.
     */
    fun clampBeat(
        carrierFrequency: Float,
        beatFrequency: Float,
        beatRange: FrequencyRange,
        carrierRange: FrequencyRange? = null
    ): Float = beatFrequency.coerceIn(beatBounds(carrierFrequency, beatRange, carrierRange))

    /**
     * Диапазон частот биений, симметричный относительно нуля.
     *
     * Старые пресеты сохраняли beatRange = (0; 1000) — отрицательные частоты
     * биений в них просто не использовались. Расширение «в зеркало» делает
     * знак доступным независимо от версии пресета и не меняет смысл старых
     * значений (модуль по-прежнему ограничен максимумом).
     */
    fun symmetricBeatRange(range: FrequencyRange): FrequencyRange = FrequencyRange(
        min = minOf(range.min, -range.max),
        max = maxOf(range.max, -range.min)
    )
}
