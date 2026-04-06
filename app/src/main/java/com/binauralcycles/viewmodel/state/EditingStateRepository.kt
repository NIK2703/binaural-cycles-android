package com.binauralcycles.viewmodel.state

import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние редактирования пресета.
 * Используется для сохранения состояния при навигации между экранами.
 */
data class EditingState(
    val editingPresetId: String? = null,
    val editingPresetName: String = "",
    val editingFrequencyCurve: FrequencyCurve? = null,
    val editingRelaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    val isActivePreset: Boolean = false
)

/**
 * Repository для хранения состояния редактирования пресета.
 * Singleton, переживающий навигацию между экранами.
 * 
 * Используется для корректной работы SharedTransition анимации:
 * состояние editingFrequencyCurve должно сохраняться при возврате на список пресетов,
 * чтобы анимация могла найти соответствующий элемент.
 */
@Singleton
class EditingStateRepository @Inject constructor() {
    
    private val _editingState = MutableStateFlow(EditingState())
    val editingState: StateFlow<EditingState> = _editingState.asStateFlow()
    
    /**
     * Установить состояние редактирования
     */
    fun setEditingState(state: EditingState) {
        _editingState.value = state
    }
    
    /**
     * Получить текущее состояние редактирования
     */
    fun getEditingState(): EditingState = _editingState.value
    
    /**
     * Очистить состояние редактирования
     */
    fun clearEditingState() {
        _editingState.value = EditingState()
    }
    
    /**
     * Завершить редактирование без очистки состояния (для плавной анимации)
     */
    fun finishEditingWithoutClear() {
        // Ничего не делаем - состояние очистится при следующем редактировании
        // через setEditingState в startEditingPreset/startNewPreset
    }
}