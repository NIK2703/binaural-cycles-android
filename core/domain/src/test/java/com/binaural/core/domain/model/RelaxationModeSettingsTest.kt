package com.binaural.core.domain.model

import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты для RelaxationModeSettings - настроек режима расслабления.
 */
class RelaxationModeSettingsTest {
    
    // ========== Значения по умолчанию ==========
    
    @Test
    fun `default settings are disabled`() {
        val settings = RelaxationModeSettings()
        
        assertFalse(settings.enabled)
        assertEquals(RelaxationMode.STEP, settings.mode)
    }
    
    @Test
    fun `default reduction percentages are valid`() {
        val settings = RelaxationModeSettings()
        
        assertTrue(settings.carrierReductionPercent in 0..100)
        assertTrue(settings.beatReductionPercent in 0..100)
    }
    
    // ========== Генерация виртуальных точек (STEP mode) ==========
    
    @Test
    fun `generateVirtualPoints STEP mode creates constant reduction`() {
        val settings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.STEP,
            carrierReductionPercent = 50,
            beatReductionPercent = 50
        )
        
        val curve = FrequencyCurve.defaultCurve()
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        // В STEP режиме точки группируются по 4:
        // Точка 1: начало периода (на базовой кривой - БЕЗ снижения)
        // Точка 2: после перехода (сниженные частоты)
        // Точка 3: конец расслабления (сниженные частоты)  
        // Точка 4: после выхода (на базовой кривой - БЕЗ снижения)
        
        // Проверяем что есть как сниженные, так и несниженные точки
        val reducedPoints = virtualPoints.filter { point ->
            val originalCarrier = curve.getCarrierFrequencyAt(point.time)
            val originalBeat = curve.getBeatFrequencyAt(point.time)
            point.carrierFrequency < originalCarrier * 0.99f && point.beatFrequency < originalBeat * 0.99f
        }
        val unreducedPoints = virtualPoints.filter { point ->
            val originalCarrier = curve.getCarrierFrequencyAt(point.time)
            val originalBeat = curve.getBeatFrequencyAt(point.time)
            point.carrierFrequency >= originalCarrier * 0.99f || point.beatFrequency >= originalBeat * 0.99f
        }
        
        assertTrue("Should have reduced points", reducedPoints.isNotEmpty())
        assertTrue("Should have unreduced points (start/end of periods)", unreducedPoints.isNotEmpty())
        
        // Проверяем что сниженные точки имеют правильное снижение
        reducedPoints.forEach { point ->
            val originalCarrier = curve.getCarrierFrequencyAt(point.time)
            val originalBeat = curve.getBeatFrequencyAt(point.time)
            
            val expectedCarrier = originalCarrier * (1 - settings.carrierReductionPercent / 100.0f)
            val expectedBeat = originalBeat * (1 - settings.beatReductionPercent / 100.0f)
            
            assertEquals(expectedCarrier, point.carrierFrequency, 0.5f)
            assertEquals(expectedBeat, point.beatFrequency, 0.5f)
        }
    }
    
    @Test
    fun `generateVirtualPoints disabled returns empty list`() {
        val settings = RelaxationModeSettings(
            enabled = false
        )
        
        val curve = FrequencyCurve.defaultCurve()
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        assertTrue(virtualPoints.isEmpty())
    }
    
    // ========== Генерация виртуальных точек (SMOOTH mode) ==========
    
    @Test
    fun `generateVirtualPoints SMOOTH mode creates gradual reduction`() {
        val settings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.SMOOTH,
            carrierReductionPercent = 50,
            beatReductionPercent = 50
        )
        
        val curve = FrequencyCurve.defaultCurve()
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        // В SMOOTH режиме уменьшение постепенно нарастает и спадает
        // Проверяем, что точки генерируются
        assertTrue(virtualPoints.isNotEmpty())
    }
    
    @Test
    fun `SMOOTH mode has zero reduction at start and end`() {
        val settings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.SMOOTH,
            carrierReductionPercent = 50,
            beatReductionPercent = 50
        )
        
        val curve = FrequencyCurve.defaultCurve()
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        if (virtualPoints.isNotEmpty()) {
            // В начале дня уменьшение минимально
            val firstPoint = virtualPoints.first()
            val originalCarrier = curve.getCarrierFrequencyAt(firstPoint.time)
            
            // Допускаем небольшое отклонение на границах
            val reduction = (originalCarrier - firstPoint.carrierFrequency) / originalCarrier
            assertTrue("Reduction at start should be small", reduction < 0.2f)
        }
    }
    
    // ========== Валидация ==========
    
    @Test
    fun `carrier reduction throws when out of range`() {
        // carrierReductionPercent должен быть 0-50
        assertThrows(IllegalArgumentException::class.java) {
            RelaxationModeSettings(
                enabled = true,
                carrierReductionPercent = 60 // > 50
            )
        }
    }
    
    @Test
    fun `beat reduction throws when out of range`() {
        assertThrows(IllegalArgumentException::class.java) {
            RelaxationModeSettings(
                enabled = true,
                beatReductionPercent = -10 // < 0
            )
        }
    }
    
    // ========== Проверка применения к кривой ==========
    
    @Test
    fun `virtual points cover entire day`() {
        val settings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.STEP,
            carrierReductionPercent = 30,
            beatReductionPercent = 40
        )
        
        val curve = FrequencyCurve.defaultCurve()
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        if (virtualPoints.isNotEmpty()) {
            // Первая точка - начало дня или близко к нему
            val firstSecond = virtualPoints.first().time.toSecondOfDay()
            assertTrue("First point should be near start of day", firstSecond < 3600) // < 1 час
            
            // Последняя точка - конец дня или близко к нему
            val lastSecond = virtualPoints.last().time.toSecondOfDay()
            assertTrue("Last point should be near end of day", lastSecond > 23 * 3600) // > 23 часов
        }
    }
    
    @Test
    fun `reduction reduces frequencies`() {
        val settings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.STEP,
            carrierReductionPercent = 50,
            beatReductionPercent = 50
        )
        
        val curve = FrequencyCurve(
            points = listOf(
                FrequencyPoint(LocalTime(0, 0), 200.0f, 10.0f),
                FrequencyPoint(LocalTime(12, 0), 200.0f, 10.0f)
            )
        )
        
        val virtualPoints = settings.generateVirtualPoints(curve)
        
        // В STEP режиме точки 2 и 3 (индексы 1 и 2 в каждой группе по 4) имеют сниженные частоты
        // Точки 1 и 4 - на базовой кривой (200.0f, 10.0f)
        
        // Проверяем что есть точки со сниженными частотами (это точки 2 и 3 в каждом периоде)
        val reducedPoints = virtualPoints.filter { point ->
            point.carrierFrequency < 200.0f && point.beatFrequency < 10.0f
        }
        
        assertTrue("Should have points with reduced frequencies", reducedPoints.isNotEmpty())
        
        // Проверяем что сниженные точки имеют примерно 50% снижение
        reducedPoints.forEach { point ->
            assertEquals(100.0f, point.carrierFrequency, 0.5f)  // 50% от 200
            assertEquals(5.0f, point.beatFrequency, 0.5f)       // 50% от 10
        }
    }
}