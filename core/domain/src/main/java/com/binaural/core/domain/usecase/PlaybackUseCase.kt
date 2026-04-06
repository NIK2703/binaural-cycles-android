package com.binaural.core.domain.usecase

import com.binaural.core.domain.service.PlaybackController
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import kotlinx.coroutines.flow.StateFlow

/**
 * UseCase для управления воспроизведением.
 * Инкапсулирует бизнес-логику воспроизведения аудио.
 */
class PlaybackUseCase(
    private val playbackController: PlaybackController
) {
    // ========== Состояние (делегирование) ==========
    
    val isPlaying: StateFlow<Boolean> get() = playbackController.isPlaying
    val currentBeatFrequency: StateFlow<Float> get() = playbackController.currentBeatFrequency
    val currentCarrierFrequency: StateFlow<Float> get() = playbackController.currentCarrierFrequency
    val isChannelsSwapped: StateFlow<Boolean> get() = playbackController.isChannelsSwapped
    val elapsedSeconds: StateFlow<Int> get() = playbackController.elapsedSeconds
    val currentPresetName: StateFlow<String?> get() = playbackController.currentPresetName
    
    // ========== Управление воспроизведением ==========
    
    fun play() = playbackController.play()
    fun stop() = playbackController.stop()
    fun stopWithFade() = playbackController.stopWithFade()
    fun pauseWithFade() = playbackController.pauseWithFade()
    fun resumeWithFade() = playbackController.resumeWithFade()
    fun togglePlayback() = playbackController.togglePlayback()
    
    // ========== Конфигурация ==========
    
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings) =
        playbackController.updateConfig(config, relaxationSettings)
    
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) =
        playbackController.updateRelaxationModeSettings(settings)
    
    fun setVolume(volume: Float) = playbackController.setVolume(volume)
    fun setSampleRate(rate: SampleRate) = playbackController.setSampleRate(rate)
    fun getSampleRate(): SampleRate = playbackController.getSampleRate()
    fun setFrequencyUpdateInterval(intervalMs: Int) = playbackController.setFrequencyUpdateInterval(intervalMs)
    
    // ========== Пресеты ==========
    
    fun setCurrentPresetName(name: String?) = playbackController.setCurrentPresetName(name)
    fun switchPresetWithFade(config: BinauralConfig) = playbackController.switchPresetWithFade(config)
    
    // ========== Гарнитура ==========
    
    fun setResumeOnHeadsetConnect(enabled: Boolean) = playbackController.setResumeOnHeadsetConnect(enabled)
    
    // ========== Lifecycle ==========
    
    fun onAppForeground() = playbackController.onAppForeground()
    fun onAppBackground() = playbackController.onAppBackground()
}