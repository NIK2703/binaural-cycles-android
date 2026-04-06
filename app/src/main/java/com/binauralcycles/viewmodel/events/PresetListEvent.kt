package com.binauralcycles.viewmodel.events

/**
 * Одноразовые события для экрана списка пресетов.
 * Используются для навигации, показа Toast и других действий,
 * которые не должны переживать пересоздание экрана.
 */
sealed interface PresetListEvent {
    /**
     * Показать сообщение об ошибке
     */
    data class ShowError(val message: String) : PresetListEvent
    
    /**
     * Навигация к редактированию пресета
     */
    data class NavigateToEdit(val presetId: String) : PresetListEvent
    
    /**
     * Навигация к созданию нового пресета
     */
    data object NavigateToNewPreset : PresetListEvent
    
    /**
     * Пресет успешно удалён
     */
    data class PresetDeleted(val presetName: String) : PresetListEvent
    
    /**
     * Пресет успешно экспортирован
     */
    data class ShowExportSuccess(val fileName: String) : PresetListEvent
    
    /**
     * Пресет успешно импортирован
     */
    data class ShowImportSuccess(val presetName: String) : PresetListEvent
    
    /**
     * Пресет успешно дублирован
     */
    data class ShowDuplicateSuccess(val presetName: String) : PresetListEvent
    
    /**
     * Навигация назад
     */
    data object NavigateBack : PresetListEvent
}