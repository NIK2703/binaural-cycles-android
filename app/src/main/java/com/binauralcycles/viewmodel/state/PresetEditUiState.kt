package com.binauralcycles.viewmodel.state

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import com.binaural.core.domain.model.RelaxationModeSettings

/**
 * Состояние UI для экрана редактирования пресета.
 */
data class PresetEditUiState(
    // Редактируемая кривая частот
    val editingFrequencyCurve: FrequencyCurve? = null,
    
    // ID редактируемого пресета (null для нового пресета)
    val editingPresetId: String? = null,
    val editingPresetName: String = "",
    
    // Оригинальный пресет для сравнения изменений (null для нового пресета)
    val originalPreset: BinauralPreset? = null,
    
    // Диапазоны частот для редактирования
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    
    // Выбранная точка на графике
    val selectedPointIndex: Int? = null,
    
    // Настройки режима расслабления
    val editingRelaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    
    // UI состояние
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // Автоматическое расширение границ графика
    val autoExpandGraphRange: Boolean = false,
    
    // Флаг, что редактируется активный пресет
    val isActivePreset: Boolean = false,
    
    // Состояние воспроизведения (для live preview)
    val isPlaying: Boolean = false,
    val isServiceConnected: Boolean = false,
    
    // Текущие частоты для отображения на графике (только для активного пресета)
    val currentCarrierFrequency: Float = 0f,
    val currentBeatFrequency: Float = 0f
) {
    // Проверка, это новый пресет или редактирование существующего
    val isNewPreset: Boolean get() = editingPresetId == null
    
    // Проверка, можно ли сохранить
    val canSave: Boolean get() = editingFrequencyCurve != null && 
                                  editingPresetName.isNotBlank() && 
                                  !isSaving
    
    // Текущий тип интерполяции
    val interpolationType: InterpolationType get() = editingFrequencyCurve?.interpolationType ?: InterpolationType.LINEAR
    
    // Текущее натяжение сплайна
    val splineTension: Float get() = editingFrequencyCurve?.splineTension ?: 0.0f
}