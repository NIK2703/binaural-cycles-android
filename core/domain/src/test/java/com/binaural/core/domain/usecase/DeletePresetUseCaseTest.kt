package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.test.FakePlaybackController
import com.binaural.core.domain.test.FakePlaybackStateRepository
import com.binaural.core.domain.test.FakePresetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для DeletePresetUseCase.
 */
class DeletePresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var playbackStateRepository: FakePlaybackStateRepository
    private lateinit var playbackController: FakePlaybackController
    private lateinit var deletePresetUseCase: DeletePresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        playbackStateRepository = FakePlaybackStateRepository()
        playbackController = FakePlaybackController()
        deletePresetUseCase = DeletePresetUseCase(
            presetRepository = presetRepository,
            playbackStateRepository = playbackStateRepository,
            playbackController = playbackController
        )
    }
    
    @Test
    fun `delete existing preset returns preset name`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        val result = deletePresetUseCase("preset-1")
        
        assertEquals("Test Preset", result)
    }
    
    @Test
    fun `delete non-existing preset returns null`() = runTest {
        val result = deletePresetUseCase("non-existing")
        
        assertNull(result)
    }
    
    @Test
    fun `delete preset removes it from repository`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        deletePresetUseCase("preset-1")
        
        assertNull(presetRepository.getPresetById("preset-1"))
    }
    
    @Test
    fun `delete active preset stops playback`() = runTest {
        val preset = createTestPreset("preset-1", "Active Preset")
        presetRepository.addPresetDirectly(preset)
        presetRepository.setActivePresetId("preset-1")
        playbackController.play()
        
        deletePresetUseCase("preset-1")
        
        assertFalse(playbackController.isPlaying.value)
    }
    
    @Test
    fun `delete active preset resets active preset id`() = runTest {
        val preset = createTestPreset("preset-1", "Active Preset")
        presetRepository.addPresetDirectly(preset)
        presetRepository.setActivePresetId("preset-1")
        
        deletePresetUseCase("preset-1")
        
        assertNull(presetRepository.getActivePresetId().first())
    }
    
    @Test
    fun `delete non-active preset does not stop playback`() = runTest {
        val activePreset = createTestPreset("active", "Active")
        val otherPreset = createTestPreset("other", "Other")
        presetRepository.addPresetDirectly(activePreset)
        presetRepository.addPresetDirectly(otherPreset)
        presetRepository.setActivePresetId("active")
        playbackController.play()
        
        deletePresetUseCase("other")
        
        assertTrue(playbackController.isPlaying.value)
        assertEquals("active", presetRepository.getActivePresetId().first())
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