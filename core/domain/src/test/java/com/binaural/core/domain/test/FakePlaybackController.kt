package com.binaural.core.domain.test

import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.PlaybackState
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake реализация PlaybackController для тестирования.
 * Эмулирует воспроизведение без реального аудио.
 */
class FakePlaybackController : PlaybackController {
    
    // ========== Private State ==========
    
    private val _isPlaying = MutableStateFlow(false)
    private val _currentBeatFrequency = MutableStateFlow(10.0f)
    private val _currentCarrierFrequency = MutableStateFlow(220.0f)
    private val _isChannelsSwapped = MutableStateFlow(false)
    private val _elapsedSeconds = MutableStateFlow(0)
    private val _currentPresetName = MutableStateFlow<String?>(null)
    private val _playbackState = MutableStateFlow(PlaybackState())
    private val _connectionState = MutableStateFlow(false)
    
    private var currentConfig: BinauralConfig? = null
    private var currentRelaxationSettings: RelaxationModeSettings? = null
    private var currentFrequencyCurve: FrequencyCurve? = null
    private var _volume = 0.7f
    private var _sampleRate = SampleRate.MEDIUM
    private var _resumeOnHeadsetConnect = false
    private var _presetIds = emptyList<String>()
    private var _currentPresetId: String? = null
    private var _frequencyUpdateIntervalMs = 0
    private var presetSwitchCallback: ((String) -> Unit)? = null
    
    // ========== Методы для тестирования ==========
    
    /**
     * Установить текущую частоту биений (для симуляции)
     */
    fun setBeatFrequency(frequency: Float) {
        _currentBeatFrequency.value = frequency
    }
    
    /**
     * Установить текущую несущую частоту (для симуляции)
     */
    fun setCarrierFrequency(frequency: Float) {
        _currentCarrierFrequency.value = frequency
    }
    
    /**
     * Установить флаг перестановки каналов
     */
    fun setChannelsSwapped(swapped: Boolean) {
        _isChannelsSwapped.value = swapped
    }
    
    /**
     * Установить прошедшее время воспроизведения
     */
    fun setElapsedSeconds(seconds: Int) {
        _elapsedSeconds.value = seconds
    }
    
    /**
     * Получить текущую конфигурацию
     */
    fun getCurrentConfig(): BinauralConfig? = currentConfig
    
    /**
     * Получить текущие настройки расслабления
     */
    fun getCurrentRelaxationSettings(): RelaxationModeSettings? = currentRelaxationSettings
    
    /**
     * Получить текущую громкость
     */
    fun getCurrentVolume(): Float = _volume
    
    /**
     * Получить текущий интервал обновления частоты (мс)
     */
    fun getFrequencyUpdateInterval(): Int = _frequencyUpdateIntervalMs
    
    /**
     * Получить флаг возобновления при подключении гарнитуры
     */
    fun isResumeOnHeadsetConnectEnabled(): Boolean = _resumeOnHeadsetConnect
    
    /**
     * Получить список ID пресетов
     */
    fun getPresetIds(): List<String> = _presetIds
    
    /**
     * Получить текущий ID пресета
     */
    fun getCurrentPresetId(): String? = _currentPresetId
    
    // ========== Реализация интерфейса PlaybackController ==========
    
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    override val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()
    
    override val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()
    
    override val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
    
    override val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    
    override val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()
    
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    
    // ========== Управление воспроизведением ==========
    
    override fun play() {
        _isPlaying.value = true
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }
    
    override fun stop() {
        _isPlaying.value = false
        _elapsedSeconds.value = 0
        _playbackState.value = _playbackState.value.copy(isPlaying = false, elapsedSeconds = 0)
    }
    
    override fun stopWithFade() {
        _isPlaying.value = false
        _elapsedSeconds.value = 0
        _playbackState.value = _playbackState.value.copy(isPlaying = false, elapsedSeconds = 0)
    }
    
    override fun pauseWithFade() {
        _isPlaying.value = false
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }
    
    override fun resumeWithFade() {
        _isPlaying.value = true
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }
    
    override fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
        _playbackState.value = _playbackState.value.copy(isPlaying = _isPlaying.value)
    }
    
    // ========== Конфигурация ==========
    
    override fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings) {
        currentConfig = config
        currentRelaxationSettings = relaxationSettings
        _playbackState.value = _playbackState.value.copy(config = config)
    }
    
    override fun updateFrequencyCurve(curve: FrequencyCurve) {
        currentFrequencyCurve = curve
    }
    
    override fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        currentRelaxationSettings = settings
    }
    
    override fun setVolume(volume: Float) {
        _volume = volume
        _playbackState.value = _playbackState.value.copy(volume = volume)
    }
    
    override fun setSampleRate(rate: SampleRate) {
        _sampleRate = rate
    }
    
    override fun getSampleRate(): SampleRate = _sampleRate
    
    override fun setFrequencyUpdateInterval(intervalMs: Int) {
        _frequencyUpdateIntervalMs = intervalMs
    }
    
    // ========== Пресеты ==========
    
    override fun setCurrentPresetName(name: String?) {
        _currentPresetName.value = name
        _playbackState.value = _playbackState.value.copy(currentPresetName = name)
    }
    
    override fun setPresetIds(ids: List<String>) {
        _presetIds = ids
    }
    
    override fun setCurrentPresetId(id: String?) {
        _currentPresetId = id
        _playbackState.value = _playbackState.value.copy(currentPresetId = id)
    }
    
    override fun switchPresetWithFade(config: BinauralConfig) {
        currentConfig = config
        _playbackState.value = _playbackState.value.copy(config = config)
    }
    
    // ========== Гарнитура ==========
    
    override fun setResumeOnHeadsetConnect(enabled: Boolean) {
        _resumeOnHeadsetConnect = enabled
    }
    
    // ========== Lifecycle ==========
    
    override fun connect() {
        _connectionState.value = true
    }
    
    override fun disconnect() {
        _connectionState.value = false
    }
    
    override fun setOnPresetSwitchCallback(callback: (String) -> Unit) {
        presetSwitchCallback = callback
    }
    
    override fun onAppForeground() {
        // No-op в fake реализации
    }
    
    override fun onAppBackground() {
        // No-op в fake реализации
    }
    
    // ========== Вспомогательные методы для тестов ==========
    
    /**
     * Симулировать переключение пресета (вызывает callback)
     */
    fun simulatePresetSwitch(presetId: String) {
        presetSwitchCallback?.invoke(presetId)
    }
    
    /**
     * Сбросить состояние (для очистки между тестами)
     */
    fun reset() {
        _isPlaying.value = false
        _currentBeatFrequency.value = 10.0f
        _currentCarrierFrequency.value = 220.0f
        _isChannelsSwapped.value = false
        _elapsedSeconds.value = 0
        _currentPresetName.value = null
        _playbackState.value = PlaybackState()
        _connectionState.value = false
        currentConfig = null
        currentRelaxationSettings = null
        currentFrequencyCurve = null
        _volume = 0.7f
        _sampleRate = SampleRate.MEDIUM
        _resumeOnHeadsetConnect = false
        _presetIds = emptyList()
        _currentPresetId = null
        _frequencyUpdateIntervalMs = 0
        presetSwitchCallback = null
    }
}