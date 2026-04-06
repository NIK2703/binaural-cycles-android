package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.PlaybackState
import com.binaural.core.domain.service.AudioEngineInterface
import com.binaural.core.domain.test.FakePlaybackStateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для UpdateFrequenciesUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateFrequenciesUseCaseTest {
    
    private lateinit var playbackStateRepository: FakePlaybackStateRepository
    private lateinit var fakeAudioEngine: FakeAudioEngineInterface
    private lateinit var updateFrequenciesUseCase: UpdateFrequenciesUseCase
    
    @Before
    fun setup() {
        playbackStateRepository = FakePlaybackStateRepository()
        fakeAudioEngine = FakeAudioEngineInterface()
        updateFrequenciesUseCase = UpdateFrequenciesUseCase(playbackStateRepository)
    }
    
    @Test
    fun `initial frequencies are zero`() = runTest {
        assertEquals(0f, updateFrequenciesUseCase.currentBeatFrequency.value, 0.001f)
        assertEquals(0f, updateFrequenciesUseCase.currentCarrierFrequency.value, 0.001f)
    }
    
    @Test
    fun `setAudioEngine stores reference`() = runTest {
        updateFrequenciesUseCase.setAudioEngine(fakeAudioEngine)
        // Проверяем косвенно через updateFrequencies
        playbackStateRepository.setInitialState(PlaybackState(isPlaying = true))
        fakeAudioEngine.setFrequencies(10f, 200f)
        
        updateFrequenciesUseCase.updateFrequencies()
        
        assertEquals(10f, updateFrequenciesUseCase.currentBeatFrequency.value, 0.001f)
        assertEquals(200f, updateFrequenciesUseCase.currentCarrierFrequency.value, 0.001f)
    }
    
    @Test
    fun `updateFrequencies does nothing when not playing`() = runTest {
        updateFrequenciesUseCase.setAudioEngine(fakeAudioEngine)
        playbackStateRepository.setInitialState(PlaybackState(isPlaying = false))
        fakeAudioEngine.setFrequencies(10f, 200f)
        
        updateFrequenciesUseCase.updateFrequencies()
        
        assertEquals(0f, updateFrequenciesUseCase.currentBeatFrequency.value, 0.001f)
        assertEquals(0f, updateFrequenciesUseCase.currentCarrierFrequency.value, 0.001f)
    }
    
    @Test
    fun `updateFrequencies updates state when playing`() = runTest {
        updateFrequenciesUseCase.setAudioEngine(fakeAudioEngine)
        playbackStateRepository.setInitialState(PlaybackState(isPlaying = true))
        fakeAudioEngine.setFrequencies(15f, 300f)
        
        updateFrequenciesUseCase.updateFrequencies()
        
        val state = playbackStateRepository.playbackState.value
        assertEquals(15f, state.currentBeatFrequency, 0.001f)
        assertEquals(300f, state.currentCarrierFrequency, 0.001f)
    }
    
    @Test
    fun `updateFrequencies calls onFrequencyUpdate callback`() = runTest {
        updateFrequenciesUseCase.setAudioEngine(fakeAudioEngine)
        playbackStateRepository.setInitialState(PlaybackState(isPlaying = true))
        fakeAudioEngine.setFrequencies(10f, 200f)
        
        var callbackCalled = false
        updateFrequenciesUseCase.onFrequencyUpdate = {
            callbackCalled = true
        }
        
        updateFrequenciesUseCase.updateFrequencies()
        
        assertTrue(callbackCalled)
    }
    
    @Test
    fun `stopAll stops all updates`() = runTest {
        updateFrequenciesUseCase.setAudioEngine(fakeAudioEngine)
        playbackStateRepository.setInitialState(PlaybackState(isPlaying = true))
        
        updateFrequenciesUseCase.startUiFrequencyUpdate()
        updateFrequenciesUseCase.startNotificationUpdate()
        
        updateFrequenciesUseCase.stopAll()
        
        // Проверяем, что_jobs отменены (косвенно)
        // После stopAll частоты не должны обновляться
        fakeAudioEngine.setFrequencies(20f, 400f)
        advanceTimeBy(2000)
        
        // Частоты должны остаться прежними
        assertEquals(0f, updateFrequenciesUseCase.currentBeatFrequency.value, 0.001f)
    }
}

/**
 * Fake реализация AudioEngineInterface для тестов.
 */
class FakeAudioEngineInterface : AudioEngineInterface {
    private val _currentBeatFrequency = MutableStateFlow(0f)
    private val _currentCarrierFrequency = MutableStateFlow(0f)
    
    override val currentBeatFrequency = _currentBeatFrequency
    override val currentCarrierFrequency = _currentCarrierFrequency
    
    fun setFrequencies(beat: Float, carrier: Float) {
        _currentBeatFrequency.value = beat
        _currentCarrierFrequency.value = carrier
    }
    
    override fun updateCurrentFrequencies() {
        // Ничего не делаем в fake
    }
}