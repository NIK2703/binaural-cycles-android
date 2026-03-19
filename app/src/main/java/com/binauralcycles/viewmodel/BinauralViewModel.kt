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
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
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
    val currentBeatFrequency: Float = 0.0f,
    val currentCarrierFrequency: Float = 0.0f,
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
    // Настройки режима расслабления (для экрана редактирования)
    val editingRelaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    // Глобальные настройки нормализации громкости (не зависят от пресета)
    val volumeNormalizationSettings: VolumeNormalizationSettings = VolumeNormalizationSettings(),
    // Глобальные настройки перестановки каналов (не зависят от пресета)
    val channelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
    // Состояние перестановки каналов (из сервиса)
    val isChannelsSwapped: Boolean = false,
    // Общие настройки приложения
    val sampleRate: SampleRate = SampleRate.LOW,
    val frequencyUpdateIntervalMs: Int = 10000,
    // Автоматическое расширение границ графика при редактировании (по умолчанию выключено)
    val autoExpandGraphRange: Boolean = false,
    // Флаг подключения к сервису
    val isServiceConnected: Boolean = false,
    // Флаг блокировки навигации во время SharedTransition анимации
    val isSharedTransitionRunning: Boolean = false,
    // Возобновление воспроизведения при подключении гарнитуры
    val resumeOnHeadsetConnect: Boolean = false
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
            
            // При подключении сервиса ВСЕГДА обновляем конфиг
            // Это гарантирует, что настройки (включая channelSwap) будут применены
            // даже если сервис подключился после загрузки пресетов
            val state = _uiState.value
            if (state.activePreset != null) {
                android.util.Log.d("BinauralViewModel", "Updating audio config for active preset: ${state.activePreset.name}, channelSwap=${state.channelSwapSettings.enabled}")
                // Устанавливаем название активного пресета для уведомления
                playbackService?.setCurrentPresetName(state.activePreset.name)
                playbackService?.setCurrentPresetId(state.activePreset.id)
            }
            // Всегда вызываем updateAudioConfig - это обновит конфиг даже если activePreset ещё не загружен
            // (в этом случае будет использован дефолтный конфиг, который потом заменится при загрузке пресета)
            updateAudioConfig()
            
            // Устанавливаем настройки, которые могли быть загружены до подключения сервиса
            playbackService?.setFrequencyUpdateInterval(state.frequencyUpdateIntervalMs)
            playbackService?.setVolume(state.volume)
            playbackService?.setSampleRate(state.sampleRate)
            playbackService?.setResumeOnHeadsetConnect(state.resumeOnHeadsetConnect)
            
            // Устанавливаем список ID пресетов для переключения с гарнитуры
            val presetIdList = state.presets.map { it.id }
            playbackService?.setPresetIds(presetIdList)
            playbackService?.setCurrentPresetId(state.activePreset?.id)
            
            // Устанавливаем callback для переключения пресетов с гарнитуры
            playbackService?.onPresetSwitch = { presetId ->
                playPreset(presetId)
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
                
                // Обновляем список ID пресетов в сервисе для переключения с гарнитуры
                playbackService?.setPresetIds(presets.map { it.id })
                
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
                        // Устанавливаем название пресета для уведомления
                        playbackService?.setCurrentPresetName(activePreset.name)
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
        // Громкость - загружаем только для UI
        // Громкость в сервис устанавливается через setVolumeImmediate() при движении слайдера
        // или при подключении сервиса в onServiceConnected
        viewModelScope.launch {
            preferencesRepository.getVolume().collect { volume ->
                _uiState.update { it.copy(volume = volume) }
            }
        }
        // Глобальные настройки перестановки каналов
        viewModelScope.launch {
            preferencesRepository.getChannelSwapSettings().collect { settings ->
                _uiState.update { it.copy(channelSwapSettings = settings) }
                // Обновляем конфиг аудио при изменении настроек каналов
                updateAudioConfig()
            }
        }
        // Глобальные настройки нормализации громкости
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationSettings().collect { settings ->
                _uiState.update { it.copy(volumeNormalizationSettings = settings) }
                // Обновляем конфиг аудио при изменении настроек нормализации
                updateAudioConfig()
            }
        }
        // Возобновление воспроизведения при подключении гарнитуры
        viewModelScope.launch {
            preferencesRepository.getResumeOnHeadsetConnect().collect { enabled ->
                _uiState.update { it.copy(resumeOnHeadsetConnect = enabled) }
                // Уведомляем сервис об изменении настройки
                playbackService?.setResumeOnHeadsetConnect(enabled)
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
        
                // Устанавливаем название пресета для уведомления
                playbackService?.setCurrentPresetName(preset.name)
                
                // Обновляем текущий ID пресета в сервисе
                playbackService?.setCurrentPresetId(presetId)
        
        // Формируем конфиг из глобальных настроек каналов и нормализации
        val config = BinauralConfig(
            frequencyCurve = preset.frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = state.channelSwapSettings.enabled,
            channelSwapIntervalSeconds = state.channelSwapSettings.intervalSeconds,
            channelSwapFadeEnabled = state.channelSwapSettings.fadeEnabled,
            channelSwapFadeDurationMs = state.channelSwapSettings.fadeDurationMs,
            channelSwapPauseDurationMs = state.channelSwapSettings.pauseDurationMs,
            normalizationType = state.volumeNormalizationSettings.type,
            volumeNormalizationStrength = state.volumeNormalizationSettings.strength
        )
        
        val relaxationSettings = preset.relaxationModeSettings
        
        // Если воспроизводится другой пресет - используем stopWithFade + play для плавного переключения
        // Сначала fade-out на старом пресете, затем fade-in на новом
        if (state.isPlaying) {
            // Сначала останавливаем с fade-out на старом пресете
            playbackService?.stopWithFade()
            // Ждем завершения fade-out (250мс + запас), затем запускаем новый пресет
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                // Применяем новый конфиг
                playbackService?.updateConfig(config, relaxationSettings)
                // Запускаем воспроизведение с fade-in
                playbackService?.play()
            }
        } else {
            // Не воспроизводится - просто обновляем конфиг и запускаем
            playbackService?.updateConfig(config, relaxationSettings)
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
                selectedPointIndex = null,  // Сбрасываем выбранную точку при начале редактирования
                editingRelaxationModeSettings = preset.relaxationModeSettings
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
                editingRelaxationModeSettings = RelaxationModeSettings()
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
                editingRelaxationModeSettings = RelaxationModeSettings()
            )
        }
        
        // Восстанавливаем кривую активного пресета в сервисе
        if (activePreset != null) {
            playbackService?.updateFrequencyCurve(activePreset.frequencyCurve)
        }
    }
    
    /**
     * Восстановить кривую активного пресета в сервисе без очистки состояния редактирования.
     * Используется при выходе с экрана редактирования для плавной анимации.
     */
    fun cancelEditingInService() {
        val activePreset = _uiState.value.activePreset
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
                editingRelaxationModeSettings = RelaxationModeSettings()
            )
        }
        // Не восстанавливаем кривую в сервисе - новые данные загрузятся через Flow
    }
    
    /**
     * Завершить редактирование без очистки состояния (для плавной анимации).
     * Используется после сохранения - данные загрузятся через Flow.
     */
    fun finishEditingWithoutClear() {
        // Ничего не делаем - состояние очистится при следующем редактировании
        // или при входе в другой экран редактирования через startEditingPreset/startNewPreset
    }
    
    /**
     * Создать новый пресет
     */
    fun createPreset(
        name: String, 
        curve: FrequencyCurve, 
        relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    ) {
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = curve,
            relaxationModeSettings = relaxationModeSettings
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(preset)
        }
    }
    
    /**
     * Сохранить редактируемый пресет
     */
    fun saveEditingPreset(
        presetId: String, 
        name: String, 
        curve: FrequencyCurve, 
        relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    ) {
        val existingPreset = _uiState.value.presets.find { it.id == presetId } ?: return
        val updatedPreset = existingPreset.copy(
            name = name,
            frequencyCurve = curve,
            relaxationModeSettings = relaxationModeSettings,
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

    /**
     * Установить громкость мгновенно (без сохранения в preferences).
     * Вызывается при движении слайдера для мгновенного применения к аудио-движку.
     */
    fun setVolumeImmediate(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackService?.setVolume(volume)
    }
    
    /**
     * Сохранить текущую громкость в preferences.
     * Вызывается при отпускании слайдера.
     */
    fun saveVolume() {
        viewModelScope.launch {
            preferencesRepository.saveVolume(_uiState.value.volume)
        }
    }

    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }

    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
    }

    // ============= Методы для редактирования точек (редактируемая кривая) =============
    
    companion object {
        private const val MIN_AUDIBLE_FREQUENCY = 20.0f
        private const val MAX_FREQUENCY = 2000.0f
        
        /**
         * Вычисляет скорректированную частоту биения для заданной несущей частоты.
         * Учитывает обе границы: нижнюю (20 Гц) и верхнюю (2000 Гц).
         * Нижняя граница: carrier - beat/2 >= MIN_AUDIBLE_FREQUENCY => beat <= 2 * (carrier - MIN_AUDIBLE_FREQUENCY)
         * Верхняя граница: carrier + beat/2 <= MAX_FREQUENCY => beat <= 2 * (MAX_FREQUENCY - carrier)
         */
        private fun adjustBeatForCarrier(carrier: Float, currentBeat: Float): Float {
            val maxBeatForLower = ((carrier - MIN_AUDIBLE_FREQUENCY) * 2).coerceAtLeast(0.0f)
            val maxBeatForUpper = ((MAX_FREQUENCY - carrier) * 2).coerceAtLeast(0.0f)
            val maxBeat = minOf(maxBeatForLower, maxBeatForUpper)
            return currentBeat.coerceAtMost(maxBeat)
        }
        
        /**
         * Вычисляет скорректированную частоту биения с учётом границ графика.
         * Дополнительно учитывает, что боковые частоты не должны выходить за границы графика.
         */
        private fun adjustBeatForCarrierWithRange(carrier: Float, currentBeat: Float, carrierRange: FrequencyRange): Float {
            // Глобальные ограничения (20-2000 Гц)
            val maxBeatForGlobalLower = ((carrier - MIN_AUDIBLE_FREQUENCY) * 2).coerceAtLeast(0.0f)
            val maxBeatForGlobalUpper = ((MAX_FREQUENCY - carrier) * 2).coerceAtLeast(0.0f)
            
            // Ограничения границ графика
            val maxBeatForRangeLower = ((carrier - carrierRange.min) * 2).coerceAtLeast(0.0f)
            val maxBeatForRangeUpper = ((carrierRange.max - carrier) * 2).coerceAtLeast(0.0f)
            
            // Берём минимум из всех ограничений
            val maxBeat = minOf(maxBeatForGlobalLower, maxBeatForGlobalUpper, maxBeatForRangeLower, maxBeatForRangeUpper)
            return currentBeat.coerceAtMost(maxBeat)
        }
    }
    
    fun updateEditingPointCarrierFrequency(frequency: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val index = state.selectedPointIndex ?: return
        
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (newCarrier, adjustedBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                // Автоматическое расширение границ (старое поведение)
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
                // Ограничение частот заданными границами графика (новое поведение по умолчанию)
                // Ограничиваем несущую частоту диапазоном графика
                val carrier = curve.carrierRange.clamp(frequency)
                // Корректируем частоту биений с учётом границ
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
                // Автоматическое расширение границ (старое поведение)
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
                // Ограничение частот заданными границами графика (новое поведение по умолчанию)
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
                // Автоматическое расширение границ (старое поведение)
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
                // Ограничение частот заданными границами графика (новое поведение по умолчанию)
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
                // Автоматическое расширение границ (старое поведение)
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
                // Ограничение частот заданными границами графика (новое поведение по умолчанию)
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
        // Ограничение частоты биений:
        // 1. Нижняя боковая >= 20 Гц: carrier - beat/2 >= 20 → beat <= 2*(carrier - 20)
        // 2. Верхняя боковая <= 2000 Гц: carrier + beat/2 <= 2000 → beat <= 2*(2000 - carrier)
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

    // ============= Методы для редактирования режима расслабления =============
    
    /**
     * Включить/выключить режим расслабления
     */
    fun setEditingRelaxationModeEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(enabled = enabled)
            )
        }
    }
    
    /**
     * Установить процент снижения несущей частоты
     */
    fun setEditingCarrierReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, 50)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(carrierReductionPercent = clampedPercent)
            )
        }
    }
    
    /**
     * Установить процент снижения частоты биений
     */
    fun setEditingBeatReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, 100)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(beatReductionPercent = clampedPercent)
            )
        }
    }
    
    /**
     * Установить режим расслабления (SIMPLE или ADVANCED)
     */
    fun setEditingRelaxationMode(mode: RelaxationMode) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(mode = mode)
            )
        }
    }
    
    /**
     * Установить паузу между периодами расслабления (в минутах)
     */
    fun setEditingRelaxationGapMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(0, 120)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(gapBetweenRelaxationMinutes = clampedMinutes)
            )
        }
    }
    
    /**
     * Установить длительность расслабления (в минутах)
     */
    fun setEditingRelaxationDurationMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(10, 60)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(relaxationDurationMinutes = clampedMinutes)
            )
        }
    }
    
    /**
     * Установить период перехода (в минутах)
     */
    fun setEditingTransitionPeriodMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(1, 15)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(transitionPeriodMinutes = clampedMinutes)
            )
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
    
    // ============= Методы для управления глобальной нормализацией громкости =============
    
    /**
     * Включить/выключить нормализацию громкости (глобальная настройка)
     */
    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        val state = _uiState.value
        // При включении устанавливаем CHANNEL, при выключении - NONE
        val newType = if (enabled) NormalizationType.CHANNEL else NormalizationType.NONE
        val newSettings = state.volumeNormalizationSettings.copy(type = newType)
        _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveVolumeNormalizationSettings(newSettings)
        }
    }
    
    /**
     * Установить силу нормализации громкости (глобальная настройка)
     */
    fun setVolumeNormalizationStrength(strength: Float) {
        val state = _uiState.value
        val clampedStrength = strength.coerceIn(0f, 2f)
        val newSettings = state.volumeNormalizationSettings.copy(strength = clampedStrength)
        _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveVolumeNormalizationSettings(newSettings)
        }
    }
    
    /**
     * Включить/выключить временную нормализацию (глобальная настройка)
     */
    fun setTemporalNormalizationEnabled(enabled: Boolean) {
        val state = _uiState.value
        // При включении временной нормализации устанавливаем TEMPORAL
        val newType = if (enabled) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
        val newSettings = state.volumeNormalizationSettings.copy(type = newType)
        _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveVolumeNormalizationSettings(newSettings)
        }
    }
    
    // ============= Методы для управления настройками перестановки каналов =============
    
    /**
     * Включить/выключить перестановку каналов
     */
    fun setChannelSwapEnabled(enabled: Boolean) {
        val state = _uiState.value
        val newSettings = state.channelSwapSettings.copy(enabled = enabled)
        _uiState.update { it.copy(channelSwapSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapSettings(newSettings)
        }
    }
    
    /**
     * Установить интервал перестановки каналов
     */
    fun setChannelSwapInterval(seconds: Int) {
        val state = _uiState.value
        val clampedSeconds = seconds.coerceIn(5, 3600)
        val newSettings = state.channelSwapSettings.copy(intervalSeconds = clampedSeconds)
        _uiState.update { it.copy(channelSwapSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapSettings(newSettings)
        }
    }
    
    /**
     * Включить/выключить плавный переход при перестановке каналов
     */
    fun setChannelSwapFadeEnabled(enabled: Boolean) {
        val state = _uiState.value
        val newSettings = state.channelSwapSettings.copy(fadeEnabled = enabled)
        _uiState.update { it.copy(channelSwapSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapSettings(newSettings)
        }
    }
    
    /**
     * Установить длительность плавного перехода при перестановке каналов
     */
    fun setChannelSwapFadeDuration(ms: Long) {
        val state = _uiState.value
        val clampedMs = ms.coerceIn(1000L, 15000L)
        val newSettings = state.channelSwapSettings.copy(fadeDurationMs = clampedMs)
        _uiState.update { it.copy(channelSwapSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapSettings(newSettings)
        }
    }
    
    /**
     * Установить длительность паузы при переключении каналов (до 1 минуты)
     */
    fun setChannelSwapPauseDuration(ms: Long) {
        val state = _uiState.value
        val clampedMs = ms.coerceIn(0L, 60000L)
        val newSettings = state.channelSwapSettings.copy(pauseDurationMs = clampedMs)
        _uiState.update { it.copy(channelSwapSettings = newSettings) }
        viewModelScope.launch {
            preferencesRepository.saveChannelSwapSettings(newSettings)
        }
    }
    
    /**
     * Включить/выключить возобновление воспроизведения при подключении гарнитуры
     */
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        _uiState.update { it.copy(resumeOnHeadsetConnect = enabled) }
        playbackService?.setResumeOnHeadsetConnect(enabled)
        viewModelScope.launch {
            preferencesRepository.saveResumeOnHeadsetConnect(enabled)
        }
    }

    private fun updateAudioConfig() {
        val state = _uiState.value
        
        // Используем настройки из редактируемого пресета если редактируется активный
        val isActivePresetEditing = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        
        // Настройки каналов и нормализации всегда берём из глобального состояния
        val channelSwapSettings = state.channelSwapSettings
        val volumeNormalizationSettings = state.volumeNormalizationSettings
        
        val (frequencyCurve, relaxationModeSettings) = if (isActivePresetEditing) {
            Pair(
                state.editingFrequencyCurve ?: state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.editingRelaxationModeSettings
            )
        } else {
            Pair(
                state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.activePreset?.relaxationModeSettings ?: RelaxationModeSettings()
            )
        }
        
        val config = BinauralConfig(
            frequencyCurve = frequencyCurve,
            volume = state.volume,
            channelSwapEnabled = channelSwapSettings.enabled,
            channelSwapIntervalSeconds = channelSwapSettings.intervalSeconds,
            channelSwapFadeEnabled = channelSwapSettings.fadeEnabled,
            channelSwapFadeDurationMs = channelSwapSettings.fadeDurationMs,
            channelSwapPauseDurationMs = channelSwapSettings.pauseDurationMs,
            normalizationType = volumeNormalizationSettings.type,
            volumeNormalizationStrength = volumeNormalizationSettings.strength
        )
        
        android.util.Log.d("BinauralViewModel", "updateAudioConfig: activePreset=${state.activePreset?.name}, " +
            "channelSwapEnabled=${channelSwapSettings.enabled}, " +
            "channelSwapInterval=${channelSwapSettings.intervalSeconds}s, " +
            "normalizationType=${volumeNormalizationSettings.type}, " +
            "relaxationEnabled=${relaxationModeSettings.enabled}, " +
            "isServiceConnected=${state.isServiceConnected}, " +
            "isActivePresetEditing=$isActivePresetEditing")
        
        playbackService?.updateConfig(config, relaxationModeSettings)
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
     * Сгенерировать уникальное имя для дубликата/импортированного пресета
     * Извлекает базовое имя (без номера в скобках) и находит следующий доступный номер
     */
    private fun generateUniqueName(baseName: String): String {
        val existingNames = _uiState.value.presets.map { it.name }.toSet()
        
        // Пытаемся извлечь базовое имя и номер из строки вида "имя (N)"
        val regex = """^(.+?) \((\d+)\)$""".toRegex()
        val match = regex.find(baseName)
        
        // Если имя уже содержит номер в скобках, извлекаем базовое имя
        val actualBaseName = if (match != null) {
            match.groupValues[1]
        } else {
            baseName
        }
        
        // Ищем все существующие имена с таким же базовым именем
        val usedNumbers = mutableSetOf<Int>()
        var hasExactBaseName = false
        
        for (name in existingNames) {
            if (name == actualBaseName) {
                hasExactBaseName = true
            } else {
                val nameMatch = regex.find(name)
                if (nameMatch != null && nameMatch.groupValues[1] == actualBaseName) {
                    usedNumbers.add(nameMatch.groupValues[2].toInt())
                }
            }
        }
        
        // Если базовое имя свободно, используем его
        if (!hasExactBaseName && actualBaseName !in existingNames) {
            return actualBaseName
        }
        
        // Находим минимальный свободный номер, начиная с 1
        var counter = 1
        while (counter in usedNumbers) {
            counter++
        }
        
        return "$actualBaseName ($counter)"
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
    
    // ============= Методы для управления блокировкой навигации =============
    
    /**
     * Начать SharedTransition анимацию (блокирует навигацию)
     */
    fun startSharedTransition() {
        _uiState.update { it.copy(isSharedTransitionRunning = true) }
    }
    
    /**
     * Завершить SharedTransition анимацию (разблокирует навигацию)
     */
    fun endSharedTransition() {
        _uiState.update { it.copy(isSharedTransitionRunning = false) }
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