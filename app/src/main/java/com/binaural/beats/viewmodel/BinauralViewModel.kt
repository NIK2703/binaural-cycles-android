package com.binaural.beats.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binaural.beats.service.BinauralPlaybackService
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
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
    val frequencyUpdateIntervalMs: Int = 100,
    // Флаг подключения к сервису
    val isServiceConnected: Boolean = false
)

@HiltViewModel
class BinauralViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: BinauralPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BinauralUiState())
    val uiState: StateFlow<BinauralUiState> = _uiState.asStateFlow()
    
    // Ссылка на сервис (может быть null если сервис не привязан)
    private var playbackService: BinauralPlaybackService? = null
    
    // ServiceConnection для привязки к сервису
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BinauralPlaybackService.LocalBinder
            playbackService = binder?.getService()
            _uiState.update { it.copy(isServiceConnected = true) }
            android.util.Log.d("BinauralViewModel", "Service connected")
            
            // Наблюдаем за состоянием воспроизведения из сервиса
            observeServiceState()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            _uiState.update { it.copy(isServiceConnected = false) }
            android.util.Log.d("BinauralViewModel", "Service disconnected")
        }
    }

    init {
        bindToService()
        loadPreferences()
        observePlaybackState()
    }
    
    private fun bindToService() {
        val intent = Intent(context, BinauralPlaybackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    // Наблюдение за состоянием сервиса перенесено в observePlaybackState()
    // для избежания дублирования
    private fun observeServiceState() {
        // Наблюдение уже настроено в observePlaybackState()
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
                        playbackService?.updateFrequencyCurve(activePreset.frequencyCurve)
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
                playbackService?.setSampleRate(sampleRate)
            }
        }
        // Загружаем интервал обновления частот
        viewModelScope.launch {
            preferencesRepository.getFrequencyUpdateInterval().collect { interval ->
                _uiState.update { it.copy(frequencyUpdateIntervalMs = interval) }
                playbackService?.setFrequencyUpdateInterval(interval)
            }
        }
    }

    private fun observePlaybackState() {
        // Наблюдаем за статическими StateFlows сервиса
        viewModelScope.launch {
            BinauralPlaybackService.isPlaying.collect { isPlaying ->
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }
        }
        viewModelScope.launch {
            BinauralPlaybackService.currentBeatFrequency.collect { freq ->
                _uiState.update { it.copy(currentBeatFrequency = freq) }
            }
        }
        viewModelScope.launch {
            BinauralPlaybackService.currentCarrierFrequency.collect { freq ->
                _uiState.update { it.copy(currentCarrierFrequency = freq) }
            }
        }
        viewModelScope.launch {
            BinauralPlaybackService.isChannelsSwapped.collect { swapped ->
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
            playbackService?.stop()
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
        
        // Обновляем конфиг аудио движка (включая нормализацию и другие настройки)
        updateAudioConfigWithPreset(preset)
        playbackService?.play()
        
        // Сохраняем активный пресет
        viewModelScope.launch {
            preferencesRepository.saveActivePresetId(presetId)
        }
    }
    
    /**
     * Обновить конфиг аудио с учётом активного пресета
     */
    private fun updateAudioConfigWithPreset(preset: BinauralPreset) {
        val state = _uiState.value
        val config = BinauralConfig(
            frequencyCurve = preset.frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = state.channelSwapEnabled,
            channelSwapIntervalSeconds = state.channelSwapIntervalSeconds,
            channelSwapFadeEnabled = state.channelSwapFadeEnabled,
            channelSwapFadeDurationMs = state.channelSwapFadeDurationMs,
            volumeNormalizationEnabled = state.volumeNormalizationEnabled,
            volumeNormalizationStrength = state.volumeNormalizationStrength
        )
        playbackService?.updateConfig(config)
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
        playbackService?.updateFrequencyCurve(preset.frequencyCurve)
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
        playbackService?.updateFrequencyCurve(defaultCurve)
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
            playbackService?.stop()
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
            playbackService?.stop()
        } else {
            playbackService?.play()
        }
    }

    fun setVolume(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackService?.setVolume(volume)
        // Сохранение в preferences не требуется - громкость не сохраняется
    }

    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }

    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
    }

    // Методы maxBeatForCarrier и minCarrierForBeat удалены.
    // Ограничение частоты биений по несущей частоте не требуется -
    // кубическая интерполяция может давать значения превышающие точки.

    // ============= Методы для редактирования точек (редактируемая кривая) =============
    
    fun updateEditingPointCarrierFrequency(frequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            // Ограничиваем только по диапазону несущей частоты
            // Частота биений не зависит от несущей - кубическая интерполяция может превышать значения точек
            val newCarrier = curve.carrierRange.clamp(frequency)
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = newCarrier,
                beatFrequency = oldPoint.beatFrequency
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
            // Ограничиваем по формуле: несущая * 2 - 20
            // и минимуму beatRange.min
            val maxBeat = (oldPoint.carrierFrequency * 2 - 20).coerceAtLeast(1.0)
            val newBeat = frequency.coerceIn(curve.beatRange.min, maxBeat)
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = oldPoint.carrierFrequency,
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
            // Ограничиваем только по диапазону несущей частоты
            // Частота биений не зависит от несущей - кубическая интерполяция может превышать значения точек
            val clampedCarrier = curve.carrierRange.clamp(newCarrier)
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = oldPoint.beatFrequency
            )
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun addEditingPoint(time: LocalTime, carrierFrequency: Double, beatFrequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val clampedCarrier = curve.carrierRange.clamp(carrierFrequency)
        // Ограничиваем частоту биений по формуле: несущая * 2 - 20
        val maxBeat = (clampedCarrier * 2 - 20).coerceAtLeast(1.0)
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
            playbackService?.updateFrequencyCurve(newCurve)
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
        playbackService?.updateFrequencyCurve(newCurve)
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
        playbackService?.setSampleRate(rate)
        viewModelScope.launch {
            preferencesRepository.saveSampleRate(rate.value)
        }
    }
    
    // Методы для управления интервалом обновления частот
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        val clampedInterval = intervalMs.coerceIn(100, 5000)
        _uiState.update { it.copy(frequencyUpdateIntervalMs = clampedInterval) }
        playbackService?.setFrequencyUpdateInterval(clampedInterval)
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
        playbackService?.updateConfig(config)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            // Сервис уже отвязан
        }
    }
}