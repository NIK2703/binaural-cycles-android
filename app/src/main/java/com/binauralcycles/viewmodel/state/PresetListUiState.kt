package com.binauralcycles.viewmodel.state

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve

/**
 * Состояние UI для экрана списка пресетов.
 */
data class PresetListUiState(
    // Список пресетов с использованием UiState для loading/error
    val presetsState: UiState<List<BinauralPreset>> = UiState.Loading,
    
    // Активный пресет
    val activePreset: BinauralPreset? = null,
    
    // Состояние воспроизведения (для отображения на карточках)
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Float = 0.0f,
    val currentCarrierFrequency: Float = 0.0f,
    val isChannelsSwapped: Boolean = false,
    
    // Состояние редактируемой кривой (для анимации перехода)
    val editingFrequencyCurve: FrequencyCurve? = null,
    val editingPresetId: String? = null,
    val editingPresetName: String? = null,
    
    // Флаг подключения к сервису
    val isServiceConnected: Boolean = false,
    
    // UI состояние
    val isLoading: Boolean = false,
    val error: String? = null
) {
    // Удобные accessor'ы
    val presets: List<BinauralPreset> get() = presetsState.getOrNull() ?: emptyList()
    val hasPresets: Boolean get() = presets.isNotEmpty()
    val isLoadingPresets: Boolean get() = presetsState.isLoading
    val presetsError: String? get() = presetsState.getErrorMessage()
}