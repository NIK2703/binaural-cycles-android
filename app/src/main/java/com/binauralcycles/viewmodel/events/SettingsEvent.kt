package com.binauralcycles.viewmodel.events

/**
 * Одноразовые события для экрана настроек.
 */
sealed interface SettingsEvent {
    /**
     * Показать сообщение
     */
    data class ShowToast(val message: String) : SettingsEvent
    
    /**
     * Показать ошибку
     */
    data class ShowError(val message: String) : SettingsEvent
    
    /**
     * Настройки сохранены
     */
    data object SettingsSaved : SettingsEvent
}