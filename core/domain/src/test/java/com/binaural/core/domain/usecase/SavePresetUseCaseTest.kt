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
 * Тесты для SavePresetUseCase.
 */
class SavePresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var savePresetUseCase: SavePresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        savePresetUseCase = SavePresetUseCase(presetRepository)
    }
    
    @Test
    fun `invoke creates new preset if not exists`() = runTest {
        val preset = createTestPreset("new-preset", "New Preset")
        
        savePresetUseCase(preset)
        
        val saved = presetRepository.getPresetById("new-preset")
        assertNotNull(saved)
        assertEquals("New Preset", saved?.name)
    }
    
    @Test
    fun `invoke updates existing preset`() = runTest {
        val preset = createTestPreset("preset-1", "Original Name")
        presetRepository.addPresetDirectly(preset)
        
        val updatedPreset = preset.copy(name = "Updated Name")
        savePresetUseCase(updatedPreset)
        
        val saved = presetRepository.getPresetById("preset-1")
        assertEquals("Updated Name", saved?.name)
    }
    
    @Test
    fun `create adds new preset`() = runTest {
        val preset = createTestPreset("new-preset", "New Preset")
        
        savePresetUseCase.create(preset)
        
        val presets = presetRepository.getPresets().first()
        assertEquals(1, presets.size)
        assertEquals("New Preset", presets.first().name)
    }
    
    @Test
    fun `update modifies existing preset`() = runTest {
        val preset = createTestPreset("preset-1", "Original")
        presetRepository.addPresetDirectly(preset)
        
        val updatedPreset = preset.copy(name = "Updated")
        savePresetUseCase.update(updatedPreset)
        
        val saved = presetRepository.getPresetById("preset-1")
        assertEquals("Updated", saved?.name)
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