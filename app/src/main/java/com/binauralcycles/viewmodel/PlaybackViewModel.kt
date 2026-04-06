package com.binauralcycles.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.model.BinauralPreset
import com.binauralcycles.viewmodel.state.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для управления воспроизведением.
 * Используется для bottom panel на всех экранах.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val playbackController: PlaybackController,
    private val playbackStateRepository: PlaybackStateRepository,
    private val presetRepository: PresetRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    
    private var lastActivePresetId: String? = null
    
    init {
        // Подключаемся к сервису
        playbackController.connect()
        
        // Наблюдаем за состоянием подключения
        viewModelScope.launch {
            playbackController.connectionState.collect { connected ->
                _uiState.update { it.copy(isServiceConnected = connected) }
            }
        }
        
        // Устанавливаем callback для переключения пресетов с гарнитуры
        playbackController.setOnPresetSwitchCallback { presetId ->
            // Этот callback будет обрабатываться в PresetListViewModel
        }
        
        // Загружаем начальную громкость из репозитория один раз
        viewModelScope.launch {
            val initialState = playbackStateRepository.playbackState.first()
            _uiState.update { it.copy(volume = initialState.volume) }
        }
        
        // Наблюдаем за состоянием воспроизведения
        observePlaybackState()
        observePlaybackStateFromRepository()
    }
    
    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackController.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            playbackController.currentBeatFrequency.collect { freq ->
                _uiState.update { it.copy(currentBeatFrequency = freq) }
            }
        }
        viewModelScope.launch {
            playbackController.currentCarrierFrequency.collect { freq ->
                _uiState.update { it.copy(currentCarrierFrequency = freq) }
            }
        }
        viewModelScope.launch {
            playbackController.isChannelsSwapped.collect { swapped ->
                _uiState.update { it.copy(isChannelsSwapped = swapped) }
            }
        }
    }
    
    private fun observePlaybackStateFromRepository() {
        // Наблюдаем за изменением активного пресета
        viewModelScope.launch {
            presetRepository.getActivePresetId().collect { presetId ->
                if (presetId != null && presetId != lastActivePresetId) {
                    // Загружаем пресет по ID
                    val presets = presetRepository.getPresets().first()
                    val preset = presets.find { it.id == presetId }
                    if (preset != null) {
                        _uiState.update { it.copy(
                            activePreset = preset,
                            activePresetName = preset.name
                        )}
                        lastActivePresetId = presetId
                    }
                } else if (presetId == null) {
                    _uiState.update { it.copy(
                        activePreset = null,
                        activePresetName = null
                    )}
                    lastActivePresetId = null
                }
            }
        }
        viewModelScope.launch {
            playbackStateRepository.isConnected.collect { connected ->
                _uiState.update { it.copy(isServiceConnected = connected) }
            }
        }
    }
    
    /**
     * Переключение воспроизведения
     */
    fun togglePlayback() {
        val state = _uiState.value
        
        if (state.isPlaying) {
            playbackController.stopWithFade()
        } else {
            if (state.activePreset != null) {
                playbackController.resumeWithFade()
            }
        }
    }
    
    /**
     * Установка громкости (немедленно, без сохранения)
     */
    fun setVolumeImmediate(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackController.setVolume(volume)
    }
    
    /**
     * Обновление активного пресета (вызывается из PresetListViewModel)
     */
    fun updateActivePreset(preset: BinauralPreset?) {
        _uiState.update { it.copy(
            activePreset = preset,
            activePresetName = preset?.name
        )}
        lastActivePresetId = preset?.id
    }
    
    /**
     * Обновление состояния подключения к сервису
     */
    fun updateServiceConnected(connected: Boolean) {
        _uiState.update { it.copy(isServiceConnected = connected) }
    }
    
    override fun onCleared() {
        super.onCleared()
        playbackController.disconnect()
    }
}