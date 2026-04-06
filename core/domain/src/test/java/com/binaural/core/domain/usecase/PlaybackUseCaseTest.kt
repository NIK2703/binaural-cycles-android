package com.binaural.core.domain.usecase

import com.binaural.core.domain.test.FakePlaybackController
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для PlaybackUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackUseCaseTest {
    
    private lateinit var playbackController: FakePlaybackController
    private lateinit var playbackUseCase: PlaybackUseCase
    
    @Before
    fun setup() {
        playbackController = FakePlaybackController()
        playbackUseCase = PlaybackUseCase(playbackController)
    }
    
    @Test
    fun `isPlaying returns controller state`() = runTest {
        assertFalse(playbackUseCase.isPlaying.first())
        
        playbackController.play()
        assertTrue(playbackUseCase.isPlaying.first())
        
        playbackController.stop()
        assertFalse(playbackUseCase.isPlaying.first())
    }
    
    @Test
    fun `play starts playback`() = runTest {
        playbackUseCase.play()
        assertTrue(playbackController.isPlaying.first())
    }
    
    @Test
    fun `stop stops playback`() = runTest {
        playbackController.play()
        playbackUseCase.stop()
        assertFalse(playbackController.isPlaying.first())
    }
    
    @Test
    fun `togglePlayback toggles state`() = runTest {
        assertFalse(playbackController.isPlaying.first())
        
        playbackUseCase.togglePlayback()
        assertTrue(playbackController.isPlaying.first())
        
        playbackUseCase.togglePlayback()
        assertFalse(playbackController.isPlaying.first())
    }
    
    @Test
    fun `updateConfig updates controller config`() = runTest {
        val config = BinauralConfig(
            frequencyCurve = FrequencyCurve.defaultCurve(),
            volume = 0.8f
        )
        val relaxationSettings = RelaxationModeSettings(enabled = true)
        
        playbackUseCase.updateConfig(config, relaxationSettings)
        
        assertEquals(config, playbackController.getCurrentConfig())
        assertEquals(relaxationSettings, playbackController.getCurrentRelaxationSettings())
    }
    
    @Test
    fun `setVolume updates controller volume`() = runTest {
        playbackUseCase.setVolume(0.5f)
        assertEquals(0.5f, playbackController.getCurrentVolume(), 0.001f)
    }
    
    @Test
    fun `setSampleRate updates controller sample rate`() = runTest {
        val sampleRate = SampleRate.HIGH
        
        playbackUseCase.setSampleRate(sampleRate)
        
        assertEquals(sampleRate, playbackController.getSampleRate())
    }
    
    @Test
    fun `setCurrentPresetName updates controller`() = runTest {
        playbackUseCase.setCurrentPresetName("Test Preset")
        assertEquals("Test Preset", playbackController.currentPresetName.first())
        
        playbackUseCase.setCurrentPresetName(null)
        assertNull(playbackController.currentPresetName.first())
    }
    
    @Test
    fun `setResumeOnHeadsetConnect updates controller`() = runTest {
        playbackUseCase.setResumeOnHeadsetConnect(true)
        assertTrue(playbackController.isResumeOnHeadsetConnectEnabled())
        
        playbackUseCase.setResumeOnHeadsetConnect(false)
        assertFalse(playbackController.isResumeOnHeadsetConnectEnabled())
    }
    
    @Test
    fun `onAppForeground calls controller`() = runTest {
        // Просто проверяем, что метод не выбрасывает исключение
        playbackUseCase.onAppForeground()
    }
    
    @Test
    fun `onAppBackground calls controller`() = runTest {
        // Просто проверяем, что метод не выбрасывает исключение
        playbackUseCase.onAppBackground()
    }
}