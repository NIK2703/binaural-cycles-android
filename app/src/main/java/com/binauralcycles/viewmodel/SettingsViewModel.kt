package com.binauralcycles.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.SwapMode
import com.binaural.core.domain.model.VolumeNormalizationSettings
import com.binauralcycles.viewmodel.events.SettingsEvent
import com.binauralcycles.viewmodel.state.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для экрана настроек.
 * Управляет глобальными настройками приложения.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    private val playbackStateRepository: PlaybackStateRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events
    
    // Job для отмены предыдущего перезапуска при быстром переключении
    private var restartJob: kotlinx.coroutines.Job? = null
    
    init {
        loadSettings()
        observePlaybackState()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.getVolume().collect { volume ->
                _uiState.update { it.copy(volume = volume) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getSampleRate().collect { rate ->
                _uiState.update { it.copy(sampleRate = SampleRate.fromValue(rate)) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getChannelSwapSettings().collect { settings ->
                _uiState.update { it.copy(channelSwapSettings = settings) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getVolumeNormalizationSettings().collect { settings ->
                _uiState.update { it.copy(volumeNormalizationSettings = settings) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getResumeOnHeadsetConnect().collect { enabled ->
                _uiState.update { it.copy(resumeOnHeadsetConnect = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getAutoResumeOnAppStart().collect { enabled ->
                _uiState.update { it.copy(autoResumeOnAppStart = enabled) }
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getBufferGenerationMinutes().collect { minutes ->
                _uiState.update { it.copy(bufferGenerationMinutes = minutes) }
            }
        }
    }
    
    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackStateRepository.playbackState.collect { state ->
                _uiState.update { it.copy(isPlaying = state.isPlaying) }
            }
        }
        viewModelScope.launch {
            playbackStateRepository.isConnected.collect { connected ->
                _uiState.update { it.copy(isServiceConnected = connected) }
            }
        }
        viewModelScope.launch {
            playbackController.isChannelsSwapped.collect { swapped ->
                _uiState.update { it.copy(isChannelsSwapped = swapped) }
            }
        }
    }
    
    // ============= Вспомогательные методы =============
    
    private fun restartWithFadeIfNeeded(applyChanges: () -> Unit) {
        restartJob?.cancel()
        
        val state = _uiState.value
        
        if (state.isPlaying && playbackStateRepository.isConnected.value) {
            playbackController.stopWithFade()
            
            restartJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                applyChanges()
                playbackController.play()
            }
        } else {
            applyChanges()
        }
    }
    
    private fun updateGlobalSettings() {
        val state = _uiState.value
        playbackController.updateGlobalSettings(
            channelSwapSettings = state.channelSwapSettings,
            volumeNormalizationSettings = state.volumeNormalizationSettings
        )
    }
    
    /**
     * Универсальный метод для обновления настроек с перезапуском воспроизведения.
     * Устраняет дублирование кода между методами настроек каналов и нормализации.
     */
    private inline fun <T> updateSettingsWithRestart(
        crossinline getCurrent: (SettingsUiState) -> T,
        crossinline transform: (T) -> T,
        crossinline updateState: (SettingsUiState, T) -> SettingsUiState,
        crossinline save: suspend (T) -> Unit
    ) {
        restartWithFadeIfNeeded {
            val currentSettings = getCurrent(_uiState.value)
            val newSettings = transform(currentSettings)
            _uiState.update { updateState(it, newSettings) }
            updateGlobalSettings()
            viewModelScope.launch {
                save(newSettings)
            }
        }
    }
    
    // ============= Методы для управления настройками =============
    
    fun setVolumeImmediate(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackController.setVolume(volume)
    }
    
    fun saveVolume() {
        viewModelScope.launch {
            settingsRepository.saveVolume(_uiState.value.volume)
        }
    }
    
    fun setSampleRate(rate: SampleRate) {
        restartWithFadeIfNeeded {
            _uiState.update { it.copy(sampleRate = rate) }
            playbackController.setSampleRate(rate)
            viewModelScope.launch {
                settingsRepository.saveSampleRate(rate.value)
            }
        }
    }
    
    fun setBufferGenerationMinutes(minutes: Int) {
        val clampedMinutes = minutes.coerceIn(1, 60)
        playbackController.setFrequencyUpdateInterval(clampedMinutes * 60 * 1000)
        viewModelScope.launch {
            settingsRepository.saveBufferGenerationMinutes(clampedMinutes)
        }
    }
    
    // ============= Методы для нормализации громкости =============
    
    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        updateSettingsWithRestart(
            getCurrent = { it.volumeNormalizationSettings },
            transform = { settings ->
                val newType = if (enabled) NormalizationType.CHANNEL else NormalizationType.NONE
                settings.copy(type = newType)
            },
            updateState = { state, settings -> state.copy(volumeNormalizationSettings = settings) },
            save = { settingsRepository.saveVolumeNormalizationSettings(it) }
        )
    }
    
    fun setVolumeNormalizationStrength(strength: Float) {
        updateSettingsWithRestart(
            getCurrent = { it.volumeNormalizationSettings },
            transform = { settings -> settings.copy(strength = strength.coerceIn(0f, 2f)) },
            updateState = { state, settings -> state.copy(volumeNormalizationSettings = settings) },
            save = { settingsRepository.saveVolumeNormalizationSettings(it) }
        )
    }
    
    fun setTemporalNormalizationEnabled(enabled: Boolean) {
        updateSettingsWithRestart(
            getCurrent = { it.volumeNormalizationSettings },
            transform = { settings ->
                val newType = if (enabled) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
                settings.copy(type = newType)
            },
            updateState = { state, settings -> state.copy(volumeNormalizationSettings = settings) },
            save = { settingsRepository.saveVolumeNormalizationSettings(it) }
        )
    }
    
    // ============= Методы для перестановки каналов =============
    
    fun setChannelSwapEnabled(enabled: Boolean) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(enabled = enabled) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setChannelSwapInterval(seconds: Int) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(intervalSeconds = seconds.coerceIn(5, 3600)) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setChannelSwapFadeEnabled(enabled: Boolean) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(fadeEnabled = enabled) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setChannelSwapFadeDuration(ms: Long) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(fadeDurationMs = ms.coerceIn(1000L, 15000L)) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setChannelSwapPauseDuration(ms: Long) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(pauseDurationMs = ms.coerceIn(0L, 60000L)) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setSwapMode(mode: SwapMode) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(swapMode = mode) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    fun setChannelSwapEnabledAndMode(enabled: Boolean, mode: SwapMode) {
        updateSettingsWithRestart(
            getCurrent = { it.channelSwapSettings },
            transform = { it.copy(enabled = enabled, swapMode = mode) },
            updateState = { state, settings -> state.copy(channelSwapSettings = settings) },
            save = { settingsRepository.saveChannelSwapSettings(it) }
        )
    }
    
    // ============= Прочие настройки =============
    
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        playbackController.setResumeOnHeadsetConnect(enabled)
        viewModelScope.launch {
            settingsRepository.saveResumeOnHeadsetConnect(enabled)
        }
    }
    
    fun setAutoResumeOnAppStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveAutoResumeOnAppStart(enabled)
        }
    }
}