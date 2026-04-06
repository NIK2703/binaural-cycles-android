package com.binauralcycles.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.usecase.DeletePresetUseCase
import com.binaural.core.domain.usecase.DuplicatePresetUseCase
import com.binaural.core.domain.usecase.ExportPresetUseCase
import com.binaural.core.domain.usecase.ImportPresetUseCase
import com.binaural.core.domain.service.FileStorageService
import com.binauralcycles.viewmodel.events.PresetListEvent
import com.binauralcycles.viewmodel.state.UiState
import com.binauralcycles.viewmodel.state.PresetListUiState
import com.binauralcycles.viewmodel.state.EditingState
import com.binauralcycles.viewmodel.state.EditingStateRepository
import com.binaural.core.domain.model.RelaxationModeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel для экрана списка пресетов.
 * Управляет списком пресетов, воспроизведением и экспортом/импортом.
 */
@HiltViewModel
class PresetListViewModel @Inject constructor(
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    private val playbackStateRepository: PlaybackStateRepository,
    private val deletePresetUseCase: DeletePresetUseCase,
    private val duplicatePresetUseCase: DuplicatePresetUseCase,
    private val exportPresetUseCase: ExportPresetUseCase,
    private val importPresetUseCase: ImportPresetUseCase,
    private val fileStorageService: FileStorageService,
    private val editingStateRepository: EditingStateRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PresetListUiState())
    val uiState: StateFlow<PresetListUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<PresetListEvent>(Channel.BUFFERED)
    val events = _events
    
    private var lastActivePresetId: String? = null
    private var autoResumeHandled = false
    
    // Job для отмены предыдущего перезапуска при быстром переключении
    private var restartJob: kotlinx.coroutines.Job? = null
    
    init {
        // Подключаемся к сервису
        playbackController.connect()
        
        // Наблюдаем за состоянием подключения
        viewModelScope.launch {
            playbackController.connectionState.collect { connected ->
                _uiState.update { it.copy(isServiceConnected = connected) }
                
                if (connected) {
                    // При подключении сервиса обновляем конфиг
                    val state = _uiState.value
                    if (state.activePreset != null) {
                        playbackController.setCurrentPresetName(state.activePreset.name)
                        playbackController.setCurrentPresetId(state.activePreset.id)
                    }
                    updateAudioConfig()
                    
                    // Устанавливаем настройки
                    val volume = settingsRepository.getVolume().first()
                    playbackController.setVolume(volume)
                    
                    val presetIds = presetRepository.getPresets().first().map { it.id }
                    playbackController.setPresetIds(presetIds)
                    
                    // Пробуем автовозобновление
                    tryAutoResumeOnAppStart()
                }
            }
        }
        
        // Устанавливаем callback для переключения пресетов с гарнитуры
        playbackController.setOnPresetSwitchCallback { presetId ->
            playPreset(presetId)
        }
        
        // Загружаем данные
        loadPresets()
        loadSettings()
        observePlaybackState()
    }
    
    private fun loadPresets() {
        viewModelScope.launch {
            presetRepository.getPresets().collect { presetsList ->
                _uiState.update { it.copy(
                    presetsState = UiState.Success(presetsList)
                )}
            }
        }
        
        // Загружаем активный пресет
        viewModelScope.launch {
            presetRepository.getActivePresetId().collect { activeId ->
                lastActivePresetId = activeId
                activeId?.let { id ->
                    val presets = _uiState.value.presets
                    val preset = presets.find { it.id == id }
                    if (preset != null) {
                        _uiState.update { it.copy(activePreset = preset) }
                        playbackStateRepository.updateState { it.copy(currentPresetId = preset.id, currentPresetName = preset.name) }
                        updateAudioConfig()
                        playbackController.setCurrentPresetName(preset.name)
                        playbackController.setCurrentPresetId(id)
                    }
                }
            }
        }
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.getVolume().collect { volume ->
                // Volume сохраняется в SettingsViewModel
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getChannelSwapSettings().collect { settings ->
                updateAudioConfig()
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getVolumeNormalizationSettings().collect { settings ->
                updateAudioConfig()
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getAutoResumeOnAppStart().collect { enabled ->
                // Обновляем настройку автовозобновления
            }
        }
        
        viewModelScope.launch {
            settingsRepository.getResumeOnHeadsetConnect().collect { enabled ->
                playbackController.setResumeOnHeadsetConnect(enabled)
            }
        }
    }
    
    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackController.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
                playbackStateRepository.updateState { it.copy(isPlaying = playing) }
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
                playbackStateRepository.updateState { it.copy(isChannelsSwapped = swapped) }
            }
        }
        // Наблюдаем за состоянием редактирования для анимации перехода
        viewModelScope.launch {
            editingStateRepository.editingState.collect { editingState ->
                _uiState.update { 
                    it.copy(
                        editingFrequencyCurve = editingState.editingFrequencyCurve,
                        editingPresetId = editingState.editingPresetId,
                        editingPresetName = editingState.editingPresetName
                    )
                }
            }
        }
    }
    
    // ============= Методы для работы с пресетами =============
    
    fun playPreset(presetId: String) {
        val presets = _uiState.value.presets
        val preset = presets.find { it.id == presetId } ?: return
        val state = _uiState.value
        
        if (state.activePreset?.id == presetId && state.isPlaying) {
            playbackController.stopWithFade()
            return
        }
        
        _uiState.update { it.copy(activePreset = preset) }
        playbackStateRepository.updateState { it.copy(currentPresetId = preset.id, currentPresetName = preset.name) }
        
        playbackController.setCurrentPresetName(preset.name)
        playbackController.setCurrentPresetId(presetId)
        
        // Получаем текущие настройки для конфига
        viewModelScope.launch {
            val channelSwapSettings = settingsRepository.getChannelSwapSettings().first()
            val volumeNormalizationSettings = settingsRepository.getVolumeNormalizationSettings().first()
            val volume = settingsRepository.getVolume().first()
            
            val config = BinauralConfig(
                frequencyCurve = preset.frequencyCurve,
                volume = volume,
                channelSwapEnabled = channelSwapSettings.enabled,
                channelSwapIntervalSeconds = channelSwapSettings.intervalSeconds,
                channelSwapFadeEnabled = channelSwapSettings.fadeEnabled,
                channelSwapFadeDurationMs = channelSwapSettings.fadeDurationMs,
                channelSwapPauseDurationMs = channelSwapSettings.pauseDurationMs,
                normalizationType = volumeNormalizationSettings.type,
                volumeNormalizationStrength = volumeNormalizationSettings.strength,
                channelSwapMode = channelSwapSettings.swapMode,
                invertTendencyBehavior = channelSwapSettings.invertTendencyBehavior
            )
            
            val relaxationSettings = preset.relaxationModeSettings
            
            if (state.isPlaying) {
                playbackController.stopWithFade()
                kotlinx.coroutines.delay(300)
                playbackController.updateConfig(config, relaxationSettings)
                playbackController.play()
            } else {
                playbackController.updateConfig(config, relaxationSettings)
                playbackController.play()
            }
        }
        
        lastActivePresetId = presetId
        viewModelScope.launch {
            presetRepository.setActivePresetId(presetId)
        }
    }
    
    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            val presetName = deletePresetUseCase(presetId)
            if (presetName != null) {
                if (_uiState.value.activePreset?.id == presetId) {
                    _uiState.update { it.copy(activePreset = null) }
                    lastActivePresetId = null
                }
                _events.send(PresetListEvent.PresetDeleted(presetName))
            }
        }
    }
    
    fun duplicatePreset(presetId: String) {
        viewModelScope.launch {
            val duplicatedPreset = duplicatePresetUseCase(presetId)
            if (duplicatedPreset != null) {
                _events.send(PresetListEvent.ShowDuplicateSuccess(duplicatedPreset.name))
            }
        }
    }
    
    // ============= Экспорт/импорт пресетов =============
    
    fun exportPresetToJson(presetId: String): String? {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return null
        return fileStorageService.exportToJson(preset)
    }
    
    fun getPresetForExport(presetId: String): BinauralPreset? {
        return _uiState.value.presets.find { it.id == presetId }
    }
    
    fun importPresetFromJson(jsonString: String): String? {
        return try {
            viewModelScope.launch {
                val importedPreset = importPresetUseCase(jsonString)
                if (importedPreset != null) {
                    _events.send(PresetListEvent.ShowImportSuccess(importedPreset.name))
                } else {
                    _events.send(PresetListEvent.ShowError("Failed to import preset"))
                }
            }
            // Возвращаем временный ID, реальный будет установлен после импорта
            "imported"
        } catch (e: Exception) {
            android.util.Log.e("PresetListViewModel", "Failed to import preset", e)
            viewModelScope.launch {
                _events.send(PresetListEvent.ShowError("Failed to import preset"))
            }
            null
        }
    }
    
    fun importPresetFromUri(uri: Uri): String? {
        return try {
            viewModelScope.launch {
                val importedPreset = importPresetUseCase.fromUri(uri.toString())
                if (importedPreset != null) {
                    _events.send(PresetListEvent.ShowImportSuccess(importedPreset.name))
                } else {
                    _events.send(PresetListEvent.ShowError("Failed to import preset from file"))
                }
            }
            "imported"
        } catch (e: Exception) {
            android.util.Log.e("PresetListViewModel", "Failed to import preset from uri", e)
            viewModelScope.launch {
                _events.send(PresetListEvent.ShowError("Failed to import preset from file"))
            }
            null
        }
    }
    
    private fun updateAudioConfig() {
        val state = _uiState.value
        val activePreset = state.activePreset ?: return
        
        viewModelScope.launch {
            val channelSwapSettings = settingsRepository.getChannelSwapSettings().first()
            val volumeNormalizationSettings = settingsRepository.getVolumeNormalizationSettings().first()
            val volume = settingsRepository.getVolume().first()
            
            val config = BinauralConfig(
                frequencyCurve = activePreset.frequencyCurve,
                volume = volume,
                channelSwapEnabled = channelSwapSettings.enabled,
                channelSwapIntervalSeconds = channelSwapSettings.intervalSeconds,
                channelSwapFadeEnabled = channelSwapSettings.fadeEnabled,
                channelSwapFadeDurationMs = channelSwapSettings.fadeDurationMs,
                channelSwapPauseDurationMs = channelSwapSettings.pauseDurationMs,
                normalizationType = volumeNormalizationSettings.type,
                volumeNormalizationStrength = volumeNormalizationSettings.strength,
                channelSwapMode = channelSwapSettings.swapMode,
                invertTendencyBehavior = channelSwapSettings.invertTendencyBehavior
            )
            
            playbackController.updateConfig(config, activePreset.relaxationModeSettings)
        }
    }
    
    private fun tryAutoResumeOnAppStart() {
        val state = _uiState.value
        
        viewModelScope.launch {
            val autoResume = settingsRepository.getAutoResumeOnAppStart().first()
            
            if (autoResume &&
                state.activePreset != null &&
                state.isServiceConnected &&
                !state.isPlaying &&
                !autoResumeHandled) {
                
                autoResumeHandled = true
                android.util.Log.d("PresetListViewModel", "Auto-resuming playback on app start")
                
                playbackController.resumeWithFade()
            }
        }
    }
    
    // Навигация
    
    /**
     * Подготовить данные для редактирования пресета ДО навигации.
     * Это нужно для корректной работы анимации - данные должны быть доступны синхронно.
     */
    fun prepareEditingPreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val isActivePreset = _uiState.value.activePreset?.id == presetId
        
        editingStateRepository.setEditingState(
            EditingState(
                editingPresetId = presetId,
                editingPresetName = preset.name,
                editingFrequencyCurve = preset.frequencyCurve,
                editingRelaxationModeSettings = preset.relaxationModeSettings,
                isActivePreset = isActivePreset
            )
        )
    }
    
    /**
     * Подготовить данные для создания нового пресета ДО навигации.
     */
    fun prepareNewPreset() {
        editingStateRepository.setEditingState(
            EditingState(
                editingPresetId = null,
                editingPresetName = "",
                editingFrequencyCurve = null,
                editingRelaxationModeSettings = RelaxationModeSettings(),
                isActivePreset = false
            )
        )
    }
    
    fun navigateToEdit(presetId: String) {
        viewModelScope.launch {
            _events.send(PresetListEvent.NavigateToEdit(presetId))
        }
    }
    
    fun navigateToNewPreset() {
        viewModelScope.launch {
            _events.send(PresetListEvent.NavigateToNewPreset)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        playbackController.disconnect()
    }
}