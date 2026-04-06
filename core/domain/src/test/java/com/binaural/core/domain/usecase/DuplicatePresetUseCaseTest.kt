package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.test.FakePresetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для DuplicatePresetUseCase.
 */
class DuplicatePresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var duplicatePresetUseCase: DuplicatePresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        duplicatePresetUseCase = DuplicatePresetUseCase(presetRepository)
    }
    
    @Test
    fun `duplicate existing preset returns new preset`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        val result = duplicatePresetUseCase("preset-1")
        
        assertNotNull(result)
        assertNotEquals("preset-1", result?.id)
    }
    
    @Test
    fun `duplicate non-existing preset returns null`() = runTest {
        val result = duplicatePresetUseCase("non-existing")
        
        assertNull(result)
    }
    
    @Test
    fun `duplicate adds new preset to repository`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        duplicatePresetUseCase("preset-1")
        
        val presets = presetRepository.getPresets().first()
        assertEquals(2, presets.size)
    }
    
    @Test
    fun `duplicate generates unique name with suffix`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        val result = duplicatePresetUseCase("preset-1")
        
        assertEquals("Test Preset (1)", result?.name)
    }
    
    @Test
    fun `duplicate generates correct suffix for multiple copies`() = runTest {
        val preset1 = createTestPreset("preset-1", "Test Preset")
        val preset2 = createTestPreset("preset-2", "Test Preset (1)")
        presetRepository.addPresetDirectly(preset1)
        presetRepository.addPresetDirectly(preset2)
        
        val result = duplicatePresetUseCase("preset-1")
        
        assertEquals("Test Preset (2)", result?.name)
    }
    
    @Test
    fun `generateUniqueName returns base name if not exists`() = runTest {
        val existingNames = setOf("Other Preset", "Another Preset")
        
        val result = duplicatePresetUseCase.generateUniqueName("Test Preset", existingNames)
        
        assertEquals("Test Preset", result)
    }
    
    @Test
    fun `generateUniqueName adds suffix if name exists`() = runTest {
        val existingNames = setOf("Test Preset", "Other Preset")
        
        val result = duplicatePresetUseCase.generateUniqueName("Test Preset", existingNames)
        
        assertEquals("Test Preset (1)", result)
    }
    
    @Test
    fun `generateUniqueName finds next available number`() = runTest {
        val existingNames = setOf("Test Preset", "Test Preset (1)", "Test Preset (2)")
        
        val result = duplicatePresetUseCase.generateUniqueName("Test Preset", existingNames)
        
        assertEquals("Test Preset (3)", result)
    }
    
    @Test
    fun `generateUniqueName fills gap in numbers`() = runTest {
        val existingNames = setOf("Test Preset", "Test Preset (2)")
        
        val result = duplicatePresetUseCase.generateUniqueName("Test Preset", existingNames)
        
        assertEquals("Test Preset (1)", result)
    }
    
    private fun createTestPreset(id: String, name: String): BinauralPreset {
        return BinauralPreset(
            id = id,
            name = name,
            frequencyCurve = FrequencyCurve.defaultCurve(),
            relaxationModeSettings = RelaxationModeSettings()
        )
    }
}