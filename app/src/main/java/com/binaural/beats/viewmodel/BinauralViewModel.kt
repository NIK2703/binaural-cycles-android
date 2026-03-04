package com.binaural.beats.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.beats.service.BinauralPlaybackService
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.data.preferences.BinauralPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import javax.inject.Inject

data class BinauralUiState(
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Double = 0.0,
    val currentCarrierFrequency: Double = 0.0,
    val frequencyCurve: FrequencyCurve = FrequencyCurve.defaultCurve(),
    val volume: Float = 0.7f,
    val selectedPointIndex: Int? = null,
    val currentTime: LocalTime = LocalTime(12, 0),
    // Диапазоны частот для редактирования
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    // Настройки перестановки каналов
    val channelSwapEnabled: Boolean = false,
    val channelSwapIntervalSeconds: Int = 300, // 5 минут
    val isChannelsSwapped: Boolean = false,
    // Настройки нормализации громкости
    val volumeNormalizationEnabled: Boolean = false,
    val volumeNormalizationStrength: Float = 0.5f,
    // Частота дискретизации
    val sampleRate: SampleRate = SampleRate.MEDIUM
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
        viewModelScope.launch {
            preferencesRepository.getFrequencyCurve().collect { curve ->
                _uiState.update { 
                    it.copy(
                        frequencyCurve = curve,
                        carrierRange = curve.carrierRange,
                        beatRange = curve.beatRange
                    ) 
                }
                audioEngine.updateFrequencyCurve(curve)
            }
        }
        // Загружаем настройки перестановки каналов
        viewModelScope.launch {
            preferencesRepository.getChannelSwapEnabled().collect { enabled ->
                _uiState.update { it.copy(channelSwapEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.getChannelSwapInterval().collect { interval ->
                _uiState.update { it.copy(channelSwapIntervalSeconds = interval) }
            }
        }
        // Загружаем настройки нормализации громкости
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationEnabled().collect { enabled ->
                _uiState.update { it.copy(volumeNormalizationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationStrength().collect { strength ->
                _uiState.update { it.copy(volumeNormalizationStrength = strength) }
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
     * При формуле carrier ± beat/2, левый канал = carrier - beat/2 должен быть >= MIN_FREQ (20 Гц)
     * => beat <= 2 * (carrier - MIN_FREQ)
     */
    private fun maxBeatForCarrier(carrierFreq: Double): Double {
        val MIN_FREQ = 20.0
        return 2.0 * maxOf(0.0, carrierFreq - MIN_FREQ)
    }
    
    /**
     * Вычисляет минимальную несущую частоту для данной частоты биений.
     * При формуле carrier ± beat/2, левый канал = carrier - beat/2 должен быть >= MIN_FREQ (20 Гц)
     * => carrier >= beat/2 + MIN_FREQ
     */
    private fun minCarrierForBeat(beatFreq: Double): Double {
        val MIN_FREQ = 20.0
        return beatFreq / 2.0 + MIN_FREQ
    }

    fun updatePointCarrierFrequency(frequency: Double) {
        val state = _uiState.value
        val index = state.selectedPointIndex ?: return
        
        val points = state.frequencyCurve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val newCarrier = state.carrierRange.clamp(frequency)
            
            // Автоматически корректируем частоту биений, если она слишком большая
            val maxBeat = maxBeatForCarrier(newCarrier)
            val newBeat = minOf(oldPoint.beatFrequency, maxBeat, state.beatRange.max)
            val clampedBeat = state.beatRange.clamp(newBeat.coerceAtLeast(state.beatRange.min))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = newCarrier,
                beatFrequency = clampedBeat
            )
            updateCurve(points)
        }
    }

    fun updatePointBeatFrequency(frequency: Double) {
        val state = _uiState.value
        val index = state.selectedPointIndex ?: return
        
        val points = state.frequencyCurve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val newBeat = state.beatRange.clamp(frequency)
            
            // Автоматически корректируем несущую частоту, если она слишком мала
            val minCarrier = minCarrierForBeat(newBeat)
            val newCarrier = maxOf(oldPoint.carrierFrequency, minCarrier, state.carrierRange.min)
            val clampedCarrier = state.carrierRange.clamp(newCarrier.coerceAtMost(state.carrierRange.max))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = newBeat
            )
            updateCurve(points)
        }
    }

    fun updatePointTime(hour: Int, minute: Int) {
        val state = _uiState.value
        val index = state.selectedPointIndex ?: return
        
        val points = state.frequencyCurve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            points[index] = FrequencyPoint(
                time = LocalTime(hour, minute),
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = oldPoint.beatFrequency
            )
            updateCurve(points.sortedBy { it.time.toSecondOfDay() })
        }
    }
    
    /**
     * Прямое обновление времени точки по индексу (используется при перетаскивании)
     */
    fun updatePointTimeDirect(index: Int, newTime: LocalTime) {
        val state = _uiState.value
        val points = state.frequencyCurve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            points[index] = FrequencyPoint(
                time = newTime,
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = oldPoint.beatFrequency
            )
            // Сортируем точки после изменения времени
            val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
            updateCurve(sortedPoints)
        }
    }
    
    /**
     * Прямое обновление несущей частоты точки по индексу (используется при перетаскивании)
     */
    fun updatePointCarrierFrequencyDirect(index: Int, newCarrier: Double) {
        val state = _uiState.value
        val points = state.frequencyCurve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val clampedCarrier = state.carrierRange.clamp(newCarrier)
            
            // Автоматически корректируем частоту биений, если она слишком большая
            val maxBeat = maxBeatForCarrier(clampedCarrier)
            val newBeat = minOf(oldPoint.beatFrequency, maxBeat, state.beatRange.max)
            val clampedBeat = state.beatRange.clamp(newBeat.coerceAtLeast(state.beatRange.min))
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = clampedBeat
            )
            updateCurve(points)
        }
    }

    fun addPoint(time: LocalTime, carrierFrequency: Double, beatFrequency: Double) {
        val state = _uiState.value
        
        val points = state.frequencyCurve.points.toMutableList()
        points.add(FrequencyPoint(
            time = time,
            carrierFrequency = state.carrierRange.clamp(carrierFrequency),
            beatFrequency = state.beatRange.clamp(beatFrequency)
        ))
        updateCurve(points.sortedBy { it.time.toSecondOfDay() })
    }

    fun removePoint(index: Int) {
        val state = _uiState.value
        
        val points = state.frequencyCurve.points.toMutableList()
        if (points.size > 2 && index in points.indices) {
            points.removeAt(index)
            updateCurve(points)
            _uiState.update { it.copy(selectedPointIndex = null) }
        }
    }

    fun updateCarrierRange(min: Double, max: Double) {
        // Проверяем, что max > min
        if (max <= min) return
        
        val state = _uiState.value
        val newRange = FrequencyRange(min, max)
        
        // Обновляем все точки с новым диапазоном
        val updatedPoints = state.frequencyCurve.points.map { point ->
            point.copy(carrierFrequency = newRange.clamp(point.carrierFrequency))
        }
        
        val newCurve = FrequencyCurve(updatedPoints, newRange, state.beatRange)
        _uiState.update {
            it.copy(
                frequencyCurve = newCurve,
                carrierRange = newRange
            )
        }
        audioEngine.updateFrequencyCurve(newCurve)
        viewModelScope.launch {
            preferencesRepository.saveFrequencyCurve(newCurve)
        }
    }

    fun updateBeatRange(min: Double, max: Double) {
        // Проверяем, что max > min
        if (max <= min) return
        
        val state = _uiState.value
        val newRange = FrequencyRange(min, max)
        
        // Обновляем все точки с новым диапазоном
        val updatedPoints = state.frequencyCurve.points.map { point ->
            point.copy(beatFrequency = newRange.clamp(point.beatFrequency))
        }
        
        val newCurve = FrequencyCurve(updatedPoints, state.carrierRange, newRange)
        _uiState.update {
            it.copy(
                frequencyCurve = newCurve,
                beatRange = newRange
            )
        }
        audioEngine.updateFrequencyCurve(newCurve)
        viewModelScope.launch {
            preferencesRepository.saveFrequencyCurve(newCurve)
        }
    }

    private fun updateCurve(points: List<FrequencyPoint>) {
        val state = _uiState.value
        val newCurve = FrequencyCurve(points, state.carrierRange, state.beatRange)
        _uiState.update { it.copy(frequencyCurve = newCurve) }
        audioEngine.updateFrequencyCurve(newCurve)
        viewModelScope.launch {
            preferencesRepository.saveFrequencyCurve(newCurve)
        }
    }

    // Методы для управления перестановкой каналов
    
    fun setChannelSwapEnabled(enabled: Boolean) {
        _uiState.update { it.copy(channelSwapEnabled = enabled) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapEnabled(enabled)
        }
    }

    fun setChannelSwapInterval(seconds: Int) {
        val clampedSeconds = seconds.coerceIn(30, 3600) // от 30 сек до 1 часа
        _uiState.update { it.copy(channelSwapIntervalSeconds = clampedSeconds) }
        updateAudioConfig()
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapInterval(clampedSeconds)
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

    private fun updateAudioConfig() {
        val state = _uiState.value
        val config = com.binaural.core.audio.model.BinauralConfig(
            frequencyCurve = state.frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = state.channelSwapEnabled,
            channelSwapIntervalSeconds = state.channelSwapIntervalSeconds,
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
