package com.binauralcycles.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.usecase.SavePresetUseCase
import com.binauralcycles.viewmodel.events.PresetEditEvent
import com.binauralcycles.viewmodel.state.PresetEditUiState
import com.binauralcycles.viewmodel.state.EditingStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import javax.inject.Inject

/**
 * ViewModel для экрана редактирования пресета.
 * Управляет редактированием кривой частот и настроек расслабления.
 */
@HiltViewModel
class PresetEditViewModel @Inject constructor(
    private val presetRepository: PresetRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    private val playbackStateRepository: PlaybackStateRepository,
    private val savePresetUseCase: SavePresetUseCase,
    private val editingStateRepository: EditingStateRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PresetEditUiState())
    val uiState: StateFlow<PresetEditUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<PresetEditEvent>(Channel.BUFFERED)
    val events = _events
    
    companion object {
        private const val MIN_AUDIBLE_FREQUENCY = 20.0f
        private const val MAX_FREQUENCY = 2000.0f
        
        private fun adjustBeatForCarrier(carrier: Float, currentBeat: Float): Float {
            val maxBeatForLower = ((carrier - MIN_AUDIBLE_FREQUENCY) * 2).coerceAtLeast(0.0f)
            val maxBeatForUpper = ((MAX_FREQUENCY - carrier) * 2).coerceAtLeast(0.0f)
            val maxBeat = minOf(maxBeatForLower, maxBeatForUpper)
            return currentBeat.coerceAtMost(maxBeat)
        }
        
        private fun adjustBeatForCarrierWithRange(carrier: Float, currentBeat: Float, carrierRange: FrequencyRange): Float {
            val maxBeatForGlobalLower = ((carrier - MIN_AUDIBLE_FREQUENCY) * 2).coerceAtLeast(0.0f)
            val maxBeatForGlobalUpper = ((MAX_FREQUENCY - carrier) * 2).coerceAtLeast(0.0f)
            val maxBeatForRangeLower = ((carrier - carrierRange.min) * 2).coerceAtLeast(0.0f)
            val maxBeatForRangeUpper = ((carrierRange.max - carrier) * 2).coerceAtLeast(0.0f)
            val maxBeat = minOf(maxBeatForGlobalLower, maxBeatForGlobalUpper, maxBeatForRangeLower, maxBeatForRangeUpper)
            return currentBeat.coerceAtMost(maxBeat)
        }
    }
    
    init {
        observePlaybackState()
    }
    
    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackStateRepository.playbackState.collect { state ->
                _uiState.update { 
                    it.copy(
                        isPlaying = state.isPlaying,
                        currentCarrierFrequency = state.currentCarrierFrequency,
                        currentBeatFrequency = state.currentBeatFrequency
                    )
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
     * Начать редактирование существующего пресета.
     * Данные получаются синхронно из EditingStateRepository, 
     * куда они были записаны ДО навигации для корректной работы анимации.
     */
    fun startEditingPreset(presetId: String) {
        // Получаем состояние синхронно из репозитория (уже подготовлено до навигации)
        val editingState = editingStateRepository.getEditingState()
        
        if (editingState.editingPresetId == presetId && editingState.editingFrequencyCurve != null) {
            // Данные уже есть в репозитории - используем их
            _uiState.update { 
                it.copy(
                    editingFrequencyCurve = editingState.editingFrequencyCurve,
                    editingPresetId = presetId,
                    editingPresetName = editingState.editingPresetName,
                    originalPreset = null, // Будет загружен асинхронно
                    carrierRange = editingState.editingFrequencyCurve.carrierRange,
                    beatRange = editingState.editingFrequencyCurve.beatRange,
                    selectedPointIndex = null,
                    editingRelaxationModeSettings = editingState.editingRelaxationModeSettings,
                    isActivePreset = editingState.isActivePreset,
                    isLoading = false
                )
            }
            
            if (editingState.isActivePreset) {
                playbackController.updateFrequencyCurve(editingState.editingFrequencyCurve)
            }
            
            // Асинхронно загружаем оригинальный пресет для сравнения изменений
            viewModelScope.launch {
                val originalPreset = presetRepository.getPresets().first().find { it.id == presetId }
                _uiState.update { it.copy(originalPreset = originalPreset) }
            }
        } else {
            // Fallback: загружаем асинхронно (для обратной совместимости)
            viewModelScope.launch {
                val preset = presetRepository.getPresets().first().find { it.id == presetId } ?: return@launch
                val activePresetId = playbackStateRepository.playbackState.value.currentPresetId
                val isActivePreset = activePresetId == presetId
                
                _uiState.update { 
                    it.copy(
                        editingFrequencyCurve = preset.frequencyCurve,
                        editingPresetId = presetId,
                        editingPresetName = preset.name,
                        originalPreset = preset,
                        carrierRange = preset.frequencyCurve.carrierRange,
                        beatRange = preset.frequencyCurve.beatRange,
                        selectedPointIndex = null,
                        editingRelaxationModeSettings = preset.relaxationModeSettings,
                        isActivePreset = isActivePreset,
                        isLoading = false
                    )
                }
                
                if (isActivePreset) {
                    playbackController.updateFrequencyCurve(preset.frequencyCurve)
                }
                
                saveEditingStateToRepository()
            }
        }
    }
    
    /**
     * Начать создание нового пресета.
     * Данные получаются синхронно из EditingStateRepository,
     * куда они были записаны ДО навигации для корректной работы анимации.
     */
    fun startNewPreset() {
        // Получаем состояние синхронно из репозитория (уже подготовлено до навигации)
        val editingState = editingStateRepository.getEditingState()
        
        if (editingState.editingPresetId == null) {
            // Для нового пресета создаём кривую по умолчанию
            val defaultCurve = editingState.editingFrequencyCurve ?: FrequencyCurve.defaultCurve()
            _uiState.update { 
                it.copy(
                    editingFrequencyCurve = defaultCurve,
                    editingPresetId = null,
                    editingPresetName = "",
                    carrierRange = defaultCurve.carrierRange,
                    beatRange = defaultCurve.beatRange,
                    selectedPointIndex = null,
                    editingRelaxationModeSettings = editingState.editingRelaxationModeSettings,
                    isActivePreset = false,
                    isLoading = false
                )
            }
        } else {
            // Fallback: создаём новую кривую
            val defaultCurve = FrequencyCurve.defaultCurve()
            _uiState.update { 
                it.copy(
                    editingFrequencyCurve = defaultCurve,
                    editingPresetId = null,
                    editingPresetName = "",
                    carrierRange = defaultCurve.carrierRange,
                    beatRange = defaultCurve.beatRange,
                    selectedPointIndex = null,
                    editingRelaxationModeSettings = RelaxationModeSettings(),
                    isActivePreset = false,
                    isLoading = false
                )
            }
        }
    }
    
    /**
     * Отменить редактирование
     * Восстанавливает кривую активного пресета в сервисе, но НЕ очищает editingFrequencyCurve
     * для плавной анимации возврата. Состояние очистится при следующем startEditingPreset/startNewPreset.
     */
    fun cancelEditing() {
        // Если редактировали активный пресет, восстанавливаем оригинальную кривую в сервисе
        val state = _uiState.value
        if (state.isActivePreset && state.editingPresetId != null) {
            viewModelScope.launch {
                val preset = presetRepository.getPresets().first().find { it.id == state.editingPresetId }
                preset?.let { playbackController.updateFrequencyCurve(it.frequencyCurve) }
            }
        }
        
        // Отправляем событие навигации БЕЗ очистки состояния для плавной анимации
        viewModelScope.launch {
            _events.send(PresetEditEvent.NavigateBack)
        }
    }
    
    /**
     * Завершить редактирование без очистки состояния
     * Используется после сохранения для плавной анимации возврата к списку
     */
    fun finishEditingWithoutClear() {
        // Если редактировали активный пресет, обновляем кривую в сервисе
        val state = _uiState.value
        if (state.isActivePreset && state.editingFrequencyCurve != null) {
            playbackController.updateFrequencyCurve(state.editingFrequencyCurve)
        }
        
        // Отправляем событие навигации БЕЗ очистки состояния
        viewModelScope.launch {
            _events.send(PresetEditEvent.NavigateBack)
        }
    }
    
    /**
     * Сохранить пресет
     */
    fun savePreset(name: String) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        if (name.isBlank()) {
            viewModelScope.launch {
                _events.send(PresetEditEvent.ShowValidationErrors(listOf("Name cannot be empty")))
            }
            return
        }
        
        _uiState.update { it.copy(isSaving = true) }
        
        viewModelScope.launch {
            if (state.isNewPreset) {
                // Создание нового пресета через UseCase
                val preset = BinauralPreset(
                    name = name,
                    frequencyCurve = curve,
                    relaxationModeSettings = state.editingRelaxationModeSettings
                )
                savePresetUseCase(preset)
                _events.send(PresetEditEvent.PresetCreated(preset.id))
            } else {
                // Обновление существующего пресета через UseCase
                val presetId = state.editingPresetId ?: return@launch
                val presets = presetRepository.getPresets().first()
                val existingPreset = presets.find { it.id == presetId } ?: return@launch
                
                val updatedPreset = existingPreset.copy(
                    name = name,
                    frequencyCurve = curve,
                    relaxationModeSettings = state.editingRelaxationModeSettings,
                    updatedAt = System.currentTimeMillis()
                )
                
                savePresetUseCase(updatedPreset)
                
                // Если редактировали активный пресет, обновляем имя в playback state
                if (state.isActivePreset) {
                    playbackStateRepository.updateState { it.copy(currentPresetName = updatedPreset.name) }
                }
                
                _events.send(PresetEditEvent.PresetSaved(presetId))
            }
            
            _uiState.update { it.copy(isSaving = false) }
        }
    }
    
    // ============= Методы для редактирования кривой =============
    
    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }
    
    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
    }
    
    fun updateEditingPointCarrierFrequency(frequency: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (newCarrier, adjustedBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                val carrier = frequency.coerceIn(20.0f, 2000.0f)
                val beat = adjustBeatForCarrier(carrier, oldPoint.beatFrequency)
                
                val upperFrequency = carrier + beat / 2.0f
                val lowerFrequency = carrier - beat / 2.0f
                
                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                Triple(carrier, beat, FrequencyRange(newMin, newMax))
            } else {
                val carrier = curve.carrierRange.clamp(frequency)
                val beat = adjustBeatForCarrierWithRange(carrier, oldPoint.beatFrequency, curve.carrierRange)
                Triple(carrier, beat, curve.carrierRange)
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = newCarrier,
                beatFrequency = adjustedBeat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun updateEditingPointBeatFrequency(frequency: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (newBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                val maxBeat = minOf(
                    (oldPoint.carrierFrequency - 20.0f) * 2.0f,
                    (2000.0f - oldPoint.carrierFrequency) * 2.0f
                ).coerceAtLeast(1.0f)
                val beat = frequency.coerceIn(curve.beatRange.min, maxBeat)
                
                val upperFrequency = oldPoint.carrierFrequency + beat / 2.0f
                val lowerFrequency = oldPoint.carrierFrequency - beat / 2.0f
                
                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                Pair(beat, FrequencyRange(newMin, newMax))
            } else {
                val beat = adjustBeatForCarrierWithRange(oldPoint.carrierFrequency, frequency, curve.carrierRange)
                    .coerceIn(curve.beatRange.min, frequency)
                Pair(beat, curve.carrierRange)
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = newBeat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun updateEditingPointTimeDirect(index: Int, newTime: LocalTime) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            points[index] = FrequencyPoint(
                time = newTime,
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = oldPoint.beatFrequency
            )
            val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
            updateEditingCurve(sortedPoints, curve.carrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun updateEditingPointCarrierFrequencyDirect(index: Int, newCarrier: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (carrier, adjustedBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                val clampedCarrier = newCarrier.coerceIn(20.0f, 2000.0f)
                val beat = adjustBeatForCarrier(clampedCarrier, oldPoint.beatFrequency)
                
                val upperFrequency = clampedCarrier + beat / 2.0f
                val lowerFrequency = clampedCarrier - beat / 2.0f
                
                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                Triple(clampedCarrier, beat, FrequencyRange(newMin, newMax))
            } else {
                val clampedCarrier = curve.carrierRange.clamp(newCarrier)
                val beat = adjustBeatForCarrierWithRange(clampedCarrier, oldPoint.beatFrequency, curve.carrierRange)
                Triple(clampedCarrier, beat, curve.carrierRange)
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = carrier,
                beatFrequency = adjustedBeat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun updateEditingPointBeatFrequencyDirect(index: Int, newBeat: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (clampedBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                val maxBeat = minOf(
                    (oldPoint.carrierFrequency - 20.0f) * 2.0f,
                    (2000.0f - oldPoint.carrierFrequency) * 2.0f
                ).coerceAtLeast(1.0f)
                val beat = newBeat.coerceIn(curve.beatRange.min, maxBeat)
                
                val upperFrequency = oldPoint.carrierFrequency + beat / 2.0f
                val lowerFrequency = oldPoint.carrierFrequency - beat / 2.0f
                
                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                Pair(beat, FrequencyRange(newMin, newMax))
            } else {
                val beat = adjustBeatForCarrierWithRange(oldPoint.carrierFrequency, newBeat, curve.carrierRange)
                    .coerceIn(curve.beatRange.min, newBeat)
                Pair(beat, curve.carrierRange)
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = clampedBeat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun addEditingPoint(time: LocalTime, carrierFrequency: Float, beatFrequency: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val clampedCarrier = curve.carrierRange.clamp(carrierFrequency)
        val maxBeat = minOf(
            (clampedCarrier - 20.0f) * 2.0f,
            (2000.0f - clampedCarrier) * 2.0f
        ).coerceAtLeast(1.0f)
        val clampedBeat = beatFrequency.coerceIn(curve.beatRange.min, maxBeat)
        
        val points = curve.points.toMutableList()
        points.add(FrequencyPoint(
            time = time,
            carrierFrequency = clampedCarrier,
            beatFrequency = clampedBeat
        ))
        updateEditingCurve(points.sortedBy { it.time.toSecondOfDay() }, curve.carrierRange, curve.beatRange, curve.interpolationType)
    }
    
    fun removeEditingPoint(index: Int) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val points = curve.points.toMutableList()
        if (points.size > 2 && index in points.indices) {
            points.removeAt(index)
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
            _uiState.update { it.copy(selectedPointIndex = null) }
        }
    }
    
    fun updateEditingCarrierRange(min: Float, max: Float) {
        if (max <= min) return
        
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val newRange = FrequencyRange(min, max)
        
        val updatedPoints = curve.points.map { point ->
            point.copy(carrierFrequency = newRange.clamp(point.carrierFrequency))
        }
        
        updateEditingCurve(updatedPoints, newRange, curve.beatRange, curve.interpolationType)
    }
    
    private fun updateEditingCurve(
        points: List<FrequencyPoint>,
        carrierRange: FrequencyRange,
        beatRange: FrequencyRange,
        interpolationType: InterpolationType = InterpolationType.LINEAR
    ) {
        try {
            val currentCurve = _uiState.value.editingFrequencyCurve
            val newCurve = FrequencyCurve(
                points = points,
                carrierRange = carrierRange,
                beatRange = beatRange,
                interpolationType = interpolationType,
                splineTension = currentCurve?.splineTension ?: 0.0f
            )
            _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
            
            val state = _uiState.value
            
            if (state.isActivePreset) {
                playbackController.updateFrequencyCurve(newCurve)
            }
            
            // Сохраняем состояние в EditingStateRepository для анимации на списке
            saveEditingStateToRepository()
        } catch (e: IllegalArgumentException) {
            // Игнорируем ошибки валидации
        }
    }
    
    fun setInterpolationType(type: InterpolationType) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val newCurve = FrequencyCurve(
            points = curve.points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = type,
            splineTension = curve.splineTension
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        
        if (state.isActivePreset) {
            playbackController.updateFrequencyCurve(newCurve)
        }
    }
    
    fun setSplineTension(tension: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val newCurve = FrequencyCurve(
            points = curve.points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = curve.interpolationType,
            splineTension = tension.coerceIn(0f, 1f)
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        
        if (state.isActivePreset) {
            playbackController.updateFrequencyCurve(newCurve)
        }
    }
    
    // ============= Методы для редактирования режима расслабления =============
    
    fun setEditingRelaxationModeEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(enabled = enabled)
            )
        }
    }
    
    fun setEditingCarrierReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, 50)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(carrierReductionPercent = clampedPercent)
            )
        }
    }
    
    fun setEditingBeatReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, 100)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(beatReductionPercent = clampedPercent)
            )
        }
    }
    
    fun setEditingRelaxationMode(mode: RelaxationMode) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(mode = mode)
            )
        }
    }
    
    fun setEditingRelaxationGapMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(0, 120)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(gapBetweenRelaxationMinutes = clampedMinutes)
            )
        }
    }
    
    fun setEditingRelaxationDurationMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(5, 60)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(relaxationDurationMinutes = clampedMinutes)
            )
        }
    }
    
    fun setEditingTransitionPeriodMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(1, 15)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(transitionPeriodMinutes = clampedMinutes)
            )
        }
    }
    
    fun setEditingSmoothIntervalMinutes(minutes: Int) {
        val clampedMinutes = minutes.coerceIn(5, 120)
        _uiState.update { state ->
            state.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(smoothIntervalMinutes = clampedMinutes)
            )
        }
    }
    
    // ============= Автоматическое расширение графика =============
    
    fun setAutoExpandGraphRange(enabled: Boolean) {
        _uiState.update { it.copy(autoExpandGraphRange = enabled) }
        viewModelScope.launch {
            settingsRepository.saveAutoExpandGraphRange(enabled)
        }
    }
    
    /**
     * Обновить имя пресета
     */
    fun updatePresetName(name: String) {
        _uiState.update { it.copy(editingPresetName = name) }
    }
    
    // ============= Методы для сохранения состояния редактирования =============
    
    /**
     * Сохранить текущее состояние редактирования в EditingStateRepository.
     * Используется для сохранения состояния перед навигацией для плавной анимации.
     */
    private fun saveEditingStateToRepository() {
        val state = _uiState.value
        editingStateRepository.setEditingState(
            com.binauralcycles.viewmodel.state.EditingState(
                editingPresetId = state.editingPresetId,
                editingPresetName = state.editingPresetName,
                editingFrequencyCurve = state.editingFrequencyCurve,
                editingRelaxationModeSettings = state.editingRelaxationModeSettings,
                isActivePreset = state.isActivePreset
            )
        )
    }
}
