package com.binauralcycles.viewmodel.state

import com.binaural.core.domain.model.BinauralPreset

/**
 * Состояние UI для панели воспроизведения (bottom panel).
 * Используется на всех экранах.
 */
data class PlaybackUiState(
    // Состояние воспроизведения
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Float = 0.0f,
    val currentCarrierFrequency: Float = 0.0f,
    val volume: Float = 0.7f,
    val isChannelsSwapped: Boolean = false,
    
    // Активный пресет
    val activePreset: BinauralPreset? = null,
    val activePresetName: String? = null,
    
    // Состояние подключения к сервису
    val isServiceConnected: Boolean = false
)