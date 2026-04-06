package com.binaural.core.domain.model

import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import kotlinx.datetime.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты для FrequencyCurve - кривой зависимости частот от времени.
 */
class FrequencyCurveTest {
    
    // ========== Создание кривой ==========
    
    @Test(expected = IllegalArgumentException::class)
    fun `curve requires at least 2 points`() {
        FrequencyCurve(
            points = listOf(
                FrequencyPoint(LocalTime(12, 0), 200.0f, 10.0f)
            )
        )
    }
    
    @Test
    fun `default curve is valid`() {
        val curve = FrequencyCurve.defaultCurve()
        
        assertTrue(curve.points.size >= 2)
        assertNotNull(curve.carrierRange)
        assertNotNull(curve.beatRange)
    }
    
    @Test
    fun `curve handles unsorted points for interpolation`() {
        // Передаём точки в несортированном порядке
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f),
                FrequencyPoint.fromHours(6, 0, 180.0f, 8.0f),
                FrequencyPoint.fromHours(18, 0, 220.0f, 12.0f)
            )
        )
        
        // Интерполяция должна работать корректно независимо от порядка входных точек
        // Проверяем интерполяцию в 6:00 (первая точка по времени)
        val freq6 = curve.getCarrierFrequencyAt(LocalTime(6, 0))
        assertEquals(180.0f, freq6, 0.001f)
        
        // Проверяем интерполяцию в 12:00 (вторая точка по времени)
        val freq12 = curve.getCarrierFrequencyAt(LocalTime(12, 0))
        assertEquals(200.0f, freq12, 0.001f)
        
        // Проверяем интерполяцию в 18:00 (третья точка по времени)
        val freq18 = curve.getCarrierFrequencyAt(LocalTime(18, 0))
        assertEquals(220.0f, freq18, 0.001f)
    }
    
    // ========== Интерполяция частот ==========
    
    @Test
    fun `getCarrierFrequencyAt returns exact point value`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 100.0f, 5.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            )
        )
        
        val freq = curve.getCarrierFrequencyAt(LocalTime(0, 0))
        assertEquals(100.0f, freq, 0.001f)
    }
    
    @Test
    fun `getBeatFrequencyAt returns exact point value`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 100.0f, 5.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            )
        )
        
        val freq = curve.getBeatFrequencyAt(LocalTime(12, 0))
        assertEquals(10.0f, freq, 0.001f)
    }
    
    @Test
    fun `interpolation between two points is monotone for monotone type`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 100.0f, 5.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            ),
            interpolationType = InterpolationType.MONOTONE
        )
        
        // Проверяем интерполяцию в середине
        val midFreq = curve.getCarrierFrequencyAt(LocalTime(6, 0))
        
        // Значение должно быть между 100 и 200
        assertTrue(midFreq >= 100.0f)
        assertTrue(midFreq <= 200.0f)
    }
    
    @Test
    fun `interpolation with linear type is exact at midpoint`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 100.0f, 5.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            ),
            interpolationType = InterpolationType.LINEAR
        )
        
        val midFreq = curve.getCarrierFrequencyAt(LocalTime(6, 0))
        assertEquals(150.0f, midFreq, 0.001f)
    }
    
    // ========== Переход через полночь ==========
    
    @Test
    fun `interpolation wraps around midnight`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(22, 0, 100.0f, 5.0f),  // 22:00
                FrequencyPoint.fromHours(2, 0, 200.0f, 10.0f)   // 02:00 следующего дня
            ),
            interpolationType = InterpolationType.LINEAR
        )
        
        // Время 00:00 (полночь) - между 22:00 и 02:00
        val midnight = LocalTime(0, 0)
        val freq = curve.getCarrierFrequencyAt(midnight)
        
        // Должно быть интерполировано между 100 и 200
        // 22:00 -> 02:00 = 4 часа
        // 22:00 -> 00:00 = 2 часа = половина пути
        assertEquals(150.0f, freq, 0.1f)
    }
    
    @Test
    fun `get frequency at midnight with curve spanning full day`() {
        val curve = FrequencyCurve.defaultCurve()
        
        // Не должно выбрасывать исключение
        val carrier = curve.getCarrierFrequencyAt(LocalTime(0, 0))
        val beat = curve.getBeatFrequencyAt(LocalTime(0, 0))
        
        assertTrue(carrier >= 0)
        assertTrue(beat >= 0)
    }
    
    // ========== Канальные частоты ==========
    
    @Test
    fun `getUpperChannelFrequencyAt returns correct value`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 200.0f, 10.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            ),
            interpolationType = InterpolationType.LINEAR
        )
        
        // carrier + beat/2 = 200 + 10/2 = 205
        val upperFreq = curve.getUpperChannelFrequencyAt(LocalTime(6, 0))
        assertEquals(205.0f, upperFreq, 0.1f)
    }
    
    @Test
    fun `getLowerChannelFrequencyAt returns correct value`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 200.0f, 10.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 10.0f)
            ),
            interpolationType = InterpolationType.LINEAR
        )
        
        // carrier - beat/2 = 200 - 10/2 = 195
        val lowerFreq = curve.getLowerChannelFrequencyAt(LocalTime(6, 0))
        assertEquals(195.0f, lowerFreq, 0.1f)
    }
    
    @Test
    fun `channel frequencies are symmetric around carrier`() {
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint.fromHours(0, 0, 200.0f, 20.0f),
                FrequencyPoint.fromHours(12, 0, 200.0f, 20.0f)
            ),
            interpolationType = InterpolationType.LINEAR
        )
        
        val time = LocalTime(6, 0)
        val upper = curve.getUpperChannelFrequencyAt(time)
        val lower = curve.getLowerChannelFrequencyAt(time)
        val carrier = curve.getCarrierFrequencyAt(time)
        
        // Среднее верхней и нижней должно равняться несущей
        assertEquals(carrier, (upper + lower) / 2, 0.1f)
    }
    
    // ========== FrequencyRange ==========
    
    @Test
    fun `FrequencyRange contains value within bounds`() {
        val range = FrequencyRange(100.0f, 200.0f)
        
        assertTrue(range.contains(150.0f))
        assertTrue(range.contains(100.0f))
        assertTrue(range.contains(200.0f))
        assertFalse(range.contains(50.0f))
        assertFalse(range.contains(250.0f))
    }
    
    @Test
    fun `FrequencyRange clamp returns value within bounds`() {
        val range = FrequencyRange(100.0f, 200.0f)
        
        assertEquals(150.0f, range.clamp(150.0f), 0.001f)
        assertEquals(100.0f, range.clamp(50.0f), 0.001f)
        assertEquals(200.0f, range.clamp(250.0f), 0.001f)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun `FrequencyRange requires max greater than min`() {
        FrequencyRange(200.0f, 100.0f)
    }
    
    // ========== getInterpolatedValues ==========
    
    @Test
    fun `getInterpolatedValues returns correct number of samples`() {
        val curve = FrequencyCurve.defaultCurve()
        
        val values = curve.getInterpolatedValues(numSamples = 50) { it.carrierFrequency }
        
        assertEquals(51, values.size) // 0..50 включительно
    }
    
    @Test
    fun `getInterpolatedValues spans full day`() {
        val curve = FrequencyCurve.defaultCurve()
        
        val values = curve.getInterpolatedValues(numSamples = 10) { it.carrierFrequency }
        
        // Первый элемент - начало дня
        assertEquals(0, values.first().first)
        
        // Последний элемент - конец дня (23:59:59)
        assertEquals(86399, values.last().first)
    }
}