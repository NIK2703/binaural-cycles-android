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
 * Тесты для StopPlaybackUseCase.
 */
class StopPlaybackUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var playbackStateRepository: FakePlaybackStateRepository
    private lateinit var playbackController: FakePlaybackController
    private lateinit var stopPlaybackUseCase: StopPlaybackUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        playbackStateRepository = FakePlaybackStateRepository()
        playbackController = FakePlaybackController()
        stopPlaybackUseCase = StopPlaybackUseCase(
            playbackController = playbackController,
            presetRepository = presetRepository,
            playbackStateRepository = playbackStateRepository
        )
    }
    
    @Test
    fun `stop playback stops controller`() = runTest {
        playbackController.play()
        assertTrue(playbackController.isPlaying.value)
        
        stopPlaybackUseCase()
        
        assertFalse(playbackController.isPlaying.value)
    }
    
    @Test
    fun `stop playback resets active preset id`() = runTest {
        presetRepository.setActivePresetId("preset-1")
        
        stopPlaybackUseCase()
        
        assertNull(presetRepository.getActivePresetId().first())
    }
    
    @Test
    fun `stop playback resets controller preset id`() = runTest {
        playbackController.setCurrentPresetId("preset-1")
        
        stopPlaybackUseCase()
        
        assertNull(playbackController.getCurrentPresetId())
    }
    
    @Test
    fun `stop playback resets controller preset name`() = runTest {
        playbackController.setCurrentPresetName("Test Preset")
        
        stopPlaybackUseCase()
        
        assertNull(playbackController.currentPresetName.value)
    }
    
    @Test
    fun `stop playback updates playback state`() = runTest {
        playbackStateRepository.updateState { 
            it.copy(currentPresetId = "preset-1", currentPresetName = "Test") 
        }
        
        stopPlaybackUseCase()
        
        val state = playbackStateRepository.playbackState.value
        assertNull(state.currentPresetId)
        assertNull(state.currentPresetName)
    }
}