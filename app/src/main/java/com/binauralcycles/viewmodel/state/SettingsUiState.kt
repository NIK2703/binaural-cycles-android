package com.binauralcycles.viewmodel.state

import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.VolumeNormalizationSettings

/**
 * Состояние UI для экрана настроек.
 */
data class SettingsUiState(
    // Аудио настройки
    val volume: Float = 0.7f,
    val sampleRate: SampleRate = SampleRate.HIGH,
    
    // Настройки каналов
    val channelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
    
    // Настройки нормализации громкости
    val volumeNormalizationSettings: VolumeNormalizationSettings = VolumeNormalizationSettings(),
    
    // UI состояние
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // Состояние воспроизведения
    val isPlaying: Boolean = false,
    val isServiceConnected: Boolean = false,
    val isChannelsSwapped: Boolean = false,
    
    // Настройки автовозобновления
    val resumeOnHeadsetConnect: Boolean = false,
    val autoResumeOnAppStart: Boolean = false,
    
    // Настройки буфера
    val bufferGenerationMinutes: Int = 5
)
