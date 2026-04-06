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
 * Тесты для PlayPresetUseCase.
 */
class PlayPresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var playbackStateRepository: FakePlaybackStateRepository
    private lateinit var playbackController: FakePlaybackController
    private lateinit var playPresetUseCase: PlayPresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        playbackStateRepository = FakePlaybackStateRepository()
        playbackController = FakePlaybackController()
        playPresetUseCase = PlayPresetUseCase(
            presetRepository = presetRepository,
            playbackStateRepository = playbackStateRepository,
            playbackController = playbackController
        )
    }
    
    @Test
    fun `play existing preset returns true`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        val result = playPresetUseCase("preset-1")
        
        assertTrue(result)
    }
    
    @Test
    fun `play non-existing preset returns false`() = runTest {
        val result = playPresetUseCase("non-existing")
        
        assertFalse(result)
    }
    
    @Test
    fun `play preset sets active preset id`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        playPresetUseCase("preset-1")
        
        assertEquals("preset-1", presetRepository.getActivePresetId().first())
    }
    
    @Test
    fun `play preset sets preset name in controller`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        playPresetUseCase("preset-1")
        
        assertEquals("Test Preset", playbackController.currentPresetName.value)
    }
    
    @Test
    fun `play preset updates playback state`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        playPresetUseCase("preset-1")
        
        val state = playbackStateRepository.playbackState.value
        assertEquals("preset-1", state.currentPresetId)
        assertEquals("Test Preset", state.currentPresetName)
    }
    
    @Test
    fun `play preset sets preset id in controller`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        playPresetUseCase("preset-1")
        
        assertEquals("preset-1", playbackController.getCurrentPresetId())
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