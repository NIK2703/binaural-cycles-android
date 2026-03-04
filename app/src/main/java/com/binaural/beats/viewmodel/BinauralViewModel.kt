package com.binaural.beats.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.core.audio.AudioConstants
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import com.binaural.data.preferences.BinauralPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import javax.inject.Inject

data class BinauralUiState(
    // Список пресетов
    val presets: List<BinauralPreset> = emptyList(),
    val activePreset: BinauralPreset? = null,
    // Состояние воспроизведения
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Double = 0.0,
    val currentCarrierFrequency: Double = 0.0,
    val volume: Float = 0.7f,
    val selectedPointIndex: Int? = null,
    val currentTime: LocalTime = LocalTime(12, 0),
    // Редактируемая кривая (для экрана редактирования)
    val editingFrequencyCurve: FrequencyCurve? = null,
    // Диапазоны частот для редактирования
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    // Настройки перестановки каналов
    val channelSwapEnabled: Boolean = false,
    val channelSwapIntervalSeconds: Int = 300, // 5 минут
    val channelSwapFadeEnabled: Boolean = true, // затухание при смене каналов
    val channelSwapFadeDurationMs: Long = 1000L, // длительность затухания/нарастания в мс
    val isChannelsSwapped: Boolean = false,
    // Настройки нормализации громкости
    val volumeNormalizationEnabled: Boolean = true,  // Включено по умолчанию
    val volumeNormalizationStrength: Float = 0.5f,
    // Частота дискретизации
    val sampleRate: SampleRate = SampleRate.MEDIUM,
    // Интервал обновления частот (мс)
    val frequencyUpdateIntervalMs: Int = 100
)

@HiltViewModel
class BinauralViewModel @Inject constructor(
    private val audioEngine: BinauralAudioEngine,
    private val preferencesRepository: BinauralPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BinauralUiState())
    val uiState: StateFlow<BinauralUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
        observeAudioState()
    }

    private fun loadPreferences() {
        // Загружаем список пресетов
        viewModelScope.launch {
            preferencesRepository.getPresets().collect { presets ->
                _uiState.update { it.copy(presets = presets) }
            }
        }
        
        // Загружаем активный пресет
        viewModelScope.launch {
            preferencesRepository.getActivePresetId().collect { activeId ->
                if (activeId != null) {
                    val presets = _uiState.value.presets
                    val activePreset = presets.find { it.id == activeId }
                    if (activePreset != null) {
                        _uiState.update { 
                            it.copy(
                                activePreset = activePreset,
                                carrierRange = activePreset.frequencyCurve.carrierRange,
                                beatRange = activePreset.frequencyCurve.beatRange
                            )
                        }
                        audioEngine.updateFrequencyCurve(activePreset.frequencyCurve)
                    }
                }
            }
        }
        
        // Загружаем настройки перестановки каналов
        viewModelScope.launch {
            preferencesRepository.getChannelSwapEnabled().collect { enabled ->
                _uiState.update { it.copy(channelSwapEnabled = enabled) }
                updateAudioConfig()
            }
        }
        viewModelScope.launch {
            preferencesRepository.getChannelSwapInterval().collect { interval ->
                _uiState.update { it.copy(channelSwapIntervalSeconds = interval) }
                updateAudioConfig()
            }
        }
        viewModelScope.launch {
            preferencesRepository.getChannelSwapFadeEnabled().collect { enabled ->
                _uiState.update { it.copy(channelSwapFadeEnabled = enabled) }
                updateAudioConfig()
            }
        }
        viewModelScope.launch {
            preferencesRepository.getChannelSwapFadeDuration().collect { duration ->
                _uiState.update { it.copy(channelSwapFadeDurationMs = duration.toLong()) }
                updateAudioConfig()
            }
        }
        // Загружаем настройки нормализации громкости
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationEnabled().collect { enabled ->
                _uiState.update { it.copy(volumeNormalizationEnabled = enabled) }
                updateAudioConfig()
            }
        }
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationStrength().collect { strength ->
                _uiState.update { it.copy(volumeNormalizationStrength = strength) }
                updateAudioConfig()
            }
        }
        // Загружаем частоту дискретизации
        viewModelScope.launch {
            preferencesRepository.getSampleRate().collect { rate ->
                val sampleRate = when (rate) {
                    22050 -> SampleRate.LOW
                    48000 -> SampleRate.HIGH
                    else -> SampleRate.MEDIUM
                }
                _uiState.update { it.copy(sampleRate = sampleRate) }
                audioEngine.setSampleRate(sampleRate)
            }
        }
        // Загружаем интервал обновления частот
        viewModelScope.launch {
            preferencesRepository.getFrequencyUpdateInterval().collect { interval ->
                _uiState.update { it.copy(frequencyUpdateIntervalMs = interval) }
                audioEngine.setFrequencyUpdateInterval(interval)
            }
        }
    }

    private fun observeAudioState() {
        viewModelScope.launch {
            audioEngine.isPlaying.collect { isPlaying ->
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }
        }
        viewModelScope.launch {
            audioEngine.currentBeatFrequency.collect { freq ->
                _uiState.update { it.copy(currentBeatFrequency = freq) }
            }
        }
        viewModelScope.launch {
            audioEngine.currentCarrierFrequency.collect { freq ->
                _uiState.update { it.copy(currentCarrierFrequency = freq) }
            }
        }
        viewModelScope.launch {
            audioEngine.isChannelsSwapped.collect { swapped ->
                _uiState.update { it.copy(isChannelsSwapped = swapped) }
            }
        }
    }

    // ============= Методы для работы с пресетами =============
    
    /**
     * Воспроизвести пресет
     */
    fun playPreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        
        // Если уже воспроизводится этот пресет - останавливаем
        if (_uiState.value.activePreset?.id == presetId && _uiState.value.isPlaying) {
            audioEngine.stop()
            return
        }
        
        // Устанавливаем активный пресет
        _uiState.update { 
            it.copy(
                activePreset = preset,
                carrierRange = preset.frequencyCurve.carrierRange,
                beatRange = preset.frequencyCurve.beatRange
            )
        }
        
        // Обновляем аудио движок
        audioEngine.updateFrequencyCurve(preset.frequencyCurve)
        audioEngine.play()
        
        // Сохраняем активный пресет
        viewModelScope.launch {
            preferencesRepository.saveActivePresetId(presetId)
        }
    }
    
    /**
     * Начать редактирование существующего пресета
     */
    fun startEditingPreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = preset.frequencyCurve,
                carrierRange = preset.frequencyCurve.carrierRange,
                beatRange = preset.frequencyCurve.beatRange
            )
        }
        audioEngine.updateFrequencyCurve(preset.frequencyCurve)
    }
    
    /**
     * Начать создание нового пресета
     */
    fun startNewPreset() {
        val defaultCurve = FrequencyCurve.defaultCurve()
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = defaultCurve,
                carrierRange = defaultCurve.carrierRange,
                beatRange = defaultCurve.beatRange,
                selectedPointIndex = null
            )
        }
        audioEngine.updateFrequencyCurve(defaultCurve)
    }
    
    /**
     * Создать новый пресет
     */
    fun createPreset(name: String, curve: FrequencyCurve) {
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = curve
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(preset)
        }
    }
    
    /**
     * Сохранить редактируемый пресет
     */
    fun saveEditingPreset(presetId: String, name: String, curve: FrequencyCurve) {
        val existingPreset = _uiState.value.presets.find { it.id == presetId } ?: return
        val updatedPreset = existingPreset.copy(
            name = name,
            frequencyCurve = curve,
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferencesRepository.updatePreset(updatedPreset)
        }
    }
    
    /**
     * Обновить название пресета
     */
    fun updatePresetName(presetId: String, name: String) {
        // Это будет сохранено в saveEditingPreset
    }
    
    /**
     * Удалить пресет
     */
    fun deletePreset(presetId: String) {
        // Если удаляем активный пресет - останавливаем воспроизведение
        if (_uiState.value.activePreset?.id == presetId) {
            audioEngine.stop()
            _uiState.update { it.copy(activePreset = null) }
            viewModelScope.launch {
                preferencesRepository.saveActivePresetId(null)
            }
        }
        
        viewModelScope.launch {
            preferencesRepository.deletePreset(presetId)
        }
    }

    // ============= Методы для редактирования кривой =============

    fun togglePlayback() {
        if (_uiState.value.isPlaying) {
            audioEngine.stop()
        } else {
            audioEngine.play()
        }
    }

    fun setVolume(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        audioEngine.setVolume(volume)
    }

    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }

    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
    }

    /**
     * Вычисляет максимально допустимую частоту биений для данной несущей.
     */
    private fun maxBeatForCarrier(carrierFreq: Double): Double {
        return 2.0 * maxOf(0.0, carrierFreq - AudioConstants.MIN_AUDIBLE_FREQUENCY)
    }
    
    /**
     * Вычисляет минимальную несущую частоту для данной частоты биений.
     */
    private fun minCarrierForBeat(beatFreq: Double): Double {
        return beatFreq / 2.0 + AudioConstants.MIN_AUDIBLE_FREQUENCY
    }

    // ============= Методы для редактирования точек (редактируемая кривая) =============
    
    fun updateEditingPointCarrierFrequency(frequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val newCarrier = curve.carrierRange.clamp(frequency)
            
            val maxBeat = maxBeatForCarrier(newCarrier)
            val newBeat = minOf(oldPoint.beatFrequency, maxBeat, curve.beatRange.max)
            val clampedBeat = curve.beatRange.clamp(newBeat.coerceAtLeast(curve.beatRange.min))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = newCarrier,
                beatFrequency = clampedBeat
            )
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun updateEditingPointBeatFrequency(frequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val newBeat = curve.beatRange.clamp(frequency)
            
            val minCarrier = minCarrierForBeat(newBeat)
            val newCarrier = maxOf(oldPoint.carrierFrequency, minCarrier, curve.carrierRange.min)
            val clampedCarrier = curve.carrierRange.clamp(newCarrier.coerceAtMost(curve.carrierRange.max))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = newBeat
            )
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
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
    
    fun updateEditingPointCarrierFrequencyDirect(index: Int, newCarrier: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val clampedCarrier = curve.carrierRange.clamp(newCarrier)
            
            val maxBeat = maxBeatForCarrier(clampedCarrier)
            val newBeat = minOf(oldPoint.beatFrequency, maxBeat, curve.beatRange.max)
            val clampedBeat = curve.beatRange.clamp(newBeat.coerceAtLeast(curve.beatRange.min))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = clampedBeat
            )
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun addEditingPoint(time: LocalTime, carrierFrequency: Double, beatFrequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val points = curve.points.toMutableList()
        points.add(FrequencyPoint(
            time = time,
            carrierFrequency = curve.carrierRange.clamp(carrierFrequency),
            beatFrequency = curve.beatRange.clamp(beatFrequency)
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
    
    fun updateEditingCarrierRange(min: Double, max: Double) {
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
            val newCurve = FrequencyCurve(points, carrierRange, beatRange, interpolationType)
            _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
            audioEngine.updateFrequencyCurve(newCurve)
        } catch (e: IllegalArgumentException) {
            // Игнорируем ошибки валидации (например, меньше 2 точек)
        }
    }
    
    /**
     * Установить тип интерполяции для редактируемой кривой
     */
    fun setInterpolationType(type: InterpolationType) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val newCurve = FrequencyCurve(
            points = curve.points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = type
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        audioEngine.updateFrequencyCurve(newCurve)
    }

    // ============= Методы для управления перестановкой каналов =============
    
    fun setChannelSwapEnabled(enabled: Boolean) {
        _uiState.update { it.copy(channelSwapEnabled = enabled) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapEnabled(enabled)
        }
    }

    fun setChannelSwapInterval(seconds: Int) {
        val clampedSeconds = seconds.coerceIn(30, 3600)
        _uiState.update { it.copy(channelSwapIntervalSeconds = clampedSeconds) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapInterval(clampedSeconds)
        }
    }

    fun setChannelSwapFadeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(channelSwapFadeEnabled = enabled) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapFadeEnabled(enabled)
        }
    }

    fun setChannelSwapFadeDuration(durationMs: Long) {
        val clampedDuration = durationMs.coerceIn(100L, 10000L)
        _uiState.update { it.copy(channelSwapFadeDurationMs = clampedDuration) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapFadeDuration(clampedDuration.toInt())
        }
    }

    // Методы для управления нормализацией громкости
    
    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(volumeNormalizationEnabled = enabled) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveVolumeNormalizationEnabled(enabled)
        }
    }

    fun setVolumeNormalizationStrength(strength: Float) {
        val clampedStrength = strength.coerceIn(0f, 1f)
        _uiState.update { it.copy(volumeNormalizationStrength = clampedStrength) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveVolumeNormalizationStrength(clampedStrength)
        }
    }

    // Методы для управления частотой дискретизации
    
    fun setSampleRate(rate: SampleRate) {
        _uiState.update { it.copy(sampleRate = rate) }
        audioEngine.setSampleRate(rate)
        viewModelScope.launch {
            preferencesRepository.saveSampleRate(rate.value)
        }
    }
    
    // Методы для управления интервалом обновления частот
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        val clampedInterval = intervalMs.coerceIn(100, 5000)
        _uiState.update { it.copy(frequencyUpdateIntervalMs = clampedInterval) }
        audioEngine.setFrequencyUpdateInterval(clampedInterval)
        viewModelScope.launch {
            preferencesRepository.saveFrequencyUpdateInterval(clampedInterval)
        }
    }

    private fun updateAudioConfig() {
        val state = _uiState.value
        val config = BinauralConfig(
            frequencyCurve = state.editingFrequencyCurve ?: state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
            volume = state.volume,
            channelSwapEnabled = state.channelSwapEnabled,
            channelSwapIntervalSeconds = state.channelSwapIntervalSeconds,
            channelSwapFadeEnabled = state.channelSwapFadeEnabled,
            channelSwapFadeDurationMs = state.channelSwapFadeDurationMs,
            volumeNormalizationEnabled = state.volumeNormalizationEnabled,
            volumeNormalizationStrength = state.volumeNormalizationStrength
        )
        audioEngine.updateConfig(config)
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }
}