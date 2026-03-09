package com.binauralcycles.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binauralcycles.service.BinauralPlaybackService
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binaural.data.preferences.BinauralPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    // ID редактируемого пресета (null для нового пресета)
    val editingPresetId: String? = null,
    // Диапазоны частот для редактирования
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    // Редактируемые настройки пресета (для экрана редактирования)
    val editingChannelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
    val editingVolumeNormalizationSettings: VolumeNormalizationSettings = VolumeNormalizationSettings(),
    // Состояние перестановки каналов (из сервиса)
    val isChannelsSwapped: Boolean = false,
    // Общие настройки приложения
    val sampleRate: SampleRate = SampleRate.LOW,
    val frequencyUpdateIntervalMs: Int = 10000,
    // Wavetable оптимизация (быстрая генерация синусоид с линейной интерполяцией)
    val wavetableOptimizationEnabled: Boolean = true,
    val wavetableSize: Int = 2048,
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
            
            // При подключении сервиса обновляем конфиг с активным пресетом
            // Это важно, т.к. при загрузке preferences сервис мог быть ещё не подключен
            val state = _uiState.value
            if (state.activePreset != null) {
                android.util.Log.d("BinauralViewModel", "Updating audio config for active preset: ${state.activePreset.name}")
                updateAudioConfig()
            }
            
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

    private var lastActivePresetId: String? = null  // Сохраняем ID последнего активного пресета
    
    private fun loadPreferences() {
        // Загружаем список пресетов и активный пресет последовательно
        viewModelScope.launch {
            // Сначала загружаем пресеты
            preferencesRepository.getPresets().collect { presets ->
                _uiState.update { it.copy(presets = presets) }
                
                // После обновления списка пресетов проверяем активный пресет
                val activeId = preferencesRepository.getActivePresetId().first()
                if (activeId != null) {
                    lastActivePresetId = activeId  // Сохраняем для togglePlayback
                    val activePreset = presets.find { it.id == activeId }
                    if (activePreset != null) {
                        _uiState.update { 
                            it.copy(
                                activePreset = activePreset,
                                carrierRange = activePreset.frequencyCurve.carrierRange,
                                beatRange = activePreset.frequencyCurve.beatRange
                            )
                        }
                        // Обновляем весь конфиг в сервисе, а не только кривую
                        // Это важно для корректного воспроизведения при старте
                        updateAudioConfig()
                    }
                }
            }
        }
        
        // Загружаем общие настройки приложения
        // Частота дискретизации
        viewModelScope.launch {
            preferencesRepository.getSampleRate().collect { rate ->
                val sampleRate = when (rate) {
                    8000 -> SampleRate.ULTRA_LOW
                    16000 -> SampleRate.VERY_LOW
                    22050 -> SampleRate.LOW
                    48000 -> SampleRate.HIGH
                    else -> SampleRate.MEDIUM
                }
                _uiState.update { it.copy(sampleRate = sampleRate) }
                playbackService?.setSampleRate(sampleRate)
            }
        }
        // Интервал обновления частот
        viewModelScope.launch {
            preferencesRepository.getFrequencyUpdateInterval().collect { interval ->
                _uiState.update { it.copy(frequencyUpdateIntervalMs = interval) }
                playbackService?.setFrequencyUpdateInterval(interval)
            }
        }
        // Wavetable оптимизация
        viewModelScope.launch {
            preferencesRepository.getWavetableOptimizationEnabled().collect { enabled ->
                _uiState.update { it.copy(wavetableOptimizationEnabled = enabled) }
                // Передаём в сервис для применения в аудио-движке
                playbackService?.setWavetableOptimizationEnabled(enabled)
            }
        }
        // Размер таблицы волн
        viewModelScope.launch {
            preferencesRepository.getWavetableSize().collect { size ->
                _uiState.update { it.copy(wavetableSize = size) }
                // Передаём в сервис для применения в аудио-движке
                playbackService?.setWavetableSize(size)
            }
        }
        // Громкость
        viewModelScope.launch {
            preferencesRepository.getVolume().collect { volume ->
                _uiState.update { it.copy(volume = volume) }
                playbackService?.setVolume(volume)
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
        val state = _uiState.value
        
        // Если уже воспроизводится этот пресет - останавливаем с затуханием
        if (state.activePreset?.id == presetId && state.isPlaying) {
            playbackService?.stopWithFade()
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
        
        // Формируем конфиг из настроек пресета
        val config = BinauralConfig(
            frequencyCurve = preset.frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = preset.channelSwapSettings.enabled,
            channelSwapIntervalSeconds = preset.channelSwapSettings.intervalSeconds,
            channelSwapFadeEnabled = preset.channelSwapSettings.fadeEnabled,
            channelSwapFadeDurationMs = preset.channelSwapSettings.fadeDurationMs,
            volumeNormalizationEnabled = preset.volumeNormalizationSettings.enabled,
            volumeNormalizationStrength = preset.volumeNormalizationSettings.strength
        )
        
        // Обновляем конфиг мгновенно (без fade при переключении пресетов)
        playbackService?.updateConfig(config)
        if (!state.isPlaying) {
            playbackService?.play()
        }
        
        // Сохраняем активный пресет
        lastActivePresetId = presetId
        viewModelScope.launch {
            preferencesRepository.saveActivePresetId(presetId)
        }
    }
    
    /**
     * Начать редактирование существующего пресета
     */
    fun startEditingPreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val isActivePreset = _uiState.value.activePreset?.id == presetId
        
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = preset.frequencyCurve,
                editingPresetId = presetId,
                carrierRange = preset.frequencyCurve.carrierRange,
                beatRange = preset.frequencyCurve.beatRange,
                editingChannelSwapSettings = preset.channelSwapSettings,
                editingVolumeNormalizationSettings = preset.volumeNormalizationSettings
            )
        }
        
        // Обновляем кривую в сервисе только если редактируется активный пресет
        // Это позволяет слышать изменения в реальном времени при редактировании активного пресета
        if (isActivePreset) {
            playbackService?.updateFrequencyCurve(preset.frequencyCurve)
        }
    }
    
    /**
     * Начать создание нового пресета
     */
    fun startNewPreset() {
        val defaultCurve = FrequencyCurve.defaultCurve()
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = defaultCurve,
                editingPresetId = null,
                carrierRange = defaultCurve.carrierRange,
                beatRange = defaultCurve.beatRange,
                selectedPointIndex = null,
                editingChannelSwapSettings = ChannelSwapSettings(),
                editingVolumeNormalizationSettings = VolumeNormalizationSettings()
            )
        }
        // Не обновляем кривую в сервисе при создании нового пресета
        // Воспроизведение продолжает использовать активный пресет
    }
    
    /**
     * Отменить редактирование и восстановить кривую активного пресета
     */
    fun cancelEditing() {
        val activePreset = _uiState.value.activePreset
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = null,
                editingPresetId = null,
                selectedPointIndex = null,
                editingChannelSwapSettings = ChannelSwapSettings(),
                editingVolumeNormalizationSettings = VolumeNormalizationSettings()
            )
        }
        
        // Восстанавливаем кривую активного пресета в сервисе
        if (activePreset != null) {
            playbackService?.updateFrequencyCurve(activePreset.frequencyCurve)
        }
    }
    
    /**
     * Завершить редактирование после успешного сохранения
     * Очищает состояние редактирования БЕЗ восстановления кривой в сервисе
     */
    fun finishEditing() {
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = null,
                editingPresetId = null,
                selectedPointIndex = null,
                editingChannelSwapSettings = ChannelSwapSettings(),
                editingVolumeNormalizationSettings = VolumeNormalizationSettings()
            )
        }
        // Не восстанавливаем кривую в сервисе - новые данные загрузятся через Flow
    }
    
    /**
     * Создать новый пресет
     */
    fun createPreset(name: String, curve: FrequencyCurve, channelSwapSettings: ChannelSwapSettings, volumeNormalizationSettings: VolumeNormalizationSettings) {
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = curve,
            channelSwapSettings = channelSwapSettings,
            volumeNormalizationSettings = volumeNormalizationSettings
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(preset)
        }
    }
    
    /**
     * Сохранить редактируемый пресет
     */
    fun saveEditingPreset(presetId: String, name: String, curve: FrequencyCurve, channelSwapSettings: ChannelSwapSettings, volumeNormalizationSettings: VolumeNormalizationSettings) {
        val existingPreset = _uiState.value.presets.find { it.id == presetId } ?: return
        val updatedPreset = existingPreset.copy(
            name = name,
            frequencyCurve = curve,
            channelSwapSettings = channelSwapSettings,
            volumeNormalizationSettings = volumeNormalizationSettings,
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferencesRepository.updatePreset(updatedPreset)
        }
    }
    
    /**
     * Удалить пресет
     */
    fun deletePreset(presetId: String) {
        // Если удаляем активный пресет - останавливаем воспроизведение с затуханием
        if (_uiState.value.activePreset?.id == presetId) {
            playbackService?.stopWithFade()
            _uiState.update { it.copy(activePreset = null) }
            lastActivePresetId = null
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
        val state = _uiState.value
        
        if (state.isPlaying) {
            // Плавная остановка с затуханием
            playbackService?.stopWithFade()
        } else {
            // Если есть активный пресет - обновляем конфиг и продолжаем воспроизведение
            if (state.activePreset != null) {
                // Важно: сначала обновляем конфиг, т.к. при запуске приложения
                // конфиг в сервисе может быть дефолтным
                updateAudioConfig()
                playbackService?.resumeWithFade()
            } else {
                // Если нет активного пресета, но есть сохранённый lastActivePresetId
                // пытаемся восстановить и воспроизвести его
                val presetId = lastActivePresetId
                if (presetId != null) {
                    val preset = state.presets.find { it.id == presetId }
                    if (preset != null) {
                        // Восстанавливаем активный пресет и запускаем воспроизведение
                        playPreset(presetId)
                    }
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackService?.setVolume(volume)
        // Сохраняем громкость в preferences для восстановления при следующем запуске
        viewModelScope.launch {
            preferencesRepository.saveVolume(volume)
        }
    }

    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }

    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
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
            
            // Проверяем, нужно ли расширить диапазон несущей частоты
            val upperFrequency = newCarrier + oldPoint.beatFrequency / 2
            val newCarrierRange = if (upperFrequency > curve.carrierRange.max) {
                FrequencyRange(curve.carrierRange.min, (upperFrequency * 1.1).coerceAtLeast(upperFrequency + 10))
            } else {
                curve.carrierRange
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = newCarrier,
                beatFrequency = oldPoint.beatFrequency
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun updateEditingPointBeatFrequency(frequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val maxBeat = (oldPoint.carrierFrequency * 2 - 20).coerceAtLeast(1.0)
            val newBeat = frequency.coerceIn(curve.beatRange.min, maxBeat)
            
            // Проверяем, нужно ли расширить диапазон несущей частоты
            val upperFrequency = oldPoint.carrierFrequency + newBeat / 2
            val newCarrierRange = if (upperFrequency > curve.carrierRange.max) {
                FrequencyRange(curve.carrierRange.min, (upperFrequency * 1.1).coerceAtLeast(upperFrequency + 10))
            } else {
                curve.carrierRange
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
    
    fun updateEditingPointCarrierFrequencyDirect(index: Int, newCarrier: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            val clampedCarrier = curve.carrierRange.clamp(newCarrier)
            
            // Проверяем, нужно ли расширить диапазон несущей частоты
            val upperFrequency = clampedCarrier + oldPoint.beatFrequency / 2
            val newCarrierRange = if (upperFrequency > curve.carrierRange.max) {
                FrequencyRange(curve.carrierRange.min, (upperFrequency * 1.1).coerceAtLeast(upperFrequency + 10))
            } else {
                curve.carrierRange
            }
            
            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = clampedCarrier,
                beatFrequency = oldPoint.beatFrequency
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun addEditingPoint(time: LocalTime, carrierFrequency: Double, beatFrequency: Double) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val clampedCarrier = curve.carrierRange.clamp(carrierFrequency)
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
            val currentCurve = _uiState.value.editingFrequencyCurve
            val newCurve = FrequencyCurve(
                points = points,
                carrierRange = carrierRange,
                beatRange = beatRange,
                interpolationType = interpolationType,
                splineTension = currentCurve?.splineTension ?: 0.0f
            )
            _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
            
            // Обновляем кривую в сервисе только если редактируется активный пресет
            val state = _uiState.value
            val isActivePreset = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
            
            if (isActivePreset) {
                playbackService?.updateFrequencyCurve(newCurve)
            }
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
            interpolationType = type,
            splineTension = curve.splineTension
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        
        // Обновляем кривую в сервисе только если редактируется активный пресет
        val isActivePreset = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        if (isActivePreset) {
            playbackService?.updateFrequencyCurve(newCurve)
        }
    }
    
    /**
     * Установить натяжение сплайна для редактируемой кривой
     */
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
        
        // Обновляем кривую в сервисе только если редактируется активный пресет
        val isActivePreset = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        if (isActivePreset) {
            playbackService?.updateFrequencyCurve(newCurve)
        }
    }

    // ============= Методы для редактирования настроек пресета =============
    
    fun setEditingChannelSwapEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingChannelSwapSettings = state.editingChannelSwapSettings.copy(enabled = enabled)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }

    fun setEditingChannelSwapInterval(seconds: Int) {
        val state = _uiState.value
        val clampedSeconds = seconds.coerceIn(30, 3600)
        _uiState.update { 
            it.copy(
                editingChannelSwapSettings = state.editingChannelSwapSettings.copy(intervalSeconds = clampedSeconds)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }

    fun setEditingChannelSwapFadeEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingChannelSwapSettings = state.editingChannelSwapSettings.copy(fadeEnabled = enabled)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }

    fun setEditingChannelSwapFadeDuration(durationMs: Long) {
        val state = _uiState.value
        val clampedDuration = durationMs.coerceIn(100L, 10000L)
        _uiState.update { 
            it.copy(
                editingChannelSwapSettings = state.editingChannelSwapSettings.copy(fadeDurationMs = clampedDuration)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }

    fun setEditingVolumeNormalizationEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingVolumeNormalizationSettings = state.editingVolumeNormalizationSettings.copy(enabled = enabled)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }

    fun setEditingVolumeNormalizationStrength(strength: Float) {
        val state = _uiState.value
        val clampedStrength = strength.coerceIn(0f, 2f)
        _uiState.update {
            it.copy(
                editingVolumeNormalizationSettings = state.editingVolumeNormalizationSettings.copy(strength = clampedStrength)
            )
        }
        updateAudioConfigIfActivePresetEditing()
    }
    
    /**
     * Обновить конфиг аудио если редактируется активный пресет
     */
    private fun updateAudioConfigIfActivePresetEditing() {
        val state = _uiState.value
        val isActivePreset = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        
        if (isActivePreset) {
            updateAudioConfig()
        }
    }

    // ============= Методы для управления общими настройками приложения =============
    
    fun setSampleRate(rate: SampleRate) {
        _uiState.update { it.copy(sampleRate = rate) }
        playbackService?.setSampleRate(rate)
        viewModelScope.launch {
            preferencesRepository.saveSampleRate(rate.value)
        }
    }
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        val clampedInterval = intervalMs.coerceIn(1000, 60000)
        _uiState.update { it.copy(frequencyUpdateIntervalMs = clampedInterval) }
        playbackService?.setFrequencyUpdateInterval(clampedInterval)
        viewModelScope.launch {
            preferencesRepository.saveFrequencyUpdateInterval(clampedInterval)
        }
    }

    fun setWavetableOptimizationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(wavetableOptimizationEnabled = enabled) }
        playbackService?.setWavetableOptimizationEnabled(enabled)
        viewModelScope.launch {
            preferencesRepository.saveWavetableOptimizationEnabled(enabled)
        }
    }

    fun setWavetableSize(size: Int) {
        _uiState.update { it.copy(wavetableSize = size) }
        playbackService?.setWavetableSize(size)
        viewModelScope.launch {
            preferencesRepository.saveWavetableSize(size)
        }
    }

    private fun updateAudioConfig() {
        val state = _uiState.value
        
        // Используем настройки из редактируемого пресета если редактируется активный
        val isActivePresetEditing = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        
        val (frequencyCurve, channelSwapSettings, volumeNormalizationSettings) = if (isActivePresetEditing) {
            Triple(
                state.editingFrequencyCurve ?: state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.editingChannelSwapSettings,
                state.editingVolumeNormalizationSettings
            )
        } else {
            Triple(
                state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.activePreset?.channelSwapSettings ?: ChannelSwapSettings(),
                state.activePreset?.volumeNormalizationSettings ?: VolumeNormalizationSettings()
            )
        }
        
        val config = BinauralConfig(
            frequencyCurve = frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = channelSwapSettings.enabled,
            channelSwapIntervalSeconds = channelSwapSettings.intervalSeconds,
            channelSwapFadeEnabled = channelSwapSettings.fadeEnabled,
            channelSwapFadeDurationMs = channelSwapSettings.fadeDurationMs,
            volumeNormalizationEnabled = volumeNormalizationSettings.enabled,
            volumeNormalizationStrength = volumeNormalizationSettings.strength
        )
        playbackService?.updateConfig(config)
    }

    // ============= Методы для экспорта/импорта пресетов =============
    
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    /**
     * Экспортировать пресет в JSON строку
     */
    fun exportPresetToJson(presetId: String): String? {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return null
        return try {
            json.encodeToString(preset)
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to export preset", e)
            null
        }
    }
    
    /**
     * Получить пресет для экспорта
     */
    fun getPresetForExport(presetId: String): BinauralPreset? {
        return _uiState.value.presets.find { it.id == presetId }
    }
    
    /**
     * Импортировать пресет из JSON
     * @return ID импортированного пресета или null при ошибке
     */
    fun importPresetFromJson(jsonString: String): String? {
        return try {
            val preset = json.decodeFromString<BinauralPreset>(jsonString)
            // Генерируем новый ID для импортированного пресета, чтобы избежать конфликтов
            val importedPreset = preset.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = generateUniqueName(preset.name),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            viewModelScope.launch {
                preferencesRepository.addPreset(importedPreset)
            }
            importedPreset.id
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to import preset", e)
            null
        }
    }
    
    /**
     * Импортировать пресет из Uri файла
     * @return ID импортированного пресета или null при ошибке
     */
    fun importPresetFromUri(uri: Uri): String? {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: return null
            
            importPresetFromJson(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to import preset from uri", e)
            null
        }
    }
    
    /**
     * Сгенерировать уникальное имя для импортированного пресета
     */
    private fun generateUniqueName(baseName: String): String {
        val existingNames = _uiState.value.presets.map { it.name }.toSet()
        if (baseName !in existingNames) return baseName
        
        var counter = 1
        while ("$baseName ($counter)" in existingNames) {
            counter++
        }
        return "$baseName ($counter)"
    }
    
    /**
     * Дублировать пресет
     */
    fun duplicatePreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val duplicatedPreset = preset.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = generateUniqueName(preset.name),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(duplicatedPreset)
        }
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