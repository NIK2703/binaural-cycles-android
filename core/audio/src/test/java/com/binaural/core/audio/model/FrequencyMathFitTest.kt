package com.binaural.core.audio.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Подгонка пары «несущая + биения» под границы ([FrequencyMath.fitBeatWithCarrierShift]).
 *
 * Правило, которое здесь проверяется: пока желаемая пульсация не выше
 * ПОТОЛКА (ширины диапазона), она применяется целиком, а от границы
 * отодвигается НЕСУЩАЯ — ровно на столько, на сколько канал за границу вылез.
 * Режется пульсация только в одном случае: несущая уже стоит посередине
 * диапазона, каналы занимают его целиком, и двигать её больше некуда.
 *
 * Обозначения в тестах: carrierRange = 100…600 Гц, потолок = 500 Гц,
 * середина диапазона = 350 Гц.
 */
class FrequencyMathFitTest {

    private val range = FrequencyRange(100.0f, 600.0f)
    private val ceiling = 500.0f
    private val middle = 350.0f

    /** Каналы при получившихся несущей и биениях: нижний, верхний. */
    private fun channels(fit: FrequencyMath.CarrierBeatFit): Pair<Float, Float> {
        val magnitude = FrequencyMath.beatMagnitude(fit.beatFrequency)
        return (fit.carrierFrequency - magnitude / 2.0f) to
            (fit.carrierFrequency + magnitude / 2.0f)
    }

    @Test
    fun ceiling_equalsRangeWidth() {
        assertEquals(ceiling, FrequencyMath.maxFittableBeatMagnitude(range), EPS)
        // Диапазон шире слышимого — потолок режет физика, а не границы графика.
        assertEquals(
            FrequencyMath.MAX_TONE_FREQUENCY - FrequencyMath.MIN_TONE_FREQUENCY,
            FrequencyMath.maxFittableBeatMagnitude(FrequencyRange(10.0f, 3000.0f)),
            EPS
        )
    }

    @Test
    fun fitsAsIs_carrierStays() {
        val fit = FrequencyMath.fitBeatWithCarrierShift(350.0f, 200.0f, range)
        assertEquals(200.0f, fit.beatFrequency, EPS)
        assertEquals(350.0f, fit.carrierFrequency, EPS)
    }

    @Test
    fun fitsExactlyOnBoundary_carrierStays() {
        // Каналы ровно 100 и 140 — нижний стоит на границе, сдвиг не нужен.
        val fit = FrequencyMath.fitBeatWithCarrierShift(120.0f, 40.0f, range)
        assertEquals(40.0f, fit.beatFrequency, EPS)
        assertEquals(120.0f, fit.carrierFrequency, EPS)
    }

    @Test
    fun lowerChannelCrossesMin_carrierPushedUp() {
        // Хотелось 200 Гц при несущей 150: нижний канал уходил на 50 Гц.
        // Пульсация сохранена целиком, несущая отодвинута на 200 — ровно
        // настолько, чтобы нижний канал сел на границу 100 Гц.
        val fit = FrequencyMath.fitBeatWithCarrierShift(150.0f, 200.0f, range)
        assertEquals(200.0f, fit.beatFrequency, EPS)
        assertEquals(200.0f, fit.carrierFrequency, EPS)
        assertEquals(100.0f, channels(fit).first, EPS)
    }

    @Test
    fun upperChannelCrossesMax_carrierPushedDown() {
        val fit = FrequencyMath.fitBeatWithCarrierShift(550.0f, 200.0f, range)
        assertEquals(200.0f, fit.beatFrequency, EPS)
        assertEquals(500.0f, fit.carrierFrequency, EPS)
        assertEquals(600.0f, channels(fit).second, EPS)
    }

    @Test
    fun shiftIsMinimal_notJumpToMiddle() {
        // Каналы 100 и 200 помещаются и так — несущая не должна уезжать
        // в середину диапазона только из-за того, что пульсация выросла.
        val fit = FrequencyMath.fitBeatWithCarrierShift(150.0f, 100.0f, range)
        assertEquals(100.0f, fit.beatFrequency, EPS)
        assertEquals(150.0f, fit.carrierFrequency, EPS)
    }

    @Test
    fun aboveCeiling_beatClampedAndCarrierMovesToMiddle() {
        val fit = FrequencyMath.fitBeatWithCarrierShift(150.0f, 900.0f, range)
        assertEquals(ceiling, fit.beatFrequency, EPS)
        assertEquals(middle, fit.carrierFrequency, EPS)
        // Каналы занимают диапазон целиком.
        assertEquals(100.0f, channels(fit).first, EPS)
        assertEquals(600.0f, channels(fit).second, EPS)

        // Несущая уже в середине — больше двигать некуда, режется пульсация.
        val fromMiddle = FrequencyMath.fitBeatWithCarrierShift(middle, 900.0f, range)
        assertEquals(ceiling, fromMiddle.beatFrequency, EPS)
        assertEquals(middle, fromMiddle.carrierFrequency, EPS)
    }

    @Test
    fun signPreserved_layoutKept() {
        // Знак — раскладка каналов, он не имеет отношения к границам.
        val fit = FrequencyMath.fitBeatWithCarrierShift(150.0f, -200.0f, range)
        assertEquals(-200.0f, fit.beatFrequency, EPS)
        assertEquals(200.0f, fit.carrierFrequency, EPS)

        val aboveCeiling = FrequencyMath.fitBeatWithCarrierShift(150.0f, -900.0f, range)
        assertEquals(-ceiling, aboveCeiling.beatFrequency, EPS)
        assertEquals(middle, aboveCeiling.carrierFrequency, EPS)
    }

    @Test
    fun zeroBeat_pullsCarrierIntoRange() {
        // Несущая вне диапазона (старый пресет, суженные границы): при нулевой
        // пульсации коридор несущих — весь диапазон, точка втягивается в него.
        val fit = FrequencyMath.fitBeatWithCarrierShift(800.0f, 0.0f, range)
        assertEquals(0.0f, fit.beatFrequency, EPS)
        assertEquals(600.0f, fit.carrierFrequency, EPS)
    }

    @Test
    fun physicalBoundsStrongerThanGraphRange() {
        // Границы графика шире слышимого: канал не имеет права уйти ниже 20 Гц,
        // поэтому несущая отодвигается от 20 Гц, а не от минимума графика.
        val wide = FrequencyRange(10.0f, 3000.0f)
        val fit = FrequencyMath.fitBeatWithCarrierShift(100.0f, 200.0f, wide)
        assertEquals(200.0f, fit.beatFrequency, EPS)
        assertEquals(120.0f, fit.carrierFrequency, EPS)
        assertEquals(FrequencyMath.MIN_TONE_FREQUENCY, channels(fit).first, EPS)
    }

    @Test
    fun channelsAlwaysInsideBounds() {
        // Инвариант по всей сетке: каналы внутри границ, пульсация не выше
        // потолка, знак сохранён.
        val carriers = listOf(20.0f, 100.0f, 150.0f, 350.0f, 550.0f, 600.0f, 2000.0f)
        val beats = listOf(-2000.0f, -500.0f, -100.0f, 0.0f, 100.0f, 500.0f, 2000.0f)
        carriers.forEach { carrier ->
            beats.forEach { beat ->
                val fit = FrequencyMath.fitBeatWithCarrierShift(carrier, beat, range)
                val (lower, upper) = channels(fit)
                assertTrue2(lower >= range.min - EPS, "$beat @ $carrier -> lower $lower")
                assertTrue2(upper <= range.max + EPS, "$beat @ $carrier -> upper $upper")
                assertTrue2(
                    FrequencyMath.beatMagnitude(fit.beatFrequency) <= ceiling + EPS,
                    "$beat @ $carrier -> beat ${fit.beatFrequency}"
                )
                assertTrue2(
                    FrequencyMath.beatMagnitude(fit.beatFrequency) <=
                        FrequencyMath.beatMagnitude(beat) + EPS,
                    "$beat @ $carrier -> beat grew"
                )
                if (beat != 0.0f) {
                    assertEquals(
                        "знак должен сохраниться",
                        if (beat < 0.0f) -1.0f else 1.0f,
                        if (fit.beatFrequency < 0.0f) -1.0f else 1.0f,
                        EPS
                    )
                }
            }
        }
    }

    private fun assertTrue2(condition: Boolean, message: String) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private companion object {
        const val EPS = 0.01f
    }
}
