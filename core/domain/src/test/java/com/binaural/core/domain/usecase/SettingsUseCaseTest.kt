package com.binaural.core.domain.usecase

import com.binaural.core.domain.test.FakePlaybackController
import com.binaural.core.domain.test.FakeSettingsRepository
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.SwapMode
import com.binaural.core.domain.model.VolumeNormalizationSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для SettingsUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUseCaseTest {
    
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var playbackController: FakePlaybackController
    private lateinit var settingsUseCase: SettingsUseCase
    
    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        playbackController = FakePlaybackController()
        settingsUseCase = SettingsUseCase(settingsRepository, playbackController)
    }
    
    // ========== Громкость ==========
    
    @Test
    fun `getVolume returns stored value`() = runTest {
        val volume = settingsUseCase.getVolume().first()
        assertEquals(0.7f, volume, 0.001f) // default value
    }
    
    @Test
    fun `setVolume updates repository and controller`() = runTest {
        settingsUseCase.setVolume(0.5f)
        
        assertEquals(0.5f, settingsRepository.getVolume().first(), 0.001f)
        assertEquals(0.5f, playbackController.getCurrentVolume(), 0.001f)
    }
    
    @Test
    fun `setVolume clamps to valid range`() = runTest {
        settingsUseCase.setVolume(-0.5f)
        assertEquals(0f, settingsRepository.getVolume().first(), 0.001f)
        
        settingsUseCase.setVolume(1.5f)
        assertEquals(1f, settingsRepository.getVolume().first(), 0.001f)
    }
    
    // ========== Частота дискретизации ==========
    
    @Test
    fun `getSampleRate returns stored value`() = runTest {
        val sampleRate = settingsUseCase.getSampleRate().first()
        assertEquals(SampleRate.MEDIUM.value, sampleRate) // default
    }
    
    @Test
    fun `setSampleRate updates repository and controller`() = runTest {
        settingsUseCase.setSampleRate(SampleRate.HIGH)
        
        assertEquals(SampleRate.HIGH.value, settingsRepository.getSampleRate().first())
        assertEquals(SampleRate.HIGH, playbackController.getSampleRate())
    }
    
    // ========== Интервал генерации буфера ==========
    
    @Test
    fun `getBufferGenerationMinutes returns stored value`() = runTest {
        val minutes = settingsUseCase.getBufferGenerationMinutes().first()
        assertEquals(10, minutes) // default
    }
    
    @Test
    fun `setBufferGenerationMinutes updates repository and controller`() = runTest {
        settingsUseCase.setBufferGenerationMinutes(30)
        
        assertEquals(30, settingsRepository.getBufferGenerationMinutes().first())
        // 30 minutes * 60 seconds * 1000 ms = 1800000 ms
        assertEquals(1800000, playbackController.getFrequencyUpdateInterval())
    }
    
    @Test
    fun `setBufferGenerationMinutes clamps to valid range`() = runTest {
        settingsUseCase.setBufferGenerationMinutes(0)
        assertEquals(1, settingsRepository.getBufferGenerationMinutes().first())
        
        settingsUseCase.setBufferGenerationMinutes(100)
        assertEquals(60, settingsRepository.getBufferGenerationMinutes().first())
    }
    
    // ========== Перестановка каналов ==========
    
    @Test
    fun `getChannelSwapSettings returns stored value`() = runTest {
        val settings = settingsUseCase.getChannelSwapSettings().first()
        assertFalse(settings.enabled) // default
    }
    
    @Test
    fun `setChannelSwapEnabled updates settings`() = runTest {
        settingsUseCase.setChannelSwapEnabled(true)
        
        val settings = settingsRepository.getChannelSwapSettings().first()
        assertTrue(settings.enabled)
    }
    
    @Test
    fun `setChannelSwapInterval updates settings`() = runTest {
        settingsUseCase.setChannelSwapInterval(600)
        
        val settings = settingsRepository.getChannelSwapSettings().first()
        assertEquals(600, settings.intervalSeconds)
    }
    
    @Test
    fun `setSwapMode updates settings`() = runTest {
        settingsUseCase.setSwapMode(SwapMode.TENDENCY)
        
        val settings = settingsRepository.getChannelSwapSettings().first()
        assertEquals(SwapMode.TENDENCY, settings.swapMode)
    }
    
    
    // ========== Нормализация громкости ==========
    
    @Test
    fun `getVolumeNormalizationSettings returns stored value`() = runTest {
        val settings = settingsUseCase.getVolumeNormalizationSettings().first()
        assertEquals(NormalizationType.TEMPORAL, settings.type) // default
    }
    
    @Test
    fun `setVolumeNormalizationEnabled updates type`() = runTest {
        settingsUseCase.setVolumeNormalizationEnabled(true)
        
        val settings = settingsRepository.getVolumeNormalizationSettings().first()
        assertEquals(NormalizationType.CHANNEL, settings.type)
        
        settingsUseCase.setVolumeNormalizationEnabled(false)
        
        val updatedSettings = settingsRepository.getVolumeNormalizationSettings().first()
        assertEquals(NormalizationType.NONE, updatedSettings.type)
    }
    
    @Test
    fun `setVolumeNormalizationStrength updates and clamps value`() = runTest {
        settingsUseCase.setVolumeNormalizationStrength(1.5f)
        
        var settings = settingsRepository.getVolumeNormalizationSettings().first()
        assertEquals(1.5f, settings.strength, 0.001f)
        
        // Test clamping
        settingsUseCase.setVolumeNormalizationStrength(-0.5f)
        settings = settingsRepository.getVolumeNormalizationSettings().first()
        assertEquals(0f, settings.strength, 0.001f)
        
        settingsUseCase.setVolumeNormalizationStrength(3.0f)
        settings = settingsRepository.getVolumeNormalizationSettings().first()
        assertEquals(2f, settings.strength, 0.001f)
    }
    
    // ========== Автовозобновление ==========
    
    @Test
    fun `getResumeOnHeadsetConnect returns stored value`() = runTest {
        val enabled = settingsUseCase.getResumeOnHeadsetConnect().first()
        assertFalse(enabled) // default
    }
    
    @Test
    fun `setResumeOnHeadsetConnect updates repository and controller`() = runTest {
        settingsUseCase.setResumeOnHeadsetConnect(true)
        
        assertTrue(settingsRepository.getResumeOnHeadsetConnect().first())
        assertTrue(playbackController.isResumeOnHeadsetConnectEnabled())
    }
    
    @Test
    fun `getAutoResumeOnAppStart returns stored value`() = runTest {
        val enabled = settingsUseCase.getAutoResumeOnAppStart().first()
        assertFalse(enabled) // default
    }
    
    @Test
    fun `setAutoResumeOnAppStart updates repository`() = runTest {
        settingsUseCase.setAutoResumeOnAppStart(true)
        assertTrue(settingsRepository.getAutoResumeOnAppStart().first())
    }
    
    // ========== График ==========
    
    @Test
    fun `getAutoExpandGraphRange returns stored value`() = runTest {
        val enabled = settingsUseCase.getAutoExpandGraphRange().first()
        assertFalse(enabled) // default
    }
    
    @Test
    fun `setAutoExpandGraphRange updates repository`() = runTest {
        settingsUseCase.setAutoExpandGraphRange(true)
        assertTrue(settingsRepository.getAutoExpandGraphRange().first())
    }
}