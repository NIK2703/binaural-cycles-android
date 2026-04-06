package com.binaural.core.domain.service

import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.PlaybackState
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.VolumeNormalizationSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Интерфейс для управления воспроизведением.
 * Абстрагирует работу с сервисом воспроизведения.
 */
interface PlaybackController {
    
    // ========== Состояние воспроизведения ==========
    
    /**
     * Единый StateFlow с полным состоянием воспроизведения.
     */
    val playbackState: StateFlow<PlaybackState>
    
    val isPlaying: StateFlow<Boolean>
    val currentBeatFrequency: StateFlow<Float>
    val currentCarrierFrequency: StateFlow<Float>
    val isChannelsSwapped: StateFlow<Boolean>
    val elapsedSeconds: StateFlow<Int>
    val currentPresetName: StateFlow<String?>
    
    // ========== Управление воспроизведением ==========
    
    fun play()
    fun stop()
    fun stopWithFade()
    fun pauseWithFade()
    fun resumeWithFade()
    fun togglePlayback()
    
    // ========== Конфигурация ==========
    
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings)
    fun updateFrequencyCurve(curve: FrequencyCurve)
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings)
    /**
     * Обновить глобальные настройки (перестановка каналов, нормализация).
     * Вызывается из SettingsViewModel при изменении настроек с перезапуском воспроизведения.
     */
    fun updateGlobalSettings(
        channelSwapSettings: ChannelSwapSettings,
        volumeNormalizationSettings: VolumeNormalizationSettings
    )
    fun setVolume(volume: Float)
    fun setSampleRate(rate: SampleRate)
    fun getSampleRate(): SampleRate
    fun setFrequencyUpdateInterval(intervalMs: Int)
    
    // ========== Пресеты ==========
    
    fun setCurrentPresetName(name: String?)
    fun setPresetIds(ids: List<String>)
    fun setCurrentPresetId(id: String?)
    fun switchPresetWithFade(config: BinauralConfig)
    
    // ========== Гарнитура ==========
    
    fun setResumeOnHeadsetConnect(enabled: Boolean)
    
    // ========== Lifecycle ==========
    
    fun connect()
    fun disconnect()
    val connectionState: StateFlow<Boolean>
    fun setOnPresetSwitchCallback(callback: (String) -> Unit)
    fun onAppForeground()
    fun onAppBackground()
}