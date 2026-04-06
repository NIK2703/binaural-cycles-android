package com.binauralcycles.service

import android.content.Context
import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.PlaybackState
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.VolumeNormalizationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация PlaybackController через ServiceLifecycleManager.
 * Предоставляет абстракцию над сервисом для ViewModel.
 * 
 * Рефакторинг: использует PlaybackStateRepository вместо собственных StateFlow.
 */
@Singleton
class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateRepository: PlaybackStateRepository,
    private val serviceLifecycleManager: ServiceLifecycleManager
) : PlaybackController {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // StateFlow для состояния подключения
    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    
    // Callback для переключения пресетов
    private var presetSwitchCallback: ((String) -> Unit)? = null
    
    // Кэшированные StateFlows для отдельных полей
    private val _isPlaying = MutableStateFlow(false)
    private val _currentBeatFrequency = MutableStateFlow(0f)
    private val _currentCarrierFrequency = MutableStateFlow(0f)
    private val _isChannelsSwapped = MutableStateFlow(false)
    private val _elapsedSeconds = MutableStateFlow(0)
    private val _currentPresetName = MutableStateFlow<String?>(null)
    
    init {
        // Настраиваем callbacks для ServiceLifecycleManager
        serviceLifecycleManager.onServiceConnected = { service ->
            _connectionState.value = true
        }
        serviceLifecycleManager.onServiceDisconnected = {
            _connectionState.value = false
        }
        
        // Подписываемся на изменения состояния из repository
        scope.launch {
            playbackStateRepository.playbackState.collectLatest { state ->
                _isPlaying.value = state.isPlaying
                _currentBeatFrequency.value = state.currentBeatFrequency
                _currentCarrierFrequency.value = state.currentCarrierFrequency
                _isChannelsSwapped.value = state.isChannelsSwapped
                _elapsedSeconds.value = state.elapsedSeconds
                _currentPresetName.value = state.currentPresetName
            }
        }
    }
    
    // ========== Lifecycle методы (реализация интерфейса) ==========
    
    override fun connect() {
        serviceLifecycleManager.connect()
    }
    
    override fun disconnect() {
        serviceLifecycleManager.disconnect()
    }
    
    override fun setOnPresetSwitchCallback(callback: (String) -> Unit) {
        presetSwitchCallback = callback
        serviceLifecycleManager.getService()?.onPresetSwitch = callback
    }
    
    /**
     * Проверить, подключен ли сервис
     */
    fun isConnected(): Boolean = serviceLifecycleManager.isConnected()
    
    // ========== PlaybackController implementation ==========
    
    override val playbackState: StateFlow<PlaybackState>
        get() = playbackStateRepository.playbackState
    
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()
    override val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()
    override val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
    override val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    override val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()
    
    // ========== Управление воспроизведением ==========
    
    override fun play() {
        serviceLifecycleManager.getService()?.play()
    }
    
    override fun stop() {
        serviceLifecycleManager.getService()?.stop()
    }
    
    override fun stopWithFade() {
        serviceLifecycleManager.getService()?.stopWithFade()
    }
    
    override fun pauseWithFade() {
        serviceLifecycleManager.getService()?.pauseWithFade()
    }
    
    override fun resumeWithFade() {
        serviceLifecycleManager.getService()?.resumeWithFade()
    }
    
    override fun togglePlayback() {
        serviceLifecycleManager.getService()?.togglePlayback()
    }
    
    // ========== Конфигурация ==========
    
    override fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings) {
        serviceLifecycleManager.getService()?.updateConfig(config, relaxationSettings)
    }
    
    override fun updateFrequencyCurve(curve: FrequencyCurve) {
        serviceLifecycleManager.getService()?.updateFrequencyCurve(curve)
    }
    
    override fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        serviceLifecycleManager.getService()?.updateRelaxationModeSettings(settings)
    }
    
    override fun updateGlobalSettings(
        channelSwapSettings: ChannelSwapSettings,
        volumeNormalizationSettings: VolumeNormalizationSettings
    ) {
        serviceLifecycleManager.getService()?.updateGlobalSettings(
            channelSwapSettings,
            volumeNormalizationSettings
        )
    }
    
    override fun setVolume(volume: Float) {
        serviceLifecycleManager.getService()?.setVolume(volume)
    }
    
    override fun setSampleRate(rate: SampleRate) {
        serviceLifecycleManager.getService()?.setSampleRate(rate)
    }
    
    override fun getSampleRate(): SampleRate {
        return serviceLifecycleManager.getService()?.getSampleRate() ?: SampleRate.MEDIUM
    }
    
    override fun setFrequencyUpdateInterval(intervalMs: Int) {
        serviceLifecycleManager.getService()?.setFrequencyUpdateInterval(intervalMs)
    }
    
    // ========== Пресеты ==========
    
    override fun setCurrentPresetName(name: String?) {
        serviceLifecycleManager.getService()?.setCurrentPresetName(name)
    }
    
    override fun setPresetIds(ids: List<String>) {
        serviceLifecycleManager.getService()?.setPresetIds(ids)
    }
    
    override fun setCurrentPresetId(id: String?) {
        serviceLifecycleManager.getService()?.setCurrentPresetId(id)
    }
    
    override fun switchPresetWithFade(config: BinauralConfig) {
        serviceLifecycleManager.getService()?.switchPresetWithFade(config)
    }
    
    // ========== Гарнитура ==========
    
    override fun setResumeOnHeadsetConnect(enabled: Boolean) {
        serviceLifecycleManager.getService()?.setResumeOnHeadsetConnect(enabled)
    }
    
    // ========== Lifecycle ==========
    
    override fun onAppForeground() {
        serviceLifecycleManager.onAppForeground()
    }
    
    override fun onAppBackground() {
        serviceLifecycleManager.onAppBackground()
    }
}
