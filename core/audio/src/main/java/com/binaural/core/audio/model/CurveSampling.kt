package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import kotlin.math.abs

/**
 * Сэмплирование интерполированной кривой по времени суток.
 *
 * Экстремумы считаются по СЭМПЛАМ кривой, а не по узлам: при CARDINAL сплайн
 * даёт overshoot — реальная кривая выходит за пределы узлов, и «максимум по
 * точкам» занизил бы потолок (или завысил бы минимум).
 *
 * Один проход обслуживает сразу две фичи режима расслабления, поэтому
 * возвращается ПАРА экстремумов:
 * - угасание по диапазону использует только потолок (вес `|beat| / beatTop`,
 *   см. [RelaxationModeSettings.fadeByRangePosition]);
 * - ограничитель территории (`docs/design_relaxation_beat_gate.md`) использует
 *   оба: порог `mMin + p·(mMax − mMin)`.
 *
 * Точность здесь вторична: эти величины задают УСИЛИЕ, а не границу
 * (недолёт потолка лишь насыщает несколько точек на весе 1 — кламп это
 * исправляет сам). Поэтому бисекция и уточнение границы не нужны, в отличие
 * от поиска границ территории.
 */
object CurveSampling {

    /** Число сэмплов по умолчанию: 720 × 120 с = ровно сутки. */
    const val DEFAULT_SAMPLE_COUNT = 720

    private const val DAY_SECONDS = 24 * 3600

    /**
     * Минимум и максимум модуля частоты биений на кривой.
     *
     * Мера — МОДУЛЬ: знак частоты биений задаёт раскладку каналов, а
     * «уровень возбуждения» определяется скоростью пульсации (правило R2 из
     * `docs/design_relaxation_beat_gate.md`).
     *
     * Кривая periodicчна по времени суток, поэтому сэмпл в 86 400 с тождествен
     * сэмплу в 0 с и отдельно не берётся: `i` идёт до `sampleCount − 1`, а шаг
     * дробный — так любое [sampleCount] покрывает сутки равномерно.
     *
     * @return Pair(минимум `|beat|`, максимум `|beat|`)
     */
    fun beatMagnitudeExtremes(
        curve: FrequencyCurve,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): Pair<Float, Float> {
        require(sampleCount > 0) { "Число сэмплов должно быть положительным" }

        val step = DAY_SECONDS.toDouble() / sampleCount
        var min = Float.POSITIVE_INFINITY
        var max = 0.0f

        for (i in 0 until sampleCount) {
            val second = (i * step).toInt().coerceIn(0, DAY_SECONDS - 1)
            val magnitude = abs(curve.getBeatFrequencyAt(LocalTime.fromSecondOfDay(second)))
            if (magnitude < min) min = magnitude
            if (magnitude > max) max = magnitude
        }

        // Пустая выборка невозможна (sampleCount > 0), но кривая из NaN дала бы
        // бесконечность — страховка, чтобы вес не уехал в бесконечность.
        return min.coerceAtLeast(0.0f) to max.coerceAtLeast(0.0f)
    }

    /**
     * Потолок модуля частоты биений — опора угасания периодов расслабления.
     *
     * Возвращает 0, только если биения тождественно равны нулю на всей кривой;
     * в этом случае снижать нечего и вес безразличен (правило R4).
     */
    fun beatMagnitudeCeiling(
        curve: FrequencyCurve,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): Float = beatMagnitudeExtremes(curve, sampleCount).second

    /**
     * Минимум и максимум несущей частоты на кривой.
     *
     * Используется опорой угасания периодов расслабления: вес несущей
     * нормируется на РЕАЛЬНЫЙ размах кривой (`carrierMinReal … carrierMaxReal`),
     * а не на объявленный `carrierRange` графика. Иначе при `carrierRange.min`
     * ниже реального минимума кривой («с запасом», как обычно и ставится ось)
     * низ кривой давал бы частичное, а не нулевое снижение — период
     * расслабления не доходил бы до нуля у дна.
     *
     * Сэмплирование — как у [beatMagnitudeExtremes]: при CARDINAL-сплайне
     * реальный экстремум может быть между узлами (overshoot).
     *
     * @return Pair(минимум несущей, максимум несущей)
     */
    fun carrierMagnitudeExtremes(
        curve: FrequencyCurve,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT
    ): Pair<Float, Float> {
        require(sampleCount > 0) { "Число сэмплов должно быть положительным" }

        val step = DAY_SECONDS.toDouble() / sampleCount
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY

        for (i in 0 until sampleCount) {
            val second = (i * step).toInt().coerceIn(0, DAY_SECONDS - 1)
            val carrier = curve.getCarrierFrequencyAt(LocalTime.fromSecondOfDay(second))
            if (carrier < min) min = carrier
            if (carrier > max) max = carrier
        }

        // Страховка от NaN в кривой: несущая всегда положительна.
        return min.coerceAtLeast(0.0f) to max.coerceAtLeast(0.0f)
    }
}
