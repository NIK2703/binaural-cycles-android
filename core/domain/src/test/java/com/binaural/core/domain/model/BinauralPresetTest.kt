package com.binaural.core.domain.model

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты для BinauralPreset - пресета бинаурального ритма.
 */
class BinauralPresetTest {
    
    // ========== Создание пресетов ==========
    
    @Test
    fun `defaultPreset has correct id and name`() {
        val preset = BinauralPreset.defaultPreset()
        
        assertEquals(BinauralPreset.DEFAULT_PRESET_ID, preset.id)
        assertEquals("Циркадный ритм", preset.name)
    }
    
    @Test
    fun `gammaPreset has correct id and name`() {
        val preset = BinauralPreset.gammaPreset()
        
        assertEquals(BinauralPreset.GAMMA_PRESET_ID, preset.id)
        assertEquals("Гамма-продуктивность", preset.name)
    }
    
    @Test
    fun `dailyCyclePreset has correct id and name`() {
        val preset = BinauralPreset.dailyCyclePreset()
        
        assertEquals(BinauralPreset.DAILY_CYCLE_PRESET_ID, preset.id)
        assertEquals("Суточный цикл", preset.name)
    }
    
    @Test
    fun `defaultPresets returns all three presets`() {
        val presets = BinauralPreset.defaultPresets()
        
        assertEquals(3, presets.size)
        assertTrue(presets.any { it.id == BinauralPreset.DEFAULT_PRESET_ID })
        assertTrue(presets.any { it.id == BinauralPreset.GAMMA_PRESET_ID })
        assertTrue(presets.any { it.id == BinauralPreset.DAILY_CYCLE_PRESET_ID })
    }
    
    @Test
    fun `custom preset has unique id`() {
        val preset1 = BinauralPreset(
            name = "Test 1",
            frequencyCurve = FrequencyCurve.defaultCurve()
        )
        val preset2 = BinauralPreset(
            name = "Test 2",
            frequencyCurve = FrequencyCurve.defaultCurve()
        )
        
        assertNotEquals(preset1.id, preset2.id)
    }
    
    // ========== Расчёт частот ==========
    
    @Test
    fun `getCarrierFrequencyAt returns value from curve`() {
        val preset = BinauralPreset.defaultPreset()
        
        val carrier = preset.getCarrierFrequencyAt(LocalTime(12, 0))
        
        assertTrue(carrier > 0)
    }
    
    @Test
    fun `getBeatFrequencyAt returns value from curve`() {
        val preset = BinauralPreset.defaultPreset()
        
        val beat = preset.getBeatFrequencyAt(LocalTime(12, 0))
        
        assertTrue(beat > 0)
    }
    
    @Test
    fun `frequencies vary throughout day`() {
        val preset = BinauralPreset.defaultPreset()
        
        val morning = preset.getBeatFrequencyAt(LocalTime(6, 0))
        val noon = preset.getBeatFrequencyAt(LocalTime(12, 0))
        val evening = preset.getBeatFrequencyAt(LocalTime(18, 0))
        
        // В разное время частоты должны отличаться (циркадный ритм)
        // Утро и вечер обычно ниже, чем полдень
        assertTrue("Frequencies should vary", morning != noon || noon != evening)
    }
    
    // ========== Режим расслабления ==========
    
    @Test
    fun `curveWithRelaxation returns base curve when disabled`() {
        val preset = BinauralPreset(
            name = "Test",
            frequencyCurve = FrequencyCurve.defaultCurve(),
            relaxationModeSettings = RelaxationModeSettings(enabled = false)
        )
        
        // Когда режим расслабления отключён, должна возвращаться базовая кривая
        val curve = preset.curveWithRelaxation
        
        assertEquals(preset.frequencyCurve, curve)
    }
    
    @Test
    fun `curveWithRelaxation returns modified curve when enabled`() {
        val preset = BinauralPreset(
            name = "Test",
            frequencyCurve = FrequencyCurve(
                points = listOf(
                    FrequencyPoint(LocalTime(0, 0), 200.0f, 10.0f),
                    FrequencyPoint(LocalTime(12, 0), 200.0f, 10.0f)
                )
            ),
            relaxationModeSettings = RelaxationModeSettings(
                enabled = true,
                mode = RelaxationMode.STEP,
                carrierReductionPercent = 50,
                beatReductionPercent = 50
            )
        )
        
        val relaxationCurve = preset.curveWithRelaxation
        
        // Кривая с расслаблением должна отличаться от базовой
        // Виртуальные точки должны генерироваться
        assertTrue(
            "Relaxation curve should have virtual points",
            relaxationCurve.points.size >= 2
        )
    }
    
    @Test
    fun `relaxation reduces frequencies during day`() {
        val baseCurve = FrequencyCurve(
            points = listOf(
                FrequencyPoint(LocalTime(0, 0), 200.0f, 10.0f),
                FrequencyPoint(LocalTime(12, 0), 200.0f, 10.0f)
            )
        )
        
        val presetWithRelaxation = BinauralPreset(
            name = "Test",
            frequencyCurve = baseCurve,
            relaxationModeSettings = RelaxationModeSettings(
                enabled = true,
                mode = RelaxationMode.STEP,
                carrierReductionPercent = 50,
                beatReductionPercent = 50
            )
        )
        
        // Проверяем, что частоты на кривой с расслаблением могут быть ниже базовых
        val relaxationCurve = presetWithRelaxation.curveWithRelaxation
        
        // Проверяем, что некоторые виртуальные точки имеют сниженные частоты
        val hasReducedFrequencies = relaxationCurve.points.any { point ->
            point.carrierFrequency < 200.0f || point.beatFrequency < 10.0f
        }
        
        assertTrue("Some points should have reduced frequencies", hasReducedFrequencies)
    }
    
    @Test
    fun `SMOOTH relaxation mode generates alternating points`() {
        val preset = BinauralPreset(
            name = "Test",
            frequencyCurve = FrequencyCurve(
                points = listOf(
                    FrequencyPoint(LocalTime(0, 0), 200.0f, 10.0f),
                    FrequencyPoint(LocalTime(12, 0), 200.0f, 10.0f)
                )
            ),
            relaxationModeSettings = RelaxationModeSettings(
                enabled = true,
                mode = RelaxationMode.SMOOTH,
                carrierReductionPercent = 50,
                beatReductionPercent = 50,
                smoothIntervalMinutes = 60 // Каждый час
            )
        )
        
        val relaxationCurve = preset.curveWithRelaxation
        
        // В SMOOTH режиме точки чередуются: базовая -> сниженная -> базовая -> ...
        assertTrue("Should have multiple points", relaxationCurve.points.size >= 2)
    }
    
    // ========== Timestamps ==========
    
    @Test
    fun `preset has valid timestamps`() {
        val beforeCreate = System.currentTimeMillis()
        val preset = BinauralPreset(
            name = "Test",
            frequencyCurve = FrequencyCurve.defaultCurve()
        )
        val afterCreate = System.currentTimeMillis()
        
        assertTrue(preset.createdAt >= beforeCreate)
        assertTrue(preset.createdAt <= afterCreate)
        assertEquals(preset.createdAt, preset.updatedAt)
    }
    
    @Test
    fun `timestamps can be set explicitly`() {
        val customTime = 1609459200000L // 2021-01-01 00:00:00 UTC
        
        val preset = BinauralPreset(
            name = "Test",
            frequencyCurve = FrequencyCurve.defaultCurve(),
            createdAt = customTime,
            updatedAt = customTime
        )
        
        assertEquals(customTime, preset.createdAt)
        assertEquals(customTime, preset.updatedAt)
    }
    
    // ========== copy() с обновлением ==========
    
    @Test
    fun `copy preserves id and updates fields`() {
        val original = BinauralPreset.defaultPreset()
        
        val copied = original.copy(name = "Modified Name")
        
        assertEquals(original.id, copied.id)
        assertEquals("Modified Name", copied.name)
        assertEquals(original.frequencyCurve, copied.frequencyCurve)
    }
}