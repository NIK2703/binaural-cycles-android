package com.binauralcycles.viewmodel.events

/**
 * Одноразовые события для экрана редактирования пресета.
 */
sealed interface PresetEditEvent {
    /**
     * Пресет успешно сохранён
     */
    data class PresetSaved(val presetId: String) : PresetEditEvent
    
    /**
     * Пресет успешно создан
     */
    data class PresetCreated(val presetId: String) : PresetEditEvent
    
    /**
     * Показать ошибки валидации
     */
    data class ShowValidationErrors(val errors: List<String>) : PresetEditEvent
    
    /**
     * Показать ошибку
     */
    data class ShowError(val message: String) : PresetEditEvent
    
    /**
     * Навигация назад
     */
    data object NavigateBack : PresetEditEvent
}