package com.binaural.core.domain.usecase

import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.test.FakePlaybackController
import com.binaural.core.domain.test.FakePresetRepository
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для PresetUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var playbackController: FakePlaybackController
    private lateinit var presetUseCase: PresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        playbackController = FakePlaybackController()
        presetUseCase = PresetUseCase(presetRepository, playbackController)
    }
    
    @Test
    fun `getPresets returns empty list initially`() = runTest {
        val presets = presetUseCase.getPresets().first()
        assertTrue(presets.isEmpty())
    }
    
    @Test
    fun `createPreset adds preset to repository`() = runTest {
        val name = "Новый пресет"
        val curve = FrequencyCurve.defaultCurve()
        
        val createdPreset = presetUseCase.createPreset(name, curve)
        
        assertEquals(name, createdPreset.name)
        assertEquals(curve, createdPreset.frequencyCurve)
        
        val presets = presetUseCase.getPresets().first()
        assertEquals(1, presets.size)
        assertEquals(createdPreset, presets.first())
    }
    
    @Test
    fun `createPreset with custom relaxation settings`() = runTest {
        val relaxationSettings = RelaxationModeSettings(
            enabled = true,
            mode = RelaxationMode.SMOOTH,
            carrierReductionPercent = 30,
            beatReductionPercent = 60
        )
        
        val createdPreset = presetUseCase.createPreset(
            name = "Relaxation Preset",
            frequencyCurve = FrequencyCurve.defaultCurve(),
            relaxationModeSettings = relaxationSettings
        )
        
        assertTrue(createdPreset.relaxationModeSettings.enabled)
        assertEquals(30, createdPreset.relaxationModeSettings.carrierReductionPercent)
        assertEquals(60, createdPreset.relaxationModeSettings.beatReductionPercent)
    }
    
    @Test
    fun `updatePreset updates existing preset`() = runTest {
        val created = presetUseCase.createPreset("Original", FrequencyCurve.defaultCurve())
        
        val updated = created.copy(name = "Updated Name")
        presetUseCase.updatePreset(updated)
        
        val presets = presetUseCase.getPresets().first()
        assertEquals(1, presets.size)
        assertEquals("Updated Name", presets.first().name)
    }
    
    @Test
    fun `deletePreset removes preset from repository`() = runTest {
        val created = presetUseCase.createPreset("To Delete", FrequencyCurve.defaultCurve())
        assertEquals(1, presetUseCase.getPresets().first().size)
        
        presetUseCase.deletePreset(created.id)
        
        assertTrue(presetUseCase.getPresets().first().isEmpty())
    }
    
    @Test
    fun `getPresetById returns correct preset`() = runTest {
        val preset1 = presetUseCase.createPreset("Preset 1", FrequencyCurve.defaultCurve())
        val preset2 = presetUseCase.createPreset("Preset 2", FrequencyCurve.defaultCurve())
        
        val found = presetUseCase.getPresetById(preset1.id)
        
        assertNotNull(found)
        assertEquals(preset1.id, found?.id)
        assertEquals("Preset 1", found?.name)
    }
    
    @Test
    fun `getPresetById returns null for non-existent id`() = runTest {
        val found = presetUseCase.getPresetById("non-existent-id")
        assertNull(found)
    }
    
    @Test
    fun `setActivePreset updates repository and controller`() = runTest {
        val preset = presetUseCase.createPreset("Active", FrequencyCurve.defaultCurve())
        val config = BinauralConfig(frequencyCurve = preset.frequencyCurve)
        
        presetUseCase.setActivePreset(preset, config)
        
        assertEquals(preset.id, presetRepository.getActivePresetId().first())
        assertEquals(preset.name, playbackController.currentPresetName.value)
        assertEquals(preset.id, playbackController.getCurrentPresetId())
    }
    
    @Test
    fun `clearActivePreset clears repository and controller`() = runTest {
        val preset = presetUseCase.createPreset("Active", FrequencyCurve.defaultCurve())
        val config = BinauralConfig(frequencyCurve = preset.frequencyCurve)
        presetUseCase.setActivePreset(preset, config)
        
        presetUseCase.clearActivePreset()
        
        assertNull(presetRepository.getActivePresetId().first())
        assertNull(playbackController.currentPresetName.value)
        assertNull(playbackController.getCurrentPresetId())
    }
    
    @Test
    fun `duplicatePreset creates copy with unique name`() = runTest {
        val original = presetUseCase.createPreset("Original", FrequencyCurve.defaultCurve())
        
        val duplicated = presetUseCase.duplicatePreset(original.id)
        
        assertNotNull(duplicated)
        assertNotEquals(original.id, duplicated?.id)
        assertEquals("Original (1)", duplicated?.name)
        assertEquals(original.frequencyCurve, duplicated?.frequencyCurve)
        assertEquals(2, presetUseCase.getPresets().first().size)
    }
    
    @Test
    fun `duplicatePreset with existing name generates correct number`() = runTest {
        val original = presetUseCase.createPreset("Test", FrequencyCurve.defaultCurve())
        presetUseCase.duplicatePreset(original.id) // "Test (1)"
        
        val secondDuplicate = presetUseCase.duplicatePreset(original.id)
        
        assertEquals("Test (2)", secondDuplicate?.name)
    }
    
    @Test
    fun `duplicatePreset returns null for non-existent id`() = runTest {
        val result = presetUseCase.duplicatePreset("non-existent-id")
        assertNull(result)
    }
    
    @Test
    fun `updatePresetIdsForPlayback updates controller`() = runTest {
        presetUseCase.createPreset("Preset 1", FrequencyCurve.defaultCurve())
        presetUseCase.createPreset("Preset 2", FrequencyCurve.defaultCurve())
        presetUseCase.createPreset("Preset 3", FrequencyCurve.defaultCurve())
        
        presetUseCase.updatePresetIdsForPlayback()
        
        assertEquals(3, playbackController.getPresetIds().size)
    }
}