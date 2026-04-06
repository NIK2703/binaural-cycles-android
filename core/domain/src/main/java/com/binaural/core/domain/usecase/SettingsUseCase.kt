package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.VolumeNormalizationSettings
import com.binaural.core.domain.model.SwapMode
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.core.domain.service.PlaybackController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * UseCase для управления настройками приложения.
 * Инкапсулирует бизнес-логику настроек и их применение к воспроизведению.
 */
class SettingsUseCase(
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController
) {
    // ========== Громкость ==========
    
    fun getVolume(): Flow<Float> = settingsRepository.getVolume()
    
    suspend fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        settingsRepository.saveVolume(clampedVolume)
        playbackController.setVolume(clampedVolume)
    }
    
    // ========== Частота дискретизации ==========
    
    fun getSampleRate(): Flow<Int> = settingsRepository.getSampleRate()
    
    suspend fun setSampleRate(rate: SampleRate) {
        settingsRepository.saveSampleRate(rate.value)
        playbackController.setSampleRate(rate)
    }
    
    // ========== Интервал генерации буфера ==========
    
    fun getBufferGenerationMinutes(): Flow<Int> = settingsRepository.getBufferGenerationMinutes()
    
    suspend fun setBufferGenerationMinutes(minutes: Int) {
        val clampedMinutes = minutes.coerceIn(1, 60)
        settingsRepository.saveBufferGenerationMinutes(clampedMinutes)
        playbackController.setFrequencyUpdateInterval(clampedMinutes * 60 * 1000)
    }
    
    // ========== Перестановка каналов ==========
    
    fun getChannelSwapSettings(): Flow<ChannelSwapSettings> = settingsRepository.getChannelSwapSettings()
    
    suspend fun setChannelSwapEnabled(enabled: Boolean) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val newSettings = currentSettings.copy(enabled = enabled)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setChannelSwapInterval(seconds: Int) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val clampedSeconds = seconds.coerceIn(5, 3600)
        val newSettings = currentSettings.copy(intervalSeconds = clampedSeconds)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setChannelSwapFadeEnabled(enabled: Boolean) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val newSettings = currentSettings.copy(fadeEnabled = enabled)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setChannelSwapFadeDuration(ms: Long) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val clampedMs = ms.coerceIn(1000L, 15000L)
        val newSettings = currentSettings.copy(fadeDurationMs = clampedMs)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setChannelSwapPauseDuration(ms: Long) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val clampedMs = ms.coerceIn(0L, 60000L)
        val newSettings = currentSettings.copy(pauseDurationMs = clampedMs)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setSwapMode(mode: SwapMode) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val newSettings = currentSettings.copy(swapMode = mode)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    suspend fun setChannelSwapEnabledAndMode(enabled: Boolean, mode: SwapMode) {
        val currentSettings = settingsRepository.getChannelSwapSettings().first()
        val newSettings = currentSettings.copy(enabled = enabled, swapMode = mode)
        settingsRepository.saveChannelSwapSettings(newSettings)
    }
    
    // ========== Нормализация громкости ==========
    
    fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings> = 
        settingsRepository.getVolumeNormalizationSettings()
    
    suspend fun setVolumeNormalizationEnabled(enabled: Boolean) {
        val currentSettings = settingsRepository.getVolumeNormalizationSettings().first()
        val newType = if (enabled) NormalizationType.CHANNEL else NormalizationType.NONE
        val newSettings = currentSettings.copy(type = newType)
        settingsRepository.saveVolumeNormalizationSettings(newSettings)
    }
    
    suspend fun setVolumeNormalizationStrength(strength: Float) {
        val currentSettings = settingsRepository.getVolumeNormalizationSettings().first()
        val clampedStrength = strength.coerceIn(0f, 2f)
        val newSettings = currentSettings.copy(strength = clampedStrength)
        settingsRepository.saveVolumeNormalizationSettings(newSettings)
    }
    
    suspend fun setTemporalNormalizationEnabled(enabled: Boolean) {
        val currentSettings = settingsRepository.getVolumeNormalizationSettings().first()
        val newType = if (enabled) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
        val newSettings = currentSettings.copy(type = newType)
        settingsRepository.saveVolumeNormalizationSettings(newSettings)
    }
    
    // ========== Автовозобновление ==========
    
    fun getResumeOnHeadsetConnect(): Flow<Boolean> = settingsRepository.getResumeOnHeadsetConnect()
    
    suspend fun setResumeOnHeadsetConnect(enabled: Boolean) {
        settingsRepository.saveResumeOnHeadsetConnect(enabled)
        playbackController.setResumeOnHeadsetConnect(enabled)
    }
    
    fun getAutoResumeOnAppStart(): Flow<Boolean> = settingsRepository.getAutoResumeOnAppStart()
    
    suspend fun setAutoResumeOnAppStart(enabled: Boolean) {
        settingsRepository.saveAutoResumeOnAppStart(enabled)
    }
    
    // ========== График ==========
    
    fun getAutoExpandGraphRange(): Flow<Boolean> = settingsRepository.getAutoExpandGraphRange()
    
    suspend fun setAutoExpandGraphRange(enabled: Boolean) {
        settingsRepository.saveAutoExpandGraphRange(enabled)
    }
    
}
